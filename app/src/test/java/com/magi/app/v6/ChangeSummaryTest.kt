package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSummaryTest {
    @Test fun countsChangedStaffCellsWishesAndRanges() {
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-03",
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "", "")),
            groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0), Staff("s1", 0), Staff("s2", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = List(3) { List(3) { 0 } }, wishes = mapOf("0,0" to 1, "1,2" to 0), staffRange = mapOf("2,1" to Range("", "1")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val before = arrayOf(intArrayOf(0, 0, 0), intArrayOf(0, 0, 0), intArrayOf(0, 0, 0))
        val after = arrayOf(intArrayOf(1, 1, 0), intArrayOf(0, 0, 0), intArrayOf(1, 1, 0))   // s0: 2 セル、s2: 2 セル（A×2 > 上限 1）
        val s = ChangeSummary.of(st, before, after, UnifiedViolationChecker.check(st, after))
        assertEquals(2, s.changedStaff); assertEquals(4, s.changedCells)
        assertEquals(2, s.wishTotal); assertEquals(2, s.wishKept)   // s0 day0=A ✓、s1 day2=休 ✓
        assertEquals(false, s.rangeAllOk)
        assertEquals(1, s.rangeOutStaff)   // s2 だけが上限超過
        assertEquals("変更：2人・4か所／希望どおり 2/2件", s.line())
        assertEquals(-1, s.familyDeltas["pref"]); assertEquals(1, s.familyDeltas["high"])   // s0 の希望が通り、s2 が上限超過
        assertEquals("減った：pref 1件", s.familyLine().substringBefore("／"))
        assertTrue(s.familyLine { if (it == "high") "上限超過" else it }.substringAfter("／").startsWith("増えた：上限超過 1件・fair 6件"))   // 加重順: 上限超過 +1(=50) が先頭、fair +6(=30) が続く（2026-10-30 high 50。3.647.0〜は fair が先頭）
    }

    @Test fun familyLineOrdersByWeightedImpactAndReportsEmptySides() {
        val line = ChangeSummary.familyLine(mapOf("weekly" to 3, "c1" to -1, "c3mn" to -2, "fair" to 4))
        assertEquals("減った：c1 1件・c3mn 2件／増えた：fair 4件・weekly 3件", line)
        assertEquals("減った：なし／増えた：なし", ChangeSummary.familyLine(emptyMap()))
    }

    @Test fun lineSaysAllWithinRangeOrFallsBackWhenStaffNotCounted() {
        assertEquals("変更：0人・0か所／希望どおり 0/0件", ChangeSummary(0, 0, 0, 0, true).line())
        assertEquals("変更：0人・0か所／希望どおり 0/0件", ChangeSummary(0, 0, 0, 0, false).line())
    }
}
