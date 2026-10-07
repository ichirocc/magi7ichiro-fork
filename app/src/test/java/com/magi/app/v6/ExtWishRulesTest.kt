package com.magi.app.v6

import com.magi.app.model.ExtWish
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仕様「希望シフトの拡張」第 9 節の例。日は 1 始まりの表記、日番号は 0 始まり。 */
class ExtWishRulesTest {
    // 0=休 1=日勤 2=夜 3=準夜。職員 A=0（3 日＝日番号 2 に日勤の希望）、B=1。
    private fun base(ext: List<ExtWish> = emptyList()) = MagiState(
        startDate = "2026-10-01", endDate = "2026-10-05",
        shifts = listOf(Shift("休み", "休", "", "", ShiftRole.Rest), Shift("日勤", "日", "", ""), Shift("夜勤", "夜", "", ""), Shift("準夜", "準", "", "")),
        groups = listOf(Group("G0", "G0")), staff = listOf(Staff("A", 0), Staff("B", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1, 1)), groupShiftApt = listOf(listOf("", "", "", "")),
        schedule = listOf(List(5) { 1 }, List(5) { 0 }),
        wishes = mapOf("0,2" to 1), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(), extWishes = ext,
    )

    private val first = ExtWish(0, listOf("2026-10-01", "2026-10-02", "2026-10-03"), listOf("夜", "準"))

    @Test fun wishDayIsDroppedFromTheSavedDays() {
        val r = ExtWishRules.sanitize(base(), first)
        assertEquals(listOf("2026-10-01", "2026-10-02"), r.saved!!.days)
        assertTrue(ExtWishRules.MSG_WISH_DAY in r.notices)
    }

    @Test fun exampleTableCountsOnlyDay2() {
        val st = base(listOf(ExtWishRules.sanitize(base(), first).saved!!))
        val board = arrayOf(intArrayOf(1, 2, 2, 2, 1), intArrayOf(0, 0, 0, 0, 0))
        val rep = UnifiedViolationChecker.check(st, board)
        assertEquals(listOf("0,1"), rep.extWishCells)
        // 採点の族・重みには入れない。
        assertEquals(UnifiedViolationChecker.check(base(), board).weightedScore, rep.weightedScore, 0.0)
        assertEquals(UnifiedViolationChecker.check(base(), board).breakdown, rep.breakdown)
    }

    @Test fun basicWishOnAnExtDayIsBlocked() {
        val st = base(listOf(ExtWishRules.sanitize(base(), first).saved!!))
        assertEquals(ExtWishRules.MSG_EXT_DAY, ExtWishRules.wishBlockedBy(st, 0, 0))
        assertNull(ExtWishRules.wishBlockedBy(st, 1, 0))
        assertNull(ExtWishRules.wishBlockedBy(st, 0, 3))
    }

    @Test fun secondEntryUnionsOnSharedDayButCountsOnce() {
        val a = ExtWishRules.sanitize(base(), first).saved!!
        val b = ExtWish(0, listOf("2026-10-02", "2026-10-05"), listOf("準"))
        val st = base(listOf(a, b))
        val p = Problem(st)
        for (k in listOf(2, 3)) {
            val board = arrayOf(intArrayOf(1, k, 1, 1, 1), intArrayOf(0, 0, 0, 0, 0))
            assertEquals(1, UnifiedViolationChecker.check(st, board).extWishCells.size)
        }
        assertTrue(!p.extBan.banned(0, 1, 1) && !p.extBan.banned(0, 1, 0))
        assertTrue(p.extBan.banned(0, 4, 3) && !p.extBan.banned(0, 4, 2))
        assertEquals(1, ExtWishRules.delta(p.extBan, 0, 1, 1, 2))
        assertEquals(-1, ExtWishRules.delta(p.extBan, 0, 1, 2, 0))
        assertEquals(0, ExtWishRules.delta(p.extBan, 0, 1, 2, 3))
        assertTrue(!p.mayPlaceAt(0, 1, 2) && p.mayPlaceAt(0, 1, 1))
    }

    @Test fun savingDropsBadPartsAndRejectsDuplicatesAndFullBans() {
        val st = base()
        val r = ExtWishRules.sanitize(st, ExtWish(1, listOf("2026-09-30", "2026-10-04"), listOf("夜", "X")))
        assertEquals(listOf("2026-10-04"), r.saved!!.days); assertEquals(listOf("夜"), r.saved!!.shifts)
        assertNull(ExtWishRules.sanitize(st, ExtWish(1, listOf("2026-11-01"), listOf("夜"))).saved)
        assertNull(ExtWishRules.sanitize(st, ExtWish(1, listOf("2026-10-04"), listOf("休", "日", "夜", "準"))).saved)
        val st2 = base(listOf(r.saved!!))
        assertNull(ExtWishRules.sanitize(st2, ExtWish(1, listOf("2026-10-04"), listOf("夜"))).saved)
        assertNotNull(ExtWishRules.sanitize(st2, ExtWish(0, listOf("2026-10-04"), listOf("夜"))).saved)
    }

    @Test fun overlappingLoadedDataKeepsBothAndExcludesTheDay() {
        val st = base(listOf(first))   // 読み込みデータで 3 日（希望の日）が重なっている
        val p = Problem(st)
        assertEquals(listOf(0 to 2), p.extBan.overlaps)
        assertTrue(!p.extBan.banned(0, 2, 2))
        assertEquals(1, st.wishes.size); assertEquals(3, st.extWishes[0].days.size)
    }

    @Test fun jsonRoundTripAndAbsentKeyIsEmpty() {
        val st = base(listOf(ExtWishRules.sanitize(base(), first).saved!!))
        val back = StateParser.parse(StateParser.serialize(st, st.schedule.toIntArray2D()))
        assertEquals(st.extWishes, back.extWishes)
        assertTrue(StateParser.parse(StateParser.serialize(base(), base().schedule.toIntArray2D())).extWishes.isEmpty())
    }
}
