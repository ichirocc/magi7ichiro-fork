package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.707.0] 編集タブ① 月次条件の見出しカード（editHeaderPlan）・希望の行の数え方（人）・ぶつかっている希望の要約（W-1）。
 * 寄せたときに選んだ日が残るか（3a-4）は Composable の動きなので android-sdk.yml と実機で確かめる。
 */
class Review3707Test {
    private val ready = EditHeaderCounts(month = "2026年11月", staff = 5, shifts = 4, wish = WishEntryCounts(3, 1, 2),
        needStdOk = true, needExceptions = 2, issues = 0)

    @Test fun monthlyHeaderHasTitleSubtitleAndThreeOneUnitPoints() {
        val p = editHeaderPlan(0, ready, panelOpen = false, ranBeforeEdit = false, running = false)
        assertEquals("月次条件（2026年11月）", p.title)
        assertEquals(EDIT_SCOPE_MONTHLY_NOTE, p.subtitle)
        assertEquals(listOf("希望・休暇 入力あり 3人（拡張希望のみ 1人）・未入力 2人", "必要人数 標準あり・例外 2件", "入力診断 問題なし"), p.points)
        assertTrue(p.points.size <= 3)
        assertEquals(EditHeaderPrimary.EDIT_NEED, p.primary)
        assertNull(p.secondary)
        assertTrue(editBarSecondary(p))
    }

    @Test fun monthlyPrimaryTable() {
        fun prim(c: EditHeaderCounts = ready, panel: Boolean = false, edited: Boolean = false, running: Boolean = false) =
            editHeaderPlan(0, c, panel, edited, running).primary
        assertEquals(EditHeaderPrimary.EDIT_NEED, prim())
        assertEquals(EditHeaderPrimary.RERUN, prim(edited = true))
        assertEquals(EditHeaderPrimary.PANEL, prim(panel = true, edited = true))
        assertNull(prim(panel = true, running = true))
        assertEquals(EditHeaderPrimary.OPEN_MASTER, prim(ready.copy(shifts = 0, staff = 0)))
        assertEquals(EditHeaderPrimary.ADD_STAFF, prim(ready.copy(staff = 0)))
        // 塗りは 1 つ（Q10）: RERUN のときだけ下部バーが塗り。
        assertTrue(!editBarSecondary(editHeaderPlan(0, ready, false, true, false)))
        assertTrue(editBarSecondary(editHeaderPlan(0, ready, true, true, false)))
        assertTrue(!editBarSecondary(editHeaderPlan(0, ready, false, false, true)))
    }

    @Test fun monthlyPointsVariants() {
        val p = editHeaderPlan(0, ready.copy(month = "", needStdOk = false, needExceptions = 0, issues = 3, wishConflicts = true), false, false, false)
        assertEquals("月次条件", p.title)
        assertEquals("必要人数 標準が未設定・例外 0件", p.points[1])
        assertEquals("入力診断 見直し 3件", p.points[2])
        assertEquals(EditHeaderSecondary.WISH_CONFLICTS, p.secondary)
    }

    @Test fun wishEntryTextCountsPeopleInNin() {
        assertEquals("入力あり 2人・未入力 1人", wishEntryText(wishEntryCounts(3, listOf("0,1", "1,2", "1,3"), emptyList())))
        assertTrue(!wishEntryText(WishEntryCounts(3, 1, 2)).contains("名"))
    }

    @Test fun wishCardTexts() {
        assertEquals("佐藤さん：希望 3件・拡張希望 1件", wishStaffSummary("佐藤", 3, 1))
        assertEquals("ぶつかっている希望を見る（佐藤さん 11/24(火)）", wishConflictLinkText("佐藤", "11/24(火)"))
        assertEquals("3日を夜の希望にする", wishApplyButton(3, "夜"))
        assertEquals("2日を選んだシフト以外にする", extWishApplyButton(2))
        assertEquals("4日に適用する", needApplyButton(4))
        assertEquals("⚠「夜」は佐藤さんの担当外です。希望は登録できますが、配置すると必須違反になります。", wishOutOfScopeNote("夜", "佐藤"))
        assertEquals("拡張希望を登録できませんでした。希望のある日だけです。日かシフトを選び直して、もう一度登録してください。", extWishFailedText("希望のある日だけです。"))
    }

    @Test fun wishConflictSummaryShowsOnlyNonZeroPoints() {
        val r = { s: Int, d: Int, only: Boolean -> WishTrialRow(s, d, "n$s", "理由", locked = true, wishOnly = only) }
        val c = WishTrialCandidates(listOf(r(0, 1, true), r(1, 2, false)), listOf(ShortfallWishGroup(3, 0, "4日 日勤 1人不足", listOf(r(2, 3, false), r(2, 3, false), r(4, 3, false)))))
        assertEquals(listOf("必須違反に関わる希望 2件（うち計算では消せない 1件）", "人員不足の日に別の勤務の希望 2人"), wishConflictSummary(c))
        assertEquals(listOf("必須違反に関わる希望 1件"), wishConflictSummary(WishTrialCandidates(listOf(r(1, 2, false)), emptyList())))
        assertEquals(emptyList<String>(), wishConflictSummary(WishTrialCandidates(emptyList(), emptyList())))
    }

    @Test fun editReturnBandPutsProblemFirstInTwoLines() {
        val r = EditReturn("なおし方", EditReturn.GUIDED, problem = "11/24(火) の A4 が 1人 足りません")
        assertEquals(listOf("11/24(火) の A4 が 1人 足りません", "直したら「なおし方」へ戻れます。"), editReturnLines(r))
        assertEquals(listOf(editReturnLine(r.copy(problem = null))), editReturnLines(r.copy(problem = null)))
        assertNull(editReturnProblem(emptyList(), 0, 3))
        assertNull(editReturnProblem(emptyList(), null, null))
    }
}
