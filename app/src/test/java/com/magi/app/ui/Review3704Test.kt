package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** [3.704.0] 配布前の確認の上段と「詳しく」・作成前の条件の行・確認の点（3 つまで）。 */
class Review3704Test {
    private val base = UiState(startDate = "2026-10-01", days = 31, hasResult = true, bestHard = 0L, v6 = null,
        breakdown = mapOf("c1" to 4, "weekly" to 20))

    @Test fun preDistTopIsHardConsultSaveAndEvaluationOnlyWhenNotEvaluated() {
        val notEvaluated = preDistributionRows(base, null)   // v6 == null は未評価
        assertEquals(listOf("評価", "必須違反", "相談", "保存"), preDistributionTop(notEvaluated).map { it.label })
        assertEquals(listOf("要調整"), preDistributionDetails(notEvaluated).map { it.label })
    }

    @Test fun preDistEvaluatedMovesEvaluationToDetails() {
        val rows = preDistributionRows(base.copy(v6 = com.magi.app.v6.V6PortReport(null, 0, 0, 0, 0, 0, 0, "", 0, emptyList(), emptyList(), 0.0, 0.0, emptyList(), emptyList())), null)
        assertEquals(listOf("必須違反", "相談", "保存"), preDistributionTop(rows).map { it.label })
        assertEquals(listOf("評価", "要調整"), preDistributionDetails(rows).map { it.label })
    }

    @Test fun preRunConditionLineCountsEachSection() {
        assertEquals("登録条件：何度つくっても残る 3件・再作成で外れる 2件・希望が上限を超える 2件／手動固定 5件は保持", preRunConditionLine(3, 2, 2, 5))
        assertEquals("登録条件：再作成で外れる 1件", preRunConditionLine(0, 1, 0, 0))
        assertEquals("登録条件：つくる前に直す項目はありません", preRunConditionLine(0, 0, 0, 0))
    }

    @Test fun preRunPrimaryUsesTheOpeningButtonWord() {
        assertEquals("勤務表をつくる", preRunProceedLabel(false))
        assertEquals("再作成", preRunProceedLabel(true))
    }

    @Test fun confirmPointsAreAtMostThree() {
        assertEquals(listOf("a", "b"), confirmDialogPoints(listOf("a", " ", "b")))
        assertEquals(3, confirmDialogPoints(listOf("a", "b", "c")).size)
        assertThrows(IllegalArgumentException::class.java) { confirmDialogPoints(listOf("a", "b", "c", "d")) }
    }
}
