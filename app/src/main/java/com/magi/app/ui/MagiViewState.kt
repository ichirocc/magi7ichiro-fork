package com.magi.app.ui

import com.magi.app.v6.MirrorKeys

/** 違反マップのキーの符号化。文字列なのは保存データ互換のため。組立と分解はここだけ＝
 *  各所で `split(",")` を書くと、どちらの添字が日かを取り違えても誰も気づけない。 */
internal object VioKey {
    /** 職員×日。 */
    fun cell(staff: Int, day: Int) = "$staff,$day"
    /** 職員×シフト。 */
    fun count(staff: Int, shift: Int) = "$staff,$shift"
    /** シフト×日。 */
    fun need(shift: Int, day: Int) = "$shift,$day"

    fun first(key: String): Int? = key.substringBefore(",").toIntOrNull()
    fun second(key: String): Int? = key.substringAfter(",").toIntOrNull()

    /** セルキー・被覆キーのどちらでも「日」は後ろ側。回数キー("i,k")には日が無いので渡さないこと。 */
    fun dayOf(key: String): Int? = second(key)
}

/** 違反クラスが必須(HARD)か。**族名の完全一致**で決める＝唯一の判定規則
 *  （部分文字列一致だと `covUx` のような族が将来入ったとき静かに誤判定する）。 */
internal fun isHardCellViolation(v: String?): Boolean =
    v != null && familyOfVioClass(v) in MirrorKeys.hard

/** 破線枠にする「重いソフト族」。重み階層（low=120 / c1=50 / c3mn=90 が high=25 を上回る）と表示強度を揃える。 */
internal val heavySoftFamilies = setOf("low", "c1", "c3mn")

internal fun isHeavySoftCellViolation(v: String?): Boolean =
    v != null && familyOfVioClass(v) in heavySoftFamilies

/** セル("i,j")の全違反クラス（重み降順）。families 未充填の経路では最重1クラスへフォールバック。 */
internal fun cellVioClasses(ui: UiState, key: String): List<String> =
    ui.violationCellFamilies[key] ?: listOfNotNull(ui.violationCells[key])

/** フィルタを通過する最重の違反クラス。最重1クラスだけで判定すると、最重族のバケツを OFF にしたときに
 *  表示中の族が同セルに残っていても枠ごと消える。 */
internal fun visibleCellVio(ui: UiState, key: String, enabled: Set<String>): String? =
    cellVioClasses(ui, key).firstOrNull { vioVisible(it, enabled) }

/** 各バケットの「違反ロケーション数」(=箇所数、見出し『要確認 N件』と同単位)。 */
internal fun vioBucketLocCounts(ui: UiState): Map<String, Int> {
    val out = HashMap<String, Int>()
    fun tally(cls: String) { bucketOfFamily(familyOfVioClass(cls))?.let { out[it] = (out[it] ?: 0) + 1 } }
    ui.violationCells.keys.forEach { key ->
        cellVioClasses(ui, key).mapNotNull { bucketOfFamily(familyOfVioClass(it)) }.toSet()
            .forEach { b -> out[b] = (out[b] ?: 0) + 1 }
    }
    ui.needViolations.values.forEach(::tally)
    ui.countViolations.values.forEach(::tally)
    return out
}

/** 被覆キー(k,j)に重なる違反クラス全部（フィルタ通過分）。`needViolations` は**最重1クラスだけ**なので、
 *  これを使わないと同じ重みの族に隠れた covO/covU を取りこぼす（実測: history 3.559.0）。 */
internal fun visibleNeedClasses(ui: UiState, shift: Int, day: Int, enabled: Set<String>): List<String> {
    val key = VioKey.need(shift, day)
    return (ui.needFamilies[key] ?: listOfNotNull(ui.needViolations[key])).filter { vioVisible(it, enabled) }
}

/** そのシフト×日の人員の状態を1語で。不足(HARD)を過剰より優先。どちらも無ければ null。 */
internal fun coverageVioAt(ui: UiState, shift: Int, day: Int, enabled: Set<String>): String? {
    val fams = visibleNeedClasses(ui, shift, day, enabled).map { familyOfVioClass(it) }
    return when { "covU" in fams -> "vio-covU"; "covO" in fams -> "vio-covO"; else -> null }
}

/** 日ヘッダの下線と同じ段階。必須が1つでもあれば実線、無くて要調整があれば破線、どちらも無ければ何も引かない。 */
internal enum class DayMark { None, Soft, Hard }

/** ある日の人員の状態＝**「不足/過剰があるか」を答える唯一の場所**。有無はチェッカー（source of
 *  truth）から、人数は診断から取り、両者が食い違わないことを `MagiViewStateTest` が固定する。 */
internal data class DayStaffing(
    val day: Int,
    val shortage: Int,
    val surplus: Int,
    val hasShortage: Boolean,
    val hasSurplus: Boolean,
    val hardVioCount: Int,
    val softVioCount: Int,
) {
    val mark: DayMark get() = when {
        hardVioCount > 0 -> DayMark.Hard
        softVioCount > 0 -> DayMark.Soft
        else -> DayMark.None
    }
}

/** 盤面から数えるだけの集計。旧実装は職員×シフトの回数を 5 箇所、日×シフトの人数を 3 箇所で個別に数えていた。 */
internal class ScheduleCounts(schedule: List<List<Int>>, staffCount: Int, dayCount: Int, shiftCount: Int) {
    /** perStaff[i][k] = 職員 i がシフト k を担当した回数。 */
    val perStaff: Array<IntArray> = Array(staffCount) { IntArray(shiftCount) }
    /** perDay[j][k] = 日 j にシフト k へ配置された人数。 */
    val perDay: Array<IntArray> = Array(dayCount) { IntArray(shiftCount) }

    init {
        for (i in 0 until staffCount) {
            val row = schedule.getOrNull(i) ?: continue
            for (j in 0 until dayCount) {
                val k = row.getOrNull(j) ?: continue
                if (k in 0 until shiftCount) { perStaff[i][k]++; perDay[j][k]++ }
            }
        }
    }

    fun staffTotal(shift: Int): Int = perStaff.sumOf { it.getOrElse(shift) { 0 } }
}

/** 画面が描くのに要る派生値を一度だけ算出して配る。各 Composable が自前で `remember{…}` すると
 *  同じ値の別々の版が並び、片方だけ壊れても誰も気づけない（3.557.0）。 */
internal class MagiViewState(val ui: UiState, val vioEnabled: Set<String> = allVioBucketKeys) {
    val counts = ScheduleCounts(ui.schedule, ui.staff, ui.days, ui.shifts)

    /** cellVio[i][j] = そのセルで表示する最重の違反クラス（フィルタ通過後。無ければ null）。 */
    val cellVio: Array<Array<String?>> = Array(ui.staff) { i ->
        Array(ui.days) { j -> visibleCellVio(ui, VioKey.cell(i, j), vioEnabled) }
    }

    val days: List<DayStaffing> = buildDays()

    fun coverageAt(shift: Int, day: Int): String? = coverageVioAt(ui, shift, day, vioEnabled)

    /** 違反ナビ（＜前/次＞）が巡回する日。日ヘッダの印が付く日と同じ集合であること。 */
    val violationDays: List<Int> get() = days.filter { it.mark != DayMark.None }.map { it.day }

    private fun buildDays(): List<DayStaffing> {
        val hard = IntArray(ui.days); val soft = IntArray(ui.days)
        val short = BooleanArray(ui.days); val over = BooleanArray(ui.days)
        val keys = if (ui.needFamilies.isNotEmpty()) ui.needFamilies.keys else ui.needViolations.keys
        for (key in keys) {
            val d = VioKey.dayOf(key) ?: continue
            if (d !in 0 until ui.days) continue
            val classes = (ui.needFamilies[key] ?: listOfNotNull(ui.needViolations[key]))
                .filter { vioVisible(it, vioEnabled) }
            if (classes.isEmpty()) continue
            // 下線の段階は最重クラスで決める（重い族が実線を要求すれば実線）。
            if (classes.any { isHardCellViolation(it) }) hard[d]++ else soft[d]++
            for (cls in classes) when (familyOfVioClass(cls)) {
                "covU" -> short[d] = true
                "covO" -> over[d] = true
            }
        }
        for (i in 0 until ui.staff) for (j in 0 until ui.days) {
            val v = cellVio[i][j] ?: continue
            if (isHardCellViolation(v)) hard[j]++ else soft[j]++
        }
        return (0 until ui.days).map { d ->
            val risk = ui.v6?.dayRisks?.getOrNull(d)
            DayStaffing(
                day = d,
                shortage = risk?.shortage ?: 0,
                surplus = risk?.surplus ?: 0,
                hasShortage = short[d],
                hasSurplus = over[d],
                hardVioCount = hard[d],
                softVioCount = soft[d],
            )
        }
    }
}
