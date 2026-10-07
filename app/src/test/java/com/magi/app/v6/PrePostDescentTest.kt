package com.magi.app.v6

import com.magi.app.model.MagiState
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
}
