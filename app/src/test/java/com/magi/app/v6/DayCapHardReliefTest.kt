package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
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

    /** 救済で必須を減らした盤面を作り直しても、必須の多い結果へ置き換えない（入口で上限 0 の日のセルを外した基準だけと比べていた、R1）。 */
    @Test fun rerunFromAReliefBoardNeverRaisesTheHardCount() = runBlocking {
        val st0 = load("sample_state_v6.json")
        val p0 = Problem(st0)
        val rest = p0.restIdx!!
        val board = st0.schedule.toIntArray2D()
        val base = UnifiedViolationChecker.check(st0, board)
        for (cell in base.violations.filterValues { it == "vio-c3n" }.keys) {
            val (i, j) = cell.split(',').map { it.toInt() }
            if (p0.wishLocked(i, j) || p0.pinned(i, j) || board[i][j] == rest || !p0.mayPlace(i, rest)) continue
            val st = capRest(st0, rest, listOf(j))
            val r = DayCapHardRelief.apply(st, board, UnifiedViolationChecker.check(st, board), Long.MAX_VALUE)
            if (r.placed.isEmpty()) continue
            val (cleared, n) = HardRepairCore.clearCappedCells(st, r.schedule)
            assertTrue(n > 0)
            val clearedReport = UnifiedViolationChecker.check(st, cleared)
            assertTrue("外した基準のほうが必須が多い前提", clearedReport.hard > r.report.hard)
            assertEquals(r.report.hard, HardRepairCore.dayCapKeptBaseline(st, r.schedule, clearedReport)!!.second.hard)
            val out = V6FinalPort.handleOptimize(st, r.schedule.copy2D(), secondsRaw = 1, workers = 1,
                requestedAlgorithm = V6Algorithm.V5, allowImpossible = true, seed = 7L)
            assertTrue("必須 ${r.report.hard} → ${out.report.hard}", out.report.hard <= r.report.hard)
            return@runBlocking
        }
        assertTrue("救済の起きる盤面が無い", false)
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
