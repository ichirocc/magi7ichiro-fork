package com.magi.app.ui

import com.magi.app.model.C3Row
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 旧 `MagiViewModel` の問い合わせ（重複判定・行の生値・族一覧）が画面から消えたぶん、ここで固定する。 */
class MagiConstraintsViewTest {
    private fun load(path: String): MagiState =
        StateParser.parse(javaClass.getResourceAsStream(path)!!.bufferedReader().readText())!!

    private fun withSeq(st: MagiState, family: String, rows: List<List<String>>): MagiState = when (family) {
        "cons3" -> st.copy(cons3 = rows.map { C3Row(it) })
        "cons3n" -> st.copy(cons3n = rows.map { C3Row(it) })
        "cons3m" -> st.copy(cons3m = rows.map { C3Row(it) })
        else -> st.copy(cons3mn = rows.map { C3Row(it) })
    }

    @Test
    fun duplicateIsDetectedAcrossFamilies() {
        val base = load("/golden_state.json")
        val st = withSeq(withSeq(base, "cons3n", listOf(listOf("A", "B"))), "cons3mn", emptyList())
        val cv = constraintsViewOf(st)
        // HARD の禁止と SOFT の回避へ同じ並びを二重掛けしても評価は禁止側が支配する＝族をまたいで知らせる。
        assertEquals("禁止の並び", cv.duplicateOf("cons3mn", listOf("A", "B")))
        assertEquals("同じ族でも重複は重複", "禁止の並び", cv.duplicateOf("cons3n", listOf("A", "B")))
        assertNull("自分自身は除く（変更時）", cv.duplicateOf("cons3n", listOf("A", "B"), excludeIndex = 0))
        assertNull("別の並びは通る", cv.duplicateOf("cons3n", listOf("A", "C")))
        assertNull("空は判定しない", cv.duplicateOf("cons3n", listOf("", "B")))
    }

    /** 正規化は「先頭から最初の空白まで・最大5」。後ろの空白で切れるので A,"",B は A と同じ並び。 */
    @Test
    fun patternIsNormalizedBeforeComparing() {
        val st = withSeq(load("/golden_state.json"), "cons3n", listOf(listOf("A", "B", "", "X")))
        val cv = constraintsViewOf(st)
        assertEquals(listOf("A", "B"), normalizeSeq(listOf(" A ", "B", "", "X")))
        assertEquals("空白以降は無いものとして比べる", "禁止の並び", cv.duplicateOf("cons3m", listOf("A", "B", "", "Z")))
    }

    @Test
    fun rowValuesMatchTheDialogInputOrder() {
        val st = load("/golden_state.json")
        val cv = constraintsViewOf(st)
        st.cons1.firstOrNull()?.let {
            assertEquals(listOf(it.day1, it.shiftKigou, it.day2), cv.rowValues("cons1", 0))
        }
        st.cons41.firstOrNull()?.let {
            assertEquals(listOf(it.groupKigou, it.shiftKigou, it.l, it.u), cv.rowValues("cons41", 0))
        }
        st.cons42.firstOrNull()?.let {
            assertEquals(listOf(it.g1Kigou, it.s1Kigou, it.g2Kigou, it.s2Kigou), cv.rowValues("cons42", 0))
        }
        assertNull("範囲外は null", cv.rowValues("cons1", 9999))
        assertNull("知らない族は null", cv.rowValues("consX", 0))
    }

    @Test
    fun familyKeysAndRowCountsFollowTheState() {
        val st = load("/golden_state.json")
        val cv = constraintsViewOf(st)
        assertEquals(
            listOf("cons1", "cons2", "cons3", "cons3n", "cons3m", "cons3mn", "cons3w", "cons41", "cons42"),
            cv.families.map { it.key },
        )
        assertEquals(listOf("cons41s", "cons42s"), cv.skillFamilies.map { it.key })
        assertEquals(st.cons3n.size, cv.families.first { it.key == "cons3n" }.rows.size)
        assertEquals(st.shifts.map { it.kigou }, cv.shiftKigou)
        assertEquals(st.groups.map { it.kigou }, cv.groupKigou)
    }

    @Test
    fun emptyStateGivesEmptyView() {
        val cv = constraintsViewOf(null)
        assertEquals(emptyList<ConstraintFamilyView>(), cv.families)
        assertNull(cv.rowValues("cons1", 0))
        assertNull(cv.duplicateOf("cons3n", listOf("A", "B")))
    }
}
