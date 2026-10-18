package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [3.721.1] 問題一覧の選んだ行は一覧の作り直しで行番号がずれても同じ族に留まる。
 */
class Review3721_1Test {
    private val before = listOf(
        ProblemRow("c3n", 0, listOf(2), "a", true),
        ProblemRow("covU", 0, listOf(2), "b", true),
    )

    @Test fun rebuiltListShiftingIndexStaysOnSameFamily() {
        val after = listOf(
            ProblemRow("covU", 0, listOf(2), "b", true),
            ProblemRow("c3n", 0, listOf(2), "a", true),
        )
        assertEquals(1, problemPickedIndex(before, 1, 0 to 2, "covU"))
        assertEquals(0, problemPickedIndex(after, 1, 0 to 2, "covU"))
    }

    @Test fun familyGoneFallsBackToFirstRowOfCell() {
        val after = listOf(
            ProblemRow("c1", 1, listOf(4), "c", false),
            ProblemRow("c3n", 0, listOf(2), "a", true),
        )
        assertEquals(1, problemPickedIndex(after, 1, 0 to 2, "covU"))
        assertEquals(1, problemPickedIndex(after, 0, 0 to 2, "covU"))
    }

    @Test fun nullFamilyKeepsOldBehavior() {
        assertEquals(1, problemPickedIndex(before, 1, 0 to 2, null))
        assertEquals(0, problemPickedIndex(before, null, 0 to 2, null))
    }
}
