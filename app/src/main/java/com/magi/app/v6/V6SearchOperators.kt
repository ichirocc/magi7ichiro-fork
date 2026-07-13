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
    for (i in 0 until p.S) if (eval.at(i, j) == overK && !p.wishLocked(i, j)) wCnt++
    if (wCnt == 0) return null
    var pickW = rng.nextInt(wCnt); var i = 0
    for (ii in 0 until p.S) if (eval.at(ii, j) == overK && !p.wishLocked(ii, j)) { if (pickW-- == 0) { i = ii; break } }
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
    for (j in 0 until p.T) if (eval.at(stf, j) != c.shiftIdx && !p.wishLocked(stf, j)) dayCnt++
    if (dayCnt == 0) return null
    var pickJ = rng.nextInt(dayCnt); var day = 0
    for (j in 0 until p.T) if (eval.at(stf, j) != c.shiftIdx && !p.wishLocked(stf, j)) { if (pickJ-- == 0) { day = j; break } }
    return intArrayOf(stf, day, c.shiftIdx)
}

internal fun findRangeLowFix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    var cCnt = 0
    for (i in 0 until p.S) for (k in 0 until p.K) { val lo = p.rangeLo[i][k]; if (lo == Int.MIN_VALUE || !p.canDo(i, k)) continue; if (eval.countForStaff(i, k) < lo) cCnt++ }
    if (cCnt == 0) return null
    var pickC = rng.nextInt(cCnt); var rlI = 0; var rlK = 0
    outer@ for (i in 0 until p.S) for (k in 0 until p.K) { val lo = p.rangeLo[i][k]; if (lo == Int.MIN_VALUE || !p.canDo(i, k)) continue; if (eval.countForStaff(i, k) < lo) { if (pickC-- == 0) { rlI = i; rlK = k; break@outer } } }
    var dayCnt = 0
    for (j in 0 until p.T) if (eval.at(rlI, j) != rlK && !p.wishLocked(rlI, j)) dayCnt++
    if (dayCnt == 0) return null
    var pickJ = rng.nextInt(dayCnt); var day = 0
    for (j in 0 until p.T) if (eval.at(rlI, j) != rlK && !p.wishLocked(rlI, j)) { if (pickJ-- == 0) { day = j; break } }
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
            for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx && !p.wishLocked(i, j)) wCnt++
            if (wCnt == 0) return null
            var pickW = rng.nextInt(wCnt); var ci = 0
            for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx && !p.wishLocked(i, j)) { if (pickW-- == 0) { ci = i; break } }
            val allowed41 = p.allowedShiftsForStaff(ci)
            var oCnt = 0; for (ak in allowed41) if (ak != c.shiftIdx) oCnt++
            if (oCnt == 0) return null
            var pickK = rng.nextInt(oCnt); var nwK = 0
            for (ak in allowed41) if (ak != c.shiftIdx) { if (pickK-- == 0) { nwK = ak; break } }
            intArrayOf(ci, j, nwK)
        }
        cnt < c.l -> {
            var aCnt = 0
            for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && eval.at(i, j) != c.shiftIdx && !p.wishLocked(i, j) && p.canDo(i, c.shiftIdx)) aCnt++
            if (aCnt == 0) return null
            var pickA = rng.nextInt(aCnt); var ai = 0
            for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && eval.at(i, j) != c.shiftIdx && !p.wishLocked(i, j) && p.canDo(i, c.shiftIdx)) { if (pickA-- == 0) { ai = i; break } }
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
            for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx && !p.wishLocked(i, j)) wCnt++
            if (wCnt == 0) return null
            var pickW = rng.nextInt(wCnt); var ci = 0
            for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && eval.at(i, j) == c.shiftIdx && !p.wishLocked(i, j)) { if (pickW-- == 0) { ci = i; break } }
            val allowed41 = p.allowedShiftsForStaff(ci)
            var oCnt = 0; for (ak in allowed41) if (ak != c.shiftIdx) oCnt++
            if (oCnt == 0) return null
            var pickK = rng.nextInt(oCnt); var nwK = 0
            for (ak in allowed41) if (ak != c.shiftIdx) { if (pickK-- == 0) { nwK = ak; break } }
            intArrayOf(ci, j, nwK)
        }
        cnt < c.l -> {
            var aCnt = 0
            for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && eval.at(i, j) != c.shiftIdx && !p.wishLocked(i, j) && p.canDo(i, c.shiftIdx)) aCnt++
            if (aCnt == 0) return null
            var pickA = rng.nextInt(aCnt); var ai = 0
            for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && eval.at(i, j) != c.shiftIdx && !p.wishLocked(i, j) && p.canDo(i, c.shiftIdx)) { if (pickA-- == 0) { ai = i; break } }
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
    for (j in 0 until p.T) if (eval.at(rhI, j) == rhK && !p.wishLocked(rhI, j)) dayCnt++
    if (dayCnt == 0) return null
    var pickJ = rng.nextInt(dayCnt); var day = 0
    for (j in 0 until p.T) if (eval.at(rhI, j) == rhK && !p.wishLocked(rhI, j)) { if (pickJ-- == 0) { day = j; break } }
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
                    if (!p.wishLocked(i, ml) && p.canDo(i, seq[missL])) return intArrayOf(i, ml, seq[missL])
                }
            }
            j++
        }
    }
    return null
}

/**
 * 8 種のターゲット修正(covO/c2/low/c41/high/c41s/c3want/apt)を一様シャッフル順に試し、最初に見つかった修正を返す（無ければ null）。
 * 1 種が null でも次へフォールスルーするため、違反が少ない近最適解でも毎反復に有効手を当てやすい。
 */
internal fun findTargetedFix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    val order = IntArray(8) { it }
    for (i in 7 downTo 1) { val j = rng.nextInt(i + 1); val t = order[i]; order[i] = order[j]; order[j] = t }
    for (idx in order) {
        val fix = when (idx) {
            0 -> findCovOFix(p, eval, rng)
            1 -> findC2Fix(p, eval, rng)
            2 -> findRangeLowFix(p, eval, rng)
            3 -> findC41Fix(p, eval, rng)
            4 -> findRangeHighFix(p, eval, rng)
            5 -> findC41sFix(p, eval, rng)
            6 -> findC3WantFix(p, eval, rng)
            else -> findAptFix(p, eval, rng)
        }
        if (fix != null) return fix
    }
    return null
}

/** [apt研磨] 適切回数(apt)の偏差を1セルで縮める手を探す。あるスタッフで apt 超過のシフト kOver を
 *  1日、apt 不足の担当可シフト kUnder へ振り替える（超過−1・不足−1 の双方向改善）。
 *  既存の find*Fix には apt 専用がなく、apt 超過(例: 単一専門職の休過多)が研磨で直らなかったため追加。
 *  担当不可・希望ロックの日は除外。covO 等の副作用は呼び出し側スコアの受理で評価。 */
internal fun findAptFix(p: Problem, eval: DeltaEvaluator, rng: Random): IntArray? {
    if (p.S == 0 || p.T == 0) return null
    val order = IntArray(p.S) { it }
    for (i in p.S - 1 downTo 1) { val j = rng.nextInt(i + 1); val t = order[i]; order[i] = order[j]; order[j] = t }
    for (i in order) {
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isEmpty()) continue
        val cnt = IntArray(p.K)
        for (j in 0 until p.T) cnt[eval.at(i, j)]++
        var kOver = -1; var kUnder = -1
        for (k in 0 until p.K) {
            val tg = p.apt[i][k]
            if (tg < 0) continue
            if (kOver < 0 && cnt[k] > tg) kOver = k
            if (kUnder < 0 && cnt[k] < tg && allowed.contains(k)) kUnder = k
        }
        if (kOver < 0 || kUnder < 0 || kOver == kUnder) continue
        val dayStart = rng.nextInt(p.T)
        for (d in 0 until p.T) {
            val j = (dayStart + d) % p.T
            if (!p.wishLocked(i, j) && eval.at(i, j) == kOver) return intArrayOf(i, j, kUnder)
        }
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
 * [E11/多人数ブロック移動] covU セル (k0, j) を同日・多人数の「玉突き連鎖」で充填する交代連鎖を
 * BFS（最短優先）で探す。対象の failure mode（実機 2026-08 データ・ユーザー指摘で確認）:
 * 直接充填の候補が (a)希望ロック (b)単一被覆シフト在勤＝引き抜くと玉突き covU (c)禁止連続 に当たり、
 * 既存の修復オペレータ（destroyRepairDay=「休→勤務」しか試さない）では踏めない「勤務→勤務」連鎖でのみ
 * 埋まる局面。ユーザー実例: 8/11 モニカ B4→Cｵ（深さ1・過剰B4から補充）／8/17 上條 Cｵ→Cｱ, 山本 →Cｵ（深さ2）。
 *
 * 探索: 「k0 を職員 i が埋める → i が空けたシフト m を次の職員が埋める → … → 空けても covU が増えない
 * シフト（需要0 or 余裕あり）で終端」。リンク条件: canDo・非wishLocked・禁止連続(c3n, 任意長=三連/五連等)の
 * プルーニング・同一職員の再訪なし・同一シフト展開の再訪なし・深さ≤maxDepth(既定5=最大5人の玉突き)。
 * 同日内交換なので被覆総量は保存。
 *
 * [禁止連続の回避=隣接日調整] 候補 i が k0 を埋めると禁止連続(c3n)に触れる場合、即除外せず、
 * 隣接日(j-1/j+1)の i 自身の割当も変えてパターンを崩せないか試す（`tryFixC3nViaAdjacentDay`）。
 * その調整で i の隣接日の元シフトが空き covU が悪化するなら、そこも同じ findCovUChain を
 * `allowCrossDayFix=false` で再帰し玉突き連鎖として埋め直す（cross-day 再帰は1段のみ＝無限展開防止）。
 * 見つかった追加手は Node.extra に積み、最終手順に合流する。
 *
 * 返り値 = 適用手 [(i, j, newK), ...]（本関数は盤面を変更しない。適用と採否=keep-best は呼び出し側＝
 * スコアリング不変・退化不能）。見つからなければ null。
 */
internal fun findCovUChain(p: Problem, sched: Array<IntArray>, k0: Int, j: Int, rng: Random, maxDepth: Int = 5, exclude: Int = -1, allowCrossDayFix: Boolean = true): List<IntArray>? {
    if (j !in 0 until p.T || k0 !in 0 until p.K || p.S == 0) return null
    val cnt = IntArray(p.K)
    for (i in 0 until p.S) { val kk = sched[i][j]; if (kk in 0 until p.K) cnt[kk]++ }
    // 充填で covU が実際に減るセルのみ対象（need 未設定などは対象外）。
    if (p.covUCell(k0, j, cnt[k0] + 1) >= p.covUCell(k0, j, cnt[k0])) return null

    // [三連/五連など任意長対応] 禁止連続(c3n)を作る移動を除外（最終ゲートは呼び出し側 checker が
    //   担保＝ここは成功率向上の枝刈り。Problem.makesForbiddenRun が任意長ルールを一般判定）。
    fun c3nHits(i: Int, newK: Int): Boolean = p.makesForbiddenRun(sched, i, j, newK)

    // [禁止連続の回避=隣接日調整] i を k0(day j) へ動かすと禁止連続に触れるとき、隣接日(j-1/j+1)の
    //   i の割当を別シフトへ変えてパターンを崩せないか試す。変更で空くシフトが covU 悪化を招くなら、
    //   同じアルゴリズムを1段だけ再帰して玉突きで埋め直す（allowCrossDayFix=false で無限展開を防止）。
    //   見つかれば [(i, j2, alt), ...サブ連鎖] を返す（盤面は一時変更するが必ず復元する）。
    fun tryFixC3nViaAdjacentDay(i: Int, fillShift: Int): List<IntArray>? {
        if (!allowCrossDayFix) return null
        for (j2 in intArrayOf(j - 1, j + 1)) {
            if (j2 !in 0 until p.T || p.wishLocked(i, j2)) continue
            val oldJ2 = sched[i][j2]
            if (oldJ2 !in 0 until p.K) continue
            // 候補シフト: 休を優先（連続禁止を崩す最も安全な既定手）、続けて担当可能シフト一覧。
            val altOrder = ArrayList<Int>()
            if (p.restIdx != oldJ2 && p.canDo(i, p.restIdx)) altOrder.add(p.restIdx)
            for (s in p.allowedShiftsForStaff(i)) if (s != oldJ2 && s !in altOrder) altOrder.add(s)
            for (alt in altOrder) {
                val cntBefore = (0 until p.S).count { sched[it][j2] == oldJ2 }
                sched[i][j2] = alt   // [一時変更] 下の判定後に必ず復元する
                val jOk = !p.makesForbiddenRun(sched, i, j, fillShift)
                val j2Ok = !p.makesForbiddenRun(sched, i, j2, alt)
                if (!jOk || !j2Ok) { sched[i][j2] = oldJ2; continue }
                if (p.covUCell(oldJ2, j2, cntBefore - 1) > p.covUCell(oldJ2, j2, cntBefore)) {
                    // i の離脱で oldJ2 が covU 悪化 → 同アルゴリズムを1段だけ再帰して埋め直す。
                    val subChain = findCovUChain(p, sched, oldJ2, j2, rng, maxDepth = maxDepth, exclude = i, allowCrossDayFix = false)
                    sched[i][j2] = oldJ2
                    if (subChain != null) return listOf(intArrayOf(i, j2, alt)) + subChain
                } else {
                    sched[i][j2] = oldJ2
                    return listOf(intArrayOf(i, j2, alt))
                }
            }
        }
        return null
    }

    // BFS ノード = 「fillShift へ staff が入る」手。子 = staff が空けた現シフトを埋める手。
    // extra = 禁止連続を回避するための追加手（隣接日調整＋サブ連鎖。無ければ null）。
    class Node(val fillShift: Int, val staff: Int, val prev: Node?, val extra: List<IntArray>? = null)

    // 職員の走査順を乱択（同型解の多様化。決定性が欲しい呼び出しは seed 固定の rng を渡す）。
    val order = IntArray(p.S) { it }
    for (x in p.S - 1 downTo 1) { val y = rng.nextInt(x + 1); val t = order[x]; order[x] = order[y]; order[y] = t }

    fun candidates(fillShift: Int, prev: Node?): List<Node> {
        val out = ArrayList<Node>()
        for (i in order) {
            if (i == exclude) continue   // [C1×E11] 呼出元が別途動かした職員を連鎖の候補から除外（無効な回帰手を防ぐ）
            val m = sched[i][j]
            if (m !in 0 until p.K || m == fillShift) continue
            if (!p.canDo(i, fillShift) || p.wishLocked(i, j)) continue
            var q = prev; var used = false
            while (q != null) { if (q.staff == i) { used = true; break }; q = q.prev }
            if (used) continue
            if (c3nHits(i, fillShift)) {
                val fix = tryFixC3nViaAdjacentDay(i, fillShift) ?: continue
                out.add(Node(fillShift, i, prev, extra = fix))
                continue
            }
            out.add(Node(fillShift, i, prev))
        }
        return out
    }
    // 終端: このノードの職員が空けるシフト m は、1人減っても covU が増えない（需要0 or 余裕あり）。
    fun tryComplete(node: Node): List<IntArray>? {
        val m = sched[node.staff][j]
        // [敵対的レビュー修正] cnt[] は探索開始時点の静的値。祖先ノードのチェーン適用でシフト m の
        //   実際のheadcountは変わりうるため、祖先を辿って m への「到着」(+1: 祖先の fillShift==m)と
        //   「離脱」(-1: 祖先の元シフト==m、つまりその祖先はチェーンの一員として既に m を離れることが
        //   確定している)を両方加味した真のheadcountで安全性を判定する。
        //   [第2版・重要] 到着分だけを補正する初版修正は不完全だった: 祖先 a が m から離脱しつつ別の
        //   祖先 g が m へ到着するケース（3段連鎖等）では、離脱を差し引かないと m のheadcountを過大評価し、
        //   実際には covU を悪化させる連鎖を安全と誤判定しかねない（false accept）。呼出元の checker+isBetter
        //   が最終防波堤とはいえ、判定ロジック自体は到着・離脱の両方を対称に扱うのが正しい。
        var adj = 0
        var anc: Node? = node.prev
        while (anc != null) {
            val a = anc                                       // [Kotlin] var の smart-cast 回避のため局所val化
            if (a.fillShift == m) adj++                        // 祖先 a が m へ到着済み
            if (sched[a.staff][j] == m) adj--                  // 祖先 a の元シフトが m＝m から離脱済み
            anc = a.prev
        }
        val trueCnt = cnt[m] + adj
        if (p.covUCell(m, j, trueCnt - 1) > p.covUCell(m, j, trueCnt)) return null
        val moves = ArrayList<IntArray>()
        var n: Node? = node
        while (n != null) {
            moves.add(intArrayOf(n.staff, j, n.fillShift))
            n.extra?.let { moves.addAll(it) }   // [禁止連続の回避] 隣接日調整＋サブ連鎖の追加手を合流
            n = n.prev
        }
        return moves
    }

    val visited = BooleanArray(p.K).also { it[k0] = true }
    var frontier = candidates(k0, null)
    var depth = 0
    while (depth < maxDepth && frontier.isNotEmpty()) {
        for (node in frontier) tryComplete(node)?.let { return it }
        val next = ArrayList<Node>()
        for (node in frontier) {
            val m = sched[node.staff][j]
            if (m in 0 until p.K && !visited[m]) { visited[m] = true; next.addAll(candidates(m, node)) }
        }
        frontier = next
        depth++
    }
    return null
}

/**
 * 非改善手の受理判定（生スコア＝hard*1_000_000+soft）。GLS 拡張分(moveAug=候補−現行)を加味する。
 * hard が +2 超増える手は常に却下。Great Deluge は水位以下かつ hard 非増加で受理。
 */
internal fun glsAccept(
    ns: Long, curScore: Long, moveAug: Double, curAug: Double,
    mode: AcceptMode, temp: Double, gdLevel: Double, rng: Random,
): Boolean {
    if (ns > curScore + 2_000_000L) return false
    return when (mode) {
        AcceptMode.GREAT_DELUGE ->
            (ns.toDouble() + curAug + moveAug) <= gdLevel && (ns / 1_000_000L) <= (curScore / 1_000_000L)
        AcceptMode.SA, AcceptMode.LAM_ADAPTIVE -> {
            // LAM_ADAPTIVE は受理式は SA と同じ Boltzmann。違いは呼び出し側が temp を受理率追従で適応させる点。
            val delta = (ns - curScore).toDouble() + moveAug
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
