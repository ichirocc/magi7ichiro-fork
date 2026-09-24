package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.535.0/HF77明示数値指示] `AptFairPolish.toleratedBetter`（fair/apt研磨で対象家族以外のSOFT悪化を
 * 研磨開始時点比+6%まで容認する）の単体検証。実盤面(MagiState)を組むと重みの掛け算を狙って作るのが
 * 難しいため、`ViolationReport`を直接構成して比較の算術だけを固定する（判定・重みは既存のMirrorKeysから
 * 読むだけで新規に増やさない）。
 */
class AptFairPolishToleranceTest {

    private fun rep(fair: Int, apt: Int, low: Int): ViolationReport = repOf("fair" to fair, "apt" to apt, "low" to low)

    private fun repOf(vararg fams: Pair<String, Int>): ViolationReport {
        val bd = fams.toMap()
        val weighted = bd.entries.sumOf { (k, v) -> v * MirrorKeys.weights.getValue(k) }
        val hard = bd.filterKeys { it in MirrorKeys.hard }.values.sum()
        return ViolationReport(
            violations = emptyMap(), needViolations = emptyMap(), countViolations = emptyMap(),
            breakdown = bd, total = bd.values.sum(), hard = hard, soft = bd.values.sum() - hard, weightedScore = weighted,
        )
    }

    @Test fun disabledFallsBackToPlainBetterReport() {
        val before = rep(fair = 10, apt = 0, low = 1)
        val bestRep = before
        val candidate = rep(fair = 9, apt = 2, low = 1)   // apt+2*4=+8 > fair-1*2=-2 の純悪化
        assertFalse("OFF時はbetterReportと同じ判定", AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = false))
        assertEquals(betterReport(candidate, bestRep), AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = false))
    }

    @Test fun withinBudgetTradeIsAcceptedEvenThoughRawScoreWorsens() {
        // baseline: 対象家族(fair)以外のSOFT合計 = low(1)*120 = 120 → 予算 = 120*0.06 = 7.2
        val before = rep(fair = 10, apt = 0, low = 1)
        val bestRep = before   // weightedScore = 20 + 0 + 120 = 140
        // candidate: fairが1改善(-2)する代わりにaptが2悪化(+8) → 生スコアは+6悪化(betterReportなら却下)
        val candidate = rep(fair = 9, apt = 2, low = 1)   // weightedScore = 18 + 8 + 120 = 146
        assertFalse("素のbetterReportは却下する（生スコアが悪化）", betterReport(candidate, bestRep))
        assertTrue("6%予算内(+8 <= 7.2)なので容認して採用する",
            AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = true))
    }

    @Test fun exceedingBudgetTradeIsStillRejected() {
        val before = rep(fair = 10, apt = 0, low = 1)   // baseline non-fair soft = 120, 予算 = 7.2
        val bestRep = before
        // candidate: fairが1改善(-2)する代わりにlowが1悪化(+120) → +120 は予算7.2を遥かに超える
        val candidate = rep(fair = 9, apt = 0, low = 2)   // weightedScore = 18 + 0 + 240 = 258
        assertFalse("生スコアの悪化(+118)を予算7.2ぶんしか許さないため、なお却下される",
            AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = true))
    }

    @Test fun cumulativeBudgetShrinksAsItIsSpent() {
        val before = rep(fair = 10, apt = 0, low = 1)   // baseline non-fair soft = 120, 予算 = 7.2
        // bestRep が既に apt+1(=4)ぶん予算を使った状態からスタート（残り予算 = 7.2 - 4 = 3.2）。
        val bestRep = rep(fair = 9, apt = 1, low = 1)   // weightedScore = 18 + 4 + 120 = 142
        // 追加でapt+1(=4)悪化する手は、残り予算3.2を超える（forgiven=3.2、未容認分0.8が残る）。
        // fairは変化なしなので、この0.8を相殺する改善が無く却下される。
        val candidateNoFairGain = rep(fair = 9, apt = 2, low = 1)   // weightedScore = 18 + 8 + 120 = 146
        assertFalse("残り予算(3.2)を超え、かつfairの改善が無いため却下",
            AptFairPolish.toleratedBetter(candidateNoFairGain, bestRep, before, "fair", enabled = true))
    }

    // ==== [3.592.0] countパラメータ: pinBad診断分岐からの呼び出しを許容カウンタへ数えない ====

    @Test fun countFalseSkipsTheTelemetryCounterEvenWhenAccepted() {
        val before = rep(fair = 10, apt = 0, low = 1)
        val bestRep = before
        val candidate = rep(fair = 9, apt = 2, low = 1)   // withinBudgetTradeIsAcceptedと同じ＝容認採用される手
        TuningTelemetry.reset()
        assertTrue(AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = true, count = false))
        assertEquals("count=falseなら診断分岐からの呼び出しはカウントしない", 0, TuningTelemetry.aptFairToleranceUsed.get())
        assertTrue(AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = true, count = true))
        assertEquals("count=true(既定)なら従来どおり数える", 1, TuningTelemetry.aptFairToleranceUsed.get())
    }

    // ==== [無害化, 2026-09-24] 許容 ON でも重い SOFT の増加・必須どうしの付け替え・対象族の改善なしの容認は採らない ====

    @Test fun heavySoftIncreaseIsRejectedEvenWhenTheRawScoreImproves() {
        // c1 が 1 増えても fair が大きく減れば素の betterReport は採るが、許容 ON では c1 の増加を 1 件も許さない。
        val before = repOf("fair" to 60, "c1" to 0, "weekly" to 5)
        val candidate = repOf("fair" to 30, "c1" to 1, "weekly" to 5)
        assertTrue(betterReport(candidate, before))
        assertFalse(AptFairPolish.toleratedBetter(candidate, before, before, "fair", enabled = true))
        val lowUp = repOf("fair" to 30, "low" to 1, "weekly" to 5)
        assertFalse(AptFairPolish.toleratedBetter(lowUp, before, before, "fair", enabled = true))
    }

    @Test fun hardFamilySwapIsRejectedEvenAtTheSameHardTotal() {
        // 必須の合計は同点(1)でも covU→c3n の付け替えは採らない（fair は改善していても）。
        val bestRep = repOf("covU" to 1, "c3n" to 0, "fair" to 10)
        val candidate = repOf("covU" to 0, "c3n" to 1, "fair" to 2)
        assertFalse(AptFairPolish.toleratedBetter(candidate, bestRep, bestRep, "fair", enabled = true))
    }

    @Test fun toleranceIsNotUsedWhenTheTargetFamilyDoesNotImprove() {
        // weekly −3(−6)・apt +2(+8) で加重 +2・件数 −1。容認(2 ≤ 予算 6)で実効 0・件数減なので旧判定なら採るが、
        //   対象の fair が減っていない＝他の族を入れ替えただけの手は採らない。
        val before = repOf("fair" to 10, "weekly" to 50, "apt" to 0)   // 非 fair SOFT = 100 → 予算 6
        val candidate = repOf("fair" to 10, "weekly" to 47, "apt" to 2)
        assertFalse(betterReport(candidate, before))
        assertFalse(AptFairPolish.toleratedBetter(candidate, before, before, "fair", enabled = true))
        val withFairGain = repOf("fair" to 9, "weekly" to 47, "apt" to 2)   // fair も減るなら容認で採る
        assertTrue(AptFairPolish.toleratedBetter(withFairGain, before, before, "fair", enabled = true))
    }
}
