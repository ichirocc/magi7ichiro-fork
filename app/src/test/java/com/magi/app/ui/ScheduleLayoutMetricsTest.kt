package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 勤務表タブの縦の寸法（行数）・下部バーの種別・タブ入場スクロールの純ロジックを固定する。 */
class ScheduleLayoutMetricsTest {
    private val m = ScheduleLayoutMetrics

    @Test fun `merged bar is 64dp and replaces the 60 plus 80 stack`() {
        assertEquals(64, m.MERGED_BAR_DP)
        assertEquals(80, m.COMMAND_BAR_DP)
        assertEquals(220, m.bottomStackDp(after = false, sheetOpen = false))
        assertEquals(144, m.bottomStackDp(after = true, sheetOpen = false))
        assertEquals(80, m.bottomStackDp(after = true, sheetOpen = true))
        assertEquals(144, m.bottomStackDp(after = true, sheetOpen = true, running = true))
    }

    @Test fun `tap targets and row height stay at 48dp`() {
        assertEquals(48, m.ROW_DP)
        assertEquals(48, m.ICON_BUTTON_DP)
        assertTrue(m.COMMAND_BUTTON_DP >= 60)
    }

    private fun rows(h: Int, after: Boolean, sheet: Int, top: Boolean, entry: Boolean = false): Int {
        val above = if (!top) 0 else if (entry) m.bodyAboveAtEntryAfter() else m.bodyAboveAtTop(after)
        return m.visibleRows(h, m.bottomStackDp(after, sheet > 0), sheet, above)
    }

    @Test fun `visible rows before and after at 915dp and 740dp`() {
        // 画面 915: scrolled / no sheet
        assertEquals(11, rows(915, false, 0, false)); assertEquals(13, rows(915, true, 0, false))
        // peek 144 / peek 238
        assertEquals(8, rows(915, false, m.SHEET_PEEK_DP, false)); assertEquals(11, rows(915, true, m.SHEET_PEEK_DP, false))
        assertEquals(6, rows(915, false, m.SHEET_PEEK_TOUR_RECOMMEND_DP, false)); assertEquals(9, rows(915, true, m.SHEET_PEEK_TOUR_RECOMMEND_DP, false))
        // 画面 740
        assertEquals(8, rows(740, false, 0, false)); assertEquals(9, rows(740, true, 0, false))
        assertEquals(5, rows(740, false, m.SHEET_PEEK_DP, false)); assertEquals(8, rows(740, true, m.SHEET_PEEK_DP, false))
        // tab entry (scroll 0 before, scrolled to grid top after)
        assertEquals(4, rows(915, false, 0, true)); assertEquals(12, rows(915, true, 0, true, entry = true))
        assertEquals(1, rows(740, false, 0, true)); assertEquals(9, rows(740, true, 0, true, entry = true))
    }

    @Test fun `exact gain is about 1_6 rows without a sheet and 2_9 with a peek sheet`() {
        assertEquals(1.58, (220 - 144) / 48.0, 0.01)
        assertEquals(2.92, ((220 - 80)) / 48.0, 0.01)
    }

    @Test fun `bar mode by tab sheet running and violation nav`() {
        assertEquals(BottomBarMode.NONE, bottomBarMode(false, true, false, false, false))
        assertEquals(BottomBarMode.COMMAND, bottomBarMode(true, false, false, false, false))
        assertEquals(BottomBarMode.COMMAND, bottomBarMode(true, false, true, true, true))
        assertEquals(BottomBarMode.MERGED_WEEK, bottomBarMode(true, true, false, false, false))
        assertEquals(BottomBarMode.MERGED_VIOLATION, bottomBarMode(true, true, false, false, true))
        assertEquals(BottomBarMode.HIDDEN, bottomBarMode(true, true, true, false, false))
        assertEquals(BottomBarMode.HIDDEN, bottomBarMode(true, true, true, false, true))
        // 最適化中は「やめる」を持つバーを必ず残す（シートが開いていても。違反ナビはシート側の前/次を使う）
        assertEquals(BottomBarMode.MERGED_WEEK, bottomBarMode(true, true, true, true, true))
        assertEquals(BottomBarMode.MERGED_VIOLATION, bottomBarMode(true, true, false, true, true))
    }

    @Test fun `scroll to grid top only on entering the schedule tab`() {
        assertTrue(shouldScrollToGridTop(prevTab = 0, tab = 1, loaded = true, sheetOpen = false, focusPending = false))
        assertTrue(shouldScrollToGridTop(prevTab = -1, tab = 1, loaded = true, sheetOpen = false, focusPending = false))
        assertFalse(shouldScrollToGridTop(prevTab = 1, tab = 1, loaded = true, sheetOpen = false, focusPending = false))
        assertFalse(shouldScrollToGridTop(prevTab = 0, tab = 2, loaded = true, sheetOpen = false, focusPending = false))
        assertFalse(shouldScrollToGridTop(prevTab = 0, tab = 1, loaded = false, sheetOpen = false, focusPending = false))
        assertFalse(shouldScrollToGridTop(prevTab = 0, tab = 1, loaded = true, sheetOpen = true, focusPending = false))
        assertFalse(shouldScrollToGridTop(prevTab = 0, tab = 1, loaded = true, sheetOpen = false, focusPending = true))
    }

    @Test fun `week range caption for the grid corner`() {
        assertEquals("10/1" to "〜7", weekRangeCaption("2026-10-01", 0, 6))
        assertEquals("10/29" to "〜11/4", weekRangeCaption("2026-10-01", 28, 34))
        assertEquals("12/28" to "〜1/3", weekRangeCaption("2026-12-01", 27, 33))
        assertNull(weekRangeCaption("bad", 0, 6))
    }
}
