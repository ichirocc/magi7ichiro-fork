package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.720.0] 編集タブ② 職員管理・年間マスターの見出し（editHeaderPlan scope1/scope2）・体制の行・回数のシートのボタン・問題一覧の選んだ行。
 */
class Review3720Test {
    private val ready = EditHeaderCounts(month = "2026年11月", staff = 15, shifts = 4, rules = 12, ranges = 3, shortShifts = 1)

    @Test fun staffHeaderIsTitleStatusAndAddStaff() {
        val p = editHeaderPlan(1, ready, panelOpen = false, ranBeforeEdit = false, running = false)
        assertEquals("職員管理", p.title)
        assertEquals(listOf("職員 15人"), p.points)
        assertEquals(EditHeaderPrimary.ADD_STAFF, p.primary)
        assertTrue(editBarSecondary(p))
        assertNull(p.notice)
        assertEquals(EditHeaderPrimary.RERUN, editHeaderPlan(1, ready, false, true, false).primary)
    }

    @Test fun masterHeaderHasThreeOneUnitPointsAndOpenMaster() {
        val p = editHeaderPlan(2, ready, panelOpen = false, ranBeforeEdit = false, running = false)
        assertEquals("年間マスター", p.title)
        assertEquals("制度が変わったときだけ", p.subtitle)
        assertEquals(listOf("ルール 12件", "ひとりの回数の上限・下限 3件", "担当が足りないシフト 1種類"), p.points)
        assertEquals(EditHeaderPrimary.OPEN_MASTER, p.primary)
        assertNull(p.notice)
        assertTrue(p.title.length <= 12)
        p.points.forEach { assertTrue(it.length <= 22) }
    }

    @Test fun masterHeaderEmptyNotice() {
        assertEquals(EDIT_MASTER_EMPTY_NOTE, editHeaderPlan(2, ready.copy(shifts = 0), false, false, false).notice)
        assertEquals(EDIT_MASTER_EMPTY_NOTE, editHeaderPlan(2, ready.copy(staff = 0), false, false, false).notice)
        assertNull(editHeaderPlan(1, ready.copy(staff = 0), false, false, false).notice)
    }

    @Test fun staffingRowUsesPeopleOnly() {
        assertEquals("担当5人・1日最大3人・余裕2人", staffingRowText(5, 3))
        assertEquals("担当3人・1日最大3人・欠勤の余裕なし", staffingRowText(3, 3))
        assertEquals("担当2人・1日最大3人・1人足りない", staffingRowText(2, 3))
        assertTrue(!staffingRowText(5, 3).contains("回") && !staffingRowText(5, 3).contains("人日"))
    }

    @Test fun countSheetButtonsSayKai() {
        assertEquals("上限を6回に上げて適用", raiseCapButton(6))
        assertEquals("下限を2回に下げて適用", lowerFloorButton(2))
    }

    @Test fun problemListPicksTappedRowNotFirstOfCell() {
        val rows = listOf(
            ProblemRow("c3n", 0, listOf(2), "a", true),
            ProblemRow("covU", 0, listOf(2), "b", true),
            ProblemRow("c1", 1, listOf(4), "c", false),
        )
        assertEquals(1, problemPickedIndex(rows, 1, 0 to 2))
        assertEquals(0, problemPickedIndex(rows, null, 0 to 2))
        assertEquals(2, problemPickedIndex(rows, 1, 1 to 4))
        assertNull(problemPickedIndex(rows, 1, null))
        assertNull(problemPickedIndex(rows, null, 9 to 9))
    }
}
