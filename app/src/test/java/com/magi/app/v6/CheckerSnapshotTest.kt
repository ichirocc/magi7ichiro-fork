package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 全面改修 P0 の安全網（`docs/overhaul_design.md` §6.1 の 2）。6 fixture の入力盤面について、チェッカーの出力
 * （族別件数・hard/soft/total/weightedScore・セル/被覆/回数の各キー空間の違反と重なり）を基準版の写しと照合する。
 * 期待値の作り直しは `MAGI_UPDATE_SNAPSHOTS=1` のときだけ（CI では作り直さない）。
 */
class CheckerSnapshotTest {
    @Test
    fun checkerOutputMatchesTheBaselineSnapshotForEveryFixture() {
        val update = System.getenv("MAGI_UPDATE_SNAPSHOTS") == "1"
        for (name in FIXTURES) {
            val json = javaClass.getResourceAsStream("/$name.json")?.bufferedReader()?.readText()
            assertNotNull("/$name.json がテストリソースにありません", json)
            val st = StateParser.parse(json!!)!!
            val actual = render(UnifiedViolationChecker.check(st))
            val path = "snapshots/checker_$name.txt"
            if (update) { File("src/test/resources/$path").writeText(actual); continue }
            val expected = javaClass.getResourceAsStream("/$path")?.bufferedReader()?.readText()
            assertNotNull("/$path がありません（MAGI_UPDATE_SNAPSHOTS=1 で基準版から作る）", expected)
            assertEquals("チェッカーの出力が基準版と違う（$name）", expected, actual)
        }
    }

    companion object {
        val FIXTURES = listOf("golden_state", "sample_state_v6", "blocked_covu_state", "sept2026_state", "oct2026_grid_state", "full_coverage_state")

        fun render(r: ViolationReport): String = buildString {
            appendLine("hard=${r.hard} soft=${r.soft} total=${r.total} weighted=${r.weightedScore}")
            for (k in MirrorKeys.all) appendLine("bd.$k=${r.breakdown[k] ?: 0}")
            fun section(tag: String, m: Map<String, *>) = m.keys.sorted().forEach { appendLine("$tag $it=${m[it]}") }
            section("cell", r.violations)
            section("need", r.needViolations)
            section("count", r.countViolations)
            section("cellF", r.cellFamilies)
            section("countF", r.countFamilies)
            section("needF", r.needFamilies)
            r.extWishCells.sorted().forEach { appendLine("extWish $it") }
        }
    }
}
