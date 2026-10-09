package com.magi.app.ui

import com.magi.app.v6.ConstraintMus
import com.magi.app.v6.CoverageShortfall
import com.magi.app.v6.CoverageVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [3.644.0] つくる前の確認の、セルを持たない行の着地先（UX-02: 推奨に出した対象を遷移先へ引き継ぐ）。 */
class EditLandingTest {
    @Test fun dayNeedLandsOnTheNeedCalendarOfThatShift() {
        val core = listOf(ConstraintMus.DayNeed(day = 11, shift = 2, need = 3), ConstraintMus.RangeCap(0, 2, 4))
        val l = landingForProofCore(core, zeroCap = false)
        assertEquals(EditLanding(0, null, needShift = 2, day = 11), l)   // [3.646.0 L01] 日も運ぶ
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

    /** [3.646.0 L01/L04] 人員不足の枠は日を運ぶ。原因が分からない枠も編集タブを開くだけにせず、その日のそのシフトの必要人数へ。 */
    @Test fun shortageLandingsCarryTheDayAndNeverFallBackToTheBareTab() {
        fun sf(verdict: CoverageVerdict, blockedNow: Boolean = false, forbid: Int = 0, pinned: List<Int> = emptyList()) =
            CoverageShortfall(9, "10/10", 3, "夜", 1, 0, 1, 4, verdict, "r", blockedNow = blockedNow, wishPinned = pinned, forbidCount = forbid)
        assertEquals(EditLanding(0, null, wishStaff = 4, day = 9), landingFor(sf(CoverageVerdict.FIXABLE, pinned = listOf(4))))
        assertEquals(EditLanding(2, "yr_ws1"), landingFor(sf(CoverageVerdict.INFEASIBLE)))
        assertEquals(EditLanding(2, "yr_cons"), landingFor(sf(CoverageVerdict.FIXABLE, blockedNow = true, forbid = 2)))
        assertEquals(EditLanding(0, null, needShift = 3, day = 9), landingFor(sf(CoverageVerdict.FIXABLE)))
        assertEquals("必要人数を見直す", landingButtonLabel(landingFor(sf(CoverageVerdict.FIXABLE))))
    }

    /** [3.646.0 L03] 編集タブの先頭の「元の確認へ戻る」の 1 行。 */
    @Test fun returnLineNamesTheOrigin() {
        assertEquals("「つくる前の確認」から来ました。直したら元の確認へ戻れます。", editReturnLine(EditReturn("つくる前の確認", EditReturn.PRE_RUN)))
        assertEquals(EditReturn.CELL, EditReturn("甲 10/3 のセル", EditReturn.CELL, 0 to 2).origin)
    }
}
