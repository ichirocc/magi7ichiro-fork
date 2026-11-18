package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-SAT の定義域の判定（[CpsatCoRun.inDomain]＝下限が当てはまる盤面か）。`magi_cpsat::Model::makeVars` と同じ規則。 */
class CpsatDomainTest {
    private val st = StateParser.parse(javaClass.getResource("/oct2026_grid_state.json")!!.readText())!!
    private val p = cachedProblem(st)
    private val input = normalizeSchedule(st.schedule.map { it.toIntArray() }.toTypedArray(), p)

    /** 探索の起点と同じ形: 希望・手動固定のマスはその値、空きは置ける値、ほかは入力のまま。 */
    private fun start() = Array(p.S) { i ->
        IntArray(p.T) { j ->
            when {
                p.wishLocked(i, j) -> p.lockTo(i, j)
                input[i][j] >= 0 -> input[i][j]
                else -> (0 until p.K).firstOrNull { p.mayPlaceAt(i, j, it) } ?: (p.restIdx ?: -1)
            }
        }
    }

    private fun freeCell(pred: (Int, Int, Int) -> Boolean): Triple<Int, Int, Int>? {
        for (i in 0 until p.S) for (j in 0 until p.T) {
            if (p.wishLocked(i, j)) continue
            for (k in 0 until p.K) if (k != input[i][j] && pred(i, j, k)) return Triple(i, j, k)
        }
        return null
    }

    @Test fun startBoardIsInDomain() {
        assertTrue(CpsatCoRun.inDomain(p, input, start()))
    }

    @Test fun placeableValueStaysInDomain() {
        val (i, j, k) = freeCell { a, b, c -> p.mayPlaceAt(a, b, c) }!!
        val b = start(); b[i][j] = k
        assertTrue(CpsatCoRun.inDomain(p, input, b))
    }

    @Test fun valueTheOptimizerMayNotPlaceIsOutOfDomain() {
        // 置けない値（担当外・上限 0 など）で入力とも違う＝CP-SAT の定義域の外（上限 0 の日に置いた最終段と同じ形）。
        val (i, j, k) = freeCell { a, b, c -> !p.mayPlaceAt(a, b, c) }!!
        val b = start(); b[i][j] = k
        assertFalse(CpsatCoRun.inDomain(p, input, b))
    }

    @Test fun wishCellTakesTheWishOrTheInputOnly() {
        // 希望のマスは「希望どおり」か「入力のまま」（規則 A、3.727.103）。それ以外の値は外。
        val (i, j) = (0 until p.S).flatMap { a -> (0 until p.T).map { a to it } }.first { (a, b) -> p.wishLocked(a, b) && !p.pinned(a, b) }
        val other = (0 until p.K).first { it != p.lockTo(i, j) && it != input[i][j] }
        val b = start(); b[i][j] = other
        assertFalse(CpsatCoRun.inDomain(p, input, b))
        val inp = input.map { it.copyOf() }.toTypedArray(); inp[i][j] = other
        assertTrue(CpsatCoRun.inDomain(p, inp, b))   // 入力がその値なら、入力のままとして中
    }
}
