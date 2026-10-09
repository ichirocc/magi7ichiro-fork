package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [3.643.0] 直したあとの 1 行は、同じ問題について「解消したか」と「ほかに何件残るか」を言う。 */
class FixOutcomeTextTest {
    @Test fun appliedMoveSaysWhatChangedAndWhatRemains() {
        assertEquals("山本 10/12「夜」→「休」 を当てました。必須違反 3→2。ほかの必須違反は 2 件残っています。", fixOutcomeText("山本 10/12「夜」→「休」", 3, 2, 40, 38))
        assertEquals("入替 を当てました。必須違反はなくなりました（合計 40→38）。", fixOutcomeText("入替", 1, 0, 40, 38))
        assertEquals("入替 を当てました。必須違反は変わらず 2 件（合計 40→39）。", fixOutcomeText("入替", 2, 2, 40, 39))
    }

    @Test fun guidedFixSaysResolvedOrStillShort() {
        assertEquals("10/12(土) の「夜」の人員不足を解消しました。ほかの必須違反は 3 件残っています。", guidedFixOutcomeText("10/12(土)", "夜", null, 3))
        assertEquals("10/12(土) の「夜」の人員不足を解消しました。必須違反はなくなりました。", guidedFixOutcomeText("10/12(土)", "夜", null, 0))
        assertEquals("10/12(土) の「夜」はまだ 1人 足りません。必須違反は 4 件です。", guidedFixOutcomeText("10/12(土)", "夜", 1, 4))
    }
}
