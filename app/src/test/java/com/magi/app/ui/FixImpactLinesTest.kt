package com.magi.app.ui

import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [思考誘導S1] 1手の得失は「必須が減るか」を先に、増える要調整を「注意」に出す。 */
class FixImpactLinesTest {
    private fun s(dh: Int, vararg diff: Pair<String, Int>) = FixSuggestion(FixKind.CHANGE, emptyList(), "x", dh, 0, diff.toList())

    @Test fun hardLineComesFromDeltaHard() {
        assertEquals("必須の約束: 減る（2件）", fixImpactLines(s(-2)).first)
        assertEquals("必須の約束: 変わらない", fixImpactLines(s(0)).first)
        assertEquals("必須の約束: 増える（1件）", fixImpactLines(s(1)).first)
    }

    @Test fun cautionListsOnlyWorsenedSoftFamilies() {
        val (_, caution) = fixImpactLines(s(-1, "covU" to -1, "high" to 1, "c1" to -1, "covO" to 2))
        assertEquals("注意: 上限超過 +1・人員過剰 +2", caution)
        assertNull(fixImpactLines(s(-1, "covU" to -1)).second)
    }
}
