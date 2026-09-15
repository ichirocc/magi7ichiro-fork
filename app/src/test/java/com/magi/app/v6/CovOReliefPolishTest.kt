package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 人員過剰の退避研磨（CovOReliefPolish）: 過剰セルの在勤者を需要 0 のシフト（B）へ退避し、希望固定と禁止連続は避ける。 */
class CovOReliefPolishTest {
    private val rest = Shift("休", "休", "", "")
    private val a = Shift("A", "A", "1", "")
    private val b = Shift("B", "B", "", "")

    private fun st(schedule: List<List<Int>>, wishes: Map<String, Int> = emptyMap(), cons3n: List<C3Row> = emptyList(),
                   staffRange: Map<String, Range> = mapOf("0,0" to Range("0", "0"), "1,0" to Range("0", "0"))): MagiState {
        val t = schedule[0].size
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-%02d".format(t),
            shifts = listOf(rest, a, b), groups = listOf(Group("G", "G")), staff = listOf(Staff("X", 0), Staff("Y", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
            schedule = schedule, wishes = wishes, staffRange = staffRange, needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = cons3n, cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(), skillGroups = emptyList(), cons41s = emptyList(),
        )
    }
    private fun sched(s: MagiState) = s.schedule.map { it.toIntArray() }.toTypedArray()

    @Test fun surplusWorkerIsMovedToDemandlessShift() {
        val s = st(listOf(listOf(1), listOf(1)))
        val r = CovOReliefPolish.apply(s, sched(s))
        assertEquals(1, r.beforeCovO); assertEquals(0, r.afterCovO); assertEquals(1, r.applied)
        assertEquals("休は上限 0 なので B へ退避", 1, r.newSchedule.count { it[0] == 2 })
        assertEquals(1, r.newSchedule.count { it[0] == 1 })
        assertTrue(r.logs.single().message.contains("採用1回"))
    }

    @Test fun wishLockedSurplusIsLeftAlone() {
        val s = st(listOf(listOf(1), listOf(1)), wishes = mapOf("0,0" to 1, "1,0" to 1))
        val r = CovOReliefPolish.apply(s, sched(s))
        assertEquals(0, r.applied); assertEquals(1, r.afterCovO)
        assertTrue(r.logs.single().message.contains("希望固定"))
    }

    @Test fun forbiddenRunBlocksTheMoveAndTheOtherWorkerMoves() {
        // X: [A, A] は 1 日目を B にすると禁止連続 B→A を作るので動かせない。Y: [A, 休] が B へ退避する。
        val s = st(listOf(listOf(1, 1), listOf(1, 0)), cons3n = listOf(C3Row(listOf("B", "A"))),
            staffRange = mapOf("0,0" to Range("0", "0"), "1,0" to Range("1", "1")))
        val r = CovOReliefPolish.apply(s, sched(s))
        assertEquals(1, r.applied)
        assertEquals(1, r.newSchedule[0][0]); assertEquals(1, r.newSchedule[0][1])
        assertEquals(2, r.newSchedule[1][0])
    }
}
