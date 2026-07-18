package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorEngineTest {
    private fun buildState(): MagiState {
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("A", "A", "1", "2"),
            Shift("B", "B", "1", "1"),
            Shift("C", "C", "1", ""),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"))
        val staff = listOf(Staff("s0", 0), Staff("s1", 0), Staff("s2", 1), Staff("s3", 1))
        val schedule = listOf(
            listOf(0, 1, 2, 0, 1, 0, 2),
            listOf(1, 0, 0, 2, 1, 2, 0),
            listOf(0, 2, 3, 0, 2, 0, 3),
            listOf(3, 0, 2, 3, 0, 2, 0),
        )
        return MagiState(
            startDate = "2025-01-01",
            endDate = "2025-01-07",
            shifts = shifts,
            groups = groups,
            staff = staff,
            use2Patterns = true,
            groupShift = listOf(listOf(1, 1, 1, 0), listOf(1, 0, 1, 1)),
            groupShiftApt = listOf(listOf("", "", "", ""), listOf("", "", "", "")),
            schedule = schedule,
            wishes = mapOf("0,0" to 0, "1,4" to 1, "2,2" to 3),
            staffRange = mapOf("0,1" to Range("2", "4"), "1,2" to Range("", "3"), "3,3" to Range("1", "")),
            needDay1 = mapOf("1,0" to "2"),
            needDay2 = mapOf("2,5" to "2"),
            cons1 = listOf(C1Row("3", "A", "1")),
            cons2 = listOf(C2Row("B", "2")),
            cons3 = listOf(C3Row(listOf("A", "B"))),
            cons3n = listOf(C3Row(listOf("C", "C"))),
            cons3m = listOf(C3Row(listOf("B", "A"))),
            cons3mn = listOf(C3Row(listOf("A", "A"))),
            cons41 = listOf(C41Row("G0", "A", "", "2")),
            cons42 = listOf(C42Row("G0", "G1", "A", "C")),
        )
    }

    @Test
    fun unifiedCheckReturnsCompleteBreakdown() {
        val st = buildState()
        val report = UnifiedViolationChecker.check(st)
        assertEquals(MirrorKeys.all.toSet(), report.breakdown.keys)
        assertEquals(report.total, report.breakdown.values.sum())
        assertEquals(report.hard, MirrorKeys.hard.sumOf { report.breakdown[it] ?: 0 })
    }

    @Test
    fun csvRoundTripKeepsScheduleSymbols() {
        val st = buildState()
        val csv = ScheduleCsvBridge.build(st, st.schedule.toIntArray2D())
        val parsed = ScheduleCsvBridge.parse(csv, st, Array(st.staffCount) { IntArray(st.dayCount) })
        assertEquals(st.schedule, parsed.schedule.map { it.toList() })
        assertTrue(parsed.report.logs.first().message.contains("staff一致"))
    }

    @Test
    fun greedyAndLightOptimizerProduceValidDimensions() {
        val st = buildState()
        val generated = GreedyMirrorScheduler.generate(st)
        assertEquals(st.staffCount, generated.schedule.size)
        assertEquals(st.dayCount, generated.schedule[0].size)
        val opt = LightMirrorOptimizer.optimize(st, generated.schedule, seconds = 0.1, seed = 1)
        assertEquals(st.staffCount, opt.schedule.size)
        assertEquals(st.dayCount, opt.schedule[0].size)
    }

    // [防御的統一/敵対的監査] markCount(countViolations) が mark/markNeed と同じ重み優先で解決することを
    // 固定する。旧・無条件上書き実装は c2(重み1)→low(重み90) の呼出順に依存して偶然に正しかった
    // （呼出順は現状のソースでは固定だがそれ自体が地雷＝将来の族追加/並べ替えで壊れうる）。この回帰
    // テストは「同一セルで複数族が重なったとき常に最重の族が表示される」という不変条件を固定する。
    @Test
    fun countViolationsPrefersHeavierFamilyOverLighterAtSameCell() {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("s0", 0))
        // X を1回しか勤務していない: cons2(count>=3)とstaffRange低(lo=3)の両方が同一セル(0,1=staff0,shift X)で発火。
        val schedule = listOf(listOf(1, 0, 0, 0))
        val st = MagiState(
            startDate = "2025-01-01", endDate = "2025-01-04",
            shifts = shifts, groups = groups, staff = staff,
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = schedule,
            wishes = emptyMap(),
            staffRange = mapOf("0,1" to Range("3", "")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(),
            cons2 = listOf(C2Row("X", "3")),
            cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val report = UnifiedViolationChecker.check(st)
        assertEquals(1, report.breakdown["c2"])
        assertEquals(2, report.breakdown["low"])   // lo(3) - got(1) = 2
        assertEquals("vio-low", report.countViolations["0,1"])   // 重い族(low=90)が軽い族(c2=1)を上書きしない/されない
    }
}
