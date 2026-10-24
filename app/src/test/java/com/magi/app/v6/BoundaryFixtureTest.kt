package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * T-16（`docs/overhaul_detailed_spec.md` §4.1 B-2）: 職員 1 名・30 名 × 期間 1 日・28〜31 日の境界で、チェッカー・フル評価・
 * 決定的モードの後処理が落ちずに返り、後処理の結果が入力より悪くならない（番兵を通る）。30 名・31 日ではビット化経路が有効。
 */
class BoundaryFixtureTest {
    private val REST = 0; private val A = 1; private val B = 2

    /** 休・A・B の 3 シフト、A/B に必要人数、禁止の並び「B→A」、窓の制約（7 日に休 1 回）、職員 0 の A に回数範囲、希望 1 件。 */
    private fun state(staff: Int, start: String, end: String, days: Int): MagiState {
        val needA = maxOf(1, staff / 4); val needB = staff / 5
        val rng = Random(staff * 100L + days)
        val sched = List(staff) { i -> List(days) { j -> if (i == 0 && j == 0) REST else rng.nextInt(3) } }   // 希望（0,0）=休 は入力で満たす
        return MagiState(
            startDate = start, endDate = end,
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("A", "A", "$needA", "$needA"), Shift("B", "B", if (needB > 0) "$needB" else "", if (needB > 0) "$needB" else "")),
            groups = listOf(Group("G", "G")), staff = List(staff) { Staff("s$it", 0) }, use2Patterns = true,
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
            schedule = sched, wishes = mapOf("0,0" to REST), staffRange = mapOf("0,$A" to Range("0", "${days / 2}")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = if (days >= 7) listOf(C1Row("7", "休", "1")) else emptyList(), cons2 = emptyList(),
            cons3 = emptyList(), cons3n = listOf(C3Row(listOf("B", "A"))), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    private fun runBoundary(staff: Int, start: String, end: String, days: Int) {
        val st = state(staff, start, end, days)
        assertEquals(staff, st.staffCount); assertEquals(days, st.dayCount)
        val p = Problem(st)
        val input = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, input.copy2D())
        Evaluator(p).fullEval(input.copy2D())
        val out = V6HotfixPasses.runPostOptimization(st, input.copy2D(), "t16", seed = 1L,
            deadlineMs = EngineClock.nowMs() + 600_000L,
            params = V6HotfixPasses.PostOptimizationParams(deterministic = true, maxRounds = 1,
                c1LnsMaxEvaluations = 2_000, personalLnsMaxEvaluations = 2_000))
        val tag = "${staff}名×${days}日"
        assertEquals("$tag 行数", staff, out.schedule.size)
        assertTrue("$tag 列数", out.schedule.all { it.size == days })
        val after = UnifiedViolationChecker.check(st, out.schedule.copy2D())
        assertTrue("$tag 必須が増えた ${before.hard}->${after.hard}", after.hard <= before.hard)
        assertFalse("$tag 入力より悪い", betterReport(before, after))
        assertEquals("$tag 希望固定のセル", REST, out.schedule[0][0])
        Evaluator(p).fullEval(out.schedule.copy2D())
    }

    @Test fun oneStaffOneDay() = runBoundary(1, "2026-01-01", "2026-01-01", 1)
    @Test fun oneStaffThirtyOneDays() = runBoundary(1, "2026-01-01", "2026-01-31", 31)
    @Test fun thirtyStaffOneDay() = runBoundary(30, "2026-01-01", "2026-01-01", 1)
    @Test fun thirtyStaffTwentyEightDays() = runBoundary(30, "2026-02-01", "2026-02-28", 28)

    @Test fun thirtyStaffThirtyOneDaysUsesTheBitPath() {
        val st = state(30, "2026-01-01", "2026-01-31", 31)
        assertTrue("30 名・31 日でビット化経路が有効", C3nBitScan.usable(Problem(st)))
        runBoundary(30, "2026-01-01", "2026-01-31", 31)
    }
}
