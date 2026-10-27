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
import com.magi.app.v6.SearchCommon.actualSeed
import com.magi.app.v6.SearchCommon.better
import com.magi.app.v6.SearchCommon.extBanInDiff
import com.magi.app.v6.SearchCommon.nowMs

/** V5（SA の多点）と ALNS（破壊と修復のチェーン、全面改修 P10b で V6NativeOptimizer から分けた）。探索の意味論は移す前と同じ。 */
internal object AlnsSearch {
    internal suspend fun runV5(
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
            SaParams(t0 = saT0, workers = HypothesisPlanning.clampWorkersToCores(options.workers), budgetMs = budgetSec * 1000L, softPolish = options.softPolish, shouldStop = shouldStop, seed = options.seed),
        ) { pr ->
            if (pr.elapsedMs % 1000L < 220L) onProgress("V5 SA", lastReport, pr.totalIters, pr.elapsedMs)
        }
        val repaired = HardRepairCore.hf67HardRepair(state, res.schedule, Random(actualSeed(options.seed) xor 0x5L))
        // 拡張希望の禁止へ置いた修復は採らない
        var outSched = if (p.keepsExtBan(res.schedule, repaired.schedule)) repaired.schedule else res.schedule
        var report = UnifiedViolationChecker.check(state, outSched)
        // [退化防止番兵 / 実機ログ起因] runAlns(578行)と同じ入力比keep-best。従来 runV5 だけ番兵が無く、SA+修復が
        //   入力より悪化した結果をそのまま返していた。RSI++ は Phase1 Seed に runV5 を使い、以降の各段は前段比
        //   keep-best のため、Phase1 の劣化(実測: 入力HARD=1/195 → Seed HARD=2/229)が全チェーンへ伝播し、
        //   最後にディスパッチャ番兵が入力へ復帰＝予算全体が無駄になっていた(実機で 275s×2回)。入力を品質床に
        //   することで以降の全フェーズが「入力以上」から積み上がる。SA が入力より良い解を見つけた場合は素通し
        //   ＝多様化は維持。スコアリング不変(選択のみ・better()=hard→weighted→total)。
        val baseSched = normalizeSchedule(initial, p)
        val baseReport = UnifiedViolationChecker.check(state, baseSched)
        val keptInput = better(baseReport, report) || !p.keepsExtBan(baseSched, outSched)
        if (keptInput) { outSched = baseSched; report = baseReport }
        lastReport = report
        val logs = listOf(MirrorLog(tag = "RunMAGI_V5",
            message = "高速SA完了 HARD=${report.hard} total=${report.total} iter=${res.totalIters}" +
                (if (res.chainWins.isNotEmpty()) {
                    val wins = res.chainWins.count { it > 0 }
                    " SAチェーン${res.chainWins.size}本(最良を更新した本数=$wins" +
                        (if (wins <= 1 && res.chainWins.size > 1) "＝並列を増やした効果は出ていません" else "") + ")"
                } else "") +
                if (keptInput) "（SA結果が入力より悪化のため入力を維持=番兵）" else "")) + repaired.logs
        return V6OptimizerResult(outSched, report.copy(logs = logs + report.logs), V6Algorithm.V5, logs, res.totalIters, nowMs() - t0)
    }

    /** [余剰ワーカー活用/多チェーンALNS] runAlns を [chains] 本、異なるシードで並列実行し keep-best
     *  で最良を採用する（SaOptimizer の多チェーンSAと同型の考え方をALNSへ拡張）。各チェーンは runAlnsSingle
     *  （単一チェーン本体）を直接呼ぶ＝再帰は構造的に不可能（[敵対的レビュー3.212.0] 旧実装は runAlns 経由の
     *  ガード再帰で、無限再帰防止が options.copy(workers=1) 1引数とコメントのみに依存していた）。
     *  restarts・GLS・destroy-repair 等の内部ロジックは一切変更しない。最終選択は全チェーン共通の
     *  better()（hard→weighted→total辞書式）でゲートするため退化不能。
     *  [敵対的レビュー3.212.0で追加した3つの堅牢化]
     *  ①部分結果許容: 1チェーンの非Cancellation例外で兄弟チェーンの有効な結果を道連れにしない（チェーン毎に
     *    捕捉・全滅時のみ最初の例外を再送出=旧単一チェーンと同じ失敗面）。
     *  ②HARD=0早期キャンセル: 非先頭チェーンの合格も検知して兄弟を即キャンセルし（§2.2絶対評価）、合格
     *    reportは c!=0 でも外側 onProgress へ転送＝runMultiWorker の仮説間キャンセルにも見えるようにする。
     *  ③観測性: 仮説検証と同型のチェーン毎結果・相異なる解数・chain0内訳をログ化（全チェーン同一解収束＝
     *    並列の無駄、をログレビューで検出可能に）。 */
    internal suspend fun runAlnsChains(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        shouldStop: () -> Boolean,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult = coroutineScope {
        val chains = max(1, options.workers)
        val base = actualSeed(options.seed)
        val passed = java.util.concurrent.atomic.AtomicInteger(-1)
        val firstError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        // [レビュー#2 3.213.0] runMultiWorker と同型: チェーン横断の改善も外側へ転送（停滞時計の集約）。
        val sharedBest = java.util.concurrent.atomic.AtomicReference<ViolationReport?>(null)
        fun improvesShared(r: ViolationReport): Boolean {
            while (true) {
                val cur = sharedBest.get()
                if (cur != null && !better(r, cur)) return false
                if (sharedBest.compareAndSet(cur, r)) return true
            }
        }
        // [敵対的レビュー修正・#5早期winner] runMultiWorkerと同型: 全チェーンをLAZYで生成してから一斉start。
        val jobs = arrayOfNulls<kotlinx.coroutines.Deferred<V6OptimizerResult?>>(chains)
        for (c in 0 until chains) {
            jobs[c] = async(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    runAlnsSingle(state, initial.copy2D(), options.copy(workers = 1, seed = base + (c + 1) * 0x2545F4914F6CDD1DL), budgetSec, shouldStop) { phase, report, iters, elapsed ->
                        // [3.410.0/E-03] 旧: HARD=0 へ最初に到達したチェーンが兄弟を即キャンセルしていた。
                        //   3.376.0 が `runAdaptivePortfolio`/`runMultiWorker` の同じ機構を撤廃したとき、
                        //   **この3つ目だけが取り残されていた**。HARD=0 到達時点で残る仕事は全部 SOFT なので、
                        //   勝者1本に絞ると指定した並列度の 1/N しか使われない。採否は全段 keep-best なので
                        //   走らせ続けても品質は退化しない。`passed` は「誰が最初に到達したか」の記録として
                        //   のみ残す（その報告を一度だけ外側へ転送する＝runMultiWorker の絶対評価に必要）。
                        val won = report != null && report.hard == 0 && passed.compareAndSet(-1, c)
                        // 先頭チェーンは常時、非先頭は合格時＋チェーン横断改善時に転送
                        //（合格の可視化がrunMultiWorkerの絶対評価に、改善の可視化が外側停滞時計に必要）。
                        val improved = report != null && improvesShared(report)
                        if (c == 0 || won || improved) onProgress(phase, report, iters, elapsed)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    firstError.compareAndSet(null, e)
                    null
                }
            }
        }
        jobs.forEach { it?.start() }
        val results = jobs.mapNotNull { d ->
            try { d?.await() } catch (_: kotlinx.coroutines.CancellationException) { null }
        }
        ensureActive()
        if (results.isEmpty()) {
            // 全チェーン失敗（キャンセル起因は上のensureActiveで伝播済＝ここは例外全滅のみ）。
            // 旧単一チェーンと同じ失敗面へ縮退: 最初の例外を再送出（黙って空成功にしない）。
            throw firstError.get() ?: IllegalStateException("runAlnsChains: no chain produced a result")
        }
        val best = results.reduce { a, b -> if (better(b.report, a.report)) b else a }
        if (ownsStatics(runSlot())) publishLiveBest(best.report, best.schedule)
        val totalIters = results.sumOf { it.iterations }
        val chain0Iters = results.firstOrNull()?.iterations ?: 0L
        val perChain = results.sortedWith(compareBy(reportComparator) { it.report })
            .joinToString("  ") { r -> "[必須${r.report.hard}/合計${r.report.total}${if (r === best) "★採用" else ""}]" }
        val distinctSols = results.map { BoardKey(it.schedule) }.distinct().size
        val failNote = if (results.size < chains) "・失敗${chains - results.size}本(例外/キャンセル)" else ""
        val extra = MirrorLog(tag = "AlnsChains", message =
            "ALNS多チェーン(${chains}並列$failNote) → 採用 HARD=${best.report.hard} total=${best.report.total}" +
                " 合計iter=${totalIters}(先頭chain=${chain0Iters}) / 各チェーン: $perChain / 相異なる解=${distinctSols}件")
        best.copy(phaseLogs = best.phaseLogs + extra, iterations = totalIters)
    }

    /** [敵対的レビュー3.212.0/構造分割] workers の意味過重（設定値/仮説内チェーン数/チェーン内=1）を
     *  ディスパッチャ3行に閉じ込める。本体 runAlnsSingle は workers を一切読まない＝再帰・誤fan-outが
     *  構造的に不可能。既存呼出元（optimize/runRsi/runRsiPlus）のシグネチャは不変。 */
    internal suspend fun runAlns(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult {
        if (options.workers > 1) return runAlnsChains(state, initial, options, budgetSec, shouldStop, onProgress)
        return runAlnsSingle(state, initial, options, budgetSec, shouldStop, onProgress)
    }

    private suspend fun runAlnsSingle(
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
        // hard→weighted→total の辞書順で悪化しないよう、開始時の盤面を baseline として保持し最後に番兵比較する。
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
                val mySlot = runSlot()   // [3.335.0] 非 suspend なローカル関数からは取れないので先に捕まえる
                fun syncReport() {
                    if (reportStale) {
                        globalReport = UnifiedViolationChecker.check(state, globalBest)
                        if (ownsStatics(mySlot)) publishLiveBest(globalReport, globalBest)
                        reportStale = false
                    }
                }
                while (nowMs() < deadline && !shouldStop()) {
                    coroutineContext.ensureActive()
                    val frac = ((deadline - nowMs()).toDouble() / max(1.0, perSec * 1000.0)).coerceIn(0.0, 1.0)
                    val ret = NativeBridge.nativeAlnsChunk(alns, 200, frac)
                    if (!NativeGate.chunkConsistent(ret, 6)) { syncReport(); NativeGate.disable("ALNSチャンク整合性NG(status=${ret.getOrNull(0)})"); return false }
                    itersTotal += ret[4]
                    if (ret[3] == 1L && ret[2] < globalScore) {
                        // [2層目の番兵] best 更新を Kotlin Evaluator でフル照合（Long== 許容誤差なし）。
                        //   [照合トグル] OFF=純ネイティブ（照合せず信頼）。C++自己整合(status)は上で常時検査済。
                        NativeBridge.nativeAlnsRead(alns, 0, bestFlat)
                        val bestSol = NativeEval.unflatten(bestFlat, p.S, p.T)
                        // 拡張希望の禁止へ置いた盤面は採らない（この restart は Kotlin ループで続ける）
                        if (!p.keepsExtBan(globalBest, bestSol)) { syncReport(); return false }
                        NativeGate.parityMismatch(ret[2]) { fullEvaluator.fullEval(bestSol) }?.let { kScore ->
                            syncReport(); NativeGate.disable("ALNS Kotlin照合NG(native=${ret[2]} kotlin=$kScore)"); return false
                        }
                        globalBest = bestSol; globalScore = ret[2]
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
            var cur = if (r == 0) globalBest.copy2D() else DestroyRepairOperators.perturb(state, globalBest, rng, strength = (0.18 * options.explore).coerceIn(0.05, 0.6))
            cur = HardRepairCore.hf67HardRepair(state, cur, rng).schedule
            if (!p.keepsExtBan(globalBest, cur)) cur = globalBest.copy2D()   // 拡張希望の禁止へ置いた再起動盤面は採らない
            val deadline = nowMs() + per * 1000L
            // [Stage8b] ネイティブ ALNS チャンクへ委譲。不可 or 番兵発火なら下の従来 Kotlin ループへ。
            val usedNative = nativeProblem != 0L && NativeGate.enabled && runRestartNative(cur, deadline, per, r)
            if (!usedNative) {
            var curReport = UnifiedViolationChecker.check(state, cur)
            eval.reset(cur)
            var curScore = eval.score()
            // [監査(未レビュー領域再監査) HF77修正] 旧コメント「再構築は restart 毎のみ」は実装と不一致だった。
            //   `gls`(374行目)は本 runAlns 呼出につき1回だけ生成され、restart(for r)ループ間で共有・持ち越される
            //   （decay(654行目)のみが希薄化する）。再構築が起きるのは runAlns が新規に呼ばれた時（RSI各ラウンド/
            //   各並列ワーカー等）のみ。globalBestは生スコアで別管理のため、この共有自体は受理動学にのみ作用し
            //   正しさは不変（keep-best）。
            var curAug = gls.augment(cur)   // [GLS] 現行盤面の penalty 拡張分を増分維持
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
                var op = if (options.opSelect == OpSelectMode.THOMPSON) SelectionHeuristics.thompsonSelect(opW, iter, rng)
                         else SelectionHeuristics.rouletteSelect(opW, rng)
                // [賢いsoft集中] HARD が最良水準(curHard<=bestHard)に到達したら残り探索を soft 修復へ寄せる。
                //   HARD=0 なら積極的に(0.30)、HARD>0 の床(構造的に解けない covU/pref/c3n 等)では控えめに(0.15)
                //   op5(targeted repair=covO/c2/上下限/c41/c41s/c3Want/apt 修復)を優先。HARD>床 の間はHARD優先で不変。
                //   従来 curHard==0 限定だと、構造的HARD床から下がれない局面で soft研磨が一度も起動しなかった
                //   (apt超過/fair が放置)。最良HARD水準なら床>0 でも soft を磨くよう修正。
                val softFocusProb = if (globalScore / SCORE_HARD_UNIT == 0L) 0.30 else 0.15
                if (curScore / SCORE_HARD_UNIT <= globalScore / SCORE_HARD_UNIT && rng.nextDouble() < softFocusProb) op = 5
                // [HF290 役割分担] explore 倍率で受理温度を調整（探索=受理寛容/精製=厳格）。explore=1.0 は従来と同一。
                //   ただし LAM_ADAPTIVE は受理率追従の適応温度 lamTemp を使う（自己調整）。
                val temp = if (options.accept == AcceptMode.LAM_ADAPTIVE) lamTemp
                           else max(0.03, (deadline - nowMs()).toDouble() / max(1.0, per * 1000.0) * options.explore)
                val curHard = curScore / SCORE_HARD_UNIT
                val gdLevel = if (options.accept == AcceptMode.GREAT_DELUGE) {
                    val frac = ((deadline - nowMs()).toDouble() / max(1.0, per * 1000.0)).coerceIn(0.0, 1.0)
                    RoleDiversityHelpers.greatDelugeLevel(gdInitial, globalScore.toDouble(), frac)
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
                        op == 3 && p.S > 0 && p.T >= 2 -> {   // 同一職員の2日入替
                            val i = rng.nextInt(p.S)
                            var ja = rng.nextInt(p.T); var jb = rng.nextInt(p.T)
                            if (ja == jb) jb = (jb + 1) % p.T
                            if (!p.wishLocked(i, ja) && !p.wishLocked(i, jb)) {
                                val ka = eval.at(i, ja); val kb = eval.at(i, jb)
                                if (ka != kb && !p.extBanned(i, ja, kb) && !p.extBanned(i, jb, ka)) {
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
                                    if (nw != oldK && !p.extBanned(i, j, nw)) {
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
                                if (fix[2] != oldK && !p.extBanned(fix[0], fix[1], fix[2])) {
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
                                if (k1 != k2 && p.mayPlace(i1, k2) && p.mayPlace(i2, k1) && !p.extBanned(i1, j, k2) && !p.extBanned(i2, j, k1)) {
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
                        0 -> if (drDay >= 0) DestroyRepairOperators.destroyRepairDayAt(state, cand, drDay, rng)
                        1 -> if (drStaff >= 0) DestroyRepairOperators.destroyRepairStaffAt(state, cand, drStaff, rng)
                        else -> DestroyRepairOperators.destroyRepairViolations(state, cand, curReport, rng)
                    }
                    // hf67 は hard 違反がある時のみ必要。
                    val fixed = if (iter % 7L == 0L && curHard > 0L) HardRepairCore.hf67HardRepair(state, cand, rng).schedule else cand
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
                    // 拡張希望の禁止へ置いた候補は評価せずに捨てる（eval へは何も反映しない）
                    val extBad = p.hasExtBan && extBanInDiff(p, fixed, diffBuf, nDiffs)
                    val nApply = if (extBad) 0 else nDiffs
                    var moveAug = 0.0
                    for (idx in 0 until nApply) {
                        val flat = diffBuf[idx]; val i = flat / p.T; val j = flat % p.T
                        moveAug += glsMoveAug(gls, i, j, cur[i][j], fixed[i][j])
                        eval.apply(i, j, fixed[i][j])
                    }
                    val ns = eval.score()
                    val improvedCur = ns < curScore
                    val accepted = !extBad && (improvedCur || glsAccept(ns, curScore, moveAug, curAug, options.accept, temp, gdLevel, rng))
                    if (options.accept == AcceptMode.LAM_ADAPTIVE && !extBad) lamUpdate(accepted)
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
                        for (idx in 0 until nApply) {
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
                    // [3.335.0] 置き換えられた古い実行が新しい実行のライブ盤面を上書きしないようにする。
                    if (ownsStatics(runSlot())) publishLiveBest(globalReport, globalBest)
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

    /** [GLS移植] 最良未更新がこの反復数を超えたら GLS penalty を強化（Web版 glsTrigger 既定200）。 */
    internal const val GLS_TRIGGER = 200L
    internal const val GLS_DECAY_EVERY = 256   // [GLS aging] この kick 数ごとに penalty を減衰し肥大化を防ぐ
}
