package com.magi.app.v6

/**
 * 期間の制約（c1）の件数。規則×職員ごとに、窓を 1 日ずつ滑らせて不足量（day2 − 回数）を見て、不足している窓が続くまとまりの
 * 「不足量の和」を 2 乗して足す（長く続く違反ほど重く。不足 1 の窓が 1 つ＝1 件、3 つ続く＝9 件）。
 * チェッカー・評価器・差分評価・C++・CP-SAT が同じ式を使う。
 */
object C1Cost {
    /** 不足量を窓の順に [add] し、最後に [end] で件数を受け取る。 */
    class Acc {
        private var run = 0L
        private var tot = 0L
        fun add(deficit: Int) { if (deficit > 0) run += deficit else { tot += run * run; run = 0 } }
        fun end(): Long { tot += run * run; run = 0; return tot }
    }

    fun rowCost(row: IntArray, x: Int, day1: Int, day2: Int): Long {
        val t = row.size
        if (day1 <= 0 || day1 > t) return 0L
        val acc = Acc()
        var z = 0
        for (l in 0 until day1) if (row[l] == x) z++
        var j = 0
        while (j <= t - day1) {
            if (j > 0) { if (row[j - 1] == x) z--; if (row[j + day1 - 1] == x) z++ }
            acc.add(day2 - z)
            j++
        }
        return acc.end()
    }
}
