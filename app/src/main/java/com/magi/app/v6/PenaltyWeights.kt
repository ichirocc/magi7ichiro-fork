package com.magi.app.v6

/**
 * 修復の候補選び・研磨の見積りが使う族の重み（`MirrorKeys.weights` の写し）。初期化のとき 1 回だけ読む＝ホットパスで
 * `weightOf` の when 分岐を払わず、重みを変えても写しの数値を手で直す必要がない。評価器（`Evaluator`/`DeltaEvaluator`）と C++ は
 * 別に保つ（それぞれ ObjectiveParityTest と native-parity が一致を守る）。
 */
internal object PenaltyWeights {
    @JvmField val LOW: Long = w("low")
    @JvmField val HIGH: Long = w("high")
    @JvmField val APT: Long = w("apt")
    @JvmField val FAIR: Long = w("fair")
    @JvmField val WEEKLY: Long = w("weekly")
    @JvmField val COVU: Long = w("covU")
    @JvmField val COVO: Long = w("covO")

    private fun w(family: String): Long {
        val v = MirrorKeys.weights.getValue(family)
        check(v == Math.floor(v)) { "$family の重み $v は整数でない（見積りは Long で掛ける）" }
        return v.toLong()
    }
}
