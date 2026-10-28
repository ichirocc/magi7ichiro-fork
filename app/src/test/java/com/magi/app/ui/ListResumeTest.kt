package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 問題一覧へ戻ったときの行（外部レビュー UI①）。見ていた行が残ればそれ、直して消えれば繰り上がった次の問題。 */
class ListResumeTest {
    private fun row(staff: Int, day: Int, fam: String = "c3n") = ProblemRow(fam, staff, listOf(day), "r$staff-$day", hard = true)

    @Test fun keepsTheRowStillPresent() {
        val rows = listOf(row(0, 1), row(0, 2), row(1, 4))
        assertEquals(1, listResumeIndex(rows, 1, 0 to 2, "c3n"))
    }

    @Test fun fixedRowMovesToTheNextProblem() {
        val after = listOf(row(0, 1), row(1, 4))      // 見ていた (0,2) を直した
        assertEquals(1, listResumeIndex(after, 1, 0 to 2, "c3n"))
        assertEquals(1, listResumeIndex(after, 5, 0 to 2, "c3n"))   // 末尾を越えたら最後の行
    }

    @Test fun nothingWhenNothingWasPickedOrTheListIsEmpty() {
        assertNull(listResumeIndex(listOf(row(0, 1)), null, null))
        assertNull(listResumeIndex(emptyList(), 0, 0 to 1))
        assertEquals(0, listResumeIndex(listOf(row(0, 1)), null, 0 to 1))   // 巡回から戻った（行番号なし）＝そのセルの行
    }
}
