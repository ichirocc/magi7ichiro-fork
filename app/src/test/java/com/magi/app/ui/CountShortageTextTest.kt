package com.magi.app.ui

import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 見ている問題の変化を最上段に（2026-10-14 利用者レビュー）: 回数・人員の対象、案ごとの変化、不足の向きの説明、確認の画面、札。 */
class CountShortageTextTest {
    private val schedule = listOf(listOf(1, 0, 0, 2), listOf(0, 1, 1, 0))

    @Test fun countTargetRecountsAfterTheOps() {
        val t = countFixTarget(mapOf("0,1" to "vio-low"), 0, 1, Triple(3, null, null))!!
        assertEquals(FixTarget(0, 1, null, 3, true, "下限"), t)
        val e = t.effect(schedule, listOf(FixCell(0, 1, 1)))
        assertEquals(CountEffect(1, 2, 3, true), e)
        assertTrue(e.fixes)
        assertEquals("Aを1回増やす", e.title("A"))
        assertEquals("1回 → 2回", e.countLine())
        assertEquals("不足 2回 → 1回", e.gapLine())
        assertEquals("適用後も、下限まで1回不足", e.afterNote(t.basis))
        assertEquals(listOf("下限まで、あと2回です", "現在 1回 ／ 下限 3回"), fixTargetHead(t, 1))
        assertEquals("モニカさんのA不足を減らす", fixTargetPurpose(t, "モニカ", "A"))
        // 別の職員だけを動かす案＝対象の回数は変わらない（「別の問題を改善する案」へ畳む）。
        val other = t.effect(schedule, listOf(FixCell(1, 0, 1), FixCell(1, 1, 0)))
        assertFalse(other.fixes)
        assertEquals("Aは変わりません", other.title("A"))
        assertEquals("Aの回数は増えません", otherFixNote("A", t))
        assertEquals("Aを増やす案", thisFixHeading("A", t))
        // 超過の向きと、届いたときの言い方。
        val over = countFixTarget(mapOf("1,1" to "vio-high"), 1, 1, Triple(null, 1, null))!!
        val oe = over.effect(schedule, listOf(FixCell(1, 2, 0)))
        assertEquals("超過 1回 → 0回", oe.gapLine())
        assertEquals("適用後は上限以内になります", oe.afterNote(over.basis))
        assertEquals(listOf("上限を1回超えています", "現在 2回 ／ 上限 1回"), fixTargetHead(over, 2))
        assertNull(countFixTarget(mapOf("0,1" to "vio-c2"), 0, 1, Triple(3, 5, 4)))
    }

    /** 11/3 の不足に別の日の案を「直し方」として出さない: 人員の対象はその日のそのシフトの人数で数える。 */
    @Test fun needTargetCountsOnlyThatDay() {
        val t = needFixTarget(mapOf("1,2" to listOf("vio-covU")), 1, 2, 2 to 3)!!
        assertEquals(FixTarget(null, 1, 2, 2, true, "必要人数"), t)
        assertEquals(1, t.countOf(schedule))
        assertTrue(t.effect(schedule, listOf(FixCell(0, 2, 1))).fixes)
        assertFalse(t.effect(schedule, listOf(FixCell(0, 1, 1))).fixes)   // 別の日の変更はこの日の人数を変えない
        assertEquals("10/3のA", fixTargetLabel(t, "A", "2026-10-01"))
        assertEquals("10/3のAを1人増やす", t.effect(schedule, listOf(FixCell(0, 2, 1))).title("10/3のA"))
        assertEquals(listOf("必要人数より1人少ない状態です", "配置 1人 ／ 必要 2人"), fixTargetHead(t, 1))
        assertEquals("10/3のAを必要人数に近づける", fixTargetPurpose(t, null, "10/3のA"))
        val o = needFixTarget(mapOf("1,2" to listOf("vio-covO")), 1, 2, 0 to 0)!!
        assertEquals(listOf("適正人数より1人多い状態です", "配置 1人 ／ 適正 0人"), fixTargetHead(o, 1))
        assertEquals("10/3のAの不足を減らす案は、今回の検索では見つかりませんでした。担当できる職員と、その日の希望・固定勤務をご確認ください。", noNeedFixLine("10/3のA", t))
        // 見ている問題を変える案を先に（同順位は元の順）。
        val far = FixSuggestion(FixKind.CHANGE, listOf(FixCell(0, 1, 1)), "far", -2, -2, emptyList())
        val near = FixSuggestion(FixKind.CHANGE, listOf(FixCell(0, 2, 1)), "near", -1, -1, emptyList())
        assertEquals(listOf(near, far), rankForTarget(listOf(far, near), schedule, t))
        assertEquals(listOf(far, near), rankForTarget(listOf(far, near), schedule, null))
    }

    @Test fun otherEffectsLeadWithHardAndSkipTheTargetFamily() {
        val s = FixSuggestion(FixKind.CHANGE, listOf(FixCell(0, 1, 1)), "x", -1, 2, listOf("low" to -1, "c1" to 1, "weekly" to 2))
        assertEquals(listOf("必須違反：1件減る", "期間の制約：1件増える", "曜日の偏り：2pt増える"), otherEffectLines(s, setOf("low", "high", "apt")))
        assertEquals("この2件の変更を適用", applyCountButton(2))
        assertEquals(APPLY_CHANGE_BUTTON, applyCountButton(1))
    }

    @Test fun countGapLineNamesCurrentTargetAndDifference() {
        assertEquals("現在 4回／目標 5回 ・ 不足 1回", countGapLine("vio-aptLow", 4, Triple(null, null, 5)))
        assertEquals("現在 7回／下限 15回 ・ 不足 8回", countGapLine("vio-low", 7, Triple(15, null, null)))
        assertEquals("Dテ　3回／目標 5回　あと2回", CountChip("Dテ", 3, 5, "", true, 1).rowText)
        assertEquals("B4　5回／上限 1回　4回多い", CountChip("B4", 5, 1, "上限", false, 2).rowText)
    }

    /** 不足は「増やせない理由」を追加候補日で言う（旧: 「抜けると人員不足」＝減らす向きの理由で、向きが逆だった）。 */
    @Test fun shortageReasonsListCandidateDaysNotRemovalRisks() {
        val u = UiState(
            startDate = "2026-10-01", staff = 2, days = 4, shifts = 3, shiftSymbols = listOf("休", "A", "B"),
            staffNames = listOf("モニカ", "ノア"), schedule = schedule,
            wishes = mapOf("0,1" to 0, "0,0" to 1), countViolations = mapOf("0,1" to "vio-low"),
        )
        val limits = { i: Int, k: Int -> if (i == 0 && k == 1) Triple(3, null, null) else Triple(null, null, null) }
        val need = { k: Int, _: Int -> if (k == 2) 1 to 1 else if (k == 1) 1 to 2 else null }
        assertEquals(CountShortage(1, 3, "下限"), countShortage(u, FixFocus(0, 1), limits))
        val why = noFixReasons(u, FixFocus(0, 1), limits, need)
        assertEquals("今回の探索では、条件を守って 2 回増やせる案が見つかりませんでした。", why.lines[0])
        assertEquals("次に確認すること：「A」を追加する候補日と、変更を妨げる条件を確認します。", why.lines[1])
        assertTrue(why.lines.any { it == "補足：「A」の 1 回のうち 1 回は本人の希望で固定されています。" })
        assertTrue(why.lines.none { "抜けると" in it })
        assertEquals(NO_FIX_SCOPE, why.lines.last())
        assertEquals(listOf(1, 2, 3), why.candidates.map { it.day })
        val (wishDay, openDay, tightDay) = why.candidates
        assertEquals(AddCandidate.Go.WISH, wishDay.go)
        assertEquals("本人の希望（休）で固定されています", wishDay.reason)
        assertEquals("モニカさんに、この日の希望を調整できるかご相談ください", wishDay.check)
        assertNull(openDay.reason)
        assertEquals(AddCandidate.Go.NONE, openDay.go)
        assertEquals("この日の「B」が必要人数ぎりぎりです（必要 1人）", tightDay.reason)
        assertEquals(AddCandidate.Go.NEED, tightDay.go)
        assertEquals("10/4(日)　B → A", addCandidateHead(tightDay, u, 1))
        assertTrue(why.wishRelated)
        assertEquals("制限時間内に、条件を守って 2 回増やせる案は見つかりませんでした。もう一度探すと続きを探せます。",
            noFixReasons(u.copy(fixTimedOut = true), FixFocus(0, 1), limits, need).lines[0])
        assertNull(countShortage(u, FixFocus(0, 1, 2), limits))
        assertNull(countShortage(u.copy(countViolations = mapOf("0,1" to "vio-high")), FixFocus(0, 1), limits))
    }

    @Test fun dayPickNamesEachShiftWithItsGap() {
        val u = UiState(shiftSymbols = listOf("休", "A", "B"), schedule = schedule)
        assertEquals("どの勤務の不足を確認しますか？", dayPickHeading(listOf(CoverageMark(1, true), CoverageMark(2, true))))
        assertEquals("どの勤務の過不足を確認しますか？", dayPickHeading(listOf(CoverageMark(1, true), CoverageMark(2, false))))
        assertEquals("A　配置 0人／必要 1人　1人不足", dayPickRow(u, 3, CoverageMark(1, true), 1 to 1))
        assertEquals("B　配置 1人／適正 0人　1人多い", dayPickRow(u, 3, CoverageMark(2, false), 0 to 0))
    }

    @Test fun readyBadgeDoesNotSayReadyWhileAdjustmentsRemain() {
        assertEquals("配布可", readyBadgeLabel(0))
        assertEquals("要調整 3件・配布前の確認", readyBadgeLabel(3))
        assertFalse("元に戻せます" in APPLY_CHANGE_BUTTON)
        assertEquals("対象期間：10/1〜10/31", periodLine("2026-10-01", 31))
    }

    /** 「どの日の不足を直す案か」と「別の日への影響」を分ける（2026-10-14 利用者レビュー 2 回目）。 */
    @Test fun otherDayImpactIsSeparatedFromTheTargetDay() {
        // 職員0: 10/1=A 10/2=休 10/3=休 10/4=B、職員1: 10/1=休 10/2=A 10/3=A 10/4=休。
        val t = needFixTarget(mapOf("1,2" to listOf("vio-covU")), 1, 2, 2 to 2)!!
        val ops = listOf(FixCell(0, 2, 1), FixCell(0, 3, 0))   // 10/3 休→A、10/4 B→休
        val need = { k: Int, j: Int -> when (k to j) { 1 to 2 -> 2 to 2; 2 to 3 -> 1 to 1; else -> null } }
        val (same, other) = headcountImpact(schedule, ops, listOf("休", "A", "B"), "2026-10-01", need, 1 to 2)
        assertEquals(listOf("10/3の休：1人 → 0人"), same)
        assertEquals(listOf("10/4の休：1人 → 2人", "10/4のB：1人 → 0人（必要 1人を下回ります）"), other)
        assertEquals("必要 2人を満たします", t.effect(schedule, ops).afterNote(t.basis))
        assertEquals(listOf("モニカさんの「休」の回数は変わりません（2回のまま）", "モニカさんの「A」：1回 → 2回", "モニカさんの「B」：1回 → 0回"),
            staffCountNotes(schedule, ops, listOf("モニカ", "ノア"), listOf("休", "A", "B")))
        assertTrue(staffCountNotes(schedule, ops.take(1), listOf("モニカ"), listOf("休", "A")).isEmpty())
        // 同じ勤務のほかの日に残る不足（案を当てた後の盤面で数える）。
        val fams = mapOf("1,2" to listOf("vio-covU"), "1,3" to listOf("vio-covU"))
        val needA = { k: Int, j: Int -> if (k == 1) 2 to 2 else null }
        assertEquals(listOf("10/4のAは、2人不足のままです"), remainingSameShift(fams, boardAfter(schedule, ops), t, needA, listOf("休", "A", "B"), "2026-10-01"))
    }

    @Test fun shiftDayPickAndRestRowsSayTheSettingFirst() {
        val u = UiState(startDate = "2026-10-01", shiftSymbols = listOf("休", "A", "B"), schedule = schedule)
        assertEquals("どの日を調整しますか？", shiftDayPickHeading())
        assertEquals("10/4(日)　配置 0人／必要 1人　1人不足", shiftDayPickRow(u, 1, 3, true, 1 to 1))
        assertEquals("10/2(金)　休み 1人／適正 0人", restOverRow(u, 0, 1, 0 to 0))
        assertEquals("10/2(金)　休み 1人／適正 未設定", restOverRow(u, 0, 1, null))
        assertEquals("各日の適正人数が0人と表示されています。意図した設定か、確認できます。", restOverIntro(true).first())
        assertEquals("保存：完了", saveStateLine(SaveState.Saved))
    }

    @Test fun previewRowsCarryTheDayHeadcountAndCountNotes() {
        val s = FixSuggestion(FixKind.SWAP_XDAY, listOf(FixCell(0, 1, 1), FixCell(0, 0, 0)), "x", 0, 0, emptyList())
        val p = chainFixPreview(s, arrayOf(intArrayOf(1, 0, 0, 2), intArrayOf(0, 1, 1, 0)), listOf("福澤"), listOf("休", "A", "B"), "2026-10-01")
        assertEquals("休 1人 → 0人、A 1人 → 2人", p.rows[0].impact)   // 10/2 休→A
        assertEquals("A 1人 → 0人、休 1人 → 2人", p.rows[1].impact)   // 10/1 A→休
        assertEquals(listOf("福澤さんの「休」の回数は変わりません（2回のまま）", "福澤さんの「A」の回数は変わりません（1回のまま）"), p.countNotes)
    }

    @Test fun appliedLinesReportTheTargetSaveAndWhatRemains() {
        val t = needFixTarget(mapOf("1,2" to listOf("vio-covU")), 1, 2, 2 to 2)!!
        val ops = listOf(FixCell(0, 2, 1))
        val after = boardAfter(schedule, ops)
        val u = UiState(startDate = "2026-10-01", shiftSymbols = listOf("休", "A", "B"), schedule = after, saveState = SaveState.Saving,
            needFamilies = mapOf("1,3" to listOf("vio-covU")))
        val a = AppliedFix("k", "x", ops, schedule, after, t)
        val need = { k: Int, _: Int -> if (k == 1) 2 to 2 else null }
        val lines = appliedLines(u, a, t, "10/3のA", need)
        assertEquals("10/3のA：1人 → 2人（必要 2人を満たします）", lines[0])
        assertEquals("10/3の休：1人 → 0人", lines[1])
        assertEquals("保存：保存中", lines[2])
        assertEquals("次に確認すること：10/4のAは、2人不足のままです。", lines[3])
        assertTrue(lines.last().startsWith("勤務表が変わったため"))
    }
}
