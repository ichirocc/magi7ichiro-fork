package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** シーソーの 3 人 × 2 日の巡回（`docs/seesaw.md` §6）: 2 つの同日の入れ替えのどちらも単独では良くならない盤面を、1 つの手で直す。 */
class CycleSwapPolishTest {
    // 0 休・1 D。s0 は D の上限 0 なのに 1 回、s2 は D の下限 1 なのに 0 回、s1 は D がちょうど 1 回（下限＝上限＝1）。
    // s2 の 1 日目は希望の休＝s0 と s2 の 1 日目の入れ替えはできない。s0↔s1（1 日目）だけ・s1↔s2（2 日目）だけでは s1 が上限超過・下限割れになる。
    private val st = MagiState(
        startDate = "2026-12-01", endDate = "2026-12-02",
        shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("D", "D", "", "")),
        groups = listOf(Group("G0", "G0")), staff = (0 until 3).map { Staff("s$it", 0, 0) },
        use2Patterns = false, groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(listOf(1, 0), listOf(0, 1), listOf(0, 0)),
        wishes = mapOf("2,0" to 0),
        staffRange = mapOf("0,1" to Range("", "0"), "1,1" to Range("1", "1"), "2,1" to Range("1", "")),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )
    private fun board() = st.schedule.map { it.toIntArray() }.toTypedArray()

    @Test fun cycleFixesOverAndUnderAtOnce() {
        val before = UnifiedViolationChecker.check(st, board())
        assertEquals(1, before.breakdown["high"] ?: 0); assertEquals(1, before.breakdown["low"] ?: 0)
        val r = CycleSwapPolish.apply(st, board())
        val after = UnifiedViolationChecker.check(st, r.newSchedule)
        assertEquals(1, r.applied)
        assertEquals(0, after.breakdown["high"] ?: 0); assertEquals(0, after.breakdown["low"] ?: 0)
        assertTrue(betterReport(after, before))
        assertEquals(listOf(0, 1), r.newSchedule[0].toList().let { listOf(it[0], r.newSchedule[1][0]) })
        assertEquals(0, r.newSchedule[2][0])   // 希望の休は動かさない
        for (j in 0 until 2) assertEquals(board().count { it[j] == 1 }, r.newSchedule.count { it[j] == 1 })   // 各日の人数は不変
    }

    @Test fun noMoveWhenNothingImproves() {
        val r = CycleSwapPolish.apply(st, CycleSwapPolish.apply(st, board()).newSchedule)
        assertEquals(0, r.applied)
    }
}
