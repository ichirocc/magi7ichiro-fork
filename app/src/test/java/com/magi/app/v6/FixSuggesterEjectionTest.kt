package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** シートの「直し方を探す」に入れた多段の玉突き連鎖（[FixKind.EJECT]）も、他の手と同じ採用規則を守る。 */
class FixSuggesterEjectionTest {
    @Test
    fun ejectionSuggestionsImproveWithoutNewHardAndMatchTheirReport() {
        val f = File("app/src/test/resources/golden_state.json").let { if (it.exists()) it else File("src/test/resources/golden_state.json") }
        val st = StateParser.parse(f.readText())!!
        val board = st.schedule.toIntArray2D()
        val base = UnifiedViolationChecker.check(st, board)
        val list = FixSuggester.suggest(st, board, maxResults = 20, deadlineMs = 6000L, ejectionChain = true)
        assertEquals("盤面は変えない", base.total, UnifiedViolationChecker.check(st, board).total)
        for (s in list) {
            val after = board.copy2D().also { w -> for (op in s.ops) w[op.staff][op.day] = op.toShift }
            val rep = UnifiedViolationChecker.check(st, after)
            assertTrue("${s.kind} ${s.label}: 改善", betterReport(rep, base))
            assertEquals("${s.kind}: 表示の必須差", rep.hard - base.hard, s.deltaHard)
        }
    }
}
