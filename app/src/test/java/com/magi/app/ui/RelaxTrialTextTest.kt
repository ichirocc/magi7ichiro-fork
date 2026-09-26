package com.magi.app.ui

import com.magi.app.model.StateParser
import com.magi.app.v6.FixSuggester
import com.magi.app.v6.RelaxTrial
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.V6SanityPort
import com.magi.app.v6.WishTrial
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [S6] 実データ（2026-10、氏名は伏せ字）で、次にやることの段の前提・ダイアログの文・手置きの設定の行（`docs/s6_relax_trial.md` §2.1・§5・§14 Q5）。 */
class RelaxTrialTextTest {
    private val st = StateParser.parse(javaClass.getResource("/oct2026_grid_state.json")!!.readText())!!
    private val board = st.schedule.toIntArray2D()
    private val rep = UnifiedViolationChecker.check(st, board)
    private val ui = UiState(
        staffNames = st.staff.map { it.name },
        shiftSymbols = st.shifts.map { it.kigou },
        schedule = board.map { it.toList() },
        violationCellFamilies = rep.cellFamilies,
        wishes = st.wishes,
        lockedWishKeys = WishTrial.lockedWishKeys(st),
        wishSelfConflicts = V6SanityPort.wishSelfConflicts(st),
    )

    @Test fun realData_ladderGoesToRelaxBeforeWishesAndTextNamesTheSet() {
        assertTrue("1 手では必須が減らない（段の前提）", FixSuggester.suggest(st, board, maxResults = 8).none { it.deltaHard < 0 })
        assertFalse("希望の段も出る盤面（S6 がその前に来る）", wishTrialCandidates(ui).isEmpty)
        val r = RelaxTrial.firstWall(st, board) as RelaxTrial.Result
        val t = relaxTrialText(r, ui)
        assertEquals(listOf("職員10 Pｼ 上限 0→1", "職員11 Cｵ 上限 0→1"), t.rows.map { it.substringBefore("（") })
        assertEquals("手で置いた勤務に合わせて上限を上げ、この組も緩めると、必須違反が 1件 減る見込みです。", t.lead)
        assertTrue(t.title, t.title.startsWith("職員10 ") && t.title.endsWith("禁止の並び"))
        assertTrue(t.moveLines.isNotEmpty() && t.moveLines.all { it.contains("→") })
        assertEquals(r.moves.size, t.moveLines.sumOf { it.count { c -> c == '→' } } + t.otherMoves)
        assertEquals("設定を緩めて手順を当てました: 必須違反 5 → 4。元に戻すで設定と勤務表をまとめて戻せます。", relaxDoneLine(r.h0, r.rr))
    }

    @Test fun realData_handPlacedUpperZeroGetsOneSettingsLine() {
        val lines = V6SanityPort.build(st, board).guidance.filter { it.problem.contains("上限 0 と食い違っています") }
        assertEquals(1, lines.size)
        assertEquals("手で置いた勤務 4件 が上限 0 と食い違っています。もう一度つくると外されます", lines[0].problem)
    }
}
