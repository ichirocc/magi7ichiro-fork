package com.magi.app.ui

import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.Problem
import org.junit.Assert.assertEquals
import org.junit.Test

class MagiConditionsViewTest {
    /** 複数日の一括は 1 回の書き換えで全日へ入る（Undo 1 回にまとめるための前提）。空欄の側は既定へ戻り、期間外の日は無視。 */
    @Test
    fun needDaysWithWritesEveryDayAndClearsTheBlankSide() {
        val nd1 = mapOf("0,1" to "2", "1,1" to "4")
        val nd2 = mapOf("0,1" to "3", "0,2" to "5")
        val (m1, m2) = needDaysWith(nd1, nd2, k = 0, days = listOf(1, 2, 31, -1), dayCount = 31, p1 = " 3 ", p2 = "")
        assertEquals(mapOf("0,1" to "3", "0,2" to "3", "1,1" to "4"), m1)
        assertEquals("上限は空欄＝既定へ戻す", emptyMap<String, String>(), m2)
    }

    @Test
    fun needDaysWithoutRemovesOnlyTheGivenShiftAndDays() {
        val nd1 = mapOf("0,1" to "2", "0,2" to "2", "1,1" to "4")
        val nd2 = mapOf("0,2" to "5")
        val (m1, m2) = needDaysWithout(nd1, nd2, k = 0, days = listOf(1, 2))
        assertEquals(mapOf("1,1" to "4"), m1)
        assertEquals(emptyMap<String, String>(), m2)
    }

    /** 2パターン目を使わない月は上限人数（need2）が効かない＝見出しにも出さない（カレンダーの各日と一致させる）。 */
    @Test
    fun needBaseLabelIgnoresNeed2WhenTheSecondPatternIsOff() {
        assertEquals("3–5人", needBaseLabel("3", "5", use2 = true))
        assertEquals("3人", needBaseLabel("3", "5", use2 = false))
        assertEquals("5人", needBaseLabel("", "5", use2 = true))
        assertEquals("need2 だけでは必要人数は未設定のまま", "未設定", needBaseLabel("", "5", use2 = false))
        assertEquals("3人", needBaseLabel("3", "3", use2 = true))
        assertEquals("上限人数", needUpperLabel(use2 = true))
        assertEquals("上限(2パターン時)", needUpperLabel(use2 = false, short = true))
    }

    private fun load(): MagiState =
        StateParser.parse(javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText())!!

    /** 初期設定の手順の「ルール N件」は全族の行数の和。族を足して数え漏らすと落ちる（MagiState の cons* をリフレクションで引く）。 */
    @Test
    fun setupRuleCountCoversEveryConstraintFamily() {
        val st = load().copy(
            cons41s = listOf(C41Row("L", "Dﾃ", "1", "")),
            cons42s = listOf(C42Row("L", "L", "Dﾃ", "Dﾃ")),
        )
        val expected = MagiState::class.java.declaredFields
            .filter { it.name.startsWith("cons") }
            .sumOf { f -> f.isAccessible = true; (f.get(st) as List<*>).size }
        assertEquals(expected, conditionsViewOf(st, Problem(st)).setupCounts.constraints)
    }
}
