package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardCausesTest {
    @Test
    fun decodeReadsCoresInJniLayout() {
        val flat = intArrayOf(
            2,
            1, 2, 0, 2, 0, 2, -1, 0, 2, 1, 0, -1,
            0, 1, 2, -1, 28, 5, 1,
        )
        val cores = HardCauses.decode(flat)
        assertEquals(2, cores.size)
        assertTrue(cores[0].minimal)
        assertEquals(
            listOf(
                HardCauses.Cause(HardCauses.Kind.WISH, 2, 0, 2, -1),
                HardCauses.Cause(HardCauses.Kind.WISH, 2, 1, 0, -1),
            ),
            cores[0].causes,
        )
        assertFalse(cores[1].minimal)
        assertEquals(HardCauses.Cause(HardCauses.Kind.NEED, -1, 28, 5, 1), cores[1].causes.single())
    }

    @Test
    fun decodeEmpty() {
        assertTrue(HardCauses.decode(intArrayOf(0)).isEmpty())
        assertTrue(HardCauses.decode(IntArray(0)).isEmpty())
    }
}
