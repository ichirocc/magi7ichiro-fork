package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 休0日の窓LNS: 夜勤ブロックの位置を並べ替えて、休を明示 0 とした日の休を消す（回数は職員ごとに不変）。 */
class RestZeroWindowLnsTest {
    // 休0 D1 A2 B3。D の翌日は D か休のみ（D→A, D→B 禁止）、D は 3 連まで。
    private fun st(schedule: List<List<Int>>, wishes: Map<String, Int> = emptyMap(), needDay: Map<String, String> = mapOf("0,3" to "0")): MagiState =
        MagiState(
            startDate = "2026-08-01", endDate = "2026-08-06",
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("D", "D", "1", ""), Shift("A", "A", "1", ""), Shift("B", "B", "", "")),
            groups = listOf(Group("G", "G")), staff = listOf(Staff("X", 0), Staff("Y", 0), Staff("Z", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1, 1)), groupShiftApt = listOf(listOf("", "", "", "")),
            schedule = schedule, wishes = wishes, staffRange = emptyMap(), needDay1 = needDay, needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(C3Row(listOf("D", "A")), C3Row(listOf("D", "B")), C3Row(listOf("D", "D", "D", "D"))),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(), skillGroups = emptyList(), cons41s = emptyList(),
        )
    // X の夜勤 3 連が 3 日目で終わり 4 日目（休 0 の日）に休が強制されている盤面。
    private val initial = listOf(
        listOf(1, 1, 1, 0, 2, 2),   // X: D D D 休 A A
        listOf(2, 2, 2, 2, 0, 1),   // Y: A A A A 休 D
        listOf(3, 3, 3, 1, 1, 0),   // Z: B B B D D 休
    )
    private fun sched(s: MagiState) = s.schedule.map { it.toIntArray() }.toTypedArray()
    private fun counts(b: Array<IntArray>, i: Int) = (0 until 4).map { k -> b[i].count { it == k } }

    @Test fun nightBlockIsRealignedSoTheZeroDayHasNoRest() {
        val s = st(initial)
        val before = UnifiedViolationChecker.check(s, sched(s))
        assertEquals(0, before.hard); assertEquals(1, before.breakdown["covO"] ?: 0)
        val r = RestZeroWindowLns.apply(s, sched(s))
        val after = UnifiedViolationChecker.check(s, r.newSchedule)
        assertEquals(1, r.applied)
        assertEquals(0, after.hard)
        assertEquals("4 日目の休が消える", 0, (0 until 3).count { r.newSchedule[it][3] == 0 })
        for (i in 0 until 3) assertEquals("回数不変 $i", counts(sched(s), i), counts(r.newSchedule, i))
        assertTrue(betterReport(after, before))
    }

    @Test fun wishLockedRestOnTheZeroDayIsNotATarget() {
        val s = st(initial, wishes = mapOf("0,3" to 0))
        val r = RestZeroWindowLns.apply(s, sched(s))
        assertEquals(0, r.applied)
        assertTrue(r.logs.single().message.contains("対象日なし"))
    }

    @Test fun noExplicitRestNeedMeansNoTarget() {
        val s = st(initial, needDay = emptyMap())
        val r = RestZeroWindowLns.apply(s, sched(s))
        assertEquals(0, r.applied)
        assertTrue(r.logs.single().message.contains("対象日なし"))
    }
}
