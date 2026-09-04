package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlin.math.max
import kotlin.math.min

/**
 * [3.496.0/ユーザー提示の確定仕様] 希望島研磨。
 *
 * - **実現可能な希望シフト日を固定アンカー**にする（`wishLocked`）。希望セル自体は変更しない。
 * - 希望日の前方・後方・両側を研磨対象にする。近接する希望は制約の影響範囲（c1 窓長・c3 系パターン長から決まる半径 R）
 *   が重なる場合に**希望島**へ統合する。周辺（島の影響範囲）に違反がある島だけ起動する。
 * - 手: 同日交換 → 可変長窓交換 → 両翼交換（島の前後の窓を同じ相手と同時に）→ 必要時のみ3職員巡回（同日）。
 *   交換対象の全セルで希望固定と担当可否を確認する。日別総人数を保存する同日交換を優先し、所属・技能群が異なる相手は後順位。
 * - 採否: 正式チェッカーで個人回数・厳密回数固定・C1・C3・所属/技能・公平性を再評価し、HARD → weightedScore → total。
 *   通常は**希望周辺（島の影響範囲の違反重み）も全体も改善する**候補だけ採用する。
 * - 停滞時（1 pass で採用0）だけ短いビーム探索（幅 [beamWidth]・深さ [beamDepth]）を行い、その途中では中立手（悪化しない手）
 *   を許す。ビームの最終盤面は全体が改善しているときだけ採用する。
 * - 最終結果は必ず開始盤面より改善している（keep-best＝改善しなければ開始盤面をそのまま返す）。採用後は違反情報・回数・
 *   希望島を再計算する。月跨ぎは扱わず `0 until T` 内で完結し、月初・月末では窓を当月範囲へ切り詰める。
 */
internal object WishIslandPolish {

    private enum class MoveKind(val label: String) { SAME_DAY("同日"), WINDOW("窓"), WINGS("両翼"), ROTATE3("巡回") }

    /** 1手＝(職員, 日, 新しい値) の集合。適用と巻き戻しが同じ形でできる。 */
    private class Move(val kind: MoveKind, val cells: IntArray, val sameGroup: Boolean, val island: Int)

    private class Island(val staff: Int, val wishDays: IntArray, val zoneFrom: Int, val zoneTo: Int)

    fun applyWishIslandPolish(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, maxEvaluations: Int = 120,
        beamWidth: Int = 4, beamDepth: Int = 3, shouldStop: () -> Boolean = { false },
    ): V6HotfixPasses.CyclicSwapResult {
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        val T = p.T; val S = p.S; val K = p.K
        // 影響半径 R: c1 の窓長・c3 系パターン長の最大 −1（最低 1）。前後 R 日が希望日の「周辺」。
        var reach = 1
        for (c in p.cons1) reach = max(reach, c.day1 - 1)
        for (list in listOf(p.cons3, p.cons3n, p.cons3m, p.cons3mn)) for (c in list) reach = max(reach, c.seq.size - 1)
        reach = min(reach, max(1, T - 1))
        fun name(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
        fun sameGroup(a: Int, b: Int) = p.sgrp[a] == p.sgrp[b] && p.ssk[a] == p.ssk[b]
        fun locked(i: Int, d: Int) = p.wishLocked(i, d)

        // ---- 希望島（職員ごとに希望日をソートし、影響範囲が重なるものを統合） ----
        fun buildIslands(): List<Island> {
            val out = ArrayList<Island>()
            for (i in 0 until S) {
                val days = (0 until T).filter { locked(i, it) }
                if (days.isEmpty()) continue
                var from = days[0]; var to = days[0]; val cur = ArrayList<Int>(); cur.add(days[0])
                fun flush() { out.add(Island(i, cur.toIntArray(), max(0, from - reach), min(T - 1, to + reach))); cur.clear() }
                for (t in 1 until days.size) {
                    val d = days[t]
                    if (d - reach <= to + reach) { to = d; cur.add(d) } else { flush(); from = d; to = d; cur.add(d) }
                }
                flush()
            }
            return out
        }
        /** 島の周辺の違反重み（希望周辺の改善判定に使う局所スコア）。セル違反＝影響範囲内、回数違反＝当該職員の全部。 */
        fun localScore(rep: ViolationReport, isl: Island): Long {
            var s = 0L
            for (d in isl.zoneFrom..isl.zoneTo) rep.cellFamilies["${isl.staff},$d"]?.forEach { s += MirrorKeys.weightOf(it.removePrefix("vio-")).toLong().coerceAtLeast(1L) }
            for ((key, cls) in rep.countViolations) if (key.substringBefore(',').toIntOrNull() == isl.staff) s += MirrorKeys.weightOf(cls.removePrefix("vio-")).toLong().coerceAtLeast(1L)
            return s
        }
        fun active(rep: ViolationReport, isl: Island) = localScore(rep, isl) > 0L

        // ---- 手の適用/巻き戻し ----
        fun apply(m: Move): IntArray {
            val old = IntArray(m.cells.size / 3)
            var t = 0
            while (t < m.cells.size) { val i = m.cells[t]; val d = m.cells[t + 1]; old[t / 3] = work[i][d]; work[i][d] = m.cells[t + 2]; t += 3 }
            return old
        }
        fun undo(m: Move, old: IntArray) { var t = 0; while (t < m.cells.size) { work[m.cells[t]][m.cells[t + 1]] = old[t / 3]; t += 3 } }
        /** 適用後の各変更セルが禁止の並び（cons3n 一般）を構成していれば正式評価の前に落とす（チェッカーが最終判定＝見逃しは無害）。 */
        fun makesForbidden(m: Move): Boolean {
            if (p.cons3n.isEmpty()) return false
            val old = apply(m)
            var bad = false
            var t = 0
            while (t < m.cells.size && !bad) { val i = m.cells[t]; val d = m.cells[t + 1]; if (p.makesForbiddenRun(work, i, d, work[i][d])) bad = true; t += 3 }
            undo(m, old)
            return bad
        }
        var prunedC3n = 0

        // ---- 候補生成（島ごと） ----
        fun genSameDay(isl: Island, ix: Int, out: MutableList<Move>) {
            val a = isl.staff
            for (d in isl.zoneFrom..isl.zoneTo) {
                if (locked(a, d)) continue
                val ka = work[a][d]; if (ka !in 0 until K) continue
                for (b in 0 until S) {
                    if (b == a || locked(b, d)) continue
                    val kb = work[b][d]; if (kb !in 0 until K || kb == ka) continue
                    if (!p.canDo(a, kb) || !p.canDo(b, ka)) continue
                    out.add(Move(MoveKind.SAME_DAY, intArrayOf(a, d, kb, b, d, ka), sameGroup(a, b), ix))
                }
            }
        }
        fun windowOk(a: Int, b: Int, s0: Int, s1: Int): Boolean {
            var changes = false
            for (d in s0..s1) {
                val ka = work[a][d]; val kb = work[b][d]
                if (ka !in 0 until K || kb !in 0 until K) return false
                if (locked(a, d) || locked(b, d)) return false
                if (!p.canDo(a, kb) || !p.canDo(b, ka)) return false
                if (ka != kb) changes = true
            }
            return changes
        }
        fun windowCells(a: Int, b: Int, s0: Int, s1: Int, into: MutableList<Int>) {
            for (d in s0..s1) { into.add(a); into.add(d); into.add(work[b][d]); into.add(b); into.add(d); into.add(work[a][d]) }
        }
        fun genWindows(isl: Island, ix: Int, out: MutableList<Move>) {
            val a = isl.staff
            val zl = isl.zoneTo - isl.zoneFrom + 1
            for (b in 0 until S) {
                if (b == a) continue
                for (len in 2..zl) for (s0 in isl.zoneFrom..(isl.zoneTo - len + 1)) {
                    val s1 = s0 + len - 1
                    if (!windowOk(a, b, s0, s1)) continue
                    val cells = ArrayList<Int>(); windowCells(a, b, s0, s1, cells)
                    out.add(Move(MoveKind.WINDOW, cells.toIntArray(), sameGroup(a, b), ix))
                }
            }
        }
        fun genWings(isl: Island, ix: Int, out: MutableList<Move>) {
            val a = isl.staff
            val first = isl.wishDays.first(); val last = isl.wishDays.last()
            if (first <= isl.zoneFrom || last >= isl.zoneTo) return   // 月初・月末で片翼が無い＝両翼交換なし
            for (b in 0 until S) {
                if (b == a) continue
                for (l0 in isl.zoneFrom until first) for (r1 in (last + 1)..isl.zoneTo) {
                    if (!windowOk(a, b, l0, first - 1) || !windowOk(a, b, last + 1, r1)) continue
                    val cells = ArrayList<Int>(); windowCells(a, b, l0, first - 1, cells); windowCells(a, b, last + 1, r1, cells)
                    out.add(Move(MoveKind.WINGS, cells.toIntArray(), sameGroup(a, b), ix))
                }
            }
        }
        fun genRotate3(isl: Island, ix: Int, out: MutableList<Move>) {
            val a = isl.staff
            for (d in isl.zoneFrom..isl.zoneTo) {
                if (locked(a, d)) continue
                val ka = work[a][d]; if (ka !in 0 until K) continue
                for (b in 0 until S) {
                    if (b == a || locked(b, d)) continue
                    val kb = work[b][d]; if (kb !in 0 until K || !p.canDo(a, kb)) continue
                    for (c in 0 until S) {
                        if (c == a || c == b || locked(c, d)) continue
                        val kc = work[c][d]; if (kc !in 0 until K || !p.canDo(b, kc) || !p.canDo(c, ka)) continue
                        if (ka == kb && kb == kc) continue
                        out.add(Move(MoveKind.ROTATE3, intArrayOf(a, d, kb, b, d, kc, c, d, ka), sameGroup(a, b) && sameGroup(b, c), ix))
                    }
                }
            }
        }
        val kindOrder = mapOf(MoveKind.SAME_DAY to 0, MoveKind.WINDOW to 1, MoveKind.WINGS to 2, MoveKind.ROTATE3 to 3)
        fun order(list: MutableList<Move>) {
            list.sortWith(compareBy<Move> { kindOrder[it.kind] }.thenBy { if (it.sameGroup) 0 else 1 }.thenBy { it.cells.size })
        }

        var applied = 0; var evaluated = 0; var beamRuns = 0; var beamApplied = 0
        var wishCount = 0; var islandCount = 0; var activeCount = 0; var candTotal = 0
        val byKind = LinkedHashMap<String, Int>()
        val rejectCulprits = RejectCulpritStats()
        val stuck = LinkedHashSet<String>()

        var pass = 0
        while (pass < maxPasses && !shouldStop() && evaluated < maxEvaluations) {
            val islands = buildIslands()
            wishCount = islands.sumOf { it.wishDays.size }; islandCount = islands.size
            val activeIslands = islands.withIndex().filter { active(bestRep, it.value) }
            activeCount = activeIslands.size
            if (activeIslands.isEmpty()) break
            var passApplied = 0
            // 評価枠は起動した島で分け合う（先頭の島が枠を使い切らないように）。
            val islandBudget = max(8, (maxEvaluations - evaluated) / activeIslands.size)
            for ((ix, isl) in activeIslands) {
                if (shouldStop() || evaluated >= maxEvaluations) break
                var islandEvals = 0
                val localBefore = localScore(bestRep, isl)
                val cands = ArrayList<Move>()
                genSameDay(isl, ix, cands); genWindows(isl, ix, cands); genWings(isl, ix, cands)
                order(cands)
                var chosen: Move? = null; var chosenRep: ViolationReport? = null
                fun evalList(list: List<Move>) {
                    val base = work.copy2D()
                    for (m in list) {
                        if (shouldStop() || evaluated >= maxEvaluations || islandEvals >= islandBudget) return
                        if (makesForbidden(m)) { prunedC3n++; continue }
                        islandEvals++
                        val old = apply(m)
                        val rep = UnifiedViolationChecker.check(state, work)
                        val pinBad = exactPinRegression(p, base, work)
                        evaluated++; candTotal++
                        val globalOk = betterReport(rep, bestRep) && !pinBad
                        val localOk = localScore(rep, isl) < localBefore
                        if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, base, work)
                        undo(m, old)
                        if (globalOk && localOk && (chosenRep == null || betterReport(rep, chosenRep!!))) { chosen = m; chosenRep = rep }
                        else rejectCulprits.record(rep, bestRep, pinBad)
                    }
                }
                evalList(cands)
                if (chosen == null && !shouldStop() && evaluated < maxEvaluations) {
                    // 必要時のみ3職員巡回
                    val rot = ArrayList<Move>(); genRotate3(isl, ix, rot); order(rot); evalList(rot)
                }
                val m = chosen
                if (m != null) {
                    apply(m); bestRep = chosenRep!!; applied++; passApplied++
                    byKind[m.kind.label] = (byKind[m.kind.label] ?: 0) + 1
                } else stuck.add(name(isl.staff))
            }
            if (passApplied == 0) {
                // 停滞: 短いビーム探索。途中は中立手（悪化しない手）を許し、最終盤面が全体で改善したときだけ採用。
                beamRuns++
                class Node(val board: Array<IntArray>, val rep: ViolationReport)
                val baseline = work.copy2D()
                var frontier = listOf(Node(work.copy2D(), bestRep))
                var bestNode: Node? = null
                for (depth in 0 until beamDepth) {
                    if (shouldStop() || evaluated >= maxEvaluations) break
                    val next = ArrayList<Node>()
                    for (node in frontier) {
                        for (s in 0 until S) System.arraycopy(node.board[s], 0, work[s], 0, T)
                        val isls = buildIslands().withIndex().filter { active(node.rep, it.value) }
                        val cands = ArrayList<Move>()
                        for ((ix, isl) in isls) { genSameDay(isl, ix, cands); genWindows(isl, ix, cands) }
                        order(cands)
                        for (m in cands) {
                            if (shouldStop() || evaluated >= maxEvaluations) break
                            if (makesForbidden(m)) { prunedC3n++; continue }
                            val old = apply(m)
                            val rep = UnifiedViolationChecker.check(state, work)
                            evaluated++
                            val pinBad = exactPinRegression(p, node.board, work)
                            // 中立手＝悪化しない（node より悪くない）。
                            if (!pinBad && !betterReport(node.rep, rep)) next.add(Node(work.copy2D(), rep))
                            undo(m, old)
                            if (next.size >= beamWidth * 6) break
                        }
                    }
                    if (next.isEmpty()) break
                    next.sortWith(reportComparator.let { cmp -> Comparator<Node> { x, y -> cmp.compare(x.rep, y.rep) } })
                    frontier = next.take(beamWidth)
                    val top = frontier.first()
                    if (betterReport(top.rep, bestRep) && (bestNode == null || betterReport(top.rep, bestNode!!.rep))) bestNode = top
                }
                val bn = bestNode
                // frontier 探索で work は各ノードの盤面に書き換わっているので、まず開始盤面へ戻す。
                for (s in 0 until S) System.arraycopy(baseline[s], 0, work[s], 0, T)
                if (bn != null && !exactPinRegression(p, baseline, bn.board)) {
                    for (s in 0 until S) System.arraycopy(bn.board[s], 0, work[s], 0, T)
                    bestRep = bn.rep; applied++; beamApplied++
                } else break   // ビームでも改善なし＝停滞で終了
            }
            pass++
        }
        // 開始盤面より改善していなければ開始盤面をそのまま返す（keep-best）。
        val finalSched = if (betterReport(bestRep, before)) work else normalizeSchedule(schedule, p)
        val finalRep = if (betterReport(bestRep, before)) bestRep else before
        val logs = listOf(MirrorLog(tag = "WishIslandPolish",
            message = "希望島研磨: 希望${wishCount}件→島${islandCount}件(起動${activeCount}件・影響半径${reach}日) 候補評価${candTotal} 正式評価${evaluated}" +
                " / total ${before.total}->${finalRep.total} HARD ${before.hard}->${finalRep.hard} 採用${applied}回" +
                (if (byKind.isNotEmpty()) "(" + byKind.entries.joinToString(" ") { "${it.key}:${it.value}" } + ")" else "") +
                (if (beamRuns > 0) " ビーム${beamRuns}回(採用${beamApplied})" else "") +
                (if (prunedC3n > 0) " 禁止の並びで枝刈り${prunedC3n}" else "") +
                (if (applied == 0 && activeCount > 0) " [頭打ち=改善手なし]" else "") +
                rejectCulprits.summary() +
                (if (stuck.isNotEmpty()) " 残存: ${stuck.take(8).joinToString(", ")}${if (stuck.size > 8) " ほか${stuck.size - 8}名" else ""}" else "")))
        return V6HotfixPasses.CyclicSwapResult(finalSched, before.total, finalRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }
}
