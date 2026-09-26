package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.toIntArray2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 勤務表の表示専用の印（c1 の窓ごとの印・回数の行末の印・日ヘッダの人員・隠れた族の点）を実データで固定する。 */
class GridDisplayMarksTest {
    private fun load(path: String): MagiState =
        StateParser.parse(javaClass.getResourceAsStream(path)!!.bufferedReader().readText())!!

    private fun uiOf(st: MagiState, rep: ViolationReport) = UiState(
        staff = st.staffCount, days = st.dayCount, shifts = st.shiftCount, schedule = st.schedule,
        shiftSymbols = st.shifts.map { it.kigou },
        violationCells = rep.violations, violationCellFamilies = rep.cellFamilies,
        needViolations = rep.needViolations, needFamilies = rep.needFamilies,
        countViolations = rep.countViolations, countFamilies = rep.countFamilies,
        distLocations = rep.distLocations, c1Shortages = c1Shortages(cachedProblem(st), st.schedule.toIntArray2D()), breakdown = rep.breakdown,
    )

    private val st = load("/oct2026_grid_state.json")
    private val rep = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
    private val ui = uiOf(st, rep)
    private val vs = MagiViewState(ui)

    private fun marked(i: Int) = (0 until st.dayCount).filter { "vio-c1" in displayCellClasses(ui, VioKey.cell(i, it), vs.c1Marks) }

    /** 実データ（10 月）: 印は不足窓の中の「休に変えられる日」だけ。すでに休の日・ランの先頭には描かない。 */
    @Test fun c1MarksAreChangeableDaysOnly() {
        val p = cachedProblem(st)
        val rest = st.shifts.indexOfFirst { it.kigou == "休" }
        // 職員01: 不足は 10/3〜10/12。10/3 の休には印なし、10/4〜10/12 の休でない変えられる日に印。
        val s0 = ui.c1Shortages.single { it.staff == 0 }
        assertEquals(2 to 11, s0.from to s0.to)
        assertTrue(s0.band)
        assertTrue("10/3 休に印", 2 !in marked(0))
        val expect0 = (3..11).filter { st.schedule[0][it] != rest && c1Changeable(p, 0, it, rest) }
        assertEquals(expect0, marked(0))
        assertEquals(listOf(3, 4, 6, 8, 9, 10), marked(0))
        assertTrue("10/7 の休（職員07）", 6 !in marked(6) && st.schedule[6][6] == rest)
        assertTrue("10/5 の休（職員10）", 4 !in marked(9) && st.schedule[9][4] == rest)
        for (sh in ui.c1Shortages) for (d in sh.marks) assertTrue(st.schedule[sh.staff][d] != rest)
        for (i in 0 until st.staffCount) println("c1 職員${i + 1} 印=${marked(i).map { "10/${it + 1}" }} 帯=${(0 until st.dayCount).filter { vs.c1Band[i][it] }.let { if (it.isEmpty()) "-" else "10/${it.first() + 1}〜10/${it.last() + 1}" }}")
        // 帯は窓が重なって続く区間だけ。職員01 は 10/3〜10/12。
        assertEquals((2..11).toList(), (0 until st.dayCount).filter { vs.c1Band[0][it] })
        assertTrue(MagiViewState(ui, allVioBucketKeys - "window").c1Band.all { r -> r.none { it } })
    }

    @Test fun everyViolatedC1WindowWithAChangeableDayHasAMark() {
        assertEquals("ランの窓数の和はチェッカーの c1 件数", rep.breakdown["c1"] ?: 0, ui.c1Shortages.sumOf { it.windows })
        val p = cachedProblem(st)
        for (sh in ui.c1Shortages) for (w in sh.from..sh.to - sh.day1 + 1) {
            val win = w until w + sh.day1
            val can = win.any { st.schedule[sh.staff][it] != sh.shift && c1Changeable(p, sh.staff, it, sh.shift) }
            if (can) assertTrue("職員${sh.staff} の窓 ${w + 1}日〜", win.any { it in sh.marks })
        }
    }

    @Test fun c1SheetTextNamesThePeriodAndCountsHeldDays() {
        val p = cachedProblem(st); val s = st.schedule.toIntArray2D()
        val text = cellDetailLines(st, p, s, 0, 2, listOf("c1")).single()
        assertEquals("要調整・期間の約束: 7日のなかに「休」が2日必要です。いま足りない期間（10/3〜10/12）があり、印の日を休にすると届く見込みです。（この日の休はすでに数に入っています）", text)
        assertTrue("（この日の" !in cellDetailLines(st, p, s, 0, 3, listOf("c1")).single())
        assertTrue("vio-c1" in sheetCellClasses(displayCellClasses(ui, VioKey.cell(0, 2), vs.c1Marks), true))
    }

    /** 休以外の規則でも同じ（A4 を 7 日に 2 日）: 印はいま A4 でなく A4 に変えられる日だけ。 */
    @Test fun c1WorksForANonRestShift() {
        val st2 = st.copy(cons1 = listOf(com.magi.app.model.C1Row("7", "A4", "2")))
        val p = cachedProblem(st2); val s = st2.schedule.toIntArray2D()
        val a4 = st2.shifts.indexOfFirst { it.kigou == "A4" }
        val sh = c1Shortages(p, s)
        assertTrue(sh.isNotEmpty() && sh.all { it.shift == a4 })
        for (x in sh) for (d in x.marks) assertTrue(s[x.staff][d] != a4 && c1Changeable(p, x.staff, d, a4))
        val x = sh.first { it.marks.isNotEmpty() }
        val line = cellDetailLines(st2, p, s, x.staff, x.marks.first(), listOf("c1")).single()
        assertTrue(line, "「A4」が2日必要" in line && "印の日をA4にすると" in line)
    }

    /** 変えられる日が 1 つも無い不足窓: その窓の日に印は出さず、勤務表だけでは満たせない旨と次の一歩。 */
    @Test fun windowWithNoChangeableDayFallsBack() {
        val rest = st.shifts.indexOfFirst { it.kigou == "休" }
        val locked = (3..9).associate { "0,$it" to st.schedule[0][it] }
        val st2 = st.copy(wishes = st.wishes + locked)
        val p = cachedProblem(st2); val s = st2.schedule.toIntArray2D()
        val s0 = c1Shortages(p, s).single { it.staff == 0 }
        assertTrue(s0.stuck)
        assertTrue(s0.marks.none { it in 3..9 })
        val line = cellDetailLines(st2, p, s, 0, 4, listOf("c1")).single()
        assertTrue(line, C1_STUCK_TEXT in line)
        assertTrue(rest >= 0)
        val u2 = ui.copy(c1Shortages = c1Shortages(p, s))
        assertTrue(0 in MagiViewState(u2).c1Stuck)
        assertTrue(staffCountLines(u2, 0).any { C1_STUCK_TEXT in it })
        // 実データでも職員11 の 10/13〜10/22 に変えられる日の無い窓がある。
        assertTrue(ui.c1Shortages.any { it.staff == 10 && it.stuck })
    }

    @Test fun checkerMarksStayAtTheRunHeadOnly() {
        val c1Cells = rep.cellFamilies.filterValues { "vio-c1" in it }.keys
        assertEquals(rep.c1Runs.map { VioKey.cell(it[0], it[1]) }.toSet(), c1Cells)
        for (key in c1Cells) if (key !in vs.c1Marks) assertTrue(key, "vio-c1" !in displayCellClasses(ui, key, vs.c1Marks))
    }

    @Test fun everyStaffWithACountKeyHasABadge() {
        val keys = rep.countFamilies.keys
        assertEquals(27, keys.size)
        val staff = keys.mapNotNull { VioKey.first(it) }.toSet()
        assertEquals(staff, vs.countBadges.keys)
        for (i in staff) assertTrue(staffCountLines(ui, i).isNotEmpty())
        assertTrue(MagiViewState(ui, allVioBucketKeys - "count").countBadges.isEmpty())
    }

    @Test fun coverageHeaderNamesTheShift() {
        val rest = st.shifts.indexOfFirst { it.kigou == "休" }
        val a4 = st.shifts.indexOfFirst { it.kigou == "A4" }
        for (d in listOf(27, 28)) assertTrue("10/${d + 1} 休▲", CoverageMark(rest, false) in vs.coverageMarks[d])
        assertTrue("10/9 A4▲", CoverageMark(a4, false) in vs.coverageMarks[8])
        assertTrue(coverageHeaderLabel(ui, vs.coverageMarks[27]).startsWith("休▲"))
        assertTrue(MagiViewState(ui, allVioBucketKeys - "need").coverageMarks.all { it.isEmpty() })
    }

    @Test fun hiddenC42sCellsGetASecondaryDot() {
        val hidden = rep.cellFamilies.keys.filter { key ->
            val cls = displayCellClasses(ui, key, vs.c1Marks)
            "vio-c42s" in cls && cls.first() != "vio-c42s"
        }
        assertTrue(hidden.isNotEmpty())
        for (key in hidden) assertNotNull(key, vs.cellSecond[VioKey.first(key)!!][VioKey.second(key)!!])
    }

    @Test fun fairAndWeeklyAppearInTheStaffSheet() {
        for (fam in listOf("fair", "weekly")) for (e in rep.distLocations[fam].orEmpty()) {
            assertTrue("$fam 職員${e[0]}", staffCountLines(ui, e[0]).any { breakdownLabels[fam]!! in it })
        }
    }

    /** 手が見つからないときは確かめた事実だけを書く（希望固定・上限 0・その日の必要人数ぎりぎり・ほかの勤務の固定）。 */
    @Test fun noFixReasonsNameOnlyVerifiedFacts() {
        val u = UiState(
            staff = 2, days = 3, shifts = 3, shiftSymbols = listOf("休", "A", "B"),
            schedule = listOf(listOf(1, 1, 0), listOf(2, 0, 1)),
            wishes = mapOf("0,0" to 1, "0,1" to 1),
        )
        val limits = { i: Int, k: Int -> if (i == 0 && k == 1) Triple(null, 0, null) else if (i == 0 && k == 0) Triple(1, 1, null) else Triple(null, null, null) }
        val need = { k: Int, _: Int -> if (k == 1) 1 to 1 else null }
        val why = noFixReasons(u, FixFocus(0, 1), limits, need)
        assertTrue(why.wishRelated)
        assertTrue(why.lines.any { "どれも本人の希望で固定" in it })
        assertTrue(why.lines.any { "上限 0" in it })
        assertTrue(why.lines.any { "必要人数ぎりぎり" in it })
        assertTrue(why.lines.any { "下限＝上限で固定" in it && "休 1回" in it })
        assertEquals(NO_FIX_SCOPE, why.lines.last())
        assertEquals("yr_count", why.settingsSection)
        val plain = noFixReasons(u, FixFocus(1, 2))
        assertEquals(listOf(NO_FIX_SCOPE), plain.lines)
        assertEquals("yr_headcount", noFixReasons(u, FixFocus(null, 1, 0)).settingsSection)
        assertTrue(noFixReasons(u, FixFocus(0, null, 0)).lines.first().contains("このセル"))
    }

    @Test fun fixFocusKeyTellsRequestsApart() {
        assertTrue(FixFocus(0, 1).key != FixFocus(0, null, 1).key)
        assertEquals(FixFocus(0, 1).key, FixFocus(0, 1, null).key)
    }

    /** シフト集計「計（期間）」: 休は 10/28-29、A4 は 10/9 の人員過剰を日数で持ち、必要数の無いシフトは載らない。 */
    @Test fun shiftTotalsCarryCoverageDays() {
        val totals = shiftCoverageTotals(vs.coverageMarks)
        val rest = st.shifts.indexOfFirst { it.kigou == "休" }
        val a4 = st.shifts.indexOfFirst { it.kigou == "A4" }
        assertTrue(totals[rest]!!.overDays.containsAll(listOf(27, 28)))
        assertEquals(listOf(8), totals[a4]!!.overDays)
        assertTrue(totals[a4]!!.glyph.startsWith("▲"))
        val needKeys = rep.needFamilies.filterValues { v -> v.any { it == "vio-covU" || it == "vio-covO" } }.keys.mapNotNull { VioKey.first(it) }.toSet()
        assertEquals(needKeys, totals.keys)
    }
}
