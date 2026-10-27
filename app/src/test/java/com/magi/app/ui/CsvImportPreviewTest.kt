package com.magi.app.ui

import com.magi.app.v6.ScheduleRunResult
import com.magi.app.v6.ViolationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 勤務表 CSV を反映する前の確認: 3 点以内（確認ダイアログの上限）で、人数・変わるセル・必須の前後・注意を出す。 */
class CsvImportPreviewTest {
    private val base = arrayOf(intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(0, 0))
    private fun rep(hard: Int) = ViolationReport(emptyMap(), emptyMap(), emptyMap(), breakdown = emptyMap(), total = hard, hard = hard, soft = 0, weightedScore = 0.0)

    @Test fun cleanImportHasTwoPoints() {
        val res = ScheduleRunResult(arrayOf(intArrayOf(1, 1), intArrayOf(1, 1), intArrayOf(0, 2)), rep(0), matched = 3)
        assertEquals(listOf("3/3名の勤務を取り込みます（変わるセル 2）", "必須違反：2 → 0"), csvImportPreviewLines(res, 3, base, 2))
    }

    @Test fun warningsFoldIntoOneLineAndStayWithinTheDialogLimit() {
        val res = ScheduleRunResult(base, rep(1), matched = 1, unknownCells = 2, unknownSymbols = listOf("X"), headerDateMismatches = 1,
            ambiguousNames = listOf("佐藤"), duplicateRowNames = listOf("鈴木"))
        val lines = csvImportPreviewLines(res, 3, base, 1)
        assertTrue(lines.size <= CONFIRM_MAX_POINTS)
        assertEquals("1/3名の勤務を取り込みます（変わるセル 0）", lines[0])
        assertTrue(lines[2], lines[2].startsWith("⚠ 2名は氏名が合わずスキップ／読めない記号 2セル（X）"))
        assertTrue(lines[2], lines[2].contains("佐藤さん") && lines[2].contains("鈴木"))
    }
}
