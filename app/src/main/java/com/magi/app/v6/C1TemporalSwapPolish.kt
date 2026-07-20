package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * C1時系列DP研磨。
 *
 * 既存C1Polishの手R3は1回の自己swapだけをbest-improvementするため、2回以上のswapを
 * 同時採用しないとC1が下がらない局所最適を越えられない。本パスは次の2段階で解く。
 *
 * 1. C1TemporalDpで、対象職員の対象シフト配置（二値列）を月全体で厳密最適化する。
 *    対象シフトの月間回数は保存し、最大4回を同時移設する。
 * 2. 変更日ごとに同日スワップ相手を選ぶbeam searchを行う。
 *    日別シフト多重集合を完全保存するためcovU/covOは構造的不変。
 *
 * DP/beamの費用は候補生成専用。最終採否は必ずUnifiedViolationCheckerと
 * hard→total→weightedScoreのkeep-bestで行う。
 */
internal object C1TemporalSwapPolish {
    private data class DaySwap(
        val day: Int,
        val partner: Int,
    )

    private data class Beam(
        val schedule: Array<IntArray>,
        val swaps: List<DaySwap>,
        val approx: Long,
    )

    private data class Plan(
        val schedule: Array<IntArray>,
        val report: ViolationReport,
        val staff: Int,
        val shift: Int,
        val relocations: Int,
        val swaps: Int,
    )

    fun apply(
        state: MagiState,
        schedule: Array<IntArray>,
        maxPasses: Int = 2,
        maxRelocations: Int = 4,
        trials: Int = 6,
        beamWidth: Int = 256,
        shouldStop: () -> Boolean = { false },
        seed: Long = 0xC1D0L,
    ): V6HotfixPasses.CyclicSwapResult {
        val p = Problem(state)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        var dpCandidates = 0
        var beamCandidates = 0
        var skippedLargeWindow = 0
        val fixedLabels = ArrayList<String>()

        if (p.cons1.isEmpty()) {
            return V6HotfixPasses.CyclicSwapResult(
                work, before.total, before.total, 0,
                listOf(MirrorLog(tag = "C1TemporalDP", message = "cons1なし=スキップ")),
            )
        }

        val rulesByShift = LinkedHashMap<Int, MutableList<C1TemporalDp.Rule>>()
        for (c in p.cons1) {
            if (c.shiftIdx !in 0 until p.K || c.day1 <= 0 || c.day2 <= 0) continue
            rulesByShift.getOrPut(c.shiftIdx) { ArrayList() }
                .add(C1TemporalDp.Rule(c.day1, c.day2))
        }

        fun better(a: ViolationReport, b: ViolationReport): Boolean {
            if (a.hard != b.hard) return a.hard < b.hard
            if (a.total != b.total) return a.total < b.total
            return a.weightedScore < b.weightedScore
        }

        fun rowC1Fires(s: Array<IntArray>, i: Int): Int {
            var out = 0
            for (c in p.cons1) {
                val x = c.shiftIdx
                val d = c.day1
                if (x !in 0 until p.K || d <= 0 || d > p.T || !p.canDo(i, x)) continue
                var count = 0
                for (j in 0 until d) if (s[i][j] == x) count++
                if (count < c.day2) out++
                var start = 1
                while (start <= p.T - d) {
                    if (s[i][start - 1] == x) count--
                    if (s[i][start + d - 1] == x) count++
                    if (count < c.day2) out++
                    start++
                }
            }
            return out
        }

        fun rowC3nFires(s: Array<IntArray>, i: Int): Int {
            var out = 0
            for (c in p.cons3n) {
                val seq = c.seq
                val d = seq.size
                if (d == 0 || d > p.T) continue
                var start = 0
                while (start <= p.T - d) {
                    var same = true
                    for (l in 0 until d) {
                        if (s[i][start + l] != seq[l]) { same = false; break }
                    }
                    if (same) out++
                    start++
                }
            }
            return out
        }

        fun rowRangeAptPenalty(s: Array<IntArray>, i: Int): Int {
            val counts = IntArray(p.K)
            for (j in 0 until p.T) {
                val k = s[i][j]
                if (k in 0 until p.K) counts[k]++
            }
            var out = 0
            for (k in 0 until p.K) {
                val n = counts[k]
                val lo = p.rangeLo[i][k]
                val hi = p.rangeHi[i][k]
                if (lo != Int.MIN_VALUE && n < lo) out += (lo - n) * 90
                if (hi != Int.MAX_VALUE && n > hi) out += (n - hi) * 45
                val target = p.apt[i][k]
                if (target >= 0) out += kotlin.math.abs(n - target)
            }
            return out
        }

        fun approxScore(s: Array<IntArray>, swaps: Int): Long {
            var c3n = 0L
            var c1 = 0L
            var range = 0L
            for (i in 0 until p.S) {
                c3n += rowC3nFires(s, i).toLong()
                c1 += rowC1Fires(s, i).toLong()
                range += rowRangeAptPenalty(s, i).toLong()
            }
            return c3n * 1_000_000_000_000L + c1 * 1_000_000L + range * 1_000L + swaps
        }

        fun planKey(s: Array<IntArray>, days: IntArray): String {
            val sb = StringBuilder(days.size * p.S * 3)
            for (j in days) {
                sb.append(j).append(':')
                for (i in 0 until p.S) sb.append(s[i][j]).append(',')
                sb.append(';')
            }
            return sb.toString()
        }

        fun buildPlan(i: Int, x: Int, candidate: C1TemporalDp.Candidate): Plan? {
            val changedDays = (0 until p.T).filter { j ->
                candidate.targetDays[j] != (work[i][j] == x)
            }.toIntArray()
            if (changedDays.isEmpty()) return null

            // 各変更日は、対象職員と1人の相手の同日swapで実現する。これにより日別人数を完全保存する。
            var beams = listOf(Beam(work.copy2D(), emptyList(), approxScore(work, 0)))
            for (j in changedDays) {
                if (shouldStop()) return null
                val next = ArrayList<Beam>()
                val targetGetsX = candidate.targetDays[j]
                for (beam in beams) {
                    val curTarget = beam.schedule[i][j]
                    for (partner in 0 until p.S) {
                        if (partner == i) continue
                        if (p.wishLocked(i, j) || p.wishLocked(partner, j)) continue
                        val curPartner = beam.schedule[partner][j]
                        if (targetGetsX) {
                            // i: a→X, partner: X→a
                            if (curTarget == x || curPartner != x) continue
                            if (!p.canDo(i, x) || !p.canDo(partner, curTarget)) continue
                        } else {
                            // i: X→b, partner: b→X
                            if (curTarget != x || curPartner == x) continue
                            if (!p.canDo(i, curPartner) || !p.canDo(partner, x)) continue
                        }
                        val cand = beam.schedule.copy2D()
                        cand[i][j] = curPartner
                        cand[partner][j] = curTarget
                        val swaps = beam.swaps + DaySwap(j, partner)
                        next.add(Beam(cand, swaps, approxScore(cand, swaps.size)))
                    }
                }
                if (next.isEmpty()) return null
                beamCandidates += next.size
                next.sortWith(compareBy<Beam> { it.approx }.thenBy { it.swaps.joinToString(",") { s -> "${s.day}:${s.partner}" } })
                val dedup = LinkedHashMap<String, Beam>()
                for (b in next) {
                    val key = planKey(b.schedule, changedDays)
                    if (key !in dedup) dedup[key] = b
                    if (dedup.size >= beamWidth.coerceAtLeast(1)) break
                }
                beams = dedup.values.toList()
            }

            val oldRowFires = rowC1Fires(work, i)
            var best: Plan? = null
            for (beam in beams) {
                if (shouldStop()) break
                if (rowC1Fires(beam.schedule, i) >= oldRowFires) continue
                val rep = UnifiedViolationChecker.check(state, beam.schedule)
                if (!better(rep, bestRep)) continue
                val old = best
                if (old == null || better(rep, old.report) ||
                    (rep.hard == old.report.hard && rep.total == old.report.total &&
                        kotlin.math.abs(rep.weightedScore - old.report.weightedScore) <= 1e-9 &&
                        beam.swaps.size < old.swaps)
                ) {
                    best = Plan(beam.schedule, rep, i, x, candidate.relocations, beam.swaps.size)
                }
            }
            return best
        }

        var pass = 0
        while (pass < maxPasses && !shouldStop()) {
            var improved = false
            for (i in 0 until p.S) {
                if (shouldStop()) break
                if (rowC1Fires(work, i) == 0) continue
                var bestForStaff: Plan? = null
                for ((x, rules) in rulesByShift) {
                    if (shouldStop()) break
                    if (!p.canDo(i, x)) continue
                    val focusBefore = C1TemporalDp.countFires(work[i], x, rules)
                    if (focusBefore == 0) continue
                    if (rules.any { it.days > 20 }) { skippedLargeWindow++; continue }
                    val locked = BooleanArray(p.T) { j -> p.wishLocked(i, j) }
                    for (trial in 0 until trials.coerceAtLeast(1)) {
                        if (shouldStop()) break
                        val trialSeed = seed xor (i.toLong() shl 32) xor (x.toLong() shl 16) xor
                            (pass.toLong() shl 8) xor trial.toLong()
                        val cand = C1TemporalDp.solve(
                            work[i], x, rules, locked,
                            maxRelocations = maxRelocations,
                            seed = trialSeed,
                        ) ?: continue
                        dpCandidates++
                        val plan = buildPlan(i, x, cand) ?: continue
                        val old = bestForStaff
                        if (old == null || better(plan.report, old.report) ||
                            (plan.report.hard == old.report.hard && plan.report.total == old.report.total &&
                                kotlin.math.abs(plan.report.weightedScore - old.report.weightedScore) <= 1e-9 &&
                                plan.swaps < old.swaps)
                        ) bestForStaff = plan
                    }
                }
                val chosen = bestForStaff ?: continue
                work = chosen.schedule.copy2D()
                bestRep = chosen.report
                applied++
                improved = true
                val name = state.staff.getOrNull(chosen.staff)?.name ?: "#${chosen.staff}"
                val symbol = state.shifts.getOrNull(chosen.shift)?.kigou ?: chosen.shift.toString()
                fixedLabels.add("$name $symbol(${chosen.relocations}移設/${chosen.swaps}同日交換)")
            }
            pass++
            if (!improved) break
        }

        val logs = listOf(
            MirrorLog(
                tag = "C1TemporalDP",
                message = "期間要件(c1)時系列DP研磨: c1 ${before.breakdown["c1"] ?: 0}->${bestRep.breakdown["c1"] ?: 0} / " +
                    "total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回 " +
                    "DP候補$dpCandidates beam候補$beamCandidates" +
                    (if (skippedLargeWindow > 0) " 長窓fallback:$skippedLargeWindow" else "") +
                    (if (fixedLabels.isNotEmpty()) " 対象: ${fixedLabels.joinToString(", ")}" else "") +
                    (if (applied == 0 && (before.breakdown["c1"] ?: 0) > 0) " [頭打ち=同時交換解なし]" else ""),
            ),
        )
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs)
    }
}
