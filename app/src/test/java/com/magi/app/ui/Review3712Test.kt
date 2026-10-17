package com.magi.app.ui

import com.magi.app.v6.V6PortAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [3.712.0] 画面ごとに「見出し＋1 行＋主ボタン」（利用者仕様）。診断カードの行は事実と札だけで、
 * 定型の説明と内訳は「詳しく」に 1 回。カードの開閉は Composable の動きなので android-sdk.yml と実機で確かめる。
 */
class Review3712Test {
    @Test fun shortageRowsAreOneFactAndATag() {
        assertEquals("10/10(土) Cｵ 不足1", shortageRowLine("10/10(土)", "Cｵ", 1))
        assertEquals("担当が足りない", shortageRowTag(infeasible = true, blockedNow = false))
        assertEquals("今の希望では埋まらない", shortageRowTag(infeasible = false, blockedNow = true))
        assertEquals("動かせば埋まる", shortageRowTag(infeasible = false, blockedNow = false))
        assertEquals("休みや余裕のある勤務から移せます", shortageRowNote(infeasible = false, blockedNow = false))
        assertNull(shortageRowNote(infeasible = true, blockedNow = false))
        assertEquals("足りない日3 · 動かせる枠あり", shortageCardLine(3, true))
        assertEquals("足りない日1 · 動かせる枠なし", shortageCardLine(1, false))
    }

    @Test fun surplusAndForbiddenRowsDropTheBreakdown() {
        assertEquals("移動先はどこも定員一杯", surplusShortReason("移動先はどこも定員一杯。内訳: Aの3人のうち 動かせる0人・玉突き必要3人・本人の希望0人・禁止の並び0人"))
        assertEquals("崩せる", forbiddenRunTag(true))
        assertEquals("崩せない", forbiddenRunTag(false))
        assertEquals("10/9 Dﾃ · 10/10 Aｱ", forbiddenRunCellsLine(listOf("10/9" to "Dﾃ", "10/10" to "Aｱ")))
        assertEquals("福澤俊陽 · 10/1", wishRowHead("福澤俊陽", "10/1"))
    }

    /** 行の文を作る関数は、定型の長文（玉突き・日の証明・内訳）を出さない。 */
    @Test fun rowBuildersNeverCarryTheBoilerplate() {
        val rows = listOf(
            shortageRowLine("10/10(土)", "Cｵ", 1), shortageRowTag(true, false), shortageRowTag(false, true), shortageRowTag(false, false),
            shortageRowNote(false, false).orEmpty(), shortageCardLine(2, true), forbiddenRunTag(true), forbiddenRunTag(false),
            forbiddenRunCellsLine(listOf("10/9" to "Dﾃ")), wishRowHead("福澤", "10/1"),
            surplusShortReason("理由。内訳: Aの1人のうち 動かせる1人"),
        )
        for (r in rows) {
            assertFalse(r, r.contains(V6PortAnalyzer.FORBIDDEN_CHAIN_HINT) || r.contains(FORBIDDEN_RETRY_NOTE))
            assertFalse(r, r.contains(V6PortAnalyzer.DAY_PROOF_REASON) || r.contains("内訳") || r.contains("玉突き"))
            assertFalse(r, r.length > 22)
        }
    }
}
