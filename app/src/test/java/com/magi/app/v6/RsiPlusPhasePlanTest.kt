package com.magi.app.v6

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RSI++ のフェーズ按分（M-04）。41 秒以上は合計＝予算で、予算の締切を足す経路に入らない＝従来どおり。 */
class RsiPlusPhasePlanTest {
    @Test
    fun planSumsToBudgetFrom41Seconds() {
        for (b in 41..600) assertEquals("budget=$b", b, RsiSearch.rsiPlusPhaseSecs(b).sum())
    }

    @Test
    fun floorsExceedBudgetBelow35Seconds() {
        for (b in 1..34) assertTrue("budget=$b", RsiSearch.rsiPlusPhaseSecs(b).sum() > b)
        for (b in 35..40) assertTrue("budget=$b", RsiSearch.rsiPlusPhaseSecs(b).sum() >= b)
    }

    @Test
    fun defaultBudgetsKeepTheirSplit() {
        assertArrayEquals(intArrayOf(60, 105, 90, 45), RsiSearch.rsiPlusPhaseSecs(300))
        assertArrayEquals(intArrayOf(10, 10, 10, 5), RsiSearch.rsiPlusPhaseSecs(1))
    }
}
