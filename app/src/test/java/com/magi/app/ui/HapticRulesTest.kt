package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticRulesTest {
    @Test
    fun onlyFailedMessagesVibrate() {
        assertEquals(MagiHaptic.REJECT, hapticForMessage(isError = true))
        assertNull(hapticForMessage(isError = false))
    }

    @Test
    fun runEndVibratesOnceOnTheFallingEdge() {
        assertEquals(MagiHaptic.CONFIRM, hapticForRunEnd(wasRunning = true, running = false, failed = false))
        assertEquals(MagiHaptic.REJECT, hapticForRunEnd(wasRunning = true, running = false, failed = true))
        assertNull(hapticForRunEnd(wasRunning = false, running = false, failed = false))
        assertNull(hapticForRunEnd(wasRunning = false, running = true, failed = false))
        assertNull(hapticForRunEnd(wasRunning = true, running = true, failed = false))
    }
}
