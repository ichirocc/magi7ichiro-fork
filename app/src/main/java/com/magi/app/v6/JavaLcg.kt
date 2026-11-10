package com.magi.app.v6

/**
 * `java.util.Random` と同じ乱数列（48 ビット線形合同法・`nextInt(bound)`・`nextDouble`）で、内部状態 [state] を読み書きできるもの。
 * 膜の細胞 LNS が C++（`JavaLcgN`）と乱数の続きを受け渡すために使う。同じ種なら `java.util.Random(seed)` と同じ列（`JavaLcgTest`）。
 */
internal class JavaLcg(seed: Long) {
    var state: Long = (seed xor MUL) and MASK

    private fun next(bits: Int): Int {
        state = (state * MUL + ADD) and MASK
        return (state ushr (48 - bits)).toInt()
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        var r = next(31)
        val m = bound - 1
        if (bound and m == 0) return ((bound.toLong() * r.toLong()) shr 31).toInt()
        var u = r
        while (true) {
            r = u % bound
            if (u - r + m >= 0) return r
            u = next(31)
        }
    }

    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * DOUBLE_UNIT

    companion object {
        private const val MUL = 0x5DEECE66DL
        private const val ADD = 0xBL
        private const val MASK = (1L shl 48) - 1
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}
