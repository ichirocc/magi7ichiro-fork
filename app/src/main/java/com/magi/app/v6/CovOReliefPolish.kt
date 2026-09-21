package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 人員過剰(covO)の退避研磨。過剰セルの在勤者を受け皿のある担当可シフト（需要 0 のシフト＝B4 等を含む）へ 1 セルずつ動かし、
 * 正式チェッカーの keep-best で採る。`V6PortAnalyzer.diagnoseSurpluses` と同じ手を診断でなく修復として総当たりする。
 */
internal object CovOReliefPolish {
    data class Result(
        val newSchedule: Array<IntArray>,
        val beforeCovO: Int,
        val afterCovO: Int,
        val applied: Int,
        val logs: List<MirrorLog>,
        val report: ViolationReport? = null,
    )

    fun apply(
        state: MagiState, schedule: Array<IntArray>,
        maxMoves: Int = 64, maxEvaluations: Int = 3_000,
        shouldStop: () -> Boolean = { false }, quantitativeRangeEval: Boolean = false,
    ): Result {
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0; var evaluations = 0
        val adopted = ArrayList<String>()
        val famHits = HashMap<String, Int>()
        var rejected = 0
        val residual = LinkedHashMap<String, String>()
        fun sym(k: Int) = state.shifts.getOrNull(k)?.kigou ?: k.toString()
        fun name(i: Int) = state.staff.getOrNull(i)?.name ?: i.toString()
        val cov = Array(p.T) { IntArray(p.K) }
        fun recount() { for (j in 0 until p.T) { cov[j].fill(0); for (i in 0 until p.S) { val k = work[i][j]; if (k in 0 until p.K) cov[j][k]++ } } }
        recount()
        var improved = true
        while (improved && applied < maxMoves && !shouldStop()) {
            improved = false
            residual.clear()
            for (j in 0 until p.T) for (k in 0 until p.K) {
                if (shouldStop() || applied >= maxMoves) break
                if (p.covOCell(k, j, cov[j][k]) <= 0) continue
                var bestI = -1; var bestM = -1; var bestAfter: ViolationReport? = null
                var pinned = 0; var noRoom = 0; var worse = 0
                for (i in 0 until p.S) {
                    if (work[i][j] != k) continue
                    if (p.wishLocked(i, j) && p.wish[i][j] == k) { pinned++; continue }
                    var tried = false
                    for (m in p.allowedShiftsForStaff(i)) {
                        if (m == k || p.makesForbiddenRun(work, i, j, m)) continue
                        if (p.covOCell(m, j, cov[j][m] + 1) > p.covOCell(m, j, cov[j][m])) continue   // 受け皿なし
                        if (evaluations >= maxEvaluations) break
                        tried = true; evaluations++
                        work[i][j] = m
                        val after = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                        work[i][j] = k
                        if (betterReport(after, bestAfter ?: bestRep)) { bestI = i; bestM = m; bestAfter = after }
                        else worstWorsenedFamily(after, bestRep)?.let { famHits[it] = (famHits[it] ?: 0) + 1 }
                    }
                    if (!tried) noRoom++ else if (bestI != i) worse++
                }
                if (bestI >= 0 && bestAfter != null) {
                    adopted.add("${name(bestI)} ${j + 1}日 ${sym(k)}→${sym(bestM)}")
                    work[bestI][j] = bestM; bestRep = bestAfter; applied++; improved = true
                    recount()
                } else {
                    rejected += worse
                    residual["${sym(k)}@${j + 1}"] = when {
                        pinned > 0 && worse == 0 && noRoom == 0 -> "希望固定"
                        worse > 0 -> "重み悪化"
                        else -> "受け皿なし"
                    }
                }
            }
        }
        val log = buildString {
            append("人員過剰の退避: covO ${before.breakdown["covO"] ?: 0}->${bestRep.breakdown["covO"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回")
            if (adopted.isNotEmpty()) append(" 対象: " + adopted.take(8).joinToString(", ") + (if (adopted.size > 8) " ほか${adopted.size - 8}件" else ""))
            if (rejected > 0) append(" 不採用${rejected}件" + famHits.entries.sortedByDescending { it.value }.take(3).joinToString(" ", "(主因 ", ")") { "${it.key}:${it.value}" })
            if (residual.isNotEmpty()) append(" 残存: " + residual.entries.take(8).joinToString(", ") { "${it.key}(${it.value})" })
            if (evaluations >= maxEvaluations) append(" [評価上限で打ち切り]")
        }
        return Result(work, before.breakdown["covO"] ?: 0, bestRep.breakdown["covO"] ?: 0, applied, listOf(MirrorLog(tag = "CovORelief", message = log)), report = bestRep)
    }
}
