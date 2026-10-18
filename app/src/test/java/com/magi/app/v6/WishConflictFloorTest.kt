package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.ManualPin
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        pins: List<ManualPin> = emptyList(),
    ) = MagiState(
        startDate = "2026-10-01", endDate = "2026-10-07",
        shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("A", "A", "", "")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("P", 0), Staff("Q", 0)),
        use2Patterns = false, groupShift = listOf(listOf(1, 1)), groupShiftApt = emptyList(),
        schedule = List(2) { List(7) { rest } }, wishes = wishes, staffRange = staffRange,
        needDay1 = needDay1, needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = cons3n, cons3m = emptyList(),
        cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(), manualPins = pins,
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

    private fun fixtures() = listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json", "oct2026_grid_state.json")
        .map { StateParser.parse(javaClass.classLoader!!.getResource(it)!!.readText())!! }

    @Test fun pinOnZeroCapShiftServesItsSeatInTheDayProof() {
        // P は A の上限 0 だが 10/1 を手で A に固定（最適化器は守る）。Q は 10/1 が休の希望。A の必要 1 人は P の固定が埋める＝日の証明は立たない。
        val st = state(mapOf("1,0" to rest), needDay1 = mapOf("1,0" to "1"), staffRange = mapOf("0,1" to Range("", "0")), pins = listOf(ManualPin(0, 0, a)))
        val p = Problem(st)
        assertFalse(p.mayPlace(0, a))
        assertTrue(ConstraintMus.analyzeDayConflicts(p).isEmpty())
        assertEquals(0, V6SanityPort.wishConflictFloorParts(p, zeroCapBinding = true).second)
        assertEquals(0, floor(st))
        // 固定を守る盤面の HARD は 0（上限 0 は要調整）＝上限 0 に頼る床も超えない
        val b = arrayOf(intArrayOf(a, rest, rest, rest, rest, rest, rest), IntArray(7) { rest })
        assertEquals(0, UnifiedViolationChecker.check(st, b).hard)
        // 固定が無ければ上限 0 に頼る日の証明が立つ（従来どおり）
        assertEquals(1, V6SanityPort.wishConflictFloorParts(Problem(st.copy(manualPins = emptyList())), zeroCapBinding = true).second)
    }

    @Test fun coverageDiagCountsAPinnedSeatAsFilled() {
        // A の必要 2 人。P は上限 0 だが 10/1 を A に固定、Q は休＝不足 1 は Q を移せば埋まる（充足可能）。旧: P を数えず 1 人 < 2 で「充足不可」。
        val st = state(emptyMap(), needDay1 = mapOf("1,0" to "2"), staffRange = mapOf("0,1" to Range("", "0")), pins = listOf(ManualPin(0, 0, a)))
        val b = arrayOf(intArrayOf(a, rest, rest, rest, rest, rest, rest), IntArray(7) { rest })
        val s = V6PortAnalyzer.diagnoseCoverage(st, b).shortfalls.single()
        assertEquals(CoverageVerdict.FIXABLE, s.verdict)
        assertEquals(2, s.capacity)
        assertTrue(ConstraintMus.dayShortProofs(Problem(st)).isEmpty())
    }

    @Test fun pinOnAnAllowedShiftCountsLikeAWish() {
        // 上限の無いシフトへの固定は希望固定と同じ: 休に固定した Q は A の席に就けない＝希望の場合と同じ床 1、コアには手動固定が載る。
        val wished = state(mapOf("1,0" to rest), needDay1 = mapOf("1,0" to "2"))
        val pinned = state(emptyMap(), needDay1 = mapOf("1,0" to "2"), pins = listOf(ManualPin(1, 0, rest)))
        assertEquals(floor(wished), floor(pinned))
        assertEquals(1, floor(pinned))
        val core = ConstraintMus.analyzeDayConflicts(Problem(pinned)).single().core
        assertTrue(core.any { it is ConstraintMus.WishPin && it.manual && it.staff == 1 })
        assertTrue(V6SanityPort.buildGuidance(pinned).any { it.problem.contains("手動固定「Q 10/1(木)=休」") })
        // 席を埋める側の固定（A に固定、上限なし）は証明に影響しない
        assertEquals(0, floor(state(emptyMap(), needDay1 = mapOf("1,0" to "1"), pins = listOf(ManualPin(0, 0, a)))))
    }

    @Test fun mayPlaceFloorNeverExceedsHardOnBoardsThatRespectMayPlaceAndPins() {
        for (st0 in fixtures()) {
            val p0 = Problem(st0)
            val rnd = java.util.Random(13)
            // 上限 0 の (職員,シフト) があれば 3 日おきにそこへ手動固定（無ければ担当可のシフトへ）
            val pins = ArrayList<ManualPin>()
            for (i in 0 until p0.S) {
                val zero = (0 until p0.K).filter { k -> p0.canDo(i, k) && !p0.mayPlace(i, k) }
                val ks = if (zero.isEmpty()) (0 until p0.K).filter { k -> p0.canDo(i, k) } else zero
                if (ks.isEmpty()) continue
                for (j in (i % 3) until p0.T step 3) pins += ManualPin(i, j, ks[rnd.nextInt(ks.size)])
            }
            val st = st0.copy(manualPins = pins)
            val p = Problem(st)
            assertTrue(p.hasPins)
            val f = V6SanityPort.wishConflictFloorParts(p, zeroCapBinding = true).let { it.first + it.second }
            val strict = V6SanityPort.wishConflictHardFloor(p)
            assertTrue(f >= strict)
            repeat(60) {
                val b = Array(p.S) { i -> IntArray(p.T) { j ->
                    if (p.pinned(i, j)) p.lockTo(i, j)
                    else if (p.wishFixed(i, j) && rnd.nextInt(4) > 0) p.wish[i][j]
                    else (0 until p.K).filter { k -> p.mayPlace(i, k) }.let { ks -> if (ks.isEmpty()) 0 else ks[rnd.nextInt(ks.size)] }
                } }
                val h = UnifiedViolationChecker.check(st, b).hard
                assertTrue("pins floor=$f hard=$h", f <= h)
                assertTrue("pins strict floor=$strict hard=$h", strict <= h)
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
        // [3.643.0] 画面の説明へ渡す終わり方の要約は Watchdog 行と同じ種別＝希望衝突の床で早期終了
        assertEquals(V6FinalPort.StopKind.WISH_FLOOR, res.stop?.kind)
        assertTrue(res.stop!!.earlyStop)
        assertTrue(res.logs.any { it.tag == "StopSummary" && it.message.contains("WISH_FLOOR") })
        assertTrue("早く返す: ${ms}ms", ms < 55_000)
    }
}
