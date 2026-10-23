package com.magi.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 勤務表を書き換える場所の棚卸し（3.690.0）。ui/ の `currentSchedule =`・UiState の `schedule = …`・`MagiEvent.Board.SetCell(` を含む行を
 * 囲む関数名で集め、許可した経路と照合する。新しい書き込みの場所はテストが落ちる＝案内からの修正は FixApplyGate を通す。
 */
class ScheduleWriteSitesTest {
    private val allowed = setOf(
        "setCell", "setCells", "revealGrid",                      // 手動編集
        "undo", "redo", "rollbackBoardCommit",                    // 元に戻す・やり直す
        "loadAsync", "makeUi", "runCsvImport",                    // 読込・CSV 取込・UiState の組み立て
        "startFullOptimize", "runSoftPolish", "applyBgResultGuarded", "opDays",   // 最適化の採用
        "generateSmartInitial", "applyAlternative", "applyWishes", // 下書き・別案・希望の反映
        "applyStructure", "applyStructureWithMessage",            // 編集タブ
        "relaxAndApply", "restoreBeforeRelax", "restore",         // 設定の緩和と、その事後確認・保存失敗の復旧（restore＝RelaxRollback の控え）
        "applyFixSuggestion",                                     // 改善手（FixApplyGate を通る）
        "maybeRunAgent",                                          // 代行役（AgentLoop の各手が FixApplyGate を通る）
    )
    private val write = Regex("""(\bcurrentSchedule\s*=[^=]|[(,\s]schedule\s*=\s*[a-zA-Z]|MagiEvent\.Board\.SetCell\()""")
    private val fn = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.]+\.)?([A-Za-z0-9_]+)\s*\(""")

    @Test fun everyScheduleWriteIsOnAnAllowedPath() {
        val dir = listOf("app/src/main/java/com/magi/app/ui", "src/main/java/com/magi/app/ui").map(::File).first { it.isDirectory }
        val bad = mutableListOf<String>()
        var sites = 0
        dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.forEach { f ->
            var cur = "?"
            f.readLines().forEachIndexed { n, line ->
                fn.find(line)?.let { cur = it.groupValues[1] }
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                if (write.containsMatchIn(line)) { sites++; if (cur !in allowed) bad += "${f.name}:${n + 1} ($cur)" }
            }
        }
        assertTrue("書き込みの場所が見つかりません（検索の式が古い）", sites > 10)
        assertTrue("勤務表を書き換える新しい場所があります: $bad。案内からの修正は previewFixSuggestion → applyFixSuggestion（FixApplyGate）を通してください", bad.isEmpty())
    }
}
