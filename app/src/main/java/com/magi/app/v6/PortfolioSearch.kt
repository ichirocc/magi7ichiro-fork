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
import com.magi.app.v6.V6NativeOptimizer.actualSeed
import com.magi.app.v6.V6NativeOptimizer.better
import com.magi.app.v6.V6NativeOptimizer.forceDiverseKick
import com.magi.app.v6.V6NativeOptimizer.hypothesisStartFor
import com.magi.app.v6.V6NativeOptimizer.nowMs
import com.magi.app.v6.AlnsSearch.runAlns
import com.magi.app.v6.RsiSearch.runRsi
import com.magi.app.v6.RsiSearch.runRsiPlus

/** 適応ポートフォリオ（どの探索器をいつ、どのワーカーで回すか）と多ワーカーの並列探索・停止の確認（全面改修 P10b で V6NativeOptimizer から分けた）。探索の意味論は移す前と同じ。 */
internal object PortfolioSearch {
    /** [3.231.0/ドッグフーディングで発見・修正] RSIラウンドループがHf63Infeasibilityへ渡す
     *  ラウンド当たりeffortIters。旧実装は1800/round固定で、INFEAS_STALL_ITERS=5000到達に
     *  約3ラウンドの同族focusを要した。E9冷却(1ラウンド休止)が2〜3の詰んだ族を交互に切替える
     *  実運用では、rounds が小さい(既定5等)と3回目のfocusが最終ラウンドに達し、deprioritize が
     *  成立しても振り向け先の残りラウンドが無かった。rounds に応じて動的に決め、
     *  「残り最低reserveRounds分を振り向けに残せる」タイミングでdeprioritizeが完了するようにする
     *  （E9の1-in-2交互を想定しattemptsTarget=ceil((rounds-reserveRounds)/2)、下限2で一度の不運な
     *  1ラウンドだけではdeprioritizeしない=E9のより軽い1R冷却との役割分担を保つ）。純関数として抽出し
     *  ユニットテスト可能にする。 */
    private data class AdaptiveWorkerOutcome(
        val elite: Array<IntArray>,
        val report: ViolationReport,
        val logs: List<MirrorLog>,
        val iterations: Long,
        val epochs: Int,
        val reassignments: Int,
        val roleRuns: Map<HypothesisEpochRole, Int>,
        /** [3.307.0/ログ強化] 役割ごとの実消費ミリ秒。量子(5/8/35/45秒)は要求値であって
         *  消費値ではないため、予算配分を論じるにはこちらが要る。 */
        val roleMillis: Map<HypothesisEpochRole, Long>,
        /** [3.306.0] ワーカーが epoch ループを抜けた時点の役割。エリート登録の分類に使う
         *  （再配属回数からの逆算では、残差ベース経路のとき実際の役割と一致しないため）。 */
        val lastRole: HypothesisEpochRole,
        /** [3.346.0/実機ログ] ワーカーが epoch ループを抜けた理由と、その時点の経過秒。
         *  `shouldStop` は**単調でない**（改善が届けば偽に戻る）のに `while` 条件で使うため、
         *  一瞬 true になった瞬間にポーリングしたワーカーだけが恒久的に離脱する。実機ログ
         *  2026-08-03 では 8本中4本が 115〜116s で離脱し、残り159秒を4本で走っていた
         *  （役割別worker秒を手で足さないと気づけなかった）。理由と時刻を明示して可視化する。 */
        val exitReason: String,
        val exitAtSec: Long,
        /** [3.346.1] 一瞬の停滞シグナルを確認窓で見送った回数（＝旧実装なら恒久離脱していた回数）。 */
        val survivedStops: Int = 0,
        /** [3.283.0/ログ強化] ワーカー専属HF63がエポック横断で学習した回避族（勝者以外のfocus学習は
         *  従来この要約でしか外へ出ない＝W1/W2が何を諦めたかがログ解析不能だった穴を埋める）。 */
        val hf63Avoided: List<String> = emptyList(),
        /** [3.409.17/実機ログ 3.409.14] ロール呼出が roleDeadline を5秒超えて走った記録
         *  （"W$i:ROLE(q=量子s→実N s)"）。実機で予算300sの実行が474〜959sまで超過したのに、
         *  どの役割が塞いだかを後から特定する手段が無かった穴を埋める。 */
        val epochOverruns: List<String> = emptyList(),
    )

    /** [3.346.1] 停滞シグナルの確認窓。この間 shouldStop が続けて真なら本物とみなす。 */
    internal const val STOP_CONFIRM_MS = 5_000L
    private const val STOP_CONFIRM_POLL_MS = 250L

    /**
     * [3.346.1/方針B] `shouldStop` が**本物の停止**かを確認窓のあいだ再確認する。
     *
     * `shouldStop` は単調でない: 締切・キャンセルは一度真なら永久に真だが、停滞シグナルは
     * 「最終改善からの経過 > 閾値」なので**他のワーカーが改善を1件出した瞬間に偽へ戻る**。
     * 旧実装はこれを epoch ループの `while` 条件で見ていたため、たまたまその瞬間にポーリングした
     * ワーカーだけが恒久的に離脱していた（実機 2026-08-03: 8本中4本が115〜116秒で消え、
     * 残り159秒を半分の並列度で走行。閾値37.5秒に対し改善間隔が37〜41秒＝ほぼ毎回きわどい）。
     *
     * ここでは短い窓のあいだ再確認し、途中で偽へ戻れば false（＝一瞬のシグナル・走行を続ける）、
     * 窓のあいだ真のままなら true（＝本物・従来どおり離脱）を返す。窓を [STOP_CONFIRM_MS] に
     * 取るのは、閾値をわずかに超えて発火する near-miss なら次の改善が数秒で届くため
     * （実機の該当ケースは発火115秒→次の改善が約3秒後）。本物の停滞ならこの窓ぶんだけ
     * 離脱が遅れるが、待機は suspend なので CPU は消費せず、8本が並行に待つので壁時計の
     * 追加も窓1回ぶん。締切超過とキャンセルは単調なので即座に true を返す。
     *
     * `deadline` は [nowMs]（`System.nanoTime()` 系の単調時計）と同じ物差しで渡すこと。
     * 壁時計（`System.currentTimeMillis()`）を混ぜると別の原点になり、締切超過を検出できない。
     */
    internal suspend fun confirmStop(
        shouldStop: () -> Boolean,
        deadline: Long,
        stopIsFinal: () -> Boolean = { true },
    ): Boolean {
        if (stopIsFinal()) return true
        val until = nowMs() + STOP_CONFIRM_MS
        while (nowMs() < until) {
            if (nowMs() >= deadline || stopIsFinal()) return true
            if (!coroutineContext.isActive) return true
            try {
                delay(STOP_CONFIRM_POLL_MS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // キャンセルは単調＝確定。旧実装（shouldStop 真で while を抜ける）と同じく
                // ループを正常終了させ、蓄積済みエリートを成果として返す。
                return true
            }
            if (!shouldStop()) return false
        }
        return true
    }

    /**
     * [3.267.0/adaptive hypothesis epochs, 3.268.0/elite archive fusion]
     * Adaptive asynchronous island portfolio. Each worker owns its epoch clock, so lightweight
     * ALNS/RSI roles can rotate every 5-8 seconds while RSI++ roles receive the 35 seconds required
     * to execute Seed(10)+RSI(10)+ALNS(10)+Polish(5) instead of being accidentally reduced to Seed.
     * A plateau saves the local elite and changes role/start basin/seed; only the shared deadline,
     * user cancellation, or HARD=0 ends the portfolio. W0 is never reassigned. Every schedule ever
     * produced by an epoch (start or role-search result) is registered into an [AdaptiveEliteArchive]
     * so the post-portfolio elite integration (EliteIntegrationPolish, see V6FinalPort) can relink
     * and fuse across the whole run, not just the final per-worker elite.
     */
    internal suspend fun runAdaptivePortfolio(
        state: MagiState,
        entry: Array<IntArray>,
        w: Int,
        options: V6OptimizerOptions,
        budgetSec: Int,
        shouldStop: () -> Boolean,
        stopIsFinal: () -> Boolean,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
    ): V6OptimizerResult = kotlinx.coroutines.supervisorScope {
        val started = nowMs()
        val deadline = started + budgetSec.coerceAtLeast(1) * 1000L
        val baseSeed = actualSeed(options.seed)
        val workers = HypothesisPlanning.portfolioWorkerCount(w)
        val lock = Any()
        val firstError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val hardZeroWinner = java.util.concurrent.atomic.AtomicInteger(-1)
        // [3.360.0/ログ強化] 全体最良を更新した回数。1回ごとの onProgress 行は既にあるが（スロットル対象外＝
        //   3.283.0）、**何回あったか**は要約に無く、実行の締めくくりで「序盤に1回きりで止まった」のか
        //   「終盤まで刻み続けた」のかがログから読めなかった（Watchdog 行が出すのは最終改善の時刻だけ）。
        //   改善が確定した分岐で数えるだけ＝ホットパスに追加コストなし。
        val globalImproves = java.util.concurrent.atomic.AtomicInteger(0)
        val lastGlobalImproveMs = java.util.concurrent.atomic.AtomicLong(started)
        val archive = AdaptiveEliteArchive()
        val p = cachedProblem(state)

        val sharedTrajectories = Array(workers) { i -> hypothesisStartFor(state, entry, i, baseSeed) }
        val initialReports = Array(workers) { i -> UnifiedViolationChecker.check(state, sharedTrajectories[i]) }
        var globalBest = entry.copy2D()
        var globalReport = UnifiedViolationChecker.check(state, globalBest)
        var globalLogs: List<MirrorLog> = emptyList()
        archive.register(
            entry, globalReport, HypothesisEpochRole.BASELINE_REFINE, worker = 0, epoch = 0, bridge = false,
        )
        for (i in 0 until workers) {
            // [3.308.0] 初期配置であることを名前で示す（値は assignmentFor(i, 0) と同じ）。
            val assignment = AdaptiveHypothesisEpochPolicy.initialAssignmentFor(i)
            archive.register(
                sharedTrajectories[i], initialReports[i], assignment.role, i, 0,
                bridge = initialReports[i].hard == globalReport.hard + 1,
            )
            if (better(initialReports[i], globalReport)) {
                globalBest = sharedTrajectories[i].copy2D(); globalReport = initialReports[i]
            }
        }

        // [3.376.0/ユーザー指示「ワーカー、並列が本当に動くようにする」] `hardZeroWinner` は
        //   「先に HARD=0 へ到達した者が勝ち＝残りを即キャンセル」する省電力機構だった（docstring 参照）。
        //   だが **HARD=0 に到達した時点で残る仕事は全部 SOFT** で、勝者1本だけがそれを担うと
        //   利用者が指定した並列度の 1/8 しか使われない。実機は初期解 HARD=64 から**1秒で HARD=0**へ
        //   到達しており（実機ログ 13:53:53→13:53:54）、そこから 195 秒を実質1並列で走っていた。
        //   3.375.0 は「入口が既に HARD=0」の場合だけを塞いだが、HARD>0 入口でも同じ潰れが
        //   数秒遅れて起きる（同じ機構）。**キル自体を撤廃**し、全ワーカーを締切まで走らせる。
        //   `hardZeroWinner` は「誰が最初に到達したか」の記録としてのみ残す（ログの情報価値を維持）。
        //   安全性: 採否は全段 keep-best（better()）なので探索を増やしても品質は退化しない。
        //   代償は電池/発熱だが、総時間は予算と停滞ウォッチドッグが従来どおり govern する。
        //   [測れなかったこと] 手元の fixture では PORTFOLIO が HARD=0 へ到達しない
        //   （golden の greedy 初期解 hard=15 → 30秒で hard=1 止まり）ため、この機構の品質への効果は
        //   A/B できていない。確かなのは構造的な帰結（キルされなくなる）だけ。
        val jobs = Array<kotlinx.coroutines.Deferred<AdaptiveWorkerOutcome>>(workers) { i ->
            async(Dispatchers.Default) {
                var trajectory = synchronized(lock) { sharedTrajectories[i].copy2D() }
                var elite = trajectory.copy2D()
                var eliteReport = initialReports[i]
                var eliteLogs: List<MirrorLog> = emptyList()
                var reassignments = 0
                var stagnantEpochs = 0
                var improvedPrevious = false
                var epoch = 0
                var iterations = 0L
                var exitReason = ""   // [3.346.0] epoch ループの離脱理由（下で確定）
                var survivedStops = 0 // [3.346.1] 一瞬の停滞シグナルを見送った回数
                val roleRuns = LinkedHashMap<HypothesisEpochRole, Int>()
                // [3.307.0/ログ強化] 役割ごとの**実消費ミリ秒**。roleRuns(エポック数)だけでは
                //   「どの役割が予算のどれだけを実際に食ったか」が読めない（量子は役割ごとに
                //   5/8/35/45 秒と桁が違い、かつロールは締切・HARD=0・内部早期終了で量子より
                //   早く戻ることがある）。エポック境界の摂動・検査・距離計算も含めた実測。
                val roleMillis = LinkedHashMap<HypothesisEpochRole, Long>()
                // [3.409.17/実機ログ 3.409.14] ロール呼出が roleDeadline（自分の量子と探索締切の min）を
                //   5秒超えて走った事実。実機で予算300sの実行が474〜959sまで超過し（W4 epoch3 の
                //   グローバル最良更新が経過474sに出た＝ロールが stopRole を数百秒無視した証拠）、
                //   どの役割が塞いだかは診断ログが次の実行で消えていて特定できなかった。
                val epochOverrunNotes = ArrayList<String>()
                // [3.281.0/停滞レビューB] ワーカー専属のHF63をエポック横断で共有。旧: runRsi 呼出ローカルのため
                //   短いエポック(rounds=2)では「focus 2ラウンドで threshold 到達→即破棄→次エポックで白紙から
                //   再学習」を全エポックで反復し、解けない族(実機: c3n)へ毎回突撃していた。ワーカー内は逐次
                //   実行＝並行アクセスなし。ワーカー間では共有しない（役割多様性を汚染しないため）。
                val workerHf63 = Hf63Infeasibility()

                // [3.278.0/監査修正×2]
                //   ①勝者継続: 旧条件 `hardZeroWinner.get() < 0` は HARD=0 到達で**勝者自身も**次 epoch 境界で
                //     停止させ、実行可能データでは残り数百秒のソフト研磨予算を放棄していた（runMultiWorker の
                //     文書化契約「勝者は自予算でソフト研磨を続ける」と衝突）。勝者だけは deadline まで継続する。
                //   ②epoch 例外隔離: 旧実装は try がロール実行(runAlns等)だけを包み、adaptiveEpochStart／check／
                //     archive.register／共有採用が無保護＝そこで例外が出ると Deferred が例外完了し await 再送出で
                //     **全ワーカーの keep-best 成果ごと optimize() が失敗**していた（runMultiWorker は仮説ごとの
                //     隔離＋firstError＋全滅時のみフォールバックを採用済みの非対称）。epoch 本体を try で包み、
                //     例外はこのワーカーの epoch ループだけを終了させて現エリートを成果として返す。
                // [3.346.1/方針B] 停滞シグナルは単調でない（改善が届けば偽に戻る）ので、
                //   `while` 条件には単調な締切・勝者確定だけを置き、シグナルは confirmStop で
                //   確認窓ぶん再確認してから離脱する。一瞬のシグナルで片肺運転にならない。
                while (nowMs() < deadline) {
                    if (shouldStop()) {
                        if (confirmStop(shouldStop, deadline, stopIsFinal)) {
                            exitReason = if (stopIsFinal()) "探索締切" else "停滞シグナル"
                            break
                        }
                        survivedStops++
                        continue
                    }
                    try {
                    val assignment = AdaptiveHypothesisEpochPolicy.assignmentFor(i, reassignments)
                    val epochT0 = nowMs()
                    val roleSeed = AdaptiveHypothesisEpochPolicy.epochSeed(baseSeed, i, epoch, reassignments)
                    // [3.282.0/新領域ログ監査] エポック改善の基準線＝エポック開始時点の自己エリート。
                    //   旧: `better(result, startReport)` で startReport は**破壊摂動済みの入口盤面**（escape系
                    //   ロールは globalBest を意図的に壊した盤面）＝keep-best のロール実行は入口には
                    //   ほぼ必ず勝つため improvedThisEpoch が恒真化し、plateau 再配属・強度昇圧
                    //   （intensityFor=reassignments/2）・役割回転が全 escape ロールで実質死んでいた
                    //   （実機ログ: W2 が凍結した globalBest を35エポック同一役で摂動し続け再配属0、
                    //   ~8s/epoch=improving量子が常時選択、グローバル改善は150秒ゼロ）。エリート比なら
                    //   「ワーカーの過去最良を実際に前進させたか」という文書化された契約どおりの判定になる。
                    val preEpochEliteReport = eliteReport
                    val snapshot = synchronized(lock) {
                        Triple(
                            globalBest.copy2D(),
                            globalReport,
                            Array(workers) { x -> sharedTrajectories[x].copy2D() },
                        )
                    }
                    var start = adaptiveEpochStart(
                        state = state,
                        globalBest = snapshot.first,
                        localTrajectory = trajectory,
                        peers = snapshot.third,
                        assignment = assignment,
                        seed = roleSeed,
                        shouldStop = shouldStop,
                        wideC3n = options.config.wideC3nBreakDays,
                    )
                    // 拡張希望の禁止へ置いた入口は採らず、自分の軌道から続ける
                    if (!p.keepsExtBan(trajectory, start)) start = trajectory.copy2D()
                    val startReport = UnifiedViolationChecker.check(state, start)
                    archive.register(
                        start, startReport, assignment.role, i, epoch,
                        bridge = startReport.hard == snapshot.second.hard + 1,
                    )
                    trajectory = start
                    if (better(startReport, eliteReport) && p.keepsExtBan(elite, start)) {
                        elite = start.copy2D(); eliteReport = startReport
                        // [3.278.0/監査修正] 旧: eliteLogs 未更新＝この入口盤面が最終勝者になると、採用盤面を
                        //   生成していない古いロール実行のフェーズログが globalLogs としてユーザーに表示されていた。
                        eliteLogs = listOf(MirrorLog(tag = "AdaptivePortfolio",
                            message = "W$i epoch${epoch + 1} 入口盤面(${AdaptiveHypothesisEpochPolicy.roleLabel(assignment)})をエリート採用 HARD=${startReport.hard} total=${startReport.total}"))
                    }
                    var startImprovedGlobal = false
                    synchronized(lock) {
                        sharedTrajectories[i] = start.copy2D()
                        if (better(startReport, globalReport) && p.keepsExtBan(globalBest, start)) {
                            globalBest = start.copy2D(); globalReport = startReport
                            globalLogs = eliteLogs   // [3.278.0] 同上: グローバル側の stale ログも同期
                            startImprovedGlobal = true
                        }
                    }
                    if (startImprovedGlobal) {
                        globalImproves.incrementAndGet(); lastGlobalImproveMs.set(nowMs())
                        if (ownsStatics(runSlot())) publishLiveBest(startReport, start)
                        onProgress(
                            "適応portfolio W$i ${AdaptiveHypothesisEpochPolicy.roleLabel(assignment)} 入口改善",
                            startReport, iterations, nowMs() - started,
                        )
                    }

                    val remainingSec = ((deadline - nowMs() + 999L) / 1000L).toInt().coerceAtLeast(0)
                    val quantum = AdaptiveHypothesisEpochPolicy.quantumSeconds(
                        assignment, improvedPrevious, remainingSec,
                    )
                    if (quantum <= 0) break
                    val roleDeadline = minOf(deadline, nowMs() + quantum * 1000L)
                    val roleIndex = i + reassignments * 8
                    // ロール1本=内部チェーン1本（希釈回避。workers==コア数が通常なので、ロール内で
                    //   さらに分割すると倍率オーバーサブスクライブになる。複数チェーン化
                    //   =portfolioRoleParallelSa は 3.409.21 の単体 A/B で中立＝削除。ON は反復数中央値が
                    //   2/3データセットで低く、チェーン分割が希釈になっていたことまで実測で確認した）。
                    val roleOptions = options.copy(
                        workers = 1,
                        seed = roleSeed,
                        explore = when (assignment.role) {
                            HypothesisEpochRole.HARD_DEBT_RSI_PLUS,
                            HypothesisEpochRole.LARGE_DESTROY_ALNS,
                            HypothesisEpochRole.MAX_DISTANCE_RSI_PLUS -> max(2.0, RoleDiversityHelpers.roleExploreFor(roleIndex))
                            else -> RoleDiversityHelpers.roleExploreFor(roleIndex)
                        },
                        accept = RoleDiversityHelpers.roleAcceptFor(roleIndex),
                        opSelect = RoleDiversityHelpers.roleOpSelectFor(roleIndex),
                        tabu = assignment.role != HypothesisEpochRole.BASELINE_REFINE,
                    )
                    val stopRole = {
                        shouldStop() || nowMs() >= roleDeadline
                    }
                    val roleT0 = nowMs()
                    // [3.600.0] 既に締切／停止済みならロールを始めない（始めれば位相下限ぶん必ず超過する）。
                    val result = if (stopRole() || quantum <= 0) null else try {
                        val progress: (String, ViolationReport?, Long, Long) -> Unit = { phase, rep, it, elapsed ->
                            if (rep?.hard == 0) hardZeroWinner.compareAndSet(-1, i)   // 記録のみ（キルしない）
                            if (i == 0 || rep?.hard == 0) {
                                onProgress(
                                    "適応portfolio W$i epoch${epoch + 1} ${AdaptiveHypothesisEpochPolicy.roleLabel(assignment)} / $phase",
                                    rep, it, elapsed,
                                )
                            }
                        }
                        when (assignment.algorithm) {
                            V6Algorithm.ALNS -> runAlns(state, start.copy2D(), roleOptions, quantum, stopRole, progress)
                            V6Algorithm.RSI -> runRsi(state, start.copy2D(), roleOptions, quantum, stopRole, progress, workerHf63)
                            else -> runRsiPlus(state, start.copy2D(), roleOptions, quantum, stopRole, progress, workerHf63, workerLabel = "W$i epoch${epoch + 1}")
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        firstError.compareAndSet(null, e)
                        null
                    }

                    // [3.409.17] roleDeadline を5秒超えたロールを役割名つきで記録（診断は実行を
                    //   またいで消えるため、集約して operation ログへ写せる形で外へ出す）。
                    if (nowMs() - roleDeadline > 5_000L) {
                        epochOverrunNotes.add(
                            "W$i:${assignment.role.name}(q=${quantum}s→実${(nowMs() - roleT0) / 1000}s)")
                    }

                    // 拡張希望の禁止へ置いたロール結果は採らない
                    if (result != null && p.keepsExtBan(start, result.schedule)) {
                        if (result.report.hard == 0) hardZeroWinner.compareAndSet(-1, i)   // 記録のみ
                        iterations += result.iterations
                        archive.register(
                            result.schedule, result.report, assignment.role, i, epoch,
                            bridge = result.report.hard == snapshot.second.hard + 1,
                        )
                        trajectory = result.schedule.copy2D()
                        if (better(result.report, eliteReport) && p.keepsExtBan(elite, result.schedule)) {
                            elite = result.schedule.copy2D()
                            eliteReport = result.report
                            eliteLogs = result.phaseLogs
                        }
                        var improvedGlobal = false
                        synchronized(lock) {
                            sharedTrajectories[i] = result.schedule.copy2D()
                            if (better(result.report, globalReport) && p.keepsExtBan(globalBest, result.schedule)) {
                                globalBest = result.schedule.copy2D()
                                globalReport = result.report
                                globalLogs = result.phaseLogs
                                improvedGlobal = true
                            }
                        }
                        if (improvedGlobal) {
                            globalImproves.incrementAndGet(); lastGlobalImproveMs.set(nowMs())
                            if (ownsStatics(runSlot())) publishLiveBest(result.report, result.schedule)
                            onProgress(
                                "適応portfolio グローバル最良更新 W$i epoch${epoch + 1}",
                                result.report, iterations, nowMs() - started,
                            )
                        }
                    }
                    // [3.600.0] ロールが協調停止に応じず締切を越えて戻ったとき、被害を1エポックに閉じる。
                    //   成果を回収した**後**に判定する＝締切間際にロールが出した改善解を捨てない。
                    if (nowMs() >= deadline) {
                        exitReason = "探索締切"
                        break
                    }

                    // [3.282.0] エポック改善＝自己エリートの前進（入口盤面の採用・ロール結果の採用いずれも
                    //   eliteReport 更新経由でここに反映される）。escape ロールが摂動入口に勝っただけでは
                    //   改善と数えない＝plateau 再配属・強度昇圧・役割回転が文書どおり機能する。
                    val improvedThisEpoch = better(eliteReport, preEpochEliteReport)
                    stagnantEpochs = AdaptiveHypothesisEpochPolicy.nextStagnantEpochs(
                        stagnantEpochs, improvedThisEpoch,
                    )
                    val nearest = synchronized(lock) {
                        var d = Int.MAX_VALUE
                        for (x in 0 until workers) if (x != i) {
                            d = minOf(d, RoleDiversityHelpers.scheduleDistance(trajectory, sharedTrajectories[x]))
                        }
                        d
                    }
                    if (AdaptiveHypothesisEpochPolicy.shouldReassign(
                            index = i,
                            improvedThisEpoch = improvedThisEpoch,
                            stagnantEpochs = stagnantEpochs,
                            nearestOtherDistance = nearest,
                        )
                    ) {
                        reassignments++
                        stagnantEpochs = 0
                        // [3.308.1/敵対検証] 既定経路はこの分岐で常に基準量子へ戻していた（旧
                        //   `improvedPrevious = false`）。roleChanged=true はその挙動を保つための
                        //   引数であって「役割が必ず変わる」という主張ではない。実際 W4 は
                        //   再配属2回目以降 ELITE_RELINK のまま変わらない
                        //   （assignmentFor(4, r>=1) は常に ELITE_RELINK）。W1/2/3/5/6/7 は
                        //   escapeRoles の index が1つ進むので必ず変わる。
                        improvedPrevious = AdaptiveHypothesisEpochPolicy
                            .carriesImprovingQuantum(improvedThisEpoch, roleChanged = true)
                    } else {
                        improvedPrevious = AdaptiveHypothesisEpochPolicy
                            .carriesImprovingQuantum(improvedThisEpoch, roleChanged = false)
                    }
                    // [3.282.0] 集計はロールが実際に走ることが確定してから（旧: quantum<=0 break の
                    //   前に merge しており、締切間際に「実行していないロール」が1件多く summary に載っていた）。
                    // [3.308.1/敵対検証] 回数と秒をここで同時に数える。旧実装は回数だけをエポック
                    //   冒頭で加算していたため、例外で break したエポックが回数には入るのに秒には
                    //   入らず、さらに epoch++ もされないので sum(roleRuns) > epochs になっていた
                    //   （ログの角括弧の合計が epoch 数と合わない）。両方をここに置けば
                    //   sum(roleRuns) == epochs == roleMillis の母集団が常に成り立つ。
                    //   なお `quantum <= 0` と例外の break はここへ到達しないため、その回の
                    //   摂動＋フル検査の時間は秒合計に入らない。実測でも 8ワーカー×300秒=2400 に対し
                    //   計2396s と数秒少なく出る（各ワーカーの最後の1回ぶん）。
                    roleRuns.merge(assignment.role, 1, Int::plus)
                    roleMillis.merge(assignment.role, nowMs() - epochT0, Long::plus)
                    epoch++
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        // [3.278.0] epoch単位の隔離: このワーカーだけ停止し、蓄積済みエリートを成果として返す。
                        firstError.compareAndSet(null, e)
                        exitReason = "例外"
                        break
                    }
                }
                // [3.346.0] while 条件のどれで抜けたかを確定
                //   （例外と確認済み停滞シグナルは上で確定済み。ここは単調な2条件のみ）。
                if (exitReason.isEmpty()) {
                    exitReason = "締切"   // [3.376.0] 勝者キル撤廃により、単調な離脱条件は締切のみ
                }
                val exitAtSec = (nowMs() - started) / 1000

                AdaptiveWorkerOutcome(
                    elite = elite,
                    report = eliteReport,
                    logs = eliteLogs,
                    lastRole = AdaptiveHypothesisEpochPolicy.assignmentFor(i, reassignments).role,
                    exitReason = exitReason,
                    exitAtSec = exitAtSec,
                    survivedStops = survivedStops,
                    iterations = iterations,
                    epochs = epoch,
                    reassignments = reassignments,
                    roleRuns = roleRuns,
                    roleMillis = roleMillis,
                    hf63Avoided = workerHf63.infeasibleFamilies(),
                    epochOverruns = epochOverrunNotes,
                )
            }
        }

        val outcomes = jobs.map { d -> d.await() }
        ensureActive()
        for ((index, o) in outcomes.withIndex()) {
            archive.register(
                o.elite, o.report,
                o.lastRole,
                index, o.epochs, bridge = o.report.hard == globalReport.hard + 1,
            )
            if (better(o.report, globalReport) && p.keepsExtBan(globalBest, o.elite)) {
                globalBest = o.elite.copy2D(); globalReport = o.report; globalLogs = o.logs
            }
        }
        val compressedElites = archive.snapshot(globalBest, globalReport)
        val alts = compressedElites.asSequence()
            .filter { !it.bridge }
            .filter { !AdaptiveEliteArchive.sameSchedule(it.schedule, globalBest) }
            .map { it.schedule.copy2D() }
            .take(3)
            .toList()
        // [3.335.0] まずこの実行のスロットへ。static は新しい実行が勝つライブ表示用なので所有時のみ。
        runSlot()?.let { it.fusionElites = compressedElites; it.alternatives = alts }
        if (ownsStatics(runSlot())) OptimizerRuntime.publishRunOutputs(alts, compressedElites)

        // [3.332.0/実機ログで判明] 旧表記は「圧縮elite=N 相異なるelite=M 距離=a..b」を1行に並べていたが、
        //   **M と 距離 の母集団が違った**（M=アーカイブの圧縮エリート／距離=8ワーカーの最終解）。
        //   読むと「10件すべて相異なるのに最小距離0」という矛盾に見える。しかも M は恒真値だった
        //   （`register` が sameSchedule で重複を弾き `snapshot` も filterNot で除くので、
        //   圧縮エリートは常に相異なる＝実機ログでも2実行とも 10/10）。
        //   意味があるのは**ワーカー解が潰れているか**（同一解に収束＝並列の無駄）なので、そちらを出す。
        val distinctWorkers = outcomes.map { BoardKey(it.elite) }.distinct().size
        val pairDistances = ArrayList<Int>()
        for (i in outcomes.indices) for (j in i + 1 until outcomes.size) {
            pairDistances.add(RoleDiversityHelpers.scheduleDistance(outcomes[i].elite, outcomes[j].elite))
        }
        val distanceNote = if (pairDistances.isEmpty()) "対象外" else
            "${pairDistances.minOrNull()}..${pairDistances.maxOrNull()}セル" +
                (if (distinctWorkers < outcomes.size) "・同一解あり" else "")
        val roleNote = outcomes.indices.joinToString(" | ") { i ->
            val o = outcomes[i]
            val used = o.roleRuns.entries.joinToString(",") { e ->
                val sec = (o.roleMillis[e.key] ?: 0L) / 1000.0
                "${e.key.name}x${e.value}/${"%.0f".format(sec)}s"
            }
            val avoided = if (o.hf63Avoided.isEmpty()) "" else "/HF63回避=${o.hf63Avoided.joinToString("+")}"
            val survived = if (o.survivedStops == 0) "" else "/停滞見送り${o.survivedStops}回"
            "W${i}:epoch${o.epochs}/再配属${o.reassignments}[$used]$avoided/離脱=${o.exitReason}@${o.exitAtSec}s$survived"
        }
        // [3.307.0/ログ強化] 全ワーカー横断の役割別 worker秒。予算配分を論じるときに見るのはここ
        //   （量子は「1エポックの要求長」であって消費ではない＝両者を取り違えると偏りの診断を誤る）。
        val roleTotals = LinkedHashMap<HypothesisEpochRole, Long>()
        for (o in outcomes) for ((r, ms) in o.roleMillis) roleTotals.merge(r, ms, Long::plus)
        val totalWorkerMs = roleTotals.values.sum().coerceAtLeast(1L)
        // [3.409.4] 外側ワーカーの実効並列度。片肺化（設定8なのに実質1本）を数字1つで検出する。
        val outerParallelism = HypothesisPlanning.observedOuterParallelism(totalWorkerMs, nowMs() - started)
        val budgetNote = roleTotals.entries.sortedByDescending { it.value }.joinToString(" ") { e ->
            "${e.key.name}=${e.value / 1000}s(${e.value * 100 / totalWorkerMs}%)"
        }
        // [3.346.0/実機ログ] 締切前に離脱したワーカーを1行で明示する。`shouldStop` は単調でないため
        //   一瞬の停滞シグナルでポーリングが当たったワーカーだけが恒久的に抜け、残りは走り続ける
        //   （実機 2026-08-03: 8本中4本が 115〜116s で離脱＝残り159秒を半分の並列度で走っていた）。
        //   旧ログは役割別worker秒を手で足さないと気づけなかった。
        //   [3.346.1] 一瞬のシグナルは confirmStop が見送るので、ここに出る停滞シグナルは
        //   確認窓を通った本物。見送り回数も併記して「何回きわどい発火があったか」を残す。
        //   [3.409.16] 早期離脱の判定は HypothesisPlanning.isEarlyWorkerExit（KDoc参照＝「探索締切」は正常終了）。
        val earlyExits = outcomes.filter { HypothesisPlanning.isEarlyWorkerExit(it.exitReason) }
        val survivedTotal = outcomes.sumOf { it.survivedStops }
        val survivedNote = if (survivedTotal == 0) "" else " 停滞見送り計${survivedTotal}回"
        val exitNote = (if (earlyExits.isEmpty()) "ワーカー離脱=全て締切まで実行" else
            "ワーカー離脱=${earlyExits.size}/${outcomes.size}本が締切前(" +
                earlyExits.groupBy { it.exitReason }.entries.joinToString(",") { e ->
                    "${e.key}${e.value.size}本@${e.value.joinToString("/") { "${it.exitAtSec}s" }}"
                } + ")") + survivedNote
        val summary = MirrorLog(
            tag = "AdaptivePortfolio",
            // [3.360.0] 合計iter と 最良更新回数 を併記。MultiWorker/AlnsChains/V5 は元から合計iterを出すのに
            //   PORTFOLIO（予算211秒以上の既定経路＝実機の主経路）だけが出しておらず、規模の比較ができなかった。
            message = "合計iter=${outcomes.sumOf { it.iterations }} 全体最良更新=${globalImproves.get()}回 / " +
                "非同期適応仮説 archive=${archive.size()} 圧縮elite=${compressedElites.size} " +
                "ワーカー解=${outcomes.size}本(相異なる${distinctWorkers}本) 距離=$distanceNote / $exitNote / " +
                "役割別worker秒(計${totalWorkerMs / 1000}s・実効外側並列=${"%.2f".format(outerParallelism)}): $budgetNote / $roleNote" +
                (firstError.get()?.let { " / 一部例外=${it.message}" } ?: "") +
                " / 採用 HARD=${globalReport.hard} total=${globalReport.total}",
        )
        // [3.409.17] エポック超過（役割名つき）は専用の [W] 行で出す。ViewModel が予算超過の実行で
        //   この行を操作ログへ写す＝診断ログが次の実行で消えても証拠が生き残る。
        val overrunLog = listOfNotNull(HypothesisPlanning.epochOverrunLog(outcomes.flatMap { it.epochOverruns }))
        val logs = globalLogs + overrunLog + summary
        V6OptimizerResult(
            globalBest,
            globalReport.copy(logs = logs + globalReport.logs),
            V6Algorithm.PORTFOLIO,
            logs,
            outcomes.sumOf { it.iterations },
            nowMs() - started,
            epochOverrunCount = outcomes.sumOf { it.epochOverruns.size },
        )
    }

    private fun adaptiveEpochStart(
        state: MagiState,
        globalBest: Array<IntArray>,
        localTrajectory: Array<IntArray>,
        peers: Array<Array<IntArray>>,
        assignment: HypothesisEpochAssignment,
        seed: Long,
        shouldStop: () -> Boolean,
        wideC3n: Boolean,
    ): Array<IntArray> {
        val p = cachedProblem(state)
        val rng = Random(seed)
        val n = max(1, assignment.intensity)
        return when (assignment.role) {
            HypothesisEpochRole.BASELINE_REFINE -> localTrajectory.copy2D()
            HypothesisEpochRole.ELITE_RELINK -> {
                val alternatives = peers.asSequence()
                    .filter { RoleDiversityHelpers.scheduleDistance(globalBest, it) > 0 }
                    .sortedByDescending { RoleDiversityHelpers.scheduleDistance(globalBest, it) }
                    .take(3).map { it.copy2D() }.toList()
                val relinked = EliteRelinking.elitePathRelink(state, globalBest, alternatives, shouldStop).first
                if (RoleDiversityHelpers.scheduleDistance(globalBest, relinked) > 0) relinked
                else hypothesisStartFor(state, globalBest, 7, seed)
            }
            HypothesisEpochRole.DAY_BLOCK_ALNS -> globalBest.copy2D().also { out ->
                if (p.T > 0) {
                    val first = rng.nextInt(p.T)
                    repeat(n * 2) { x -> DestroyRepairOperators.destroyRepairDayAt(state, out, (first + x) % p.T, rng) }
                }
            }
            HypothesisEpochRole.HARD_FAMILY_RSI -> {
                var out = globalBest.copy2D()
                repeat(n) {
                    val rep = UnifiedViolationChecker.check(state, out)
                    val focus = when {
                        (rep.breakdown["covU"] ?: 0) > 0 -> "covU"
                        (rep.breakdown["c3n"] ?: 0) > 0 -> "c3n"
                        else -> RsiFocusSelection.maxViolatedFamily(rep)
                    }
                    out = RsiHypothesisOperators.rsiGenerateHypothesis(state, out, rep, focus, rng, wideC3n = wideC3n)
                }
                out
            }
            HypothesisEpochRole.HARD_DEBT_RSI_PLUS -> globalBest.copy2D().also { out ->
                forceDiverseKick(p, out, rng, 2 + n)
            }
            HypothesisEpochRole.LARGE_DESTROY_ALNS -> globalBest.copy2D().also { out ->
                repeat(n * 2) {
                    if (p.T > 0) DestroyRepairOperators.destroyRepairDayAt(state, out, rng.nextInt(p.T), rng)
                    if (p.S > 0) DestroyRepairOperators.destroyRepairStaffAt(state, out, rng.nextInt(p.S), rng)
                }
            }
            HypothesisEpochRole.PERSONAL_RSI -> {
                var out = globalBest.copy2D()
                repeat(n) {
                    val rep = UnifiedViolationChecker.check(state, out)
                    val focus = when {
                        (rep.breakdown["apt"] ?: 0) > 0 -> "apt"
                        (rep.breakdown["high"] ?: 0) > 0 -> "high"
                        (rep.breakdown["low"] ?: 0) > 0 -> "low"
                        (rep.breakdown["fair"] ?: 0) > 0 -> "fair"
                        else -> "total"
                    }
                    out = RsiHypothesisOperators.rsiGenerateHypothesis(state, out, rep, focus, rng, wideC3n = wideC3n)
                }
                out
            }
            HypothesisEpochRole.MAX_DISTANCE_RSI_PLUS -> globalBest.copy2D().also { out ->
                forceMaxDistanceKick(p, out, peers, rng, 3 + n)
            }
            HypothesisEpochRole.PERSON_SWAP_ILS -> globalBest.copy2D().also { out ->
                personSwapKick(p, out, rng, n)
            }
        }
    }

    private fun forceMaxDistanceKick(
        p: Problem,
        out: Array<IntArray>,
        peers: Array<Array<IntArray>>,
        rng: Random,
        target: Int,
    ) {
        if (p.S == 0 || p.T == 0) return
        var changed = 0
        var attempts = 0
        val touched = HashSet<Long>()
        while (changed < target && attempts++ < max(64, p.S * p.T * 6)) {
            val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
            val key = i.toLong() * max(1, p.T) + j
            if (!touched.add(key) || p.wishLocked(i, j)) continue
            val old = out[i][j]
            val allowed = p.allowedShiftsForStaff(i).filter { it != old }
            if (allowed.isEmpty()) continue
            var bestK = -1; var bestFreq = Int.MAX_VALUE; var tied = 0
            for (k in allowed) {
                if (p.extBanned(i, j, k)) continue   // 拡張希望の禁止へは置かない
                val freq = peers.count { peer -> peer.getOrNull(i)?.getOrNull(j) == k }
                if (freq < bestFreq) { bestFreq = freq; bestK = k; tied = 1 }
                else if (freq == bestFreq) {
                    tied++
                    if (HypothesisDiversityPolicy.takeReservoirTie(tied, rng)) bestK = k
                }
            }
            if (bestK >= 0) { out[i][j] = bestK; changed++ }
        }
    }

    /**
     * [3.517.0] PERSON_SWAP_ILS: 同群2名の1ヶ月分割当を丸ごと交換するILS摂動。交換相手は全ペア
     * 総当たりでなく「fair 負担が大きい職員」優先（経緯・実証データは `docs/history/3.4xx.md` 3.517.0）。
     * [希望固定の徹底] [wishPinStrict] の間は、どちらかのセルが希望固定の日は交換しない（その日だけ行が残る）。
     */
    internal fun personSwapKick(p: Problem, out: Array<IntArray>, rng: Random, pairs: Int) {
        if (p.S < 2 || p.T == 0) return
        val counts = Array(p.S) { IntArray(p.K) }
        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = out[i][j]
            if (k in 0 until p.K) counts[i][k]++
        }
        // 職員ごとの負担＝群×担当ONシフトごとに、群の全メンバーの生回数 round(平均) からの L1 偏差の和。
        // 評価器の fair（`Problem.fairDevOfBucket`＝3.541.0 達成率モード、母集団は mayPlace/回数>0）とは別の旧式。
        // 評価器の式へ揃えるのは交換相手の選び方＝探索動学の変更なので別件。
        val burden = DoubleArray(p.S)
        for (g in 0 until p.G) {
            val mem = p.groupMembers[g]
            if (mem.size < 2) continue
            for (k in p.bucket[g]) {
                var sum = 0
                for (x in mem) sum += counts[x][k]
                val tgt = Math.round(sum.toDouble() / mem.size).toInt()
                for (x in mem) burden[x] += kotlin.math.abs(counts[x][k] - tgt)
            }
        }
        val swapped = BooleanArray(p.S)
        var done = 0
        var attempts = 0
        while (done < pairs && attempts++ < max(32, p.S * 4)) {
            val a = weightedBurdenPick(burden, swapped, rng) ?: break
            val g = p.sgrp[a]
            val candidates = p.groupMembers[g].filter { it != a && !swapped[it] }
            if (candidates.isEmpty()) { swapped[a] = true; continue }
            val b = candidates.maxByOrNull { burden[it] } ?: continue
            for (j in 0 until p.T) {
                if (p.wishLocked(a, j) || p.wishLocked(b, j) || p.pinned(a, j) || p.pinned(b, j)) continue
                // 個人上限0は職員ごと＝同じ群でも相手の勤務を置けない日がある（mayPlace、3.507.0）。その日は交換しない。
                val ka = out[a][j]; val kb = out[b][j]
                if ((kb in 0 until p.K && !p.mayPlace(a, kb)) || (ka in 0 until p.K && !p.mayPlace(b, ka))) continue
                if (ka != kb && (p.extBanned(a, j, kb) || p.extBanned(b, j, ka))) continue   // 拡張希望の禁止へは置かない
                val tmp = out[a][j]; out[a][j] = out[b][j]; out[b][j] = tmp
            }
            swapped[a] = true; swapped[b] = true
            done++
        }
    }

    private fun weightedBurdenPick(burden: DoubleArray, excluded: BooleanArray, rng: Random): Int? {
        val candidates = burden.indices.filter { !excluded[it] }
        if (candidates.isEmpty()) return null
        val total = candidates.sumOf { burden[it] }
        if (total <= 0.0) return candidates[rng.nextInt(candidates.size)]
        var r = rng.nextDouble() * total
        for (i in candidates) {
            r -= burden[i]
            if (r <= 0.0) return i
        }
        return candidates.last()
    }

    /**
     * Run up to [w] independent hypotheses concurrently (distinct seeds) and keep the best —
     * the native W0..Wn multi-worker pool with the spec's hybrid termination (§2.2/§4.2):
     *  - 絶対評価: the first hypothesis to reach the pass line (HARD=0) is recorded as the winner.
     *    [3.376.0] It no longer cancels the others: once HARD=0 is reached the remaining work is all
     *    SOFT, and keeping a single worker wastes the parallelism the user asked for.
     *  - 相対評価: if none passes by the deadline, the lowest-penalty hypothesis is adopted.
     * Worker 0's progress is forwarded, prefixed with the number of hypotheses still running.
     */
    internal suspend fun runMultiWorker(
        w: Int,
        options: V6OptimizerOptions,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
        run: suspend (Int, V6OptimizerOptions, (String, ViolationReport?, Long, Long) -> Unit) -> V6OptimizerResult,
    ): V6OptimizerResult = kotlinx.coroutines.supervisorScope {
        // [3.371.0/並列SA本格再有効化] spawn数×チェーン内訳は HypothesisPlanning.hypothesisSpawnPlan（単一ソース）から。
        val (hSpawn, plan) = HypothesisPlanning.hypothesisSpawnPlan(options.workers, w)
        if (hSpawn <= 1) return@supervisorScope run(0, options.copy(workers = plan[0]), onProgress)
        val base = actualSeed(options.seed)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val winner = java.util.concurrent.atomic.AtomicInteger(-1)
        // [レビュー#2 3.213.0] 全ワーカー横断の最良(hard→weighted→total)を追跡し、どのワーカーの改善も
        //   外側へ転送する。旧: i==0 のみ転送＝W1..W4 だけが改善を続ける局面で外側ウォッチドッグ
        //   (V6FinalPort の停滞時計)が改善を観測できず、HARD平坦時の短い猶予(stallHardMs)で全ワーカーを
        //   早期停止し得た。非改善レポートまで転送すると phase 文字列が W0 と交互に振れて外側の
        //   フェーズ遷移リセット（意図的な猶予付与）を偽発火させるため、改善時のみ転送する。
        val sharedBest = java.util.concurrent.atomic.AtomicReference<ViolationReport?>(null)
        fun improvesShared(r: ViolationReport): Boolean {
            while (true) {
                val cur = sharedBest.get()
                if (cur != null && !better(r, cur)) return false
                if (sharedBest.compareAndSet(cur, r)) return true
            }
        }
        // [敵対的レビュー修正・#4例外隔離] supervisorScope＋仮説ごとのtry/catchで、1仮説の通常例外が
        //   他仮説を道連れにしない（runAlnsChainsと同型のパターンへ統一）。firstErrorは全滅時のみ使用。
        val firstError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        // [敵対的レビュー修正・#5早期winner] 全ジョブをLAZYで生成してから一斉start。生成前のjobsはnullでは
        //   なくLAZYな未開始Deferredなので、早いwinnerのcancel()がまだstart前のジョブにも正しく効く
        //   （旧実装はjobs配列がnullのままcancel()を呼んでも無効化されず、後から作られる新規ジョブが
        //   キャンセルを免れて走ってしまっていた）。
        val jobs = arrayOfNulls<kotlinx.coroutines.Deferred<V6OptimizerResult?>>(hSpawn)
        for (i in 0 until hSpawn) {
            jobs[i] = async(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                // [3.491.0/外部レビュー第7弾] 旧: 「開始時点で既に勝者が確定していれば何もせず抜ける」
                //   事前チェックが残っていた。3.376.0 で「HARD=0 到達で残りを即キャンセル」を撤廃し
                //   winner を記録専用にしたのに、この1行だけが**仮説の起動を黙って省く**経路として生き残り、
                //   スレッドプールの起動順しだいで仕様（全本継続）と違う本数しか走らなかった。撤去。
                try {
                    // [HF290 役割分担＋論文活用] 各仮説に探索/精製プロファイル＋受理基準(SA/GD)を割当て多様化（W0=ベースライン）。
                    run(i, options.copy(workers = plan[i], seed = base + (i + 1) * 0x9E3779B1L, explore = RoleDiversityHelpers.roleExploreFor(i), accept = RoleDiversityHelpers.roleAcceptFor(i), opSelect = RoleDiversityHelpers.roleOpSelectFor(i))) { phase, report, iters, elapsed ->
                        val improved = report != null && improvesShared(report)
                        if (i == 0 || improved) onProgress("仮説${(hSpawn - completed.get()).coerceAtLeast(1)}本探索中 / $phase", report, iters, elapsed)
                        // 絶対評価: 合格ライン(HARD=0)に最初に到達した仮説が、残りを即キャンセル
                        // [3.376.0] 「HARD=0 到達で残りを即キャンセル」を撤廃（runAdaptivePortfolio と同じ理由:
                        //   到達後に残る仕事は全部 SOFT で、1本に絞ると指定した並列度が無駄になる）。
                        //   winner は「誰が最初に合格したか」の記録として残す（下のログ表記に使う）。
                        if (report != null && report.hard == 0) winner.compareAndSet(-1, i)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    firstError.compareAndSet(null, e)
                    null
                } finally {
                    completed.incrementAndGet()
                }
            }
        }
        jobs.forEach { it?.start() }
        val results = jobs.mapNotNull { d ->
            try { d?.await() } catch (_: kotlinx.coroutines.CancellationException) { null }
        }
        // 兄弟キャンセル(自己)とユーザー停止(外部)を区別: 外部停止ならここで伝播させる。
        ensureActive()
        val best = if (results.isEmpty()) run(0, options.copy(workers = plan[0]), onProgress)
        else results.reduce { a, b -> if (better(b.report, a.report)) b else a }
        if (ownsStatics(runSlot())) publishLiveBest(best.report, best.schedule)
        // 「他の案」: 採用案以外の仮説結果を品質順に保持（重複schedule除外、最大3件）
        val alts = results.asSequence()
            .filter { it !== best }
            .sortedWith(compareBy(reportComparator) { it.report })
            .map { it.schedule }
            .distinctBy { BoardKey(it) }
            .take(3)
            .toList()
        runSlot()?.alternatives = alts                      // [3.335.0] この実行の「他の案」
        if (ownsStatics(runSlot())) OptimizerRuntime.publishRunOutputs(alts)
        val totalIters = results.sumOf { it.iterations }
        val mode = if (winner.get() >= 0) "合格あり(全本継続)" else "時間内最良採用"
        val chainNote = if (plan.max() > 1) "・仮説内${plan.min()}〜${plan.max()}並列(SA/ALNS多チェーン、設定${options.workers}がコア数を超えるため仮説数を絞り並列SAへ配分)" else ""
        val failNote = if (results.size < hSpawn) "・失敗${hSpawn - results.size}本(例外/キャンセル${firstError.get()?.let { "・${it.message}" } ?: ""})" else ""
        // [3.266.0/hypothesis basin diversity] 各仮説の入口が実際にどう多様化されたかをログに残す。
        val entryRoles = (0 until hSpawn).joinToString(" ") { i ->
            val sp = HypothesisDiversityPolicy.startPlanFor(i)
            "W$i=${sp.mode.name.removeSuffix("_REPAIR")}${if (sp.intensity > 0) "x${sp.intensity}" else ""}"
        }
        val hypNote = if (hSpawn < w) "${hSpawn}本(設定仮説数${w}をコア数まで縮小)" else "${hSpawn}本"
        val extra = MirrorLog(tag = "MultiWorker", message = "仮説 $hypNote ($mode・役割分担:探索/精製＋受理SA/GreatDeluge多様化$chainNote$failNote) → 採用 HARD=${best.report.hard} total=${best.report.total} 合計iter=${totalIters} / 入口役割 $entryRoles")
        // [過程検証] 各仮説の個別結果・多様性（相異なる解の数）・保持した他の案数をログ化し、探索過程を後から検証できるようにする。
        //   各仮説の合計が揃っていれば収束、ばらけていれば多様な探索ができている、と判別できる。
        val perHyp = results.sortedWith(compareBy(reportComparator) { it.report })
            .joinToString("  ") { r -> "[必須${r.report.hard}/合計${r.report.total}${if (r === best) "★採用" else ""}]" }
        val distinctSols = results.map { BoardKey(it.schedule) }.distinct().size
        val pairDistances = ArrayList<Int>()
        for (a in results.indices) for (b in a + 1 until results.size) {
            pairDistances.add(AdaptiveEliteArchive.scheduleDistance(results[a].schedule, results[b].schedule))
        }
        val distanceNote = if (pairDistances.isEmpty()) "解間距離=対象外" else
            "解間距離=${pairDistances.minOrNull()}..${pairDistances.maxOrNull()}セル"
        val verifyLog = MirrorLog(tag = "仮説検証", message = "各仮説 ${results.size} 本の結果: $perHyp / 相異なる解=${distinctSols}件 / $distanceNote / 他の案として保持=${OptimizerRuntime.lastAlternatives.size}件")
        best.copy(phaseLogs = best.phaseLogs + extra + verifyLog, iterations = totalIters)
    }
}
