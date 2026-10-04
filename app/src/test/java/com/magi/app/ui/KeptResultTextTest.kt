package com.magi.app.ui

import com.magi.app.ui.KeptResultText.Score
import org.junit.Assert.assertEquals
import org.junit.Test

class KeptResultTextTest {
    @Test fun weightedDecidesWhenTotalDropped() {
        val now = Score(5, 48412.4, 369); val prev = Score(5, 48230.0, 376)
        assertEquals(
            "今回（必須5・重み48,412・合計369）は前回（必須5・重み48,230・合計376）より改善しませんでした（重みが大きいため。比べる順＝必須→重み→合計）。前回の結果を維持します。",
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
}
