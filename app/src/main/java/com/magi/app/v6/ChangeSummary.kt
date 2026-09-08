package com.magi.app.v6

import com.magi.app.model.MagiState

/** 最適化・自動修正の前後比較（完了表示用）: 変更した職員数・セル数、希望固定の充足、個人回数が全員範囲内か。 */
data class ChangeSummary(
    val changedStaff: Int, val changedCells: Int,
    val wishKept: Int, val wishTotal: Int,
    val rangeAllOk: Boolean,
) {
    /** 完了カード 1 行。例: 「変更 4人・7セル／希望 42/42／個人回数 全員範囲内」 */
    fun line(): String = "変更 ${changedStaff}人・${changedCells}セル／希望 $wishKept/$wishTotal／個人回数 " +
        (if (rangeAllOk) "全員範囲内" else "範囲外あり")

    companion object {
        fun of(state: MagiState, before: Array<IntArray>, after: Array<IntArray>, report: ViolationReport): ChangeSummary {
            val p = Problem(state)
            var staff = 0; var cells = 0
            for (i in 0 until p.S) {
                var c = 0
                for (j in 0 until p.T) if (before.getOrNull(i)?.getOrNull(j) != after.getOrNull(i)?.getOrNull(j)) c++
                if (c > 0) { staff++; cells += c }
            }
            var wishTotal = 0; var wishKept = 0
            for (i in 0 until p.S) for (j in 0 until p.T) if (p.wishLocked(i, j)) { wishTotal++; if (after.getOrNull(i)?.getOrNull(j) == p.wish[i][j]) wishKept++ }
            val rangeOk = (report.breakdown["low"] ?: 0) == 0 && (report.breakdown["high"] ?: 0) == 0
            return ChangeSummary(staff, cells, wishKept, wishTotal, rangeOk)
        }
    }
}
