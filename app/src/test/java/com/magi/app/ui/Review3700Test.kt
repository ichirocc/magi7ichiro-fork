package com.magi.app.ui

import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.700.0: D03 争点カード（推奨A／案B を既存の主一覧の上位 2 件から）。 */
class Review3700Test {
    // 職員 3 人 × 1 日。勤務 0＝A、1＝休。A は必要 2 人で 1 人不足。
    private val board = listOf(listOf(0), listOf(1), listOf(1))
    private val names = listOf("古泉", "上條", "佐藤")
    private val syms = listOf("A", "休")
    private val under = FixTarget(null, 0, 0, 2, true, "必要人数")
    private fun sug(staff: Int, label: String) = FixSuggestion(FixKind.CHANGE, listOf(FixCell(staff, 0, 0)), label, -1, -1, emptyList())
    private val s1 = sug(1, "上條出勤")
    private val s2 = sug(2, "佐藤出勤")
    private val s3 = sug(1, "上條出勤（別）")
    private fun card(main: List<FixSuggestion>, t: FixTarget = under) = contestCard(t, "10/1のA", main, board, names, syms)

    @Test fun noSuggestionsNoCard() = assertNull(card(emptyList()))

    @Test fun oneSuggestionShowsAOnly() {
        val c = card(listOf(s1))!!
        assertSame(s1, c.a.suggestion)
        assertNull(c.b)
    }

    @Test fun aAndBFollowTheExistingListOrder() {
        val c = card(listOf(s2, s1, s3))!!
        assertSame(s2, c.a.suggestion)
        assertSame(s1, c.b!!.suggestion)
        assertEquals("推奨A: 佐藤出勤", c.a.label)
        assertEquals("案B: 上條出勤", c.b!!.label)
        assertNull(c.b!!.reason)
    }

    @Test fun reasonComesFromTheSuggestionsRecountedEffect() {
        val c = card(listOf(s1, s2))!!
        val e = under.effect(board, s1.ops)
        assertEquals("理由: ${e.afterNote(under.basis)}（${e.gapLine()}）", c.a.reason)
        assertEquals("10/1のAの不足", c.title)
    }

    @Test fun bothTargetTheSameProblemAndNonFixesAreSkipped() {
        val noop = FixSuggestion(FixKind.CHANGE, listOf(FixCell(1, 0, 1)), "変わらない", 0, 0, emptyList())
        val c = card(listOf(noop, s1, s2))!!
        listOf(c.a, c.b!!).forEach { assertTrue(under.effect(board, it.suggestion.ops).fixes) }
        assertNull(card(listOf(s1), FixTarget(null, 0, 0, 0, false, "適正人数")))
    }
}
