package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.706.0] 設定タブの組む時間の候補と表記。 */
class Review3706Test {
    @Test fun presetsAlwaysIncludeCurrentWithinRangeAscending() {
        for (cur in listOf(10, 45, 60, 90, 300, 5, 999)) {
            val p = budgetPresets(cur)
            assertTrue(cur.coerceIn(10, com.magi.app.v6.MAX_OPTIMIZE_SEC) in p)
            assertTrue(p.all { it in 10..com.magi.app.v6.MAX_OPTIMIZE_SEC })
            assertEquals(p.sorted().distinct(), p)
        }
        assertEquals(listOf(10, 60, 120, 180, 240, 300), budgetPresets(60))
        assertEquals(listOf(10, 60, 90, 120, 180, 240, 300), budgetPresets(90))
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
