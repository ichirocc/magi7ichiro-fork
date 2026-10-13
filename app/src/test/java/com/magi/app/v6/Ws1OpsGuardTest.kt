package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** グループ追加とシフト削除で「担当できるシフトが無いグループ」を作らない（保存後の読み込みで拒否される状態）。 */
class Ws1OpsGuardTest {
    private fun st(rest: Boolean) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-02",
        shifts = listOf(Shift("日勤", "日", "1", ""), Shift("休み", "休", "", "", if (rest) com.magi.app.model.ShiftRole.Rest else com.magi.app.model.ShiftRole.None)),
        groups = listOf(Group("G", "G"), Group("H", "H")), staff = listOf(Staff("a", 0), Staff("b", 1)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1), listOf(1, 0)), groupShiftApt = emptyList(),
        schedule = listOf(listOf(0, 1), listOf(0, 0)), wishes = emptyMap(), staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun addedGroupCanDoAShiftEvenWithoutRestRole() {
        val added = Ws1Ops.addGroup(st(rest = false), "N", "N")
        assertTrue("休みの役割が無くても先頭シフトを担当にする", added.groupShift.last().any { it == 1 })
        assertEquals(listOf(0, 1), Ws1Ops.addGroup(st(rest = true), "N", "N").groupShift.last())   // 休みがあれば休み
    }

    @Test fun removingTheOnlyShiftOfAGroupIsRefused() {
        val s = st(rest = true)
        assertEquals(1, Ws1Ops.groupLeftWithoutShift(s, 0))   // H は日勤だけ
        val sched = arrayOf(intArrayOf(0, 1), intArrayOf(0, 0))
        assertEquals(2, Ws1Ops.removeShift(s, sched, 0).state.shifts.size)   // 消さない
        assertNull(Ws1Ops.groupLeftWithoutShift(s, 1))
        assertEquals(1, Ws1Ops.removeShift(s, sched, 1).state.shifts.size)   // 休みは消せる（H は日勤が残る）
    }
}
