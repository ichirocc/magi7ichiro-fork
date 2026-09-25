package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.C3wRow
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [S5] 希望取り消しの試算（`docs/s5_wish_trial.md` §12 T1〜T7・T11）。 */
class WishTrialTest {
    private val rest = 0; private val a = 1; private val b = 2
    private val row1 = listOf(rest, rest, rest, rest, rest)

    private fun state(
        schedule: List<List<Int>>, wishes: Map<String, Int>,
        cons3w: List<C3wRow> = emptyList(), cons3n: List<C3Row> = emptyList(),
        groupShift: List<List<Int>> = listOf(listOf(1, 1, 1)),
    ) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-05",
        shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0), Staff("s1", 0)),
        use2Patterns = false, groupShift = groupShift, groupShiftApt = emptyList(),
        schedule = schedule, wishes = wishes, staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = cons3n, cons3m = emptyList(),
        cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(), cons3w = cons3w,
    )

    private fun run(st: MagiState, i: Int, j: Int) = WishTrial.trial(st, st.schedule.toIntArray2D(), i, j) as WishTrial.Result

    @Test fun t1_certainPartIsOneForPrefAndC3wXAndZeroForC3n() {
        val pref = state(listOf(listOf(rest, rest, b, rest, rest), row1), mapOf("0,2" to a))
        assertEquals(1, run(pref, 0, 2).a)
        val c3w = state(listOf(listOf(rest, b, a, rest, rest), row1), mapOf("0,2" to a), cons3w = listOf(C3wRow("A", "B")))
        assertEquals(1, run(c3w, 0, 2).a)
        val c3n = state(listOf(listOf(rest, a, a, rest, rest), row1), mapOf("0,2" to a), cons3n = listOf(C3Row(listOf("A", "A"))))
        val r = run(c3n, 0, 2)
        assertEquals(1, r.h0)
        assertEquals("満たされた希望は同じ盤面では何も消さない", 0, r.a)
    }

    @Test fun t2_unlockedOrMissingWishIsNotATarget() {
        val unlocked = state(listOf(listOf(rest, rest, b, rest, rest), row1), mapOf("0,2" to a), groupShift = listOf(listOf(1, 0, 1)))
        assertNull("担当できない勤務の希望", WishTrial.trial(unlocked, unlocked.schedule.toIntArray2D(), 0, 2))
        assertFalse("0,2" in WishTrial.lockedWishKeys(unlocked))
        val minus = state(listOf(listOf(rest, rest, b, rest, rest), row1), mapOf("0,2" to -1))
        assertNull("-1 の希望", WishTrial.trial(minus, minus.schedule.toIntArray2D(), 0, 2))
        assertNull("希望なし", WishTrial.trial(minus, minus.schedule.toIntArray2D(), 1, 2))
        val locked = state(listOf(listOf(rest, rest, b, rest, rest), row1), mapOf("0,2" to a))
        assertEquals(setOf("0,2"), WishTrial.lockedWishKeys(locked))
    }

    @Test fun t3_repairIndependentOfTheWishIsSubtractedByTheControl() {
        // s1 の禁止の並び（希望と無関係）は希望を残したままでも修復で消える＝取り消しに帰属させない。
        val st = state(listOf(listOf(rest, a, a, rest, rest), listOf(a, a, rest, rest, rest)), mapOf("0,2" to a),
            cons3n = listOf(C3Row(listOf("A", "A"))))
        val r = run(st, 0, 2)
        assertTrue("前提: 修復で減る", r.rr < r.h0)
        assertTrue("前提: 対照も減る", r.rk < r.h0)
        assertEquals(0, r.att)
        assertEquals(0, r.b)
        assertEquals(0, r.aPrime)
    }

    @Test fun t3b_c3wFixableWhileKeepingTheWishGivesAttBelowA() {
        // 前日の B は希望を残したまま動かせる＝対照が a をすでに含む。生の a を「確実に」と言わない。
        val st = state(listOf(listOf(rest, b, a, rest, rest), row1), mapOf("0,2" to a), cons3w = listOf(C3wRow("A", "B")))
        val r = run(st, 0, 2)
        assertEquals(1, r.a)
        assertTrue("対照が減らす", r.rk < r.h0)
        assertTrue(r.att < r.a)
        assertTrue(r.aPrime < r.a)
    }

    @Test fun t4_deterministic() {
        val st = state(listOf(listOf(rest, a, a, rest, rest), listOf(a, a, rest, rest, rest)), mapOf("0,2" to a),
            cons3n = listOf(C3Row(listOf("A", "A"))))
        assertEquals(run(st, 0, 2), run(st, 0, 2))
        val ctl = (WishTrial.control(st, st.schedule.toIntArray2D()) as WishTrial.ControlOutcome).control
        assertEquals("対照を渡しても同じ", run(st, 0, 2), WishTrial.trial(st, st.schedule.toIntArray2D(), 0, 2, control = ctl))
    }

    @Test fun t5_unassignedCellIsUnavailableNotZero() {
        val st = state(listOf(listOf(rest, -1, b, rest, rest), row1), mapOf("0,2" to a))
        assertTrue(WishTrial.trial(st, st.schedule.toIntArray2D(), 0, 2) is WishTrial.Unavailable)
        assertTrue(WishTrial.control(st, st.schedule.toIntArray2D()) is WishTrial.Unavailable)
    }

    @Test fun t6_stoppedGivesNoNumbers() {
        val st = state(listOf(listOf(rest, rest, b, rest, rest), row1), mapOf("0,2" to a))
        assertEquals(WishTrial.Stopped, WishTrial.trial(st, st.schedule.toIntArray2D(), 0, 2, shouldStop = { true }))
    }

    @Test fun t7_inputsAreNotMutated() {
        val st = state(listOf(listOf(rest, a, a, rest, rest), listOf(a, a, rest, rest, rest)), mapOf("0,2" to a),
            cons3n = listOf(C3Row(listOf("A", "A"))))
        val copy = st.copy()
        val board = st.schedule.toIntArray2D()
        val before = board.copy2D()
        WishTrial.trial(st, board, 0, 2)
        assertEquals(copy, st)
        assertTrue(before.contentDeepEquals(board))
    }

    @Test fun t11_wishPinnedListsOnlyPlaceableStaffLockedToAnotherShift() {
        // A の必要 3。s0=B 希望（入る）、s1=A 希望（不足シフトの希望＝入らない）、s2=A 上限 0 で B 希望（入らない）、
        // s3=希望なし（入らない）、s4=A を担当できず B 希望（入らない）。
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-01",
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "3", "3"), Shift("B", "B", "", "")),
            groups = listOf(Group("G", "G"), Group("H", "H")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0), Staff("s2", 0), Staff("s3", 0), Staff("s4", 1)),
            use2Patterns = true, groupShift = listOf(listOf(1, 1, 1), listOf(1, 0, 1)), groupShiftApt = emptyList(),
            schedule = listOf(listOf(b), listOf(a), listOf(b), listOf(rest), listOf(b)),
            wishes = mapOf("0,0" to b, "1,0" to a, "2,0" to b, "4,0" to b),
            staffRange = mapOf("2,1" to Range("", "0")), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(),
            cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val sf = V6PortAnalyzer.diagnoseCoverage(st).shortfalls.single { it.shiftIndex == a }
        assertEquals(listOf(0), sf.wishPinned)
        assertEquals(CoverageVerdict.INFEASIBLE, sf.verdict)
        assertEquals("いまの希望のままでは担当できる人が2人で必要数3に届きません（希望で別の勤務に固定: 1人）", sf.reason)

        val noWish = st.copy(wishes = emptyMap(), staff = st.staff.take(2) + Staff("s2", 1) + st.staff.drop(3).map { Staff(it.name, 1) })
        val sf2 = V6PortAnalyzer.diagnoseCoverage(noWish).shortfalls.single { it.shiftIndex == a }
        assertTrue(sf2.wishPinned.isEmpty())
        assertEquals(CoverageVerdict.INFEASIBLE, sf2.verdict)
        assertTrue(sf2.reason, sf2.reason.startsWith("担当可能な職員が"))
    }
}
