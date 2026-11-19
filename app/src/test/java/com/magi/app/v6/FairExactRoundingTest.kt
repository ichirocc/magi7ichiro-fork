package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** fair の偏差はちょうど半分を切り上げる（分数で計算、3.727.107）。浮動小数では 3.5 が 3.4999… になり 3 へ切り捨てられていた。 */
class FairExactRoundingTest {
    @Test fun halfDeviationRoundsUp() {
        // A: 帯 [0,3] で 0 回＝達成率 −1・幅 1.5。B: 目安 3 で 7 回＝達成率 4/3・幅 3（中央値＝B）。A の偏差＝(4/3+1)×1.5＝3.5 → 4。
        val st = MagiState(
            startDate = "2026-12-01", endDate = "2026-12-14",
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("X", "X", "", "")),
            groups = listOf(Group("G0", "G0")), staff = listOf(Staff("A", 0), Staff("B", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "3")),
            schedule = listOf(List(14) { 0 }, List(14) { if (it < 7) 1 else 0 }),
            wishes = emptyMap(), staffRange = mapOf("0,1" to Range("0", "3")), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = Problem(st)
        assertEquals(3, p.apt[1][1])
        val counts = intArrayOf(0, 7)
        assertEquals(4 + 4, p.fairDevOfBucket(0, 1) { counts[it] }.total)   // 偏差の和 4 ＋ 最大の偏差 4
    }
}
