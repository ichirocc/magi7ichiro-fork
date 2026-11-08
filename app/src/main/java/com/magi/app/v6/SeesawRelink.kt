package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * シーソーの S3（`docs/seesaw.md` §6.3）。本命から「他の案」の各盤面へ、違うセルを違反セルから先に 1 つずつ（同じ日に値を交換し合う 2 人は対で）
 * 写して歩く Path Relinking。必須が増える手は取り消す（3.727.42）。途中の盤面が `betterReport` で本命より良く固定（厳密ピン）も破らなければ本命を差し替え、必須の件数が同じで本命に
 * 優越されない盤面は**交換の候補**として残す（S4 の「他の案」に出す）。採否は `betterReport` だけ（I2）。
 * 既定 OFF（[PolishGate.seesawRelink]）＝測ってから採否。
 */
object SeesawRelink {

    class Result(
        val schedule: Array<IntArray>,
        val report: ViolationReport,
        val applied: Int,
        /** 本命に優越されない交換の候補（最大 [MAX_TRADEOFFS]、必須の件数は本命と同じ）。 */
        val tradeoffs: List<Array<IntArray>>,
        val logs: List<MirrorLog>,
    )

    const val MAX_TRADEOFFS = 3

    /**
     * 案ごとの歩き方（3.727.42）。どれも数ミリ秒なので全部走らせ、良いほうを採る。
     * PLAIN＝1 マスずつ写し戻さない（3.727.22 からの歩き）、PAIRED＝同じ日に値を交換し合う 2 人は対で写す、
     * PAIRED_KEEP_HARD＝対で写し、必須が増える手は戻す（磨いた本命から粗い案へ歩いても途中が崩れない）。
     */
    enum class Walk { PLAIN, PAIRED, PAIRED_KEEP_HARD }

    /** a が b に優越する（21 族すべて a ≤ b、どれかで <）。重みを使わない比較（`docs/seesaw.md` §2）。 */
    fun dominates(a: ViolationReport, b: ViolationReport): Boolean {
        var strict = false
        for (f in MirrorKeys.all) {
            val x = a.breakdown[f] ?: 0; val y = b.breakdown[f] ?: 0
            if (x > y) return false
            if (x < y) strict = true
        }
        return strict
    }

    fun apply(
        state: MagiState,
        best0: Array<IntArray>,
        alternatives: List<Array<IntArray>>,
        maxMillis: Long = 3_000L,
        shouldStop: () -> Boolean = { false },
        walks: List<Walk> = Walk.entries,
    ): Result {
        val p = cachedProblem(state)
        val t0 = EngineClock.nowMs()
        fun out() = shouldStop() || EngineClock.nowMs() - t0 >= maxMillis
        var best = best0.copy2D()
        var bestRep = UnifiedViolationChecker.check(state, best)
        var applied = 0
        val pool = ArrayList<Pair<Array<IntArray>, ViolationReport>>()
        for (alt in alternatives) for (walk in walks) {
            val pairMoves = walk != Walk.PLAIN
            val undoHardIncrease = walk == Walk.PAIRED_KEEP_HARD
            if (out()) break
            if (alt.size != best.size || alt.any { it.size != p.T }) continue
            val start = best.copy2D()
            val cur = best.copy2D()
            val startRep = bestRep
            val vcells = HashSet<Long>()
            for (k in startRep.violations.keys) {
                val parts = k.split(','); val i = parts.getOrNull(0)?.toIntOrNull(); val j = parts.getOrNull(1)?.toIntOrNull()
                if (i != null && j != null) vcells.add(i.toLong() * p.T + j)
            }
            val diffs = ArrayList<Pair<Int, Int>>()
            for (i in cur.indices) for (j in cur[i].indices) if (cur[i][j] != alt[i][j]) diffs.add(i to j)
            diffs.sortBy { (i, j) -> if (i.toLong() * p.T + j in vcells) 0 else 1 }
            fun movable(i: Int, j: Int) = !(p.wishLocked(i, j) && alt[i][j] != p.lockTo(i, j)) && !p.extBanned(i, j, alt[i][j])
            // 歩きの盤面は必須を増やさない（増える手は取り消す）＝磨いた本命から粗い案へ 1 マス写すたびに人員不足が出て、
            //   以後の盤面がすべて悪くなるのを防ぐ。同じ日に値を交換し合っている 2 人は対で写す（その日の人数が変わらない）。
            var curRep = startRep
            for ((i, j) in diffs) {
                if (out()) break
                if (cur[i][j] == alt[i][j] || !movable(i, j)) continue
                val partner = if (!pairMoves) null else (0 until p.S).firstOrNull { b ->
                    b != i && cur[b][j] != alt[b][j] && cur[b][j] == alt[i][j] && alt[b][j] == cur[i][j] && movable(b, j)
                }
                val cells = if (partner != null) listOf(i, partner) else listOf(i)
                val saved = cells.map { cur[it][j] }
                for (a in cells) cur[a][j] = alt[a][j]
                fun undo() { cells.forEachIndexed { x, a -> cur[a][j] = saved[x] } }
                // PLAIN は 3.727.22 の歩きのまま（回数固定を崩した盤面は採らずに先へ進む＝道筋は戻さない）。
                if (exactPinRegression(p, start, cur)) { if (pairMoves) undo(); continue }
                val rep = UnifiedViolationChecker.check(state, cur)
                if (undoHardIncrease && rep.hard > curRep.hard) { undo(); continue }
                curRep = rep
                if (betterReport(rep, bestRep)) {
                    best = cur.copy2D(); bestRep = rep; applied++
                } else if (rep.hard == bestRep.hard && !dominates(bestRep, rep) && rep.breakdown != bestRep.breakdown) {
                    pool.add(cur.copy2D() to rep)
                }
            }
        }
        val tradeoffs = pickTradeoffs(bestRep, pool)
        val logs = listOf(MirrorLog(level = "I", tag = "SeesawRelink",
            message = "シーソーの Path Relinking: 案 ${alternatives.size}・採用 ${applied}・交換の候補 ${tradeoffs.size}（集めた ${pool.size}）・重み ${bestRep.weightedScore.toLong()}"))
        return Result(best, bestRep, applied, tradeoffs.map { it.first }, logs)
    }

    /**
     * 交換の候補を選ぶ: 最終の本命に優越されず、必須の件数が同じ盤面のうち、互いに優越されないものを残し、族の件数ベクトルが同じものは 1 つに。
     * 並びは重み付きの点数の低い順（本命から譲る量が小さい順）で、本命より良くなる族の組が違うものを優先して最大 [MAX_TRADEOFFS]。
     */
    internal fun pickTradeoffs(best: ViolationReport, pool: List<Pair<Array<IntArray>, ViolationReport>>): List<Pair<Array<IntArray>, ViolationReport>> {
        val ok = pool.filter { (_, r) -> r.hard == best.hard && !dominates(best, r) && r.breakdown != best.breakdown }
        val nd = ok.filter { (_, r) -> ok.none { (_, o) -> o !== r && dominates(o, r) } }
            .distinctBy { (_, r) -> MirrorKeys.all.map { r.breakdown[it] ?: 0 } }
            .sortedBy { it.second.weightedScore }
        val picked = ArrayList<Pair<Array<IntArray>, ViolationReport>>()
        val seen = HashSet<Set<String>>()
        for (c in nd) {
            val better = MirrorKeys.all.filter { (c.second.breakdown[it] ?: 0) < (best.breakdown[it] ?: 0) }.toSet()
            if (seen.add(better)) picked.add(c)
            if (picked.size >= MAX_TRADEOFFS) break
        }
        for (c in nd) { if (picked.size >= MAX_TRADEOFFS) break; if (c !in picked) picked.add(c) }
        return picked
    }
}
