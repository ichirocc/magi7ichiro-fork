package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 全面改修 P0 の安全網（`docs/overhaul_design.md` §6.1 の 3）。6 fixture について `V6SanityPort.build` の診断
 * （警告・注記・設定の案内・不可能な希望など）を基準版の写しと照合する。作り直しは `MAGI_UPDATE_SNAPSHOTS=1` のときだけ。
 */
class DiagnosisSnapshotTest {
    @Test
    fun diagnosisMatchesTheBaselineSnapshotForEveryFixture() {
        val update = System.getenv("MAGI_UPDATE_SNAPSHOTS") == "1"
        for (name in CheckerSnapshotTest.FIXTURES) {
            val json = javaClass.getResourceAsStream("/$name.json")?.bufferedReader()?.readText()
            assertNotNull("/$name.json がテストリソースにありません", json)
            val actual = render(V6SanityPort.build(StateParser.parse(json!!)!!))
            val path = "snapshots/diagnosis_$name.txt"
            if (update) { File("src/test/resources/$path").writeText(actual); continue }
            val expected = javaClass.getResourceAsStream("/$path")?.bufferedReader()?.readText()
            assertNotNull("/$path がありません（MAGI_UPDATE_SNAPSHOTS=1 で基準版から作る）", expected)
            assertEquals("診断が基準版と違う（$name）", expected, actual)
        }
    }

    private fun render(r: V6SanityReport): String = buildString {
        appendLine("ok=${r.ok}")
        r.warns.forEach { appendLine("warn $it") }
        r.notes.forEach { appendLine("note $it") }
        appendLine("bits ${r.loadDataBitSummary}")
        r.loadDataBitDetails.forEach { appendLine("bit $it") }
        r.shiftCountDiagnostics.forEach { appendLine("shiftCount $it") }
        r.impossibleWishes.forEach { appendLine("impossible $it") }
        r.duplicateSeqConstraints.forEach { appendLine("dupSeq $it") }
        r.guidance.forEach { appendLine("guide $it") }
    }
}
