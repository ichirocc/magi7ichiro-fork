package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [ソフト起点の玉突き連鎖, 測定中・既定 OFF＝`PolishGate.allFamilyEjectionChain`] 正式評価器が返す違反の座標を
 * そのまま「玉」にし（職員の行・日の列へ広げない）、その玉の族の生件数を減らす手だけで連鎖する。
 * 手は 5 種（回数の交換・回数の 1 セル変更・人員過剰の移動・窓の 1 セル変更・公平/曜日の交換）に限る。
 * 次の玉は直前の手が触った日か起点の窓と日が重なるものだけ。候補が尽きたら枝を閉じる（範囲内の山登りで代用しない）。
 * 採否は終点だけ: `adoptionGate` に加えて必須 5 族のどれも生件数が増えないこと。
 */
internal object SoftEjectionChain {

    /** 測定・テスト用: 玉ごとに作った手の数。本番では null。 */
    @Volatile internal var genProbe: ((Ball, Int) -> Unit)? = null

    /** 測定・テスト用: 初手の (起点の族, 打つ前の加重生件数, 打った後)。本番では null。 */
    @Volatile internal var firstMoveProbe: ((String, Long, Long) -> Unit)? = null

    private val HARD_FAMILIES = listOf("groupViol", "c3n", "covU", "pref", "c3w")

    private const val COUNT = 0
    private const val COVO = 1
    private const val WINDOW = 2
    private const val FAIR = 3
    private const val WEEKLY = 4

    /** 玉。dir: 回数の玉で +1＝多すぎ・-1＝少なすぎ・0＝不明（c2）。days==null は月全体（回数・公平・曜日）。 */
    internal class Ball(val fam: String, val kind: Int, val i: Int, val k: Int, val dir: Int, val days: IntArray?, val cells: List<IntArray>)

    /** 1 手＝(i, j, k) の並び。 */
    private class Move(val cells: IntArray) {
        fun key(): String = cells.joinToString(",")
    }

    internal fun ballsOf(p: Problem, work: Array<IntArray>, rep: ViolationReport, families: Set<String>): List<Ball> {
        val out = ArrayList<Ball>()
        for ((key, classes) in rep.countFamilies) {
            val (i, k) = key.split(",").map { it.toInt() }
            if (k !in 0 until p.K) continue
            for (c in classes) {
                val cls = c.removePrefix("vio-")
                val (fam, dir) = when (cls) {
                    "low" -> "low" to -1; "high" -> "high" to 1
                    "aptLow" -> "apt" to -1; "aptHigh" -> "apt" to 1
                    "c2" -> "c2" to 0
                    else -> continue
                }
                if (fam in families) out.add(Ball(fam, COUNT, i, k, dir, null, emptyList()))
            }
        }
        for ((key, classes) in rep.needFamilies) {
            val (k, j) = key.split(",").map { it.toInt() }
            for (c in classes) {
                val fam = c.removePrefix("vio-")
                if (fam !in families) continue
                when (fam) {
                    "covO" -> out.add(Ball(fam, COVO, -1, k, 0, intArrayOf(j), emptyList()))
                    "c41", "c41s" -> {
                        val cells = (0 until p.S).filter { p.mayPlace(it, k) }.map { intArrayOf(it, j) }
                        out.add(Ball(fam, WINDOW, -1, k, 0, intArrayOf(j), cells))
                    }
                }
            }
        }
        if ("c1" in families) for (r in rep.c1Runs) {
            val i = r[0]; val lo = r[1]; val hi = minOf(p.T - 1, r[1] + r[2] - 1 + r[3] - 1)
            if (hi < lo) continue
            out.add(Ball("c1", WINDOW, i, -1, 0, (lo..hi).toList().toIntArray(), (lo..hi).map { intArrayOf(i, it) }))
        }
        // c3 族: 印のある日を職員ごとに連続で束ね、パターン長ぶん先まで（run-deficit は先頭 1 日にだけ印が付く）。
        val c3len = mapOf("c3" to p.cons3, "c3m" to p.cons3m, "c3mn" to p.cons3mn).mapValues { (_, l) -> l.maxOfOrNull { it.seq.size } ?: 1 }
        val marks = HashMap<String, java.util.TreeMap<Int, java.util.TreeSet<Int>>>()
        for ((key, classes) in rep.cellFamilies) {
            val (i, j) = key.split(",").map { it.toInt() }
            for (c in classes) {
                val fam = c.removePrefix("vio-")
                if (fam !in families) continue
                when (fam) {
                    "c3", "c3m", "c3mn" -> marks.getOrPut(fam) { java.util.TreeMap() }.getOrPut(i) { java.util.TreeSet() }.add(j)
                    "c42", "c42s" -> marks.getOrPut(fam) { java.util.TreeMap() }.getOrPut(j) { java.util.TreeSet() }.add(i)
                }
            }
        }
        for ((fam, m) in marks) {
            if (fam == "c42" || fam == "c42s") {
                for ((j, staff) in m) out.add(Ball(fam, WINDOW, -1, -1, 0, intArrayOf(j), staff.map { intArrayOf(it, j) }))
                continue
            }
            val ext = (c3len[fam] ?: 1) - 1
            for ((i, days) in m) {
                var start = -1; var prev = -10
                fun flush() {
                    if (start < 0) return
                    val hi = minOf(p.T - 1, prev + ext)
                    val d = (start..hi).toList()
                    out.add(Ball(fam, WINDOW, i, -1, 0, d.toIntArray(), d.map { intArrayOf(i, it) }))
                }
                for (j in days) { if (j > prev + 1) { flush(); start = j }; prev = j }
                flush()
            }
        }
        for (l in rep.distLocations["fair"].orEmpty()) if ("fair" in families) out.add(Ball("fair", FAIR, l[0], l[1], 0, null, emptyList()))
        for (l in rep.distLocations["weekly"].orEmpty()) if ("weekly" in families && l.size >= 3) out.add(Ball("weekly", WEEKLY, l[0], l[1], 0, null, emptyList()))
        return out
    }

    fun apply(
        state: MagiState, schedule: Array<IntArray>, config: C1EjectionChainPolish.Config,
        families: Set<String>, shouldStop: () -> Boolean, quantitativeRangeEval: Boolean,
        stats: C1EjectionChainPolish.Stats = C1EjectionChainPolish.Stats(),
    ): V6HotfixPasses.CyclicSwapResult {
        val t0 = EngineClock.nowMs()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        var rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        val before = rep
        val fams = families.intersect(MirrorKeys.soft.toSet())
        val hasUnassigned = work.any { row -> row.any { it !in 0 until p.K } }
        if (hasUnassigned || fams.isEmpty() || fams.none { (rep.breakdown[it] ?: 0) > 0 }) {
            return V6HotfixPasses.CyclicSwapResult(work, rep.total, rep.total, 0,
                listOf(MirrorLog(tag = "SoftEjectionChain", message = "対象なし=スキップ")), report = rep)
        }
        val de = DeltaEvaluator(p)
        de.reset(work)
        val pinBlocks = PinBlockAttribution()

        fun out(): Boolean {
            val why = when {
                shouldStop() -> "中断"
                config.maxEvaluations > 0 && stats.evaluations >= config.maxEvaluations -> "評価上限"
                config.maxCandidates > 0 && stats.generated >= config.maxCandidates -> "生成上限"
                config.maxEvaluations <= 0 && config.maxCandidates <= 0 && EngineClock.nowMs() - t0 >= config.maxMillis -> "時間切れ"
                else -> return false
            }
            stats.endReason = why
            return true
        }
        fun hardOf(s: Long) = s / SCORE_HARD_UNIT
        fun softOf(s: Long) = s - hardOf(s) * SCORE_HARD_UNIT
        fun rawW(fam: String): Long {
            val raw = if (fam == "low" || fam == "high") de.rangeRaw().let { if (fam == "low") it.first else it.second } else de.familyRaw()[fam] ?: 0L
            return (raw * MirrorKeys.weightOf(fam)).toLong()
        }
        val touched = HashSet<Int>()
        fun free(i: Int, j: Int) = !p.wishLocked(i, j) && !touched.contains(i * p.T + j)
        fun pinned(i: Int, k: Int): Boolean {
            val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
            return lo != Int.MIN_VALUE && hi != Int.MAX_VALUE && lo == hi
        }
        fun colCount(k: Int, j: Int): Int { var n = 0; for (i in 0 until p.S) if (work[i][j] == k) n++; return n }
        /** 移動元の (シフト, 日) が人員の下限を割らない。 */
        fun keepsFloor(k: Int, j: Int) = p.covUCell(k, j, colCount(k, j) - 1) <= p.covUCell(k, j, colCount(k, j))

        fun legal(m: IntArray): Boolean {
            val delta = HashMap<Long, Int>()
            var x = 0
            while (x < m.size) {
                val i = m[x]; val j = m[x + 1]; val k = m[x + 2]
                if (!free(i, j) || !p.mayPlace(i, k) || work[i][j] == k) return false
                delta.merge(i.toLong() * p.K + work[i][j], -1, Int::plus)
                delta.merge(i.toLong() * p.K + k, 1, Int::plus)
                x += 3
            }
            for ((key, d) in delta) if (d != 0 && pinned((key / p.K).toInt(), (key % p.K).toInt())) return false
            return true
        }
        fun movesOf(b: Ball): List<IntArray> {
            val ms = ArrayList<IntArray>()
            when (b.kind) {
                COUNT -> {
                    val i = b.i; val k = b.k
                    // 回数の交換: 同じ日・同じシフトで反対向きの回数違反を持つ相手と入れ替える（被覆は不変）。
                    if (b.dir != 0) {
                        val opp = when (b.fam) { "low", "high" -> if (b.dir > 0) "vio-low" else "vio-high"; else -> if (b.dir > 0) "vio-aptLow" else "vio-aptHigh" }
                        for (i2 in 0 until p.S) {
                            if (i2 == i || rep.countFamilies["$i2,$k"]?.contains(opp) != true) continue
                            for (j in 0 until p.T) {
                                val (from, to) = if (b.dir > 0) i to i2 else i2 to i
                                val y = work[to][j]
                                if (work[from][j] == k && y != k) ms.add(intArrayOf(from, j, y, to, j, k))
                            }
                        }
                    }
                    // 回数の 1 セル変更。人員過剰が増える手は人員過剰が玉の対象のときだけ（次の玉として拾える）。
                    fun covOGrows(y: Int, j: Int) = "covO" !in fams && p.covOCell(y, j, colCount(y, j) + 1) > p.covOCell(y, j, colCount(y, j))
                    for (j in 0 until p.T) {
                        val cur = work[i][j]
                        if (cur == k && b.dir >= 0) {
                            if (!keepsFloor(k, j)) continue
                            for (y in p.allowedShiftsForStaff(i)) if (y != k && !covOGrows(y, j)) ms.add(intArrayOf(i, j, y))
                        } else if (cur != k && b.dir <= 0) {
                            if (!keepsFloor(cur, j) || covOGrows(k, j)) continue
                            ms.add(intArrayOf(i, j, k))
                        }
                    }
                }
                COVO -> {
                    val j = b.days!![0]; val k = b.k
                    for (i in 0 until p.S) {
                        if (work[i][j] != k) continue
                        p.restIdx?.let { r -> if (r != k) ms.add(intArrayOf(i, j, r)) }
                        for (y in p.allowedShiftsForStaff(i)) if (y != k && y != p.restIdx && p.covUCell(y, j, colCount(y, j)) > 0) ms.add(intArrayOf(i, j, y))
                    }
                }
                WINDOW -> for (c in b.cells) {
                    val i = c[0]; val j = c[1]
                    if (b.k >= 0) {
                        // c41/c41s: そのシフトを担当できる職員だけ（cells が既に絞ってある）。
                        if (work[i][j] == b.k) { for (y in p.allowedShiftsForStaff(i)) if (y != b.k) ms.add(intArrayOf(i, j, y)) }
                        else ms.add(intArrayOf(i, j, b.k))
                    } else for (y in p.allowedShiftsForStaff(i)) if (y != work[i][j]) ms.add(intArrayOf(i, j, y))
                }
                FAIR -> {
                    val i = b.i; val k = b.k
                    for (i2 in p.groupMembers[p.sgrp[i]]) {
                        if (i2 == i) continue
                        for (j in 0 until p.T) {
                            val a = work[i][j]; val c = work[i2][j]
                            if (a == c || (a != k && c != k)) continue
                            ms.add(intArrayOf(i, j, c, i2, j, a))
                        }
                    }
                }
                WEEKLY -> {
                    val i = b.i; val k = b.k
                    val wd = IntArray(7); var n = 0
                    for (j in 0 until p.T) if (work[i][j] == k) { wd[(p.dow0 + j) % 7]++; n++ }
                    for (a in 0 until p.T) {
                        if (work[i][a] != k || 7 * wd[(p.dow0 + a) % 7] <= n) continue
                        for (bd in 0 until p.T) {
                            val y = work[i][bd]
                            if (y == k || 7 * wd[(p.dow0 + bd) % 7] >= n) continue
                            for (i2 in 0 until p.S) if (i2 != i && work[i2][a] == y && work[i2][bd] == k) ms.add(intArrayOf(i, a, y, i, bd, k, i2, a, k, i2, bd, y))
                        }
                    }
                }
            }
            return ms
        }

        val path = ArrayList<IntArray>()   // (i, j, old, new)
        fun applyMove(m: IntArray) {
            var x = 0
            while (x < m.size) {
                val i = m[x]; val j = m[x + 1]
                path.add(intArrayOf(i, j, work[i][j], m[x + 2])); touched.add(i * p.T + j)
                work[i][j] = m[x + 2]; de.apply(i, j, m[x + 2]); x += 3
            }
        }
        fun undoMove(m: IntArray) {
            repeat(m.size / 3) {
                val e = path.removeAt(path.size - 1)
                work[e[0]][e[1]] = e[2]; de.apply(e[0], e[1], e[2]); touched.remove(e[0] * p.T + e[1])
            }
        }
        /** 玉を減らす手を良い順に [玉の加重減少, 移動後のソフト, 先頭セル] で並べる。 */
        fun scored(balls: List<Ball>, baseHard: Long): List<LongArray> {
            val best = HashMap<String, LongArray>()
            val keep = HashMap<String, IntArray>()
            for (b in balls) {
                val r0 = rawW(b.fam)
                val ms = movesOf(b)
                genProbe?.invoke(b, ms.size)
                for (m in ms) {
                    if (out()) break
                    if (!legal(m)) continue
                    stats.generated++
                    applyMove(m); stats.evaluations++
                    val gain = r0 - rawW(b.fam); val s = de.score()
                    undoMove(m)
                    if (gain <= 0 || hardOf(s) > baseHard + config.hardSlack) continue
                    val key = Move(m).key()
                    val row = longArrayOf(-gain, softOf(s), m[0].toLong(), m[1].toLong(), m[2].toLong(), s)
                    val prev = best[key]
                    if (prev == null || CAND.compare(row, prev) < 0) { best[key] = row; keep[key] = m }
                }
            }
            stats.candidates += best.size
            return best.entries.sortedWith { a, b -> CAND.compare(a.value, b.value) }.map { e -> longArrayOf(*e.value, -1) + keep[e.key]!!.map { it.toLong() } }
        }
        fun moveOf(c: LongArray): IntArray = IntArray(c.size - 7) { c[7 + it].toInt() }
        fun daysOf(m: IntArray): Set<Int> = (m.indices step 3).map { m[it + 1] }.toSet()
        fun overlaps(b: Ball, days: Set<Int>) = b.days == null || b.days.any { it in days }

        var applied = 0
        var round = 0
        while (round < config.maxRounds && !out()) {
            round++
            var improved = false
            val byFam = ballsOf(p, work, rep, fams).groupBy { it.fam }
            val lists = byFam.entries.sortedWith(compareBy({ -MirrorKeys.weightOf(it.key) }, { it.key })).map { (f, us0) ->
                val off = ((round - 1) * config.unitsPerFamily) % us0.size
                f to (us0.drop(off) + us0.take(off)).take(config.unitsPerFamily)
            }
            val seeds = ArrayList<Ball>()
            var n = 0
            while (true) { var any = false; for ((_, l) in lists) if (n < l.size) { seeds.add(l[n]); any = true }; if (!any) break; n++ }
            for (seed in seeds) {
                if (out()) break
                stats.seeds++
                val famStat = stats.byFamily.getOrPut(seed.fam) { LongArray(4) }
                famStat[0]++
                val evalAtSeed = stats.evaluations
                fun seedSpent() = config.perSeedEvaluations > 0 && stats.evaluations - evalAtSeed >= config.perSeedEvaluations
                val baseScore = de.score(); val baseHard = hardOf(baseScore)
                var bestScore = baseScore
                var bestPath: List<IntArray> = emptyList()
                val originDays = seed.days?.toSet() ?: emptySet()
                fun dfs(depth: Int, lastDays: Set<Int>) {
                    val s = de.score()
                    if (s < bestScore) { bestScore = s; bestPath = path.map { it.copyOf() } }
                    if (depth >= MAX_DEPTH || out() || seedSpent()) return
                    val now = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                    val near = lastDays + originDays
                    val balls = ballsOf(p, work, now, fams).filter { overlaps(it, near) }
                    val savedRep = rep; rep = now
                    val cand = scored(balls, baseHard)
                    rep = savedRep
                    val b = BRANCH[minOf(depth - 1, BRANCH.size - 1)]
                    for (c in cand.take(b)) {
                        if (out() || seedSpent()) break
                        val m = moveOf(c)
                        stats.chainsTried++
                        applyMove(m)
                        dfs(depth + 1, daysOf(m))
                        undoMove(m)
                    }
                }
                for (c in scored(listOf(seed), baseHard).take(config.seedBranch)) {
                    if (out() || seedSpent()) break
                    val m = moveOf(c)
                    stats.chainsTried++
                    val r0 = rawW(seed.fam)
                    applyMove(m)
                    firstMoveProbe?.invoke(seed.fam, r0, rawW(seed.fam))
                    dfs(1, daysOf(m))
                    undoMove(m)
                }
                famStat[1] += stats.evaluations - evalAtSeed
                if (bestPath.isEmpty() || bestScore >= baseScore) continue
                val prev = Array(p.S) { work[it].copyOf() }
                for (e in bestPath) { work[e[0]][e[1]] = e[3]; de.apply(e[0], e[1], e[3]) }
                val rep2 = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                C1EjectionChainPolish.deltaMismatch(de, rep2)?.let { diff ->
                    stats.mismatch = "起点=${seed.fam} 手順=" + bestPath.joinToString(";") { "${it[0]},${it[1]}:${it[2]}->${it[3]}" } + " " + diff
                    stats.endReason = "差分不一致"
                }
                val hardOk = HARD_FAMILIES.all { (rep2.breakdown[it] ?: 0) <= (rep.breakdown[it] ?: 0) }
                if (stats.mismatch == null && hardOk && adoptionGate(p, prev, work, rep2, rep, pinBlocks).accepted) {
                    famStat[2]++; famStat[3] += (rep.weightedScore - rep2.weightedScore).toLong()
                    rep = rep2; applied += bestPath.size; stats.accepted++; improved = true
                    stats.acceptedMaxDepth = maxOf(stats.acceptedMaxDepth, bestPath.size)
                } else {
                    for (e in bestPath.asReversed()) { work[e[0]][e[1]] = e[2]; de.apply(e[0], e[1], e[2]) }
                }
                if (stats.mismatch != null) break
            }
            if (!improved || stats.mismatch != null) break
        }
        if (stats.endReason == "時間切れ") stats.timeouts++
        val logs = listOf(MirrorLog(tag = "SoftEjectionChain",
            message = "ソフト起点玉突き連鎖[対象${fams.sorted().joinToString(",")}/起点${stats.seeds}/生成${stats.generated}/候補${stats.candidates}/評価${stats.evaluations}/試行${stats.chainsTried}/採用${stats.accepted}/採用深さ最大${stats.acceptedMaxDepth}/終了${stats.endReason}/${EngineClock.nowMs() - t0}ms]: " +
                "score ${before.weightedScore.toLong()}->${rep.weightedScore.toLong()} HARD ${before.hard}->${rep.hard} total ${before.total}->${rep.total} 族差 " +
                (before.breakdown.keys + rep.breakdown.keys).sorted().mapNotNull { f -> val d = (rep.breakdown[f] ?: 0) - (before.breakdown[f] ?: 0); if (d != 0) "$f${if (d > 0) "+" else ""}$d" else null }.joinToString(" ") +
                " 起点族別[" + stats.byFamily.entries.joinToString(" ") { (f, v) -> "$f:起点${v[0]}/評価${v[1]}/採用${v[2]}/減${v[3]}" } + "]" +
                (stats.mismatch?.let { " 差分不一致: $it" } ?: "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, rep.total, applied, logs, pinBlocks = pinBlocks, report = rep)
    }

    /** 深さ（初手を含む）と、初手より後の深さごとの分岐数。 */
    private const val MAX_DEPTH = 4
    private val BRANCH = intArrayOf(3, 2, 1)

    private val CAND = compareBy<LongArray>({ it[0] }, { it[1] }, { it[2] }, { it[3] }, { it[4] })
}
