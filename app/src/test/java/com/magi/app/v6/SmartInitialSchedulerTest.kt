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
    fun satisfiesMultipleC1RulesOnSameShiftSimultaneously() {
        // 同一シフト(休)に「5日窓≥1」と「14日窓≥4」の2規則を同時に課す
        // （CLAUDE.md記載の実運用例 cons1=[5日窓休≥1, 14日窓休≥4, ...] と同型の同一シフト複数規則）。
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-14",
            shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", "")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("a", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(List(14) { -1 }),
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(
                C1Row(day1 = "5", shiftKigou = "休", day2 = "1"),
                C1Row(day1 = "14", shiftKigou = "休", day2 = "4"),
            ),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val result = SmartInitialScheduler.generate(st)
        assertEquals(0, result.report.breakdown["c1"] ?: -1)
        assertEquals(0, result.report.hard)
        val restCount = result.schedule[0].count { it == 0 }
        assertTrue("14日窓規則(≥4)を満たすには休が4日以上必要", restCount >= 4)
    }

    @Test
    fun satisfiesC1RulesOnDifferentShiftsForSameStaff() {
        // 異なるシフト(A/B)に別々のC1規則を課すケース（複数規則がシフトをまたぐ場合）。
        // シフトindex順(A→B)で逐次構築するため、Aの決定がBの空き日を狭めるが、
        // 各規則が軽い(5日窓≥1)ため両立できることを確認する。
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-11",
            shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("a", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)),
            groupShiftApt = listOf(listOf("", "", "")),
            schedule = listOf(List(11) { -1 }),
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(
                C1Row(day1 = "5", shiftKigou = "A", day2 = "1"),
                C1Row(day1 = "5", shiftKigou = "B", day2 = "1"),
            ),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val result = SmartInitialScheduler.generate(st)
        assertEquals(0, result.report.breakdown["c1"] ?: -1)
        assertEquals(0, result.report.hard)
    }

    @Test
    fun respectsPersonalUpperLimitEvenWhenC1WindowRequiresMore() {
        // 実機ログ由来の構造的矛盾を最小再現: 「5日窓でXを2回以上」というC1規則は、
        // 10日間を通して満たすには複数回のX配置が要る（例: day2,4,6,8）。しかし本人の
        // 個人上限(staffRange hi=1)は1回までしか許さない。high(重み45)はc1(重み15)より
        // 重いため、C1充足のためだけに上限を超えてXを増やしてはならない。
        fun state(withCap: Boolean): MagiState = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-10",
            shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", "")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("a", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(List(10) { -1 }),
            wishes = emptyMap(),
            staffRange = if (withCap) mapOf("0,1" to Range(lo = "", hi = "1")) else emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )

        val cappedResult = SmartInitialScheduler.generate(state(withCap = true))
        val cappedX = cappedResult.schedule[0].count { it == 1 }
        assertTrue("個人上限(hi=1)を超えて割り当ててはならない: xCount=$cappedX", cappedX <= 1)

        // 対照: 上限が無ければC1充足のためもっと多くのXを割り当てる
        // （＝上限パラメータが実際に効いていることの確認、恒常的にno-opでないことの担保）。
        val uncappedResult = SmartInitialScheduler.generate(state(withCap = false))
        val uncappedX = uncappedResult.schedule[0].count { it == 1 }
        assertTrue(
            "上限が無ければcapped構成(${cappedX}件)より多くXを割り当てるはず: uncapped=$uncappedX",
            uncappedX > cappedX,
        )
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
