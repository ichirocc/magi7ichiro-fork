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
import com.magi.app.v6.V6NativeOptimizer.better
import com.magi.app.v6.V6NativeOptimizer.extBanInDiff
import com.magi.app.v6.V6NativeOptimizer.nowMs

/**
 * 探索直後の研磨（全面改修 P10c）: 探索器が返した盤面を、差分評価を生スコア源にして局所研磨する（hard を増やさない SA、最後に入力と番兵比較）。
 * 後処理チェーン（`V6HotfixPasses.runPostOptimization`＝複数段の研磨を予算で回す枠組み）とは別物で、探索器の一部として各探索の最後と
 * 「自動で整える」（`V6NativeOptimizer.softPolishOnly`）から呼ぶ。
 */
internal object SearchPolish {
    internal data class PolishResult(val schedule: Array<IntArray>, val logs: List<MirrorLog>, val iterations: Long, val report: ViolationReport)

    /**
     * 最終研磨フェーズ。[差分化移植] DeltaEvaluator を生スコア源にして直接評価で回す
     * （copy2D / 全件 check() を毎反復行わない）。op0-2 は単一/二セル直接評価、op3-8 は
     * findTargetedFix（シャッフル付きフォールバック）、op9-10 は copy 系の destroy/repair を
     * 変更セルだけ eval へ反映する。受理は hard 非悪化(best基準)＋ SA。
     * 不変条件: eval.at(i,j) == cur[i][j]。生スコアと weightedScore は目的が異なるため、
     * 入力(best)を baseline として保持し最後に番兵比較して退化を防ぐ。
     */
    internal suspend fun hf80PostPolish(
        state: MagiState, initial: Array<IntArray>, seconds: Int, seed: Long,
        shouldStop: () -> Boolean = { false },
        /** 呼出側が `initial` の評価を既に持つならそれ。 */
        initialReport: ViolationReport? = null,
    ): PolishResult {
        val started = nowMs()
        val rng = Random(seed)
        val p = cachedProblem(state)
        var best = initial.copy2D()
        var bestReport = initialReport ?: UnifiedViolationChecker.check(state, best)
        val baseSched = best          // 入力スナップショット（best は改善時に別配列へ差し替わる）
        val baseReport = bestReport
        var iters = 0L
        val deadline = started + seconds * 1000L
        // [E10/停滞早期終了] 実機ログで PostPolish が 45s枠を最後まで走り切って改善0（40.977s/40.988s の2例）
        //   ＝重研磨済み盤面ではプラトー後の期待値が低い。best が枠の1/5(下限3s)無改善なら早期に返す。
        //   keep-best＋末尾の入力比番兵(better(baseReport,bestReport)→入力復帰)のためそれまでの最良を保持する＝時間/電池を節約
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
            return PolishResult(best, logs, iters, bestReport)
        }
        var cur = best.copy2D()
        val eval = DeltaEvaluator(p)
        eval.reset(cur)
        var curScore = eval.score()
        var bestScore = curScore
        val diffBuf = IntArray(p.S * p.T)
        // [C③修正] ここへ来るのは native 経路が番兵発火で未完了(nat.completed==false)に戻った異常系のみ。
        //   native 区間で一度も改善しなかった(nat.best==null)なら、その無改善経過を停滞時計へ引き継ぐため
        //   起点を started にする（旧: 常に nowMs() で再スタートし、native の無改善時間が停滞判定から抜け落ち、
        //   さらに約 stallMs ぶん余計に回っていた）。改善済みは最終改善時刻不明のため保守的に nowMs()。
        var lastImproveMs = if (nat.best == null) started else nowMs()
        var lastBestMark = bestScore
        var stallDurationMs = -1L   // [停滞時間のログ出力] 発火の瞬間の無改善経過(ms)
        while (!shouldStop()) {
            val nowLoop = nowMs()
            if (nowLoop >= deadline) break
            if (bestScore < lastBestMark) { lastBestMark = bestScore; lastImproveMs = nowLoop }
            else if (nowLoop - lastImproveMs >= stallMs) { stalled = true; stallDurationMs = nowLoop - lastImproveMs; break }
            coroutineContext.ensureActive()
            val curHard = curScore / SCORE_HARD_UNIT
            val bestHard = bestScore / SCORE_HARD_UNIT
            when (rng.nextInt(11)) {
                0 -> {   // random allowed single cell (direct-eval)
                    if (p.S > 0 && p.T > 0) {
                        val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
                        if (!p.wishLocked(i, j)) {
                            val allowed = p.allowedShiftsForStaff(i)
                            if (allowed.isNotEmpty()) {
                                val oldK = eval.at(i, j); val nw = allowed[rng.nextInt(allowed.size)]
                                if (nw != oldK && !p.extBanned(i, j, nw)) {
                                    eval.apply(i, j, nw)
                                    val ns = eval.score()
                                    if (ns / SCORE_HARD_UNIT <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
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
                            if (ka != kb && !p.extBanned(i, ja, kb) && !p.extBanned(i, jb, ka)) {
                                eval.apply(i, ja, kb); eval.apply(i, jb, ka)
                                val ns = eval.score()
                                if (ns / SCORE_HARD_UNIT <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
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
                            if (k1 != k2 && p.mayPlace(i1, k2) && p.mayPlace(i2, k1) && !p.extBanned(i1, j, k2) && !p.extBanned(i2, j, k1)) {
                                eval.apply(i1, j, k2); eval.apply(i2, j, k1)
                                val ns = eval.score()
                                if (ns / SCORE_HARD_UNIT <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
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
                        if (fix[2] != oldK && !p.extBanned(fix[0], fix[1], fix[2])) {
                            eval.apply(fix[0], fix[1], fix[2])
                            val ns = eval.score()
                            if (ns / SCORE_HARD_UNIT <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
                                cur[fix[0]][fix[1]] = fix[2]; curScore = ns
                                if (betterScore(ns, bestScore)) { best = cur.copy2D(); bestScore = ns; bestReport = UnifiedViolationChecker.check(state, cur) }
                            } else eval.apply(fix[0], fix[1], oldK)
                        }
                    }
                }
                else -> {   // copy-based multi-cell destroy/repair (ops 9,10)
                    val cand = cur.copy2D()
                    val drDay2 = if (rng.nextBoolean()) { DestroyRepairOperators.destroyRepairViolations(state, cand, bestReport, rng); -1 }
                                 else { val j = if (p.T > 0) rng.nextInt(p.T) else -1; if (j >= 0) DestroyRepairOperators.destroyRepairDayAt(state, cand, j, rng); j }
                    // hard-feasible のときは hf67 を省略（DeltaEvaluator が hard 退化を弾く）。
                    val fixed = if (curHard > 0L) HardRepairCore.hf67HardRepair(state, cand, rng).schedule else cand
                    val nDiffs = if (drDay2 >= 0 && fixed === cand) {
                        var n = 0
                        for (i in 0 until p.S) if (cur[i][drDay2] != fixed[i][drDay2]) diffBuf[n++] = i * p.T + drDay2
                        n
                    } else diffInto(p.T, cur, fixed, diffBuf)
                    // 拡張希望の禁止へ置いた候補は評価せずに捨てる（eval へは何も反映しない）
                    val extBad = p.hasExtBan && extBanInDiff(p, fixed, diffBuf, nDiffs)
                    val nApply = if (extBad) 0 else nDiffs
                    for (idx in 0 until nApply) {
                        val flat = diffBuf[idx]; eval.apply(flat / p.T, flat % p.T, fixed[flat / p.T][flat % p.T])
                    }
                    val ns = eval.score()
                    if (!extBad && ns / SCORE_HARD_UNIT <= bestHard && (betterScore(ns, curScore) || acceptWorseScore(ns, curScore, 0.15, rng))) {
                        cur = fixed; curScore = ns
                        if (betterScore(ns, bestScore)) { best = fixed.copy2D(); bestScore = ns; bestReport = UnifiedViolationChecker.check(state, fixed) }
                    } else {
                        for (idx in 0 until nApply) {
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
        val logs = listOf(MirrorLog(iter = iters, tag = "HF80", message = "PostPolish ${nowMs() - started}ms HARD=${bestReport.hard} total=${bestReport.total}" +
            if (stalled) "（停滞早期終了 枠${seconds}s・停滞${stallDurationMs}ms無改善）" else ""))
        return PolishResult(best, logs, iters, bestReport)
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
                    if (!NativeGate.chunkConsistent(ret, 5)) {
                        NativeGate.disable("Polishチャンク整合性NG(status=${ret.getOrNull(0)})")
                        return NativePolishRun(false, best, iters, false)
                    }
                    iters += ret[4]
                    if (ret[3] == 1L && ret[2] < verifiedBest) {
                        // [2層目番兵] best 更新チャンクの盤面を Kotlin フル評価で照合（Long== 許容誤差なし）。
                        //   [照合トグル] OFF=純ネイティブ（照合せず信頼）。C++自己整合(status)は上で常時検査済。
                        NativeBridge.nativePolishRead(h, 0, buf)
                        val sol = NativeEval.unflatten(buf, p.S, p.T)
                        // 拡張希望の禁止へ置いた盤面は採らない（照合済み best から Kotlin ループで続ける）
                        if (!p.keepsExtBan(best ?: initial, sol)) return NativePolishRun(false, best, iters, false)
                        NativeGate.parityMismatch(ret[2]) { fullEvaluator.fullEval(sol) }?.let { k ->
                            NativeGate.disable("Polish Kotlin照合NG(native=${ret[2]} kotlin=$k)")
                            return NativePolishRun(false, best, iters, false)
                        }
                        best = sol
                        verifiedBest = ret[2]
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
}
