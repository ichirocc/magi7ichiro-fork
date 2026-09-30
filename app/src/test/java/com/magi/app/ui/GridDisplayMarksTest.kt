package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.canDo
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
        assertEquals("要調整・期間の約束: 7日のなかに「休」が2日必要です。いま足りない期間（10/3〜10/12）があり、印の日を休にするとこの約束の日数に届きます（ほかの約束への影響は見ていません）。（この日の休はすでに数に入っています）", text)
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

    /** 窓の不足が 2 日で変えられる日が 1 日だけ: 届く見込みとは言わず、減るだけ＋勤務表だけでは満たせない旨。 */
    @Test fun windowWithFewerChangeableDaysThanTheDeficitIsStuck() {
        val rest = st.shifts.indexOfFirst { it.kigou == "休" }
        val p0 = cachedProblem(st)
        val w = (0 until st.shiftCount).first { it != rest && p0.canDo(0, it) }
        val st2 = st.copy(cons1 = listOf(com.magi.app.model.C1Row(st.dayCount.toString(), "休", "2")),
            wishes = st.wishes.filterKeys { !it.startsWith("0,") } + (0 until st.dayCount).filter { it != 3 }.associate { "0,$it" to w })
        val p = cachedProblem(st2); val s = st2.schedule.toIntArray2D()
        for (d in 0 until st.dayCount) s[0][d] = w
        assertTrue(c1Changeable(p, 0, 3, rest))
        val s0 = c1Shortages(p, s).single { it.staff == 0 }
        assertEquals(listOf(3), s0.marks)
        assertTrue(s0.stuck)
        val line = c1CellText(listOf(s0), s, 0, 3, { st.shifts[it].kigou }, { "${it + 1}日" })!!
        assertTrue(line, "届き" !in line && "届く" !in line && "不足は減ります" in line && C1_STUCK_TEXT in line)
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

    /** 行末の印のシート（実データ 職員02）: 回数は 2 列のチップ、曜日は 1 シフト 1 句、公平化は差ごとに 1 行。 */
    @Test fun staffCountSheetOnTheRealBoard() {
        val u = ui.copy(startDate = st.startDate)
        val lim: (Int, Int) -> Triple<Int?, Int?, Int?> = { i, k -> conditionsViewOf(st, com.magi.app.v6.cachedProblem(st)).staffCellLimits(i, k) }
        val sh = staffCountSheet(u, 1, lim)
        assertEquals(listOf("Dﾃ  -1回 (3/4)", "Cｵ  +3回 (8/5)", "有  -1回 (0/1)"), sh.chips.map { it.text })
        assertEquals(listOf(true, false, true), sh.chips.map { it.under })
        assertEquals(listOf("差 1回 : A4, 有"), sh.fair)
        assertEquals(rep.distLocations["weekly"]!!.count { it[0] == 1 }, sh.weekly.size)
        val low = staffCountSheet(u, 3, lim).chips.single { it.shift == "B1" }
        assertEquals("B1  -3回 (17/下限20)", low.text)
        assertEquals("Aｱ  +1回 (1/上限0)", staffCountSheet(u, 3, lim).chips.first().text)
    }

    /** 曜日の句の数値＝各側の |e| の和÷7 を丸めたもの（両側で同じ値）。採点 Σ|e|/7 のおよそ半分で、偏りがあれば 1 以上。 */
    @Test fun weeklySkewPhraseMatchesTheScoredDeviation() {
        assertEquals(null, weeklySkewPhrase(intArrayOf(1, 1, 1, 1, 1, 1, 1)))
        assertEquals("日・月に集中 (+1)", weeklySkewPhrase(intArrayOf(2, 2, 1, 1, 1, 1, 1)))
        assertEquals("金が少ない (-1)", weeklySkewPhrase(intArrayOf(1, 1, 1, 1, 1, 0, 1)))
        assertEquals("日・月に集中 (+2)／水が少ない (-2)", weeklySkewPhrase(intArrayOf(3, 3, 2, 0, 2, 2, 2)))
        for (e in rep.distLocations["weekly"]!!) {
            val row = st.schedule[e[0]]; val wd = IntArray(7)
            row.forEachIndexed { j, k -> if (k == e[1]) wd[(dow0Of(st.startDate) + j) % 7]++ }
            assertEquals(e[2], com.magi.app.v6.weeklyDevOfBucket(wd))
            val ns = Regex("\\(([+-])(\\d+)\\)").findAll(weeklySkewPhrase(wd)!!).map { it.groupValues[2].toInt() }.toList()
            assertTrue("$e ns=$ns", ns.isNotEmpty() && ns.distinct().size == 1 && ns.all { n -> n >= 1 && kotlin.math.abs(2 * n - e[2]) <= 1 })
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
        assertTrue(why.lines.any { "個人の上限が 0 回" in it })
        assertTrue(why.lines.any { "必要人数ぎりぎり" in it })
        assertTrue(why.lines.any { "下限＝上限で固定" in it && "休 1回" in it })
        assertEquals(NO_FIX_SCOPE, why.lines.last())
        assertEquals("yr_count", why.settingsSection)
        val plain = noFixReasons(u, FixFocus(1, 2))
        assertEquals(listOf(NO_FIX_SCOPE), plain.lines)
        assertEquals("yr_headcount", noFixReasons(u, FixFocus(null, 1, 0)).settingsSection)
        assertTrue(noFixReasons(u, FixFocus(0, null, 0)).lines.first().contains("このセル"))
        // 禁止の並びの相手が本人の希望＝希望が関わる（［希望を見る］が出る）。板挟みの「他の人で補う」も同じ。
        val c3 = UiState(startDate = "2026-10-01", schedule = listOf(listOf(2, 3)), wishes = mapOf("0,1" to 3), shiftSymbols = listOf("休", "Pｼ", "Dﾃ", "A4"),
            violationCellFamilies = mapOf("0,0" to listOf("vio-c3n"), "0,1" to listOf("vio-c3n")))
        val w0 = noFixReasons(c3, FixFocus(0, null, 0))
        assertTrue(w0.wishRelated)
        assertEquals("この並びには本人の希望（10/2 の「A4」）が入っています。", w0.lines.first())
        val w1 = noFixReasons(c3, FixFocus(null, null, 1, exceptStaff = 0))
        assertTrue(w1.wishRelated)
        assertEquals("本人の希望（10/2 の「A4」）は守ったままです。", w1.lines.first())
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

    /** 上限0（休を除く）のシフトが入った日は全部セルに印（破線・回数チップに従う）。上限1以上の超過は名前の横だけ。 */
    @Test fun zeroCapCellsAreMarkedAndFollowTheCountChip() {
        val p = cachedProblem(st); val s = st.schedule.toIntArray2D()
        val z = zeroCapCells(p, s)
        val ui2 = ui.copy(zeroCapCells = z)
        val vs2 = MagiViewState(ui2)
        fun sym(k: Int) = st.shifts[k].kigou
        val expect = HashSet<String>()
        for (i in 0 until st.staffCount) for (j in 0 until st.dayCount) {
            val k = s[i][j]
            if (sym(k) != "休" && p.rangeHi[i][k] == 0) expect += VioKey.cell(i, j)
        }
        assertEquals(expect, z)
        // 職員04: Aｱ（10/10）と Cｵ（10/11）は上限0。A4 は上限0だがこの盤面には入っていない。
        val s3 = (0 until st.dayCount).filter { VioKey.cell(3, it) in z }.map { "10/${it + 1} ${sym(s[3][it])}" }
        assertEquals(listOf("10/10 Aｱ", "10/11 Cｵ"), s3)
        for (key in z) {
            val i = VioKey.first(key)!!; val j = VioKey.second(key)!!
            assertTrue(key, "vio-high" in rep.countFamilies[VioKey.count(i, s[i][j])].orEmpty())
            assertTrue(key, isHeavySoftCellViolation(vs2.cellVio[i][j]) || isHardCellViolation(vs2.cellVio[i][j]))
        }
        assertEquals(null, MagiViewState(ui2, allVioBucketKeys - bucketOfFamily("high")!!).cellVio[3][9])
        // 上限1以上の超過（職員04 の範囲 20〜21 等）はセルに出さない。
        for (key in rep.countFamilies.filterValues { "vio-high" in it }.keys) {
            val i = VioKey.first(key)!!; val k = VioKey.second(key)!!
            if (p.rangeHi[i][k] > 0) for (j in 0 until st.dayCount) if (s[i][j] == k) assertTrue(VioKey.cell(i, j) !in z)
        }
        println("上限0の印 " + z.map { VioKey.first(it)!! to VioKey.second(it)!! }.sortedWith(compareBy({ it.second }, { it.first }))
            .joinToString { (i, j) -> "${st.staff[i].name} 10/${j + 1} ${sym(s[i][j])}" })
    }

    @Test fun zeroCapStatusLineIsNeutralAndNamesTheWish() {
        val p = cachedProblem(st); val s = st.schedule.toIntArray2D()
        assertEquals("⚠ 要調整：$ZERO_CAP_TEXT", cellStatusLine(st, p, s, 3, 9, listOf("high")).text)
        val i11 = st.staff.indexOfFirst { it.name == "職員11" }
        assertEquals("⚠ 要調整：$ZERO_CAP_WISH_TEXT", cellStatusLine(st, p, s, i11, 5, listOf("high")).text)
        assertTrue("間違い" !in ZERO_CAP_TEXT && "ミス" !in ZERO_CAP_WISH_TEXT)
    }

    /** 許容0の超過（人員の上限0・グループの上限0・適切回数0）: 入っているセルはどれも超過＝全部に印。
     *  上限1以上の超過や不足には出さない。実データの件数を出す。 */
    @Test fun zeroAllowCellsMarkEveryCellOnlyWhenTheAllowanceIsZero() {
        val p = cachedProblem(st); val s = st.schedule.toIntArray2D()
        val z = zeroAllowCells(p, s)
        for ((key, cls) in z) {
            val i = VioKey.first(key)!!; val j = VioKey.second(key)!!; val k = s[i][j]
            when (familyOfVioClass(cls)) {
                "covO" -> { val n = (0 until p.S).count { s[it][j] == k }; assertEquals(key, n, p.covOCell(k, j, n)) }
                "c41" -> assertTrue(key, p.cons41.any { it.u == 0 && it.shiftIdx == k && it.groupIdx == p.sgrp[i] })
                "c41s" -> assertTrue(key, p.cons41s.any { it.u == 0 && it.shiftIdx == k && it.groupIdx == p.ssk[i] })
                "apt" -> assertEquals(key, 0, p.apt[i][k])
                else -> error(cls)
            }
        }
        val byFam = z.values.groupingBy { familyOfVioClass(it) }.eachCount()
        println("許容0の印 ${z.size}/${st.staffCount * st.dayCount} セル $byFam")
        assertTrue("盤面の3割を超えない", z.size * 10 <= st.staffCount * st.dayCount * 3)

        // 合成: グループの上限0 を足すと、その日そのシフトのグループ員のセル全部に角の印（チップ OFF で消える）。
        val i0 = 0; val j0 = 0; val k0 = s[i0][j0]
        val g = p.sgrp[i0]
        val st2 = st.copy(cons41 = listOf(com.magi.app.model.C41Row(st.groups[g].kigou, st.shifts[k0].kigou, "0", "0")))
        val p2 = cachedProblem(st2)
        val z2 = zeroAllowCells(p2, s)
        val members = (0 until p2.S).filter { p2.sgrp[it] == g && s[it][j0] == k0 }
        for (x in members) assertEquals("vio-c410", z2[VioKey.cell(x, j0)])
        val ui2 = ui.copy(zeroAllowCells = z2, zeroCapCells = emptySet())
        val key0 = VioKey.cell(i0, j0)
        assertTrue("vio-c410" in displayCellClasses(ui2, key0, emptySet()))
        val off = MagiViewState(ui2, allVioBucketKeys - bucketOfFamily("c41")!!)
        assertTrue(off.cellVio[i0][j0] != "vio-c410")
    }
}
