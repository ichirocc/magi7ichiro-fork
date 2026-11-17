package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日の上限人数が 0 の (日, シフト) には、最適化器が新しく置かない（希望は優先・採点は covO のまま）。 */
class DayCapZeroTest {
    private fun load(name: String): MagiState = StateParser.parse(javaClass.getResource("/$name")!!.readText())!!

    private fun capZero(st: MagiState, cells: List<Pair<Int, Int>>): MagiState {
        val d1 = st.needDay1.toMutableMap(); val d2 = st.needDay2.toMutableMap()
        for ((k, j) in cells) { d1["$k,$j"] = "0"; d2["$k,$j"] = "0" }
        return st.copy(needDay1 = d1, needDay2 = d2)
    }

    /** 最適化器が置いた、希望でも固定でもないセルの (シフト, 日) を 3 つ選ぶ（上限 0 にすると置けなくなる値）。 */
    private fun targets(st: MagiState, out: Array<IntArray>): List<Pair<Int, Int>> {
        val p = Problem(st)
        val seen = LinkedHashSet<Pair<Int, Int>>()
        for (j in 0 until p.T) for (i in 0 until p.S) {
            val k = out[i][j]
            if (k < 0 || p.wish[i][j] >= 0 || p.pinned(i, j)) continue
            if ((0 until p.S).any { p.wish[it][j] == k }) continue
            if (seen.size < 3 && (j + k) % 3 == 0) seen.add(k to j)
        }
        assertEquals(3, seen.size)
        return seen.toList()
    }

    @Test fun capZeroBansOnlyNonWishCellsAndLeavesScoring() {
        val st0 = load("sample_state_v6.json")
        val p0 = Problem(st0)
        val i = (0 until p0.S).first { p0.wish[it][0] < 0 }
        val k = p0.allowedShiftsForStaff(i).first()
        val p = Problem(capZero(st0, listOf(k to 0)))
        assertTrue(p.dayCapBanned(i, 0, k))
        assertTrue(p.extBanned(i, 0, k))
        assertFalse(p.extWishBanned(i, 0, k))
        assertFalse(p.mayPlaceAt(i, 0, k))
        assertFalse(p.dayCapBanned(i, 1, k))
        val withWish = (0 until p.S).firstOrNull { p.wish[it][0] >= 0 }
        if (withWish != null) assertFalse(p.dayCapBanned(withWish, 0, p.wish[withWish][0]))
    }

    @Test fun optimizerDoesNotPlaceOnCapZeroDays() = runBlocking {
        val st0 = load("sample_state_v6.json")
        val input = st0.schedule.toIntArray2D()
        val out0 = V6FinalPort.handleOptimize(st0, input.copy2D(), secondsRaw = 2, workers = 1,
            requestedAlgorithm = V6Algorithm.V5, allowImpossible = true, seed = 3L).schedule
        val st = capZero(st0, targets(st0, out0))
        val p = Problem(st)
        val base = HardRepairCore.clearCappedCells(st, p.withManualPins(normalizeSchedule(input, p))).first
        for (algo in listOf(V6Algorithm.V5, V6Algorithm.PORTFOLIO)) {
            val out = V6FinalPort.handleOptimize(st, input.copy2D(), secondsRaw = 2, workers = 2,
                requestedAlgorithm = algo, allowImpossible = true, seed = 5L).schedule
            val fresh = p.extBanNewCells(base, out).filter { (i, j) -> p.dayCapBanned(i, j, out[i][j]) }
            assertTrue("$algo: 上限 0 の日に新しく置いた $fresh", fresh.isEmpty())
        }
    }
}
