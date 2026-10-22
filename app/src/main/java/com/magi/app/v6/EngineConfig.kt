package com.magi.app.v6

/**
 * 1 回の「つくる」で使うエンジン設定（`docs/overhaul_detailed_spec.md` §3.2）。実行の開始時に [snapshot] で 1 回だけ作り、
 * 以後は変えない＝実行中に [PolishGate] が書き換わってもその実行には効かない。項目は P2a〜P2c で移した（停滞・床・壁、後処理、玉突き）。
 */
data class EngineConfig(
    /** 停滞の閾値＝予算×この割合（[V6FinalPort.normalStallMs]）。0.9 は 3.447.0 で据え置き確定（HF77）。 */
    val normalStallFraction: Double = 0.9,
    // 停滞・床・壁（`docs/stall_escape.md`。意味は PolishGate の同名の KDoc）
    val c3nWallShortStall: Boolean = false,
    val floorShortStall: Boolean = true,
    val c3nWallLegacy: Boolean = false,
    val c3nWallDeepCheck: Boolean = false,
    val adaptiveStall: Boolean = false,
    // 後処理（PostOptimizationParams へ渡す。じっくりで ON になる 3 つを含む）
    val combineExhaustPairs: Boolean = false,
    val lnsAdaptive: Boolean = true,
    val aptFairSoftTolerance: Boolean = false,
    val countChainPolish: Boolean = false,
    val ejection: EjectionFlags = EjectionFlags(),
) {
    fun postParams(quantitativeRangeEval: Boolean) = V6HotfixPasses.PostOptimizationParams(
        quantitativeRangeEval = quantitativeRangeEval,
        combineExhaustPairs = combineExhaustPairs,
        lnsAdaptive = lnsAdaptive,
        aptFairSoftTolerance = aptFairSoftTolerance,
        countChainEnabled = countChainPolish,
        ejection = ejection,
    )

    companion object {
        /** 設定画面・背景 Worker の復元が書いた [PolishGate] を写す。エンジンの入口でだけ呼ぶ。 */
        fun snapshot(): EngineConfig = EngineConfig(
            normalStallFraction = PolishGate.normalStallFraction,
            c3nWallShortStall = PolishGate.c3nWallShortStall,
            floorShortStall = PolishGate.floorShortStall,
            c3nWallLegacy = PolishGate.c3nWallLegacy,
            c3nWallDeepCheck = PolishGate.c3nWallDeepCheck,
            adaptiveStall = PolishGate.adaptiveStall,
            combineExhaustPairs = PolishGate.combineExhaustPairs,
            lnsAdaptive = PolishGate.lnsAdaptive,
            aptFairSoftTolerance = PolishGate.aptFairSoftTolerance,
            countChainPolish = PolishGate.countChainPolish,
            ejection = EjectionFlags(
                c1Chain = PolishGate.c1EjectionChain,
                allFamilyChain = PolishGate.allFamilyEjectionChain,
                pipelineFocus = PolishGate.ejectionPipelineFocus,
                pipelineRounds = PolishGate.ejectionPipelineRounds,
                pipelineHardLeg = PolishGate.ejectionPipelineHardLeg,
                swapMoves = PolishGate.ejectionChainSwapMoves,
                maxMillis = PolishGate.ejectionChainMaxMillis,
                useLeftover = PolishGate.ejectionUseLeftover,
                holeFocus = C1EjectionChainPolish.defaultHoleFocus,
                holeSoftOnly = C1EjectionChainPolish.defaultHoleSoftOnly,
            ),
        )
    }
}

/** 玉突きの設定（設定タブの「玉突きで直す」4 択・入れ替え・採れたら探し直す・必須から・余った時間・秒数・範囲）。既定は本番の既定。 */
data class EjectionFlags(
    val c1Chain: Boolean = false,
    val allFamilyChain: Boolean = false,
    val pipelineFocus: EjectionChainPipeline.Focus = EjectionChainPipeline.Focus.BOTH,
    val pipelineRounds: Boolean = true,
    val pipelineHardLeg: Boolean = false,
    val swapMoves: Boolean = true,
    val maxMillis: Long = CHAIN_RESERVE_SEC * 1000L,
    val useLeftover: Boolean = false,
    val holeFocus: Boolean = true,
    val holeSoftOnly: Boolean = true,
)
