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
        // [回帰/E8] 全族0件なら apt/weekly/fair 追加後も従来どおり "total"（件数0族を focus しない）。
        val r = report(emptyMap())
        assertEquals("total", V6NativeOptimizer.maxViolatedFamily(r))
    }

    // [同根の穴=weekly/fair] apt と同じ理由で未focusだった weekly/fair も件数最大なら選ばれること
    //   （実データ検証: weekly L1偏差合計65はaptの37より大きい・fair合計11）。
    @Test fun maxViolatedFamilyPicksWeeklyWhenDominantSoft() {
        val r = report(mapOf("weekly" to 65, "apt" to 37, "fair" to 11))
        assertEquals("weekly", V6NativeOptimizer.maxViolatedFamily(r))
    }

    @Test fun maxViolatedFamilyPicksFairWhenDominantSoft() {
        val r = report(mapOf("fair" to 11, "c2" to 1))
        assertEquals("fair", V6NativeOptimizer.maxViolatedFamily(r))
    }

    // [3.204.0/実機ログ起因] covO は日×シフトのセル単独違反のため件数が常に一桁台に留まり、
    // c1/c42/weekly のような数十件規模の族に「件数最大」選択で恒久的に勝てない
    // （CoverageDiag診断で「動かせる」と出たcovOセルが300秒経っても解消されないことを実機ログで確認）。
    // 3ラウンドに1回、count>0のcovOを件数によらず優先する周期的保証枠を固定する。
    @Test fun maxViolatedFamilyGivesCovOPeriodicSlotEvenWhenSmall() {
        val r = report(mapOf("c1" to 87, "covO" to 2))
        assertEquals("covO", V6NativeOptimizer.maxViolatedFamily(r, round = 2))
        assertEquals("covO", V6NativeOptimizer.maxViolatedFamily(r, round = 5))
    }

    @Test fun maxViolatedFamilyCovOPeriodicSlotDoesNotFireOnOtherRounds() {
        // [回帰] round%3==2 以外は従来どおり件数最大(c1)が選ばれること。
        val r = report(mapOf("c1" to 87, "covO" to 2))
        assertEquals("c1", V6NativeOptimizer.maxViolatedFamily(r, round = 0))
        assertEquals("c1", V6NativeOptimizer.maxViolatedFamily(r, round = 1))
        assertEquals("c1", V6NativeOptimizer.maxViolatedFamily(r))   // round省略(-1)も従来どおり
    }

    @Test fun maxViolatedFamilyStillPrioritizesHardOverCovOPeriodicSlot() {
        // [回帰] covO周期枠の追加がD1/A1のHARD優先ルールを壊さないこと。
        val r = report(mapOf("c3n" to 1, "covO" to 5), hard = 1)
        assertEquals("c3n", V6NativeOptimizer.maxViolatedFamily(r, round = 2))
    }

    @Test fun maxViolatedFamilyCovOPeriodicSlotRespectsAvoid() {
        // [回帰] E9冷却等でcovOがavoidに入っていれば周期枠も発火せず通常選択にフォールバックすること。
        val r = report(mapOf("c1" to 87, "covO" to 2))
        assertEquals("c1", V6NativeOptimizer.maxViolatedFamily(r, avoid = setOf("covO"), round = 2))
    }

    // [3.207.0/実機ログ起因=3.204.0の周期枠が典型的な5ラウンドRSIで丸ごと空振りしていた] round%3==2 の
    // 唯一の該当ラウンド(0始まりで2番目)は、HF63がc3n/covUをdeprioritizeし終える前(約3ラウンドの停滞を要する)
    // に来てしまい、HARD優先ループがそのラウンドを消費してcovO分岐へ到達しなかった（実機ログ: round=3/5
    // focus=c3n、covO=6は最後まで不変）。HARDが本当に解けない場合はHF63が最終的にdeprioritizeし尽くすため、
    // RSIフェーズの最終ラウンドを追加の保証枠にする。
    @Test fun maxViolatedFamilyGivesCovOFinalRoundSlotEvenOutsidePeriodicModulo() {
        val r = report(mapOf("weekly" to 56, "covO" to 6))
        // round=4は5ラウンド構成(roundsTotal=5)の最終ラウンド(0始まり)。4%3=1で周期枠には該当しないが
        // 最終ラウンドとして発火し、件数最大(weekly=56)を上書きしてcovOが選ばれること。
        assertEquals("covO", V6NativeOptimizer.maxViolatedFamily(r, round = 4, roundsTotal = 5))
    }

    @Test fun maxViolatedFamilyFinalRoundSlotRequiresRoundsTotalToBeProvided() {
        // [回帰] roundsTotal省略(-1、旧経路)では最終ラウンド判定が無効化され従来どおり件数最大に戻ること。
        val r = report(mapOf("weekly" to 56, "covO" to 6))
        assertEquals("weekly", V6NativeOptimizer.maxViolatedFamily(r, round = 4))
    }

    @Test fun maxViolatedFamilyFinalRoundSlotStillRespectsHardPriorityAndAvoid() {
        // [回帰] 最終ラウンドの保証枠もHARD優先ルールとavoidを壊さないこと。
        val r1 = report(mapOf("c3n" to 1, "covO" to 6), hard = 1)
        assertEquals("c3n", V6NativeOptimizer.maxViolatedFamily(r1, round = 4, roundsTotal = 5))
        val r2 = report(mapOf("weekly" to 56, "covO" to 6))
        assertEquals("weekly", V6NativeOptimizer.maxViolatedFamily(r2, avoid = setOf("covO"), round = 4, roundsTotal = 5))
    }

    // [3.204.0] covO は markNeed(k,j) で needViolations に載り report.violations(セル"i,j"マップ)には
    // 現れないため、他の focus 未対応族が使う destroyRepairViolations では covO 専用のヒントが無かった。
    // applyCovOFree が「動かせる」在勤者を実際に他シフトへ移し covO を解消することを固定する。
    private fun covOState(schedule: List<List<Int>>, wishes: Map<String, Int> = emptyMap()): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-01",
        shifts = listOf(Shift("休み", "休", "", ""), Shift("早番", "A", "1", "")),
        groups = listOf(Group("G0", "G0")),
        staff = listOf(Staff("a", 0), Staff("b", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = schedule, wishes = wishes, staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun applyCovOFreeRelievesFreelyMovableSurplus() {
        val st = covOState(listOf(listOf(1), listOf(1)))   // 両者ともA（必要1に対し現状2＝過剰1）
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["covO"])
        val applied = V6NativeOptimizer.applyCovOFree(st, sched, Random(1))
        assertEquals(1, applied)
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals(0, after.breakdown["covO"] ?: 0)
        assertEquals(0, after.hard)
    }

    @Test fun applyCovOFreeLeavesWishPinnedSurplusUntouched() {
        // 両者ともAを希望固定（希望どおり配置済み）だと、動かすと希望未充足(pref)に化けるため何もしない。
        val st = covOState(listOf(listOf(1), listOf(1)), wishes = mapOf("0,0" to 1, "1,0" to 1))
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["covO"])
        val applied = V6NativeOptimizer.applyCovOFree(st, sched, Random(1))
        assertEquals(0, applied)
        assertEquals(1, sched[0][0])
        assertEquals(1, sched[1][0])
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
        // [smoke] weekly/fair も同じ経路（apt同様、専用オペレータ不要で例外なく完走すること）。
        for (focus in listOf("weekly", "fair")) {
            val out2 = V6NativeOptimizer.rsiGenerateHypothesis(st, base, rep, focus, Random(1))
            assertNotNull(out2)
            assertEquals(base.size, out2.size)
        }
    }

    // [smoke] focus="covO" が新設の applyCovOFree 経路へ正しくルーティングされ、例外なく同一次元の
    //   盤面を返すこと。実際の解消効果は applyCovOFreeRelievesFreelyMovableSurplus が直接検証する。
    @Test fun rsiGenerateHypothesisCovOFocusReturnsValidSchedule() {
        val st = covOState(listOf(listOf(1), listOf(1)))
        val base = st.schedule.toIntArray2D()
        val rep = UnifiedViolationChecker.check(st, base)
        val out = V6NativeOptimizer.rsiGenerateHypothesis(st, base, rep, "covO", Random(1))
        assertNotNull(out)
        assertEquals(base.size, out.size)
        assertEquals(base[0].size, out[0].size)
    }
}
