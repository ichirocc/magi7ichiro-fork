package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.494.0] 連交換研磨（汎用）の回帰テスト。シフトの意味を使わないことを2つの形で固定する:
 *  1. 挟まれセル（前日=N の連、翌日=希望固定 E、禁止の並び N→E）: N の連を他職員の連と交換して当日を E にでき high が消える。
 *  2. 禁止の並びが**一切無い**データで、休の連の交換だけで low（休の下限割れ）が消える＝夜勤や cons3n を前提にしない。
 */
class RunSwapPolishTest {
    private fun base(schedule: List<List<Int>>, wishes: Map<String, Int>, staffRange: Map<String, Range>, cons3n: List<C3Row>) = MagiState(
        startDate = "2026-06-01", endDate = "2026-06-06",
        shifts = listOf(Shift("休", "休", "", ""), Shift("N", "N", "", ""), Shift("E", "E", "", "")),
        groups = listOf(Group("A", "A"), Group("B", "B")),
        staff = listOf(Staff("甲", 0), Staff("乙", 1)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1), listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", ""), listOf("", "", "")),
        schedule = schedule, wishes = wishes, staffRange = staffRange,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = cons3n,
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test
    fun exchangingTheAdjacentRunFreesTheSandwichedDay() {
        // 甲: N N 休 E* E E（3日目が挟まれセル、休は上限0） / 乙: E E 休 E N N
        val s = base(listOf(listOf(1, 1, 0, 2, 2, 2), listOf(2, 2, 0, 2, 1, 1)), mapOf("0,3" to 2), mapOf("0,0" to Range("0", "0")),
            listOf(C3Row(listOf("N", "E", "", "", ""))))
        val sched = s.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(s, sched)
        assertEquals(1, before.breakdown["high"])
        val r = RunSwapPolish.applyRunSwapPolish(s, sched)
        val after = UnifiedViolationChecker.check(s, r.newSchedule)
        assertTrue("採用が1回以上: ${r.logs.first().message}", r.applied >= 1)
        assertEquals(0, after.breakdown["high"] ?: 0)
        assertEquals(0, after.breakdown["c3n"] ?: 0)
        assertEquals("希望は固定のまま", 2, r.newSchedule[0][3])
        assertEquals(2, r.newSchedule[0].count { it == 1 }); assertEquals(2, r.newSchedule[1].count { it == 1 })
        assertTrue(betterReport(after, before))
    }

    @Test
    fun exchangingARestRunFixesACountShortfallWithoutAnyForbiddenRule() {
        // 甲: N N E E E E（休0回、下限2） / 乙: 休 休 N N E E。甲の N 連(1-2日)と乙の N 連(3-4日)を交換すると
        // 甲=休 休 N N E E で休が2回＝low 解消。禁止の並びは無し＝「夜勤」という概念を使わない。
        val s = base(listOf(listOf(1, 1, 2, 2, 2, 2), listOf(0, 0, 1, 1, 2, 2)), emptyMap(), mapOf("0,0" to Range("2", "")), emptyList())
        val sched = s.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(s, sched)
        assertEquals(2, before.breakdown["low"])
        val r = RunSwapPolish.applyRunSwapPolish(s, sched)
        val after = UnifiedViolationChecker.check(s, r.newSchedule)
        assertTrue("採用が1回以上: ${r.logs.first().message}", r.applied >= 1)
        assertEquals(0, after.breakdown["low"] ?: 0)
        assertEquals(2, r.newSchedule[0].count { it == 0 })
        assertTrue(betterReport(after, before))
    }
}
