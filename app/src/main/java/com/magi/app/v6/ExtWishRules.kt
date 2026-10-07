package com.magi.app.v6

import com.magi.app.model.ExtWish
import com.magi.app.model.MagiState
import java.time.LocalDate

/**
 * 拡張希望（基本希望の否定形）の保存規則・禁止表・違反判定。正は `docs/business-logic.md` の「拡張希望」。
 * 採点の族・重みには入れない（重み未指示）。違反は `ViolationReport.extWishCells` に別件数で出す。
 */
object ExtWishRules {

    /** 保存・前処理の結果。[saved]＝残った件（null＝件ごと保存しない）、[notices]＝案内。 */
    data class Saved(val saved: ExtWish?, val notices: List<String>)

    const val MSG_WISH_DAY = "希望のある日は、拡張希望に入れられない"
    const val MSG_EXT_DAY = "この日は拡張希望の指定日なので、希望は入れられない"

    private fun dayIndex(state: MagiState, iso: String): Int? = runCatching {
        val d = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(state.startDate), LocalDate.parse(iso.trim())).toInt()
        d.takeIf { it in 0 until state.dayCount }
    }.getOrNull()

    private fun dateOf(state: MagiState, j: Int): String = LocalDate.parse(state.startDate).plusDays(j.toLong()).toString()

    private fun wishDays(state: MagiState, i: Int): Set<Int> =
        state.wishes.keys.mapNotNull { k -> k.split(",").takeIf { it.size == 2 && it[0].toIntOrNull() == i }?.get(1)?.toIntOrNull() }.toSet()

    /** 第 5 節: 期間外の日・存在しない記号・希望シフト日を落とし、空や置けるシフトが残らない件、同じ件の重複を拒む。 */
    fun sanitize(state: MagiState, input: ExtWish, existing: List<ExtWish> = state.extWishes): Saved {
        val notes = ArrayList<String>()
        val i = input.staff
        if (i !in state.staff.indices) return Saved(null, listOf("存在しない職員の拡張希望は保存しない"))
        val days = LinkedHashSet<Int>()
        for (d in input.days) { val j = dayIndex(state, d); if (j == null) notes.add("期間外の日付 $d を外した") else days.add(j) }
        val kigou = state.shifts.map { it.kigou }
        val shifts = LinkedHashSet<String>()
        for (s in input.shifts) { if (s in kigou) shifts.add(s) else notes.add("存在しないシフト記号 $s を外した") }
        val wd = wishDays(state, i)
        if (days.any { it in wd }) { days.removeAll(wd); notes.add(MSG_WISH_DAY) }
        if (days.isEmpty() || shifts.isEmpty()) return Saved(null, notes + "日か禁止シフトが残らないので保存しない")
        val p = cachedProblem(state)
        val banned = shifts.map { kigou.indexOf(it) }.toSet()
        if ((0 until p.K).none { it !in banned && p.mayPlace(i, it) }) return Saved(null, notes + "置けるシフトが残らないので保存しない")
        val out = ExtWish(i, days.sorted().map { dateOf(state, it) }, kigou.filter { it in shifts })
        if (existing.any { it.staff == i && it.days.toSet() == out.days.toSet() && it.shifts.toSet() == out.shifts.toSet() }) {
            return Saved(null, notes + "同じ日と同じ禁止シフトの拡張希望が既にある")
        }
        return Saved(out, notes)
    }

    /** 第 4 節: 基本希望を (i, j) に保存してよいか。だめなら案内を返す。 */
    fun wishBlockedBy(state: MagiState, i: Int, j: Int): String? =
        if (state.extWishes.any { e -> e.staff == i && e.days.any { dayIndex(state, it) == j } }) MSG_EXT_DAY else null

    /**
     * 第 6 節: 禁止表。[flat] の [(i*T+j)*K+k] が true＝セル (i,j) に k を置くと違反。割当は見ない。
     * 希望シフト日は空。拡張希望が 1 つも効かなければ [flat]＝null（判定を無料にする）。
     * 読み込みデータで希望の日と重なっていた (i,j) は [overlaps] に入れて案内に出す。
     */
    class BanTable(val S: Int, val T: Int, val K: Int, val flat: BooleanArray?, val overlaps: List<Pair<Int, Int>>) {
        val isEmpty: Boolean get() = flat == null
        fun banned(i: Int, j: Int, k: Int): Boolean {
            val f = flat ?: return false
            if (i < 0 || i >= S || j < 0 || j >= T || k < 0 || k >= K) return false
            return f[(i * T + j) * K + k]
        }
    }

    fun banTable(state: MagiState, S: Int, T: Int, K: Int): BanTable {
        if (state.extWishes.isEmpty()) return BanTable(S, T, K, null, emptyList())
        var flat: BooleanArray? = null
        val overlaps = ArrayList<Pair<Int, Int>>()
        val kigou = state.shifts.map { it.kigou }
        for (e in state.extWishes) {
            val i = e.staff
            if (i !in 0 until S) continue
            val ks = e.shifts.map { kigou.indexOf(it) }.filter { it in 0 until K }
            if (ks.isEmpty()) continue
            val wd = wishDays(state, i)
            for (d in e.days) {
                val j = dayIndex(state, d) ?: continue
                if (j !in 0 until T) continue
                if (j in wd) { overlaps.add(i to j); continue }
                val f = flat ?: BooleanArray(S * T * K).also { flat = it }
                for (k in ks) f[(i * T + j) * K + k] = true
            }
        }
        return BanTable(S, T, K, flat, overlaps.distinct())
    }

    /** 表示用: セル "i,j" → その日に禁止のシフト index（希望シフト日は空＝入らない）。拡張希望が無ければ空。 */
    fun bannedByCell(state: MagiState): Map<String, Set<Int>> {
        if (state.extWishes.isEmpty()) return emptyMap()
        val t = banTable(state, state.staffCount, state.dayCount, state.shiftCount)
        val f = t.flat ?: return emptyMap()
        val out = HashMap<String, Set<Int>>()
        for (i in 0 until t.S) for (j in 0 until t.T) {
            val ks = (0 until t.K).filter { f[(i * t.T + j) * t.K + it] }
            if (ks.isNotEmpty()) out["$i,$j"] = ks.toSet()
        }
        return out
    }

    /** 第 7 節: 違反セル（"i,j"）。未割当は数えない。 */
    fun violations(table: BanTable, schedule: Array<IntArray>, K: Int): List<String> {
        if (table.isEmpty) return emptyList()
        val out = ArrayList<String>()
        for (i in schedule.indices) for (j in schedule[i].indices) {
            val k = schedule[i][j]
            if (k in 0 until K && table.banned(i, j, k)) out.add("$i,$j")
        }
        return out
    }

    /** 第 8 節: 1 セルを [old]→[new] に変えたときのこの件数の差。 */
    fun delta(table: BanTable, i: Int, j: Int, old: Int, new: Int): Int =
        (if (table.banned(i, j, new)) 1 else 0) - (if (table.banned(i, j, old)) 1 else 0)
}
