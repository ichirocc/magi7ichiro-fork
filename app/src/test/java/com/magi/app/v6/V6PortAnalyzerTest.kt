package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6PortAnalyzerTest {
    @Test
    fun v6OverviewComputesAptAndRisk() {
        val st = MagiState(
            startDate = "2025-12-01",
            endDate = "2025-12-03",
            shifts = listOf(Shift("休み", "休", "", ""), Shift("早番", "A", "1", "1")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0)),
            use2Patterns = true,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "2")),
            schedule = listOf(listOf(1, 1, 1), listOf(0, 0, 0)),
            wishes = emptyMap(),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val report = UnifiedViolationChecker.check(st)
        val v6 = V6PortAnalyzer.analyze(st, st.schedule.toIntArray2D(), report)
        assertEquals(3, v6.demand)
        assertEquals(100, v6.coveragePct)
        assertTrue(v6.aptPenalty > 0.0)
        assertTrue(v6.sanityNotes.any { it.contains("groupShiftApt") })
    }

    // [実バグ修正の回帰] diagnoseCoverage が need1 のみを見て miss=need1-got を計算していたため、
    // need1 未設定・need2 単独定義（Problem.covUCell の「片方定義=その値」対応セル）の covU 違反が
    // 診断から丸ごと消えていた。need1="" / need2="2" で1人しか配置しない盤面を使い、
    // covUCell（source of truth）どおり不足1として検出されることを固定する。
    @Test
    fun diagnoseCoverageCatchesNeed2OnlyShortfall() {
        val st = MagiState(
            startDate = "2025-12-01",
            endDate = "2025-12-01",
            shifts = listOf(Shift("休み", "休", "", ""), Shift("早番", "A", "", "2")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0)),
            use2Patterns = true,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1), listOf(0)),
            wishes = emptyMap(),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val diag = V6PortAnalyzer.diagnoseCoverage(st)
        assertEquals(1, diag.totalShortfall)
        assertEquals(1, diag.shortfalls.size)
        val sf = diag.shortfalls.single()
        assertEquals(1, sf.shiftIndex)
        assertEquals(1, sf.got)
        assertEquals(1, sf.miss)
    }
}
