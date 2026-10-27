package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 代行役（AX）のカードの文: 何もしなかった輪は出さない・必須が動けば必須を、動かなければ要調整を言う。 */
class AgentTextTest {
    private fun rec(n: Int, hb: Int, ha: Int) = AgentRecord(List(n) { "手$it" }, hb, ha, "10件", "8件", "当てられる手が尽きた")

    @Test fun headline() {
        assertNull(agentHeadline(rec(0, 3, 3)))
        assertEquals("代行で 2 手を当てました（必須違反 3→1）", agentHeadline(rec(2, 3, 1)))
        assertEquals("代行で 1 手を当てました（要調整 10→8件）", agentHeadline(rec(1, 1, 1)))
        assertEquals("代行で 1 手を当てました", agentNotice(rec(1, 1, 1)))
        assertNull(agentNotice(rec(0, 1, 1)))
    }

    @Test fun softHeadPicksFirstMovedUnit() {
        assertEquals("147→140件", softHeadDelta("147件・3人・80回・283pt", "140件・4人・70回・277pt"))
        assertEquals("80→70回", softHeadDelta("3人・80回", "3人・70回"))
        assertEquals("3→3人", softHeadDelta("3人", "3人"))
        assertEquals("5→0件", softHeadDelta("5件", "なし"))
        assertEquals("なし→2件", softHeadDelta("なし", "2件"))
        assertEquals("代行をやめる", stopButtonLabel(false, true, true))
        assertEquals("やめる", stopButtonLabel(true, true, true))
        assertEquals(CANCEL_FIX_SEARCH_BUTTON, stopButtonLabel(false, true, false))
    }


    @Test fun pending() {
        assertEquals(emptyList<String>(), agentPendingItems(0, false, 0))
        assertNull(agentPendingLine(emptyList()))
        assertEquals("判断待ち：希望の見直し 2件・設定を緩める候補・設定ミスの直し 3件", agentPendingLine(agentPendingItems(2, true, 3)))
        assertEquals("判断待ち：設定ミスの直し 1件", agentPendingLine(agentPendingItems(0, false, 1)))
    }

    @Test fun edgeCounts() {
        assertEquals(emptyList<String>(), agentPendingItems(-1, false, -3))   // 負の数は判断待ちに数えない
        assertEquals("代行で 12 手を当てました（必須違反 9999→0）", agentHeadline(rec(12, 9999, 0)))
    }

    @Test fun homeDiagnosisCountAllCards() {
        val u = UiState(copilotHint = "x", observedPinBlockedAttempts = 1)
        assertEquals(2, homeDiagnosisCount(u))
    }
}
