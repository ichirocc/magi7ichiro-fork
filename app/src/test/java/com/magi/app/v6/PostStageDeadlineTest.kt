package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-9（`docs/overhaul_detailed_spec.md`）: 後処理チェーンの各段が締切を守る。本番と同じく締切を `shouldStop` に畳んで
 * `runPostOptimization` を呼び、(a) 締切が既に過ぎていれば各段がすぐ返り、盤面は入力より悪くならない、
 * (b) 締切が近くても全体が予算＋一定の後始末の範囲に収まる、を実データの fixture で確かめる。
 * HF67 単体の締切は `Hf67DeadlineTest`、予算の式は `TimeBudgetTest`、探索全体の超過は `BudgetOverrunTest`。
 */
class PostStageDeadlineTest {
    private val fixtures = listOf("golden_state", "sept2026_state")

    private fun load(name: String) = StateParser.parse(
        javaClass.getResourceAsStream("/$name.json")?.bufferedReader()?.readText().also { assertNotNull(it) }!!)!!

    private fun run(name: String, budgetMs: Long): Triple<ViolationReport, V6PostOptimizationResult, Long> {
        val st = load(name)
        val s = st.schedule.toIntArray2D()
        val input = UnifiedViolationChecker.check(st, s)
        val deadline = EngineClock.nowMs() + budgetMs
        val t0 = EngineClock.nowMs()
        val r = V6HotfixPasses.runPostOptimization(st, s, "test", seed = 1L,
            shouldStop = { EngineClock.nowMs() >= deadline }, deadlineMs = deadline)
        return Triple(input, r, EngineClock.nowMs() - t0)
    }

    /** (a) 締切が過ぎている: 段ごとの所要は短く、全体もすぐ返り、結果は入力に負けない（keep-best）。 */
    @Test fun expiredDeadlineReturnsPromptlyAndKeepsTheInput() {
        for (name in fixtures) {
            val (input, r, elapsed) = run(name, budgetMs = -1L)
            assertTrue("$name: 締切超過なのに ${elapsed}ms", elapsed < 3_000L)
            r.stageRecords.forEach { assertTrue("$name: 段 ${it.key} が締切後に ${it.ms}ms 走った", it.ms < 1_000L) }
            assertTrue("$name: 必須が増えた ${input.hard}→${r.report.hard}", r.report.hard <= input.hard)
            assertFalse("$name: 結果が入力より悪い", betterReport(input, r.report))
        }
    }

    /** (b) 締切が近い（1.5 秒）: 全体が予算＋後始末（最終検査・HF70・段の途中の 1 手）の範囲に収まり、段の合計も経過を超えない。 */
    @Test fun nearDeadlineStaysWithinBudgetPlusCleanup() {
        val budget = 1_500L
        for (name in fixtures) {
            val (input, r, elapsed) = run(name, budget)
            assertTrue("$name: 予算 ${budget}ms に対し ${elapsed}ms", elapsed <= budget + 3_000L)
            val sum = r.stageRecords.sumOf { it.ms }
            assertTrue("$name: 段の合計 ${sum}ms が経過 ${elapsed}ms を超えた", sum <= elapsed + 50L)
            assertFalse("$name: 結果が入力より悪い", betterReport(input, r.report))
        }
    }
}
