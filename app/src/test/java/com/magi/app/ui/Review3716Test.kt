package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.716.0] 論理監査の是正: 問題一覧の 1 行は名前と日を残して型の側を切る（INV-01）、希望の行の語、禁止の並びは全日をつなぐ、
 * 案なしの 1 行は締切なら時間の行、続けて 0 件は人の操作だけ数える、札は検査済みのときだけ、分析の一文の単位（C# `Review3716Test` と同じ文字列）。
 */
class Review3716Test {
    @Test fun problemLineKeepsPrefixAndTruncatesBody() {
        // 前置き（名前と日）は残し、型の側を「…」で切る＝長い名前でも行の区別がつく。
        assertEquals("福澤俊陽 10/1 希望と違う勤務…", problemLine("pref", "福澤俊陽", "10/1", "A"))
        assertEquals("とても長い名前の職員さん 12/3…", problemLine("pref", "とても長い名前の職員さん", "12/31", "A"))   // 前置きだけで 17 字を超える
        assertEquals("とても長い名前の職員さんです 12…", problemLine("groupViol", "とても長い名前の職員さんです", "12/31", "A"))
        assertEquals("福澤 10/1 希望と違う勤務です", problemLine("pref", "福澤", "10/1", "A"))
        assertEquals("福澤 10/1 拡張希望と違う勤務", problemLine("extWish", "福澤", "10/1", "A"))
        for (f in listOf("covU", "c3w", "low", "high", "c3n", "pref", "extWish", "groupViol", "c41")) {
            val l = problemLine(f, "とても長い名前の職員さんですよ", "12/31", "長い記号の勤務", "長い記号の勤務", 12, listOf("長い記号", "長い記号", "長い記号"))
            assertTrue(l, l.length <= PROBLEM_LINE_MAX)
            if (f != "covU") assertTrue(l, l.startsWith("とても長い名前の職員さんですよ"))
        }
    }

    @Test fun forbiddenRunJoinsAllSymbols() {
        assertEquals("福澤 夜→夜→夜の並び", problemLine("c3n", "福澤", "10/1", "夜", "夜", syms = listOf("夜", "夜", "夜")))
        assertEquals("夜→夜→夜→夜→夜→夜→夜→夜→夜…", problemLine("c3n", "", "10/1", "夜", "夜", syms = List(12) { "夜" }))
        assertEquals("禁止の並びです", problemLine("c3n", "", "10/1", "夜"))
        assertEquals("福澤 禁止の並びです", problemLine("c3n", "福澤", "10/1", "夜", syms = listOf("夜")))
        val item = TourItem("c3n", 0, listOf(0, 1, 2), "禁止の並び 夜→夜→夜 ・ 10/1〜10/3", syms = listOf("夜", "夜", "夜"), dayMd = "10/1")
        assertEquals("福澤 夜→夜→夜の並び", tourProblemLine(item, "福澤"))
        assertEquals("福澤 10/1 希望と違う勤務です", tourProblemLine(TourItem("pref", 0, listOf(0), "希望 A ・ 10/1", syms = listOf("A"), dayMd = "10/1"), "福澤"))
    }

    @Test fun noFixLineSaysTimeWhenTimedOut() {
        assertEquals("時間内に見つかりませんでした", noFixLine(false, timedOut = true))
        assertEquals(NO_FIX_TIMEOUT_LINE, noFixLine(true, timedOut = true))
        assertEquals(REPEATED_EMPTY_LINE, noFixLine(true, timedOut = false))
        assertEquals(NO_FIX_LINE, noFixLine(false))
        assertTrue(NO_FIX_TIMEOUT_LINE.length <= 22)
    }

    @Test fun emptyStreakIgnoresAutoSearches() {
        val a = nextEmptySearchStreak(null, "k|全体|false", 1L, 2L, 0)
        // 自動の探索は数えも途切れさせもしない。
        assertSame(a, nextEmptySearchStreak(a, "k|全体|false", 1L, 2L, 0, auto = true))
        assertSame(a, nextEmptySearchStreak(a, "k|全体|false", 1L, 2L, 3, auto = true))
        assertNull(nextEmptySearchStreak(null, "k|全体|false", 1L, 2L, 0, auto = true))
        assertEquals(2, nextEmptySearchStreak(a, "k|全体|false", 1L, 2L, 0)!!.count)
        // セルのシート（quick）と全面は別の鍵。
        assertEquals(1, nextEmptySearchStreak(a, "k|全体|true", 1L, 2L, 0)!!.count)
    }

    @Test fun badgesOnlyAfterCheckAndCountLineUnits() {
        val plan = homePlan(false, true, true)
        assertFalse(HomeSection.BADGES in homeCardSections(plan, UiState(loaded = true, hasResult = true, engineRan = true, checkRev = 0)))
        assertTrue(HomeSection.BADGES in homeCardSections(plan, UiState(loaded = true, hasResult = true, engineRan = true, checkRev = 1)))
        val made = UiState(loaded = true, hasResult = true, engineRan = true, checkRev = 1, bestHard = 0L, bestSoft = 5L, breakdown = mapOf("c1" to 5))
        assertEquals("必須0件 · 要調整5件", analysisSummary(made, 0, null).sentence)
    }

    @Test fun sameResultKeyedByStateFingerprint() {
        val b = listOf(listOf(0, 1)); val n = SameResultNote(3, 7L, b)
        assertTrue(sameResultVisible(n, 7L, b))
        assertFalse(sameResultVisible(n, 8L, b))
        assertFalse(sameResultVisible(n, 7L, listOf(listOf(1, 1))))
    }
}
