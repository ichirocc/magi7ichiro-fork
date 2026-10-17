package com.magi.app.ui

import com.magi.app.v6.CoverageShortfall
import com.magi.app.v6.CoverageVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.713.0] 利用者のデザイン例（2026-10-17）に合わせた短い骨格: 見出し 12 字以内・説明 1 行 22 字以内・必須の札は残りの件数だけ。
 * 決定済みのボタンの語（「この変更を適用する」「今は変更しない」など）は変えない。
 */
class Review3713Test {
    private val sf = CoverageShortfall(9, "10/10(土)", 0, "Aｱ", need = 1, got = 0, miss = 1, capacity = 3, verdict = CoverageVerdict.FIXABLE, reason = "")

    @Test fun homeHeadingPillAndProblemLine() {
        assertEquals("今月の判断", homeHeadline(HomePlanKind.LIST, "2026-10-01"))
        assertEquals("必須 残り4件", homeHardPill(4L, "4件"))
        assertEquals("必須は満たしています", homeHardPill(0L, "0件"))
        assertEquals("10/10のAｱが1人不足", shortageHeadline(sf).headline)
        assertEquals("配置 0 / 必要 1", shortageHeadline(sf).status)
        assertEquals("担当・希望の確認", SHORTAGE_TAG)
        assertEquals("必須を見る", HOME_LIST_BUTTON)
        assertEquals("配布前の確認", HOME_PRE_DIST_BUTTON)
        assertEquals("表で全体を見る", HOME_SCHEDULE_HELPER)
        assertEquals("相談中 3件", consultLine(3))
    }

    @Test fun dialogsAndEmptyStates() {
        assertEquals("変更の確認", PREVIEW_TITLE)
        assertEquals("2か所変わります", previewCountLine(2))
        assertEquals("山本  10/15  休 → 遅番", previewRowLine("山本", "10/15", "休", "遅番"))
        assertEquals("案はありません", NO_FIX_TITLE)
        assertEquals("設定か希望の確認が必要です", noFixLine(false))
        assertEquals(REPEATED_EMPTY_LINE, noFixLine(true))
        assertEquals("作成が終わりました", CREATION_DONE_TITLE)
        assertEquals("必須3件 · 要調整12件", analysisCountLine("3件", "12件"))
        // 決定済みの語は据え置き。
        assertEquals("この変更を適用する", APPLY_CHANGE_BUTTON)
        assertEquals("今は変更しない", KEEP_CURRENT_BUTTON)
    }

    @Test fun lengthLimits() {
        for (h in listOf(HOME_MONTH_HEADLINE, PREVIEW_TITLE, NO_FIX_TITLE, CREATION_DONE_TITLE, CELL_SHEET_TITLE)) assertTrue(h, h.length <= 12)
        for (l in listOf(HOME_HARD0_HEADLINE, shortageHeadline(sf).headline, shortageHeadline(sf).status, NO_FIX_LINE, REPEATED_EMPTY_LINE,
            previewCountLine(12), consultLine(10)!!, homeHardPill(4L, "4件"))) assertTrue(l, l.length <= 22)
    }
}
