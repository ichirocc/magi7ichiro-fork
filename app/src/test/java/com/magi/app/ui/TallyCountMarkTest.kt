package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** シフト集計の職員別セルの印: ▼▲ は個人の下限割れ・上限超過だけ（分析の「下限割れ」の数とそろう）、目安の回数との差は ▽△。 */
class TallyCountMarkTest {
    @Test fun lowAndHighKeepFilledMarksAndAptGetsHollowMarks() {
        assertEquals("▼", tallyCountGlyph("vio-low"))
        assertEquals("▲", tallyCountGlyph("vio-high"))
        assertEquals("▽", tallyCountGlyph("vio-aptLow"))
        assertEquals("△", tallyCountGlyph("vio-aptHigh"))
        assertEquals("", tallyCountGlyph(null))
        assertEquals("", tallyCountGlyph("vio-c2"))
    }

    @Test fun wordsMatchTheAnalysisFamilyNames() {
        assertEquals("下限割れ", tallyCountWord("vio-low"))
        assertEquals(breakdownLabels["low"], tallyCountWord("vio-low"))
        assertEquals(breakdownLabels["high"], tallyCountWord("vio-high"))
        assertEquals("目安の回数より少ない", tallyCountWord("vio-aptLow"))
        assertEquals("不足", tallyCountWord("vio-c2"))
    }
}
