package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class C1TemporalDpPolishTest {
    @Test
    fun exactDpCrossesTwoSwapLocalMinimumAndPreservesCount() {
        // T=11, D=5,N=2。X={0,1,5,6}はc1=1。
        // 全1回swapはc1を減らせないが、0→2 と 5→7 の2同時移設でc1=0になる。
        val row = intArrayOf(1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0)
        val rules = listOf(C1TemporalDp.Rule(days = 5, minimum = 2))
        val before = C1TemporalDp.countFires(row, 1, rules)
        assertEquals(1, before)

        val out = C1TemporalDp.solve(
            row = row,
            targetShift = 1,
            rules = rules,
            locked = BooleanArray(row.size),
            maxRelocations = 4,
            seed = 7L,
        )
        assertNotNull(out)
        requireNotNull(out)
        assertEquals(0, out.fires)
        assertEquals(2, out.relocations)
        assertEquals(4, out.changedCells)
        assertEquals("X月間回数を保存", row.count { it == 1 }, out.targetDays.count { it })
    }

    @Test
    fun exactDpNeverChangesLockedTargetOrNonTargetDay() {
        // [CI失敗の修正] 日0(X)・日10(非X)を固定すると、窓[1-5]と窓[6-10]が互いに素な区間で
        // それぞれ独立に2件以上要求するため、日0固定分(1)と合わせて必要X数≥5だが月間回数保存で
        // 4のまま＝数学的に不可能（鳩の巣原理）。日1(X, 解=0→2/5→7で不変)・日9(非X, 同解で不変)を
        // 固定に差し替え、既知の実行可能解(X→{1,2,6,7})と両立することを確認済みの構成にする。
        val row = intArrayOf(1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0)
        val locked = BooleanArray(row.size)
        locked[1] = true   // X固定
        locked[9] = true   // 非X固定
        val out = C1TemporalDp.solve(
            row, 1, listOf(C1TemporalDp.Rule(5, 2)), locked,
            maxRelocations = 4, seed = 11L,
        )
        assertNotNull(out)
        requireNotNull(out)
        assertTrue(out.targetDays[1])
        assertFalse(out.targetDays[9])
    }

    private fun multiSwapState(): MagiState {
        val shifts = listOf(
            Shift("Y", "Y", "", ""),
            Shift("X", "X", "", ""),
        )
        val groups = listOf(Group("G", "G"))
        val staff = listOf(
            Staff("target", 0),
            Staff("partner", 0),
            Staff("stable", 0),
        )
        val target = listOf(1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0)
        val partner = target.map { if (it == 1) 0 else 1 } // 全変更日に完全な同日swap相手がいる
        val stable = List(11) { 1 }
        return MagiState(
            startDate = "2026-01-01", endDate = "2026-01-11",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = listOf(target, partner, stable),
            wishes = emptyMap(),
            // count-changingの手Bを不採用にし、X回数保存の同時移設だけが解になるよう固定。
            staffRange = mapOf("0,1" to Range("4", "4")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun temporalDpPolishAcceptsTwoSimultaneousDailySwaps() {
        // [CI失敗の修正] 既存applyC1WindowPolish(手R1-R3含む)は本セッションの累積改善により
        // この最小盤面を単独で解消してしまい(=手R3の全ペア探索がこの規模では十分に強力)、
        // 「1回swapでは越えられない」という前提の再現に使えなくなっていた。本テストの目的は
        // C1TemporalSwapPolish自体が2同時移設を実現できることの検証のため、既存C1Polishの
        // 前処理を経由せず対象パスを直接呼び出す形に変更する。
        val st = multiSwapState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["c1"] ?: 0)
        assertEquals(0, before.hard)

        val out = C1TemporalSwapPolish.apply(
            st, sched,
            maxPasses = 1, maxRelocations = 4, trials = 8, beamWidth = 128, seed = 7L,
        )
        val after = UnifiedViolationChecker.check(st, out.newSchedule)
        assertEquals(0, after.breakdown["c1"] ?: -1)
        assertEquals(0, after.hard)
        assertEquals(1, out.applied)
        assertTrue(out.logs.first().message.contains("2移設"))

        // 同日swapだけで実現するため、全日でシフト多重集合が完全保存される。
        for (j in 0 until 11) {
            val b = (0 until 3).map { sched[it][j] }.sorted()
            val a = (0 until 3).map { out.newSchedule[it][j] }.sorted()
            assertEquals("day=${j}の日別シフト人数", b, a)
        }
        assertEquals("targetのX月間回数", 4, out.newSchedule[0].count { it == 1 })
    }
}
