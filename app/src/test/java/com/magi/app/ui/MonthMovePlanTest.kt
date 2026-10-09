package com.magi.app.ui

import com.magi.app.model.ExtWish
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.ManualPin
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.643.0] 月を移す前に、引き継ぐもの（日番号で残る）と消えるもの（日付で持つ拡張希望・期間外）を Ws1Ops.resizeDays と同じ規則で数える。 */
class MonthMovePlanTest {
    private fun state(days: Int = 31) = MagiState(
        startDate = "2026-10-01", endDate = "2026-10-$days",
        shifts = listOf(Shift("休", "休", "", "", role = ShiftRole.Rest), Shift("日勤", "A", "1", "")),
        groups = listOf(Group("一般", "N")), staff = listOf(Staff("甲", 0), Staff("乙", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = List(2) { List(days) { 0 } },
        wishes = mapOf("0,4" to 0, "1,30" to 1), staffRange = emptyMap(),
        needDay1 = mapOf("1,2" to "2", "1,30" to "1"), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
        manualPins = listOf(ManualPin(0, 1, 1), ManualPin(1, 30, 0)),
        extWishes = listOf(ExtWish(0, listOf("2026-10-05", "2026-11-05"), listOf("A")), ExtWish(1, listOf("2026-10-20"), listOf("A"))),
    )

    @Test fun movingToAThirtyDayMonthDropsDateKeyedAndOutOfRangeItems() {
        val p = monthMovePlan(state(), 2026, 11)   // 11 月＝30 日
        assertEquals(30, p.days)
        assertEquals("通常希望は日番号で残る（31 日目の 1 件だけ落ちる）", 1, p.carriedWishes)
        assertEquals(1, p.carriedNeedExceptions); assertEquals(1, p.droppedNeedExceptions)
        assertEquals(1, p.carriedPins); assertEquals(1, p.droppedPins)
        assertEquals("拡張希望は日付で持つ＝10 月の 2 日分は 11 月の期間外", 2, p.droppedExtWishDays)
        assertEquals("乙の件は日が残らず丸ごと消える", 1, p.droppedExtWishes)
        assertTrue(p.needsConfirm)
        val lines = monthMoveLines(p)
        assertTrue(lines[0].startsWith("引き継ぐ: 勤務表の中身（同じ日番号に残ります）・通常希望 1 件"))
        assertTrue(lines[1].startsWith("消える: 期間の外の拡張希望 2 日分"))
    }

    @Test fun emptyStateNeedsNoConfirmation() {
        val st = state().copy(wishes = emptyMap(), needDay1 = emptyMap(), manualPins = emptyList(), extWishes = emptyList())
        val p = monthMovePlan(st, 2026, 11)
        assertFalse(p.needsConfirm)
        assertEquals(listOf("引き継ぐ: 勤務表の中身（同じ日番号に残ります）"), monthMoveLines(p))
    }
}
