package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * シーソーの 1 組の手「3 人 × 2 日の巡回」（`docs/seesaw.md` §6）。A の日 X の s を B が、B の日 Y の s を C が引き受ける:
 * A[X]=s,B[X]=t,B[Y]=s,C[Y]=t → A[X]=t,B[X]=s,B[Y]=t,C[Y]=s（各日の人数と B の回数は不変）。2 つの同日の入れ替えを 1 つの手として
 * 採点するので、片方だけでは落ちる組み合わせを拾える。合法な手を全部並べ、`betterReport` でいちばん良い手を採って繰り返す（keep-best）。
 * 希望・手動固定のセルと、置けない・拡張希望の禁止・日の上限 0 のシフト（`mayPlaceAt`）へは動かさない。
 */
internal object CycleSwapPolish {
    data class Result(val newSchedule: Array<IntArray>, val applied: Int, val report: ViolationReport, val logs: List<MirrorLog>)

    fun apply(state: MagiState, schedule: Array<IntArray>, maxRounds: Int = 20, shouldStop: () -> Boolean = { false }): Result {
        val p = Problem(state)
        val b = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, b)
        var cur = before
        var applied = 0
        var evals = 0
        val t0 = System.nanoTime()
        fun ok(i: Int, j: Int, k: Int) = !p.wishLocked(i, j) && p.mayPlaceAt(i, j, k)
        fun swap(i1: Int, i2: Int, j: Int) { val t = b[i1][j]; b[i1][j] = b[i2][j]; b[i2][j] = t }
        round@ for (round in 0 until maxRounds) {
            var best: IntArray? = null
            var bestRep = cur
            for (bb in 0 until p.S) for (x in 0 until p.T) for (y in 0 until p.T) {
                if (x == y) continue
                if (shouldStop()) break@round
                val t = b[bb][x]; val s = b[bb][y]
                if (t == s || t !in 0 until p.K || s !in 0 until p.K) continue
                if (!ok(bb, x, s) || !ok(bb, y, t)) continue
                for (a in 0 until p.S) {
                    if (a == bb || b[a][x] != s || !ok(a, x, t)) continue
                    for (c in 0 until p.S) {
                        if (c == bb || c == a || b[c][y] != t || !ok(c, y, s)) continue
                        swap(a, bb, x); swap(bb, c, y)
                        val r = UnifiedViolationChecker.check(state, b); evals++
                        swap(bb, c, y); swap(a, bb, x)
                        if (betterReport(r, bestRep)) { bestRep = r; best = intArrayOf(a, bb, c, x, y) }
                    }
                }
            }
            val m = best ?: break
            swap(m[0], m[1], m[3]); swap(m[1], m[2], m[4])
            cur = bestRep; applied++
        }
        val ms = (System.nanoTime() - t0) / 1_000_000L
        val msg = "3人×2日の巡回: 採用 $applied 手・評価 $evals 回・${ms}ms・重み ${before.weightedScore.toLong()}→${cur.weightedScore.toLong()}・必須 ${before.hard}→${cur.hard}"
        return Result(b, applied, cur, listOf(MirrorLog(tag = "CycleSwapPolish", message = msg)))
    }
}
