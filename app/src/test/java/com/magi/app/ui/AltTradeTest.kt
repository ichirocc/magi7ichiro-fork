package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** シーソーの S4（docs/seesaw.md §6.4）: 他の案を族の件数で比べた種類と、交換の説明の文。 */
class AltTradeTest {
    private val cur = mapOf("c1" to 5, "high" to 2, "weekly" to 10)

    @Test
    fun kinds() {
        assertEquals(AltTrade.TRADE, altTrade(cur, cur + ("c1" to 3) + ("high" to 3)))
        assertEquals(AltTrade.DOMINATES, altTrade(cur, cur + ("weekly" to 8)))
        assertEquals(AltTrade.DOMINATED, altTrade(cur, cur + ("high" to 4)))
        assertEquals(AltTrade.SAME, altTrade(cur, cur))
    }

    @Test
    fun tradeLineSaysWhatImprovesFirstInUnits() {
        assertEquals("期間の制約が 2件減り、上限超過が 1回増えます", altTradeLine(cur, cur + ("c1" to 3) + ("high" to 3)))
        assertEquals("どの項目も今と同じか少なく、曜日の偏りが 2pt減ります", altTradeLine(cur, cur + ("weekly" to 8)))
        assertEquals("どの項目も今と同じか多い案です", altTradeLine(cur, cur + ("high" to 4)))
        assertNull(altTradeLine(cur, cur))
    }

    @Test
    fun manyFamiliesAreCappedAtTwo() {
        val alt = mapOf("c1" to 2, "high" to 1, "weekly" to 9, "c3" to 1, "c3m" to 1, "low" to 1)
        assertEquals("期間の制約が 3件・上限超過が 1回（ほか 1 項目）減り、守るとよい並びが 1件・推奨の並びが 1件（ほか 1 項目）増えます", altTradeLine(cur, alt))
    }

    @Test
    fun summaryKeepsOldTextWithoutTrade() {
        assertEquals("案1：必須違反 1件（今と同じ）", alternativeSummary(1, "1件", 1, 1, worse = false, better = false))
        assertEquals("案1：必須違反 1件（今と同じ）。期間の制約が 2件減り、上限超過が 1回増えます",
            alternativeSummary(1, "1件", 1, 1, worse = false, better = false, trade = "期間の制約が 2件減り、上限超過が 1回増えます"))
    }

    /** カードの 1 案ぶん（docs/screen_spec.md §01「他の案」）: 札・減る・増える。 */
    @Test
    fun cardLineHasTagDownAndUp() {
        assertEquals(AltLine("一長一短", "減る：期間の制約 2件", "増える：上限超過 1回"), altLine(0, cur, 0, cur + ("c1" to 3) + ("high" to 3)))
        assertEquals(AltLine("今より良い", "減る：曜日の偏り 2pt", null), altLine(0, cur, 0, cur + ("weekly" to 8)))
        assertEquals(AltLine("今より悪い", null, "増える：上限超過 2回"), altLine(0, cur, 0, cur + ("high" to 4)))
        assertEquals(AltLine("今と同じ", null, null), altLine(0, cur, 0, cur))
        assertEquals("必須が少ない", altLine(2, cur, 1, cur).tag)
        assertEquals("必須が多い", altLine(1, cur, 2, cur).tag)
    }
}
