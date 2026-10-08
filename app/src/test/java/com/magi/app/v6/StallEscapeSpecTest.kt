package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/stall_escape.md` の規則と数値をそのまま固定する（仕様 → テスト）。表の値が変わったら仕様も同じコミットで直す。
 * 既存の `V6FinalPortTest`（発火式・実効閾値）・`HypothesisEpochPolicyTest`（層 B）・`Hf63InfeasibilityTest`（HF63）・
 * `V6NativeOptimizerChoiceTest`（focus 選択）・`StallPolishInjectionTest` が持たない表の値と境界だけを扱う。
 */
class StallEscapeSpecTest {

    // §5.1 時間の切り方: 300 s・60 s・20 s 予算の表
    @Test fun budgetTableFor300Seconds() {
        val b = V6FinalPort.watchdogBudget(300_000L, startMs = 0L, hardDeadlineMs = 300_000L, fraction = 0.9)
        assertEquals(45_000L, b.minRunMs)
        assertEquals(25_000L, b.postReserveMs)
        assertEquals(275_000L, b.searchDeadlineMs)
        assertEquals(275_000L, b.searchWindowMs)
        assertEquals(270_000L, b.stallMs)
        assertEquals(37_500L, b.stallHardMs)
        assertEquals(7_500L, b.phaseGraceMs)
    }

    @Test fun budgetTableFor60SecondsFallsBackToWindowFraction() {
        val b = V6FinalPort.watchdogBudget(60_000L, startMs = 0L, hardDeadlineMs = 60_000L, fraction = 0.9)
        assertEquals(10_000L, b.minRunMs)
        assertEquals(8_000L, b.postReserveMs)
        assertEquals(52_000L, b.searchWindowMs)
        assertEquals(46_800L, b.stallMs)   // 54 s ≥ 52 s の区間なので 52 s × 0.9
        assertEquals(15_000L, b.stallHardMs)
        assertEquals(2_000L, b.phaseGraceMs)
    }

    @Test fun twentySecondBudgetCannotFireTheNormalWatchdog() {
        val b = V6FinalPort.watchdogBudget(20_000L, startMs = 0L, hardDeadlineMs = 20_000L, fraction = 0.9)
        assertEquals(8_000L, b.minRunMs)
        assertEquals(8_000L, b.postReserveMs)
        assertEquals(12_000L, b.searchWindowMs)
        assertTrue("20 s の下限が探索区間を超える＝通常閾値は探索終了まで真にならない", b.stallMs >= b.searchWindowMs)
        assertTrue("短い閾値の下限 15 s も区間 12 s を超える＝この予算では停滞発火は起きず締切だけで止まる", b.stallHardMs >= b.searchWindowMs)
    }

    @Test fun budgetUsesStartAndHardDeadlineNotZero() {
        val b = V6FinalPort.watchdogBudget(300_000L, startMs = 1_000_000L, hardDeadlineMs = 1_300_000L, fraction = 0.9)
        assertEquals(1_275_000L, b.searchDeadlineMs)
        assertEquals(275_000L, b.searchWindowMs)
    }

    // §3.2 監視判定は weightedScore にだけ 1e-6 の許容差。採否の betterReport は厳密。
    @Test fun progressImprovedFollowsHardThenWeightedThenTotal() {
        assertTrue(V6FinalPort.progressImproved(h = 0, wgt = 1000.0, t = 50, bh = 1, bWeighted = 10.0, bTotal = 1))
        assertTrue(V6FinalPort.progressImproved(h = 1, wgt = 9.0, t = 50, bh = 1, bWeighted = 10.0, bTotal = 1))
        assertTrue(V6FinalPort.progressImproved(h = 1, wgt = 10.0, t = 0, bh = 1, bWeighted = 10.0, bTotal = 1))
        assertFalse(V6FinalPort.progressImproved(h = 1, wgt = 11.0, t = 0, bh = 1, bWeighted = 10.0, bTotal = 1))
        assertFalse(V6FinalPort.progressImproved(h = 2, wgt = 0.0, t = 0, bh = 1, bWeighted = 10.0, bTotal = 1))
    }

    @Test fun progressImprovedIgnoresFloatingNoiseButBetterReportIsStrict() {
        assertFalse("1e-9 の差は改善と数えない", V6FinalPort.progressImproved(h = 1, wgt = 10.0 - 1e-9, t = 1, bh = 1, bWeighted = 10.0, bTotal = 1))
        assertTrue("許容差の内なら total の減少を改善と数える", V6FinalPort.progressImproved(h = 1, wgt = 10.0 + 1e-9, t = 0, bh = 1, bWeighted = 10.0, bTotal = 1))
        assertFalse("許容差の外（+1）は total が減っても改善でない", V6FinalPort.progressImproved(h = 1, wgt = 11.0, t = 0, bh = 1, bWeighted = 10.0, bTotal = 1))
    }

    @Test fun weightsAreIntegersSoToleranceNeverHidesARealDifference() {
        assertTrue(MirrorKeys.weights.values.all { it == Math.floor(it) && it >= 2.0 })
        assertEquals(setOf("groupViol", "c3n", "covU", "pref", "c3w"), MirrorKeys.hard.toSet())
    }

    // §5.4 発火式の境界: 全て厳密な >
    @Test fun firingBoundariesAreStrict() {
        val start = 0L; val minRun = 45_000L; val grace = 7_500L; val eff = 270_000L
        fun fired(now: Long, lastImprove: Long, lastPhase: Long) =
            V6FinalPort.watchdogStagnationFired(now, start, minRun, lastPhase, grace, lastImprove, eff)
        assertFalse("ちょうど effStall は未発火", fired(now = 300_000L, lastImprove = 30_000L, lastPhase = 0L))
        assertTrue("effStall + 1 と猶予経過で発火", fired(now = 300_001L, lastImprove = 30_000L, lastPhase = 0L))
        assertFalse("猶予中は effStall × 2 ちょうどでも未発火", fired(now = 540_000L, lastImprove = 0L, lastPhase = 540_000L))
        assertTrue("effStall × 2 + 1 なら猶予に関わらず発火", fired(now = 540_001L, lastImprove = 0L, lastPhase = 540_001L))
        assertFalse("minRun ちょうどは未発火", fired(now = 45_000L, lastImprove = -300_000L, lastPhase = -100_000L))
    }

    // §5.5 停止確認の窓
    @Test fun stopConfirmWindowIsFiveSeconds() {
        assertEquals(5_000L, V6NativeOptimizer.STOP_CONFIRM_MS)
    }

    // §7.1 HF63 が追跡する 13 族と effortIters の式
    @Test fun hf63TracksExactlyThirteenFamilies() {
        assertEquals(
            setOf("c1", "c2", "c3", "c3n", "c3m", "c3mn", "c41", "c42", "covU", "covO", "pref", "low", "high"),
            Hf63Infeasibility.KEY_TO_INDEX.keys,
        )
        for (k in listOf("groupViol", "c3w", "c41s", "c42s", "apt", "weekly", "fair")) assertFalse(k, k in Hf63Infeasibility.KEY_TO_INDEX)
    }

    @Test fun effortItersFollowsTheAttemptsTargetFormula() {
        assertEquals(2_500, HypothesisPlanning.rsiHf63EffortIters(2))
        assertEquals(2_500, HypothesisPlanning.rsiHf63EffortIters(5))
        assertEquals(1_667, HypothesisPlanning.rsiHf63EffortIters(8))
        assertEquals(1_250, HypothesisPlanning.rsiHf63EffortIters(10))
    }

    // §6 層 B の定数
    @Test fun portfolioQuantaAndDuplicateDistance() {
        assertEquals(5, AdaptiveHypothesisEpochPolicy.BASE_QUANTUM_SEC)
        assertEquals(8, AdaptiveHypothesisEpochPolicy.IMPROVING_QUANTUM_SEC)
        assertEquals(35, AdaptiveHypothesisEpochPolicy.RSI_PLUS_BASE_QUANTUM_SEC)
        assertEquals(45, AdaptiveHypothesisEpochPolicy.RSI_PLUS_IMPROVING_QUANTUM_SEC)
        assertEquals(2, AdaptiveHypothesisEpochPolicy.DUPLICATE_DISTANCE_CELLS)
        assertEquals(1, AdaptiveHypothesisEpochPolicy.nextStagnantEpochs(0, improvedThisEpoch = false))
        assertEquals(0, AdaptiveHypothesisEpochPolicy.nextStagnantEpochs(3, improvedThisEpoch = true))
    }

    // §10 既定 OFF と既定値
    @Test fun defaultsMatchTheSpec() {
        assertEquals(0.9, PolishGate.normalStallFraction, 0.0)
        assertFalse(PolishGate.stallPolishInjection)
        assertFalse(PolishGate.postChainRollbackCountsZero)
        assertEquals(WishFloorMode.OFF, PolishGate.wishConflictFloorMode)
        assertEquals(2, V6FinalPort.STALL_OVERRIDE_FACTOR)
        assertEquals(5000, Hf63Infeasibility.INFEAS_STALL_ITERS)
        assertEquals(3, StallPolishInjection.MAX_INJECTIONS)
        assertEquals(6_000L, StallPolishInjection.CAP_MS)
    }
}
