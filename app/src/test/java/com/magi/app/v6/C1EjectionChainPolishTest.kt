package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class C1EjectionChainPolishTest {
    private fun state(): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-03",
        shifts = listOf(Shift("休み", "休", "", "", ShiftRole.Rest), Shift("日勤", "D", "1", ""), Shift("夜勤", "N", "1", "")),
        groups = listOf(Group("G0", "G0")),
        staff = listOf(Staff("s0", 0), Staff("s1", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(listOf(2, 2, 2), listOf(1, 1, 1)),
        wishes = emptyMap(), staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = listOf(C1Row("3", "D", "1"), C1Row("3", "N", "1")), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun noSingleCellMoveImprovesButChainDoes() {
        val st = state()
        val s0 = st.schedule.toIntArray2D()
        val rep0 = UnifiedViolationChecker.check(st, s0)
        assertEquals(0, rep0.hard)
        assertTrue((rep0.breakdown["c1"] ?: 0) > 0)
        for (i in 0 until 2) for (j in 0 until 3) for (k in 0 until 3) {
            if (k == s0[i][j]) continue
            val w = Array(2) { s0[it].copyOf() }; w[i][j] = k
            assertFalse(betterReport(UnifiedViolationChecker.check(st, w), rep0))
        }
        val r = C1EjectionChainPolish.apply(st, s0)
        assertEquals(0, r.report!!.hard)
        assertTrue((r.report!!.breakdown["c1"] ?: 0) < (rep0.breakdown["c1"] ?: 0))
        assertTrue(betterReport(r.report!!, rep0))
    }

    @Test fun gateDefaultsOff() {
        assertFalse(PolishGate.c1EjectionChain)
    }

    /** 連鎖が子評価に使う差分評価の族別内訳・必須数・加重値・総数が、実データ上で正式評価と一致する。 */
    @Test fun deltaFamiliesMatchCheckerOnRealData() {
        val st = com.magi.app.model.StateParser.parse(javaClass.getResource("/sept2026_state.json")!!.readText())!!
        val p = Problem(st)
        val work = normalizeSchedule(st.schedule.toIntArray2D(), p)
        val de = DeltaEvaluator(p); de.reset(work)
        val rng = kotlin.random.Random(7)
        repeat(300) { n ->
            val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
            if (!p.wishLocked(i, j)) { val k = p.allowedShiftsForStaff(i).random(rng); work[i][j] = k; de.apply(i, j, k) }
            if (n % 30 == 0) {
                val rep = UnifiedViolationChecker.check(st, work)
                for ((f, raw) in de.familyRaw()) assertEquals("family=$f", (rep.breakdown[f] ?: 0).toLong(), raw)
                val (lo, hi) = de.rangeRaw()
                assertEquals((rep.breakdown["low"] ?: 0).toLong(), lo)
                assertEquals((rep.breakdown["high"] ?: 0).toLong(), hi)
                assertEquals(rep.hard.toLong(), de.score() / SCORE_HARD_UNIT)
            }
        }
    }

    /** 評価上限で打ち切っても、正式評価で採った盤面か起点だけを返す（同点・悪化を返さない）。 */
    @Test fun evaluationCapReturnsStartOrStrictlyBetter() {
        val st = com.magi.app.model.StateParser.parse(javaClass.getResource("/sept2026_state.json")!!.readText())!!
        val s0 = normalizeSchedule(st.schedule.toIntArray2D(), Problem(st))
        val rep0 = UnifiedViolationChecker.check(st, s0)
        for (cap in longArrayOf(1L, 500L)) {
            val r = C1EjectionChainPolish.apply(st, s0.map { it.copyOf() }.toTypedArray(), C1EjectionChainPolish.Config(maxEvaluations = cap))
            val rep = UnifiedViolationChecker.check(st, r.newSchedule)
            assertTrue(rep.hard <= rep0.hard)
            assertTrue(r.newSchedule.contentDeepEquals(s0) || betterReport(rep, rep0))
        }
    }
}
