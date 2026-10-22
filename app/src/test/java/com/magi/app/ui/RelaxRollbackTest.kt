package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RelaxRollbackTest {
    private val before = UiState(hasResult = false, engineRan = true, ranBeforeEdit = false, relaxedBoard = true, structureEdited = false,
        runSummary = "前後", schedule = listOf(listOf(0, 1)), canUndo = false, canRedo = true, editRev = 7,
        stalledHardFamilies = listOf("covU"), alternatives = listOf("案1"), alternativeApplied = 0, fixDoneKey = "k")

    @Test fun restoreReturnsEveryFieldTheApplyChanged() {
        val rb = RelaxRollback(before, listOf("u1"), listOf("r1"), "res", "alts")
        val applied = before.copy(hasResult = true, engineRan = false, ranBeforeEdit = true, relaxedBoard = true, structureEdited = true,
            runSummary = null, stopSummary = null, schedule = listOf(listOf(1, 1)), canUndo = true, canRedo = false, editRev = 8,
            stalledHardFamilies = emptyList(), alternatives = emptyList(), alternativeApplied = -1, fixDoneKey = "", messageIsError = true)
        assertEquals(before, rb.restore(applied))
    }

    @Test fun restoreKeepsUnrelatedFields() {
        val rb = RelaxRollback(before, emptyList<String>(), emptyList(), null, Unit)
        val cur = before.copy(budgetSec = before.budgetSec + 5, editRev = 9)
        assertEquals(before.budgetSec + 5, rb.restore(cur).budgetSec)
    }

    @Test fun restoreStacksBringsBackDroppedOldestAndClearedRedo() {
        val undo = (1..30).map { "u$it" }
        val rb = RelaxRollback(before, undo, listOf("r1", "r2"), null, Unit)
        val u = ArrayDeque(undo.drop(1) + "new"); val r = ArrayDeque<String>()
        rb.restoreStacks(u, r)
        assertEquals(undo, u.toList())
        assertEquals(listOf("r1", "r2"), r.toList())
    }
}
