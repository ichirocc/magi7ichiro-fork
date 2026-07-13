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
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.PI
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

/** ALNS の受理基準。SA=Boltzmann(従来) / GREAT_DELUGE=時間予定型 Great Deluge（水位以下を受理） /
 *  LAM_ADAPTIVE=Lam-Delosme適応冷却（受理率を目標値に追従させ温度を自己調整。Boltzmann受理を使う）。 */
enum class AcceptMode { SA, GREAT_DELUGE, LAM_ADAPTIVE }

/** ALNS の演算子選択方式。ROULETTE=重み比例(従来) / THOMPSON=Thompson sampling(平滑報酬opWを
 *  事後平均、時間減衰ノイズで探索する確率的選択。停滞しにくく不確実性下で原理的)。 */
enum class OpSelectMode { ROULETTE, THOMPSON }

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
    /** ALNS の演算子選択方式。並列仮説の一部に Thompson sampling を割当てて選択戦略を多様化（W0は ROULETTE でベースライン保持）。 */
    val opSelect: OpSelectMode = OpSelectMode.ROULETTE,
    /** 局所移動に短期Tabu記憶を適用（直近変更セルの即時復帰を tenure 期間禁止。global最良更新時はアスピレーションで解禁）。
     *  並列仮説の一部にのみ割当て（W0はOFFでベースライン保持）。destroy/repair等の大近傍手は対象外。 */
    val tabu: Boolean = false,
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

    /** [論文活用] 並列仮説で受理戦略を多様化（W0,W1=SA基準 / W2,W4=Great Deluge / W3=Lam適応冷却）。
     *  W0 は常に SA でベースライン保持＝退化防止。 */
    internal fun roleAcceptFor(i: Int): AcceptMode = when (i) {
        2, 4 -> AcceptMode.GREAT_DELUGE
        3 -> AcceptMode.LAM_ADAPTIVE
        else -> AcceptMode.SA
    }

    /** [論文活用] 並列仮説で演算子選択を多様化（W1=Thompson sampling / 他=roulette）。
     *  W0 は常に roulette でベースライン保持＝退化防止。 */
    internal fun roleOpSelectFor(i: Int): OpSelectMode = if (i == 1) OpSelectMode.THOMPSON else OpSelectMode.ROULETTE

    /**
     * 時間予定型 Great Deluge の水位（Burke, Bykov, Newall & Petrovic 2004）。
     * frac=1(序盤)で initial、frac=0(終盤)で best へ線形降下。候補スコア ≤ 水位 なら受理。
     */
    internal fun greatDelugeLevel(initial: Double, best: Double, frac: Double): Double =
        best + (initial - best) * frac.coerceIn(0.0, 1.0)

    /** 直近の並列探索で得た「他の案」（採用案以外の候補スケジュール、品質順・最大3件）。 */
    @Volatile var lastAlternatives: List<Array<IntArray>> = emptyList()
        private set

    /** [他の案の保全] optimize() は入口で lastAlternatives を空にするため、追加精製(ExtraRefine)等で
     *  optimize() を再呼出しする側が「本走行の他の案」を退避→復元できるようにする（V6FinalPort 専用）。 */
    fun restoreAlternatives(saved: List<Array<IntArray>>) { lastAlternatives = saved }

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
        // [N1b] 入口修復(hf67)は better(hard→total→weighted) 改善時のみ採用。既に良好な入力
        //   （前回結果の再最適化など）を破壊し、探索を劣化seedに係留する事故を防ぐ
        //   （運用ログ実例: 入力214 → 修復後HARD4/250 → 275秒が回復に浪費）。hf66(群内正規化)は無条件維持。
        val entryReport = UnifiedViolationChecker.check(state, schedule)
        val repaired = hf67HardRepair(state, schedule, Random(actualSeed(options.seed) xor 0x67L)).schedule
        val repairedReport = UnifiedViolationChecker.check(state, repaired)
        val hf67Adopted = better(repairedReport, entryReport)
        if (hf67Adopted) schedule = repaired
        val entryBoard = schedule.copy2D()   // [N1c] 内側番兵用に入力の勤務表を保持
        val entryBoardReport = if (hf67Adopted) repairedReport else entryReport
        var logs = listOf(
            MirrorLog(tag = "V6Dispatcher", message = "algorithm=$chosen budget=${options.totalBudgetSec}s workers=${options.workers.coerceIn(1, 5)}（設定${options.workers}）"),
            MirrorLog(tag = "HF67", message = if (hf67Adopted)
                "入口修復を採用 HARD ${entryReport.hard}->${repairedReport.hard} / total ${entryReport.total}->${repairedReport.total}"
            else
                "入口修復を見送り（入力の方が良好: HARD ${entryReport.hard}/total ${entryReport.total} ≦ 修復後 HARD ${repairedReport.hard}/total ${repairedReport.total}）"),
        )
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
        // [N1c] 内側番兵: 最終結果が入力の勤務表より劣るなら入力の勤務表へ復帰（FinalPortの外側Sentinelと二重化）。
        //   全段keep-bestのため通常は発火しない。発火時は「予算が改善に寄与しなかった」ことの可視化を兼ねる。
        if (better(entryBoardReport, finalReport)) {
            logs = logs + MirrorLog(level = "W", tag = "V6Dispatcher",
                message = "内側番兵: 結果(HARD=${finalReport.hard}/total=${finalReport.total})が入力の勤務表(HARD=${entryBoardReport.hard}/total=${entryBoardReport.total})より劣化のため入力の勤務表を採用")
            return V6OptimizerResult(entryBoard, entryBoardReport.copy(logs = logs + entryBoardReport.logs), chosen, logs, result.iterations + polished.iterations, nowMs() - started)
        }
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
                val res = run(i, options.copy(workers = 1, seed = base + (i + 1) * 0x9E3779B1L, explore = roleExploreFor(i), accept = roleAcceptFor(i), opSelect = roleOpSelectFor(i))) { phase, report, iters, elapsed ->
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
        // [過程検証] 各仮説の個別結果・多様性（相異なる解の数）・保持した他の案数をログ化し、探索過程を後から検証できるようにする。
        //   各仮説の合計が揃っていれば収束、ばらけていれば多様な探索ができている、と判別できる。
        val perHyp = results.sortedWith(compareBy({ it.report.hard }, { it.report.total }))
            .joinToString("  ") { r -> "[必須${r.report.hard}/合計${r.report.total}${if (r === best) "★採用" else ""}]" }
        val distinctSols = results.map { r -> r.schedule.joinToString("|") { row -> row.joinToString(",") } }.distinct().size
        val verifyLog = MirrorLog(tag = "仮説検証", message = "各仮説 ${results.size} 本の結果: $perHyp / 相異なる解=${distinctSols}件 / 他の案として保持=${lastAlternatives.size}件")
        best.copy(phaseLogs = best.phaseLogs + extra + verifyLog, iterations = totalIters)
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

    /** [Thompson sampling] 演算子選択。平滑報酬 opW を事後平均、探索ノイズを反復で減衰させた
     *  ガウス事後から各演算子の標本を引き、最大の演算子を選ぶ。重み比例(roulette)より停滞しにくく、
     *  不確実性下での選択が原理的。ノイズσは序盤大きく(探索)→終盤小さく(活用)アニールする。 */
    private fun thompsonSelect(opW: DoubleArray, iter: Long, rng: Random): Int {
        val sigma = 0.5 / sqrt(1.0 + iter / 500.0)
        var bestOp = 0
        var bestSample = Double.NEGATIVE_INFINITY
        for (k in opW.indices) {
            val u1 = rng.nextDouble().coerceIn(1e-9, 1.0)
            val u2 = rng.nextDouble()
            val g = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)   // Box-Muller 標準正規
            val s = opW[k] + g * sigma
            if (s > bestSample) { bestSample = s; bestOp = k }
        }
        return bestOp
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
            SaParams(t0 = saT0, workers = options.workers, budgetMs = budgetSec * 1000L, softPolish = options.softPolish, shouldStop = shouldStop, seed = options.seed),
        ) { pr ->
            if (pr.elapsedMs % 1000L < 220L) onProgress("V5 SA", lastReport, pr.totalIters, pr.elapsedMs)
        }
        val repaired = hf67HardRepair(state, res.schedule, Random(actualSeed(options.seed) xor 0x5L))
        var outSched = repaired.schedule
        var report = UnifiedViolationChecker.check(state, outSched)
        // [退化防止番兵 / 実機ログ起因] runAlns(578行)と同じ入力比keep-best。従来 runV5 だけ番兵が無く、SA+修復が
        //   入力より悪化した結果をそのまま返していた。RSI++ は Phase1 Seed に runV5 を使い、以降の各段は前段比
        //   keep-best のため、Phase1 の劣化(実測: 入力HARD=1/195 → Seed HARD=2/229)が全チェーンへ伝播し、
        //   最後にディスパッチャ番兵が入力へ復帰＝予算全体が無駄になっていた(実機で 275s×2回)。入力を品質床に
        //   することで以降の全フェーズが「入力以上」から積み上がる。SA が入力より良い解を見つけた場合は素通し
        //   ＝多様化は維持。スコアリング不変(選択のみ・better()=hard→total→weighted)。
        val baseSched = normalizeSchedule(initial, p)
        val baseReport = UnifiedViolationChecker.check(state, baseSched)
        val keptInput = better(baseReport, report)
        if (keptInput) { outSched = baseSched; report = baseReport }
        lastReport = report
        val logs = listOf(MirrorLog(tag = "RunMAGI_V5",
            message = "高速SA完了 HARD=${report.hard} total=${report.total} iter=${res.totalIters}" +
                if (keptInput) "（SA結果が入力より悪化のため入力を維持=番兵）" else "")) + repaired.logs
        return V6OptimizerResult(outSched, report.copy(logs = logs + report.logs), V6Algorithm.V5, logs, res.totalIters, nowMs() - t0)
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
        // [高速化/零アロケ] op0-2(copy系)は毎反復 cur.copy2D() を新規確保していた（数百万回/実行のGC圧）。
        //   使い回しのスクラッチ盤面へ arraycopy し、採用時は cur とスワップ（旧 cur が次のスクラッチになる）。
        //   hf67 経由(fixed!==cand)の採用時は fixed が新規配列なのでスクラッチはそのまま次反復で再利用。
        var scratchBuf = Array(p.S) { IntArray(p.T) }
        val diffBuf = IntArray(p.S * p.T)   // scratch: flat indices i*T+j of changed cells (zero-alloc)

        // [ネイティブ加速 Stage8b] runAlns の内側ループ(下の while)を C++ ALNS チャンクへ JNI 委譲する。
        //   Kotlin 保持: restart 境界の perturb+hf67・進捗/liveBest・キャンセル・2層番兵。problem は read-only で
        //   restart 跨ぎ共有。ネイティブ不可 or 番兵発火時は下の従来 Kotlin ループへフォールバック（退化不能）。
        val nativeProblem = if (NativeGate.usable) runCatching { NativeEval.createHandle(p) }.getOrDefault(0L) else 0L
        val fullEvaluator = Evaluator(p)
        val bestFlat = IntArray(p.S * p.T)
        suspend fun runRestartNative(cur: Array<IntArray>, deadline: Long, perSec: Int, r: Int): Boolean {
            val alns = runCatching {
                NativeBridge.nativeAlnsCreate(nativeProblem, NativeEval.flatten(cur), rng.nextLong(),
                    options.accept.ordinal, options.opSelect.ordinal, options.explore)
            }.getOrDefault(0L)
            if (alns == 0L) { NativeGate.disable("ALNS状態生成NG"); return false }
            try {
                // [全体計算の最小化] checker.check と liveBest 全面コピーは表示専用のため 250ms 周期に間引く。
                //   2層目番兵の fullEval（正しさ）は従来どおり改善チャンクごとに実施＝退化不能は不変。
                var reportStale = false
                var lastUiMs = 0L
                fun syncReport() {
                    if (reportStale) {
                        globalReport = UnifiedViolationChecker.check(state, globalBest)
                        liveBest = globalBest.map { it.toList() }
                        reportStale = false
                    }
                }
                while (nowMs() < deadline && !shouldStop()) {
                    coroutineContext.ensureActive()
                    val frac = ((deadline - nowMs()).toDouble() / max(1.0, perSec * 1000.0)).coerceIn(0.0, 1.0)
                    val ret = NativeBridge.nativeAlnsChunk(alns, 200, frac)
                    if (ret.size < 6 || ret[0] != 0L) { syncReport(); NativeGate.disable("ALNSチャンク整合性NG(status=${ret.getOrNull(0)})"); return false }
                    itersTotal += ret[4]
                    if (ret[3] == 1L && ret[2] < globalScore) {
                        // [2層目の番兵] best 更新を Kotlin Evaluator でフル照合（Long== 許容誤差なし）。
                        NativeBridge.nativeAlnsRead(alns, 0, bestFlat)
                        val bestSol = NativeEval.unflatten(bestFlat, p.S, p.T)
                        val kScore = fullEvaluator.fullEval(bestSol)
                        if (kScore != ret[2]) { syncReport(); NativeGate.disable("ALNS Kotlin照合NG(native=${ret[2]} kotlin=$kScore)"); return false }
                        globalBest = bestSol; globalScore = kScore
                        lastImproveIter = itersTotal
                        reportStale = true
                    }
                    val nowUi = nowMs()
                    if (reportStale && nowUi - lastUiMs >= 250) { syncReport(); lastUiMs = nowUi }
                    onProgress("ALNS restart ${r + 1}/$restarts", globalReport, itersTotal, nowMs() - started)
                    yield()
                }
                syncReport()   // restart 終端ログ(HARD/total)と liveBest を最終同期
                return true
            } finally {
                NativeBridge.nativeAlnsDestroy(alns)
            }
        }

        try {
        for (r in 0 until restarts) {
            if (shouldStop()) break
            coroutineContext.ensureActive()
            // [restart 摂動] 一律 strength=0.18。非線形スケジュール(2.51)は nsp_bench --real の final 品質で
            //   +101% 悪化と実測されたため revert(序盤の大摂動が強い repair 下で良解を壊し最終品質を損なう)。
            var cur = if (r == 0) globalBest.copy2D() else perturb(state, globalBest, rng, strength = (0.18 * options.explore).coerceIn(0.05, 0.6))
            cur = hf67HardRepair(state, cur, rng).schedule
            val deadline = nowMs() + per * 1000L
            // [Stage8b] ネイティブ ALNS チャンクへ委譲。不可 or 番兵発火なら下の従来 Kotlin ループへ。
            val usedNative = nativeProblem != 0L && NativeGate.enabled && runRestartNative(cur, deadline, per, r)
            if (!usedNative) {
            var curReport = UnifiedViolationChecker.check(state, cur)
            eval.reset(cur)
            var curScore = eval.score()
            var curAug = gls.augment(cur)   // [GLS] 現行盤面の penalty 拡張分を増分維持（再構築は restart 毎のみ）
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
            // [Lam適応冷却] W3 (accept==LAM_ADAPTIVE) のみ使用。観測受理率 lamAcc を Lam-Delosme の
            //   目標受理率(序盤0.44で平坦→中盤で線形降下→終盤≈0)に追従させ、温度 lamTemp を乗算的に自己調整。
            //   温度パラメータの手調整が不要になる。リスタートごとに高温から再開。他ワーカ/W0には無影響。
            var lamTemp = max(1.0, options.explore)
            var lamAcc = 0.44
            fun lamUpdate(accepted: Boolean) {
                lamAcc = 0.97 * lamAcc + 0.03 * (if (accepted) 1.0 else 0.0)
                val f = ((deadline - nowMs()).toDouble() / max(1.0, per * 1000.0)).coerceIn(0.0, 1.0)
                val target = when { f > 0.85 -> 0.44; f > 0.15 -> 0.44 * (f - 0.15) / 0.70; else -> 0.0 }
                lamTemp = (lamTemp * if (lamAcc > target) 0.998 else 1.002).coerceIn(0.03, 4.0)
            }
            while (nowMs() < deadline && !shouldStop()) {
                coroutineContext.ensureActive()
                var op = if (options.opSelect == OpSelectMode.THOMPSON) thompsonSelect(opW, iter, rng)
                         else rouletteSelect(opW, rng)
                // [賢いsoft集中] HARD が最良水準(curHard<=bestHard)に到達したら残り探索を soft 修復へ寄せる。
                //   HARD=0 なら積極的に(0.30)、HARD>0 の床(構造的に解けない covU/pref/c3n 等)では控えめに(0.15)
                //   op5(targeted repair=covO/c2/上下限/c41/c41s/c3Want/apt 修復)を優先。HARD>床 の間はHARD優先で不変。
                //   従来 curHard==0 限定だと、構造的HARD床から下がれない局面で soft研磨が一度も起動しなかった
                //   (apt超過/fair が放置)。最良HARD水準なら床>0 でも soft を磨くよう修正。
                val softFocusProb = if (globalScore / 1_000_000L == 0L) 0.30 else 0.15
                if (curScore / 1_000_000L <= globalScore / 1_000_000L && rng.nextDouble() < softFocusProb) op = 5
                // [HF290 役割分担] explore 倍率で受理温度を調整（探索=受理寛容/精製=厳格）。explore=1.0 は従来と同一。
                //   ただし LAM_ADAPTIVE は受理率追従の適応温度 lamTemp を使う（自己調整）。
                val temp = if (options.accept == AcceptMode.LAM_ADAPTIVE) lamTemp
                           else max(0.03, (deadline - nowMs()).toDouble() / max(1.0, per * 1000.0) * options.explore)
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
                            if (!p.wishLocked(i, ja) && !p.wishLocked(i, jb)) {
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
                            if (!p.wishLocked(i, j)) {
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
                            if (!p.wishLocked(i1, j) && !p.wishLocked(i2, j)) {
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
                        if (options.accept == AcceptMode.LAM_ADAPTIVE) lamUpdate(accepted)
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
                    val cand = scratchBuf
                    for (i2 in 0 until p.S) System.arraycopy(cur[i2], 0, cand[i2], 0, p.T)
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
                    if (options.accept == AcceptMode.LAM_ADAPTIVE) lamUpdate(accepted)
                    if (accepted) {
                        // [零アロケ] スクラッチ採用時は cur とスワップ（旧 cur を次のスクラッチへ）。
                        if (fixed === scratchBuf) { val t = cur; cur = fixed; scratchBuf = t } else cur = fixed
                        curScore = ns; curAug += moveAug
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
            }   // if (!usedNative)
            logs.add(MirrorLog(iter = itersTotal, tag = "RunMAGI_ALNS", message = "restart=${r + 1}/$restarts best HARD=${globalReport.hard} total=${globalReport.total} GLS=${if (usedNative) "native" else gls.kickCount().toString()}"))
        }
        } finally { if (nativeProblem != 0L) NativeBridge.nativeDestroyProblem(nativeProblem) }
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
        // [HARD=0非到達への配慮 / 静的covU床] 構造的 covU 下限（有資格者を全員就けても埋まらない席=forcedCovU）は
        //   最適化中に不変。covU がこの床に達したら「これ以上 covU は下げられない」と静的に確定するので、HF63 の
        //   動的検知(約3ラウンド無改善を要する)を待たず round 0 から即 focus 除外し、RSI の残ラウンドを解ける族
        //   (他HARD/SOFT)へ回す。床=0（構造的不足なし＝HARD=0 到達可能な一般ケース）なら常に no-op＝挙動不変。
        //   focus 選択のみの変更でスコアリング不変（keep-best=better() が結果を担保）＝退化なし・3.74.0 と同方針。
        val covUFloor = try { V6SanityPort.structuralHardFloor(state, cachedProblem(state)) } catch (_: Exception) { 0 }
        var stagnantRounds = 0   // [N4] better() 無改善の連続ラウンド数
        // [E9/状況適応] 直前ラウンドが「完全空振り」(候補不採用＋focus族の件数も不変)だった focus を
        //   次の1ラウンドだけ回避する軽い冷却。同一 focus の同一仮説を3連発する空転(実機: c3n×3R=~63s 無変化、
        //   HF63 の恒久判定は約3R を要す)を、c3n→c1→c3n… の交互へ多様化する。1ラウンド限定なので
        //   乱数運の悪い1回で族を見捨てない(恒久除外は従来どおり HF63 のみ)。focus 選択のみ＝スコアリング不変。
        var cooldownFocus: String? = null
        for (round in 0 until rounds) {
            if (shouldStop()) break
            coroutineContext.ensureActive()
            // [監査修正] HF63 は Web の per-iter 前提(5000 iter 無改善で infeasible)だが、native はここで per-round
            //   にしか呼べず cumulative iters を渡すと1ラウンドで ≫5000 跳び、解ける HARD を約1ラウンドで誤 infeasible
            //   判定→focus 除外していた。ラウンド境界の呼出に「ラウンド番号×1800」を渡し、閾値5000到達を約3ラウンドの
            //   連続無改善に引き伸ばす（class は Web 忠実移植のまま・呼出側で粒度を補正）。iters 自体は本来用途に不変。
            hf63.updateFromBreakdown(bestReport.breakdown, round * 1800)
            // [12h見直し] 動的(HF63)と静的(covU床)の avoid を分離して保持する。N4 早期脱出(下記)の発火条件は
            //   HF63 の動的検知のみでゲートしないと、構造的covU>0 のデータでは静的除外が round 0 から avoid を
            //   非空にし、「旧N4の厳密な部分集合」保証(650-654行)を破って2停滞ラウンドで RSI が即終了してしまう。
            val dynamicAvoid = hf63.infeasibleBreakdownKeys()
            val avoid = dynamicAvoid.toMutableSet()
            // [静的covU床] covU が構造的下限（covUFloor）に達している間は解けないので focus から即除外する。
            //   合法配置では covU >= covUFloor（下限）。担当外配置(groupViol)が混在すると covU が床を下回り得るが、
            //   その間 covU を focus しても無意味（groupViol が hard-first で先に選ばれる）なので `<=` で除外が正しい。
            if (covUFloor > 0 && (bestReport.breakdown["covU"] ?: 0) <= covUFloor) avoid.add("covU")
            // [E9] 冷却は focus 選択にのみ合流（HF63 ログ・N4 発火条件には混ぜない＝恒久判定と区別）。
            val focusAvoid = if (cooldownFocus != null) avoid + cooldownFocus!! else avoid
            val focus = maxViolatedFamily(bestReport, focusAvoid)
            if (avoid.isNotEmpty()) {
                logs.add(MirrorLog(iter = iters, tag = "HF63", message = "deprioritize ${avoid.joinToString(",")} → focus=$focus (round ${round + 1})"))
            }
            if (cooldownFocus != null) {
                logs.add(MirrorLog(iter = iters, tag = "RSIFocus", message = "直前ラウンド空振りのため ${cooldownFocus} を1ラウンド休止 → focus=$focus (round ${round + 1})"))
            }
            val focusedBefore = bestReport.breakdown[focus] ?: 0
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
                stagnantRounds = 0
                cooldownFocus = null   // [E9] 進展あり＝冷却解除
            } else {
                stagnantRounds++
                // [E9] 完全空振り(不採用＋focus族の件数が減っていない)なら次ラウンドだけこの focus を休止。
                //   候補が focus 族を減らしていた(=方向は有望だが総合で負けた)場合は冷却しない。
                cooldownFocus = if (focus != "total" && (candReport.breakdown[focus] ?: 0) >= focusedBefore) focus else null
            }
            logs.add(MirrorLog(iter = iters, tag = "RunMAGI_RSI", message = "round=${round + 1}/$rounds focus=$focus best HARD=${bestReport.hard} total=${bestReport.total}"))
            liveBest = best.map { it.toList() }   // [DefragLiveView] 計算中ライブ盤面を公開
            onProgress("RSI $focus", bestReport, iters, nowMs() - started)
            // [N4改] focus枯渇の早期終了。旧版は「2R無改善」だけで打ち切っていたが、これは達成可能族が
            //   残る場合でもランダム探索(destroy-repair)を早期に切り、乱数運の悪い2Rで本来伸びる盤面を捨てうる
            //   （proxyでA/B不能な領域＝安全側に倒す）。発火条件を hf63 が infeasible 族を検出済み(avoid非空)＝
            //   「達成可能な focus を撃ち尽くした」ときに限定する。これは旧条件の厳密な部分集合のため、
            //   旧N4より早く止まることはない＝品質は退化しない。avoid が空(まだ狙える族がある)の間は全予算で探索。
            //   ※後段(hf80)は固定予算のため厳密な「予算移譲」ではなく、無改善ラウンドの空転停止(電池/熱/時間節約)。
            // [12h見直し] 発火は動的検知(dynamicAvoid=HF63)のみでゲートする。静的covU床(合流後のavoid)を使うと
            //   構造的covU>0 のデータで round 0 から常時武装し、旧N4保証(上記)を破る。
            if (stagnantRounds >= 2 && dynamicAvoid.isNotEmpty()) {
                logs.add(MirrorLog(iter = iters, tag = "RunMAGI_RSI", message = "早期終了: focus枯渇(deprioritize=${dynamicAvoid.size}族)＋${stagnantRounds}R無改善（残${rounds - round - 1}Rの空転を停止）"))
                break
            }
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
            val fired = lr.chain3 + lr.chain4 + lr.rect + lr.blkN > 0
            // [監査#1] Chain3/4の受理(gateW)はweighted単層でHARD増を相殺受理し得るため、採用は
            //   runRsiと同じ better(hard→total→weighted) でゲートする（素通しでHARD悪化を最終出力しない）。
            // ※ run{} 末尾のため if を式位置にしない（else-if 連鎖は式扱いとなり全分岐必須。ネストifの文形式で書く）。
            if (fired) {
                if (better(lr.report, best.report)) {
                    bestSched = lr.schedule
                    logs.add(MirrorLog(tag = "EarlyChain", message = "早期循環フック改善 (Chain3=${lr.chain3} Chain4=${lr.chain4} Rect=${lr.rect} BlkN=${lr.blkN}) HARD=${lr.report.hard} total=${lr.report.total}"))
                    logs.addAll(lr.logs)
                } else {
                    logs.add(MirrorLog(tag = "EarlyChain", message = "採用見送り（hard/total非改善ガード） HARD=${lr.report.hard} total=${lr.report.total}"))
                }
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
                // [N1a] 充填量は per-cell 実需要（#4b: OR/AND）。旧 need1 のみ基準では P2 で救済済みの
                //   セル（休日体制など P1>P2）まで埋めに行き、既良盤面を壊していた。
                var miss = p.covUCell(k, j, cov[j][k])
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
                    if (p.wishLocked(i, jj) || out[i][jj] == k) continue
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
     * [ソフト研磨専用] 現在の盤面をHARDガード付きで局所研磨し、SOFTのみ削減する公開エントリ。
     * 破壊/多様化フェーズは行わず、hf80PostPolish の keep-best＋退化防止により入力以上の盤面のみ返す
     * （HARD=0 は壊さない）。最適化(もう一度つくる)と違い、必須が一時的に増えることはない。
     */
    suspend fun softPolishOnly(
        state: MagiState,
        schedule: Array<IntArray>,
        seconds: Int,
        seed: Long = 0x50F11L,
        shouldStop: () -> Boolean = { false },
    ): Array<IntArray> = hf80PostPolish(state, schedule, max(1, seconds), seed, shouldStop).schedule

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
        var iters = 0L
        val deadline = started + seconds * 1000L
        // [E10/停滞早期終了] 実機ログで PostPolish が 45s枠を最後まで走り切って改善0（40.977s/40.988s の2例）
        //   ＝重研磨済み盤面ではプラトー後の期待値が低い。best が枠の1/5(下限3s)無改善なら早期に返す。
        //   keep-best＋末尾の入力比番兵(better(baseReport,bestReport)→入力復帰)のため品質は不変＝時間/電池だけ節約
        //   （2.65.0 HF66 / 2.67.0 停滞ウォッチドッグと同方針）。native/Kotlin 両経路で共通。
        val stallMs = max(3000L, seconds * 1000L / 5)
        var stalled = false
        // [Stage10/第3期] ネイティブ経路: C++ PolishChunk（同一オペ構成11-way・同一受理・keep-best）＋2層番兵。
        //   枠を消費し切れば早期 return。番兵発火時は「照合済み best」だけ引き継ぎ、下の Kotlin ループが
        //   残り時間を続行する（NativeGate は閉鎖済み＝以後の実行は全て Kotlin）。
        val nat = runPolishChunksNative(p, best, deadline, stallMs, seed, shouldStop)
        iters += nat.iters
        nat.best?.let { best = it; bestReport = UnifiedViolationChecker.check(state, it) }
        if (nat.stalled) stalled = true
        if (nat.completed) {
            if (better(baseReport, bestReport)) { best = baseSched; bestReport = baseReport }
            val logs = listOf(MirrorLog(iter = iters, tag = "HF80", message = "PostPolish ${nowMs() - started}ms HARD=${bestReport.hard} total=${bestReport.total}（ネイティブ）" + if (stalled) "（停滞早期終了 枠${seconds}s）" else ""))
            return PolishResult(best, logs, iters)
        }
        var cur = best.copy2D()
        val eval = DeltaEvaluator(p)
        eval.reset(cur)
        var curScore = eval.score()
        var bestScore = curScore
        val diffBuf = IntArray(p.S * p.T)
        var lastImproveMs = nowMs()
        var lastBestMark = bestScore
        while (!shouldStop()) {
            val nowLoop = nowMs()
            if (nowLoop >= deadline) break
            if (bestScore < lastBestMark) { lastBestMark = bestScore; lastImproveMs = nowLoop }
            else if (nowLoop - lastImproveMs >= stallMs) { stalled = true; break }
            coroutineContext.ensureActive()
            val curHard = curScore / 1_000_000L
            val bestHard = bestScore / 1_000_000L
            when (rng.nextInt(11)) {
                0 -> {   // random allowed single cell (direct-eval)
                    if (p.S > 0 && p.T > 0) {
                        val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
                        if (!p.wishLocked(i, j)) {
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
                        if (!p.wishLocked(i, ja) && !p.wishLocked(i, jb)) {
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
                        if (!p.wishLocked(i1, j) && !p.wishLocked(i2, j)) {
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
        val logs = listOf(MirrorLog(iter = iters, tag = "HF80", message = "PostPolish ${nowMs() - started}ms HARD=${bestReport.hard} total=${bestReport.total}" + if (stalled) "（停滞早期終了 枠${seconds}s）" else ""))
        return PolishResult(best, logs, iters)
    }

    /** [Stage10] ネイティブ Polish 実行の結果。completed=枠を消費し切った(=Kotlin ループ不要) /
     *  best=Kotlin フル評価で照合済みの改善盤面(改善なし・未使用は null) / stalled=E10 停滞早期終了で戻った。 */
    private class NativePolishRun(val completed: Boolean, val best: Array<IntArray>?, val iters: Long, val stalled: Boolean)

    /**
     * [Stage10/第3期] hf80PostPolish の C++ チャンク駆動。SaOptimizer.runWorkerNative / runRestartNative と同型:
     * チャンク間でキャンセル・締切・E10 停滞を確認し、best 更新チャンクの盤面を Kotlin Evaluator.fullEval で
     * Long== 照合（2層目番兵。1層目=チャンク末尾の C++ 自己整合=status）。どちらか発火で NativeGate を閉じ
     * completed=false を返す＝呼び出し側の Kotlin ループが「照合済み best」から残り時間を続行（退化不能）。
     */
    private suspend fun runPolishChunksNative(
        p: Problem,
        initial: Array<IntArray>,
        deadline: Long,
        stallMs: Long,
        seed: Long,
        shouldStop: () -> Boolean,
    ): NativePolishRun {
        if (!NativeGate.usable) return NativePolishRun(false, null, 0L, false)
        val ph = runCatching { NativeEval.createHandle(p) }.getOrDefault(0L)
        if (ph == 0L) return NativePolishRun(false, null, 0L, false)
        try {
            val h = NativeBridge.nativePolishCreate(ph, NativeEval.flatten(initial), seed)
            if (h == 0L) return NativePolishRun(false, null, 0L, false)
            try {
                val fullEvaluator = Evaluator(p)
                val buf = IntArray(p.S * p.T)
                var verifiedBest = fullEvaluator.fullEval(initial)
                var best: Array<IntArray>? = null
                var iters = 0L
                var lastImproveMs = nowMs()
                while (!shouldStop()) {
                    val nowLoop = nowMs()
                    if (nowLoop >= deadline) return NativePolishRun(true, best, iters, false)
                    if (nowLoop - lastImproveMs >= stallMs) return NativePolishRun(true, best, iters, true)
                    coroutineContext.ensureActive()
                    // [全体計算の最小化] 400反復/チャンク＝チャンク末尾の自己整合フル評価の頻度を半減
                    //   （hint は best 改善駆動で更新されるためチャンク粒度に依存しない。締切/停滞/キャンセルの
                    //   確認粒度は ms 級のまま）。
                    val ret = NativeBridge.nativePolishChunk(h, 400)
                    if (ret.size < 5 || ret[0] != 0L) {
                        NativeGate.disable("Polishチャンク整合性NG(status=${ret.getOrNull(0)})")
                        return NativePolishRun(false, best, iters, false)
                    }
                    iters += ret[4]
                    if (ret[3] == 1L && ret[2] < verifiedBest) {
                        // [2層目番兵] best 更新チャンクの盤面を Kotlin フル評価で照合（Long== 許容誤差なし）。
                        NativeBridge.nativePolishRead(h, 0, buf)
                        val sol = NativeEval.unflatten(buf, p.S, p.T)
                        val k = fullEvaluator.fullEval(sol)
                        if (k != ret[2]) {
                            NativeGate.disable("Polish Kotlin照合NG(native=${ret[2]} kotlin=$k)")
                            return NativePolishRun(false, best, iters, false)
                        }
                        best = sol
                        verifiedBest = k
                        lastImproveMs = nowLoop
                    }
                    yield()
                }
                return NativePolishRun(true, best, iters, false)   // shouldStop=キャンセル/締切は呼び出し側の扱いと同じ
            } finally {
                NativeBridge.nativePolishDestroy(h)
            }
        } finally {
            NativeBridge.nativeDestroyProblem(ph)
        }
    }

    private fun bestStaffForCoverage(p: Problem, schedule: Array<IntArray>, counts: Array<IntArray>, j: Int, k: Int): Int {
        var bestI = -1
        var bestScore = Int.MAX_VALUE
        for (i in 0 until p.S) {
            if (!p.canDo(i, k)) continue
            if (p.wishLocked(i, j) && p.wish[i][j] != k) continue
            val old = schedule[i][j]
            if (old == k) continue   // [監査#3] 既就業者はスキップ（旧: return で当該(日,シフト)の充填全体が中断していた）
            val hi = p.rangeHi[i][k]
            val over = if (hi != Int.MAX_VALUE && counts[i][k] >= hi) 500 else 0
            val oldNeedCost = coverageShortageCost(p, schedule, j, old)
            // [監査#12] 符号修正: 旧 `- oldNeedCost` は「外すと不足が生じる職員」ほど優先ドナー化していた
            //   （最小スコア採用のため減算=優遇）。引き抜きコストとして加算し、休・過剰被覆側を優先する。
            val score = over + counts[i][k] * 3 + oldNeedCost
            if (score < bestScore) { bestScore = score; bestI = i }
        }
        return bestI
    }

    private fun coverageShortageCost(p: Problem, schedule: Array<IntArray>, j: Int, k: Int): Int {
        if (k !in 0 until p.K) return 0
        var cov = 0
        for (i in 0 until p.S) if (schedule[i][j] == k) cov++
        // [N1a] 引き抜きで per-cell 実需要(U)が増える＝不足を生む職員はコスト50（旧: need1のみ基準）。
        //   ちょうど充足のセル(U=0→1)も保護される点は旧 `cov <= need` と同等。
        return if (p.covUCell(k, j, cov - 1) > p.covUCell(k, j, cov)) 50 else 0
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
        val rest = restShiftIndex(state)   // [監査#2] 休はindex0固定でなく記号から解決（Level Zero: 全シフト同等・番号非依存）
        val cnt = Array(p.S) { IntArray(p.K) }
        for (i in 0 until p.S) for (jj in 0 until p.T) { val k = schedule[i][jj]; if (k in 0 until p.K) cnt[i][k]++ }
        // destroy: 非希望セルを休へ。休を担当できない職員は対象外（群外割当を作らない）。cnt も同期。
        for (i in 0 until p.S) {
            if (p.wishLocked(i, j) || !p.canDo(i, rest)) continue
            val old = schedule[i][j]
            if (old != rest && old in 0 until p.K) { schedule[i][j] = rest; cnt[i][old]--; cnt[i][rest]++ }
        }
        val covJ = IntArray(p.K)
        for (i in 0 until p.S) { val k = schedule[i][j]; if (k in 0 until p.K) covJ[k]++ }
        // [c41-aware / 実測 tools/nsp_bench.py: 群レンジ(cons41)があると小幅改善・無ければゼロ overhead で無害]
        //   群の「日次人数レンジ(cons41)」も marginal に加味し、群レンジ(上下限)も同時に研磨する。
        val hasC41 = p.cons41.isNotEmpty()
        val grpCnt = if (hasC41) Array(p.G) { IntArray(p.K) } else emptyArray()
        if (hasC41) for (i in 0 until p.S) { val k = schedule[i][j]; if (k in 0 until p.K) grpCnt[p.sgrp[i]][k]++ }
        fun c41DayMarg(g: Int, k: Int): Long {
            if (!hasC41) return 0L
            var d = 0L
            for (c in p.cons41) {
                if (c.groupIdx != g || c.shiftIdx != k) continue
                val z = grpCnt[g][k]; val z1 = z + 1
                val before = (if (z < c.l) c.l - z else 0) + (if (z > c.u) z - c.u else 0)
                val after = (if (z1 < c.l) c.l - z1 else 0) + (if (z1 > c.u) z1 - c.u else 0)
                d += (after - before).toLong()
            }
            return d
        }
        // repair: 各勤務シフトの需要を soft(個人 low/high/apt ＋ 群レンジ c41)最小の休スタッフで満たす。
        for (k in 0 until p.K) {
            if (k == rest) continue   // [監査#2] 休以外の全シフトを対象（旧: k in 1..K-1 の「0=休」前提）
            val need = p.need1[k][j]
            if (need <= 0) continue
            var miss = need - covJ[k]
            while (miss > 0) {
                var bestI = -1; var bestDelta = Long.MAX_VALUE
                for (i in 0 until p.S) {
                    if (schedule[i][j] != rest || p.wishLocked(i, j) || !p.canDo(i, k)) continue
                    val delta = staffCountPenaltyAt(p, i, k, cnt[i][k] + 1) - staffCountPenaltyAt(p, i, k, cnt[i][k]) + c41DayMarg(p.sgrp[i], k)
                    if (delta < bestDelta) { bestDelta = delta; bestI = i }
                }
                if (bestI < 0) break
                schedule[bestI][j] = k; cnt[bestI][k]++; cnt[bestI][rest]--; covJ[k]++; miss--
                if (hasC41) grpCnt[p.sgrp[bestI]][k]++
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
        val rest = restShiftIndex(state)   // [監査#2] 休の記号解決
        if (!p.canDo(i, rest)) return      // 休を担当できない職員は破壊修復の対象外（群外割当を作らない）
        // [soft-aware staff-DR / 実測 tools/nsp_bench.py --real: staff+viol で実データ final -49.5%]
        //   非希望セルを休へ destroy → 各日の被覆穴を「staff i の marginal soft 最小のシフト」で repair。
        //   被覆穴のみ埋める(過剰=covO を作らない)。希望固定は保持。スコアリング不変=Δ×フル無関係。
        val cntI = IntArray(p.K)
        for (jj in 0 until p.T) { val k = schedule[i][jj]; if (k in 0 until p.K) cntI[k]++ }
        for (j in 0 until p.T) {
            if (p.wishLocked(i, j)) continue
            val old = schedule[i][j]
            if (old != rest && old in 0 until p.K) { schedule[i][j] = rest; cntI[old]--; cntI[rest]++ }
        }
        // [高速化] 旧: 日×シフトごとに被覆を全職員走査(O(T×K×S))。盤面のうち本関数中に変わるのは staff i の行
        //   だけなので、被覆を一度だけ数え(O(S×T))、割当のたびに差分更新する(O(T×K))。挙動は再カウントと同一。
        val cov = Array(p.T) { IntArray(p.K) }
        for (x in 0 until p.S) for (j in 0 until p.T) { val k2 = schedule[x][j]; if (k2 in 0 until p.K) cov[j][k2]++ }
        for (j in 0 until p.T) {
            if (p.wishLocked(i, j) || schedule[i][j] != rest) continue
            var bestK = -1; var bestDelta = Long.MAX_VALUE
            for (k in 0 until p.K) {
                if (k == rest || !p.canDo(i, k)) continue
                val need = p.need1[k][j]
                if (need <= 0) continue
                if (cov[j][k] >= need) continue
                val delta = staffCountPenaltyAt(p, i, k, cntI[k] + 1) - staffCountPenaltyAt(p, i, k, cntI[k])
                if (delta < bestDelta) { bestDelta = delta; bestK = k }
            }
            if (bestK >= 0) { schedule[i][j] = bestK; cntI[bestK]++; cntI[rest]--; cov[j][bestK]++; cov[j][rest]-- }
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
            if (i !in 0 until p.S || j !in 0 until p.T || p.wishLocked(i, j)) return@repeat
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
        if (p.wishLocked(i, j)) return
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
        if (p.wishLocked(i, a) || p.wishLocked(i, b)) return
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
            // [実機ログ起因] groupViol/pref は hf67 の作用対象(hf66DataHardening=群外修正・希望反映)だが、
            //   c3n(禁止連続=HARD)は hf67 が一切作用しない(被覆/希望/下限のみ)＝c3n focus のラウンドが no-op 仮説で
            //   空転していた(実機3実行×計10ラウンドで c3n=1 不変→HF63 が c3n を誤 infeasible 判定)。c3n のセルは
            //   violations マップに載る(両端2セル)ので、違反セルを直接再割当する destroyRepairViolations(else)へ回す。
            //   仮説はラウンド単位 better() keep-best でゲート済＝退化なし。
            "groupViol", "pref" -> {
                val fixed = hf67HardRepair(state, out, rng).schedule
                for (i in 0 until p.S) for (j in 0 until p.T) out[i][j] = fixed[i][j]
            }
            else -> repeat(12) { destroyRepairViolations(state, out, report, rng) }
        }
        return out
    }

    private fun maxViolatedFamily(report: ViolationReport, avoid: Set<String> = emptySet()): String {
        val order = listOf("groupViol", "covU", "pref", "c3n", "low", "high", "c41", "c41s", "c2", "covO", "c42", "c42s", "c1", "c3", "c3m", "c3mn")
        // [D1/A1] 解ける HARD 族(groupViol/covU/pref/c3n)は件数に関わらず SOFT より先に focus する。
        //   旧実装は純・件数最大だったため、単一の c3n=1 が c1=118 等の高頻度 SOFT に埋もれ RSI が一度も HARD を
        //   狙わない失敗があった。目的関数 better() は辞書式(hard<<total<<weighted)で HARD 支配ゆえ focus も HARD
        //   優先が整合。avoid(HF63=構造的に充足困難)に入る HARD は「解けない」ため除外し無駄打ちを避ける(残予算は
        //   下段の SOFT 研磨へ)。この分岐は hard=0 のとき no-op＝全 soft の一般ケースは従来と不変。
        for (key in order) {
            if (key !in MirrorKeys.hard || key in avoid) continue
            if ((report.breakdown[key] ?: 0) > 0) return key
        }
        // 解ける HARD が無い(全て 0 か avoid)＝以降は SOFT。従来どおり非avoidの族から件数最大を返す。
        // [E8/実機ログ起因] 件数0の族は focus しない（旧: bestCount=-1 初期化のため、非avoidの正件数族が
        //   order に1つも無いと先頭 groupViol=0 が「0 > -1」で当選→hf67ルートがクリーン盤面への no-op 仮説
        //   ＝1ラウンド(実測~21s)空振りしていた。12シフト実機ログ round=4/5 focus=groupViol(件数0)で確認）。
        //   該当なしは "total" を返し、rsiGenerateHypothesis の else 分岐＝全違反セル hint の汎用修復
        //   (destroyRepairViolations, focus 非依存)ラウンドとして時間を有効化する。focus 選択のみ＝スコアリング不変。
        var best = "total"
        var bestCount = 0
        for (key in order) {
            if (key in avoid) continue
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
