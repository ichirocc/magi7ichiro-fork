package com.magi.app.ui

import com.magi.app.v6.CoverageShortfall
import com.magi.app.v6.CoverageVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.698.0: ホームの主カードを「見出し → 状態 2 行 → 主ボタン 1 つ」に（並びは純関数、対象は INV-01、単位は INV-07）。 */
class Review3698Test {
    private fun ui(running: Boolean, hasResult: Boolean, hard0: Boolean, engineRan: Boolean = hasResult) =
        UiState(loaded = true, running = running, hasResult = hasResult, bestHard = if (hard0) 0L else 3L, engineRan = engineRan, checkRev = if (hasResult) 1L else 0L)

    /** Review3690Test の表を使い回す: 段ごとに 見出し → 状態 → 主ボタン の順で、塗りの主は 1 つ、でき具合は結果があるときだけ。 */
    @Test fun homeCardSectionsOrderPerStage() {
        for (running in listOf(false, true)) for (hasResult in listOf(false, true)) for (hard0 in listOf(false, true)) {
            val plan = homePlan(running, hasResult, hard0)
            val s = homeCardSections(plan, ui(running, hasResult, hard0))
            val case = "running=$running hasResult=$hasResult hard0=$hard0 kind=${plan.kind}"
            assertEquals(case, s, s.distinct())
            assertEquals(case, if (running) 0 else 1, s.count { it == HomeSection.PRIMARY })
            if (running) { assertEquals(case, listOf(HomeSection.PROGRESS), s); continue }
            assertTrue(case, s.indexOf(HomeSection.HEADLINE) < s.indexOf(HomeSection.STATUS))
            assertTrue(case, s.indexOf(HomeSection.STATUS) < s.indexOf(HomeSection.PRIMARY))
            assertTrue(case, s.indexOf(HomeSection.PRIMARY) < s.indexOf(HomeSection.HELPER))
            assertTrue(case, s.indexOf(HomeSection.HELPER) < s.indexOf(HomeSection.DETAIL))
            assertEquals(case, hasResult, HomeSection.SATISFACTION in s)
            assertEquals(case, hasResult, HomeSection.CREATION in s)
            assertEquals(case, hasResult, HomeSection.BADGES in s)
            if (hasResult) assertTrue(case, s.indexOf(HomeSection.DETAIL) < s.indexOf(HomeSection.SATISFACTION) && s.indexOf(HomeSection.SATISFACTION) < s.indexOf(HomeSection.INFO))
        }
        // 結果はあるが自動作成を経ていない（手で編集）＝作成の行は出ない。
        assertFalse(HomeSection.CREATION in homeCardSections(homePlan(false, true, false), ui(false, true, false, engineRan = false)))
    }

    @Test fun othersLinkHiddenWhenPrimaryGoesToAnalysis() {
        val u = ui(false, true, false)
        assertFalse(HomeSection.OTHERS in homeCardSections(homePlan(false, true, false), u))                       // LIST
        assertFalse(HomeSection.OTHERS in homeCardSections(homePlan(false, true, false, hasMove = true), u))       // APPLY_MOVE
        assertTrue(HomeSection.OTHERS in homeCardSections(homePlan(false, true, false, guided = true), u))         // GUIDED_FIX
        assertTrue(HomeSection.OTHERS in homeCardSections(homePlan(false, true, false, wishes = true), u))         // WISHES
        assertFalse(HomeSection.PRIMARY in homeCardSections(homePlan(false, true, false, fixSearching = true), u)) // SEARCHING は主ボタン無し
    }

    @Test fun statusLinesAreAtMostTwo() {
        assertEquals(listOf("10/15(水) の Cｵ が 1人 足りません", "未確認事項 2 件"), homeStatusLines(HomePlanKind.GUIDED_FIX, "10/15(水) の Cｵ が 1人 足りません", "未確認事項 2 件", "この内容で勤務表CSVを保存済みです（10:00）。"))
        assertEquals(listOf("未確認事項 2 件", "09:00 に保存した勤務表CSVは、今の内容と違います（配るなら保存し直してください）。"),
            homeStatusLines(HomePlanKind.PRE_DIST, "", "未確認事項 2 件", "09:00 に保存した勤務表CSVは、今の内容と違います（配るなら保存し直してください）。"))
        assertEquals(emptyList<String>(), homeStatusLines(HomePlanKind.PRE_DIST, "", null, "この内容で勤務表CSVを保存済みです（10:00）。"))
        assertEquals(emptyList<String>(), homeStatusLines(HomePlanKind.MAKE, "", null, null))
        for (k in HomePlanKind.values()) assertTrue(homeStatusLines(k, "文", "相談", "違います").size <= 2)
    }

    @Test fun headlinePerStage() {
        assertEquals("今月の判断", homeHeadline(HomePlanKind.LIST))
        assertEquals("今月の判断", homeHeadline(HomePlanKind.GUIDED_FIX))
        assertEquals("今月の判断", homeHeadline(HomePlanKind.WISHES))
        assertEquals(HOME_MAKE_HEADLINE, homeHeadline(HomePlanKind.MAKE))
        assertEquals(HOME_MONTH_HEADLINE, homeHeadline(HomePlanKind.PRE_DIST))
        assertFalse(HOME_MAKE_HEADLINE.contains("②")); assertFalse(HOME_HARD0_HEADLINE.contains("③"))
    }

    private val shortfall = CoverageShortfall(14, "10/15(水)", 2, "Cｵ", need = 2, got = 1, miss = 1, capacity = 5, verdict = CoverageVerdict.FIXABLE, reason = "")

    @Test fun shortageHeadlineNamesShiftAndCount() {
        val h = shortageHeadline(shortfall)
        assertEquals("10/15のCｵが1人不足", h.headline)
        assertEquals("配置 1 / 必要 2", h.status)
        assertEquals("担当・希望の確認", h.badge)
    }

    /** 「次に確認する問題」は主ボタンの対象と同じ（INV-01）。見出しと同じ対象なら完了の 1 行だけ。 */
    @Test fun creationNextTargetFollowsStage() {
        fun t(k: HomePlanKind) = creationNextTarget(k, shortage = "10/15(水) Cｵ", move = "山田 10/3", wish = "佐藤 10/8", relax = "鈴木 10/8〜10/9", tour = "福澤さん 禁止の並び")
        assertEquals("10/15(水) Cｵ", t(HomePlanKind.GUIDED_FIX))
        assertEquals("山田 10/3", t(HomePlanKind.APPLY_MOVE))
        for (k in listOf(HomePlanKind.WISHES, HomePlanKind.WISH_FLOOR, HomePlanKind.WISH_INFEASIBLE)) assertEquals("佐藤 10/8", t(k))
        assertEquals("鈴木 10/8〜10/9", t(HomePlanKind.RELAX))
        assertEquals("福澤さん 禁止の並び", t(HomePlanKind.LIST))
        assertEquals("福澤さん 禁止の並び", t(HomePlanKind.SEARCHING))
        for (k in listOf(HomePlanKind.MAKE, HomePlanKind.PRE_DIST, HomePlanKind.RUNNING, HomePlanKind.DATA_INFEASIBLE)) assertNull(t(k))
        assertEquals("直し方を見る", homeTargetLabel("直し方を見る", t(HomePlanKind.GUIDED_FIX), null))
        assertEquals(listOf(CREATION_DONE_TITLE), creationTopLines("10/15(水) Cｵ", headlineTarget = "10/15(水) Cｵ"))
        assertEquals(listOf(CREATION_DONE_TITLE, "次に確認する問題：X"), creationTopLines("X", headlineTarget = "Y"))
    }

    @Test fun emptyStateWords() {
        assertEquals("読み込んでいます…", emptyStateSubtitle(true))
        assertEquals("保存データ・サンプル・新規から選べます", emptyStateSubtitle(false))
        assertEquals("変更内容を確認する", REVIEW_CHANGE_BUTTON)
        assertEquals("必須を見る", HOME_LIST_BUTTON)
    }
}
