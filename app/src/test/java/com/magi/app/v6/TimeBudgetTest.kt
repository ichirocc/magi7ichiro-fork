package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-8: TimeBudget の各取り分が、移す前の V6FinalPort の式と同じ値（予算 1・30・31・60・210・211・300 秒）。 */
class TimeBudgetTest {
    private val start = 1_000_000L

    @Test fun sharesMatchTheFormerFormulas() {
        for (sec in listOf(1, 30, 31, 60, 210, 211, 300)) {
            val tb = TimeBudget.start(sec) { start }
            // 旧: budgetMs = seconds*1000、postReserveFixedMs = (POST+CHAIN)*1000、hardDeadline = start + budget + reserve
            val budgetMs = sec * 1000L
            val reserve = (POST_RESERVE_SEC + CHAIN_RESERVE_SEC) * 1000L
            assertEquals(start, tb.startMs)
            assertEquals(budgetMs, tb.searchMs)
            assertEquals(reserve, tb.reserveMs)
            assertEquals(start + budgetMs + reserve, tb.hardDeadlineMs)
            val fraction = EngineConfig().normalStallFraction
            assertEquals("予算 ${sec}s の停滞の見張り", V6FinalPort.watchdogBudget(budgetMs, start, start + budgetMs + reserve, fraction, postReserveFixedMs = reserve),
                tb.watchdog(fraction))
        }
    }

    @Test fun overBudgetBoundary() {
        val tb = TimeBudget.start(60) { start }
        assertFalse(tb.overBudget(tb.hardDeadlineMs))      // ちょうど締切は超過ではない（旧: > ）
        assertTrue(tb.overBudget(tb.hardDeadlineMs + 1))
    }

    /** P3b: 各段の取り分が移す前の式と同じ（残り 0・端数・上限に届く/届かない）。 */
    @Test fun budgetSharesMatchTheFormerInlineFormulas() {
        for (rem in listOf(0L, 1L, 7L, 999L, 8_000L, 13_501L, 60_000L, 600_001L)) {
            for (cap in listOf(0L, 3_000L, 6_000L, 25_000L)) {
                assertEquals((rem / 2).coerceAtMost(cap), BudgetShare.half(rem, cap))
                assertEquals(minOf(cap, rem / 2), BudgetShare.half(rem, cap))                  // EjectionChainPipeline の旧式
                assertEquals((rem / 4).coerceIn(0L, cap), BudgetShare.quarter(rem, cap))
                assertEquals(rem.coerceAtMost(cap), BudgetShare.upTo(rem, cap))
            }
            for ((part, total) in listOf(8_000L to 14_000L, 0L to 0L, 5_000L to 5_000L)) {
                val clamp = 30_000L
                val old = if (total <= 0L) 0L else (rem.coerceAtMost(clamp) * part / total).coerceAtMost(part)
                assertEquals(old, BudgetShare.proportional(rem, clamp, part, total))
            }
        }
    }
}
