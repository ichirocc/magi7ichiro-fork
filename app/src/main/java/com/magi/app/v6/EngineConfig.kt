package com.magi.app.v6

/**
 * 1 回の「つくる」で使うエンジン設定（`docs/overhaul_detailed_spec.md` §3.2）。実行の開始時に [snapshot] で 1 回だけ作り、
 * 以後は変えない＝実行中に [PolishGate] が書き換わってもその実行には効かない。項目は P2a〜P2c で段階的に移す。
 */
data class EngineConfig(
    /** 停滞の閾値＝予算×この割合（[V6FinalPort.normalStallMs]）。0.9 は 3.447.0 で据え置き確定（HF77）。 */
    val normalStallFraction: Double = 0.9,
    /** 早期終了の希望衝突の床を上限 0 込みにする（3.709.0 で既定 ON）。 */
    val floorCountsUpperZero: Boolean = true,
) {
    companion object {
        /** 設定画面・背景 Worker の復元が書いた [PolishGate] を写す。エンジンの入口でだけ呼ぶ。 */
        fun snapshot(): EngineConfig = EngineConfig(
            normalStallFraction = PolishGate.normalStallFraction,
            floorCountsUpperZero = PolishGate.floorCountsUpperZero,
        )
    }
}
