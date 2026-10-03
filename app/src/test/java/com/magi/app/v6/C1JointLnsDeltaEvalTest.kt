package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C3wRow
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [Config.deltaChildEval] の検証。G1＝子の差分 report が正式 check と全族一致、
 * G2＝決定的モード（評価回数上限）で flag OFF/ON の最終盤面と report が一致。
 */
class C1JointLnsDeltaEvalTest {
    private val fixtures = listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")

    private fun load(name: String): MagiState =
        StateParser.parse(javaClass.getResourceAsStream("/$name")!!.bufferedReader().readText())!!

    private fun synthetic(seed: Long): MagiState {
        val rng = Random(seed)
        val shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", "2"), Shift("B", "B", "1", "1"), Shift("C", "C", "1", ""))
        val staff = List(7) { Staff("s$it", it % 2, (it / 2) % 2) }
        val t = 14
        val schedule = List(staff.size) { i -> List(t) { if (i % 2 == 0) rng.nextInt(3) else listOf(0, 2, 3)[rng.nextInt(3)] } }
        return MagiState(
            startDate = "2025-01-01", endDate = "2025-01-14",
            shifts = shifts, groups = listOf(Group("G0", "G0"), Group("G1", "G1")), staff = staff, use2Patterns = true,
            groupShift = listOf(listOf(1, 1, 1, 0), listOf(1, 0, 1, 1)),
            groupShiftApt = listOf(listOf("", "4", "3", ""), listOf("", "", "3", "4")),
            schedule = schedule, wishes = mapOf("0,0" to 0, "1,4" to 2, "2,2" to 1),
            staffRange = mapOf("0,1" to Range("3", "5"), "1,2" to Range("", "4"), "3,3" to Range("2", "")),
            needDay1 = mapOf("1,0" to "2"), needDay2 = mapOf("2,5" to "2"),
            cons1 = listOf(C1Row("3", "A", "1"), C1Row("5", "A", "2"), C1Row("4", "休", "1"), C1Row("6", "B", "2")),
            cons2 = listOf(C2Row("B", "3")), cons3 = listOf(C3Row(listOf("A", "B"))), cons3n = listOf(C3Row(listOf("C", "C"))),
            cons3m = listOf(C3Row(listOf("B", "A"))), cons3mn = listOf(C3Row(listOf("A", "A"))),
            cons41 = listOf(C41Row("G0", "A", "1", "2")), cons42 = listOf(C42Row("G0", "G1", "A", "C")),
            skillGroups = listOf(Group("SK0", "SK0"), Group("SK1", "SK1")),
            cons41s = listOf(C41Row("SK0", "A", "", "2")), cons42s = listOf(C42Row("SK0", "SK1", "A", "C")),
            cons3w = listOf(C3wRow("B", "C")),
        )
    }

    /** 1〜3 セルの束。3 セル束の半分は同一職員の近接日（c1 窓が重なる）に置く。 */
    private fun randomBundle(rng: Random, board: Array<IntArray>, k: Int): IntArray {
        val s = board.size; val t = board[0].size
        val size = 1 + rng.nextInt(3)
        val out = ArrayList<Int>()
        val used = HashSet<Int>()
        val i0 = rng.nextInt(s); val j0 = rng.nextInt(t)
        while (out.size < size * 3) {
            val near = size == 3 && rng.nextBoolean()
            val i = if (near) i0 else rng.nextInt(s)
            val j = if (near) (j0 + rng.nextInt(5) - 2).coerceIn(0, t - 1) else rng.nextInt(t)
            if (!used.add(i * t + j)) { if (used.size >= s * t) break; continue }
            var nw = rng.nextInt(k)
            if (nw == board[i][j]) nw = (nw + 1) % k
            out += i; out += j; out += nw
        }
        return out.toIntArray()
    }

    private fun assertBundlesMatch(label: String, st: MagiState, board: Array<IntArray>, q: Boolean, n: Int, seed: Long) {
        val p = cachedProblem(st, q)
        val rng = Random(seed)
        val bundles = List(n) { randomBundle(rng, board, p.K) }
        val got = C1JointLnsPolish.DeltaPool(p).evaluate(board, bundles)
        for ((idx, cells) in bundles.withIndex()) {
            val next = board.copy2D()
            for (c in cells.indices step 3) next[cells[c]][cells[c + 1]] = cells[c + 2]
            val want = UnifiedViolationChecker.check(st, next, quantitativeRangeEval = q)
            val tag = "$label q=$q bundle#$idx ${cells.toList()}"
            for (fam in MirrorKeys.all) assertEquals("$tag $fam", want.breakdown[fam] ?: 0, got[idx].breakdown[fam] ?: 0)
            assertEquals("$tag hard", want.hard, got[idx].hard)
            assertEquals("$tag total", want.total, got[idx].total)
            assertEquals("$tag weighted", want.weightedScore, got[idx].weightedScore, 0.0)
            assertEquals("$tag worstWorsened", worstWorsenedFamily(want, UnifiedViolationChecker.check(st, board, quantitativeRangeEval = q)),
                worstWorsenedFamily(got[idx], UnifiedViolationChecker.check(st, board, quantitativeRangeEval = q)))
        }
    }

    @Test
    fun deltaChildReportsMatchCheckerOnRealFixtures() {
        for (name in fixtures) {
            val st = load(name)
            val board = normalizeSchedule(st.schedule.toIntArray2D(), cachedProblem(st))
            for (q in listOf(false, true)) assertBundlesMatch(name, st, board, q, 300, name.hashCode().toLong())
        }
    }

    @Test
    fun deltaChildReportsMatchCheckerOnSyntheticStates() {
        for (seed in 1L..6L) {
            val st = synthetic(seed)
            val board = normalizeSchedule(st.schedule.toIntArray2D(), cachedProblem(st))
            for (q in listOf(false, true)) assertBundlesMatch("synthetic$seed", st, board, q, 400, seed * 31)
        }
    }

    private fun runBoth(st: MagiState, board: Array<IntArray>, q: Boolean, evals: Int): Pair<V6HotfixPasses.CyclicSwapResult, V6HotfixPasses.CyclicSwapResult> {
        fun run(delta: Boolean) = C1JointLnsPolish.apply(
            st, board.copy2D(),
            C1JointLnsPolish.Config(maxMillis = 60_000L, patienceMs = 0L, maxEvaluations = evals, deltaChildEval = delta),
            quantitativeRangeEval = q,
        )
        return run(false) to run(true)
    }

    private fun assertSameResult(label: String, off: V6HotfixPasses.CyclicSwapResult, on: V6HotfixPasses.CyclicSwapResult) {
        assertTrue("$label board", off.newSchedule.contentDeepEquals(on.newSchedule))
        assertEquals("$label applied", off.applied, on.applied)
        assertEquals("$label breakdown", off.report!!.breakdown, on.report!!.breakdown)
        assertEquals("$label weighted", off.report!!.weightedScore, on.report!!.weightedScore, 0.0)
        val strip = { m: String -> m.substringBefore(" 停止=") }
        assertEquals("$label log", strip(off.logs.first().message), strip(on.logs.first().message))
    }

    @Test
    fun deterministicModeGivesTheSameBoardWithAndWithoutDelta() {
        var applied = 0
        for (name in fixtures) {
            val st = load(name)
            val board = st.schedule.toIntArray2D()
            val (off, on) = runBoth(st, board, false, 4000)
            assertSameResult(name, off, on)
            applied += on.applied
        }
        for (seed in 1L..4L) {
            val st = synthetic(seed)
            for (q in listOf(false, true)) {
                val (off, on) = runBoth(st, st.schedule.toIntArray2D(), q, 1500)
                assertSameResult("synthetic$seed q=$q", off, on)
                applied += on.applied
            }
        }
        assertTrue("採用のある経路を通っている", applied >= 4)
    }
}
