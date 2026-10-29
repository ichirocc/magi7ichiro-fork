package com.magi.app.ui

import com.magi.app.v6.ViolationKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-29 画面案: ホームの「次にやること」・問題一覧の 2 段の行・ホームの区画。 */
class NextItemsTest {
    private val items = listOf(
        TourItem("c3n", 0, listOf(7, 8), "禁止の並び Dﾃ→A4", listOf("Dﾃ", "A4"), "10/8"),
        TourItem("c3w", 1, listOf(3), "希望の前日", listOf("Dﾃ"), "10/4"),
        TourItem("pref", 2, listOf(5), "希望と違う勤務", listOf("A4"), "10/6"),
        TourItem("c3n", 3, listOf(1, 2), "禁止の並び", listOf("Dﾃ", "A4"), "10/2"),
    )
    private val names = listOf("アリフ", "古泉", "福澤", "大島")
    private val rows = hardViolationRows(items, names, setOf("1,3"))

    @Test fun topThreeWithTagsAndWishOnlyLast() {
        val fixable = setOf(ViolationKey("c3n", ViolationKey.Space.CELL, 0, 8))
        val next = homeNextItems(rows, items, fixable)
        assertEquals(listOf(1, 2, 3), next.map { it.rank })
        assertEquals(listOf("アリフ", "福澤", "大島"), next.map { it.name })   // 希望のまま（古泉）は後ろ＝上位 3 件に入らない
        assertEquals(listOf(NEXT_TAG_FIXABLE, NEXT_TAG_CHECK, NEXT_TAG_CHECK), next.map { it.tag })
        assertEquals("Dﾃ→A4の並び", next[0].reason)
        assertEquals(0 to 7, next[0].cell)
    }

    @Test fun noTagAboutFixesBeforeSearching() {
        val next = homeNextItems(rows, items, null, max = 4)
        assertEquals(listOf(null, null, null, WISH_ONLY_TAG), next.map { it.tag })
    }

    @Test fun rowPartsSplitTheNameFromTheReason() {
        assertEquals("福澤" to "10/4 希望の前日", problemRowParts("福澤 10/4 希望の前日", "福澤"))
        assertEquals(null to "10/10のAｱが1人不足", problemRowParts("10/10のAｱが1人不足", null))
        assertEquals(null to "別の人の行", problemRowParts("別の人の行", "福澤"))
        assertEquals("必須 3件", problemListCount("3件"))
        assertEquals("要調整 (12)", softFoldLabel(12))
    }

    @Test fun nextItemsSectionSitsBetweenStatusAndPrimaryOnlyWithHard() {
        val plan = homePlan(false, true, false)
        val s = homeCardSections(plan, UiState(loaded = true, hasResult = true, bestHard = 3L, checkRev = 1L))
        assertTrue(s.indexOf(HomeSection.STATUS) < s.indexOf(HomeSection.NEXT_ITEMS))
        assertTrue(s.indexOf(HomeSection.NEXT_ITEMS) < s.indexOf(HomeSection.PRIMARY))
        assertFalse(HomeSection.NEXT_ITEMS in homeCardSections(homePlan(false, true, true), UiState(loaded = true, hasResult = true, bestHard = 0L, checkRev = 1L)))
        assertFalse(HomeSection.NEXT_ITEMS in homeCardSections(plan, UiState(loaded = true, hasResult = true, bestHard = 3L, checkRev = 0L)))
    }
}
