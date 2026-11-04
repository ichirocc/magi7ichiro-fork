package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** シーソーの S3（`SeesawRelink`、docs/seesaw.md §6.3）。 */
class SeesawRelinkTest {
    private val state = StateParser.parse(javaClass.classLoader!!.getResource("sample_state_v6.json")!!.readText())!!
    private val init = state.schedule.map { it.toIntArray() }.toTypedArray()

    private fun polished(seed: Long) =
        MembraneCellLns.apply(state, init, MembraneCellLns.Params(deterministic = true, maxCells = 30, itersPerCell = 60_000, seed = seed)).newSchedule

    /** 本命＝膜の細胞で磨いた盤面（seed 1、MembraneCellLnsTest の C# 指紋と同じ条件）、他の案＝別の seed（5）で磨いた同じくらいの出来の盤面。 */
    private val best = polished(1L)
    private fun alts(): List<Array<IntArray>> = listOf(polished(5L))

    @Test
    fun neverWorseAndTradeoffsAreNotDominated() {
        val p = cachedProblem(state)
        val r = SeesawRelink.apply(state, best, alts(), maxMillis = 600_000L)
        val before = UnifiedViolationChecker.check(state, best)
        assertFalse(betterReport(before, r.report))
        assertFalse(exactPinRegression(p, best, r.schedule))
        for (i in 0 until p.S) for (j in 0 until p.T) if (p.wishLocked(i, j)) assertEquals(init[i][j], r.schedule[i][j])
        assertTrue(r.tradeoffs.size <= SeesawRelink.MAX_TRADEOFFS)
        for (t in r.tradeoffs) {
            val rt = UnifiedViolationChecker.check(state, t)
            assertEquals(r.report.hard, rt.hard)
            assertFalse("交換の候補が本命に優越されている", SeesawRelink.dominates(r.report, rt))
        }
    }

    @Test
    fun dominatesIsStrictAndIgnoresWeights() {
        val a = UnifiedViolationChecker.check(state, init)
        assertFalse(SeesawRelink.dominates(a, a))
    }

    /** C# の同名テストと同じ指紋＝言語跨ぎで同じ盤面と同じ候補の数を出す（この組では途中の盤面を 2 回採り、交換の候補は出ない）。 */
    @Test
    fun matchesCSharpOnSampleFixture() {
        val r = SeesawRelink.apply(state, best, alts(), maxMillis = 600_000L)
        assertEquals(EXPECTED_APPLIED, r.applied)
        assertEquals(EXPECTED_TRADEOFFS, r.tradeoffs.size)
        assertEquals(EXPECTED_HASH, r.schedule.joinToString(";") { it.joinToString(",") }.hashCode())
    }

    @Test
    fun offByDefault() {
        assertFalse(PolishGate.seesawRelink)
        assertFalse(EngineConfig().seesawRelink)
    }

    companion object {
        const val EXPECTED_APPLIED = 2
        const val EXPECTED_TRADEOFFS = 0
        const val EXPECTED_HASH = -2074942663
    }
}
