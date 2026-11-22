package com.magi.app.v6

import com.magi.app.model.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** シフトの間隔（gap）の研磨: 1 マスの長方形交換では禁止の並びになって直せない盤面を、区間の入れ替えで直す。 */
class GapPolishTest {
    @After fun restore() { GapPolish.segmentSwap = true }

    // 0 休・1 D・2 A。D の翌日に A は禁止、D は 4 日以上空ける。
    private val st = MagiState(
        startDate = "2026-12-01", endDate = "2026-12-10",
        shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("D", "D", "", ""), Shift("A", "A", "", "")),
        groups = listOf(Group("G0", "G0")), staff = (0 until 2).map { Staff("s$it", 0, 0) },
        use2Patterns = false, groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(
            listOf(1, 0, 1, 0, 2, 2, 2, 2, 2, 2),   // D 休 D 休 A … ＝間隔 2（足りない 2 日）
            listOf(2, 2, 2, 2, 2, 1, 0, 2, 2, 2),
        ),
        wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = listOf(C3Row(listOf("D", "A"))),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        consGap = listOf(GapRow("D", "4")),
    )
    private fun board() = st.schedule.map { it.toIntArray() }.toTypedArray()

    @Test fun rectangleAloneCannotFixWithoutForbiddenRun() {
        GapPolish.segmentSwap = false
        val r = GapPolish.apply(st, board())
        assertEquals(2, r.report!!.breakdown["gap"] ?: 0)
        assertEquals(0, r.applied)
    }

    @Test fun segmentSwapMovesTheBlockWithItsFollowingDay() {
        val before = UnifiedViolationChecker.check(st, board())
        val r = GapPolish.apply(st, board())
        val after = UnifiedViolationChecker.check(st, r.newSchedule)
        assertEquals(0, after.breakdown["gap"] ?: 0)
        assertEquals(0, after.hard)
        assertTrue(betterReport(after, before))
        // 各日の人数は変わらない
        for (j in 0 until 10) for (k in 0 until 3) {
            assertEquals(board().count { it[j] == k }, r.newSchedule.count { it[j] == k })
        }
    }
}
