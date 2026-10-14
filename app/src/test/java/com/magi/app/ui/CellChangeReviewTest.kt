package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** セルを 1 つ変える前の確認（2026-10-14 利用者レビュー「問題を確認 → 変更方法を選ぶ → 影響を確認して適用」）を実データで固定する。 */
class CellChangeReviewTest {
    private fun load(path: String): MagiState =
        StateParser.parse(javaClass.getResourceAsStream(path)!!.bufferedReader().readText())!!
    private val st = load("/oct2026_grid_state.json")
    private val s = st.schedule.toIntArray2D()
    private val rep = UnifiedViolationChecker.check(st, s)

    @Test fun reviewIsTheCheckersBeforeAndAfter() {
        // 禁止の並びのあるセルを 1 つ選び、別の勤務にしたときの確認を作る。
        val key = rep.cellFamilies.entries.first { "vio-c3n" in it.value }.key
        val (i, j) = key.split(",").map { it.toInt() }
        val k = (0 until st.shiftCount).first { it != s[i][j] }
        val names = st.staff.map { it.name }; val syms = st.shifts.map { it.kigou }
        val r = cellChangeReview(st, s, i, j, k, names, syms, null, null, pinned = false)
        val trial = Array(s.size) { s[it].copyOf() }.also { it[i][j] = k }
        val after = UnifiedViolationChecker.check(st, trial)
        assertEquals("必須違反（勤務表全体）：${rep.hard}件 → ${after.hard}件", r.hardLine)
        assertEquals("${names[i]}さん　${DayText.full(st.startDate, j)} ${syms[s[i][j]]} → ${syms[k]}", r.change)
        assertTrue(r.neighbors.contains("${DayText.short(st.startDate, j)}：${syms[k]}"))
        // このセルの禁止の並びは、変えた後の報告に同じ場所の鍵が残るかどうかで「解消／残る」を言う。
        val stillHere = "vio-c3n" in after.cellFamilies[key].orEmpty()
        assertTrue(r.thisCell.any { it == "禁止の並び：" + if (stillHere) "残ります" else "解消します" })
        assertEquals(r.counts.size, listOf(s[i][j], k).distinct().size)
        assertTrue(r.counts.all { it.startsWith("本人の") })
    }

    @Test fun wishAndPinLinesAndProblemSentences() {
        val (i, j) = 0 to 0
        val names = st.staff.map { it.name }; val syms = st.shifts.map { it.kigou }
        val k = (0 until st.shiftCount).first { it != s[i][j] }
        val wished = st.copy(wishes = st.wishes + ("$i,$j" to k))
        assertEquals("希望（${syms[k]}）どおりになります", cellChangeReview(wished, s, i, j, k, names, syms, null, null, false).wishPin.first())
        assertTrue(cellChangeReview(st.copy(wishes = st.wishes - "$i,$j"), s, i, j, k, names, syms, null, null, true).wishPin.first().startsWith("このセルは手動固定"))
        assertEquals("前後の勤務との組み合わせが禁止条件に該当しています", cellProblemSentence("c3n"))
        assertEquals("10/28(水) Dテ → 10/29(木) B4", sequenceLine("2026-10-01", List(31) { if (it == 27) 0 else 1 }, listOf(28, 27), listOf("Dテ", "B4")))
        assertEquals(null, sequenceLine("2026-10-01", listOf(0), listOf(0), listOf("A")))
    }

    @Test fun boardStatusSeparatesAutoCreateFromEvaluation() {
        assertEquals("自動作成：未実行" to "評価：未評価", boardStatusLines(false, false, false, 0, 0))
        assertEquals("自動作成：未実行（手で編集）" to "評価：必須違反 3件", boardStatusLines(false, true, true, 3, 5))
        assertEquals("自動作成：済み" to "評価：要調整 5件", boardStatusLines(true, true, true, 0, 5))
        assertEquals("自動作成：済み" to "評価：違反なし", boardStatusLines(true, true, true, 0, 0))
    }

    @Test fun cellCountLinesKeepTheTargetVisible() {
        val u = UiState(shiftSymbols = listOf("休", "Cオ", "B4"), schedule = listOf(listOf(2, 1, 2, 1, 0)), countViolations = mapOf("0,1" to "vio-aptLow"))
        val limits = { _: Int, k: Int -> when (k) { 1 -> Triple(null, null, 6); 2 -> Triple(null, null, 5); else -> Triple(null, null, null) } }
        assertEquals(listOf("B4：現在2回／目標5回", "Cオ：現在2回／目標6回"), cellCountLines(u, 0, 2, limits))
    }
}
