package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.694.0] CoverageDiag の枠行の根拠は既存のフィールド（dayProof・verdict・wishPinned・blockedNow・searchMiss）だけから決まる。 */
class CoverageBasis3694Test {
    private fun slot(verdict: CoverageVerdict, reason: String, blockedNow: Boolean = false, wishPinned: List<Int> = emptyList(),
                     searchMiss: Boolean = false, dayProof: Boolean = false) =
        CoverageShortfall(0, "10/10(土)", 1, "Aｱ", need = 1, got = 0, miss = 1, capacity = 1, verdict = verdict, reason = reason,
            blockedNow = blockedNow, wishPinned = wishPinned, searchMiss = searchMiss, dayProof = dayProof)

    @Test fun basisIsDecidedFromFieldsNotFromReasonText() {
        assertEquals("日の証明", slot(CoverageVerdict.INFEASIBLE, "x", wishPinned = listOf(0), dayProof = true).basis())
        assertEquals("希望固定を含む人数の不足", slot(CoverageVerdict.INFEASIBLE, "x", wishPinned = listOf(0)).basis())
        assertEquals("担当できる人数の不足", slot(CoverageVerdict.INFEASIBLE, "x").basis())
        assertEquals("試した入れ替えの範囲、証明ではない", slot(CoverageVerdict.FIXABLE, "x", blockedNow = true, searchMiss = true).basis())
        assertNull(slot(CoverageVerdict.FIXABLE, "x", blockedNow = true).basis())
        assertNull(slot(CoverageVerdict.FIXABLE, "x").basis())
    }

    @Test fun logLinesAppendTheBasisAndKeepTheOldWords() {
        val slots = listOf(
            slot(CoverageVerdict.INFEASIBLE, V6PortAnalyzer.DAY_PROOF_REASON, dayProof = true),
            slot(CoverageVerdict.INFEASIBLE, "いまの希望のままでは…", wishPinned = listOf(2)),
            slot(CoverageVerdict.INFEASIBLE, "担当可能な職員が1人で必要数2に届きません（データ上、充足不可）"),
            slot(CoverageVerdict.FIXABLE, V6PortAnalyzer.CHAIN_NOT_FOUND_IN_TRIED, blockedNow = true, searchMiss = true),
            slot(CoverageVerdict.FIXABLE, "空き番がいます"),
        )
        val lines = CoverageDiagnosis(totalShortfall = 5, infeasibleSlots = 3, fixableSlots = 2, shortfalls = slots).logLines()
        assertTrue(lines[1], lines[1].contains("— 充足不可: この希望と上限のままでは埋まりません") && lines[1].endsWith("（根拠: 日の証明）"))
        assertTrue(lines[2], lines[2].endsWith("（根拠: 希望固定を含む人数の不足）"))
        assertTrue(lines[3], lines[3].endsWith("（根拠: 担当できる人数の不足）"))
        assertTrue(lines[4], lines[4].contains("— 試した入れ替えでは埋まらなかった:") && lines[4].endsWith("（根拠: 試した入れ替えの範囲、証明ではない）"))
        assertTrue(lines[5], lines[5].contains("— 充足可能: 空き番がいます"))
        assertFalse(lines[5], lines[5].contains("（根拠:"))
    }
}
