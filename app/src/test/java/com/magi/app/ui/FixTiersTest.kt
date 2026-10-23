package com.magi.app.ui

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 12/16 の Dﾃ 過剰と同じ形の盤面で、入り口の 1 セルの案（Tier 1）と段の分け方を確かめる。 */
class FixTiersTest {
    companion object {
        const val REST = 0; const val D = 1

        /** 金沢（休は下限＝上限 2 回、D の 2 連続は禁止）・山本・佐藤。2 日目（day1）の D が 2 人で適正 1 人を超える。 */
        fun board12x16(): MagiState = MagiState(
            startDate = "2026-12-15", endDate = "2026-12-18",
            shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("D", "D", "1", "1")),
            groups = listOf(Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2")),
            staff = listOf(Staff("金沢", 0), Staff("山本", 1), Staff("佐藤", 2)), use2Patterns = true,
            groupShift = listOf(listOf(1, 1), listOf(1, 1), listOf(1, 1)), groupShiftApt = listOf(listOf("", ""), listOf("", ""), listOf("", "")),
            schedule = listOf(listOf(D, D, REST, REST), listOf(REST, D, REST, D), listOf(REST, REST, D, REST)),
            wishes = emptyMap(), staffRange = mapOf("0,0" to Range("2", "2")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = listOf(C3Row(listOf("D", "D"))),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        fun sched(st: MagiState) = Array(st.staffCount) { st.schedule[it].toIntArray() }
        val over = FixTarget(null, D, 1, 1, false, "適正人数")
        val limits: (Int, Int) -> Pair<Int, Int>? = { k, _ -> if (k == D) 1 to 1 else null }
    }

    /** D を担当できない職員が先に並んでいても、後ろの職員の 1 セルの案は出る（旧: 置けるシフトの無いセルで走査ごと止まった）。 */
    @Test fun aStaffWhoCannotTakeTheShiftDoesNotStopTheScan() {
        val st = board12x16().copy(groupShift = listOf(listOf(1, 0), listOf(1, 1), listOf(1, 1)),
            schedule = listOf(listOf(REST, REST, REST, REST), listOf(REST, D, REST, D), listOf(REST, REST, D, REST)))
        val under = FixTarget(null, D, 0, 1, true, "必要人数")
        val moves = entryMoves(st, sched(st), under, REST)
        assertTrue(moves.toString(), moves.any { it.sug.ops == listOf(FixCell(1, 0, D)) })
        assertTrue(moves.none { it.sug.ops.single().staff == 0 })
    }

    @Test fun entryMoveOfAnotherStaffIsTier1AndPinnedStaffIsBlockedWithReason() {
        val st = board12x16(); val s = sched(st)
        val moves = entryMoves(st, s, over, REST)
        val yamamoto = moves.single { it.sug.ops == listOf(FixCell(1, 1, REST)) }
        assertTrue(yamamoto.applicable)
        val kanazawa = moves.single { it.sug.ops.single().staff == 0 }
        assertFalse(kanazawa.applicable)
        assertNotNull(kanazawa.reason)
        val v = classifyTiers(st, s, over, moves, emptyList(), limits)
        assertEquals(listOf(listOf(FixCell(1, 1, REST))), v.of(FixTier.T1).map { it.sug.ops })
        assertTrue(v.of(FixTier.T1).none { it.sug.ops.single().staff == 0 })   // 適用できない 1 セルは案にしない（理由の行だけ）
        assertEquals(1, v.blocked.size)
        assertTrue(v.blocked.single(), v.blocked.single().startsWith("金沢") && "適用できません：" in v.blocked.single())
    }

    @Test fun sameStaffOtherDaySwapIsMoveOnly() {
        val st = board12x16(); val s = sched(st)
        val swap = FixSuggestion(FixKind.SWAP_XDAY, listOf(FixCell(0, 1, REST), FixCell(0, 3, D)), "swap", -1, -1, emptyList())
        val v = classifyTiers(st, s, over, emptyList(), listOf(swap), limits)
        val t = v.items.single()
        assertEquals(FixTier.MOVE_ONLY, t.tier)
        val reason = t.explain!!.extras.single().reason
        assertNotNull(reason)
        assertTrue(reason!!, reason.startsWith("入れないと金沢さんの"))
    }

    @Test fun outOfFrameForbiddenRunOnlyFixIsTier3() {
        val st = board12x16(); val s = sched(st)
        val c3nOnly = FixSuggestion(FixKind.CHANGE, listOf(FixCell(0, 0, REST)), "c3n", -1, 0, listOf("c3n" to -1))
        val v = classifyTiers(st, s, over, emptyList(), listOf(c3nOnly), limits)
        assertEquals(FixTier.T3, v.items.single().tier)
        assertFalse(v.hasMain)
    }

    @Test fun moveOnlyHeadingSaysPeriodGapDoesNotShrink() {
        assertEquals("Dの過剰は減りません（別の日へ移すだけの案 1件）", moveOnlyHeading("D", false, 1))
    }
}
