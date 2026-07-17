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
        deadlineMs: Long = Long.MAX_VALUE,
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
        // [残予算ガード] HF66 は手ごとに全候補をフル check する高コストパス。残予算の半分まで(残り半分を
        //   後段の研磨群へ確保)＋絶対上限6sで打ち切り、暴走で後続パスを予算超過で打ち切らせない。
        val hf66Cap = ((deadlineMs - t66).coerceAtLeast(0L) / 2).coerceAtMost(6_000L)
        val r66 = applyHF66IntraStaffRedistribution(state, work, maxMoves = 30, shouldStop = shouldStop, deadlineMs = t66 + hf66Cap)
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

        // [weekly 研磨の穴を埋める] 曜日平準化(weekly)は同日2者スワップでは動かせない（勤務↔勤務は曜日別の
        //   勤務/休が不変）ため、被覆保存の2職員×2日 長方形交換で「過剰曜日→過少曜日」へ勤務を移す。実目的関数
        //   isBetter で採否＝退化なし。下の equalize 系(分散指標)より先に L1 指向のこのパスを走らせる。
        onPhase("後処理 曜日平準化(長方形交換)")
        val rWrb = applyWeeklyRebalancePolish(state, work, maxPasses = 2, shouldStop = shouldStop)
        work = rWrb.newSchedule.copy2D()
        logs.addAll(rWrb.logs)

        // [交互最適化(Alternating Optimization)] 長方形交換(クロス日)が届かない同日内の「休の割当先」を、日ブロック
        //   ごとの最小費用割当(Hungarian＝凸最適化)で weekly/range/apt 同時最適に再配置し、不動点まで巡回する。
        //   rectangle(クロス日)と AO(同日内)は相補的＝両方走らせて weekly の取りこぼしを二方向から詰める。keep-best。
        onPhase("後処理 交互最適化(日ブロック割当)")
        val rAlt = applyAlternatingSoftPolish(state, work, maxSweeps = 4, shouldStop = shouldStop)
        work = rAlt.newSchedule.copy2D()
        logs.addAll(rAlt.logs)

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
                    // [監査(未レビュー領域再監査)] HF66(2.65.0)/BlockRotationPolish(3.84.0)と同型の予算超過対策。
                    //   旧: 日(j)ループ先頭のみで確認していたため、1日分のO(S^2)スキャンが締切後も走り切っていた。
                    if (shouldStop()) break
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
                    if (shouldStop()) break
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
                        // [監査(未レビュー領域再監査)] O(S^2)内側スキャンにも締切確認を追加（HF66/BlockRotationPolishと同型）。
                        if (shouldStop()) break
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
                            // [#5 差分前フィルタ] 同 sgrp かつ同 ssk の2者ブロック交換のみ前判定。
                            val canPre = p.sgrp[i] == p.sgrp[i2] && p.ssk[i] == p.ssk[i2]
                            val preP = if (canPre) staffPacked(p, work, i) + staffPacked(p, work, i2) else 0L
                            for (t in 0 until w) { val tmp = work[i][j + t]; work[i][j + t] = work[i2][j + t]; work[i2][j + t] = tmp }
                            if (canPre) {
                                val postP = staffPacked(p, work, i) + staffPacked(p, work, i2)
                                if (postP >= preP) { for (t in 0 until w) { val tmp = work[i][j + t]; work[i][j + t] = work[i2][j + t]; work[i2][j + t] = tmp }; skipped++; continue }
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
                        if (shouldStop()) break   // [予算ガード] 締切後は O(cand^3) の全候補フル評価を走り切らせない(HF66=2.65.0と同方針)。
                        if (ai !in anchorStaff) continue
                        for (bi in cand) {
                            if (shouldStop()) break   // [予算ガード] 内側スキャンでも締切確認しバーストを O(cand) 以内に抑える。
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
                                // [#5 差分前フィルタ] 同 sgrp かつ同 ssk の手のみ前判定(群/スキル群/被覆/pref不変
                                //   →関与3名の packed が改善しなければ全体目的も改善しえない)。採用はフル評価が担う=安全。
                                val canPre = p.sgrp[ai] == p.sgrp[bi] && p.sgrp[bi] == p.sgrp[ci] &&
                                    p.ssk[ai] == p.ssk[bi] && p.ssk[bi] == p.ssk[ci]
                                val preP = if (canPre) staffPacked(p, work, ai) + staffPacked(p, work, bi) + staffPacked(p, work, ci) else 0L
                                for (t in 0 until w) { work[ai][j + t] = sb[t]; work[bi][j + t] = sc[t]; work[ci][j + t] = sa[t] }
                                if (canPre) {
                                    val postP = staffPacked(p, work, ai) + staffPacked(p, work, bi) + staffPacked(p, work, ci)
                                    if (postP >= preP) { for (t in 0 until w) { work[ai][j + t] = sa[t]; work[bi][j + t] = sb[t]; work[ci][j + t] = sc[t] }; skipped++; continue }
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
        // [一括修正] dow0 は Problem.dow0（目的関数 weekly と同一ソース）を使う（旧: ここで再パース＝重複計算）。
        val dow0 = p.dow0
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
                    // [監査(未レビュー領域再監査)] O(S^2)内側スキャンにも締切確認を追加。
                    if (shouldStop()) break
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
        // [一括修正] 休 index は Problem.restIdx（目的関数 weekly と同一解決・未発見時は 0 フォールバック）を使う。
        //   旧: ローカル indexOfFirst は未発見で -1 ＝全シフトを勤務扱いし、目的関数と別の指標を最小化していた（latent）。
        val restIdx = p.restIdx
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
                    // [監査(未レビュー領域再監査)] O(S^2)内側スキャンにも締切確認を追加。
                    if (shouldStop()) break
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

    /**
     * [ソフト研磨・weekly（曜日平準化）＝長方形交換] weekly は「職員が特定の曜日にばかり勤務する」偏りで、
     * L1偏差（`weeklyDevOfBucket`＝各曜日の勤務数の round(平均) からの偏差和）で評価される。**同日2者スワップ
     * （CyclicSwap / equalize 系）は勤務↔勤務では曜日別の勤務/休が変わらず weekly をほぼ動かせない**（勤務種類が
     * 入れ替わるだけで、どちらの職員も「その曜日に勤務している」事実は不変）。これが「weekly の研磨ができていない」
     * 実害の根本（実機ログで weekly=56＝SOFT 残差の最大級）。
     *
     * そこで **被覆保存の 2職員×2日 長方形交換** を導入する: 職員 i が「過剰曜日の日 j1 で勤務(シフト x)・過少曜日の
     * 日 j2 で休」、相手 i' が「j1 で休・j2 で勤務(シフト y)」のとき、両者の j1/j2 を丸ごと入替える
     * （i: j1→休/j2→y、i': j1→x/j2→休）。各日の各シフト人数は保存される（j1 の x は i→i'、j2 の y は i'→i へ移るだけ）
     * ため covU/covO・群レンジ・pref は不変で、i の勤務が過剰曜日→過少曜日へ移動して weekly が下がる。fair（群内シフト
     * 回数）や low/high/apt/c2 など per-staff 族も副次的に動く。**採否は実目的関数 isBetter のみ**（hard→total→weighted、
     * total は weekly/fair を含む）＝退化なし（keep-best）。weekly>0 の職員のみ起点＋first-improvement で空探索は即終了。
     * 変更セルは wish 固定なら不動（4セルとも movable ガード）。covO/c42/c2 など per-day 族は同日 CyclicSwap（isBetter）が
     * 既に最適に研磨済みのため本パスの対象外（2.49.0 の「専用パスは冗長」の結論を踏襲）。
     */
    fun applyWeeklyRebalancePolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 2, shouldStop: () -> Boolean = { false }): CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        val rest = p.restIdx
        fun movable(i: Int, j: Int) = p.wish[i][j] < 0
        fun weekdayOf(j: Int) = (p.dow0 + j) % 7
        fun wdBucket(i: Int): IntArray {
            val wd = IntArray(7)
            for (j in 0 until p.T) { val k = work[i][j]; if (k != rest && k in 0 until p.K) wd[weekdayOf(j)]++ }
            return wd
        }
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            for (i in 0 until p.S) {
                if (shouldStop()) break
                val wd = wdBucket(i)
                if (weeklyDevOfBucket(wd) == 0) continue
                var sum = 0; for (w in wd) sum += w
                val tgt = Math.round(sum.toDouble() / 7.0).toInt()
                // 最も過剰な曜日（勤務が多すぎ）と最も過少な曜日（少なすぎ）を1つずつ狙う。
                var wOver = -1; var wUnder = -1; var maxOver = 0; var maxUnder = 0
                for (w in 0 until 7) {
                    if (wd[w] - tgt > maxOver) { maxOver = wd[w] - tgt; wOver = w }
                    if (tgt - wd[w] > maxUnder) { maxUnder = tgt - wd[w]; wUnder = w }
                }
                if (wOver < 0 || wUnder < 0) continue
                // i が過剰曜日に勤務している日(movable, 非休) / 過少曜日に休んでいる日(movable)。
                val overDays = (0 until p.T).filter { weekdayOf(it) == wOver && movable(i, it) && work[i][it] != rest && work[i][it] in 0 until p.K }
                val underDays = (0 until p.T).filter { weekdayOf(it) == wUnder && movable(i, it) && work[i][it] == rest }
                var done = false
                for (j1 in overDays) {
                    if (done || shouldStop()) break
                    val x = work[i][j1]
                    for (j2 in underDays) {
                        if (done) break
                        for (ip in 0 until p.S) {
                            if (ip == i) continue
                            // 相手 i' は j1 で休・j2 で勤務(非休)、両日 movable。被覆保存には i'←x(j1), i←y(j2) が担当可であること。
                            if (!movable(ip, j1) || !movable(ip, j2)) continue
                            if (work[ip][j1] != rest) continue
                            val y = work[ip][j2]
                            if (y == rest || y !in 0 until p.K) continue
                            if (!p.canDo(ip, x) || !p.canDo(i, y)) continue
                            // 長方形交換を適用（被覆保存）→ フル評価 → 改善時のみ採用、不採用なら完全巻き戻し。
                            work[i][j1] = rest; work[i][j2] = y; work[ip][j1] = x; work[ip][j2] = rest
                            val rep = UnifiedViolationChecker.check(state, work)
                            if (isBetter(rep, bestRep)) { bestRep = rep; applied++; improved = true; done = true; break }
                            work[i][j1] = x; work[i][j2] = rest; work[ip][j1] = rest; work[ip][j2] = y
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "WeeklyRebalance",
            message = "曜日平準化(長方形交換): total ${before.total}->${bestRep.total} 採用${applied}回"))
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

    /** 職員 i の c1 fire 総数（全 cons1・canDo ガード。checker/MirrorCore と同一のスライド窓意味論）。 */
    private fun c1RowFires(p: Problem, work: Array<IntArray>, i: Int): Int {
        var fires = 0
        for (c in p.cons1) {
            val x = c.shiftIdx; val d = c.day1; val n = c.day2
            if (x !in 0 until p.K || d <= 0 || !p.canDo(i, x)) continue
            var w = 0
            while (w <= p.T - d) {
                var z = 0
                for (l in 0 until d) if (work[i][w + l] == x) z++
                if (z < n) fires++
                w++
            }
        }
        return fires
    }

    /**
     * [ソフト研磨・C1] 期間要件 cons1（D日窓にシフトXをN回以上・職員ごと）の研磨。
     * c1不足の (職員 i, 窓) を見つけ、その窓内で i が X でない日 j に対し、その日に X をしている提供者 i' と
     * **同日スワップ**（i←X, i'←iの旧シフト＝被覆不変・HARD維持）して i の X を増やす。実目的関数で評価し
     * 改善時のみ採用（keep-best＝退化なし）。汎用循環交換と違い**c1不足の窓に的を絞る**ので c1 を効率的に削る。
     * [E11/多人数ブロック移動を反映] 同日スワップの直接相手 i' が見つからない/不採用のときは諦めず、
     * i を X へ直接動かし、空いた旧シフト a の穴を `findCovUChain`（covU の玉突き連鎖）と同じ機構で
     * 埋め直す（a に need1 が無い/余裕があるなら連鎖不要でそのまま採用判定）。i の移動＋連鎖手をまとめて
     * 1候補として実目的関数で評価し、改善時のみ採用（不採用時は連鎖手も含め正しく全巻き戻し）。
     *
     * [C1研磨アルゴリズムの再設計/回数保存移設の追加] 手A(同日スワップ)/手B(直接移動+連鎖)はどちらも
     * 「i の X 回数を+1する」count-changing 手しか生成できない。golden_state の残差解剖(Python実測)では
     * c1=115 fires のうち relocation-only=48（休 fires の80%が個人別回数の下限=上限で固定された職員由来）
     * は、X追加が low/high(90/45)>c1(4×窓数)で必ず isBetter に棄却され、**i自身のXを余剰位置→不足窓へ
     * 移す回数保存の移設**だけが唯一の改善手と判明（行内2日swapの貪欲シムで c1 115→62, -46%）。
     * 現行手A/Bにこの移設プリミティブが無い欠落を埋めるため、手A(同日交換)の直後・手B(直接移動)の前に
     * 保存性の強い順で2手を追加する:
     *   手R1=鏡像長方形（i=[X@j1,b@j]↔i'=[b@j1,X@j]の4セル交換）: 両職員の回数と日別人数が両方保存
     *        （groupViol/pref/low/high/apt/c2/covU/covO/c41系まで構造的不変）＝isBetterはc1/c3系/weekly
     *        だけの勝負になり採用されやすい最も安全な移設。
     *   手R2=自己2日swap（i の X@j1 ↔ b@j）: i の回数は保存（low/high/apt/c2/pref/groupViol不変）だが
     *        日別人数が変わるため、離脱側2箇所を p.covUCell（source of truth）で事前除外してから適用。
     * どちらも c3n(HARD) は p.makesForbiddenRun で事前枝刈り（見逃しても isBetter が最終拒否＝安全側）。
     * 採否は既存と同じ isBetter(hard→total→weighted) の keep-best のみ＝退化不能・HF77非該当（重み不変）。
     * add-fixable（追加が唯一の解の局面）は既存手A/Bの担当のまま＝手クラスが互いに素で冗長を作らない。
     */
    fun applyC1WindowPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0x1C1L): CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        var aRect = 0; var aSelf = 0
        if (p.cons1.isEmpty()) {
            return CyclicSwapResult(work, before.total, bestRep.total, 0,
                listOf(MirrorLog(tag = "C1Polish", message = "cons1なし=スキップ")))
        }
        val rng = Random(seed)
        fun movable(i: Int, j: Int) = p.wish[i][j] < 0
        // [C1研磨・手B強化] staff i2 が shift x2 について day を含むいずれかの窓で不足しているか（全cons1横断）。
        //   手B(findCovUChain の玉突き連鎖)の候補選定に c1Pref として渡し、「連鎖に組み込む相手が、たまたま
        //   その相手自身のc1不足も一緒に解消する」候補を優先させる（並べ替えのみ・安全条件は不変・探索の
        //   正しさは常に isBetter が最終担保）。
        fun c1Deficient(i2: Int, x2: Int, day: Int): Boolean {
            if (day !in 0 until p.T) return false
            for (c2 in p.cons1) {
                if (c2.shiftIdx != x2 || c2.day1 <= 0) continue
                if (inDeficientC1Window(p, work, i2, x2, c2.day1, c2.day2, day)) return true
            }
            return false
        }
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
                    // [移設ドナー] i 自身の X 保有日のうち「抜いても this ルールの窓が新規に不足化しない」余剰位置。
                    //   盤面が変わるたび(i,x)単位で無効化し次の j で再構築する（遅延キャッシュ）。
                    var donorsCache: List<Int>? = null
                    fun donors(): List<Int> = donorsCache ?: (0 until p.T).filter { j1 ->
                        work[i][j1] == x && movable(i, j1) && run {
                            var wStart = maxOf(0, j1 - d + 1); val wEnd = minOf(j1, p.T - d)
                            var surplus = true
                            while (wStart <= wEnd) {
                                var z = 0
                                for (l in 0 until d) if (work[i][wStart + l] == x) z++
                                if (z <= n) { surplus = false; break }   // 閾値ちょうど以下=抜くと新規fire→保守的に除外
                                wStart++
                            }
                            surplus
                        }
                    }.also { donorsCache = it }
                    for (j in 0 until p.T) {
                        // [監査(未レビュー領域再監査)] このjループはi2走査に加えfindCovUChainのBFSも伴い重い
                        //   （HF66/BlockRotationPolishと同型の予算超過対策として日ごとにも確認）。
                        if (shouldStop()) break
                        if (work[i][j] == x || !movable(i, j)) continue
                        if (!inDeficientC1Window(p, work, i, x, d, n, j)) continue
                        val a = work[i][j]                                  // i の旧シフト
                        var done = false
                        for (i2 in 0 until p.S) {
                            if (i2 == i || work[i2][j] != x || !movable(i2, j) || !p.canDo(i2, a)) continue
                            work[i][j] = x; work[i2][j] = a                 // 同日スワップ（被覆不変）
                            val rep = UnifiedViolationChecker.check(state, work)
                            if (isBetter(rep, bestRep)) { bestRep = rep; applied++; improved = true; done = true; break }
                            work[i][j] = a; work[i2][j] = x                 // 巻き戻し
                        }
                        if (done) { donorsCache = null; continue }

                        // [手R1] 鏡像長方形: i=[X@j1,a@j] ↔ i2=[a@j1,X@j]。回数・日別人数とも完全保存
                        //   （i2 は既に保有しているシフトしか持たない＝canDo自動成立だが規律として明示検査する）。
                        val fires0 = c1RowFires(p, work, i)
                        for (j1 in donors()) {
                            if (done || shouldStop()) break
                            if (j1 == j) continue
                            work[i][j1] = a; work[i][j] = x
                            val gain = fires0 - c1RowFires(p, work, i)
                            work[i][j1] = x; work[i][j] = a                 // 判定用の一時変更は必ず復元
                            if (gain <= 0) continue
                            for (i2 in 0 until p.S) {
                                if (done || shouldStop()) break
                                if (i2 == i) continue
                                if (work[i2][j1] != a || work[i2][j] != x) continue      // 完全鏡像の相手のみ
                                if (!movable(i2, j1) || !movable(i2, j)) continue
                                if (!p.canDo(i, x) || !p.canDo(i2, a)) continue           // 構造上恒真・規律として明示
                                work[i][j1] = a; work[i][j] = x; work[i2][j1] = x; work[i2][j] = a
                                val bad3n = p.makesForbiddenRun(work, i, j1, a) || p.makesForbiddenRun(work, i, j, x) ||
                                    p.makesForbiddenRun(work, i2, j1, x) || p.makesForbiddenRun(work, i2, j, a)
                                if (!bad3n) {
                                    val rep = UnifiedViolationChecker.check(state, work)
                                    if (isBetter(rep, bestRep)) {
                                        bestRep = rep; applied++; aRect++; improved = true; done = true
                                        donorsCache = null
                                        break
                                    }
                                }
                                if (!done) { work[i][j1] = x; work[i][j] = a; work[i2][j1] = a; work[i2][j] = x }
                            }
                        }
                        if (done) continue

                        // [手R2] 自己2日swap: i の X@j1 ↔ a@j（回数保存＝low/high/apt/c2不変。日別人数が
                        //   変わるため離脱側2箇所を covUCell(source of truth)で事前除外してから適用）。
                        //   a が normalizeSchedule 由来の -1(範囲外/未割当) なら「本物のシフト」ではないため
                        //   R2(自己内の付け替え)の対象外とする（work[i][j1] へ -1 を書き込む不正な手を防ぐ。
                        //   手A/手Bは a=-1 でも canDo(-1)=false / findCovUChain の範囲ガードで元々安全なので
                        //   この場合も手Bへは進める＝ここは continue でなく R2 ブロックだけを囲む）。
                        if (a in 0 until p.K) {
                            for (j1 in donors()) {
                                if (done || shouldStop()) break
                                if (j1 == j) continue
                                work[i][j1] = a; work[i][j] = x
                                val gain = fires0 - c1RowFires(p, work, i)
                                work[i][j1] = x; work[i][j] = a
                                if (gain <= 0) continue
                                var cx = 0; var ca = 0
                                for (s in 0 until p.S) { if (work[s][j1] == x) cx++; if (work[s][j] == a) ca++ }
                                if (p.covUCell(x, j1, cx - 1) > p.covUCell(x, j1, cx)) continue   // X の j1 離脱で covU 悪化
                                if (p.covUCell(a, j, ca - 1) > p.covUCell(a, j, ca)) continue      // a の j 離脱で covU 悪化
                                work[i][j1] = a; work[i][j] = x
                                val bad3n = p.makesForbiddenRun(work, i, j1, a) || p.makesForbiddenRun(work, i, j, x)
                                if (!bad3n) {
                                    val rep = UnifiedViolationChecker.check(state, work)
                                    if (isBetter(rep, bestRep)) {
                                        bestRep = rep; applied++; aSelf++; improved = true; done = true; donorsCache = null
                                    }
                                }
                                if (!done) { work[i][j1] = x; work[i][j] = a }
                            }
                        }
                        if (done) continue

                        // [E11反映] 直接の交換相手が見つからない/不採用 → i を X へ動かし、空いた a の穴を
                        //   玉突き連鎖で埋め直す（findCovUChain は盤面を変えないため元値を保存して巻き戻せるようにする）。
                        work[i][j] = x
                        // exclude=i: i は既に x へ動かした本人なので、a を埋め戻す候補から除外
                        //   （除外しないと「i が a に戻る」= i の移動そのものを打ち消す退行手をBFSが選びうる）。
                        // c1Pref=c1Deficient: 連鎖の相手選びを「その相手自身のc1不足も一緒に解消するか」で
                        //   優先付け（並べ替えのみ・見つからなければ従来どおり）。
                        val chain = findCovUChain(p, work, a, j, rng, exclude = i,
                            c1Pref = { s2, sh, dy -> c1Deficient(s2, sh, dy) })
                        val oldVals = chain?.let { ch -> IntArray(ch.size) { work[ch[it][0]][ch[it][1]] } }
                        chain?.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
                        val rep = UnifiedViolationChecker.check(state, work)
                        if (isBetter(rep, bestRep)) {
                            bestRep = rep; applied++; improved = true
                            donorsCache = null
                        } else {
                            if (chain != null && oldVals != null) for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
                            work[i][j] = a
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "C1Polish",
            message = "期間要件(c1)研磨: c1 ${before.breakdown["c1"] ?: 0}->${bestRep.breakdown["c1"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回(鏡像:$aRect 自己:$aSelf)" +
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
                        // [ソフト研磨・候補生成の重み整合] 従来の rangePen は low/high を 3/3 の擬似重みで評価していたが、
                        //   真の目的関数(Evaluator / staffCountPenaltyAt / UnifiedViolationChecker)は low=90・high=45・apt=1。
                        //   proxy が重い low/high を apt(重み1)と同格(3対1)に扱うと Hungarian が「軽い apt を直すため重い
                        //   low/high を犠牲にする」候補を生みやすく、良候補を生み損ねる(CLAUDE.md 既知・測定待ち)。
                        //   proxy を目的関数と同一の 90/45/1 に整合させ、生成候補を真の目的へ寄せる。採否は従来どおり
                        //   keep-best(isBetter@UnifiedViolationChecker)が担うため退化なし＝スコアリング不変。
                        fun rangePen(x: Int) = (if (lo != Int.MIN_VALUE) 90L * maxOf(0, lo - x) else 0L) + 45L * maxOf(0, x - hi)
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

    /**
     * [ソフト研磨・交互最適化(Alternating Optimization / 交代最適化)] 全変数を同時に解かず「1ブロックずつ順に最適化
     * して巡回する」座標降下法（block coordinate descent）をソフト制約研磨に導入する新アルゴリズム。ブロック＝各日(列):
     * その日の (シフト人数=被覆) を固定したまま、希望未固定(wish<0)の職員を「個人別回数(range 90/45)・適切回数(apt 1)・
     * **曜日平準化(weekly 1)**」の限界費用が最小になるよう **最小費用割当(Hungarian＝割当LP＝凸最適化)** で最適再配置し、
     * 日 j を 0..T-1 と巡回して 1スイープで1日も変化しなくなるまで（＝座標降下の不動点）反復する。
     *
     * 既存 `applyDayAssignmentPolish`（range/apt のみ・単発）を、①weekly を費用に含め ②反復収束（交互）まで一般化した
     * もの。weekly を費用に入れる意味＝その日の「休スロット」を誰に割り当てるかで各職員の曜日別勤務数が変わる（被覆は不変）。
     * 「その曜日に働き過ぎの職員へ休を、少なすぎる職員へ勤務を」割り当てる候補を Hungarian が同日内で**同時最適**に生成し、
     * 曜日偏りを直す。同日内の最適再配置＝rectangle（3.197.0, クロス日の2職員×2日）とは別種の被覆保存手＝相補的。
     * 採否は実目的関数 isBetter（hard→total→weighted, keep-best）＝退化なし。fair 等の他 soft は isBetter が担保する
     * （費用に無い族も採用判定で悪化しないことを保証）。純 Kotlin 後処理＝ネイティブ hot-path 非干渉（parity 影響なし）。
     */
    fun applyAlternatingSoftPolish(state: MagiState, schedule: Array<IntArray>, maxSweeps: Int = 4, shouldStop: () -> Boolean = { false }): DayAssignResult {
        val p = Problem(state)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        val rest = p.restIdx
        fun aptTarget(i: Int, k: Int): Int? {
            val g = state.staff.getOrNull(i)?.groupIdx ?: return null
            return state.groupShiftApt.getOrNull(g)?.getOrNull(k)?.trim()?.toIntOrNull()
        }
        // weekly の wd バケット（職員×曜日の勤務数）と目標 round(勤務日/7)。被覆保存の再配置ごとに更新。
        fun wdOf(i: Int): IntArray {
            val wd = IntArray(7)
            for (j in 0 until p.T) { val k = work[i][j]; if (k != rest && k in 0 until p.K) wd[(p.dow0 + j) % 7]++ }
            return wd
        }
        fun tgtOf(wd: IntArray): Int { var s = 0; for (w in wd) s += w; return Math.round(s.toDouble() / 7.0).toInt() }
        var wd = Array(p.S) { wdOf(it) }
        var wdTgt = IntArray(p.S) { tgtOf(wd[it]) }
        fun cnt(): Array<IntArray> = countMatrix(p, work)
        var counts = cnt()
        var sweep = 0
        var lastSweep = 0
        while (sweep < maxSweeps) {
            if (shouldStop()) break
            var changedInSweep = false
            for (j in 0 until p.T) {
                if (shouldStop()) break
                val free = (0 until p.S).filter { i -> p.wish[i][j] < 0 }
                if (free.size < 2) continue
                val slots = free.map { work[it][j] }
                val n = free.size
                val wdj = (p.dow0 + j) % 7
                val costM = Array(n) { r ->
                    val i = free[r]
                    LongArray(n) { c ->
                        val k = slots[c]
                        if (k !in 0 until p.K || !p.canDo(i, k)) MinCostAssignment.INF
                        else {
                            val x0 = counts[i][k] - (if (work[i][j] == k) 1 else 0)   // この日を除いた現状カウント
                            val x1 = x0 + 1
                            val lo = p.rangeLo[i][k]
                            val hi = effectiveHi(p, i, k)
                            // range/apt は applyDayAssignmentPolish と同一の目的関数整合 proxy（90/45/1）。
                            fun rangePen(x: Int) = (if (lo != Int.MIN_VALUE) 90L * maxOf(0, lo - x) else 0L) + 45L * maxOf(0, x - hi)
                            var cost = rangePen(x1) - rangePen(x0)
                            val t = aptTarget(i, k)
                            if (t != null) cost += (kotlin.math.abs(x1 - t) - kotlin.math.abs(x0 - t)).toLong()
                            // weekly 限界費用: 当日を k(=勤務 or 休)にしたときの職員 i の曜日 wdj バケットの L1 偏差変化（重み1）。
                            val curWork = if (work[i][j] != rest && work[i][j] in 0 until p.K) 1 else 0
                            val newWork = if (k != rest) 1 else 0
                            if (curWork != newWork) {
                                val base = wd[i][wdj] - curWork              // 当日を除いた曜日カウント
                                val tgt = wdTgt[i]
                                cost += (kotlin.math.abs((base + newWork) - tgt) - kotlin.math.abs((base + curWork) - tgt)).toLong()
                            }
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
                if (isBetter(rep, bestRep)) {
                    work = cand; bestRep = rep; counts = cnt()
                    wd = Array(p.S) { wdOf(it) }; wdTgt = IntArray(p.S) { tgtOf(wd[it]) }
                    applied++; changedInSweep = true
                }
            }
            sweep++; lastSweep = sweep
            if (!changedInSweep) break   // 座標降下の不動点＝この巡回で1日も改善しない
        }
        val logs = listOf(MirrorLog(tag = "AltOptPolish",
            message = "交互最適化(日ブロック・weekly込み割当): total ${before.total}->${bestRep.total} 採用${applied}日 (${lastSweep}スイープ)"))
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

    fun applyHF66IntraStaffRedistribution(state: MagiState, schedule: Array<IntArray>, maxMoves: Int = 30, shouldStop: () -> Boolean = { false }, deadlineMs: Long = Long.MAX_VALUE): HF66Result {
        val p = Problem(state)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var current = before
        var moves = 0
        var shortageMoves = 0
        var capacityMoves = 0
        var rollback = 0
        // [残予算ガード] shouldStop(全体締切)に加え HF66 専用の時間上限(deadlineMs)も尊重する。1手の全候補
        //   スキャンは候補ごとにフル check するため高コスト。手ごとだけでなく内側ループでも締切を確認し、
        //   締切後に1手分のスキャンを走り切って後段の研磨パスを押し出す(=予算超過で打ち切らせる)のを防ぐ。
        //   keep-best のため途中中断しても解は退化しない(採用は isBetter な bestMove のみ)。
        fun outOfTime() = shouldStop() || System.currentTimeMillis() >= deadlineMs

        while (moves < maxMoves) {
            if (outOfTime()) break
            val counts = countMatrix(p, work)
            var bestMove: MoveCandidate? = null
            var bestReport: ViolationReport? = null
            scan@ for (i in 0 until p.S) {
                if (outOfTime()) break@scan
                val lows = ArrayList<Int>()
                val highs = ArrayList<Int>()
                for (k in 0 until p.K) {
                    if (p.canDo(i, k) && p.rangeLo[i][k] != Int.MIN_VALUE && counts[i][k] < p.rangeLo[i][k]) lows.add(k)
                    if (counts[i][k] > effectiveHi(p, i, k)) highs.add(k)
                }
                for (want in lows) for (give in highs) {
                    if (outOfTime()) break@scan
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
        if (moves == 0 && !outOfTime()) {
            val rng = Random(0x66L)
            var t = 0
            while (t < maxMoves) {
                if (outOfTime()) break
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
        val advice = if (issues.isEmpty()) "" else "設定(担当範囲), 希望, 必要人数, 連勤禁止条件を確認してください"
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
    // ============================================================================
    // [#5 差分前フィルタ] 職員 i の packed メトリクス = (hard:c3n件数) を最上位、(total:変化しうる全family
    // の件数) を中位、(weighted) を下位に詰めた単一 Long（isBetter の hard→total→weighted と同順）。
    // 研磨手で動きうる per-staff family のうち主要分を集計: c1(4)/c2(1)/c3(3)/c3n(HARD7000)/c3m(2)/c3mn(12)/
    // low(90)/high(45)。※apt/fair/weekly(各1) は本 packed に含めない＝それら**のみ**を改善する手はこの前フィルタで
    // こぼす(研磨が弱まるだけ=keep-best 安全、誤採用や悪化は起きない)。関与職員が sgrp も ssk も同一なら、群(c41/c42)・スキル群(c41s/c42s)・被覆(covU/covO)・
    // pref(希望固定セルは不動) は全て不変。よって関与職員のこの packed が改善しなければ全体目的も改善しえず、
    // その手はフル評価をスキップしてよい(前フィルタ)。採用判定は従来どおりフル評価 isBetter が担う＝安全。
    // 1職員あたり各レベルは小さく、関与2-3名の和でも桁跨ぎしない(hardレベル:1e15, totalレベル:1e9)。
    private fun staffPacked(p: Problem, sched: Array<IntArray>, i: Int): Long {
        var hard = 0L; var total = 0L; var wgt = 0L
        val cnt = IntArray(p.K)                                   // 期間内シフト回数(c2/low/high 用)
        for (j in 0 until p.T) { val k = sched[i][j]; if (k in 0 until p.K) cnt[k]++ }
        for (c in p.cons1) {                                      // c1: d日窓で shiftIdx が day2 回未満
            if (!p.canDo(i, c.shiftIdx)) continue
            var j = 0
            while (j <= p.T - c.day1) {
                var z = 0
                for (l in 0 until c.day1) if (sched[i][j + l] == c.shiftIdx) z++
                if (z < c.day2) { total++; wgt += 4 }
                j++
            }
        }
        for (c in p.cons2) if (p.canDo(i, c.shiftIdx) && cnt[c.shiftIdx] < c.count) { total++; wgt += 1 } // c2
        for (k in 0 until p.K) {                                  // low/high: 回数レンジ(不足/超過「量」を加算)
            val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]; val n = cnt[k]
            if (lo != Int.MIN_VALUE && lo != 0 && p.canDo(i, k) && n < lo) { val d = (lo - n).toLong(); total += d; wgt += d * 90 }
            if (hi != Int.MAX_VALUE && n > hi) { val d = (n - hi).toLong(); total += d; wgt += d * 45 }
        }
        val c3nC = c3FamCount(p, sched, i, p.cons3n, true)        // c3n は HARD
        val c3C = c3FamCount(p, sched, i, p.cons3, false)
        val c3mC = c3FamCount(p, sched, i, p.cons3m, false)
        val c3mnC = c3FamCount(p, sched, i, p.cons3mn, true)
        hard += c3nC
        total += c3nC + c3C + c3mC + c3mnC
        wgt += c3nC * 7000 + c3C * 3 + c3mC * 2 + c3mnC * 12
        return hard * 1_000_000_000_000_000L + total * 1_000_000_000L + wgt
    }

    private fun c3FamCount(p: Problem, sched: Array<IntArray>, i: Int, list: List<C3>, forbidden: Boolean): Long {
        var c = 0L
        for (con in list) {
            val seq = con.seq; val d = seq.size
            if (d == 0 || d > p.T) continue
            var j = 0
            while (j <= p.T - d) {
                if (sched[i][j] == seq[0]) {
                    var z = 0
                    for (l in 1 until d) if (sched[i][j + l] == seq[l]) z++
                    val fire = if (forbidden) z == d - 1 else z < d - 1
                    if (fire) c++
                }
                j++
            }
        }
        return c
    }
}
