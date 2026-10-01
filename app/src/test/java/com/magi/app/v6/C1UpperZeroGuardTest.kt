package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** [3.507.0] C1 研磨の直接移動も上限 0（mayPlace=false）の (職員,シフト) へ置かない（机上テストで発見）。 */
class C1UpperZeroGuardTest {
    private val rest = listOf(0, 0, 0)

    private fun state(): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-03",
        shifts = listOf(Shift("休み", "休", "", "", ShiftRole.Rest), Shift("日勤", "D", "", "")),
        groups = listOf(Group("G0", "G0")),
        staff = listOf(Staff("s0", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(rest),
        wishes = emptyMap(),
        staffRange = mapOf("0,1" to Range("", "0")),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = listOf(C1Row("3", "D", "1")), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun assertUntouched(r: V6HotfixPasses.CyclicSwapResult) {
        assertEquals(rest, r.newSchedule[0].toList())
        assertEquals(0, r.applied)
    }

    @Test fun fixtureHasC1DeficitAndUpperZero() {
        val st = state()
        val p = Problem(st)
        assertFalse(p.mayPlace(0, 1))
        assertEquals(1, UnifiedViolationChecker.check(st, st.schedule.toIntArray2D()).breakdown["c1"] ?: 0)
    }

    @Test fun indexChainRepairDoesNotPlaceOnUpperZero() {
        val st = state()
        assertUntouched(C1RepairOperators.indexChainRepair(st, st.schedule.toIntArray2D()))
    }

    @Test fun windowPolishDoesNotPlaceOnUpperZero() {
        val st = state()
        assertUntouched(C1RepairOperators.selfRelocateAndSameDaySwap(st, st.schedule.toIntArray2D()))
    }

    @Test fun beamPolishDoesNotPlaceOnUpperZero() {
        val st = state()
        assertEquals(rest, C1RepairOperators.wideBeam(st, st.schedule.toIntArray2D()).newSchedule[0].toList())
    }
}
