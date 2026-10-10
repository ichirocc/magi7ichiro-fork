package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.canDo
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.canDoShiftsForStaff
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** セル編集シートの固定配置・1 行の状態・おすすめの点を実データで固定する。 */
class CellSheetLogicTest {
    private val st: MagiState =
        StateParser.parse(javaClass.getResourceAsStream("/oct2026_grid_state.json")!!.bufferedReader().readText())!!
    private val s = st.schedule.toIntArray2D()
    private val rep = UnifiedViolationChecker.check(st, s)
    private val p = cachedProblem(st)

    private fun staff(name: String) = st.staff.indexOfFirst { it.name == name }

    private fun status(i: Int, j: Int): CellStatus {
        val cur = s[i][j]
        val fams = cellStatusFamilies(rep.cellFamilies["$i,$j"].orEmpty(), rep.needFamilies["$cur,$j"].orEmpty(), rep.countFamilies["$i,$cur"].orEmpty())
        return cellStatusLine(st, p, s, i, j, fams)
    }

    @Test fun slotsKeepTheirPlaceWhateverTheStaffCanDo() {
        val shifts = (0 until 10).toList()
        val all = cellSheetSlots(shifts, shifts.toSet())
        val few = cellSheetSlots(shifts, setOf(0, 3))
        assertEquals(3, all.size)
        assertEquals(all.map { r -> r.map { it?.shift } }, few.map { r -> r.map { it?.shift } })
        assertEquals(listOf(true, false, false, true), few[0].map { it!!.canDo })
        assertEquals(listOf(8, 9, null, null), few[2].map { it?.shift })
    }

    /** 担当できるシフトが 0 件の職員は空（全部を押せる扱いにしない）。全部になるのはデータ未読込のときだけ。 */
    @Test fun staffWithNoDoableShiftGetsNoDoableButtons() {
        val by = listOf(setOf(0, 2), emptySet())
        assertEquals(setOf(0, 2), sheetCanDo(by, 0, 5))
        assertEquals(emptySet<Int>(), sheetCanDo(by, 1, 5))
        assertEquals(emptySet<Int>(), sheetCanDo(by, 7, 5))
        assertEquals((0 until 5).toSet(), sheetCanDo(emptyList(), 0, 5))
        assertTrue(cellSheetSlots(sheetShifts(5, by), sheetCanDo(by, 1, 5)).flatten().filterNotNull().none { it.canDo })
    }

    /** [S6] 試算できない盤面（未割当のセル）は「組が無い」と言わず理由を出す。失敗はやり直しを出す。 */
    @Test fun relaxUnavailableAndFailedAreNotNoWall() {
        val s2 = s.map { it.copyOf() }.toTypedArray().also { it[0][0] = -1 }
        val out = com.magi.app.v6.RelaxTrial.firstWall(st, s2)
        assertTrue(out is com.magi.app.v6.RelaxTrial.Unavailable)
        val reason = (out as com.magi.app.v6.RelaxTrial.Unavailable).reason
        assertEquals(RelaxHandoff.UNAVAILABLE, relaxHandoff(null, false, false, 0, 0, unavailable = reason))
        assertEquals("設定を緩める試算はできません（未割当のセルがあります）", relaxUnavailableText(reason))
        assertEquals(RelaxHandoff.FAILED, relaxHandoff(null, false, false, 0, 0, failed = true))
        assertEquals(RelaxHandoff.SEARCHING, relaxHandoff(null, true, false, 0, 0, failed = true))
        assertTrue(relaxOutcomeApplies(3L, 3L, "a", "a"))
        assertTrue("後から始めた試算がある", !relaxOutcomeApplies(2L, 3L, "a", "a"))
        assertTrue("データが変わった", !relaxOutcomeApplies(3L, 3L, "a", "b"))
        assertTrue(!relaxOutcomeApplies(3L, 3L, "a", null))
    }

    @Test fun shiftsNobodyCanDoAreHiddenAndLeftHandMirrors() {
        val shown = sheetShifts(6, listOf(setOf(0, 2), setOf(2, 5), emptySet()))
        assertEquals(listOf(0, 2, 5), shown)
        assertEquals((0 until 3).toList(), sheetShifts(3, listOf(emptySet())))
        val right = cellSheetSlots(shown, setOf(0))
        val left = cellSheetSlots(shown, setOf(0), leftHand = true)
        assertEquals(listOf(0, 2, 5, null), right[0].map { it?.shift })
        assertEquals(listOf(null, 5, 2, 0), left[0].map { it?.shift })
        val canDo = (0 until st.staffCount).map { i -> p.canDoShiftsForStaff(i).toSet() }
        val real = sheetShifts(st.shiftCount, canDo)
        val layout = cellSheetSlots(real, emptySet()).map { r -> r.map { it?.shift } }
        for (c in canDo) assertEquals(layout, cellSheetSlots(real, c).map { r -> r.map { it?.shift } })
    }

    @Test fun c3wLineNamesTheNextDayWish() {
        val line = status(staff("職員03"), 0)
        println(line)
        assertEquals(CellSeverity.HARD, line.severity)
        assertTrue(line.text, line.text.startsWith("⚠ 必須：翌日("))
        assertTrue(line.text, "への前日禁止" in line.text)
    }

    @Test fun c42sLineNamesThePairedStaff() {
        val i = staff("職員06")
        val line = status(i, 5)
        println(line)
        assertTrue(line.text, "スキルグループペア禁止" in line.text)
        val partners = rep.cellFamilies.filter { (k, v) -> k.endsWith(",5") && "vio-c42s" in v && k != "$i,5" }.keys
            .map { st.staff[it.substringBefore(',').toInt()].name }
        assertTrue(partners.isNotEmpty())
        assertTrue(line.text, partners.any { it in line.text })
    }

    @Test fun c3nLineShowsThePatternAndDays() {
        val line = status(staff("職員10"), 7)
        println(line)
        assertEquals(CellSeverity.HARD, line.severity)
        assertTrue(line.text, "禁止の並び" in line.text && "→" in line.text && "10/" in line.text)
    }

    @Test fun cleanCellSaysNoViolationAndGetsNoDots() {
        val clean = (0 until st.staffCount).flatMap { i -> (0 until st.dayCount).map { i to it } }.first { (i, j) ->
            val cur = s[i][j]
            rep.cellFamilies["$i,$j"].isNullOrEmpty() && rep.needFamilies["$cur,$j"].isNullOrEmpty() && rep.countFamilies["$i,$cur"].isNullOrEmpty()
        }
        assertEquals(CellStatus(CellSeverity.NONE, "違反なし"), status(clean.first, clean.second))
        assertTrue(evaluateShiftMarks(st, s, clean.first, clean.second, CellSeverity.NONE, (0 until st.shiftCount).toList()).recommended.isEmpty())
    }

    @Test fun marksRecommendOnlyHardReducersAndWarnOnNewHard() {
        val i = staff("職員03")
        val cands = (0 until st.shiftCount).toList()
        val m = evaluateShiftMarks(st, s, i, 0, CellSeverity.HARD, cands)
        val base = UnifiedViolationChecker.check(st, s)
        for (k in cands) {
            if (k == s[i][0]) continue
            val t = Array(s.size) { s[it].copyOf() }.also { it[i][0] = k }
            val r = UnifiedViolationChecker.check(st, t)
            val newHard = com.magi.app.v6.MirrorKeys.hard.any { (r.breakdown[it] ?: 0) > (base.breakdown[it] ?: 0) }
            assertEquals("shift $k risk", newHard, k in m.hardRisk)
            assertEquals("shift $k rec", !newHard && r.hard < base.hard, k in m.recommended)
        }
        assertEquals(ShiftMarks(), evaluateShiftMarks(st, s, i, 0, CellSeverity.HARD, cands, stillWanted = { false }))
    }

    /** 職員10 10/8・10/9: おすすめ 0・置ける候補が全部警告＝1 マスでは直らない注記。印の計算前や、おすすめがあるセルには出ない。 */
    @Test fun allRiskCellGetsTheSingleCellNote() {
        val i = staff("職員10")
        val cands = p.canDoShiftsForStaff(i).toSet()
        for (j in listOf(7, 8)) {
            val m = evaluateShiftMarks(st, s, i, j, CellSeverity.HARD, cands.sorted())
            assertTrue("10/${j + 1}", singleCellHopeless(m, cands, s[i][j]))
        }
        val m8 = evaluateShiftMarks(st, s, i, 7, CellSeverity.HARD, cands.sorted())
        assertEquals("前日が Dﾃ なので、Dﾃ 以外はどれも禁止の並びになります", allRiskReason(st, p, s, i, 7, m8, cands))
        val m9 = evaluateShiftMarks(st, s, i, 8, CellSeverity.HARD, cands.sorted())
        assertEquals("A4 は本人の希望なので、ほかへ変えると希望と違う勤務になります", allRiskReason(st, p, s, i, 8, m9, cands))
        assertEquals(null, allRiskReason(st, p, s, i, 7, ShiftMarks(), cands))
        assertEquals("関連セル: 10/9(金) A4（希望・反映済）", relatedCellsLine(st, s, i, listOf(8)))
        assertEquals("関連セル: 10/8(木) Dﾃ", relatedCellsLine(st, s, i, listOf(7)))
        assertEquals(null, relatedCellsLine(st, s, i, emptyList()))
        assertTrue(!singleCellHopeless(ShiftMarks(), cands, s[i][7]))
        assertTrue(!singleCellHopeless(ShiftMarks(recommended = setOf(0), hardRisk = cands - 0), cands, s[i][7]))
        assertTrue(!singleCellHopeless(ShiftMarks(hardRisk = cands), setOf(s[i][7]), s[i][7]))
    }

    /** 職員1010/8 に A4 を置くと禁止の並びが増える＝警告の印。 */
    @Test fun a4OnStaff10Oct8IsMarkedAsHardRisk() {
        val i = staff("職員10")
        val a4 = st.shifts.indexOfFirst { it.kigou == "A4" }
        val m = evaluateShiftMarks(st, s, i, 7, CellSeverity.HARD, (0 until st.shiftCount).toList())
        println("職員10 10/8 cur=${st.shifts[s[i][7]].kigou} risk=${m.hardRisk.map { st.shifts[it].kigou }} rec=${m.recommended.map { st.shifts[it].kigou }}")
        if (s[i][7] != a4) assertTrue(a4 in m.hardRisk)
    }

    /** 職員0810/3: 休の希望を守ったまま要調整がある＝板挟み。原因は同じ日の相手を名指しする。 */
    @Test fun wishDilemmaOnStaff08Oct3NamesTheCause() {
        val i = staff("職員08")
        val line = status(i, 2)
        println("職員08 10/3 $line")
        val wish = st.wishes["$i,2"]
        assertEquals(s[i][2], wish)
        assertTrue(isWishDilemma(wish, s[i][2], line.severity))
        assertEquals("本人の希望（休）を守っています", wishKeptLine(st.shifts[wish!!].kigou))
        assertTrue(line.cause, "職員" in line.cause && "との" in line.cause)
        assertTrue(!isWishDilemma(wish, s[i][2], CellSeverity.NONE))
        assertTrue(!isWishDilemma(null, s[i][2], line.severity))
        assertTrue(!isWishDilemma(wish!! + 1, s[i][2], line.severity))
    }

    @Test fun tourVisitsHardCellsFirstByDayThenStaff() {
        val ui = UiState(violationCellFamilies = mapOf(
            "3,5" to listOf("vio-c3n"), "1,5" to listOf("vio-pref"), "0,2" to listOf("vio-c42s"), "2,1" to listOf("vio-c3w"),
        ))
        assertEquals(listOf(2 to 1, 1 to 5, 3 to 5), violationTour(ui))
        assertEquals(listOf(2 to 1, 1 to 5, 3 to 5, 0 to 2), violationTour(ui, includeSoft = true))
        assertEquals(listOf(0 to 2), violationTour(UiState(violationCellFamilies = mapOf("0,2" to listOf("vio-c42s")))))
        val tour = violationTour(ui)
        assertEquals(1 to 5, nextTourCell(tour, 2 to 1))
        assertEquals(2 to 1, nextTourCell(tour, 3 to 5))
        assertEquals(2 to 1, nextTourCell(tour, 9 to 9))
        assertEquals(null, nextTourCell(emptyList(), 0 to 0))
        val real = violationTour(UiState(violationCellFamilies = rep.cellFamilies))
        assertTrue(real.isNotEmpty() && real.all { (a, b) -> rep.cellFamilies["$a,$b"]!!.any { isHardCellViolation(it) } })
    }

    /** 巡回は違反単位: 実データの必須 5 件（c3n 4＋c3w 1）が 5 件、日→職員の順、職員10 は 10/8〜10/9 の 1 件、人員不足は別の 1 行。 */
    @Test fun tourItemsAreOnePerHardViolation() {
        val items = hardViolationItems(st, p, s, rep.cellFamilies)
        println(items.map { it.heading })
        assertEquals(rep.hard - (rep.breakdown["covU"] ?: 0), items.size)
        assertEquals(items.sortedWith(compareBy({ it.days.first() }, { it.staff })), items)
        val a = items.single { it.staff == staff("職員10") }
        assertEquals(listOf(7, 8), a.days)
        assertEquals("禁止の並び Dﾃ→A4 ・ 10/8〜10/9", a.heading)
        val w = items.single { it.family == "c3w" }
        assertEquals(staff("職員03") to 0, w.cell)
        assertEquals(listOf(0, 1), w.days)
        assertEquals("必須違反 ${items.indexOf(a) + 1} / 5 ・ 禁止の並び Dﾃ→A4 ・ 10/8〜10/9", tourHeading(items, items.indexOf(a)))
        assertEquals(null, tourHeading(items, 9))
        assertEquals("必須違反 ${items.indexOf(a) + 1} / 5 ・ 禁止の並び Dﾃ→A4", peekHeading(tourHeading(items, items.indexOf(a))!!))
        assertEquals("ほかに人員不足 2件（日ヘッダから）", tourCovULine(2))
        assertEquals(null, tourCovULine(0))
    }

    @Test fun countLineAndDayLabels() {
        val keys = rep.countFamilies.keys.mapNotNull { VioKey.first(it) }
        val i = keys.first()
        val line = staffCountShort(st, p, s, i, rep.countFamilies)
        println("count line 職員${i + 1}: $line")
        assertTrue(line, line.contains("▼") || line.contains("▲"))
        assertEquals("", staffCountShort(st, p, s, i, emptyMap()))
        assertEquals("10/7(水)", adjacentDayLabel("2026-10-01", 31, 6))
        assertEquals(null, adjacentDayLabel("2026-10-01", 31, 31))
        assertEquals(null, adjacentDayLabel("2026-10-01", 31, -1))
        assertEquals("職員01 10/3 をA4に変更しました", cellChangedMessage("職員01", "2026-10-01", 2, "A4"))
    }

    @Test fun fixesByOthersKeepThePersonAndTheDay() {
        val mk = { ops: List<com.magi.app.v6.FixCell> -> com.magi.app.v6.FixSuggestion(com.magi.app.v6.FixKind.values().first(), ops, "", 0, 0, emptyList()) }
        val a = mk(listOf(com.magi.app.v6.FixCell(1, 2, 0)))
        val b = mk(listOf(com.magi.app.v6.FixCell(7, 2, 0), com.magi.app.v6.FixCell(1, 2, 3)))
        val c = mk(listOf(com.magi.app.v6.FixCell(1, 3, 0)))
        assertEquals(listOf(a), fixesByOthers(listOf(a, b, c), day = 2, except = 7))
        assertTrue(FixFocus(null, null, 2, exceptStaff = 7).key != FixFocus(null, null, 2).key)
    }

    @Test fun wishTabStates() {
        assertEquals("未登録", wishTabState(null, 1))
        assertEquals("反映済", wishTabState(1, 1))
        assertEquals("未反映", wishTabState(2, 1))
    }

    @Test fun sheetRevChangesOnWishOnlyChangeAndUndoRestoresWishDisplay() {
        val before = UiState(schedule = st.schedule, checkRev = 5, editRev = 2).withWishDisplay(st)
        assertEquals(st.wishes, before.wishes)
        val key = st.wishes.keys.first()
        val edited = st.copy(wishes = st.wishes + (key to (st.wishes.getValue(key) + 1) % st.shiftCount))
        val afterWish = before.withWishDisplay(edited)
        assertEquals(before.schedule, afterWish.schedule)
        assertTrue(cellSheetRev(before) != cellSheetRev(afterWish))
        // 元に戻すは同じ段の設定から希望の表示を即時に戻す（検査の完了を待たない）
        val undone = afterWish.copy(editRev = afterWish.editRev + 1).withWishDisplay(st)
        assertEquals(st.wishes, undone.wishes)
        assertEquals(before.lockedWishKeys, undone.lockedWishKeys)
        assertTrue(cellSheetRev(afterWish) != cellSheetRev(undone))
    }

    /** [S6] 起点の窓と手順のセルだけがホームの組を引き継ぐ。探索中・組なしはそれぞれの言い方、セルごとの試算はしない。 */
    @Test fun relaxHandoffOnlyForCellsTheFoundSetTouches() {
        val r = com.magi.app.v6.RelaxTrial.firstWall(st, s) as com.magi.app.v6.RelaxTrial.Result
        val i = staff("職員10")
        assertEquals(i to 7, r.staff to r.day)
        assertEquals(RelaxHandoff.OFFER, relaxHandoff(r, false, false, i, 7))
        assertEquals(RelaxHandoff.OFFER, relaxHandoff(r, false, false, i, 8))
        val touched = r.moves.first { it.staff != i }
        assertEquals(RelaxHandoff.OFFER, relaxHandoff(r, false, false, touched.staff, touched.day))
        assertEquals(RelaxHandoff.NONE, relaxHandoff(r, false, false, i, 20))
        assertEquals(RelaxHandoff.NONE, relaxHandoff(r, true, true, i, 20))
        assertEquals(RelaxHandoff.SEARCHING, relaxHandoff(null, true, false, i, 7))
        assertEquals(RelaxHandoff.NO_WALL, relaxHandoff(null, false, true, i, 7))
        assertEquals(RelaxHandoff.NONE, relaxHandoff(null, false, false, i, 7))
        assertEquals(RelaxHandoff.STOPPED, relaxHandoff(null, false, false, i, 7, stopped = true))
        assertEquals(RelaxHandoff.SEARCHING, relaxHandoff(null, true, false, i, 7, stopped = true))
        assertEquals(RelaxHandoff.OFFER, relaxHandoff(r, false, false, i, 7, stopped = true))
        assertEquals("例外として緩める候補: A", relaxPeekLabel("A"))
        val ui = UiState(staffNames = st.staff.map { it.name }, shiftSymbols = st.shifts.map { it.kigou }, violationCellFamilies = rep.cellFamilies)
        assertEquals("設定を緩めると、この禁止の並びを解消できる見込みです（上限 2件）" + (if (r.rr > 0) "。他の必須違反 ${r.rr}件 は残ります" else ""), relaxHandoffLine(r, ui))
        assertEquals("設定を緩めると、この禁止の並びを解消できる見込みです（上限 2件）。他の必須違反 3件 は残ります", relaxHandoffLine(r.copy(rr = 3), ui))
    }

    /** 職員10 10/9（A4 の希望を守る板挟み）: 同じ禁止の並びのもう一方は 10/8。10/8 から見れば 10/9。c3w は印の前日と希望の翌日。 */
    @Test fun partnerCellOfTheSameViolation() {
        val i = staff("職員10")
        fun fams(j: Int) = cellStatusFamilies(rep.cellFamilies["$i,$j"].orEmpty(), emptyList(), emptyList())
        assertEquals(listOf(7), violationPartnerDays(p, s, i, 8, fams(8), rep.cellFamilies))
        assertEquals(listOf(8), violationPartnerDays(p, s, i, 7, fams(7), rep.cellFamilies))
        assertEquals("同じ違反のもう一方のセル（10/8(木)）を見る", partnerCellLabel(st.startDate, 7, true))
        assertEquals("同じ違反のほかのセル（10/8(木)）を見る", partnerCellLabel(st.startDate, 7, false))
        assertEquals("10/8〜10/9", DayText.range(st.startDate, 7, 8))
        assertEquals("10/8(木)", DayText.range(st.startDate, 7, 7))
        assertEquals("8日", DayText.full("", 7))
        val w = staff("職員03")
        assertEquals(listOf(1), violationPartnerDays(p, s, w, 0, listOf("c3w"), rep.cellFamilies))
        assertEquals(listOf(0), violationPartnerDays(p, s, w, 1, emptyList(), rep.cellFamilies))
    }

    @Test fun fixPanelStatesSpinOnlyWhileRunning() {
        assertEquals(FixPanelState.WAIT_CHECK, fixPanelState(running = true, fixSearching = false, doneKey = "k", failedKey = "", key = "k"))
        assertEquals(FixPanelState.NOT_STARTED, fixPanelState(running = false, fixSearching = false, doneKey = "", failedKey = "", key = "k"))
        assertEquals(FixPanelState.RUNNING, fixPanelState(running = false, fixSearching = true, doneKey = "", failedKey = "", key = "k"))
        assertEquals(FixPanelState.DONE, fixPanelState(running = false, fixSearching = false, doneKey = "k", failedKey = "", key = "k"))
        assertEquals(FixPanelState.FAILED, fixPanelState(running = false, fixSearching = false, doneKey = "", failedKey = "k", key = "k"))
        assertEquals(FixPanelState.NOT_STARTED, fixPanelState(running = false, fixSearching = false, doneKey = "other", failedKey = "", key = "k"))
    }

    @Test fun noticeUndoOnlyForItsOwnOperationAndProgressDoesNotReplaceIt() {
        assertTrue(noticeUndoApplies(7L, 7L))
        assertTrue(!noticeUndoApplies(8L, 7L))
        assertTrue(!noticeUndoApplies(null, 7L))
        assertTrue(!messageMayReplaceNotice(noticeShowing = true, isError = false))
        assertTrue(messageMayReplaceNotice(noticeShowing = true, isError = true))
        assertTrue(messageMayReplaceNotice(noticeShowing = false, isError = false))
    }

    @Test fun detailListsEveryOverlappingFamily() {
        val (key, cls) = rep.cellFamilies.entries.first { it.value.map { c -> familyOfVioClass(c) }.distinct().size >= 2 }
        val i = VioKey.first(key)!!; val j = VioKey.second(key)!!
        val fams = cellStatusFamilies(cls, emptyList(), emptyList())
        val lines = cellDetailLines(st, p, s, i, j, fams)
        println(lines)
        assertEquals(fams.size, lines.size)
        assertTrue(lines.all { it.startsWith("必須・") || it.startsWith("要調整・") })
    }

    /** 大島愛（写しでは職員08）10/10: 上限0 の Cｱ・有 を回数の行で言い、シフトボタンに「上限0」を添える。360dp で 2 行に収まる。 */
    @Test fun zeroCapIsSpelledOutInTheCountLineAndButtons() {
        val i = st.staff.indexOfFirst { it.name == "職員08" }
        val line = "回数 " + staffCountShort(st, p, s, i, rep.countFamilies)
        println("職員08 10/10 $line")
        val fams = cellStatusFamilies(rep.cellFamilies[VioKey.cell(i, 9)].orEmpty(), rep.needFamilies[VioKey.need(s[i][9], 9)].orEmpty(), rep.countFamilies[VioKey.count(i, s[i][9])].orEmpty())
        println("職員08 10/10 今=${st.shifts[s[i][9]].kigou} 希望=${p.wish[i][9]} 状態=${cellStatusLine(st, p, s, i, 9, fams).text}")
        println("職員08 ボタン " + (0 until p.K).joinToString(" ") { k -> "[" + st.shifts[k].kigou + (if (!p.canDo(i, k)) " 外" else if (k in zeroCapShifts(p, i)) "/上限0" else "") + "]" })
        assertTrue(line, line.contains("Cｱ 2回（$ZERO_CAP_NOTE）▲") && line.contains("有 1回（上限0）▲"))
        assertTrue(line, line.contains("(下限"))
        assertTrue(line, fitsTwoLines(line, COUNT_LINE_EM))
        val caps = zeroCapShifts(p, i).map { st.shifts[it].kigou }
        assertTrue(caps.toString(), "Cｱ" in caps && "有" in caps && "休" !in caps)
        assertEquals("Cｱは個人の上限0（入れない指定）のシフトです。希望どおり入れると要調整に数えます", wishZeroCapLine("Cｱ"))
        for (x in 0 until st.staffCount) {
            val l = "回数 " + staffCountShort(st, p, s, x, rep.countFamilies)
            assertTrue("職員${x + 1}: $l", fitsTwoLines(l, COUNT_LINE_EM))
        }
        assertTrue(!fitsTwoLines("あ".repeat(55), COUNT_LINE_EM))
    }

    @Test fun peekPutsCurrentAndWishFirstThenShiftOrder() {
        assertEquals(listOf(3, 5, 0, 1), peekShifts(listOf(0, 1, 2, 3, 4, 5), setOf(0, 1, 3, 5), current = 3, wish = 5))
        assertEquals(listOf(0, 1, 2), peekShifts(listOf(0, 1, 2), setOf(0, 1, 2), current = -1, wish = null))
        assertEquals(listOf(2, 0, 1, 3), peekShifts(listOf(0, 1, 2, 3, 4), setOf(0, 1, 2, 3, 4), current = 2, wish = 2))
    }

    @Test fun peekRecommendationOnlyFromAnExistingRelaxMove() {
        val r = com.magi.app.v6.RelaxTrial.Result(1, 4, 2..6, emptyList(), emptyList(), 1, 1, 1, 0,
            listOf(com.magi.app.v6.RelaxTrial.Move(1, 4, 0, 2)))
        assertEquals(2, peekRecommendation(r, 1, 4))
        assertEquals(null, peekRecommendation(r, 1, 5))
        assertEquals(null, peekRecommendation(null, 1, 4))
    }

    @Test
    fun peekPickCountFitsFourAtPhoneWidths() {
        assertEquals(4, peekPickCount(360 - 32))
        assertEquals(4, peekPickCount(390 - 32))
        assertEquals(4, peekPickCount(282))
        assertEquals(3, peekPickCount(281))
        assertEquals(1, peekPickCount(40))
        assertEquals(4, peekPickCount(800))
    }

    /** 人員不足・過剰の文言はチェッカー（covUCell/covOCell）と同じ実効の必要数を出す。 */
    @Test fun coverageWordingUsesCheckersEffectiveDemand() {
        val k = s[0][0].takeIf { it >= 0 } ?: 0
        fun detail(need1: String, need2: String, fam: String): String {
            val sh = st.shifts.mapIndexed { idx, x -> if (idx == k) x.copy(need1 = need1, need2 = need2) else x }
            val st2 = st.copy(shifts = sh, use2Patterns = true, needDay1 = emptyMap(), needDay2 = emptyMap())
            return cellStatusLine(st2, cachedProblem(st2), s, 0, 0, listOf(fam)).text
        }
        assertTrue(detail("", "3", "covU"), detail("", "3", "covU").contains("必要3人"))
        assertTrue(detail("5", "3", "covU"), detail("5", "3", "covU").contains("必要3人"))
        assertTrue(detail("5", "3", "covO"), detail("5", "3", "covO").contains("適正5人"))
    }

    @Test
    fun hardViolationRows_putsWishOnlyLastAndKeepsTourIndex() {
        val items = listOf(
            TourItem("c3n", 0, listOf(1, 2), "禁止の並び A→B ・ 10/2〜10/3"),
            TourItem("pref", 1, listOf(4), "希望の勤務 A ・ 10/5"),
            TourItem("c3w", 2, listOf(5, 6), "希望の前日禁止 A→B ・ 10/6〜10/7"),
        )
        val rows = hardViolationRows(items, listOf("佐藤", "鈴木"), setOf("0,1", "0,2", "2,5"))
        assertEquals(listOf(1, 2, 0), rows.map { it.tourAt })
        assertEquals(listOf(false, false, true), rows.map { it.wishOnly })
        assertEquals(listOf("鈴木", "職員3", "佐藤"), rows.map { it.name })
        assertEquals("禁止の並び A→B ・ 10/2〜10/3", rows.last().heading)
    }

    /** [3.645.2/実機報告] 拡張希望（この日はこのシフト以外）がある日の希望タブは「未登録」と言わない。 */
    @Test fun wishTabLineShowsExtendedWishes() {
        val sym = { k: Int -> listOf("休", "Pｼ", "Dﾃ", "Cｱ")[k] }
        assertEquals("希望 —（未登録）", wishTabLine(null, 3, null, sym, pinned = false))
        assertEquals("希望 休・Pｼ・Dﾃ 以外（反映済）", wishTabLine(null, 3, setOf(2, 0, 1), sym, pinned = false))
        assertEquals("希望 休・Pｼ 以外（未反映）・手動固定", wishTabLine(null, 0, setOf(0, 1), sym, pinned = true))
        assertEquals("希望 Cｱ（反映済）・休 以外（反映済）", wishTabLine(3, 3, setOf(0), sym, pinned = false))
    }

    /** 希望モードで押せない日＝その職員の拡張希望の指定日（日だけで決める＝wishBlockedBy と同じ）。禁止の記号は件の和集合をシフト一覧の順で。 */
    @Test fun extWishDayListsBannedShiftsInShiftOrder() {
        val ext = listOf(ExtWishView(0, 2, "A", listOf(3, 5), listOf("Pｼ")), ExtWishView(1, 2, "A", listOf(5), listOf("Pｼ", "休")))
        val kigou = listOf("休", "Aｱ", "Pｼ")
        assertEquals(listOf("休", "Pｼ"), extWishDayKigou(ext, 2, 4, kigou))
        assertEquals(listOf("Pｼ"), extWishDayKigou(ext, 2, 2, kigou))
        assertNull(extWishDayKigou(ext, 2, 3, kigou))
        assertNull(extWishDayKigou(ext, 1, 4, kigou))
        assertEquals("この日は拡張希望（休・Pｼ 以外）の指定日なので、希望は入れられません", extWishDayNote(listOf("休", "Pｼ")))
    }

    /** [3.646.0] 直し方の探索の締切: セルのシートは 3 秒、全体は 8 秒。探索中の 1 行に最長の秒数を添える。 */
    @Test fun fixSearchBudgetsAreNamedInTheWaitingLine() {
        assertEquals(3000L, FIX_SEARCH_QUICK_MS)
        assertEquals(8000L, FIX_SEARCH_MS)
        assertEquals("この場所の直し方を探しています…（最長 3 秒）", fixSearchingText(true))
        assertEquals("直し方を探しています…（最長 8 秒）", fixSearchingText(false))
    }
}
