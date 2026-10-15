package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 配布前確認（仕様 §10）: 計算完了・必須違反 0 件・相談・保存を別々の行で示す（UX-09）。 */
class PreDistributionCheckTest {
    private val base = UiState(startDate = "2026-10-01", days = 31, hasResult = true, bestHard = 0L,
        breakdown = mapOf("c1" to 4, "weekly" to 20, "covU" to 0))

    @Test fun zeroHardWithOpenConsultShowsBothStates() {
        val rows = preDistributionRows(base.copy(consults = listOf(ConsultItem(subject = "s", note = "n", staffName = "a"))), null).associateBy { it.label }
        assertEquals(true, rows.getValue("必須違反").ok)
        assertEquals("0件", rows.getValue("必須違反").value)
        assertEquals(false, rows.getValue("相談").ok)   // 必須 0 でも相談の未完了は別に示す
        assertEquals(false, rows.getValue("保存").ok)
        assertEquals("まだ勤務表 CSV に保存していません", rows.getValue("保存").value)
        assertEquals("期間の制約 4件・曜日の偏り 20pt", rows.getValue("要調整").value)   // 重み×数の順（作成の結果カードと同じ）
        assertEquals("対象期間 10/1〜10/31・現在採用している勤務表", rows.getValue("対象").value)
    }

    @Test fun hardRowListsFamiliesAndSaveRowReadsTheCsvLine() {
        val rows = preDistributionRows(base.copy(bestHard = 3L, breakdown = mapOf("covU" to 2, "c3n" to 1)),
            "この内容で勤務表 CSV を保存済みです（10:15）。").associateBy { it.label }
        assertEquals("1件・2人（禁止の並び 1件・人員不足 2人）", rows.getValue("必須違反").value)
        assertEquals(true, rows.getValue("保存").ok)
    }

    /** [3.678.0] 相談の未完了は回答待ちと保留だけ（了承・不可の回答があれば数えない）。 */
    @Test fun consultRowCountsOnlyPendingAndHeld() {
        val items = listOf(ConsultItem("a", "n"), ConsultItem("b", "n", answer = ConsultAnswer.AGREED),
            ConsultItem("c", "n", held = true, answer = ConsultAnswer.AGREED), ConsultItem("d", "n", answer = ConsultAnswer.DECLINED))
        assertEquals("未完了 2件（うち保留 1件）（ホームの「相談中」）", preDistributionRows(base.copy(consults = items), null).associateBy { it.label }.getValue("相談").value)
        val agreed = preDistributionRows(base.copy(consults = listOf(ConsultItem("b", "n", answer = ConsultAnswer.AGREED))), null).associateBy { it.label }.getValue("相談")
        assertEquals(true, agreed.ok)
    }

    /** [3.665.0] 月の境目は評価していないと明示する（前月・翌月の勤務はデータに無い）。注記は窓が月の外へはみ出しうる日を変えたときだけ。 */
    @Test fun monthEdgeIsShownAsNotEvaluated() {
        assertEquals(MONTH_EDGE_NOTE, preDistributionRows(base, null).associateBy { it.label }.getValue("月の境目").value)
        assertEquals(true, monthEdgeTouched(listOf(1), 31, 3))
        assertEquals(true, monthEdgeTouched(listOf(29), 31, 3))
        assertEquals(false, monthEdgeTouched(listOf(2, 28), 31, 3))
        assertEquals(false, monthEdgeTouched(listOf(0), 31, 1))
    }
}
