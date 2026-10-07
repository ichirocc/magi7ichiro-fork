package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外部レビュー OPT-01〜03: 事前判定・最良の記録を正式比較（必須件数→重み付き→総数）に揃える／評価上限を超えない。 */
class ReviewOptFixesTest {
    /**
     * 1 名×2 日。0 日目は希望 B で固定、1 日目は A が 1 人必要、B→A は禁止の並び、休は下限 2。
     * 盤面 [B, 休] で 1 日目を A にする手だけが改善: 必須 1→1（人員不足→禁止の並び）、重み付き 10120→9240、ソフトは +120。
     */
    private fun swapFamilyState(): MagiState = MagiState(
        startDate = "2026-10-01", endDate = "2026-10-02",
        shifts = listOf(Shift("休み", "休", "", "", ShiftRole.Rest), Shift("A勤", "A", "", ""), Shift("B勤", "B", "", "")),
        groups = listOf(Group("G0", "G0")), staff = listOf(Staff("s0", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(listOf(2, 0)), wishes = mapOf("0,0" to 2), staffRange = mapOf("0,0" to Range("2", "")),
        needDay1 = mapOf("1,1" to "1"), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = listOf(C3Row(listOf("B", "A"))),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun fixtureIsTheSwapFamilyCase() {
        val st = swapFamilyState()
        val before = UnifiedViolationChecker.check(st, arrayOf(intArrayOf(2, 0)))
        val after = UnifiedViolationChecker.check(st, arrayOf(intArrayOf(2, 1)))
        assertEquals(1, before.hard); assertEquals(1, after.hard)
        assertTrue(betterReport(after, before))
        val de = DeltaEvaluator(Problem(st)); de.reset(arrayOf(intArrayOf(2, 0)))
        val s0 = de.score(); de.apply(0, 1, 1)
        assertTrue("素の score では悪化に見える（不具合の原因）", de.score() > s0)
    }

    @Test fun reportKeyMatchesCheckerOnRealData() {
        val st = StateParser.parse(javaClass.getResource("/sept2026_state.json")!!.readText())!!
        val p = Problem(st)
        val work = normalizeSchedule(st.schedule.toIntArray2D(), p)
        val de = DeltaEvaluator(p); de.reset(work)
        val rng = kotlin.random.Random(11)
        repeat(200) {
            val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
            if (!p.wishLocked(i, j)) { val k = p.allowedShiftsForStaff(i).random(rng); work[i][j] = k; de.apply(i, j, k) }
            val rep = UnifiedViolationChecker.check(st, work)
            assertArrayEquals(longArrayOf(rep.hard.toLong(), rep.weightedScore.toLong(), rep.total.toLong()), de.reportKey())
        }
    }

    @Test fun prePostDescentTakesTheFamilySwapImprovement() {
        val st = swapFamilyState()
        val r = PrePostDescent.apply(st, arrayOf(intArrayOf(2, 0)), maxMillis = 60_000L, maxDraws = 500, seed = 1L)
        assertEquals(1, r.newSchedule[0][1])
        assertTrue(r.improved >= 1)
    }

    @Test fun ejectionChainKeepsAndAdoptsTheFamilySwapImprovement() {
        val st = swapFamilyState()
        val r = C1EjectionChainPolish.apply(st, arrayOf(intArrayOf(2, 0)),
            C1EjectionChainPolish.Config(origin = C1EjectionChainPolish.Origin.ALL, maxEvaluations = 2_000L))
        assertEquals(1, r.newSchedule[0][1])
    }

    @Test fun ejectionChainNeverExceedsTheEvaluationCap() {
        val st = StateParser.parse(javaClass.getResource("/sept2026_state.json")!!.readText())!!
        for (cap in longArrayOf(5_000L, 10_000L)) {
            val stats = C1EjectionChainPolish.Stats()
            C1EjectionChainPolish.apply(st, st.schedule.toIntArray2D(),
                C1EjectionChainPolish.Config(origin = C1EjectionChainPolish.Origin.ALL, maxEvaluations = cap), stats = stats)
            assertTrue("評価 ${stats.evaluations} > 上限 $cap", stats.evaluations <= cap)
        }
    }
}
