package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** シフトの間隔（gap）: かたまりの最後の日から次のかたまりの最初の日までの日数差が N 未満なら N − 差 を数える（3.727.109）。 */
class GapRuleTest {
    private val D = 1

    @Test fun deficitCountsShortGapsBetweenBlocks() {
        assertEquals(0, GapRule.deficit(intArrayOf(1, 0, 0, 0, 0, 1), D, 5))         // 1 日目→6 日目＝差 5
        assertEquals(1, GapRule.deficit(intArrayOf(1, 0, 0, 0, 1, 0), D, 5))         // 差 4
        assertEquals(3, GapRule.deficit(intArrayOf(1, 0, 1, 0, 0, 0), D, 5))         // 差 2
        assertEquals(0, GapRule.deficit(intArrayOf(1, 1, 0, 0, 0, 0, 1), D, 5))      // かたまりの最後（2 日目）→7 日目＝差 5
        assertEquals(1, GapRule.deficit(intArrayOf(0, 1, 1, 0, 0, 0, 1), D, 5))      // 3 日目→7 日目＝差 4
        assertEquals(3 + 2, GapRule.deficit(intArrayOf(1, 0, 1, 0, 0, 1), D, 5))     // 差 2 と 差 3
        assertEquals(0, GapRule.deficit(intArrayOf(0, 1, 1, 1, 0, 0), D, 5))         // かたまり 1 つ＝前が無い
    }

    @Test fun violatingStartsAreTheLaterBlocks() {
        val out = ArrayList<Int>()
        GapRule.violatingStarts(intArrayOf(1, 0, 1, 0, 0, 0, 0, 0, 1), D, 5) { out.add(it) }
        assertEquals(listOf(2), out)
    }

    private val k = 4   // 0 休・1 D・2 A・3 B

    private fun randomState(rng: Random): MagiState {
        val s = 6; val t = 21
        val staffRange = HashMap<String, Range>()
        staffRange["0,1"] = Range("", "0")   // 職員 0 は D の上限 0 回＝対象外
        return MagiState(
            startDate = "2026-12-01", endDate = "2026-12-21",
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("D", "D", "1", "1"), Shift("A", "A", "1", ""), Shift("B", "B", "", "")),
            groups = listOf(Group("G0", "G0"), Group("G1", "G1")), staff = (0 until s).map { Staff("s$it", it % 2, it % 2) },
            use2Patterns = true, groupShift = listOf(List(k) { 1 }, listOf(1, 0, 1, 1)),
            groupShiftApt = listOf(List(k) { "" }, List(k) { "" }),
            schedule = List(s) { List(t) { rng.nextInt(k) } },
            wishes = emptyMap(), staffRange = staffRange, needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row("7", "休", "2")), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
            consGap = listOf(GapRow("D", "5"), GapRow("休", "3"), GapRow("D", "3")),
        )
    }

    @Test fun checkerAndEvaluatorAgree() {
        val rng = Random(20261119)
        var sawGap = false
        repeat(300) {
            val st = randomState(rng)
            val p = Problem(st)
            val b = st.schedule.map { it.toIntArray() }.toTypedArray()
            val r = UnifiedViolationChecker.check(st, b)
            val bd = HashMap<String, Long>()
            Evaluator(p).fullEvalParts(b, bd)
            assertEquals((r.breakdown["gap"] ?: 0).toLong(), bd["gap"] ?: 0L)
            val manual = p.consGap.sumOf { c -> (0 until p.S).sumOf { i -> if (p.c1Applies(i, c.shiftIdx)) GapRule.deficit(b[i], c.shiftIdx, c.days) else 0 } }
            assertEquals(manual, r.breakdown["gap"] ?: 0)
            if (manual > 0) sawGap = true
        }
        assertTrue(sawGap)
    }

    @Test fun upperZeroAndCannotDoAreExcluded() {
        val st = randomState(Random(1)).let { s0 ->
            s0.copy(schedule = List(6) { i -> List(21) { j -> if (i <= 1 && j % 2 == 0) 1 else 0 } }, consGap = listOf(GapRow("D", "5")))
        }
        val r = UnifiedViolationChecker.check(st, st.schedule.map { it.toIntArray() }.toTypedArray())
        // 職員 0 は D の上限 0、職員 1 は G1＝D を担当できない → どちらも数えない
        assertEquals(0, r.breakdown["gap"] ?: 0)
    }

    @Test fun deltaMatchesFull() {
        val rng = Random(7)
        repeat(20) {
            val p = Problem(randomState(rng))
            val ev = Evaluator(p)
            val de = DeltaEvaluator(p)
            de.reset(randomState(rng).schedule.map { it.toIntArray() }.toTypedArray())
            repeat(3_000) {
                val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
                val old = de.at(i, j)
                de.apply(i, j, rng.nextInt(k))
                assertEquals(ev.fullEval(de.snapshot()), de.score())
                if (rng.nextBoolean()) { de.apply(i, j, old); assertEquals(ev.fullEval(de.snapshot()), de.score()) }
            }
            val bd = HashMap<String, Long>()
            ev.fullEvalParts(de.snapshot(), bd)
            assertEquals(bd["gap"] ?: 0L, de.familyRaw()["gap"])
        }
    }

    @Test fun csvRoundTripKeepsRows() {
        val st = randomState(Random(5))
        val r = ConstraintsCsvIO.parse(ConstraintsCsvIO.build(st), st)!!
        assertEquals(st.consGap, r.state.consGap)
        assertEquals(0, r.rejected)
    }

    @Test fun shiftRenameFollowsRows() {
        val st = randomState(Random(6))
        assertEquals(2, Ws1Ops.shiftRefCount(st, "D"))
    }

    @Test fun jsonRoundTripKeepsRows() {
        val st = randomState(Random(3))
        val back = StateParser.parse(StateParser.serialize(st, st.schedule.map { it.toIntArray() }.toTypedArray()))
        assertEquals(st.consGap, back.consGap)
    }
}
