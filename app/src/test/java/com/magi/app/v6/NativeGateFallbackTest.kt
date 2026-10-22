package com.magi.app.v6

import com.magi.app.model.StateParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-13（`docs/overhaul_design.md` §6.2）。2 層番兵の判定と、ゲートが閉じた後に Kotlin が正しい結果を返すこと。
 *  ホストは `.so` を読めないので C++ チャンク自体は走らない（実機と native-parity の受け持ち）。 */
class NativeGateFallbackTest {
    @After fun reopenGate() { NativeGate.resetForTest(); NativeGate.parityCheckEnabled = true }

    @Test fun chunkIsConsistentOnlyWithStatusZeroAndEnoughFields() {
        assertFalse(NativeGate.chunkConsistent(null, 6))
        assertFalse(NativeGate.chunkConsistent(LongArray(5), 6))
        assertFalse(NativeGate.chunkConsistent(longArrayOf(1, 0, 0, 0, 0, 0), 6))
        assertTrue(NativeGate.chunkConsistent(LongArray(6), 6))
        assertTrue(NativeGate.chunkConsistent(LongArray(5), 5))
    }

    @Test fun parityReportsTheKotlinScoreOnlyOnMismatch() {
        val before = TuningTelemetry.parityChecks.get()
        assertNull(NativeGate.parityMismatch(100L) { 100L })
        assertEquals(99L, NativeGate.parityMismatch(100L) { 99L })
        assertEquals(before + 2, TuningTelemetry.parityChecks.get())
    }

    @Test fun parityOffTrustsNativeWithoutEvaluating() {
        NativeGate.parityCheckEnabled = false
        val before = TuningTelemetry.parityChecks.get()
        assertNull(NativeGate.parityMismatch(100L) { throw AssertionError("照合 OFF で Kotlin 評価を呼んだ") })
        assertEquals(before, TuningTelemetry.parityChecks.get())
    }

    @Test fun mismatchClosesTheGateAndKotlinStillReturnsAVerifiedResult() {
        val st = StateParser.parse(javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText())!!
        val p = Problem(st)
        val ev = Evaluator(p)
        // 呼び出し元と同じ形: 照合が食い違ったらゲートを閉じる。
        NativeGate.parityMismatch(1L) { 2L }?.let { k -> NativeGate.disable("Kotlin照合NG (native=1 kotlin=$k)") }
        assertFalse(NativeGate.enabled)
        assertFalse(NativeGate.usable)
        assertEquals("Kotlin照合NG (native=1 kotlin=2)", NativeGate.reason)

        val init = p.initialAssignment()
        val r = runBlocking { SaOptimizer(p, ev).run(SaParams(budgetMs = 300L, workers = 1, seed = 7L)) }
        assertEquals("退化後の結果のスコアは Kotlin のフル評価と一致する", ev.fullEval(r.schedule), r.score)
        assertTrue("退化後も入力より悪くしない", r.score <= ev.fullEval(init))
        assertFalse("一度閉じたゲートは実行で開かない", NativeGate.enabled)
    }
}
