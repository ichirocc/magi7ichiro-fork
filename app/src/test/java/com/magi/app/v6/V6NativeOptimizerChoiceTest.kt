package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Random

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

    // [実機ログ起因=公平化のズレ] apt(適切回数)は旧 order に無く RSI 探索中は一度も focus されなかった
    //   （実データ検証: apt L1偏差合計37 vs staffRange低/高はわずか3）。maxViolatedFamily の SOFT
    //   フォールバックが apt を件数最大の族として選べることを固定する。
    private fun report(breakdown: Map<String, Int>, hard: Int = 0): ViolationReport = ViolationReport(
        violations = emptyMap(), needViolations = emptyMap(), countViolations = emptyMap(),
        breakdown = breakdown, total = breakdown.values.sum(), hard = hard,
        soft = breakdown.values.sum() - hard, weightedScore = breakdown.values.sum().toDouble(),
    )

    @Test fun maxViolatedFamilyPicksAptWhenDominantSoft() {
        val r = report(mapOf("apt" to 5, "c2" to 1, "covO" to 2))
        assertEquals("apt", V6NativeOptimizer.maxViolatedFamily(r))
    }

    @Test fun maxViolatedFamilyStillPrioritizesHardOverApt() {
        // [回帰] apt追加が既存のHARD優先ルール(D1/A1)を壊さないこと。
        val r = report(mapOf("c3n" to 1, "apt" to 100), hard = 1)
        assertEquals("c3n", V6NativeOptimizer.maxViolatedFamily(r))
    }

    @Test fun maxViolatedFamilyFallsBackToTotalWhenAllZero() {
        // [回帰/E8] 全族0件なら apt 追加後も従来どおり "total"（件数0族を focus しない）。
        val r = report(emptyMap())
        assertEquals("total", V6NativeOptimizer.maxViolatedFamily(r))
    }

    // [smoke] focus="apt" が destroyRepairStaff 経路(low/high/c2と合流)へ正しくルーティングされ、
    //   例外なく同一次元の盤面を返すこと。改善量そのものはラウンド単位 keep-best が別途保証する。
    @Test fun rsiGenerateHypothesisAptFocusReturnsValidSchedule() {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("P", "P", "1", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("a", 0), Staff("b", 0))
        val schedule = listOf(listOf(0, 1, 0, 1), listOf(1, 0, 1, 0))
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-04",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "1")),
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val base = st.schedule.toIntArray2D()
        val rep = UnifiedViolationChecker.check(st, base)
        val out = V6NativeOptimizer.rsiGenerateHypothesis(st, base, rep, "apt", Random(1))
        assertNotNull(out)
        assertEquals(base.size, out.size)
        assertEquals(base[0].size, out[0].size)
    }
}
