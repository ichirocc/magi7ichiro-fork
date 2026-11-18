package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 推奨の並び（複数シフト）で、一致した窓の中の 2 つ目以降のセルを書き出しと数えないこと（3.727.102）。 */
class C3WantCoverTest {
    private val ddrr = intArrayOf(1, 1, 0, 0)

    private fun fires(row: IntArray, seq: IntArray) = (0..row.size - seq.size).count { C3Run.wantFires(row, it, seq) }

    @Test fun completedPatternCountsNothing() {
        assertEquals(0, fires(intArrayOf(1, 1, 0, 0, 2, 2), ddrr))
        assertEquals(0, fires(intArrayOf(2, 1, 1, 0, 0, 2), ddrr))
    }

    @Test fun incompleteStartsStillCount() {
        assertEquals(1, fires(intArrayOf(1, 0, 0, 2, 2, 2), ddrr))
        assertEquals(1, fires(intArrayOf(1, 1, 1, 0, 0, 2), ddrr))   // 最初の Dﾃ だけ。2 つ目から一致、3 つ目はその中
        assertEquals(3, fires(intArrayOf(1, 1, 2, 1, 1, 2, 2), ddrr))
    }

    @Test fun maskMatchesTheScalarRule() {
        val rng = Random(7)
        repeat(5_000) {
            val t = 6 + rng.nextInt(20)
            val row = IntArray(t) { rng.nextInt(3) }
            var first = 0L; var full = 0L
            for (j in 0..t - ddrr.size) {
                if (row[j] == ddrr[0]) first = first or (1L shl j)
                if (C3Run.matchAt(row, j, ddrr)) full = full or (1L shl j)
            }
            val range = (1L shl (t - ddrr.size + 1)) - 1
            assertEquals(fires(row, ddrr), java.lang.Long.bitCount(C3Run.wantFireMask(first, full, ddrr.size) and range))
        }
    }

    private fun state(): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", ""), Shift("B", "B", "1", ""))
        val staff = (0 until 4).map { Staff("s$it", 0, 0) }
        val rng = Random(3)
        return MagiState(
            startDate = "2025-01-01", endDate = "2025-01-14",
            shifts = shifts, groups = listOf(Group("G0", "G0")), staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
            schedule = (0 until 4).map { List(14) { rng.nextInt(3) } },
            wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = listOf(C3Row(listOf("A", "A", "B"))), cons3n = emptyList(),
            cons3m = listOf(C3Row(listOf("A", "A", "休", "休"))), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test fun checkerCountsMatchTheRule() {
        val st = state()
        val sched = st.schedule.map { it.toIntArray() }.toTypedArray()
        val r = UnifiedViolationChecker.check(st, sched)
        assertEquals(sched.sumOf { fires(it, ddrr) }, r.breakdown["c3m"] ?: 0)
        assertEquals(sched.sumOf { fires(it, intArrayOf(1, 1, 2)) }, r.breakdown["c3"] ?: 0)
    }

    @Test fun deltaMatchesFullAcrossRandomMoves() {
        val p = Problem(state())
        val ev = Evaluator(p)
        val de = DeltaEvaluator(p)
        val rng = Random(11)
        var sawCover = false
        repeat(20_000) {
            val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
            val old = de.at(i, j)
            de.apply(i, j, rng.nextInt(3))
            assertEquals(ev.fullEval(de.snapshot()), de.score())
            val row = de.snapshot()[i]
            if ((1..row.size - 4).any { C3Run.matchAt(row, it - 1, ddrr) && row[it] == 1 }) sawCover = true
            if (rng.nextBoolean()) { de.apply(i, j, old); assertEquals(ev.fullEval(de.snapshot()), de.score()) }
        }
        assertTrue("一致した窓の中に先頭と同じシフトが来る盤面を通っていない", sawCover)
        assertFalse(C3Run.wantFires(intArrayOf(1, 1, 0, 0), 1, ddrr))
    }
}
