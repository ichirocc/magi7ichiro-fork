package com.magi.app.v6

/**
 * Incremental (delta) evaluator — the native equivalent of the Web's BIT-DELTA framework.
 *
 * Instead of recomputing the whole objective for every candidate (Evaluator.fullEval,
 * O(S * T * constraints)), this maintains running per-piece penalty totals plus the
 * aggregates needed to update them, and computes the score change caused by a single
 * cell move (i, j): old shift -> new shift, touching only the windows/columns that can
 * actually change.
 *
 * Per-worker mutable state; not thread-safe (each SA worker owns one instance).
 *
 * The delta logic was validated against fullEval with a 20,000-move randomized
 * differential test (zero mismatches on both preview and committed score).
 */
class DeltaEvaluator(private val p: Problem, private val c3RunMode: Boolean = true) {

    private val S = p.S; private val T = p.T; private val K = p.K
    private val a: Array<IntArray>

    // aggregates
    private val cntSS = Array(S) { IntArray(K) }      // per-staff per-shift count
    private val cntDay = Array(K) { IntArray(T) }     // per-shift per-day count

    // running penalty pieces
    private var sc1 = 0L; private var sc2 = 0L; private var sc41 = 0L; private var sc42 = 0L
    private var sc41s = 0L; private var sc42s = 0L   // [スキルグループ] c41s/c42s（ssk ベース、soft）
    private var sc3 = 0L; private var hc3n = 0L; private var sc3m = 0L; private var sc3mn = 0L
    private var hpref = 0L; private var hct = 0L
    private var sApt = 0L                             // [統一apt] 適切回数(双方向目標)の running total（SOFT, 重み1）
    private var sFair = 0L                            // [統一fair] グループ内公平化の running total（SOFT, 重み1）
    private var scovO = 0L                            // [統一a] 過剰被覆(covO)の running total（SOFT）
    private var covP1 = 0L; private var covP2 = 0L

    // stashed deltas from the last preview (applied by commit())
    private var lI = -1; private var lJ = -1; private var lOld = -1; private var lNw = -1
    private var dC1 = 0L; private var dC2 = 0L; private var dC41 = 0L; private var dC42 = 0L
    private var dC41s = 0L; private var dC42s = 0L
    private var dC3 = 0L; private var dC3n = 0L; private var dC3m = 0L; private var dC3mn = 0L
    private var dPref = 0L; private var dCt = 0L; private var dApt = 0L; private var dFair = 0L; private var dCovO = 0L; private var nCovP1 = 0L; private var nCovP2 = 0L

    init {
        a = p.initialAssignment()
        rebuild()
    }

    /** Reset to a fresh assignment and recompute all aggregates / totals. */
    fun reset(init: Array<IntArray>) {
        for (i in 0 until S) System.arraycopy(init[i], 0, a[i], 0, T)
        rebuild()
    }

    fun snapshot(): Array<IntArray> = Array(S) { a[it].copyOf() }

    /** Copy the current assignment into a caller-owned buffer (no allocation; hot-loop best capture). */
    fun snapshotInto(dst: Array<IntArray>) {
        for (i in 0 until S) System.arraycopy(a[i], 0, dst[i], 0, T)
    }

    /** Current shift assigned at (i,j). */
    fun at(i: Int, j: Int): Int = a[i][j]

    /** Running per-staff count of shift k (O(1) lookup; backs the findXxx targeted fixers). */
    fun countForStaff(i: Int, k: Int): Int = cntSS[i][k]

    /** Running per-day count of shift k on day j (O(1) lookup; backs findCovOFix). */
    fun countOnDay(k: Int, j: Int): Int = cntDay[k][j]

    /** Fused previewMove + commit for a single cell. Returns the new total score. */
    fun apply(i: Int, j: Int, nw: Int): Long {
        previewMove(i, j, nw)
        commit(i, j, nw)
        return score()
    }

    fun score(): Long = scoreFrom(covP1, covP2)

    private fun scoreFrom(p1: Long, p2: Long): Long {
        val cov = covUOf(p1, p2)   // [監査#4] covUOf へ一元化（falsy-zero是正・Δ×フル一致）
        val h1 = hc3n + cov + hpref
        // [統一a/b] range(hct, 重み付き) と covO(scovO) を SOFT に含める（旧: hct は h2=表示HARD）。
        // [統一c] c3/c3m/c3mn に checker 重み(3/2/12)を適用（sc3等は #fire/run-deficit の生カウント）。
        // [統一c1] c1 にも checker 重み(4)を適用（sc1 は #fire 生カウント、canDoガード済）。
        // [統一apt/fair] sApt(適切回数の双方向L1偏差) と sFair(群内公平化L1偏差) を SOFT に含める（共に重み1）。
        val soft = sc1 * 4 + sc2 + sc41 + sc42 + sc41s + sc42s + sc3 * 3 + sc3m * 2 + sc3mn * 12 + hct + sApt + sFair + scovO
        return h1 * 1_000_000L + soft
    }

    /** Preview the score after moving (i,j) -> nw, stashing deltas for commit(). No mutation of totals. */
    private fun previewMove(i: Int, j: Int, nw: Int): Long {
        val old = a[i][j]
        lI = i; lJ = j; lOld = old; lNw = nw
        if (nw == old) {
            dC1 = 0; dC2 = 0; dC41 = 0; dC42 = 0; dC41s = 0; dC42s = 0; dC3 = 0; dC3n = 0; dC3m = 0; dC3mn = 0
            dPref = 0; dCt = 0; dApt = 0; dFair = 0; dCovO = 0; nCovP1 = covP1; nCovP2 = covP2
            return score()
        }

        // windowed families (c1, c3 family): before/after via temporary swap
        val bC1 = c1Local(i, j); val bC3 = c3Local(i, j, p.cons3, false)
        val bC3n = c3Local(i, j, p.cons3n, true); val bC3m = c3Local(i, j, p.cons3m, false)
        val bC3mn = c3Local(i, j, p.cons3mn, true)
        a[i][j] = nw
        val aC1 = c1Local(i, j); val aC3 = c3Local(i, j, p.cons3, false)
        val aC3n = c3Local(i, j, p.cons3n, true); val aC3m = c3Local(i, j, p.cons3m, false)
        val aC3mn = c3Local(i, j, p.cons3mn, true)
        a[i][j] = old
        dC1 = (aC1 - bC1); dC3 = (aC3 - bC3); dC3n = (aC3n - bC3n); dC3m = (aC3m - bC3m); dC3mn = (aC3mn - bC3mn)

        // c2 (per-staff total) for shifts old / nw
        var d2 = 0L
        for (c in p.cons2) {
            if (!p.canDo(i, c.shiftIdx)) continue   // [監査#5統一] c2All と同一ガード（Δ×フル一致）
            when (c.shiftIdx) {
                old -> d2 += viol01(cntSS[i][old] - 1 < c.count) - viol01(cntSS[i][old] < c.count)
                nw -> d2 += viol01(cntSS[i][nw] + 1 < c.count) - viol01(cntSS[i][nw] < c.count)
            }
        }
        dC2 = d2

        // ct (LimMin/LimMax) for shifts old / nw
        dCt = (rangeViol(i, old, cntSS[i][old] - 1) - rangeViol(i, old, cntSS[i][old])) +
              (rangeViol(i, nw, cntSS[i][nw] + 1) - rangeViol(i, nw, cntSS[i][nw]))

        // [統一apt] 適切回数(双方向目標) for shifts old / nw — staff i の old/nw 列のみ変化（range と同形）。
        dApt = (aptViol(i, old, cntSS[i][old] - 1) - aptViol(i, old, cntSS[i][old])) +
               (aptViol(i, nw, cntSS[i][nw] + 1) - aptViol(i, nw, cntSS[i][nw]))

        // [統一fair] グループ内公平化 — staff i の群 gI の old/nw 列のみ偏差が動く（本人 ±1 で平均も動くので群内再計算）。
        val gI = p.sgrp[i]
        dFair = 0L
        if (p.canDo(i, old)) dFair += fairDevAt(gI, old, i, -1) - fairDevAt(gI, old, -1, 0)
        if (p.canDo(i, nw)) dFair += fairDevAt(gI, nw, i, +1) - fairDevAt(gI, nw, -1, 0)

        // pref (this cell only)
        val w = p.wish[i][j]
        dPref = (if (w >= 0 && nw != w) 1L else 0L) - (if (w >= 0 && old != w) 1L else 0L)

        // c41 (group/day range) on day j — only constraints touching this staff's group & shifts
        val gi = p.sgrp[i]
        var d41 = 0L
        for (c in p.cons41) {
            if (c.groupIdx != gi || (c.shiftIdx != old && c.shiftIdx != nw)) continue
            var z = 0
            for (ii in 0 until S) if (p.sgrp[ii] == c.groupIdx && a[ii][j] == c.shiftIdx) z++
            val za = z + (if (c.shiftIdx == nw) 1 else 0) - (if (c.shiftIdx == old) 1 else 0)
            d41 += viol01(za < c.l || c.u < za) - viol01(z < c.l || c.u < z)
        }
        dC41 = d41

        // c42 (group pair) on day j — skip constraints the move cannot change (mirrors c41's guard).
        var d42 = 0L
        for (c in p.cons42) {
            val touch1 = c.g1 == gi && (c.s1 == old || c.s1 == nw)
            val touch2 = c.g2 == gi && (c.s2 == old || c.s2 == nw)
            if (!touch1 && !touch2) continue   // n1a==n1 && n2a==n2 -> delta 0
            var n1 = 0; var n2 = 0
            for (ii in 0 until S) {
                if (p.sgrp[ii] == c.g1 && a[ii][j] == c.s1) n1++
                if (p.sgrp[ii] == c.g2 && a[ii][j] == c.s2) n2++
            }
            val n1a = n1 + (if (c.g1 == gi && c.s1 == nw) 1 else 0) - (if (c.g1 == gi && c.s1 == old) 1 else 0)
            val n2a = n2 + (if (c.g2 == gi && c.s2 == nw) 1 else 0) - (if (c.g2 == gi && c.s2 == old) 1 else 0)
            d42 += n1a.toLong() * n2a.toLong() - n1.toLong() * n2.toLong()
        }
        dC42 = d42

        // c41s (skill group/day range) on day j — same shape as c41 but indexed by ssk
        val gis = p.ssk[i]
        var d41s = 0L
        for (c in p.cons41s) {
            if (c.groupIdx != gis || (c.shiftIdx != old && c.shiftIdx != nw)) continue
            var z = 0
            for (ii in 0 until S) if (p.ssk[ii] == c.groupIdx && a[ii][j] == c.shiftIdx) z++
            val za = z + (if (c.shiftIdx == nw) 1 else 0) - (if (c.shiftIdx == old) 1 else 0)
            d41s += viol01(za < c.l || c.u < za) - viol01(z < c.l || c.u < z)
        }
        dC41s = d41s

        // c42s (skill group pair) on day j — same shape as c42 but indexed by ssk
        var d42s = 0L
        for (c in p.cons42s) {
            val touch1 = c.g1 == gis && (c.s1 == old || c.s1 == nw)
            val touch2 = c.g2 == gis && (c.s2 == old || c.s2 == nw)
            if (!touch1 && !touch2) continue
            var n1 = 0; var n2 = 0
            for (ii in 0 until S) {
                if (p.ssk[ii] == c.g1 && a[ii][j] == c.s1) n1++
                if (p.ssk[ii] == c.g2 && a[ii][j] == c.s2) n2++
            }
            val n1a = n1 + (if (c.g1 == gis && c.s1 == nw) 1 else 0) - (if (c.g1 == gis && c.s1 == old) 1 else 0)
            val n2a = n2 + (if (c.g2 == gis && c.s2 == nw) 1 else 0) - (if (c.g2 == gis && c.s2 == old) 1 else 0)
            d42s += n1a.toLong() * n2a.toLong() - n1.toLong() * n2.toLong()
        }
        dC42s = d42s

        // covU: update P1/P2 totals for the two affected (shift,day) cells
        var p1 = covP1; var p2 = covP2
        val co = cntDay[old][j]
        if (p.need1[old][j] >= 0) p1 += short0(p.need1[old][j], co - 1) - short0(p.need1[old][j], co)
        if (p.use2 && p.need2[old][j] >= 0) p2 += short0(p.need2[old][j], co - 1) - short0(p.need2[old][j], co)
        val cn = cntDay[nw][j]
        if (p.need1[nw][j] >= 0) p1 += short0(p.need1[nw][j], cn + 1) - short0(p.need1[nw][j], cn)
        if (p.use2 && p.need2[nw][j] >= 0) p2 += short0(p.need2[nw][j], cn + 1) - short0(p.need2[nw][j], cn)
        nCovP1 = p1; nCovP2 = p2

        // [統一a] covO 差分：影響する2セル(old,j)/(nw,j) の過剰被覆量の差（cntDay は commit 前なので co/cn が現値）
        dCovO = (covOCell(old, j, co - 1) - covOCell(old, j, co)) +
                (covOCell(nw, j, cn + 1) - covOCell(nw, j, cn))

        // [統一b] dCt(range) は SOFT へ移動（hard から除外）。
        val dHard = dC3n + (covUOf(p1, p2) - covUOf(covP1, covP2)) + dPref
        // [統一c] c3/c3m/c3mn の delta にも checker 重み(3/2/12)を適用（full soft と同一係数）。
        // [統一c1] c1 の delta にも ×4。
        val dSoft = dC1 * 4 + dC2 + dC41 + dC42 + dC41s + dC42s + dC3 * 3 + dC3m * 2 + dC3mn * 12 + dCt + dApt + dFair + dCovO
        return score() + dHard * 1_000_000L + dSoft
    }

    /** Apply the stashed move from the last previewMove(i,j,nw). Internal — external callers use apply(). */
    private fun commit(i: Int, j: Int, nw: Int) {
        require(lI >= 0 && i == lI && j == lJ && nw == lNw) { "commit must match last preview" }
        require(a[i][j] == lOld) { "state changed after preview" }
        val old = lOld
        try {
            if (nw == old) return
            a[i][j] = nw
            cntSS[i][old]--; cntSS[i][nw]++
            cntDay[old][j]--; cntDay[nw][j]++
            sc1 += dC1; sc2 += dC2; sc41 += dC41; sc42 += dC42; sc41s += dC41s; sc42s += dC42s
            sc3 += dC3; hc3n += dC3n; sc3m += dC3m; sc3mn += dC3mn
            hpref += dPref; hct += dCt; sApt += dApt; sFair += dFair; scovO += dCovO
            covP1 = nCovP1; covP2 = nCovP2
        } finally {
            // invalidate the stash so a stray double-commit cannot corrupt aggregates
            lI = -1; lJ = -1; lOld = -1; lNw = -1
        }
    }

    // ---- aggregate / total rebuild --------------------------------------------

    private fun rebuild() {
        for (i in 0 until S) java.util.Arrays.fill(cntSS[i], 0)
        for (k in 0 until K) java.util.Arrays.fill(cntDay[k], 0)
        for (i in 0 until S) for (j in 0 until T) { val k = a[i][j]; cntSS[i][k]++; cntDay[k][j]++ }

        sc1 = c1All(); sc2 = c2All(); sc41 = c41All(); sc42 = c42All(); sc41s = c41sAll(); sc42s = c42sAll()
        sc3 = c3All(p.cons3, false); hc3n = c3All(p.cons3n, true)
        sc3m = c3All(p.cons3m, false); sc3mn = c3All(p.cons3mn, true)
        hpref = prefAll(); hct = ctAll(); sApt = aptAll(); sFair = fairAll(); scovO = covOAll()
        val cov = covAll(); covP1 = cov[0]; covP2 = cov[1]
    }

    // ---- helpers ---------------------------------------------------------------

    private fun viol01(b: Boolean): Long = if (b) 1L else 0L
    private fun short0(need: Int, have: Int): Long = if (have < need) (need - have).toLong() else 0L
    private fun covUOf(p1: Long, p2: Long): Long =
        if (p.use2 && p.hasNeed2) minOf(p1, p2) else p1   // [監査#4] Evaluator と同一（falsy-zero是正）

    private fun rangeViol(i: Int, k: Int, n: Int): Long {
        // [統一b] UnifiedViolationChecker と同分類(SOFT)・同重み: low(lo!=0, canDo必須)=amount×90 / high=amount×45。
        val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
        var v = 0L
        if (lo != Int.MIN_VALUE && lo != 0 && n < lo && p.canDo(i, k)) v += (lo - n).toLong() * 90L
        if (hi != Int.MAX_VALUE && n > hi) v += (n - hi).toLong() * 45L
        return v
    }

    /** [統一a] 1セル(shift k, 日 j)の過剰被覆量。上限 hi=(use2&&need2>=0?need2:need1)。checker covO と同一。 */
    private fun covOCell(k: Int, j: Int, got: Int): Long {
        val n1 = p.need1[k][j]
        if (n1 < 0) return 0L
        val hi = if (p.use2 && p.need2[k][j] >= 0) p.need2[k][j] else n1
        return if (got > hi) (got - hi).toLong() else 0L
    }
    private fun covOAll(): Long {
        var s = 0L
        for (k in 0 until K) for (j in 0 until T) s += covOCell(k, j, cntDay[k][j])
        return s
    }

    private fun c1Local(i: Int, j: Int): Long {
        var tot = 0L
        for (c in p.cons1) {
            if (!p.canDo(i, c.shiftIdx)) continue   // [統一] 担当不可は対象外(チェッカーと一致)
            val js0 = maxOf(0, j - c.day1 + 1); val js1 = minOf(T - c.day1, j)
            var js = js0
            while (js <= js1) {
                var z = 0; var l = 0
                while (l < c.day1) { if (a[i][js + l] == c.shiftIdx) z++; l++ }
                if (z < c.day2) tot += 1   // [統一] #fire 計上。重みは soft 集約で×4
                js++
            }
        }
        return tot
    }

    private fun c3Local(i: Int, j: Int, list: List<C3>, fbd: Boolean): Long {
        var sub = 0L
        for (c in list) {
            val seq = c.seq; val D = seq.size
            if (D == 0 || D > T) continue   // [監査#9統一] 期間超の行はチェッカー同様スキップ
            // [HF507] single-shift run: deficit is per-staff whole-row, not windowed.
            // A move at (i,j) only affects staff i's row, so recompute row i's run deficit
            // (before/after via the caller's swap captures the delta correctly).
            if (!fbd && c3RunMode && C3Run.isSingleShiftSeq(seq)) {
                sub += C3Run.rowDeficit(a, i, seq[0], D)
                continue
            }
            val js0 = maxOf(0, j - D + 1); val js1 = minOf(T - D, j)
            var js = js0
            while (js <= js1) {
                if (a[i][js] == seq[0]) {
                    var z = 0; var l = 1
                    while (l < D) { if (a[i][js + l] == seq[l]) z++; l++ }
                    val fire = if (fbd) (z == D - 1) else (z < D - 1)
                    if (fire) sub += 1   // [統一] #fire 計上。重みは soft 集約で適用
                }
                js++
            }
        }
        return sub
    }

    private fun c1All(): Long {
        var tot = 0L
        for (c in p.cons1) for (i in 0 until S) {
            if (!p.canDo(i, c.shiftIdx)) continue   // [統一] 担当不可は対象外(チェッカーと一致)
            var js = 0
            while (js <= T - c.day1) {
                var z = 0; var l = 0
                while (l < c.day1) { if (a[i][js + l] == c.shiftIdx) z++; l++ }
                if (z < c.day2) tot += 1   // [統一] #fire 計上。重みは soft 集約で×4
                js++
            }
        }
        return tot
    }

    private fun c2All(): Long {
        var tot = 0L
        for (c in p.cons2) for (i in 0 until S) { if (!p.canDo(i, c.shiftIdx)) continue; if (cntSS[i][c.shiftIdx] < c.count) tot += 1 }   // [監査#5統一]
        return tot
    }

    private fun c41All(): Long {
        var tot = 0L
        for (c in p.cons41) for (j in 0 until T) {
            var z = 0
            for (i in 0 until S) if (p.sgrp[i] == c.groupIdx && a[i][j] == c.shiftIdx) z++
            if (z < c.l || c.u < z) tot += 1
        }
        return tot
    }

    private fun c42All(): Long {
        var tot = 0L
        for (c in p.cons42) for (j in 0 until T) {
            var n1 = 0; var n2 = 0
            for (i in 0 until S) {
                if (p.sgrp[i] == c.g1 && a[i][j] == c.s1) n1++
                if (p.sgrp[i] == c.g2 && a[i][j] == c.s2) n2++
            }
            tot += n1.toLong() * n2.toLong()
        }
        return tot
    }

    private fun c41sAll(): Long {
        var tot = 0L
        for (c in p.cons41s) for (j in 0 until T) {
            var z = 0
            for (i in 0 until S) if (p.ssk[i] == c.groupIdx && a[i][j] == c.shiftIdx) z++
            if (z < c.l || c.u < z) tot += 1
        }
        return tot
    }

    private fun c42sAll(): Long {
        var tot = 0L
        for (c in p.cons42s) for (j in 0 until T) {
            var n1 = 0; var n2 = 0
            for (i in 0 until S) {
                if (p.ssk[i] == c.g1 && a[i][j] == c.s1) n1++
                if (p.ssk[i] == c.g2 && a[i][j] == c.s2) n2++
            }
            tot += n1.toLong() * n2.toLong()
        }
        return tot
    }

    private fun c3All(list: List<C3>, fbd: Boolean): Long {
        var sub = 0L
        for (c in list) {
            val seq = c.seq; val D = seq.size
            if (D == 0 || D > T) continue   // [監査#9統一] 期間超の行はチェッカー同様スキップ
            // [HF507] non-forbidden single-shift run -> run deficit (per staff whole-row)
            if (!fbd && c3RunMode && C3Run.isSingleShiftSeq(seq)) {
                for (i in 0 until S) sub += C3Run.rowDeficit(a, i, seq[0], D)
                continue
            }
            for (i in 0 until S) {
                var j = 0
                while (j <= T - D) {
                    if (a[i][j] == seq[0]) {
                        var z = 0; var l = 1
                        while (l < D) { if (a[i][j + l] == seq[l]) z++; l++ }
                        val fire = if (fbd) (z == D - 1) else (z < D - 1)
                        if (fire) sub += 1   // [統一] #fire 計上。重みは soft 集約で適用
                    }
                    j++
                }
            }
        }
        return sub
    }

    private fun prefAll(): Long {
        var h = 0L
        for (i in 0 until S) for (j in 0 until T) {
            val w = p.wish[i][j]; if (w >= 0 && a[i][j] != w) h++
        }
        return h
    }

    private fun ctAll(): Long {
        var h = 0L
        for (i in 0 until S) for (k in 0 until K) h += rangeViol(i, k, cntSS[i][k])
        return h
    }

    /** [統一apt] 1セル(staff i, shift k)の適切回数偏差。重み1の L1 |n-t|。UnifiedViolationChecker の "apt" と一致。 */
    private fun aptViol(i: Int, k: Int, n: Int): Long {
        val t = p.apt[i][k]
        return if (t >= 0) kotlin.math.abs(n - t).toLong() else 0L
    }
    private fun aptAll(): Long {
        var h = 0L
        for (i in 0 until S) for (k in 0 until K) h += aptViol(i, k, cntSS[i][k])
        return h
    }

    /** [統一fair] 群g・シフトk の公平化偏差。staff [special] のカウントに [delta] を加味（preview用）。
     *  round(平均) からのメンバー L1 偏差和。UnifiedViolationChecker の "fair" と一致。 */
    private fun fairDevAt(g: Int, k: Int, special: Int, delta: Int): Long {
        val mem = p.groupMembers[g]
        val m = mem.size
        if (m < 2) return 0L
        var sum = 0
        for (x in mem) sum += cntSS[x][k] + (if (x == special) delta else 0)
        val tgt = Math.round(sum.toDouble() / m).toInt()
        var d = 0L
        for (x in mem) { val c = cntSS[x][k] + (if (x == special) delta else 0); d += kotlin.math.abs(c - tgt).toLong() }
        return d
    }
    private fun fairAll(): Long {
        var h = 0L
        for (g in 0 until p.G) for (k in p.bucket[g]) h += fairDevAt(g, k, -1, 0)
        return h
    }

    private fun covAll(): LongArray {
        var p1 = 0L; var p2 = 0L
        for (j in 0 until T) for (k in 0 until K) {
            val n = p.need1[k][j]; val have = cntDay[k][j]
            if (n >= 0 && have < n) p1 += (n - have)
            if (p.use2) { val n2 = p.need2[k][j]; if (n2 >= 0 && have < n2) p2 += (n2 - have) }
        }
        return longArrayOf(p1, p2)
    }
}
