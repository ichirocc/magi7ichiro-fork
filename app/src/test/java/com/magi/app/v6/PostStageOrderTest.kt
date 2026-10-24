package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * T-10（全面改修 P4 の安全網、`docs/overhaul_detailed_spec.md` §5）: 決定的モードの後処理で、段の名前（記録の key）の並びと
 * 各段の採用数・巻き戻しが基準版と同じ。後処理を `PostPipeline` へ移すあいだ、段の順序と中身が変わっていないことを守る。
 * 作り直しは `MAGI_UPDATE_SNAPSHOTS=1` のときだけ。
 */
class PostStageOrderTest {
    @Test
    fun deterministicPostStagesMatchTheBaselineOrder() {
        val actual = buildString {
            for (name in CheckerSnapshotTest.FIXTURES) {
                val json = PostStageOrderTest::class.java.getResourceAsStream("/$name.json")?.bufferedReader()?.readText()
                assertNotNull("/$name.json がテストリソースにありません", json)
                val st = StateParser.parse(json!!)!!
                val r = V6HotfixPasses.runPostOptimization(st, st.schedule.toIntArray2D(), "t10", seed = 1L,
                    deadlineMs = EngineClock.nowMs() + 3_600_000L,
                    params = V6HotfixPasses.PostOptimizationParams(deterministic = true,
                        c1LnsMaxEvaluations = 5_000, personalLnsMaxEvaluations = 5_000))
                appendLine("== $name")
                r.stageRecords.forEach { s -> appendLine("${s.key} applied=${s.applied} rolledBack=${s.rolledBack}") }
            }
        }
        val path = "snapshots/post_stage_order.txt"
        if (System.getenv("MAGI_UPDATE_SNAPSHOTS") == "1") { File("src/test/resources/$path").writeText(actual); return }
        val expected = javaClass.getResourceAsStream("/$path")?.bufferedReader()?.readText()
        assertNotNull("/$path がありません（MAGI_UPDATE_SNAPSHOTS=1 で基準版から作る）", expected)
        assertEquals("後処理の段の並び・採用数が基準版と違う", expected, actual)
    }
}
