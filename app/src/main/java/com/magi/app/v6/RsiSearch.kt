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
import com.magi.app.v6.V6NativeOptimizer.PolishResult
import com.magi.app.v6.V6NativeOptimizer.actualSeed
import com.magi.app.v6.V6NativeOptimizer.better
import com.magi.app.v6.V6NativeOptimizer.hf80PostPolish
import com.magi.app.v6.V6NativeOptimizer.nowMs
import com.magi.app.v6.AlnsSearch.runAlns
import com.magi.app.v6.AlnsSearch.runV5

/** RSI と RSI++（違反に集中する探索、全面改修 P10b で V6NativeOptimizer から分けた）。探索の意味論は移す前と同じ。 */
internal object RsiSearch {
    internal suspend fun runRsi(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
        sharedHf63: Hf63Infeasibility? = null,
    ): V6OptimizerResult {
        val started = nowMs()
        val rng = Random(actualSeed(options.seed) xor 0x451L)
        val p = cachedProblem(state)
        var best = normalizeSchedule(initial, p)
        var bestReport = UnifiedViolationChecker.check(state, best)
        var iters = 0L
        val rounds = max(2, min(8, budgetSec / 30 + 2))
        val per = max(1, budgetSec / rounds)
        val logs = ArrayList<MirrorLog>()
        // [HF63] ラウンド境界で改善ストリームを追跡し、構造的に充足困難な族を focus 対象から外す。
        // best-of-rounds のため、回避は「無駄なラウンドを達成可能な族へ振り向ける」だけで悪化は起きない。
        // [3.281.0/停滞レビューB] 適応ポートフォリオの短いエポック(rounds=2)では「focus を2ラウンド投入して
        //   threshold に達した瞬間に runRsi ごと破棄→次エポックで白紙から再学習」を数十回反復していた
        //   （実機ログ: W1x32+W2x35=67エポックが毎回 c3n を2ラウンドずつ攻めて学習を捨てる）。呼出元
        //   （ワーカー）が sharedHf63 を渡すとエポックを跨いで停滞学習が持続する。gBestCurV は全期間min の
        //   ため、エポック間の摂動で族の件数が一時的に増えても self-correction は誤発火しない（真の改善=
        //   全期間minの更新のみでリセット）＝クロスエポック共有は意味論的に健全。省略時は従来どおり新規。
        val hf63 = sharedHf63 ?: Hf63Infeasibility()
        // [3.288.0/ログ強化=回数軸] 戦略変更（focus遷移・E9冷却・HF63降格）を1行に集約するための足跡。
        //   スパム対応: HF63/ピボット行は「内容が変わったときだけ」出す（旧: avoid が立つと毎ラウンド同文を出力）。
        val focusTrail = ArrayList<String>()
        var e9Cooldowns = 0
        var lastLoggedAvoid: Set<String>? = null
        var lastLoggedPivot: String? = null
        // [HARD=0非到達への配慮 / 静的covU床] 構造的 covU 下限（有資格者を全員就けても埋まらない席=forcedCovU）は
        //   最適化中に不変。covU がこの床に達したら「これ以上 covU は下げられない」と静的に確定するので、HF63 の
        //   動的検知(約3ラウンド無改善を要する)を待たず round 0 から即 focus 除外し、RSI の残ラウンドを解ける族
        //   (他HARD/SOFT)へ回す。床=0（構造的不足なし＝HARD=0 到達可能な一般ケース）なら常に no-op＝挙動不変。
        //   focus 選択のみの変更でスコアリング不変（keep-best=better() が結果を担保）＝退化なし・3.74.0 と同方針。
        val covUFloor = try { V6SanityPort.structuralHardFloor(state) } catch (_: Exception) { 0 }
        var stagnantRounds = 0   // [N4] better() 無改善の連続ラウンド数
        // [E9/状況適応] 直前ラウンドが「完全空振り」(候補不採用＋focus族の件数も不変)だった focus を
        //   次の1ラウンドだけ回避する軽い冷却。同一 focus の同一仮説を3連発する空転(実機: c3n×3R=~63s 無変化、
        //   HF63 の恒久判定は約3R を要す)を、c3n→c1→c3n… の交互へ多様化する。1ラウンド限定なので
        //   乱数運の悪い1回で族を見捨てない(恒久除外は従来どおり HF63 のみ)。focus 選択のみ＝スコアリング不変。
        var cooldownFocus: String? = null
        // [レビュー#5 3.213.0] HF63 の停滞加算を「直前ラウンドで実際に focus した族」に限定する。
        //   旧: updateFromBreakdown が全族を無差別に停滞加算し、covU 張り付き中に一度も試していない
        //   c3n 等の HARD 族まで約3ラウンドで誤 deprioritize し得た（SOFT は 3.184.0 の avoid フィルタで
        //   緩和済みだが HARD は残っていた）。
        // [3.231.0/ドッグフーディングで発見・修正] 旧 effortIters=1800/round(固定) は
        //   INFEAS_STALL_ITERS=5000 到達に約3ラウンドの同族focusを要する。E9冷却(1ラウンド休止)が
        //   2〜3の詰んだ族を交互に切替えるため、実際にその族が3回目の focus を受けるのは
        //   （rounds=5の場合）round1,3,5＝最終ラウンドで、deprioritize が成立しても振り向け先の
        //   ラウンドが残っていなかった（実機ログでround1〜5が3族の堂々巡りのまま全く改善しない事例を
        //   確認）。effortIters を rounds に応じて動的に決め、詰んだ族の deprioritize が
        //   「残り最低2ラウンドを振り向けに残せる」タイミングで完了するようにする
        //   （reserveRounds=2・E9の1-in-2交互を想定しattemptsTarget=ceil((rounds-2)/2)を2で下駄履かせ、
        //   一度の不運な1ラウンドだけでは deprioritize しない=E9のより軽い1R冷却との役割分担を保つ）。
        //   rounds が大きいほど attemptsTarget も緩み、旧来同様じっくり粘れる。focus 選択のみの変更で
        //   スコアリング不変（keep-best=better()が結果を担保）。
        val effortIters = HypothesisPlanning.rsiHf63EffortIters(rounds)
        var lastFocus: String? = null
        for (round in 0 until rounds) {
            if (shouldStop()) break
            coroutineContext.ensureActive()
            // [監査修正] HF63 は Web の per-iter 前提(5000 iter 無改善で infeasible)。ラウンド粒度の呼出に
            //   effortIters/round を渡し、閾値5000到達を有限ラウンド分の focus 投入無改善に引き伸ばす
            //   （class は Web 忠実移植のまま・呼出側で粒度を補正）。iters 自体は本来用途に不変。
            hf63.updateFromBreakdownFocused(bestReport.breakdown, lastFocus, effortIters)
            // [12h見直し] 動的(HF63)と静的(covU床)の avoid を分離して保持する。N4 早期脱出(下記)の発火条件は
            //   HF63 の動的検知のみでゲートしないと、構造的covU>0 のデータでは静的除外が round 0 から avoid を
            //   非空にし、「旧N4の厳密な部分集合」保証(650-654行)を破って2停滞ラウンドで RSI が即終了してしまう。
            val dynamicAvoid = hf63.infeasibleBreakdownKeys()
            // [実機ログ起因/SOFT誤deprioritize] HF63 は breakdown 値が減らなければ族を stall 計上するため、
            //   「covU に focus が張り付いて一度も focus されず不減の SOFT 族」(実機: c1=87/low/high 等)まで
            //   infeasible 判定してしまう。これを focus の avoid に入れると本来直せる SOFT が永久に focus されない
            //   （pivot しても weekly/fair だけ残り destroyRepairStaff が cost 未対応で効かない）。focus の
            //   deprioritize は真に構造的な HARD（covU 床/c3n/pref/groupViol）のみに限定し、SOFT は常に focusable に
            //   保つ（SOFT の同一 focus 空転は cooldownFocus の1R休止＋keep-best＋有限ラウンドで自己収束）。
            //   N4 早期終了の武装判定（下記）は従来どおり dynamicAvoid（全族）で行い、pivot 可否は avoid(HARD) で判定する。
            // [静的covU床] covU が構造的下限（covUFloor）に達している間は解けないので focus から即除外する。
            //   合法配置では covU >= covUFloor（下限）。担当外配置(groupViol)が混在すると covU が床を下回り得るが、
            //   その間 covU を focus しても無意味（groupViol が hard-first で先に選ばれる）なので `<=` で除外が正しい。
            // [E9] 冷却は focus 選択にのみ合流（HF63 ログ・N4 発火条件には混ぜない＝恒久判定と区別）。
            val (avoid, focusAvoid) = RsiFocusSelection.avoidSets(dynamicAvoid, bestReport.breakdown["covU"] ?: 0, covUFloor, cooldownFocus)
            // [測定中/backlog#28] 既定OFF時はround（従来どおりrunRsi呼出し単位でリセット）。ONならhf63が
            //   持続するカウンタを使い、apt/covOの周期枠(%3)をエポック跨ぎで維持する。1ラウンドにつき1回だけ
            //   進め、下段の早期終了判定(pivot)にも同じ値を使う（そちらで再度進めない）。
            val focus = RsiFocusSelection.maxViolatedFamily(bestReport, focusAvoid, round, rounds)
            if (avoid.isNotEmpty() && avoid != lastLoggedAvoid) {
                // [3.288.0/スパム対応] 集合が変化したラウンドのみログ（旧: 毎ラウンド同文）。
                logs.add(MirrorLog(iter = iters, tag = "HF63", message = "deprioritize ${avoid.joinToString(",")} → focus=$focus (round ${round + 1})"))
                lastLoggedAvoid = avoid.toSet()
                focusTrail.add("[HF63降格:${avoid.joinToString("+")}]")
            }
            if (cooldownFocus != null) {
                logs.add(MirrorLog(iter = iters, tag = "RSIFocus", message = "直前ラウンド空振りのため ${cooldownFocus} を1ラウンド休止 → focus=$focus (round ${round + 1})"))
                e9Cooldowns++
            }
            focusTrail.add(focus)
            val focusedBefore = bestReport.breakdown[focus] ?: 0
            lastFocus = focus   // [レビュー#5] 次ラウンド頭の HF63 更新へ「このラウンドの投入先」を渡す
            val hypothesis = RsiHypothesisOperators.rsiGenerateHypothesis(state, best, bestReport, focus, rng, shouldStop, wideC3n = options.config.wideC3nBreakDays)
            val phase = if (round % 2 == 0) runAlns(state, hypothesis, options.copy(restarts = 1), per, shouldStop, onProgress) else runV5(state, hypothesis, options, per, shouldStop, onProgress)
            iters += phase.iterations
            var candSched = phase.schedule
            var candReport = phase.report
            // [HF361/528/541移植] EarlyChain: Web 内部V5の停滞(reheat)フック(L11705-)に対応する RSI ラウンド境界で発火
            //   Chain3/4 は常時、Rect/BlkN は optFlags.rectSwap(既定ON)に従う — Web 呼出順 e3/e4/e5/e6 と同一。
            run {
                val lr = V6LateOperators.improve(state, candSched, candReport, rng, started + budgetSec * 1000L, rectEnabled = options.rectSwap, shouldStop = shouldStop)
                if (lr.chain3 + lr.chain4 + lr.rect + lr.blkN > 0 && p.keepsExtBan(candSched, lr.schedule)) {
                    candSched = lr.schedule
                    candReport = lr.report
                    logs.add(MirrorLog(iter = iters, tag = "EarlyChain", message = "早期循環フック改善 (Chain3=${lr.chain3} Chain4=${lr.chain4} Rect=${lr.rect} BlkN=${lr.blkN}) round=${round + 1} HARD=${candReport.hard} total=${candReport.total}"))
                    logs.addAll(lr.logs)
                }
            }
            // 拡張希望の禁止へ置いたラウンド結果は採らない
            if (better(candReport, bestReport) && p.keepsExtBan(best, candSched)) {
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
            // [3.288.0/スパム対応] ラウンド行は「改善したラウンド」と「最終ラウンド」だけに絞る。
            //   適応ポートフォリオは 1エポック=2ラウンド×数十エポック×ワーカー数のため、旧・全ラウンド出力では
            //   この1種類だけで診断ログの大半を占め、重要イベント（HF63降格・早期終了・壁判定）を押し出していた。
            //   焦点の履歴は末尾の「戦略変更」1行（focus遷移を連続圧縮）が全ラウンド分を保持する＝情報は失わない。
            if (stagnantRounds == 0 || round == rounds - 1) {
                logs.add(MirrorLog(iter = iters, tag = "RunMAGI_RSI", message = "round=${round + 1}/$rounds focus=$focus best HARD=${bestReport.hard} total=${bestReport.total}" + if (stagnantRounds > 0) "（無改善${stagnantRounds}R）" else "（改善）"))
            }
            if (ownsStatics(runSlot())) publishLiveBest(bestReport, best)   // [DefragLiveView] 計算中ライブ盤面
            onProgress("RSI $focus", bestReport, iters, nowMs() - started)
            // [N4改] focus枯渇の早期終了。旧版は「2R無改善」だけで打ち切っていたが、これは達成可能族が
            //   残る場合でもランダム探索(destroy-repair)を早期に切り、乱数運の悪い2Rで本来伸びる盤面を捨てうる
            //   （proxyでA/B不能な領域＝安全側に倒す）。発火条件を hf63 が infeasible 族を検出済み(avoid非空)＝
            //   「達成可能な focus を撃ち尽くした」ときに限定する。これは旧条件の厳密な部分集合のため、
            //   旧N4より早く止まることはない＝品質は退化しない。avoid が空(まだ狙える族がある)の間は全予算で探索。
            //   ※後段(hf80)は固定予算のため厳密な「予算移譲」ではなく、無改善ラウンドの空転停止(電池/熱/時間節約)。
            // [12h見直し] 発火は動的検知(dynamicAvoid=HF63)のみでゲートする。静的covU床(合流後のavoid)を使うと
            //   構造的covU>0 のデータで round 0 から常時武装し、旧N4保証(上記)を破る。
            // [ユーザー指示/HARD残でもSOFT focus] 停滞した HARD(covU等)を deprioritize してもなお狙える族
            //   (low/high/c2・c41/c41s/c42/c42s・covO・weekly・fair・c1・c3系・apt)が残るなら、早期終了せず
            //   残ラウンドを SOFT 最適化に振り向ける（focus は L741 で focusAvoid により既に SOFT へピボット済）。
            //   keep-best(better() は hard 非悪化を要求)が HARD 悪化を防ぐ＝HARD残のまま SOFT を最適化しても安全。
            //   本当に狙える族が尽きた(pivot=="total" or 件数0)ときだけ従来どおり空転停止する。stuck な SOFT も
            //   HF63 が順次 dynamicAvoid へ入れて focusable から外すため、いずれ pivot 枯渇→終了で自己収束する。
            if (stagnantRounds >= 2 && dynamicAvoid.isNotEmpty()) {
                val pivot = RsiFocusSelection.maxViolatedFamily(bestReport, avoid, round, rounds)   // avoid=dynamicAvoid＋静的covU床
                if (pivot == "total" || (bestReport.breakdown[pivot] ?: 0) == 0) {
                    logs.add(MirrorLog(iter = iters, tag = "RunMAGI_RSI", message = "早期終了: 狙える族が枯渇(deprioritize=${avoid.size}族)＋${stagnantRounds}R無改善（残${rounds - round - 1}Rの空転を停止）"))
                    break
                }
                if (pivot != lastLoggedPivot) {
                    // [3.288.0/スパム対応] pivot が変わったときだけログ（旧: 停滞が続く限り毎ラウンド同文）。
                    logs.add(MirrorLog(iter = iters, tag = "RunMAGI_RSI", message = "HARD残(${dynamicAvoid.joinToString(",")})を回避しSOFTへピボット継続 → 次focus候補=$pivot（HARD非悪化はkeep-bestが担保）"))
                    lastLoggedPivot = pivot
                }
            }
        }
        // [3.592.0] ループ内は毎ラウンド頭で「前ラウンドの focus」を記録するため、最終ラウンド分の
        //   投入量だけ記録されず取りこぼしていた（次回runRsi呼出時にlastFocusがリセットされ消える）。
        hf63.updateFromBreakdownFocused(bestReport.breakdown, lastFocus, effortIters)
        // [3.288.0/ログ強化=回数軸] 戦略変更の1行サマリ（focus遷移を連続圧縮）。2手以上あるときだけ出す＝スパムなし。
        if (focusTrail.count { !it.startsWith("[") } >= 2) {
            logs.add(MirrorLog(iter = iters, tag = "戦略変更", message =
                "RSI focus遷移: ${RoleDiversityHelpers.compressFocusTrail(focusTrail)}" +
                    (if (e9Cooldowns > 0) " / E9冷却${e9Cooldowns}回" else "") +
                    (hf63.infeasibleFamilies().takeIf { it.isNotEmpty() }?.let { " / HF63降格={${it.joinToString(",")}}" } ?: "")))
        }
        // [3.288.0/ログ強化=状態軸] このRSI実行でHF63が「構造的に充足困難」と学習した族を実行横断で集約
        //   （エピローグの残存分析行が読む。ワーカー並行呼出があるため synchronized 集約）。
        recordInfeasibleScoped(hf63.infeasibleBreakdownKeys())
        return V6OptimizerResult(best, bestReport.copy(logs = logs + bestReport.logs), V6Algorithm.RSI, logs, iters, nowMs() - started)
    }

    internal suspend fun runRsiPlus(
        state: MagiState,
        initial: Array<IntArray>,
        options: V6OptimizerOptions,
        budgetSec: Int,
        shouldStop: () -> Boolean = { false },
        onProgress: (String, ViolationReport?, Long, Long) -> Unit,
        sharedHf63: Hf63Infeasibility? = null,   // [3.281.0/B] Phase2 RSI へ透過（エポック跨ぎのHF63学習持続）
        workerLabel: String = "",
    ): V6OptimizerResult {
        val started = nowMs()
        val logs = ArrayList<MirrorLog>()
        val who = if (workerLabel.isEmpty()) "" else "[$workerLabel] "
        fun scoreOf(r: ViolationReport) = "HARD=${r.hard} total=${r.total} weighted=${"%.1f".format(r.weightedScore)}"
        // [3.600.0] 入口で既に停止済みなら位相下限ぶんの無駄走りをせず入力をそのまま返す（keep-best不変）。
        if (shouldStop()) {
            val rep = UnifiedViolationChecker.check(state, initial)
            return V6OptimizerResult(initial, rep, V6Algorithm.RSI_PLUS, logs, 0L, 0L)
        }
        val seedSec = max(10, (budgetSec * 0.20).toInt())
        val rsiSec = max(10, (budgetSec * 0.35).toInt())
        val alnsSec = max(10, (budgetSec * 0.30).toInt())
        val polishSec = max(5, budgetSec - seedSec - rsiSec - alnsSec)
        // [2026-09-22/backlog#34] 各フェーズの実測msを予算msと並べてログへ出す。実機で観測された
        //   量子比最大約181倍（W5:MAX_DISTANCE_RSI_PLUS q=45s→実8166s）がSeed/Hypothesis/Refine/Polishの
        //   どこで生じたかは従来ログ（HARD/totalのみ）では特定できなかった。次の実機ログでの切り分け用。
        val seedT0 = nowMs()
        val seed = runV5(state, initial, options, seedSec, shouldStop, onProgress)
        logs.add(MirrorLog(tag = "RSIPlus", message = "${who}Phase1 Seed: ${scoreOf(seed.report)} 実測${nowMs() - seedT0}ms(予算${seedSec}000ms)"))
        val rsiT0 = nowMs()
        val rsiSkipped = shouldStop()
        val rsi = if (rsiSkipped) seed else runRsi(state, seed.schedule, options, rsiSec, shouldStop, onProgress, sharedHf63)
        // 拡張希望の禁止へ置いた段の結果は採らない（前段を維持）
        val p = cachedProblem(state)
        val base = if (better(rsi.report, seed.report) && p.keepsExtBan(seed.schedule, rsi.schedule)) rsi else seed
        logs.add(MirrorLog(tag = "RSIPlus", message = "${who}Phase2 Hypothesis${if (rsiSkipped) "(スキップ)" else ""}: ${scoreOf(base.report)} 実測${nowMs() - rsiT0}ms(予算${rsiSec}000ms)"))
        val alnsT0 = nowMs()
        val refineSkipped = shouldStop()
        val refine = if (refineSkipped) base else runAlns(state, base.schedule, options.copy(restarts = max(1, options.restarts)), alnsSec, shouldStop, onProgress)
        val best = if (better(refine.report, base.report) && p.keepsExtBan(base.schedule, refine.schedule)) refine else base
        logs.add(MirrorLog(tag = "RSIPlus", message = "${who}Phase3 Refine${if (refineSkipped) "(スキップ)" else ""}: ${scoreOf(refine.report)} 実測${nowMs() - alnsT0}ms(予算${alnsSec}000ms)"))
        var bestSched = best.schedule
        // [HF361/528/541移植] EarlyChain: Refine 確定後の停滞境界で Chain3/4(常時)+Rect/BlkN(rectSwap)を発火
        run {
            val lr = V6LateOperators.improve(state, bestSched, best.report, Random(actualSeed(options.seed) xor 0x528L), started + budgetSec * 1000L, rectEnabled = options.rectSwap, shouldStop = shouldStop)
            val fired = lr.chain3 + lr.chain4 + lr.rect + lr.blkN > 0
            // [監査#1] Chain3/4の受理(gateW)はweighted単層でHARD増を相殺受理し得るため、採用は
            //   runRsiと同じ better(hard→weighted→total) でゲートする（素通しでHARD悪化を最終出力しない）。
            // ※ run{} 末尾のため if を式位置にしない（else-if 連鎖は式扱いとなり全分岐必須。ネストifの文形式で書く）。
            if (fired) {
                if (better(lr.report, best.report) && p.keepsExtBan(bestSched, lr.schedule)) {
                    bestSched = lr.schedule
                    logs.add(MirrorLog(tag = "EarlyChain", message = "早期循環フック改善 (Chain3=${lr.chain3} Chain4=${lr.chain4} Rect=${lr.rect} BlkN=${lr.blkN}) HARD=${lr.report.hard} total=${lr.report.total}"))
                    logs.addAll(lr.logs)
                } else {
                    logs.add(MirrorLog(tag = "EarlyChain", message = "採用見送り（hard/total非改善ガード） HARD=${lr.report.hard} total=${lr.report.total}"))
                }
            }
        }
        val polishT0 = nowMs()
        val polishSkipped = shouldStop()
        val polish = if (polishSkipped) {
            PolishResult(bestSched, emptyList(), 0L, UnifiedViolationChecker.check(state, bestSched))
        } else {
            hf80PostPolish(state, bestSched, polishSec, actualSeed(options.seed) xor 0x555L, shouldStop)
        }
        val report = polish.report
        logs.add(MirrorLog(tag = "RSIPlus", message = "${who}Phase4 Polish${if (polishSkipped) "(スキップ)" else ""}: ${scoreOf(report)} 実測${nowMs() - polishT0}ms(予算${polishSec}000ms) 全体実測${nowMs() - started}ms(予算${budgetSec}000ms)"))
        return V6OptimizerResult(
            polish.schedule,
            report.copy(logs = logs + seed.phaseLogs + rsi.phaseLogs + refine.phaseLogs + polish.logs + report.logs),
            V6Algorithm.RSI_PLUS,
            logs + seed.phaseLogs + rsi.phaseLogs + refine.phaseLogs + polish.logs,
            seed.iterations + rsi.iterations + refine.iterations + polish.iterations,
            nowMs() - started,
        )
    }
}
