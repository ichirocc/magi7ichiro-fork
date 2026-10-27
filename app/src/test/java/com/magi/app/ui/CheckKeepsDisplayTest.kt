package com.magi.app.ui

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.ManualPin
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import com.magi.app.v6.ShiftAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 走行中の検査の結果が、その後の手動固定・表示色（検査を回さない 2 経路）を画面から巻き戻さない（[keepDisplayOf]）。 */
class CheckKeepsDisplayTest {
    private val checked = MagiState(
        startDate = "2026-10-01", endDate = "2026-10-03",
        shifts = listOf(Shift("休", "休", "", "", role = ShiftRole.Rest), Shift("日勤", "A", "1", "")),
        groups = listOf(Group("一般", "N")), staff = listOf(Staff("甲", 0), Staff("乙", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = List(2) { List(3) { 0 } },
        wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )
    private val fromCheck = UiState().withShiftColors(checked).withWishDisplay(checked)

    @Test fun pinAndColorChangedDuringTheCheckSurviveItsResult() {
        // 検査の後にセルを変えた盤面（state.schedule）に固定を付け、色を変えた。
        val now = checked.copy(schedule = listOf(listOf(1, 0, 0), listOf(0, 0, 0)),
            manualPins = listOf(ManualPin(0, 0, 1)), shiftColors = mapOf("A" to "#112233"))
        val u = fromCheck.keepDisplayOf(checked, now)
        assertEquals(setOf(VioKey.cell(0, 0)), u.manualPins)
        assertEquals(ShiftAppearance.resolveShiftColor("#112233", 1), u.shiftColorHex[1])
        assertTrue("検査を始める前の表示は固定なし", fromCheck.manualPins.isEmpty())
    }

    @Test fun otherChangesAndNoChangeLeaveTheCheckResultAlone() {
        assertSame(fromCheck, fromCheck.keepDisplayOf(checked, checked))
        assertSame(fromCheck, fromCheck.keepDisplayOf(checked, null))
        // 希望の変更は採点に効く＝検査の世代で古い結果ごと捨てる側。ここでは触らない。
        val wished = checked.copy(wishes = mapOf("0,0" to 1), manualPins = listOf(ManualPin(0, 0, 1)))
        assertSame(fromCheck, fromCheck.keepDisplayOf(checked, wished))
    }

    @Test fun pushReportAppliesItWithTheCurrentState() {
        val dir = listOf("app/src/main/java/com/magi/app/ui", "src/main/java/com/magi/app/ui").map(::File).first { it.isDirectory }
        // pushReport は全面改修 P6f で `MagiViewModelDiagnostics.kt` の拡張関数へ移った（本体の外）。
        val vm = File(dir, "MagiViewModelDiagnostics.kt").readText()
        val body = vm.substringAfter("internal suspend fun MagiViewModel.pushReport(").substringBefore("\n}\n")
        assertTrue("検査の結果を反映する CAS が今の state で手動固定・表示色を残す", body.contains(".keepDisplayOf(st, state)"))
    }
}
