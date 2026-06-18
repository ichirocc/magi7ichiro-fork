package com.magi.app.v6

import com.magi.app.model.MagiState

/** 改善手の種類（UIのチップ表示用）。 */
enum class FixKind { CHANGE, CHANGE_MULTI, SWAP, SWAP_XDAY, SWAP_MULTI }

/** 1セルへの代入（move = これらを盤面にセットする）。 */
data class FixCell(val staff: Int, val day: Int, val toShift: Int)

/**
 * [改善提案] 1手で違反がどれだけ減るかを評価した候補。
 * move は ops（セル代入の集合）で表現し、適用は ops を順にセットするだけ（全種類を統一）。
 *  - CHANGE       : 1マスを別シフトへ
 *  - CHANGE_MULTI : 同一スタッフの2マスを同時変更（下限の競合など、1マスでは直せない不足に有効）
 *  - SWAP         : 同日2人を入れ替え（被覆不変）
 *  - SWAP_XDAY    : 別日どうしを入れ替え（被覆が両日で変化）
 *  - SWAP_MULTI   : 同日3人を巡回交換（2人交換が担当可否で塞がる時の打開）
 */
data class FixSuggestion(
    val kind: FixKind,
    val ops: List<FixCell>,
    val label: String,
    val deltaHard: Int,
    val deltaTotal: Int,
    val diff: List<Pair<String, Int>>,
)

/**
 * 違反を減らす「1手」を列挙する。最適化エンジンと同じ評価（canDo 可否・希望ロック保護・
 * UnifiedViolationChecker による被覆込み (hard,total,weighted) 辞書式改善）。CHANGE / CHANGE_MULTI /
 * SWAP / SWAP_XDAY / SWAP_MULTI を統合し、効果順・同型重複排除で返す。読取専用。
 * 高コストな手（複数マス・別日・3人）は違反箇所にターゲットし、締切（deadlineMs）で打ち切る賢い探索。
 */
object FixSuggester {
    fun suggest(
        state: MagiState,
        schedule: Array<IntArray>,
        focusStaff: Int? = null,
        maxResults: Int = 8,
        deadlineMs: Long = 6000L,
    ): List<FixSuggestion> {
        val p = Problem(state)
        if (p.S < 1 || p.T < 1) return emptyList()
        val s = normalizeSchedule(schedule, p)
        val base = UnifiedViolationChecker.check(state, s)

        fun nm(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
        fun sym(k: Int) = if (k >= 0) (state.shifts.getOrNull(k)?.kigou ?: "$k") else "—"
        fun dlab(j: Int): String = try {
            val d = java.time.LocalDate.parse(state.startDate).plusDays(j.toLong())
            "${d.monthValue}/${d.dayOfMonth}"
        } catch (e: Exception) { "${j + 1}日" }
        fun diffOf(rep: ViolationReport): List<Pair<String, Int>> {
            val out = ArrayList<Pair<String, Int>>()
            for (k in (base.breakdown.keys + rep.breakdown.keys)) {
                val d = (rep.breakdown[k] ?: 0) - (base.breakdown[k] ?: 0)
                if (d != 0) out.add(k to d)
            }
            out.sortBy { it.second }
            return out
        }

        val found = ArrayList<Quad>()
        val start = System.currentTimeMillis()
        var evals = 0
        fun timeUp() = System.currentTimeMillis() - start > deadlineMs

        // ops をその場で適用→評価→復元（割当を作らず高速）。改善なら候補に追加。
        fun tryOps(kind: FixKind, ops: List<FixCell>, label: String) {
            val saved = IntArray(ops.size) { s[ops[it].staff][ops[it].day] }
            for (op in ops) s[op.staff][op.day] = op.toShift
            val rep = UnifiedViolationChecker.check(state, s)
            evals++
            for (idx in ops.indices) s[ops[idx].staff][ops[idx].day] = saved[idx]
            val better = rep.hard < base.hard ||
                (rep.hard == base.hard && rep.total < base.total) ||
                (rep.hard == base.hard && rep.total == base.total && rep.weightedScore < base.weightedScore)
            if (better) {
                found.add(Quad(FixSuggestion(kind, ops, label, rep.hard - base.hard, rep.total - base.total, diffOf(rep)),
                    rep.hard - base.hard, rep.total - base.total, rep.weightedScore - base.weightedScore))
            }
        }

        val focus = focusStaff
        fun inFocus(i: Int) = focus == null || i == focus
        fun pairFocus(i: Int, i2: Int) = focus == null || i == focus || i2 == focus

        // 違反に関与する staff / day / shift をターゲット集合として抽出。
        val countHot = HashSet<Int>()                  // 回数違反(low/high)のあるstaff
        val shortShift = HashMap<Int, MutableSet<Int>>()  // staff -> 下限割れのシフト集合
        for ((key, cls) in base.countViolations) {
            val pp = key.split(","); val i = pp.getOrNull(0)?.toIntOrNull() ?: continue; val k = pp.getOrNull(1)?.toIntOrNull() ?: continue
            countHot.add(i)
            if (cls == "vio-low") shortShift.getOrPut(i) { HashSet() }.add(k)
        }
        val hotCells = ArrayList<Int>()                // pack i*1000+j（セル違反）
        val hotDays = HashSet<Int>()
        for (key in base.violations.keys) {
            val pp = key.split(","); val i = pp.getOrNull(0)?.toIntOrNull() ?: continue; val j = pp.getOrNull(1)?.toIntOrNull() ?: continue
            hotCells.add(i * 1000 + j); hotDays.add(j)
        }
        for (key in base.needViolations.keys) key.split(",").getOrNull(1)?.toIntOrNull()?.let { hotDays.add(it) }

        // ---- Phase 1: 単一マス変更（広く）----
        run {
            for (i in 0 until p.S) {
                if (!inFocus(i)) continue
                val allowed = p.allowedShiftsForStaff(i)
                for (j in 0 until p.T) {
                    if (p.wish[i][j] >= 0) continue
                    val a = s[i][j]
                    for (k in allowed) {
                        if (k == a || timeUp()) continue
                        tryOps(FixKind.CHANGE, listOf(FixCell(i, j, k)), "${nm(i)} ${dlab(j)} 「${sym(a)}」→「${sym(k)}」")
                    }
                }
            }
        }
        // ---- Phase 2: 同日2人交換 ----
        run {
            for (i in 0 until p.S) for (i2 in i + 1 until p.S) {
                if (!pairFocus(i, i2)) continue
                for (j in 0 until p.T) {
                    if (timeUp()) break
                    if (p.wish[i][j] >= 0 || p.wish[i2][j] >= 0) continue
                    val a = s[i][j]; val b = s[i2][j]
                    if (a == b || !p.canDo(i, b) || !p.canDo(i2, a)) continue
                    tryOps(FixKind.SWAP, listOf(FixCell(i, j, b), FixCell(i2, j, a)),
                        "${nm(i)} 「${sym(a)}」 ↔ ${nm(i2)} 「${sym(b)}」（${dlab(j)}）")
                }
            }
        }
        // ---- Phase 3: 複数マス変更（同一スタッフ・下限割れ当事者にターゲット）----
        run {
            val targetStaff = if (focus != null) listOf(focus) else countHot.toList()
            for (i in targetStaff) {
                if (timeUp()) break
                val allowed = p.allowedShiftsForStaff(i)
                // 目標シフト = そのstaffの下限割れシフト ∪ 休(0)。なければ単一マス候補を流用するため全許可。
                val targets: List<Int> = (shortShift[i]?.toList() ?: emptyList()).let { if (it.isEmpty()) allowed.toList() else it + 0 }.distinct()
                val cells = (0 until p.T).filter { p.wish[i][it] < 0 }
                for (a in cells.indices) {
                    if (timeUp()) break
                    for (b in a + 1 until cells.size) {
                        if (timeUp()) break
                        val j1 = cells[a]; val j2 = cells[b]; val s1 = s[i][j1]; val s2 = s[i][j2]
                        for (k1 in targets) {
                            if (k1 == s1) continue
                            for (k2 in targets) {
                                if (k2 == s2 || timeUp()) continue
                                tryOps(FixKind.CHANGE_MULTI, listOf(FixCell(i, j1, k1), FixCell(i, j2, k2)),
                                    "${nm(i)} ${dlab(j1)}「${sym(s1)}」→「${sym(k1)}」＋${dlab(j2)}「${sym(s2)}」→「${sym(k2)}」")
                            }
                        }
                    }
                }
            }
        }
        // ---- Phase 4: 同日3人巡回交換（被覆不変・違反日にターゲット）----
        run {
            val days3 = if (focus != null) (0 until p.T).toList() else hotDays.toList()
            for (j in days3) {
                if (timeUp()) break
                for (a in 0 until p.S) {
                    if (timeUp()) break
                    if (p.wish[a][j] >= 0) continue
                    for (b in 0 until p.S) {
                        if (b == a || p.wish[b][j] >= 0) continue
                        for (c in 0 until p.S) {
                            if (c == a || c == b || p.wish[c][j] >= 0 || timeUp()) continue
                            if (focus != null && a != focus && b != focus && c != focus) continue
                            // 重複列挙を避けるため a を最小に固定
                            if (a > b || a > c) continue
                            val sa = s[a][j]; val sb = s[b][j]; val sc = s[c][j]
                            if (sa == sb && sb == sc) continue
                            // 巡回: a<-sb, b<-sc, c<-sa
                            if (!p.canDo(a, sb) || !p.canDo(b, sc) || !p.canDo(c, sa)) continue
                            tryOps(FixKind.SWAP_MULTI, listOf(FixCell(a, j, sb), FixCell(b, j, sc), FixCell(c, j, sa)),
                                "（3人）${nm(a)}・${nm(b)}・${nm(c)} を ${dlab(j)} で入替")
                        }
                    }
                }
            }
        }
        // ---- Phase 5: 別日交換（違反関与セルを起点）----
        run {
            val anchors = ArrayList<Int>()
            anchors.addAll(hotCells)
            val anchorStaff = if (focus != null) listOf(focus) else countHot.toList()
            for (i in anchorStaff) for (j in 0 until p.T) if (p.wish[i][j] < 0) anchors.add(i * 1000 + j)
            val seenAnchor = HashSet<Int>()
            for (packed in anchors) {
                if (!seenAnchor.add(packed) || timeUp()) continue
                val i1 = packed / 1000; val j1 = packed % 1000
                if (i1 !in 0 until p.S || j1 !in 0 until p.T || p.wish[i1][j1] >= 0) continue
                val a = s[i1][j1]
                for (i2 in 0 until p.S) {
                    if (timeUp()) break
                    if (focus != null && i1 != focus && i2 != focus) continue
                    for (j2 in 0 until p.T) {
                        if (j2 == j1 && i2 == i1) continue
                        if (p.wish[i2][j2] >= 0 || timeUp()) continue
                        val b = s[i2][j2]
                        if (a == b || !p.canDo(i1, b) || !p.canDo(i2, a)) continue
                        val label = if (i1 == i2)
                            "${nm(i1)} ${dlab(j1)}「${sym(a)}」 ↔ ${dlab(j2)}「${sym(b)}」（別日）"
                        else
                            "${nm(i1)} ${dlab(j1)}「${sym(a)}」 ↔ ${nm(i2)} ${dlab(j2)}「${sym(b)}」（別日）"
                        tryOps(FixKind.SWAP_XDAY, listOf(FixCell(i1, j1, b), FixCell(i2, j2, a)), label)
                    }
                }
            }
        }

        // 効果順（必須減 > 合計減 > 重み減）。同型の手は1つに絞り多様性確保。
        found.sortWith(compareBy({ it.dHard }, { it.dTotal }, { it.dWeighted }))
        val seen = HashSet<String>()
        val result = ArrayList<FixSuggestion>()
        for (q in found) {
            val sug = q.sug
            val sig = sug.kind.name + ":" + sug.ops.joinToString("|") { "${it.staff}.${it.toShift}" }
            if (seen.add(sig)) result.add(sug)
            if (result.size >= maxResults) break
        }
        return result
    }

    private class Quad(val sug: FixSuggestion, val dHard: Int, val dTotal: Int, val dWeighted: Double)
}
