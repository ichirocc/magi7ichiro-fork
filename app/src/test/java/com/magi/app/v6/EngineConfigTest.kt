package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Test

/** T-6（`docs/overhaul_detailed_spec.md`）。EngineConfig の既定が本番の既定（PolishGate の初期値）と同じで、写しが後の変更を受けない。 */
class EngineConfigTest {
    @Test fun defaultsMatchTheProductionDefaults() {
        val d = EngineConfig()
        assertEquals(0.9, d.normalStallFraction, 0.0)
        assertEquals(true, d.floorCountsUpperZero)
        assertEquals(d, EngineConfig.snapshot())
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
