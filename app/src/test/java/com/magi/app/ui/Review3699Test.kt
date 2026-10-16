package com.magi.app.ui

import com.magi.app.v6.FixCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.699.0: 画面精査の是正（日の一覧・門と同じ判定・過剰の題・全部 ⚠ の次の一手・下書きの確認の前後・下書き後の主ボタン）。 */
class Review3699Test {
    // 職員 3 人 × 2 日。勤務 0＝A、1＝休。
    private val board = listOf(listOf(0, 0), listOf(0, 1), listOf(1, 0))
    private val names = listOf("金沢", "山田", "佐藤")
    private val syms = listOf("A", "休")
    private val overDay0 = FixTarget(null, 0, 0, 1, false, "適正人数")

    @Test fun dayListShowsOnlySuggestionsTouchingThatDay() {
        assertTrue(listableFix(null, board, listOf(FixCell(0, 0, 1)), 0))
        assertFalse(listableFix(null, board, listOf(FixCell(0, 1, 1)), 0))
        assertTrue(listableFix(null, board, listOf(FixCell(0, 1, 1)), null))
        assertEquals(3, fixListDay(FixFocus(null, 0, 3)))
        assertNull(fixListDay(FixFocus(1, null, 3)))
        assertNull(fixListDay(FixFocus(null, 0, 3, allDays = true)))
    }

    @Test fun listUsesTheApplyGatePredicate() {
        val removes = listOf(FixCell(0, 0, 1))
        val adds = listOf(FixCell(2, 0, 0))
        assertEquals(improvesTarget(overDay0, board, removes), listableFix(overDay0, board, removes, null))
        assertTrue(listableFix(overDay0, board, removes, 0))
        assertFalse(improvesTarget(overDay0, board, adds))
        assertFalse(listableFix(overDay0, board, adds, 0))
    }

    @Test fun surplusMoveTitleSaysItReducesTheSurplus() {
        assertEquals("金沢さんを A から 休 へ移して過剰を減らす", surplusMoveTitle(overDay0, board, listOf(FixCell(0, 0, 1)), names, syms))
        assertNull(surplusMoveTitle(overDay0.copy(under = true), board, listOf(FixCell(0, 0, 1)), names, syms))
        assertNull(surplusMoveTitle(overDay0, board, listOf(FixCell(2, 0, 0)), names, syms))
        assertNull(surplusMoveTitle(overDay0, board, listOf(FixCell(0, 0, 1), FixCell(1, 0, 1)), names, syms))
    }

    @Test fun allBlockedPeekOffersOneSearch() {
        // 3.702.0 S-3: ちら見の「変更案を探す」は問題のあるセルで常に出す（候補が全部 ⚠ のときだけ、をやめた）。
        assertEquals(PEEK_SEARCH_BUTTON, peekSearchAction(0, CellSeverity.HARD))
        assertNull(peekSearchAction(1, CellSeverity.HARD))      // 希望の登録中
        assertNull(peekSearchAction(0, CellSeverity.NONE))
    }

    @Test fun draftConfirmShowsBeforeAndAfter() {
        assertEquals("今の勤務表を下書きで置き換えます（必須 3件・1人→1件）。元に戻せます", draftConfirmText("3件・1人", "1件"))
        assertEquals("今の勤務表（必須 2件）を下書きで置き換えます。元に戻せます", draftConfirmText("2件"))
        assertEquals("今の勤務表を下書きで置き換えます。元に戻せます", draftConfirmText(null, "1件"))
    }

    @Test fun draftStagePrimaryIsMake() {
        val p = homePlan(false, true, false, draft = true, hasMove = true, guided = true)
        assertEquals(HomePlanKind.DRAFT, p.kind)
        assertEquals(HomeAction.MAKE, p.primary)
        assertTrue(p.primaryEnabled)
        assertEquals(HomePlanKind.DRAFT, homePlan(false, true, true, draft = true).kind)
        assertEquals(HomePlanKind.DATA_INFEASIBLE, homePlan(false, true, false, infeasible = true, draft = true).kind)
        assertEquals(HomePlanKind.RUNNING, homePlan(true, true, false, draft = true).kind)
        val s = homeCardSections(p, UiState(loaded = true, hasResult = true, bestHard = 3L, engineRan = true))
        assertEquals(1, s.count { it == HomeSection.PRIMARY })
        assertEquals("下書きができました（必須 3件）。「勤務表をつくる」で仕上げます", homeDraftStatus("3件", 3L))
    }
}
