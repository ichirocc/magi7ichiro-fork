package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarTourLabelTest {
    @Test fun covUIsListedInPeopleNotAddedToPlaces() {
        // 必須 4 = c3w 1 か所 + 人員不足 3 人。札がか所だけだと隣の「必須違反 4」と合わない（2026-10-25 実機）。
        assertEquals("必須違反1か所・人員不足3人を見る", topBarTourLabel(1, 3))
        assertEquals("必須違反2か所を見る", topBarTourLabel(2, 0))
        assertEquals("必須違反2か所を見る", topBarTourLabel(2))
    }
}
