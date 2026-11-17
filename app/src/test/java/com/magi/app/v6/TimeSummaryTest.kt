package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeSummaryTest {
    private fun summary(over: Boolean = false) = V6FinalPort.TimeSummary(
        totalMs = 438_066L,
        phases = listOf("探索" to 50_017L, "連鎖" to 0L, "解き直し" to 370_016L, "統合" to 1_003L, "後処理" to 8_967L, "追加精製" to 8_062L),
        postTop = listOf("C1玉突き連鎖" to 4_105L, "MembraneCellLns" to 1_730L),
        overBudget = over,
    )

    @Test fun longestPhaseFirstWithPostDetail() {
        assertEquals(
            "所要 438秒: 解き直し 370秒（84%） ・ 探索 50秒（11%） ・ 後処理 9.0秒（2%）（C1玉突き連鎖 4.1秒・MembraneCellLns 1.7秒） ・ 追加精製 8.1秒（1%） ・ 統合 1.0秒（0%）",
            summary().opLine())
    }

    @Test fun overBudgetIsMarked() {
        assertEquals(true, summary(over = true).opLine().startsWith("所要 438秒（予算超過）: "))
    }

    @Test fun allPhasesUnderOneSecond() {
        val s = V6FinalPort.TimeSummary(800L, listOf("探索" to 500L, "後処理" to 300L), emptyList(), false)
        assertEquals("所要 0.8秒: 内訳はどれも1秒未満", s.opLine())
    }
}
