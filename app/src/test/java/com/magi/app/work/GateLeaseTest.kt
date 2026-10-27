package com.magi.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 置き換えで Worker が重なっても、最後に返した Worker が最初の（画面の）値を戻す。 */
class GateLeaseTest {
    @Test fun overlappingWorkersRestoreTheScreenValues() {
        val lease = GateLease()
        val screen = mapOf<String, Any>("x" to false)
        lease.enter(screen)                                  // 旧 Worker（画面の値を覚える）
        lease.enter(mapOf<String, Any>("x" to true))         // 新 Worker（旧の投入値は覚えない）
        assertNull(lease.exit())                             // 旧が先に終わる＝まだ戻さない
        assertEquals(screen, lease.exit())                   // 新が終わる＝画面の値
        assertNull(lease.exit())                             // 余分な返却は何もしない
    }

    @Test fun singleWorker() {
        val lease = GateLease()
        val screen = mapOf<String, Any>("x" to 1)
        lease.enter(screen)
        assertEquals(screen, lease.exit())
        lease.enter(mapOf<String, Any>("x" to 2))
        assertEquals(mapOf<String, Any>("x" to 2), lease.exit())   // 次の実行は新しく覚える
    }
}
