package com.magi.app.work

import org.junit.Assert.assertFalse
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

    @Test
    fun stopBetweenOwnershipCheckAndSettingRunningDoesNotLeaveItSet() {
        // 所有を確かめて実行中を立てた直後に停止（マーカー消去）→ 停止経路は所有者でない。誰も所有していないので降ろす。
        val releasedByMe = cancelPathReleases(ownedAtCancel = false)
        assertTrue(releaseRunningAtEnd(releasedByMe, setByMe = true, ownsNow = { false }, activeRunId = { 0L }))
    }

    @Test
    fun replacedRunKeepsTheNewRunsFlagEvenIfItSetRunning() {
        assertFalse(releaseRunningAtEnd(false, setByMe = true, ownsNow = { false }, activeRunId = { 42L }))
    }

    @Test
    fun runThatNeverSetRunningDoesNotClearAnotherRunsFlag() {
        assertFalse(releaseRunningAtEnd(false, setByMe = false, ownsNow = { false }, activeRunId = { 0L }))
        assertTrue(releaseRunningAtEnd(true, setByMe = false, ownsNow = { false }, activeRunId = { 7L }))
    }
}
