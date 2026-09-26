package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [S6] 設定の緩和の試算（`docs/s6_relax_trial.md` §12 R1〜R13）。 */
class RelaxTrialTest {
    private val rest = 0; private val a = 1; private val b = 2

    // 2 日・A は毎日 1 人必要・「A→A」は禁止。s0 が A を 2 日続ける（必須違反 1）。s1 は A の上限 0。
    private fun state(
        s1Row: List<Int> = listOf(rest, rest),
        staffRange: Map<String, Range> = mapOf("1,1" to Range("0", "0")),
        groupShift: List<List<Int>> = listOf(listOf(1, 1, 1)),
    ) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-02",
        shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "1", "1"), Shift("B", "B", "", "")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0), Staff("s1", 0)),
        use2Patterns = false, groupShift = groupShift, groupShiftApt = emptyList(),
        schedule = listOf(listOf(a, a), s1Row), wishes = emptyMap(), staffRange = staffRange,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = listOf(C3Row(listOf("A", "A"))), cons3m = emptyList(),
        cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun discover(st: MagiState, i: Int = 0, j: Int = 0, stop: () -> Boolean = { false }) =
        RelaxTrial.discover(st, st.schedule.toIntArray2D(), i, j, shouldStop = stop)

    @Test fun r1_findsTheUpperZeroThatBlocksTheFix() {
        val st = state()
        assertEquals("前提: 必須違反 1", 1, UnifiedViolationChecker.check(st).hard)
        val r = discover(st) as RelaxTrial.Result
        assertEquals(listOf(RelaxTrial.Relax(1, a, 1)), r.relaxes)
        assertTrue(r.prerequisite.isEmpty())
        assertEquals(1, r.h0); assertEquals(1, r.rk); assertEquals(0, r.rr)
        assertEquals(1, r.att); assertEquals(1, r.attWalls)
        assertEquals("s0 と s1 がどちらかの日の A と休を入れ替える 1 組", 2, r.moves.size)
        assertTrue(r.moves.all { it.day == r.moves[0].day })
    }

    @Test fun r2_noUpperZeroWallGivesNoWall() {
        // s1 は A を担当できない（グループ）→ 上限 0 の母集団に A は入らない。B の上限 0 は緩めても直らない。
        val st = state(staffRange = mapOf("1,2" to Range("0", "0")), groupShift = listOf(listOf(1, 1, 1)))
            .copy(groups = listOf(Group("G", "G"), Group("H", "H")), groupShift = listOf(listOf(1, 1, 1), listOf(1, 0, 1)),
                staff = listOf(Staff("s0", 0), Staff("s1", 1)))
        assertEquals(listOf(1 to b), RelaxTrial.upperZeroWalls(st))
        assertEquals(RelaxTrial.NoWall, discover(st))
        val none = state(staffRange = emptyMap()).copy(groups = listOf(Group("G", "G"), Group("H", "H")),
            groupShift = listOf(listOf(1, 1, 1), listOf(1, 0, 1)), staff = listOf(Staff("s0", 0), Staff("s1", 1)))
        assertEquals(RelaxTrial.NoWall, discover(none))
    }

    @Test fun r3_handPlacedCappedCellIsAPrerequisiteNotAWall() {
        // s1 の 1 日目に上限 0 の B が手で置いてある（本実行の入口の clear が外す）。
        val st = state(s1Row = listOf(b, rest), staffRange = mapOf("1,1" to Range("0", "0"), "1,2" to Range("", "0")))
        assertEquals(listOf(RelaxTrial.Relax(1, b, 1)), RelaxTrial.handPlaced(st, st.schedule.toIntArray2D()))
        val r = discover(st) as RelaxTrial.Result
        assertEquals(listOf(RelaxTrial.Relax(1, b, 1)), r.prerequisite)
        assertEquals(listOf(RelaxTrial.Relax(1, a, 1)), r.relaxes)
        assertEquals(0, r.rr)
    }

    @Test fun r4_deterministic() {
        assertEquals(discover(state()), discover(state()))
    }

    @Test fun r5_unassignedCellIsUnavailableNotZero() {
        assertTrue(discover(state(s1Row = listOf(-1, rest))) is RelaxTrial.Unavailable)
    }

    @Test fun r6_stoppedGivesNoNumbers() {
        assertEquals(RelaxTrial.Stopped, discover(state(), stop = { true }))
    }

    @Test fun r7_inputsAreNotMutated() {
        val st = state()
        val copy = st.copy()
        val board = st.schedule.toIntArray2D()
        val before = board.copy2D()
        discover(st)
        assertEquals(copy, st)
        assertTrue(before.contentDeepEquals(board))
    }

    @Test fun r8_nonHardCellIsNotAnAnchor() {
        assertNull(discover(state(), i = 1, j = 0))
    }

    @Test fun r9_applyRaisesOnlyTheUpperAndPinsAreNotWalls() {
        val st = state(staffRange = mapOf("1,1" to Range("0", "0"), "1,0" to Range("", "0"), "0,2" to Range("1", "1")))
        assertEquals("休みの上限 0 と、0 でない「ちょうど」は母集団に入らない", listOf(1 to a), RelaxTrial.upperZeroWalls(st))
        val st2 = RelaxTrial.apply(st, listOf(RelaxTrial.Relax(1, a, 1)))
        assertEquals(Range("0", "1"), st2.staffRange["1,1"])
        assertEquals(st.staffRange - "1,1", st2.staffRange - "1,1")
        assertTrue(cachedProblem(st2, false).mayPlace(1, a))
    }

    @Test fun r10_wishSelfConflictCellIsNotAnAnchor() {
        // s0 の A→A が両日とも希望＝設定では解けない（S5 の領分）。
        val st = state().copy(wishes = mapOf("0,0" to a, "0,1" to a))
        assertTrue(RelaxTrial.anchors(st, st.schedule.toIntArray2D()).isEmpty())
        assertEquals(RelaxTrial.NoWall, RelaxTrial.firstWall(st, st.schedule.toIntArray2D()))
        assertEquals(listOf(0 to 0), RelaxTrial.anchors(state(), state().schedule.toIntArray2D()))
    }

    @Test fun r11_applyMovesRefusesAMismatchedBoard() {
        val bd = arrayOf(intArrayOf(a, a), intArrayOf(rest, rest))
        val m = listOf(RelaxTrial.Move(0, 1, a, rest), RelaxTrial.Move(1, 1, rest, a))
        assertTrue(RelaxTrial.applyMoves(bd, m)!!.contentDeepEquals(arrayOf(intArrayOf(a, rest), intArrayOf(rest, a))))
        assertEquals("入力は書かない", a, bd[0][1])
        assertNull(RelaxTrial.applyMoves(arrayOf(intArrayOf(a, b), intArrayOf(rest, rest)), m))
    }

    @Test fun r12_tooManyRelaxesIsNoWall() {
        val st = state()
        assertEquals(RelaxTrial.NoWall, RelaxTrial.discover(st, st.schedule.toIntArray2D(), 0, 0, maxRelaxes = 0))
    }

    /** 実データ（2026-10、氏名は伏せ字）: 職員10（アリフ）10/8→10/9 の禁止の並びを 職員11 Cｵ＋職員10 Pｼ の組で解く（§13）。 */
    @Test fun r13_realData_setFoundAndConfirmReproducesTheTrial() {
        val st = StateParser.parse(javaClass.getResource("/oct2026_grid_state.json")!!.readText())!!
        val board = st.schedule.toIntArray2D()
        val pS = st.shifts.indexOfFirst { it.kigou == "Pｼ" }; val cO = st.shifts.indexOfFirst { it.kigou == "Cｵ" }
        val r = RelaxTrial.firstWall(st, board) as RelaxTrial.Result
        assertEquals(listOf(RelaxTrial.Relax(9, pS, 1), RelaxTrial.Relax(10, cO, 1)), r.relaxes)
        assertEquals(5, r.h0); assertEquals(4, r.rr); assertEquals(1, r.att)
        assertEquals("手で置いた上限 0 は前提に分ける", RelaxTrial.handPlaced(st, board), r.prerequisite)
        val ns = RelaxTrial.apply(st, r.prerequisite + r.relaxes)
        val nb = RelaxTrial.applyMoves(board, r.moves)!!
        assertEquals("確定の盤面は試算の結果そのもの", r.rr, UnifiedViolationChecker.check(ns, nb).hard)
    }
}
