package com.magi.app.ui

import com.magi.app.v6.ChangeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.683.0 の UX 監査（文言と表示の純関数）。 */
class Review3683Test {
    @Test fun wishDayButtonNamesTheDay() {
        assertEquals("11/3 の希望を見る", wishDayButton("11/3"))
        assertEquals("希望を見る", wishDayButton(null))
    }

    @Test fun countSectionOnlyWithCountFamilies() {
        assertTrue(showCountSection(listOf("0,1"), 0, listOf("A 3回")))
        assertFalse(showCountSection(listOf("1,1"), 0, listOf("A 3回")))
        assertFalse(showCountSection(listOf("0,1"), 0, emptyList()))
    }

    @Test fun compactNoFixLines() {
        assertEquals("この場所を直す案は見つかりませんでした。理由A", compactNoFixLine(listOf("理由A", "理由B"), null, null, null))
        assertEquals("この場所を直す案は見つかりませんでした", compactNoFixLine(emptyList(), null, null, null))
        assertEquals("このセルの禁止の並びを解消する案は見つかりませんでした。前後の日（11/2・11/4）を開いて確かめられます",
            compactNoFixLine(listOf("理由A"), "禁止の並び", "11/2", "11/4"))
    }

    @Test fun staffSheetPreselectsSingleRow() {
        assertEquals(2, staffSheetPreselect(listOf(2, -1)))
        assertNull(staffSheetPreselect(listOf(1, 2)))
    }

    @Test fun creationTopUsesStaffName() {
        assertEquals("福澤さん 禁止の並び", creationNextProblem("福澤", "禁止の並び"))
        assertEquals("福澤 11/3 禁止の並び", creationNextProblem("福澤", "福澤 11/3 禁止の並び"))
        assertEquals(listOf(CREATION_DONE_TITLE, "次に確認する問題：X"), creationTopLines("X"))
        assertEquals(KeptResultText.KEPT_TITLE, creationTopLines(null, kept = true).single())
    }

    @Test fun changeSummaryUsesUnitsWithoutWeights() {
        val s = ChangeSummary(12, 84, 42, 42, true, mapOf("c1" to -2, "weekly" to 3, "covU" to -1))
        assertEquals("変更：12人・84か所／希望どおり 42/42件", s.line())
        assertEquals("減った：covU 1人・c1 2件／増えた：weekly 3pt", s.familyLine(unit = ::unitOf))
    }

    @Test fun preRunRepeatHint() {
        assertEquals("前回と同じ探索時間・方式で再作成します。前回いちばん多い必須違反は禁止の並び（3件）です。同じ設定では同じ違反が残りやすいため、編集タブでこれを 1 つ緩めると減る見込みがあります。",
            preRunRepeatHintText("禁止の並び（3件）"))
    }

    @Test fun alternativesCompareWithNow() {
        assertEquals("案1：必須違反 2件（今より多い）", alternativeSummary(1, "2件", 1, 2, true))
        assertEquals("案2：必須違反 なし（今より少ない）", alternativeSummary(2, "なし", 1, 0, false))
        assertTrue(alternativesAllWorse(listOf(true, true), 2))
        assertFalse(alternativesAllWorse(listOf(true), 2))
    }

    @Test fun notificationHasNoPt() {
        assertEquals(listOf("必須違反はありません", "要調整 2件。配布前の確認で中身を確かめてください"), notifyDoneLines(0, mapOf("c1" to 2, "weekly" to 9)))
    }

    @Test fun wordingConstants() {
        assertEquals("条件ごとの状況（守れている 10／残っている 3）", conditionStatusTitle(10, 3))
        assertEquals("担当外の希望 4件をまとめて外す（勤務表は変わりません・元に戻せます）", bulkClearWishesLabel(4))
        assertEquals("変更案を見る", VIEW_FIXES_BUTTON)
    }
}
