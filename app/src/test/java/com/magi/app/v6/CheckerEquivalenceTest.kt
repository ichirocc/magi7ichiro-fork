package com.magi.app.v6

import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * `UnifiedViolationChecker.check` と高速化前の写し `CheckerReferenceV0` の報告を全フィールド（マップは挿入順込み、
 * weightedScore は生ビット、ログは時間の括弧を除く文面・水準・タグ）で突き合わせる。
 * 盤面は実データ fixture そのもの＋乱択の書き換え、規則は fixture のもの＋乱択で足した c41/c42/c41s/c42s（所属の範囲外・未所属・自己ペアを含む）。
 */
class CheckerEquivalenceTest {
    private fun load(name: String): MagiState =
        StateParser.parse(javaClass.getResourceAsStream("/$name")!!.bufferedReader().readText())!!

    private fun StringBuilder.map(tag: String, m: Map<String, *>) {
        append(tag).append('{')
        for ((k, v) in m) append(k).append('=').append(v).append(';')
        append('}')
    }

    private fun canon(r: ViolationReport): String = buildString {
        map("v", r.violations); map("n", r.needViolations); map("c", r.countViolations)
        map("cf", r.cellFamilies); map("kf", r.countFamilies); map("nf", r.needFamilies)
        map("b", r.breakdown); map("d", r.distLocations)
        append("t=").append(r.total).append(";h=").append(r.hard).append(";s=").append(r.soft)
        append(";w=").append(r.weightedScore.toRawBits()).append(";r=").append(r.c1Runs)
        for (l in r.logs) append(";log=").append(l.iter).append('/').append(l.level).append('/').append(l.tag).append('/')
            .append(l.message.replace(Regex(""" \(\d+ms\)$"""), ""))
    }

    private fun assertSame(label: String, st: MagiState, s: Array<IntArray>, q: Boolean) {
        val want = canon(CheckerReferenceV0.check(st, s, quantitativeRangeEval = q))
        val got = canon(UnifiedViolationChecker.check(st, s, quantitativeRangeEval = q))
        assertEquals(label, want, got)
    }

    private fun perturb(st: MagiState, rnd: Random, n: Int): Array<IntArray> {
        val s = st.schedule.toIntArray2D()
        val k = st.shifts.size
        val flips = if (n % 10 == 0) s.size * (s.firstOrNull()?.size ?: 0) else 1 + rnd.nextInt(40)
        repeat(flips) {
            val i = rnd.nextInt(s.size)
            val j = rnd.nextInt(s[i].size)
            s[i][j] = rnd.nextInt(-1, k)
        }
        return s
    }

    /** 規則と所属を乱択で足した状態。範囲の端（0・空欄）、同じ (グループ, シフト) の自己ペア、所属 -1・範囲外を混ぜる。 */
    private fun augmented(base: MagiState, rnd: Random): MagiState {
        val skills = base.skillGroups.ifEmpty { listOf(Group("S甲", "S甲"), Group("S乙", "S乙"), Group("S丙", "S丙")) }
        val shifts = base.shifts.map { it.kigou }
        fun bound() = if (rnd.nextInt(4) == 0) "" else rnd.nextInt(0, 4).toString()
        fun c41(groups: List<Group>) = List(rnd.nextInt(1, 6)) {
            val l = bound(); val u = bound()
            C41Row(groups.random(rnd).kigou, shifts.random(rnd), l, if (l.isEmpty() && u.isEmpty()) "1" else u)
        }
        fun c42(groups: List<Group>) = List(rnd.nextInt(1, 6)) {
            val g1 = groups.random(rnd).kigou; val s1 = shifts.random(rnd)
            if (rnd.nextInt(4) == 0) C42Row(g1, g1, s1, s1) else C42Row(g1, groups.random(rnd).kigou, s1, shifts.random(rnd))
        }
        return base.copy(
            skillGroups = skills,
            staff = base.staff.map { it.copy(skillIdx = rnd.nextInt(-1, skills.size + 1)) },
            cons41 = base.cons41 + c41(base.groups), cons42 = base.cons42 + c42(base.groups),
            cons41s = base.cons41s + c41(skills), cons42s = base.cons42s + c42(skills),
        )
    }

    @Test
    fun reportsMatchTheReferenceOnRealAndRandomBoards() {
        val names = listOf("oct2026_grid_state.json", "golden_state.json", "sept2026_state.json", "sample_state_v6.json",
            "full_coverage_state.json", "blocked_covu_state.json")
        val fixtures = names.map(::load)
        for ((name, st) in names.zip(fixtures)) for (q in listOf(false, true)) assertSame("$name q=$q", st, st.schedule.toIntArray2D(), q)
        val rnd = Random(20261003)
        repeat(4000) { n ->
            val st = fixtures[n % fixtures.size]
            assertSame("random#$n ${names[n % names.size]}", st, perturb(st, rnd, n), n % 7 == 0)
        }
        repeat(200) { m ->
            val st = augmented(fixtures[m % fixtures.size], rnd)
            for (r in 0 until 10) {
                val n = m * 10 + r
                val board = if (r == 0) st.schedule.toIntArray2D() else perturb(st, rnd, n)
                assertSame("augmented#$m/$r ${names[m % names.size]}", st, board, n % 7 == 0)
            }
        }
    }
}
