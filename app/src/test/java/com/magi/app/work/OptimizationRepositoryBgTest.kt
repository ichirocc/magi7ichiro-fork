package com.magi.app.work

import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.ViolationReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [外部レビュー R6・N6] 背景結果の受け取りの順序と、Worker へ渡す計算条件。 */
class OptimizationRepositoryBgTest {
    private fun result(): OptimizationRepository.BgResult = OptimizationRepository.BgResult(
        schedule = arrayOf(intArrayOf(0)),
        report = ViolationReport(emptyMap(), emptyMap(), emptyMap(), breakdown = emptyMap(), total = 0, hard = 0, soft = 0, weightedScore = 0.0),
        phase = "test",
        runId = 1L,
    )

    @After fun tearDown() { OptimizationRepository.clear() }

    /** 旧: 購読は起動直後。復元前（state=null）に受けて捨てた結果は、StateFlow が再送しないので二度と来ない。 */
    @Test fun resultIgnoredBeforeRestoreIsNeverRedeliveredByAPlainCollector() = runBlocking {
        OptimizationRepository.publishResult(result())
        val restored = MutableStateFlow(false)
        val got = mutableListOf<OptimizationRepository.BgResult>()
        val job = launch { OptimizationRepository.result.collect { r -> if (r != null && restored.value) got += r } }
        yield()
        restored.value = true
        repeat(5) { yield() }
        job.cancel()
        assertTrue("旧来の購読は復元後に結果を受け取れない（R6 の再現）", got.isEmpty())
    }

    @Test fun resultPublishedBeforeRestoreIsDeliveredOnceRestoreCompletes() = runBlocking {
        val r = result()
        OptimizationRepository.publishResult(r)
        val restored = MutableStateFlow(false)
        val got = mutableListOf<OptimizationRepository.BgResult>()
        val job = launch { OptimizationRepository.collectResultsAfter(restored) { got += it } }
        repeat(5) { yield() }
        assertTrue("復元前に受け取っている", got.isEmpty())
        restored.value = true
        withTimeout(5_000) { while (got.isEmpty()) yield() }
        job.cancel()
        assertSame(r, got.single())
    }

    @Test fun dropResultLeavesANewerResultAlone() {
        val old = result()
        val newer = result()
        OptimizationRepository.publishResult(newer)
        OptimizationRepository.dropResult(old)
        assertSame("捨てた結果の後に公開された結果を消した", newer, OptimizationRepository.result.value)
        OptimizationRepository.dropResult(newer)
        assertNull(OptimizationRepository.result.value)
    }

    /** 方式・仕上げ最適化も inputData を往復して Worker に届く。 */
    @Test fun runConfigCarriesAlgorithmAndSoftPolishThroughInputData() {
        for (alg in V6Algorithm.entries) for (polish in listOf(true, false)) {
            val cfg = OptimizationRepository.RunConfig(seconds = 300, workers = 8, softPolish = polish, algorithm = alg)
            assertEquals(cfg, OptimizationRepository.RunConfig.fromInput(cfg.toInput()))
        }
    }

    /** この版より前に投入された Work（鍵は seconds/workers/runId だけ）は従来どおりの条件で走る。 */
    @Test fun legacyInputKeepsFormerConditions() {
        // 旧 VM と同じく Int と Long を混ぜる（mapOf の型推論だと整数リテラルが Long に寄るので put で入れる）。
        val legacy = HashMap<String, Any?>().apply { put("seconds", 120); put("workers", 6); put("runId", 5L) }
        val cfg = OptimizationRepository.RunConfig.fromInput(legacy)
        assertEquals(OptimizationRepository.RunConfig(120, 6, softPolish = false, algorithm = V6Algorithm.AUTO), cfg)
        val empty = OptimizationRepository.RunConfig.fromInput(mapOf("seconds" to 0, "algorithm" to "NO_SUCH"))
        assertEquals(OptimizationRepository.seconds, empty.seconds)
        assertEquals(OptimizationRepository.workers, empty.workers)
        assertEquals(V6Algorithm.AUTO, empty.algorithm)
    }
}
