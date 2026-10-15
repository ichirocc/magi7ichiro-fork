package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 印と一文の写しが、セルのシート・上部の札・巡回と同じ答えを持つことを実データで固定する。 */
class DisplaySnapshotTest {
    private val st: MagiState =
        StateParser.parse(javaClass.getResourceAsStream("/oct2026_grid_state.json")!!.bufferedReader().readText())!!
    private val s = st.schedule.toIntArray2D()
    private val rep = UnifiedViolationChecker.check(st, s)
    private val ui = UiState(
        schedule = s.map { it.toList() }, bestHard = rep.hard.toLong(), bestSoft = rep.soft.toLong(),
        breakdown = emptyBreakdown + rep.breakdown, violationCells = rep.violations,
        violationCellFamilies = rep.cellFamilies, needFamilies = rep.needFamilies, countFamilies = rep.countFamilies,
        c1Shortages = c1Shortages(cachedProblem(st), s), checkRev = 1,
    )
    private val snap = buildDisplaySnapshot(st, ui)

    @Test fun cellSeverityMatchesItsStatusLine() {
        assertTrue(snap.cellMarks.isNotEmpty())
        val p = cachedProblem(st)
        val c1 = c1DisplayMarks(ui)
        for ((cell, m) in snap.cellMarks) {
            val (i, j) = cell
            assertEquals(cellMarkFamilies(ui, i, j, c1), m.families)
            val line = cellStatusLine(st, p, s, i, j, m.families)
            assertEquals(line.severity, m.severity)
            assertEquals(line.text, m.oneLine)
            assertEquals(m.families.any { it in MirrorKeys.hard }, m.severity == CellSeverity.HARD)
            assertTrue(m.oneLine, m.oneLine.startsWith(if (m.severity == CellSeverity.HARD) "⚠ 必須：" else "⚠ 要調整："))
        }
        assertEquals(CellSeverity.NONE, snap.cellMark(-1, -1).severity)
    }

    @Test fun hardTextEqualsUnitTotalsAndDisplayHardTextReadsIt() {
        assertEquals(unitTotals(ui.breakdown, MirrorKeys.hard) ?: "${ui.bestHard}件", snap.hardText)
        val withSnap = ui.copy(display = snap)
        assertTrue(snap.freshFor(withSnap))
        assertEquals(snap.hardText, displayHardText(withSnap))
        val stale = withSnap.copy(checkRev = 2, bestHard = 0, breakdown = emptyBreakdown)
        assertTrue(!snap.freshFor(stale))
        assertEquals(hardText(emptyBreakdown, 0), displayHardText(stale))
    }

    @Test fun tourCountEqualsTourItems() {
        val items = hardViolationItems(st, cachedProblem(st), s, rep.cellFamilies)
        assertEquals(items, snap.tourItems)
        assertEquals(items.size, snap.tourItems.size)
        assertEquals(if (items.isEmpty()) tourCovULine(snap.covUPeople) else tourHeading(items, 0), snap.nextAction)
    }
}
