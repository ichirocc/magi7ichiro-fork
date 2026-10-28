package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [UI-S2] 勤務表の絞り込み札は 3 つ（人員・希望・回数）＋「その他」。外している札は閉じていても見せる（絞り込みの状態を隠さない）。 */
class VioBucketsVisibleTest {
    @Test fun threeCommonByDefaultAllWhenMoreIsOpen() {
        assertEquals(listOf("need", "pref", "count"), visibleVioBuckets(false, allVioBucketKeys).map { it.key })
        assertEquals(allVioBucketKeys.size, visibleVioBuckets(true, allVioBucketKeys).size)
    }

    @Test fun aFilteredOutBucketStaysVisibleWhileClosed() {
        assertEquals(listOf("need", "pref", "count", "window"), visibleVioBuckets(false, allVioBucketKeys - "window").map { it.key })
    }
}
