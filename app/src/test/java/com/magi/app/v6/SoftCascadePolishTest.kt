package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftCascadePolishTest {
    private val st = StateParser.parse(javaClass.getResource("/sept2026_state.json")!!.readText())!!
    private val cfg = SoftCascadePolish.Config(maxMillis = Long.MAX_VALUE)

    private fun run(s0: Array<IntArray> = st.schedule.toIntArray2D(), c: SoftCascadePolish.Config = cfg) =
        SoftCascadePolish.apply(st, s0, c, { false }, 0L, false)

    @Test fun neverRaisesAnyHardFamilyAndReportMatchesChecker() {
        val s0 = st.schedule.toIntArray2D()
        val rep0 = UnifiedViolationChecker.check(st, s0)
        val r = run(s0)
        for (h in MirrorKeys.hard) assertTrue(h, (r.report.breakdown[h] ?: 0) <= (rep0.breakdown[h] ?: 0))
        val again = UnifiedViolationChecker.check(st, r.newSchedule)
        assertEquals(again.breakdown, r.report.breakdown)
        assertTrue(r.applied == 0 || betterReport(r.report, rep0))
        assertFalse(betterReport(rep0, r.report))
    }

    @Test fun doesNotMutateInputAndIsDeterministic() {
        val s0 = st.schedule.toIntArray2D()
        val copy = s0.map { it.copyOf() }.toTypedArray()
        val a = run(s0); val b = run(copy.map { it.copyOf() }.toTypedArray())
        assertTrue(s0.contentDeepEquals(copy))
        assertTrue(a.newSchedule.contentDeepEquals(b.newSchedule))
    }

    @Test fun keepsWishesAndDoesNotPlaceForbiddenShifts() {
        val p = Problem(st)
        val s0 = normalizeSchedule(st.schedule.toIntArray2D(), p)
        val r = run(s0.map { it.copyOf() }.toTypedArray())
        for (i in 0 until p.S) for (j in 0 until p.T) {
            if (r.newSchedule[i][j] == s0[i][j]) continue
            assertFalse(p.wishLocked(i, j))
            assertTrue(p.mayPlace(i, r.newSchedule[i][j]))
        }
    }

    @Test fun stopReturnsRootUnchanged() {
        val s0 = st.schedule.toIntArray2D()
        val r = SoftCascadePolish.apply(st, s0, cfg, { true }, 0L, false)
        assertEquals(0, r.applied)
        assertTrue(r.newSchedule.contentDeepEquals(normalizeSchedule(s0, Problem(st))))
    }

    @Test fun anchorsKeepTheirCoordinates() {
        val p = Problem(st)
        val work = normalizeSchedule(st.schedule.toIntArray2D(), p)
        val rep = UnifiedViolationChecker.check(st, work)
        val balls = SoftCascadePolish.ballsOf(p, work, rep)
        for (b in balls) when (b.fam) {
            "c2", "low", "high", "apt", "fair", "weekly" -> assertTrue(b.fam, b.i >= 0 && b.k >= 0)
            "covO", "c41", "c41s" -> { assertTrue(b.k >= 0 && b.days!!.size == 1); for (c in b.cells) assertTrue(p.mayPlace(c[0], b.k)) }
            "c1" -> assertTrue(b.days!!.isNotEmpty())
        }
        // 1 つの回数キーに重なった族を落とさない。
        for ((key, classes) in rep.countFamilies) {
            val (i, k) = key.split(",").map { it.toInt() }
            val fams = classes.map { it.removePrefix("vio-").let { c -> if (c.startsWith("apt")) "apt" else c } }.filter { it in setOf("c2", "low", "high", "apt") }.toSet()
            assertEquals(fams, balls.filter { it.i == i && it.k == k && it.fam in setOf("c2", "low", "high", "apt") }.map { it.fam }.toSet())
        }
        val c42 = rep.cellFamilies.values.flatten().count { it == "vio-c42" || it == "vio-c42s" }
        if (c42 > 0) assertTrue(balls.any { it.fam == "c42" || it.fam == "c42s" })
    }

    @Test fun gateDefaultsOff() {
        assertFalse(PolishGate.softCascade)
        assertFalse(V6HotfixPasses.PostOptimizationParams().softCascadeEnabled)
    }
}
