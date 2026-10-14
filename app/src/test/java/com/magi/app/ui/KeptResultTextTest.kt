package com.magi.app.ui

import com.magi.app.ui.KeptResultText.Score
import org.junit.Assert.assertEquals
import org.junit.Test

class KeptResultTextTest {
    @Test fun weightedDecidesWhenTotalDropped() {
        val now = Score(5, 48412.4, 369); val prev = Score(5, 48230.0, 376)
        assertEquals(
            "改善が確認できなかったため、前回の勤務表を維持しました（今回の案は採用していません）。理由：重み付き評価点が高いため（今回 必須5・評価点48,412・合計369／前回 必須5・評価点48,230・合計376。比べる順＝必須→評価点→合計）。",
            KeptResultText.screen(now, prev),
        )
        assertEquals(
            "再実行: 今回 必須5・評価点48,412・合計369 は前回 必須5・評価点48,230・合計376 以下に改善せず（重み付き評価点が高いため）→ 前回を維持",
            KeptResultText.log("再実行", now, prev),
        )
    }

    @Test fun hardThenTotalDecide() {
        assertEquals("必須違反が多いため", KeptResultText.reason(Score(6, 1.0, 1), Score(5, 9.0, 9)))
        assertEquals("違反の合計が多いため", KeptResultText.reason(Score(5, 9.0, 10), Score(5, 9.0, 9)))
        assertEquals("同じ点数のため", KeptResultText.reason(Score(5, 9.0, 9), Score(5, 9.0, 9)))
    }

    @Test fun noteIsAppendedOnlyWhenPresent() {
        assertEquals("A", KeptResultText.withNote("A", null))
        assertEquals("A B", KeptResultText.withNote("A", "B"))
    }

    /** 計算完了の通知は「計算が終わった」と「必須違反 0 件＝配布前確認へ」を分ける（仕様 §9）。 */
    @Test fun completedTextSeparatesDoneFromZeroHard() {
        assertEquals("計算が完了しました。設定した必須条件を満たしています。配布前の確認に進んでください（要調整 12件）", completedText(0, mapOf("c1" to 12), ""))
        assertEquals("計算が完了しました（閉じている間に計算）。必須違反 2件・1人・要調整 34件・6回", completedText(3, mapOf("covU" to 1, "c3n" to 2, "low" to 6, "c1" to 34), "（閉じている間に計算）"))
    }
}
