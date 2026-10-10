package com.magi.app.ui

import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [3.644.0/UX-03] 複数人の入替を当てる前の一覧＝だれの・どの日の・何→何と、必須の増減。 */
class ChainFixPreviewTest {
    private val snap = arrayOf(intArrayOf(0, 0, 1), intArrayOf(1, 0, 0), intArrayOf(0, 2, 0))
    private fun s(diff: List<Pair<String, Int>> = emptyList()) = FixSuggestion(FixKind.CHAIN,
        listOf(FixCell(0, 2, 2), FixCell(2, 2, 1), FixCell(1, 0, 2)), "（玉突き）10/3 の「夜」を複数人の入替で埋める", deltaHard = -1, deltaTotal = 1, diff = diff)

    @Test fun listsEveryChangeWithBeforeAndAfter() {
        val p = chainFixPreview(s(listOf("covU" to -1, "covO" to 1)), snap, listOf("甲", "乙", "丙"), listOf("休", "日", "夜"), "2026-10-01")
        assertEquals("複数人の入れ替え", p.title)
        assertEquals(listOf("甲 10/3 日 → 夜", "丙 10/3 休 → 日", "乙 10/1 日 → 夜"), p.changes)
        assertEquals("必須違反: 1件減る", p.hardLine)
        assertEquals("増える要調整: 人員過剰 +1", p.caution)
    }

    @Test fun noCautionWhenNothingGetsWorseAndNamesFallBack() {
        val p = chainFixPreview(s(), snap, emptyList(), emptyList(), "2026-10-01")
        assertNull(p.caution)
        assertEquals("職員1 10/3 1 → 2", p.changes[0])
    }
}
