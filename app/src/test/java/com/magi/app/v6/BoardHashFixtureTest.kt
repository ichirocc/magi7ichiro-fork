package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 全面改修 P0 の安全網（`docs/overhaul_design.md` §6.1 の 1）。6 fixture × seed 1〜3 で、決定的モードの後処理を通した
 * 最終盤面を `StateFingerprint` でハッシュし、基準版の写しと照合する。作り直しは `MAGI_UPDATE_SNAPSHOTS=1` のときだけ。
 * 探索本体は決定的でないため対象外（時間制の対比較が受け持つ、§3.3）。
 */
class BoardHashFixtureTest {
    @Test
    fun deterministicPostChainBoardMatchesTheBaselineHashForEveryFixtureAndSeed() {
        val actual = buildString {
            for (name in CheckerSnapshotTest.FIXTURES) {
                val json = javaClass.getResourceAsStream("/$name.json")?.bufferedReader()?.readText()
                assertNotNull("/$name.json がテストリソースにありません", json)
                val st = StateParser.parse(json!!)!!
                val input = st.schedule.toIntArray2D()
                for (seed in 1L..3L) {
                    val out = V6HotfixPasses.runPostOptimization(st, input.copy2D(), "p0", seed = seed,
                        deadlineMs = EngineClock.nowMs() + 3_600_000L,
                        params = V6HotfixPasses.PostOptimizationParams(deterministic = true,
                            c1LnsMaxEvaluations = 5_000, personalLnsMaxEvaluations = 5_000)).schedule
                    val hash = StateFingerprint.of(st.copy(schedule = out.map { it.toList() }))
                    appendLine("$name seed=$seed hash=$hash")
                }
            }
        }
        val path = "snapshots/board_hash.txt"
        if (System.getenv("MAGI_UPDATE_SNAPSHOTS") == "1") { File("src/test/resources/$path").writeText(actual); return }
        val expected = javaClass.getResourceAsStream("/$path")?.bufferedReader()?.readText()
        assertNotNull("/$path がありません（MAGI_UPDATE_SNAPSHOTS=1 で基準版から作る）", expected)
        assertEquals("決定的モードの最終盤面が基準版と違う", expected, actual)
    }
}
