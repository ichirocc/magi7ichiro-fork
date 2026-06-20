package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import java.util.Random

/**
 * Native port / fusion of the V6 Web optimizer dispatcher.
 *
 * Web V6 chooses V5 / ALNS / RSI / RSI++ by budget and then runs post-passes
 * (HF66/HF67/HF80 family).  This Kotlin version keeps the same public semantics:
 * AUTO chooses an algorithm by time budget, V5 is parallel SA, ALNS uses destroy/repair
 * multi-restart, RSI focuses on the currently most violated family, and RSI++ chains
 * seed -> hypothesis -> refine -> polish.
 */
enum class V6Algorithm { AUTO, V5, ALNS, RSI, RSI_PLUS, PORTFOLIO }

/** ALNS の受理基準。SA=Boltzmann(従来) / GREAT_DELUGE=時間予定型 Great Deluge（水位以下を受理）。 */
enum class AcceptMode { SA, GREAT_DELUGE }

data class V6OptimizerOptions(
    val algorithm: V6Algorithm = V6Algorithm.AUTO,
    val totalBudgetSec: Int = 300,
    val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    val softPolish: Boolean = true,
    val restarts: Int = 2,
    val seed: Long = 0L,
    /** [HF528/532移植] RectSwap2/C1BlockN を RSI 系へ伝播。Web optFlags.rectSwap 既定ON(HF532 恒久ON確定)。 */
    val rectSwap: Boolean = true,
    /** Run the final HF80 epilogue polish inside optimize(). Set false when the caller
     *  (e.g. V6FinalPort.handleOptimize) runs its own post-optimization chain, to avoid
     *  polishing twice. Direct callers keep the default so they still get a polish. */
    val postPolish: Boolean = true,
    /** [HF290 役割分担移植] 探索/精製の温度・摂動倍率。1.0=ベースライン(従来)。>1=探索(高温/大摂動)、<1=精製(低温)。
     *  並列仮説ごとに別の値を割当てて多様化（W0は常に1.0でベースライン保持＝退化防止）。 */
    val explore: Double = 1.0,
    /** ALNS の受理基準。並列仮説の一部に Great Deluge を割当てて受理戦略を多様化（W0は SA でベースライン保持）。 */
    val accept: AcceptMode = AcceptMode.SA,
)

data class V6OptimizerResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val algorithm: V6Algorithm,
    val phaseLogs: List<MirrorLog>,
    val iterations: Long,
    val elapsedMs: Long,
)

object V6NativeOptimizer {
    /** [GLS移植] 最良未更新がこの反復数を超えたら GLS penalty を強化（Web版 glsTrigger 既定200）。 */
    private const val GLS_TRIGGER = 200L
    private const val GLS_DECAY_EVERY = 256   // [GLS aging] この kick 数ごとに penalty を減衰し肥大化を防ぐ

    /** [HF290 役割分担移植] 並列仮説の探索/精製プロファイル（温度・摂動の倍率）。
     *  W0=1.0(ベースライン=退化防止)、以降は探索(>1)/精製(<1)を交互に割当てて portfolio を多様化。 */
    private val ROLE_EXPLORE = doubleArrayOf(1.0, 2.0, 0.5, 1.6, 0.6)
    internal fun roleExploreFor(i: Int): Double = if (i in ROLE_EXPLORE.indices) ROLE_EXPLORE[i] else 1.0

    /** [論文活用] 並列仮説の一部に Great Deluge 受理を割当てて受理戦略を多様化（W0,W1,W3=SA / W2,W4=GD）。 */
    internal fun roleAcceptFor(i: Int): AcceptMode = if (i == 2 || i == 4) AcceptMode.GREAT_DELUGE else AcceptMode.SA

    /**
     * 時間予定型 Great Deluge の水位（Burke, Bykov, Newall & Petrovic 2004）。
     * frac=1(序盤)で initial、frac=0(終盤)で best へ線形降下。候補スコア ≤ 水位 なら受理。
     */
    internal fun greatDelugeLevel(initial: Double, best: Double, frac: Double): Double =
        best + (initial - best) * frac.coerceIn(0.0, 1.0)

    /** 直近の並列探索で得た「他の案」（採用案以外の候補スケジュール、品質順・最大3件）。 */
    @Volatile var lastAlternatives: List<Array<IntArray>> = emptyList()
        private set

    /** [DefragLiveView移植] 実行中の最良盤面スナップショット（計算中ライブ表示用・読取専用）。
     *  進捗の節目で更新。並列時はどのワーカーの最良でも有効な解なので last-writer-wins で問題ない。 */
    @Volatile var liveBest: List<List<Int>>? = null
        private set

    suspend fun optimize(
        state: MagiState,
        initial: Array<IntArray> = state.schedule.toIntArray2D(),
        options: V6OptimizerOptions = V6OptimizerOptions(),
        shouldStop: () -> Boolean = { false },
        onProgress: (String, ViolationReport?, Long, Long) -> Unit = { _, _, _, _ -> },
    ): V6OptimizerResult {
        val started = nowMs()
        lastAlternatives = emptyList()
        liveBest = null
        val chosen = chooseAlgorithm(options.algorithm, options.totalBudgetSec)
        val p = cachedProblem(state)
        var schedule = hf66DataHardening(state, normalizeSchedule(initial, p), "pre")
        schedule = hf67HardRepair(state, schedule, Random(actualSeed(options.seed) xor 0x67L)).schedule
        var logs = listOf(MirrorLog(tag = "V6Dispatcher", message = "algorithm=$chosen budget=${options.totalBudgetSec}s workers=${options.workers}"))
        // 仕様書 §2.2/§4.1: 最大5仮説を並列探索。
        val w = options.workers.coerceIn(1, 5)
        val full = max(1, options.totalBudgetSec)
        val result = when (chosen) {
            // V5 already runs `workers` parallel SA chains inside SaOptimizer.
            V6Algorithm.V5 -> runV5(state, schedule, options, full, shouldStop, onProgress)
            // ALNS/RSI/RSI++ are run as up to 5 parallel hypotheses with hybrid early-cancel.
            V6Algorithm.ALNS -> runMultiWorker(w, options, onProgress) { _, o, prog -> runAlns(state, schedule.copy2D(), o, full, shouldStop, prog) }
            V6Algorithm.RSI -> runMultiWorker(w, options, onProgress) { _, o, prog -> runRsi(state, schedule.copy2D(), o, full, shouldStop, prog) }
            V6Algorithm.RSI_PLUS -> runMultiWorker(w, options, onProgress) { _, o, prog -> runRsiPlus(state, schedule.copy2D(), o, full, shouldStop, prog) }
            // [協力ポートフォリオ] 1回の最適化で各仮説に異なる方式を割当て、keep-best プールで最良を共有採用。
            V6Algorithm.PORTFOLIO -> runMultiWorker(w, options, onProgress) { i, o, prog ->
                when (portfolioAlgoFor(i)) {
                    V6Algorithm.ALNS -> runAlns(state, schedule.copy2D(), o, full, shouldStop, prog)
                    V6Algorithm.RSI -> runRsi(state, schedule.copy2D(), o, full, shouldStop, prog)
                    else -> runRsiPlus(state, schedule.copy2D(), o, full, shouldStop, prog)
                }
            }
            V6Algorithm.AUTO -> error("AUTO must be resolved")
        }
        logs = logs + result.phaseLogs
        // [review #3] Final epilogue polish only when the caller isn't running its own post chain.
        val polished = if (options.postPolish && !shouldStop())
            hf80PostPolish(state, result.schedule, max(1, min(30, options.totalBudgetSec / 20)), actualSeed(options.seed) xor 0x80L, shouldStop)
        else PolishResult(result.schedule, emptyList(), 0)
        val finalReport = UnifiedViolationChecker.check(state, polished.schedule)
        logs = logs + polished.logs + MirrorLog(
            tag = "V6Dispatcher",
            message = "完了 algorithm=$chosen HARD=${finalReport.hard} total=${finalReport.total} elapsed=${nowMs() - started}ms",
        )
        return V6OptimizerResult(polished.schedule, finalReport.copy(logs = logs + finalReport.logs), chosen, logs, result.iterations + polished.iterations, nowMs() - started)
    }

    /**
     * Run up to [w] independent hypotheses concurrently (distinct seeds) and keep the best —
     * the native W0..Wn multi-worker pool with the spec's hybrid termination (§2.2/§4.2):
     *  - 絶対評価: the first hypothesis to reach the pass line (HARD=0) cancels the others
     *    immediately (saves battery/heat); the winner finishes its own budget (soft polish).
     *  - 相対評価: if none passes by the deadline, the lowest-penalty hypothesis is adopted.
     * Worker 0's progress is forwarded, prefixed with the number of hypotheses still running.
     */
    private suspend fun runMultiWorker(
        w: Int,
        options: V6OptimizerOptions,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
        run: suspend (Int, V6OptimizerOptions, (String, ViolationReport?, Long, Long) -> Unit) -> V6OptimizerResult,
    ): V6OptimizerResult = coroutineScope {
        if (w <= 1) return@coroutineScope run(0, options.copy(workers = 1), onProgress)
        val base = actualSeed(options.seed)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val winner = java.util.concurrent.atomic.AtomicInteger(-1)
        val jobs = arrayOfNulls<kotlinx.coroutines.Deferred<V6OptimizerResult>>(w)
        for (i in 0 until w) {
            jobs[i] = async(Dispatchers.Default) {
                // [HF290 役割分担＋論文活用] 各仮説に探索/精製プロファイル＋受理基準(SA/GD)を割当て多様化（W0=ベースライン）。
                val res = run(i, options.copy(workers = 1, seed = base + (i + 1) * 0x9E3779B1L, explore = roleExploreFor(i), accept = roleAcceptFor(i))) { phase, report, iters, elapsed ->
                    if (i == 0) onProgress("仮説${(w - completed.get()).coerceAtLeast(1)}本探索中 / $phase", report, iters, elapsed)
                    // 絶対評価: 合格ライン(HARD=0)に最初に到達した仮説が、残りを即キャンセル
                    if (report != null && report.hard == 0 && winner.compareAndSet(-1, i)) {
                        for (j in 0 until w) if (j != i) jobs[j]?.cancel()
                    }
                }
                completed.incrementAndGet()
                res
            }
        }
        val results = jobs.mapNotNull { d ->
            try { d?.await() } catch (_: kotlinx.coroutines.CancellationException) { null }
        }
        // 兄弟キャンセル(自己)とユーザー停止(外部)を区別: 外部停止ならここで伝播させる。
        ensureActive()
        val best = if (results.isEmpty()) run(0, options.copy(workers = 1), onProgress)
        else results.reduce { a, b -> if (better(b.report, a.report)) b else a }
        // 「他の案」: 採用案以外の仮説結果を品質順に保持（重複schedule除外、最大3件）
        lastAlternatives = results.asSequence()
            .filter { it !== best }
            .sortedWith(compareBy({ it.report.hard }, { it.report.total }))
            .map { it.schedule }
            .distinctBy { sch -> sch.joinToString("|") { it.joinToString(",") } }
            .take(3)
            .toList()
        val totalIters = results.sumOf { it.iterations }
        val mode = if (winner.get() >= 0) "合格で早期キャンセル" else "時間内最良採用"
        val extra = MirrorLog(tag = "MultiWorker", message = "仮説 ${w} 本 ($mode・役割分担:探索/精製＋受理SA/GreatDeluge多様化) → 採用 HARD=${best.report.hard} total=${best.report.total} 合計iter=${totalIters}")
        best.copy(phaseLogs = best.phaseLogs + extra, iterations = totalIters)
    }

    /** Roulette-wheel operator selection for the adaptive LNS. */
    private fun rouletteSelect(weights: DoubleArray, rng: Random): Int {
        var sum = 0.0
        for (wgt in weights) sum += wgt
        if (sum <= 0.0) return rng.nextInt(weights.size)
        var r = rng.nextDouble() * sum
        for (i in weights.indices) {
            r -= weights[i]
            if (r <= 0.0) return i
        }
        return weights.size - 1
    }

    /** [協力ポートフォリオ] 仮説 i に割り当てる方式。最上位(RSI++)を厚めに、ALNS/RSI で多様化。 */
    private fun portfolioAlgoFor(i: Int): V6Algorithm = when (i % 4) {
        1 -> V6Algorithm.ALNS
        2 -> V6Algorithm.RSI
        else -> V6Algorithm.RSI_PLUS
    }

    fun chooseAlgorithm(requested: V6Algorithm, budgetSec: Int): V6Algorithm {
        if (requested != V6Algorithm.AUTO) return requested
        // [5分圧縮] 上限300sでも最上位 RSI++ に到達できるよう閾値を前倒し。
        return when {
            budgetSec <= 30 -> V6Algorithm.V5
            budgetSec <= 90 -> V6Algorithm.ALNS
            budgetSec <= 210 -> V6Algorithm.RSI
            else -> V6Algorithm.RSI_PLUS
        }
    }

    private suspend fun runV5(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult {
        val t0 = nowMs()
        val p = Problem(state.withSchedule(initial))
        val ev = Evaluator(p)
        var lastReport: ViolationReport? = null
        // [HF290 役割分担] explore 倍率で初期温度を調整（探索=高温/精製=低温）。explore=1.0 は従来と同一。
        val saT0 = (10.0 * options.explore).coerceIn(2.0, 40.0)
        val res = SaOptimizer(p, ev).run(
            SaParams(t0 = saT0, workers = options.workers, budgetMs = budgetSec * 1000L, softPolish = options.softPolish, shouldStop = shouldStop),
        ) { pr ->
            if (pr.elapsedMs % 1000L < 220L) onProgress("V5 SA", lastReport, pr.totalIters, pr.elapsedMs)
        }
        val repaired = hf67HardRepair(state, res.schedule, Random(actualSeed(options.seed) xor 0x5L))
        val report = UnifiedViolationChecker.check(state, repaired.schedule)
        lastReport = report
        val logs = listOf(MirrorLog(tag = "RunMAGI_V5", message = "高速SA完了 HARD=${report.hard} total=${report.total} iter=${res.totalIters}")) + repaired.logs
        return V6OptimizerResult(repaired.schedule, report.copy(logs = logs + report.logs), V6Algorithm.V5, logs, res.totalIters, nowMs() - t0)
    }

    private suspend fun runAlns(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult {
        val started = nowMs()
        val rng = Random(actualSeed(options.seed) xor 0xA17A5L)
        val p = cachedProblem(state)
        val restarts = max(1, options.restarts)
        val per = max(1, budgetSec / restarts)
        var globalBest = normalizeSchedule(initial, p)
        var globalReport = UnifiedViolationChecker.check(state, globalBest)
        // [退化防止] hot-loop は生スコア(DeltaEvaluator)で最良を追うが、生スコアと weightedScore は
        // 目的が異なる（range は生スコアで hard、weightedScore で soft）。最終結果が入力(best)より
        // hard→total→weighted の辞書順で悪化しないよう、開始時の盤面を baseline として保持し最後に番兵比較する。
        val baseBest = globalBest.copy2D()
        val baseReport = globalReport
        var itersTotal = 0L
        val logs = ArrayList<MirrorLog>()
        // [GLS移植] Guided Local Search: 受理(accept-worse)を penalty で誘導し局所最適から脱出。
        // グローバル最良は生スコアで別管理するので、GLSで真の最良を失うことはない。
        val gls = GlsPenalty(p.S, p.T, p.K)
        var lastImproveIter = 0L
        // [差分化移植] DeltaEvaluator を hot-loop のスコア源にして copy2D / 全件 check() を回避する。
        //   - 直接評価アーム(op3-6): eval+cur に直接適用、不採択は反転（copy2D なし／ゼロアロケーション）。
        //   - copy系アーム(op0-2): 変更セルだけを eval へ反映（op0=O(S)列, op1=O(T)行 の targeted 差分）。
        // グローバル最良は生スコア(score)でゲートし、更新時のみ check() で ViolationReport を確定する
        // （SaOptimizer と同じ目的関数。最終的なアルゴリズム間比較は better(ViolationReport) で行う）。
        val eval = DeltaEvaluator(p)
        eval.reset(globalBest)
        var globalScore = eval.score()
        val diffBuf = IntArray(p.S * p.T)   // scratch: flat indices i*T+j of changed cells (zero-alloc)
        for (r in 0 until restarts) {
            if (shouldStop()) break
            coroutineContext.ensureActive()
            // [restart 摂動] 一律 strength=0.18。非線形スケジュール(2.51)は nsp_bench --real の final 品質で
            //   +101% 悪化と実測されたため revert(序盤の大摂動が強い repair 下で良解を壊し最終品質を損なう)。
            var cur = if (r == 0) globalBest.copy2D() else perturb(state, globalBest, rng, strength = (0.18 * options.explore).coerceIn(0.05, 0.6))
            cur = hf67HardRepair(state, cur, rng).schedule
            var curReport = UnifiedViolationChecker.check(state, cur)
            eval.reset(cur)
            var curScore = eval.score()
            var curAug = gls.augment(cur)   // [GLS] 現行盤面の penalty 拡張分を増分維持（再構築は restart 毎のみ）
            val deadline = nowMs() + per * 1000L
            // [論文活用] Great Deluge の初期水位＝このリスタート開始時のスコア（時間予定型で best へ降下）。
            val gdInitial = curScore.toDouble()
            var iter = 0L
            // [Adaptive LNS] learned operator weights (roulette-wheel selection + reaction-factor
            // update), per Ropke & Pisinger and recent adaptive-LNS personnel-scheduling work
            // (Ouberkouk, Boufflet & Moukrim, J. Heuristics 2023). Replaces uniform operator choice.
            val opW = DoubleArray(7) { 1.0 }
            val opScore = DoubleArray(7)
            val opCnt = IntArray(7)
            var sinceUpdate = 0
            while (nowMs() < deadline && !shouldStop()) {
                coroutineContext.ensureActive()
                val op = rouletteSelect(opW, rng)
                // [HF290 役割分担] explore 倍率で受理温度を調整（探索=受理寛容/精製=厳格）。explore=1.0 は従来と同一。
                val temp = max(0.03, (deadline - nowMs()).toDouble() / max(1.0, per * 1000.0) * options.explore)
                val curHard = curScore / 1_000_000L
                val gdLevel = if (options.accept == AcceptMode.GREAT_DELUGE) {
                    val frac = ((deadline - nowMs()).toDouble() / max(1.0, per * 1000.0)).coerceIn(0.0, 1.0)
                    greatDelugeLevel(gdInitial, globalScore.toDouble(), frac)
                } else 0.0
                var reward = 0.2   // default: rejected / no-op

                if (op in 3..6) {
                    // ── 直接評価パス(op3-6): copy2D なし。eval+cur に直接適用し、不採択は反転 ──
                    // 不変条件: eval.at(i,j) == cur[i][j] が常時成立。変更セル(≤2)を保持して反転に使う。
                    var moved = false
                    var ns = curScore
                    var moveAug = 0.0
                    var c0i = -1; var c0j = -1; var c0old = -1
                    var c1i = -1; var c1j = -1; var c1old = -1
                    when {
                        op == 3 && p.S > 0 && p.T >= 2 -> {   // swapWithinStaff
                            val i = rng.nextInt(p.S)
                            var ja = rng.nextInt(p.T); var jb = rng.nextInt(p.T)
                            if (ja == jb) jb = (jb + 1) % p.T
                            if (p.wish[i][ja] < 0 && p.wish[i][jb] < 0) {
                                val ka = eval.at(i, ja); val kb = eval.at(i, jb)
                                if (ka != kb) {
                                    eval.apply(i, ja, kb); eval.apply(i, jb, ka)
                                    c0i = i; c0j = ja; c0old = ka; c1i = i; c1j = jb; c1old = kb
                                    moveAug = glsMoveAug(gls, i, ja, ka, kb) + glsMoveAug(gls, i, jb, kb, ka)
                                    ns = eval.score(); moved = true
                                }
                            }
                        }
                        op == 4 && p.S > 0 && p.T > 0 -> {   // randomAllowedCell
                            val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
                            if (p.wish[i][j] < 0) {
                                val allowed = p.allowedShiftsForStaff(i)
                                if (allowed.isNotEmpty()) {
                                    val oldK = eval.at(i, j); val nw = allowed[rng.nextInt(allowed.size)]
                                    if (nw != oldK) {
                                        eval.apply(i, j, nw)
                                        c0i = i; c0j = j; c0old = oldK
                                        moveAug = glsMoveAug(gls, i, j, oldK, nw)
                                        ns = eval.score(); moved = true
                                    }
                                }
                            }
                        }
                        op == 5 -> {   // targeted single-cell repair (direct-eval)
                            val fix = findTargetedFix(p, eval, rng)
                            if (fix != null) {
                                val oldK = eval.at(fix[0], fix[1])
                                if (fix[2] != oldK) {
                                    eval.apply(fix[0], fix[1], fix[2])
                                    c0i = fix[0]; c0j = fix[1]; c0old = oldK
                                    moveAug = glsMoveAug(gls, fix[0], fix[1], oldK, fix[2])
                                    ns = eval.score(); moved = true
                                }
                            }
                        }
                        op == 6 && p.S >= 2 && p.T > 0 -> {   // swapTwoStaffSameDay (coverage-neutral)
                            val j = rng.nextInt(p.T)
                            val i1 = rng.nextInt(p.S); var i2 = rng.nextInt(p.S)
                            if (i2 == i1) i2 = (i2 + 1) % p.S
                            if (p.wish[i1][j] < 0 && p.wish[i2][j] < 0) {
                                val k1 = eval.at(i1, j); val k2 = eval.at(i2, j)
                                if (k1 != k2 && p.canDo(i1, k2) && p.canDo(i2, k1)) {
                                    eval.apply(i1, j, k2); eval.apply(i2, j, k1)
                                    c0i = i1; c0j = j; c0old = k1; c1i = i2; c1j = j; c1old = k2
                                    moveAug = glsMoveAug(gls, i1, j, k1, k2) + glsMoveAug(gls, i2, j, k2, k1)
                                    ns = eval.score(); moved = true
                                }
                            }
                        }
                    }
                    if (moved) {
                        val improvedCur = ns < curScore
                        val accepted = improvedCur || glsAccept(ns, curScore, moveAug, curAug, options.accept, temp, gdLevel, rng)
                        if (accepted) {
                            cur[c0i][c0j] = eval.at(c0i, c0j)
                            if (c1i >= 0) cur[c1i][c1j] = eval.at(c1i, c1j)
                            curScore = ns; curAug += moveAug
                            if (ns < globalScore) {
                                globalBest = cur.copy2D(); globalScore = ns
                                globalReport = UnifiedViolationChecker.check(state, cur)
                                lastImproveIter = itersTotal
                                reward = 4.0
                            } else reward = if (improvedCur) 2.0 else 1.0
                        } else {
                            if (c1i >= 0) eval.apply(c1i, c1j, c1old)   // revert eval; cur was never mutated
                            eval.apply(c0i, c0j, c0old)
                        }
                        opScore[op] += reward; opCnt[op]++
                    }
                } else {
                    // ── copy系パス(op0-2): 変更セルだけ eval へ反映（targeted O(S)/O(T) 差分） ──
                    val cand = cur.copy2D()
                    val drDay = if (op == 0 && p.T > 0) rng.nextInt(p.T) else -1
                    val drStaff = if (op == 1 && p.S > 0) rng.nextInt(p.S) else -1
                    when (op) {
                        0 -> if (drDay >= 0) destroyRepairDayAt(state, cand, drDay, rng)
                        1 -> if (drStaff >= 0) destroyRepairStaffAt(state, cand, drStaff, rng)
                        else -> destroyRepairViolations(state, cand, curReport, rng)
                    }
                    // hf67 は hard 違反がある時のみ必要。
                    val fixed = if (iter % 7L == 0L && curHard > 0L) hf67HardRepair(state, cand, rng).schedule else cand
                    val nDiffs = when {
                        op == 0 && drDay >= 0 && fixed === cand -> {
                            var n = 0
                            for (i in 0 until p.S) if (cur[i][drDay] != fixed[i][drDay]) diffBuf[n++] = i * p.T + drDay
                            n
                        }
                        op == 1 && drStaff >= 0 && fixed === cand -> {
                            var n = 0
                            val row = fixed[drStaff]; val curRow = cur[drStaff]
                            for (j in 0 until p.T) if (curRow[j] != row[j]) diffBuf[n++] = drStaff * p.T + j
                            n
                        }
                        else -> diffInto(p.T, cur, fixed, diffBuf)
                    }
                    var moveAug = 0.0
                    for (idx in 0 until nDiffs) {
                        val flat = diffBuf[idx]; val i = flat / p.T; val j = flat % p.T
                        moveAug += glsMoveAug(gls, i, j, cur[i][j], fixed[i][j])
                        eval.apply(i, j, fixed[i][j])
                    }
                    val ns = eval.score()
                    val improvedCur = ns < curScore
                    val accepted = improvedCur || glsAccept(ns, curScore, moveAug, curAug, options.accept, temp, gdLevel, rng)
                    if (accepted) {
                        cur = fixed; curScore = ns; curAug += moveAug
                        if (ns < globalScore) {
                            globalBest = fixed.copy2D(); globalScore = ns
                            globalReport = UnifiedViolationChecker.check(state, fixed)
                            lastImproveIter = itersTotal
                            reward = 4.0
                        } else reward = if (improvedCur) 2.0 else 1.0
                    } else {
                        for (idx in 0 until nDiffs) {
                            val flat = diffBuf[idx]; eval.apply(flat / p.T, flat % p.T, cur[flat / p.T][flat % p.T])
                        }
                    }
                    opScore[op] += reward; opCnt[op]++
                }

                // [GLS] 停滞時(直近の最良更新から GLS_TRIGGER 反復超)に、違反セルの最大util割当を強化。
                if (itersTotal - lastImproveIter > GLS_TRIGGER && iter % 50L == 0L) {
                    val cells = ArrayList<Pair<Int, Int>>(curReport.violations.size)
                    for (vkey in curReport.violations.keys) {
                        val parts = vkey.split(',')
                        val ci = parts.getOrNull(0)?.toIntOrNull()
                        val cj = parts.getOrNull(1)?.toIntOrNull()
                        if (ci != null && cj != null) cells.add(ci to cj)
                    }
                    if (gls.penalizeWorst(cur, cells)) {
                        curAug += gls.lambda   // penalized a current cell -> augment(cur) += lambda
                        // [GLS aging] 一定 kick ごとに penalty を減衰し肥大化を防ぐ。penalty集合が変わるので
                        //   curAug を augment(cur) で再同期（globalBest は生スコア管理＝解の質は退化しない）。
                        if (gls.kickCount() % GLS_DECAY_EVERY == 0) { gls.decay(); curAug = gls.augment(cur) }
                    }
                }
                // destroyRepairViolations 用に curReport を周期更新（hint の鮮度確保）。
                if (iter % 200L == 0L) curReport = UnifiedViolationChecker.check(state, cur)
                if (++sinceUpdate >= 64) {
                    for (k in opW.indices) {
                        if (opCnt[k] > 0) opW[k] = (0.8 * opW[k] + 0.2 * (opScore[k] / opCnt[k])).coerceAtLeast(0.05)
                        opScore[k] = 0.0; opCnt[k] = 0
                    }
                    sinceUpdate = 0
                }
                iter++
                itersTotal++
                if (iter % 120L == 0L) {
                    liveBest = globalBest.map { it.toList() }   // [DefragLiveView] 計算中ライブ盤面を公開
                    onProgress("ALNS restart ${r + 1}/$restarts", globalReport, itersTotal, nowMs() - started)
                    yield()
                }
            }
            logs.add(MirrorLog(iter = itersTotal, tag = "RunMAGI_ALNS", message = "restart=${r + 1}/$restarts best HARD=${globalReport.hard} total=${globalReport.total} GLS=${gls.kickCount()}"))
        }
        // [退化防止] 生スコア最良が weightedScore 辞書順では入力より悪い可能性があるため番兵で保証。
        if (better(baseReport, globalReport)) { globalBest = baseBest; globalReport = baseReport }
        return V6OptimizerResult(globalBest, globalReport.copy(logs = logs + globalReport.logs), V6Algorithm.ALNS, logs, itersTotal, nowMs() - started)
    }

    private suspend fun runRsi(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult {
        val started = nowMs()
        val rng = Random(actualSeed(options.seed) xor 0x451L)
        var best = normalizeSchedule(initial, cachedProblem(state))
        var bestReport = UnifiedViolationChecker.check(state, best)
        var iters = 0L
        val rounds = max(2, min(8, budgetSec / 30 + 2))
        val per = max(1, budgetSec / rounds)
        val logs = ArrayList<MirrorLog>()
        // [HF63] ラウンド境界で改善ストリームを追跡し、構造的に充足困難な族を focus 対象から外す。
        // best-of-rounds のため、回避は「無駄なラウンドを達成可能な族へ振り向ける」だけで悪化は起きない。
        val hf63 = Hf63Infeasibility()
        for (round in 0 until rounds) {
            if (shouldStop()) break
            coroutineContext.ensureActive()
            hf63.updateFromBreakdown(bestReport.breakdown, iters.toInt())
            val avoid = hf63.infeasibleBreakdownKeys()
            val focus = maxViolatedFamily(bestReport, avoid)
            if (avoid.isNotEmpty()) {
                logs.add(MirrorLog(iter = iters, tag = "HF63", message = "deprioritize ${avoid.joinToString(",")} → focus=$focus (round ${round + 1})"))
            }
            val hypothesis = rsiGenerateHypothesis(state, best, bestReport, focus, rng)
            val phase = if (round % 2 == 0) runAlns(state, hypothesis, options.copy(restarts = 1), per, shouldStop, onProgress) else runV5(state, hypothesis, options, per, shouldStop, onProgress)
            iters += phase.iterations
            var candSched = phase.schedule
            var candReport = phase.report
            // [HF361/528/541移植] EarlyChain: Web 内部V5の停滞(reheat)フック(L11705-)に対応する RSI ラウンド境界で発火
            //   Chain3/4 は常時、Rect/BlkN は optFlags.rectSwap(既定ON)に従う — Web 呼出順 e3/e4/e5/e6 と同一。
            run {
                val lr = V6LateOperators.improve(state, candSched, candReport, rng, started + budgetSec * 1000L, rectEnabled = options.rectSwap)
                if (lr.chain3 + lr.chain4 + lr.rect + lr.blkN > 0) {
                    candSched = lr.schedule
                    candReport = lr.report
                    logs.add(MirrorLog(iter = iters, tag = "EarlyChain", message = "早期循環フック改善 (Chain3=${lr.chain3} Chain4=${lr.chain4} Rect=${lr.rect} BlkN=${lr.blkN}) round=${round + 1} HARD=${candReport.hard} total=${candReport.total}"))
                    logs.addAll(lr.logs)
                }
            }
            if (better(candReport, bestReport)) {
                best = candSched.copy2D()
                bestReport = candReport
            }
            logs.add(MirrorLog(iter = iters, tag = "RunMAGI_RSI", message = "round=${round + 1}/$rounds focus=$focus best HARD=${bestReport.hard} total=${bestReport.total}"))
            liveBest = best.map { it.toList() }   // [DefragLiveView] 計算中ライブ盤面を公開
            onProgress("RSI $focus", bestReport, iters, nowMs() - started)
        }
        return V6OptimizerResult(best, bestReport.copy(logs = logs + bestReport.logs), V6Algorithm.RSI, logs, iters, nowMs() - started)
    }

    private suspend fun runRsiPlus(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult {
        val started = nowMs()
        val seedSec = max(10, (budgetSec * 0.20).toInt())
        val rsiSec = max(10, (budgetSec * 0.35).toInt())
        val alnsSec = max(10, (budgetSec * 0.30).toInt())
        val polishSec = max(5, budgetSec - seedSec - rsiSec - alnsSec)
        val logs = ArrayList<MirrorLog>()
        val seed = runV5(state, initial, options, seedSec, shouldStop, onProgress)
        logs.add(MirrorLog(tag = "RSIPlus", message = "Phase1 Seed: HARD=${seed.report.hard} total=${seed.report.total}"))
        val rsi = if (shouldStop()) seed else runRsi(state, seed.schedule, options, rsiSec, shouldStop, onProgress)
        val base = if (better(rsi.report, seed.report)) rsi else seed
        logs.add(MirrorLog(tag = "RSIPlus", message = "Phase2 Hypothesis: HARD=${base.report.hard} total=${base.report.total}"))
        val refine = if (shouldStop()) base else runAlns(state, base.schedule, options.copy(restarts = max(1, options.restarts)), alnsSec, shouldStop, onProgress)
        val best = if (better(refine.report, base.report)) refine else base
        var bestSched = best.schedule
        // [HF361/528/541移植] EarlyChain: Refine 確定後の停滞境界で Chain3/4(常時)+Rect/BlkN(rectSwap)を発火
        run {
            val lr = V6LateOperators.improve(state, bestSched, best.report, Random(actualSeed(options.seed) xor 0x528L), started + budgetSec * 1000L, rectEnabled = options.rectSwap)
            if (lr.chain3 + lr.chain4 + lr.rect + lr.blkN > 0) {
                bestSched = lr.schedule
                logs.add(MirrorLog(tag = "EarlyChain", message = "早期循環フック改善 (Chain3=${lr.chain3} Chain4=${lr.chain4} Rect=${lr.rect} BlkN=${lr.blkN}) HARD=${lr.report.hard} total=${lr.report.total}"))
                logs.addAll(lr.logs)
            }
        }
        val polish = hf80PostPolish(state, bestSched, polishSec, actualSeed(options.seed) xor 0x555L, shouldStop)
        val report = UnifiedViolationChecker.check(state, polish.schedule)
        logs.add(MirrorLog(tag = "RSIPlus", message = "Phase3/4 Refine+Polish: HARD=${report.hard} total=${report.total}"))
        return V6OptimizerResult(
            polish.schedule,
            report.copy(logs = logs + seed.phaseLogs + rsi.phaseLogs + refine.phaseLogs + polish.logs + report.logs),
            V6Algorithm.RSI_PLUS,
            logs + seed.phaseLogs + rsi.phaseLogs + refine.phaseLogs + polish.logs,
            seed.iterations + rsi.iterations + refine.iterations + polish.iterations,
            nowMs() - started,
        )
    }

    private fun hf66DataHardening(state: MagiState, schedule: Array<IntArray>, tag: String): Array<IntArray> {
        val p = cachedProblem(state)
        val out = normalizeSchedule(schedule, p)
        for (i in 0 until p.S) {
            val allowed = p.allowedShiftsForStaff(i)
            val fallback = allowed.firstOrNull() ?: 0
            for (j in 0 until p.T) {
                val k = out[i][j]
                if (k !in 0 until p.K || !p.canDo(i, k)) out[i][j] = fallback
            }
        }
        return out
    }

    private data class RepairResult(val schedule: Array<IntArray>, val logs: List<MirrorLog>)

    private fun hf67HardRepair(state: MagiState, schedule: Array<IntArray>, rng: Random): RepairResult {
        val p = cachedProblem(state)
        val out = hf66DataHardening(state, schedule, "hf67")
        val logs = ArrayList<MirrorLog>()
        var changed = 0

        // Apply feasible wishes first; infeasible wishes are logged by Sanity, not forced.
        for (i in 0 until p.S) for (j in 0 until p.T) {
            val w = p.wish[i][j]
            if (w in 0 until p.K && p.canDo(i, w) && out[i][j] != w) {
                out[i][j] = w
                changed++
            }
        }

        repeat(3) {
            val cov = coverage(p, out)
            val counts = countMatrix(p, out)
            for (j in 0 until p.T) for (k in 0 until p.K) {
                val need = p.need1[k][j]
                if (need <= 0) continue
                var miss = need - cov[j][k]
                while (miss > 0) {
                    val i = bestStaffForCoverage(p, out, counts, j, k)
                    if (i < 0) break
                    val old = out[i][j]
                    if (old == k) break
                    out[i][j] = k
                    cov[j][k]++
                    if (old in 0 until p.K) cov[j][old]--
                    changed++
                    miss--
                }
            }
        }

        // Range lower bounds: fill shortage where possible without touching locked wishes.
        val counts = countMatrix(p, out)
        for (i in 0 until p.S) for (k in 0 until p.K) {
            val lo = p.rangeLo[i][k]
            if (lo == Int.MIN_VALUE || !p.canDo(i, k)) continue
            var need = lo - counts[i][k]
            var guard = 0
            while (need > 0 && guard++ < p.T) {
                var bestJ = -1
                var bestScore = Int.MAX_VALUE
                for (jj in 0 until p.T) {
                    if (p.wish[i][jj] >= 0 || out[i][jj] == k) continue
                    val score = coverageShortageCost(p, out, jj, out[i][jj]) + rng.nextInt(3)
                    if (score < bestScore) {
                        bestScore = score
                        bestJ = jj
                    }
                }
                if (bestJ < 0) break
                val j = bestJ
                val old = out[i][j]
                out[i][j] = k
                if (old in 0 until p.K) counts[i][old]--
                counts[i][k]++
                changed++
                need--
            }
        }
        if (changed > 0) logs.add(MirrorLog(tag = "HF67", message = "HardRepair changed=$changed"))
        return RepairResult(out, logs)
    }

    private data class PolishResult(val schedule: Array<IntArray>, val logs: List<MirrorLog>, val iterations: Long)

    /**
     * 最終研磨フェーズ。[差分化移植] DeltaEvaluator を生スコア源にして直接評価で回す
     * （copy2D / 全件 check() を毎反復行わない）。op0-2 は単一/二セル直接評価、op3-8 は
     * findTargetedFix（シャッフル付きフォールバック）、op9-10 は copy 系の destroy/repair を
     * 変更セルだけ eval へ反映する。受理は hard 非悪化(best基準)＋ SA。
     * 不変条件: eval.at(i,j) == cur[i][j]。生スコアと weightedScore は目的が異なるため、
     * 入力(best)を baseline として保持し最後に番兵比較して退化を防ぐ。
     */
    private suspend fun hf80PostPolish(state: MagiState, initial: Array<IntArray>, seconds: Int, seed: Long, shouldStop: () -> Boolean = { false }): PolishResult {
        val started = nowMs()
        val rng = Random(seed)
        val p = cachedProblem(state)
        var best = initial.copy2D()
        var bestReport = UnifiedViolationChecker.check(state, best)
        val baseSched = best          // 入力スナップショット（best は改善時に別配列へ差し替わる）
        val baseReport = bestReport
        var cur = best.copy2D()
        val eval = DeltaEvaluator(p)
        eval.reset(cur)
        var curScore = eval.score()
        var bestScore = curScore
        var iters = 0L
        val diffBuf = IntArray(p.S * p.T)
        val deadline = nowMs() + seconds * 1000L
        while (nowMs() < deadline && !shouldStop()) {
            coroutineContext.ensureActive()
            val curHard = curScore / 1_000_000L
            val bestHard = bestScore / 1_000_000L
            when (rng.nextInt(11)) {
                0 -> {   // random allowed single cell (direct-eval)
                    if (p.S > 0 && p.T > 0) {
                        val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
                        if (p.wish[i][j] < 0) {
                            val allowed = p.allowedShiftsForStaff(i)
                            if (allowed.isNotEmpty()) {
                                val oldK = eval.at(i, j); val nw = allowed[rng.nextInt(allowed.size)]
                                if (nw != oldK) {
                                    eval.apply(i, j, nw)
                                    val ns = eval.score()
                                    if (ns / 1_000_000L <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
                                        cur[i][j] = nw; curScore = ns
                                        if (betterScore(ns, bestScore)) { best = cur.copy2D(); bestScore = ns; bestReport = UnifiedViolationChecker.check(state, cur) }
                                    } else eval.apply(i, j, oldK)
                                }
                            }
                        }
                    }
                }
                1 -> {   // swap two days within one staff row (direct-eval)
                    if (p.S > 0 && p.T >= 2) {
                        val i = rng.nextInt(p.S)
                        var ja = rng.nextInt(p.T); var jb = rng.nextInt(p.T)
                        if (ja == jb) jb = (jb + 1) % p.T
                        if (p.wish[i][ja] < 0 && p.wish[i][jb] < 0) {
                            val ka = eval.at(i, ja); val kb = eval.at(i, jb)
                            if (ka != kb) {
                                eval.apply(i, ja, kb); eval.apply(i, jb, ka)
                                val ns = eval.score()
                                if (ns / 1_000_000L <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
                                    cur[i][ja] = kb; cur[i][jb] = ka; curScore = ns
                                    if (betterScore(ns, bestScore)) { best = cur.copy2D(); bestScore = ns; bestReport = UnifiedViolationChecker.check(state, cur) }
                                } else { eval.apply(i, ja, ka); eval.apply(i, jb, kb) }
                            }
                        }
                    }
                }
                2 -> {   // swap two staff on same day (direct-eval, coverage-neutral)
                    if (p.S >= 2 && p.T > 0) {
                        val j = rng.nextInt(p.T)
                        val i1 = rng.nextInt(p.S); var i2 = rng.nextInt(p.S)
                        if (i2 == i1) i2 = (i2 + 1) % p.S
                        if (p.wish[i1][j] < 0 && p.wish[i2][j] < 0) {
                            val k1 = eval.at(i1, j); val k2 = eval.at(i2, j)
                            if (k1 != k2 && p.canDo(i1, k2) && p.canDo(i2, k1)) {
                                eval.apply(i1, j, k2); eval.apply(i2, j, k1)
                                val ns = eval.score()
                                if (ns / 1_000_000L <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
                                    cur[i1][j] = k2; cur[i2][j] = k1; curScore = ns
                                    if (betterScore(ns, bestScore)) { best = cur.copy2D(); bestScore = ns; bestReport = UnifiedViolationChecker.check(state, cur) }
                                } else { eval.apply(i1, j, k1); eval.apply(i2, j, k2) }
                            }
                        }
                    }
                }
                in 3..8 -> {   // targeted single-cell fix with shuffled fallback (direct-eval)
                    val fix = findTargetedFix(p, eval, rng)
                    if (fix != null) {
                        val oldK = eval.at(fix[0], fix[1])
                        if (fix[2] != oldK) {
                            eval.apply(fix[0], fix[1], fix[2])
                            val ns = eval.score()
                            if (ns / 1_000_000L <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
                                cur[fix[0]][fix[1]] = fix[2]; curScore = ns
                                if (betterScore(ns, bestScore)) { best = cur.copy2D(); bestScore = ns; bestReport = UnifiedViolationChecker.check(state, cur) }
                            } else eval.apply(fix[0], fix[1], oldK)
                        }
                    }
                }
                else -> {   // copy-based multi-cell destroy/repair (ops 9,10)
                    val cand = cur.copy2D()
                    val drDay2 = if (rng.nextBoolean()) { destroyRepairViolations(state, cand, bestReport, rng); -1 }
                                 else { val j = if (p.T > 0) rng.nextInt(p.T) else -1; if (j >= 0) destroyRepairDayAt(state, cand, j, rng); j }
                    // hard-feasible のときは hf67 を省略（DeltaEvaluator が hard 退化を弾く）。
                    val fixed = if (curHard > 0L) hf67HardRepair(state, cand, rng).schedule else cand
                    val nDiffs = if (drDay2 >= 0 && fixed === cand) {
                        var n = 0
                        for (i in 0 until p.S) if (cur[i][drDay2] != fixed[i][drDay2]) diffBuf[n++] = i * p.T + drDay2
                        n
                    } else diffInto(p.T, cur, fixed, diffBuf)
                    for (idx in 0 until nDiffs) {
                        val flat = diffBuf[idx]; eval.apply(flat / p.T, flat % p.T, fixed[flat / p.T][flat % p.T])
                    }
                    val ns = eval.score()
                    if (ns / 1_000_000L <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
                        cur = fixed; curScore = ns
                        if (betterScore(ns, bestScore)) { best = fixed.copy2D(); bestScore = ns; bestReport = UnifiedViolationChecker.check(state, fixed) }
                    } else {
                        for (idx in 0 until nDiffs) {
                            val flat = diffBuf[idx]; eval.apply(flat / p.T, flat % p.T, cur[flat / p.T][flat % p.T])
                        }
                    }
                }
            }
            iters++
            if (iters % 150L == 0L) yield()
        }
        // [退化防止] 生スコア最良が weightedScore 辞書順で入力より悪い場合は入力へ戻す。
        if (better(baseReport, bestReport)) { best = baseSched; bestReport = baseReport }
        val logs = listOf(MirrorLog(iter = iters, tag = "HF80", message = "PostPolish ${nowMs() - started}ms HARD=${bestReport.hard} total=${bestReport.total}"))
        return PolishResult(best, logs, iters)
    }

    private fun bestStaffForCoverage(p: Problem, schedule: Array<IntArray>, counts: Array<IntArray>, j: Int, k: Int): Int {
        var bestI = -1
        var bestScore = Int.MAX_VALUE
        for (i in 0 until p.S) {
            if (!p.canDo(i, k)) continue
            if (p.wish[i][j] >= 0 && p.wish[i][j] != k) continue
            val old = schedule[i][j]
            if (old == k) return i
            val hi = p.rangeHi[i][k]
            val over = if (hi != Int.MAX_VALUE && counts[i][k] >= hi) 500 else 0
            val oldNeedCost = coverageShortageCost(p, schedule, j, old)
            val score = over + counts[i][k] * 3 - oldNeedCost
            if (score < bestScore) { bestScore = score; bestI = i }
        }
        return bestI
    }

    private fun coverageShortageCost(p: Problem, schedule: Array<IntArray>, j: Int, k: Int): Int {
        if (k !in 0 until p.K) return 0
        val need = p.need1[k][j]
        if (need <= 0) return 0
        var cov = 0
        for (i in 0 until p.S) if (schedule[i][j] == k) cov++
        return if (cov <= need) 50 else 0
    }

    private fun destroyRepairDay(state: MagiState, schedule: Array<IntArray>, rng: Random) {
        val p = cachedProblem(state)
        if (p.T == 0) return
        destroyRepairDayAt(state, schedule, rng.nextInt(p.T), rng)
    }

    /** [soft-aware repair] 割当 i→shift k の per-staff soft(low/high/apt, checker と同一式)を count n で評価。 */
    private fun staffCountPenaltyAt(p: Problem, i: Int, k: Int, n: Int): Long {
        var pen = 0L
        val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
        if (lo != Int.MIN_VALUE && lo != 0 && n < lo) pen += (lo - n).toLong() * 90L
        if (hi != Int.MAX_VALUE && n > hi) pen += (n - hi).toLong() * 45L
        val t = p.apt[i][k]
        if (t >= 0) pen += kotlin.math.abs(n - t).toLong()
        return pen
    }

    private fun destroyRepairDayAt(state: MagiState, schedule: Array<IntArray>, j: Int, rng: Random) {
        val p = cachedProblem(state)
        if (p.T == 0) return
        // [soft-aware destroy-repair / 実測検証 tools/nsp_bench.py] 従来はランダム順で穴を埋めるだけ(soft無視)で、
        //   等価ベンチでは soft-aware 修復が AUC -24%〜-34% と唯一の大幅改善だった。ここで同じレバーを適用:
        //   非希望セルを休へ destroy → 各需要を「割当の marginal soft が最小の休スタッフ」で repair。
        //   休→k のみ移すため被覆穴を新たに作らない。希望固定は保持。受理(SA/isBetter)が最終採否=安全。
        val cnt = Array(p.S) { IntArray(p.K) }
        for (i in 0 until p.S) for (jj in 0 until p.T) { val k = schedule[i][jj]; if (k in 0 until p.K) cnt[i][k]++ }
        // destroy: 非希望セルを休(0)へ。cnt も同期。
        for (i in 0 until p.S) {
            if (p.wish[i][j] >= 0) continue
            val old = schedule[i][j]
            if (old != 0 && old in 0 until p.K) { schedule[i][j] = 0; cnt[i][old]--; cnt[i][0]++ }
        }
        val covJ = IntArray(p.K)
        for (i in 0 until p.S) { val k = schedule[i][j]; if (k in 0 until p.K) covJ[k]++ }
        // repair: 各勤務シフトの需要を soft 最小の休スタッフで満たす。
        for (k in 1 until p.K) {
            val need = p.need1[k][j]
            if (need <= 0) continue
            var miss = need - covJ[k]
            while (miss > 0) {
                var bestI = -1; var bestDelta = Long.MAX_VALUE
                for (i in 0 until p.S) {
                    if (schedule[i][j] != 0 || p.wish[i][j] >= 0 || !p.canDo(i, k)) continue
                    val delta = staffCountPenaltyAt(p, i, k, cnt[i][k] + 1) - staffCountPenaltyAt(p, i, k, cnt[i][k])
                    if (delta < bestDelta) { bestDelta = delta; bestI = i }
                }
                if (bestI < 0) break
                schedule[bestI][j] = k; cnt[bestI][k]++; cnt[bestI][0]--; covJ[k]++; miss--
            }
        }
    }

    private fun destroyRepairStaff(state: MagiState, schedule: Array<IntArray>, rng: Random) {
        val p = cachedProblem(state)
        if (p.S == 0) return
        destroyRepairStaffAt(state, schedule, rng.nextInt(p.S), rng)
    }

    private fun destroyRepairStaffAt(state: MagiState, schedule: Array<IntArray>, i: Int, rng: Random) {
        val p = cachedProblem(state)
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isEmpty()) return
        // [soft-aware staff-DR / 実測 tools/nsp_bench.py --real: staff+viol で実データ final -49.5%]
        //   非希望セルを休へ destroy → 各日の被覆穴を「staff i の marginal soft 最小のシフト」で repair。
        //   被覆穴のみ埋める(過剰=covO を作らない)。希望固定は保持。スコアリング不変=Δ×フル無関係。
        val cntI = IntArray(p.K)
        for (jj in 0 until p.T) { val k = schedule[i][jj]; if (k in 0 until p.K) cntI[k]++ }
        for (j in 0 until p.T) {
            if (p.wish[i][j] >= 0) continue
            val old = schedule[i][j]
            if (old != 0 && old in 0 until p.K) { schedule[i][j] = 0; cntI[old]--; cntI[0]++ }
        }
        for (j in 0 until p.T) {
            if (p.wish[i][j] >= 0 || schedule[i][j] != 0) continue
            var bestK = -1; var bestDelta = Long.MAX_VALUE
            for (k in 1 until p.K) {
                if (!p.canDo(i, k)) continue
                val need = p.need1[k][j]
                if (need <= 0) continue
                var cov = 0
                for (x in 0 until p.S) if (schedule[x][j] == k) cov++
                if (cov >= need) continue
                val delta = staffCountPenaltyAt(p, i, k, cntI[k] + 1) - staffCountPenaltyAt(p, i, k, cntI[k])
                if (delta < bestDelta) { bestDelta = delta; bestK = k }
            }
            if (bestK >= 0) { schedule[i][j] = bestK; cntI[bestK]++; cntI[0]-- }
        }
    }

    private fun destroyRepairViolations(state: MagiState, schedule: Array<IntArray>, report: ViolationReport, rng: Random) {
        val p = cachedProblem(state)
        val keys = report.violations.keys.toList()
        if (keys.isEmpty()) { randomAllowedCell(state, schedule, rng); return }
        repeat(min(8, keys.size)) {
            val key = keys[rng.nextInt(keys.size)]
            val i = key.substringBefore(',').toIntOrNull() ?: return@repeat
            val j = key.substringAfter(',').toIntOrNull() ?: return@repeat
            if (i !in 0 until p.S || j !in 0 until p.T || p.wish[i][j] >= 0) return@repeat
            val allowed = p.allowedShiftsForStaff(i)
            if (allowed.isEmpty()) return@repeat
            // [soft-aware violations / 実測で実データ final -22.6%] 違反セルを、staff i の現状回数で
            //   marginal soft(old→k)最小のシフトへ再割当(従来はランダム)。スコアリング不変=Δ×フル無関係。
            val cntI = IntArray(p.K)
            for (jj in 0 until p.T) { val k = schedule[i][jj]; if (k in 0 until p.K) cntI[k]++ }
            val old = schedule[i][j]
            var bestK = old; var bestDelta = Long.MAX_VALUE
            for (k in allowed) {
                if (k == old) continue
                val dOld = if (old in 0 until p.K) staffCountPenaltyAt(p, i, old, cntI[old] - 1) - staffCountPenaltyAt(p, i, old, cntI[old]) else 0L
                val dK = staffCountPenaltyAt(p, i, k, cntI[k] + 1) - staffCountPenaltyAt(p, i, k, cntI[k])
                val delta = dOld + dK
                if (delta < bestDelta) { bestDelta = delta; bestK = k }
            }
            if (bestK != old) schedule[i][j] = bestK
        }
    }

    private fun randomAllowedCell(state: MagiState, schedule: Array<IntArray>, rng: Random) {
        val p = cachedProblem(state)
        if (p.S == 0 || p.T == 0) return
        val i = rng.nextInt(p.S)
        val j = rng.nextInt(p.T)
        if (p.wish[i][j] >= 0) return
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isNotEmpty()) schedule[i][j] = allowed[rng.nextInt(allowed.size)]
    }

    private fun swapWithinStaff(state: MagiState, schedule: Array<IntArray>, rng: Random) {
        val p = cachedProblem(state)
        if (p.S == 0 || p.T < 2) return
        val i = rng.nextInt(p.S)
        var a = rng.nextInt(p.T)
        var b = rng.nextInt(p.T)
        if (a == b) b = (b + 1) % p.T
        if (p.wish[i][a] >= 0 || p.wish[i][b] >= 0) return
        val tmp = schedule[i][a]
        schedule[i][a] = schedule[i][b]
        schedule[i][b] = tmp
    }

    private fun perturb(state: MagiState, base: Array<IntArray>, rng: Random, strength: Double): Array<IntArray> {
        val p = cachedProblem(state)
        val out = base.copy2D()
        val n = max(1, (p.S * p.T * strength).toInt())
        repeat(n) { randomAllowedCell(state, out, rng) }
        return out
    }

    private fun rsiGenerateHypothesis(state: MagiState, base: Array<IntArray>, report: ViolationReport, focus: String, rng: Random): Array<IntArray> {
        val out = base.copy2D()
        val p = cachedProblem(state)
        when (focus) {
            "covU", "c41", "c41s" -> repeat(8) { destroyRepairDay(state, out, rng) }   // c41s=スキル群の1日人数(c41と同型)
            "low", "high", "c2" -> repeat(8) { destroyRepairStaff(state, out, rng) }
            "groupViol", "pref", "c3n" -> {
                val fixed = hf67HardRepair(state, out, rng).schedule
                for (i in 0 until p.S) for (j in 0 until p.T) out[i][j] = fixed[i][j]
            }
            else -> repeat(12) { destroyRepairViolations(state, out, report, rng) }
        }
        return out
    }

    private fun maxViolatedFamily(report: ViolationReport, avoid: Set<String> = emptySet()): String {
        val order = listOf("groupViol", "covU", "pref", "c3n", "low", "high", "c41", "c41s", "c2", "covO", "c42", "c42s", "c1", "c3", "c3m", "c3mn")
        // [HF63] まず deprioritize 対象(構造的に充足困難)を除いた族から最大違反を選ぶ。
        if (avoid.isNotEmpty()) {
            var b = "total"; var bc = -1
            for (key in order) {
                if (key in avoid) continue
                val n = report.breakdown[key] ?: 0
                if (n > bc) { bc = n; b = key }
            }
            if (bc > 0) return b   // 達成可能な族に違反が残っていればそれを優先
        }
        var best = "total"
        var bestCount = -1
        for (key in order) {
            val n = report.breakdown[key] ?: 0
            if (n > bestCount) {
                bestCount = n
                best = key
            }
        }
        return best
    }

    private fun better(a: ViolationReport, b: ViolationReport): Boolean = when {
        a.hard != b.hard -> a.hard < b.hard
        a.total != b.total -> a.total < b.total
        else -> a.weightedScore < b.weightedScore
    }

    /**
     * [品質向上] エリート解の Path Relinking（Glover, Laguna & Martí 2000 / Scatter Search）。
     * 並列ポートフォリオが保持する精鋭解（[lastAlternatives]）と現行最良を「再結合」し、両者の中間に
     * しばしば存在する、どの単独軌道でも届かない良解を拾う。best を起点に各 alt へ強制マーチ（差分セルを
     * alt 値へ順次適用）し、経路上の最良中間解を保持。常に best 起点から評価するので**退化しない**。
     * 早期停止で空いた予算を、頭打ちした同種探索ではなく「別種の探索」に充てて品質を底上げする。
     */
    fun elitePathRelink(
        state: MagiState,
        best: Array<IntArray>,
        alternatives: List<Array<IntArray>>,
        shouldStop: () -> Boolean,
    ): Pair<Array<IntArray>, ViolationReport> {
        var bestSched = best.copy2D()
        var bestRep = UnifiedViolationChecker.check(state, bestSched)
        if (alternatives.isEmpty()) return bestSched to bestRep
        for (alt in alternatives) {
            if (shouldStop()) break
            val cur = bestSched.copy2D()              // 常に現行最良から再結合（中間最良は別管理＝退化なし）
            var curRep = UnifiedViolationChecker.check(state, cur)
            val diffs = ArrayList<Pair<Int, Int>>()
            for (i in cur.indices) for (j in cur[i].indices) {
                if (i < alt.size && j < alt[i].size && cur[i][j] != alt[i][j]) diffs.add(i to j)
            }
            if (diffs.isEmpty()) continue
            // 違反セルを先に動かす（インパクト大の組み替えを前倒し）。
            val vcells = HashSet<Pair<Int, Int>>()
            for (vkey in curRep.violations.keys) {
                val parts = vkey.split(',')
                val ci = parts.getOrNull(0)?.toIntOrNull(); val cj = parts.getOrNull(1)?.toIntOrNull()
                if (ci != null && cj != null) vcells.add(ci to cj)
            }
            diffs.sortBy { if (it in vcells) 0 else 1 }
            for ((i, j) in diffs) {
                if (shouldStop()) break
                cur[i][j] = alt[i][j]                 // alt へ向けた強制マーチ
                curRep = UnifiedViolationChecker.check(state, cur)
                if (better(curRep, bestRep)) { bestSched = cur.copy2D(); bestRep = curRep }
            }
        }
        return bestSched to bestRep
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
    private fun actualSeed(seed: Long): Long = if (seed == 0L) System.nanoTime() else seed
}
