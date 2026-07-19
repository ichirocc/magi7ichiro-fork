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
 * [RangePolish・玉突き連鎖の横展開その2] grilling不要(C3mnPolishと同型、ユーザー承認2026-07-19)で
 * 実装した個人回数(low/high)研磨の検証。桒澤美幸の実例（Aｱ超過・B1担当が全職員中唯一で交換相手が
 * 構造的に存在しない）を最小盤面で再現する: A(職員)がshift X(need1=1)を超過(high)。Aが自身のX保有日を
 * 別シフト(Y)へ動かすだけではXの被覆が欠けるため、直接の自己修正は成立しない。B(在勤中のZ、需要なし=
 * いつでも動かせる)がXへ玉突きで補充することで初めて解消する局面。
 */
class RangePolishTest {

    private fun highState(): MagiState {
        // shift: 0=休(need無) 1=X(need1=1) 2=Y(need無、Aの逃げ先) 3=Z(need無、Bの現在地)
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("X", "X", "1", ""),
            Shift("Y", "Y", "", ""),
            Shift("Z", "Z", "", ""),
        )
        val groups = listOf(Group("GA", "GA"), Group("GB", "GB"))
        val groupShift = listOf(
            listOf(1, 1, 1, 0), // GA(A)=休,X,Y
            listOf(1, 1, 0, 1), // GB(B)=休,X,Z
        )
        val staff = listOf(Staff("A", 0), Staff("B", 1))
        val schedule = listOf(
            listOf(1, 1), // A = X, X （高hi=1に対し2回＝超過1）
            listOf(3, 3), // B = Z, Z （需要なし=いつでも動かせる）
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-02",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift,
            groupShiftApt = List(2) { List(4) { "" } },
            schedule = schedule,
            wishes = emptyMap(),
            staffRange = mapOf("0,1" to Range("0", "1")), // A の shift X: lo=0, hi=1
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun rangePolishResolvesHighViaChainWhenDirectSelfChangeWouldCreateCovU() {
        val st = highState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はhigh違反があること", (before.breakdown["high"] ?: 0) > 0)
        assertEquals("初期はHARD=0(covU無し、AがXを単独充足)", 0, before.hard)

        val result = applyRangePolish(st, sched, seed = 1L)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("玉突き適用後はhigh=0", 0, after.breakdown["high"] ?: -1)
        assertEquals("HARDは悪化しない(0のまま)", 0, after.hard)
        assertEquals("Xの被覆(covU)は悪化しない", 0, after.breakdown["covU"] ?: -1)
        assertTrue("実際に手が採用されている", result.applied > 0)
    }

    @Test
    fun rangePolishIsNoOpWhenNoStaffRange() {
        val st = highState().copy(staffRange = emptyMap())
        val sched = st.schedule.toIntArray2D()
        val result = applyRangePolish(st, sched, seed = 1L)
        assertEquals(0, result.applied)
    }
}
