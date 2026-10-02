package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertTrue
import org.junit.Test

class RsiPlusPhaseLogTest {
    @Test fun phaseLinesCarryWeightedScoreWorkerLabelAndSkipMarker() {
        val st = StateParser.parse(javaClass.classLoader!!.getResource("golden_state.json")!!.readText())!!
        var t0 = -1L
        val stop = {
            val now = System.currentTimeMillis()
            if (t0 < 0) t0 = now
            now - t0 > 500
        }
        val res = kotlinx.coroutines.runBlocking {
            V6NativeOptimizer.optimize(st, options = V6OptimizerOptions(algorithm = V6Algorithm.RSI_PLUS, totalBudgetSec = 40, workers = 1, postPolish = false), shouldStop = stop)
        }
        val lines = (res.phaseLogs + res.report.logs).filter { it.tag == "RSIPlus" }.map { it.message }
        val all = lines.joinToString("\n")
        assertTrue(all, lines.any { it.startsWith("[仮説0] Phase1 Seed: ") && it.contains("weighted=") })
        for (p in listOf("Phase2 Hypothesis(スキップ)", "Phase3 Refine(スキップ)", "Phase4 Polish(スキップ)"))
            assertTrue(all, lines.any { it.contains(p) && it.contains("weighted=") })
    }
}
