package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * シフトの間隔（gap）の研磨。同日 2 者の交換（CyclicSwap）は X を別の日へ動かせないので、2 職員 i・i' の行を 2 つの区間で丸ごと入れ替える
 * （各日の各シフト人数は不変）。区間 A は間隔の足りないかたまりを前後 0〜[EXT] 日広げたもの、区間 W は別の 1〜[MAX_LEN] 日（無しも可）。
 * 長方形交換（i の X 1 マスと i' の X 1 マスの入れ替え）は A・W とも長さ 1 の場合にあたる。
 *
 * 移動できる箇所を先に見つけてから動かす: 区間ごとに「入れ替えても禁止の並び・希望の前日の禁止を作らず、固定（希望）と置けないシフトに
 * 触れない」かを先に判定し、合格した区間どうしの組だけを、i の間隔の不足が減るときに採点する。夜勤のように後ろの並びが決まったシフトを
 * 1 マスだけ動かす手は禁止の並び（必須）を作るので、並びごと動く区間だけが残る。採否は他の研磨と同じ keep-best。
 */
internal object GapPolish {
    private const val EXT = 3
    private const val MAX_LEN = 7
    /** false＝区間 W の長さを 1（長方形交換と同じ近傍）に絞る（測定の対照腕）。 */
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
        var tried = 0
        var slots = 0
        val maxLen = if (segmentSwap) MAX_LEN else 1
        val maxExt = if (segmentSwap) EXT else 0

        fun swap(i: Int, ip: Int, a: Int, b: Int) {
            for (j in a..b) { val t = work[i][j]; work[i][j] = work[ip][j]; work[ip][j] = t }
        }

        /** 入れ替えた後の行で、変わったマスが禁止の並び・希望の前日の禁止を作らないか（[swap] 済みの盤面で見る）。 */
        fun cleanAfterSwap(i: Int, ip: Int, a: Int, b: Int): Boolean {
            for (j in a..b) {
                if (work[i][j] == work[ip][j]) continue
                if (p.makesForbiddenRun(work, i, j, work[i][j]) || p.makesForbiddenRun(work, ip, j, work[ip][j])) return false
            }
            return true
        }

        /** 区間 [a, b] で i と i' を入れ替えられるか（固定・置けないシフト・新しい禁止の並び）。 */
        fun slotOk(i: Int, ip: Int, a: Int, b: Int): Boolean {
            var differs = false
            for (j in a..b) {
                val u = work[i][j]; val v = work[ip][j]
                if (u == v) continue
                differs = true
                if (p.wishLocked(i, j) || p.wishLocked(ip, j)) return false
                if (!p.mayPlaceAt(i, j, v) || !p.mayPlaceAt(ip, j, u)) return false
            }
            if (!differs) return false
            swap(i, ip, a, b)
            val ok = cleanAfterSwap(i, ip, a, b)
            swap(i, ip, a, b)
            return ok
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

        fun onePass(): Boolean {
            for (c in p.consGap) {
                val x = c.shiftIdx
                for (i in 0 until p.S) {
                    if (shouldStop()) return false
                    if (!p.c1Applies(i, x)) continue
                    val deficit0 = GapRule.deficit(work[i], x, c.days)
                    if (deficit0 == 0) continue
                    for (ip in 0 until p.S) {
                        if (ip == i) continue
                        // 区間 A（違反のかたまりを含む）と区間 W（他の場所）の候補を先に見つける。
                        val anchors = ArrayList<IntArray>()
                        for (bl in violatingBlocks(work[i], x, c.days)) for (lx in 0..maxExt) for (rx in 0..maxExt) {
                            val a = bl[0] - lx; val b = bl[1] + rx
                            if (a >= 0 && b < p.T && anchors.none { it[0] == a && it[1] == b } && slotOk(i, ip, a, b)) anchors.add(intArrayOf(a, b))
                        }
                        if (anchors.isEmpty()) continue
                        val others = ArrayList<IntArray>()
                        for (len in 1..maxLen) for (cs in 0..p.T - len) if (slotOk(i, ip, cs, cs + len - 1)) others.add(intArrayOf(cs, cs + len - 1))
                        slots += anchors.size + others.size
                        for (aw in anchors) {
                            val a = aw[0]; val b = aw[1]
                            for (w in others.asSequence().filter { it[1] < a - 1 || it[0] > b + 1 } + sequenceOf(null)) {
                                if (shouldStop()) return false
                                if (w == null && segmentSwap.not()) continue
                                val prev = work.copy2D()
                                swap(i, ip, a, b); if (w != null) swap(i, ip, w[0], w[1])
                                val clean = GapRule.deficit(work[i], x, c.days) < deficit0 &&
                                    (w == null || (cleanAfterSwap(i, ip, a, b) && cleanAfterSwap(i, ip, w[0], w[1])))
                                if (clean) {
                                    tried++
                                    val rep = screen.checkIfBetter(state, work, bestRep)
                                    if (rep != null && adoptionGate(p, prev, work, rep, bestRep, pinBlocks).accepted) {
                                        bestRep = rep; applied++
                                        return true
                                    }
                                }
                                if (w != null) swap(i, ip, w[0], w[1]); swap(i, ip, a, b)
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
            while (!shouldStop() && onePass()) improved = true
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "GapPolish",
            message = "シフトの間隔(gap)研磨(2人の区間入れ替え・移動先を先に探す): gap ${before.breakdown["gap"] ?: 0}->${bestRep.breakdown["gap"] ?: 0} / total ${before.total}->${bestRep.total} 採用${applied}回 移動できる区間${slots} 採点${tried}"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks, report = bestRep)
    }
}
