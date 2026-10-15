package com.magi.app.ui

import com.magi.app.model.C41Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import com.magi.app.v6.CoverageVerdict
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.Problem
import com.magi.app.v6.SettingFixAction
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.V6PortAnalyzer
import com.magi.app.v6.V6SanityPort
import com.magi.app.v6.ViolationKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.687.0 のコードレビュー対応（R01〜R05・R08・R09・R11・R12）。 */
class Review3687Test {
    private fun st1(groupShift: List<List<Int>>, schedule: List<List<Int>>, staff: List<Staff>, wishes: Map<String, Int> = emptyMap(),
                    shifts: List<Shift>, groups: List<Group>, cons41: List<C41Row> = emptyList(), days: Int = 1) = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-%02d".format(days), shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
        groupShift = groupShift, groupShiftApt = groupShift.map { r -> r.map { "" } }, schedule = schedule, wishes = wishes,
        staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = cons41, cons42 = emptyList(),
    )

    // R08: 不足枠の候補は正式な前後の検査で採点してから確認の画面へ（0・空の差分を出さない）。
    @Test fun shortageCandidateIsEvaluatedBeforePreview() {
        val st = FixTiersTest.board12x16(); val s = FixTiersTest.sched(st)
        val op = FixCell(1, 1, FixTiersTest.REST)
        val sug = V6PortAnalyzer.evaluatedSuggestion(st, s, FixKind.CHANGE, listOf(op), "x")
        val before = UnifiedViolationChecker.check(st, s)
        val after = UnifiedViolationChecker.check(st, FixTiersTest.sched(st).also { it[1][1] = FixTiersTest.REST })
        assertEquals(after.hard - before.hard, sug.deltaHard)
        assertEquals(after.total - before.total, sug.deltaTotal)
        assertTrue("過剰が減る差分を持つ", sug.diff.isNotEmpty() && sug.deltaTotal != 0)
    }

    // R02: 希望の取消は診断の入力の指紋と職員 ID で照合する。
    private fun wishState(gs: List<Int>, staff: List<Staff>, wishes: Map<String, Int>) = st1(
        listOf(gs, listOf(1, 1)), listOf(listOf(0), listOf(0)), staff, wishes,
        listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("A", "A", "", "")), listOf(Group("G", "G"), Group("H", "H")))

    @Test fun removeWishRefusesWhenInputsChangedAndFollowsStaffId() {
        val s0 = wishState(listOf(1, 0), listOf(Staff("X", 0, id = 7), Staff("Y", 1, id = 8)), mapOf("0,0" to 1))
        val issue = V6SanityPort.buildGuidance(s0).single { it.action == SettingFixAction.REMOVE_WISH }
        assertEquals(7, issue.wishStaffId)
        assertNotNull(issue.wishFingerprint)
        // 担当できるようになった＝診断が古い。取り消さない。
        val canDoNow = s0.copy(groupShift = listOf(listOf(1, 1), listOf(1, 1)))
        assertTrue(SettingFixLogic.wishDiagnosisStale(canDoNow, issue))
        assertNull(SettingFixLogic.apply(canDoNow, issue))
        // 同じ入力なら取り消せる。
        assertEquals(emptyMap<String, Int>(), SettingFixLogic.apply(s0, issue)!!.wishes)
        // 並び替えたあとも ID で同じ人を指す（並び順のキーで別の人の希望を消さない）。
        val reordered = s0.copy(staff = listOf(s0.staff[1].copy(groupIdx = 1), s0.staff[0]), wishes = mapOf("1,0" to 1, "0,0" to 1),
            schedule = listOf(listOf(0), listOf(0)))
        assertEquals("1,0", SettingFixLogic.currentWishKey(reordered, issue))
        // 職員がいなくなった＝古い。
        assertTrue(SettingFixLogic.wishDiagnosisStale(s0.copy(staff = listOf(Staff("Y", 1, id = 8)), schedule = listOf(listOf(0)), wishes = emptyMap()), issue))
    }

    // R01: 証明つき（担当できる人数 < 必要数）だけを「充足不可」と言い、限定探索の不発は「試した入れ替えでは埋まらなかった」。
    private fun cascade(cWished: Boolean) = st1(
        listOf(listOf(1, 1, 1), listOf(1, 0, 1)), listOf(listOf(2), listOf(0)), listOf(Staff("A", 0), Staff("C", 1)),
        if (cWished) mapOf("1,0" to 0) else emptyMap(),
        listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("X", "X", "1", ""), Shift("Y", "Y", "1", "")), listOf(Group("GA", "GA"), Group("GC", "GC")))

    @Test fun searchMissIsNotCalledInfeasible() {
        val d = V6PortAnalyzer.diagnoseCoverage(cascade(cWished = true))
        val sf = d.shortfalls.single { it.shiftIndex == 1 }
        assertTrue(sf.blockedNow && sf.searchMiss)
        assertEquals(1, d.searchMissSlots); assertEquals(0, d.provenBlockedSlots)
        val log = d.logLines()
        assertTrue(log.toString(), log.first().contains("1枠は 試した入れ替えでは埋まらなかった") && !log.first().contains("不能"))
        assertTrue(log.toString(), log.any { it.contains("— 試した入れ替えでは埋まらなかった:") })
        assertFalse(log.toString(), log.first().contains("この希望・担当のままでは人員不足は減りません"))
        val proven = V6PortAnalyzer.diagnoseCoverage(st1(listOf(listOf(1, 0)), listOf(listOf(0)), listOf(Staff("A", 0)),
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("X", "X", "1", "")), groups = listOf(Group("G", "G"))))
        assertEquals(CoverageVerdict.INFEASIBLE, proven.shortfalls.single().verdict)
        assertTrue(proven.logLines().any { it.contains("— 充足不可:") })
    }

    // R03: 範囲の内外は族の名前でなく鍵の場所で決める。前からある別の違反を直すだけの付随は必要な変更に数えない。
    @Test fun frameIsJudgedByKeyLocation() {
        val day = FixTiersTest.over
        assertTrue(keyInFrame(day, ViolationKey("c3n", ViolationKey.Space.CELL, 2, 1)))
        assertFalse(keyInFrame(day, ViolationKey("covU", ViolationKey.Space.NEED, 1, 3)))
        assertFalse(keyInFrame(day, ViolationKey("low", ViolationKey.Space.COUNT, 0, 1)))
        val staff = FixTarget(0, 1, null, 2, true, "下限")
        assertTrue(keyInFrame(staff, ViolationKey("c3n", ViolationKey.Space.CELL, 0, 3)))
        assertFalse(keyInFrame(staff, ViolationKey("c3n", ViolationKey.Space.CELL, 1, 3)))
        val e = FixExplanation(emptyList(), listOf(ExtraReason(listOf(FixCell(0, 0, 0)), "ついで", incidental = true)), emptyList())
        assertFalse("ついでに直るだけの付随は理由つきの埋め合わせではない", e.allExplained)
    }

    @Test fun compensatingExtraStaysExplainedAndTier2() {
        val st = FixTiersTest.board12x16(); val s = FixTiersTest.sched(st)
        val swap = FixSuggestion(FixKind.SWAP_XDAY, listOf(FixCell(0, 1, FixTiersTest.REST), FixCell(0, 3, FixTiersTest.D)), "swap", -1, -1, emptyList())
        val e = explainExtras(st, s, FixTiersTest.over, swap)
        assertTrue(e.extras.toString(), e.allExplained && e.extras.none { it.incidental })
    }

    // R04: まとめた付随の変更を先頭の職員に寄せない。
    @Test fun groupedExtrasNameEveryStaff() {
        val names = listOf("金沢", "山本", "佐藤")
        assertEquals("金沢さんの", extraStaffWhere(listOf(FixCell(0, 1, 0), FixCell(0, 2, 0)), names))
        assertEquals("山本さん・佐藤さんの合計 ", extraStaffWhere(listOf(FixCell(1, 1, 0), FixCell(2, 2, 0), FixCell(1, 3, 0)), names))
    }

    // R05: グループのレンジの負の値は空欄と同じ（制限なし）＝採点と設定の見直しの案内が一致する。
    @Test fun negativeGroupRangeBoundIsUnset() {
        fun p(l: String, u: String) = Problem(st1(listOf(listOf(1, 1)), listOf(listOf(0)), listOf(Staff("A", 0)),
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("X", "X", "", "")), groups = listOf(Group("G", "G")),
            cons41 = listOf(C41Row("G", "X", l, u)))).cons41.single()
        p("-1", "-2").let { assertEquals(0, it.l); assertEquals(Int.MAX_VALUE, it.u) }
        p("1", "-1").let { assertEquals(1, it.l); assertEquals(Int.MAX_VALUE, it.u) }
        p("", "0").let { assertEquals(0, it.l); assertEquals(0, it.u) }
        p("abc", "2").let { assertEquals(0, it.l); assertEquals(2, it.u) }
        val issues = V6SanityPort.buildGuidance(st1(listOf(listOf(1, 1)), listOf(listOf(0)), listOf(Staff("A", 0)),
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("X", "X", "", "")), groups = listOf(Group("G", "G")),
            cons41 = listOf(C41Row("G", "X", "1", "-1"))))
        assertTrue(issues.toString(), issues.any { "グループのレンジ" in it.where && "負の値" in it.problem && "制限なし" in it.problem })
        // 負の上限が「どの人数でも違反」にならない。
        val st = st1(listOf(listOf(1, 1)), listOf(listOf(0)), listOf(Staff("A", 0)),
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("X", "X", "", "")), groups = listOf(Group("G", "G")),
            cons41 = listOf(C41Row("G", "X", "", "-1")))
        assertEquals(0, UnifiedViolationChecker.check(st, arrayOf(intArrayOf(0))).breakdown["c41"] ?: 0)
    }

    // R09: 確認の画面の門の説明は「必須を増やす」変更を断る（要調整だけの改善は通る）。
    @Test fun gateNoteMatchesTheRealGate() {
        assertTrue(PREVIEW_GATE_NOTE.contains("必須違反を増やす"))
        assertFalse(PREVIEW_GATE_NOTE.contains("減らない"))
    }

    // R11: 色の札の件数は族の単位。
    @Test fun colorChipUsesFamilyUnit() {
        assertEquals("人", unitOf("covU")); assertEquals("回", unitOf("low")); assertEquals("pt", unitOf("fair")); assertEquals("件", unitOf("c3n"))
    }

    // R12: 同名の職員を ID で別の人として束ね、グループ記号を添える。
    @Test fun previewRowsGroupByStaffId() {
        val sug = FixSuggestion(FixKind.CHANGE_MULTI, listOf(FixCell(0, 0, 1), FixCell(1, 0, 1)), "x", 0, 0, emptyList())
        val p = chainFixPreview(sug, arrayOf(intArrayOf(0), intArrayOf(0)), listOf("佐藤", "佐藤"), listOf("休", "A"), "2026-08-01",
            staffIds = listOf(11, 12), staffGroups = listOf("G", "H"))
        assertEquals(listOf(11, 12), p.rows.map { it.staffKey })
        assertEquals(2, p.rows.groupBy { it.staffKey }.size)
        assertEquals(listOf("佐藤（G）", "佐藤（H）"), p.rows.map { it.staff })
        val single = chainFixPreview(sug, arrayOf(intArrayOf(0), intArrayOf(0)), listOf("佐藤", "鈴木"), listOf("休", "A"), "2026-08-01")
        assertEquals(listOf("佐藤", "鈴木"), single.rows.map { it.staff })
    }
}
