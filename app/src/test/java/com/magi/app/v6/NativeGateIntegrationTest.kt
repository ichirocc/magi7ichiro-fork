package com.magi.app.v6

import com.magi.app.model.StateParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `docs/safety_inventory.md` 機構の穴 1: 実際の SA の呼び出し経路で、ネイティブの誤りを番兵が見つけて NativeGate を閉じ、
 * Kotlin へ退化して正しい結果を返す。`.so` の代わりに [NativeGate.saSeamForTest] へ偽のチャンクを差し込む。
 * 判定だけの単体は `NativeGateFallbackTest`（T-13）。
 */
class NativeGateIntegrationTest {
    @After fun restore() { NativeGate.saSeamForTest = null; NativeGate.resetForTest(); NativeGate.parityCheckEnabled = true }

    private val st = StateParser.parse(javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText())!!
    private val p = Problem(st)
    private val ev = Evaluator(p)

    private fun run(seam: NativeSaSeam): SaResult {
        NativeGate.resetForTest()
        NativeGate.saSeamForTest = seam
        return runBlocking { SaOptimizer(p, ev).run(SaParams(budgetMs = 400L, workers = 1, seed = 7L)) }
    }

    private fun assertVerifiedFallback(r: SaResult) {
        assertFalse("ゲートが閉じていない", NativeGate.enabled)
        assertEquals("結果のスコアは Kotlin のフル評価と一致する", ev.fullEval(r.schedule), r.score)
        assertTrue("入力より悪くしない", r.score <= ev.fullEval(p.initialAssignment()))
    }

    /** 2 層目の番兵: 偽のネイティブが盤面を変えずに「良くなった」と嘘のスコアを返す → Kotlin 照合で不一致 → 閉じて退化。 */
    @Test fun parityMismatchOnTheRealSaPathClosesTheGateAndFallsBackToKotlin() {
        val calls = AtomicInteger()
        val r = run(NativeSaSeam { _, _, bestScore, _ -> calls.incrementAndGet(); longArrayOf(0L, 0L, bestScore - 1, 100L, 0L, 0L) })
        assertTrue("偽のチャンクが呼ばれていない＝ネイティブ経路を通っていない", calls.get() >= 1)
        assertTrue("理由: ${NativeGate.reason}", NativeGate.reason?.startsWith("Kotlin照合NG") == true)
        assertVerifiedFallback(r)
    }

    /** 1 層目の番兵: 偽のネイティブが status≠0（自己整合 NG）を返す → 閉じて退化。 */
    @Test fun inconsistentChunkOnTheRealSaPathClosesTheGateAndFallsBackToKotlin() {
        val r = run(NativeSaSeam { _, _, _, _ -> longArrayOf(1L, 0L, 0L, 0L, 0L, 0L) })
        assertTrue("理由: ${NativeGate.reason}", NativeGate.reason?.startsWith("SAチャンク整合性NG") == true)
        assertVerifiedFallback(r)
    }

    /** 正直なネイティブ（改善なしでスコアをそのまま返す）なら、ゲートは開いたままでネイティブ経路を回り切る。 */
    @Test fun honestChunkKeepsTheGateOpen() {
        val calls = AtomicInteger()
        val r = run(NativeSaSeam { _, _, bestScore, _ -> calls.incrementAndGet(); longArrayOf(0L, 0L, bestScore, 100L, 0L, 0L) })
        assertTrue(calls.get() >= 1)
        assertTrue("ゲートが閉じた: ${NativeGate.reason}", NativeGate.enabled)
        assertEquals(ev.fullEval(r.schedule), r.score)
    }
}
