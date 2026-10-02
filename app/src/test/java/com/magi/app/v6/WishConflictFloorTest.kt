package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [E0] 希望衝突の床（`V6SanityPort.wishConflictHardFloor`）の数え方と、どの盤面の HARD も超えないこと（健全性）。 */
class WishConflictFloorTest {
    private val rest = 0; private val a = 1

    private fun state(
        wishes: Map<String, Int>,
        needDay1: Map<String, String> = emptyMap(),
        staffRange: Map<String, Range> = emptyMap(),
        cons3n: List<C3Row> = emptyList(),
    ) = MagiState(
        startDate = "2026-10-01", endDate = "2026-10-07",
        shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("A", "A", "", "")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("P", 0), Staff("Q", 0)),
        use2Patterns = false, groupShift = listOf(listOf(1, 1)), groupShiftApt = emptyList(),
        schedule = List(2) { List(7) { rest } }, wishes = wishes, staffRange = staffRange,
        needDay1 = needDay1, needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = cons3n, cons3m = emptyList(),
        cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun floor(st: MagiState) = V6SanityPort.wishConflictHardFloor(Problem(st))

    @Test fun forbiddenWindowFullyWishedCountsOne() {
        val st = state(mapOf("0,2" to rest, "0,3" to rest, "0,4" to rest), cons3n = listOf(C3Row(listOf("休", "休", "休"))))
        assertEquals(1, floor(st))
    }

    @Test fun dayWhoseNeedCannotBeMetUnderPinsCountsOne() {
        val st = state(mapOf("1,0" to rest), needDay1 = mapOf("1,0" to "2"))
        assertEquals(1, floor(st))
    }

    @Test fun dayProofThatReliesOnZeroCapIsNotCounted() {
        val st = state(mapOf("0,0" to rest), needDay1 = mapOf("1,0" to "1"), staffRange = mapOf("1,1" to Range("", "0")))
        val p = Problem(st)
        assertTrue(ConstraintMus.analyzeDayConflicts(p).isNotEmpty())
        assertTrue(ConstraintMus.dayProofsWithoutZeroCap(p).isEmpty())
        assertEquals(0, floor(st))
    }

    @Test fun dayProofOnAConflictDayIsNotCountedTwice() {
        val st = state(
            mapOf("0,2" to rest, "0,3" to rest, "0,4" to rest, "1,3" to rest),
            needDay1 = mapOf("1,3" to "1"), cons3n = listOf(C3Row(listOf("休", "休", "休"))),
        )
        assertEquals(1, floor(st))
    }

    @Test fun floorNeverExceedsHardOnRealFixtures() {
        for (name in listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json", "oct2026_grid_state.json")) {
            val st = StateParser.parse(javaClass.classLoader!!.getResource(name)!!.readText())!!
            val p = Problem(st)
            val f = V6SanityPort.wishConflictHardFloor(p)
            val rnd = java.util.Random(7)
            val boards = listOf(st.schedule.toIntArray2D()) + List(30) { Array(p.S) { IntArray(p.T) { rnd.nextInt(p.K) } } } +
                List(30) { Array(p.S) { i -> IntArray(p.T) { j -> if (p.wishFixed(i, j) && rnd.nextInt(4) > 0) p.wish[i][j] else rnd.nextInt(p.K) } } }
            for (b in boards) {
                val r = UnifiedViolationChecker.check(st, b)
                val h = r.hard
                assertTrue("$name floor=$f hard=$h", f <= h)
                val w = V6SanityPort.wishConflictHard(p, b)
                assertTrue("$name c3n", (w["c3n"] ?: 0) <= (r.breakdown["c3n"] ?: 0))
                assertTrue("$name c3w", (w["c3w"] ?: 0) <= (r.breakdown["c3w"] ?: 0))
                assertTrue("$name 衝突の件数は床以上", (w["c3n"] ?: 0) + (w["c3w"] ?: 0) + (w["pref"] ?: 0) >= V6SanityPort.wishConflictFloorParts(p).first)
                if (V6FinalPort.wishFloorReached(h, f) { V6SanityPort.hardAllWishOrigin(p, b, r, V6SanityPort.wishConflictFloorParts(p).second) })
                    assertEquals(f, h)
            }
        }
    }

    @Test fun mayPlaceFloorNeverExceedsHardOnBoardsThatRespectMayPlace() {
        for (name in listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json", "oct2026_grid_state.json")) {
            val st = StateParser.parse(javaClass.classLoader!!.getResource(name)!!.readText())!!
            val p = Problem(st)
            val f = V6SanityPort.wishConflictFloorParts(p, zeroCapBinding = true).let { it.first + it.second }
            assertTrue(f >= V6SanityPort.wishConflictHardFloor(p))
            val rnd = java.util.Random(11)
            repeat(60) {
                val b = Array(p.S) { i -> IntArray(p.T) { j ->
                    if (p.wishFixed(i, j) && rnd.nextInt(4) > 0) p.wish[i][j]
                    else (0 until p.K).filter { k -> p.mayPlace(i, k) }.let { ks -> if (ks.isEmpty()) 0 else ks[rnd.nextInt(ks.size)] }
                } }
                val h = UnifiedViolationChecker.check(st, b).hard
                assertTrue("$name mp floor=$f hard=$h", f <= h)
            }
        }
    }

    @Test fun boardAtTheFloorTriggersE0bEarlyStop() {
        // 希望どうしの衝突 1 組だけの盤面＝床 1。探索はすぐ床に届き、E0B は短い閾値で止まり後処理の研磨を省く。
        val st = state(mapOf("0,2" to rest, "0,3" to rest, "0,4" to rest), cons3n = listOf(C3Row(listOf("休", "休", "休"))))
        assertEquals(1, floor(st))
        val t0 = System.currentTimeMillis()
        val res = kotlinx.coroutines.runBlocking {
            V6FinalPort.handleOptimize(st, secondsRaw = 60, workers = 2, allowImpossible = true, wishFloorMode = WishFloorMode.E0B)
        }
        val ms = System.currentTimeMillis() - t0
        assertEquals(1, res.report.hard)
        assertTrue(res.logs.joinToString("\n") { it.message }, res.logs.any { it.tag == "EarlyStop" && it.message.contains("希望衝突の床に到達＝E0B") })
        assertTrue(res.logs.any { it.tag == "Watchdog" && it.message.contains("希望衝突の床1=到達") })
        assertTrue(res.logs.any { it.tag == "Watchdog" && Regex("入力超えの最終改善=(なし|経過\\d+s)・").containsMatchIn(it.message) })
        assertTrue("早く返す: ${ms}ms", ms < 55_000)
    }
}
