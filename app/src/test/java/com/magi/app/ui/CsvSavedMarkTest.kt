package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.ScheduleCsvBridge
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** backlog#46 T02: 変更→CSV 保存→元に戻す→再検査→保存一致表示（C# `MagiViewModelCsvTest` の CsvSavedLine 2 件と同じ筋）。 */
class CsvSavedMarkTest {
    private val st: MagiState =
        StateParser.parse(javaClass.getResourceAsStream("/sample_state_v6.json")!!.bufferedReader().readText())!!
    private fun board() = st.schedule.toIntArray2D()
    private fun flipped(b: Array<IntArray>): Array<IntArray> =
        Array(b.size) { b[it].copyOf() }.also { it[0][0] = if (it[0][0] == 0) 1 else 0 }

    @Test
    fun savedLineIsBoundToTheExportedContent() {
        val m = CsvSavedMark()
        val b = board()
        m.noteExport(st, b, ScheduleCsvBridge.build(st, b))
        m.noteSaved(st, b)
        assertTrue(m.line("10:00", st, b)!!.startsWith("この内容で勤務表CSVを保存済みです（10:00"))
    }

    @Test
    fun savedLineDistinguishesTheSavedContentFromTheCurrentOne() {
        val m = CsvSavedMark()
        val b = board()
        m.noteExport(st, b, ScheduleCsvBridge.build(st, b))
        val changed = flipped(b)   // 書き出しのあと、書き込みが終わる前の変更
        m.noteSaved(st, changed)
        assertTrue(m.line("10:00", st, changed)!!.contains("今の内容と違います"))
        m.clear()
        assertNull(m.line("10:00", st, changed))
    }

    @Test
    fun changeSaveUndoRecheckShowsSavedMatch() {
        val m = CsvSavedMark()
        val b0 = board()
        val b1 = flipped(b0)   // 変更
        m.noteExport(st, b1, ScheduleCsvBridge.build(st, b1))
        m.noteSaved(st, b1)    // CSV 保存
        assertEquals(true, m.savedMatches(st, b1))
        assertEquals(false, m.savedMatches(st, b0))   // 元に戻す → 保存した内容と違う
        assertTrue(m.line("10:00", st, b0)!!.contains("今の内容と違います"))
        val redone = flipped(b0)   // やり直し（別の配列・同じ内容）→ 再検査で保存一致
        assertEquals(true, m.savedMatches(st, redone))
        val settingsOnly = st.copy()   // 設定だけの変更（別の state・同じ CSV）も一致
        assertEquals(true, m.savedMatches(settingsOnly, redone))
        assertNull(m.line(null, st, redone))
        assertEquals(false, m.savedMatches(null, redone))
    }
}
