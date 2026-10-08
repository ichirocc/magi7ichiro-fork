package com.magi.app.v6

import com.magi.app.model.ExtWish
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外部レビュー DATA-01〜03・NEW-04・NEW-05・OLD-01〜03（2026-10-08）。 */
class ReviewExtWishFollowTest {
    // 0=休 1=A 2=D。職員 A,B,C（同じ群）。B に「D 禁止」（1〜2 日）。
    private fun state(ext: List<ExtWish> = listOf(ExtWish(1, listOf("2026-07-01", "2026-07-02"), listOf("D")))) = MagiState(
        startDate = "2026-07-01", endDate = "2026-07-03",
        shifts = listOf(Shift("休み", "休", "", "", ShiftRole.Rest), Shift("A", "A", "1", ""), Shift("D", "D", "1", "")),
        groups = listOf(Group("G0", "G0")), staff = listOf(Staff("A", 0), Staff("B", 0), Staff("C", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(listOf(1, 0, 0), listOf(2, 2, 0), listOf(0, 1, 1)),
        wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(), extWishes = ext,
    )
    private fun grid(st: MagiState) = st.schedule.map { it.toIntArray() }.toTypedArray()

    @Test fun moveStaffKeepsTheExtWishOnTheSamePerson() {           // DATA-01
        val st = state()
        val r = Ws1Ops.moveStaff(st, grid(st), 1, -1)
        assertEquals("B", r.state.staff[0].name)
        assertEquals(listOf(0), r.state.extWishes.map { it.staff })
    }

    @Test fun removeStaffDropsItsExtWishAndShiftsTheRest() {        // DATA-02
        val st = state(listOf(ExtWish(1, listOf("2026-07-01"), listOf("D")), ExtWish(2, listOf("2026-07-02"), listOf("A"))))
        val r = Ws1Ops.removeStaff(st, grid(st), 1)
        assertEquals(listOf(1), r.state.extWishes.map { it.staff })
        assertEquals(listOf("A"), r.state.extWishes[0].shifts)
    }

    @Test fun renamingAShiftFollowsIntoExtWishesAndRemovingDropsIt() {   // DATA-03
        val st = state()
        val renamed = Ws1Ops.editShift(st, 2, "D", "NEW", "1", "", false)
        assertEquals(listOf("NEW"), renamed.extWishes[0].shifts)
        val removed = Ws1Ops.removeShift(st, grid(st), 2).state
        assertTrue(removed.extWishes.isEmpty())
    }

    @Test fun shrinkingThePeriodDropsDaysOutside() {
        val st = state()
        val r = Ws1Ops.resizeDays(st, grid(st), 1)
        assertEquals(listOf("2026-07-01"), r.state.extWishes[0].days)
    }

    @Test fun unionWithExistingBansMustLeaveAPlaceableShift() {     // NEW-04
        val st = state(listOf(ExtWish(1, listOf("2026-07-01"), listOf("休", "A"))))   // 1 日は D しか置けない
        assertNull(ExtWishRules.sanitize(st, ExtWish(1, listOf("2026-07-01"), listOf("D"))).saved)
        assertTrue(ExtWishRules.sanitize(st, ExtWish(1, listOf("2026-07-02"), listOf("D"))).saved != null)
    }

    @Test fun branchingFirstEntryIsUsedForTheFirstExpansion() {      // NEW-05
        val st = com.magi.app.model.StateParser.parse(javaClass.getResource("/sept2026_state.json")!!.readText())!!
        val one = C1EjectionChainPolish.Stats(); val two = C1EjectionChainPolish.Stats()
        C1EjectionChainPolish.apply(st, st.schedule.toIntArray2D(), C1EjectionChainPolish.Config(maxDepth = 2, branching = intArrayOf(1), maxEvaluations = 60_000L), stats = one)
        C1EjectionChainPolish.apply(st, st.schedule.toIntArray2D(), C1EjectionChainPolish.Config(maxDepth = 2, branching = intArrayOf(2), maxEvaluations = 60_000L), stats = two)
        assertTrue("分岐 2 なら 1 より展開が増える (${one.chainsTried} vs ${two.chainsTried})", two.chainsTried > one.chainsTried)
    }

    @Test fun fingerprintSeparatesNeedDay1FromNeedDay2AndSeesExtWishes() {   // OLD-01
        val a = state(emptyList()).copy(needDay1 = mapOf("1,0" to "1"))
        val b = state(emptyList()).copy(needDay2 = mapOf("1,0" to "1"))
        assertNotEquals(StateFingerprint.of(a), StateFingerprint.of(b))
        assertNotEquals(StateFingerprint.of(state(emptyList())), StateFingerprint.of(state()))
    }

    @Test fun csvImportsRefuseToGuessBetweenSameNameStaff() {        // OLD-02/03
        val st = state(emptyList()).copy(staff = listOf(Staff("同名", 0), Staff("同名", 0), Staff("C", 0)), wishes = mapOf("2,0" to 1))
        val w = WishesCsvIO.parse("氏名,日,希望シフト\n同名,1,D\n", st)!!
        assertEquals(1, w.rejected); assertEquals(0, w.accepted)
        assertTrue(w.samples.first().startsWith("同姓同名"))
        val groups2 = st.copy(groups = listOf(Group("G0", "G0"), Group("H", "H")), groupShift = listOf(listOf(1, 1, 1), listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", ""), listOf("", "", "")))
        assertNull(StaffCsvIO.parse("氏名,グループ,スキル\n同名,H,\n", groups2))
        val up = StaffCsvIO.parseUpsert("氏名,グループ,スキル\n同名,H,\n", groups2, grid(groups2))
        assertTrue(up == null || (up.updated == 0 && up.added == 0 && up.ambiguousNames == listOf("同名")))
    }
}
