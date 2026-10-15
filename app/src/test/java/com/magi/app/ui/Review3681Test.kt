package com.magi.app.ui

import com.magi.app.model.ExtWish
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import com.magi.app.v6.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.681.0: 不足枠の候補（B4）、確認の画面の対象の前後（B8）、残る差の単位（B11）、止めた探索（B7）。 */
class Review3681Test {
    // 0=休 1=A（1 日目は 2 人要る） 2=D。職員 X,Y,Z（同じ群）。
    private fun state(ext: List<ExtWish> = emptyList(), range: Map<String, Range> = emptyMap(), x: List<Int> = listOf(0, 0, 0)) = MagiState(
        startDate = "2026-07-01", endDate = "2026-07-03",
        shifts = listOf(Shift("休み", "休", "", "", ShiftRole.Rest), Shift("A", "A", "1", ""), Shift("D", "D", "", "")),
        groups = listOf(Group("G0", "G0")), staff = listOf(Staff("X", 0), Staff("Y", 0), Staff("Z", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(x, listOf(0, 0, 0), listOf(1, 1, 1)),
        wishes = emptyMap(), staffRange = range, needDay1 = mapOf("1,0" to "2"), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(), extWishes = ext,
    )
    private fun grid(st: MagiState) = st.schedule.map { it.toIntArray() }.toTypedArray()
    private fun cands(st: MagiState) = shortageCandidates(st, Problem(st), grid(st), 0, 1, 0).map { it.staffIndex }

    @Test fun extBannedStaffIsNotOfferedForTheShortage() {
        assertEquals(listOf(0, 1), cands(state()))
        assertEquals("拡張希望で A を禁止した日の Y は出さない", listOf(0), cands(state(listOf(ExtWish(1, listOf("2026-07-01"), listOf("A"))))))
    }

    @Test fun staffAtTheirUpperLimitIsNotOffered() {
        val st = state(range = mapOf("0,1" to Range("", "1")), x = listOf(0, 1, 0))
        assertEquals(listOf(1), cands(st))
    }

    @Test fun reviewSaysTheTargetsBeforeAndAfter() {
        val st = state()
        val t = FixTarget(null, 1, 0, 2, true, "必要人数")
        val names = st.staff.map { it.name }; val syms = st.shifts.map { it.kigou }
        val r = cellChangeReview(st, grid(st), 0, 0, 1, names, syms, null, null, false, listOf(t))
        assertEquals("人員不足（${DayText.short(st.startDate, 0)}のA）：1人 → 2人", r.thisCell.first())
        val none = cellChangeReview(st, grid(st), 0, 2, 2, names, syms, null, null, false)
        assertEquals(listOf(NO_CELL_VIOLATION), none.thisCell)
    }

    @Test fun remainingGapUsesTheTargetsUnit() {
        val multi = FixTarget(null, 1, null, 0, true, "必要人数", listOf(0, 1), listOf(2, 2))
        assertEquals("A（2日）は、合計 3人不足です", remainingGapLine("A（2日）", multi, 3))
        assertEquals("Aは、下限まであと2回です", remainingGapLine("A", FixTarget(0, 1, null, 3, true, "下限"), 2))
    }

    @Test fun cancelledSearchIsItsOwnPanelState() {
        assertEquals(FixPanelState.CANCELLED, fixPanelState(false, false, "", "", "k", cancelledKey = "k"))
        assertEquals("探し直しが始まれば探索中", FixPanelState.RUNNING, fixPanelState(false, true, "", "", "k", cancelledKey = "k"))
        assertTrue(FIX_SEARCH_RETRY_BUTTON == "もう一度探す")
    }
}
