package com.magi.app.v6

/**
 * Faithful port of the Web worker's `fullEval`.
 *
 * Lexicographic objective:  score = hard1 * 1_000_000 + soft
 *   hard1 = c3n (forbidden seq) + covU (per-day need shortfall, MIN=OR over P1/P2) + pref
 *   soft  = c1 (window) + c2 (per-staff total) + c41 (group/day range)
 *           + c42 (group pair conflict) + c41s/c42s (skill-group変種) + c3 (want seq) + c3m + c3mn
 *           + [統一a/b] low/high (range, amount×90/45) + covO (over-coverage, amount)
 *   ※ range と covO は UnifiedViolationChecker と同分類(SOFT)・同重み。Web betterVec の lim 層は
 *     soft 内の高重み(90/45)として表現（hard1 は *1_000_000 で常に優先）。
 *
 * The solution `a[i][j]` is the assigned shift index (exactly one shift per cell),
 * the equivalent of the Web's one-hot `x[i][j][k] === 1`.
 *
 * Phase 1 recomputes the whole objective per candidate (no BIT-DELTA). It is exact;
 * native speed absorbs the cost. Δ-evaluation is a later optimization.
 */
class Evaluator(private val p: Problem, private val c3RunMode: Boolean = true) {

    fun fullEval(a: Array<IntArray>): Long {
        val S = p.S; val T = p.T; val K = p.K
        var hard1 = 0L
        var soft = 0L

        // c1: every window of length day1 must contain >= day2 of shiftIdx
        // [統一] (1)担当不可スタッフは対象外(canDoガード=チェッカーと一致、解消不能な幻の違反を除去)、
        //   (2)#fire 計上(soft += 1*重み4)。旧: 全スタッフ・soft += d1(フラット)。
        for (c in p.cons1) {
            val d1 = c.day1; val si = c.shiftIdx; val d2 = c.day2
            for (i in 0 until S) {
                if (!p.canDo(i, si)) continue
                var j = 0
                while (j <= T - d1) {
                    var z = 0
                    var l = 0
                    while (l < d1) { if (a[i][j + l] == si) z++; l++ }
                    if (z < d2) soft += 4L
                    j++
                }
            }
        }

        // c2: per-staff total of a shift must reach count
        for (c in p.cons2) {
            for (i in 0 until S) {
                var z = 0
                for (j in 0 until T) if (a[i][j] == c.shiftIdx) z++
                if (z < c.count) soft += 1
            }
        }

        // c41: per-day, count of (group, shift) must lie in [l, u]
        for (c in p.cons41) {
            for (j in 0 until T) {
                var z = 0
                for (i in 0 until S) if (p.sgrp[i] == c.groupIdx && a[i][j] == c.shiftIdx) z++
                if (z < c.l || c.u < z) soft += 1
            }
        }

        // c42: per-day, (g1,s1) co-occurring with (g2,s2) is penalized per pair
        for (c in p.cons42) {
            for (j in 0 until T) {
                var n1 = 0; var n2 = 0
                for (i in 0 until S) {
                    if (p.sgrp[i] == c.g1 && a[i][j] == c.s1) n1++
                    if (p.sgrp[i] == c.g2 && a[i][j] == c.s2) n2++
                }
                soft += n1.toLong() * n2.toLong()
            }
        }

        // c41s / c42s: スキルグループ版（ssk = スキル群index。既存 sgrp とは独立）。罰則は c41/c42 と同等(soft)。
        for (c in p.cons41s) {
            for (j in 0 until T) {
                var z = 0
                for (i in 0 until S) if (p.ssk[i] == c.groupIdx && a[i][j] == c.shiftIdx) z++
                if (z < c.l || c.u < z) soft += 1
            }
        }
        for (c in p.cons42s) {
            for (j in 0 until T) {
                var n1 = 0; var n2 = 0
                for (i in 0 until S) {
                    if (p.ssk[i] == c.g1 && a[i][j] == c.s1) n1++
                    if (p.ssk[i] == c.g2 && a[i][j] == c.s2) n2++
                }
                soft += n1.toLong() * n2.toLong()
            }
        }

        // c3 family — [統一] UnifiedViolationChecker と同じ重み(c3=3/c3m=2/c3mn=12)を soft に適用。
        // c3n は forbidden=HARD として hard1(count, ×1e6) のまま。窓マッチは #fire 計上(後述の sub += 1)。
        soft += c3check(a, p.cons3, false) * 3L
        hard1 += c3check(a, p.cons3n, true)    // forbidden -> display HARD (count)
        soft += c3check(a, p.cons3m, false) * 2L
        soft += c3check(a, p.cons3mn, true) * 12L

        // pref: wished cell not honored -> display HARD
        for (i in 0 until S) for (j in 0 until T) {
            val w = p.wish[i][j]
            if (w >= 0 && a[i][j] != w) hard1 += 1
        }

        // [統一a/b] range (LimMin/LimMax) は SOFT。UnifiedViolationChecker と同じ amount×重み(low=90/high=45)・
        // 同じガード(lo!=0, low は canDo 必須)。旧実装は hard2(=表示HARD) として +1 計上していた。
        val ssn = Array(S) { IntArray(K) }
        for (i in 0 until S) for (j in 0 until T) ssn[i][a[i][j]]++
        for (i in 0 until S) for (k in 0 until K) {
            val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
            val n = ssn[i][k]
            if (lo != Int.MIN_VALUE && lo != 0 && n < lo && p.canDo(i, k)) soft += (lo - n).toLong() * 90L
            if (hi != Int.MAX_VALUE && n > hi) soft += (n - hi).toLong() * 45L
            // [統一apt] 適切回数(双方向目標) SOFT・重み1・L1偏差|n-t|。UnifiedViolationChecker の "apt" と一致。
            val t = p.apt[i][k]
            if (t >= 0) soft += kotlin.math.abs(n - t).toLong()
        }

        // covU: per-day need shortfall. MIN=OR two-generation design (P1 vs P2).
        var c2v1 = 0L; var c2v2 = 0L
        for (j in 0 until T) {
            for (k in 0 until K) {
                val n = p.need1[k][j]
                if (n >= 0) {
                    var dsn = 0
                    for (i in 0 until S) if (a[i][j] == k) dsn++
                    if (dsn < n) c2v1 += (n - dsn)
                    // [統一a] covO(過剰被覆) を SOFT 追加。UnifiedViolationChecker と同じ上限 hi=(use2&&need2>=0?need2:need1)。
                    // checker の covO 重み 0.5 を整数化(=1)。最適化器も過剰配置を減らすようになる。
                    val hi = if (p.use2 && p.need2[k][j] >= 0) p.need2[k][j] else n
                    if (dsn > hi) soft += (dsn - hi).toLong()
                }
                if (p.use2) {
                    val n2 = p.need2[k][j]
                    if (n2 >= 0) {
                        var dsn2 = 0
                        for (i in 0 until S) if (a[i][j] == k) dsn2++
                        if (dsn2 < n2) c2v2 += (n2 - dsn2)
                    }
                }
            }
        }
        hard1 += if (p.use2) minOf(c2v1, if (c2v2 != 0L) c2v2 else c2v1) else c2v1

        return hard1 * 1_000_000L + soft
    }

    /** Returns the hard / soft split for display (運用違反 vs SOFT). */
    fun split(score: Long): Pair<Long, Long> = (score / 1_000_000L) to (score % 1_000_000L)

    private fun c3check(a: Array<IntArray>, list: List<C3>, forbidden: Boolean): Long {
        val S = p.S; val T = p.T
        var sub = 0L
        for (c in list) {
            val seq = c.seq
            val D = seq.size
            if (D == 0) continue
            val first = seq[0]
            // [HF507] non-forbidden single-shift run -> run deficit (per staff whole-row)
            if (!forbidden && c3RunMode && C3Run.isSingleShiftSeq(seq)) {
                for (i in 0 until S) sub += C3Run.rowDeficit(a, i, first, D)
                continue
            }
            for (i in 0 until S) {
                var j = 0
                while (j <= T - D) {
                    if (a[i][j] == first) {
                        var z = 0
                        var l = 1
                        while (l < D) { if (a[i][j + l] == seq[l]) z++; l++ }
                        val fire = if (forbidden) (z == D - 1) else (z < D - 1)
                        if (fire) sub += 1   // [統一] #fire 計上(チェッカー inc(key,1) と一致)。重みは呼び出し側で適用
                    }
                    j++
                }
            }
        }
        return sub
    }
}
