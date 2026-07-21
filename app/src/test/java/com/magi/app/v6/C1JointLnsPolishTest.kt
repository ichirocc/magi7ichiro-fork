package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.255.0, 受領・検証のうえ適用] C1JointLnsPolish単体の検証。実データ(golden_state.json/
 * sample_state_v6.json、ホストJVM実行)で、既存パイプライン(Window+C1TemporalFlowPolish+BeamWide等)
 * 適用後にも追加で改善を見つけること(sample_state_v6.jsonでHARD 5->4)を確認済み。
 */
class C1JointLnsPolishTest {
    @Test
    fun resolvesSimpleC1DeficiencyWithSameDaySwapPartner() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G", "G"))
        val staff = listOf(Staff("target", 0), Staff("partner", 0))
        val target = listOf(1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0)
        val partner = target.map { if (it == 1) 0 else 1 }
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-11",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = listOf(target, partner),
            wishes = emptyMap(),
            // [3.256.0, 厳密ピン保護追加に伴う訂正] 実際に見つかる同日swap束は target の X 回数を
            //   4→6 へ変える（窓[6-10]の充足には既存4回の再配置でなく純増が必要と判明・手計算で確認済み）。
            //   旧 Range("4","4")（意図せぬ厳密ピン）は新設の exactPinRegression 保護に正しく拒否される
            //   ため、本テストの主旨（同日swap束によるc1解消）に無関係な下限4のみへ緩和。
            staffRange = mapOf("0,1" to Range("4", "")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["c1"] ?: 0)
        assertEquals(0, before.hard)

        val out = C1JointLnsPolish.apply(
            st, sched,
            C1JointLnsPolish.Config(maxMillis = 2000L, maxRestarts = 2, maxDepth = 3),
        )
        val after = UnifiedViolationChecker.check(st, out.newSchedule)
        assertEquals(0, after.breakdown["c1"] ?: -1)
        assertEquals(0, after.hard)
        assertTrue("何らかの手が採用されている", out.applied > 0)
        assertTrue("totalが真に改善する", after.total < before.total)
    }

    @Test
    fun isNoOpWhenNoCons1Rules() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G", "G"))
        val staff = listOf(Staff("a", 0))
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-03",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(0, 1, 0)), wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val out = C1JointLnsPolish.apply(st, sched)
        assertEquals(0, out.applied)
    }
}
