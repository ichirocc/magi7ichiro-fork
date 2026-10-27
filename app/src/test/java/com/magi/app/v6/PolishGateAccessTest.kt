package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-7（`docs/overhaul_detailed_spec.md`）: エンジン（v6）は `PolishGate` を読み書きしない。設定は実行の開始時に
 * `EngineConfig.snapshot()` が 1 回だけ写し、探索・後処理はその写しを引数で受け取る（画面・背景 Worker が書くのは自由）。
 */
class PolishGateAccessTest {
    @Test fun engineReadsPolishGateOnlyThroughEngineConfig() {
        val dir = listOf("app/src/main/java/com/magi/app/v6", "src/main/java/com/magi/app/v6").map(::File).first { it.isDirectory }
        val bad = mutableListOf<String>()
        var seen = 0
        dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.forEach { f ->
            val code = f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().map { it.substringBefore("//") }
            code.forEachIndexed { n, line ->
                if (!line.contains("PolishGate.")) return@forEachIndexed
                seen++
                if (f.name != "EngineConfig.kt") bad += "${f.name}:${n + 1}"
            }
        }
        assertTrue("検索の式が古い（EngineConfig の写しが見つからない）", seen > 0)
        assertEquals("エンジンが PolishGate を直接読んでいます（EngineConfig の写しを引数で渡す）", emptyList<String>(), bad)
    }
}
