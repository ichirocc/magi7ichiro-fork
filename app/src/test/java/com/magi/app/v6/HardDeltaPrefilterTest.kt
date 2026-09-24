package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** [HardDelta] が checker の HARD 差と厳密に一致し、[PolishGate.hardDeltaPrefilter] の ON/OFF で盤面が変わらないこと。 */
class HardDeltaPrefilterTest {
    private val fixtures = listOf("/golden_state.json", "/sample_state_v6.json", "/blocked_covu_state.json", "/sept2026_state.json")

    private fun load(res: String): MagiState = StateParser.parse(javaClass.getResourceAsStream(res)!!.bufferedReader().readText())!!
    private fun board(st: MagiState, p: Problem) = normalizeSchedule(Array(st.schedule.size) { st.schedule[it].toIntArray() }, p)
    private fun hard(st: MagiState, b: Array<IntArray>) = UnifiedViolationChecker.check(st, b).hard

    @Test
    fun deltaEqualsCheckerHardDifferenceOnRandomChanges() {
        val rng = Random(20260924L)
        for (res in fixtures) {
            val st = load(res); val p = Problem(st)
            var base = board(st, p)
            repeat(150) { trial ->
                val cand = base.copy2D()
                val cells = if (trial % 3 == 0) 1 else 2 + rng.nextInt(6)
                repeat(cells) { cand[rng.nextInt(p.S)][rng.nextInt(p.T)] = rng.nextInt(p.K + 1) - (if (rng.nextInt(10) == 0) 1 else 0) }
                assertEquals("$res trial=$trial", hard(st, cand) - hard(st, base), HardDelta.delta(p, base, cand))
                if (trial % 10 == 0) base = normalizeSchedule(cand, p)
            }
        }
    }

    @Test
    fun sameDayPermutationDeltaEqualsCheckerHardDifference() {
        val rng = Random(7L)
        for (res in fixtures) {
            val st = load(res); val p = Problem(st)
            val work = board(st, p)
            repeat(200) { trial ->
                val j = rng.nextInt(p.T)
                val k = 2 + rng.nextInt(2)
                val staff = (0 until p.S).shuffled(rng).take(k).toIntArray()
                val old = IntArray(k) { work[staff[it]][j] }
                val before = work.copy2D()
                for (t in 0 until k) work[staff[t]][j] = old[(t + 1) % k]
                val expected = hard(st, work) - hard(st, before)
                assertEquals("$res trial=$trial", expected, HardDelta.sameDayPermutationDelta(p, work, j, staff, old))
                assertEquals("$res trial=$trial", expected, HardDelta.delta(p, before, work))
                for (t in 0 until k) work[staff[t]][j] = old[t]
            }
        }
    }

    private fun <T> withPrefilter(on: Boolean, block: () -> T): T {
        val saved = PolishGate.hardDeltaPrefilter
        PolishGate.hardDeltaPrefilter = on
        try { return block() } finally { PolishGate.hardDeltaPrefilter = saved }
    }

    @Test
    fun cyclicSwapAndC1BeamBoardsAreIdenticalWithPrefilterOnAndOff() {
        for (res in fixtures) {
            val st = load(res); val p = Problem(st)
            fun cyc() = CyclicSwapWeeklyPolish.applyCyclicSwapPolish(st, board(st, p))
            fun beam() = C1WindowPolish.applyC1BeamPolish(st, board(st, p), beamWidth = 6, maxSteps = 6)
            val cOn = withPrefilter(true) { cyc() }; val cOff = withPrefilter(false) { cyc() }
            assertTrue("$res cyclic", cOn.newSchedule.contentDeepEquals(cOff.newSchedule))
            assertEquals(res, cOff.applied, cOn.applied)
            val bOn = withPrefilter(true) { beam() }; val bOff = withPrefilter(false) { beam() }
            assertTrue("$res beam", bOn.newSchedule.contentDeepEquals(bOff.newSchedule))
        }
    }
}
