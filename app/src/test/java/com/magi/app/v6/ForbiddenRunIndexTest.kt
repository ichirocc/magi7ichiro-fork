package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** 索引版 [Problem.makesForbiddenRun] とビット版 [C1DeltaPrefilter.staffC3nFires] が、素朴な全走査と同じ答えを返すこと。 */
class ForbiddenRunIndexTest {
    private fun naiveMakes(p: Problem, s: Array<IntArray>, i: Int, j: Int, k: Int): Boolean {
        if (p.c3wBanned(i, j, k)) return true
        for (c in p.cons3n) {
            val d = c.seq.size
            if (d == 0 || d > p.T) continue
            for (st in maxOf(0, j - d + 1)..minOf(j, p.T - d)) {
                if ((0 until d).all { l -> (if (st + l == j) k else s[i][st + l]) == c.seq[l] }) return true
            }
        }
        return false
    }

    private fun naiveFires(p: Problem, row: IntArray): Int {
        var n = 0
        for (c in p.cons3n) {
            val d = c.seq.size
            if (d == 0 || d > p.T) continue
            for (st in 0..p.T - d) if ((0 until d).all { row[st + it] == c.seq[it] }) n++
        }
        return n
    }

    @Test fun matchesNaiveScanOnRealData() {
        for (f in listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json", "oct2026_grid_state.json", "full_coverage_state.json")) {
            val st = StateParser.parse(javaClass.getResource("/$f")!!.readText())!!
            val p = Problem(st)
            val s = normalizeSchedule(st.schedule.toIntArray2D(), p)
            val rng = kotlin.random.Random(f.hashCode())
            repeat(400) {
                val i = rng.nextInt(p.S)
                repeat(6) { s[i][rng.nextInt(p.T)] = rng.nextInt(p.K) }
                assertEquals(f, naiveFires(p, s[i]), C1DeltaPrefilter.staffC3nFires(p, s[i]))
                for (j in 0 until p.T) {
                    val k = rng.nextInt(p.K)
                    assertEquals("$f i=$i j=$j k=$k", naiveMakes(p, s, i, j, k), p.makesForbiddenRun(s, i, j, k))
                }
            }
        }
    }
}
