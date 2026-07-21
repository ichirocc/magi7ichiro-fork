package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [初期解生成(賢い版), 新設] `SmartInitialScheduler`単体の検証。
 * 「希望→C1→必要人数→個人下限→残り埋め」の順で、既存`GreedyMirrorScheduler`(C1非考慮)より
 * C1充足に優れることを確認する。
 */
class SmartInitialSchedulerTest {
    private fun blankState(cons1: List<C1Row> = emptyList()): MagiState = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-11",
        shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", "")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("a", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(List(11) { -1 }),
        wishes = emptyMap(),
        staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = cons1, cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test
    fun satisfiesC1FromBlankWhereGreedyMirrorSchedulerFails() {
        val st = blankState(cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")))

        val smart = SmartInitialScheduler.generate(st)
        assertEquals(0, smart.report.breakdown["c1"] ?: -1)
        assertEquals(0, smart.report.hard)

        // 対照: 既存の簡易作成(C1非考慮)は同じ盤面でc1を解消できない
        // （restBonusにより全セルが休へ倒れ、Xが1つも配置されないため）。
        val naive = GreedyMirrorScheduler.generate(st)
        assertTrue("既存の簡易作成はC1を考慮しないため違反が残るはず", (naive.report.breakdown["c1"] ?: 0) > 0)
    }

    @Test
    fun respectsFeasibleWish() {
        val st = blankState(cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")))
            .let { it.copy(wishes = mapOf("0,2" to 1)) }   // shift index 1 = "X"

        val result = SmartInitialScheduler.generate(st)
        assertEquals(1, result.newScheduleXAt(0, 2))
    }

    @Test
    fun isNoOpFriendlyWhenNoCons1Rules() {
        val st = blankState()
        val result = SmartInitialScheduler.generate(st)
        // C1規則が無くても正常に完成盤面を返す(空きセルが残らない)。
        assertTrue(result.schedule[0].all { it in 0..1 })
    }

    @Test
    fun keepsExistingScheduleWhenMostlyFilled() {
        // 11日中6日(過半数)を希望と無関係な値で埋めた状態は「既存表ベース」として保持される。
        val filled = listOf(0, 0, 0, 1, 1, 1, -1, -1, -1, -1, -1)
        val st = blankState(cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")))
            .let { it.copy(schedule = listOf(filled)) }

        val result = SmartInitialScheduler.generate(st)
        for (j in 0 until 6) assertEquals(filled[j], result.schedule[0][j])
    }

    private fun ScheduleRunResult.newScheduleXAt(staff: Int, day: Int): Int = schedule[staff][day]
}
