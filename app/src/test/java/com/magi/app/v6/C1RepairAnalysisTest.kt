package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.273.0] C1 Repair Analysis（A6）＋ 厳密窓修復（A2/A3/A4）の検証。
 * 各テストは「厳密探索が単一same-day swapの合成では到達できない多日多職員連動手を見つける」
 * または「coverage入替でも解消不能を証明する」ことを、手計算で答えを設計した最小盤面で固定する。
 */
class C1RepairAnalysisTest {

    private fun st(days: Int, staff: Int, sched: List<List<Int>>, cons1: List<C1Row>): MagiState {
        val end = "2026-01-" + days.toString().padStart(2, '0')
        val shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", ""), Shift("Y", "Y", "", ""))
        return MagiState(
            startDate = "2026-01-01", endDate = end,
            shifts = shifts, groups = listOf(Group("G", "G")),
            staff = List(staff) { Staff("s$it", 0) }, use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
            schedule = sched, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = cons1, cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun analyzeEnumeratesDeficientWindowsMatchingChecker() {
        // i0: X X Y Y  a: Y Y X X, ルール「X 2日窓≥1」。checker の c1 と件数一致を確認。
        val s = st(4, 2, listOf(listOf(1, 1, 2, 2), listOf(2, 2, 1, 1)), listOf(C1Row("2", "X", "1")))
        val p = Problem(s)
        val rep = UnifiedViolationChecker.check(s, s.schedule.toIntArray2D())
        val vios = C1RepairAnalysis.analyze(p, s.schedule.toIntArray2D())
        assertEquals("analyze の窓件数は checker の c1 と一致", rep.breakdown["c1"], vios.size)
    }

    @Test
    fun exactSolveFindsCoordinatedCrossDayMultiStaffMove() {
        // i0: X X Y Y  a: Y Y X X, coverage=各日{X,Y}固定. ルール「X 2日窓≥1」.
        // 唯一の0達成は day1,day2 双方の i0<->a swap（多日連動）＝単一same-day swapの1手では到達不能.
        val s = st(4, 2, listOf(listOf(1, 1, 2, 2), listOf(2, 2, 1, 1)), listOf(C1Row("2", "X", "1")))
        val p = Problem(s); val sched = s.schedule.toIntArray2D()
        val base = UnifiedViolationChecker.check(s, sched)
        assertEquals(2, base.breakdown["c1"])
        val v = C1RepairAnalysis.analyze(p, sched).first { it.staff == 0 }
        val r = C1RepairAnalysis.solveWindow(p, sched, v)
        assertTrue("探索を完了(exhaustive)", r.exhaustive)
        assertEquals("joint c1 を 0 まで下げられると証明", 0, r.minJointC1)
        val patch = requireNotNull(r.patch)
        assertTrue("多日連動(2日以上を触る)", patch.map { it[1] }.distinct().size >= 2)
        // 適用して checker で確認: c1=0・coverage保存
        val w = sched.map { it.clone() }.toTypedArray()
        for (op in patch) w[op[0]][op[1]] = op[2]
        val after = UnifiedViolationChecker.check(s, w)
        assertEquals(0, after.breakdown["c1"])
        for (d in 0 until p.T) for (k in 0 until p.K) {
            assertEquals("coverage保存 d=$d k=$k",
                (0 until p.S).count { sched[it][d] == k }, (0 until p.S).count { w[it][d] == k })
        }
    }

    @Test
    fun exactSolveProvesCoverageNeutralWallWhenTokensAreScarce() {
        // 各日 X が1つしかない構成で i0 が「X 3日窓≥2」を要求 → どう入れ替えても i0 は窓内 X を2回持てない
        // （3日で X トークンは3個だが1日1個・他職員も奪い合い）。exhaustive で焦点残>0 を証明.
        // day: 0 1 2  i0: Y 休 Y  a: X Y X  b: 休 X 休  → 各日 X ちょうど1つ.
        val s = st(3, 3, listOf(listOf(2, 0, 2), listOf(1, 2, 1), listOf(0, 1, 0)), listOf(C1Row("3", "X", "2")))
        val p = Problem(s); val sched = s.schedule.toIntArray2D()
        val v = C1RepairAnalysis.analyze(p, sched).first { it.staff == 0 }
        val r = C1RepairAnalysis.solveWindow(p, sched, v)
        assertTrue("探索完了", r.exhaustive)
        assertTrue("焦点職員の窓は coverage入替でも解消不能(残>0)", r.focusResidual > 0)
        assertNull("改善する patch は存在しない", r.patch)
        val walls = C1RepairAnalysis.provenWalls(p, sched)
        assertTrue("provenWalls が i0 の壁を検出", walls.any { it.staff == 0 && it.shift == 1 })
    }

    @Test
    fun passAppliesExactRepairAndIsKeepBestSafe() {
        val s = st(4, 2, listOf(listOf(1, 1, 2, 2), listOf(2, 2, 1, 1)), listOf(C1Row("2", "X", "1")))
        val sched = s.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(s, sched)
        val res = V6HotfixPasses.applyC1ExactWindowRepair(s, sched)
        val after = UnifiedViolationChecker.check(s, res.newSchedule)
        assertTrue("c1 が改善", (after.breakdown["c1"] ?: 0) < (before.breakdown["c1"] ?: 0))
        assertTrue("HARD 非悪化", after.hard <= before.hard)
        assertTrue("total 非悪化", after.total <= before.total)
        // 入力配列は不変（呼出側が別名共有しても安全）
        assertTrue(sched.indices.all { sched[it].contentEquals(s.schedule[it].toIntArray()) })
    }

    @Test
    fun passIsNoOpWhenNoCons1() {
        val s = st(3, 2, listOf(listOf(1, 0, 1), listOf(0, 1, 0)), emptyList())
        val res = V6HotfixPasses.applyC1ExactWindowRepair(s, s.schedule.toIntArray2D())
        assertEquals(0, res.applied)
    }
}
