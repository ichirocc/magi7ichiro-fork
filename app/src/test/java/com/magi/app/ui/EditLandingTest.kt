package com.magi.app.ui

import com.magi.app.v6.ConstraintMus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [3.644.0] つくる前の確認の、セルを持たない行の着地先（UX-02: 推奨に出した対象を遷移先へ引き継ぐ）。 */
class EditLandingTest {
    @Test fun dayNeedLandsOnTheNeedCalendarOfThatShift() {
        val core = listOf(ConstraintMus.DayNeed(day = 11, shift = 2, need = 3), ConstraintMus.RangeCap(0, 2, 4))
        val l = landingForProofCore(core, zeroCap = false)
        assertEquals(EditLanding(0, null, needShift = 2), l)
        assertEquals("必要人数を見直す", landingButtonLabel(l))
    }

    @Test fun zeroCapWinsWhenTheProofIsTaggedZeroCap() {
        val core = listOf(ConstraintMus.DayNeed(11, 2, 3), ConstraintMus.RangeCap(5, 2, 0))
        val l = landingForProofCore(core, zeroCap = true)
        assertEquals(EditLanding(2, "yr_count", countCell = 5 to 2, label = LANDING_ZERO_CAP), l)
        assertEquals(LANDING_ZERO_CAP, landingButtonLabel(l))
    }

    @Test fun rangeAndWindowRulesLandOnCountsAndConstraints() {
        assertEquals(EditLanding(2, "yr_count", countCell = 3 to 1), landingForProofCore(listOf(ConstraintMus.RangeFloor(3, 1, 2)), zeroCap = false))
        assertEquals("回数の下限・上限を見直す", landingButtonLabel(EditLanding(2, "yr_count", countCell = 3 to 1)))
        assertEquals(EditLanding(2, "yr_cons", label = LANDING_WINDOW), landingForProofCore(listOf(ConstraintMus.WindowRule(1, 7, 2)), zeroCap = false))
        assertNull(landingForProofCore(listOf(ConstraintMus.WishPin(0, 1, 1)), zeroCap = false))
    }

    @Test fun existingLabelsAreUnchanged() {
        assertEquals("希望を見直す", landingButtonLabel(EditLanding(0, null, wishStaff = 3)))
        assertEquals("禁止の並びを見直す", landingButtonLabel(EditLanding(2, "yr_cons")))
        assertEquals("担当を見直す", landingButtonLabel(EditLanding(2, "yr_ws1")))
        assertEquals("データを見直す", landingButtonLabel(null))
    }
}
