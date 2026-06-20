package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HF63 の忠実移植検証。Web `HF63.runSelfTest()` の判定値（A=6250 / B=50000 / C=2500 / D=50000）を
 * そのまま再現して固定する。
 */
class Hf63InfeasibilityTest {

    @Test
    fun scenarioA_hardCovU_deprioritizedAfterStall() {
        val hf = Hf63Infeasibility()
        hf.update(8, 24, 0)        // CovU(HARD) 初回: best=24
        hf.update(8, 24, 5000)     // 5000 iter 改善なし → infeasible 判定
        assertTrue(hf.isInfeasibleLikely(8))
        assertEquals(6250, hf.maxLam(8))   // 50000 / 8
    }

    @Test
    fun scenarioB_selfCorrectionRestoresFullLambda() {
        val hf = Hf63Infeasibility()
        hf.update(8, 24, 0)
        hf.update(8, 24, 5000)             // deprioritize
        hf.update(8, 10, 12000)            // 改善検出 → フラグ解除
        assertFalse(hf.isInfeasibleLikely(8))
        assertEquals(50000, hf.maxLam(8))  // 平常復帰
    }

    @Test
    fun scenarioC_softLimMax_deprioritized() {
        val hf = Hf63Infeasibility()
        hf.update(12, 18, 0)               // LimMax(SOFT) best=18
        hf.update(12, 18, 5000)            // stall → infeasible
        assertTrue(hf.isInfeasibleLikely(12))
        assertEquals(2500, hf.maxLam(12))  // 10000 / 4
    }

    @Test
    fun scenarioD_activeConstraintNeverFlagged() {
        val hf = Hf63Infeasibility()
        for (i in 0 until 10000) {
            hf.update(3, 100 - i / 100, i)  // C3n が改善し続ける
        }
        assertFalse(hf.isInfeasibleLikely(3))
        assertEquals(50000, hf.maxLam(3))   // HARD 平常維持
    }

    @Test
    fun sentinelSkipsUntrackedAndBreakdownMapsFamilies() {
        val hf = Hf63Infeasibility()
        // covU=8 を停滞させ、c3n=3 は SENTINEL でスキップ
        val arr = IntArray(Hf63Infeasibility.N_CONSTRAINTS) { Hf63Infeasibility.SENTINEL }
        arr[8] = 5
        hf.updateBatch(arr, 0)
        hf.updateBatch(arr, 5000)
        assertTrue(hf.isInfeasibleLikely(8))
        assertFalse(hf.isInfeasibleLikely(3))   // SENTINEL のため未追跡

        // breakdown 経由でも covU が追える
        val hf2 = Hf63Infeasibility()
        hf2.updateFromBreakdown(mapOf("covU" to 7), 0)
        hf2.updateFromBreakdown(mapOf("covU" to 7), 5000)
        assertTrue(hf2.isInfeasibleLikely(8))
        assertEquals(listOf("CovU"), hf2.infeasibleFamilies())
    }

    @Test
    fun infeasibleBreakdownKeysForFocusAvoidance() {
        val hf = Hf63Infeasibility()
        // covU と pref を停滞させる
        hf.updateFromBreakdown(mapOf("covU" to 3, "pref" to 2, "c1" to 5), 0)
        hf.updateFromBreakdown(mapOf("covU" to 3, "pref" to 2, "c1" to 1), 5000) // c1 は改善
        val avoid = hf.infeasibleBreakdownKeys()
        assertTrue("covU" in avoid)
        assertTrue("pref" in avoid)
        assertFalse("c1" in avoid)   // 改善中の族は回避しない
    }

    @Test
    fun weightFactorReflectsDeprioritization() {
        val hf = Hf63Infeasibility()
        assertEquals(1.0, hf.weightFactor(8), 1e-9)   // 平常
        hf.update(8, 3, 0); hf.update(8, 3, 5000)
        assertEquals(0.125, hf.weightFactor(8), 1e-9) // HARD infeasible → 1/8
    }

    @Test
    fun hardInfeasibleLikelyGatesStrategicOscillation() {
        val hf = Hf63Infeasibility()
        assertFalse(hf.hardInfeasibleLikely())          // 初期: 振動は発動しない
        // SOFT 族(LimMax=12)だけ詰まっても HARD ゲートは立たない(hard を緩める意味がないため)
        hf.update(12, 5, 0); hf.update(12, 5, 5000)
        assertTrue(hf.isInfeasibleLikely(12))
        assertFalse(hf.hardInfeasibleLikely())
        // HARD 族(covU=8)が構造的に詰まると ゲート ON → 戦略的振動が選択的に発動
        hf.update(8, 2, 6000); hf.update(8, 2, 11000)
        assertTrue(hf.hardInfeasibleLikely())
    }
}
