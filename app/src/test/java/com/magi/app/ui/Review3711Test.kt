package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自動の案探しの記録、前回と同じ結果、続けて 0 件の案内（C# `Review3711Test` と同じ文字列）。 */
class Review3711Test {
    @Test fun autoSearchLinesAreDistinct() {
        assertEquals("案を探す（自動）: 全体", OpLogText.fixSearchStartLine(OpLogText.FIX_ORIGIN_ALL, auto = true))
        assertEquals("案（自動）: 2件（3秒）", OpLogText.fixSearchEndLine(2, 3_500L, auto = true))
        assertEquals("案（自動）: なし（1秒）", OpLogText.fixSearchEndLine(0, 1_000L, auto = true))
        assertEquals("案: なし（1秒）", OpLogText.fixSearchEndLine(0, 1_000L))
    }

    @Test fun hesitationSkipsAutoSearches() {
        val msgs = listOf(
            OpLogText.fixSearchStartLine(OpLogText.FIX_ORIGIN_ALL, auto = true), OpLogText.fixSearchEndLine(3, 1_000L, auto = true),
            OpLogText.fixSearchStartLine(OpLogText.FIX_ORIGIN_ALL, auto = true), OpLogText.fixSearchEndLine(0, 1_000L, auto = true),
            OpLogText.fixSearchStartLine("11/3のAｱ"), OpLogText.fixSearchEndLine(0, 2_000L),
        )
        val h = OpLogText.hesitation(msgs, msgs.indices.map { it * 1_000L })
        assertEquals(1, h.searches); assertEquals(0, h.searchesWithResult); assertEquals(1, h.searchesEmpty)
    }

    @Test fun sameResultNoticeAndVisibility() {
        assertEquals("前回と同じ結果です。条件を変えない限り、この必須違反3件は消えません。", sameResultNotice(3))
        assertEquals("前回と同じ結果です。", sameResultNotice(0))
        assertFalse(isSameRunResult(null, 1L to 2L))
        assertTrue(isSameRunResult(1L to 2L, 1L to 2L))
        assertFalse(isSameRunResult(1L to 2L, 1L to 3L))
        assertFalse(isSameRunResult(1L to 2L, 9L to 2L))
        val b = listOf(listOf(0, 1)); val n = SameResultNote(3, 4, b)
        assertTrue(sameResultVisible(n, 4, b))
        assertFalse(sameResultVisible(n, 5, b))
        assertFalse(sameResultVisible(n, 4, listOf(listOf(1, 1))))
        assertFalse(sameResultVisible(null, 4, b))
        assertEquals("同じ結果（盤面指紋 00000000000000ab）", OpLogText.sameResultLine(0xabL))
    }

    @Test fun emptyStreakCountsOnlySameOriginAndBoard() {
        val a = nextEmptySearchStreak(null, "k|全体", 1L, 2L, 0)
        assertEquals(1, a!!.count)
        assertEquals(2, nextEmptySearchStreak(a, "k|全体", 1L, 2L, 0)!!.count)
        assertEquals(1, nextEmptySearchStreak(a, "j|11/3", 1L, 2L, 0)!!.count)
        assertEquals(1, nextEmptySearchStreak(a, "k|全体", 5L, 2L, 0)!!.count)
        assertEquals(1, nextEmptySearchStreak(a, "k|全体", 1L, 6L, 0)!!.count)
        assertNull(nextEmptySearchStreak(a, "k|全体", 1L, 2L, 1))
        assertEquals(RepeatedEmptyAction.WISHES, repeatedEmptyAction(true, true))
        assertEquals(RepeatedEmptyAction.SETTINGS, repeatedEmptyAction(false, true))
        assertNull(repeatedEmptyAction(false, false))
        assertEquals("この対象には、いまの条件で直せる変更案がありません。", REPEATED_EMPTY_LINE)
    }
}
