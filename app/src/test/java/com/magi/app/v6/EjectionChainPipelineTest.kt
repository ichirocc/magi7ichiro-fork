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

/** 玉突き連鎖パイプライン。予察で当たりが無ければ深い探索をしない（不変条件）。 */
class EjectionChainPipelineTest {
    /** 2 人×3 日。s0 は夜勤だけ・s1 は日勤だけで、どちらも c1（3 日に日勤・夜勤を各 1 以上）を欠く。同日の入れ替えで直る。 */
    private fun swapState(schedule: List<List<Int>> = listOf(listOf(2, 2, 2), listOf(1, 1, 1))): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-03",
        shifts = listOf(Shift("休み", "休", "", "", ShiftRole.Rest), Shift("日勤", "D", "1", ""), Shift("夜勤", "N", "1", "")),
        groups = listOf(Group("G0", "G0")), staff = listOf(Staff("s0", 0), Staff("s1", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = listOf(C1Row("3", "D", "1"), C1Row("3", "N", "1")), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    /** 1 人×3 日。日勤の必要 1 を毎日 s0 だけが満たすので、c1（夜勤 1 以上）を直すと必ず人員不足になる＝直す手順が無い。 */
    private fun wallState(): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-03",
        shifts = listOf(Shift("休み", "休", "", "", ShiftRole.Rest), Shift("日勤", "D", "1", ""), Shift("夜勤", "N", "", "")),
        groups = listOf(Group("G0", "G0")), staff = listOf(Staff("s0", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(listOf(1, 1, 1)), wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = listOf(C1Row("3", "N", "1")), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    /** 2 人×3 日。日勤の必要 1 が 3 日目だけ欠ける（人員不足 1）。3 日目の誰かを日勤にすれば直る。 */
    private fun coverState(): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-03",
        shifts = listOf(Shift("休み", "休", "", "", ShiftRole.Rest), Shift("日勤", "D", "1", ""), Shift("夜勤", "N", "", "")),
        groups = listOf(Group("G0", "G0")), staff = listOf(Staff("s0", 0), Staff("s1", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(listOf(1, 1, 2), listOf(2, 2, 0)), wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun run(st: MagiState, focus: EjectionChainPipeline.Focus, tel: MutableList<EjectionChainPipeline.Telemetry>,
        repeatRounds: Boolean = false, hardLeg: Boolean = false) =
        EjectionChainPipeline.apply(st, st.schedule.toIntArray2D(),
            EjectionChainPipeline.Config(focus = focus, deterministic = true, repeatRounds = repeatRounds, hardLeg = hardLeg), telemetry = tel)

    @Test fun probeHitIsCommittedAndDeepSearchRunsOnlyWithHits() {
        val st = swapState()
        val rep0 = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
        val tel = ArrayList<EjectionChainPipeline.Telemetry>()
        val r = run(st, EjectionChainPipeline.Focus.C1, tel)
        val t = tel.single()
        assertTrue(t.line(), t.shallowHits + t.midHits > 0)
        assertTrue(t.line(), t.deepRan)
        assertTrue(t.line(), t.committed > 0)
        assertEquals("committed", t.endReason)
        assertTrue(betterReport(r.report!!, rep0))
        assertTrue(r.report!!.hard <= rep0.hard)
        assertEquals(UnifiedViolationChecker.check(st, r.newSchedule).weightedScore, r.report!!.weightedScore, 0.0)
    }

    @Test fun probeMissSkipsTheDeepSearch() {
        val st = wallState()
        val s0 = st.schedule.toIntArray2D()
        assertTrue((UnifiedViolationChecker.check(st, s0).breakdown["c1"] ?: 0) > 0)
        val tel = ArrayList<EjectionChainPipeline.Telemetry>()
        val r = run(st, EjectionChainPipeline.Focus.C1, tel)
        val t = tel.single()
        assertTrue(t.line(), t.seeds > 0)
        assertEquals(0, t.shallowHits + t.midHits)
        assertFalse(t.line(), t.deepRan)
        assertEquals("probe_miss", t.endReason)
        assertTrue(r.newSchedule.contentDeepEquals(s0))
    }

    @Test fun noResidualDoesNothing() {
        val st = swapState(listOf(listOf(1, 2, 1), listOf(2, 1, 2)))
        val s0 = st.schedule.toIntArray2D()
        assertEquals(0, UnifiedViolationChecker.check(st, s0).breakdown["c1"] ?: 0)
        val tel = ArrayList<EjectionChainPipeline.Telemetry>()
        val r = run(st, EjectionChainPipeline.Focus.C1, tel)
        assertEquals("no_residual", tel.single().endReason)
        assertFalse(tel.single().deepRan)
        assertTrue(r.newSchedule.contentDeepEquals(s0))
    }

    @Test fun deterministicRunsAreReproducible() {
        val st = swapState()
        val a = run(st, EjectionChainPipeline.Focus.BOTH, ArrayList())
        val b = run(st, EjectionChainPipeline.Focus.BOTH, ArrayList())
        assertTrue(a.newSchedule.contentDeepEquals(b.newSchedule))
        val noMs = Regex("\\d+ms")
        assertEquals(a.logs.map { it.message.replace(noMs, "ms") }, b.logs.map { it.message.replace(noMs, "ms") })
    }

    // BOTH は C1→SOFT。SOFT 側の残差は c1 を除いた数（c1 は C1 側が受け持つ）。
    @Test fun bothRunsC1ThenSoftWithoutC1() {
        val st = swapState()
        val tel = ArrayList<EjectionChainPipeline.Telemetry>()
        run(st, EjectionChainPipeline.Focus.BOTH, tel)
        assertEquals(listOf("C1", "SOFT"), tel.map { it.focus })
        val soft = tel[1]
        val b = soft.before!!
        assertEquals(b.total - b.hard - (b.breakdown["c1"] ?: 0), soft.residual)
        for (t in tel) assertTrue(t.line(), !t.deepRan || t.shallowHits + t.midHits > 0)
    }

    // 探し直し: 採用があった巡のあとだけ次の巡を回し、採用が無い巡で止まる。2 巡目以降の記録行に「巡N」。
    @Test fun repeatRoundsReindexesOnlyAfterACommit() {
        val st = swapState()
        val one = run(st, EjectionChainPipeline.Focus.C1, ArrayList())
        val tel = ArrayList<EjectionChainPipeline.Telemetry>()
        val r = run(st, EjectionChainPipeline.Focus.C1, tel, repeatRounds = true)
        assertEquals(listOf(1, 2), tel.map { it.round })
        assertTrue(tel[0].line(), tel[0].committed > 0)
        assertEquals("no_residual", tel[1].endReason)
        assertTrue(tel[1].line().contains("巡2 "))
        assertFalse(tel[0].line().contains("巡1"))
        assertTrue(r.newSchedule.contentDeepEquals(one.newSchedule))
    }

    // 必須の焦点: BOTH の先頭に走り、人員不足を起点に直す。既定（OFF）では必須の焦点を走らせない。
    @Test fun hardLegRunsFirstAndRepairsARemainingHard() {
        val st = coverState()
        assertEquals(1, UnifiedViolationChecker.check(st, st.schedule.toIntArray2D()).hard)
        val offTel = ArrayList<EjectionChainPipeline.Telemetry>()
        run(st, EjectionChainPipeline.Focus.BOTH, offTel)
        assertEquals(listOf("C1", "SOFT"), offTel.map { it.focus })
        val tel = ArrayList<EjectionChainPipeline.Telemetry>()
        val r = run(st, EjectionChainPipeline.Focus.BOTH, tel, hardLeg = true)
        assertEquals(listOf("HARD", "C1", "SOFT"), tel.map { it.focus })
        assertEquals(1, tel[0].residual)
        assertTrue(tel[0].line(), tel[0].committed > 0)
        assertEquals(0, r.report!!.hard)
        assertEquals(UnifiedViolationChecker.check(st, r.newSchedule).weightedScore, r.report!!.weightedScore, 0.0)
    }

    // 有効なとき、同じ位置の従来の玉突きは走らせない。
    @Test fun postChainRunsThePipelineInsteadOfTheOldChain() {
        val st = swapState()
        val oldC1 = PolishGate.c1EjectionChain
        val oldFocus = PolishGate.ejectionPipelineFocus
        try {
            PolishGate.c1EjectionChain = true
            PolishGate.ejectionPipelineFocus = EjectionChainPipeline.Focus.C1
            val r = V6HotfixPasses.runPostOptimization(st, st.schedule.toIntArray2D(), "pipe", seed = 3L,
                deadlineMs = EngineClock.nowMs() + 10_000L, params = V6HotfixPasses.PostOptimizationParams(deterministic = true))
            assertTrue(r.stageRecords.any { it.key == "玉突きパイプライン" })
            assertFalse(r.stageRecords.any { it.key == "C1玉突き連鎖" })
        } finally {
            PolishGate.c1EjectionChain = oldC1
            PolishGate.ejectionPipelineFocus = oldFocus
        }
    }
}
