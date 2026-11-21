package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * グループ内の回数を、採点に関係なくそろえる（画面の「グループ内の回数をそろえる」、希望の反映と同じく利用者が押して上書きする操作）。
 * 手は同じ日・同じ勤務グループの 2 人のシフトの入れ替えだけ＝日ごとのシフト別人数とグループのシフト別合計は変わらない。
 * 各人の目標（置ける人の平均を個人の下限・上限へ収めた値）からのずれの 2 乗和が最も減る入れ替えから当てる。当てない手:
 * 希望・手動固定のマス、置けないシフト（`mayPlaceAt`）、個人の下限・上限から外れる（すでに外れている側はそれ以上悪くしない）、必須違反が増える。
 * 要調整（c1・並び・曜日など）は悪くなってもよい（利用者決定）。[shifts] を渡すとそのシフトの回数だけをそろえる（ほかのシフトの回数は
 * 入れ替えで動くことがあるが、個人の下限・上限は守る）。1 組ずつ当てて止まったら、月全体の入れ替えを焼きなましで探す（[anneal]）。
 */
object GroupCountEqualizer {
    data class Result(val schedule: Array<IntArray>, val swaps: Int, val spreadBefore: Long, val spreadAfter: Long)

    /**
     * 各人の目標回数: グループ×シフトごとに、そのシフトを置ける人（`mayPlace`）の平均を、その人の下限・上限へ収めた値。
     * 置けない人は対象外（NaN）。入れ替えはグループ内・置ける人どうしの合計を変えないので、目標は途中で動かない。
     */
    private fun targets(p: Problem, cnt: Array<IntArray>, shifts: Set<Int>?): Array<DoubleArray> {
        val t = Array(p.S) { DoubleArray(p.K) { Double.NaN } }
        for (m in p.groupMembers) {
            if (m.size < 2) continue
            for (k in 0 until p.K) {
                if (shifts != null && k !in shifts) continue
                val el = m.filter { p.mayPlace(it, k) }
                if (el.size < 2) continue
                val mean = el.sumOf { cnt[it][k] }.toDouble() / el.size
                for (i in el) {
                    val lo = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) 0 else it }.toDouble()
                    val hi = p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) Double.MAX_VALUE else it.toDouble() }
                    t[i][k] = mean.coerceIn(lo, maxOf(lo, hi))
                }
            }
        }
        return t
    }

    private const val ANNEAL_ITERS = 400_000
    private const val ANNEAL_RUNS = 3

    /**
     * 1 組ずつの入れ替えでは範囲や禁止の並びにかかって進めないとき用の焼きなまし（月全体、同じグループの 2 人の入れ替え・3 人の回し・前後 2 日をまとめた入れ替え）。
     * 途中は個人の範囲を外れても必須が増えてもよい（罰として数える）が、採るのは「各 (職員,シフト) の範囲からの外れ量が元以下・
     * 必須が元以下」の盤面のうち、目標からのずれがいちばん小さいものだけ。よりそろった盤面が見つかれば [work] を書き換えて true。
     */
    private fun anneal(p: Problem, work: Array<IntArray>, cnt0: Array<IntArray>, tgt: Array<DoubleArray>, iters: Int, seed: Long): Boolean {
        /**
         * 日 [d]（と [d2]≥0 なら日 d2 にも同時に）[who] の順でシフトを 1 つずつ回す（who[t] が who[t+1] のシフトを受け取る）。
         * 2 人＝入れ替え、3 人＝3 人の回し。2 日＝前後の日の入れ替えを 1 手で（片方だけでは範囲を外れる組を通す）。日ごとの人数は変わらない。
         */
        class Mv(val who: IntArray, val d: Int, val d2: Int)
        val moves = ArrayList<Mv>()
        for (m in p.groupMembers) for (d in 0 until p.T) {
            val free = m.filter { !p.wishLocked(it, d) }
            for (ai in free.indices) for (bi in ai + 1 until free.size) {
                val a = free[ai]; val b = free[bi]
                moves.add(Mv(intArrayOf(a, b), d, -1))
                for (d2 in d + 1 until p.T) if (!p.wishLocked(a, d2) && !p.wishLocked(b, d2)) moves.add(Mv(intArrayOf(a, b), d, d2))
                for (ci in bi + 1 until free.size) {
                    moves.add(Mv(intArrayOf(a, b, free[ci]), d, -1))
                    moves.add(Mv(intArrayOf(a, free[ci], b), d, -1))
                }
            }
        }
        if (moves.isEmpty()) return false
        val cnt = Array(p.S) { cnt0[it].copyOf() }
        fun lo(i: Int, k: Int) = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) 0 else it }
        fun out(i: Int, k: Int, n: Int) = maxOf(0, lo(i, k) - n) + p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) 0 else maxOf(0, n - it) }
        val out0 = Array(p.S) { i -> IntArray(p.K) { k -> out(i, k, cnt[i][k]) } }
        fun sq(i: Int, k: Int, n: Int) = tgt[i][k].let { if (it.isNaN()) 0.0 else (n - it) * (n - it) }
        /** 範囲の外れ量のうち元を超えた分。 */
        fun excess(i: Int, k: Int, n: Int) = maxOf(0, out(i, k, n) - out0[i][k])
        var devCur = 0.0
        for (i in 0 until p.S) for (k in 0 until p.K) devCur += sq(i, k, cnt[i][k])
        var exCur = 0
        var hardCur = 0   // 元からの必須の増減
        val cur = work.copy2D(); val trial = work.copy2D()
        var bestDev = devCur
        var best: Array<IntArray>? = null
        val rnd = java.util.Random(seed)
        val t0 = 1.0; val t1 = 0.02
        val wEx = 2.0; val wHard = 20.0
        val ci = IntArray(12); val ck = IntArray(12); val cd = IntArray(12)   // この手での (職員,シフト) ごとの回数の増減
        for (step in 0 until iters) {
            val mv = moves[rnd.nextInt(moves.size)]
            val who = mv.who; val n = who.size
            val days = if (mv.d2 >= 0) intArrayOf(mv.d, mv.d2) else intArrayOf(mv.d)
            var ok = true; var moved = false; var nc = 0
            fun add(i: Int, k: Int, dd: Int) {
                for (e in 0 until nc) if (ci[e] == i && ck[e] == k) { cd[e] += dd; return }
                ci[nc] = i; ck[nc] = k; cd[nc] = dd; nc++
            }
            for (d in days) {
                for (t in 0 until n) {
                    val from = cur[who[t]][d]; val to = cur[who[(t + 1) % n]][d]
                    if (from !in 0 until p.K || to !in 0 until p.K) { ok = false; break }
                    if (from == to) continue
                    if (!p.mayPlaceAt(who[t], d, to)) { ok = false; break }
                    add(who[t], from, -1); add(who[t], to, 1); moved = true
                }
                if (!ok) break
            }
            if (!ok || !moved) continue
            var dDev = 0.0; var dEx = 0
            for (e in 0 until nc) {
                val i = ci[e]; val k = ck[e]; val dd = cd[e]
                if (dd == 0) continue
                dDev += sq(i, k, cnt[i][k] + dd) - sq(i, k, cnt[i][k])
                dEx += excess(i, k, cnt[i][k] + dd) - excess(i, k, cnt[i][k])
            }
            for (d in days) for (t in 0 until n) trial[who[t]][d] = cur[who[(t + 1) % n]][d]
            val dHard = HardDelta.delta(p, cur, trial)
            val df = dDev + wEx * dEx + wHard * dHard
            val temp = t0 * Math.pow(t1 / t0, step.toDouble() / iters)
            if (df <= 0 || rnd.nextDouble() < Math.exp(-df / temp)) {
                for (d in days) for (t in 0 until n) cur[who[t]][d] = trial[who[t]][d]
                for (e in 0 until nc) cnt[ci[e]][ck[e]] += cd[e]
                devCur += dDev; exCur += dEx; hardCur += dHard
                if (exCur == 0 && hardCur <= 0 && devCur < bestDev - 1e-9) {
                    bestDev = devCur
                    best = (best ?: Array(p.S) { IntArray(p.T) }).also { bb -> for (i in 0 until p.S) cur[i].copyInto(bb[i]) }
                }
            } else for (d in days) for (t in 0 until n) trial[who[t]][d] = cur[who[t]][d]
        }
        val bb = best ?: return false
        for (i in 0 until p.S) bb[i].copyInto(work[i])
        return true
    }

    private fun counts(p: Problem, s: Array<IntArray>) = Array(p.S) { i -> IntArray(p.K).also { c -> for (k in s[i]) if (k in 0 until p.K) c[k]++ } }

    /** 目標からのずれの 2 乗和（×100 で整数に。小さいほどそろっている）。 */
    private fun dev(t: Array<DoubleArray>, cnt: Array<IntArray>): Long {
        var d = 0.0
        for (i in t.indices) for (k in t[i].indices) if (!t[i][k].isNaN()) { val e = cnt[i][k] - t[i][k]; d += e * e }
        return Math.round(d * 100)
    }

    fun apply(state: MagiState, schedule: Array<IntArray>, shifts: Set<Int>? = null, maxSwaps: Int = 2_000,
              annealIters: Int = ANNEAL_ITERS, seed: Long = 1L): Result {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val cnt = counts(p, work)
        val tgt = targets(p, cnt, shifts)
        val before = dev(tgt, cnt)
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)   // 希望固定と手動固定
        /** 回数 n→n+d で、個人の範囲からの外れ量が増えないか。 */
        fun rangeOk(i: Int, k: Int, d: Int): Boolean {
            val lo = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) 0 else it }
            val hi = p.rangeHi[i][k]
            fun out(n: Int) = maxOf(0, lo - n) + (if (hi == Int.MAX_VALUE) 0 else maxOf(0, n - hi))
            val n = cnt[i][k]
            return out(n + d) <= out(n)
        }
        /** (i,k) の回数を d だけ変えたときのずれの 2 乗の変化（対象外は 0）。 */
        fun dsq(i: Int, k: Int, d: Int): Double {
            val t = tgt[i][k]
            if (t.isNaN()) return 0.0
            val a = cnt[i][k] - t; val b = a + d
            return b * b - a * a
        }
        data class Cand(val gain: Double, val a: Int, val b: Int, val d: Int)
        var swaps = 0
        while (swaps < maxSwaps) {
            val cands = ArrayList<Cand>()
            for (m in p.groupMembers) {
                if (m.size < 2) continue
                for (ai in m.indices) for (bi in ai + 1 until m.size) {
                    val a = m[ai]; val b = m[bi]
                    for (d in 0 until p.T) {
                        val x = work[a][d]; val y = work[b][d]
                        if (x == y || x !in 0 until p.K || y !in 0 until p.K || !movable(a, d) || !movable(b, d)) continue
                        if (!p.mayPlaceAt(a, d, y) || !p.mayPlaceAt(b, d, x)) continue
                        // a: x−1・y+1、b: x+1・y−1（負＝目標へ近づく）
                        val gain = dsq(a, x, -1) + dsq(a, y, 1) + dsq(b, x, 1) + dsq(b, y, -1)
                        if (gain > -1e-9) continue
                        if (!rangeOk(a, x, -1) || !rangeOk(a, y, 1) || !rangeOk(b, x, 1) || !rangeOk(b, y, -1)) continue
                        cands.add(Cand(gain, a, b, d))
                    }
                }
            }
            if (cands.isEmpty()) break
            cands.sortWith(compareBy<Cand> { it.gain }.thenBy { it.a }.thenBy { it.b }.thenBy { it.d })
            var done = false
            for (c in cands) {
                val x = work[c.a][c.d]; val y = work[c.b][c.d]
                val prev = work.copy2D()
                work[c.a][c.d] = y; work[c.b][c.d] = x
                if (HardDelta.delta(p, prev, work) > 0) { work[c.a][c.d] = x; work[c.b][c.d] = y; continue }
                cnt[c.a][x]--; cnt[c.a][y]++; cnt[c.b][x]++; cnt[c.b][y]--
                swaps++; done = true
                break
            }
            if (!done) break
        }
        // 焼きなましは乱数で行き着く先が揺れるので、種を変えて ANNEAL_RUNS 回走らせ、いちばんそろった盤面を採る
        var bestRun: Array<IntArray>? = null; var bestRunDev = dev(tgt, cnt)
        if (annealIters > 0) for (run in 0 until ANNEAL_RUNS) {
            val w = work.copy2D()
            if (!anneal(p, w, cnt, tgt, annealIters, seed + run)) continue
            val dv = dev(tgt, counts(p, w))
            if (dv < bestRunDev) { bestRunDev = dv; bestRun = w }
        }
        bestRun?.let { bb ->
            var changed = 0
            for (i in 0 until p.S) for (j in 0 until p.T) if (bb[i][j] != work[i][j]) changed++
            swaps += (changed + 1) / 2   // 変わったマスの数から数えた入れ替えの組数（3 人の回しは 2 組と数える）
            for (i in 0 until p.S) bb[i].copyInto(work[i])
            for (i in 0 until p.S) { cnt[i].fill(0); for (k in work[i]) if (k in 0 until p.K) cnt[i][k]++ }
        }
        return Result(work, swaps, before, dev(tgt, cnt))
    }
}
