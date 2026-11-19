package com.magi.app.v6

/**
 * シフトの間隔（族 gap、SOFT）。行の中で [x] が続く日をひとかたまりとし、かたまりの最後の日から次のかたまりの最初の日までの
 * 日数差が [n] 未満なら、足りない日数（n − 差）を数える。月の最初のかたまりは前月を持たないので数えない。
 * チェッカー・評価器・差分評価・C++ が同じ式を使う。
 */
object GapRule {
    fun deficit(row: IntArray, x: Int, n: Int): Int {
        var sum = 0
        var lastEnd = -1
        var j = 0
        val t = row.size
        while (j < t) {
            if (row[j] != x) { j++; continue }
            if (lastEnd >= 0) { val g = j - lastEnd; if (g < n) sum += n - g }
            while (j < t && row[j] == x) j++
            lastEnd = j - 1
        }
        return sum
    }

    /** 違反しているかたまりの最初の日（違反箇所の印）。 */
    fun violatingStarts(row: IntArray, x: Int, n: Int, out: (Int) -> Unit) {
        var lastEnd = -1
        var j = 0
        val t = row.size
        while (j < t) {
            if (row[j] != x) { j++; continue }
            if (lastEnd >= 0 && j - lastEnd < n) out(j)
            while (j < t && row[j] == x) j++
            lastEnd = j - 1
        }
    }
}
