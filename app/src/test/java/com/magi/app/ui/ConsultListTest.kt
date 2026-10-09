package com.magi.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(ConsultItem("甲 10/12 の希望「夜」", "取り消すか勤務を変えるか: 禁止の並びに当たる", 0, 11, staffName = "甲"),
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
        assertEquals(true, isConsulted(l, ConsultItem("x", "y")))
        assertEquals(false, isConsulted(l, ConsultItem("x", "z")))
    }

    @Test fun chainAndFixConsultsSummarizeTheChange() {
        val s = FixSuggestion(FixKind.CHAIN, listOf(FixCell(0, 2, 2)), "（玉突き）10/3 の「夜」を複数人の入替で埋める", -1, 0, listOf("covU" to -1))
        val p = chainFixPreview(s, arrayOf(intArrayOf(0, 0, 1)), listOf("甲"), listOf("休", "日", "夜"), "2026-10-01")
        assertEquals(ConsultItem(s.label, "甲 10/3 日 → 夜（必須違反: 1件減る）"), consultChain(p))
        assertEquals(ConsultItem(s.label, "必須違反: 1件減る"), consultFix(s))
    }

    /** [3.646.0 B01/B02] 対象は氏名と実日付で引き直す＝職員の並び替え・削除、月の移動のあとに別のセルを開かない。 */
    @Test fun targetIsResolvedByNameAndDate() {
        val c = consultWish("乙", "10/12", "夜", "r", 1, 11, "2026-10-12")
        assertEquals(1 to 11, consultCell(c, "2026-10-01", listOf("甲", "乙"), 31))
        assertEquals(0 to 11, consultCell(c, "2026-10-01", listOf("乙", "甲"), 31))          // 並び替え: 氏名で引き直す
        assertNull(consultCell(c, "2026-10-01", listOf("甲"), 31))                            // 削除: 開けない
        assertNull(consultCell(c, "2026-11-01", listOf("甲", "乙"), 30))                      // 月の移動: 日付が期間の外
        assertNull(consultCell(c, "2026-10-01", listOf("乙", "乙"), 31))                      // [3.650.0] 同名が複数で並びが不明＝開かない
        assertEquals("同じ名前の職員が複数いて、どの人か決められません（乙）", consultTargetNote(c, "2026-10-01", listOf("乙", "甲", "乙"), listOf(), 31))
        assertEquals("いまの職員一覧にいません（乙）", consultTargetNote(c, "2026-10-01", listOf("甲"), listOf(), 31))
        assertEquals("いまの期間にない日です（10/12）", consultTargetNote(c, "2026-11-01", listOf("甲", "乙"), listOf(), 30))
        assertNull(consultTargetNote(c, "2026-10-01", listOf("甲", "乙"), listOf(), 31))
        assertNull(consultTargetNote(consultShortage("10/12", "夜", emptyList()), "2026-10-01", listOf("甲"), listOf(), 31))   // 対象を持たない相談は何も言わない
    }

    @Test fun legacyItemsWithoutNameOrDateFallBackToTheirIndexes() {
        val c = ConsultItem("x", "y", 1, 5)
        assertEquals(1 to 5, consultCell(c, "2026-10-01", listOf("甲", "乙"), 31))
        assertNull(consultCell(c, "2026-10-01", listOf("甲"), 31))
        assertNull(consultCell(c, "2026-10-01", listOf("甲", "乙"), 5))
    }

    /** [3.646.0 L02] 入れ替えの相談は枠（日付・記号）か案そのものを持ち、今の勤務表で見直せる。 */
    @Test fun chainTargetIsResolvedByDateAndSymbol() {
        val t = ChainTarget("2026-10-03", "夜", "lbl")
        assertEquals(2 to 2, consultChainTarget(t, "2026-10-01", listOf("休", "日", "夜"), 31))
        assertEquals(2 to 0, consultChainTarget(t, "2026-10-01", listOf("夜", "日", "休"), 31))   // シフトの並び替え: 記号で引き直す
        assertNull(consultChainTarget(t, "2026-11-01", listOf("休", "日", "夜"), 30))
        assertNull(consultChainTarget(t, "2026-10-01", listOf("休", "日"), 31))
        assertTrue(consultChainResumable(ChainTarget(null, null, "lbl", suggestion = FixSuggestion(FixKind.CHAIN, listOf(FixCell(0, 2, 2)), "lbl", -1, 0, emptyList())), "2026-10-01", listOf("休"), 31))
        assertFalse(consultChainResumable(t, "2026-11-01", listOf("休", "日", "夜"), 30))
        val c = ConsultItem("lbl", "n", chain = t)
        assertEquals("いまの期間・シフトにない枠です（10/3 の「夜」）", consultTargetNote(c, "2026-11-01", listOf("甲"), listOf("休", "日", "夜"), 30))
        assertNull(consultTargetNote(c, "2026-10-01", listOf("甲"), listOf("休", "日", "夜"), 31))
        assertEquals("2026-10-12", isoDate("2026-10-01", 11))
        assertEquals(11, dayIndexOf("2026-10-01", "2026-10-12", 31))
        assertNull(dayIndexOf("2026-10-01", "2026-11-12", 31))
    }

    /** [3.650.0/外部レビュー] 同じ名前の職員は、積んだときと並びが同じときだけ位置で開く。 */
    @Test fun sameNamedStaffOpenOnlyWhileTheRosterIsUnchanged() {
        val names = listOf("佐藤", "甲", "佐藤")
        val c = consultWish("佐藤", "10/12", "夜", "r", 2, 11, "2026-10-12").copy(rosterKey = rosterKeyOf(names))
        assertEquals(2 to 11, consultCell(c, "2026-10-01", names, 31))
        assertNull(consultCell(c, "2026-10-01", listOf("佐藤", "佐藤", "甲"), 31))
        assertEquals(0 to 11, consultCell(c, "2026-10-01", listOf("佐藤", "甲"), 31))   // 1 人になれば氏名で引ける
        assertTrue(rosterKeyOf(emptyList()) != 0)
    }

    /** [3.650.0/外部レビュー] 積んだ案は出したときの盤面と設定の指紋を持ち、今と違えば当てない（枠を持つ相談は探し直すので関係しない）。 */
    @Test fun aSavedSuggestionIsStaleOnceTheBoardOrSettingsChange() {
        val sug = FixSuggestion(FixKind.CHAIN, listOf(FixCell(0, 2, 2), FixCell(1, 2, 0)), "lbl", -1, 0, emptyList())
        val t = ChainTarget(null, null, "lbl", suggestion = sug, boardKey = 11L, stateKey = 22L)
        assertFalse(consultChainStale(t, 11L, 22L))
        assertTrue(consultChainStale(t, 12L, 22L))
        assertTrue(consultChainStale(t, 11L, 23L))
        assertTrue("指紋の無い案は古いとみなす", consultChainStale(t.copy(boardKey = 0L), 11L, 22L))
        assertFalse("枠を持つ相談（案なし）は探し直す", consultChainStale(ChainTarget("2026-10-03", "夜", "lbl"), 1L, 2L))
    }
}
