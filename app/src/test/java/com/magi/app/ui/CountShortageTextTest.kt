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
        assertEquals("10/3のA不足を解消する", fixTargetPurpose(t, null, "10/3のA"))
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
        // [3.678.0] カードの先頭に必須の変化を出したら繰り返さない。
        assertEquals(listOf("期間の制約：1件増える", "曜日の偏り：2pt増える"), otherEffectLines(s, setOf("low", "high", "apt"), hardShown = true))
        assertEquals("この2か所の変更を適用する", applyCountButton(2))
        assertEquals(APPLY_CHANGE_BUTTON, applyCountButton(1))
    }

    @Test fun countChipRowTextNamesTargetAndDifference() {
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
        assertEquals("今回探した範囲では、条件を守って 2 回増やせる案が見つかりませんでした。", why.lines[0])
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
        assertEquals("必須違反 0・配布前の確認", readyBadgeLabel(0))
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
        val a = AppliedFix("k", "x", ops, schedule, after, t, 3, 2, 10, 11)
        val need = { k: Int, _: Int -> if (k == 1) 2 to 2 else null }
        val lines = appliedLines(u, a, t, "10/3のA", need)
        assertEquals("今回の問題：解消（10/3のA：1人 → 2人、必要 2人を満たします）", lines[0])
        assertEquals("Aの不足（期間全体）：5人 → 4人", lines[1])
        assertEquals("必須 3件→2件", lines[2])
        assertEquals("10/3の休：1人 → 0人", lines[3])
        assertEquals("保存：保存中", lines[4])
        assertEquals("次：10/4のAは、2人不足のままです", lines[5])
        assertTrue(lines.last().startsWith("勤務表が変わったため"))
        // 保存済みなら保存の行は出さない。続けて同じ勤務の案へ進むボタン。
        assertTrue(appliedLines(u.copy(saveState = SaveState.Saved), a, t, "10/3のA", need).none { it.startsWith("保存：") })
        assertEquals("続けて A の案を見る", appliedNextButton(true, "A", 2, 2))
        assertEquals("必須違反2か所を順に見る", appliedNextButton(false, "A", 2, 2))
        assertNull(appliedNextButton(false, "A", 0, 2))
        assertEquals("新たに生じた問題：禁止の並び +1件", appearedFamiliesLine(mapOf("c3n" to 0), mapOf("c3n" to 1, "c1" to 0)))
    }

    /** 日別の詳細で職員に絞っても、その日のその勤務の問題のまま探す（日付を落とさない）。 */
    @Test fun personFocusOnADayKeepsTheNeedTarget() {
        val u = UiState(schedule = schedule, needFamilies = mapOf("1,2" to listOf("vio-covU")))
        val cv = ConditionsView(needLimits = List(3) { k -> List(4) { if (k == 1) 2 to 2 else null } })
        assertEquals(FixTarget(null, 1, 2, 2, true, "必要人数"), panelTarget(u, cv, FixFocus(0, 1, 2)))
        assertEquals(FixTarget(null, 1, 2, 2, true, "必要人数"), panelTarget(u, cv, FixFocus(null, 1, 2)))
        assertNull(panelTarget(u, cv, FixFocus(0, null, 2)))   // セルのシート（勤務なし）は対象なし
        assertNull(panelTarget(u, cv, FixFocus(null, 1, 2, exceptStaff = 0)))
    }

    /** 自動と担当者の分担（2026-10-14 利用者レビュー）: 作成前に自動で行うこと・保持するもの、作成後は「完了＝配布可」と言わない。 */
    @Test fun autoAndOperatorSplitTexts() {
        assertEquals("10/1〜10/31・12人・組む時間 1分（＋仕上げ 20秒）。開始後は一手ごとの確認はしません", preRunPlanLine("2026-10-01", 31, 12, 60, 20))
        assertEquals("登録条件：つくる前に直す項目はありません／手動固定 3件は保持", preRunConditionLine(0, 0, 0, 3))
        assertEquals("登録条件：希望が上限を超える 2件", preRunConditionLine(0, 0, 2, 0))
        assertEquals("期間の制約 2件・曜日の偏り 12pt", softSummary(mapOf("c1" to 2, "weekly" to 12, "c3n" to 1)))
        assertEquals("なし", softSummary(mapOf("covU" to 1)))
        assertEquals("解消", appliedStatusWord(CountEffect(0, 1, 1, true, "人")))
        assertEquals("減少", appliedStatusWord(CountEffect(7, 9, 15, true)))
        assertEquals("残存", appliedStatusWord(CountEffect(7, 7, 15, true)))
        assertEquals("別案を見る", BACK_TO_FIXES_BUTTON)
    }

    /** 同じ勤務の不足の日々をまとめて見る（日ごとの不足の合計。ある日の過剰で別の日の不足を相殺しない）。 */
    @Test fun multiDayTargetSumsPerDayShortages() {
        // A の必要: 10/3＝2人（今 1人）、10/4＝1人（今 0人）。
        val fams = mapOf("1,2" to listOf("vio-covU"), "1,3" to listOf("vio-covU"))
        val need = { k: Int, j: Int -> if (k == 1) (if (j == 2) 2 to 2 else 1 to 1) else null }
        val t = multiNeedTarget(fams, 1, need)!!
        assertEquals(listOf(2, 3), t.days); assertEquals(listOf(2, 1), t.needs)
        assertEquals(2, t.gapOf(schedule))
        assertNull(multiNeedTarget(mapOf("1,2" to listOf("vio-covU")), 1, need))   // 1 日だけならまとめない
        // 10/3 だけ埋める案＝合計 2 → 1、両日を埋める案＝2 → 0。
        val one = t.effect(schedule, listOf(FixCell(0, 2, 1)))
        assertEquals(2 to 1, one.gaps); assertTrue(one.fixes)
        assertEquals("適用後も、合計 1人不足", one.afterNote(t.basis))
        val both = t.effect(schedule, listOf(FixCell(0, 2, 1), FixCell(1, 3, 1)))
        assertEquals("対象の日はすべて必要人数を満たします", both.afterNote(t.basis))
        assertEquals("不足 2人 → 0人", both.gapLine())
        // 10/4 の A を足して 10/3 の A を抜く案は、合計が減らない（相殺しない）。
        val swap = t.effect(schedule, listOf(FixCell(1, 2, 0), FixCell(1, 3, 1)))
        assertFalse(swap.fixes)
        assertEquals("A（2日）", fixTargetLabel(t, "A", "2026-10-01"))
        assertEquals("A（2日）の不足をまとめて解消する", fixTargetPurpose(t, null, "A（2日）"))
        assertEquals(listOf("2日で合計2人不足しています（日ごとの不足の合計）", "対象の日：10/3・10/4"), fixTargetHead(t, 0, schedule, "2026-10-01"))
        assertEquals("両日の不足をまとめて直す案を探す", multiFixButton(2))
        assertEquals("3日の不足をまとめて直す案を探す", multiFixButton(3))
        val u = UiState(schedule = schedule, needFamilies = fams)
        val cv = ConditionsView(needLimits = List(3) { k -> List(4) { j -> need(k, j) } })
        assertEquals(t, panelTarget(u, cv, FixFocus(null, 1, null, allDays = true)))
        assertEquals("-,1,-,all", FixFocus(null, 1, null, allDays = true).key)
    }

    @Test fun applyRequiresTargetImprovementAndShowsUnevaluated() {
        val fams = mapOf("1,2" to listOf("vio-covU"), "1,3" to listOf("vio-covU"))
        val need = { k: Int, j: Int -> if (k == 1) (if (j == 2) 2 to 2 else 1 to 1) else null }
        val t = multiNeedTarget(fams, 1, need)!!
        assertTrue(improvesTarget(t, schedule, listOf(FixCell(0, 2, 1))))
        // 相殺するだけの案は全体が良くなっても当てない（統合仕様 INV-03）。対象を持たない案は判定しない。
        assertFalse(improvesTarget(t, schedule, listOf(FixCell(1, 2, 0), FixCell(1, 3, 1))))
        assertTrue(improvesTarget(null, schedule, listOf(FixCell(1, 2, 0))))
        assertEquals("新たな必須違反：未評価（適用の直前に検査します）", newHardLine(null))
        assertEquals("新たな必須違反：なし", newHardLine(emptyList()))
        assertEquals("新たな必須違反：禁止の並び", newHardLine(listOf("禁止の並び")))
    }

    @Test fun timeoutAndSaveFailureAreSaidSeparately() {
        val t = needFixTarget(mapOf("1,2" to listOf("vio-covU")), 1, 2, 2 to 2)!!
        // 時間切れは「案が無い」と分ける（統合仕様 INV-08）。
        assertEquals("10/3のAの不足を減らす案は、制限時間内には見つかりませんでした。もう一度探すと続きを探せます。", noNeedFixLine("10/3のA", t, timedOut = true))
        assertEquals("保存：失敗（「もう一度保存する」で保存し直してください）", saveStateLine(SaveState.Failed))
    }

    @Test fun unitsAreNotSummedAcrossKinds() {
        // 件・人・回・pt は 1 つの数に足さない（統合仕様 INV-07）。
        val b = mapOf("c3n" to 2, "covU" to 1, "low" to 6, "apt" to 3, "c1" to 4, "covO" to 2, "fair" to 30)
        assertEquals("2件・1人", unitTotals(b, com.magi.app.v6.MirrorKeys.hard))
        assertEquals("4件・2人・9回・30pt", unitTotals(b, com.magi.app.v6.MirrorKeys.soft))
        assertEquals("4件・2人・9回", unitTotals(b, com.magi.app.v6.MirrorKeys.soft, includePt = false))
        assertEquals("必須違反 2件・1人・要調整 4件・2人・9回・30pt", reportCountsText(b))
        assertEquals("下限割れ −6回・禁止の並び +1件", familyDeltaList(listOf("low" to -6, "c3n" to 1)))
        assertEquals("要調整 4件・2人・9回・30pt・配布前の確認", readyBadgeLabel(40, b))
        assertEquals("評価：必須違反 2件・1人", boardStatusLines(true, true, true, 3, 0, b).second)
    }

    @Test fun fixFocusFollowsTheStaffIdAcrossReorderAndDeletion() {
        val f = FixFocus(1, 2, 3, exceptStaff = 0)
        // 職員 ID 20（番号 1）と 10（番号 0）で開き、並べ替えのあとは新しい番号で探す（統合仕様 5.1）。
        assertEquals(FixFocus(0, 2, 3, exceptStaff = 2), resolveFixFocus(f, 20, 10, listOf(20, 30, 10)))
        assertEquals(f, resolveFixFocus(f, 20, 10, listOf(10, 20, 30)))
        assertNull(resolveFixFocus(f, 20, 10, listOf(10, 30)))   // 削除＝別の人を探さない
        assertEquals(f, resolveFixFocus(f, null, 0, listOf(5, 6)))   // ID なし＝番号のまま
    }

    @Test fun movingTheExcessToAnotherDayIsSaidAsAMove() {
        // 2 人 × 3 日。勤務 1 の適正は毎日 1 人。10/1 に 2 人＝期間全体の過剰 1。
        val board = listOf(listOf(1, 0, 0), listOf(1, 1, 0))
        val lim = { k: Int, _: Int -> if (k == 1) 1 to 1 else null }
        assertEquals(1, shiftPeriodGap(board, 1, false, lim))
        // 10/1 の 2 人目を休にし、代わりに 10/2 へ 1 人足す＝10/1 は直るが 10/2 が過剰＝移るだけ（2026-10-15 利用者「思考誘導が混乱する」）。
        val moved = boardAfter(board, listOf(FixCell(1, 0, 0), FixCell(0, 1, 1)))
        assertEquals(1, shiftPeriodGap(moved, 1, false, lim))
        assertEquals("Dﾃの過剰（期間全体）：1人 → 1人＝別の日へ移るだけです", periodGapLine("Dﾃ", false, 1, 1))
        assertEquals("Dﾃの過剰（期間全体）：1人 → 0人", periodGapLine("Dﾃ", false, 1, 0))
        assertEquals("必須違反の変化：禁止の並び −1件", hardChangeLine(listOf("c3n" to -1, "covO" to 0, "weekly" to 2)))
        assertNull(hardChangeLine(listOf("weekly" to 2)))
        val t = needFixTarget(mapOf("1,0" to listOf("vio-covO"), "1,1" to listOf("vio-covO")), 1, 0, 1 to 1)!!
        assertEquals("ほかにDﾃが多い日：10/2（1人）", otherDaysGapLine(mapOf("1,0" to listOf("vio-covO"), "1,1" to listOf("vio-covO")), moved, t, lim, listOf("休", "Dﾃ"), "2026-10-01"))
    }
}
