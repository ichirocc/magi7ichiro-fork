package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlexibleDayFlowTest {
    @Test
    fun flowAllowsChangingTheDailyShiftMultiset() {
        val x = FlexibleDayFlow.INF
        val staffCost = arrayOf(
            longArrayOf(x, x, 0), // victimはB1のみ
            longArrayOf(0, 0, x), // substituteは休/Aｱ
        )
        // AｱとB1の1人目を強く優遇。旧token方式では存在しないB1を生成できない。
        val marginal = arrayOf(
            longArrayOf(0, 0),
            longArrayOf(-8000, 1),
            longArrayOf(-8000, 1),
        )
        val r = FlexibleDayFlow.solve(staffCost, marginal)
        requireNotNull(r)
        assertEquals(listOf(2, 1), r.assignment.toList())
    }

    private fun fiveIllegalAaState(): MagiState {
        val shifts = listOf(
            Shift("休み", "休", "", ""),
            Shift("A", "Aｱ", "1", "1"),
            Shift("B1", "B1", "1", "1"),
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-05",
            shifts = shifts,
            groups = listOf(Group("victim", "V"), Group("general", "G")),
            staff = listOf(Staff("美幸相当", 0), Staff("代用者", 1)),
            use2Patterns = true,
            groupShift = listOf(
                listOf(1, 0, 1), // victim: 休/B1、Aｱ不可
                listOf(1, 1, 0), // substitute: 休/Aｱ
            ),
            groupShiftApt = List(2) { List(3) { "" } },
            schedule = listOf(
                List(5) { 1 }, // victimに担当不可Aｱが5回
                List(5) { 0 },
            ),
            wishes = emptyMap(),
            staffRange = mapOf(
                "0,0" to Range("0", "5"),
                "0,1" to Range("0", "0"),
                "0,2" to Range("0", "5"),
            ),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun rangePolishEliminatesFiveIllegalAaCellsInOnePass() {
        val st = fiveIllegalAaState()
        val before = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
        assertEquals(5, before.breakdown["groupViol"] ?: 0)

        val result = V6HotfixPasses.applyRangePolish(
            st, st.schedule.toIntArray2D(), maxPasses = 1, seed = 1L,
        )
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("担当不可Aｱを全排出", 0, after.breakdown["groupViol"] ?: -1)
        assertEquals("victimは全日B1", List(5) { 2 }, result.newSchedule[0].toList())
        assertEquals("代用者は全日Aｱ", List(5) { 1 }, result.newSchedule[1].toList())
        assertEquals("被覆不足なし", 0, after.breakdown["covU"] ?: -1)
        assertTrue(result.logs.first().message.contains("柔軟日割当:5"))
    }

    @Test
    fun fixedIllegalCellIsNotMoved() {
        val base = fiveIllegalAaState()
        val st = base.copy(wishes = mapOf("0,0" to 1))
        val result = V6HotfixPasses.applyRangePolish(
            st, st.schedule.toIntArray2D(), maxPasses = 1, seed = 1L,
        )
        assertEquals("希望固定Aｱは保持", 1, result.newSchedule[0][0])
        assertEquals("残るgroupViolは明示的固定由来", 1,
            UnifiedViolationChecker.check(st, result.newSchedule).breakdown["groupViol"] ?: 0)
    }
}
