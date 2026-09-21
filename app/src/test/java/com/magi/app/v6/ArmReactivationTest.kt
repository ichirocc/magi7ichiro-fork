package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.580.0/backlog#26] `V6HotfixPasses.targetFamiliesRemain` の検証。既定OFFの専用修復腕
 * （C2Polish/C42FlowPolish/C1成分修復/C3nMarginLnsPolish/CountChainPolish）を「対象違反が残っている
 * 局面でだけ」再活性化するための判定材料が、正式チェッカーの breakdown 生値を正しく見ることを固定する
 * （腕自身の自己申告カウンタに頼らないという backlog#26 の要件そのもの）。
 */
class ArmReactivationTest {
    /** 3 職員×31日。X は毎日1人(need1=1)、A の X 上限超過1件のみを持つ（`CountChainPolishTest.chainState` と同型）。 */
    private fun highOnlyState(): MagiState {
        val shifts = listOf(Shift("休み", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "1", ""), Shift("Y", "Y", "", ""))
        val groups = listOf(Group("G", "G"))
        val t = 31
        val a = IntArray(t) { 2 }; val b = IntArray(t) { 2 }; val c = IntArray(t) { 2 }
        for (j in 0 until t) { c[j] = 1 }
        for (j in listOf(0, 10, 20)) { a[j] = 1; c[j] = 2 }
        for (j in listOf(5, 15)) { b[j] = 1; c[j] = 2 }
        return MagiState(
            startDate = "2026-10-01", endDate = "2026-10-31",
            shifts = shifts, groups = groups, staff = listOf(Staff("A", 0), Staff("B", 0), Staff("C", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
            schedule = listOf(a.toList(), b.toList(), c.toList()),
            wishes = emptyMap(), staffRange = mapOf("0,1" to Range("", "2"), "1,1" to Range("", "2")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun targetFamiliesRemain_trueWhenTheFamilyHasNonZeroBreakdown() {
        val st = highOnlyState()
        val sched = st.schedule.toIntArray2D()
        assertTrue("A の high 違反1件が前提", V6HotfixPasses.targetFamiliesRemain(st, sched, false, "high"))
    }

    @Test
    fun targetFamiliesRemain_falseWhenTheFamilyIsAbsent() {
        val st = highOnlyState()
        val sched = st.schedule.toIntArray2D()
        assertFalse("cons2/cons3n等は未定義なので0件", V6HotfixPasses.targetFamiliesRemain(st, sched, false, "c2"))
        assertFalse(V6HotfixPasses.targetFamiliesRemain(st, sched, false, "c3n"))
        assertFalse(V6HotfixPasses.targetFamiliesRemain(st, sched, false, "covU"))
    }

    @Test
    fun targetFamiliesRemain_isOrAcrossMultipleFamilies() {
        val st = highOnlyState()
        val sched = st.schedule.toIntArray2D()
        assertTrue("high と covU の OR＝1件でもあれば true", V6HotfixPasses.targetFamiliesRemain(st, sched, false, "covU", "high"))
        assertFalse("両方0件なら false", V6HotfixPasses.targetFamiliesRemain(st, sched, false, "covU", "c2"))
    }

    @Test
    fun targetFamiliesRemain_falseOnceTheViolationIsResolved() {
        val st = highOnlyState()
        val fixed = CountChainPolish.applyCountChainPolish(st, st.schedule.toIntArray2D()).newSchedule
        assertFalse("CountChainPolish適用後はhigh=0のはず", V6HotfixPasses.targetFamiliesRemain(st, fixed, false, "high"))
    }
}
