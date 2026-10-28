package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 置き換え前の勤務表との比較は必須→重み→合計の辞書式（3.727.0 実機ログ: 元 43337 を下書きに置き換え、作成が 43563 で負けた）。 */
class PreDraftNoticeTest {
    @Test fun lexicographicLikeReportComparator() {
        assertTrue(preDraftBetter(4, 43563.0, 378, 4, 43337.0, 411))    // 重みが軽い元の表のほうが良い（合計は多くても）
        assertFalse(preDraftBetter(4, 42965.0, 403, 4, 43337.0, 411))   // 結果が勝てば知らせない
        assertTrue(preDraftBetter(5, 100.0, 1, 4, 99999.0, 999))        // 必須が先
        assertFalse(preDraftBetter(4, 100.0, 10, 4, 100.0, 10))         // 同じなら良くない
        assertTrue(preDraftBetter(4, 100.0, 10, 4, 100.0, 9))
    }

    @Test fun noticeText() {
        assertEquals("下書きに置き換える前の勤務表のほうが良い結果です（必須 4件・重み 43337）。", preDraftNoticeText("4件", 43337.0))
    }
}
