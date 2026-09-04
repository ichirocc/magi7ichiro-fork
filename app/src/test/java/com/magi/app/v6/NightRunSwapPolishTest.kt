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
 * [3.493.0] 夜勤連交換研磨の回帰テスト。挟まれセル（前日=夜勤 N、翌日=希望固定の早番 E、禁止の並び N→E）で
 * 当日に置けるのが 休 だけ＝休の上限0 を破る局面を作り、夜勤の連を他職員の同じ長さの連と丸ごと交換すると
 * 当日を E に付け替えられて high が消えることを固定する。
 */
class NightRunSwapPolishTest {
    // 0=休 1=N(夜勤) 2=E(早番)。被覆は使わない（need 空）＝回数・並びの族だけで採否が決まる。
    private fun state(): MagiState = MagiState(
        startDate = "2026-06-01", endDate = "2026-06-06",
        shifts = listOf(Shift("休", "休", "", ""), Shift("N", "N", "", ""), Shift("E", "E", "", "")),
        groups = listOf(Group("A", "A"), Group("B", "B")),
        staff = listOf(Staff("甲", 0), Staff("乙", 1)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1), listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", ""), listOf("", "", "")),
        // 甲: N N 休 E* E E（3日目が挟まれセル） / 乙: E E 休 E N N（5-6日目の連が甲の1-2日目の連と交換可能）
        schedule = listOf(listOf(1, 1, 0, 2, 2, 2), listOf(2, 2, 0, 2, 1, 1)),
        wishes = mapOf("0,3" to 2),
        staffRange = mapOf("0,0" to Range("0", "0")),   // 甲の休は上限0
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = listOf(C3Row(listOf("N", "E", "", "", ""))),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test
    fun exchangingTheNightRunFreesTheSandwichedDay() {
        val s = state()
        val sched = s.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(s, sched)
        assertEquals("前提: 甲の休(上限0)が1回超過", 1, before.breakdown["high"])
        val r = NightRunSwapPolish.applyNightRunSwapPolish(s, sched)
        val after = UnifiedViolationChecker.check(s, r.newSchedule)
        assertTrue("採用が1回以上: ${r.logs.first().message}", r.applied >= 1)
        assertEquals("休の超過が消える", 0, after.breakdown["high"] ?: 0)
        assertEquals("禁止の並びは作らない", 0, after.breakdown["c3n"] ?: 0)
        assertEquals("希望は固定のまま", 2, r.newSchedule[0][3])
        // 夜勤の回数は両者とも保存（連ごと交換）
        assertEquals(2, r.newSchedule[0].count { it == 1 }); assertEquals(2, r.newSchedule[1].count { it == 1 })
        assertTrue(betterReport(after, before))
    }

    @Test
    fun skipsCleanlyWithoutForbiddenSequences() {
        val s = state().copy(cons3n = emptyList())
        val r = NightRunSwapPolish.applyNightRunSwapPolish(s, s.schedule.toIntArray2D())
        assertEquals(0, r.applied)
        assertTrue(r.logs.first().message.contains("スキップ"))
    }
}
