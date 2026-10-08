package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [全SOFT玉突き連鎖, 測定中・既定 OFF＝`PolishGate.softCascade`] 後処理の最後の盤面変更（人員過剰の退避の直後・HF70 の前）。
 * 15 ソフト族の違反を、正式評価器の場所マップ（`countFamilies`/`needFamilies`/`cellFamilies`/`c1Runs`/`distLocations`）の
 * 座標のまま玉にする（職員の全日・日の全職員へ広げない）。手は 8 種で、どれも起点の族の生件数を減らすものだけ。
 * 深さ 2 以降は親の手の日と重なる玉、または親の手で増えた族の玉だけを打つ（無関係な違反への山登りで埋めない）。
 * ビームの節はすべて正式評価を持ち、必須 5 族はどの節でも族ごとに root 以下、ソフトの増分は加重負債 [Config.maxDebt] まで。
 * 返すのは root より `betterReport` が真の最良節、無ければ root（入力の盤面と report のまま）。
 */
object SoftCascadePolish {

    data class Config(
        val maxDepth: Int = 4,
        val beamWidth: Int = 16,
        val maxChildrenPerNode: Int = 24,
        /** `UnifiedViolationChecker.check` の回数。 */
        val maxEvaluations: Int = 500,
        val maxChangedCells: Int = 12,
        val maxMillis: Long = 500L,
        /** ソフトの起点からの加重増分の上限（low 1 件分）。 */
        val maxDebt: Double = MirrorKeys.weightOf("low"),
    )

    data class Result(
        val newSchedule: Array<IntArray>,
        val report: ViolationReport,
        val applied: Int,
        val evaluated: Int,
        val screened: Int,
        val maxDepthReached: Int,
        val logs: List<MirrorLog>,
    )

    private const val COUNT = 0
    private const val COVO = 1
    private const val WINDOW = 2
    private const val C41 = 3
    private const val PAIR = 4
    private const val FAIR = 5
    private const val WEEKLY = 6

    /**
     * 玉。dir は回数の玉で +1＝多すぎ・-1＝少なすぎ・0＝不明（c2）。days は手が触ってよい日（null＝月全体の回数・偏り）。
     * span は深さ 2 以降の重なり判定に使う日（null＝月全体）。
     */
    internal class Ball(
        val fam: String, val kind: Int, val i: Int, val k: Int, val dir: Int,
        val days: IntArray?, val cells: List<IntArray>, val amount: Int,
    ) {
        val priority: Double get() = amount * MirrorKeys.weightOf(fam) + cells.size
    }

    private fun pair(key: String): Pair<Int, Int> { val c = key.indexOf(','); return key.substring(0, c).toInt() to key.substring(c + 1).toInt() }

    internal fun ballsOf(p: Problem, work: Array<IntArray>, rep: ViolationReport): List<Ball> {
        val out = ArrayList<Ball>()
        for ((key, classes) in rep.countFamilies) {
            val (i, k) = pair(key)
            if (i !in 0 until p.S || k !in 0 until p.K) continue
            var cnt = 0
            for (j in 0 until p.T) if (work[i][j] == k) cnt++
            for (c in classes) {
                val (fam, dir) = when (c.removePrefix("vio-")) {
                    "low" -> "low" to -1; "high" -> "high" to 1
                    "aptLow" -> "apt" to -1; "aptHigh" -> "apt" to 1
                    "c2" -> "c2" to 0
                    else -> continue
                }
                val amount = when (fam) {
                    "low" -> (p.rangeLo[i][k] - cnt).coerceAtLeast(1)
                    "high" -> (cnt - p.rangeHi[i][k]).coerceAtLeast(1)
                    else -> 1
                }
                out.add(Ball(fam, COUNT, i, k, dir, null, emptyList(), amount))
            }
        }
        for ((key, classes) in rep.needFamilies) {
            val (k, j) = pair(key)
            if (k !in 0 until p.K || j !in 0 until p.T) continue
            var got = 0
            for (i in 0 until p.S) if (work[i][j] == k) got++
            for (c in classes) when (val fam = c.removePrefix("vio-")) {
                "covO" -> out.add(Ball(fam, COVO, -1, k, 0, intArrayOf(j), emptyList(), p.covOCell(k, j, got).coerceAtLeast(1)))
                "c41", "c41s" -> {
                    val cells = (0 until p.S).filter { p.mayPlace(it, k) }.map { intArrayOf(it, j) }
                    out.add(Ball(fam, C41, -1, k, 0, intArrayOf(j), cells, 1))
                }
            }
        }
        for (r in rep.c1Runs) {
            if (r.size < 4) continue
            val i = r[0]; val lo = r[1]; val hi = minOf(p.T - 1, r[1] + r[2] - 1 + r[3] - 1)
            if (i !in 0 until p.S || hi < lo) continue
            val d = (lo..hi).toList()
            out.add(Ball("c1", WINDOW, i, -1, 0, d.toIntArray(), d.map { intArrayOf(i, it) }, r[2]))
        }
        // c3 族は職員ごとの印の連続日、c42 族は同じ日の印（checker は `mark(職員, 日)` だけ＝needFamilies には無い）。
        val runs = HashMap<String, java.util.TreeMap<Int, java.util.TreeSet<Int>>>()
        for ((key, classes) in rep.cellFamilies) {
            val (i, j) = pair(key)
            for (c in classes) when (val fam = c.removePrefix("vio-")) {
                "c3", "c3m", "c3mn" -> runs.getOrPut(fam) { java.util.TreeMap() }.getOrPut(i) { java.util.TreeSet() }.add(j)
                "c42", "c42s" -> runs.getOrPut(fam) { java.util.TreeMap() }.getOrPut(j) { java.util.TreeSet() }.add(i)
            }
        }
        for ((fam, m) in runs.toSortedMap()) {
            if (fam == "c42" || fam == "c42s") {
                for ((j, staff) in m) out.add(Ball(fam, PAIR, -1, -1, 0, intArrayOf(j), staff.map { intArrayOf(it, j) }, staff.size))
                continue
            }
            for ((i, days) in m) {
                var start = -1; var prev = -10
                fun flush() {
                    if (start < 0) return
                    val d = (start..prev).toList()
                    out.add(Ball(fam, WINDOW, i, -1, 0, d.toIntArray(), d.map { intArrayOf(i, it) }, d.size))
                }
                for (j in days) { if (j > prev + 1) { flush(); start = j }; prev = j }
                flush()
            }
        }
        for (l in rep.distLocations["fair"].orEmpty()) if (l.size >= 3) out.add(Ball("fair", FAIR, l[0], l[1], 0, null, emptyList(), kotlin.math.abs(l[2])))
        for (l in rep.distLocations["weekly"].orEmpty()) if (l.size >= 3) out.add(Ball("weekly", WEEKLY, l[0], l[1], 0, null, emptyList(), kotlin.math.abs(l[2])))
        return out.sortedWith(compareBy<Ball>({ -it.priority }, { it.fam }, { it.i }, { it.days?.firstOrNull() ?: -1 }, { it.k }))
    }

    private class Node(val board: Array<IntArray>, val report: ViolationReport, val changed: Set<Int>,
                       val depth: Int, val moveDays: Set<Int>, val grown: Set<String>)

    fun apply(
        state: MagiState, schedule: Array<IntArray>, config: Config, shouldStop: () -> Boolean,
        deadlineMs: Long, quantitativeRangeEval: Boolean,
    ): Result {
        val t0 = EngineClock.nowMs()
        val p = Problem(state, quantitativeRangeEval)
        val start = normalizeSchedule(schedule, p)
        val rootRep = UnifiedViolationChecker.check(state, start, quantitativeRangeEval)
        val root = Node(start, rootRep, emptySet(), 0, emptySet(), emptySet())
        var evaluated = 0; var screened = 0; var maxDepthReached = 0
        var why = "完了"
        fun out(): Boolean {
            val w = when {
                shouldStop() -> "中断"
                evaluated >= config.maxEvaluations -> "評価上限"
                EngineClock.nowMs() - t0 >= config.maxMillis -> "時間切れ"
                deadlineMs > 0 && EngineClock.remainingMs(deadlineMs) <= 0L -> "締切"
                else -> return false
            }
            why = w; return true
        }
        val shapeOk = start.size == p.S && start.all { row -> row.size == p.T && row.all { it in 0 until p.K } }
        fun softRaw(rep: ViolationReport, f: String) = rep.breakdown[f] ?: 0
        fun debt(rep: ViolationReport): Double =
            MirrorKeys.soft.sumOf { f -> maxOf(0, softRaw(rep, f) - softRaw(rootRep, f)) * MirrorKeys.weightOf(f) }
        fun hardOk(rep: ViolationReport) = MirrorKeys.hard.all { (rep.breakdown[it] ?: 0) <= (rootRep.breakdown[it] ?: 0) }
        var best = root
        if (shapeOk && MirrorKeys.soft.any { softRaw(rootRep, it) > 0 }) {
            var beam = listOf(root)
            val seen = HashSet<String>().apply { add(start.joinToString("|") { it.joinToString(",") }) }
            for (depth in 1..config.maxDepth) {
                if (out()) break
                val children = ArrayList<Node>()
                for (node in beam) {
                    if (out()) break
                    for (c in expand(p, node, depth, config, ::out)) {
                        if (out()) break
                        screened++
                        val key = c.first.joinToString("|") { it.joinToString(",") }
                        if (!seen.add(key)) continue
                        val rep = UnifiedViolationChecker.check(state, c.first, quantitativeRangeEval); evaluated++
                        if (!hardOk(rep) || debt(rep) > config.maxDebt) continue
                        val grown = MirrorKeys.soft.filter { softRaw(rep, it) > softRaw(node.report, it) }.toSet()
                        children.add(Node(c.first, rep, node.changed + c.second, depth, c.third, grown))
                    }
                }
                if (children.isEmpty()) break
                beam = children.sortedWith { a, b -> if (betterReport(a.report, b.report)) -1 else if (betterReport(b.report, a.report)) 1 else 0 }.take(config.beamWidth)
                maxDepthReached = depth
                for (c in beam) if (betterReport(c.report, best.report)) best = c
            }
        }
        val applied = if (best === root) 0 else best.changed.size
        val elapsed = EngineClock.nowMs() - t0
        val logs = listOf(MirrorLog(tag = "SoftCascade",
            message = "SoftCascade evaluated=$evaluated screened=$screened accepted=${if (applied > 0) 1 else 0} depth=$maxDepthReached elapsed=${elapsed}ms 終了=$why " +
                "score ${rootRep.weightedScore.toLong()}->${best.report.weightedScore.toLong()} total ${rootRep.total}->${best.report.total}"))
        return Result(best.board.map { it.copyOf() }.toTypedArray(), best.report, applied, evaluated, screened, maxDepthReached, logs)
    }

    /** 節から子（盤面・変更セル・触った日）を作る。玉の族を減らす手だけを、差分評価で必須の族を増やすものを除いて上位 [Config.maxChildrenPerNode] 個。 */
    private fun expand(p: Problem, node: Node, depth: Int, config: Config, out: () -> Boolean): List<Triple<Array<IntArray>, Set<Int>, Set<Int>>> {
        val work = node.board.map { it.copyOf() }.toTypedArray()
        val de = DeltaEvaluator(p); de.reset(work)
        var balls = ballsOf(p, work, node.report)
        if (depth >= 2) balls = balls.filter { b -> b.fam in node.grown || (b.days != null && b.days.any { it in node.moveDays }) || (b.days == null && b.i >= 0 && node.changed.any { it / p.T == b.i }) }
        fun rawW(f: String): Double {
            val raw = if (f == "low" || f == "high") de.rangeRaw().let { if (f == "low") it.first else it.second } else de.familyRaw()[f] ?: 0L
            return raw * MirrorKeys.weightOf(f)
        }
        fun hardRaw(): LongArray { val r = de.familyRaw(); return longArrayOf(r["c3n"] ?: 0, r["covU"] ?: 0, r["pref"] ?: 0, r["groupViol"] ?: 0, r["c3w"] ?: 0) }
        val h0 = hardRaw()
        val cand = ArrayList<Pair<DoubleArray, IntArray>>()
        val seenMoves = HashSet<String>()
        for (b in balls) {
            if (out()) break
            val r0 = rawW(b.fam)
            for (m in movesOf(p, work, node.report, b)) {
                if (out()) break
                // 変更セルの上限は、ここまでに触ったセルとの和集合で見る（同じセルを再び変える手を二重に数えない）。
                var union = node.changed.size
                for (x in 0 until m.size / 3) if ((m[3 * x] * p.T + m[3 * x + 1]) !in node.changed) union++
                if (union > config.maxChangedCells) continue
                if (!legal(p, work, m)) continue
                val key = m.joinToString(",")
                // 同じ手が別の玉からも出るときは、どの玉の件数も減らさないと分かった後でなく、減らす手として採った後に重複を省く。
                if (key in seenMoves) continue
                val olds = IntArray(m.size / 3)
                for (x in olds.indices) { val i = m[3 * x]; val j = m[3 * x + 1]; olds[x] = work[i][j]; work[i][j] = m[3 * x + 2]; de.apply(i, j, m[3 * x + 2]) }
                val gain = r0 - rawW(b.fam)
                val h = hardRaw()
                val soft = de.score() % SCORE_HARD_UNIT
                for (x in olds.indices.reversed()) { val i = m[3 * x]; val j = m[3 * x + 1]; work[i][j] = olds[x]; de.apply(i, j, olds[x]) }
                if (gain <= 0.0 || h.indices.any { h[it] > h0[it] }) continue
                seenMoves.add(key)
                cand.add(doubleArrayOf(-gain, soft.toDouble(), m[0].toDouble(), m[1].toDouble(), m[2].toDouble()) to m)
            }
        }
        cand.sortWith { a, b ->
            for (x in 0 until 5) { val c = a.first[x].compareTo(b.first[x]); if (c != 0) return@sortWith c }
            0
        }
        return cand.take(config.maxChildrenPerNode).map { (_, m) ->
            val nb = work.map { it.copyOf() }.toTypedArray()
            val cells = HashSet<Int>(); val days = HashSet<Int>()
            for (x in 0 until m.size / 3) { nb[m[3 * x]][m[3 * x + 1]] = m[3 * x + 2]; cells.add(m[3 * x] * p.T + m[3 * x + 1]); days.add(m[3 * x + 1]) }
            Triple(nb, cells, days)
        }
    }

    /** 希望固定・担当不可（上限 0 を含む `mayPlace`）・拡張希望の禁止を崩さず、値が実際に変わる手だけ。 */
    private fun legal(p: Problem, work: Array<IntArray>, m: IntArray): Boolean {
        var x = 0
        while (x < m.size) {
            val i = m[x]; val j = m[x + 1]; val k = m[x + 2]
            if (k !in 0 until p.K || p.wishLocked(i, j) || !p.mayPlaceAt(i, j, k) || work[i][j] == k) return false
            x += 3
        }
        return true
    }

    private fun movesOf(p: Problem, work: Array<IntArray>, rep: ViolationReport, b: Ball): List<IntArray> {
        val ms = ArrayList<IntArray>()
        fun colCount(k: Int, j: Int): Int { var n = 0; for (i in 0 until p.S) if (work[i][j] == k) n++; return n }
        fun makesCovU(k: Int, j: Int) = p.covUCell(k, j, colCount(k, j) - 1) > p.covUCell(k, j, colCount(k, j))
        fun growsCovO(k: Int, j: Int) = p.covOCell(k, j, colCount(k, j) + 1) > p.covOCell(k, j, colCount(k, j))
        when (b.kind) {
            COUNT -> {
                val i = b.i; val k = b.k
                if (b.dir != 0) {
                    // 1. 回数の交換: 同じ日・同じシフトで反対向きの相手と入れ替える（被覆は不変）。
                    val opp = when (b.fam) { "low", "high" -> if (b.dir > 0) "vio-low" else "vio-high"; else -> if (b.dir > 0) "vio-aptLow" else "vio-aptHigh" }
                    for (i2 in 0 until p.S) {
                        if (i2 == i || rep.countFamilies["$i2,$k"]?.contains(opp) != true) continue
                        val from = if (b.dir > 0) i else i2; val to = if (b.dir > 0) i2 else i
                        for (j in 0 until p.T) { val y = work[to][j]; if (work[from][j] == k && y != k) ms.add(intArrayOf(from, j, y, to, j, k)) }
                    }
                }
                // 2. 回数の 1 セル変更。移動元が人員不足を作る手は除く。人員過剰が増える手は、その (シフト,日) が次の玉になる。
                for (j in 0 until p.T) {
                    val cur = work[i][j]
                    if (cur == k && b.dir >= 0) {
                        if (makesCovU(k, j)) continue
                        for (y in p.allowedShiftsForStaff(i)) if (y != k) ms.add(intArrayOf(i, j, y))
                    } else if (cur != k && b.dir <= 0) {
                        if (makesCovU(cur, j)) continue
                        ms.add(intArrayOf(i, j, k))
                    }
                }
            }
            COVO -> {
                // 3. 人員過剰の移動: 同じ日の休か、同じ日の人員不足シフトへ。
                val j = b.days!![0]; val k = b.k
                for (i in 0 until p.S) {
                    if (work[i][j] != k) continue
                    p.restIdx?.let { r -> if (r != k) ms.add(intArrayOf(i, j, r)) }
                    for (y in p.allowedShiftsForStaff(i)) if (y != k && y != p.restIdx && p.covUCell(y, j, colCount(y, j)) > 0) ms.add(intArrayOf(i, j, y))
                }
            }
            // 4. 窓の 1 セル変更（違反が占める日だけ）・6. ペアの片方を変える。
            WINDOW, PAIR -> for (c in b.cells) {
                val i = c[0]; val j = c[1]
                for (y in p.allowedShiftsForStaff(i)) if (y != work[i][j] && !makesCovU(work[i][j], j)) ms.add(intArrayOf(i, j, y))
            }
            // 5. 日の人数: そのシフトを担当できる職員だけ。
            C41 -> for (c in b.cells) {
                val i = c[0]; val j = c[1]
                if (work[i][j] == b.k) { if (!makesCovU(b.k, j)) for (y in p.allowedShiftsForStaff(i)) if (y != b.k) ms.add(intArrayOf(i, j, y)) }
                else if (!makesCovU(work[i][j], j) && !growsCovO(b.k, j)) ms.add(intArrayOf(i, j, b.k))
            }
            // 7. 公平の交換: 同じ群のメンバーと、そのシフトを持つ／持たない 1 日を入れ替える。
            FAIR -> {
                val i = b.i; val k = b.k
                for (i2 in p.groupMembers[p.sgrp[i]]) {
                    if (i2 == i) continue
                    for (j in 0 until p.T) {
                        val a = work[i][j]; val c = work[i2][j]
                        if (a != c && (a == k || c == k)) ms.add(intArrayOf(i, j, c, i2, j, a))
                    }
                }
            }
            // 8. 曜日の交換: 多すぎる曜日の日と少なすぎる曜日の日を、2 職員×2 日で入れ替える。
            WEEKLY -> {
                val i = b.i; val k = b.k
                val wd = IntArray(7); var n = 0
                for (j in 0 until p.T) if (work[i][j] == k) { wd[(p.dow0 + j) % 7]++; n++ }
                for (a in 0 until p.T) {
                    if (work[i][a] != k || 7 * wd[(p.dow0 + a) % 7] <= n) continue
                    for (d in 0 until p.T) {
                        val y = work[i][d]
                        if (y == k || 7 * wd[(p.dow0 + d) % 7] >= n) continue
                        for (i2 in 0 until p.S) if (i2 != i && work[i2][a] == y && work[i2][d] == k) ms.add(intArrayOf(i, a, y, i, d, k, i2, a, k, i2, d, y))
                    }
                }
            }
        }
        return ms
    }
}
