package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Test

/** T-6（`docs/overhaul_detailed_spec.md`）。EngineConfig の既定が本番の既定（PolishGate の初期値）と同じで、写しが後の変更を受けない。 */
class EngineConfigTest {
    @Test fun defaultsMatchTheProductionDefaults() {
        val d = EngineConfig()
        assertEquals(0.9, d.normalStallFraction, 0.0)
        assertEquals(true, d.floorCountsUpperZero)
        assertEquals(false, d.c3nWallShortStall)
        assertEquals(true, d.floorShortStall)
        assertEquals(false, d.c3nWallLegacy)
        assertEquals(false, d.c3nWallDeepCheck)
        assertEquals(false, d.adaptiveStall)
        assertEquals(false, d.combineExhaustPairs)
        assertEquals(true, d.lnsAdaptive)
        assertEquals(false, d.aptFairSoftTolerance)
        assertEquals(false, d.countChainPolish)
        assertEquals(EjectionFlags(c1Chain = false, allFamilyChain = false, pipelineFocus = EjectionChainPipeline.Focus.BOTH,
            pipelineAfterRepair = true, pipelineRounds = true, pipelineHardLeg = false, swapMoves = true, maxMillis = 10_000L,
            useLeftover = false, holeFocus = true, holeSoftOnly = true), d.ejection)
        assertEquals(d, EngineConfig.snapshot())
    }

    @Test fun postParamsCarryTheSnapshottedPostFlags() {
        val p = EngineConfig(combineExhaustPairs = true, lnsAdaptive = false, aptFairSoftTolerance = true, countChainPolish = true)
            .postParams(quantitativeRangeEval = false)
        assertEquals(true, p.combineExhaustPairs)
        assertEquals(false, p.lnsAdaptive)
        assertEquals(true, p.aptFairSoftTolerance)
        assertEquals(true, p.countChainEnabled)
        assertEquals(V6HotfixPasses.PostOptimizationParams(lnsAdaptive = true), EngineConfig().postParams(false))
    }

    @Test fun snapshotIsFixedAtTheStartOfTheRun() {
        val saved = PolishGate.floorCountsUpperZero
        try {
            PolishGate.floorCountsUpperZero = false
            val c = EngineConfig.snapshot()
            PolishGate.floorCountsUpperZero = true
            assertEquals(false, c.floorCountsUpperZero)
        } finally {
            PolishGate.floorCountsUpperZero = saved
        }
    }
}
