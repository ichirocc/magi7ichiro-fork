package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlin.math.abs

/** 最適化・自動修正の前後比較（完了表示用）: 変更した職員数・セル数、希望固定の充足、個人回数が全員範囲内か、族別の増減。 */
data class ChangeSummary(
    val changedStaff: Int, val changedCells: Int,
    val wishKept: Int, val wishTotal: Int,
    val rangeAllOk: Boolean,
    /** 族別の件数増減（後 − 前。0 の族は含まない）。 */
    val familyDeltas: Map<String, Int> = emptyMap(),
    /** 回数の下限・上限を外れた職員の人数（0＝数えられなかったか範囲内）。 */
    val rangeOutStaff: Int = 0,
) {
    /** 完了カード 1 行目。例: 「変更 4人・7セル／希望どおり 42/42／回数の下限・上限 全員守れています」 */
    fun line(): String = "変更 ${changedStaff}人・${changedCells}セル／希望どおり $wishKept/$wishTotal／回数の下限・上限 " +
        when {
            rangeAllOk -> "全員守れています"
            rangeOutStaff > 0 -> "外れた人 ${rangeOutStaff}人"
            else -> "外れた人あり"
        }

    /** 完了カード 2 行目（族名は [label] で表示語へ）。例: 「改善 回避の並び -2・期間の制約 -1（重み 90）／悪化 曜日の偏り +3（重み 3）」 */
    fun familyLine(label: (String) -> String = { it }): String = familyLine(familyDeltas, label)

    companion object {
        fun of(state: MagiState, before: Array<IntArray>, after: Array<IntArray>, report: ViolationReport, beforeReport: ViolationReport? = null): ChangeSummary {
            val p = cachedProblem(state)
            var staff = 0; var cells = 0
            for (i in 0 until p.S) {
                var c = 0
                for (j in 0 until p.T) if (before.getOrNull(i)?.getOrNull(j) != after.getOrNull(i)?.getOrNull(j)) c++
                if (c > 0) { staff++; cells += c }
            }
            var wishTotal = 0; var wishKept = 0
            for (i in 0 until p.S) for (j in 0 until p.T) if (p.wishFixed(i, j)) { wishTotal++; if (after.getOrNull(i)?.getOrNull(j) == p.wish[i][j]) wishKept++ }
            val rangeOk = (report.breakdown["low"] ?: 0) == 0 && (report.breakdown["high"] ?: 0) == 0
            val rangeOut = report.countFamilies.filterValues { "vio-low" in it || "vio-high" in it }.keys
                .mapTo(HashSet()) { it.substringBefore(',') }.size
            val deltas = familyDeltas(beforeReport ?: UnifiedViolationChecker.check(state, before), report)
            return ChangeSummary(staff, cells, wishKept, wishTotal, rangeOk, deltas, rangeOut)
        }

        fun familyDeltas(before: ViolationReport, after: ViolationReport): Map<String, Int> =
            (before.breakdown.keys + after.breakdown.keys)
                .associateWith { (after.breakdown[it] ?: 0) - (before.breakdown[it] ?: 0) }
                .filterValues { it != 0 }

        /** 改善（減った族）と悪化（増えた族）を重み×増減の大きい順に並べ、それぞれ重み付き合計を添える。 */
        fun familyLine(deltas: Map<String, Int>, label: (String) -> String = { it }): String {
            fun part(title: String, sign: Int): String {
                val items = deltas.entries.filter { it.value * sign > 0 }
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { abs(it.value) * MirrorKeys.weightOf(it.key) }.thenBy { it.key })
                if (items.isEmpty()) return "$title なし"
                val weighted = items.sumOf { abs(it.value) * MirrorKeys.weightOf(it.key) }
                return "$title " + items.joinToString("・") { "${label(it.key)} ${if (it.value < 0) "-" else "+"}${abs(it.value)}" } +
                    "（重み ${weighted.toLong()}）"
            }
            return part("改善", -1) + "／" + part("悪化", +1)
        }
    }
}
