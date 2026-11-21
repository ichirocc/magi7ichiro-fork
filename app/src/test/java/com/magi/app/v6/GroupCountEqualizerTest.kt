package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** グループ内の回数をそろえる（[GroupCountEqualizer]、3.727.115）。 */
class GroupCountEqualizerTest {
    private val k = 3   // 0 休・1 D・2 A

    private fun state(sched: List<List<Int>>, staffRange: Map<String, Range> = emptyMap(), wishes: Map<String, Int> = emptyMap()) = MagiState(
        startDate = "2026-12-01", endDate = "2026-12-14",
        shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("D", "D", "", ""), Shift("A", "A", "", "")),
        groups = listOf(Group("G0", "G0"), Group("G1", "G1")), staff = sched.indices.map { Staff("s$it", if (it < 3) 0 else 1, 0) },
        use2Patterns = false, groupShift = listOf(List(k) { 1 }, List(k) { 1 }), groupShiftApt = listOf(List(k) { "" }, List(k) { "" }),
        schedule = sched, wishes = wishes, staffRange = staffRange, needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun dayCounts(s: Array<IntArray>, t: Int) = (0 until t).map { j -> s.groupingBy { it[j] }.eachCount() }

    @Test fun equalizesWithinGroupAndKeepsEachDay() {
        // G0 の 3 人: s0 は D ばかり、s2 は休ばかり → 同じ日の入れ替えでそろう
        val sched = listOf(List(14) { 1 }, List(14) { if (it % 2 == 0) 1 else 0 }, List(14) { 0 }, List(14) { 2 })
        val st = state(sched)
        val b = sched.map { it.toIntArray() }.toTypedArray()
        val r = GroupCountEqualizer.apply(st, b)
        assertTrue(r.swaps > 0)
        assertTrue(r.spreadAfter < r.spreadBefore)
        assertEquals(dayCounts(b, 14), dayCounts(r.schedule, 14))
        val d = (0..2).map { i -> r.schedule[i].count { it == 1 } }
        assertTrue("D の回数の差が 1 以内: $d", d.max() - d.min() <= 1)
        assertEquals(sched[3], r.schedule[3].toList())   // 1 人だけのグループは動かさない
    }

    @Test fun keepsWishesRangesAndNeverAddsHard() {
        val rng = Random(11)
        repeat(30) {
            val sched = List(5) { List(14) { rng.nextInt(k) } }
            val wishes = mapOf("0,0" to 1, "1,3" to 0)
            val range = mapOf("1,1" to Range("2", "4"))
            val st = state(sched, range, wishes)
            val b = sched.map { it.toIntArray() }.toTypedArray()
            val before = UnifiedViolationChecker.check(st, b)
            val r = GroupCountEqualizer.apply(st, b)
            val after = UnifiedViolationChecker.check(st, r.schedule)
            assertTrue(after.hard <= before.hard)
            assertTrue(r.spreadAfter <= r.spreadBefore)
            if (b[0][0] == 1) assertEquals(1, r.schedule[0][0])
            if (b[1][3] == 0) assertEquals(0, r.schedule[1][3])
            fun out(s: Array<IntArray>) = s[1].count { it == 1 }.let { maxOf(0, 2 - it) + maxOf(0, it - 4) }
            assertTrue(out(r.schedule) <= out(b))
            assertEquals(dayCounts(b, 14), dayCounts(r.schedule, 14))
        }
    }
}
