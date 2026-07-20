package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlin.math.abs

/**
 * [C1協調ビーム研磨, 外部パッチ受領・検証のうえ適用] 既存の手A(同日2人swap)/手R1(鏡像長方形)/
 * 手R2(自己2日swap)/手B(直接移動+玉突き)/手R3(全ペア再配置)/C1TemporalDp(単一職員のDP最適配置+
 * 同日swap実現)はいずれも「1回の手だけで即座に改善するか」を評価して採否する（isBetterで単発判定）。
 * このため「単独では総合スコアが変わらない(タイ)/悪化するが、複数の同日手を束ねて初めて改善する」
 * 局面（例: 職員Aの窓不足を職員Bとの同日swapで直すと職員Bが新たに不足するが、別日に職員Cとの
 * 同日swapでBを直せば全体として改善する）を、既存の逐次パスは見つけられない
 * （各パスはアンカー1件ずつ試して不採用ならその場で巻き戻すのみ・複数アンカーの累積を評価しない）。
 *
 * 本パスは同日スワップ/3人回転の束をビームサーチで探索する。中間ノードは一時的に悪化してもよい
 * （debt予算内）が、**最終的に採用するのは実チェッカー(UnifiedViolationChecker)で厳密に検証した
 * 完成ノードのみ**。CombinatorialRepair(3.249.0)が「各パスで個別に不採用だった候補を後から束ねる」
 * のに対し、本パスは「そもそも個別に試したことのない同日手の組合せ」を深さ優先で生成する点が異なる
 * （手Aは同日swapを1つずつ試して即rejectするのみで、reject後にcombinableへ記録しない＝
 * CombinatorialRepairの対象外の穴）。
 *
 * 最終採用条件:
 *  1. 正式な辞書式順序: hard -> total -> weightedScore（既存isBetterと同一式）
 *  2. c1窓fire合計が厳密に減少
 *  3. どの職員のc1窓fire数もパス開始時点(root)より増加しない
 *
 * 生成する手は全て同日の2人swapまたは3人ローテーションのみ＝日別シフト多重集合(covU/covO)は
 * 構造的に不変。
 */
internal object BeamC1PolishV2 {
    private data class Anchor(
        val staff: Int,
        val day: Int,
        val targetShift: Int,
        val deficitWeight: Int,
    )

    private sealed interface Move {
        val day: Int
        data class Swap(
            override val day: Int,
            val receiver: Int,
            val donor: Int,
        ) : Move

        data class Rotate3(
            override val day: Int,
            val receiver: Int,
            val donor: Int,
            val bridge: Int,
        ) : Move
    }

    private data class Node(
        val schedule: Array<IntArray>,
        val report: ViolationReport,
        val c1ByStaff: IntArray,
        val c1Total: Int,
        val moves: List<Move>,
    )

    fun apply(
        state: MagiState,
        schedule: Array<IntArray>,
        maxPasses: Int = 2,
        beamWidth: Int = 12,
        maxDepth: Int = 4,
        maxAnchors: Int = 24,
        maxDirectDonors: Int = 8,
        maxRotationsPerAnchor: Int = 8,
        hardDebt: Int = 1,
        totalDebt: Int = 12,
        c1Debt: Int = 4,
        shouldStop: () -> Boolean = { false },
    ): V6HotfixPasses.CyclicSwapResult {
        val p = Problem(state)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var current = before
        var applied = 0
        var expanded = 0
        var generated = 0
        val acceptedLabels = ArrayList<String>()

        if (p.cons1.isEmpty() || (before.breakdown["c1"] ?: 0) == 0) {
            return V6HotfixPasses.CyclicSwapResult(
                work,
                before.total,
                before.total,
                0,
                listOf(MirrorLog(tag = "BeamC1V2", message = "期間要件(c1)対象なし=スキップ")),
            )
        }

        val width = beamWidth.coerceAtLeast(1)
        val depthLimit = maxDepth.coerceAtLeast(1)
        val passLimit = maxPasses.coerceAtLeast(1)

        var pass = 0
        while (pass < passLimit && !shouldStop()) {
            val rootSchedule = work.copy2D()
            val rootReport = current
            val rootC1 = c1WindowFiresByStaff(p, rootSchedule)
            val rootC1Total = rootC1.sum()
            if (rootC1Total == 0) break

            var beam = listOf(Node(rootSchedule, rootReport, rootC1, rootC1Total, emptyList()))
            var bestAccepted: Node? = null
            var depth = 0

            while (depth < depthLimit && beam.isNotEmpty() && !shouldStop()) {
                depth++
                val children = ArrayList<Node>()

                for (parent in beam) {
                    if (shouldStop()) break
                    expanded++
                    val anchors = collectAnchors(p, parent.schedule, maxAnchors)
                    for (anchor in anchors) {
                        if (shouldStop()) break
                        val moves = generateMoves(
                            p = p,
                            schedule = parent.schedule,
                            anchor = anchor,
                            maxDirectDonors = maxDirectDonors,
                            maxRotations = maxRotationsPerAnchor,
                        )
                        for (move in moves) {
                            if (shouldStop()) break
                            if (parent.moves.lastOrNull()?.let { isImmediateInverse(it, move) } == true) continue

                            val candidateSchedule = parent.schedule.copy2D()
                            applyMove(candidateSchedule, move)
                            generated++

                            val report = UnifiedViolationChecker.check(state, candidateSchedule)
                            val c1ByStaff = c1WindowFiresByStaff(p, candidateSchedule)
                            val c1Total = c1ByStaff.sum()

                            // Intermediate debt gate. It deliberately does not require improvement
                            // over the parent, allowing a later move to complete a coordinated bundle.
                            if (report.hard > rootReport.hard + hardDebt.coerceAtLeast(0)) continue
                            if (report.total > rootReport.total + totalDebt.coerceAtLeast(0)) continue
                            if (c1Total > rootC1Total + c1Debt.coerceAtLeast(0)) continue

                            val child = Node(
                                schedule = candidateSchedule,
                                report = report,
                                c1ByStaff = c1ByStaff,
                                c1Total = c1Total,
                                moves = parent.moves + move,
                            )
                            children.add(child)

                            if (passesFinalGate(child, rootReport, rootC1, rootC1Total)) {
                                val old = bestAccepted
                                if (old == null || better(child.report, old.report) ||
                                    (sameReport(child.report, old.report) && child.moves.size < old.moves.size)
                                ) {
                                    bestAccepted = child
                                }
                            }
                        }
                    }
                }

                if (children.isEmpty()) break
                val dedup = LinkedHashMap<Long, Node>()
                children.sortWith(nodeComparator(rootReport))
                for (child in children) {
                    val sig = scheduleSignature(child.schedule)
                    val old = dedup[sig]
                    if (old == null || nodeComparator(rootReport).compare(child, old) < 0) {
                        dedup[sig] = child
                    }
                    if (dedup.size >= width * 4) break
                }
                beam = dedup.values.sortedWith(nodeComparator(rootReport)).take(width)
            }

            val chosen = bestAccepted ?: break
            work = chosen.schedule.copy2D()
            current = chosen.report
            applied++
            acceptedLabels.add(describeBundle(state, chosen.moves, rootC1Total, chosen.c1Total))
            pass++
        }

        val logs = listOf(
            MirrorLog(
                tag = "BeamC1V2",
                message = "期間要件(c1)協調ビーム研磨: c1 ${before.breakdown["c1"] ?: 0}->${current.breakdown["c1"] ?: 0}" +
                    " / total ${before.total}->${current.total} HARD ${before.hard}->${current.hard}" +
                    " 採用${applied}束 展開$expanded 候補$generated" +
                    (if (acceptedLabels.isNotEmpty()) " 対象: ${acceptedLabels.joinToString(", ")}" else "") +
                    (if (applied == 0) " [頭打ち=協調束でも改善なし]" else ""),
            ),
        )
        return V6HotfixPasses.CyclicSwapResult(work, before.total, current.total, applied, logs)
    }

    private fun passesFinalGate(
        node: Node,
        rootReport: ViolationReport,
        rootC1: IntArray,
        rootC1Total: Int,
    ): Boolean {
        if (!better(node.report, rootReport)) return false
        if (node.c1Total >= rootC1Total) return false
        for (i in rootC1.indices) {
            if (node.c1ByStaff[i] > rootC1[i]) return false
        }
        return true
    }

    private fun better(a: ViolationReport, b: ViolationReport): Boolean {
        if (a.hard != b.hard) return a.hard < b.hard
        if (a.total != b.total) return a.total < b.total
        return a.weightedScore < b.weightedScore
    }

    private fun sameReport(a: ViolationReport, b: ViolationReport): Boolean =
        a.hard == b.hard && a.total == b.total && abs(a.weightedScore - b.weightedScore) <= 1e-9

    private fun nodeComparator(root: ViolationReport): Comparator<Node> =
        compareBy<Node> { (it.report.hard - root.hard).coerceAtLeast(0) }
            .thenBy { it.c1Total }
            .thenBy { (it.report.total - root.total).coerceAtLeast(0) }
            .thenBy { it.report.hard }
            .thenBy { it.report.total }
            .thenBy { it.report.weightedScore }
            .thenBy { it.moves.size }

    /** Exact checker-compatible C1 window-fire counts, split per staff. */
    private fun c1WindowFiresByStaff(p: Problem, schedule: Array<IntArray>): IntArray {
        val out = IntArray(p.S)
        for (c in p.cons1) {
            val d = c.day1
            val n = c.day2
            val x = c.shiftIdx
            if (d <= 0 || d > p.T || x !in 0 until p.K) continue
            for (i in 0 until p.S) {
                if (!p.canDo(i, x)) continue
                var start = 0
                while (start <= p.T - d) {
                    var count = 0
                    for (l in 0 until d) if (schedule[i][start + l] == x) count++
                    if (count < n) out[i]++
                    start++
                }
            }
        }
        return out
    }

    /**
     * Collect receiver cells inside deficient windows. A cell receives a larger weight
     * when it belongs to several deficient windows/rules.
     */
    private fun collectAnchors(p: Problem, schedule: Array<IntArray>, limit: Int): List<Anchor> {
        val weights = LinkedHashMap<Triple<Int, Int, Int>, Int>()
        for (c in p.cons1) {
            val d = c.day1
            val n = c.day2
            val x = c.shiftIdx
            if (d <= 0 || d > p.T || x !in 0 until p.K) continue
            for (i in 0 until p.S) {
                if (!p.canDo(i, x)) continue
                var start = 0
                while (start <= p.T - d) {
                    var count = 0
                    for (l in 0 until d) if (schedule[i][start + l] == x) count++
                    if (count < n) {
                        for (j in start until start + d) {
                            if (schedule[i][j] == x || p.wish[i][j] >= 0) continue
                            val key = Triple(i, j, x)
                            weights[key] = (weights[key] ?: 0) + (n - count).coerceAtLeast(1)
                        }
                    }
                    start++
                }
            }
        }
        return weights.entries
            .map { (k, w) -> Anchor(k.first, k.second, k.third, w) }
            .sortedWith(
                compareByDescending<Anchor> { it.deficitWeight }
                    .thenBy { it.staff }
                    .thenBy { it.day }
                    .thenBy { it.targetShift },
            )
            .take(limit.coerceAtLeast(1))
    }

    private fun generateMoves(
        p: Problem,
        schedule: Array<IntArray>,
        anchor: Anchor,
        maxDirectDonors: Int,
        maxRotations: Int,
    ): List<Move> {
        val i = anchor.staff
        val j = anchor.day
        val x = anchor.targetShift
        val a = schedule[i][j]
        if (a == x || p.wish[i][j] >= 0 || !p.canDo(i, x)) return emptyList()

        val out = ArrayList<Move>()
        var direct = 0
        for (donor in 0 until p.S) {
            if (donor == i || schedule[donor][j] != x || p.wish[donor][j] >= 0) continue
            if (p.canDo(donor, a)) {
                out.add(Move.Swap(j, i, donor))
                direct++
                if (direct >= maxDirectDonors.coerceAtLeast(1)) break
            }
        }

        var rotations = 0
        rotationLoop@ for (donor in 0 until p.S) {
            if (donor == i || schedule[donor][j] != x || p.wish[donor][j] >= 0) continue
            for (bridge in 0 until p.S) {
                if (bridge == i || bridge == donor || p.wish[bridge][j] >= 0) continue
                val y = schedule[bridge][j]
                if (y == x || y == a) continue
                // i:a -> x, donor:x -> y, bridge:y -> a
                if (!p.canDo(donor, y) || !p.canDo(bridge, a)) continue
                out.add(Move.Rotate3(j, i, donor, bridge))
                rotations++
                if (rotations >= maxRotations.coerceAtLeast(0)) break@rotationLoop
            }
        }
        return out
    }

    private fun applyMove(schedule: Array<IntArray>, move: Move) {
        when (move) {
            is Move.Swap -> {
                val a = schedule[move.receiver][move.day]
                schedule[move.receiver][move.day] = schedule[move.donor][move.day]
                schedule[move.donor][move.day] = a
            }
            is Move.Rotate3 -> {
                val a = schedule[move.receiver][move.day]
                val x = schedule[move.donor][move.day]
                val y = schedule[move.bridge][move.day]
                schedule[move.receiver][move.day] = x
                schedule[move.donor][move.day] = y
                schedule[move.bridge][move.day] = a
            }
        }
    }

    private fun isImmediateInverse(a: Move, b: Move): Boolean = when {
        a is Move.Swap && b is Move.Swap ->
            a.day == b.day && a.receiver == b.donor && a.donor == b.receiver
        else -> false
    }

    private fun scheduleSignature(schedule: Array<IntArray>): Long {
        var h = -0x340d631b7bdddcdbL
        for (row in schedule) for (v in row) {
            h = h xor v.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    private fun describeBundle(
        state: MagiState,
        moves: List<Move>,
        beforeC1: Int,
        afterC1: Int,
    ): String {
        val text = moves.joinToString("+") { move ->
            when (move) {
                is Move.Swap -> {
                    val a = state.staff.getOrNull(move.receiver)?.name ?: "#${move.receiver}"
                    val b = state.staff.getOrNull(move.donor)?.name ?: "#${move.donor}"
                    "${move.day + 1}日 $a⇔$b"
                }
                is Move.Rotate3 -> {
                    val a = state.staff.getOrNull(move.receiver)?.name ?: "#${move.receiver}"
                    val b = state.staff.getOrNull(move.donor)?.name ?: "#${move.donor}"
                    val c = state.staff.getOrNull(move.bridge)?.name ?: "#${move.bridge}"
                    "${move.day + 1}日 $a←$b←$c"
                }
            }
        }
        return "c1 $beforeC1->$afterC1 [$text]"
    }
}
