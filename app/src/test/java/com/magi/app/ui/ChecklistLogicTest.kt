package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [3.643.0] 希望の行は登録の有無だけを数える: 拡張希望だけの職員も「入力あり」、未入力は希望なしと未確認を区別しない。 */
class ChecklistLogicTest {
    @Test fun countsRegularAndExtendedOnlyAndNoInput() {
        val c = wishEntryCounts(5, wishKeys = listOf("0,3", "0,4", "2,1"), extKeys = listOf("2,5", "3,0"))
        assertEquals(WishEntryCounts(entered = 3, extOnly = 1, noInput = 2), c)
        assertEquals("入力あり 3名（拡張希望のみ 1名）・未入力 2名", wishEntryText(c))
        assertEquals("入力あり 0名・未入力 4名", wishEntryText(wishEntryCounts(4, emptyList(), emptyList())))
    }

    @Test fun ignoresKeysOutsideTheStaffRange() {
        assertEquals(WishEntryCounts(1, 0, 1), wishEntryCounts(2, listOf("1,0", "7,0", "x,0"), emptyList()))
    }
}
