package com.magi.app.ui

import com.magi.app.work.SaveGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 機構の穴 2（`docs/safety_inventory.md`）。VM の自動保存・`retrySave`（＝`saveNow`）が呼ぶ書き込み本体
 *  `SaveGate.writeTextIfLatest` を実ファイルで通す: 書けない間は失敗、書ける状態に戻った「もう一度保存する」で
 *  最新の本文が入り、通知は復旧になる。 */
class SaveRetryFileTest {
    @Test fun retryRewritesTheRealFileAfterAFailedSave() {
        val root = Files.createTempDirectory("save-retry").toFile()
        try {
            val dir = File(root, "files")   // まだ無い＝一時ファイルが作れず書き込みが失敗する
            val target = File(dir, "magi_autosave.json")
            val gate = SaveGate()
            val n = SaveFailNotifier()
            var gen = 0

            assertEquals(false, gate.writeTextIfLatest(++gen, target, "{\"v\":1}"))
            val failed = n.report(ok = false, retried = false)
            assertEquals(SaveFailDialog.FIRST, failed.dialog)
            assertEquals(SaveNotice.FAILED, failed.notice)
            assertFalse(target.exists())

            assertTrue(dir.mkdirs())
            assertEquals(true, gate.writeTextIfLatest(++gen, target, "{\"v\":2}"))
            val recovered = n.report(ok = true, retried = true)
            assertEquals(SaveNotice.RECOVERED, recovered.notice)
            assertNull(recovered.dialog)
            assertEquals("{\"v\":2}", target.readText())
            assertEquals(listOf("magi_autosave.json"), dir.list()!!.toList())   // 一時ファイルの残骸なし

            // 再試行より前に始まった古い自動保存が後から終わっても、書き直した内容を戻さない。
            assertNull(gate.writeTextIfLatest(1, target, "{\"v\":1}"))
            assertEquals("{\"v\":2}", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun failedRetryLeavesThePreviousFileIntact() {
        val root = Files.createTempDirectory("save-retry").toFile()
        try {
            val target = File(root, "magi_autosave.json")
            val gate = SaveGate()
            assertEquals(true, gate.writeTextIfLatest(1, target, "old"))
            // 置き換え先がディレクトリ＝rename も直接書きも失敗する。例外は失敗に畳まれ、元の内容は残る。
            val blocked = File(root, "blocked").apply { mkdirs() }
            assertEquals(false, gate.writeTextIfLatest(2, blocked, "new"))
            assertEquals("old", target.readText())
            assertTrue(blocked.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
