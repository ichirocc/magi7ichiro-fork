package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S6 の確定は終わり方を問わず「S6反映 結果=…」を 1 行残す: relaxAndApply の各 return の直前の区間に applyLog がちょうど 1 つ。 */
class RelaxApplyLogSitesTest {
    @Test fun everyExitOfRelaxAndApplyLogsOneResultLine() {
        val f = listOf("app/src/main/java/com/magi/app/ui", "src/main/java/com/magi/app/ui").map(::File).first { it.isDirectory }.resolve("MagiViewModel.kt")
        val src = f.readText()
        val start = src.indexOf("internal fun relaxAndApply(")
        val end = src.indexOf("\n    }\n", start)
        assertTrue(start >= 0 && end > start)
        val segs = src.substring(start, end).split("return RelaxApproval.Verdict.").dropLast(1)
        assertTrue(segs.size >= 8)
        segs.forEachIndexed { i, seg -> assertEquals("return #$i", 1, Regex("""RelaxApproval\.applyLog\(""").findAll(seg).count()) }
    }
}
