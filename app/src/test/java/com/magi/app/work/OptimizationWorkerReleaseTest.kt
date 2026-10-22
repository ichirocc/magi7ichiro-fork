package com.magi.app.work

import org.junit.Assert.assertTrue
import org.junit.Test

/** 停止経路で `OptimizationRepository.running` が降りること（3.438.0/外部レビュー C1、B-03）。 */
class OptimizationWorkerReleaseTest {

    @Test
    fun cancelPathReleasesRunningEvenAfterMarkerIsCleared() {
        val releasedByMe = cancelPathReleases(ownedAtCancel = true)
        // reportClear("停止") が runId マーカーを消した後の finally では、所有権の再判定は必ず false。
        assertTrue(shouldReleaseRunning(releasedByMe) { false })
    }

    @Test
    fun replacedRunDoesNotReleaseTheNewRunsFlag() {
        val releasedByMe = cancelPathReleases(ownedAtCancel = false)
        assertTrue(!shouldReleaseRunning(releasedByMe) { false })
        assertTrue(shouldReleaseRunning(releasedByMe) { true })
    }
}
