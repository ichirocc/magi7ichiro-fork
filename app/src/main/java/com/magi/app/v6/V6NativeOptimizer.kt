package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.v6.OptimizerRuntime.RunSlot
import com.magi.app.v6.OptimizerRuntime.ownsStatics
import com.magi.app.v6.OptimizerRuntime.publishLiveBest
import com.magi.app.v6.OptimizerRuntime.recordInfeasibleScoped
import com.magi.app.v6.OptimizerRuntime.runSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
import com.magi.app.v6.SearchPolish.PolishResult
import com.magi.app.v6.SearchCommon.actualSeed
import com.magi.app.v6.SearchCommon.better
import com.magi.app.v6.SearchPolish.hf80PostPolish
import com.magi.app.v6.PortfolioSearch.hypothesisStartFor
import com.magi.app.v6.SearchCommon.nowMs
import com.magi.app.v6.PortfolioSearch.runAdaptivePortfolio
import com.magi.app.v6.AlnsSearch.runAlns
import com.magi.app.v6.PortfolioSearch.runMultiWorker
import com.magi.app.v6.RsiSearch.runRsi
import com.magi.app.v6.RsiSearch.runRsiPlus
import com.magi.app.v6.AlnsSearch.runV5

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
    /** 既定は作った時点の [PolishGate] の写し。`handleOptimize` は入口の写しを渡す。 */
    val config: EngineConfig = EngineConfig.snapshot(),
)

data class V6OptimizerResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val algorithm: V6Algorithm,
    val phaseLogs: List<MirrorLog>,
    val iterations: Long,
    val elapsedMs: Long,
    // [3.335.0/外部レビュー P1] 以下は**この実行の成果物**。旧実装は `lastAlternatives` 等の可変 static を
    //   呼び出し側が返却後に読んでいたため、実行が重なると（WorkManager の REPLACE で旧 Worker が
    //   協調キャンセルを待つ間など）別の実行の値を読み得た。採用盤面は元から返り値で流れるので
    //   **誤った勤務表にはならない**が、「他の案」「残存分析」「ライブ表示」が混ざり得た。
    val alternatives: List<Array<IntArray>> = emptyList(),
    /** HF63 が充足困難と学習した族の**内部キー**（c3n 等。表示名 C3n ではない）。残存分析の注記の履歴にだけ使い、族の分類には使わない（3.642.0）。 */
    val infeasibleFamilies: Set<String> = emptySet(),
    /** [3.601.0/backlog#34] `runAdaptivePortfolio`のロールがroleDeadlineを5秒超えて戻った回数の合計
     *  （`epochOverruns`と同じ判定を全ワーカー分集計）。PORTFOLIO以外は常に0。既存の`overrunLog`は
     *  自由文字列で機械比較できないため、tools/loopベンチが数値比較できるようここへ複製する。 */
    val epochOverrunCount: Int = 0,
) {
    /** 同上。`AdaptiveElite` は internal なので本体プロパティとして持つ（`copy()` は引き継がない＝
     *  作った側が明示的に載せる）。 */
    internal var fusionElites: List<AdaptiveElite> = emptyList()
}

object V6NativeOptimizer {




    /**
     * [3.335.0/外部レビュー P1] この実行だけの成果物入れ（[RunSlot]）を作り、コルーチンのコンテキストで
     * 呼び出し木の隅々まで運ぶ。結果は返り値に載せて返すので、**実行が重なっても呼び出し側は自分の
     * 実行の値だけを読む**（旧実装は返却後に可変 static を読んでいた）。static は「いちばん新しい実行の
     * ライブ表示」として残し、置き換えられた古い実行は書き込まない。
     */
    suspend fun optimize(
        state: MagiState,
        initial: Array<IntArray> = state.schedule.toIntArray2D(),
        options: V6OptimizerOptions = V6OptimizerOptions(),
        shouldStop: () -> Boolean = { false },
        onProgressRaw: (String, ViolationReport?, Long, Long) -> Unit = { _, _, _, _ -> },
        /** [3.346.1] `shouldStop` が真のとき、それが**単調な停止**（探索締切・キャンセル）かを返す。
         *  停滞シグナルは単調でない（改善が届けば偽に戻る）ので、適応ポートフォリオはそれだけを
         *  確認窓で再確認する。既定は「常に単調」＝確認せず即離脱＝従来どおり。 */
        stopIsFinal: () -> Boolean = { true },
    ): V6OptimizerResult {
        val slot = OptimizerRuntime.beginRun()
        val r = OptimizerRuntime.inSlot(slot) {
            optimizeInSlot(state, initial, options, shouldStop, onProgressRaw, stopIsFinal)
        }
        return r.copy(alternatives = slot.alternatives, infeasibleFamilies = slot.infeasible)
            .also { it.fusionElites = slot.fusionElites }
    }

    private suspend fun optimizeInSlot(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        shouldStop: () -> Boolean,
        onProgressRaw: (String, ViolationReport?, Long, Long) -> Unit,
        stopIsFinal: () -> Boolean,
    ): V6OptimizerResult {
        val started = nowMs()
        // [敵対的レビュー: 進捗コールバックの直列化] runMultiWorker(仮説横断)・runAlnsChains(チェーン横断)は
        //   複数の Dispatchers.Default コルーチンから同じ onProgress を並行呼出しうる（best更新自体は
        //   各所でCAS/ロック済みだが、呼出元のコールバック本体＝UI/Worker側の非同期共有状態(スナップショット
        //   ファイル書込み等)は無保護だった）。この最上位の入口1箇所でロックすれば、内側の多層fan-out
        //   （仮説×チェーン）を経ても最終的にユーザーコールバックへは必ず直列で届く。
        val progressLock = Any()
        val onProgress: (String, ViolationReport?, Long, Long) -> Unit = { phase, report, iters, elapsed ->
            synchronized(progressLock) { onProgressRaw(phase, report, iters, elapsed) }
        }
        val chosen = SelectionHeuristics.chooseAlgorithm(options.algorithm, options.totalBudgetSec)
        val p = cachedProblem(state)
        var schedule = HardRepairCore.hf66DataHardening(state, normalizeSchedule(initial, p), "pre")
        // [N1b] 入口修復(hf67)は better(hard→weighted→total) 改善時のみ採用。既に良好な入力
        //   （前回結果の再最適化など）を破壊し、探索を劣化seedに係留する事故を防ぐ
        //   （運用ログ実例: 入力214 → 修復後HARD4/250 → 275秒が回復に浪費）。hf66(群内正規化)は無条件維持。
        val entryReport = UnifiedViolationChecker.check(state, schedule)
        val repaired = HardRepairCore.hf67HardRepair(state, schedule, Random(actualSeed(options.seed) xor 0x67L)).schedule
        val repairedReport = UnifiedViolationChecker.check(state, repaired)
        val hf67Adopted = better(repairedReport, entryReport) && p.keepsExtBan(schedule, repaired)
        if (hf67Adopted) schedule = repaired
        val entryBoard = schedule.copy2D()   // [N1c] 内側番兵用に入力の勤務表を保持
        val entryBoardReport = if (hf67Adopted) repairedReport else entryReport
        // [仮説数上限撤廃] 旧仕様は「最大HypothesisPlanning.MAX_HYPOTHESES(5)仮説」固定で、超過ワーカーは仮説内並列度へ配分
        //   していた。ユーザー指示により固定上限を撤廃し、仮説数(w)をワーカー設定にそのまま連動させる
        //   （多様性>深さ。V5だけは仮説の概念を使わずworkersをそのままSAチェーン数とする＝対象外）。
        val w = HypothesisPlanning.hypothesisCount(options.workers)
        // [3.371.0/並列SA本格再有効化] 表示はエンジンが実際に使う HypothesisPlanning.hypothesisSpawnPlan（runMultiWorker と
        //   同一関数）から導出。PORTFOLIO は runMultiWorker を経由せず各ロールが単一チェーンで走る
        //   （ロール内並列SA=portfolioRoleParallelSa は 3.409.21 の単体 A/B で中立＝削除。
        //   ON は反復数中央値がむしろ低かった＝チェーン分割が希釈になっていた）。
        val (spawnHyp, plan) = HypothesisPlanning.hypothesisSpawnPlan(options.workers, w)
        val planNote = plan.let { pl ->
            val mn = pl.min(); val mx = pl.max()
            if (mn == mx) "仮説内${mn}並列" else "仮説内${mn}〜${mx}並列"
        }
        val workersNote = when (chosen) {
            V6Algorithm.V5 -> "workers=${options.workers}（SAチェーン）"
            V6Algorithm.PORTFOLIO -> "workers=${options.workers}（適応ポートフォリオ仮説${w}・各ロール単一チェーン）"
            else -> "workers=${options.workers}（実効仮説${spawnHyp}${if (spawnHyp < w) "＝設定${w}をコア数まで縮小" else ""}・$planNote）"
        }
        var logs = listOf(
            MirrorLog(tag = "V6Dispatcher", message = "algorithm=$chosen budget=${options.totalBudgetSec}s $workersNote"),
            MirrorLog(tag = "HF67", message = if (hf67Adopted)
                "入口修復を採用 HARD ${entryReport.hard}->${repairedReport.hard} / total ${entryReport.total}->${repairedReport.total}"
            else if (better(repairedReport, entryReport))
                "入口修復を見送り（修復後の方が良いが拡張希望の禁止に触れる: HARD ${entryReport.hard}->${repairedReport.hard} / total ${entryReport.total}->${repairedReport.total}）"
            else
                "入口修復を見送り（入力の方が良好: HARD ${entryReport.hard}/total ${entryReport.total} ≦ 修復後 HARD ${repairedReport.hard}/total ${repairedReport.total}）"),
        )
        val full = max(1, options.totalBudgetSec)
        val result = when (chosen) {
            // V5 already runs `workers` parallel SA chains inside SaOptimizer.
            V6Algorithm.V5 -> runV5(state, schedule, options, full, shouldStop, onProgress)
            // ALNS/RSI/RSI++ are run as up to 5 parallel hypotheses with hybrid early-cancel.
            // [3.266.0/hypothesis basin diversity] 各仮説の入口盤面を hypothesisStartFor で多様化
            //   （W0/W4のみ現行盤面のコピー=安全フロア維持）。旧実装は全仮説が同一盤面から出発しており、
            //   探索経路が異なっても頻繁に同じ吸引域へ収束していた（実データで8仮説→相異なる解1件を確認）。
            V6Algorithm.ALNS -> runMultiWorker(w, options, onProgress) { i, o, prog ->
                runAlns(state, hypothesisStartFor(state, schedule, i, o.seed), o, full, shouldStop, prog)
            }
            V6Algorithm.RSI -> runMultiWorker(w, options, onProgress) { i, o, prog ->
                runRsi(state, hypothesisStartFor(state, schedule, i, o.seed), o, full, shouldStop, prog)
            }
            V6Algorithm.RSI_PLUS -> runMultiWorker(w, options, onProgress) { i, o, prog ->
                runRsiPlus(state, hypothesisStartFor(state, schedule, i, o.seed), o, full, shouldStop, prog, workerLabel = "仮説$i")
            }
            // [3.267.0/adaptive hypothesis epochs] 1回起動して終了を待つ旧協力ポートフォリオ（各仮説に
            //   異なる方式を割当て keep-best で最良採用）では、入口を多様化しても収束後は同じ吸引域へ
            //   潰れたまま残時間を消費していた。5〜8秒（RSI++は35秒）epochで停滞/basin重複を検知し、
            //   エリートを保存しながら役割を再配属する非同期適応ポートフォリオへ置換。
            V6Algorithm.PORTFOLIO -> runAdaptivePortfolio(state, schedule, w, options, full, shouldStop, stopIsFinal, onProgress)
            V6Algorithm.AUTO -> error("AUTO must be resolved")
        }
        logs = logs + result.phaseLogs
        // [E11/多人数ブロック移動] エピローグで残 covU を「勤務→勤務」連鎖で充填（ALNS単独や covU を focus
        //   しなかった経路でも走る保険）。keep-best 照合＝退化不能。ユーザー実例(8/11・8/17)の詰み局面を解く。
        var resultSched = result.schedule
        // 拡張希望の禁止へ置いた探索結果は採らない（どこかの手が判定を漏らしたときの最後の砦）
        if (!p.keepsExtBan(schedule, resultSched)) {
            logs = logs + MirrorLog(level = "W", tag = "V6Dispatcher",
                message = "探索結果が拡張希望の禁止へ置いていたため入口の勤務表を採用（${p.extBanNewCells(schedule, resultSched).size}セル）")
            resultSched = schedule
        }
        // [3.569.0] この盤面の評価は研磨の入口と出口でも要る＝同じ盤面を 3 回 check しない（report は盤面と対で持ち回る）。
        var resultRep = UnifiedViolationChecker.check(state, resultSched)
        if (resultRep.hard > 0 && (resultRep.breakdown["covU"] ?: 0) > 0 && !shouldStop()) {
            val cand = resultSched.copy2D()
            val n = RsiHypothesisOperators.applyCovUChains(state, cand, Random(actualSeed(options.seed) xor 0xC0FFEEL), wideC3n = options.config.wideC3nBreakDays)
            if (n > 0) {
                val candRep = UnifiedViolationChecker.check(state, cand)
                if (better(candRep, resultRep) && p.keepsExtBan(resultSched, cand)) {
                    logs = logs + MirrorLog(tag = "ChainFill",
                        message = "多人数ブロック移動で covU 充填: HARD ${resultRep.hard}→${candRep.hard} / total ${resultRep.total}→${candRep.total}（連鎖${n}件）")
                    resultSched = cand
                    resultRep = candRep
                }
            }
        }
        // [review #3] Final epilogue polish only when the caller isn't running its own post chain.
        val polished = if (options.postPolish && !shouldStop())
            hf80PostPolish(state, resultSched, max(1, min(30, options.totalBudgetSec / 20)), actualSeed(options.seed) xor 0x80L, shouldStop, initialReport = resultRep)
        else PolishResult(resultSched, emptyList(), 0, resultRep)
        val finalReport = polished.report
        logs = logs + polished.logs + MirrorLog(
            tag = "V6Dispatcher",
            message = "完了 algorithm=$chosen HARD=${finalReport.hard} total=${finalReport.total} elapsed=${nowMs() - started}ms",
        )
        // [N1c] 内側番兵: 最終結果が入力の勤務表より劣るなら入力の勤務表へ復帰（FinalPortの外側Sentinelと二重化）。
        //   全段keep-bestのため通常は発火しない。発火時は「予算が改善に寄与しなかった」ことの可視化を兼ねる。
        if (better(entryBoardReport, finalReport)) {
            logs = logs + MirrorLog(level = "W", tag = "V6Dispatcher",
                message = "内側番兵: 結果(HARD=${finalReport.hard}/total=${finalReport.total})が入力の勤務表(HARD=${entryBoardReport.hard}/total=${entryBoardReport.total})より劣化のため入力の勤務表を採用")
            return V6OptimizerResult(entryBoard, entryBoardReport.copy(logs = logs + entryBoardReport.logs), chosen, logs, result.iterations + polished.iterations, nowMs() - started, epochOverrunCount = result.epochOverrunCount)
        }
        return V6OptimizerResult(polished.schedule, finalReport.copy(logs = logs + finalReport.logs), chosen, logs, result.iterations + polished.iterations, nowMs() - started, epochOverrunCount = result.epochOverrunCount)
    }





    /** `report` は常に `schedule` の評価（hf80PostPolish 内で盤面と対で更新される）。 */

    /**
     * [ソフト研磨専用] 現在の盤面をHARDガード付きで局所研磨し、SOFTのみ削減する公開エントリ。
     * 破壊/多様化フェーズは行わず、hf80PostPolish の keep-best＋退化防止により入力以上の盤面のみ返す
     * （HARD=0 は壊さない）。最適化(再作成する)と違い、必須が一時的に増えることはない。
     */
    suspend fun softPolishOnly(
        state: MagiState,
        schedule: Array<IntArray>,
        seconds: Int,
        seed: Long = 0x50F11L,
        shouldStop: () -> Boolean = { false },
    ): Array<IntArray> = hf80PostPolish(state, cachedProblem(state).withManualPins(schedule), max(1, seconds), seed, shouldStop).schedule




}
