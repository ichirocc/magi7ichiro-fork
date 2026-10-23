package com.magi.app.ui

import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.V6FinalPort.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.721.2] 終わり方の一文は採用した最終結果の数だけを言う（停滞を検知した時点の盤面の数は入力に無い）。
 * エンジンは `remainingHard = finalReport.hard`、画面は同じ採用レポートの breakdown を渡す。
 */
class StopExplanationBoundaryTest {
    private fun s(kind: StopKind, finalHard: Int, early: Boolean = true) =
        V6FinalPort.StopSummary(earlyStop = early, kind = kind, usedSec = 90, budgetSec = 120, stalledSec = 30, remainingHard = finalHard)

    @Test fun postProcessingReductionIsReflectedInTheLine() {
        // 停滞検知は必須 5 件、後処理で 2 件へ減った＝文は最終の 2 件だけを言う。
        val e = stopExplanationOf(s(StopKind.NORMAL_STALL, 2), mapOf("c3n" to 2))!!
        assertTrue(e.line, e.line.contains("必須違反 2件"))
        assertFalse(e.line, e.line.contains("5件"))
    }

    @Test fun finalBreakdownUnitsWinOverTheCount() {
        val e = stopExplanationOf(s(StopKind.DEADLINE, 3, early = false), mapOf("covU" to 1, "c3n" to 2))!!
        assertTrue(e.line, e.line.contains("残る必須違反は 2件・1人です"))
    }

    @Test fun floorThenPostProcessingStaysConsistentWithFinalCounts() {
        val e = stopExplanationOf(s(StopKind.PLATEAU_FLOOR, 1), mapOf("covU" to 1))!!
        assertTrue(e.line, e.line.contains("残る必須違反 1人"))
        assertEquals(StopNext.REVIEW_STAFFING, e.next)
        val w = stopExplanationOf(s(StopKind.WISH_FLOOR, 1), mapOf("pref" to 1))!!
        assertTrue(w.line, w.line.contains("残る必須違反 1件"))
    }

    @Test fun finalZeroHardShowsNoExplanationForAnyKind() {
        for (k in StopKind.values()) {
            assertNull(k.name, stopExplanationOf(s(k, 0, early = k != StopKind.DEADLINE)))
            assertNull(k.name, stopExplanationOf(s(k, 0, early = k != StopKind.DEADLINE), emptyMap()))
        }
    }
}
