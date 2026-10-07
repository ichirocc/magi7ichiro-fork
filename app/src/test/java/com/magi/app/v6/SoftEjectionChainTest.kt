package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftEjectionChainTest {
    private val st = StateParser.parse(javaClass.getResource("/sept2026_state.json")!!.readText())!!

    @After fun clear() { SoftEjectionChain.genProbe = null; SoftEjectionChain.firstMoveProbe = null }

    private fun run(families: Set<String> = MirrorKeys.soft.toSet()) =
        SoftEjectionChain.apply(st, st.schedule.toIntArray2D(), C1EjectionChainPolish.Config(maxEvaluations = 40_000L), families, { false }, false)

    @Test fun firstMoveReducesItsOwnFamily() {
        val seen = ArrayList<Triple<String, Long, Long>>()
        SoftEjectionChain.firstMoveProbe = { f, a, b -> seen.add(Triple(f, a, b)) }
        run()
        assertTrue(seen.isNotEmpty())
        for ((f, a, b) in seen) assertTrue("$f $a->$b", b < a)
    }

    @Test fun countBallIsNotAllDaysTimesAllShifts() {
        val p = Problem(st)
        val sizes = ArrayList<Int>()
        SoftEjectionChain.genProbe = { b, n -> if (b.fam in setOf("c2", "low", "high", "apt") && b.i >= 0) sizes.add(n) }
        run(setOf("c2", "low", "high", "apt"))
        assertTrue(sizes.isNotEmpty())
        // 1 セル変更は「そのシフトを持つ日 × 他シフト」か「持たない日 × 1」に限る＋交換。全日×全シフト（T×K）に届かない。
        for (n in sizes) assertTrue("$n", n < p.T * p.K)
    }

    @Test fun c41BallOnlyHoldsStaffWhoCanDoTheShift() {
        val p = Problem(st)
        val work = normalizeSchedule(st.schedule.toIntArray2D(), p)
        val rep = UnifiedViolationChecker.check(st, work)
        val balls = SoftEjectionChain.ballsOf(p, work, rep, setOf("c41", "c41s"))
        for (b in balls) for (c in b.cells) assertTrue(p.mayPlace(c[0], b.k))
    }

    @Test fun neverRaisesAnyHardFamilyAndKeepsCheckerAgreement() {
        val rep0 = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
        val r = run()
        val rep = r.report!!
        for (f in listOf("groupViol", "c3n", "covU", "pref", "c3w")) assertTrue(f, (rep.breakdown[f] ?: 0) <= (rep0.breakdown[f] ?: 0))
        assertEquals(rep.weightedScore, UnifiedViolationChecker.check(st, r.newSchedule).weightedScore, 0.0)
        assertTrue(!betterReport(rep0, rep))
    }

    @Test fun gateStaysOff() {
        assertTrue(!PolishGate.allFamilyEjectionChain)
    }
}
