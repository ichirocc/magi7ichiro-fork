package com.magi.app.ui

import com.magi.app.model.C3Row
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.model.C1Row
import com.magi.app.model.C42Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** 途中の空欄は正規化で黙って切れる（A,"",B は A だけの並び）＝ダイアログと VM はこの判定で断る。 */
    @Test
    fun gapInsideThePatternIsDetected() {
        assertTrue(seqHasGap(listOf("A", "", "B", "", "")))
        assertTrue("1番目が空で後ろがある", seqHasGap(listOf("", "B", "", "", "")))
        assertFalse("末尾の空欄は切れ目ではない", seqHasGap(listOf("A", "B", "", "", "")))
        assertFalse("1つだけの並びは受け付ける", seqHasGap(listOf("A", "", "", "", "")))
        assertFalse(seqHasGap(listOf("", "", "", "", "")))
    }

    /** 行は1行ずつ別に数える（MirrorCore.checkC3Family）＝同じ起点の行を「どれか」で束ねる見出しにしない。 */
    @Test
    fun startHeadingDoesNotClaimAnyOf() {
        for (fam in listOf("cons3", "cons3n", "cons3m", "cons3mn")) {
            val h = seqStartHeading(fam, "Dﾃ")
            assertTrue(h, h.startsWith("【Dﾃ の次の日"))
            assertFalse(h, "どれか" in h)
        }
    }

    /** 期間の制約: Problem が捨てる 0・期間より長い窓・必ず違反になる 必要数>何日間 を入口で断る。空欄は理由を出さない。 */
    @Test
    fun cons1InputRejectsValuesTheEngineDropsOrAlwaysFails() {
        assertNull(cons1InputError("5", "1", 30))
        assertNull("何日間=期間の日数は通す", cons1InputError("30", "30", 30))
        assertNull("空欄は理由なし（確定だけ止まる）", cons1InputError("", "", 30))
        assertNotNull("0日", cons1InputError("0", "1", 30))
        assertNotNull("期間より長い", cons1InputError("40", "1", 30))
        assertNotNull("必要数0", cons1InputError("5", "0", 30))
        assertNotNull("必要数>何日間", cons1InputError("3", "5", 30))
        assertNull("勤務表が無い(0日)ときは上限を見ない", cons1InputError("40", "1", 0))
        assertNotNull(cons2InputError("0"))
        assertNull(cons2InputError("3"))
        assertNull(cons2InputError(""))
        assertTrue("レンジの両方空欄は評価されない", rangeBothBlank(" ", ""))
        assertFalse(rangeBothBlank("", "2"))
    }

    /** 同じ値の行は違反が2倍に数えられる＝入口で止める。数値の 05 と 5 は同じ、自分自身（変更時）は除く。 */
    @Test
    fun duplicateRowIsDetectedWithinTheFamily() {
        val st = load("/golden_state.json").copy(
            cons1 = listOf(C1Row("5", "休", "1")),
            cons42 = listOf(C42Row("A", "B", "Dﾃ", "Dﾃ")),
        )
        val cv = constraintsViewOf(st)
        assertTrue(cv.rowDuplicate("cons1", listOf("5", "休", "1")))
        assertTrue("数値の前ゼロ・前後の空白は同じとみなす", cv.rowDuplicate("cons1", listOf(" 05", "休", "1 ")))
        assertFalse("自分自身は除く（変更時）", cv.rowDuplicate("cons1", listOf("5", "休", "1"), excludeIndex = 0))
        assertFalse("値が違えば通る", cv.rowDuplicate("cons1", listOf("5", "休", "2")))
        assertTrue("ペア禁止は画面の入力順（g1,s1,g2,s2）で比べる", cv.rowDuplicate("cons42", listOf("A", "Dﾃ", "B", "Dﾃ")))
        assertFalse("別の族とは比べない", cv.rowDuplicate("cons2", listOf("5", "休", "1")))
        assertEquals(st.dayCount, cv.dayCount)
    }
}
