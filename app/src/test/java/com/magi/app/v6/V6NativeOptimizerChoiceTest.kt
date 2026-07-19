package com.magi.app.v6

import com.magi.app.model.C41Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    // [3.208.0/提供された全実機ログ(7本)でaptが同型のstarvationを起こしていたことを確認] apt は常に
    // breakdown 最小級(1〜11、他族の一桁〜二桁下)で、"focus=apt" は一度も出現せず"focus=weekly"のみが
    // 件数最大フォールバックで選ばれ続けていた。covOとは別の周期(round%3==1)と最終ラウンドをaptにも割当てる。
    @Test fun maxViolatedFamilyGivesAptPeriodicSlotEvenWhenSmall() {
        val r = report(mapOf("c1" to 87, "apt" to 1))
        assertEquals("apt", V6NativeOptimizer.maxViolatedFamily(r, round = 1))
        assertEquals("apt", V6NativeOptimizer.maxViolatedFamily(r, round = 4))
    }

    @Test fun maxViolatedFamilyAptPeriodicSlotDoesNotFireOnOtherRounds() {
        // [回帰] round%3==1 以外(かつ最終ラウンドでもない)は従来どおり件数最大(c1)が選ばれること。
        val r = report(mapOf("c1" to 87, "apt" to 1))
        assertEquals("c1", V6NativeOptimizer.maxViolatedFamily(r, round = 0))
        assertEquals("c1", V6NativeOptimizer.maxViolatedFamily(r, round = 2))
        assertEquals("c1", V6NativeOptimizer.maxViolatedFamily(r))   // round省略(-1)も従来どおり
    }

    @Test fun maxViolatedFamilyAptAndCovOPeriodicSlotsDoNotCollide() {
        // [回帰] apt(round%3==1)とcovO(round%3==2)は別ラウンドに割当てられ、互いを上書きしないこと。
        val r = report(mapOf("c1" to 87, "apt" to 1, "covO" to 6))
        assertEquals("apt", V6NativeOptimizer.maxViolatedFamily(r, round = 1))
        assertEquals("covO", V6NativeOptimizer.maxViolatedFamily(r, round = 2))
    }

    @Test fun maxViolatedFamilyFinalRoundPrefersAptOverCovOWhenBothPresent() {
        // aptはcovOよりさらに恒常的に不利(実機ログで常に最小級)なため、最終ラウンドでの取り合いでは
        // aptを優先する。
        val r = report(mapOf("weekly" to 56, "apt" to 1, "covO" to 6))
        assertEquals("apt", V6NativeOptimizer.maxViolatedFamily(r, round = 4, roundsTotal = 5))
    }

    @Test fun maxViolatedFamilyAptSlotStillRespectsHardPriorityAndAvoid() {
        // [回帰] aptの保証枠もHARD優先ルールとavoidを壊さないこと。
        val r1 = report(mapOf("c3n" to 1, "apt" to 1), hard = 1)
        assertEquals("c3n", V6NativeOptimizer.maxViolatedFamily(r1, round = 1))
        val r2 = report(mapOf("c1" to 87, "apt" to 1))
        assertEquals("c1", V6NativeOptimizer.maxViolatedFamily(r2, avoid = setOf("apt"), round = 1))
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

    // [3.209.0] c41/c41s は covO と同じく markNeed(needViolations) にしか載らず、GLSキック/
    // destroyRepairViolations が一切ヒントを持てない。applyC41Free が群レンジ[l,u]の超過・不足を
    // 実際に動かして解消することを固定する。
    private fun c41State(schedule: List<List<Int>>, l: String, u: String, wishes: Map<String, Int> = emptyMap()): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-01",
        shifts = listOf(Shift("休み", "Y", "", ""), Shift("勤務", "X", "", "")),
        groups = listOf(Group("G0", "G0")),
        staff = listOf(Staff("a", 0), Staff("b", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = schedule, wishes = wishes, staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = listOf(C41Row(groupKigou = "G0", shiftKigou = "X", l = l, u = u)),
        cons42 = emptyList(),
    )

    @Test fun applyC41FreeRelievesFreelyMovableExcess() {
        // 群定員[0,1]に対し2名がXに在籍＝超過1。両者とも希望固定なしなので1名が動かせるはず。
        val st = c41State(listOf(listOf(1), listOf(1)), l = "0", u = "1")
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["c41"] ?: 0)
        val applied = V6NativeOptimizer.applyC41Free(st, sched, Random(1), skill = false)
        assertEquals(1, applied)
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals(0, after.breakdown["c41"] ?: 0)
        assertEquals(0, after.hard)
    }

    @Test fun applyC41FreeFillsFreelyMovableDeficiency() {
        // 群定員[1,2]に対し0名がXに在籍＝不足1。両者ともYで希望固定なしなので1名を引き込めるはず。
        val st = c41State(listOf(listOf(0), listOf(0)), l = "1", u = "2")
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["c41"] ?: 0)
        val applied = V6NativeOptimizer.applyC41Free(st, sched, Random(1), skill = false)
        assertEquals(1, applied)
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals(0, after.breakdown["c41"] ?: 0)
        assertEquals(0, after.hard)
    }

    @Test fun applyC41FreeLeavesWishPinnedExcessUntouched() {
        // 両者ともXを希望固定（希望どおり配置済み）だと、動かすと希望未充足に化けるため何もしない。
        val st = c41State(listOf(listOf(1), listOf(1)), l = "0", u = "1", wishes = mapOf("0,0" to 1, "1,0" to 1))
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["c41"] ?: 0)
        val applied = V6NativeOptimizer.applyC41Free(st, sched, Random(1), skill = false)
        assertEquals(0, applied)
        assertEquals(1, sched[0][0])
        assertEquals(1, sched[1][0])
    }

    @Test fun applyC41FreeIsNoOpWhenRulesEmpty() {
        val st = c41State(listOf(listOf(1), listOf(1)), l = "0", u = "1").copy(cons41 = emptyList())
        val sched = st.schedule.toIntArray2D()
        assertEquals(0, V6NativeOptimizer.applyC41Free(st, sched, Random(1), skill = false))
    }

    // [監査(他の制約は大丈夫か)/玉突き連鎖の横展開その4] 旧実装は「離脱元/到着先どちらもcovU/covO
    //   非悪化」の直接移動が見つからないと即座に諦めていた。c3mn(3.214.0)/RangePolish(3.215.0)/
    //   C3RunPolishと同型の穴: 離脱元シフト(X)自体が全体needをちょうど単独充足しており、誰か1人が
    //   離脱すると即covU化する構造的にブロックされた局面。findCovUChainで別職員に玉突き充填すれば
    //   解消できることを固定する。
    @Test fun applyC41FreeResolvesExcessViaChainWhenDirectMoveWouldCreateCovU() {
        // shift: 0=休(need無) 1=X(c41対象、need1=2でA/Bちょうど単独充足) 2=Y(need無、Aの逃げ先)
        //   3=Z(need無、Cの現在地)
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("X", "X", "2", ""),
            Shift("Y", "Y", "", ""), Shift("Z", "Z", "", ""),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"))
        val groupShift = listOf(
            listOf(1, 1, 1, 0), // G0(A,B)=休,X,Y
            listOf(1, 1, 1, 1), // G1(C)=休,X,Y,Z
        )
        val staff = listOf(Staff("A", 0), Staff("B", 0), Staff("C", 1))
        val schedule = listOf(listOf(1), listOf(1), listOf(3)) // A=X, B=X（G0のX在籍2名=超過1、Xのneed1=2をちょうど充足）
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(2) { List(4) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = listOf(C41Row(groupKigou = "G0", shiftKigou = "X", l = "0", u = "1")),
            cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["c41"] ?: 0)
        assertEquals(0, before.hard)   // Xはneed1=2をA/Bがちょうど充足＝離脱すると即covU化する構造的にブロックされた局面

        val applied = V6NativeOptimizer.applyC41Free(st, sched, Random(1), skill = false)
        assertTrue("玉突き連鎖を含め何らかの手が採用されている", applied > 0)
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("c41超過が解消", 0, after.breakdown["c41"] ?: -1)
        assertEquals("HARDは悪化しない", 0, after.hard)
        assertEquals("Xの被覆(covU)は悪化しない", 0, after.breakdown["covU"] ?: -1)
    }

    @Test fun applyC41FreeResolvesDeficiencyViaChainWhenDirectMoveWouldCreateCovU() {
        // shift: 0=休(need無) 1=X(c41対象、need無) 2=Y(need1=1、Aの現在地) 3=W(need1=1、Bの現在地)
        //   4=Z(need無、Cの現在地)。offShift候補(A,B)がどちらもneed1を単独充足しており直接移動は不可。
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("X", "X", "", ""),
            Shift("Y", "Y", "1", ""), Shift("W", "W", "1", ""), Shift("Z", "Z", "", ""),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"))
        val groupShift = listOf(
            listOf(1, 1, 1, 1, 0), // G0(A,B)=休,X,Y,W
            listOf(1, 0, 1, 1, 1), // G1(C)=休,Y,W,Z
        )
        val staff = listOf(Staff("A", 0), Staff("B", 0), Staff("C", 1))
        val schedule = listOf(listOf(2), listOf(3), listOf(4)) // A=Y, B=W（G0のX在籍0名=不足1）, C=Z
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(2) { List(5) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = listOf(C41Row(groupKigou = "G0", shiftKigou = "X", l = "1", u = "2")),
            cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["c41"] ?: 0)
        assertEquals(0, before.hard)

        val applied = V6NativeOptimizer.applyC41Free(st, sched, Random(1), skill = false)
        assertTrue("玉突き連鎖を含め何らかの手が採用されている", applied > 0)
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("c41不足が解消", 0, after.breakdown["c41"] ?: -1)
        assertEquals("HARDは悪化しない", 0, after.hard)
        assertEquals("Y/Wの被覆(covU)は悪化しない", 0, after.breakdown["covU"] ?: -1)
    }

    // [smoke] focus="c41"/"c41s" が新設の applyC41Free 経路へ正しくルーティングされ、例外なく
    //   同一次元の盤面を返すこと。実際の解消効果は上記の直接テストが検証する。
    @Test fun rsiGenerateHypothesisC41FocusReturnsValidSchedule() {
        val st = c41State(listOf(listOf(1), listOf(1)), l = "0", u = "1")
        val base = st.schedule.toIntArray2D()
        val rep = UnifiedViolationChecker.check(st, base)
        for (focus in listOf("c41", "c41s")) {
            val out = V6NativeOptimizer.rsiGenerateHypothesis(st, base, rep, focus, Random(1))
            assertNotNull(out)
            assertEquals(base.size, out.size)
            assertEquals(base[0].size, out[0].size)
        }
    }

    // [余剰ワーカー活用] perHypothesisWorkers: 仕様§2.2の5仮説上限を超えて設定したworkersを、
    // 各仮説の内部並列度（RSI/RSI++のSAチェーン数・ALNSの多チェーン）へ均等配分する計算のみを固定する
    // 純粋関数テスト（並列実行そのものはJVMユニットテストでは検証しない＝実機ログ/CIビルドで確認）。
    @Test fun perHypothesisWorkersDistributesSurplusEvenly() {
        assertEquals(1, V6NativeOptimizer.perHypothesisWorkers(workers = 1, hypotheses = 5))
        assertEquals(1, V6NativeOptimizer.perHypothesisWorkers(workers = 5, hypotheses = 5))
        // 実機ログ実例: workers設定8・仮説5 → 8/5=1(切り捨て)。旧実装は常に1だったのでこの値は不変。
        assertEquals(1, V6NativeOptimizer.perHypothesisWorkers(workers = 8, hypotheses = 5))
        // workers=16・仮説5 → 16/5=3。旧実装なら5を超えた11ワーカー分が完全に無駄だった。
        assertEquals(3, V6NativeOptimizer.perHypothesisWorkers(workers = 16, hypotheses = 5))
    }

    @Test fun perHypothesisWorkersNeverReturnsLessThanOne() {
        assertEquals(1, V6NativeOptimizer.perHypothesisWorkers(workers = 0, hypotheses = 5))
        // hypotheses=0 は縮退入力（実際は coerceIn(1,5) 済みで到達しない）。内側ガード max(1,hypotheses)=1 に
        //   より 3/1=3 が返る＝「1未満を返さない」性質は満たす（初版の期待値1は誤り→CI失敗で検出・修正）。
        assertEquals(3, V6NativeOptimizer.perHypothesisWorkers(workers = 3, hypotheses = 0))
    }

    // [敵対的レビュー3.212.0] hypothesisChainPlan: 余り配分（6〜9帯で余剰が実際に使われる=旧perW床のみの
    //   「無駄にならない」虚偽表示の是正）＋コア数クランプ（壁時計締切下の希釈=品質逆行リスクの回避）。
    @Test fun chainPlanDistributesRemainderToLeadingHypotheses() {
        // 動機となった実機ログ当該ケース: workers=8/8コア → [2,2,2,1,1]（旧perW床は全仮説1で余剰3本廃棄だった）
        val plan8 = V6NativeOptimizer.hypothesisChainPlan(workers = 8, hypotheses = 5, cores = 8)
        assertEquals(listOf(2, 2, 2, 1, 1), plan8.toList())
        assertEquals(8, plan8.sum())
        // 割り切れるケース: workers=5 → 全仮説1（旧来どおり）
        assertEquals(listOf(1, 1, 1, 1, 1), V6NativeOptimizer.hypothesisChainPlan(5, 5, 8).toList())
    }

    @Test fun chainPlanClampsToCoreCount() {
        // workers=16/8コア → 配分総量はコア数8まで（15コルーチン/8スレッドの希釈を作らない）
        val plan = V6NativeOptimizer.hypothesisChainPlan(workers = 16, hypotheses = 5, cores = 8)
        assertEquals(8, plan.sum())
        assertEquals(listOf(2, 2, 2, 1, 1), plan.toList())
        // 16コアなら16本フル配分
        assertEquals(16, V6NativeOptimizer.hypothesisChainPlan(16, 5, 16).sum())
    }

    @Test fun chainPlanEveryHypothesisGetsAtLeastOneChain() {
        // workers<hypotheses でも各仮説に最低1本（合計は hypotheses を下回らない）
        val plan = V6NativeOptimizer.hypothesisChainPlan(workers = 2, hypotheses = 5, cores = 8)
        assertEquals(5, plan.size)
        assertTrue(plan.all { it >= 1 })
        // 縮退入力も安全
        assertTrue(V6NativeOptimizer.hypothesisChainPlan(0, 0, 0).all { it >= 1 })
    }

    // [敵対的レビュー] cores<hypotheses（低コア端末で5仮説固定）のときは、仕様上の「最低1仮説1本」の
    //   floorがコア数クランプより優先される（各仮説は最低1本必要＝5仮説なら5本を下回れない）。これは
    //   hypothesisChainPlan の欠陥ではなく、hypotheses数自体を減らさない設計（w=MAX_HYPOTHESESは
    //   仕様上不変）に由来する構造的な下限であることを固定する（余剰チェーンの"追加"はしないことを
    //   合わせて確認=このケースでは各仮説ちょうど1本のみで、コア数超のオーバーサブスクライブを
    //   それ以上増やさない）。
    @Test fun chainPlanFloorsAtHypothesesCountEvenBelowCoreCount() {
        val plan = V6NativeOptimizer.hypothesisChainPlan(workers = 16, hypotheses = 5, cores = 2)
        assertEquals(5, plan.sum())
        assertEquals(listOf(1, 1, 1, 1, 1), plan.toList())
    }

    // [敵対的レビュー修正・#6] V5(高速計算)はhypothesisChainPlanを使わずoptions.workersをそのまま
    //   SAチェーン数へ渡していたため、コア数クランプの恩恵を受けなかった。専用の総並列度クランプを検証。
    @Test fun clampWorkersToCoresLimitsToAvailableCores() {
        assertEquals(8, V6NativeOptimizer.clampWorkersToCores(workers = 16, cores = 8))
        assertEquals(16, V6NativeOptimizer.clampWorkersToCores(workers = 16, cores = 16))
        assertEquals(4, V6NativeOptimizer.clampWorkersToCores(workers = 4, cores = 8))
    }

    @Test fun clampWorkersToCoresNeverReturnsLessThanOne() {
        assertEquals(1, V6NativeOptimizer.clampWorkersToCores(workers = 0, cores = 8))
        assertEquals(1, V6NativeOptimizer.clampWorkersToCores(workers = 4, cores = 0))
    }

    // [仮説数上限撤廃・ユーザー指示「仮説数は最低2最大設定値」] 旧 optimize() は
    // options.workers.coerceIn(1, MAX_HYPOTHESES=5) で固定上限だった。hypothesisCount はこの上限を
    // 撤廃し、workers>=2 ならそのまま workers を仮説数として使う（多様性優先）。
    @Test fun hypothesisCountScalesWithWorkersBeyondOldFixedCapOfFive() {
        assertEquals(8, V6NativeOptimizer.hypothesisCount(8))
        assertEquals(16, V6NativeOptimizer.hypothesisCount(16))
    }

    @Test fun hypothesisCountFloorsAtTwoEvenWhenWorkersIsOne() {
        // workers=1 でも最低2仮説の多様探索を保証する（意図的なオーバーサブスクライブ）。
        assertEquals(2, V6NativeOptimizer.hypothesisCount(1))
        assertEquals(2, V6NativeOptimizer.hypothesisCount(0))
    }

    @Test fun hypothesisCountMatchesWorkersInTheOldCapRange() {
        // 旧上限5以下の帯でも従来どおり workers に一致すること（回帰確認）。
        assertEquals(2, V6NativeOptimizer.hypothesisCount(2))
        assertEquals(5, V6NativeOptimizer.hypothesisCount(5))
    }

    // [敵対的レビュー修正・#3] liveBest の CAS 管理: publishLiveBest は真に better() な報告のときだけ
    // liveBest を更新し、劣る/同値の報告では既存の liveBest を保持する（旧last-writer-winsの退行を防ぐ）。
    // グローバルなシングルトン状態(liveBest/liveBestReport)を扱うため、他テストの残存値に依存しないよう
    // 「明確に最良(hard=0,total=0)」→「明確に劣る(hard=0,total=極大)」の順で呼び、劣る側が無視されることを
    // 直接確認する（逆順=劣る値を先に публиしても後続の最良値が正しく採用されることも合わせて確認）。
    private fun tinyState(): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("A", 0))
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1)), groupShiftApt = listOf(listOf("")),
            schedule = listOf(listOf(0)), wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test fun publishLiveBestIgnoresWorseReportAfterBestPublished() {
        val st = tinyState()
        val sched = st.schedule.toIntArray2D()
        val goodReport = UnifiedViolationChecker.check(st, sched)  // hard=0/total=0（休のみ・制約なし）
        val goodSched = arrayOf(intArrayOf(0))
        V6NativeOptimizer.publishLiveBest(goodReport, goodSched)
        assertEquals(listOf(listOf(0)), V6NativeOptimizer.liveBest)

        // 明確に劣る report をでっち上げて publish を試みる（同一 hard・total だけ悪化させた copy）。
        val worseReport = goodReport.copy(total = goodReport.total + 999_999)
        val worseSched = arrayOf(intArrayOf(999))
        V6NativeOptimizer.publishLiveBest(worseReport, worseSched)
        // 劣る report は無視され、liveBest は直前の良好な盤面のまま。
        assertEquals(listOf(listOf(0)), V6NativeOptimizer.liveBest)

        // 更に良い report(total を減らす)なら正しく更新される。
        val betterReport = goodReport.copy(total = -1)
        val betterSched = arrayOf(intArrayOf(7))
        V6NativeOptimizer.publishLiveBest(betterReport, betterSched)
        assertEquals(listOf(listOf(7)), V6NativeOptimizer.liveBest)
    }
}
