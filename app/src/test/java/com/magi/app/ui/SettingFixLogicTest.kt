package com.magi.app.ui

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import com.magi.app.v6.SettingFixAction
import com.magi.app.v6.V6SanityPort
import com.magi.app.v6.c3SeqKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingFixLogicTest {
    /** X は A が上限 0（入れない指定）、Y は制限なし。A の必要数は need1/need2。 */
    private fun demandState(
        days: Int, need1: String = "2", need2: String = "", use2: Boolean = false,
        wishes: Map<String, Int> = emptyMap(), nd1: Map<String, String> = emptyMap(), nd2: Map<String, String> = emptyMap(),
    ) = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-%02d".format(days),
        shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("A", "A", need1, need2)),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("X", 0), Staff("Y", 0)), use2Patterns = use2,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(List(days) { 0 }, List(days) { 0 }), wishes = wishes,
        staffRange = mapOf("0,1" to Range("0", "0")),
        needDay1 = nd1, needDay2 = nd2,
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun capIssues(s: MagiState) = V6SanityPort.buildGuidance(s).filter { it.action == SettingFixAction.CAP_DEMAND }

    /** 直して再診断、を繰り返して残りが 0 になるまで。 */
    private fun fixAll(s0: MagiState): MagiState {
        var s = s0
        repeat(40) {
            val i = capIssues(s).firstOrNull() ?: return s
            s = SettingFixLogic.apply(s, i) ?: error("fix was a no-op for ${i.where}")
        }
        error("did not converge")
    }

    @Test fun dateSpecificShortageWritesOnlyThatDaysException() {
        val s = demandState(days = 2, wishes = mapOf("0,0" to 1))   // 0日目は X も A に固定＝足りる。1日目だけ不足
        val issues = capIssues(s)
        assertEquals(1, issues.size)
        assertEquals(1, issues[0].demandDayIdx)
        val ns = SettingFixLogic.apply(s, issues[0])!!
        assertEquals("標準の必要数は他の日のために動かさない", "2", ns.shifts[1].need1)
        assertEquals("1", ns.needDay1["1,1"])
        assertNull(ns.needDay1["1,0"])
        assertEquals(0, capIssues(ns).size)
    }

    @Test fun existingExceptionIsLoweredInPlace() {
        val s = demandState(days = 2, wishes = mapOf("0,0" to 1), nd1 = mapOf("1,1" to "3"))
        val ns = SettingFixLogic.apply(s, capIssues(s).single())!!
        assertEquals("2", ns.shifts[1].need1)
        assertEquals("1", ns.needDay1["1,1"])
        assertEquals(0, capIssues(ns).size)
    }

    @Test fun shortageOnEveryDayIsOneMonthWideIssueThatLowersTheStandard() {
        val s = demandState(days = 3)
        val issues = capIssues(s)
        assertEquals(1, issues.size)
        assertNull(issues[0].demandDayIdx)
        val ns = SettingFixLogic.apply(s, issues[0])!!
        assertEquals("1", ns.shifts[1].need1)
        assertTrue(ns.needDay1.isEmpty())
        assertEquals(0, capIssues(ns).size)
    }

    @Test fun monthWideIsNotUsedWhenADayHasItsOwnException() {
        val s = demandState(days = 3, nd1 = mapOf("1,1" to "2"))
        val issues = capIssues(s)
        assertEquals(3, issues.size)
        assertTrue(issues.all { it.demandDayIdx != null })
        assertEquals(0, capIssues(fixAll(s)).size)
    }

    @Test fun secondPatternIsWrittenOnlyWhenUsedAndAboveCap() {
        val s = demandState(days = 2, need1 = "2", need2 = "3", use2 = true, wishes = mapOf("0,0" to 1))
        val ns = SettingFixLogic.apply(s, capIssues(s).single())!!
        assertEquals("1", ns.needDay1["1,1"])
        assertEquals("1", ns.needDay2["1,1"])
        assertEquals("2", ns.shifts[1].need1)
        assertEquals("3", ns.shifts[1].need2)
        assertEquals(0, capIssues(ns).size)
        val blank2 = demandState(days = 2, need1 = "2", need2 = "", use2 = true, wishes = mapOf("0,0" to 1))
        assertNull(SettingFixLogic.apply(blank2, capIssues(blank2).single())!!.needDay2["1,1"])
        val off = demandState(days = 2, need1 = "2", need2 = "3", use2 = false, wishes = mapOf("0,0" to 1))
        assertNull(SettingFixLogic.apply(off, capIssues(off).single())!!.needDay2["1,1"])
    }

    // ---- DELETE_DUP_SEQ ----
    private fun seqState(n3n: List<List<String>>) = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-04",
        shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("A", "A", "", ""), Shift("R", "R", "", "")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("X", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(listOf(0, 0, 0, 0)), wishes = emptyMap(), staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = n3n.map { C3Row(it) }, cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun dupIssues(s: MagiState) = V6SanityPort.buildGuidance(s).filter { it.action == SettingFixAction.DELETE_DUP_SEQ }

    @Test fun duplicateRowsWithAGapAreDeleted() {
        val s = seqState(listOf(listOf("A", "", "R"), listOf("A", "", "R")))
        val issue = dupIssues(s).single()
        assertEquals("A", issue.seqKey)
        val ns = SettingFixLogic.apply(s, issue)!!
        assertEquals(1, ns.cons3n.size)
        assertEquals(0, dupIssues(ns).size)
    }

    @Test fun gapRowIsNotCompactedIntoAnotherRule() {
        val s = seqState(listOf(listOf("A", "", "R"), listOf("A", "R"), listOf("A", "R")))
        val issue = dupIssues(s).single()
        assertEquals("A→R", issue.seqKey)
        val ns = SettingFixLogic.apply(s, issue)!!
        assertEquals(listOf(listOf("A", "", "R"), listOf("A", "R")), ns.cons3n.map { it.pattern })
        assertEquals(0, dupIssues(ns).size)
    }

    @Test fun deleteIsNullWhenNothingMatches() {
        val s = seqState(listOf(listOf("A", "R")))
        val issue = V6SanityPort.buildGuidance(seqState(listOf(listOf("A", "R"), listOf("A", "R")))).single { it.action == SettingFixAction.DELETE_DUP_SEQ }
        val ns = SettingFixLogic.apply(seqState(listOf(listOf("A", "", "R"))), issue.copy(seqKey = "A→R"))
        assertNull(ns)
        assertNotNull(SettingFixLogic.apply(s, issue))
    }

    @Test fun sequenceKeyTruncatesAtFirstBlank() {
        assertEquals("A", c3SeqKey(listOf("A", "", "R")))
        assertEquals("A→R", c3SeqKey(listOf("A", "R")))
        assertEquals("", c3SeqKey(listOf("", "R")))
    }
}
