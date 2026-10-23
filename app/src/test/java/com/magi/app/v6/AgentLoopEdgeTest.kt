package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 代行役の輪のエッジデータ: 空・最小・全固定・必須 0・壊れた研磨結果・最大規模でも入力より悪くせず、例外で落ちない。 */
class AgentLoopEdgeTest {
    private val REST = 0; private val A = 1

    private fun state(staff: Int, days: Int, sched: List<List<Int>>, wishes: Map<String, Int> = emptyMap()) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-%02d".format(days.coerceAtLeast(1)),
        shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "1", "1")),
        groups = listOf(Group("G", "G")), staff = List(staff) { Staff("s$it", 0) }, use2Patterns = true,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = sched, wishes = wishes, staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )
    private fun arr(st: MagiState) = Array(st.staffCount) { st.schedule[it].toIntArray() }
    private fun assertNotWorse(r: AgentLoop.Result) =
        assertTrue("入力より悪くしない", !betterReport(r.before, r.after) || r.steps.isEmpty() && r.before.total == r.after.total)

    @Test fun oneStaffOneDay() = runBlocking {
        val st = state(1, 1, listOf(listOf(REST)))   // A が 1 人要るのに休＝不足 1
        val r = AgentLoop.run(st, arr(st), System.currentTimeMillis() + 10_000L)
        assertEquals(0, r.after.hard)
        assertNotWorse(r)
    }

    @Test fun everyCellWishLockedLeavesBoardAsIs() = runBlocking {
        // 2 人とも全日を休で希望固定＝不足は直せない。1 手の案も研磨の差分も希望のセルを触るのでゲートが断る。
        val w = mapOf("0,0" to REST, "0,1" to REST, "1,0" to REST, "1,1" to REST)
        val st = state(2, 2, listOf(listOf(REST, REST), listOf(REST, REST)), w)
        val s = arr(st)
        val r = AgentLoop.run(st, s, Long.MAX_VALUE, now = { 0L },
            suggest = { _, _, _ -> listOf(FixSuggestion(FixKind.CHANGE, listOf(FixCell(0, 0, A)), "希望を壊す", -1, -1, emptyList())) },
            widen = { _, b, _ -> b.also { it[1][1] = A } })
        assertEquals(0, r.steps.size)
        assertEquals(r.before.hard, r.after.hard)
        assertEquals(REST, s[0][0])
    }

    @Test fun hardZeroInputNeverWidens() = runBlocking {
        val st = state(2, 2, listOf(listOf(A, REST), listOf(REST, A)))
        var calls = 0
        val r = AgentLoop.run(st, arr(st), Long.MAX_VALUE, now = { 0L }, suggest = { _, _, _ -> emptyList() }, widen = { _, b, _ -> calls++; b })
        assertEquals(0, r.before.hard)
        assertEquals(0, calls)
        assertEquals(AgentLoop.Stop.NO_CANDIDATE, r.stop)
    }

    @Test fun malformedWidenResultsAreRejected() = runBlocking {
        val st = state(2, 2, listOf(listOf(REST, A), listOf(REST, A)))
        val bad = listOf<Array<IntArray>>(
            arrayOf(),                                        // 行が無い
            arrayOf(intArrayOf(A)),                           // 行も日も足りない（あるセルだけが差分）
            arrayOf(intArrayOf(99, A), intArrayOf(REST, A)),  // 範囲外のシフト
        )
        for (b in bad) {
            val r = AgentLoop.run(st, arr(st), Long.MAX_VALUE, now = { 0L }, suggest = { _, _, _ -> emptyList() }, widen = { _, _, _ -> b })
            assertNotWorse(r)
            r.steps.forEach { step -> step.ops.forEach { op -> assertTrue(op.toShift in 0..1 && op.staff in 0..1 && op.day in 0..1) } }
        }
    }

    @Test fun widenExceptionKeepsEarlierSteps() = runBlocking {
        val st = state(2, 2, listOf(listOf(REST, A), listOf(REST, A)))
        var n = 0
        // 1 回目は合格する手（s0 の 2 日を入れ替える）、2 回目からは案なし→研磨が例外。
        val r = AgentLoop.run(st, arr(st), Long.MAX_VALUE, now = { 0L },
            suggest = { _, _, _ -> if (n++ == 0) listOf(FixSuggestion(FixKind.SWAP, listOf(FixCell(0, 0, A), FixCell(0, 1, REST)), "入替", -1, -2, emptyList())) else emptyList() },
            widen = { _, _, _ -> error("研磨の失敗") })
        assertEquals(1, r.steps.size)
        assertEquals(0, r.after.hard)
    }

    @Test fun zeroRoundsAndEmptyStaff() = runBlocking {
        val st = state(2, 2, listOf(listOf(REST, A), listOf(REST, A)))
        val r = AgentLoop.run(st, arr(st), Long.MAX_VALUE, maxRounds = 0)
        assertEquals(AgentLoop.Stop.ROUND_LIMIT, r.stop); assertEquals(0, r.steps.size)
        val empty = state(0, 2, emptyList())
        val e = AgentLoop.run(empty, arrayOf(), System.currentTimeMillis() + 2_000L)
        assertEquals(0, e.steps.size)
    }

    /** 業務の上限（30 名・31 日）でも締切で止まり、結果は入力より悪くならない。 */
    @Test fun largestBoardStopsByDeadline() = runBlocking {
        val sched = List(30) { i -> List(31) { j -> if ((i + j) % 3 == 0) A else REST } }
        val st = state(30, 31, sched)
        val t0 = System.currentTimeMillis()
        val r = AgentLoop.run(st, arr(st), t0 + 4_000L, searchMs = 1_000L)
        assertTrue("締切 4 秒＋探索 1 回ぶんで止まる", System.currentTimeMillis() - t0 < 4_000L + 3_000L)
        assertNotWorse(r)
        assertEquals(r.after.total, UnifiedViolationChecker.check(st, r.schedule).total)
    }
}
