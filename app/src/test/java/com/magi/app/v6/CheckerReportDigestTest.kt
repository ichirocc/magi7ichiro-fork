package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/**
 * `UnifiedViolationChecker.check` の出力全体（logs の時間を除く）を、4 つの実データ fixture から作った乱択盤面 2000 枚で
 * 1 つのダイジェストに固定する。期待値は割当削減（ef656a5）の前後で同じ値になることを確かめてある。
 * weightedScore を含むので重みを変えると動く（3.647.0 fair 2→5 で更新。旧: 6d863247…）。
 */
class CheckerReportDigestTest {
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
        append(";w=").append(r.weightedScore.toRawBits()).append(";r=").append(r.c1Runs).append('\n')
    }

    @Test
    fun checkerReportDigestIsStable() {
        val fixtures = listOf("oct2026_grid_state.json", "golden_state.json", "sept2026_state.json", "sample_state_v6.json").map(::load)
        val rnd = Random(20260929)
        val md = MessageDigest.getInstance("SHA-256")
        repeat(2000) { n ->
            val st = fixtures[n % fixtures.size]
            val s = st.schedule.toIntArray2D()
            val k = st.shifts.size
            val flips = if (n % 10 == 0) s.size * (s.firstOrNull()?.size ?: 0) else 1 + rnd.nextInt(40)
            repeat(flips) {
                val i = rnd.nextInt(s.size)
                val j = rnd.nextInt(s[i].size)
                s[i][j] = rnd.nextInt(-1, k)
            }
            md.update(canon(UnifiedViolationChecker.check(st, s, quantitativeRangeEval = n % 7 == 0)).toByteArray())
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        assertEquals(EXPECTED, hex)
    }

    private companion object {
        const val EXPECTED = "35a5e43d176f53b9cfa0f6167e4ef3bd2fc69f053c39700f07d60d69e20be372"
    }
}
