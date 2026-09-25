package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.canDoShiftsForStaff
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
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

    @Test fun countLineAndDayLabels() {
        val keys = rep.countFamilies.keys.mapNotNull { VioKey.first(it) }
        val i = keys.first()
        val line = staffCountShort(st, p, s, i, rep.countFamilies)
        println("count line 職員${i + 1}: $line")
        assertTrue(line, line.contains("▼") || line.contains("▲"))
        assertEquals("", staffCountShort(st, p, s, i, emptyMap()))
        assertEquals("7日(水)", adjacentDayLabel("2026-10-01", 31, 6))
        assertEquals(null, adjacentDayLabel("2026-10-01", 31, 31))
        assertEquals(null, adjacentDayLabel("2026-10-01", 31, -1))
        assertEquals("職員01 3日をA4に変更しました", cellChangedMessage("職員01", 2, "A4"))
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
}
