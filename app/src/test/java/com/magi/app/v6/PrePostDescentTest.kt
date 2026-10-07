package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 後処理前の局所降下（PrePostDescent / PolishGate.prePostDescent）。 */
class PrePostDescentTest {
    private fun load(name: String): MagiState = StateParser.parse(javaClass.classLoader!!.getResource(name)!!.readText())!!
    private fun board(st: MagiState) = Array(st.schedule.size) { st.schedule[it].toIntArray() }

    @Test fun flagRoundTripsThroughSnapshot() {
        val saved = PolishGate.snapshot()
        try {
            PolishGate.prePostDescent = true
            val snap = PolishGate.snapshot()
            PolishGate.prePostDescent = false
            PolishGate.restore(snap)
            assertTrue(PolishGate.prePostDescent)
        } finally { PolishGate.restore(saved) }
    }

    @Test fun offLeavesPostChainUntouched() {
        val st = load("golden_state.json")
        val saved = PolishGate.snapshot()
        try {
            PolishGate.prePostDescent = false
            val prm = V6HotfixPasses.PostOptimizationParams(deterministic = true)
            val a = V6HotfixPasses.runPostOptimization(st, board(st), "t", seed = 3L, params = prm)
            val b = V6HotfixPasses.runPostOptimization(st, board(st), "t", seed = 3L, params = prm)
            assertTrue(a.schedule.contentDeepEquals(b.schedule))
            assertFalse(a.logs.any { it.tag == "PrePostDescent" })
            assertFalse(a.stageRecords.any { it.key == "PrePostDescent" })
        } finally { PolishGate.restore(saved) }
    }

    @Test fun neverWorseAndRespectsPins() {
        for (name in listOf("golden_state.json", "sample_state_v6.json", "sept2026_state.json", "blocked_covu_state.json")) {
            val st = load(name)
            val p = Problem(st)
            val init = normalizeSchedule(board(st), p)
            if (init.any { row -> row.any { it < 0 } }) continue
            val inRep = UnifiedViolationChecker.check(st, init)
            val r = PrePostDescent.apply(st, init, maxMillis = Long.MAX_VALUE, maxDraws = 4_000, maxEvaluations = 800, seed = 11L)
            assertFalse(name, betterReport(inRep, r.report))
            assertEquals(name, UnifiedViolationChecker.check(st, r.newSchedule).weightedScore, r.report.weightedScore, 0.0)
            assertFalse(name, exactPinRegression(p, init, r.newSchedule))
            for (i in 0 until p.S) for (j in 0 until p.T) {
                if (p.wishLocked(i, j)) assertEquals("$name 希望固定 $i,$j", init[i][j], r.newSchedule[i][j])
                else if (r.newSchedule[i][j] != init[i][j]) assertTrue("$name mayPlace $i,$j", p.mayPlace(i, r.newSchedule[i][j]))
            }
            assertTrue(r.logs.single().message.startsWith("後処理前の局所降下"))
        }
    }

    @Test fun onInPostChainIsLoggedAndNeverWorse() {
        val st = load("sample_state_v6.json")
        val saved = PolishGate.snapshot()
        try {
            PolishGate.prePostDescent = true
            val inRep = UnifiedViolationChecker.check(st, board(st))
            val res = V6HotfixPasses.runPostOptimization(st, board(st), "t", seed = 5L, params = V6HotfixPasses.PostOptimizationParams(deterministic = true))
            assertTrue(res.logs.any { it.tag == "PrePostDescent" })
            assertFalse(betterReport(inRep, res.report))
        } finally { PolishGate.restore(saved) }
    }

    @Test fun cappedShiftIsNeverMovedToAnotherCell() {
        // 1 人・5 日。B は個人上限 0（mayPlace=false）で入力の 5 日目に残っている。同一職員の 2 日交換でも別の日へ移さない。
        val rest = Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest)
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-05",
            shifts = listOf(rest, Shift("A", "A", "1", ""), Shift("B", "B", "", "")), groups = listOf(Group("G", "G")), staff = listOf(Staff("X", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
            schedule = listOf(listOf(1, 0, 0, 1, 2)), wishes = emptyMap(), staffRange = mapOf("0,2" to Range("0", "0")), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(), skillGroups = emptyList(), cons41s = emptyList(),
        )
        val p = Problem(st)
        assertFalse(p.mayPlace(0, 2))
        for (seed in 0L until 20L) {
            val r = PrePostDescent.apply(st, board(st), maxMillis = Long.MAX_VALUE, maxDraws = 2_000, seed = seed)
            for (j in 0 until 4) assertTrue("seed=$seed 日${j + 1}", r.newSchedule[0][j] != 2)
        }
    }
}
