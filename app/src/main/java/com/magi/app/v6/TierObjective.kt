package com.magi.app.v6

/**
 * [#50 P1、docs/weightless_objective.md §4] 重みを使わない目的関数の比べ方を、今の採否と並べて出す（ログだけ。採否には使わない＝出力不変）。
 *
 * - 段の鍵（§4.1・§4.2）: 許容ラインは既定 0 なので、段 T の超過量 E_T は族の量の合計、残り R_T は 0。族の並びの辞書式は [TierKey.lex]。
 * - 職員ごとの不満（§4.4b）: T1 の族（下限割れ・期間の制約・上限超過・回避の並び）の職員ごとの量の合計 p_i。大きい順に並べて比べる。
 * - fair の leximin（§4.4）: グループ×担当シフトごとの職員の偏差（[fairDevOfBucket] の内訳）を全部つないで大きい順に並べる。
 *
 * 職員ごとの量はチェッカー（[UnifiedViolationChecker]）と同じ数え方を職員の行ごとに書き直したもの。合計が報告の
 * `breakdown` と一致することを `TierObjectiveTest` が 6 fixture で固定する（数え方がずれたら落ちる）。
 */
object TierObjective {
    /** 段と族（§4.1。c1 は T1＝2026-11-04 の決定）。 */
    val tiers: List<List<String>> = listOf(
        listOf("groupViol", "covU", "c3n", "c3w", "pref", "extWish"),
        listOf("low", "c1", "high", "c3mn"),
        listOf("c3", "c3m", "c41", "c42", "c41s", "c42s"),
        listOf("apt", "c2", "covO", "weekly", "fair"),
    )

    /** [excess]＝段ごとの E_T、[lex]＝段ごとの族の量（段の中の並び順）、[dissatisfaction]・[fairLeximin]＝大きい順（0 は除く）。 */
    class TierKey(val excess: List<Int>, val lex: List<List<Int>>, val dissatisfaction: List<Int>, val fairLeximin: List<Int>) {
        val maxDissatisfaction: Int get() = dissatisfaction.firstOrNull() ?: 0
        val sumDissatisfaction: Int get() = dissatisfaction.sum()
    }

    fun key(p: Problem, schedule: Array<IntArray>, report: ViolationReport): TierKey {
        val excess = tiers.map { t -> t.sumOf { report.breakdown[it] ?: 0 } }
        val lex = tiers.map { t -> t.map { report.breakdown[it] ?: 0 } }
        val perStaff = t1PerStaff(p, schedule)
        val dis = IntArray(p.S) { i -> perStaff.values.sumOf { it[i] } }.filter { it > 0 }.sortedDescending()
        return TierKey(excess, lex, dis, fairDeviations(p, schedule).sortedDescending())
    }

    /** T1 の族ごとの職員ごとの量（族→長さ S の配列）。チェッカーの low/high（amount）・c1（窓の数）・c3mn（窓の数）と同じ数え方。 */
    fun t1PerStaff(p: Problem, schedule: Array<IntArray>): Map<String, IntArray> {
        val low = IntArray(p.S); val high = IntArray(p.S); val c1 = IntArray(p.S); val c3mn = IntArray(p.S)
        val counts = countMatrix(p, schedule)
        for (i in 0 until p.S) for (k in 0 until p.K) {
            val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]; val n = counts[i][k]
            if (lo != Int.MIN_VALUE && lo != 0 && p.canDo(i, k) && n < lo) low[i] += lo - n
            if (hi != Int.MAX_VALUE && n > hi) high[i] += n - hi
        }
        for (c in p.cons1) {
            if (c.day1 <= 0 || c.day1 > p.T) continue
            for (i in 0 until p.S) {
                if (!p.canDo(i, c.shiftIdx)) continue
                val row = schedule[i]
                for (w in 0..p.T - c.day1) {
                    var z = 0
                    for (l in 0 until c.day1) if (row[w + l] == c.shiftIdx) z++
                    if (z < c.day2) c1[i] += c.day2 - z
                }
            }
        }
        for (c in p.cons3mn) {
            val seq = c.seq; val d = seq.size
            if (d == 0 || d > p.T) continue
            for (i in 0 until p.S) for (j in 0..p.T - d) {
                if ((0 until d).all { l -> schedule[i][j + l] == seq[l] }) c3mn[i]++
            }
        }
        return linkedMapOf("low" to low, "c1" to c1, "high" to high, "c3mn" to c3mn)
    }

    /** fair の職員ごとの偏差（チェッカーと同じグループ×担当シフトの範囲、偏差 > 0 のものだけ）。合計にグループ×シフトごとの最大を足すと `breakdown["fair"]`（3.727.77）。 */
    fun fairDeviations(p: Problem, schedule: Array<IntArray>): List<Int> {
        val counts = countMatrix(p, schedule)
        val out = ArrayList<Int>()
        for (g in 0 until p.G) {
            if (p.groupMembers[g].size < 2) continue
            for (k in p.bucket[g]) for ((_, dev) in p.fairDevOfBucket(g, k) { x -> counts[x][k] }.perMember) out.add(dev)
        }
        return out
    }

    /** ログの 1 行（段の超過量・職員ごとの不満の最大と合計・fair の leximin の上位）。 */
    fun logLine(k: TierKey): String =
        "段の鍵（重みなし・ライン0、採否には使わない）: " + k.excess.mapIndexed { t, e -> "T$t=$e" }.joinToString(" ") +
            " ／ 職員の不満 最大${k.maxDissatisfaction}・合計${k.sumDissatisfaction}（${k.dissatisfaction.size}人）" +
            " ／ fair の上位 " + (if (k.fairLeximin.isEmpty()) "なし" else k.fairLeximin.take(5).joinToString(","))
}
