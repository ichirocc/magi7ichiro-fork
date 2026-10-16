package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationKey
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.682.0: 残りの UX 監査（足りない側のセル・確認の一文・反映の帯・回数の案・ホームと分析の見出し・画面の言葉）。 */
class Review3682Test {
    private val st: MagiState = StateParser.parse(javaClass.getResourceAsStream("/oct2026_grid_state.json")!!.bufferedReader().readText())!!
    private val s = st.schedule.toIntArray2D()
    private val names = st.staff.map { it.name }
    private val syms = st.shifts.map { it.kigou }

    @Test fun shortSideCellIsNotTheCellsProblem() {
        assertEquals(setOf("covU", "low"), shortSideFamilies(listOf("vio-covU"), listOf("vio-low")))
        assertEquals(setOf("apt"), shortSideFamilies(listOf("vio-covO"), listOf("vio-aptLow")))
        assertTrue(shortSideFamilies(listOf("vio-covO"), listOf("vio-high", "vio-aptHigh")).isEmpty())
        assertEquals("福澤さんのA4は必要な勤務です（変えると 10/8 の A4 の不足が 2人に増えます）",
            shortSideCaution("福澤", "A4", "10/8", 2, 7, "下限", 8))
        assertEquals("福澤さんの休は必要な勤務です（変えると 休 が 7回になり、下限 8回をさらに下回ります）",
            shortSideCaution("福澤", "休", "10/8", null, 7, "下限", 8))
        assertEquals("10/8 の A4 に入れる人を探す", shortSideSearchButton("10/8", "A4"))
    }

    @Test fun reviewVerdictFollowsTheChecker() {
        val rep = UnifiedViolationChecker.check(st, s)
        val key = rep.cellFamilies.entries.first { "vio-c3n" in it.value }.key
        val (i, j) = key.split(",").map { it.toInt() }
        for (k in 0 until st.shiftCount) {
            if (k == s[i][j]) continue
            val r = cellChangeReview(st, s, i, j, k, names, syms, null, null, false)
            val after = UnifiedViolationChecker.check(st, Array(s.size) { s[it].copyOf() }.also { it[i][j] = k })
            when {
                after.hard > rep.hard -> { assertTrue(r.verdictBad); assertTrue(r.verdict!!.startsWith("この変更で必須違反が増えます（")) }
                "vio-c3n" !in after.cellFamilies[key].orEmpty() -> { assertFalse(r.verdictBad); assertTrue(r.verdict!!.contains("禁止の並び")) }
            }
        }
        // 希望も手動固定も無いセルは、その節を出さない。
        val (a, b) = (0 until st.staffCount).flatMap { x -> (0 until st.dayCount).map { x to it } }.first { (x, d) -> "$x,$d" !in st.wishes }
        assertTrue(cellChangeReview(st, s, a, b, (s[a][b] + 1) % st.shiftCount, names, syms, null, null, false).wishPin.isEmpty())
        assertEquals("新しく生じる・悪くなる問題", REVIEW_APPEARED_HEADING)
        assertEquals("今は変更しない", KEEP_UNCHANGED_BUTTON)
        assertEquals("希望を外す（元に戻せます）", REMOVE_WISH_BUTTON)
    }

    @Test fun keyGapMeasuresHeadcountAndCounts() {
        val board = arrayOf(intArrayOf(1, 0), intArrayOf(0, 0))
        val need = { k: Int, _: Int -> if (k == 1) 2 to 2 else null }
        val lim = { _: Int, k: Int -> if (k == 0) Triple(3, 4, null) else Triple(null, null, null) }
        assertEquals(Triple("不足", "人", 1), violationKeyGap(ViolationKey("covU", ViolationKey.Space.NEED, 1, 0), board, lim, need))
        assertEquals(Triple("不足", "回", 2), violationKeyGap(ViolationKey("low", ViolationKey.Space.COUNT, 0, 0), board, lim, need))
        assertNull(violationKeyGap(ViolationKey("c3n", ViolationKey.Space.CELL, 0, 0), board, lim, need))
    }

    @Test fun countTargetCardsNameOtherStaffAndMoves() {
        val sched = listOf(listOf(0, 0, 1), listOf(1, 0, 0))
        val ops = listOf(FixCell(0, 2, 0), FixCell(1, 1, 1))
        val lim = { _: Int, k: Int -> if (k == 0) Triple(2, null, null) else Triple(null, null, null) }
        assertEquals("（下限 8回を下回ります）", countCrossNote(8, 7, 8, null))
        assertEquals("（上限 4回を超えます）", countCrossNote(4, 5, null, 4))
        assertEquals("", countCrossNote(9, 8, 8, null))
        assertEquals(listOf("新たに生じる：${breakdownLabels["low"]}（ノアさんの休）"), newCountBreakLines(sched, ops, 0, lim, listOf("モニカ", "ノア"), listOf("休", "A")))
        assertTrue(staffCountNotes(sched, ops, listOf("モニカ", "ノア"), listOf("休", "A"), lim, 0).contains("ノアさんの「休」：2回 → 1回（下限 2回を下回ります）"))
        val t = FixTarget(0, 0, null, 3, true, "下限")
        assertTrue(countMovesOnly(FixSuggestion(FixKind.CHAIN, ops, "x", 0, 0, listOf("low" to 0)), t))
        assertFalse(countMovesOnly(FixSuggestion(FixKind.CHAIN, ops, "x", 0, 0, listOf("low" to -1)), t))
        assertFalse(countMovesOnly(FixSuggestion(FixKind.CHAIN, ops, "x", 0, 0, listOf("low" to 0)), FixTarget(null, 0, 1, 2, true, "必要人数")))
    }

    @Test fun homeAndAnalysisWording() {
        assertEquals("必須違反 3件 を減らす手があります", homeFixHeadline("3件", emptyList()))
        assertEquals("必須違反 3件 のうち 1件（禁止の並び：福澤さん 10/3）を減らす手があります", homeFixHeadline("3件", listOf("禁止の並び：福澤さん 10/3")))
        assertTrue(homeFixHeadline("3件", listOf("a", "b", "c")).contains("（a、b ほか）"))
        val rep = UnifiedViolationChecker.check(st, s)
        val key = rep.cellFamilies.entries.first { "vio-c3n" in it.value }.key
        val (i, j) = key.split(",").map { it.toInt() }
        val labels = (0 until st.shiftCount).filter { it != s[i][j] }.flatMap { resolvedHardLabels(st, s, listOf(FixCell(i, j, it)), names, syms) }
        assertTrue(labels.all { it.contains("：") })
        val hardFix = FixSuggestion(FixKind.CHAIN, emptyList(), "x", -1, -1, listOf("c3n" to -1, "c1" to 1))
        assertEquals("必須違反の変化：${breakdownLabels["c3n"]} −1件", fixBenefitLine(hardFix))
        assertEquals("要調整の変化：${breakdownLabels["c1"]} −1件", fixBenefitLine(FixSuggestion(FixKind.CHAIN, emptyList(), "x", 0, -1, listOf("c1" to -1))))
        assertNull(fixBenefitLine(FixSuggestion(FixKind.CHAIN, emptyList(), "x", 0, 0, emptyList())))
    }

    @Test fun userFacingLabelsAndMessages() {
        assertEquals("3人の勤務を順に入れ替える（5か所）", fixDisplayLabel("（玉突き連鎖）3人・5マスを順に入れ替え"))
        assertEquals("10/8 の 4人の勤務を組み直す", fixDisplayLabel("（再最適化）10/8 の4名を最適割当"))
        assertEquals("福澤さんの A4 を 2回増やす", fixDisplayLabel("（連鎖）福澤 の「A4」不足を2コマ補充"))
        assertEquals("入替", fixDisplayLabel("入替"))
        assertEquals("自動で整えました。要調整 12件 → 9件（必須違反はなしのまま）", polishResultText(true, "12件", "9件", 0, "なし"))
        assertEquals("自動で整えましたが、要調整は減りませんでした（勤務表は変えていません）", polishResultText(false, "12件", "12件", 0, "なし"))
        assertEquals("休 8回 → 7回（下限 8回を割ります）", guidedCandidateImpact("休", 8, 8))
        assertEquals("休 9回 → 8回", guidedCandidateImpact("休", 9, 8))
        assertNull(guidedCandidateImpact(null, 0, null))
        assertEquals("ほかの必須違反（1件）が残っています", otherHardAfterShortage(mapOf("covU" to 2, "c3n" to 1)))
        assertNull(otherHardAfterShortage(mapOf("covU" to 2)))
    }
}
