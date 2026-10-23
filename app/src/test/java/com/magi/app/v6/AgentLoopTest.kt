package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 代行役の輪: 合格した手だけを当て、入力を変えず、結果は入力より悪くならない。 */
class AgentLoopTest {
    private val REST = 0; private val A = 1

    /** 1 日目は A が 0 人（不足 1）、2 日目は A が 2 人（過剰 1）。s0 の 2 日を入れ替えれば両方消える。 */
    private fun state() = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-02",
        shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "1", "1")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0), Staff("s1", 0)), use2Patterns = true,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(listOf(REST, A), listOf(REST, A)), wishes = emptyMap(), staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )
    private fun sched(st: MagiState) = Array(st.staffCount) { st.schedule[it].toIntArray() }

    @Test fun appliesGatedMovesUntilNoCandidateAndKeepsInput() = runBlocking {
        val st = state(); val s = sched(st)
        val r = AgentLoop.run(st, s, deadlineMs = System.currentTimeMillis() + 20_000L)
        assertEquals(1, r.before.hard)
        assertEquals(0, r.after.hard)
        assertTrue(r.steps.isNotEmpty())
        assertEquals(AgentLoop.Stop.NO_CANDIDATE, r.stop)
        assertEquals(REST, s[0][0]); assertEquals(A, s[0][1])   // 入力は不変
        for (k in 1 until r.steps.size) assertEquals(r.steps[k - 1].hardAfter, r.steps[k].hardBefore)
        assertEquals(r.after.total, UnifiedViolationChecker.check(st, r.schedule).total)
    }

    @Test fun rejectedCandidatesAreNotApplied() = runBlocking {
        val st = state(); val s = sched(st)
        // 何も変えない案（ゲートが「変更がありません」で断る）と、悪くする案だけを返す探索。
        val bad = FixSuggestion(FixKind.CHANGE, listOf(FixCell(1, 1, REST)), "悪くする", 1, 1, emptyList())
        val none = FixSuggestion(FixKind.CHANGE, emptyList(), "空", 0, 0, emptyList())
        val r = AgentLoop.run(st, s, deadlineMs = Long.MAX_VALUE, suggest = { _, _, _ -> listOf(none, bad) })
        assertEquals(0, r.steps.size)
        assertEquals(AgentLoop.Stop.NO_CANDIDATE, r.stop)
        assertEquals(r.before.total, r.after.total)
    }

    @Test fun stopsWhenCancelledOrOutOfTime() = runBlocking {
        val st = state(); val s = sched(st)
        assertEquals(AgentLoop.Stop.CANCELLED, AgentLoop.run(st, s, Long.MAX_VALUE, active = { false }).stop)
        assertEquals(AgentLoop.Stop.TIME_LIMIT, AgentLoop.run(st, s, deadlineMs = 0L, now = { 1L }).stop)
    }

    /** 1 手の案が尽きて必須が残れば広域の研磨を 1 回だけ試し、合格した差分だけを 1 手として当てる（#13(g)）。 */
    @Test fun widensOnceWhenNoCandidateAndGatesTheResult() = runBlocking {
        val st = state(); val s = sched(st)
        var calls = 0
        val fixed = arrayOf(intArrayOf(A, REST), intArrayOf(REST, A))   // s0 の 2 日を入れ替えた盤面＝必須 0
        val r = AgentLoop.run(st, s, Long.MAX_VALUE, now = { 0L }, suggest = { _, _, _ -> emptyList() },
            widen = { _, _, _ -> calls++; fixed })
        assertEquals(1, calls)
        assertEquals(1, r.steps.size)
        assertEquals("${AgentLoop.WIDEN_LABEL}（2セル）", r.steps[0].label)
        assertEquals(0, r.after.hard)
        assertEquals(AgentLoop.Stop.NO_CANDIDATE, r.stop)
    }

    @Test fun widenResultThatDoesNotImproveIsDropped() = runBlocking {
        val st = state(); val s = sched(st)
        var calls = 0
        val r = AgentLoop.run(st, s, Long.MAX_VALUE, now = { 0L }, suggest = { _, _, _ -> emptyList() },
            widen = { _, b, _ -> calls++; b })   // 変えない＝ゲートが断る
        assertEquals(1, calls)
        assertEquals(0, r.steps.size)
        assertEquals(r.before.total, r.after.total)
    }
}
