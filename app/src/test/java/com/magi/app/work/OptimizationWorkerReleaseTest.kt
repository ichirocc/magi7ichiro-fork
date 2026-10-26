package com.magi.app.work

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 背景実行の `OptimizationRepository.running` が、停止・置き換え・完了のどれでも正しく降りること（3.438.0 C1・C-01）。 */
class OptimizationWorkerReleaseTest {

    @After fun tearDown() { OptimizationRepository.clearRunning() }

    /** 通常の停止: 押した側が下ろし、Worker の finally の解除は冪等。 */
    @Test
    fun stopThenWorkerReleaseLeavesNotRunning() {
        val t = OptimizationRepository.holdRunning()
        OptimizationRepository.clearRunning()
        OptimizationRepository.releaseRunning(t)
        assertFalse(OptimizationRepository.running.value)
    }

    /** C-01: 所有権の確認の後・印を立てる前に停止が入っても、Worker 自身の解除で降りる（所有権は失っている）。 */
    @Test
    fun stopBeforeWorkerHoldsStillEndsNotRunning() {
        OptimizationRepository.clearRunning()   // stop() が先に走る
        val t = OptimizationRepository.holdRunning()   // Worker が後から立てる
        assertTrue(OptimizationRepository.running.value)
        OptimizationRepository.releaseRunning(t)   // キャンセル後の finally
        assertFalse(OptimizationRepository.running.value)
    }

    /** 置き換え: 旧実行の解除は、新実行が先に立てていても後に立てていても新実行の印を消さない。 */
    @Test
    fun replacedRunDoesNotReleaseTheNewRunsFlag() {
        val newer = OptimizationRepository.holdRunning()
        val older = OptimizationRepository.holdRunning()   // 旧実行が遅れて立てた
        OptimizationRepository.releaseRunning(older)
        assertTrue(OptimizationRepository.running.value)
        OptimizationRepository.releaseRunning(newer)
        assertFalse(OptimizationRepository.running.value)
    }

    @Test
    fun releasingTwiceOrAnUnknownTokenIsHarmless() {
        val h = RunningHolds()
        val a = h.hold()
        val b = h.hold()
        h.release(a); h.release(a); h.release(999L)
        assertTrue(h.any)
        h.release(b)
        assertFalse(h.any)
    }
}
