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
        // 1 人だけのグループ（s3）は目標を持たないが、別のグループとの入れ替えの相手にはなる＝日ごとの人数だけ確かめる
    }

    @Test fun onlyPickedShiftsAreTargeted() {
        // D だけ選ぶ: D の差は縮むが、A だけの偏りのための入れ替えはしない
        val sched = listOf(List(14) { 1 }, List(14) { 0 }, List(14) { 2 }, List(14) { 0 })
        val st = state(sched)
        val b = sched.map { it.toIntArray() }.toTypedArray()
        val onlyA = GroupCountEqualizer.apply(st, b, setOf(2))
        val onlyD = GroupCountEqualizer.apply(st, b, setOf(1))
        val d = (0..2).map { i -> onlyD.schedule[i].count { it == 1 } }
        assertTrue("D の差が縮む: $d", d.max() - d.min() <= 1)
        val a = (0..2).map { i -> onlyA.schedule[i].count { it == 2 } }
        assertTrue("A の差が縮む: $a", a.max() - a.min() <= 1)
        assertEquals(0, GroupCountEqualizer.apply(st, b, emptySet()).swaps)
    }

    @Test fun monthWideChainWhenSingleSwapsAreBlocked() {
        // 休は 2 人とも 7 回固定。偶数日の D↔休 は 1 組では休の範囲を外れ、奇数日の 休↔A は D が動かない
        // → 偶数日と奇数日の 2 組を合わせて初めて D がそろう（焼きなまし）。s2 は D 不可・A 14 回固定で動かない。
        val sched = listOf(List(14) { if (it % 2 == 0) 1 else 0 }, List(14) { if (it % 2 == 0) 0 else 2 }, List(14) { 2 })
        val range = mapOf("0,0" to Range("7", "7"), "1,0" to Range("7", "7"), "2,1" to Range("0", "0"), "2,2" to Range("14", "14"))
        val st = state(sched, range)
        val b = sched.map { it.toIntArray() }.toTypedArray()
        assertEquals(0, GroupCountEqualizer.apply(st, b, setOf(1), annealIters = 0).swaps)
        val r = GroupCountEqualizer.apply(st, b, setOf(1))
        val d = (0..1).map { i -> r.schedule[i].count { it == 1 } }
        assertTrue("D の差が 1 以内: $d", kotlin.math.abs(d[0] - d[1]) <= 1)
        assertEquals(listOf(7, 7), (0..1).map { i -> r.schedule[i].count { it == 0 } })
        assertEquals(dayCounts(b, 14), dayCounts(r.schedule, 14))
    }

    @Test fun threePersonRotationWhenEveryPairSwapIsBlocked() {
        // 休・D・A・B の 4 種。上限 0 で s0 は B 不可・s1 は D 不可・s2 は A 不可。毎日 s0=D・s1=A・s2=B
        // → どの 2 人の入れ替えも誰かに置けないシフトが入る。3 人の回し（s0←A・s1←B・s2←D）だけが D を s0 から s2 へ渡せる。
        val st = MagiState(
            startDate = "2026-12-01", endDate = "2026-12-14",
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("D", "D", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
            groups = listOf(Group("G0", "G0")), staff = (0..2).map { Staff("s$it", 0, 0) },
            use2Patterns = false, groupShift = listOf(List(4) { 1 }), groupShiftApt = listOf(List(4) { "" }),
            schedule = listOf(List(14) { 1 }, List(14) { 2 }, List(14) { 3 }), wishes = emptyMap(),
            staffRange = mapOf("0,3" to Range("0", "0"), "1,1" to Range("0", "0"), "2,2" to Range("0", "0")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val b = st.schedule.map { it.toIntArray() }.toTypedArray()
        assertEquals(0, GroupCountEqualizer.apply(st, b, setOf(1), annealIters = 0).swaps)
        val r = GroupCountEqualizer.apply(st, b, setOf(1))
        val d = listOf(0, 2).map { i -> r.schedule[i].count { it == 1 } }
        assertTrue("D の差が 1 以内: $d", kotlin.math.abs(d[0] - d[1]) <= 1)
        for (j in 0 until 14) {
            assertEquals(setOf(1, 2, 3), (0..2).map { r.schedule[it][j] }.toSet())   // 日ごとの人数は不変
            assertTrue(r.schedule[0][j] != 3 && r.schedule[1][j] != 1 && r.schedule[2][j] != 2)   // 置けないシフトは入らない
        }
    }

    @Test fun crossGroupPartnerMovesCountsTheOwnGroupCannot() {
        // 休・D・A・B。G0＝s0・s1・s2、G1＝s3。毎日 s0=D・s1=A・s2=A・s3=B。s1・s2 は B を置けず（上限 0）、s0 は A を置けない＝
        // G0 の中ではどう入れ替えても D は s0 から動かない。G1 の s3 を介した 3 人の回し（s0←B・s3←A・s1←D）だけが D を渡せる。
        val st = MagiState(
            startDate = "2026-12-01", endDate = "2026-12-14",
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("D", "D", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
            groups = listOf(Group("G0", "G0"), Group("G1", "G1")), staff = (0..3).map { Staff("s$it", if (it < 3) 0 else 1, 0) },
            use2Patterns = false, groupShift = listOf(List(4) { 1 }, List(4) { 1 }), groupShiftApt = listOf(List(4) { "" }, List(4) { "" }),
            schedule = listOf(List(14) { 1 }, List(14) { 2 }, List(14) { 2 }, List(14) { 3 }), wishes = emptyMap(),
            staffRange = mapOf("0,2" to Range("0", "0"), "1,3" to Range("0", "0"), "2,3" to Range("0", "0")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val b = st.schedule.map { it.toIntArray() }.toTypedArray()
        assertEquals(0, GroupCountEqualizer.apply(st, b, setOf(1), annealIters = 0).swaps)
        val r = GroupCountEqualizer.apply(st, b, setOf(1))
        val d = (0..2).map { i -> r.schedule[i].count { it == 1 } }
        assertTrue("G0 の D が 1 人に偏らない: $d", d.max() - d.min() < 14)
        assertEquals(dayCounts(b, 14), dayCounts(r.schedule, 14))
        for (j in 0 until 14) assertTrue(r.schedule[0][j] != 2 && r.schedule[1][j] != 3 && r.schedule[2][j] != 3)
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

    @Test fun lowersCheckerFairOnRealData() {
        // ずれは採点の fair と同じ物差し＝押したあとチェッカーの fair が増えない・必須が増えない
        for (name in listOf("golden_state.json", "oct2026_grid_state.json")) {
            val st = com.magi.app.model.StateParser.parse(javaClass.classLoader!!.getResource(name)!!.readText())!!
            val b = normalizeSchedule(st.schedule.map { it.toIntArray() }.toTypedArray(), Problem(st))
            val before = UnifiedViolationChecker.check(st, b)
            val t0 = System.currentTimeMillis()
            val r = GroupCountEqualizer.apply(st, b)
            val after = UnifiedViolationChecker.check(st, r.schedule)
            println("$name fair ${before.breakdown["fair"]}->${after.breakdown["fair"]} spread ${r.spreadBefore}->${r.spreadAfter} swaps ${r.swaps} ${System.currentTimeMillis() - t0}ms")
            assertTrue(name, (after.breakdown["fair"] ?: 0) <= (before.breakdown["fair"] ?: 0))
            assertTrue(name, after.hard <= before.hard)
        }
    }
}
