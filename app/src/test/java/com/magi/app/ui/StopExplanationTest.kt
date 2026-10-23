package com.magi.app.ui

import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.V6FinalPort.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.643.0] 終わり方の一文は種別ごとに次の一手が決まり、内部名（英字）を含まない。 */
class StopExplanationTest {
    private fun s(kind: StopKind, hard: Int, early: Boolean = true) =
        V6FinalPort.StopSummary(earlyStop = early, kind = kind, usedSec = 84, budgetSec = 120, stalledSec = 45, remainingHard = hard)

    @Test fun nothingToSayWhenTheDeadlineEndedAPerfectBoard() { assertNull(stopExplanationOf(s(StopKind.DEADLINE, 0, early = false))) }

    /** [3.678.0] 必須 0 なら早く終えた理由も言わない（結果カードの見出しで足りる）。 */
    @Test fun earlyStopWithNoHardLeftSaysNothing() { assertNull(stopExplanationOf(s(StopKind.PLATEAU_FLOOR, 0))) }

    /** [3.678.0] 探索時間がすでに上限なら「延ばす」を勧めない。 */
    @Test fun deadlineAtMaxBudgetDoesNotSuggestMoreTime() {
        val atMax = V6FinalPort.StopSummary(earlyStop = false, kind = StopKind.DEADLINE, usedSec = 84, budgetSec = com.magi.app.v6.MAX_OPTIMIZE_SEC, stalledSec = 45, remainingHard = 2)
        assertEquals(StopNext.FIND_FIX, stopExplanationOf(atMax)!!.next)
    }

    @Test fun remainingHardUsesUnits() {
        val e = stopExplanationOf(s(StopKind.DEADLINE, 3, early = false), mapOf("c3n" to 1, "covU" to 2))!!
        assertTrue(e.line, e.line.contains("残る必須違反は 1件・2人です"))
        assertFalse(e.line.contains("主探索") || e.line.contains("制限時間"))
    }

    @Test fun eachKindMapsToItsNextStep() {
        assertEquals(StopNext.MORE_TIME, stopExplanationOf(s(StopKind.DEADLINE, 2, early = false))!!.next)
        assertEquals(StopNext.REVIEW_STAFFING, stopExplanationOf(s(StopKind.PLATEAU_FLOOR, 2))!!.next)
        assertEquals(StopNext.REVIEW_WISHES, stopExplanationOf(s(StopKind.WISH_FLOOR, 1))!!.next)
        assertEquals(StopNext.REVIEW_WISHES, stopExplanationOf(s(StopKind.C3N_WALL_CERTIFIED, 1))!!.next)
        assertEquals(StopNext.FIND_FIX, stopExplanationOf(s(StopKind.C3N_WALL_EMPIRICAL, 1))!!.next)
        assertEquals(StopNext.FIND_FIX, stopExplanationOf(s(StopKind.NORMAL_STALL, 3))!!.next)
    }

    @Test fun linesCarryTheNumbersAndNoInternalNames() {
        for (k in StopKind.values()) {
            val e = stopExplanationOf(s(k, 1))!!
            assertFalse("内部名（英字）を画面に出さない: ${e.line}", Regex("[A-Za-z]").containsMatchIn(e.line))
            assertTrue(e.line, e.line.contains("1件"))
        }
        assertTrue(stopExplanationOf(s(StopKind.NORMAL_STALL, 3))!!.line.contains("45 秒"))
        assertNull(stopNextLabel(StopNext.NONE))
    }
}
