package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.255.0, 受領・検証のうえ適用] PersonalBalanceJointLnsPolish単体の検証。実データ(golden_state.json/
 * sample_state_v6.json、ホストJVM実行)で、既存パイプライン適用後にも追加で改善を見つけること
 * (sample_state_v6.jsonでpersonal 34->31・total 196->195)を確認済み。
 */
class PersonalBalanceJointLnsPolishTest {
    @Test
    fun resolvesSimpleLowDeficiencyWithoutFairSideEffect() {
        // 2職員を別々の単独群(G0/G1)にする＝fair(群内公平化)の巻き添えを避ける
        // （2人共有群だとこの規模ではfairが同時に動いてtotalが改善しない中立トレードになる）。
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"))
        val staff = listOf(Staff("a", 0), Staff("b", 1))
        val a = listOf(1, 1, 0, 0, 0)
        val b = listOf(0, 0, 0, 0, 0)
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-05",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1), listOf(1, 1)),
            groupShiftApt = listOf(listOf("", ""), listOf("", "")),
            schedule = listOf(a, b), wishes = emptyMap(),
            staffRange = mapOf("0,1" to Range("4", "")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(2, before.breakdown["low"] ?: 0)
        assertEquals(0, before.hard)

        val out = PersonalBalanceJointLnsPolish.apply(
            st, sched,
            PersonalBalanceJointLnsPolish.Config(maxMillis = 2000L, maxRestarts = 2, maxDepth = 3),
        )
        val after = UnifiedViolationChecker.check(st, out.newSchedule)
        assertEquals(0, after.breakdown["low"] ?: -1)
        assertEquals(0, after.hard)
        assertTrue("何らかの手が採用されている", out.applied > 0)
        assertTrue("totalが真に改善する", after.total < before.total)
    }

    @Test
    fun isNoOpWhenNoRangeOrAptConfigured() {
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
        val out = PersonalBalanceJointLnsPolish.apply(st, sched)
        assertEquals(0, out.applied)
    }

    /** [3.654.0/外部レビュー] 自己日交換・クロス日移送の禁止の並びは、交換後の盤面で見る。旧: 交換前の盤面で 1 セルずつ見ており、
     *  「2 日目に X」だけなら X→X になるが、1 日目を Y へ戻す交換後は並びが無い手を捨てていた（期間の制約 c1 が残った）。 */
    @Test
    fun selfDaySwapIsJudgedOnTheBoardAfterBothCellsChange() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-03",
            shifts = shifts, groups = listOf(Group("G", "G")), staff = listOf(Staff("a", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1, 0, 0)),   // X, Y, Y
            wishes = mapOf("0,2" to 0),           // 3 日目は Y の希望
            staffRange = mapOf("0,1" to Range("2", "")),   // X の下限 2（構造的には 1 回まで＝下限割れ 1 が残る）
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(com.magi.app.model.C1Row(day1 = "2", shiftKigou = "X", day2 = "1")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(com.magi.app.model.C3Row(listOf("X", "X"))), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["c1"])
        assertEquals(1, before.breakdown["low"])
        val out = PersonalBalanceJointLnsPolish.apply(st, sched)
        val after = UnifiedViolationChecker.check(st, out.newSchedule)
        assertEquals(listOf(0, 1, 0), out.newSchedule[0].toList())   // Y, X, Y
        assertEquals(0, after.breakdown["c1"])
        assertEquals(0, after.hard)
    }
}
