package com.magi.app.ui

import com.magi.app.ui.KeptResultText.Score
import org.junit.Assert.assertEquals
import org.junit.Test

class KeptResultTextTest {
    @Test fun weightedDecidesWhenTotalDropped() {
        val now = Score(5, 48412.4, 369); val prev = Score(5, 48230.0, 376)
        assertEquals(
            "改善が確認できなかったため、前回の勤務表を維持しました。今回（必須5・重み48,412・合計369）は前回（必須5・重み48,230・合計376）より改善しませんでした（重みが大きいため。比べる順＝必須→重み→合計）。",
            KeptResultText.screen(now, prev),
        )
        assertEquals(
            "再実行: 今回 必須5・重み48,412・合計369 は前回 必須5・重み48,230・合計376 以下に改善せず（重みが大きいため）→ 前回を維持",
            KeptResultText.log("再実行", now, prev),
        )
    }

    @Test fun hardThenTotalDecide() {
        assertEquals("必須が多いため", KeptResultText.reason(Score(6, 1.0, 1), Score(5, 9.0, 9)))
        assertEquals("合計が多いため", KeptResultText.reason(Score(5, 9.0, 10), Score(5, 9.0, 9)))
        assertEquals("同じ点数のため", KeptResultText.reason(Score(5, 9.0, 9), Score(5, 9.0, 9)))
    }

    @Test fun noteIsAppendedOnlyWhenPresent() {
        assertEquals("A", KeptResultText.withNote("A", null))
        assertEquals("A B", KeptResultText.withNote("A", "B"))
    }

    /** 計算完了の通知は「計算が終わった」と「必須違反 0 件＝配布前確認へ」を分ける（仕様 §9）。 */
    @Test fun completedTextSeparatesDoneFromZeroHard() {
        assertEquals("計算が完了しました。登録された必須違反0件・配布前確認へ（違反の合計 12件）", completedText(0, 12, ""))
        assertEquals("計算が完了しました（閉じている間に計算）。必須違反 3件・違反の合計 40件", completedText(3, 40, "（閉じている間に計算）"))
    }
}
