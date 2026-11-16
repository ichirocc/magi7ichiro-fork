package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.706.0] 設定タブの組む時間の候補と表記。 */
class Review3706Test {
    /** [3.727.88] タイルは 3・5・7分、ユーザー指定は 2〜10分を 1 分ずつ。既定は 5分。 */
    @Test fun presetsAndCustomRange() {
        assertEquals(listOf(180, 300, 420), BUDGET_PRESET_SECS)
        assertTrue(BUDGET_PRESET_SECS.all { it in MIN_BUDGET_SEC..com.magi.app.v6.MAX_OPTIMIZE_SEC })
        assertEquals(120 to 600, MIN_BUDGET_SEC to com.magi.app.v6.MAX_OPTIMIZE_SEC)
        assertEquals(240, budgetCustomStep(180, 1))
        assertEquals(120, budgetCustomStep(180, -5))
        assertEquals(600, budgetCustomStep(540, 3))
        assertEquals(120, budgetCustomStep(60, 0))
        assertEquals(180, budgetCustomStep(150, 0))
        assertEquals(300, UiState().budgetSec)
    }

    @Test fun budgetTextMatchesPreRunPlanLine() {
        for (sec in listOf(10, 60, 90, 300)) {
            assertTrue(preRunPlanLine("2026-10-01", 31, 12, sec, 20).contains("組む時間 ${budgetText(sec)}（"))
        }
        assertEquals("1分", budgetText(60))
        assertEquals("90秒", budgetText(90))
        assertEquals("組む時間 1分（＋仕上げと玉突き 20秒）", budgetStatusLine(60, 20))
    }
}
