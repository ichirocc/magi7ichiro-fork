package com.magi.app.v6

/**
 * 修復の候補選び・研磨の見積りが使う族の重み（`MirrorKeys.weights` の写し）。初期化のとき 1 回だけ読む＝ホットパスで
 * `weightOf` の when 分岐を払わず、重みを変えても写しの数値を手で直す必要がない。評価器（`Evaluator`/`DeltaEvaluator`）と C++ は
 * 別に保つ（それぞれ ObjectiveParityTest と native-parity が一致を守る）。
 */
internal object PenaltyWeights {
    // [#50] 方式（[ObjectiveWeights]）で切り替わるので、毎回いまの表から読む（表は方式ごとに 1 つだけ作ってある）。
    val LOW: Long get() = ObjectiveWeights.soft().low
    val HIGH: Long get() = ObjectiveWeights.soft().high
    val APT: Long get() = ObjectiveWeights.soft().apt
    val FAIR: Long get() = ObjectiveWeights.soft().fair
    val WEEKLY: Long get() = ObjectiveWeights.soft().weekly
    val COVU: Long get() = ObjectiveWeights.soft().covU
    val COVO: Long get() = ObjectiveWeights.soft().covO
}
