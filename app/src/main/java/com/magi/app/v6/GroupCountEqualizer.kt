package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * グループ内の回数を、採点に関係なくそろえる（画面の「グループ内の回数をそろえる」、希望の反映と同じく利用者が押して上書きする操作）。
 * 手は同じ日・同じ勤務グループの 2 人のシフトの入れ替えだけ＝日ごとのシフト別人数とグループのシフト別合計は変わらない。
 * 各人の目標（置ける人の平均を個人の下限・上限へ収めた値）からのずれの 2 乗和が最も減る入れ替えから当てる。当てない手:
 * 希望・手動固定のマス、置けないシフト（`mayPlaceAt`）、個人の下限・上限から外れる（すでに外れている側はそれ以上悪くしない）、必須違反が増える。
 * 要調整（c1・並び・曜日など）は悪くなってもよい（利用者決定）。
 */
object GroupCountEqualizer {
    data class Result(val schedule: Array<IntArray>, val swaps: Int, val spreadBefore: Long, val spreadAfter: Long)

    /**
     * 各人の目標回数: グループ×シフトごとに、そのシフトを置ける人（`mayPlace`）の平均を、その人の下限・上限へ収めた値。
     * 置けない人は対象外（NaN）。入れ替えはグループ内・置ける人どうしの合計を変えないので、目標は途中で動かない。
     */
    private fun targets(p: Problem, cnt: Array<IntArray>): Array<DoubleArray> {
        val t = Array(p.S) { DoubleArray(p.K) { Double.NaN } }
        for (m in p.groupMembers) {
            if (m.size < 2) continue
            for (k in 0 until p.K) {
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

    private fun counts(p: Problem, s: Array<IntArray>) = Array(p.S) { i -> IntArray(p.K).also { c -> for (k in s[i]) if (k in 0 until p.K) c[k]++ } }

    /** 目標からのずれの 2 乗和（×100 で整数に。小さいほどそろっている）。 */
    private fun dev(t: Array<DoubleArray>, cnt: Array<IntArray>): Long {
        var d = 0.0
        for (i in t.indices) for (k in t[i].indices) if (!t[i][k].isNaN()) { val e = cnt[i][k] - t[i][k]; d += e * e }
        return Math.round(d * 100)
    }

    fun apply(state: MagiState, schedule: Array<IntArray>, maxSwaps: Int = 2_000): Result {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val cnt = counts(p, work)
        val tgt = targets(p, cnt)
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
        return Result(work, swaps, before, dev(tgt, cnt))
    }
}
