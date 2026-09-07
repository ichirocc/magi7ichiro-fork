package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Test

/** 群目標（apt）を個人 [lo,hi] に加えて「構造的に到達できる範囲」へ収める（桒澤 B4 の幻の超過の恒久対策）。 */
class AptReachClampTest {
    private val REST = 0; private val A = 1; private val B = 2

    private fun state(ranges: Map<String, Range>, aptA: String, wishes: Map<String, Int> = emptyMap()) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-05",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", aptA, "")),
        schedule = listOf(listOf(REST, REST, REST, REST, REST)), wishes = wishes, staffRange = ranges,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun targetRisesToTheDaysThatMustLandOnTheShift() {
        // 休 2〜2・B 0〜0 → 5 日のうち 3 日は A にしか置けない。群目標 1 は到達不能なので 3 へ。
        val st = state(mapOf("0,$REST" to Range("2", "2"), "0,$B" to Range("0", "0")), "1")
        assertEquals(3, Problem(st).apt[0][A])
    }

    @Test fun unboundedOtherShiftLeavesTheTargetAlone() {
        val st = state(mapOf("0,$REST" to Range("2", "2")), "1")
        assertEquals(1, Problem(st).apt[0][A])
    }

    @Test fun wishPinnedCellsCountAsLowerBoundsOfOtherShifts() {
        // 休の希望固定 3 日（範囲なし）→ A に使える日は最大 2。群目標 4 は 2 へ。
        val st = state(emptyMap(), "4", wishes = mapOf("0,0" to REST, "0,1" to REST, "0,2" to REST))
        assertEquals(2, Problem(st).apt[0][A])
    }

    @Test fun personalRangeWinsWhenTheReachContradictsIt() {
        // 到達下限は 3 だが個人上限 1 と矛盾 → 従来どおり個人設定でクランプ（1）。
        val st = state(mapOf("0,$REST" to Range("2", "2"), "0,$B" to Range("0", "0"), "0,$A" to Range("", "1")), "1")
        assertEquals(1, Problem(st).apt[0][A])
    }
}
