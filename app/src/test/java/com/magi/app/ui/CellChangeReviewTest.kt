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
        assertEquals("必須違反（勤務表全体）：${hardText(rep.breakdown, rep.hard.toLong())} → ${hardText(after.breakdown, after.hard.toLong())}", r.hardLine)
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
        assertEquals("自動作成前" to "評価：未評価", boardStatusLines(false, false, false, 0, 0))
        assertEquals("手で編集" to "評価：必須違反 3件", boardStatusLines(false, true, true, 3, 5))
        assertEquals("自動作成済み" to "評価：要調整 5件", boardStatusLines(true, true, true, 0, 5))
        assertEquals("自動作成済み" to "評価：違反なし", boardStatusLines(true, true, true, 0, 0))
        // [3.678.0] 自動作成のあと手で変えた盤面は「未実行」と言わない。
        assertEquals("手で変更あり", boardStatusLines(false, true, true, 0, 0, ranBeforeEdit = true).first)
        assertEquals("手で編集", boardStatusLines(false, true, true, 0, 0, ranBeforeEdit = false).first)
    }

    @Test fun cellCountLinesKeepTheTargetVisible() {
        val u = UiState(shiftSymbols = listOf("休", "Cオ", "B4"), schedule = listOf(listOf(2, 1, 2, 1, 0)), countViolations = mapOf("0,1" to "vio-aptLow"))
        val limits = { _: Int, k: Int -> when (k) { 1 -> Triple(null, null, 6); 2 -> Triple(null, null, 5); else -> Triple(null, null, null) } }
        assertEquals(listOf("B4：現在2回／目標5回", "Cオ：現在2回／目標6回"), cellCountLines(u, 0, 2, limits))
    }

    /** 選んだセルの問題が案で実際に消えるか（族の全体の件数ではなく、この場所の違反の同一性で判定する）。 */
    @Test fun resolvesAtCellLooksAtThisPlaceOnly() {
        val key = rep.cellFamilies.entries.first { "vio-c3n" in it.value }.key
        val (i, j) = key.split(",").map { it.toInt() }
        for (k in 0 until st.shiftCount) {
            if (k == s[i][j]) continue
            val ops = listOf(com.magi.app.v6.FixCell(i, j, k))
            val trial = Array(s.size) { s[it].copyOf() }.also { it[i][j] = k }
            val gone = "vio-c3n" !in UnifiedViolationChecker.check(st, trial).cellFamilies[key].orEmpty()
            assertEquals(gone, resolvesAtCell(st, s, rep, ops, i, j, "c3n"))
        }
        // 別の職員の別の日だけを変える案は、このセルの問題を解消しない。
        val other = (0 until st.staffCount).first { it != i }
        val far = (j + 10) % st.dayCount
        val k2 = (0 until st.shiftCount).first { it != s[other][far] }
        val farTrial = Array(s.size) { s[it].copyOf() }.also { it[other][far] = k2 }
        val stillHere = "vio-c3n" in UnifiedViolationChecker.check(st, farTrial).cellFamilies[key].orEmpty()
        if (stillHere) assertEquals(false, resolvesAtCell(st, s, rep, listOf(com.magi.app.v6.FixCell(other, far, k2)), i, j, "c3n"))
        // 問題の無いセルは解消しない（族を選ばなくても）。
        val clean = (0 until st.staffCount).flatMap { a -> (0 until st.dayCount).map { a to it } }.first { (a, b) ->
            rep.cellFamilies["$a,$b"].isNullOrEmpty() && rep.countFamilies["$a,${s[a][b]}"].isNullOrEmpty() && rep.needFamilies["${s[a][b]},$b"].isNullOrEmpty()
        }
        assertEquals(false, resolvesAtCell(st, s, rep, listOf(com.magi.app.v6.FixCell(clean.first, clean.second, s[clean.first][clean.second])), clean.first, clean.second, null))
    }

    @Test fun newHardLabelsAreTheCheckersNewKeys() {
        val names = st.staff.map { it.name }; val syms = st.shifts.map { it.kigou }
        val (i, j) = 0 to 0
        for (k in 0 until st.shiftCount) {
            val ops = listOf(com.magi.app.v6.FixCell(i, j, k))
            val trial = Array(s.size) { s[it].copyOf() }.also { it[i][j] = k }
            val expected = com.magi.app.v6.ViolationIdentity.newHard(rep, UnifiedViolationChecker.check(st, trial)).size
            val got = newHardLabels(st, s, ops, names, syms)
            assertEquals(expected, got.size)
            assertTrue(got.all { it.startsWith("必須・") })
        }
    }

    @Test fun previewWishAndConsultLines() {
        val rows = listOf(ChangeRow("福澤", "11/3", "休", "Aア", "希望（休）と異なる勤務になります"), ChangeRow("金沢", "11/5", "A4", "休", "希望どおり"))
        assertEquals("希望と異なる勤務になる：福澤さん 11/3" to "必要な相談：福澤さん（希望と異なる勤務になる日）", previewWishLines(rows))
        assertEquals("変更する勤務に希望はありません" to "必要な相談：なし", previewWishLines(listOf(ChangeRow("a", "1", "x", "y"))))
        assertEquals("変更箇所：2人・3セル", changeSizeLine(listOf(com.magi.app.v6.FixCell(0, 1, 1), com.magi.app.v6.FixCell(0, 2, 1), com.magi.app.v6.FixCell(3, 1, 0))))
        val t = FixTarget(null, 1, 2, 1, true, "必要人数")
        assertEquals(listOf("11/3のAアの不足を減らす変更案を探しています", "ほかの日や職員の変更が必要な場合は、関連する変更もまとめて確認します。"), targetSearchingLines(t, "11/3のAア"))
    }
}
