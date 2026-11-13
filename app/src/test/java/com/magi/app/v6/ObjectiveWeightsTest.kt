package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * [#50] 採点の方式（[ObjectiveWeights]）。既定（WEIGHTS）は今の重みそのもの、優先順位（PRIORITY）でも
 * チェッカーの weightedScore・評価器・差分評価が同じ重みで一致すること（片方だけ切り替わると探索と採否がずれる）。
 */
class ObjectiveWeightsTest {
    @After fun back() { ObjectiveWeights.method = ObjectiveMethod.WEIGHTS; PolishGate.aptFairSoftTolerance = false }

    /** 優先順位では apt/fair の 6% 許容を入れても実行の設定には載らない（重み（従来）では従来どおり載る）。 */
    @Test fun aptFairToleranceIsOffUnderPriority() {
        PolishGate.aptFairSoftTolerance = true
        ObjectiveWeights.method = ObjectiveMethod.PRIORITY
        assertEquals(false, EngineConfig.snapshot().aptFairSoftTolerance)
        ObjectiveWeights.method = ObjectiveMethod.WEIGHTS
        assertEquals(true, EngineConfig.snapshot().aptFairSoftTolerance)
    }

    @Test fun defaultMethodIsTheCurrentWeights() {
        assertEquals(ObjectiveMethod.WEIGHTS, ObjectiveWeights.method)
        assertEquals(MirrorKeys.weights, ObjectiveWeights.table())
    }

    @Test fun priorityTableKeepsHardAndUsesTierWeights() {
        val t = ObjectiveWeights.table(ObjectiveMethod.PRIORITY)
        for (f in MirrorKeys.hard) assertEquals(f, MirrorKeys.weights[f], t[f])
        for (f in TierObjective.tiers[1]) assertEquals(f, 200.0, t[f])
        for (f in TierObjective.tiers[2]) assertEquals(f, 20.0, t[f])
        for (f in TierObjective.tiers[3]) assertEquals(f, if (f == "fair") 50.0 else 1.0, t[f])
        assertEquals(MirrorKeys.weights.keys.toList(), t.keys.toList())
    }

    @Test fun checkerEvaluatorAndDeltaAgreeUnderBothMethods() {
        for (m in ObjectiveMethod.entries) {
            ObjectiveWeights.method = m
            for (name in CheckerSnapshotTest.FIXTURES) {
                val json = ObjectiveWeightsTest::class.java.getResourceAsStream("/$name.json")!!.bufferedReader().readText()
                val st = StateParser.parse(json)!!
                val p = Problem(st)
                val ev = Evaluator(p)
                val rng = Random(11)
                val board = st.schedule.toIntArray2D()
                val de = DeltaEvaluator(p).also { it.reset(board) }
                repeat(400) { step ->
                    val i = rng.nextInt(p.S); val j = rng.nextInt(p.T); val k = rng.nextInt(p.K)
                    de.apply(i, j, k); board[i][j] = k
                    if (step % 40 == 0) {
                        val full = ev.fullEval(board)
                        assertEquals("$m $name #$step Δ と全体", full, de.score())
                        val rep = UnifiedViolationChecker.check(st, board)
                        val hardW = MirrorKeys.hard.sumOf { (rep.breakdown[it] ?: 0) * ObjectiveWeights.of(it) }
                        assertEquals("$m $name #$step 評価器の soft とチェッカー", (full % SCORE_HARD_UNIT).toDouble(), rep.weightedScore - hardW, 1e-6)
                    }
                }
            }
        }
    }
}
