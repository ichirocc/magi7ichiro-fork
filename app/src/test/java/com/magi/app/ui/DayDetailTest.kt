package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 日の詳細（日ヘッダのシート）の行: 入っている職員・必要人数との対比・日送りの端。 */
class DayDetailTest {
    private val sym = listOf("休", "Pｼ", "Dﾃ", "Aｱ")
    // 職員 0〜4 の 1 日目（添字 0）だけを使う。
    private val sched = listOf(listOf(1), listOf(1), listOf(2), listOf(0), listOf(3))
    private val limits = { k: Int, _: Int -> when (k) { 1 -> 2 to 2; 2 -> 2 to 2; 3 -> 0 to 0; else -> null } }

    @Test fun rowsShowWhoIsOnEachShiftAndTheNeed() {
        val rows = daySlotRows(sched, sym, 0, limits)
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.shift })
        assertEquals(listOf(0, 1), rows[1].staff)
        assertEquals("休　1人", rows[0].line)
        assertEquals("Pｼ　2/2人　充足", rows[1].line)
        assertEquals("Dﾃ　1/2人　1人不足", rows[2].line)
        assertEquals("Aｱ　1人（適正 0人）　1人多い", rows[3].line)
        assertEquals(listOf(DaySlotState.NONE, DaySlotState.OK, DaySlotState.UNDER, DaySlotState.OVER), rows.map { it.state })
    }

    @Test fun shiftsWithNobodyAndNoNeedAreLeftOut() {
        val rows = daySlotRows(listOf(listOf(1)), sym, 0) { _, _ -> null }
        assertEquals(listOf(1), rows.map { it.shift })
    }

    @Test fun aNeededShiftWithNobodyStillShows() {
        val rows = daySlotRows(listOf(listOf(0)), sym, 0) { k, _ -> if (k == 2) 1 to 1 else null }
        assertEquals("Dﾃ　0/1人　1人不足", rows.single { it.shift == 2 }.line)
    }

    @Test fun dayStepStopsAtTheEdges() {
        assertNull(dayStep(0, -1, 30))
        assertEquals(1, dayStep(0, 1, 30))
        assertNull(dayStep(29, 1, 30))
    }
}
