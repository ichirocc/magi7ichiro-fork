package com.magi.app.v6

import com.magi.app.model.StateParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallPolishInjectionTest {

    @Test fun thresholdIsAtLeast20sOrTenthOfBudget() {
        assertEquals(20_000L, StallPolishInjection.stallThresholdMs(120))
        assertEquals(30_000L, StallPolishInjection.stallThresholdMs(300))
    }

    @Test fun triggersOnlyAfterStallWithRoomAndBudgetLeft() {
        val dl = 300_000L
        assertFalse(StallPolishInjection.shouldInject(129_999L, 100_000L, dl, 300, 0))
        assertTrue(StallPolishInjection.shouldInject(130_000L, 100_000L, dl, 300, 0))
        assertFalse(StallPolishInjection.shouldInject(130_000L, 100_000L, dl, 300, StallPolishInjection.MAX_INJECTIONS))
        assertFalse(StallPolishInjection.shouldInject(dl - 7_999L, 0L, dl, 300, 0))
    }

    @Test fun flagRoundTripsThroughSnapshot() {
        val saved = PolishGate.snapshot()
        try {
            PolishGate.stallPolishInjection = true
            val snap = PolishGate.snapshot()
            PolishGate.stallPolishInjection = false
            PolishGate.restore(snap)
            assertTrue(PolishGate.stallPolishInjection)
        } finally { PolishGate.restore(saved) }
    }

    @Test fun offFlagDoesNotStartInjector() = runBlocking {
        val st = StateParser.parse(javaClass.classLoader!!.getResource("golden_state.json")!!.readText())!!
        val saved = PolishGate.snapshot()
        try {
            for (on in listOf(false, true)) {
                PolishGate.stallPolishInjection = on
                val res = V6NativeOptimizer.optimize(
                    st, Array(st.schedule.size) { st.schedule[it].toIntArray() },
                    V6OptimizerOptions(algorithm = V6Algorithm.PORTFOLIO, totalBudgetSec = 3, workers = 2, seed = 7L),
                )
                assertEquals(on, res.phaseLogs.any { it.message.startsWith("停滞時研磨注入") })
            }
        } finally { PolishGate.restore(saved) }
    }
}
