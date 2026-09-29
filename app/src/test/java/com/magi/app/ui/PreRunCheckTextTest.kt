package com.magi.app.ui

import com.magi.app.model.StateParser
import com.magi.app.v6.PreRunCheck
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [つくる前の確認] 実データ（2026-10、氏名は伏せ字）でシートの行（利用者決定 2026-09-28）。 */
class PreRunCheckTextTest {
    private val st = StateParser.parse(javaClass.getResource("/oct2026_grid_state.json")!!.readText())!!
    private val board = st.schedule.toIntArray2D()
    private val ui = UiState(staffNames = st.staff.map { it.name }, shiftSymbols = st.shifts.map { it.kigou }, startDate = st.startDate)

    @Test fun realData_sheetRows() {
        val t = preRunSheetText(PreRunCheck.build(st, board), ui)
        assertEquals("計算では消えない（9件）", t.floorHeader)
        assertEquals(listOf(
            "職員01 10/25・10/26・10/27 本人の希望「休→休→休」が禁止の並びに当たっています",
            "職員03 10/1「Dﾃ」→ 10/2「休」 本人の希望どうしが希望の前日の禁止に当たっています",
            "職員05 10/23・10/24・10/25 本人の希望「休→休→休」が禁止の並びに当たっています",
            "職員08 10/10・10/11・10/12 本人の希望「休→休→休」が禁止の並びに当たっています",
            "10/9(金) 必要人数と本人の希望の衝突（8件は同時に成立しません・証明つき）（入れない指定が絡む）",
            "10/10(土) 必要人数と本人の希望の衝突（9件は同時に成立しません・証明つき）（入れない指定が絡む）",
            "10/11(日) 必要人数と本人の希望の衝突（9件は同時に成立しません・証明つき）（入れない指定が絡む）",
            "10/29(木) 必要人数と本人の希望の衝突（7件は同時に成立しません・証明つき）（入れない指定が絡む）",
            "職員04 本人の希望と条件の組合せ（4件は同時に成立しません・証明つき）",
        ), t.floorRows.map { it.text })
        assertTrue(t.hasWishRows)
        assertEquals(PRE_RUN_ZERO_CAP_NOTE, t.zeroCapNote)
        assertEquals("もう一度つくると外れる（4件）", t.rerunHeader)
        assertEquals(listOf("職員08 10/9 Cｱ", "職員04 10/10 Aｱ", "職員04 10/11 Cｵ", "職員08 10/29 Cｱ"), t.rerunRows.map { it.text })
        assertEquals(listOf(7 to 8, 3 to 9, 3 to 10, 7 to 28), t.rerunRows.map { it.staff to it.day })
        assertEquals("職員08「有」など：上限0のシフトに希望が載っています。上限0は意図した制限です。残るのは要調整です。希望を変えるか、例外として後から「設定を緩めたら」で試せます。", t.overCapNote)
        assertEquals(listOf("職員08「有」 本人の希望1件（個人の上限0回）", "職員11「Cｵ」 本人の希望12件（個人の上限0回）"), t.overCapRows.map { it.text })
        assertEquals("個人の上限0：22組（8人）。入れないシフトの指定です。つくったあとに、例外として緩める試算もできます。", t.wallLine)
    }

    @Test fun zeroCapRows_taggedAndPointToRelax() {
        val base = PreRunCheck.build(st, board)
        val f = com.magi.app.v6.V6SanityPort.ForcedCovU(0, "X", 2, 3)
        val t = preRunSheetText(base.copy(forcedShortfalls = listOf(f), zeroCapShortShifts = setOf(0),
            zeroCapProofDays = setOf(base.dayProofs.first().day)), ui)
        assertTrue(t.floorRows.first { it.text.startsWith("「X」") }.text.endsWith(PRE_RUN_ZERO_CAP_TAG))
        assertTrue(t.floorRows.first { it.text.startsWith("10/9(金)") }.text.endsWith(PRE_RUN_ZERO_CAP_TAG))
        assertTrue(t.floorRows.none { it.text.startsWith("10/10(土)") && it.text.endsWith(PRE_RUN_ZERO_CAP_TAG) })
        assertEquals(PRE_RUN_ZERO_CAP_NOTE, t.zeroCapNote)
    }
}
