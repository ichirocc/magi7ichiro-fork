package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [3.643.0] 直したあとの 1 行は、同じ問題について「解消したか」と「ほかに何件残るか」を言う。 */
class FixOutcomeTextTest {
    @Test fun appliedMoveSaysWhatChangedAndWhatRemains() {
        assertEquals("山本 10/12「夜」→「休」 を当てました。必須違反 3→2。ほかの必須違反は 2件 残っています。", fixOutcomeText("山本 10/12「夜」→「休」", 3, 2, "40件", "38件"))
        assertEquals("入替 を当てました。必須違反はなくなりました（要調整 40件→38件）。", fixOutcomeText("入替", 1, 0, "40件", "38件"))
        assertEquals("入替 を当てました。必須違反は変わらず 2件（要調整 40件→39件）。", fixOutcomeText("入替", 2, 2, "40件", "39件"))
        // [3.678.0] 内訳があれば単位ごとに（人員不足は人）。
        assertEquals("入替 を当てました。必須違反は変わらず 1件・2人（要調整 40件→39件）。", fixOutcomeText("入替", 3, 3, "40件", "39件", mapOf("c3n" to 1, "covU" to 2)))
    }

    /** [3.663.0] 当てたあとの内訳: 解消・減少と新たに生じた問題を分け、場所だけ移った違反も言う（仕様 §8）。 */
    @Test fun resultDetailSeparatesResolvedAppearedAndMoved() {
        fun rep(cells: Map<String, List<String>>, b: Map<String, Int>): com.magi.app.v6.ViolationReport {
            val hard = b.filterKeys { it in com.magi.app.v6.MirrorKeys.hard }.values.sum()
            return com.magi.app.v6.ViolationReport(emptyMap(), emptyMap(), emptyMap(), cellFamilies = cells,
                breakdown = b, total = b.values.sum(), hard = hard, soft = b.values.sum() - hard, weightedScore = 0.0)
        }
        val before = rep(mapOf("0,3" to listOf("vio-c3n"), "1,2" to listOf("vio-c1"), "2,2" to listOf("vio-covU")), mapOf("c3n" to 1, "c1" to 1, "covU" to 1, "weekly" to 4))
        val after = rep(mapOf("1,5" to listOf("vio-c1"), "2,2" to listOf("vio-covU")), mapOf("c1" to 1, "covU" to 1, "weekly" to 2, "c3mn" to 1))
        assertEquals("解消・減少した違反: 禁止の並び 1件・曜日の偏り 2pt。新たに生じた問題: 回避の並び +1件・期間の制約（場所が変わった）。新たな必須違反はありません（全体の必須違反は 1人）。",
            fixResultDetail(before, after))
        assertEquals("解消・減少した違反: 禁止の並び 1件。新たに生じた問題: なし。", fixResultDetail(rep(mapOf("0,3" to listOf("vio-c3n")), mapOf("c3n" to 1)), rep(emptyMap(), emptyMap())))
    }

    @Test fun guidedFixSaysResolvedOrStillShort() {
        assertEquals("10/12(土) の「夜」の人員不足を解消しました。ほかの必須違反は 3件 残っています。", guidedFixOutcomeText("10/12(土)", "夜", null, 3))
        assertEquals("10/12(土) の「夜」の人員不足を解消しました。必須違反はなくなりました。", guidedFixOutcomeText("10/12(土)", "夜", null, 0))
        assertEquals("10/12(土) の「夜」はまだ 1人 足りません。必須違反は 4件です。", guidedFixOutcomeText("10/12(土)", "夜", 1, 4))
    }
}
