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

    /** [3.650.0/外部レビュー] 入力途中の選択は対象を名前で追い、対象や期間が変わったら持ち越さない。 */
    @Test fun inputSelectionFollowsTheStaffAndDropsDaysWhenTheTargetOrPeriodChanges() {
        val p = editPeriod("2026-10-01", 31)
        val names = listOf("甲", "乙", "丙")
        val pick = pickDays(pickAt(EditPick(), names, p, 2), names, p, setOf(3, 4))
        assertEquals(EditPick(2, "丙", setOf(3, 4), p, names.hashCode()), pick)
        val moved = listOf("丙", "甲", "乙")
        assertEquals(EditPick(0, "丙", setOf(3, 4), p, moved.hashCode()), resolvePick(pick, moved, p))       // 並び替え: 名前で追う
        assertEquals(EditPick(0, "甲", emptySet(), p, listOf("甲", "乙").hashCode()), resolvePick(pick, listOf("甲", "乙"), p))   // 削除: 先頭へ戻して日を消す
        val p11 = editPeriod("2026-11-01", 30)
        assertEquals(EditPick(2, "丙", emptySet(), p11, names.hashCode()), resolvePick(pick, names, p11))   // 月の移動: 日を消す
        assertEquals(EditPick(0, "甲", emptySet(), p, names.hashCode()), resolvePick(EditPick(), names, p))   // まだ選んでいない
        assertEquals(EditPick(period = p), resolvePick(pick, emptyList(), p))
    }

    @Test fun sameNamedStaffAreFollowedOnlyWhileTheRosterIsUnchanged() {
        val p = editPeriod("2026-10-01", 31)
        val names = listOf("佐藤", "佐藤", "鈴木")
        val pick = pickDays(pickAt(EditPick(), names, p, 1), names, p, setOf(5))
        assertEquals(1, resolvePick(pick, names, p).index)
        assertEquals(setOf(5), resolvePick(pick, names, p).days)
        val reordered = listOf("鈴木", "佐藤", "佐藤")
        assertEquals("同じ名前で並びが変わったら区別できない＝先頭へ戻して日を消す",
            EditPick(0, "鈴木", emptySet(), p, reordered.hashCode()), resolvePick(pick, reordered, p))
    }
}
