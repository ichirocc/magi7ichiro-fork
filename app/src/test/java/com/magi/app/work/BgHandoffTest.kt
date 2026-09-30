package com.magi.app.work

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 背景実行の結果を画面へ渡す間の、保存・再確認・編集ガード（外部レビュー P1-a/P1-c/P2）。 */
class BgHandoffTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun recoveryFilesSurviveWhenSavingTheAppliedStateFails() = runBlocking {
        val f = RunFiles(tmp.root)
        f.result.writeText("result")
        f.snapshot.writeText("snap")
        val cleared = clearAfterSaved({ false }) { f.clear() }
        assertFalse(cleared)
        assertTrue("保存に失敗したら結果を残す", f.result.exists())
        assertTrue(f.snapshot.exists())
    }

    @Test
    fun recoveryFilesAreClearedOnlyAfterTheSaveReturns() = runBlocking {
        val f = RunFiles(tmp.root)
        f.result.writeText("result")
        val order = ArrayList<String>()
        val cleared = clearAfterSaved({ order += "save:" + f.result.exists(); true }) { order += "clear"; f.clear() }
        assertTrue(cleared)
        assertEquals(listOf("save:true", "clear"), order)
        assertFalse(f.result.exists())
    }

    @Test
    fun anEditMadeWhileWaitingForEvaluationInvalidatesTheResult() {
        val board = arrayOf(intArrayOf(0, 1), intArrayOf(2, 3))
        val state = Any()
        val snap = BgApplySnapshot(7L, state, 99L, board)
        board[0][0] = 5   // 盤面を配列のまま書き換える編集
        assertFalse(snap.unchanged(7L, state, 99L, board))
        board[0][0] = 0
        assertTrue(snap.unchanged(7L, state, 99L, board))
        assertFalse("実行の世代が変わった", snap.unchanged(8L, state, 99L, board))
        assertFalse("入力が変わった", snap.unchanged(7L, state, 100L, board))
        assertFalse("状態が差し替わった", snap.unchanged(7L, Any(), 99L, board))
    }

    @Test
    fun theGuardClosesAtEnqueueAndOnlyItsOwnRunOpensIt() {
        val g = BgEditGuard()
        assertFalse(g.closed)
        g.begin(10L)
        assertTrue("投入した時点で閉じる（Worker の開始を待たない）", g.closed)
        g.end(9L)
        assertTrue("置き換えられた旧実行の終了では開かない", g.closed)
        g.end(0L)
        assertTrue(g.closed)
        g.end(10L)
        assertFalse(g.closed)
        g.begin(11L); g.release()
        assertFalse("停止・失敗で開く", g.closed)
    }
}
