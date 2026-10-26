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

    @Test
    fun editingTheBoardInPlaceAfterExportDoesNotChangeWhatWasSaved() {
        // セルの編集は盤面の配列をその場で書き換える。書き出しの文脈は写しを持つ＝書き出しのあとの編集が混ざらない。
        val m = CsvSavedMark()
        val b = board()
        val original = Array(b.size) { b[it].copyOf() }
        val csv = ScheduleCsvBridge.build(st, b)
        m.noteExport(st, b, csv)
        b[0][0] = if (b[0][0] == 0) 1 else 0   // 書き込みが終わる前に、同じ配列へ編集
        m.noteSaved(st, b)
        assertEquals(false, m.savedMatches(st, b))
        assertEquals(true, m.savedMatches(st, original))   // 書き出した時の内容とは一致
    }

    @Test
    fun boardsWithTheSameFingerprintAreNotSavedMatchesByFingerprint() {
        // 盤面の指紋（h*31+v）が衝突する 2 盤面: [1,0] と [0,31] はどちらも 31 を足す。指紋だけで「同じ」と言わない。
        val m = CsvSavedMark { _, s -> s.joinToString(";") { it.joinToString(",") } }
        val a = arrayOf(intArrayOf(1, 0))
        val b = arrayOf(intArrayOf(0, 31))
        assertEquals(com.magi.app.v6.StateFingerprint.ofBoard(a), com.magi.app.v6.StateFingerprint.ofBoard(b))
        m.noteExport(st, a, "1,0")
        m.noteSaved(st, a)
        assertEquals(true, m.savedMatches(st, a))
        assertEquals(false, m.savedMatches(st, b))
    }
}
