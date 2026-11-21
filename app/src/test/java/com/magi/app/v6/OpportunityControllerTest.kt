package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 残り違反に合わせて役割を選ぶ制御（[OpportunityController]）。 */
class OpportunityControllerTest {
    private val roles = listOf(HypothesisEpochRole.DAY_BLOCK_ALNS, HypothesisEpochRole.HARD_FAMILY_RSI, HypothesisEpochRole.HARD_DEBT_RSI_PLUS)

    private fun report(hard: Int, weighted: Double, breakdown: Map<String, Int>) =
        ViolationReport(violations = emptyMap(), needViolations = emptyMap(), countViolations = emptyMap(), breakdown = breakdown,
            hard = hard, soft = breakdown.values.sum() - hard, total = breakdown.values.sum(), weightedScore = weighted)

    @Test fun phaseIsTheHeaviestRemainingFamily() {
        val c = OpportunityController(roles)
        assertEquals("covU", c.phaseOf(report(2, 20_000.0, mapOf("covU" to 1, "c3n" to 1, "c1" to 50))))
        assertEquals("c1", c.phaseOf(report(0, 5_000.0, mapOf("c1" to 50, "c2" to 10))))
    }

    @Test fun untriedRolesFirstThenBestRatePerSecond() {
        val c = OpportunityController(roles)
        val before = report(1, 20_000.0, mapOf("covU" to 1))
        // 試していない役割を先に（今の役割は選ばない）
        assertNotEquals(HypothesisEpochRole.DAY_BLOCK_ALNS, c.choose("covU", HypothesisEpochRole.DAY_BLOCK_ALNS))
        // HARD_FAMILY_RSI は 10 秒で必須 1 件、HARD_DEBT_RSI_PLUS は 30 秒で何も減らさない
        repeat(5) {
            c.record("covU", HypothesisEpochRole.HARD_FAMILY_RSI, 10.0, before, report(0, 10_000.0, emptyMap()))
            c.record("covU", HypothesisEpochRole.HARD_DEBT_RSI_PLUS, 30.0, before, before)
            c.record("covU", HypothesisEpochRole.DAY_BLOCK_ALNS, 5.0, before, before)
        }
        assertEquals(HypothesisEpochRole.HARD_FAMILY_RSI, c.choose("covU", HypothesisEpochRole.DAY_BLOCK_ALNS))
        // 局面ごとに分けて数える＝別の局面では未試行から
        assertEquals(HypothesisEpochRole.HARD_FAMILY_RSI, c.choose("c1", HypothesisEpochRole.DAY_BLOCK_ALNS))
    }

    @Test fun hardDropCountsEvenWhenWeightRises() {
        val g = OpportunityController.gainOf(report(2, 20_000.0, emptyMap()), report(1, 25_000.0, emptyMap()))
        assertEquals(OpportunityController.HARD_UNIT, g, 1e-9)
    }
}
