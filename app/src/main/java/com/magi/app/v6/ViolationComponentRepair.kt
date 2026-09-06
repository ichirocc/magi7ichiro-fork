package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 違反連結成分修復（自律改善ループ Iteration 2 の第一弾。設計はユーザー提示）。
 *
 * 各研磨パスが単独では不採用にした候補（[CombinatorialRepair.Candidate]）を後処理チェーン全体で共有し、
 * 違反（セル・回数・人数）を起点に「その違反を触る候補（主）＋主と職員か日を共有する候補（助）」の集合を作り、
 * その中で 2〜[Params.maxK] 手のトランザクションをビームで探す（職員か日の共有だけで連結すると実データでは全候補が
 * 1 つの成分になるので、起点＝違反で範囲を切る）。途中の手は一時悪化を許し（[DeltaEvaluator] の推定値で枝を絞り、
 * 厳密ピンを新たに崩す枝は推定段階で落とす）、commit は正式チェッカーの [betterReport]（HARD→weighted→total）と
 * 厳密ピン検査で決める＝推定の誤差で退化しない。例外時は試行手を必ず巻き戻す。
 */
object ViolationComponentRepair {

    /** 計測専用の品質ベクトル（採用判定には使わない。採用基準の変更は独立した A/B 項目）。 */
    data class QualityVector(
        val hardCount: Int,
        val hardWeighted: Double,
        val softWeighted: Double,
        val wishMisses: Int,
        val changedCells: Int,
    ) : Comparable<QualityVector> {
        override fun compareTo(other: QualityVector): Int {
            if (hardCount != other.hardCount) return hardCount.compareTo(other.hardCount)
            if (hardWeighted != other.hardWeighted) return hardWeighted.compareTo(other.hardWeighted)
            if (softWeighted != other.softWeighted) return softWeighted.compareTo(other.softWeighted)
            if (wishMisses != other.wishMisses) return wishMisses.compareTo(other.wishMisses)
            return changedCells.compareTo(other.changedCells)
        }

        companion object {
            fun of(report: ViolationReport, changedCells: Int): QualityVector {
                var hardW = 0.0; var softW = 0.0
                for ((k, v) in report.breakdown) {
                    val c = v * MirrorKeys.weightOf(k)
                    if (k in MirrorKeys.hard) hardW += c else softW += c
                }
                return QualityVector(report.hard, hardW, softW, report.breakdown["pref"] ?: 0, changedCells)
            }
        }
    }

    data class Params(
        /** 1 トランザクションに束ねる候補数の上限。 */
        val maxK: Int = 4,
        /** ビームの幅（推定値で残す部分トランザクションの数）。 */
        val beamWidth: Int = 8,
        /** 正式チェッカー呼出の上限（1 回の呼出全体）。 */
        val maxEvaluations: Int = 64,
        /** 推定（DeltaEvaluator）の上限。 */
        val maxEstimates: Int = 6_000,
        /** 起点にする違反の上限（HARD→回数→人数の順）。 */
        val maxAnchors: Int = 24,
        /** 1 起点あたり探索に入れる候補数（主候補を先に、助候補を後に詰める）。 */
        val maxPatchesPerAnchor: Int = 40,
    )

    /** 盤面差分。`ops` は [職員, 日, 新シフト] の並び（[CombinatorialRepair.Candidate.ops] と同じ形）。 */
    class Patch(val ops: List<IntArray>, val mechanism: String, val hint: String) {
        val staff: IntArray = ops.map { it[0] }.distinct().sorted().toIntArray()
        val days: IntArray = ops.map { it[1] }.distinct().sorted().toIntArray()
        val cellKeys: LongArray = ops.map { it[0] * 100_000L + it[1] }.distinct().sorted().toLongArray()
        fun overlaps(o: Patch): Boolean {
            var a = 0; var b = 0
            while (a < cellKeys.size && b < o.cellKeys.size) {
                val d = cellKeys[a].compareTo(o.cellKeys[b])
                if (d == 0) return true
                if (d < 0) a++ else b++
            }
            return false
        }
        val signature: String = ops.joinToString(";") { "${it[0]},${it[1]},${it[2]}" }
    }

    /** 違反の起点。セル違反は (staff, day)、回数違反は staff、人数違反は day を範囲に持つ。 */
    internal class Anchor(val hard: Boolean, val family: String, val staff: Int, val day: Int) {
        fun touches(pt: Patch): Boolean = when {
            staff >= 0 && day >= 0 -> pt.cellKeys.contains(staff * 100_000L + day)
            staff >= 0 -> pt.staff.contains(staff)
            else -> pt.days.contains(day)
        }
        val label: String get() = family + (if (staff >= 0) " 職員$staff" else "") + (if (day >= 0) " ${day + 1}日" else "")
    }

    private fun parseKey(key: String): Pair<Int, Int>? {
        val c = key.indexOf(','); if (c <= 0) return null
        val a = key.substring(0, c).toIntOrNull() ?: return null
        val b = key.substring(c + 1).toIntOrNull() ?: return null
        return a to b
    }

    /** 起点の並び: HARD のセル・人数違反 → 回数違反 → SOFT のセル・人数違反（同種はキー順で決定的）。 */
    internal fun anchors(report: ViolationReport): List<Anchor> {
        fun fam(cls: String) = cls.removePrefix("vio-")
        val cells = report.violations.entries.sortedBy { it.key }.mapNotNull { (k, cls) -> parseKey(k)?.let { (i, j) -> Anchor(fam(cls) in MirrorKeys.hard, fam(cls), i, j) } }
        val needs = report.needViolations.entries.sortedBy { it.key }.mapNotNull { (k, cls) -> parseKey(k)?.let { (_, j) -> Anchor(fam(cls) in MirrorKeys.hard, fam(cls), -1, j) } }
        val counts = report.countViolations.entries.sortedBy { it.key }.mapNotNull { (k, cls) -> parseKey(k)?.let { (i, _) -> Anchor(false, fam(cls), i, -1) } }
        return (cells + needs).filter { it.hard } + counts + (cells + needs).filter { !it.hard }
    }

    /** 起点ごとの探索集合＝主候補（起点を触る）＋助候補（主と職員か日を共有）。主候補が無い起点は除く。 */
    internal fun anchorSets(anchors: List<Anchor>, patches: List<Patch>, cap: Int): List<Pair<Anchor, List<Int>>> {
        val out = ArrayList<Pair<Anchor, List<Int>>>()
        for (a in anchors) {
            val primary = patches.indices.filter { a.touches(patches[it]) }
            if (primary.isEmpty()) continue
            val staffSet = HashSet<Int>(); val daySet = HashSet<Int>()
            for (idx in primary) { staffSet.addAll(patches[idx].staff.toList()); daySet.addAll(patches[idx].days.toList()) }
            val helpers = patches.indices.filter { idx -> idx !in primary && (patches[idx].staff.any { it in staffSet } || patches[idx].days.any { it in daySet }) }
            out.add(a to (primary + helpers).take(cap))
        }
        return out
    }

    fun repair(
        state: MagiState,
        schedule: Array<IntArray>,
        pool: List<CombinatorialRepair.Candidate>,
        params: Params = Params(),
        shouldStop: () -> Boolean = { false },
    ): V6HotfixPasses.CyclicSwapResult {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        val pinBlocks = PinBlockAttribution()
        var applied = 0
        fun done(message: String) = V6HotfixPasses.CyclicSwapResult(
            work, before.total, bestRep.total, applied, listOf(MirrorLog(tag = "ComponentRepair", message = "違反連結成分修復: $message")),
            observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks,
        )
        if (pool.size < 2) return done("候補${pool.size}件=スキップ")
        if (work.any { row -> row.any { it !in 0 until p.K } }) return done("未割当セルあり=スキップ")

        // 候補→差分。現盤面で no-op のもの・範囲外のもの・同一差分は落とす。
        val seen = HashSet<String>()
        val patches = ArrayList<Patch>()
        for (c in pool) {
            if (c.ops.isEmpty() || c.ops.any { it.size < 3 || it[0] !in 0 until p.S || it[1] !in 0 until p.T || it[2] !in 0 until p.K }) continue
            if (c.ops.all { work[it[0]][it[1]] == it[2] }) continue
            if (c.ops.any { p.wishLocked(it[0], it[1]) }) continue   // 希望固定セルは動かさない（規約）
            val pt = Patch(c.ops, c.mechanism, c.hint)
            if (seen.add(pt.signature)) patches.add(pt)
        }
        if (patches.size < 2) return done("有効候補${patches.size}件=スキップ")

        val delta = DeltaEvaluator(p)
        delta.reset(work)
        // 厳密ピン（lo==hi）の (職員, シフト)。推定段階で「新たに崩す」枝を落とすために使う（exactPinRegression と同じ判定）。
        val pinned = Array(p.S) { i -> (0 until p.K).filter { k -> val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]; lo != Int.MIN_VALUE && hi != Int.MAX_VALUE && lo == hi }.toIntArray() }
        var estimates = 0; var evaluations = 0; var anchorsTried = 0; var prunedPin = 0
        val acceptedLabels = ArrayList<String>()
        val rejectReasons = LinkedHashMap<String, Int>()

        /** 候補 [ids] をまとめて当てたときの推定値（DeltaEvaluator）。厳密ピンを新たに崩す枝は Long.MAX_VALUE。必ず元へ戻す。 */
        fun estimate(ids: IntArray): Long {
            estimates++
            val undo = ArrayList<IntArray>()
            val touched = HashSet<Int>()
            val beforeCnt = HashMap<Long, Int>()
            try {
                for (id in ids) for (i in patches[id].staff) if (touched.add(i)) for (k in pinned[i]) beforeCnt[i * 1000L + k] = delta.countForStaff(i, k)
                for (id in ids) for (op in patches[id].ops) {
                    val old = delta.at(op[0], op[1])
                    if (old != op[2]) { delta.apply(op[0], op[1], op[2]); undo.add(intArrayOf(op[0], op[1], old)) }
                }
                for (i in touched) for (k in pinned[i]) {
                    val lo = p.rangeLo[i][k]
                    val before = beforeCnt[i * 1000L + k] ?: continue
                    if (kotlin.math.abs(delta.countForStaff(i, k) - lo) > kotlin.math.abs(before - lo)) { prunedPin++; return Long.MAX_VALUE }
                }
                return delta.score()
            } finally {
                for (r in undo.asReversed()) delta.apply(r[0], r[1], r[2])
            }
        }

        fun overlapsAny(ids: IntArray, j: Int): Boolean = ids.any { patches[it].overlaps(patches[j]) }

        class Node(val ids: IntArray, val est: Long)
        val nodeOrder = Comparator<Node> { a, b ->
            val c = a.est.compareTo(b.est); if (c != 0) return@Comparator c
            val n = minOf(a.ids.size, b.ids.size)
            for (t in 0 until n) { val d = a.ids[t].compareTo(b.ids[t]); if (d != 0) return@Comparator d }
            a.ids.size.compareTo(b.ids.size)
        }

        /** 成分 [remaining] の中で最浅の深さで見つかる「正式評価で改善する」トランザクション。無ければ null。 */
        fun search(remaining: List<Int>): Pair<IntArray, ViolationReport>? {
            val baseEst = delta.score()
            val base = work.copy2D()
            var frontier = remaining.map { Node(intArrayOf(it), estimate(intArrayOf(it))) }.filter { it.est != Long.MAX_VALUE }.sortedWith(nodeOrder).take(params.beamWidth)
            var depth = 1
            while (frontier.isNotEmpty()) {
                var best: Pair<IntArray, ViolationReport>? = null
                for (node in frontier) {
                    if (shouldStop() || evaluations >= params.maxEvaluations) break
                    if (node.est > baseEst) continue
                    if (depth == 1 && node.est == baseEst) continue   // 単独候補は各パスで既に正式評価済み＝推定が改善のときだけ再評価
                    evaluations++
                    val ops = node.ids.flatMap { patches[it].ops }
                    val saved = IntArray(ops.size) { work[ops[it][0]][ops[it][1]] }
                    val rep: ViolationReport
                    val pinBad: Boolean
                    try {
                        for (op in ops) work[op[0]][op[1]] = op[2]
                        rep = UnifiedViolationChecker.check(state, work)
                        val improves = betterReport(rep, bestRep)
                        pinBad = improves && exactPinRegression(p, base, work)
                        if (pinBad) pinBlocks.record(p, base, work)
                    } finally {
                        for ((t, op) in ops.withIndex()) work[op[0]][op[1]] = saved[t]
                    }
                    if (!pinBad && betterReport(rep, bestRep)) {
                        val cur = best
                        if (cur == null || betterReport(rep, cur.second)) best = node.ids to rep
                    } else {
                        val why = when {
                            pinBad -> "ピン破り"
                            rep.hard > bestRep.hard -> "必須増"
                            rep.weightedScore > bestRep.weightedScore -> "重み悪化"
                            rep.total > bestRep.total -> "件数悪化"
                            else -> "同値"
                        }
                        rejectReasons.merge(why, 1, Int::plus)
                    }
                }
                if (best != null) return best
                if (depth >= params.maxK || shouldStop() || evaluations >= params.maxEvaluations || estimates >= params.maxEstimates) return null
                val next = ArrayList<Node>()
                for (node in frontier) {
                    val last = node.ids.last()
                    for (j in remaining) {
                        if (j <= last || overlapsAny(node.ids, j)) continue
                        if (estimates >= params.maxEstimates) break
                        val ids = node.ids + j
                        val est = estimate(ids)
                        if (est != Long.MAX_VALUE) next.add(Node(ids, est))
                    }
                }
                frontier = next.sortedWith(nodeOrder).take(params.beamWidth)
                depth++
            }
            return null
        }

        // 起点（違反）ごとに探索し、採用があれば盤面が変わるので起点を作り直す。1 周して採用が無ければ終わり。
        val used = HashSet<Int>()
        var anchorCount = 0; var maxSet = 0
        outer@ while (!shouldStop() && evaluations < params.maxEvaluations && estimates < params.maxEstimates) {
            val sets = anchorSets(anchors(bestRep), patches, params.maxPatchesPerAnchor)
                .map { (a, ids) -> a to ids.filter { it !in used } }.filter { it.second.isNotEmpty() }
            anchorCount = sets.size
            var committed = false
            for ((anchor, ids) in sets.take(params.maxAnchors)) {
                if (shouldStop() || evaluations >= params.maxEvaluations || estimates >= params.maxEstimates) break
                anchorsTried++; maxSet = maxOf(maxSet, ids.size)
                val (chosen, rep) = search(ids) ?: continue
                for (id in chosen) for (op in patches[id].ops) {
                    if (work[op[0]][op[1]] != op[2]) { work[op[0]][op[1]] = op[2]; delta.apply(op[0], op[1], op[2]) }
                }
                bestRep = rep; applied++; used.addAll(chosen.toList())
                acceptedLabels.add(anchor.label + ": " + chosen.joinToString("+") { patches[it].hint.ifBlank { patches[it].mechanism } } + "(k=${chosen.size})")
                committed = true
                continue@outer
            }
            if (!committed) break
        }

        val mech = LinkedHashMap<String, Int>()
        for (pt in patches) mech.merge(pt.mechanism, 1, Int::plus)
        return done(
            "候補${patches.size}件(" + mech.entries.joinToString(" ") { "${it.key}×${it.value}" } + ") 起点${anchorCount}件(探索${anchorsTried}件・最大${maxSet}候補)" +
                " 推定${estimates}回(ピン枝刈り${prunedPin}) 正式評価${evaluations}回 採用${applied}件" +
                (if (acceptedLabels.isNotEmpty()) "[" + acceptedLabels.joinToString(", ") + "]" else "") +
                (if (rejectReasons.isNotEmpty()) " 不採用(" + rejectReasons.entries.joinToString(" ") { "${it.key}:${it.value}" } + ")" else "") +
                " / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} score ${before.weightedScore.toLong()}->${bestRep.weightedScore.toLong()}",
        )
    }
}
