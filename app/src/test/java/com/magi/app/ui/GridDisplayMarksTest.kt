package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 勤務表の表示専用の印（c1 の窓ごとの印・回数の行末の印・日ヘッダの人員・隠れた族の点）を実データで固定する。 */
class GridDisplayMarksTest {
    private fun load(path: String): MagiState =
        StateParser.parse(javaClass.getResourceAsStream(path)!!.bufferedReader().readText())!!

    private fun uiOf(st: MagiState, rep: ViolationReport) = UiState(
        staff = st.staffCount, days = st.dayCount, shifts = st.shiftCount, schedule = st.schedule,
        shiftSymbols = st.shifts.map { it.kigou },
        violationCells = rep.violations, violationCellFamilies = rep.cellFamilies,
        needViolations = rep.needViolations, needFamilies = rep.needFamilies,
        countViolations = rep.countViolations, countFamilies = rep.countFamilies,
        distLocations = rep.distLocations, c1Runs = rep.c1Runs, breakdown = rep.breakdown,
    )

    private val st = load("/oct2026_grid_state.json")
    private val rep = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
    private val ui = uiOf(st, rep)
    private val vs = MagiViewState(ui)

    @Test fun everyViolatedC1WindowContainsADisplayMark() {
        assertEquals("ランの窓数の和はチェッカーの c1 件数", rep.breakdown["c1"] ?: 0, rep.c1Runs.sumOf { it[2] })
        assertTrue(rep.c1Runs.isNotEmpty())
        for ((i, j0, n, w) in rep.c1Runs) for (s in j0 until j0 + n) {
            val hit = (s until s + w).any { a -> "vio-c1" in displayCellClasses(ui, VioKey.cell(i, a), vs.c1Anchors) }
            assertTrue("職員$i の窓 ${s + 1}日〜 に印がない", hit)
        }
    }

    @Test fun checkerMarksStayAtTheRunHeadOnly() {
        val c1Cells = rep.cellFamilies.filterValues { "vio-c1" in it }.keys
        assertEquals(rep.c1Runs.map { VioKey.cell(it[0], it[1]) }.toSet(), c1Cells)
    }

    @Test fun everyStaffWithACountKeyHasABadge() {
        val keys = rep.countFamilies.keys
        assertEquals(27, keys.size)
        val staff = keys.mapNotNull { VioKey.first(it) }.toSet()
        assertEquals(staff, vs.countBadges.keys)
        for (i in staff) assertTrue(staffCountLines(ui, i).isNotEmpty())
        assertTrue(MagiViewState(ui, allVioBucketKeys - "count").countBadges.isEmpty())
    }

    @Test fun coverageHeaderNamesTheShift() {
        val rest = st.shifts.indexOfFirst { it.kigou == "休" }
        val a4 = st.shifts.indexOfFirst { it.kigou == "A4" }
        for (d in listOf(27, 28)) assertTrue("10/${d + 1} 休▲", CoverageMark(rest, false) in vs.coverageMarks[d])
        assertTrue("10/9 A4▲", CoverageMark(a4, false) in vs.coverageMarks[8])
        assertTrue(coverageHeaderLabel(ui, vs.coverageMarks[27]).startsWith("休▲"))
        assertTrue(MagiViewState(ui, allVioBucketKeys - "need").coverageMarks.all { it.isEmpty() })
    }

    @Test fun hiddenC42sCellsGetASecondaryDot() {
        val hidden = rep.cellFamilies.filterValues { "vio-c42s" in it && it.first() != "vio-c42s" }.keys
        assertEquals(5, hidden.size)
        for (key in hidden) assertNotNull(key, vs.cellSecond[VioKey.first(key)!!][VioKey.second(key)!!])
    }

    @Test fun fairAndWeeklyAppearInTheStaffSheet() {
        for (fam in listOf("fair", "weekly")) for (e in rep.distLocations[fam].orEmpty()) {
            assertTrue("$fam 職員${e[0]}", staffCountLines(ui, e[0]).any { breakdownLabels[fam]!! in it })
        }
    }
}
