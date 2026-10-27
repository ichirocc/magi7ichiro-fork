package com.magi.app.v6

/**
 * 1 回の「つくる」で使うエンジン設定（`docs/overhaul_detailed_spec.md` §3.2）。実行の開始時に [snapshot] で 1 回だけ作り、
 * 以後は変えない＝実行中に [PolishGate] が書き換わってもその実行には効かない。項目は P2a〜P2c で移した（停滞・床・壁、後処理、玉突き）。
 */
data class EngineConfig(
    /** 停滞の閾値＝予算×この割合（[V6FinalPort.normalStallMs]）。0.9 は 3.447.0 で据え置き確定（HF77）。 */
    val normalStallFraction: Double = 0.9,
    // 停滞・床・壁（`docs/stall_escape.md`。意味は PolishGate の同名の KDoc）
    val floorShortStall: Boolean = true,
    val c3nWallDeepCheck: Boolean = false,
    // 後処理（PostOptimizationParams へ渡す。じっくりで ON になる 3 つを含む）
    val combineExhaustPairs: Boolean = false,
    val aptFairSoftTolerance: Boolean = false,
    val countChainPolish: Boolean = false,
    /** 禁止連続の崩し範囲を広げる（探索の強さ「じっくり」。探索の奥 `breakableDaysFor` まで引数で渡す）。 */
    val wideC3nBreakDays: Boolean = false,
    val ejection: EjectionFlags = EjectionFlags(),
) {
    fun postParams() = V6HotfixPasses.PostOptimizationParams(
        combineExhaustPairs = combineExhaustPairs,
        aptFairSoftTolerance = aptFairSoftTolerance,
        countChainEnabled = countChainPolish,
        wideC3nBreakDays = wideC3nBreakDays,
        ejection = ejection,
    )

    companion object {
        /** 実行の外（分析・変更案の探索）が読む、いまの「禁止連続の崩し範囲」。実行の中は [wideC3nBreakDays]（写し）を渡す。 */
        fun currentWideC3nBreakDays(): Boolean = PolishGate.wideC3nBreakDays

        /** 設定画面・背景 Worker の復元が書いた [PolishGate] を写す。エンジンの入口でだけ呼ぶ。 */
        fun snapshot(): EngineConfig = EngineConfig(
            normalStallFraction = PolishGate.normalStallFraction,
            floorShortStall = PolishGate.floorShortStall,
            c3nWallDeepCheck = PolishGate.c3nWallDeepCheck,
            combineExhaustPairs = PolishGate.combineExhaustPairs,
            aptFairSoftTolerance = PolishGate.aptFairSoftTolerance,
            countChainPolish = PolishGate.countChainPolish,
            wideC3nBreakDays = PolishGate.wideC3nBreakDays,
            ejection = EjectionFlags(
                c1Chain = PolishGate.c1EjectionChain,
                allFamilyChain = PolishGate.allFamilyEjectionChain,
                pipelineFocus = PolishGate.ejectionPipelineFocus,
                pipelineHardLeg = PolishGate.ejectionPipelineHardLeg,
                maxMillis = PolishGate.ejectionChainMaxMillis,
                holeFocus = C1EjectionChainPolish.defaultHoleFocus,
                holeSoftOnly = C1EjectionChainPolish.defaultHoleSoftOnly,
            ),
        )
    }
}

/** 玉突きの設定（設定タブの「玉突きで直す」4 択・必須から・秒数・範囲）。既定は本番の既定。入れ替えと探し直しは P2d で常時。 */
data class EjectionFlags(
    val c1Chain: Boolean = false,
    val allFamilyChain: Boolean = false,
    val pipelineFocus: EjectionChainPipeline.Focus = EjectionChainPipeline.Focus.BOTH,
    val pipelineHardLeg: Boolean = false,
    val maxMillis: Long = CHAIN_RESERVE_SEC * 1000L,
    val holeFocus: Boolean = true,
    val holeSoftOnly: Boolean = true,
)
