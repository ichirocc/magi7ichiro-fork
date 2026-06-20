package com.magi.app.v6

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 戦略的振動（glsAccept の hardRelax）の検証。
 * hardRelax>0 の間だけ受理判定で hard 差分を割引し、実行不可の壁を越えやすくする。
 * 生スコア・globalBest は不変（呼び出し側で raw ゲート）＝Δ×フル無関係・解は退化しない設計。
 */
class StrategicOscillationTest {

    private val cur = 200L            // hard0, soft200（実行可能）
    private val infeasibleNs = 1_000_050L   // hard1(実行不可), soft50（soft は改善）

    @Test fun plainAcceptCannotCrossInfeasibleWall() {
        // hardRelax=0（従来）: hard ジャンプ(≈1e6)で受理確率が 0 に潰れ、壁を越えられない。
        val rng = Random(1)
        assertFalse(glsAccept(infeasibleNs, cur, 0.0, 0.0, AcceptMode.SA, temp = 10.0, gdLevel = 0.0, rng = rng, hardRelax = 0.0))
    }

    @Test fun oscillationRelaxLetsSearchCrossInfeasibleWall() {
        // hardRelax=0.9999: hard 差分を割引 → soft 改善(-150)が勝ち delta<=0 で確実に受理（壁越え）。
        val rng = Random(1)
        assertTrue(glsAccept(infeasibleNs, cur, 0.0, 0.0, AcceptMode.SA, temp = 10.0, gdLevel = 0.0, rng = rng, hardRelax = 0.9999))
    }

    @Test fun perStepCapStillBoundsExcursion() {
        // 振動中でも per-step 上限(ns > cur + 2_000_000)は維持＝実行不可への暴走を防ぐ。
        val rng = Random(1)
        val far = cur + 3_000_000L   // +3 hard 相当の大ジャンプ
        assertFalse(glsAccept(far, cur, 0.0, 0.0, AcceptMode.SA, temp = 10.0, gdLevel = 0.0, rng = rng, hardRelax = 0.9999))
    }

    @Test fun hardRelaxZeroIsBackwardCompatible() {
        // soft 改善(hard 不変)は従来どおり常に受理（delta<=0）。hardRelax 既定0で挙動不変。
        val rng = Random(1)
        assertTrue(glsAccept(100L, 200L, 0.0, 0.0, AcceptMode.SA, temp = 1.0, gdLevel = 0.0, rng = rng, hardRelax = 0.0))
    }
}
