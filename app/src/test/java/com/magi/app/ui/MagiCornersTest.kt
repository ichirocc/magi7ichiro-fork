package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS 26 の同心の角: カードの中の面の角＝カードの角−余白、シートはカードより丸い。 */
class MagiCornersTest {
    @Test fun innerSurfaceIsConcentricWithTheCard() {
        assertEquals(MagiCorners.INNER_DP, concentricCornerDp(MagiCorners.CARD_DP, MagiCorners.CARD_INSET_DP))
        assertTrue(MagiCorners.SHEET_DP >= MagiCorners.CARD_DP)
    }

    @Test fun tooSmallCornerStopsAtTheMinimum() {
        assertEquals(4, concentricCornerDp(10, 16))
    }
}
