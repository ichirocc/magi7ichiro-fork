package com.magi.app.ui

import com.magi.app.model.Range
import com.magi.app.v6.FixApplyGate
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.FixKind
import com.magi.app.v6.PreRunCheck
import com.magi.app.v6.Problem
import com.magi.app.v6.SettingFixAction
import com.magi.app.v6.SettingIssue
import com.magi.app.v6.IssueKind
import com.magi.app.v6.StateFingerprint
import com.magi.app.v6.UnifiedViolationChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.686.0 のコードレビュー対応（純関数の部分）。 */
class Review3686Test {
    private val st = FixTiersTest.board12x16()

    @Test fun initBaseKeyChangesOnBoardReplacementOnly() {
        assertEquals(initBaseKeyOf(5L, 0L), initBaseKeyOf(5L, 0L))
        assertNotEquals(initBaseKeyOf(5L, 0L), initBaseKeyOf(5L, 1L))
        assertNotEquals(initBaseKeyOf(5L, 0L), initBaseKeyOf(6L, 0L))
    }

    @Test fun preRunAckReappearsOnlyWhenHandPlacedUpperZeroCellsChange() {
        assertEquals(StateFingerprint.of(st), preRunAckFingerprint(st, emptyList()))
        val c = PreRunCheck.HandPlacedCell(1, 1, FixTiersTest.D)
        assertNotEquals(preRunAckFingerprint(st, emptyList()), preRunAckFingerprint(st, listOf(c)))
        assertEquals(preRunAckFingerprint(st, listOf(c)), preRunAckFingerprint(st, listOf(c.copy())))
        // 上限 0 の D を手で置いた盤面は外れるセルとして数える
        val capped = st.copy(staffRange = st.staffRange + ("1,1" to Range("", "0")))
        assertTrue(PreRunCheck.rerunClears(capped, FixTiersTest.sched(capped)).isNotEmpty())
    }

    @Test fun alternativeSummaryByAdoptionOutcome() {
        assertEquals("案1：必須違反 1件（今と同じ）", alternativeSummary(1, "1件", 1, 1, worse = false, better = false))
        assertEquals("案1：必須違反 1件（今と同じ・全体は今より良い（重みつき））", alternativeSummary(1, "1件", 1, 1, worse = false, better = true))
        assertEquals("案1：必須違反 1件（今と同じ・全体は今より悪い（重みつき））", alternativeSummary(1, "1件", 1, 1, worse = true, better = false))
        assertEquals("案1：必須違反 1件", alternativeSummary(1, "1件", null, 1, worse = false, better = false))
    }

    @Test fun removedWishCountsAsAlreadyRemoved() {
        val issue = SettingIssue(IssueKind.WISH, "x", "p", "f", action = SettingFixAction.REMOVE_WISH, wishKey = "0,0", wishShift = 1)
        val gone = st.copy(wishes = emptyMap())
        assertTrue(SettingFixLogic.wishAlreadyRemoved(gone, issue))
        assertFalse(SettingFixLogic.wishChanged(gone, issue))
        val other = st.copy(wishes = mapOf("0,0" to 0))
        assertTrue(SettingFixLogic.wishChanged(other, issue))
        assertFalse(SettingFixLogic.wishAlreadyRemoved(other, issue))
    }

    @Test fun draftConfirmWithoutCountWhenNotEvaluated() {
        assertEquals("今の勤務表を下書きで置き換えます。元に戻せます", draftConfirmText(null))
        assertEquals("今の勤務表（必須 2件）を下書きで置き換えます。元に戻せます", draftConfirmText("2件"))
    }

    @Test fun progressSummaryUsesUnitsForStartAndSoft() {
        val ui = UiState(bestHard = 2, initHard = 5, breakdown = mapOf("covU" to 2), initBreakdown = mapOf("covU" to 5))
        assertTrue(progressSummary(ui), progressSummary(ui).startsWith("必須違反 残り2人（開始5人）"))
        val noBase = ui.copy(initBreakdown = emptyMap())
        assertTrue(progressSummary(noBase), progressSummary(noBase).startsWith("必須違反 残り2人  "))
        val soft = UiState(bestHard = 0, bestSoft = 3, breakdown = mapOf("high" to 3), initBreakdown = mapOf("high" to 7))
        assertTrue(progressSummary(soft), progressSummary(soft).startsWith("要調整 3回（開始7回）"))
    }

    @Test fun topFixResolvedOnlyWhenBoardAndOpsMatch() {
        val ops = listOf(FixCell(1, 1, 0))
        val sch = listOf(listOf(1, 1))
        val sug = FixSuggestion(FixKind.CHANGE, ops, "x", -1, -1, emptyList())
        val ui = UiState(schedule = sch, fixSuggestions = listOf(sug), topFixResolved = TopFixResolved(ops, sch, listOf("人員不足：A")))
        assertEquals(listOf("人員不足：A"), topFixResolvedLabels(ui))
        assertEquals(emptyList<String>(), topFixResolvedLabels(ui.copy(schedule = listOf(listOf(0, 1)))))
    }

    @Test fun gateOverloadWithPrebuiltProblemGivesSameOutcome() {
        val s = FixTiersTest.sched(st)
        for (op in listOf(FixCell(1, 1, 0), FixCell(0, 1, 0), FixCell(2, 0, 1))) {
            val a = FixApplyGate.apply(st, s, listOf(op))
            val b = FixApplyGate.apply(st, s, listOf(op), Problem(st), UnifiedViolationChecker.check(st, s))
            assertEquals(a.javaClass, b.javaClass)
            assertEquals(a.before.total, b.before.total)
        }
    }

    @Test fun entryMovesSkipShiftsTheOptimizerMayNotPlace() {
        val capped = st.copy(staffRange = st.staffRange + ("1,1" to Range("", "0")))
        val under = FixTarget(1, FixTiersTest.D, null, 3, true, "x")
        assertEquals(emptyList<EntryMove>(), entryMoves(capped, FixTiersTest.sched(capped), under, FixTiersTest.REST))
    }
}
