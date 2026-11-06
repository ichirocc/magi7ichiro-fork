package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 勤務表を書き換える場所の棚卸し（3.690.0）。ui/ の `currentSchedule =`・UiState の `schedule = …`・`MagiEvent.Board.SetCell(` を含む行を
 * 囲む関数名で集め、許可した経路と照合する。新しい書き込みの場所はテストが落ちる＝案内からの修正は FixApplyGate を通す。
 */
class ScheduleWriteSitesTest {
    private val allowed = setOf(
        "setCell", "setCells",                                    // 手動編集
        "MagiApp",                                                // 画面からのセル編集のイベント（MagiEvent.Board.SetCell → setCell）
        "undo", "redo", "rollbackBoardCommit",                    // 元に戻す・やり直す
        "restorePreDraft",                                        // 下書きに置き換える前の勤務表へ戻す（元に戻すと同じく保存済みの盤面を戻すだけ）
        "loadAsync", "makeUi", "runCsvImport", "commitCsvBoard",  // 読込・CSV 取込（commitCsvBoard は runCsvImport だけが呼ぶ）・UiState の組み立て
        "startFullOptimize", "runSoftPolish", "applyBgResultGuarded",  // 最適化の採用
        "init",                                                   // 背景結果の反映に失敗したとき、今の勤務表を表示に戻す（init の購読）
        "generateSmartInitial", "applyAlternative", "applyWishes", // 下書き・別案・希望の反映
        "applyStructure", "applyStructureWithMessage",            // 編集タブ
        "relaxAndApply", "restoreBeforeRelax", "restore",         // 設定の緩和と、その事後確認・保存失敗の復旧（restore＝RelaxRollback の控え）
        "applyFixSuggestion", "commitFixBoard",                   // 改善手（FixApplyGate を通る）。commitFixBoard は applyFixSuggestion だけが呼ぶ（下の検査）
        "maybeRunAgent",                                          // 代行役（AgentLoop の各手が FixApplyGate を通る）
        "differsOnlyInPinsOrColors",                              // 比較のための写し（盤面は書かない）
    )
    private val write = Regex("""(\bcurrentSchedule\s*=[^=]|[(,\s]schedule\s*=\s*[a-zA-Z]|MagiEvent\.Board\.SetCell\()""")
    private val fn = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.]+\.)?([A-Za-z0-9_]+)\s*\(""")
    private val typeDecl = Regex("""\b(class|object|interface)\s""")

    /**
     * 各行の持ち主（ファイル直下の関数か、クラス直下のメンバ）。関数の中の局所関数（例: 画面の `revealGrid`）は
     * 持ち主を変えない＝その後ろの行が局所関数の名で許可を素通りしない。クラス直下の init・プロパティもそれぞれの名を持つ。
     */
    private fun owners(lines: List<String>): List<String> {
        var cur = "?"; var inType = false
        return lines.map { line ->
            val t = line.trimStart(); val indent = line.length - t.length
            val decl = t.isNotEmpty() && !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") && !t.startsWith("@") && !t.startsWith("}") && !t.startsWith(")")
            if (decl && indent == 0) {
                val f = fn.find(line)
                if (f != null && !typeDecl.containsMatchIn(line.substringBefore("fun"))) { inType = false; cur = f.groupValues[1] }
                else if (typeDecl.containsMatchIn(line)) { inType = true; cur = "?" }
            } else if (decl && indent == 4 && inType) {
                cur = fn.find(line)?.groupValues?.get(1) ?: if (t.startsWith("init")) "init" else "?"
            }
            cur
        }
    }

    @Test fun everyScheduleWriteIsOnAnAllowedPath() {
        val dir = listOf("app/src/main/java/com/magi/app/ui", "src/main/java/com/magi/app/ui").map(::File).first { it.isDirectory }
        val bad = mutableListOf<String>()
        var sites = 0
        dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.forEach { f ->
            val lines = f.readLines()
            val own = owners(lines)
            lines.forEachIndexed { n, line ->
                val cur = own[n]
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                if (write.containsMatchIn(line)) { sites++; if (cur !in allowed) bad += "${f.name}:${n + 1} ($cur)" }
            }
        }
        assertTrue("書き込みの場所が見つかりません（検索の式が古い）", sites > 10)
        assertTrue("勤務表を書き換える新しい場所があります: $bad。案内からの修正は previewFixSuggestion → applyFixSuggestion（FixApplyGate）を通してください", bad.isEmpty())
        // 本体の書き込み口（extension 用、全面改修 P6a・P6e）は決まった関数からだけ呼ぶ。
        for ((writer, caller) in listOf("commitFixBoard" to "applyFixSuggestion", "commitCsvBoard" to "runCsvImport")) {
            val callers = mutableListOf<String>()
            dir.listFiles { f -> f.extension == "kt" }!!.forEach { f ->
                val lines = f.readLines()
                val own = owners(lines)
                lines.forEachIndexed { n, line ->
                    val cur = own[n]
                    if (Regex("""\b$writer\(""").containsMatchIn(line) && !line.contains("fun $writer")) callers += cur
                }
            }
            assertEquals(writer, listOf(caller), callers)
        }
    }
}
