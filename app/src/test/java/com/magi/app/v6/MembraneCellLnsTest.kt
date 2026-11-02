package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MembraneCellLnsTest {
    private val state = StateParser.parse(javaClass.classLoader!!.getResource("golden_state.json")!!.readText())!!

    @Test
    fun neverWorseThanInputAndKeepsLockedCells() {
        val p = cachedProblem(state)
        val init = state.schedule.map { it.toIntArray() }.toTypedArray()
        val r = MembraneCellLns.apply(state, init, MembraneCellLns.Params(deterministic = true, maxCells = 6, itersPerCell = 20_000))
        val before = UnifiedViolationChecker.check(state, init)
        val after = UnifiedViolationChecker.check(state, r.newSchedule)
        assertFalse(betterReport(before, after))
        assertTrue(after.hard <= before.hard)
        for (i in 0 until p.S) for (j in 0 until p.T) if (p.wishLocked(i, j)) assertEquals(init[i][j], r.newSchedule[i][j])
        assertFalse(exactPinRegression(p, init, r.newSchedule))
    }

    @Test
    fun deterministicModeIsReproducible() {
        val init = state.schedule.map { it.toIntArray() }.toTypedArray()
        val params = MembraneCellLns.Params(deterministic = true, maxCells = 4, itersPerCell = 10_000)
        val a = MembraneCellLns.apply(state, init, params).newSchedule
        val b = MembraneCellLns.apply(state, init, params).newSchedule
        assertTrue(a.indices.all { a[it].contentEquals(b[it]) })
    }

    /** C# の同名テストと同じ指紋（盤面を "a,b;c,d" に並べた文字列の hashCode）＝言語跨ぎで同じ盤面を出す。 */
    @Test
    fun matchesCSharpOnSampleFixture() {
        val st = StateParser.parse(javaClass.classLoader!!.getResource("sample_state_v6.json")!!.readText())!!
        val r = MembraneCellLns.apply(st, st.schedule.map { it.toIntArray() }.toTypedArray(), MembraneCellLns.Params(deterministic = true, maxCells = 30, itersPerCell = 60_000))
        assertEquals(8, r.applied)
        assertEquals(1952902516, r.newSchedule.joinToString(";") { it.joinToString(",") }.hashCode())
    }

    @Test
    fun onByDefault() {
        assertTrue(PolishGate.membraneCellLns)
        assertTrue(EngineConfig().postParams().membraneCell)
    }
}
