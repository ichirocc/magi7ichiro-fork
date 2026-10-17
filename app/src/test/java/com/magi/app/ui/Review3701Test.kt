package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.701.0: 保存の失敗を知らせる（上部の語・ダイアログ・主カードの状態の行）と、上部の札の狭い幅。 */
class Review3701Test {
    @Test fun topBarSaveWordOnlyWhenFailed() {
        assertEquals("保存できていません", topBarSaveWord(SaveState.Failed))
        assertNull(topBarSaveWord(SaveState.Saved))
        assertNull(topBarSaveWord(SaveState.Saving))
        assertNull(topBarSaveWord(SaveState.Dirty))
    }

    @Test fun narrowTopBarWrapsBelow396dp() {
        assertTrue(topBarStatusWrapped(395))
        assertFalse(topBarStatusWrapped(396))
    }

    /** 書き込みを失敗させる保存先（fake）。 */
    private class FakeStore(var failing: Boolean) { fun write(): Boolean = !failing }

    @Test fun dialogShownOnceWhenSaveTurnsFailed() {
        val store = FakeStore(failing = false)
        val n = SaveFailNotifier()
        assertNull(n.onResult(store.write(), retried = false))
        store.failing = true
        assertEquals(SaveFailDialog.FIRST, n.onResult(store.write(), retried = false))
        // 1.2 秒ごとの自動保存が失敗し続けても出し直さない。
        assertNull(n.onResult(store.write(), retried = false))
        assertNull(n.onResult(store.write(), retried = false))
        // 利用者のやり直しも失敗したら「まだ保存できていません」。
        val again = n.onResult(store.write(), retried = true)
        assertEquals(SaveFailDialog.AGAIN, again)
        assertEquals("まだ保存できていません", saveFailDialogTitle(again!!))
        assertEquals("自動保存できませんでした", saveFailDialogTitle(SaveFailDialog.FIRST))
        store.failing = false
        assertNull(n.onResult(store.write(), retried = true))
        assertTrue(n.lastOk)
        store.failing = true
        assertEquals(SaveFailDialog.FIRST, n.onResult(store.write(), retried = false))
    }

    @Test fun saveFailedLineInHomeStatusRegardlessOfStage() {
        for (engineRan in listOf(true, false)) {
            val ui = UiState(loaded = true, hasResult = true, bestHard = 0L, engineRan = engineRan, saveState = SaveState.Failed)
            assertTrue(HomeSection.STATUS in homeCardSections(homePlan(false, true, true), ui))
        }
        val pre = homeStatusLines(HomePlanKind.PRE_DIST, null, "未確認事項 1 件", "勤務表CSVは今の内容と違います", saveFailed = true)
        assertEquals(listOf(HOME_SAVE_FAILED_LINE, "未確認事項 1 件"), pre)
        val list = homeStatusLines(HomePlanKind.LIST, "必須違反が 2件 残っています。", "未確認事項 1 件", null, saveFailed = true)
        assertEquals(2, list.size)
        assertEquals(HOME_SAVE_FAILED_LINE, list[0])
        assertFalse(HOME_SAVE_FAILED_LINE in homeStatusLines(HomePlanKind.MAKE, "", null, null))
        assertTrue(homeStatusLines(HomePlanKind.MAKE, "", null, null, saveFailed = true) == listOf(HOME_SAVE_FAILED_LINE))
    }

    @Test fun csvFailureReopensPreDistribution() {
        assertTrue(csvExportReopensPreDist(false))
        assertFalse(csvExportReopensPreDist(true))
    }
}
