package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveEliteArchiveTest {
    private fun report(hard: Int, total: Int, weighted: Double = total.toDouble()) = ViolationReport(
        violations = emptyMap(),
        needViolations = emptyMap(),
        countViolations = emptyMap(),
        breakdown = emptyMap(),
        total = total,
        hard = hard,
        soft = (total - hard).coerceAtLeast(0),
        weightedScore = weighted,
    )

    private fun board(vararg values: Int): Array<IntArray> = arrayOf(values)

    @Test
    fun exactDuplicateIsReplacedOnlyByBetterOfficialObjective() {
        val archive = AdaptiveEliteArchive()
        val a = board(0, 1, 0, 1)
        archive.register(a, report(1, 9), HypothesisEpochRole.DAY_BLOCK_ALNS, 1, 1, bridge = false)
        archive.register(a, report(1, 10), HypothesisEpochRole.HARD_FAMILY_RSI, 2, 2, bridge = false)
        assertEquals(9, archive.allForTest().single().report.total)

        archive.register(a, report(1, 8), HypothesisEpochRole.HARD_FAMILY_RSI, 2, 3, bridge = false)
        assertEquals(1, archive.size())
        assertEquals(8, archive.allForTest().single().report.total)
        assertEquals(3, archive.allForTest().single().epoch)
    }

    @Test
    fun compressionKeepsQualityDistanceAndBridgePopulations() {
        val archive = AdaptiveEliteArchive()
        val reference = board(0, 0, 0, 0, 0, 0)
        archive.register(board(0, 0, 0, 0, 0, 1), report(0, 8), HypothesisEpochRole.BASELINE_REFINE, 0, 0, false)
        archive.register(board(0, 0, 0, 0, 1, 0), report(0, 9), HypothesisEpochRole.PERSONAL_RSI, 6, 1, false)
        archive.register(board(1, 1, 1, 0, 0, 0), report(0, 11), HypothesisEpochRole.DAY_BLOCK_ALNS, 1, 1, false)
        archive.register(board(1, 1, 1, 1, 1, 1), report(0, 12), HypothesisEpochRole.MAX_DISTANCE_RSI_PLUS, 7, 1, false)
        archive.register(board(2, 2, 2, 2, 2, 2), report(1, 7), HypothesisEpochRole.HARD_DEBT_RSI_PLUS, 3, 1, true)

        val selected = archive.snapshot(reference, report(0, 10), maxQuality = 2, maxDiversity = 2, maxBridge = 1)
        assertEquals(5, selected.size)
        assertEquals(2, selected.count { it.tier == AdaptiveEliteTier.QUALITY })
        assertEquals(2, selected.count { it.tier == AdaptiveEliteTier.DIVERSITY })
        assertEquals(1, selected.count { it.tier == AdaptiveEliteTier.BRIDGE })
        assertTrue(selected.any { AdaptiveEliteArchive.sameSchedule(it.schedule, board(1, 1, 1, 1, 1, 1)) })
        assertTrue(selected.any { it.bridge && it.report.hard == 1 })
    }

    @Test
    fun snapshotsAreDefensiveCopies() {
        val archive = AdaptiveEliteArchive()
        val a = board(0, 1, 2)
        archive.register(a, report(0, 1), HypothesisEpochRole.BASELINE_REFINE, 0, 0, false)
        a[0][0] = 9
        val snapshot = archive.snapshot(board(0, 0, 0), report(0, 2))
        assertFalse(snapshot.single().schedule[0][0] == 9)
        snapshot.single().schedule[0][1] = 9
        assertEquals(1, archive.allForTest().single().schedule[0][1])
    }
}
