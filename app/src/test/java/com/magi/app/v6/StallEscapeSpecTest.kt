package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `docs/stall_escape.md` の規則と数値をそのまま固定する（仕様 → テスト）。表の値が変わったら仕様も同じコミットで直す。
 * 既存の `V6FinalPortTest`（発火式・実効閾値）・`HypothesisEpochPolicyTest`（層 B）・`Hf63InfeasibilityTest`（HF63）・
 * `V6NativeOptimizerChoiceTest`（focus 選択）・`StallPolishInjectionTest` が持たない表の値・境界と、§5.2〜5.4 の判定の補助
 * （`WatchdogBest` の発火、`C3nWallProof` の証拠の選択、`BoardKeyedFlag`）を扱う。
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

    private fun rep(hard: Int, weighted: Double, total: Int, vararg fams: Pair<String, Int>): ViolationReport = ViolationReport(
        violations = emptyMap(), needViolations = emptyMap(), countViolations = emptyMap(),
        breakdown = fams.toMap(), total = total, hard = hard, soft = total - hard, weightedScore = weighted,
    )

    // §5.2 停滞ラッチは改善報告で降りる（永続ラッチではない）
    @Test fun stagnationLatchClearsOnImprovementAndStaysOnNonImprovement() {
        val wd = V6FinalPort.WatchdogBest(startMs = 0L)
        assertTrue(wd.observe(rep(1, 100.0, 5), nowMs = 1_000L, observedIters = 10L, beatsInput = { true }, wishC3wProven = 0))
        assertEquals(1_000L, wd.lastBeatInputMs.get())
        wd.fire(nowMs = 300_000L, observedIters = 500L, byOverride = true)
        assertTrue(wd.stagnationFired.get()); assertEquals(299_000L, wd.stagnationDurationMs.get()); assertEquals(500L, wd.stagnationIters.get())
        assertTrue(wd.stagnationByOverride.get())
        assertFalse("悪化は改善でない＝ラッチは立ったまま", wd.observe(rep(1, 101.0, 5), 301_000L, 600L, { false }, 0))
        assertTrue(wd.stagnationFired.get())
        assertTrue("改善でラッチが降りる", wd.observe(rep(1, 99.0, 5), 302_000L, 700L, { false }, 0))
        assertFalse(wd.stagnationFired.get()); assertEquals(-1L, wd.stagnationDurationMs.get()); assertEquals(-1L, wd.stagnationIters.get())
        assertFalse(wd.stagnationByOverride.get())
        assertEquals(302_000L, wd.lastBestImproveMs.get()); assertEquals(700L, wd.lastBestImproveIters.get())
        assertEquals("入力を上回らない改善では lastBeatInputMs は動かない", 1_000L, wd.lastBeatInputMs.get())
        assertEquals(2, wd.bestVersion.get())
    }

    @Test fun observeTracksNonCovUHardAndC3nOnlyFlag() {
        val wd = V6FinalPort.WatchdogBest(startMs = 0L)
        var beatsAsked = 0
        assertFalse("改善でなければ beatsInput は評価しない", wd.observe(rep(Int.MAX_VALUE, Double.MAX_VALUE, Int.MAX_VALUE), 1L, 0L, { beatsAsked++; true }, 0))
        assertEquals(0, beatsAsked)
        wd.observe(rep(3, 9000.0 * 3, 3, "c3n" to 2, "covU" to 1), 10L, 1L, { true }, 0)
        assertEquals(2, wd.bestNonCovUHard.get()); assertTrue(wd.bestNonCovUAllC3n.get())
        wd.observe(rep(2, 9000.0 * 2, 2, "c3n" to 1, "pref" to 1), 20L, 2L, { true }, 0)
        assertEquals(2, wd.bestNonCovUHard.get()); assertFalse("pref が残れば c3n だけではない", wd.bestNonCovUAllC3n.get())
        wd.observe(rep(1, 9000.0, 1, "c3w" to 1), 30L, 3L, { true }, 1)
        assertFalse("c3w ≤ wishC3wProven でも c3n > 0 が要る", wd.bestNonCovUAllC3n.get())
        assertEquals(3, wd.bestVersion.get())
    }

    // §7.2 回避集合: SOFT は avoid に入らない、静的 covU 床、冷却は focusAvoid だけ
    @Test fun avoidSetsKeepSoftFocusableAndSeparateCooldown() {
        val (avoid, focusAvoid) = RsiFocusSelection.avoidSets(setOf("c1", "low", "c3n", "covO"), covU = 3, covUFloor = 0, cooldownFocus = null)
        assertEquals(setOf("c3n"), avoid); assertEquals(setOf("c3n"), focusAvoid)
        val (a2, f2) = RsiFocusSelection.avoidSets(setOf("pref"), covU = 2, covUFloor = 2, cooldownFocus = "c1")
        assertEquals(setOf("pref", "covU"), a2); assertEquals(setOf("pref", "covU", "c1"), f2)
        val (a3, _) = RsiFocusSelection.avoidSets(emptySet(), covU = 3, covUFloor = 2, cooldownFocus = null)
        assertTrue("床より上の covU は焦点に残る", a3.isEmpty())
        val (a4, _) = RsiFocusSelection.avoidSets(emptySet(), covU = 0, covUFloor = 0, cooldownFocus = null)
        assertTrue("床 0 は no-op", a4.isEmpty())
    }

    // §10 既定 OFF と既定値
    @Test fun defaultsMatchTheSpec() {
        assertEquals(0.9, PolishGate.normalStallFraction, 0.0)
        assertFalse(PolishGate.stallPolishInjection)
        assertFalse(PolishGate.postChainRollbackCountsZero)
        assertEquals(WishFloorMode.OFF, PolishGate.wishConflictFloorMode)
        assertTrue(PolishGate.c3nWallShortStall)
        assertFalse("基準腕（HEAD の壁判定）は既定で使わない", PolishGate.c3nWallLegacy)
        assertTrue("後期演算は停止要求を見る（既定）", PolishGate.lateOpStopPropagation)
        assertFalse("1 手探索の反証は既定で使わない（測定中）", PolishGate.c3nWallDeepCheck)
        assertFalse("適応閾値は既定で使わない（測定中）", PolishGate.adaptiveStall)
        assertEquals(2, V6FinalPort.STALL_OVERRIDE_FACTOR)
        assertEquals(3 to 3, V6FinalPort.ADAPTIVE_STALL_FACTOR to V6FinalPort.ADAPTIVE_STALL_MIN_GAPS); assertEquals(8, V6FinalPort.ADAPTIVE_STALL_WINDOW)
        assertEquals(5000, Hf63Infeasibility.INFEAS_STALL_ITERS)
        assertEquals(3, StallPolishInjection.MAX_INJECTIONS)
        assertEquals(6_000L, StallPolishInjection.CAP_MS)
    }

    // §10 測定スイッチは背景実行への引き継ぎ（snapshot/restore）を往復する
    @Test fun wallMeasurementSwitchesRoundTripThroughSnapshot() {
        val saved = PolishGate.snapshot()
        try {
            PolishGate.c3nWallLegacy = true
            PolishGate.lateOpStopPropagation = false
            PolishGate.c3nWallDeepCheck = true
            PolishGate.adaptiveStall = true
            val snap = PolishGate.snapshot()
            PolishGate.c3nWallLegacy = false
            PolishGate.lateOpStopPropagation = true
            PolishGate.c3nWallDeepCheck = false
            PolishGate.adaptiveStall = false
            PolishGate.restore(snap)
            assertTrue("c3nWallLegacy は往復する", PolishGate.c3nWallLegacy)
            assertFalse("lateOpStopPropagation は往復する", PolishGate.lateOpStopPropagation)
            assertTrue("c3nWallDeepCheck は往復する", PolishGate.c3nWallDeepCheck)
            assertTrue("adaptiveStall は往復する", PolishGate.adaptiveStall)
        } finally { PolishGate.restore(saved) }
    }

    // §5.8 C 適応閾値: 間隔 3 個未満は使わない、最大×3 を [短, 通常] に挟む、通常分岐だけを縮める（床・壁の短い閾値はそのまま）
    @Test fun adaptiveStallNeedsThreeGapsAndClampsBetweenShortAndNormal() {
        assertNull(V6FinalPort.adaptiveStallMs(listOf(5_000L, 6_000L), 37_500L, 270_000L))
        assertEquals("最大 20 s × 3 = 60 s", 60_000L, V6FinalPort.adaptiveStallMs(listOf(5_000L, 20_000L, 6_000L), 37_500L, 270_000L))
        assertEquals("短い閾値より下には行かない", 37_500L, V6FinalPort.adaptiveStallMs(listOf(1_000L, 2_000L, 3_000L), 37_500L, 270_000L))
        assertEquals("通常閾値より上には行かない", 270_000L, V6FinalPort.adaptiveStallMs(listOf(100_000L, 100_000L, 100_000L), 37_500L, 270_000L))
        assertEquals("短>通常の帯（20 s 予算）でも通常を超えない", 9_000L, V6FinalPort.adaptiveStallMs(listOf(1_000L, 1_000L, 1_000L), 10_000L, 9_000L))
    }

    @Test fun adaptiveOnlyShortensTheNormalBranch() {
        val normal = V6FinalPort.effectiveStallMs(3, 0, 3, false, false, 37_500L, 270_000L)
        assertEquals(270_000L, normal)
        assertEquals("通常分岐は適応値まで縮む", 60_000L, V6FinalPort.effectiveStallMs(3, 0, 3, false, false, 37_500L, 270_000L, adaptiveMs = 60_000L))
        assertEquals("plateau の短い閾値は変わらない", 37_500L, V6FinalPort.effectiveStallMs(0, 0, 0, false, false, 37_500L, 270_000L, adaptiveMs = 60_000L))
        assertEquals("null＝既定と同じ", normal, V6FinalPort.effectiveStallMs(3, 0, 3, false, false, 37_500L, 270_000L, adaptiveMs = null))
    }

    @Test fun observeRecordsGapsBetweenImprovementsOnlyAndKeepsTheLastEight() {
        val wd = V6FinalPort.WatchdogBest(startMs = 0L)
        wd.observe(rep(5, 500.0, 5), 10_000L, 1L, { true }, 0)
        assertEquals("最初の改善までは間隔ではない", emptyList<Long>(), wd.recentGaps())
        var w = 500.0
        for (k in 1..10) { w -= 1.0; wd.observe(rep(5, w, 5), 10_000L + k * 1_000L * k, 1L + k, { true }, 0) }
        val gaps = wd.recentGaps()
        assertEquals(8, gaps.size)
        assertEquals("古い順・直近 8 個（間隔 k² − (k−1)² × 1000 の k=3..10）", (3..10).map { (it * it - (it - 1) * (it - 1)) * 1_000L }, gaps)
        assertFalse("悪化は間隔に数えない", wd.observe(rep(5, w + 1, 5), 999_000L, 99L, { true }, 0))
        assertEquals(8, wd.recentGaps().size)
    }

    // §5.4 判定と発火の間に改善が割り込んだら発火しない（判定後の改善の順序を検査する）
    @Test fun fireIsRefusedWhenAnImprovementInterleavesBetweenDecisionAndFire() {
        val wd = V6FinalPort.WatchdogBest(startMs = 0L)
        wd.observe(rep(1, 100.0, 5), nowMs = 1_000L, observedIters = 10L, beatsInput = { true }, wishC3wProven = 0)
        val gen = wd.bestVersion.get()                       // 判定した世代
        wd.observe(rep(1, 99.0, 5), nowMs = 200_000L, observedIters = 20L, beatsInput = { false }, wishC3wProven = 0)
        assertFalse("判定後に改善が届いたら発火しない", wd.fireIfGeneration(gen, nowMs = 400_000L, observedIters = 30L, byOverride = false, wall = false))
        assertFalse("古い判定で停滞ラッチを立て直さない", wd.stagnationFired.get())
        val gen2 = wd.bestVersion.get()
        assertTrue("世代が変わらなければ発火する", wd.fireIfGeneration(gen2, nowMs = 400_000L, observedIters = 30L, byOverride = false, wall = true))
        assertTrue(wd.stagnationFired.get()); assertTrue(wd.stagnationWall.get())
    }

    // §5.4 一度確定した停滞ラッチは、同じ世代の後続の判定で時刻・反復数・壁の記録を上書きしない
    @Test fun repeatedDecisionDoesNotOverwriteALatchedFire() {
        val wd = V6FinalPort.WatchdogBest(startMs = 0L)
        wd.observe(rep(1, 100.0, 5), nowMs = 1_000L, observedIters = 10L, beatsInput = { true }, wishC3wProven = 0)
        val gen = wd.bestVersion.get()
        assertTrue(wd.fireIfGeneration(gen, nowMs = 400_000L, observedIters = 30L, byOverride = false, wall = false))
        val duration = wd.stagnationDurationMs.get()
        assertTrue(wd.fireIfGeneration(gen, nowMs = 900_000L, observedIters = 90L, byOverride = true, wall = true))
        assertEquals("確定済みの発火は時刻を上書きしない", duration, wd.stagnationDurationMs.get())
        assertEquals(30L, wd.stagnationIters.get())
        assertFalse("確定済みの発火は壁の記録を上書きしない", wd.stagnationWall.get())
    }

    // §5.3 診断キャッシュは盤面の内容で鍵をとり、別盤面の結果を返さない
    @Test fun boardKeyedFlagNeverReturnsAnotherBoardsResult() {
        val flag = V6FinalPort.BoardKeyedFlag()
        val x = listOf(listOf(1, 2), listOf(3, 4))
        val y = listOf(listOf(9, 9), listOf(9, 9))
        var evals = 0
        val eval: (List<List<Int>>) -> Boolean = { b -> evals++; b == x }
        assertTrue(flag.get(x, eval)); assertEquals(1, evals)
        assertTrue("内容が同じ盤面は再診断しない", flag.get(listOf(listOf(1, 2), listOf(3, 4)), eval)); assertEquals(1, evals)
        assertFalse("別の盤面の結果は返さない", flag.get(y, eval)); assertEquals(2, evals)
        assertTrue("戻っても、その盤面自身の結果で判定する", flag.get(x, eval)); assertEquals(3, evals)
    }

    // §5.3 壁の証拠は、生存盤面の報告が最良の報告と同じ参照のときだけ使う（値が等しいだけでは使わない）
    @Test fun c3nWallBindsOnlyToTheSameReportObject() {
        val a = rep(2, 18000.0, 4, "c3n" to 2, "covU" to 0)
        val same = rep(2, 18000.0, 4, "c3n" to 2, "covU" to 0)
        assertFalse(V6FinalPort.c3nWallSameReport(null, a))
        assertFalse(V6FinalPort.c3nWallSameReport(a, null))
        assertTrue(V6FinalPort.c3nWallSameReport(a, a))
        assertFalse("値が同じでも別の報告は同じ盤面とみなさない", V6FinalPort.c3nWallSameReport(same, a))
    }

    // §5.3 既定の判定は、生存盤面の報告が最良と同じ参照のときだけ診断を使う（値が同じ別の報告では使わない）
    @Test fun c3nWallProofUsesOnlyTheLiveBoardThatIsTheBestReport() {
        val best = rep(2, 18000.0, 4, "c3n" to 2, "covU" to 0)
        val sameValues = rep(2, 18000.0, 4, "c3n" to 2, "covU" to 0)
        val board = listOf(listOf(1, 2), listOf(3, 4))
        var diagCalls = 0
        var live = V6NativeOptimizer.LiveBestSnapshot(best, board)
        val proof = V6FinalPort.C3nWallProof(
            bestVersion = { 1 }, bestReport = { best }, liveSnapshot = { live }, diagnose = { diagCalls++; true },
        )
        assertTrue("同じ参照の生存盤面は診断の結果を使う", proof.bound())
        assertEquals(1, diagCalls)
        live = V6NativeOptimizer.LiveBestSnapshot(sameValues, board)
        assertFalse("値が同じでも別の参照の生存盤面は使わない", V6FinalPort.C3nWallProof(
            bestVersion = { 1 }, bestReport = { best }, liveSnapshot = { live }, diagnose = { true },
        ).bound())
    }

    // §5.3 段の境界で生存盤面が空になっても、同じ最良版で成立した判定は持ち越す。版が変われば持ち越さない
    @Test fun c3nWallProofCarriesItsVerdictAcrossAStageBoundaryOnly() {
        val best = rep(2, 18000.0, 4, "c3n" to 2, "covU" to 0)
        val board = listOf(listOf(1, 2), listOf(3, 4))
        var version = 1
        var live: V6NativeOptimizer.LiveBestSnapshot? = V6NativeOptimizer.LiveBestSnapshot(best, board)
        val proof = V6FinalPort.C3nWallProof(
            bestVersion = { version }, bestReport = { best }, liveSnapshot = { live }, diagnose = { true },
        )
        assertTrue(proof.bound())
        live = null
        assertTrue("同じ最良版なら持ち越す", proof.bound())
        version = 2
        assertFalse("版が変われば持ち越さない", proof.bound())
    }

    // §5.3 診断の間に生存盤面が入れ替わったら、その判定は使わない
    @Test fun c3nWallProofRefusesAVerdictWhenTheLiveBoardChangesDuringDiagnosis() {
        val best = rep(2, 18000.0, 4, "c3n" to 2, "covU" to 0)
        val board = listOf(listOf(1, 2), listOf(3, 4))
        var live = V6NativeOptimizer.LiveBestSnapshot(best, board)
        val proof = V6FinalPort.C3nWallProof(
            bestVersion = { 1 }, bestReport = { best }, liveSnapshot = { live },
            diagnose = { live = V6NativeOptimizer.LiveBestSnapshot(best, listOf(listOf(9))); true },
        )
        assertFalse("診断の間に入れ替わったら判定を使わない", proof.bound())
    }

    // 測定の基準腕は HEAD の判定: 報告の参照を見ず、生存盤面の盤面を版ごとに一度だけ診断する
    @Test fun c3nWallLegacyArmDiagnosesTheLiveBoardOncePerVersion() {
        val best = rep(2, 18000.0, 4, "c3n" to 2, "covU" to 0)
        val other = rep(2, 18000.0, 4, "c3n" to 1, "pref" to 1)
        var version = 1
        var diagCalls = 0
        val proof = V6FinalPort.C3nWallProof(
            bestVersion = { version }, bestReport = { best },
            liveSnapshot = { V6NativeOptimizer.LiveBestSnapshot(other, listOf(listOf(1))) },
            diagnose = { diagCalls++; true },
        )
        assertTrue(proof.legacy())
        assertTrue(proof.legacy())
        assertEquals("同じ版では一度だけ診断する", 1, diagCalls)
        version = 2
        proof.legacy()
        assertEquals(2, diagCalls)
    }

    // ログの件数は呼び出し回数ではなく、生存盤面の更新ごとに数える
    @Test fun c3nWallProofCountsEachLiveBoardUpdateOnceNotEachPoll() {
        val best = rep(2, 18000.0, 4, "c3n" to 2, "covU" to 0)
        val other = rep(2, 18000.0, 4, "c3n" to 1, "pref" to 1)
        val snap = V6NativeOptimizer.LiveBestSnapshot(other, listOf(listOf(1)))
        val proof = V6FinalPort.C3nWallProof(
            bestVersion = { 1 }, bestReport = { best }, liveSnapshot = { snap }, diagnose = { true },
        )
        repeat(50) { proof.bound() }
        assertEquals("同じ生存盤面は一度だけ数える", 1, proof.checks.get())
        assertEquals("対応しなかった更新も一度だけ数える", 1, proof.mismatch.get())
    }
}
