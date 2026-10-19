package com.magi.app.ui

import com.magi.app.v6.RelaxTrial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaxApprovalTest {
    private fun result(h0: Int = 3, rr: Int = 1) = RelaxTrial.Result(0, 2, 0..4, emptyList(), listOf(RelaxTrial.Relax(0, 1, 1)), h0, h0, h0, rr,
        listOf(RelaxTrial.Move(0, 2, 0, 1)))
    private fun r1() = RelaxApproval.start(result(), true, "t1", "fp")
    private fun r3() = RelaxApproval.next(RelaxApproval.next(r1(), true), true)

    @Test fun improvingResultOpensReadOnlyR1() {
        val f = r1()
        assertEquals(RelaxApproval.Step.R1, f.step)
        assertEquals(1, f.expectedHard)
    }

    @Test fun staleGoesToR4AndChangesNothing() {
        assertEquals(RelaxApproval.EndReason.STALE, RelaxApproval.start(result(), false, "t", "f").end)
        val f = RelaxApproval.next(r1(), false)
        assertEquals(RelaxApproval.Step.R4, f.step)
        assertEquals(RelaxApproval.EndReason.STALE, f.end)
        assertEquals(RelaxApproval.EndReason.STALE, RelaxApproval.finish(r3(), RelaxApproval.Verdict.Stale).end)
    }

    @Test fun noWallStoppedUnavailableGoToR4WithDistinctReasons() {
        val a = RelaxApproval.start(RelaxTrial.NoWall, true, "t", "f")
        val b = RelaxApproval.start(RelaxTrial.Stopped, true, "t", "f")
        val c = RelaxApproval.start(RelaxTrial.Unavailable("未割当"), true, "t", "f")
        listOf(a, b, c).forEach { assertEquals(RelaxApproval.Step.R4, it.step) }
        assertEquals(3, setOf(a.end, b.end, c.end).size)
        assertEquals("未割当", c.detail)
        for (e in listOf(a.end!!, b.end!!)) {
            assertFalse(e.text.contains("解消不能"))
            assertFalse(e.text.contains("0件"))
        }
        assertEquals(RelaxApproval.EndReason.NO_GAIN, RelaxApproval.start(result(rr = 3), true, "t", "f").end)
    }

    @Test fun blankReasonDisablesApprove() {
        assertFalse(RelaxApproval.canApprove("", result(), true))
        assertFalse(RelaxApproval.canApprove("  \n", result(), true))
        assertTrue(RelaxApproval.canApprove("師長と確認済み", result(), true))
        assertFalse(RelaxApproval.canApprove("理由", result(), false))
        assertFalse(RelaxApproval.canApprove("理由", RelaxTrial.NoWall, true))
    }

    @Test fun postCheckMismatchRollsBack() {
        val p = RelaxApproval.press(r3(), "d1")!!
        val bad = RelaxApproval.finish(p, RelaxApproval.Verdict.Applied(2))
        assertEquals(RelaxApproval.Step.R4, bad.step)
        assertEquals(RelaxApproval.EndReason.ROLLED_BACK, bad.end)
        val ok = RelaxApproval.finish(p, RelaxApproval.Verdict.Applied(1))
        assertEquals(RelaxApproval.Step.R5, ok.step)
        assertEquals("d1", ok.decisionId)
        assertEquals(RelaxApproval.EndReason.SAVE_FAILED, RelaxApproval.finish(p, RelaxApproval.Verdict.SaveFailed).end)
    }

    @Test fun doublePressIsNoOp() {
        val p = RelaxApproval.press(r3(), "d1")!!
        assertNull(RelaxApproval.press(p, "d1"))
        assertNull(RelaxApproval.press(r1(), "d1"))
    }

    @Test fun declineEndsWithoutChange() {
        assertEquals(RelaxApproval.EndReason.DECLINED, RelaxApproval.decline(r3()).end)
    }

    @Test fun decisionIdIsDeterministic() {
        assertEquals(RelaxApproval.decisionId("t1", 1000L), RelaxApproval.decisionId("t1", 1000L))
        assertNotEquals(RelaxApproval.decisionId("t1", 1000L), RelaxApproval.decisionId("t1", 1001L))
        assertEquals(8, RelaxApproval.decisionId("t1", 1000L).length)
    }

    @Test fun logLinesCarryDecisionAndResult() {
        val l = RelaxApproval.approvalLog("d1", "t1", "確認済み", listOf("A 夜 上限0→1"))
        assertTrue(l.startsWith("S6承認 decisionId=d1 trial=t1 理由=確認済み 前→後 A 夜 上限0→1"))
        assertEquals("S6反映 結果=復旧 check=2", RelaxApproval.applyLog("復旧", 2))
    }
}
