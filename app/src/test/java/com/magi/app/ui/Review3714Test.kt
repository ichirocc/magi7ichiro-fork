package com.magi.app.ui

import com.magi.app.v6.CoverageShortfall
import com.magi.app.v6.CoverageVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.714.0] 3.713.0 で見送ったデザイン例の残り: 問題一覧の 1 行（18 字まで）・人員不足の詳しい画面の文字ボタン・希望の前日の行・
 * 案なしの主ボタン・セルのシートのルールの 1 行・設定／条件の見出し・未作成の見出し。決定済みのボタンの語は変えない。
 */
class Review3714Test {
    private val sf = CoverageShortfall(9, "10/10(土)", 0, "Aｱ", need = 1, got = 0, miss = 1, capacity = 3, verdict = CoverageVerdict.FIXABLE, reason = "",
        wishPinned = listOf(4))

    @Test fun problemLinePerFamily() {
        assertEquals("10/10のAｱが1人不足", problemLine("covU", "", "10/10", "Aｱ"))
        assertEquals(shortageHeadline(sf).headline, problemLine("covU", "", "10/10", "Aｱ"))
        assertEquals("福澤 10/1 希望の前日", problemLine("c3w", "福澤", "10/1", "Dﾃ", "休"))
        assertEquals("夜勤があと1回不足", problemLine("low", "", "10/1", "夜勤"))
        assertEquals("福澤 夜勤があと2回不足", problemLine("low", "福澤", "10/1", "夜勤", n = 2))
        assertEquals("福澤 夜勤が1回多い", problemLine("high", "福澤", "10/1", "夜勤"))
        assertEquals("夜勤の翌日が早番です", problemLine("c3n", "", "10/1", "夜勤", "早番"))
        assertEquals("福澤 夜勤の翌日が早番です", problemLine("c3n", "福澤", "10/1", "夜勤", "早番"))
        assertEquals("希望が重なっています", problemLine("pref", "福澤俊陽", "10/1", "A"))
        assertEquals("福澤 10/1 担当外の勤務", problemLine("groupViol", "福澤", "10/1", "A"))
    }

    @Test fun problemLineRowsAndLimits() {
        val items = listOf(TourItem("c3w", 0, listOf(0, 1), "希望の前日の禁止 Dﾃ→休 ・ 10/1〜10/2", syms = listOf("Dﾃ", "休"), dayMd = "10/1"))
        val rows = hardViolationRows(items, listOf("福澤"), emptySet())
        assertEquals("福澤 10/1 希望の前日", rows.single().line)
        assertEquals("残っている問題 4件", hardListTitle("4件"))
        for (f in listOf("covU", "c3w", "low", "high", "c3n", "pref", "extWish", "groupViol", "c41")) {
            val l = problemLine(f, "とても長い名前の職員さん", "12/31", "長い記号の勤務", "長い記号の勤務", 12)
            assertTrue(l, l.length <= PROBLEM_LINE_MAX)
        }
    }

    @Test fun shortageDetailAndWishDayBefore() {
        assertEquals("希望を見る", SHORTAGE_WISH_BUTTON)
        assertEquals("担当を編集", SHORTAGE_SKILL_BUTTON)
        assertEquals(EditLanding(0, null, wishStaff = 4, day = 9), shortageWishLanding(sf))
        assertEquals(EditLanding(2, "yr_ws1"), shortageSkillLanding())
        assertEquals("翌日の休の前に置けない勤務", c3wShortLine(true, "休"))
        assertEquals("前日のDﾃと並べられない希望", c3wShortLine(false, "Dﾃ"))
        assertEquals("希望のまま", WISH_KEEP_BUTTON)
        assertEquals("相談", WISH_CONSULT_SHORT)
        assertEquals("保留", WISH_HOLD_SHORT)
    }

    @Test fun noFixCellSheetTabsAndHome() {
        assertEquals("問題一覧", NO_FIX_LIST_BUTTON)
        assertEquals("閉じる", NO_FIX_CLOSE_BUTTON)
        assertEquals("希望の前日ルールに当たります", cellRuleLine("c3w"))
        assertEquals("詳しく見る", CELL_DETAIL_BUTTON)
        assertEquals("設定", SETTINGS_HEADING)
        assertEquals("組む時間 1分", budgetShortLine(60))
        assertEquals("組む時間 10秒", budgetShortLine(10))
        assertEquals("組む時間を変える", BUDGET_CHANGE_BUTTON)
        assertEquals("条件", EDIT_TAB_HEADING)
        assertEquals("希望・人数・基本", EDIT_TAB_LINE)
        assertEquals("まだ作っていません", homeHeadline(HomePlanKind.MAKE))
        assertEquals("データを開く", EMPTY_STATE_TITLE)
        assertEquals("開く", CONSULT_OPEN)
    }

    @Test fun lengthLimits() {
        for (h in listOf(SETTINGS_HEADING, EDIT_TAB_HEADING, HOME_MAKE_HEADLINE, EMPTY_STATE_TITLE, hardListTitle("12件"))) assertTrue(h, h.length <= 12)
        val fams = listOf("c3n", "c3mn", "c3w", "pref", "extWish", "groupViol", "covU", "covO", "low", "high", "c1", "c41s", "c42s")
        for (l in fams.map { cellRuleLine(it) } + listOf(EDIT_TAB_LINE, budgetShortLine(300), c3wShortLine(true, "Dﾃ"), c3wShortLine(false, "Pｼ"), emptyStateSubtitle(false)))
            assertTrue(l, l.length <= 22)
    }
}
