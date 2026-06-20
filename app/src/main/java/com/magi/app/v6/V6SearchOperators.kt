package com.magi.app.v6

import java.util.Random
import kotlin.math.exp
import kotlin.math.max

// [リファクタ Phase2] V6NativeOptimizer から抽出した純粋ヘルパ群（オブジェクト状態に非依存・
// 引数 Problem/DeltaEvaluator/GlsPenalty/Random のみ）。ターゲット修正(findXxx)と生スコアの
// 受理判定・差分ユーティリティ。同一パッケージのトップレベル internal 関数なので呼び出し側は不変。

// ── findXxx: ターゲット型 single-cell 修正を [i, j, newK] で返す（無ければ null）。
// 集合構築は 2 パス（個数を数えてから N 番目を選ぶ）で ArrayList/filter を排し GC 圧を下げる。
// ALNS の直接評価アームから eval+cur へ copy2D なしで適用される。

internal fun findCovOFix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    if (p.T == 0 || p.K == 0) return null
    val j = rng.nextInt(p.T)
    var overK = -1; var maxOver = 0
    for (k in 0 until p.K) {
        val lo = p.need1[k][j]; if (lo < 0) continue
        val hi = if (p.use2 && p.need2[k][j] >= 0) p.need2[k][j] else lo
        val over = eval.countOnDay(k, j) - hi
        if (over > maxOver) { maxOver = over; overK = k }
    }
    if (overK < 0) return null
    var wCnt = 0
    for (i in 0 until p.S) if (eval.at(i, j) == overK && p.wish[i][j] < 0) wCnt++
    if (wCnt == 0) return null
    var pickW = rng.nextInt(wCnt); var i = 0
    for (ii in 0 until p.S) if (eval.at(ii, j) == overK && p.wish[ii][j] < 0) { if (pickW-- == 0) { i = ii; break } }
    var bestNw = -1; var bestDef = Int.MIN_VALUE
    for (k in 0 until p.K) {
        if (k == overK || !p.canDo(i, k)) continue
        val lo = p.need1[k][j]
        val def = if (lo >= 0) lo - eval.countOnDay(k, j) else 0
        if (def > bestDef) { bestDef = def; bestNw = k }
    }
    return if (bestNw >= 0) intArrayOf(i, j, bestNw) else null
}

internal fun findC2Fix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    if (p.cons2.isEmpty()) return null
    val c = p.cons2[rng.nextInt(p.cons2.size)]
    var dCnt = 0
    for (i in 0 until p.S) { if (!p.canDo(i, c.shiftIdx)) continue; if (eval.countForStaff(i, c.shiftIdx) < c.count) dCnt++ }
    if (dCnt == 0) return null
    var pickI = rng.nextInt(dCnt); var stf = 0
    for (i in 0 until p.S) { if (!p.canDo(i, c.shiftIdx)) continue; if (eval.countForStaff(i, c.shiftIdx) < c.count) { if (pickI-- == 0) { stf = i; break } } }
    var dayCnt = 0
    for (j in 0 until p.T) if (eval.at(stf, j) != c.shiftIdx && p.wish[stf][j] < 0) dayCnt++
    if (dayCnt == 0) return null
    var pickJ = rng.nextInt(dayCnt); var day = 0
    for (j in 0 until p.T) if (eval.at(stf, j) != c.shiftIdx && p.wish[stf][j] < 0) { if (pickJ-- == 0) { day = j; break } }
    return intArrayOf(stf, day, c.shiftIdx)
}

internal fun findRangeLowFix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    var cCnt = 0
    for (i in 0 until p.S) for (k in 0 until p.K) { val lo = p.rangeLo[i][k]; if (lo == Int.MIN_VALUE || !p.canDo(i, k)) continue; if (eval.countForStaff(i, k) < lo) cCnt++ }
    if (cCnt == 0) return null
    var pickC = rng.nextInt(cCnt); var rlI = 0; var rlK = 0
    outer@ for (i in 0 until p.S) for (k in 0 until p.K) { val lo = p.rangeLo[i][k]; if (lo == Int.MIN_VALUE || !p.canDo(i, k)) continue; if (eval.countForStaff(i, k) < lo) { if (pickC-- == 0) { rlI = i; rlK = k; break@outer } } }
    var dayCnt = 0
    for (j in 0 until p.T) if (eval.at(rlI, j) != rlK && p.wish[rlI][j] < 0) dayCnt++
    if (dayCnt == 0) return null
    var pickJ = rng.nextInt(dayCnt); var day = 0
    for (j in 0 until p.T) if (eval.at(rlI, j) != rlK && p.wish[rlI][j] < 0) { if (pickJ-- == 0) { day = j; break } }
    return intArrayOf(rlI, day, rlK)
}

internal fun findC41Fix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    if (p.cons41.isEmpty() || p.T == 0) return null
    val c = p.cons41[rng.nextInt(p.cons41.size)]
    val j = rng.nextInt(p.T)
    var cnt = 0
    for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx) cnt++
    return when {
        cnt > c.u -> {
            var wCnt = 0
            for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx && p.wish[i][j] < 0) wCnt++
            if (wCnt == 0) return null
            var pickW = rng.nextInt(wCnt); var ci = 0
            for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx && p.wish[i][j] < 0) { if (pickW-- == 0) { ci = i; break } }
            val allowed41 = p.allowedShiftsForStaff(ci)
            var oCnt = 0; for (ak in allowed41) if (ak != c.shiftIdx) oCnt++
            if (oCnt == 0) return null
            var pickK = rng.nextInt(oCnt); var nwK = 0
            for (ak in allowed41) if (ak != c.shiftIdx) { if (pickK-- == 0) { nwK = ak; break } }
            intArrayOf(ci, j, nwK)
        }
        cnt < c.l -> {
            var aCnt = 0
            for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && eval.at(i, j) != c.shiftIdx && p.wish[i][j] < 0 && p.canDo(i, c.shiftIdx)) aCnt++
            if (aCnt == 0) return null
            var pickA = rng.nextInt(aCnt); var ai = 0
            for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && eval.at(i, j) != c.shiftIdx && p.wish[i][j] < 0 && p.canDo(i, c.shiftIdx)) { if (pickA-- == 0) { ai = i; break } }
            intArrayOf(ai, j, c.shiftIdx)
        }
        else -> null
    }
}

/** c41 のスキルグループ版（ssk + cons41s）。形は findC41Fix と同一。 */
internal fun findC41sFix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    if (p.cons41s.isEmpty() || p.T == 0) return null
    val c = p.cons41s[rng.nextInt(p.cons41s.size)]
    val j = rng.nextInt(p.T)
    var cnt = 0
    for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx) cnt++
    return when {
        cnt > c.u -> {
            var wCnt = 0
            for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx && p.wish[i][j] < 0) wCnt++
            if (wCnt == 0) return null
            var pickW = rng.nextInt(wCnt); var ci = 0
            for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx && p.wish[i][j] < 0) { if (pickW-- == 0) { ci = i; break } }
            val allowed41 = p.allowedShiftsForStaff(ci)
            var oCnt = 0; for (ak in allowed41) if (ak != c.shiftIdx) oCnt++
            if (oCnt == 0) return null
            var pickK = rng.nextInt(oCnt); var nwK = 0
            for (ak in allowed41) if (ak != c.shiftIdx) { if (pickK-- == 0) { nwK = ak; break } }
            intArrayOf(ci, j, nwK)
        }
        cnt < c.l -> {
            var aCnt = 0
            for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && eval.at(i, j) != c.shiftIdx && p.wish[i][j] < 0 && p.canDo(i, c.shiftIdx)) aCnt++
            if (aCnt == 0) return null
            var pickA = rng.nextInt(aCnt); var ai = 0
            for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && eval.at(i, j) != c.shiftIdx && p.wish[i][j] < 0 && p.canDo(i, c.shiftIdx)) { if (pickA-- == 0) { ai = i; break } }
            intArrayOf(ai, j, c.shiftIdx)
        }
        else -> null
    }
}

internal fun findRangeHighFix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    var cCnt = 0
    for (i in 0 until p.S) for (k in 0 until p.K) { val hi = p.rangeHi[i][k]; if (hi == Int.MAX_VALUE) continue; if (eval.countForStaff(i, k) > hi) cCnt++ }
    if (cCnt == 0) return null
    var pickC = rng.nextInt(cCnt); var rhI = 0; var rhK = 0
    outer@ for (i in 0 until p.S) for (k in 0 until p.K) { val hi = p.rangeHi[i][k]; if (hi == Int.MAX_VALUE) continue; if (eval.countForStaff(i, k) > hi) { if (pickC-- == 0) { rhI = i; rhK = k; break@outer } } }
    var dayCnt = 0
    for (j in 0 until p.T) if (eval.at(rhI, j) == rhK && p.wish[rhI][j] < 0) dayCnt++
    if (dayCnt == 0) return null
    var pickJ = rng.nextInt(dayCnt); var day = 0
    for (j in 0 until p.T) if (eval.at(rhI, j) == rhK && p.wish[rhI][j] < 0) { if (pickJ-- == 0) { day = j; break } }
    val allowed = p.allowedShiftsForStaff(rhI)
    var oCnt = 0; for (ak in allowed) if (ak != rhK) oCnt++
    if (oCnt == 0) return null
    var pickK = rng.nextInt(oCnt); var nwK = 0
    for (ak in allowed) if (ak != rhK) { if (pickK-- == 0) { nwK = ak; break } }
    return intArrayOf(rhI, day, nwK)
}

internal fun findC3WantFix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    val list = when {
        p.cons3.isNotEmpty() && p.cons3m.isNotEmpty() -> if (rng.nextBoolean()) p.cons3 else p.cons3m
        p.cons3.isNotEmpty() -> p.cons3
        p.cons3m.isNotEmpty() -> p.cons3m
        else -> return null
    }
    val c = list[rng.nextInt(list.size)]
    val seq = c.seq; val D = seq.size
    if (D < 2 || D > p.T) return null
    val iStart = rng.nextInt(p.S)
    for (di in 0 until p.S) {
        val i = (iStart + di) % p.S
        var j = 0
        while (j <= p.T - D) {
            if (eval.at(i, j) == seq[0]) {
                var miss = 0; var missL = -1
                for (l in 1 until D) {
                    if (eval.at(i, j + l) != seq[l]) { miss++; if (miss > 1) break else missL = l }
                }
                if (miss == 1 && missL >= 0) {
                    val ml = j + missL
                    if (p.wish[i][ml] < 0 && p.canDo(i, seq[missL])) return intArrayOf(i, ml, seq[missL])
                }
            }
            j++
        }
    }
    return null
}

/**
 * 6 種のターゲット修正を一様シャッフル順に試し、最初に見つかった修正を返す（無ければ null）。
 * 1 種が null でも次へフォールスルーするため、違反が少ない近最適解でも毎反復に有効手を当てやすい。
 */
internal fun findTargetedFix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    val order = IntArray(7) { it }
    for (i in 6 downTo 1) { val j = rng.nextInt(i + 1); val t = order[i]; order[i] = order[j]; order[j] = t }
    for (idx in order) {
        val fix = when (idx) {
            0 -> findCovOFix(p, eval, rng)
            1 -> findC2Fix(p, eval, rng)
            2 -> findRangeLowFix(p, eval, rng)
            3 -> findC41Fix(p, eval, rng)
            4 -> findRangeHighFix(p, eval, rng)
            5 -> findC41sFix(p, eval, rng)
            else -> findC3WantFix(p, eval, rng)
        }
        if (fix != null) return fix
    }
    return null
}

/** [GLS] 1 セルの割当変更による penalty 拡張分の差分（変更セルだけで O(1)）。 */
internal fun glsMoveAug(gls: GlsPenalty, i: Int, j: Int, oldK: Int, nwK: Int): Double =
    if (oldK == nwK) 0.0 else gls.lambda * (gls.penaltyOf(i, j, nwK) - gls.penaltyOf(i, j, oldK)).toDouble()

/** DeltaEvaluator 生スコア(hard*1_000_000+soft)の比較。小さいほど良い。 */
internal fun betterScore(a: Long, b: Long): Boolean = a < b

/** DeltaEvaluator 生スコアの SA 受理。hard が +2 超増える手は却下。 */
internal fun acceptWorseScore(a: Long, b: Long, temp: Double, rng: Random): Boolean {
    if (a > b + 2_000_000L) return false
    val delta = (a - b).toDouble()
    return delta <= 0.0 || rng.nextDouble() < exp(-max(0.0, delta) / (200.0 * temp + 1e-9))
}

/**
 * 非改善手の受理判定（生スコア＝hard*1_000_000+soft）。GLS 拡張分(moveAug=候補−現行)を加味する。
 * hard が +2 超増える手は常に却下。Great Deluge は水位以下かつ hard 非増加で受理。
 */
internal fun glsAccept(
    ns: Long, curScore: Long, moveAug: Double, curAug: Double,
    mode: AcceptMode, temp: Double, gdLevel: Double, rng: Random, hardRelax: Double = 0.0,
): Boolean {
    if (ns > curScore + 2_000_000L) return false   // per-step 上限(±2 hard)。振動中も維持し実行不可への暴走を防ぐ。
    return when (mode) {
        AcceptMode.GREAT_DELUGE ->
            (ns.toDouble() + curAug + moveAug) <= gdLevel && (ns / 1_000_000L) <= (curScore / 1_000_000L)
        AcceptMode.SA -> {
            // [戦略的振動] hardRelax>0 の間だけ受理判定の hard 差分を (1-hardRelax) に割引し、実行不可の壁を
            //   越えやすくする。生スコア(ns/curScore)・globalBest は不変＝Δ×フル無関係・解は退化しない
            //   (Python PoC で escape 20/20・実行不可解 0/20 を確認済)。hardRelax=0 で従来と完全一致。
            val delta = if (hardRelax > 0.0) {
                val nh = ns / 1_000_000L; val nsSoft = ns % 1_000_000L
                val ch = curScore / 1_000_000L; val csSoft = curScore % 1_000_000L
                (nh - ch).toDouble() * (1.0 - hardRelax) * 1_000_000.0 + (nsSoft - csSoft).toDouble() + moveAug
            } else {
                (ns - curScore).toDouble() + moveAug
            }
            delta <= 0.0 || rng.nextDouble() < exp(-max(0.0, delta) / (200.0 * temp + 1e-9))
        }
    }
}

/** from と to で異なるセルの flat index(i*T+j) を buf に詰め、件数を返す（ゼロアロケーション）。 */
internal fun diffInto(T: Int, from: Array<IntArray>, to: Array<IntArray>, buf: IntArray): Int {
    var n = 0
    for (i in from.indices) {
        val fr = from[i]; val tr = to[i]
        for (j in 0 until T) if (fr[j] != tr[j]) buf[n++] = i * T + j
    }
    return n
}
