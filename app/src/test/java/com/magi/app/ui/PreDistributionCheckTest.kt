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
        assertEquals("曜日の偏り 20件・期間の制約 4件", rows.getValue("要調整").value)
        assertEquals("10/1〜10/31 の採用した勤務表", rows.getValue("対象").value)
    }

    @Test fun hardRowListsFamiliesAndSaveRowReadsTheCsvLine() {
        val rows = preDistributionRows(base.copy(bestHard = 3L, breakdown = mapOf("covU" to 2, "c3n" to 1)),
            "この内容で勤務表 CSV を保存済みです（10:15）。").associateBy { it.label }
        assertEquals("3件（禁止の並び 1件・人員不足 2件）", rows.getValue("必須違反").value)
        assertEquals(true, rows.getValue("保存").ok)
    }
}
