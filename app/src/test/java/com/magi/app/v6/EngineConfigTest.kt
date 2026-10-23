package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Test

/** T-6（`docs/overhaul_detailed_spec.md`）。EngineConfig の既定が本番の既定（PolishGate の初期値）と同じで、写しが後の変更を受けない。 */
class EngineConfigTest {
    @Test fun defaultsMatchTheProductionDefaults() {
        val d = EngineConfig()
        assertEquals(0.9, d.normalStallFraction, 0.0)
        assertEquals(true, d.floorShortStall)
        assertEquals(false, d.c3nWallDeepCheck)
        assertEquals(false, d.combineExhaustPairs)
        assertEquals(false, d.aptFairSoftTolerance)
        assertEquals(false, d.countChainPolish)
        assertEquals(EjectionFlags(c1Chain = false, allFamilyChain = false, pipelineFocus = EjectionChainPipeline.Focus.BOTH,
            pipelineHardLeg = false, maxMillis = 10_000L, holeFocus = true, holeSoftOnly = true), d.ejection)
        assertEquals(d, EngineConfig.snapshot())
    }

    @Test fun postParamsCarryTheSnapshottedPostFlags() {
        val p = EngineConfig(combineExhaustPairs = true, aptFairSoftTolerance = true, countChainPolish = true)
            .postParams()
        assertEquals(true, p.combineExhaustPairs)
        assertEquals(true, p.aptFairSoftTolerance)
        assertEquals(true, p.countChainEnabled)
        assertEquals(V6HotfixPasses.PostOptimizationParams(), EngineConfig().postParams())
    }

    @Test fun snapshotIsFixedAtTheStartOfTheRun() {
        val saved = PolishGate.c3nWallDeepCheck
        try {
            PolishGate.c3nWallDeepCheck = true
            val c = EngineConfig.snapshot()
            PolishGate.c3nWallDeepCheck = false
            assertEquals(true, c.c3nWallDeepCheck)
        } finally {
            PolishGate.c3nWallDeepCheck = saved
        }
    }
}
