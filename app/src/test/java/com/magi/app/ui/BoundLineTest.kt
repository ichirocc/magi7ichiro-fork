package com.magi.app.ui

import com.magi.app.v6.V6FinalPort.BoundSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundLineTest {
    private fun b(hard: Long, proven: Boolean, gap: Double?) = BoundSummary(hard, proven, 100, 101.0, gap, false)

    @Test fun provenMinimumAndGap() =
        assertEquals("根拠: 必須違反 4 件はこれ以上減らせないと証明済み・最良との差は多くても 1.8%", boundLineOf(b(4, true, 0.018), 4))

    @Test fun smallGapSaysWithinOnePercent() =
        assertEquals("根拠: 必須違反なし・最良との差は 1% 以下", boundLineOf(b(0, true, 0.004), 0))

    @Test fun unprovenOrDifferentHardSaysOnlyGap() {
        assertEquals("根拠: 最良との差は多くても 12.5%", boundLineOf(b(4, false, 0.125), 4))
        assertEquals("根拠: 最良との差は多くても 12.5%", boundLineOf(b(3, true, 0.125), 4))
    }

    @Test fun nothingToSay() {
        assertNull(boundLineOf(null, 4))
        assertNull(boundLineOf(b(3, true, null), 4))
    }
}
