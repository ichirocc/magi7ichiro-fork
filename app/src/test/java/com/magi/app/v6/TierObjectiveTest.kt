package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** [#50 P1] 段の鍵と職員ごとの量が、チェッカーの `breakdown` と同じ数え方であること（ずれたら並行して出す鍵が嘘になる）。 */
class TierObjectiveTest {
    private fun boards(st: com.magi.app.model.MagiState, p: Problem): List<Array<IntArray>> {
        val rng = Random(7)
        val input = st.schedule.toIntArray2D()
        // 入力の盤面と、セルを揺らした盤面（違反が多く出る形）で確かめる。
        return listOf(input) + List(3) { input.copy2D().also { b -> repeat(p.S * p.T / 3) { b[rng.nextInt(p.S)][rng.nextInt(p.T)] = rng.nextInt(p.K) } } }
    }

    @Test
    fun perStaffAmountsAddUpToTheCheckerBreakdown() {
        for (name in CheckerSnapshotTest.FIXTURES) {
            val json = TierObjectiveTest::class.java.getResourceAsStream("/$name.json")?.bufferedReader()?.readText()
            assertNotNull(name, json)
            val st = StateParser.parse(json!!)!!
            val p = Problem(st)
            for ((b, board) in boards(st, p).withIndex()) {
                val rep = UnifiedViolationChecker.check(st, board)
                for ((fam, arr) in TierObjective.t1PerStaff(p, board)) assertEquals("$name #$b $fam", rep.breakdown[fam] ?: 0, arr.sum())
                // fair＝職員ごとの偏差の和＋グループ×シフトごとの最大の偏差（3.727.77）。
                val counts = countMatrix(p, board)
                var worst = 0
                for (g in 0 until p.G) if (p.groupMembers[g].size >= 2) for (k in p.bucket[g])
                    worst += p.fairDevOfBucket(g, k) { x -> counts[x][k] }.perMember.maxOfOrNull { it.second } ?: 0
                assertEquals("$name #$b fair", rep.breakdown["fair"] ?: 0, TierObjective.fairDeviations(p, board).sum() + worst)
                val k = TierObjective.key(p, board, rep)
                assertEquals("$name #$b 段の超過量の合計＝total", rep.total, k.excess.sum())
                assertEquals("$name #$b T0＝hard", rep.hard, k.excess[0])
                assertEquals(k.dissatisfaction.sortedDescending(), k.dissatisfaction)
                assertTrue(k.dissatisfaction.all { it > 0 })
            }
        }
    }

    @Test
    fun everyFamilyIsInExactlyOneTier() {
        val all = TierObjective.tiers.flatten()
        assertEquals(all.size, all.toSet().size)
        assertEquals(MirrorKeys.all.toSet(), all.toSet())
    }
}
