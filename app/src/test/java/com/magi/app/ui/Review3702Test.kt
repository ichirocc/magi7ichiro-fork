package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.702.0: 勤務表タブの状態の 1 行（G2-1）・必須違反の一覧の形（G2-3）・セルの確認シート（S-1〜S-6）。 */
class Review3702Test {
    @Test fun stateLineFiveStates() {
        assertEquals("まだ作っていません", scheduleStateLine(false, false, 0, "", "なし", null).text)
        assertEquals("組んでいます", scheduleStateLine(true, true, 3, "3件", "なし", "10/3 山田").text)
        val tour = scheduleStateLine(true, false, 2, "2件", "5回", "10/3 山田")
        assertEquals("必須違反 2件（次：10/3 山田さん）", tour.text)
        assertTrue(tour.hard && tour.tourFirst)
        val covU = scheduleStateLine(true, false, 1, "1人", "5回", null)
        assertEquals("必須違反 1人（日の印から開けます）", covU.text)
        assertTrue(covU.hard); assertFalse(covU.tourFirst)
        val ok = scheduleStateLine(true, false, 0, "0件", "4件・3回", null)
        assertEquals("必須違反はありません · 要調整 4件・3回", ok.text)
        assertFalse(ok.hard || ok.tourFirst)
    }

    @Test fun stateLineIsCapped() {
        val long = scheduleStateLine(true, false, 9, "12件・3人", "なし", "10/13 長谷川太郎")
        assertEquals(SCHEDULE_STATE_MAX, long.text.length)
        assertTrue(long.text.endsWith("…"))
        assertEquals("abc", capStateLine("abc", 3))
        assertEquals("ab…", capStateLine("abcd", 3))
    }

    @Test fun hardListShape() {
        assertEquals("残っている問題 3件・2人", hardListTitle("3件・2人"))
        assertEquals("直し方を見る", HARD_LIST_START_BUTTON)
    }

    @Test fun targetLineAndBadge() {
        assertEquals("このマスの確認", CELL_SHEET_TITLE)
        assertEquals("山田さん · 10/3(金) · A4", cellSheetTarget("山田", "10/3(金)", "A4"))
        assertEquals("山田さん · 10/3(金)", cellSheetTarget("山田", "10/3(金)", null))
        assertEquals("必須", cellSheetBadge(CellSeverity.HARD))
        assertEquals("必須 2/5", cellSheetBadge(CellSeverity.HARD, 1, 5))
        assertEquals("要調整", cellSheetBadge(CellSeverity.SOFT, 1, 5))
        assertNull(cellSheetBadge(CellSeverity.NONE))
    }

    @Test fun peekHasExactlyOnePrimary() {
        // 問題のあるセルの割当では主（全幅の塗り）は「変更案を探す」1 つ。緩める候補・希望を外すは外枠（画面側）。
        for (sev in listOf(CellSeverity.HARD, CellSeverity.SOFT)) assertEquals(PEEK_SEARCH_BUTTON, peekSearchAction(0, sev))
        assertEquals("変更案を探す", PEEK_SEARCH_BUTTON)
        assertNull(peekSearchAction(0, CellSeverity.NONE))
        assertNull(peekSearchAction(1, CellSeverity.SOFT))
    }

    @Test fun shortSideIsPrimaryOnlyWhenAlone() {
        assertTrue(shortSideFilled(listOf("covU")))
        assertFalse(shortSideFilled(listOf("covU", "c3n")))
    }
}
