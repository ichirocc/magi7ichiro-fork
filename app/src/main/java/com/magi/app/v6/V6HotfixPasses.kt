package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Native replacements for the Web-only post-optimization hotfix modules.
 *
 * The Web V6 calls HF80 -> HF67 -> HF66 -> HF70 after each optimizer run from
 * inside App.handleOptimize().  Android does not have window.HFxx modules, so the
 * passes live here as pure Kotlin and can be called from ViewModel/tests.
 */
data class HF80Result(
    val newSchedule: Array<IntArray>,
    val beforeHard: Int,
    val afterHard: Int,
    val beforeScore: Double,
    val afterScore: Double,
    val cycles: Int,
    val applied: Boolean,
    val reason: String,
    val logs: List<MirrorLog>,
)

data class HF67Result(
    val newSchedule: Array<IntArray>,
    val beforeTotal: Int,
    val afterTotal: Int,
    val swapsApplied: Int,
    val shortageSwaps: Int,
    val capacitySwaps: Int,
    val swapsRollback: Int,
    val logs: List<MirrorLog>,
)

data class HF66Result(
    val newSchedule: Array<IntArray>,
    val beforeTotal: Int,
    val afterTotal: Int,
    val movesApplied: Int,
    val shortageMoves: Int,
    val capacityMoves: Int,
    val movesRollback: Int,
    val logs: List<MirrorLog>,
)

data class HF70Result(
    val anomalies: Int,
    val message: String,
    val advice: String,
    val logs: List<MirrorLog>,
)

data class V6PostOptimizationResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val hf80: HF80Result,
    val hf67: HF67Result,
    val hf66: HF66Result,
    val hf70: HF70Result,
    val logs: List<MirrorLog>,
)

object V6HotfixPasses {
    /**
     * [review: budget] 後処理チェーン HF80 -> HF67 -> HF66 -> HF70。
     * @param shouldStop true を返した時点で各パスの反復を打ち切る。全体予算(deadline)超過と
     *        coroutine キャンセルの両方を呼び出し側でこのラムダに束ねる。HF80/67/66 は
     *        deadline で短縮/打ち切り、HF70(異常検知=安価)は診断のため常に実行する。
     * @param onPhase 各パス開始時に呼ばれ、UI 進捗を後処理中も更新できる(ハング誤認の防止)。
     */
    fun runPostOptimization(
        state: MagiState,
        schedule: Array<IntArray>,
        algoName: String,
        seed: Long = System.nanoTime(),
        shouldStop: () -> Boolean = { false },
        onPhase: (String) -> Unit = {},
    ): V6PostOptimizationResult {
        var work = schedule.copy2D()
        val logs = ArrayList<MirrorLog>()
        val t0 = System.currentTimeMillis()

        onPhase("後処理 HF80 戦略的振動")
        val t80 = System.currentTimeMillis()
        val r80 = applyHF80StrategicOscillation(state, work, maxCycles = 3, seed = seed xor 0x80L, shouldStop = shouldStop)
        work = r80.newSchedule.copy2D()
        logs.addAll(r80.logs)

        onPhase("後処理 HF67 職員間スワップ")
        val t67 = System.currentTimeMillis()
        val r67 = applyHF67InterStaffSwap(state, work, maxSwaps = 30, shouldStop = shouldStop)
        work = r67.newSchedule.copy2D()
        logs.addAll(r67.logs)

        onPhase("後処理 HF66 職員内再配分")
        val t66 = System.currentTimeMillis()
        val r66 = applyHF66IntraStaffRedistribution(state, work, maxMoves = 30, shouldStop = shouldStop)
        work = r66.newSchedule.copy2D()
        logs.addAll(r66.logs)

        onPhase("後処理 厳密日割当")
        val rAsg = applyDayAssignmentPolish(state, work, shouldStop = shouldStop)
        work = rAsg.newSchedule.copy2D()
        logs.addAll(rAsg.logs)

        // [研磨可否の検証] ソフト研磨クラスタ(循環 / c1 / c1回転 / c3 / c3回転)の前後を測る基準。
        val preSoftRep = UnifiedViolationChecker.check(state, work)

        // [パス間フィックスポイント再ループ] 各パスは内部で自己収束するが、別パスの変更が他パスの改善を
        //   再び開く（例: c3の組替えで新たなc1充足余地が出る）。クラスタ全体を「1巡で1手も採用されなく
        //   なるまで」最大 maxRounds 巡だけ繰り返す。全パスkeep-best＝退化なし。shouldStop と maxRounds で
        //   上限。違反セル指向なので空巡は即終了（コスト0）。
        val c3Anchor = setOf("vio-c3", "vio-c3m", "vio-c3mn")
        val c1Anchor = setOf("vio-c1")
        val maxRounds = 4
        var round = 0
        var totalCyc = 0; var totalC1 = 0; var totalC1r = 0; var totalC3 = 0; var totalC3r = 0
        while (round < maxRounds && !shouldStop()) {
            var roundApplied = 0

            onPhase("後処理 循環交換(k=2,3) [巡${round + 1}]")
            val rCyc = applyCyclicSwapPolish(state, work, maxPasses = 4, shouldStop = shouldStop)
            work = rCyc.newSchedule.copy2D(); totalCyc += rCyc.applied; roundApplied += rCyc.applied
            if (round == 0) logs.addAll(rCyc.logs)

            onPhase("後処理 期間要件(c1)研磨 [巡${round + 1}]")
            val rC1 = applyC1WindowPolish(state, work, maxPasses = 3, shouldStop = shouldStop)
            work = rC1.newSchedule.copy2D(); totalC1 += rC1.applied; roundApplied += rC1.applied
            if (round == 0) logs.addAll(rC1.logs)

            onPhase("後処理 期間要件(c1)3者回転研磨 [巡${round + 1}]")
            val rC1r = applyBlockRotationPolish(state, work, c1Anchor, "C1Rotate", maxPasses = 2, shouldStop = shouldStop)
            work = rC1r.newSchedule.copy2D(); totalC1r += rC1r.applied; roundApplied += rC1r.applied
            if (round == 0) logs.addAll(rC1r.logs)

            onPhase("後処理 連続規則(c3系)研磨 [巡${round + 1}]")
            val rC3 = applyC3SequencePolish(state, work, maxPasses = 3, shouldStop = shouldStop)
            work = rC3.newSchedule.copy2D(); totalC3 += rC3.applied; roundApplied += rC3.applied
            if (round == 0) logs.addAll(rC3.logs)

            onPhase("後処理 連続規則(c3系)3者回転研磨 [巡${round + 1}]")
            val rC3r = applyBlockRotationPolish(state, work, c3Anchor, "C3Rotate", maxPasses = 2, shouldStop = shouldStop)
            work = rC3r.newSchedule.copy2D(); totalC3r += rC3r.applied; roundApplied += rC3r.applied
            if (round == 0) logs.addAll(rC3r.logs)

            round++
            if (roundApplied == 0) break   // この巡で1手も採用なし＝joint局所最適に到達
        }

        // [研磨可否の検証ログ] ソフトc3系3種(c3/c3m/c3mn)とc1の増減・採用数・HARD不変・巡回数を集約。
        // 採用0かつ対象>0なら「頭打ち(改善手なし=正常)」、対象0なら「対象なし」と明示。
        run {
            val softAfter = UnifiedViolationChecker.check(state, work)
            fun bd(r: ViolationReport, k: String) = r.breakdown[k] ?: 0
            val adopted = totalCyc + totalC1 + totalC1r + totalC3 + totalC3r
            val targets = bd(preSoftRep, "c1") + bd(preSoftRep, "c3") + bd(preSoftRep, "c3m") + bd(preSoftRep, "c3mn")
            val verdict = when {
                adopted > 0 -> "有効(採用${adopted}手)"
                targets == 0 -> "対象なし"
                else -> "頭打ち(採用0=改善手なし・正常)"
            }
            val hardNote = if (softAfter.hard == preSoftRep.hard) "不変" else "変化${preSoftRep.hard}->${softAfter.hard}!"
            logs.add(MirrorLog(tag = "SoftPolishVerify", message =
                "ソフトc1/c3系研磨 可否=$verdict (${round}巡) | c1 ${bd(preSoftRep, "c1")}->${bd(softAfter, "c1")}" +
                    " / c3 ${bd(preSoftRep, "c3")}->${bd(softAfter, "c3")}" +
                    " / c3m ${bd(preSoftRep, "c3m")}->${bd(softAfter, "c3m")}" +
                    " / c3mn ${bd(preSoftRep, "c3mn")}->${bd(softAfter, "c3mn")}" +
                    " | HARD $hardNote / total ${preSoftRep.total}->${softAfter.total}" +
                    " (採用内訳 循環:${totalCyc} c1:${totalC1} c1回転:${totalC1r} c3:${totalC3} c3回転:${totalC3r})"))
        }

        onPhase("後処理 グループ内シフト回数の平準化")
        val rGeq = applyGroupShiftEqualizePolish(state, work, maxPasses = 2, shouldStop = shouldStop)
        work = rGeq.newSchedule.copy2D()
        logs.addAll(rGeq.logs)

        onPhase("後処理 7日周期(曜日)の平準化")
        val rWeq = applyWeeklyEqualizePolish(state, work, maxPasses = 2, shouldStop = shouldStop)
        work = rWeq.newSchedule.copy2D()
        logs.addAll(rWeq.logs)

        val tHf = System.currentTimeMillis()
        if (shouldStop()) {
            logs.add(MirrorLog(level = "W", tag = "POST", message = "予算超過のため後処理を短縮しました(残りパスは打ち切り)"))
        }

        onPhase("後処理 HF70 異常検知")
        val report = UnifiedViolationChecker.check(state, work)
        val r70 = detectHF70Anomalies(state, work, algoName, report)
        logs.addAll(r70.logs)

        val tEnd = System.currentTimeMillis()
        logs.add(MirrorLog(level = "I", tag = "POST",
            message = "後処理タイミング 総${tEnd - t0}ms: HF80=${t67 - t80}ms HF67=${t66 - t67}ms HF66=${tHf - t66}ms"))

        val allLogs = ArrayList<MirrorLog>()
        allLogs.addAll(logs)
        allLogs.addAll(report.logs)
        return V6PostOptimizationResult(work, report.copy(logs = allLogs), r80, r67, r66, r70, logs)
    }

    fun applyHF80StrategicOscillation(
        state: MagiState,
        schedule: Array<IntArray>,
        maxCycles: Int = 3,
        seed: Long = System.nanoTime(),
        shouldStop: () -> Boolean = { false },
    ): HF80Result {
        val p = Problem(state)
        val rng = Random(seed)
        val before = UnifiedViolationChecker.check(state, schedule)
        var best = normalizeSchedule(schedule, p)
        var bestReport = before
        var applied = false
        var usedCycles = 0
        val cycleMax = max(0, maxCycles)
        var cycle = 0
        while (cycle < cycleMax) {
            if (shouldStop()) break
            val cand = best.copy2D()
            val strength = max(1, (p.S * p.T * (0.03 + cycle * 0.02)).toInt())
            var t = 0
            while (t < strength) {
                if (p.S > 0 && p.T > 0) {
                    val i = rng.nextInt(p.S)
                    val j = rng.nextInt(p.T)
                    if (p.wish[i][j] < 0) {
                        val allowed = p.allowedShiftsForStaff(i)
                        if (allowed.isNotEmpty()) cand[i][j] = allowed[rng.nextInt(allowed.size)]
                    }
                }
                t++
            }
            val polished = localBestImprovement(state, cand, 250 + cycle * 120, rng, shouldStop)
            val rep = UnifiedViolationChecker.check(state, polished)
            usedCycles = cycle + 1
            if (isBetter(rep, bestReport)) {
                best = polished
                bestReport = rep
                applied = true
            }
            cycle++
        }
        val reason = if (applied) "strategic oscillation accepted" else "no improving oscillation"
        val logs = listOf(MirrorLog(tag = "HF80", message = "SO applied=$applied HARD ${before.hard}->${bestReport.hard} score ${before.weightedScore.toLong()}->${bestReport.weightedScore.toLong()} cycles=$usedCycles"))
        return HF80Result(best, before.hard, bestReport.hard, before.weightedScore, bestReport.weightedScore, usedCycles, applied, reason, logs)
    }

    data class CyclicSwapResult(
        val newSchedule: Array<IntArray>,
        val beforeTotal: Int,
        val afterTotal: Int,
        val applied: Int,
        val logs: List<MirrorLog>,
    )

    /**
     * [ソフト研磨・T2] 被覆を保つ循環交換（k=2,3）研磨。各日の (日,シフト) 人数を保ったまま、職員の
     * シフトを **2職員スワップ / 3職員ローテーション** で組み替える。被覆は不変＝HARD充足を維持し、
     * 連続規則(c3/c3m) や希望・回数の相互作用を**実目的関数(UnifiedViolationChecker)で評価**して
     * 改善時のみ採用（keep-best＝退化なし）。日内Hungarian(range/apt最適)が触れない c3 を狙う。
     * 注: 提案サイクルは必ず実チェックで検証してから採用するため、サイクル生成が不完全でも悪化しない。
     */
    fun applyCyclicSwapPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 4, shouldStop: () -> Boolean = { false }): CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        fun movable(i: Int, j: Int) = p.wish[i][j] < 0     // 希望固定セルは動かさない
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            for (j in 0 until p.T) {
                if (shouldStop()) break
                // --- k=2: 2職員スワップ（同日・被覆不変）---
                for (a in 0 until p.S) {
                    if (!movable(a, j)) continue
                    for (b in a + 1 until p.S) {
                        if (!movable(b, j)) continue
                        val sa = work[a][j]; val sb = work[b][j]
                        if (sa == sb || !p.canDo(a, sb) || !p.canDo(b, sa)) continue
                        work[a][j] = sb; work[b][j] = sa
                        val rep = UnifiedViolationChecker.check(state, work)
                        if (isBetter(rep, bestRep)) { bestRep = rep; applied++; improved = true }
                        else { work[a][j] = sa; work[b][j] = sb }
                    }
                }
                // --- k=3: 3職員ローテーション（同日・被覆不変）---
                for (a in 0 until p.S) {
                    if (!movable(a, j)) continue
                    for (b in a + 1 until p.S) {
                        if (!movable(b, j)) continue
                        for (c in b + 1 until p.S) {
                            if (!movable(c, j)) continue
                            if (shouldStop()) break
                            val sa = work[a][j]; val sb = work[b][j]; val sc = work[c][j]
                            if (sa == sb && sb == sc) continue
                            // a←sb, b←sc, c←sa（feasibleなら適用→評価→不採用なら巻き戻し）
                            if (p.canDo(a, sb) && p.canDo(b, sc) && p.canDo(c, sa)) {
                                work[a][j] = sb; work[b][j] = sc; work[c][j] = sa
                                val rep = UnifiedViolationChecker.check(state, work)
                                if (isBetter(rep, bestRep)) { bestRep = rep; applied++; improved = true; continue }
                                work[a][j] = sa; work[b][j] = sb; work[c][j] = sc
                            }
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "CyclicSwap",
            message = "循環交換(k=2,3)研磨: total ${before.total}->${bestRep.total} 採用${applied}回"))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs)
    }

    /**
     * [ソフト研磨・連続規則] c3(必須の並び)・c3m(推奨)・c3mn(回避)・c3n(禁止=HARD) はいずれも職員の
     * 連続日の並びで決まる。同日スワップ(循環交換)では1日しか変えられず多日パターンに届かないため、
     * 2職員 i,i' が 連続 W 日(W=2,3)を丸ごと交換する（各日の被覆＝人数が不変＝HARD維持）。両者の W日
     * パターンが入れ替わり、2〜3日にわたる並びを直せる。実目的関数で評価し改善時のみ採用（keep-best＝
     * 退化なし）。isBetter は HARD を最優先するため、c3n(禁止=HARD) の解消も同時に拾う。
     */
    fun applyC3SequencePolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }): CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        var skipped = 0     // [#5] 前フィルタでフル評価を省いた手数
        fun movable(i: Int, j: Int) = p.wish[i][j] < 0
        val windows = intArrayOf(2, 3)   // 連続2日・3日（c3は最大5連日だが2-3日窓でほぼ捕捉）
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            // [違反セル指向] c3系で違反している職員のみを起点に絞る。c3は職員ごと→2者交換で改善する手は
            //   必ず違反職員を含む＝取りこぼし無し(ロスレス)。空なら即終了でコスト0。
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work)
            val anchorStaff = HashSet<Int>()
            for ((key, cls) in rep0.violations) {
                if (cls == "vio-c3" || cls == "vio-c3m" || cls == "vio-c3mn") anchorStaff.add(key.substringBefore(",").toIntOrNull() ?: continue)
            }
            if (anchorStaff.isEmpty()) break
            for (w in windows) {
                if (p.T < w) continue
                for (j in 0..p.T - w) {
                    if (shouldStop()) break
                    for (i in 0 until p.S) {
                        if ((0 until w).any { !movable(i, j + it) }) continue
                        for (i2 in i + 1 until p.S) {
                            if (i !in anchorStaff && i2 !in anchorStaff) continue   // 違反職員を含む対のみ
                            if ((0 until w).any { !movable(i2, j + it) }) continue
                            var feasible = true; var same = true
                            for (t in 0 until w) {
                                if (!p.canDo(i, work[i2][j + t]) || !p.canDo(i2, work[i][j + t])) { feasible = false; break }
                                if (work[i][j + t] != work[i2][j + t]) same = false
                            }
                            if (!feasible || same) continue
                            // [#5 差分前フィルタ] 同群の2者ブロック交換は関与2職員の per-staff 違反量で事前判定。
                            val sameGroup = p.sgrp[i] == p.sgrp[i2]
                            val preW = if (sameGroup) staffSoftWeighted(p, work, i) + staffSoftWeighted(p, work, i2) else 0L
                            for (t in 0 until w) { val tmp = work[i][j + t]; work[i][j + t] = work[i2][j + t]; work[i2][j + t] = tmp }
                            if (sameGroup) {
                                val postW = staffSoftWeighted(p, work, i) + staffSoftWeighted(p, work, i2)
                                if (postW >= preW) { for (t in 0 until w) { val tmp = work[i][j + t]; work[i][j + t] = work[i2][j + t]; work[i2][j + t] = tmp }; skipped++; continue }
                            }
                            val rep = UnifiedViolationChecker.check(state, work)
                            if (isBetter(rep, bestRep)) { bestRep = rep; applied++; improved = true }
                            else for (t in 0 until w) { val tmp = work[i][j + t]; work[i][j + t] = work[i2][j + t]; work[i2][j + t] = tmp }   // 巻き戻し
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "C3Polish",
            message = "連続規則c3系研磨(2者ブロック): c3 ${before.breakdown["c3"] ?: 0}->${bestRep.breakdown["c3"] ?: 0}" +
                " / c3m ${before.breakdown["c3m"] ?: 0}->${bestRep.breakdown["c3m"] ?: 0}" +
                " / c3mn ${before.breakdown["c3mn"] ?: 0}->${bestRep.breakdown["c3mn"] ?: 0}" +
                " / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回 (差分前フィルタで省略${skipped}手)"))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs)
    }

    /**
     * [ソフト研磨・c3系強化] c3/c3m/c3mn(連続規則)で違反しているセルを起点に、3職員×連日(2-3日)の
     * ブロック「回転」を試す。2者ブロック入替や同日k=3巡回では到達できない3者×窓の組替えを、各日の
     * (日,シフト)人数を保ったまま（=被覆/HARD不変）行い、実目的(UnifiedViolationChecker)で改善時のみ
     * 採用（keep-best＝退化なし）。重み・パラメータは不変。違反セル指向なので低コスト。
     * 2回の2者交換に分解すると中間で悪化するため山登りでは越えられない局面を、回転1手で跨ぐのが狙い。
     */
    /**
     * [ソフト研磨・3者回転] 指定クラス(anchorClasses)で違反しているセルを起点に、3職員×連日(2-3日)の
     * ブロック「回転」を試す。2者ブロック入替/同日k=3巡回では到達できない3者×窓の組替えを、各日の
     * (日,シフト)人数を保ったまま（=被覆/HARD不変）行い、実目的(UnifiedViolationChecker)で改善時のみ
     * 採用（keep-best＝退化なし）。c1・c3系どちらの違反起点にも使える汎用版。重み・パラメータ不変。
     * 2回の2者交換に分解すると中間で悪化するため山登りでは越えられない局面を、回転1手で跨ぐのが狙い。
     */
    fun applyBlockRotationPolish(state: MagiState, schedule: Array<IntArray>, anchorClasses: Set<String>, tag: String, maxPasses: Int = 2, shouldStop: () -> Boolean = { false }): CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        var skipped = 0     // [#5] 前フィルタでフル評価を省いた手数(有効性ログ用)
        fun movable(i: Int, j: Int) = p.wish[i][j] < 0     // 希望固定セルは動かさない
        val windows = intArrayOf(2, 3)
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            // 指定クラスで違反している職員(=回転の起点)を収集。無ければ即終了（コスト0）。
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work)
            val anchorStaff = HashSet<Int>()
            for ((key, cls) in rep0.violations) {
                if (cls in anchorClasses) anchorStaff.add(key.substringBefore(",").toIntOrNull() ?: continue)
            }
            if (anchorStaff.isEmpty()) break
            var improved = false
            for (w in windows) {
                if (p.T < w) continue
                for (j in 0..p.T - w) {
                    if (shouldStop()) break
                    // この窓で全日movableな職員のみ回転対象（同一3名を各日で回す＝日内人数不変）。
                    val cand = (0 until p.S).filter { i -> (0 until w).all { movable(i, j + it) } }
                    if (cand.size < 3) continue
                    for (ai in cand) {
                        if (ai !in anchorStaff) continue
                        for (bi in cand) {
                            if (bi == ai) continue
                            for (ci in cand) {
                                if (ci == ai || ci == bi) continue
                                // 回転 ai<-bi, bi<-ci, ci<-ai が各日で担当可能か。
                                var feasible = true
                                for (t in 0 until w) {
                                    if (!p.canDo(ai, work[bi][j + t]) || !p.canDo(bi, work[ci][j + t]) || !p.canDo(ci, work[ai][j + t])) { feasible = false; break }
                                }
                                if (!feasible) continue
                                val sa = IntArray(w) { work[ai][j + it] }
                                val sb = IntArray(w) { work[bi][j + it] }
                                val sc = IntArray(w) { work[ci][j + it] }
                                // [#5 差分前フィルタ] 同群の3者回転は関与職員の per-staff 違反量で事前判定し、
                                //   改善見込みの無い手はフル評価をスキップ(採用判定は従来どおりフル評価が担う=安全)。
                                val sameGroup = p.sgrp[ai] == p.sgrp[bi] && p.sgrp[bi] == p.sgrp[ci]
                                val preW = if (sameGroup) staffSoftWeighted(p, work, ai) + staffSoftWeighted(p, work, bi) + staffSoftWeighted(p, work, ci) else 0L
                                for (t in 0 until w) { work[ai][j + t] = sb[t]; work[bi][j + t] = sc[t]; work[ci][j + t] = sa[t] }
                                if (sameGroup) {
                                    val postW = staffSoftWeighted(p, work, ai) + staffSoftWeighted(p, work, bi) + staffSoftWeighted(p, work, ci)
                                    if (postW >= preW) { for (t in 0 until w) { work[ai][j + t] = sa[t]; work[bi][j + t] = sb[t]; work[ci][j + t] = sc[t] }; skipped++; continue }
                                }
                                val rep = UnifiedViolationChecker.check(state, work)
                                if (isBetter(rep, bestRep)) { bestRep = rep; applied++; improved = true }
                                else for (t in 0 until w) { work[ai][j + t] = sa[t]; work[bi][j + t] = sb[t]; work[ci][j + t] = sc[t] }   // 巻き戻し
                            }
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = tag,
            message = "$tag 3者回転研磨: c1 ${before.breakdown["c1"] ?: 0}->${bestRep.breakdown["c1"] ?: 0}" +
                " / c3 ${before.breakdown["c3"] ?: 0}->${bestRep.breakdown["c3"] ?: 0}" +
                " / c3m ${before.breakdown["c3m"] ?: 0}->${bestRep.breakdown["c3m"] ?: 0}" +
                " / c3mn ${before.breakdown["c3mn"] ?: 0}->${bestRep.breakdown["c3mn"] ?: 0}" +
                " / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回 (差分前フィルタで省略${skipped}手)"))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs)
    }

    // 主目的(hard→total→weighted)を悪化させないか。平準化は「悪化させない範囲」で二次最適化する。
    private fun mainNotWorse(rep: ViolationReport, best: ViolationReport): Boolean =
        rep.hard < best.hard ||
            (rep.hard == best.hard && rep.total < best.total) ||
            (rep.hard == best.hard && rep.total == best.total && rep.weightedScore <= best.weightedScore + 1e-9)

    // グループ内シフト回数のばらつき（群ごと・担当ONシフトごとの分散の総和）。小さいほど平準。
    private fun groupShiftVariance(p: Problem, state: MagiState, counts: Array<IntArray>): Double {
        var v = 0.0
        for (g in 0 until p.G) {
            val gs = state.groupShift.getOrNull(g) ?: continue
            val mem = (0 until p.S).filter { p.sgrp[it] == g }
            if (mem.size < 2) continue
            for (k in 0 until p.K) {
                if (gs.getOrNull(k) != 1) continue
                var sum = 0; for (i in mem) sum += counts[i][k]
                val mean = sum.toDouble() / mem.size
                for (i in mem) { val d = counts[i][k] - mean; v += d * d }
            }
        }
        return v
    }

    // 7日周期(曜日)の偏り: 各職員の勤務(休以外)を曜日7バケットに割り、分散を総和。小さいほど曜日が均等。
    private fun dayOfWeekVariance(p: Problem, state: MagiState, work: Array<IntArray>, restIdx: Int): Double {
        val dow0 = runCatching { java.time.LocalDate.parse(state.startDate).dayOfWeek.value % 7 }.getOrDefault(0)
        var v = 0.0
        for (i in 0 until p.S) {
            val wd = IntArray(7)
            for (j in 0 until p.T) { val k = work[i][j]; if (k != restIdx && k in 0 until p.K) wd[(dow0 + j) % 7]++ }
            val avg = wd.sum() / 7.0
            for (x in wd) v += (x - avg) * (x - avg)
        }
        return v
    }

    /**
     * [平準化・グループ内シフト回数] 同一グループ内で各シフトの担当回数を均す。同日に同一グループの2職員の
     * シフトを入替え（被覆＝人数不変・HARD維持）、主目的(hard/total/weighted)を悪化させない範囲で
     * グループ内分散を厳密に下げる移動だけ採用（lexicographic）。主目的は不変なので退化しない。
     */
    fun applyGroupShiftEqualizePolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 2, shouldStop: () -> Boolean = { false }): CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var bestMetric = groupShiftVariance(p, state, countMatrix(p, work))
        var applied = 0
        fun movable(i: Int, j: Int) = p.wish[i][j] < 0
        var pass = 0
        while (pass < maxPasses && bestMetric > 0.0) {
            if (shouldStop()) break
            var improved = false
            for (j in 0 until p.T) {
                if (shouldStop()) break
                for (a in 0 until p.S) {
                    if (!movable(a, j)) continue
                    for (b in a + 1 until p.S) {
                        if (!movable(b, j) || p.sgrp[a] != p.sgrp[b]) continue
                        val sa = work[a][j]; val sb = work[b][j]
                        if (sa == sb || !p.canDo(a, sb) || !p.canDo(b, sa)) continue
                        work[a][j] = sb; work[b][j] = sa
                        val rep = UnifiedViolationChecker.check(state, work)
                        val m = groupShiftVariance(p, state, countMatrix(p, work))
                        if (mainNotWorse(rep, bestRep) && m < bestMetric - 1e-9) { bestRep = rep; bestMetric = m; applied++; improved = true }
                        else { work[a][j] = sa; work[b][j] = sb }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "GroupEqualize",
            message = "グループ内シフト回数の平準化: ばらつき ${"%.1f".format(before.let { groupShiftVariance(p, state, countMatrix(p, normalizeSchedule(schedule, p))) })}->${"%.1f".format(bestMetric)} 採用${applied}回"))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs)
    }

    /**
     * [平準化・7日周期] 各職員の勤務が特定の曜日に偏らないよう均す。同日の2職員のシフトを入替え
     * （被覆不変・HARD維持）、主目的を悪化させない範囲で曜日分散を厳密に下げる移動だけ採用。退化なし。
     */
    fun applyWeeklyEqualizePolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 2, shouldStop: () -> Boolean = { false }): CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        val restIdx = state.shifts.indexOfFirst { it.kigou == "休" }
        var bestRep = before
        var bestMetric = dayOfWeekVariance(p, state, work, restIdx)
        val beforeMetric = bestMetric
        var applied = 0
        fun movable(i: Int, j: Int) = p.wish[i][j] < 0
        var pass = 0
        while (pass < maxPasses && bestMetric > 0.0) {
            if (shouldStop()) break
            var improved = false
            for (j in 0 until p.T) {
                if (shouldStop()) break
                for (a in 0 until p.S) {
                    if (!movable(a, j)) continue
                    for (b in a + 1 until p.S) {
                        if (!movable(b, j)) continue
                        val sa = work[a][j]; val sb = work[b][j]
                        if (sa == sb || !p.canDo(a, sb) || !p.canDo(b, sa)) continue
                        work[a][j] = sb; work[b][j] = sa
                        val rep = UnifiedViolationChecker.check(state, work)
                        val m = dayOfWeekVariance(p, state, work, restIdx)
                        if (mainNotWorse(rep, bestRep) && m < bestMetric - 1e-9) { bestRep = rep; bestMetric = m; applied++; improved = true }
                        else { work[a][j] = sa; work[b][j] = sb }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "WeeklyEqualize",
            message = "7日周期(曜日)の平準化: 偏り ${"%.1f".format(beforeMetric)}->${"%.1f".format(bestMetric)} 採用${applied}回"))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs)
    }

    /** day j を含む有効窓のどれかで、職員 i のシフト X が N 回未満（=c1不足）か。 */
    private fun inDeficientC1Window(p: Problem, work: Array<IntArray>, i: Int, x: Int, d: Int, n: Int, j: Int): Boolean {
        if (d <= 0) return false
        var w = maxOf(0, j - d + 1)
        val wEnd = minOf(j, p.T - d)
        while (w <= wEnd) {
            var z = 0
            for (l in 0 until d) if (work[i][w + l] == x) z++
            if (z < n) return true
            w++
        }
        return false
    }

    /**
     * [ソフト研磨・C1] 期間要件 cons1（D日窓にシフトXをN回以上・職員ごと）の研磨。
     * c1不足の (職員 i, 窓) を見つけ、その窓内で i が X でない日 j に対し、その日に X をしている提供者 i' と
     * **同日スワップ**（i←X, i'←iの旧シフト＝被覆不変・HARD維持）して i の X を増やす。実目的関数で評価し
     * 改善時のみ採用（keep-best＝退化なし）。汎用循環交換と違い**c1不足の窓に的を絞る**ので c1 を効率的に削る。
     */
    fun applyC1WindowPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }): CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        if (p.cons1.isEmpty()) {
            return CyclicSwapResult(work, before.total, bestRep.total, 0,
                listOf(MirrorLog(tag = "C1Polish", message = "cons1なし=スキップ")))
        }
        fun movable(i: Int, j: Int) = p.wish[i][j] < 0
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            // [違反セル指向] c1で違反している職員のみを起点に絞る。c1は職員ごと→改善手は必ず違反職員を
            //   含む＝ロスレス。空なら即終了でコスト0。
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work)
            val anchorStaff = HashSet<Int>()
            for ((key, cls) in rep0.violations) {
                if (cls == "vio-c1") anchorStaff.add(key.substringBefore(",").toIntOrNull() ?: continue)
            }
            if (anchorStaff.isEmpty()) break
            for (c in p.cons1) {
                val x = c.shiftIdx; val d = c.day1; val n = c.day2
                if (x !in 0 until p.K || d <= 0) continue
                for (i in 0 until p.S) {
                    if (shouldStop()) break
                    if (i !in anchorStaff) continue
                    if (!p.canDo(i, x)) continue
                    for (j in 0 until p.T) {
                        if (work[i][j] == x || !movable(i, j)) continue
                        if (!inDeficientC1Window(p, work, i, x, d, n, j)) continue
                        val a = work[i][j]                                  // i の旧シフト
                        for (i2 in 0 until p.S) {
                            if (i2 == i || work[i2][j] != x || !movable(i2, j) || !p.canDo(i2, a)) continue
                            work[i][j] = x; work[i2][j] = a                 // 同日スワップ（被覆不変）
                            val rep = UnifiedViolationChecker.check(state, work)
                            if (isBetter(rep, bestRep)) { bestRep = rep; applied++; improved = true; break }
                            work[i][j] = a; work[i2][j] = x                 // 巻き戻し
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "C1Polish",
            message = "期間要件(c1)研磨: c1 ${before.breakdown["c1"] ?: 0}->${bestRep.breakdown["c1"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && (before.breakdown["c1"] ?: 0) > 0) " [頭打ち=改善手なし]" else "")))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs)
    }

    data class DayAssignResult(
        val newSchedule: Array<IntArray>,
        val beforeTotal: Int,
        val afterTotal: Int,
        val appliedDays: Int,
        val logs: List<MirrorLog>,
    )

    /**
     * [ソフト研磨・厳密] 日ごと最小費用割当による研磨。各日の (日,シフト) 人数（=HARD充足）を固定したまま、
     * 希望未固定(wish<0)の職員を、その日の同一シフト集合へ「個人別回数(range)・適切回数(apt)の逸脱が最小」に
     * **厳密再割当**（Hungarian）。乱択でなく日内最適の候補を作り、全体が改善した日だけ採用（keep-best＝退化なし）。
     * 連続規則・希望・平準化など列横断の相互作用は採用判定(UnifiedViolationChecker)で担保する。
     */
    fun applyDayAssignmentPolish(state: MagiState, schedule: Array<IntArray>, shouldStop: () -> Boolean = { false }): DayAssignResult {
        val p = Problem(state)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        // 適切回数(apt)目標: state.groupShiftApt[群][シフト] の整数（空=なし）。
        fun aptTarget(i: Int, k: Int): Int? {
            val g = state.staff.getOrNull(i)?.groupIdx ?: return null
            return state.groupShiftApt.getOrNull(g)?.getOrNull(k)?.trim()?.toIntOrNull()
        }
        fun cnt(): Array<IntArray> = countMatrix(p, work)
        var counts = cnt()
        for (j in 0 until p.T) {
            if (shouldStop()) break
            val free = (0 until p.S).filter { i -> p.wish[i][j] < 0 }
            if (free.size < 2) continue
            val slots = free.map { work[it][j] }                       // 当日の同一シフト多重集合（人数固定）
            val n = free.size
            val costM = Array(n) { r ->
                val i = free[r]
                LongArray(n) { c ->
                    val k = slots[c]
                    if (k !in 0 until p.K || !p.canDo(i, k)) MinCostAssignment.INF
                    else {
                        val x0 = counts[i][k] - (if (work[i][j] == k) 1 else 0)   // この日を除いた現状カウント
                        val x1 = x0 + 1                                            // k を割当てた後
                        val lo = p.rangeLo[i][k]
                        val hi = effectiveHi(p, i, k)
                        fun rangePen(x: Int) = (if (lo != Int.MIN_VALUE) 3L * maxOf(0, lo - x) else 0L) + 3L * maxOf(0, x - hi)
                        var cost = rangePen(x1) - rangePen(x0)                     // range の限界費用
                        val t = aptTarget(i, k)
                        if (t != null) cost += (kotlin.math.abs(x1 - t) - kotlin.math.abs(x0 - t)).toLong()  // apt の限界費用
                        cost
                    }
                }
            }
            val assign = MinCostAssignment.solve(costM)
            val cand = work.copy2D()
            var changed = false
            for (r in free.indices) {
                val i = free[r]; val k = slots[assign[r]]
                if (cand[i][j] != k) { cand[i][j] = k; changed = true }
            }
            if (!changed) continue
            val rep = UnifiedViolationChecker.check(state, cand)
            if (isBetter(rep, bestRep)) { work = cand; bestRep = rep; counts = cnt(); applied++ }
        }
        val logs = listOf(MirrorLog(tag = "DayAssign",
            message = "日ごと厳密割当: total ${before.total}->${bestRep.total} 採用${applied}日"))
        return DayAssignResult(work, before.total, bestRep.total, applied, logs)
    }

    fun applyHF67InterStaffSwap(state: MagiState, schedule: Array<IntArray>, maxSwaps: Int = 30, shouldStop: () -> Boolean = { false }): HF67Result {
        val p = Problem(state)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var current = before
        var swaps = 0
        var shortage = 0
        var capacity = 0
        var rollback = 0

        while (swaps < maxSwaps) {
            if (shouldStop()) break
            val counts = countMatrix(p, work)
            var best: SwapCandidate? = null
            var bestReport: ViolationReport? = null
            for (k in 0 until p.K) {
                val lows = ArrayList<Int>()
                val highs = ArrayList<Int>()
                for (i in 0 until p.S) {
                    if (p.canDo(i, k) && p.rangeLo[i][k] != Int.MIN_VALUE && counts[i][k] < p.rangeLo[i][k]) lows.add(i)
                    if (counts[i][k] > effectiveHi(p, i, k)) highs.add(i)
                }
                for (to in lows) {
                    for (from in highs) {
                        if (to == from) continue
                        val cand = trySwapShiftBetweenStaff(p, work, from, to, k) ?: continue
                        val rep = UnifiedViolationChecker.check(state, cand.first)
                        val ref = bestReport ?: current
                        if (isBetter(rep, ref)) {
                            best = cand.second
                            bestReport = rep
                        }
                    }
                }
            }
            if (best == null || bestReport == null) break
            val b = best
            val next = work.copy2D()
            val tmp = next[b.fromStaff][b.fromDay]
            next[b.fromStaff][b.fromDay] = next[b.toStaff][b.toDay]
            next[b.toStaff][b.toDay] = tmp
            work = next
            current = bestReport
            swaps++
            shortage++
            if (current.soft < before.soft) capacity++
        }
        if (swaps == 0 && !shouldStop()) {
            val improved = localPairwiseStaffSwap(state, work, maxSwaps, shouldStop)
            work = improved.first
            swaps = improved.second
            rollback = improved.third
            current = UnifiedViolationChecker.check(state, work)
            capacity = swaps
        }
        val logs = listOf(MirrorLog(tag = "HF67", message = "inter-staff swap applied=$swaps rollback=$rollback total ${before.total}->${current.total}"))
        return HF67Result(work, before.total, current.total, swaps, shortage, capacity, rollback, logs)
    }

    fun applyHF66IntraStaffRedistribution(state: MagiState, schedule: Array<IntArray>, maxMoves: Int = 30, shouldStop: () -> Boolean = { false }): HF66Result {
        val p = Problem(state)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var current = before
        var moves = 0
        var shortageMoves = 0
        var capacityMoves = 0
        var rollback = 0

        while (moves < maxMoves) {
            if (shouldStop()) break
            val counts = countMatrix(p, work)
            var bestMove: MoveCandidate? = null
            var bestReport: ViolationReport? = null
            for (i in 0 until p.S) {
                val lows = ArrayList<Int>()
                val highs = ArrayList<Int>()
                for (k in 0 until p.K) {
                    if (p.canDo(i, k) && p.rangeLo[i][k] != Int.MIN_VALUE && counts[i][k] < p.rangeLo[i][k]) lows.add(k)
                    if (counts[i][k] > effectiveHi(p, i, k)) highs.add(k)
                }
                for (want in lows) for (give in highs) {
                    for (j in 0 until p.T) {
                        if (work[i][j] != give || p.wish[i][j] >= 0) continue
                        val cand = work.copy2D()
                        cand[i][j] = want
                        val rep = UnifiedViolationChecker.check(state, cand)
                        if (isBetter(rep, bestReport ?: current)) {
                            bestMove = MoveCandidate(i, j, give, want)
                            bestReport = rep
                        }
                    }
                }
            }
            val mv = bestMove ?: break
            work[mv.staff][mv.day] = mv.toShift
            current = bestReport ?: UnifiedViolationChecker.check(state, work)
            moves++
            shortageMoves++
            if (current.soft < before.soft) capacityMoves++
        }
        if (moves == 0 && !shouldStop()) {
            val rng = Random(0x66L)
            var t = 0
            while (t < maxMoves) {
                if (shouldStop()) break
                if (p.S > 0 && p.T > 0) {
                    val cand = work.copy2D()
                    val i = rng.nextInt(p.S)
                    val j = rng.nextInt(p.T)
                    if (p.wish[i][j] < 0) {
                        val allowed = p.allowedShiftsForStaff(i)
                        if (allowed.isNotEmpty()) {
                            val old = cand[i][j]
                            cand[i][j] = allowed[rng.nextInt(allowed.size)]
                            if (cand[i][j] != old) {
                                val rep = UnifiedViolationChecker.check(state, cand)
                                if (isBetter(rep, current)) {
                                    work = cand
                                    current = rep
                                    moves++
                                    capacityMoves++
                                } else {
                                    rollback++
                                }
                            }
                        }
                    }
                }
                t++
            }
        }
        val logs = listOf(MirrorLog(tag = "HF66", message = "intra-staff redistribution applied=$moves rollback=$rollback total ${before.total}->${current.total}"))
        return HF66Result(work, before.total, current.total, moves, shortageMoves, capacityMoves, rollback, logs)
    }

    fun detectHF70Anomalies(
        state: MagiState,
        schedule: Array<IntArray>,
        algoName: String,
        report: ViolationReport = UnifiedViolationChecker.check(state, schedule),
    ): HF70Result {
        val invalid = invalidAssignmentCount(state, schedule)
        val impossible = V6SanityPort.detectImpossibleWishes(state).size
        val hardCore = report.hard - (report.breakdown["pref"] ?: 0)
        val issues = ArrayList<String>()
        if (invalid > 0) issues.add("担当不可/範囲外配置 $invalid 件")
        if (impossible > 0) issues.add("不可能希望 $impossible 件")
        if (hardCore > 0) issues.add("希望以外HARD $hardCore 件")
        val msg = if (issues.isEmpty()) "HF70: $algoName 異常なし" else "HF70: ${issues.joinToString(" / ")}"
        val advice = if (issues.isEmpty()) "" else "設定(ws1/担当範囲), 希望(ws3), 必要人数, 連勤禁止条件を確認してください"
        val level = if (issues.isEmpty()) "I" else "W"
        val logs = listOf(MirrorLog(level = level, tag = "HF70", message = msg + if (advice.isNotBlank()) " — $advice" else ""))
        return HF70Result(issues.size, msg, advice, logs)
    }

    private data class SwapCandidate(val fromStaff: Int, val fromDay: Int, val toStaff: Int, val toDay: Int)
    private data class MoveCandidate(val staff: Int, val day: Int, val fromShift: Int, val toShift: Int)

    private fun trySwapShiftBetweenStaff(p: Problem, schedule: Array<IntArray>, from: Int, to: Int, shift: Int): Pair<Array<IntArray>, SwapCandidate>? {
        val fromDays = ArrayList<Int>()
        val toDays = ArrayList<Int>()
        for (j in 0 until p.T) {
            if (schedule[from][j] == shift && p.wish[from][j] < 0) fromDays.add(j)
            if (schedule[to][j] != shift && p.wish[to][j] < 0 && p.canDo(to, shift) && p.canDo(from, schedule[to][j])) toDays.add(j)
        }
        for (jf in fromDays) for (jt in toDays) {
            val cand = schedule.copy2D()
            val tmp = cand[from][jf]
            cand[from][jf] = cand[to][jt]
            cand[to][jt] = tmp
            return Pair(cand, SwapCandidate(from, jf, to, jt))
        }
        return null
    }

    private fun localPairwiseStaffSwap(state: MagiState, schedule: Array<IntArray>, maxSwaps: Int, shouldStop: () -> Boolean = { false }): Triple<Array<IntArray>, Int, Int> {
        val p = Problem(state)
        var work = schedule.copy2D()
        var current = UnifiedViolationChecker.check(state, work)
        var applied = 0
        var rollback = 0
        loop@ for (i in 0 until p.S) for (i2 in i + 1 until p.S) for (j in 0 until p.T) {
            if (applied >= maxSwaps || shouldStop()) break@loop
            if (p.wish[i][j] >= 0 || p.wish[i2][j] >= 0) continue
            val a = work[i][j]
            val b = work[i2][j]
            if (a == b || !p.canDo(i, b) || !p.canDo(i2, a)) continue
            val cand = work.copy2D()
            cand[i][j] = b
            cand[i2][j] = a
            val rep = UnifiedViolationChecker.check(state, cand)
            if (isBetter(rep, current)) {
                work = cand
                current = rep
                applied++
            } else {
                rollback++
            }
        }
        return Triple(work, applied, rollback)
    }

    private fun localBestImprovement(state: MagiState, schedule: Array<IntArray>, tries: Int, rng: Random, shouldStop: () -> Boolean = { false }): Array<IntArray> {
        val p = Problem(state)
        var best = schedule.copy2D()
        var bestReport = UnifiedViolationChecker.check(state, best)
        var t = 0
        val maxTry = max(0, tries)
        while (t < maxTry) {
            if (shouldStop()) break
            if (p.S > 0 && p.T > 0) {
                val cand = best.copy2D()
                val i = rng.nextInt(p.S)
                val j = rng.nextInt(p.T)
                if (p.wish[i][j] < 0) {
                    val allowed = p.allowedShiftsForStaff(i)
                    if (allowed.isNotEmpty()) {
                        cand[i][j] = allowed[rng.nextInt(allowed.size)]
                        val rep = UnifiedViolationChecker.check(state, cand)
                        if (isBetter(rep, bestReport)) {
                            best = cand
                            bestReport = rep
                        }
                    }
                }
            }
            t++
        }
        return best
    }

    private fun effectiveHi(p: Problem, i: Int, k: Int): Int {
        val hi = p.rangeHi[i][k]
        return if (hi == Int.MAX_VALUE) Int.MAX_VALUE / 4 else hi
    }

    private fun invalidAssignmentCount(state: MagiState, schedule: Array<IntArray>): Int {
        val p = Problem(state)
        val s = normalizeSchedule(schedule, p)
        var n = 0
        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = s[i][j]
            if (k !in 0 until p.K || !p.canDo(i, k)) n++
        }
        return n
    }

    private fun isBetter(a: ViolationReport, b: ViolationReport): Boolean {
        if (a.hard != b.hard) return a.hard < b.hard
        if (a.total != b.total) return a.total < b.total
        return a.weightedScore < b.weightedScore
    }

    // ============================================================================
    // [#5 差分前フィルタ] 職員 i の「研磨手で動きうる families」の重み付き違反量を checker と同一ロジックで
    // 厳密計算する。c1(4) + c3(3) + c3m(2) + c3mn(12) + c3n(HARD=7000)。研磨手は被覆保存の置換なので、
    // 関与職員が全員同一グループなら、群(c41/c42/groupViol)・被覆(covU/covO)・pref(希望固定セルは不動)は不変。
    // よって「関与職員のこの値が改善しない同群の手」は全体目的(hard/total/weighted)も改善しえない。
    // ⇒ そういう手では高コストの UnifiedViolationChecker.check をスキップできる(前フィルタ)。
    // 安全性: 採用判定は従来どおりフル評価 isBetter が担う。前フィルタに誤りがあっても「改善手を取り
    //         こぼす(=研磨が弱まるだけ)」方向にしか働かず、誤採用や研磨前からの悪化は起こらない。
    private fun staffSoftWeighted(p: Problem, sched: Array<IntArray>, i: Int): Long {
        var w = 0L
        for (c in p.cons1) {                                   // c1: d日窓で shiftIdx が day2 回未満 → 違反
            if (!p.canDo(i, c.shiftIdx)) continue
            var j = 0
            while (j <= p.T - c.day1) {
                var z = 0
                for (l in 0 until c.day1) if (sched[i][j + l] == c.shiftIdx) z++
                if (z < c.day2) w += 4L
                j++
            }
        }
        w += c3FamilyWeighted(p, sched, i, p.cons3, false, 3L)   // c3  (required系: z<d-1 で発火)
        w += c3FamilyWeighted(p, sched, i, p.cons3m, false, 2L)  // c3m
        w += c3FamilyWeighted(p, sched, i, p.cons3mn, true, 12L) // c3mn(forbidden系: z==d-1 で発火)
        w += c3FamilyWeighted(p, sched, i, p.cons3n, true, 7000L)// c3n (HARD)
        return w
    }

    private fun c3FamilyWeighted(p: Problem, sched: Array<IntArray>, i: Int, list: List<C3>, forbidden: Boolean, weight: Long): Long {
        var w = 0L
        for (c in list) {
            val seq = c.seq; val d = seq.size
            if (d == 0 || d > p.T) continue
            var j = 0
            while (j <= p.T - d) {
                if (sched[i][j] == seq[0]) {
                    var z = 0
                    for (l in 1 until d) if (sched[i][j + l] == seq[l]) z++
                    val fire = if (forbidden) z == d - 1 else z < d - 1
                    if (fire) w += weight
                }
                j++
            }
        }
        return w
    }
}
