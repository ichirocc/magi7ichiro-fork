package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.719.0] 視覚弱点者対応 第1便（拡大・読み上げ・コントラスト）: 文字拡大で 1 行の文を 2 行にする閾値、ホームの主ボタンの字数は倍率で縮む、
 * 勤務表の寸法は 1.3× まで不変・それ以上は比例、要調整のアンバーは白地と #E5E5E5 で 3:1 以上、見分けにくいセル地の輪郭の判定
 * （C# `Review3719Test` と同じ数）。
 */
class Review3719Test {
    @Test fun maxLinesGrowsAtLargeFontScale() {
        assertEquals(1, a11yMaxLines(1.0f))
        assertEquals(1, a11yMaxLines(1.15f))
        assertEquals(2, a11yMaxLines(1.3f))
        assertEquals(2, a11yMaxLines(2.0f))
        assertEquals(3, a11yMaxLines(1.3f, base = 2))
    }

    @Test fun homeTargetLabelShrinksWithFontScale() {
        // 18 字 ÷ 倍率。1.3× は 13 字まで＝「アリ 10/8の希望を見る」（13 字）は入り、「アリフ 10/8の希望を見る」（14 字）は元の文言に戻る。
        assertEquals("アリフ 10/8の希望を見る", homeTargetLabel(HOME_WISH_BUTTON, "アリフ", "10/8"))
        assertEquals("アリ 10/8の希望を見る", homeTargetLabel(HOME_WISH_BUTTON, "アリ", "10/8", 1.3f))
        assertEquals(HOME_WISH_BUTTON, homeTargetLabel(HOME_WISH_BUTTON, "アリフ", "10/8", 1.3f))
        assertEquals("10/8のなおし方を見る", homeTargetLabel("なおし方を見る", null, "10/8", 1.0f))
        assertEquals("なおし方を見る", homeTargetLabel("なおし方を見る", null, "10/8", 2.0f))   // 9 字まで
    }

    @Test fun scheduleMetricsKeepSevenDaysUntilLargeScale() {
        assertEquals(1f, ScheduleLayoutMetrics.growth(1.0f))
        assertEquals(1f, ScheduleLayoutMetrics.growth(1.3f))
        assertEquals(48, ScheduleLayoutMetrics.rowDp(1.3f))
        assertEquals(36, ScheduleLayoutMetrics.cellMinDp(1.3f))
        // 2.0× は 1.3× からの比率（≈1.54）で広がる＝390dp の端末（内容幅 326−56）に 7 日は収まらず、見える日が減る。
        assertEquals(74, ScheduleLayoutMetrics.rowDp(2.0f))
        assertEquals(111, ScheduleLayoutMetrics.dayHeaderDp(2.0f))
        assertEquals(55, ScheduleLayoutMetrics.cellMinDp(2.0f))
        assertEquals(74, ScheduleLayoutMetrics.cellMaxDp(2.0f))
        assertTrue((326 - 56) / 7 < ScheduleLayoutMetrics.cellMinDp(2.0f))
        assertTrue((326 - 56) / 7 >= ScheduleLayoutMetrics.cellMinDp(1.3f))
        // 3.0× でも 2.0× で止める。
        assertEquals(ScheduleLayoutMetrics.rowDp(2.0f), ScheduleLayoutMetrics.rowDp(3.0f))
        assertTrue(ScheduleLayoutMetrics.visibleRows(915, 140, 144, 60, fontScale = 2.0f) < ScheduleLayoutMetrics.visibleRows(915, 140, 144, 60))
    }

    @Test fun amberMeetsUiContrastOnWhiteAndLightGray() {
        assertEquals("#B86E00", ACCENT_ORANGE_HEX)
        assertEquals(ACCENT_ORANGE_HEX, SOFT_VIO_DEFAULT_HEX)
        assertTrue(contrastRatio(ACCENT_ORANGE_HEX, "#FFFFFF") >= 3.0f)
        assertTrue(contrastRatio(ACCENT_ORANGE_HEX, "#E5E5E5") >= 3.0f)
        assertEquals(3.99f, contrastRatio(ACCENT_ORANGE_HEX, "#FFFFFF"), 0.02f)
        assertEquals(3.16f, contrastRatio(ACCENT_ORANGE_HEX, "#E5E5E5"), 0.02f)
        // 旧値は足りなかった（記録のため）。
        assertTrue(contrastRatio("#E08A1E", "#FFFFFF") < 3.0f)
        assertTrue(contrastRatio("#F59E0B", "#FFFFFF") < 3.0f)
        assertEquals(21f, contrastRatio("#000000", "#FFFFFF"), 0.01f)
    }

    @Test fun outlineOnlyWhenCellBlendsIntoSurface() {
        val surface = wcagLuminance("#FBFDFC")
        assertTrue(lowContrastOutline(wcagLuminance("#FFFFFF"), surface))
        assertTrue(lowContrastOutline(wcagLuminance("#E5E5E5"), surface))   // 1.25:1
        assertTrue(lowContrastOutline(wcagLuminance("#FFEECA"), surface))
        assertFalse(lowContrastOutline(wcagLuminance("#B86E00"), surface))
        assertFalse(lowContrastOutline(wcagLuminance("#8A979B"), surface))
        assertFalse(lowContrastOutline(wcagLuminance("#C68B0C"), surface))
    }
}
