package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Test

class V6NativeOptimizerChoiceTest {
    @Test fun autoBudgetChoosesExpectedAlgorithm() {
        // [5分圧縮] 上限300sでも RSI++ に到達するよう前倒し（≤30 V5 / ≤90 ALNS / ≤210 RSI / それ以上 RSI++）。
        assertEquals(V6Algorithm.V5, V6NativeOptimizer.chooseAlgorithm(V6Algorithm.AUTO, 10))
        assertEquals(V6Algorithm.ALNS, V6NativeOptimizer.chooseAlgorithm(V6Algorithm.AUTO, 60))
        assertEquals(V6Algorithm.RSI, V6NativeOptimizer.chooseAlgorithm(V6Algorithm.AUTO, 150))
        assertEquals(V6Algorithm.RSI_PLUS, V6NativeOptimizer.chooseAlgorithm(V6Algorithm.AUTO, 300))
        assertEquals(V6Algorithm.ALNS, V6NativeOptimizer.chooseAlgorithm(V6Algorithm.ALNS, 10))
    }

    @Test fun roleProfilesDiversifyWithBaselineFirst() {
        // [HF290 役割分担] W0 は必ず 1.0（ベースライン＝退化防止）、以降は探索(>1)/精製(<1)で多様化、範囲外は 1.0。
        assertEquals(1.0, V6NativeOptimizer.roleExploreFor(0), 1e-9)
        assertEquals(2.0, V6NativeOptimizer.roleExploreFor(1), 1e-9)   // 探索
        assertEquals(0.5, V6NativeOptimizer.roleExploreFor(2), 1e-9)   // 精製
        assertEquals(1.6, V6NativeOptimizer.roleExploreFor(3), 1e-9)
        assertEquals(0.6, V6NativeOptimizer.roleExploreFor(4), 1e-9)
        assertEquals(1.0, V6NativeOptimizer.roleExploreFor(7), 1e-9)   // 範囲外=既定
    }

    @Test fun greatDelugeLevelDecaysFromInitialToBest() {
        // [論文活用] 時間予定型GD: frac=1(序盤)→initial、frac=0(終盤)→best へ線形降下、外れ値はクランプ。
        assertEquals(100.0, V6NativeOptimizer.greatDelugeLevel(100.0, 10.0, 1.0), 1e-9)
        assertEquals(10.0, V6NativeOptimizer.greatDelugeLevel(100.0, 10.0, 0.0), 1e-9)
        assertEquals(55.0, V6NativeOptimizer.greatDelugeLevel(100.0, 10.0, 0.5), 1e-9)
        assertEquals(100.0, V6NativeOptimizer.greatDelugeLevel(100.0, 10.0, 2.0), 1e-9)
        assertEquals(10.0, V6NativeOptimizer.greatDelugeLevel(100.0, 10.0, -1.0), 1e-9)
    }

    @Test fun roleAcceptAssignsGreatDelugeToSomeWorkers() {
        assertEquals(AcceptMode.SA, V6NativeOptimizer.roleAcceptFor(0))   // W0 ベースライン
        assertEquals(AcceptMode.SA, V6NativeOptimizer.roleAcceptFor(1))
        assertEquals(AcceptMode.GREAT_DELUGE, V6NativeOptimizer.roleAcceptFor(2))
        // [陳腐化修正] W3 は後日 Lam-Delosme 適応冷却(LAM_ADAPTIVE)へ多様化された（roleAcceptFor 実装＋専用コード）。
        //   テストが旧SA期待のまま残り V6 Engine Check が恒常赤だったのを実装に追随。
        assertEquals(AcceptMode.LAM_ADAPTIVE, V6NativeOptimizer.roleAcceptFor(3))
        assertEquals(AcceptMode.GREAT_DELUGE, V6NativeOptimizer.roleAcceptFor(4))
    }
}
