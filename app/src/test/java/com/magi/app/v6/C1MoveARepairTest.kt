package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PolishGate.c1MoveARepair] 手A（同日交換）の相手 i2 が受け取るシフトで禁止連続ができる局面。
 * i=[A,A,A] は X(3日で1回以上) が不足。i2=[X,D,X] と day0 を交換すると i2=[A,D,X] で禁止 A→D が成立し、
 * 素の交換は HARD 増で棄却される。i2 の day1 の D を付け替えれば c1 だけが 1 減る。
 */
class C1MoveARepairTest {
    @After fun reset() { PolishGate.c1MoveARepair = false }

    private fun state(): MagiState = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-03",
        shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""), Shift("D", "D", "", ""), Shift("A", "A", "", "")),
        groups = listOf(Group("G0", "G0")), staff = listOf(Staff("i", 0), Staff("i2", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1, 1)),
        groupShiftApt = List(1) { List(4) { "" } },
        schedule = listOf(listOf(3, 3, 3), listOf(1, 2, 1)),
        wishes = emptyMap(), staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = listOf(C1Row(day1 = "3", shiftKigou = "X", day2 = "1")),
        cons2 = emptyList(), cons3 = emptyList(),
        cons3n = listOf(C3Row(listOf("A", "D"))), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test
    fun repairedSwapReducesC1WithoutHardIncrease() {
        val st = state()
        val sched = st.schedule.toIntArray2D()
        val p = Problem(st)
        val w = sched.copy2D(); w[0][0] = 1; w[1][0] = 3
        assertTrue("前提: 素の交換は禁止連続を作る", p.makesForbiddenRun(w, 1, 0, 3))
        val before = UnifiedViolationChecker.check(st, sched)
        PolishGate.c1MoveARepair = true
        val res = C1WindowPolish.applyC1WindowPolish(st, sched, maxPasses = 1)
        val after = UnifiedViolationChecker.check(st, res.newSchedule)
        assertEquals(0, after.hard)
        assertTrue((after.breakdown["c1"] ?: 0) < (before.breakdown["c1"] ?: 0))
        assertTrue(res.logs.any { it.message.contains("手A禁止連続修復:試行1/採用1") })
    }

    @Test
    fun offLeavesLogUnchanged() {
        val st = state()
        val res = C1WindowPolish.applyC1WindowPolish(st, st.schedule.toIntArray2D(), maxPasses = 1)
        assertFalse(res.logs.any { it.message.contains("手A禁止連続修復") })
    }
}
