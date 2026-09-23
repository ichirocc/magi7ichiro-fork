package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 違反 20 族の「位置の種類」を固定する。勤務表のセルシートに出るのはセル（職員×日）と人員（シフト×日）の族だけで、
 * 回数（職員×シフト）と位置なしの族は出ない。族を増やしたらここに分類を足す＝セルシートへの出し忘れに気づける。
 */
class ViolationLocationPartitionTest {
    private val cell = setOf("pref", "groupViol", "c3n", "c3w", "c1", "c3", "c3m", "c3mn", "c42", "c42s")
    private val need = setOf("covU", "covO", "c41", "c41s")
    private val count = setOf("c2", "low", "high", "apt")
    private val none = setOf("fair", "weekly")

    private fun fam(cls: String): String = cls.removePrefix("vio-").let { if (it == "aptLow" || it == "aptHigh") "apt" else it }

    @Test fun partitionCoversAllFamiliesExactlyOnce() {
        val parts = listOf(cell, need, count, none)
        assertEquals(MirrorKeys.all.size, parts.sumOf { it.size })
        assertEquals(MirrorKeys.all.toSet(), parts.flatten().toSet())
        assertEquals(5, MirrorKeys.hard.size)
        assertEquals(15, MirrorKeys.soft.size)
    }

    @Test fun everyHardFamilyIsShownOnTheCellSheet() {
        assertTrue(MirrorKeys.hard.all { it in cell || it in need })
    }

    @Test fun checkerPlacesEachFamilyInItsDeclaredLocation() {
        val seen = HashSet<String>()
        for (f in listOf("full_coverage_state.json", "golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")) {
            val st = StateParser.parse(File("src/test/resources/$f").takeIf { it.exists() }?.readText() ?: File("app/src/test/resources/$f").readText())
            val r = UnifiedViolationChecker.check(st, Array(st.staffCount) { st.schedule[it].toIntArray() })
            r.cellFamilies.values.flatten().map(::fam).forEach { assertTrue("$f: $it はセル族でない", it in cell); seen += it }
            r.needFamilies.values.flatten().map(::fam).forEach { assertTrue("$f: $it は人員族でない", it in need); seen += it }
            r.countFamilies.values.flatten().map(::fam).forEach { assertTrue("$f: $it は回数族でない", it in count); seen += it }
        }
        for ((name, part) in listOf("セル" to cell, "人員" to need, "回数" to count)) assertTrue("$name 族が一度も観測されない: $seen", seen.any { it in part })
    }
}
