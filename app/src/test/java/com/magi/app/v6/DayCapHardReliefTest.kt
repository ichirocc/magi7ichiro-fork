package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上限 0 の日を守るせいで残る必須違反は、その日に置いて減らす。必須が減らない手は採らない。 */
class DayCapHardReliefTest {
    private fun load(name: String): MagiState = StateParser.parse(javaClass.getResource("/$name")!!.readText())!!

    private fun capRest(st: MagiState, rest: Int, days: List<Int>) = st.copy(
        needDay1 = st.needDay1 + days.associate { "$rest,$it" to "0" }, needDay2 = st.needDay2 + days.associate { "$rest,$it" to "0" })

    @Test fun placesOnACapZeroDayOnlyWhenTheHardCountDrops() {
        val st0 = load("sample_state_v6.json")
        val p0 = Problem(st0)
        val rest = p0.restIdx!!
        val board = st0.schedule.toIntArray2D()
        val base = UnifiedViolationChecker.check(st0, board)
        var tried = 0
        for (cell in base.violations.filterValues { it == "vio-c3n" }.keys) {
            val (i, j) = cell.split(',').map { it.toInt() }
            if (p0.wishLocked(i, j) || p0.pinned(i, j) || board[i][j] == rest || !p0.mayPlace(i, rest)) continue
            val st = capRest(st0, rest, listOf(j))
            val before = UnifiedViolationChecker.check(st, board)
            val r = DayCapHardRelief.apply(st, board, before, Long.MAX_VALUE)
            tried++
            assertTrue("必須が増えた", r.report.hard <= before.hard)
            if (r.placed.isNotEmpty()) {
                assertTrue("置いたのに必須が減っていない", r.report.hard < before.hard)
                assertEquals(r.report.hard, UnifiedViolationChecker.check(st, r.schedule).hard)
                return
            }
        }
        assertTrue("禁止の並びの候補が無い (tried=$tried)", false)
    }

    @Test fun leavesTheBoardAloneWithoutCapZeroDays() {
        val st = load("sample_state_v6.json")
        val board = st.schedule.toIntArray2D()
        val rep = UnifiedViolationChecker.check(st, board)
        val r = DayCapHardRelief.apply(st, board, rep, Long.MAX_VALUE)
        assertTrue(r.placed.isEmpty())
        assertEquals(rep.hard, r.report.hard)
    }
}
