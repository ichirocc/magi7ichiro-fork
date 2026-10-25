package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TourRetargetTest {
    private fun t(staff: Int, day: Int, fam: String = "pref") = TourItem(fam, staff, listOf(day), "h$staff-$day")
    private val a = t(0, 1); private val b = t(1, 2); private val c = t(2, 3); private val d = t(3, 4)

    @Test fun fixedItemVanishesNextIsTheFollowingOne() {
        // [A,B,C,D] で B を見て直す → [A,C,D]。次は C（旧来の tourAt+1 は D に飛んでいた）。
        val p = tourRetarget(listOf(a, b, c, d), TourPos(1, true), listOf(a, c, d))
        assertEquals(TourPos(1, false), p)
        assertEquals(c, listOf(a, c, d)[p.at])   // 次＝at、前＝at−1＝A
    }

    @Test fun currentItemSurvivesKeepsItWhenOthersVanish() {
        assertEquals(TourPos(1, true), tourRetarget(listOf(a, b, c, d), TourPos(2, true), listOf(b, c, d)))
    }

    @Test fun secondFixWithoutMovingStillPointsAtNextSurvivor() {
        // 消えたあと（次＝C）にさらに C も消えた → 次は D。
        assertEquals(TourPos(1, false), tourRetarget(listOf(a, c, d), TourPos(1, false), listOf(a, d)))
    }

    @Test fun lastItemVanishesWrapsToFirst() {
        assertEquals(TourPos(0, false), tourRetarget(listOf(a, b, c), TourPos(2, true), listOf(a, b)))
    }

    @Test fun sameCellDifferentFamilyIsDifferentItem() {
        val b2 = t(1, 2, "groupViol")
        assertEquals(TourPos(2, false), tourRetarget(listOf(a, b, c), TourPos(1, true), listOf(a, b2, c)))
    }

    @Test fun emptyListsAreSafe() {
        assertEquals(TourPos(0, false), tourRetarget(listOf(a), TourPos(0, true), emptyList()))
        assertEquals(TourPos(0, false), tourRetarget(emptyList(), TourPos(3, true), listOf(a)))
    }
}
