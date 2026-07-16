package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [weekly 研磨の穴] applyWeeklyRebalancePolish（被覆保存の 2職員×2日 長方形交換）の検証。
 * 同日2者スワップでは動かせない weekly（曜日平準化のL1偏差）を、長方形交換が下げられること、
 * かつ keep-best（HARD不変・被覆保存）を確認する。
 */
class WeeklyRebalancePolishTest {

    // 2職員・14日・シフト W(need1=1)/休。各日ちょうど1人が W に入る（被覆= covU/covO 0）。
    // A は weekday {6,0,1} に勤務が偏り(各2回)・weekday {3,4,5} は0回 → weekly-L1=6。
    // B はその補集合で対称に weekly-L1=6（合計12）。長方形交換で過剰曜日→過少曜日へ勤務を移せる。
    private fun weeklyState(): MagiState {
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("W", "W", "1", ""),   // need1=1
        )
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1))   // G0: 休・W とも担当可
        val staff = listOf(Staff("A", 0), Staff("B", 0))
        // 0..13。A の勤務日 = {0,1,2,3,7,8,9}、B = 残り {4,5,6,10,11,12,13}。
        val aRow = listOf(1, 1, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0)
        val bRow = listOf(0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 1, 1)
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-14",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift,
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = listOf(aRow, bRow),
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun weeklyRebalanceReducesWeeklyDeviationAndPreservesCoverage() {
        val st = weeklyState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)

        // 前提: 被覆は満たされ(covU/covO=0)、weekly のみが違反として残っている。
        assertEquals("初期 covU=0", 0, before.breakdown["covU"] ?: 0)
        assertEquals("初期 covO=0", 0, before.breakdown["covO"] ?: 0)
        assertEquals("初期 HARD=0", 0, before.hard)
        assertTrue("初期 weekly>0（曜日の偏りがある）", (before.breakdown["weekly"] ?: 0) > 0)

        val res = V6HotfixPasses.applyWeeklyRebalancePolish(st, sched)
        val after = UnifiedViolationChecker.check(st, res.newSchedule)

        assertTrue("長方形交換を1手以上採用したこと", res.applied > 0)
        assertTrue("weekly が減少したこと", (after.breakdown["weekly"] ?: 0) < (before.breakdown["weekly"] ?: 0))
        // keep-best: total は非悪化、HARD は不変(=0)、被覆は保存。
        assertTrue("total が非悪化(keep-best)", after.total <= before.total)
        assertEquals("HARD 不変(=0)", 0, after.hard)
        assertEquals("被覆保存: covU=0 のまま", 0, after.breakdown["covU"] ?: 0)
        assertEquals("被覆保存: covO=0 のまま", 0, after.breakdown["covO"] ?: 0)
    }

    @Test
    fun weeklyRebalanceIsNoOpWhenBalanced() {
        // 既に weekly=0（各職員が全曜日を均等に勤務）なら 1手も採用しない（空探索は即終了）。
        val shifts = listOf(Shift("休", "休", "", ""), Shift("W", "W", "1", ""))
        val groups = listOf(Group("G0", "G0"))
        // 7日・1職員が毎日 W（各曜日ちょうど1回＝weekly=0）。need を満たすため2人目は毎日休。
        val staff = listOf(Staff("A", 0), Staff("B", 0))
        val aRow = listOf(1, 1, 1, 1, 1, 1, 1)
        val bRow = listOf(0, 0, 0, 0, 0, 0, 0)
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-07",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = listOf(aRow, bRow),
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val res = V6HotfixPasses.applyWeeklyRebalancePolish(st, sched)
        assertEquals("均等配置では採用0（no-op）", 0, res.applied)
    }
}
