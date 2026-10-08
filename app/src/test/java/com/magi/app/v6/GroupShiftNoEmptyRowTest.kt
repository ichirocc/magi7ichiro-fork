package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 外部レビュー CFG-01: 休の役割が無い設定でも、担当できるシフトが無い群を作らない。 */
class GroupShiftNoEmptyRowTest {
    private fun state() = MagiState(
        startDate = "2026-07-01", endDate = "2026-07-02",
        shifts = listOf(Shift("A", "A", "1", ""), Shift("B", "B", "1", "")),   // 休の役割なし
        groups = listOf(Group("G0", "G0"), Group("G1", "G1")), staff = listOf(Staff("s0", 0), Staff("s1", 1)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1), listOf(1, 0)), groupShiftApt = listOf(listOf("", ""), listOf("", "")),
        schedule = listOf(listOf(0, 0), listOf(0, 0)), wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun rowOffWithoutRestIsRejected() {
        val st = state()
        assertSame(st, Ws1Ops.setGroupShiftRow(st, 0, false))
        assertEquals(listOf(listOf(1, 1), listOf(1, 1)), Ws1Ops.setGroupShiftRow(st, 1, true).groupShift)
    }

    @Test fun singleCellOffKeepsAtLeastOneShift() {
        val st = state()
        assertSame(st, Ws1Ops.setGroupShift(st, 1, 0, false))                       // G1 は A だけ
        assertEquals(listOf(listOf(0, 1), listOf(1, 0)), Ws1Ops.setGroupShift(st, 0, 0, false).groupShift)
    }

    @Test fun columnOffIsRejectedWhenAnyGroupWouldBeEmpty() {
        val st = state()
        assertSame(st, Ws1Ops.setGroupShiftColumn(st, 0, false))                    // G1 が空になる
        assertEquals(listOf(listOf(1, 0), listOf(1, 0)), Ws1Ops.setGroupShiftColumn(st, 1, false).groupShift)
    }
}
