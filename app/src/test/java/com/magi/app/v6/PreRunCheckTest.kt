package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [つくる前の確認] 分類と指紋（`PreRunCheck`）。 */
class PreRunCheckTest {
    private val st = StateParser.parse(javaClass.getResource("/oct2026_grid_state.json")!!.readText())!!
    private val board = st.schedule.toIntArray2D()

    @Test fun realData_counts() {
        val s = PreRunCheck.build(st, board)
        assertEquals(listOf("c3n", "c3w", "c3n", "c3n"), s.wishConflicts.map { it.family })
        assertEquals(0, s.impossibleWishes.size)
        assertEquals(0, s.forcedShortfalls.size)
        assertEquals(listOf(8, 9, 10, 28), s.dayProofs.map { it.day })
        assertEquals(listOf(3), s.staffProofs.map { it.staff })
        assertEquals(listOf(Triple(7, 1, 0), Triple(10, 12, 0)), s.wishOverCaps.map { Triple(it.staff, it.wished, it.hi) })
        assertEquals(11, s.floorCount)
        assertEquals(4, s.rerunClears.size)
        assertEquals(PreRunCheck.WallHint(22, 8, 7, 7), s.wallHint)
        assertTrue(s.needsSheet)
    }

    @Test fun rerunClearsMatchRelaxTrialHandPlaced() {
        val pairs = PreRunCheck.build(st, board).rerunClears.map { it.staff to it.shift }.toSet()
        assertEquals(RelaxTrial.handPlaced(st, board).map { it.staff to it.shift }.toSet(), pairs)
    }

    @Test fun emptyWhenNothingToSay() {
        val s = PreRunCheck.Summary(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        assertFalse(s.needsSheet)
        assertNull(PreRunCheck.wallHint(emptyList()))
    }

    @Test fun wallHint_tieGoesToLowerIndex() {
        assertEquals(PreRunCheck.WallHint(4, 2, 1, 2), PreRunCheck.wallHint(listOf(3 to 1, 1 to 1, 3 to 2, 1 to 2)))
    }

    @Test fun fingerprint_changesWithBoardAndWishes() {
        val f0 = PreRunCheck.fingerprint(st, board)
        assertEquals(f0, PreRunCheck.fingerprint(st, board.copy2D()))
        val b2 = board.copy2D(); b2[0][0] = (b2[0][0] + 1) % st.shifts.size
        assertNotEquals(f0, PreRunCheck.fingerprint(st, b2))
        val w = st.wishes.toMutableMap(); w.remove(w.keys.first())
        assertNotEquals(f0, PreRunCheck.fingerprint(st.copy(wishes = w), board))
    }
}
