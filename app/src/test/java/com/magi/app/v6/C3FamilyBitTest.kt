package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Random

/** 正式チェッカーの並びの窓の照合: ビット経路（AND＋popcount）とスカラーの走査で、数・場所・順が同じ。 */
class C3FamilyBitTest {
    private fun both(st: com.magi.app.model.MagiState, s: Array<IntArray>): Pair<ViolationReport, ViolationReport> {
        val bit = UnifiedViolationChecker.check(st, s.copy2D())
        UnifiedViolationChecker.forceScalarC3 = true
        try { return bit to UnifiedViolationChecker.check(st, s.copy2D()) } finally { UnifiedViolationChecker.forceScalarC3 = false }
    }

    private fun assertSame(tag: String, a: ViolationReport, b: ViolationReport) {
        assertEquals("$tag breakdown", b.breakdown, a.breakdown)
        assertEquals("$tag violations", b.violations.toList(), a.violations.toList())
        assertEquals("$tag cellFamilies", b.cellFamilies.toList(), a.cellFamilies.toList())
        assertEquals("$tag weighted", b.weightedScore, a.weightedScore, 0.0)
    }

    @Test fun bitAndScalarAgreeOnFixturesAndRandomBoards() {
        val rng = Random(7)
        for (name in CheckerSnapshotTest.FIXTURES) {
            val json = javaClass.getResourceAsStream("/$name.json")?.bufferedReader()?.readText()
            assertNotNull(json)
            val st = StateParser.parse(json!!)!!
            val base = st.schedule.toIntArray2D()
            val (a, b) = both(st, base)
            assertSame(name, a, b)
            repeat(20) { t ->
                val s = base.copy2D()
                // 盤面の 3 割をランダムに書き換える（未割当 -1 も混ぜる）。
                for (i in s.indices) for (j in s[i].indices) if (rng.nextInt(10) < 3) s[i][j] = if (rng.nextInt(50) == 0) -1 else rng.nextInt(st.shiftCount)
                val (x, y) = both(st, s)
                assertSame("$name#$t", x, y)
            }
        }
    }
}
