package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Iteration 7] 決定的モード（`PostOptimizationParams.deterministic`）＝時間でなく回数で止める。同じ入力・seed なら同じ盤面。 */
class DeterministicPostChainTest {
    private fun state(needA: String = "2"): MagiState {
        val t = 8
        val rows = listOf(listOf(1, 1, 1, 0, 1, 1, 1, 0), listOf(0, 0, 1, 1, 0, 0, 1, 1), listOf(1, 0, 0, 1, 1, 0, 0, 1))
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-08",
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", needA, "")), groups = listOf(Group("G", "G")),
            staff = listOf(Staff("X", 0), Staff("Y", 0), Staff("Z", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = rows, wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row("3", "休", "1")), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        ).also { check(it.schedule[0].size == t) }
    }

    private fun run(params: V6HotfixPasses.PostOptimizationParams): V6PostOptimizationResult {
        val s = state()
        return V6HotfixPasses.runPostOptimization(s, s.schedule.map { it.toIntArray() }.toTypedArray(), "det", seed = 5L,
            deadlineMs = EngineClock.nowMs() + 10_000L, params = params)
    }

    @Test
    fun twoRunsProduceTheSameBoard() {
        val p = V6HotfixPasses.PostOptimizationParams(deterministic = true)
        val a = run(p); val b = run(p)
        assertTrue(a.schedule.contentDeepEquals(b.schedule))
        assertEquals(a.report.weightedScore, b.report.weightedScore, 0.0)
    }

    @Test
    fun jointLnsStopsByEvaluationCount() {
        // チェーン内では前段の巡回研磨が c1 を 0 にして LNS が「対象なし」になるので、入力盤面（c1 違反あり）に直接当てる。
        val s = state()
        val r = C1RepairOperators.jointLns(s, s.schedule.map { it.toIntArray() }.toTypedArray(), config = C1JointLnsPolish.Config(maxEvaluations = 3, patienceMs = 0L))
        val msg = r.logs.first().message
        assertTrue(msg, msg.contains("評価回数上限3"))
    }

    @Test
    fun evaluationCapIsIgnoredWhenZero() {
        val cfg = C1JointLnsPolish.Config(maxEvaluations = 0, maxMillis = 500L, patienceMs = 0L)
        val s = state()
        val r = C1RepairOperators.jointLns(s, s.schedule.map { it.toIntArray() }.toTypedArray(), config = cfg)
        assertTrue(r.logs.first().message, !r.logs.first().message.contains("評価回数上限"))
    }

    /** [postChainRunningKeepBest] チェーン内で「良い手→悪い手」の順に畳み込まれたとき、flag OFF は今日と同じ
     * 退行放置、flag ON は悪い手の直前（＝良い手の盤面）へ巻き戻すことを、`PostChain` を直接駆動して確認する。 */
    private fun makeCyclicSwapResult(schedule: Array<IntArray>, report: ViolationReport, tag: String) =
        V6HotfixPasses.CyclicSwapResult(schedule, report.total, report.total, 1, listOf(MirrorLog(tag = tag, message = "$tag 適用")), report = report)

    @Test
    fun runningKeepBestOffKeepsChainRegression() {
        val s = state()
        val work0 = s.schedule.map { it.toIntArray() }.toTypedArray()
        val report0 = UnifiedViolationChecker.check(s, work0)
        // 改善盤面(実測): [1][1]=1 で HARD 2->1・weightedScore 20144->10188。
        val improved = work0.map { it.copyOf() }.toTypedArray().also { it[1][1] = 1 }
        val improvedReport = UnifiedViolationChecker.check(s, improved)
        // 退行盤面(実測): 改善盤面からさらに [1][0]=1 で HARD は不変(1)だが weightedScore 10188->10252 に悪化。
        val regressed = improved.map { it.copyOf() }.toTypedArray().also { it[1][0] = 1 }
        val regressedReport = UnifiedViolationChecker.check(s, regressed)
        assertTrue("改善盤面が起点より良いこと(テスト前提)", betterReport(improvedReport, report0))
        assertTrue("退行盤面が改善盤面より悪いこと(テスト前提)", betterReport(improvedReport, regressedReport))

        val chainOff = V6HotfixPasses.PostChain(onPhase = {}, schedule = work0, state = s, quantitativeRangeEval = false,
            runningKeepBest = false, initialReport = report0)
        chainOff.adopt(makeCyclicSwapResult(improved, improvedReport, "Good"))
        chainOff.adopt(makeCyclicSwapResult(regressed, regressedReport, "Bad"))
        assertTrue("flag OFF は今日と同じくチェーン内の退行を放置する", chainOff.work.contentDeepEquals(regressed))
        assertTrue(chainOff.logs.none { it.message.contains("チェーン内巻き戻しで不採用") })
    }

    @Test
    fun runningKeepBestOnRevertsChainRegression() {
        val s = state()
        val work0 = s.schedule.map { it.toIntArray() }.toTypedArray()
        val report0 = UnifiedViolationChecker.check(s, work0)
        val improved = work0.map { it.copyOf() }.toTypedArray().also { it[1][1] = 1 }
        val improvedReport = UnifiedViolationChecker.check(s, improved)
        val regressed = improved.map { it.copyOf() }.toTypedArray().also { it[1][0] = 1 }
        val regressedReport = UnifiedViolationChecker.check(s, regressed)

        val chainOn = V6HotfixPasses.PostChain(onPhase = {}, schedule = work0, state = s, quantitativeRangeEval = false,
            runningKeepBest = true, initialReport = report0)
        chainOn.adopt(makeCyclicSwapResult(improved, improvedReport, "Good"))
        chainOn.adopt(makeCyclicSwapResult(regressed, regressedReport, "Bad"))
        assertTrue("flag ON はチェーン内最良（改善盤面）へ巻き戻す", chainOn.work.contentDeepEquals(improved))
        assertTrue("巻き戻された Bad パスのログは棄却マーカー付きで残る（ログを落とさない）",
            chainOn.logs.any { it.tag == "Bad" && it.message.contains("チェーン内巻き戻しで不採用") })
        assertTrue("採用された Good パスのログはマーカーなし", chainOn.logs.any { it.tag == "Good" && !it.message.contains("チェーン内巻き戻しで不採用") })
    }

    // 外部レビュー N9: 盤面を変えなかったパス（最良と同点・同盤面）には巻き戻し印を付けない。
    @Test
    fun runningKeepBestDoesNotMarkUnchangedPass() {
        val s = state()
        val work0 = s.schedule.map { it.toIntArray() }.toTypedArray()
        val report0 = UnifiedViolationChecker.check(s, work0)
        val improved = work0.map { it.copyOf() }.toTypedArray().also { it[1][1] = 1 }
        val improvedReport = UnifiedViolationChecker.check(s, improved)
        val chain = V6HotfixPasses.PostChain(onPhase = {}, schedule = work0, state = s, quantitativeRangeEval = false,
            runningKeepBest = true, initialReport = report0)
        chain.adopt(makeCyclicSwapResult(improved, improvedReport, "Good"))
        chain.adopt(makeCyclicSwapResult(improved.map { it.copyOf() }.toTypedArray(), improvedReport, "Noop"))
        assertTrue(chain.work.contentDeepEquals(improved))
        assertTrue(chain.logs.none { it.message.contains("チェーン内巻き戻しで不採用") })
    }

    // 構造的 covU 床 > 0（必要人数 5 > 職員 3）の盤面では巻き戻さない＝必須件数が増えた試行はすべてこの形だった（2026-09-22）。
    @Test
    fun runningKeepBestIsInactiveWhenStructuralHardFloorIsPositive() {
        val s = state(needA = "5")
        assertTrue(V6SanityPort.structuralHardFloor(s) > 0)
        val work0 = s.schedule.map { it.toIntArray() }.toTypedArray()
        val report0 = UnifiedViolationChecker.check(s, work0)
        val improved = work0.map { it.copyOf() }.toTypedArray().also { it[1][1] = 1 }
        val regressed = improved.map { it.copyOf() }.toTypedArray().also { it[1][0] = 0; it[0][0] = 0 }
        val chain = V6HotfixPasses.PostChain(onPhase = {}, schedule = work0, state = s, quantitativeRangeEval = false,
            runningKeepBest = true, initialReport = report0)
        chain.adopt(makeCyclicSwapResult(improved, UnifiedViolationChecker.check(s, improved), "Good"))
        chain.adopt(makeCyclicSwapResult(regressed, UnifiedViolationChecker.check(s, regressed), "Bad"))
        assertTrue("構造床>0 では巻き戻さず最後の盤面のまま", chain.work.contentDeepEquals(regressed))
    }

    // 同点の横移動も最良盤面へ戻す（同点は採らない）。
    @Test
    fun runningKeepBestRollsBackLateralMove() {
        val s = state()
        val work0 = s.schedule.map { it.toIntArray() }.toTypedArray()
        val report0 = UnifiedViolationChecker.check(s, work0)
        val improved = work0.map { it.copyOf() }.toTypedArray().also { it[1][1] = 1 }
        val improvedReport = UnifiedViolationChecker.check(s, improved)
        assertTrue(betterReport(improvedReport, report0))
        // 同群・個人設定なしの 2 人の行を入れ替えた盤面＝報告は同点
        val lateral = arrayOf(improved[1].copyOf(), improved[0].copyOf(), improved[2].copyOf())
        val lateralReport = UnifiedViolationChecker.check(s, lateral)
        assertTrue(!betterReport(lateralReport, improvedReport) && !betterReport(improvedReport, lateralReport))
        assertTrue(!lateral.contentDeepEquals(improved))
        val c = V6HotfixPasses.PostChain(onPhase = {}, schedule = work0, state = s, quantitativeRangeEval = false,
            runningKeepBest = true, initialReport = report0)
        c.adopt(makeCyclicSwapResult(improved, improvedReport, "Good"))
        c.adopt(makeCyclicSwapResult(lateral, lateralReport, "Last"))
        assertTrue("同点でも最良盤面へ戻す", c.work.contentDeepEquals(improved))
    }

    // #36 finalOnly: パス間では巻き戻さず、restoreBestIfWorse で末尾に 1 回だけ最良盤面へ戻す。
    @Test
    fun finalOnlyDefersRollbackToChainEnd() {
        val s = state()
        val work0 = s.schedule.map { it.toIntArray() }.toTypedArray()
        val report0 = UnifiedViolationChecker.check(s, work0)
        val improved = work0.map { it.copyOf() }.toTypedArray().also { it[1][1] = 1 }
        val improvedReport = UnifiedViolationChecker.check(s, improved)
        val regressed = improved.map { it.copyOf() }.toTypedArray().also { it[1][0] = 1 }
        val regressedReport = UnifiedViolationChecker.check(s, regressed)
        val chain = V6HotfixPasses.PostChain(onPhase = {}, schedule = work0, state = s, quantitativeRangeEval = false,
            runningKeepBest = true, initialReport = report0, finalOnly = true)
        chain.adopt(makeCyclicSwapResult(improved, improvedReport, "Good"))
        chain.adopt(makeCyclicSwapResult(regressed, regressedReport, "Bad"))
        assertTrue("パス間では巻き戻さない", chain.work.contentDeepEquals(regressed))
        assertTrue(chain.restoreBestIfWorse())
        assertTrue("末尾で最良盤面へ戻す", chain.work.contentDeepEquals(improved))
        assertTrue("最良なら何もしない", !chain.restoreBestIfWorse())
    }
}
