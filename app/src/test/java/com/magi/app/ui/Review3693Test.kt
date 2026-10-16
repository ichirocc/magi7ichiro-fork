package com.magi.app.ui

import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.693.0: 8秒理解UI 第1便＝数の単位の誤り（件と人を足さない＝INV-07、巡回の数、全角のコロン）。 */
class Review3693Test {
    @Test fun runningBadgeSaysHardPerUnitAndNothingAtZero() {
        assertEquals("組んでいます 必須1件・2人", runningBadgeLabel(mapOf("covU" to 2, "c3n" to 1), 3L))
        assertEquals("組んでいます 必須5件", runningBadgeLabel(emptyMap(), 5L))
        assertEquals("組んでいます", runningBadgeLabel(emptyMap(), 0L))
    }

    @Test fun appliedNextButtonCountsTourItemsNotReportHard() {
        assertEquals("残っている必須違反を順に見る（2か所）", appliedNextButton(false, null, 3, 2))
        assertNull(appliedNextButton(false, null, 3, 0))
        assertEquals("続けて A の案を見る", appliedNextButton(true, "A", 3, 0))
    }

    @Test fun fixOutcomeSaysBeforeAndAfterPerUnit() {
        assertEquals("入替 を適用しました。必須違反 1件・2人→1人。ほかの必須違反は 1人 残っています。",
            fixOutcomeText("入替", 3, 1, "40件", "39件", mapOf("covU" to 1), mapOf("c3n" to 1, "covU" to 2)))
        assertEquals("入替 を適用しました。必須違反 3件→2件。ほかの必須違反は 2件 残っています。", fixOutcomeText("入替", 3, 2, "40件", "39件"))
    }

    @Test fun colonsAreFullWidth() {
        val (hard, caution) = fixImpactLines(FixSuggestion(FixKind.CHANGE, emptyList(), "x", -1, 0, listOf("c3n" to -1, "covO" to 1)))
        assertEquals("必須違反：1件減る", hard)
        assertEquals("増える要調整：人員過剰 +1人", caution)
    }
}
