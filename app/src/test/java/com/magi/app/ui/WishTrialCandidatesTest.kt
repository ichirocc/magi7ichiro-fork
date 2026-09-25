package com.magi.app.ui

import com.magi.app.v6.CoverageDiagnosis
import com.magi.app.v6.CoverageShortfall
import com.magi.app.v6.CoverageVerdict
import com.magi.app.v6.WishSelfConflict
import com.magi.app.v6.WishTrial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [S5] 試算の候補（§2.2・§2.3）と結果の文（§5）。`docs/s5_wish_trial.md` §12 T9・T10。 */
class WishTrialCandidatesTest {
    private fun shortfall(day: Int, shift: Int, pinned: List<Int>) = CoverageShortfall(
        day, "${day + 1}日", shift, "日", need = 2, got = 1, miss = 1, capacity = 1,
        verdict = CoverageVerdict.INFEASIBLE, reason = "", wishPinned = pinned,
    )

    @Test fun t9_dedupeRepresentativeReasonC3wYUnlockedAndShortfallRows() {
        val ui = UiState(
            staffNames = listOf("山田", "佐藤", "鈴木"),
            shiftSymbols = listOf("休", "日", "夜"),
            wishes = mapOf("0,2" to 1, "1,3" to 2, "1,4" to 1, "2,5" to 1, "0,6" to 0, "2,6" to 2),
            lockedWishKeys = setOf("0,2", "1,3", "1,4", "0,6", "2,6"),
            violationCellFamilies = mapOf(
                "0,2" to listOf("vio-pref", "vio-c3n"),   // 同じ (職員, 日) ＝pref を代表、c3n は「ほか」
                "1,3" to listOf("vio-c3w"),               // 翌日 (1,4) の X と、前日自身の希望 Y (1,3)
                "2,5" to listOf("vio-c3n"),               // 担当できない勤務の希望＝locked=false
            ),
            coverageDiag = CoverageDiagnosis(2, 2, 0, listOf(shortfall(6, 1, listOf(2, 0)), shortfall(2, 1, listOf(0))), emptyList()),
        )
        val c = wishTrialCandidates(ui)
        assertEquals(listOf(
            WishTrialRow(0, 2, "山田", "希望の勤務になっていません（ほか: 禁止の並び・人手不足の日）", true),
            WishTrialRow(1, 3, "佐藤", "翌日（5日）の希望の勤務の前日に置けない勤務の希望です", true),
            WishTrialRow(1, 4, "佐藤", "前日（4日）に置けない勤務が入っています", true),
            WishTrialRow(2, 5, "鈴木", "希望が禁止の並びに掛かっています", false),
        ), c.direct)
        // (0,2) は S5a が代表＝S5b の 3日 の枠は空になって消える。枠は日順、行は職員順。
        assertEquals(1, c.shortfall.size)
        val g = c.shortfall.single()
        assertEquals("7日 日 1人不足", g.header)
        assertEquals(listOf(WishTrialRow(0, 6, "山田", "休の希望", true), WishTrialRow(2, 6, "鈴木", "夜の希望", true)), g.rows)
    }

    @Test fun t9_c3wWithoutLockedYListsOnlyX() {
        val ui = UiState(staffNames = listOf("山田"), violationCellFamilies = mapOf("0,1" to listOf("vio-c3w")), lockedWishKeys = setOf("0,2"))
        assertEquals(listOf(2), wishTrialCandidates(ui).direct.map { it.day })
    }

    // 実データ（古泉 10/25-27 の休の希望と「休→休→休」禁止、福澤 10/1 Dﾃ・10/2 休と前日の禁止）の形。
    private val selfUi = UiState(
        staffNames = listOf("古泉", "福澤"),
        shiftSymbols = listOf("休", "日", "Dﾃ"),
        wishes = mapOf("0,24" to 0, "0,25" to 0, "0,26" to 0, "1,0" to 2, "1,1" to 0),
        lockedWishKeys = setOf("0,24", "0,25", "0,26", "1,0", "1,1"),
        wishSelfConflicts = listOf(
            WishSelfConflict(0, "c3n", listOf(24, 25, 26), listOf(0, 0, 0)),
            WishSelfConflict(1, "c3w", listOf(0, 1), listOf(2, 0)),
        ),
    )

    @Test fun t9_prefCellInWishSelfConflictListsItsSiblingWishes() {
        val ui = selfUi.copy(violationCellFamilies = mapOf("0,24" to listOf("vio-pref"), "1,1" to listOf("vio-pref")))
        assertEquals(listOf(
            WishTrialRow(0, 24, "古泉", "希望の勤務になっていません", true),
            WishTrialRow(0, 25, "古泉", "希望どうしが禁止の並び「休→休→休」を作っています", true),
            WishTrialRow(0, 26, "古泉", "希望どうしが禁止の並び「休→休→休」を作っています", true),
            WishTrialRow(1, 0, "福澤", "希望どうしが前日の禁止「Dﾃ→休」に当たっています", true),
            WishTrialRow(1, 1, "福澤", "希望の勤務になっていません", true),
        ), wishTrialCandidates(ui).direct)
    }

    @Test fun t9_selfConflictWithoutPrefCellAddsNothing() {
        // 並びが成立している（c3n の印が窓の全セル）＝行は今までどおり。pref の無い組は兄弟を足さない。
        val ui = selfUi.copy(violationCellFamilies = mapOf("0,24" to listOf("vio-c3n"), "0,25" to listOf("vio-c3n"), "0,26" to listOf("vio-c3n")))
        assertEquals(listOf(24, 25, 26), wishTrialCandidates(ui).direct.map { it.day })
        assertEquals(setOf("希望が禁止の並びに掛かっています"), wishTrialCandidates(ui).direct.map { it.reason }.toSet())
        assertEquals(emptyList<WishTrialRow>(), wishTrialCandidates(selfUi).direct)
    }

    @Test fun t9_siblingRowsDedupeAcrossOverlappingWindowsAndWithShortfall() {
        // 休の希望 4 連日＝窓 [1,2,3] と [2,3,4]。2日が崩れると兄弟は 1・3・4日 を 1 行ずつ。4日 は人手不足の枠にも出る＝S5a が代表。
        val ui = UiState(
            staffNames = listOf("大島"), shiftSymbols = listOf("休", "日"),
            wishes = (1..4).associate { "0,$it" to 0 }, lockedWishKeys = (1..4).map { "0,$it" }.toSet(),
            wishSelfConflicts = listOf(
                WishSelfConflict(0, "c3n", listOf(1, 2, 3), listOf(0, 0, 0)),
                WishSelfConflict(0, "c3n", listOf(2, 3, 4), listOf(0, 0, 0)),
            ),
            violationCellFamilies = mapOf("0,2" to listOf("vio-pref")),
            coverageDiag = CoverageDiagnosis(1, 1, 0, listOf(shortfall(4, 1, listOf(0))), emptyList()),
        )
        val c = wishTrialCandidates(ui)
        assertEquals(listOf(1, 2, 3, 4), c.direct.map { it.day })
        assertEquals("希望どうしが禁止の並び「休→休→休」を作っています（ほか: 人手不足の日）", c.direct.last().reason)
        assertEquals(emptyList<ShortfallWishGroup>(), c.shortfall)
    }

    private fun r(h0: Int, hx: Int, rk: Int, rr: Int): WishTrial.Result {
        val a = h0 - hx; val pk = minOf(h0, rk); val pc = minOf(hx, rr); val att = pk - pc
        return WishTrial.Result(h0, hx, rk, rr, a, att, minOf(a, maxOf(0, att)), maxOf(0, att - a), pk, pc)
    }

    @Test fun t10_sevenWordings() {
        assertEquals("取り消すと必須違反が確実に1件 減り、もう一度つくるとさらに2件 減る見込みです。", wishTrialText(r(5, 4, 5, 2)))
        assertEquals("取り消すと必須違反が確実に1件 減ります。", wishTrialText(r(5, 4, 5, 4)))
        assertEquals("取り消してもう一度つくると、必須違反が2件 減る見込みです。", wishTrialText(r(5, 5, 5, 3)))
        assertEquals("この試算では、減る見込みは見つかりませんでした（もう一度つくると減ることはあります）。", wishTrialText(r(5, 5, 5, 5)))
        assertEquals("もう一度つくるだけの場合より、さらに1件 減る見込みです。", wishTrialText(r(5, 5, 3, 2)))
        assertEquals("取り消さなくても、もう一度つくるだけで同じだけ減る見込みです。", wishTrialText(r(5, 4, 3, 3)))
        assertEquals("試算できませんでした（未割当のセルがあります）。", wishTrialText(WishTrial.Unavailable("未割当のセルがあります")))
        assertNull(wishTrialText(WishTrial.Stopped))
        assertEquals("希望を残したまま、もう一度つくるだけで必須違反が2件 減る見込みです。", wishTrialKeepOnlyText(WishTrial.Control(5, 3)))
        assertNull(wishTrialKeepOnlyText(WishTrial.Control(5, 5)))
    }
}
