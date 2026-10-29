package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS 26 の同心の角: カードの中の面の角＝カードの角−余白、シートはカードより丸い。 */
class CornerMetricsTest {
    @Test fun innerSurfaceIsConcentricWithTheCard() {
        assertEquals(CornerMetrics.INNER_DP, concentricCornerDp(CornerMetrics.CARD_DP, CornerMetrics.CARD_INSET_DP))
        assertTrue(CornerMetrics.SHEET_DP >= CornerMetrics.CARD_DP)
    }

    @Test fun tooSmallCornerStopsAtTheMinimum() {
        assertEquals(4, concentricCornerDp(10, 16))
    }
}
