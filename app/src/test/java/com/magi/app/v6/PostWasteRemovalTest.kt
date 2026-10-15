package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.680.0] 出力不変の無駄取り 2 件（[PolishGate.rotationDeltaPrescreen]・[PolishGate.roundPassMemo]）。ON/OFF で盤面が同じこと。 */
class PostWasteRemovalTest {
    private fun load(name: String) = StateParser.parse(javaClass.getResource("/$name")!!.readText())!!

    private fun <T> withGates(prescreen: Boolean, memo: Boolean, block: () -> T): T {
        val a = PolishGate.rotationDeltaPrescreen; val b = PolishGate.roundPassMemo
        PolishGate.rotationDeltaPrescreen = prescreen; PolishGate.roundPassMemo = memo
        try { return block() } finally { PolishGate.rotationDeltaPrescreen = a; PolishGate.roundPassMemo = b }
    }

    /** 3 者回転を盤面に当てたあとの差分キーが、正式評価の (hard, weightedScore, total) と毎手一致する（群をまたぐ組も含む）。 */
    @Test fun deltaKeyAgreesWithCheckerOnRotations() {
        for (q in listOf(false, true)) {
            val st = load("sept2026_state.json")
            val p = Problem(st, q)
            val work = normalizeSchedule(st.schedule.toIntArray2D(), p)
            val de = DeltaEvaluator(p); de.reset(work)
            val rng = kotlin.random.Random(23)
            var cross = 0
            repeat(150) {
                val w = 2 + rng.nextInt(2); val j = rng.nextInt(p.T - w + 1)
                val (a, b, c) = (0 until p.S).shuffled(rng).take(3)
                if ((0 until w).any { t -> p.wishLocked(a, j + t) || p.wishLocked(b, j + t) || p.wishLocked(c, j + t) }) return@repeat
                if (p.sgrp[a] != p.sgrp[b] || p.sgrp[b] != p.sgrp[c]) cross++
                for (t in 0 until w) {
                    val sa = work[a][j + t]; val sb = work[b][j + t]; val sc = work[c][j + t]
                    work[a][j + t] = sb; work[b][j + t] = sc; work[c][j + t] = sa
                    de.apply(a, j + t, sb); de.apply(b, j + t, sc); de.apply(c, j + t, sa)
                }
                val rep = UnifiedViolationChecker.check(st, work, quantitativeRangeEval = q)
                assertTrue("q=$q", C3RotationPolish.deltaKeyMatches(de, rep))
                assertArrayEquals(longArrayOf(rep.hard.toLong(), rep.weightedScore.toLong(), rep.total.toLong()), de.reportKey())
                assertEquals(false, C3RotationPolish.deltaKeyBetter(de.reportKey(), rep))
            }
            assertTrue("群をまたぐ組を含む", cross > 0)
        }
    }

    @Test fun rotationPrescreenKeepsTheSameBoard() {
        for (f in listOf("golden_state.json", "sample_state_v6.json")) {
            val st = load(f)
            val s0 = st.schedule.toIntArray2D()
            val anchors = setOf("vio-c3", "vio-c3m", "vio-c3mn", "vio-c1")
            val off = withGates(false, true) { C3RotationPolish.applyBlockRotationPolish(st, s0.copy2D(), anchors, "t", maxPasses = 1) }
            val on = withGates(true, true) { C3RotationPolish.applyBlockRotationPolish(st, s0.copy2D(), anchors, "t", maxPasses = 1) }
            assertTrue(f, off.newSchedule.contentDeepEquals(on.newSchedule))
            assertEquals(f, off.applied, on.applied)
            assertEquals(f, off.report?.weightedScore, on.report?.weightedScore)
        }
    }

    @Test fun roundPassMemoKeepsTheSameBoard() {
        for (f in listOf("golden_state.json", "sample_state_v6.json")) {
            val st = load(f)
            val params = V6HotfixPasses.PostOptimizationParams(deterministic = true, c1LnsMaxEvaluations = 2_000, personalLnsMaxEvaluations = 2_000)
            fun run(memo: Boolean) = withGates(true, memo) {
                V6HotfixPasses.runPostOptimization(st, st.schedule.toIntArray2D(), "t", seed = 3L, params = params)
            }
            val off = run(false); val on = run(true)
            assertTrue(f, off.schedule.contentDeepEquals(on.schedule))
            assertEquals(f, off.report.weightedScore, on.report.weightedScore, 0.0)
            assertEquals(f, off.stageRecords.map { it.key to it.applied }, on.stageRecords.map { it.key to it.applied })
        }
    }

    /** [3.686.0] 基準で 0 の回数の族（下限割れ）が回転で現れる盤面でも、差分評価の前判定は正式評価と同じ盤面を返す。 */
    @Test fun rotationPrescreenKeepsTheSameBoardWhenARangeFamilyAppears() {
        val st0 = load("sept2026_state.json")
        val s0 = st0.schedule.toIntArray2D()
        // 全員の各シフトの回数をそのまま下限にする＝基準では low 0、回転で 1 つでも減れば現れる。
        val ranges = HashMap(st0.staffRange)
        for (i in s0.indices) for (k in st0.shifts.indices) {
            val n = s0[i].count { it == k }
            if (n > 0 && "$i,$k" !in ranges) ranges["$i,$k"] = com.magi.app.model.Range("$n", "")
        }
        val st = st0.copy(staffRange = ranges)
        for (q in listOf(false, true)) {
            assertEquals(0, UnifiedViolationChecker.check(st, normalizeSchedule(s0, Problem(st, q)), quantitativeRangeEval = q).breakdown["low"] ?: 0)
            val anchors = setOf("vio-c3", "vio-c3m", "vio-c3mn", "vio-c1")
            val off = withGates(false, true) { C3RotationPolish.applyBlockRotationPolish(st, s0.copy2D(), anchors, "t", maxPasses = 1, quantitativeRangeEval = q) }
            val on = withGates(true, true) { C3RotationPolish.applyBlockRotationPolish(st, s0.copy2D(), anchors, "t", maxPasses = 1, quantitativeRangeEval = q) }
            assertTrue("q=$q", off.newSchedule.contentDeepEquals(on.newSchedule))
            assertEquals("q=$q", off.applied, on.applied)
            assertTrue("q=$q 食い違いで止めていない", on.logs.none { "食い違い" in it.message })
        }
    }

    @Test fun prescreenVerifiesCloseCallsAndEveryNth() {
        val st = load("golden_state.json")
        val rep = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
        val w = rep.weightedScore.toLong()
        assertTrue(C3RotationPolish.prescreenNeedsVerify(longArrayOf(rep.hard.toLong(), w, 0), rep, 1))
        assertTrue(C3RotationPolish.prescreenNeedsVerify(longArrayOf(rep.hard.toLong() + 1, w + 100, 0), rep, 0))
        assertTrue(!C3RotationPolish.prescreenNeedsVerify(longArrayOf(rep.hard.toLong() + 1, w + 100, 0), rep, 1))
    }
}
