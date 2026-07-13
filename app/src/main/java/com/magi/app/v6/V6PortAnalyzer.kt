package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.MagiState
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Native port of the V6 Web overview / risk / load analysis layer. */
data class V6DayRisk(
    val dayIndex: Int,
    val label: String,
    val shortage: Int,
    val detail: String,
)

data class V6StaffProfile(
    val staffIndex: Int,
    val name: String,
    val groupSymbol: String,
    val workCount: Int,
    val violationCount: Int,
    val workloadText: String,
)

data class V6PortReport(
    val coveragePct: Int?,
    val demand: Int,
    val covU: Int,
    val hardCore: Int,
    val hardGuard: Int,
    val softCore: Int,
    val topRiskDay: Int,
    val topRiskLabel: String,
    val topRiskShortage: Int,
    val dayRisks: List<V6DayRisk>,
    val staffProfiles: List<V6StaffProfile>,
    val aptPenalty: Double,
    val equPenalty: Double,
    val sanityWarnings: List<String>,
    val sanityNotes: List<String>,
) {
    /** Days that still have a coverage shortage (referenced by the V6 RISK gauge). */
    val highRiskDays: Int get() = dayRisks.count { it.shortage > 0 }
}

/** 充足不可(infeasible)＝担当可能な職員数が必要数に届かない / 充足可能(fixable)＝枠は足りるが最適化が未到達。 */
enum class CoverageVerdict { INFEASIBLE, FIXABLE }

/** 人員不足(covU)が残る 1 つの (日, シフト) 枠の診断。読み取り専用・エンジン非変更。 */
data class CoverageShortfall(
    val dayIndex: Int,
    val dayLabel: String,
    val shiftIndex: Int,
    val shiftSymbol: String,
    val need: Int,
    val got: Int,
    val miss: Int,
    /** その日そのシフトに法的に配置し得る最大職員数（担当可 かつ 別シフトへ希望固定されていない）。 */
    val capacity: Int,
    val verdict: CoverageVerdict,
    val reason: String,
)

/** covU(人員不足)の原因診断。どの枠が「数学的に充足不可」か「充足可能だが未到達」かを切り分ける。 */
data class CoverageDiagnosis(
    val totalShortfall: Int,
    val infeasibleSlots: Int,
    val fixableSlots: Int,
    val shortfalls: List<CoverageShortfall>,
    val relaxations: List<String> = emptyList(),  // [IIS/緩和案] 担当追加で解ける見込みの提案（データは変えない）
) {
    val hasShortage: Boolean get() = totalShortfall > 0
    /** 不足が全て「充足不可」＝このデータでは HARD=0 にできない（想定内の残存）。 */
    val allInfeasible: Boolean get() = hasShortage && fixableSlots == 0

    /** 診断ログ（エクスポートされる「MAGI ログ」に載る形式の文字列）。 */
    fun logLines(): List<String> {
        if (!hasShortage) return emptyList()
        val out = ArrayList<String>()
        out.add("[W] CoverageDiag: 人員不足 合計${totalShortfall} — 充足不可${infeasibleSlots}枠 / 充足可能${fixableSlots}枠")
        for (s in shortfalls.take(8)) {
            val v = if (s.verdict == CoverageVerdict.INFEASIBLE) "充足不可" else "充足可能"
            out.add("[W] CoverageDiag: ${s.dayLabel} ${s.shiftSymbol} 必要${s.need}/現状${s.got}(不足${s.miss}) — ${v}: ${s.reason}")
        }
        if (shortfalls.size > 8) out.add("[W] CoverageDiag: ほか${shortfalls.size - 8}枠")
        for (r in relaxations.take(4)) out.add("[W] CoverageDiag 緩和案: $r")
        return out
    }
}

object V6PortAnalyzer {
    /**
     * 人員不足(covU)の枠ごとの原因診断。エンジンは変更せず、現在の解だけを読み取り、
     * 各不足枠について「担当可能な職員の最大数(capacity)」を数え、必要数に届くかで判定する。
     *  - capacity < need → INFEASIBLE（どう割り当ててもこの枠は埋まらない＝データ上充足不可）
     *  - capacity >= need → FIXABLE（枠は足りる。他シフトに就いている人を移せば理論上は解消し得るが、
     *    並び/回数などの制約に阻まれ最適化が未到達）
     */
    fun diagnoseCoverage(
        state: MagiState,
        schedule: Array<IntArray> = state.schedule.toIntArray2D(),
        report: ViolationReport = UnifiedViolationChecker.check(state, schedule),
    ): CoverageDiagnosis {
        val p = cachedProblem(state)
        val norm = normalizeSchedule(schedule, p)
        val cov = coverage(p, norm)
        // [なぜ埋まらないか / 三連・五連など任意長対応] 職員 i を日 j にシフト newK へ動かすと
        //   禁止連続(c3n)を作るか。Problem.makesForbiddenRun が任意長ルールを一般判定する。
        fun c3nAt(i: Int, j: Int, newK: Int): Boolean = p.makesForbiddenRun(norm, i, j, newK)
        val list = ArrayList<CoverageShortfall>()
        var infeasible = 0
        var fixable = 0
        var total = 0
        for (j in 0 until p.T) {
            for (k in 0 until p.K) {
                val need = p.need1[k][j]
                if (need <= 0) continue
                val got = cov[j][k]
                val miss = need - got
                if (miss <= 0) continue
                total += miss
                var capacity = 0
                for (i in 0 until p.S) {
                    if (!p.canDo(i, k)) continue
                    val w = p.wish[i][j]
                    if (w in 0 until p.K && w != k) continue   // 別シフトへ希望固定 → この枠には回せない
                    capacity++
                }
                val verdict = if (capacity < need) CoverageVerdict.INFEASIBLE else CoverageVerdict.FIXABLE
                if (verdict == CoverageVerdict.INFEASIBLE) infeasible++ else fixable++
                val sym = state.shifts.getOrNull(k)?.kigou ?: k.toString()
                val reason = if (verdict == CoverageVerdict.INFEASIBLE) {
                    "担当可能な職員が${capacity}人で必要数${need}に届きません（データ上、充足不可）"
                } else {
                    // [なぜ埋まらないか] 「移せる候補」(canDo・別シフト希望でない・現在このシフトに未在勤)を、
                    //   なぜ今動かせないかで4分類する。空き番=休/過剰から直接移せる / 玉突き=引くと別のcovU /
                    //   希望固定=本人の希望で固定 / 禁止連続=移すと c3n。読取専用・スコア不変。
                    var free = 0; var cascade = 0; var pinned = 0; var forbid = 0
                    for (i in 0 until p.S) {
                        if (!p.canDo(i, k)) continue
                        val m = norm[i][j]
                        if (m == k) continue                                   // 既にこのシフト=移す対象でない
                        val w = p.wish[i][j]
                        if (w in 0 until p.K && w != k) continue               // 別シフトへ希望固定=capacity 対象外
                        if (p.wishLocked(i, j)) { pinned++; continue }
                        if (c3nAt(i, j, k)) { forbid++; continue }
                        // m から1人引くと covU が増える=玉突き（多人数入替=連鎖でしか解けない）。
                        if (m in 0 until p.K && p.covUCell(m, j, cov[j][m] - 1) > p.covUCell(m, j, cov[j][m])) cascade++ else free++
                    }
                    val hint = when {
                        free > 0 -> "空き番${free}人を${sym}へ移せば充足（最適化が未到達＝勤務表でこのセルの『直し方を探す』で解消可）"
                        cascade > 0 -> "空き番が無く、過剰シフトからの多人数入替（玉突き=ブロック移動）が必要"
                        else -> "候補が希望/禁止連続で塞がっており、希望を1件調整するか担当を追加すると解消に近づく"
                    }
                    "担当可能${capacity}人・今動かせる空き番${free}人（玉突き${cascade}・希望固定${pinned}・禁止連続${forbid}）。$hint"
                }
                list.add(
                    CoverageShortfall(j, dayLabel(state.startDate, j), k, sym, need, got, miss, capacity, verdict, reason)
                )
            }
        }
        list.sortWith(
            compareByDescending<CoverageShortfall> { it.verdict == CoverageVerdict.INFEASIBLE }
                .thenByDescending { it.miss }
        )
        // [緩和案/IIS] 構造的に充足不可なシフトについて、担当追加(クロストレーニング)で解ける見込みを提示する。
        //   候補は未活用(需要のあるシフトへの稼働が少ない)職員を優先。これは担当追加の「提案」であって
        //   データは一切変更しない（採否は業務担当者が判断）。HF77準拠。
        val relaxations = ArrayList<String>()
        run {
            val demandShifts = (0 until p.K).filter { kk -> (0 until p.T).any { jj -> p.need1[kk][jj] > 0 } }.toSet()
            fun demandLoad(i: Int): Int = (0 until p.T).count { jj -> norm[i][jj] in demandShifts }
            val infeasByShift = list.filter { it.verdict == CoverageVerdict.INFEASIBLE }
                .groupBy { it.shiftIndex }
                .mapValues { e -> e.value.maxOf { it.miss } }
            for ((k, peakMiss) in infeasByShift.entries.sortedByDescending { it.value }) {
                val sym = state.shifts.getOrNull(k)?.kigou ?: "$k"
                val cands = (0 until p.S).filter { !p.canDo(it, k) }
                    .sortedBy { demandLoad(it) }
                    .take((peakMiss + 1).coerceAtLeast(2))
                    .map { state.staff.getOrNull(it)?.name ?: "#$it" }
                if (cands.isNotEmpty()) {
                    relaxations.add("「$sym」は担当可能者が不足（ピーク不足${peakMiss}人）。$sym を ${cands.joinToString("・")}（稼働が少なめ）に担当追加すると解消に近づきます")
                }
            }
        }
        return CoverageDiagnosis(total, infeasible, fixable, list, relaxations)
    }

    fun analyze(
        state: MagiState,
        schedule: Array<IntArray> = state.schedule.toIntArray2D(),
        report: ViolationReport = UnifiedViolationChecker.check(state, schedule),
    ): V6PortReport {
        val p = cachedProblem(state)
        val normalized = normalizeSchedule(schedule, p)
        val cov = coverage(p, normalized)
        val counts = countMatrix(p, normalized)
        val dayRisks = buildDayRisks(state, p, cov)
        val demand = totalDemand(p)
        val covU = report.breakdown["covU"] ?: 0
        val coveragePct = if (demand > 0) {
            (((demand - covU).coerceAtLeast(0).toDouble() / demand.toDouble()) * 100.0).roundToInt()
        } else null

        var topRiskDay = -1
        var topRiskLabel = "-"
        var topRiskShortage = 0
        for (risk in dayRisks) {
            if (risk.shortage > topRiskShortage) {
                topRiskDay = risk.dayIndex
                topRiskLabel = risk.label
                topRiskShortage = risk.shortage
            }
        }

        val hardGuard = report.breakdown["groupViol"] ?: 0
        val hardCore = (report.breakdown["c3n"] ?: 0) + (report.breakdown["covU"] ?: 0) + (report.breakdown["pref"] ?: 0)
        val softCore = (report.total - hardGuard - hardCore).coerceAtLeast(0)
        val staffViol = staffViolationCounts(p, report)

        return V6PortReport(
            coveragePct = coveragePct,
            demand = demand,
            covU = covU,
            hardCore = hardCore,
            hardGuard = hardGuard,
            softCore = softCore,
            topRiskDay = topRiskDay,
            topRiskLabel = topRiskLabel,
            topRiskShortage = topRiskShortage,
            dayRisks = dayRisks,
            staffProfiles = buildStaffProfiles(state, p, normalized, counts, staffViol),
            aptPenalty = aptPenalty(state, p, counts),
            equPenalty = equalizationPenalty(state, p, normalized, counts, report.breakdown),
            sanityWarnings = sanityWarnings(state, p, normalized),
            sanityNotes = sanityNotes(state),
        )
    }

    private fun totalDemand(p: Problem): Int {
        var out = 0
        for (j in 0 until p.T) {
            for (k in 0 until p.K) {
                val need = p.need1[k][j]
                if (need > 0) out += need
            }
        }
        return out
    }

    private fun buildDayRisks(state: MagiState, p: Problem, cov: Array<IntArray>): List<V6DayRisk> {
        val out = ArrayList<V6DayRisk>(p.T)
        for (j in 0 until p.T) {
            var shortfall = 0
            val parts = ArrayList<String>()
            for (k in 0 until p.K) {
                val need = p.need1[k][j]
                if (need < 0) continue
                val miss = (need - cov[j][k]).coerceAtLeast(0)
                shortfall += miss
                if (miss > 0) {
                    val sym = state.shifts.getOrNull(k)?.kigou ?: k.toString()
                    parts.add("${sym}×${miss}")
                }
            }
            out.add(V6DayRisk(j, dayLabel(state.startDate, j), shortfall, parts.joinToString(" ")))
        }
        return out
    }

    private fun staffViolationCounts(p: Problem, report: ViolationReport): IntArray {
        val out = IntArray(p.S)
        fun addCellKey(key: String) {
            val i = key.substringBefore(',').toIntOrNull() ?: return
            if (i in 0 until p.S) out[i]++
        }
        for (key in report.violations.keys) addCellKey(key)
        for (key in report.countViolations.keys) addCellKey(key)
        return out
    }

    private fun buildStaffProfiles(
        state: MagiState,
        p: Problem,
        schedule: Array<IntArray>,
        counts: Array<IntArray>,
        staffViol: IntArray,
    ): List<V6StaffProfile> {
        val rest = state.shifts.indexOfFirst { shift -> shift.kigou == "休" }
        val profiles = ArrayList<V6StaffProfile>(p.S)
        for (i in 0 until p.S) {
            var work = 0
            for (j in 0 until p.T) if (schedule[i][j] != rest) work++

            val pairs = ArrayList<Pair<Int, Int>>()
            for (k in 0 until p.K) {
                val n = counts[i][k]
                if (n > 0) pairs.add(Pair(k, n))
            }
            pairs.sortByDescending { pair -> pair.second }
            val parts = ArrayList<String>()
            val limit = minOf(3, pairs.size)
            for (idx in 0 until limit) {
                val k = pairs[idx].first
                val n = pairs[idx].second
                val sym = state.shifts.getOrNull(k)?.kigou ?: k.toString()
                parts.add("${sym}:${n}")
            }
            val staff = state.staff.getOrNull(i)
            val g = staff?.groupIdx ?: -1
            profiles.add(
                V6StaffProfile(
                    staffIndex = i,
                    name = staff?.name ?: "#${i}",
                    groupSymbol = state.groups.getOrNull(g)?.kigou ?: "",
                    workCount = work,
                    violationCount = staffViol.getOrElse(i) { 0 },
                    workloadText = parts.joinToString(" / "),
                )
            )
        }
        profiles.sortWith(compareByDescending<V6StaffProfile> { it.violationCount }.thenByDescending { it.workCount })
        return profiles
    }

    /** V6 CountApt port: sum((count - apt)^2 / apt^2), group aptitude expanded to staff. */
    private fun aptPenalty(state: MagiState, p: Problem, counts: Array<IntArray>): Double {
        var out = 0.0
        for (i in 0 until p.S) {
            val g = p.sgrp.getOrNull(i) ?: continue
            val row = state.groupShiftApt.getOrNull(g) ?: continue
            for (k in 0 until p.K) {
                val apt = row.getOrNull(k)?.trim()?.toDoubleOrNull() ?: continue
                if (apt <= 0.0) continue
                val d = counts[i][k].toDouble() - apt
                out += (d * d) / (apt * apt)
            }
        }
        return out
    }

    /** Compact V6 equalization overview: member variance + day-of-week variance with HARD-aware psi. */
    private fun equalizationPenalty(
        state: MagiState,
        p: Problem,
        schedule: Array<IntArray>,
        counts: Array<IntArray>,
        breakdown: Map<String, Int>,
    ): Double {
        if (p.S == 0 || p.T == 0 || p.K == 0 || p.G == 0) return 0.0
        val members = Array(p.G) { ArrayList<Int>() }
        for (i in 0 until p.S) {
            val g = p.sgrp[i]
            if (g in 0 until p.G) members[g].add(i)
        }

        var raw = 0.0
        for (g in 0 until p.G) {
            val gs = state.groupShift.getOrNull(g) ?: continue
            val mem = members[g]
            if (mem.isEmpty()) continue
            for (k in 0 until p.K) {
                if (gs.getOrNull(k) != 1) continue
                if (mem.size == 1) {
                    val i = mem[0]
                    val explicit = explicitTarget(p, i, k)
                    val apt = state.groupShiftApt.getOrNull(g)?.getOrNull(k)?.trim()?.toDoubleOrNull()
                    val target = explicit ?: apt ?: continue
                    val dev = counts[i][k].toDouble() - target
                    raw += dev * dev + abs(dev) * 2.0
                } else {
                    var sum = 0
                    for (i in mem) sum += counts[i][k]
                    val mean = sum.toDouble() / mem.size.toDouble()
                    var varSum = 0.0
                    var maxDev = 0.0
                    for (i in mem) {
                        val d = counts[i][k].toDouble() - mean
                        varSum += d * d
                        maxDev = max(maxDev, abs(d))
                    }
                    raw += varSum + maxDev * 2.0
                }
            }
        }

        val startDow = startDow(state.startDate)
        val dowCnt = Array(p.S) { Array(7) { IntArray(p.K) } }
        for (i in 0 until p.S) {
            for (j in 0 until p.T) {
                val k = schedule[i][j]
                if (k in 0 until p.K) dowCnt[i][(startDow + j) % 7][k]++
            }
        }
        for (g in 0 until p.G) {
            val gs = state.groupShift.getOrNull(g) ?: continue
            val mem = members[g]
            if (mem.size <= 1) continue
            for (dow in 0 until 7) {
                for (k in 0 until p.K) {
                    if (gs.getOrNull(k) != 1) continue
                    var sum = 0
                    for (i in mem) sum += dowCnt[i][dow][k]
                    val mean = sum.toDouble() / mem.size.toDouble()
                    var varSum = 0.0
                    var maxDev = 0.0
                    for (i in mem) {
                        val d = dowCnt[i][dow][k].toDouble() - mean
                        varSum += d * d
                        maxDev = max(maxDev, abs(d))
                    }
                    raw += varSum + maxDev * 2.0
                }
            }
        }
        val hard = (breakdown["groupViol"] ?: 0) + (breakdown["c3n"] ?: 0) + (breakdown["covU"] ?: 0) + (breakdown["pref"] ?: 0)
        val psi = max(0.2, 1.0 / (1.0 + 10.0 * hard.toDouble()))
        return raw * psi
    }

    private fun explicitTarget(p: Problem, i: Int, k: Int): Double? {
        val loSet = p.rangeLo[i][k] != Int.MIN_VALUE
        val hiSet = p.rangeHi[i][k] != Int.MAX_VALUE
        return when {
            loSet && hiSet -> (p.rangeLo[i][k] + p.rangeHi[i][k]).toDouble() / 2.0
            loSet -> p.rangeLo[i][k].toDouble()
            hiSet -> p.rangeHi[i][k].toDouble()
            else -> null
        }
    }

    private fun sanityWarnings(state: MagiState, p: Problem, schedule: Array<IntArray>): List<String> {
        val warns = ArrayList<String>()
        var badAssign = 0
        for (i in 0 until p.S) {
            for (j in 0 until p.T) {
                val k = schedule[i][j]
                if (k in 0 until p.K && !p.canDo(i, k)) badAssign++
            }
        }
        if (badAssign > 0) warns.add("担当不可の配置が ${badAssign} セルあります")

        var badWish = 0
        for ((key, k) in state.wishes) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull()
            val j = parts.getOrNull(1)?.toIntOrNull()
            if (i == null || j == null || i !in 0 until p.S || j !in 0 until p.T || k !in 0 until p.K) {
                badWish++
            } else if (!p.canDo(i, k)) {
                badWish++
            }
        }
        if (badWish > 0) warns.add("範囲外または担当外の希望シフトが ${badWish} 件あります")

        var badRange = 0
        for ((key, r) in state.staffRange) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull()
            val k = parts.getOrNull(1)?.toIntOrNull()
            val lo = r.lo.trim().toIntOrNull()
            val hi = r.hi.trim().toIntOrNull()
            if (i == null || k == null || i !in 0 until p.S || k !in 0 until p.K) badRange++
            if (lo != null && hi != null && lo > hi) badRange++
        }
        if (badRange > 0) warns.add("staffRange の範囲外キーまたは lo>hi が ${badRange} 件あります")

        val dup = duplicatePatternCount(state)
        if (dup > 0) warns.add("連続パターン制約の重複定義が ${dup} 件あります")
        if (state.cons41.isEmpty()) warns.add("cons41 が未設定です（グループ別人数範囲を使う場合は確認）")
        return warns
    }

    private fun sanityNotes(state: MagiState): List<String> {
        val notes = ArrayList<String>()
        var aptSet = 0
        for (row in state.groupShiftApt) for (cell in row) if (cell.trim().isNotEmpty()) aptSet++
        notes.add("groupShiftApt 適切回数: ${aptSet} 件設定")
        if (state.needDay1.isEmpty() && state.needDay2.isEmpty()) notes.add("needDay1/needDay2 は全空です（shift既定 need を使用）")
        notes.add("V6 Native overview: リスクカレンダー / 負荷プロフィール / SanityCheck 有効")
        return notes
    }

    private fun duplicatePatternCount(state: MagiState): Int {
        return countDuplicatePatternRows(state.cons3) +
            countDuplicatePatternRows(state.cons3n) +
            countDuplicatePatternRows(state.cons3m) +
            countDuplicatePatternRows(state.cons3mn)
    }

    private fun countDuplicatePatternRows(rows: List<C3Row>): Int {
        val seen = HashSet<String>()
        var dup = 0
        for (row in rows) {
            val symbols = ArrayList<String>()
            for (s in row.pattern) {
                if (s.isBlank()) break
                symbols.add(s)
            }
            val key = symbols.joinToString("→")
            if (key.isBlank()) continue
            if (!seen.add(key)) dup++
        }
        return dup
    }
}

fun dayLabel(startDate: String, offset: Int): String {
    return try {
        val d = LocalDate.parse(startDate).plusDays(offset.toLong())
        val wd = "月火水木金土日"[d.dayOfWeek.value - 1]
        "${d.monthValue}/${d.dayOfMonth}(${wd})"
    } catch (_: Exception) {
        "${offset + 1}日"
    }
}

private fun startDow(startDate: String): Int {
    return try {
        LocalDate.parse(startDate).dayOfWeek.value % 7
    } catch (_: Exception) {
        0
    }
}
