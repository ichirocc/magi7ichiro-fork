package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.toIntArray2D
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.V6PortAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MagiViewStateTest {
    private val fixtures = listOf(
        "/golden_state.json", "/sample_state_v6.json", "/blocked_covu_state.json",
        "/full_coverage_state.json", "/sept2026_state.json",
    )

    private fun load(path: String): MagiState =
        StateParser.parse(javaClass.getResourceAsStream(path)!!.bufferedReader().readText())!!

    /** 実機の `makeUi` と同じ源（チェッカー＋診断）から、この層が読む欄だけを詰める。 */
    private fun viewStateOf(st: MagiState): MagiViewState {
        val sched = st.schedule.toIntArray2D()
        val rep = UnifiedViolationChecker.check(st, sched)
        val v6 = V6PortAnalyzer.analyze(st, sched, rep)
        return MagiViewState(
            UiState(
                staff = st.staffCount, days = st.dayCount, shifts = st.shiftCount,
                schedule = st.schedule, v6 = v6, breakdown = rep.breakdown,
                violationCells = rep.violations, violationCellFamilies = rep.cellFamilies,
                needViolations = rep.needViolations, needFamilies = rep.needFamilies,
                countViolations = rep.countViolations, countFamilies = rep.countFamilies,
            ),
        )
    }

    /**
     * 3.557.0 の不具合を構造的に締める。日ヘッダの「▲N」（診断 `dayRisks.surplus`）と
     * シフト集計の▲（チェッカー `needViolations` の covO）が食い違ってはいけない。
     * 旧実装ではこの一致が崩れており、この表明は修正前なら 5 件すべてで落ちる。
     */
    @Test
    fun surplusAndShortageAgreeBetweenCheckerAndDiagnosis() {
        for (path in fixtures) {
            val vs = viewStateOf(load(path))
            for (d in vs.days) {
                assertEquals(
                    "$path ${d.day + 1}日: チェッカーが過剰と言う日と診断の▲N は一致する",
                    d.hasSurplus, d.surplus > 0,
                )
                assertEquals(
                    "$path ${d.day + 1}日: 不足も同じ",
                    d.hasShortage, d.shortage > 0,
                )
            }
        }
    }

    /** 族名の完全一致（新）と部分文字列一致（旧）は、現行 20 族では同じ答えを出す。 */
    @Test
    fun exactFamilyMatchAgreesWithLegacySubstringRule() {
        val classes = MirrorKeys.all.map { "vio-$it" } + listOf("vio-aptLow", "vio-aptHigh")
        for (cls in classes) {
            val legacy = MirrorKeys.hard.any { cls.contains(it) }
            assertEquals("$cls の必須判定は旧規則と一致", legacy, isHardCellViolation(cls))
        }
        assertTrue("必須族は必須", isHardCellViolation("vio-covU"))
        assertTrue("紛らわしい c3mn を c3n と読み違えない", !isHardCellViolation("vio-c3mn"))
        assertTrue("過剰は必須ではない", !isHardCellViolation("vio-covO"))
    }

    @Test
    fun vioKeyRoundTrips() {
        assertEquals(3, VioKey.first(VioKey.cell(3, 7)))
        assertEquals(7, VioKey.dayOf(VioKey.cell(3, 7)))
        assertEquals(2, VioKey.first(VioKey.need(2, 20)))
        assertEquals(20, VioKey.dayOf(VioKey.need(2, 20)))
        assertEquals(null, VioKey.dayOf("covU"))
    }

    /** 盤面から数えるだけの値は、素朴な数え上げと一致する。 */
    @Test
    fun countsMatchNaiveTally() {
        for (path in fixtures) {
            val st = load(path)
            val vs = viewStateOf(st)
            for (i in 0 until st.staffCount) for (k in 0 until st.shiftCount) {
                assertEquals(
                    "$path 職員$i シフト$k",
                    st.schedule[i].count { it == k }, vs.counts.perStaff[i][k],
                )
            }
            for (j in 0 until st.dayCount) for (k in 0 until st.shiftCount) {
                assertEquals(
                    "$path ${j + 1}日 シフト$k",
                    (0 until st.staffCount).count { st.schedule[it][j] == k }, vs.counts.perDay[j][k],
                )
            }
        }
    }

    /** 下線の段階は「必須があれば実線、無くて要調整があれば破線」。両方0なら引かない。 */
    @Test
    fun dayMarkFollowsSeverity() {
        fun day(hard: Int, soft: Int) =
            DayStaffing(0, 0, 0, false, false, hard, soft).mark
        assertEquals(DayMark.Hard, day(1, 5))
        assertEquals(DayMark.Hard, day(1, 0))
        assertEquals(DayMark.Soft, day(0, 1))
        assertEquals(DayMark.None, day(0, 0))
    }

    /** 違反のある日は必ず印が付く（付かない日が1つでもあれば、それは 3.557.0 と同型の取りこぼし）。 */
    @Test
    fun everyDayWithAViolationGetsAMark() {
        for (path in fixtures) {
            val vs = viewStateOf(load(path))
            for (d in vs.days) {
                if (d.hasShortage || d.hasSurplus) {
                    assertTrue("$path ${d.day + 1}日は人員の違反があるのに印が無い", d.mark != DayMark.None)
                }
            }
        }
    }

    /** 違反ナビが巡回する日と、日ヘッダに印が付く日は同じ集合（片方だけ抜けると「印の無い日へ飛ぶ」）。 */
    @Test
    fun navigatorDaysMatchMarkedDays() {
        for (path in fixtures) {
            val vs = viewStateOf(load(path))
            val marked = vs.days.filter { it.mark != DayMark.None }.map { it.day }
            assertEquals(path, marked, vs.violationDays)
        }
    }

    /** 同じ重みの族に隠れた covO/covU を、被覆の問い合わせが取りこぼさない。 */
    @Test
    fun coverageLookupSeesClassesHiddenBehindEqualWeightFamilies() {
        val ui = UiState(
            staff = 1, days = 1, shifts = 2, schedule = listOf(listOf(0)),
            needViolations = mapOf("1,0" to "vio-c41s"),
            needFamilies = mapOf("1,0" to listOf("vio-c41s", "vio-covO")),
        )
        assertEquals("最重1クラスだけ見ると c41s に隠れて過剰が消える", "vio-covO", coverageVioAt(ui, 1, 0, allVioBucketKeys))
        assertTrue("過剰のある日は印が付く", MagiViewState(ui).days[0].hasSurplus)
    }

    /** 違反フィルタのチップ件数も同じ（needViolations だけを数えると c41s に隠れた過剰が「人員 0」になる）。 */
    @Test
    fun bucketChipCountsSeeClassesHiddenBehindEqualWeightFamilies() {
        val ui = UiState(
            staff = 1, days = 1, shifts = 2, schedule = listOf(listOf(0)),
            needViolations = mapOf("1,0" to "vio-c41s"),
            needFamilies = mapOf("1,0" to listOf("vio-c41s", "vio-covO")),
        )
        val counts = vioBucketLocCounts(ui)
        assertEquals("c41s に隠れた過剰も人員に数える", 1, counts["need"])
        assertEquals("グループルールは 1 箇所のまま", 1, counts["group"])
        val fallback = vioBucketLocCounts(ui.copy(needFamilies = emptyMap()))
        assertEquals("families 未充填の経路は最重1クラスへフォールバック", null, fallback["need"])
        assertEquals(1, fallback["group"])
    }

    /** 最終週が 7 日に満たない月でも、右端まで送れば最終週になる（左端の日だけだと最後から 2 番目で止まる）。 */
    @Test
    fun currentWeekReachesAPartialLastWeekAtTheRightEdge() {
        val weeks = listOf((0..6).toList(), (7..13).toList(), (14..20).toList(), (21..27).toList(), (28..30).toList())
        assertEquals(0, currentWeekIndex(weeks, leftDay = 0, atEnd = false))
        assertEquals(2, currentWeekIndex(weeks, leftDay = 14, atEnd = false))
        assertEquals("31日・7日表示の右端は左端が 24日目", 3, currentWeekIndex(weeks, leftDay = 24, atEnd = false))
        assertEquals(4, currentWeekIndex(weeks, leftDay = 24, atEnd = true))
        assertEquals(0, currentWeekIndex(listOf((0..6).toList()), leftDay = 0, atEnd = true))
        assertEquals(0, currentWeekIndex(emptyList(), leftDay = 3, atEnd = false))
    }

    /** 色の対象ごとに別の段（違う対象の変更は 1 つの「元に戻す」にまとめない）。 */
    @Test
    fun colourUndoKeyNamesTheChangedTargetsOnly() {
        val base = mapOf("D" to "#ff0000", "__vio__" to "#00ff00")
        assertEquals("D", DisplayOnlyUndo.colorKey(base, base + ("D" to "#0000ff")))
        assertEquals("__vio__", DisplayOnlyUndo.colorKey(base, base - "__vio__"))
        assertEquals("N", DisplayOnlyUndo.colorKey(base, base + ("N" to "#123456")))
        assertTrue("D の続けての変更は同じ目印", DisplayOnlyUndo.colorKey(base, base + ("D" to "#111111")) ==
            DisplayOnlyUndo.colorKey(base + ("D" to "#111111"), base + ("D" to "#222222")))
        assertFalse("D と違反色は別の目印", DisplayOnlyUndo.colorKey(base, base + ("D" to "#111111")) ==
            DisplayOnlyUndo.colorKey(base, base + ("__vio__" to "#222222")))
    }

    /** 差が表示色だけの段は、戻しても結果・他の案・直し方を外さない（盤面か設定が違えば通常の元に戻す）。 */
    @Test
    fun colourOnlyDifferenceIsDetectedAgainstBoardAndSettings() {
        val st = load("/golden_state.json")
        val sched = st.schedule.toIntArray2D()
        val recoloured = st.copy(shiftColors = st.shiftColors + ("__vio__" to "#123456"))
        assertTrue(DisplayOnlyUndo.differsOnlyInColors(st, sched, recoloured, sched.map { it.clone() }.toTypedArray()))
        val edited = sched.map { it.clone() }.toTypedArray().also { it[0][0] = (it[0][0] + 1) % st.shiftCount }
        assertFalse("盤面が違う", DisplayOnlyUndo.differsOnlyInColors(st, sched, recoloured, edited))
        assertFalse("設定が違う", DisplayOnlyUndo.differsOnlyInColors(st, sched, recoloured.copy(use2Patterns = !st.use2Patterns), sched))
    }
}
