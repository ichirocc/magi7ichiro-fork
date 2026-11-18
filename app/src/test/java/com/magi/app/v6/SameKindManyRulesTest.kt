package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * 同種の制約が複数あるとき（同じシフトの C1 を 2 本・推奨/回避/禁止の並びを複数・同じグループのレンジを複数 など）、
 * チェッカーと Evaluator の族ごとの件数が一致し、DeltaEvaluator がフル評価と一致すること（2026-11-18 利用者「同種の制約が複数あると正しい最適化しない」）。
 */
class SameKindManyRulesTest {
    private val k = 4   // 0 休・1 A・2 B・3 C

    private fun randomState(rng: Random): MagiState {
        val s = 6; val t = 14
        val staffRange = HashMap<String, Range>()
        for (i in 0 until s) for (x in 1 until k) when (rng.nextInt(4)) {
            0 -> staffRange["$i,$x"] = Range("", "0")
            1 -> staffRange["$i,$x"] = Range(rng.nextInt(4).toString(), (3 + rng.nextInt(5)).toString())
        }
        return MagiState(
            startDate = "2026-12-01", endDate = "2026-12-14",
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("A", "A", "1", "2"), Shift("B", "B", "1", ""), Shift("C", "C", "", "")),
            groups = listOf(Group("G0", "G0"), Group("G1", "G1")), staff = (0 until s).map { Staff("s$it", it % 2, it % 2) },
            use2Patterns = true, groupShift = listOf(List(k) { 1 }, List(k) { 1 }),
            groupShiftApt = listOf(listOf("", "3", "", ""), listOf("", "", "2", "")),
            schedule = List(s) { List(t) { rng.nextInt(k) } },
            wishes = emptyMap(), staffRange = staffRange, needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row("7", "休", "2"), C1Row("14", "休", "4"), C1Row("5", "休", "1"), C1Row("14", "A", "1"), C1Row("7", "A", "2")),
            cons2 = listOf(C2Row("B", "2"), C2Row("B", "3"), C2Row("C", "1")),
            cons3 = listOf(C3Row(listOf("A", "A", "休")), C3Row(listOf("B", "B"))),
            cons3n = listOf(C3Row(listOf("A", "B")), C3Row(listOf("A", "C")), C3Row(listOf("A", "B"))),
            cons3m = listOf(C3Row(listOf("A", "A", "休", "休")), C3Row(listOf("休", "休")), C3Row(listOf("B", "休"))),
            cons3mn = listOf(C3Row(listOf("休", "A", "休")), C3Row(listOf("A", "休", "A"))),
            cons41 = listOf(C41Row("G0", "A", "1", "2"), C41Row("G0", "A", "", "1"), C41Row("G1", "B", "1", "")),
            cons42 = listOf(C42Row("G0", "G1", "A", "B"), C42Row("G0", "G0", "A", "A")),
            skillGroups = listOf(Group("K0", "K0"), Group("K1", "K1")),
            cons41s = listOf(C41Row("K0", "B", "", "1"), C41Row("K0", "B", "1", "")),
            cons42s = listOf(C42Row("K0", "K1", "C", "C"), C42Row("K1", "K1", "A", "B")),
        )
    }

    @Test fun checkerAndEvaluatorAgreePerFamily() {
        val rng = Random(20261118)
        repeat(300) {
            val st = randomState(rng)
            val p = Problem(st)
            val b = st.schedule.map { it.toIntArray() }.toTypedArray()
            val r = UnifiedViolationChecker.check(st, b)
            val bd = HashMap<String, Long>()
            Evaluator(p).fullEvalParts(b, bd)
            for (key in listOf("c1", "c2", "c3", "c3n", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covU", "covO", "low", "high"))
                assertEquals("族 $key", (r.breakdown[key] ?: 0).toLong(), bd[key] ?: 0L)
        }
    }

    @Test fun deltaMatchesFullWithManyRulesOfTheSameKind() {
        val rng = Random(7)
        repeat(20) {
            val p = Problem(randomState(rng))
            val ev = Evaluator(p)
            val de = DeltaEvaluator(p)
            repeat(2_000) {
                val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
                val old = de.at(i, j)
                de.apply(i, j, rng.nextInt(k))
                assertEquals(ev.fullEval(de.snapshot()), de.score())
                if (rng.nextBoolean()) { de.apply(i, j, old); assertEquals(ev.fullEval(de.snapshot()), de.score()) }
            }
        }
    }
}
