package com.magi.app.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WorkManager の enqueue が後から失敗したときの片付けの判定（3.592.0、M-02）。 */
class BgEnqueueFailureTest {

    @Test
    fun asyncEnqueueFailureCleansUpOnlyTheCurrentRun() {
        assertTrue(asyncEnqueueFailureCleansUp(failed = true, currentRunId = 7L, runId = 7L))
        assertFalse("置き換え済みの新しい実行には触れない", asyncEnqueueFailureCleansUp(failed = true, currentRunId = 8L, runId = 7L))
        assertFalse("片付け済み（bgRunId=0）なら二重に片付けない", asyncEnqueueFailureCleansUp(failed = true, currentRunId = 0L, runId = 7L))
        assertFalse(asyncEnqueueFailureCleansUp(failed = false, currentRunId = 7L, runId = 7L))
    }
}
