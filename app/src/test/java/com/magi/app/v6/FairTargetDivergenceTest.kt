package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [3.588.0/3.589.0] `AptFairPolish.fairTarget`（生回数平均）と`Problem.fairDevOfBucket`（達成率モード）は
 *  独立した2経路であるため乖離しうる。実データでの実測経緯: docs/history/3.4xx.md 3.588.0/3.589.0。 */
class FairTargetDivergenceTest {
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
        // 実データ(sept2026, 群0/シフト0)で見つかった構図を3人へ凝縮: 範囲[7,9]/[7,9]/[3,10]、回数7/8/9
        // （フル最適化を回さず範囲/回数を直接与える。経緯: docs/history 3.588.0/3.589.0）。
        val shifts = listOf(Shift("休み", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", ""))
        val t = 9
        val counts = intArrayOf(7, 8, 9)
        val schedule = List(3) { i -> List(t) { j -> if (j < counts[i]) 1 else 0 } }
        val sr = mapOf("0,1" to Range("7", "9"), "1,1" to Range("7", "9"), "2,1" to Range("3", "10"))
        val st = MagiState(
            startDate = "2026-10-01", endDate = "2026-10-09",
            shifts = shifts, groups = listOf(Group("G", "G")), staff = List(3) { Staff("S$it", 0) }, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = schedule, wishes = emptyMap(), staffRange = sr,
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = Problem(st)
        val cm = countMatrix(p, st.schedule.toIntArray2D())
        val g = 0; val k = 1; val x = 1
        assertEquals("設計どおりxの回数は8", 8, cm[x][k])
        assertEquals("fairTargetは「一致」と判定=何もしない", "match", rawDirection(p, g, k, cm, x))
        assertEquals("達成率モードでは実は不足方向の是正が有効", "low", officialDirection(p, g, k, cm, x))
    }

    @Test
    fun fairTargetAndOfficialDirectionAgreeOnMostRealDataCells() {
        var total = 0; var mismatches = 0
        for (name in listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")) {
            val f = File("app/src/test/resources/$name").let { if (it.exists()) it else File("src/test/resources/$name") }
            val st = StateParser.parse(f.readText())!!
            val board = st.schedule.toIntArray2D()
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
        assertTrue("実データ(素の入力)では乖離は稀（今回の測定: $mismatches/$total 件）", mismatches <= 2)
        assertTrue("distLocations[\"fair\"]自体は複数件観測できている", total >= 20)
    }
}
