package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * シフトの間隔（gap）の研磨。同日 2 者の交換（CyclicSwap）は X を別の日へ動かせないので、被覆保存の 2 職員×2 日 長方形交換を使う:
 * 職員 i が日 d で X・日 d' で y、相手 i' が日 d で z・日 d' で X のとき、i: d→z / d'→X、i': d→X / d'→y と入れ替える。
 * 各日の各シフト人数と、i・i' の X の回数は変わらない（近すぎる X を離れた日へずらす）。
 * 起点は間隔が足りないかたまりの組（前のかたまりと近すぎる後のかたまり）のセル。採否は他の研磨と同じ keep-best。
 */
internal object GapPolish {
    fun apply(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false },
    ): V6HotfixPasses.CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        if (p.consGap.isEmpty() || (before.breakdown["gap"] ?: 0) == 0) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, before.total, 0, emptyList(), report = before)
        }
        val pinBlocks = PinBlockAttribution()
        var bestRep = before
        val screen = DeltaScreen(p)
        var applied = 0
        var tried = 0
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)

        /** 間隔が足りないかたまりの組のセル（前のかたまり＋後のかたまり）。 */
        fun anchorDays(row: IntArray, x: Int, n: Int): List<Int> {
            val out = LinkedHashSet<Int>()
            var prevStart = -1; var prevEnd = -1
            var j = 0
            while (j < p.T) {
                if (row[j] != x) { j++; continue }
                val s = j
                while (j < p.T && row[j] == x) j++
                val e = j - 1
                if (prevEnd >= 0 && s - prevEnd < n) { for (d in prevStart..prevEnd) out.add(d); for (d in s..e) out.add(d) }
                prevStart = s; prevEnd = e
            }
            return out.toList()
        }

        var pass = 0
        while (pass < maxPasses && !shouldStop()) {
            var improved = false
            for (c in p.consGap) {
                val x = c.shiftIdx
                for (i in 0 until p.S) {
                    if (shouldStop()) break
                    if (!p.c1Applies(i, x)) continue
                    var done = false
                    for (d in anchorDays(work[i], x, c.days)) {
                        if (done || shouldStop()) break
                        if (work[i][d] != x || !movable(i, d)) continue
                        for (dp in 0 until p.T) {
                            if (done || shouldStop()) break
                            val y = work[i][dp]
                            if (dp == d || y == x || y !in 0 until p.K || !movable(i, dp)) continue
                            for (ip in 0 until p.S) {
                                if (ip == i || !movable(ip, d) || !movable(ip, dp) || work[ip][dp] != x) continue
                                val z = work[ip][d]
                                if (z == x || z !in 0 until p.K) continue
                                if (!p.mayPlace(i, z) || !p.mayPlace(ip, y)) continue
                                if (p.extBanned(i, d, z) || p.extBanned(i, dp, x) || p.extBanned(ip, d, x) || p.extBanned(ip, dp, y)) continue
                                tried++
                                val prev = work.copy2D()
                                work[i][d] = z; work[i][dp] = x; work[ip][d] = x; work[ip][dp] = y
                                val rep = screen.checkIfBetter(state, work, bestRep)
                                if (rep != null && adoptionGate(p, prev, work, rep, bestRep, pinBlocks).accepted) {
                                    bestRep = rep; applied++; improved = true; done = true; break
                                }
                                work[i][d] = x; work[i][dp] = y; work[ip][d] = z; work[ip][dp] = x
                            }
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "GapPolish",
            message = "シフトの間隔(gap)研磨(長方形交換): gap ${before.breakdown["gap"] ?: 0}->${bestRep.breakdown["gap"] ?: 0} / total ${before.total}->${bestRep.total} 採用${applied}回 試行${tried}"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks, report = bestRep)
    }
}
