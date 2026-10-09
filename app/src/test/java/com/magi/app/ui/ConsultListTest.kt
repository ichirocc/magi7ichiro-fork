package com.magi.app.ui

import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [3.645.0/仕様 5.3] 相談してから決める判断＝対象と検討内容を持ち、同じものは 2 度積まない。出力は止めない（行の文言だけ）。 */
class ConsultListTest {
    @Test fun lineOnlyWhenSomethingIsPending() {
        assertNull(consultLine(0))
        assertEquals("未確認事項 2 件（相談中。下の一覧で確認してから配ってください）", consultLine(2))
    }

    @Test fun buildersCarryTheTargetAndTheQuestion() {
        assertEquals(ConsultItem("甲 10/12 の希望「夜」", "取り消すか勤務を変えるか: 禁止の並びに当たる", 0, 11),
            consultWish("甲", "10/12", "夜", "禁止の並びに当たる", 0, 11))
        assertEquals("甲 10/12 の希望", consultWish("甲", "10/12", null, "r", 0, 11).subject)
        assertEquals("だれかを入れる: 甲・乙・丙・丁・戊 ほか1人", consultShortage("10/12", "夜", listOf("甲", "乙", "丙", "丁", "戊", "己")).note)
        assertEquals("入れる人を相談", consultShortage("10/12", "夜", emptyList()).note)
        val row = PreRunRow("「夜」 3日で担当できる人より必要人数が多く、人員不足が合計3人残ります", landing = EditLanding(2, "yr_ws1"))
        assertEquals(row.text, consultPreRun(row).subject)
        assertNull(consultPreRun(row).staff)
    }

    @Test fun sameSubjectAndNoteIsNotAddedTwice() {
        val a = ConsultItem("x", "y")
        val l = consultAdd(emptyList(), a)!!
        assertEquals(listOf(a), l)
        assertNull(consultAdd(l, ConsultItem("x", "y")))
        assertEquals(2, consultAdd(l, ConsultItem("x", "z"))!!.size)
    }

    @Test fun chainAndFixConsultsSummarizeTheChange() {
        val s = FixSuggestion(FixKind.CHAIN, listOf(FixCell(0, 2, 2)), "（玉突き）10/3 の「夜」を複数人の入替で埋める", -1, 0, listOf("covU" to -1))
        val p = chainFixPreview(s, arrayOf(intArrayOf(0, 0, 1)), listOf("甲"), listOf("休", "日", "夜"), "2026-10-01")
        assertEquals(ConsultItem(s.label, "甲 10/3 日 → 夜（必須違反: 1件減る）"), consultChain(p))
        assertEquals(ConsultItem(s.label, "必須違反: 1件減る"), consultFix(s))
    }
}
