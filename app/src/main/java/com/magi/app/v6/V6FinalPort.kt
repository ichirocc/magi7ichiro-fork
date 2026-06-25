package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive

/**
 * Final bridge for Web App-level handlers.
 *
 * V6 Web kept many behaviors inside React App methods instead of standalone
 * worker functions: handleSimple, handleCheck, handleOptimize, busy-detail
 * construction, impossible-wish gate, algorithm labels, and post-optimization
 * HF80/HF67/HF66/HF70 chaining.  This object ports those App-level semantics as
 * pure Kotlin so ViewModel/Compose can call the same workflow without WebView.
 */
/** 勤務表最適化のタイムアウト上限（秒）。高精度を保ったまま5分(300s)以内へ圧縮（停滞早期脱出＋RSI++をこの予算に収める）。 */
const val MAX_OPTIMIZE_SEC = 300

object V6FinalPort {
    data class BusyDetail(
        val algorithm: String,
        val base: String = algorithm,
        val problemSize: String,
        val constraintCount: String,
        val subtitle: String = "",
        val phaseDesc: String = "",
        val expectedSec: String = "",
        val estimatedIter: String = "",
        val uiFrozen: Boolean = false,
        val startedAt: Long = System.currentTimeMillis(),
    )

    data class AlgorithmLabel(val icon: String, val name: String, val desc: String, val tech: String)

    sealed class OptimizationPlan {
        data class V5(val seconds: Int) : OptimizationPlan()
        data class ALNS(val seconds: Int, val restarts: Int) : OptimizationPlan()
        data class RSIThenALNS(val rsiSec: Int, val alnsSec: Int, val alnsRestarts: Int) : OptimizationPlan()
        data class RSIPlus(val seconds: Int) : OptimizationPlan()
    }

    data class ImpossibleWishGate(
        val allowed: Boolean,
        val count: Int,
        val message: String,
        val logs: List<MirrorLog>,
    )

    data class ActionResult(
        val schedule: Array<IntArray>,
        val report: ViolationReport,
        val phase: String,
        val busyDetail: BusyDetail,
        val logs: List<MirrorLog>,
        val post: V6PostOptimizationResult? = null,
    )

    fun buildBusyDetail(state: MagiState, algorithm: String, overrides: Map<String, String> = emptyMap()): BusyDetail {
        val n = state.staffCount
        val t = state.dayCount
        val k = state.shiftCount
        val totalHardCons = state.cons1.size + state.cons2.size + state.cons3.size + state.cons3n.size + state.cons41.size + state.cons42.size
        val totalSoftCons = state.cons3m.size + state.cons3mn.size
        val wishCount = state.wishes.size
        return BusyDetail(
            algorithm = algorithm,
            problemSize = "${n}名 × ${t}日 × ${k}シフト = ${n * t * k} セル",
            constraintCount = "HARD ${totalHardCons}件 / SOFT ${totalSoftCons}件 / 希望 ${wishCount}件",
            subtitle = overrides["subtitle"].orEmpty(),
            phaseDesc = overrides["phaseDesc"].orEmpty(),
            expectedSec = overrides["expectedSec"].orEmpty(),
            estimatedIter = overrides["estimatedIter"].orEmpty(),
            uiFrozen = overrides["uiFrozen"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    fun confirmDespiteImpossibleWishes(state: MagiState, allowImpossible: Boolean = false): ImpossibleWishGate {
        val imp = V6SanityPort.detectImpossibleWishes(state)
        if (imp.isEmpty()) return ImpossibleWishGate(true, 0, "不可能希望なし", emptyList())
        val lines = imp.groupBy { it.staffName }.entries.take(12).map { (name, rows) ->
            "・$name: ${rows.size}件 (${rows.take(3).joinToString { "${it.dayIndex + 1}日=${it.shiftSymbol}" }})"
        }
        val msg = "不可能希望が ${imp.size}件あります。担当範囲外シフトへの希望は永久に充足できません。\n" +
            lines.joinToString("\n") +
            if (imp.size > lines.sumOf { line -> Regex("\\d+件").find(line)?.value?.removeSuffix("件")?.toIntOrNull() ?: 0 }) "\n…詳細はSanityCheckを確認" else ""
        val level = if (allowImpossible) "W" else "E"
        val logs = listOf(MirrorLog(level = level, tag = "ImpossibleWishGate", message = msg.replace("\n", " / ")))
        return ImpossibleWishGate(allowImpossible, imp.size, msg, logs)
    }

    fun optimizationPlan(seconds: Int): OptimizationPlan {
        // [review #4] Honor the user's budget: the algorithm is still chosen by range, but the
        // run time uses the requested `seconds` (previously fixed 10/30/90/150+75... regardless).
        // [5分圧縮] 上限300sでも最上位の RSI++ 4フェーズが回るよう閾値を前倒し。
        val s = seconds.coerceAtLeast(1)
        return when {
            s <= 30 -> OptimizationPlan.V5(s)
            s <= 60 -> OptimizationPlan.ALNS(s, 1)
            s <= 90 -> OptimizationPlan.ALNS(s, 2)
            s <= 210 -> { val rsi = (s * 2) / 3; OptimizationPlan.RSIThenALNS(rsi, s - rsi, 2) }
            else -> OptimizationPlan.RSIPlus(s)
        }
    }

    fun getAlgorithmLabel(seconds: Int): AlgorithmLabel = when {
        seconds <= 10 -> AlgorithmLabel("⚡", "高速", "短時間でサッと作成", "v5")
        seconds <= 30 -> AlgorithmLabel("★", "標準", "速さと品質のバランス", "v5")
        seconds <= 60 -> AlgorithmLabel("★★", "高品質", "じっくり改善", "ALNS×1")
        seconds <= 90 -> AlgorithmLabel("★★", "推奨", "品質重視 （おすすめ）", "ALNS×2")
        seconds <= 210 -> AlgorithmLabel("🧬", "学習+研磨", "RSI重み学習→ALNS研磨 （再現性検証付き）", "RSI→ALNS")
        seconds <= 300 -> AlgorithmLabel("🌈", "究極(5分)", "RSI++ 4フェーズ (Open-ended探索→PhaseC研磨)", "RSI++")
        else -> AlgorithmLabel("🌈", "究極", "最大限の品質 (${seconds / 60}分)", "RSI++拡張")
    }

    suspend fun handleSimple(state: MagiState, allowImpossible: Boolean = false): ActionResult = withContext(Dispatchers.Default) {
        require(state.dayCount > 0) { "対象期間が無効です。終了日を開始日より後の日付にしてください" }
        val gate = confirmDespiteImpossibleWishes(state, allowImpossible)
        if (!gate.allowed) error(gate.message)
        val busy = buildBusyDetail(state, "シフト作成中", mapOf(
            "subtitle" to "初期勤務表を作成中",
            "phaseDesc" to "希望シフトと必要人数を考慮して、担当できるシフトを割り当てています",
            "expectedSec" to "< 1 秒",
            "estimatedIter" to "~800 回",
        ))
        val res = GreedyMirrorScheduler.generate(state)
        val logs = gate.logs + res.report.logs + MirrorLog(tag = "MAGI_GenerateInitial", message = "簡易作成 完了 HARD=${res.report.hard} total=${res.report.total}")
        ActionResult(res.schedule, res.report.copy(logs = logs), "simple", busy, logs)
    }

    suspend fun handleCheck(state: MagiState, schedule: Array<IntArray> = state.schedule.toIntArray2D()): ActionResult = withContext(Dispatchers.Default) {
        val busy = buildBusyDetail(state, "違反チェック中", mapOf(
            "subtitle" to "違反チェック",
            "phaseDesc" to "勤務表のすべての違反を確認しています（最適化結果は変更しません）",
            "expectedSec" to "< 0.1 秒",
            "estimatedIter" to "評価のみ （反復なし）",
        ))
        val report = UnifiedViolationChecker.check(state, schedule)
        val hc = report.hard
        val sc = report.soft
        val logs = report.logs + MirrorLog(tag = "UnifiedCheck", message = if (report.total == 0) "違反なし ✓" else "HARD ${hc}件・品質 ${sc}件")
        ActionResult(schedule.copy2D(), report.copy(logs = logs), "check", busy, logs)
    }

    suspend fun handleOptimize(
        state: MagiState,
        schedule: Array<IntArray> = state.schedule.toIntArray2D(),
        secondsRaw: Int,
        workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
        softPolish: Boolean = false,
        requestedAlgorithm: V6Algorithm = V6Algorithm.AUTO,
        allowImpossible: Boolean = false,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit = { _, _, _, _ -> },
    ): ActionResult = withContext(Dispatchers.Default) {
        require(state.dayCount > 0) { "対象期間が無効です。基本情報で終了日を開始日より後にしてください" }
        // タイムアウト上限10分(600s)を厳守（業務指示）。呼び出し側に依らずここで頭打ちにする。
        val seconds = secondsRaw.coerceIn(1, MAX_OPTIMIZE_SEC)
        val gate = confirmDespiteImpossibleWishes(state, allowImpossible)
        if (!gate.allowed) error(gate.message)
        // [最終番兵用] 入力の評価を保持。万一パイプラインが入力より悪い結果を出した場合に復帰する（多重防御）。
        val baseProblem = cachedProblem(state)
        val normInput = normalizeSchedule(schedule, baseProblem)
        val inputReport = UnifiedViolationChecker.check(state, normInput)
        val label = getAlgorithmLabel(seconds)
        val plan = optimizationPlan(seconds)
        val busy = buildBusyDetail(state, label.name, mapOf(
            "subtitle" to label.desc,
            "phaseDesc" to "${label.tech} をNativeエンジンで実行中",
            "expectedSec" to "約 ${seconds} 秒",
            "estimatedIter" to "問題サイズに応じて自動調整",
        ))
        // [review #1] When the user explicitly picks an algorithm (not AUTO), honor it with the
        // full requested budget. AUTO keeps the time-budget-based plan. [review #3] postPolish=false
        // so optimize() does NOT polish internally — the single post chain below owns polishing.
        val opts = if (requestedAlgorithm != V6Algorithm.AUTO) {
            V6OptimizerOptions(requestedAlgorithm, seconds.coerceAtLeast(1), workers, softPolish, restarts = 2, postPolish = false)
        } else when (plan) {
            is OptimizationPlan.V5 -> V6OptimizerOptions(V6Algorithm.V5, plan.seconds, workers, softPolish, restarts = 1, postPolish = false)
            is OptimizationPlan.ALNS -> V6OptimizerOptions(V6Algorithm.ALNS, plan.seconds, workers, softPolish, restarts = plan.restarts, postPolish = false)
            is OptimizationPlan.RSIThenALNS -> V6OptimizerOptions(V6Algorithm.RSI, plan.rsiSec, workers, softPolish, restarts = plan.alnsRestarts, postPolish = false)
            is OptimizationPlan.RSIPlus -> V6OptimizerOptions(V6Algorithm.RSI_PLUS, plan.seconds, workers, softPolish, restarts = 2, postPolish = false)
        }
        val optsR = opts.copy(rectSwap = V6LateOperators.optFlagBool(state, "rectSwap", true))   // [HF532移植] optFlags.rectSwap 既定ON
        // [review: 600s budget] optimize() + runPostOptimization() を一つの予算で管理する。
        // 後処理は元々 deadline も progress も持たず、optimize が予算を使い切った後も走り続け、
        // 合計が予算を大きく超過していた(実機44分/予算600s)。ここで全体に hardDeadline を張り、
        // coroutine キャンセル(計算を止める)と束ねて後処理へ伝播する。
        val startMs = System.currentTimeMillis()
        val budgetMs = seconds.toLong() * 1000L
        val hardDeadlineMs = startMs + budgetMs
        val effHypotheses = workers.coerceIn(1, 5)   // 仕様§2.2: 最大5仮説並列(workers>5でも実効5)

        // ----- 停滞早期脱出ウォッチドッグ -----
        // 進捗ストリームから「最良解(hard→total→重み付きの辞書順)」の更新時刻を追跡し、一定時間
        // (stallMs)改善が無ければ予算上限を待たずに終了する。改善が続く限り絶対に止めない＝品質は不変。
        // フェーズ遷移でもタイマをリセットするので、各フェーズには必ず stallMs 分の猶予がある。
        // 狙い: 「データ上 HARD=0 にできない / 既に研磨が頭打ち」の局面で、最大10分の予算を無駄に
        // 回し続ける（=ユーザーには『ハングして常に10分かかる』と映る）問題を解消する。
        // [早期脱出方針] 実機ログで停滞検知が予算上限とほぼ同時(301s)に発火＝時間がほぼ節約できていなかった。
        //   停滞許容を短縮して「無改善なら早く返す」方針へ。globalBest は生スコア管理のため早期終了でも品質は不変
        //   （最後の改善時刻でタイマをリセット＝改善が続く限り止めない・フェーズ遷移でもリセットで猶予確保）。
        val minRunMs = (budgetMs / 6).coerceIn(8_000L, 45_000L)   // 最初の猶予（早すぎる停止を防ぐ）
        // [5分強化] HARD>0（=未配布・配れない）は最優先で解消すべき失敗状態。予算の大半を使って多様化
        //   （多仮説＋HF80 戦略的振動）で HARD クリアを試みる。旧 budgetMs/6(=300s予算で50s) は早すぎ、
        //   実機ログで HARD=1 のまま 50s で早期終了し残り 250s を捨てていた。→ budgetMs*9/10(=270s)。
        //   改善が続く限り lastImproveMs がリセットされるので、生産的な探索は自然に締切まで走る。
        val stallMs = (budgetMs * 9 / 10).coerceAtLeast(20_000L)
        // [5分圧縮] HARD=0到達後（=配布可・残りは研磨のみ）は頭打ちをより早く検知して終了（plateauなので品質は不変）。
        val stallHardMs = (budgetMs / 8).coerceAtLeast(15_000L)   // 5分予算→37.5s
        // [賢い早期脱出] 証明可能に解消不能なHARDの下限。実現不能な希望（担当外シフトへの希望）は
        //   どう探索しても消せない＝恒久pref違反なので、HARD はこの件数より下がらない。HARD がこの
        //   下限まで到達したら「HARD=0 到達」と同じく頭打ち(plateau)とみなし、短い stallHardMs で早く返す。
        //   解ける可能性のあるHARD(>下限)は従来どおり stallMs(=予算9/10)でしっかり粘るため退行はしない。
        //   下限は構造的(assignabilityのみ)で最適化中に変化しないため一度だけ算出する。
        val hardFloor = try { V6SanityPort.detectImpossibleWishes(state).size } catch (_: Exception) { 0 }
        val lastImproveMs = java.util.concurrent.atomic.AtomicLong(startMs)
        val stagnationFired = java.util.concurrent.atomic.AtomicBoolean(false)
        val bestHard = java.util.concurrent.atomic.AtomicInteger(Int.MAX_VALUE)   // 並列ワーカーから読むため atomic
        var bTotal = Int.MAX_VALUE; var bWeighted = Double.MAX_VALUE; var lastPhase = ""
        val progressLock = Any()   // [競合解消] 並列ワーカーから呼ばれる best 追跡の read-modify-write を直列化
        val progressWatch: (String, ViolationReport?, Long, Long) -> Unit = { phase, report, iters, elapsed ->
            synchronized(progressLock) {
                val base = phase.substringAfter("/ ").trim().ifEmpty { phase }   // 「仮説N本探索中 / 」接頭辞を除去
                if (base != lastPhase) { lastPhase = base; lastImproveMs.set(System.currentTimeMillis()) }
                if (report != null) {
                    val h = report.hard; val t = report.total; val wgt = report.weightedScore
                    val bh = bestHard.get()
                    val improved = h < bh || (h == bh && t < bTotal) || (h == bh && t == bTotal && wgt < bWeighted - 1e-6)
                    if (improved) { bestHard.set(h); bTotal = t; bWeighted = wgt; lastImproveMs.set(System.currentTimeMillis()) }
                }
            }
            onProgress(phase, report, iters, elapsed)   // ユーザーコールバックはロック外で呼ぶ
        }
        val shouldStop = {
            val now = System.currentTimeMillis()
            // [賢い早期脱出] bestHard が「解消不能な下限(hardFloor)」以下＝解けるHARDは出し切った状態。
            //   この時点で残るのは消せない実現不能希望のみなので、HARD=0 と同様に短い猶予で頭打ち終了。
            //   hardFloor=0（実現不能希望なし）なら従来の「bestHard==0」と完全一致＝挙動不変。
            val effStall = if (bestHard.get() <= hardFloor) stallHardMs else stallMs
            when {
                now >= hardDeadlineMs || !isActive -> true
                now - startMs > minRunMs && now - lastImproveMs.get() > effStall -> { stagnationFired.set(true); true }
                else -> false
            }
        }

        val tFirst0 = System.currentTimeMillis()
        val first = V6NativeOptimizer.optimize(state, schedule, optsR, shouldStop, progressWatch)
        val tFirst1 = System.currentTimeMillis()
        // [review #5] RSIThenALNS は RSI(first)→ALNS(chained) を同一予算内で直列実行する。各段は
        //   postPolish=false（optsR で統一）なので段内 polish は走らない。最終 polish は段ではなく
        //   下流の runPostOptimization() に一度だけ集約しているため、ここでの二重 polish は意図的に無い。
        val chained = if (requestedAlgorithm == V6Algorithm.AUTO && plan is OptimizationPlan.RSIThenALNS && !shouldStop()) {
            V6NativeOptimizer.optimize(state, first.schedule, optsR.copy(algorithm = V6Algorithm.ALNS, totalBudgetSec = plan.alnsSec), shouldStop, progressWatch)
        } else first
        val tChain1 = System.currentTimeMillis()

        // [品質向上] エリート再結合(Path Relinking): 並列ポートフォリオの精鋭解と現行最良を再結合し、
        //   両者の中間にある良解を拾う。早期停止等で空いた予算の一部(≤15%・15〜60s)だけを使い、5分は超えない。
        //   現行最良起点なので退化しない（best-of-best）。
        val relinkBudgetMs = (budgetMs * 15 / 100).coerceIn(15_000L, 60_000L)
        val relinkDeadline = minOf(hardDeadlineMs, System.currentTimeMillis() + relinkBudgetMs)
        val relinkStop = { System.currentTimeMillis() >= relinkDeadline || !isActive }
        // [#3 並列の論理改善: 反復パスリンク] 精鋭解を一度再結合して終わりにせず、改善が出る限り
        //   「再結合結果を新たな起点」にして残り精鋭解と再結合し直す(島モデルの再結合を逐次で深掘り)。
        //   elitePathRelink は常に起点(best)から評価＝各回退化しない(best-of-best)。最大3巡＋relinkStopで停止。
        val alts = V6NativeOptimizer.lastAlternatives
        var relinkSched = chained.schedule
        var relinkRep = chained.report
        if (alts.isNotEmpty() && !relinkStop()) {
            var rl = 0
            while (rl < 3 && !relinkStop()) {
                val (s, r) = V6NativeOptimizer.elitePathRelink(state, relinkSched, alts, relinkStop)
                val improved = r.hard < relinkRep.hard ||
                    (r.hard == relinkRep.hard && r.total < relinkRep.total) ||
                    (r.hard == relinkRep.hard && r.total == relinkRep.total && r.weightedScore < relinkRep.weightedScore - 1e-6)
                relinkSched = s; relinkRep = r
                rl++
                if (!improved) break   // この巡で改善なし＝再結合の不動点に到達
            }
        }
        val relinkImproved = relinkRep.hard < chained.report.hard ||
            (relinkRep.hard == chained.report.hard && relinkRep.total < chained.report.total) ||
            (relinkRep.hard == chained.report.hard && relinkRep.total == chained.report.total && relinkRep.weightedScore < chained.report.weightedScore - 1e-6)
        val tRelink1 = System.currentTimeMillis()

        val post = V6HotfixPasses.runPostOptimization(
            state, relinkSched, label.tech,
            shouldStop = shouldStop,
            onPhase = { phase -> progressWatch(phase, null, System.currentTimeMillis() - startMs, budgetMs) },
            deadlineMs = hardDeadlineMs,   // [残予算ガード] HF66 が後段パスを押し出さないよう全体締切を渡す
        )
        val tPost1 = System.currentTimeMillis()
        val overBudget = tPost1 - startMs > budgetMs
        val timingLog = MirrorLog(
            level = if (overBudget) "W" else "I",
            tag = "TIME",
            message = "総${(tPost1 - startMs) / 1000.0}s (予算${seconds}s${if (overBudget) " 超過" else ""}): " +
                "探索${(tFirst1 - tFirst0) / 1000.0}s + 連鎖${(tChain1 - tFirst1) / 1000.0}s + 再結合${(tRelink1 - tChain1) / 1000.0}s + 後処理${(tPost1 - tRelink1) / 1000.0}s " +
                "/ workers設定${workers} 実効仮説${effHypotheses}",
        )
        val relinkLog = if (relinkImproved) listOf(MirrorLog(
            level = "I", tag = "PathRelink",
            message = "エリート再結合で改善: HARD ${chained.report.hard}→${relinkRep.hard} / total ${chained.report.total}→${relinkRep.total}（精鋭${alts.size}解と再結合）",
        )) else emptyList()
        val stagnationLog = if (stagnationFired.get()) listOf(MirrorLog(
            level = "I", tag = "EarlyStop",
            message = "停滞検知: 改善が無いため早期終了（予算${seconds}s中 ${(tPost1 - startMs) / 1000}sで停止・解は最良を維持）",
        )) else emptyList()
        // [最終番兵/多重防御] 全段 keep-best のため通常は発火しないが、万一パイプラインが入力より
        // 悪い結果を返した場合は入力を採用し退化を防ぐ（checkResultWorse をここで配線）。
        val regression = checkResultWorse(inputReport, post.report)
        val finalSched = if (regression != null) normInput else post.schedule
        val finalReport = if (regression != null) inputReport else post.report
        val sentinelLog = if (regression != null) listOf(MirrorLog(
            level = "W", tag = "Sentinel",
            message = "後処理結果が入力より悪化を検知したため入力を採用しました（多重防御）: $regression",
        )) else emptyList()
        // post.report.logs = [HF80/67/66/70 logs + POST timing + UnifiedViolationChecker logs]。
        // post.logs は post.report.logs の部分集合なので両方足すと重複する → post.report.logs のみ使う。
        val logs = listOf(timingLog) + sentinelLog + relinkLog + stagnationLog + gate.logs + first.phaseLogs + (if (chained !== first) chained.phaseLogs else emptyList()) + post.report.logs
        ActionResult(finalSched, finalReport.copy(logs = logs), "optimize:${label.tech}", busy, logs, post)
    }

    fun checkResultWorse(before: ViolationReport?, after: ViolationReport): String? {
        if (before == null) return null
        return when {
            after.hard > before.hard -> "HARDが悪化しました: ${before.hard} -> ${after.hard}"
            after.total > before.total && after.hard >= before.hard -> "違反総数が悪化しました: ${before.total} -> ${after.total}"
            after.weightedScore > before.weightedScore && after.total >= before.total -> "重み付きスコアが悪化しました: ${before.weightedScore.toLong()} -> ${after.weightedScore.toLong()}"
            else -> null
        }
    }

    fun buildBusyLogLine(detail: BusyDetail): String = buildString {
        append(detail.algorithm)
        if (detail.subtitle.isNotBlank()) append(" — ").append(detail.subtitle)
        append(" / ").append(detail.problemSize)
        append(" / ").append(detail.constraintCount)
    }
}
