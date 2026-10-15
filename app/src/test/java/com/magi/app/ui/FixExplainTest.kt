package com.magi.app.ui

import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 付随の変更の理由（1 つずつ外して正式な検査で数え直す）と、必須違反が変わらない案の添え書き。 */
class FixExplainTest {
    private val st = FixTiersTest.board12x16()
    private val s = FixTiersTest.sched(st)

    @Test fun splitsPrimaryInsideTheEntryFrame() {
        val ops = listOf(FixCell(0, 1, FixTiersTest.REST), FixCell(0, 3, FixTiersTest.D))
        val (p, x) = splitPrimary(FixTiersTest.over, st.schedule, ops)
        assertEquals(listOf(ops[0]), p); assertEquals(listOf(ops[1]), x)
    }

    @Test fun extraReasonComesFromTheFamilyThatGrowsWithoutIt() {
        val swap = FixSuggestion(FixKind.SWAP_XDAY, listOf(FixCell(0, 1, FixTiersTest.REST), FixCell(0, 3, FixTiersTest.D)), "swap", -1, -1, emptyList())
        val e = explainExtras(st, s, FixTiersTest.over, swap)
        assertTrue(e.allExplained)
        assertTrue(!e.mainlyOutOfFrameHard)
        // D の 2 連続（金沢 12/15→12/16）がついでに直る。
        assertTrue(e.incidental.toString(), e.incidental.any { "金沢さん" in it })
        val lines = extraReasonLines(e, st.schedule, st.staff.map { it.name }, st.shifts.map { it.kigou }, st.startDate)
        assertTrue(lines.toString(), lines.first().startsWith("合わせて変える勤務：金沢さん ") && lines[1].startsWith("　理由：入れないと"))
    }

    @Test fun hardUnchangedLinesOnlyWhenRequiredRemain() {
        val soft = FixSuggestion(FixKind.CHANGE, emptyList(), "x", 0, 0, listOf("covO" to -1, "c3" to 1))
        assertEquals(listOf("必須違反は変わりません（要調整だけが変わります）", "件数は同じで、重い要調整が減ります"), hardUnchangedLines(soft, true))
        assertTrue(hardUnchangedLines(soft, false).isEmpty())
        assertTrue(hardUnchangedLines(soft.copy(deltaHard = -1), true).isEmpty())
    }
}
