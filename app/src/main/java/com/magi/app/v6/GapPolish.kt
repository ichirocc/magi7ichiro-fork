package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * シフトの間隔（gap）の研磨。同日 2 者の交換（CyclicSwap）は X を別の日へ動かせないので、被覆保存の手を 2 つ使う:
 * ①2 職員×2 日 長方形交換＝職員 i が日 d で X・日 d' で y、相手 i' が日 d で z・日 d' で X のとき、i: d→z / d'→X、i': d→X / d'→y と入れ替える
 * （各日の各シフト人数と、i・i' の X の回数は変わらない）。
 * ②区間の入れ替え＝間隔の足りないかたまりを前後 0〜[SEG_EXT] 日広げた区間と、別の 1〜[SEG_MAX_LEN] 日の区間で、i と i' の行を丸ごと入れ替える
 * （各日の人数は不変）。①は X を 1 マスだけ動かすため、X の後に続くべき並び（夜勤→休 など）を崩して禁止の並び（必須）を作りやすい＝
 * 夜勤のように前後の並びが決まったシフトでは候補のほぼ全部が必須増で採れない。②は並びごと動かす。
 * 起点は間隔が足りないかたまりの組（前のかたまりと近すぎる後のかたまり）。採否は他の研磨と同じ keep-best。
 */
internal object GapPolish {
    private const val SEG_EXT = 2
    private const val SEG_MAX_LEN = 5
    /** false＝②区間の入れ替えを使わない（測定の対照腕）。 */
    @Volatile var segmentSwap: Boolean = true

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
        var appliedSeg = 0
        var tried = 0
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)

        /** 盤面を書き換えた後に呼ぶ。良くなれば採って true、だめなら false（呼び出し側が戻す）。 */
        fun tryAdopt(prev: Array<IntArray>): Boolean {
            tried++
            val rep = screen.checkIfBetter(state, work, bestRep) ?: return false
            if (!adoptionGate(p, prev, work, rep, bestRep, pinBlocks).accepted) return false
            bestRep = rep
            applied++
            return true
        }

        /** 間隔が足りないかたまりの組（前のかたまり＋後のかたまり）。各要素は [開始, 終了]。 */
        fun violatingBlocks(row: IntArray, x: Int, n: Int): List<IntArray> {
            val out = ArrayList<IntArray>()
            var prevStart = -1; var prevEnd = -1
            var j = 0
            while (j < p.T) {
                if (row[j] != x) { j++; continue }
                val s = j
                while (j < p.T && row[j] == x) j++
                val e = j - 1
                if (prevEnd >= 0 && s - prevEnd < n) {
                    if (out.none { it[0] == prevStart }) out.add(intArrayOf(prevStart, prevEnd))
                    out.add(intArrayOf(s, e))
                }
                prevStart = s; prevEnd = e
            }
            return out
        }

        fun rectanglePass(): Boolean {
            for (c in p.consGap) {
                val x = c.shiftIdx
                for (i in 0 until p.S) {
                    if (shouldStop()) return false
                    if (!p.c1Applies(i, x)) continue
                    for (bl in violatingBlocks(work[i], x, c.days)) for (d in bl[0]..bl[1]) {
                        if (work[i][d] != x || !movable(i, d)) continue
                        for (dp in 0 until p.T) {
                            if (shouldStop()) return false
                            val y = work[i][dp]
                            if (dp == d || y == x || y !in 0 until p.K || !movable(i, dp)) continue
                            for (ip in 0 until p.S) {
                                if (ip == i || !movable(ip, d) || !movable(ip, dp) || work[ip][dp] != x) continue
                                val z = work[ip][d]
                                if (z == x || z !in 0 until p.K) continue
                                if (!p.mayPlace(i, z) || !p.mayPlace(ip, y)) continue
                                if (p.extBanned(i, d, z) || p.extBanned(i, dp, x) || p.extBanned(ip, d, x) || p.extBanned(ip, dp, y)) continue
                                val prev = work.copy2D()
                                work[i][d] = z; work[i][dp] = x; work[ip][d] = x; work[ip][dp] = y
                                if (tryAdopt(prev)) return true
                                work[i][d] = x; work[i][dp] = y; work[ip][d] = z; work[ip][dp] = x
                            }
                        }
                    }
                }
            }
            return false
        }

        fun swapDays(i: Int, ip: Int, a: Int, b: Int, cs: Int, ce: Int) {
            for (j in a..b) { val t = work[i][j]; work[i][j] = work[ip][j]; work[ip][j] = t }
            for (j in cs..ce) { val t = work[i][j]; work[i][j] = work[ip][j]; work[ip][j] = t }
        }

        fun segmentPass(): Boolean {
            for (c in p.consGap) {
                val x = c.shiftIdx
                for (i in 0 until p.S) {
                    if (shouldStop()) return false
                    if (!p.c1Applies(i, x)) continue
                    val deficit0 = GapRule.deficit(work[i], x, c.days)
                    if (deficit0 == 0) continue
                    for (bl in violatingBlocks(work[i], x, c.days)) for (lx in 0..SEG_EXT) for (rx in 0..SEG_EXT) {
                        val a = bl[0] - lx; val b = bl[1] + rx
                        if (a < 0 || b >= p.T) continue
                        if ((a..b).any { !movable(i, it) }) continue
                        for (len in 1..SEG_MAX_LEN) for (cs in 0..p.T - len) {
                            if (shouldStop()) return false
                            val ce = cs + len - 1
                            if (ce >= a - 1 && cs <= b + 1) continue   // 重なる・隣り合う区間は 1 区間の入れ替えと同じ
                            if ((cs..ce).any { !movable(i, it) }) continue
                            for (ip in 0 until p.S) {
                                if (ip == i) continue
                                if ((a..b).any { !movable(ip, it) } || (cs..ce).any { !movable(ip, it) }) continue
                                if ((a..b).all { work[i][it] == work[ip][it] } && (cs..ce).all { work[i][it] == work[ip][it] }) continue
                                if ((a..b).any { !p.mayPlaceAt(i, it, work[ip][it]) || !p.mayPlaceAt(ip, it, work[i][it]) }) continue
                                if ((cs..ce).any { !p.mayPlaceAt(i, it, work[ip][it]) || !p.mayPlaceAt(ip, it, work[i][it]) }) continue
                                val prev = work.copy2D()
                                swapDays(i, ip, a, b, cs, ce)
                                if (GapRule.deficit(work[i], x, c.days) < deficit0 && tryAdopt(prev)) { appliedSeg++; return true }
                                swapDays(i, ip, a, b, cs, ce)
                            }
                        }
                    }
                }
            }
            return false
        }

        var pass = 0
        while (pass < maxPasses && !shouldStop()) {
            var improved = false
            while (!shouldStop() && rectanglePass()) improved = true
            while (segmentSwap && !shouldStop() && segmentPass()) improved = true
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "GapPolish",
            message = "シフトの間隔(gap)研磨(長方形交換＋区間の入れ替え): gap ${before.breakdown["gap"] ?: 0}->${bestRep.breakdown["gap"] ?: 0} / total ${before.total}->${bestRep.total} 採用${applied}回(区間${appliedSeg}) 試行${tried}"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks, report = bestRep)
    }
}
