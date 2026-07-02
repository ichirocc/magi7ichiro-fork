package com.magi.app.v6

import com.magi.app.model.MagiState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import kotlin.math.exp
import kotlin.math.max

/**
 * Kotlin port of magi_python_mirror.py.
 *
 * The high-speed native SA remains the main optimizer, but this module brings the
 * mirror app's operational layer into the Android app: unified violation breakdown,
 * greedy/simple schedule creation, light local search, and CSV round-trip helpers.
 */
data class MirrorLog(
    val ts: Long = System.currentTimeMillis(),
    val iter: Long = 0,
    val level: String = "I",
    val tag: String,
    val message: String,
)

data class ViolationReport(
    val violations: Map<String, String>,
    val needViolations: Map<String, String>,
    val countViolations: Map<String, String>,
    val breakdown: Map<String, Int>,
    val total: Int,
    val hard: Int,
    val soft: Int,
    val weightedScore: Double,
    val logs: List<MirrorLog> = emptyList(),
)

data class ScheduleRunResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    /** CSV取込で氏名が一致したスタッフ行数。最適化系の結果では未使用(-1)。 */
    val matched: Int = -1,
)

data class LightOptimizeResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val iterations: Long,
    val accepts: Long,
    val elapsedMs: Long,
)

object MirrorKeys {
    val hard = listOf("groupViol", "c3n", "covU", "pref")
    val soft = listOf("c1", "c2", "c3", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covO", "low", "high", "apt", "fair")
    val all = listOf("c1", "c2", "c3", "c3n", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covU", "covO", "pref", "low", "high", "groupViol", "apt", "fair")
    // [N2/⛏11] weightedScore の重み（単一の真実）。UI の重み表もこのマップを描画して
    //   最適化器とのドリフトを防ぐ。挿入順 = weightedScore の加算順（Double 結果を不変に保つ）。
    val weights: Map<String, Double> = linkedMapOf(
        "groupViol" to 10000.0, "pref" to 9000.0, "covU" to 8000.0, "c3n" to 7000.0,
        "low" to 90.0, "high" to 45.0,
        "c3mn" to 12.0, "c1" to 4.0, "c3" to 3.0, "c3m" to 2.0,
        "c2" to 1.0, "c41" to 1.0, "c42" to 1.0, "c41s" to 1.0, "c42s" to 1.0,
        "apt" to 1.0, "fair" to 1.0,
        "covO" to 0.5,
    )
}

object UnifiedViolationChecker {
    private val vioClass = mapOf(
        "c1" to "vio-c1", "c2" to "vio-c2", "c3" to "vio-c3", "c3n" to "vio-c3n",
        "c3m" to "vio-c3m", "c3mn" to "vio-c3mn", "c41" to "vio-c41", "c42" to "vio-c42",
        "c41s" to "vio-c41s", "c42s" to "vio-c42s",
        "covU" to "vio-covU", "covO" to "vio-covO", "pref" to "vio-pref",
        "low" to "vio-low", "high" to "vio-high", "groupViol" to "vio-groupViol",
        // 適切回数(双方向目標): 不足=赤 / 超過=橙（range と同色だが家族は別。TallyCard/内訳で個別解決可能にする）。
        "aptLow" to "vio-aptLow", "aptHigh" to "vio-aptHigh",
    )

    fun check(state: MagiState, schedule: Array<IntArray> = state.schedule.toIntArray2D()): ViolationReport {
        val t0 = System.nanoTime()
        val p = cachedProblem(state)
        val s = normalizeSchedule(schedule, p)
        val breakdown = linkedMapOf<String, Int>()
        for (key0 in MirrorKeys.all) breakdown[key0] = 0
        val violations = linkedMapOf<String, String>()
        val needViolations = linkedMapOf<String, String>()
        val countViolations = linkedMapOf<String, String>()

        fun inc(key: String, amount: Int = 1) { breakdown[key] = (breakdown[key] ?: 0) + amount }
        fun mark(i: Int, j: Int, family: String) { violations["$i,$j"] = vioClass[family] ?: family }
        fun markNeed(k: Int, j: Int, family: String) { needViolations["$k,$j"] = vioClass[family] ?: family }
        fun markCount(i: Int, k: Int, family: String) { countViolations["$i,$k"] = vioClass[family] ?: family }
        fun cellIs(i: Int, j: Int, k: Int): Boolean = i in 0 until p.S && j in 0 until p.T && s[i][j] == k

        for (c in p.cons1) {
            for (i in 0 until p.S) {
                if (!p.canDo(i, c.shiftIdx)) continue
                var j = 0
                while (j <= p.T - c.day1) {
                    var z = 0
                    for (l in 0 until c.day1) if (cellIs(i, j + l, c.shiftIdx)) z++
                    if (z < c.day2) {
                        inc("c1")
                        for (l in 0 until c.day1) mark(i, j + l, "c1")
                    }
                    j++
                }
            }
        }

        val counts = countMatrix(p, s)
        for (c in p.cons2) {
            for (i in 0 until p.S) {
                if (!p.canDo(i, c.shiftIdx)) continue
                if (counts[i][c.shiftIdx] < c.count) {
                    inc("c2")
                    markCount(i, c.shiftIdx, "c2")
                }
            }
        }

        for (c in p.cons41) {
            for (j in 0 until p.T) {
                var z = 0
                for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && cellIs(i, j, c.shiftIdx)) z++
                if (z < c.l || z > c.u) {
                    inc("c41")
                    markNeed(c.shiftIdx, j, "c41")
                }
            }
        }

        for (c in p.cons42) {
            for (j in 0 until p.T) {
                val left = ArrayList<Int>()
                val right = ArrayList<Int>()
                for (i in 0 until p.S) {
                    if (p.sgrp[i] == c.g1 && cellIs(i, j, c.s1)) left.add(i)
                    if (p.sgrp[i] == c.g2 && cellIs(i, j, c.s2)) right.add(i)
                }
                for (i in left) for (i2 in right) {
                    inc("c42")
                    mark(i, j, "c42")
                    mark(i2, j, "c42")
                }
            }
        }

        // [スキルグループ新設] スキル群の C41/C42 相当（ssk を参照・既存ユニットの sgrp とは独立）。
        for (c in p.cons41s) {
            for (j in 0 until p.T) {
                var z = 0
                for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && cellIs(i, j, c.shiftIdx)) z++
                if (z < c.l || z > c.u) { inc("c41s"); markNeed(c.shiftIdx, j, "c41s") }
            }
        }
        for (c in p.cons42s) {
            for (j in 0 until p.T) {
                val left = ArrayList<Int>(); val right = ArrayList<Int>()
                for (i in 0 until p.S) {
                    if (p.ssk[i] == c.g1 && cellIs(i, j, c.s1)) left.add(i)
                    if (p.ssk[i] == c.g2 && cellIs(i, j, c.s2)) right.add(i)
                }
                for (i in left) for (i2 in right) { inc("c42s"); mark(i, j, "c42s"); mark(i2, j, "c42s") }
            }
        }

        checkC3Family(p, s, p.cons3, "c3", forbidden = false, { key, amt -> inc(key, amt) }, ::mark)
        checkC3Family(p, s, p.cons3n, "c3n", forbidden = true, { key, amt -> inc(key, amt) }, ::mark)
        checkC3Family(p, s, p.cons3m, "c3m", forbidden = false, { key, amt -> inc(key, amt) }, ::mark)
        checkC3Family(p, s, p.cons3mn, "c3mn", forbidden = true, { key, amt -> inc(key, amt) }, ::mark)

        for (i in 0 until p.S) for (j in 0 until p.T) {
            val w = p.wish[i][j]
            if (w in 0 until p.K && s[i][j] != w) {
                inc("pref")
                mark(i, j, "pref")
            }
        }

        for (i in 0 until p.S) {
            for (k in 0 until p.K) {
                val lo = p.rangeLo[i][k]
                val hi = p.rangeHi[i][k]
                val n = counts[i][k]
                if (lo != Int.MIN_VALUE && lo != 0 && p.canDo(i, k) && n < lo) {
                    inc("low", lo - n)
                    markCount(i, k, "low")
                }
                if (hi != Int.MAX_VALUE && n > hi) {
                    inc("high", n - hi)
                    markCount(i, k, "high")
                }
                // [統一apt] 適切回数(群単位の双方向目標)。SOFT・重み1・L1偏差|n-t|。担当可シフトのみ(apt 構築時に canDo ガード済)。
                // セル着色は range(low/high, 重み90/45)を優先し、未マークのときだけ apt 色(不足=赤/超過=橙)を付ける。
                val t = p.apt[i][k]
                if (t >= 0 && n != t) {
                    inc("apt", kotlin.math.abs(n - t))
                    if (!countViolations.containsKey("$i,$k")) markCount(i, k, if (n > t) "aptHigh" else "aptLow")
                }
            }
        }

        // [統一fair] グループ内公平化: 群×担当ONシフトごと、メンバー回数の round(平均) からの L1 偏差和。
        // SOFT・重み1。最適化器(Evaluator/Delta)と同一指標。内訳チップ(UI)には出さず weightedScore/total に算入。
        for (g in 0 until p.G) {
            val mem = p.groupMembers[g]
            val m = mem.size
            if (m < 2) continue
            for (k in p.bucket[g]) {
                var sum = 0
                for (x in mem) sum += counts[x][k]
                val tgt = Math.round(sum.toDouble() / m).toInt()
                var d = 0
                for (x in mem) d += kotlin.math.abs(counts[x][k] - tgt)
                if (d > 0) inc("fair", d)
            }
        }

        val cov = coverage(p, s)
        for (j in 0 until p.T) {
            for (k in 0 until p.K) {
                val lo = p.need1[k][j]
                if (lo < 0) continue
                val hi = if (p.use2 && p.need2[k][j] >= 0) p.need2[k][j] else lo
                val got = cov[j][k]
                if (got < lo) {
                    inc("covU", lo - got)
                    markNeed(k, j, "covU")
                } else if (got > hi) {
                    inc("covO", got - hi)
                    markNeed(k, j, "covO")
                }
            }
        }

        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = s[i][j]
            if (k in 0 until p.K && !p.canDo(i, k)) {
                inc("groupViol")
                mark(i, j, "groupViol")
            }
        }

        var total = 0
        for (v in breakdown.values) total += v
        var hard = 0
        for (key0 in MirrorKeys.hard) hard += breakdown[key0] ?: 0
        val soft = total - hard
        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L)
        val hardParts = ArrayList<String>()
        for (key0 in MirrorKeys.hard) hardParts.add("${key0}=${breakdown[key0] ?: 0}")
        val hardStr = hardParts.joinToString(" ")
        val softParts = ArrayList<String>()
        for (key0 in MirrorKeys.soft) {
            val n = breakdown[key0] ?: 0
            if (n > 0) softParts.add("${key0}=${n}")
        }
        val softStr = softParts.joinToString(" ")
        val msg = if (total == 0) {
            "違反なし"
        } else {
            "合計=$total | HARD=$hard [$hardStr]" + if (soft > 0) " | SOFT=$soft [$softStr]" else ""
        }
        val level = if (total == 0) "I" else "W"
        return ViolationReport(
            violations = violations,
            needViolations = needViolations,
            countViolations = countViolations,
            breakdown = breakdown,
            total = total,
            hard = hard,
            soft = soft,
            weightedScore = weightedScore(breakdown),
            logs = listOf(MirrorLog(iter = 0, level = level, tag = "UnifiedCheck", message = "$msg (${elapsedMs}ms)")),
        )
    }

    private fun checkC3Family(
        p: Problem,
        schedule: Array<IntArray>,
        list: List<C3>,
        key: String,
        forbidden: Boolean,
        inc: (String, Int) -> Unit,
        mark: (Int, Int, String) -> Unit,
    ) {
        for (c in list) {
            val seq = c.seq
            val d = seq.size
            if (d == 0 || d > p.T) continue
            // [統一: 最適化器 Evaluator の HF507 と一致] 非forbidden の単一シフト連は run-deficit で評価する。
            // 長さ r(<d) の run ごとに (d-r) を加算し、その run のセルを強調。素の窓マッチとは違反の「方向」が
            // 異なる（窓=未完成窓数 / run=不足ぶん）ため、最適化器と表示・提案が食い違わないよう統一する。
            if (!forbidden && C3Run.isSingleShiftSeq(seq)) {
                val first = seq[0]
                for (i in 0 until p.S) {
                    val row = schedule[i]
                    val t = row.size
                    var runStart = -1
                    var r = 0
                    var j = 0
                    while (j <= t) {
                        val on = j < t && row[j] == first
                        if (on) {
                            if (r == 0) runStart = j
                            r++
                        } else if (r > 0) {
                            val deficit = d - r
                            if (deficit > 0) {
                                inc(key, deficit)
                                for (jj in runStart until runStart + r) mark(i, jj, key)
                            }
                            r = 0; runStart = -1
                        }
                        j++
                    }
                }
                continue
            }
            for (i in 0 until p.S) {
                var j = 0
                while (j <= p.T - d) {
                    if (schedule[i][j] == seq[0]) {
                        var z = 0
                        for (l in 1 until d) if (schedule[i][j + l] == seq[l]) z++
                        val fire = if (forbidden) z == d - 1 else z < d - 1
                        if (fire) {
                            inc(key, 1)
                            for (l in 0 until d) mark(i, j + l, key)
                        }
                    }
                    j++
                }
            }
        }
    }


    private fun weightedScore(b: Map<String, Int>): Double {
        // [N2/⛏11] 重みは MirrorKeys.weights を単一の真実として参照。挿入順を保持しているため
        //   加算順は従来と同一＝Double 結果は不変。UI の重み表も同マップを描画する。
        var out = 0.0
        for ((key, weight) in MirrorKeys.weights) out += (b[key] ?: 0).toDouble() * weight
        return out
    }

}

fun List<List<Int>>.toIntArray2D(): Array<IntArray> {
    return Array(size) { i -> this[i].toIntArray() }
}
fun Array<IntArray>.copy2D(): Array<IntArray> {
    return Array(size) { i -> this[i].copyOf() }
}

fun Problem.canDo(staffI: Int, shiftK: Int): Boolean {
    if (staffI !in 0 until S || shiftK !in 0 until K) return false
    val g = sgrp[staffI]
    return bucket.getOrNull(g)?.contains(shiftK) == true
}

/** [監査#11①] セル(i,j)の希望を「不可侵（凍結）」として扱うか。
 *  実現可能な希望のみ凍結する。担当不可（bucket外）の不可能希望は凍結しない＝セルを
 *  被覆等の最適化へ復帰させ、入口fallback値のまま座礁するのを防ぐ。
 *  正当性: 不可能希望の pref 寄与は「w を割当て不能」ゆえ割当値に依存しない定数1。
 *  可動化しても pref は増減せず、他目的の最適化余地だけが広がる。
 *  pref の計数自体（不可能希望も違反として表示・カウント）は不変更（#11②は別裁定）。 */
fun Problem.wishLocked(i: Int, j: Int): Boolean {
    val w = wish[i][j]
    return w >= 0 && canDo(i, w)
}

fun Problem.allowedShiftsForStaff(staffI: Int): IntArray {
    // canDo と整合: 群bucketをそのまま返す（空＝担当可能シフトなし）。全呼び出し側は空配列を
    // ガード済み。旧実装は空bucketで全Kにフォールバックし canDo(=false) と矛盾していた（潜在バグ）。
    // 実データ（各群に担当シフト定義あり=非空）では挙動不変。
    return bucket.getOrNull(sgrp.getOrNull(staffI) ?: -1) ?: IntArray(0)
}

fun normalizeSchedule(schedule: Array<IntArray>, p: Problem): Array<IntArray> = Array(p.S) { i ->
    IntArray(p.T) { j ->
        val k = schedule.getOrNull(i)?.getOrNull(j) ?: 0
        if (k in 0 until p.K) k else -1
    }
}

fun countMatrix(p: Problem, schedule: Array<IntArray>): Array<IntArray> {
    val out = Array(p.S) { IntArray(p.K) }
    for (i in 0 until p.S) for (j in 0 until p.T) {
        val k = schedule[i][j]
        if (k in 0 until p.K) out[i][k]++
    }
    return out
}

/**
 * 同一 state 参照に対する Problem の単一エントリ・メモ化（性能）。
 * Problem の構築は全制約解決(cons3族/c41/c42/bucket/need…)で高コスト。最適化中は state 参照が
 * 一定なので、ここでキャッシュすると毎反復の再構築（1反復あたり破壊/修復＋hf67＋check で約3回）を排除できる。
 * Problem は実質イミュータブルで、これらの用途は schedule 非依存のため、5ワーカー間の共有読取も安全。
 * 参照比較(===)のみ。別 state が来れば作り直すので陳腐化しない。
 *
 * スレッド安全性: key と value を 1 つの不変 Entry にまとめ、@Volatile 参照を 1 回だけ読む。
 * 旧実装は key/value を別々の Volatile に持ち非アトミックに書いていたため、別スレッドが
 * 「新しい key だが古い value」を読み、要求 state と次元(S/T/K)の異なる Problem を返し得た
 * （fg の refreshCheck と bg 最適化が同一プロセスで重なると到達 → 誤スコア/AIOOBE）。
 */
private object ProblemCache {
    private class Entry(val key: MagiState, val value: Problem)
    @Volatile private var entry: Entry? = null
    fun get(state: MagiState): Problem {
        val e = entry
        if (e != null && e.key === state) return e.value
        val np = Problem(state)
        entry = Entry(state, np)   // 単一参照の公開はアトミック。race時の重複生成は等価で無害。
        return np
    }
}
fun cachedProblem(state: MagiState): Problem = ProblemCache.get(state)

fun coverage(p: Problem, schedule: Array<IntArray>): Array<IntArray> {
    val out = Array(p.T) { IntArray(p.K) }
    for (i in 0 until p.S) for (j in 0 until p.T) {
        val k = schedule[i][j]
        if (k in 0 until p.K) out[j][k]++
    }
    return out
}

fun lockedMatrix(p: Problem): Array<BooleanArray> {
    val out = Array(p.S) { BooleanArray(p.T) }
    for (i in 0 until p.S) {
        for (j in 0 until p.T) {
            out[i][j] = p.wish[i][j] in 0 until p.K
        }
    }
    return out
}

fun restShiftIndex(state: MagiState): Int = state.shifts.indexOfFirst { it.kigou == "休" }.takeIf { it >= 0 } ?: 0

fun formatDay(startDate: String, offset: Int): String {
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val d = fmt.parse(startDate) ?: return "${offset + 1}日"
        val cal = java.util.Calendar.getInstance(Locale.JAPAN)
        cal.time = d
        cal.add(java.util.Calendar.DATE, offset)
        val wd = "日月火水木金土"[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}($wd)"
    } catch (_: Exception) {
        "${offset + 1}日"
    }
}

fun MagiState.withSchedule(schedule: Array<IntArray>): MagiState {
    val rows = ArrayList<List<Int>>(schedule.size)
    for (row in schedule) rows.add(row.toList())
    return copy(schedule = rows)
}
