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

    /** C# の同名テストと同じ指紋＝言語跨ぎで同じ盤面と同じ候補の数を出す（この組では途中の盤面を採らず、交換の候補も出ない）。 */
    @Test
    fun matchesCSharpOnSampleFixture() {
        val r = SeesawRelink.apply(state, best, alts(), maxMillis = 600_000L)
        assertEquals(EXPECTED_APPLIED, r.applied)
        assertEquals(EXPECTED_TRADEOFFS, r.tradeoffs.size)
        assertEquals(EXPECTED_HASH, r.schedule.joinToString(";") { it.joinToString(",") }.hashCode())
    }

    /** 3.727.42: 旧の歩き（PLAIN）に対で写す歩きを足しても本命は旧以上のまま、交換の候補は増える（full_coverage・優先順位の採点で 1→3。fair 20 の 3.727.75 から種 2/6/10/14、旧の種 1/5/9/13 は両方 0）。 */
    @Test
    fun pairedWalksKeepTheMainAndAddTradeoffs() {
        ObjectiveWeights.method = ObjectiveMethod.PRIORITY
        try {
            val st = StateParser.parse(javaClass.classLoader!!.getResource("full_coverage_state.json")!!.readText())!!
            val b0 = st.schedule.map { it.toIntArray() }.toTypedArray()
            fun pol(seed: Long) = MembraneCellLns.apply(st, b0, MembraneCellLns.Params(deterministic = true, maxCells = 30, itersPerCell = 60_000, seed = seed)).newSchedule
            val b = pol(2L); val alts = listOf(pol(6L), pol(10L), pol(14L))
            val plain = SeesawRelink.apply(st, b, alts, maxMillis = 600_000L, walks = listOf(SeesawRelink.Walk.PLAIN))
            val all = SeesawRelink.apply(st, b, alts, maxMillis = 600_000L)
            assertFalse(betterReport(plain.report, all.report))
            assertTrue("交換の候補 ${all.tradeoffs.size} > ${plain.tradeoffs.size}", all.tradeoffs.size > plain.tradeoffs.size)
            for (t in all.tradeoffs) {
                val rt = UnifiedViolationChecker.check(st, t)
                assertEquals(all.report.hard, rt.hard)
                assertFalse(SeesawRelink.dominates(all.report, rt))
            }
        } finally { ObjectiveWeights.method = ObjectiveMethod.WEIGHTS }
    }

    @Test
    fun offByDefault() {
        assertFalse(PolishGate.seesawRelink)
        assertFalse(EngineConfig().seesawRelink)
    }

    companion object {
        // [3.727.79] c1＝窓の不足量で 0/3/-1124469770 → 0/0/1395822771（C# の同名テストと同じ値）。
        const val EXPECTED_APPLIED = 0
        const val EXPECTED_TRADEOFFS = 0
        const val EXPECTED_HASH = 1395822771
    }
}
