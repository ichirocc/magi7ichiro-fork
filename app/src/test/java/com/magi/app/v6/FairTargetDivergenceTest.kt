package com.magi.app.v6

import com.magi.app.model.StateParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [3.588.0] `AptFairPolish.fairTarget`（生回数平均）と`Problem.fairDevOfBucket`（達成率モード）は
 *  独立した2経路であるため乖離しうる。実データでの実測は経緯: docs/history/3.4xx.md 3.588.0。 */
class FairTargetDivergenceTest {
    private fun sept2026() = StateParser.parse(
        File("app/src/test/resources/sept2026_state.json").let { if (it.exists()) it else File("src/test/resources/sept2026_state.json") }.readText()
    )!!

    /** fairDevOfBucketを仮想入力(±1)で呼び、totalが減る方向を返す（"high"=減らすべき/"low"=増やすべき/"ambiguous"）。 */
    private fun officialDirection(p: Problem, g: Int, k: Int, counts: Array<IntArray>, x: Int): String {
        val base = p.fairDevOfBucket(g, k) { xx -> counts[xx][k] }
        val down = p.fairDevOfBucket(g, k) { xx -> if (xx == x) counts[xx][k] - 1 else counts[xx][k] }
        val up = p.fairDevOfBucket(g, k) { xx -> if (xx == x) counts[xx][k] + 1 else counts[xx][k] }
        return when {
            down.total < base.total -> "high"
            up.total < base.total -> "low"
            else -> "ambiguous"
        }
    }

    private fun rawDirection(p: Problem, g: Int, k: Int, counts: Array<IntArray>, x: Int): String {
        val mem = p.groupMembers[g]
        val tgtRaw = Math.round(mem.sumOf { counts[it][k] }.toDouble() / mem.size).toInt()
        return when {
            counts[x][k] > tgtRaw -> "high"
            counts[x][k] < tgtRaw -> "low"
            else -> "match"
        }
    }

    @Test
    fun fairTargetOmitsACellThatOfficialAchievementRateWouldStillFlag() {
        val st = sept2026()
        val sched = st.schedule.toIntArray2D()
        val post = runBlocking {
            V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "test", seed = 1L,
                params = V6HotfixPasses.PostOptimizationParams(deterministic = true))
        }
        val p = Problem(st)
        val counts = countMatrix(p, post.schedule)
        val g = 0; val k = 0; val x = 2
        assertEquals("この実データ実測: xの回数は8", 8, counts[x][k])
        assertEquals("fairTargetは「一致」と判定=何もしない", "match", rawDirection(p, g, k, counts, x))
        assertEquals("達成率モードでは実は不足方向の是正が有効", "low", officialDirection(p, g, k, counts, x))
    }

    @Test
    fun fairTargetAndOfficialDirectionAgreeOnMostRealDataCells() {
        var total = 0; var mismatches = 0
        for (name in listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")) {
            val f = File("app/src/test/resources/$name").let { if (it.exists()) it else File("src/test/resources/$name") }
            val st = StateParser.parse(f.readText())!!
            val sched = st.schedule.toIntArray2D()
            for (board in listOf(sched, runBlocking {
                V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "test", seed = 1L,
                    params = V6HotfixPasses.PostOptimizationParams(deterministic = true))
            }.schedule)) {
                val p = Problem(st)
                val rep = UnifiedViolationChecker.check(st, board)
                val counts = countMatrix(p, board)
                for (loc in rep.distLocations["fair"].orEmpty()) {
                    val x = loc.getOrNull(0) ?: continue; val k = loc.getOrNull(1) ?: continue
                    if (x !in 0 until p.S || k !in 0 until p.K) continue
                    val g = p.sgrp.getOrNull(x) ?: continue
                    if (g !in p.bucket.indices) continue
                    total++
                    val official = officialDirection(p, g, k, counts, x)
                    if (official == "ambiguous") continue
                    if (rawDirection(p, g, k, counts, x) != official) mismatches++
                }
            }
        }
        assertTrue("実データでは乖離は稀（今回の測定: $mismatches/$total 件）", mismatches <= 2)
        assertTrue("distLocations[\"fair\"]自体は複数件観測できている", total >= 20)
    }
}
