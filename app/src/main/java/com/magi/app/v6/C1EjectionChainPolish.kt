package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlin.random.Random

/**
 * [c1 月全体の玉突き連鎖, 測定中・既定 OFF＝`PolishGate.c1EjectionChain`] c1 不足窓のセルに不足シフトを置き、
 * その手が作った歪みを「直前に触った職員の行（全日）」と「直前に触った日の列（全職員）」の中から直す手を
 * 継ぎ足す深さ優先探索。途中の盤面は HARD が一時的に増えてよく（終点だけ採否）、評価は [DeltaEvaluator] の
 * apply/undo。連鎖全体を正式 checker の `adoptionGate`（betterReport＋厳密ピン）で 1 回だけ採否する。
 *
 * 広域ビーム（[C1WindowPolish.applyC1BeamPolish]）との違い: ビームは各段で HARD≦開始を要求し、継ぎ足しは同日の
 * covU 玉突き（`findCovUChain`）に限る。ここは継ぎ足し先を日をまたいで職員の行にも広げ、途中の HARD 増を許す。
 */
internal object C1EjectionChainPolish {

    data class Config(
        val maxDepth: Int = 8,
        /** 深さごとの分岐数（尽きたら最後の値）。 */
        val branching: IntArray = intArrayOf(4, 3, 2, 2, 1),
        /** 途中の盤面で許す HARD の増分。 */
        val hardSlack: Int = 2,
        val maxMillis: Long = 3_000L,
        /** 0 以下＝無制限。決定的モードではこちらで止める。 */
        val maxEvaluations: Long = 0L,
        val maxRounds: Int = 4,
        /** true＝v2（族越え）: 直前の手で悪化した族を重み順に修復対象とし、一か月全日×全職員から次の手を探す。false＝v1（直前の行と列）。 */
        val crossFamily: Boolean = defaultCrossFamily,
        /** v2: Δ評価の上位から族別差分まで調べる候補数。 */
        val familyProbe: Int = 48,
    )

    /** 測定用の切替（v1/v2 を同条件で比べる）。 */
    @Volatile internal var defaultCrossFamily: Boolean = true

    class Stats { var seeds = 0; var chainsTried = 0; var accepted = 0; var evaluations = 0L; var timeouts = 0 }

    private fun familyWeighted(de: DeltaEvaluator): LongArray {
        val raw = de.familyRaw()
        val out = LongArray(FAMILIES.size + 1)
        for ((idx, f) in FAMILIES.withIndex()) out[idx] = ((raw[f] ?: 0L) * MirrorKeys.weightOf(f)).toLong()
        out[FAMILIES.size] = de.rangeWeighted()
        return out
    }
    private val FAMILIES = listOf("c1", "c2", "c41", "c42", "c41s", "c42s", "c3", "c3n", "c3m", "c3mn",
        "pref", "groupViol", "c3w", "apt", "fair", "weekly", "covO", "covU")

    fun apply(
        state: MagiState, schedule: Array<IntArray>, config: Config = Config(),
        shouldStop: () -> Boolean = { false }, quantitativeRangeEval: Boolean = false, stats: Stats = Stats(),
    ): V6HotfixPasses.CyclicSwapResult {
        val t0 = EngineClock.nowMs()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        var rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        val before = rep
        val hasUnassigned = work.any { row -> row.any { it !in 0 until p.K } }
        if (p.cons1.isEmpty() || hasUnassigned || (rep.breakdown["c1"] ?: 0) == 0) {
            return V6HotfixPasses.CyclicSwapResult(work, rep.total, rep.total, 0,
                listOf(MirrorLog(tag = "C1EjectionChain", message = "対象なし=スキップ")), report = rep)
        }
        val de = DeltaEvaluator(p)
        de.reset(work)
        val zob = Random(0xE1EC7L).let { r -> Array(p.S) { Array(p.T) { LongArray(p.K) { r.nextLong() } } } }
        var hash = 0L
        for (i in 0 until p.S) for (j in 0 until p.T) hash = hash xor zob[i][j][work[i][j]]
        val pinBlocks = PinBlockAttribution()

        fun out(): Boolean = shouldStop() ||
            (config.maxEvaluations > 0 && stats.evaluations >= config.maxEvaluations) ||
            (config.maxEvaluations <= 0 && EngineClock.nowMs() - t0 >= config.maxMillis)

        fun hardOf(score: Long) = score / SCORE_HARD_UNIT

        fun move(i: Int, j: Int, k: Int) {
            hash = hash xor zob[i][j][work[i][j]] xor zob[i][j][k]
            work[i][j] = k
            de.apply(i, j, k)
        }

        var applied = 0
        var round = 0
        while (round < config.maxRounds && !out()) {
            round++
            var improvedThisRound = false
            val seeds = ArrayList<IntArray>()
            for (c in p.cons1) {
                val x = c.shiftIdx
                if (x !in 0 until p.K || c.day1 <= 0) continue
                for (i in 0 until p.S) {
                    if (!p.mayPlace(i, x)) continue
                    for (j in 0 until p.T) {
                        if (work[i][j] == x || p.wishLocked(i, j)) continue
                        if (inDeficientC1Window(p, work, i, x, c.day1, c.day2, j)) seeds.add(intArrayOf(i, j, x))
                    }
                }
            }
            for (seed in seeds) {
                if (out()) break
                val (si, sj, sx) = Triple(seed[0], seed[1], seed[2])
                if (work[si][sj] == sx || p.cons1.none { it.shiftIdx == sx && it.day1 > 0 && inDeficientC1Window(p, work, si, sx, it.day1, it.day2, sj) }) continue
                stats.seeds++
                val baseScore = de.score()
                val baseHard = hardOf(baseScore)
                val visited = HashSet<Long>()
                val path = ArrayList<IntArray>()   // (i, j, old, new)
                var bestScore = baseScore
                var bestPath: List<IntArray> = emptyList()
                val touched = HashSet<Int>()

                fun dfs(depth: Int, li: Int, lj: Int, famPrev: LongArray) {
                    val s = de.score()
                    if (s < bestScore) { bestScore = s; bestPath = path.map { it.copyOf() } }
                    if (depth >= config.maxDepth || out()) return
                    val cand = ArrayList<LongArray>()
                    fun consider(i: Int, j: Int) {
                        if (touched.contains(i * p.T + j) || p.wishLocked(i, j)) return
                        val cur = work[i][j]
                        for (k in p.allowedShiftsForStaff(i)) {
                            if (k == cur) continue
                            val sc = de.previewMove(i, j, k); stats.evaluations++
                            if (hardOf(sc) > baseHard + config.hardSlack) continue
                            if (visited.contains(hash xor zob[i][j][cur] xor zob[i][j][k])) continue
                            cand.add(longArrayOf(sc, i.toLong(), j.toLong(), k.toLong()))
                        }
                    }
                    val famNow = if (config.crossFamily) familyWeighted(de) else famPrev
                    if (config.crossFamily) {
                        for (i2 in 0 until p.S) { for (j2 in 0 until p.T) consider(i2, j2); if (out()) { stats.timeouts++; return } }
                        cand.sortWith(compareBy<LongArray>({ it[0] }, { it[1] }, { it[2] }, { it[3] }))
                        val worse = LongArray(famNow.size) { maxOf(0L, famNow[it] - famPrev[it]) }
                        if (worse.any { it > 0 }) {
                            val top = cand.take(config.familyProbe)
                            val gain = HashMap<LongArray, Long>()
                            for (c in top) {
                                val i = c[1].toInt(); val j = c[2].toInt(); val k = c[3].toInt(); val old = work[i][j]
                                work[i][j] = k; de.apply(i, j, k)
                                val f2 = familyWeighted(de)
                                work[i][j] = old; de.apply(i, j, old)
                                var g = 0L
                                for (x in worse.indices) if (worse[x] > 0) g += minOf(worse[x], maxOf(0L, famNow[x] - f2[x]))
                                gain[c] = g
                            }
                            cand.clear(); cand.addAll(top.sortedWith(compareBy<LongArray>({ -(gain[it] ?: 0L) }, { it[0] }, { it[1] }, { it[2] }, { it[3] })))
                        }
                    } else {
                        for (j2 in 0 until p.T) if (j2 != lj) consider(li, j2)
                        for (i2 in 0 until p.S) if (i2 != li) consider(i2, lj)
                        cand.sortWith(compareBy<LongArray>({ it[0] }, { it[1] }, { it[2] }, { it[3] }))
                    }
                    val b = config.branching[minOf(depth, config.branching.size - 1)]
                    var taken = 0
                    for (c in cand) {
                        if (taken >= b || out()) break
                        val i = c[1].toInt(); val j = c[2].toInt(); val k = c[3].toInt()
                        val nh = hash xor zob[i][j][work[i][j]] xor zob[i][j][k]
                        if (!visited.add(nh)) continue
                        taken++
                        stats.chainsTried++
                        val old = work[i][j]
                        path.add(intArrayOf(i, j, old, k)); touched.add(i * p.T + j)
                        move(i, j, k)
                        dfs(depth + 1, i, j, famNow)
                        move(i, j, old)
                        path.removeAt(path.size - 1); touched.remove(i * p.T + j)
                    }
                }

                val old0 = work[si][sj]
                path.add(intArrayOf(si, sj, old0, sx)); touched.add(si * p.T + sj)
                visited.add(hash)
                val fam0 = if (config.crossFamily) familyWeighted(de) else LongArray(0)
                move(si, sj, sx)
                visited.add(hash)
                dfs(1, si, sj, fam0)
                move(si, sj, old0)
                path.clear()

                if (bestPath.isEmpty() || bestScore >= baseScore) continue
                val prev = Array(p.S) { work[it].copyOf() }
                for (m in bestPath) move(m[0], m[1], m[3])
                val rep2 = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                if (adoptionGate(p, prev, work, rep2, rep, pinBlocks).accepted) {
                    rep = rep2; applied += bestPath.size; stats.accepted++; improvedThisRound = true
                } else {
                    for (m in bestPath.asReversed()) move(m[0], m[1], m[2])
                }
            }
            if (!improvedThisRound) break
        }
        val logs = listOf(MirrorLog(tag = "C1EjectionChain",
            message = "期間要件(c1)玉突き連鎖${if (config.crossFamily) "v2" else "v1"}[起点${stats.seeds}/連鎖${stats.chainsTried}/採用${stats.accepted}/評価${stats.evaluations}/時間切れ${stats.timeouts}/${EngineClock.nowMs() - t0}ms]: " +
                "c1 ${before.breakdown["c1"] ?: 0}->${rep.breakdown["c1"] ?: 0} score ${before.weightedScore.toLong()}->${rep.weightedScore.toLong()} HARD ${before.hard}->${rep.hard}"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, rep.total, applied, logs, pinBlocks = pinBlocks, report = rep)
    }
}
