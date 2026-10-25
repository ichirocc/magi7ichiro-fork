package com.magi.app.v6

import com.magi.app.model.StateParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/**
 * [3.511.3, backlog #14(g)] 「内部ループが 100〜250ms ごとに停止判定、300 秒設定で実時間 305 秒以内、
 * 停止要求から 5 秒以内、例外ワーカーで固まらない」を回帰として固定する。
 *
 * [StopPropagationTest] は `shouldStop={true}` を最初から渡すため、ループへ一度も入らずに戻る＝
 * 入口ガードの検証にしかならない。ここでは実際にループへ入ってから (a) 予算が自然に切れたときの超過幅、
 * (b) 計算中に外部停止要求が来てから戻るまでの時間、(c) 仮説ワーカーが例外を投げても制御が戻ること、
 * の3つを別々に固定する（300秒→1秒への比例縮小は下記の理由で不採用＝一定オーバーヘッドが埋もれる）。
 */
class BudgetOverrunTest {
    private fun goldenState() =
        StateParser.parse(javaClass.classLoader!!.getResource("golden_state.json")!!.readText())!!

    /** [3.511.3] 300秒→1秒のような比例縮小はしない: 予算満了後の後始末（hf67HardRepair・checker・
     *  ワーカーjoin）はbudgetMsにほぼ比例しない一定コストのため、縮小すると許容枠に対して過大に見える。
     *  1秒(=Int最小の totalBudgetSec)の予算でも一定コストの範囲(3秒)に収まることを確認する。
     *
     *  [3.511.3/発見→2026-10-25 M-04 で解消] RSI_PLUS はフェーズ下限 10/10/10/5（合計 35 秒）が予算を超え、
     *  1・10・30 秒の予算でいずれも約 36 秒走っていた。下限の合計が予算を超えるときだけ予算の締切でも止めるようにしたので、
     *  他の方式と同じ枠で見る（按分そのものは `RsiPlusPhasePlanTest`）。 */
    @Test
    fun naturalBudgetExpiryOverrunStaysBounded() = runBlocking {
        val st = goldenState()
        val initial = Array(st.schedule.size) { st.schedule[it].toIntArray() }
        val budgetMs = 1_000L
        val overrunToleranceMs = 3_000L
        val algorithms = listOf(V6Algorithm.V5, V6Algorithm.ALNS, V6Algorithm.RSI, V6Algorithm.PORTFOLIO, V6Algorithm.RSI_PLUS)
        for (alg in algorithms) {
            val t0 = EngineClock.nowMs()
            val res = V6NativeOptimizer.optimize(
                st, Array(initial.size) { initial[it].clone() },
                V6OptimizerOptions(algorithm = alg, totalBudgetSec = 1, workers = 4, seed = 12345L),
                shouldStop = { false },
            )
            val elapsed = EngineClock.nowMs() - t0
            assertTrue("$alg の予算超過が許容枠を超えた（budget=${budgetMs}ms elapsed=${elapsed}ms）", elapsed <= budgetMs + overrunToleranceMs)
            val base = UnifiedViolationChecker.check(st, initial)
            val after = UnifiedViolationChecker.check(st, res.schedule)
            assertTrue("$alg が予算満了時に入力より悪い盤面を返した", !betterReport(base, after))
        }
    }

    /** [3.511.3] 実計算に入ってから300ms後に外部停止要求を出し、5秒以内に戻ることを確認する
     *  （StopPropagationTestの「t=0で即true」と違い、実機の「しばらく待ってからやめるを押す」に対応）。
     *  内部ループ構造が異なる代表2種（単一チェーン系のV5・複数仮説協調のPORTFOLIO）に絞る。 */
    @Test
    fun stopRequestDuringRealComputationHonoredWithinFiveSeconds() = runBlocking {
        val st = goldenState()
        val initial = Array(st.schedule.size) { st.schedule[it].toIntArray() }
        for (alg in listOf(V6Algorithm.V5, V6Algorithm.PORTFOLIO)) {
            val stopFlag = AtomicBoolean(false)
            val flipMs = AtomicLong(-1L)
            val job = async(Dispatchers.Default) {
                V6NativeOptimizer.optimize(
                    st, Array(initial.size) { initial[it].clone() },
                    V6OptimizerOptions(algorithm = alg, totalBudgetSec = 10, workers = 4, seed = 12345L),
                    shouldStop = { stopFlag.get() },
                )
            }
            launch(Dispatchers.Default) {
                delay(300L)
                flipMs.set(EngineClock.nowMs())
                stopFlag.set(true)
            }
            job.await()
            val returnMs = EngineClock.nowMs()
            assertTrue("$alg の停止要求が反映されていない", flipMs.get() > 0)
            assertTrue("$alg が停止要求から5秒以内に戻らなかった（${returnMs - flipMs.get()}ms）", returnMs - flipMs.get() < 5_000L)
        }
    }

    /** [3.511.3] 仮説の一部（偶数index）が例外を投げても、runMultiWorker 全体が2秒以内に制御を返す
     *  （supervisorScope + per-hypothesis try/catch が機能し続けることの固定）。全滅時のフォールバック
     *  （results.isEmpty() 経由の無防備な run(0,...) 再呼出）は例外伝播が仕様のため、ハングしないことだけを見る。 */
    @Test
    fun runMultiWorkerDoesNotHangWhenHypothesesThrow() = runBlocking {
        val st = goldenState()
        val schedule = Array(st.schedule.size) { st.schedule[it].toIntArray() }
        val report = UnifiedViolationChecker.check(st, schedule)
        fun trivialResult() = V6OptimizerResult(schedule, report, V6Algorithm.V5, emptyList(), 0L, 0L)

        val t0 = EngineClock.nowMs()
        val partial = PortfolioSearch.runMultiWorker(4, V6OptimizerOptions(workers = 4), onProgress = { _, _, _, _ -> }) { i, _, _ ->
            if (i % 2 == 0) throw RuntimeException("injected w=$i") else trivialResult()
        }
        assertTrue("部分失敗が2秒以内に戻らなかった", EngineClock.nowMs() - t0 < 2_000L)
        assertTrue("非throw側の結果が返る", partial.schedule.contentDeepEquals(schedule))

        val t1 = EngineClock.nowMs()
        runCatching {
            PortfolioSearch.runMultiWorker(4, V6OptimizerOptions(workers = 4), onProgress = { _, _, _, _ -> }) { i, _, _ ->
                throw RuntimeException("injected all w=$i")
            }
        }
        assertTrue("全滅時も2秒以内に制御が戻った（成功/例外いずれでもハングしない）", EngineClock.nowMs() - t1 < 2_000L)
    }
}
