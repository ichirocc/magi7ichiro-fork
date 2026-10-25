package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class V6DashboardStatTest {
    @Test fun hardIsSplitByUnitLikeOtherScreens() {
        // 必須 4 = 希望の前日の禁止 1 件 + 人員不足 3 人。足して「4」と言わない（2026-10-25 実機）。
        assertEquals("1件・3人", v6HardStat(mapOf("c3w" to 1, "covU" to 3), 4))
        assertEquals("4", v6HardStat(emptyMap(), 4))
    }
}
