package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.600.0/backlog#34] `rsiPlusPhaseBudgets`（`roleBudgetFit` ON のときの RSI+ 位相配分）の契約。
 * 旧実装（既定・OFF側）は各位相 `max(10, 比率)` の下限があり、量子が小さいと合計が量子を超えた。
 * ON 側は「合計＝予算ちょうど」を保ちつつ、予算が下限を賄えるときだけ従来の床を維持する。
 */
class RsiPlusPhaseBudgetTest {

    @Test
    fun sumAlwaysEqualsBudgetAndStaysNonNegative() {
        for (b in 0..120) {
            val p = V6NativeOptimizer.rsiPlusPhaseBudgets(b)
            assertEquals(4, p.size)
            assertEquals("budget=$b", b, p.sum())
            assertTrue("budget=$b phases=${p.toList()}", p.all { it >= 0 })
        }
    }

    @Test
    fun shortBudgetStillTriesEveryPhase() {
        // ユーザー決定（2026-09-18）: 短時間でも各段階を試す＝一部へ集中させない。
        val p = V6NativeOptimizer.rsiPlusPhaseBudgets(8)
        assertEquals(8, p.sum())
        assertTrue("phases=${p.toList()}", p.all { it >= 1 })
    }

    @Test
    fun largeBudgetKeepsLegacyFloors() {
        val p = V6NativeOptimizer.rsiPlusPhaseBudgets(100)
        assertEquals(100, p.sum())
        assertTrue("phases=${p.toList()}", p[0] >= 10 && p[1] >= 10 && p[2] >= 10 && p[3] >= 5)
    }
}
