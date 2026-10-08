package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [統合段の評価モード, 3.642.0] 統合（EliteIntegrationPolish.apply）が返す報告は、呼出元の量的評価モードで採点される。
 *  検査するのは最終報告と根の盤面だけで、内部の中間評価まで網羅はしない。 */
class EliteIntegrationQuantitativeTest {

    private fun buildState(schedule: List<List<Int>>): MagiState {
        val shifts = listOf(
            Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "", ""), Shift("B", "B", "", ""),
        )
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("s0", 0), Staff("s1", 0), Staff("s2", 0), Staff("s3", 0))
        val groupShift = listOf(listOf(1, 1, 1))
        return MagiState(
            startDate = "2025-01-01", endDate = "2025-01-0${schedule[0].size}",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = listOf(listOf("", "", "")), schedule = schedule,
            wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = listOf(C2Row("A", "3")), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = listOf(C41Row("G0", "B", "2", "2")), cons42 = emptyList(),
        )
    }

    @Test
    fun returnedReportFollowsTheRequestedEvaluationMode() {
        val days = 5
        val state = buildState(List(4) { List(days) { 0 } })
        val root = Array(4) { IntArray(days) }
        val res = EliteIntegrationPolish.apply(state, root, emptyList(), { false }, EngineClock.nowMs() + 60_000L, quantitativeRangeEval = true)
        assertEquals(12, res.report.breakdown["c2"])
        val quant = UnifiedViolationChecker.check(state, res.schedule, quantitativeRangeEval = true)
        assertEquals(quant.weightedScore, res.report.weightedScore, 0.0)
        assertTrue("この盤面は評価モードで差が出る（二値は 4）",
            UnifiedViolationChecker.check(state, res.schedule).breakdown["c2"] != res.report.breakdown["c2"])
    }
}
