package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 差分での候補の採点（[DeltaScreen]）がチェッカーの比較キーと一致し、研磨の結果を変えないこと。 */
class DeltaScreenTest {
    private val fixtures = listOf(
        "golden_state.json", "sample_state_v6.json", "blocked_covu_state.json",
        "sept2026_state.json", "oct2026_grid_state.json", "full_coverage_state.json",
    )

    private fun load(name: String) = StateParser.parse(javaClass.getResource("/$name")!!.readText())!!

    private fun <T> withMethod(m: ObjectiveMethod, body: () -> T): T {
        val saved = ObjectiveWeights.method
        ObjectiveWeights.method = m
        try { return body() } finally { ObjectiveWeights.method = saved }
    }

    @Test fun keyMatchesCheckerForMultiCellCandidates() {
        for (m in ObjectiveMethod.entries) withMethod(m) {
            for (f in fixtures) {
                val st = load(f)
                val p = Problem(st)
                val base = normalizeSchedule(st.schedule.toIntArray2D(), p)
                if (base.any { row -> row.any { it !in 0 until p.K } }) continue
                val screen = DeltaScreen(p)
                val rng = kotlin.random.Random(f.hashCode())
                repeat(60) {
                    val cand = base.copy2D()
                    repeat(1 + rng.nextInt(12)) { cand[rng.nextInt(p.S)][rng.nextInt(p.T)] = rng.nextInt(p.K) }
                    val rep = UnifiedViolationChecker.check(st, cand)
                    val k = screen.key(cand)
                    assertNotNull(k)
                    assertArrayEquals("$f $m", longArrayOf(rep.hard.toLong(), rep.weightedScore.toLong(), rep.total.toLong()), k)
                }
            }
        }
    }

    @Test fun personalJointLnsReturnsTheSameBoardWithAndWithoutTheScreen() {
        val cfg = PersonalBalanceJointLnsPolish.Config(maxMillis = 60_000L, maxEvaluations = 600)
        var compared = 0
        for (m in ObjectiveMethod.entries) withMethod(m) {
            for (f in fixtures) {
                val st = load(f)
                val sched = st.schedule.toIntArray2D()
                val saved = DeltaScreen.enabled
                try {
                    DeltaScreen.enabled = false
                    val full = PersonalBalanceJointLnsPolish.apply(st, sched, cfg)
                    DeltaScreen.enabled = true
                    val delta = PersonalBalanceJointLnsPolish.apply(st, sched, cfg)
                    assertTrue("$f $m", AdaptiveEliteArchive.sameSchedule(full.newSchedule, delta.newSchedule))
                    assertEquals("$f $m", full.report?.weightedScore, delta.report?.weightedScore)
                    assertEquals("$f $m", full.logs.map { it.message }, delta.logs.map { it.message })
                    compared++
                } finally {
                    DeltaScreen.enabled = saved
                }
            }
        }
        assertEquals(fixtures.size * ObjectiveMethod.entries.size, compared)
    }

    @Test fun c1BeamReturnsTheSameBoardWithAndWithoutTheScreen() {
        fun noMs(logs: List<MirrorLog>) = logs.map { it.message.replace(Regex("/\\d+ms"), "/_ms") }
        for (m in ObjectiveMethod.entries) withMethod(m) {
            for (f in fixtures) {
                val st = load(f)
                val sched = st.schedule.toIntArray2D()
                val saved = DeltaScreen.enabled
                try {
                    DeltaScreen.enabled = false
                    val full = C1WindowPolish.applyC1BeamPolish(st, sched, maxSteps = 12)
                    DeltaScreen.enabled = true
                    val delta = C1WindowPolish.applyC1BeamPolish(st, sched, maxSteps = 12)
                    assertTrue("$f $m", AdaptiveEliteArchive.sameSchedule(full.newSchedule, delta.newSchedule))
                    assertEquals("$f $m", full.report?.breakdown, delta.report?.breakdown)
                    assertEquals("$f $m", noMs(full.logs), noMs(delta.logs))
                } finally {
                    DeltaScreen.enabled = saved
                }
            }
        }
    }
}
