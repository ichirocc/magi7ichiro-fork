package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.ManualPin
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.272.0] ConstraintMus（Constraint IR + deletion-based MUS）の検証。
 * 各テストは「極小コアの正確な構成」まで固定する（極小性: どの1件を外しても証明が崩れる構成を
 * 手計算で設計済み＝コアは一意）。
 */
class ConstraintMusTest {

    private fun state(
        days: Int,
        shifts: List<Shift>,
        wishes: Map<String, Int> = emptyMap(),
        staffRange: Map<String, Range> = emptyMap(),
        cons1: List<C1Row> = emptyList(),
        staffCount: Int = 1,
    ): MagiState {
        val end = "2026-01-" + days.toString().padStart(2, '0')
        return MagiState(
            startDate = "2026-01-01", endDate = end,
            shifts = shifts,
            groups = listOf(Group("G", "G")),
            staff = List(staffCount) { Staff("s$it", 0) },
            use2Patterns = false,
            groupShift = listOf(List(shifts.size) { 1 }),
            groupShiftApt = listOf(List(shifts.size) { "" }),
            schedule = List(staffCount) { List(days) { 0 } },
            wishes = wishes,
            staffRange = staffRange,
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = cons1, cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    private fun List<ConstraintMus.Item>.countOf(cls: Class<*>) = count { cls.isInstance(it) }

    @Test
    fun staffMusCapVersusPinnedWishes() {
        // X上限1に対しXへの固定希望が2件 → {上限, 希望, 希望} の3件が極小コア。
        val st = state(
            days = 5,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", "")),
            wishes = mapOf("0,0" to 1, "0,2" to 1),
            staffRange = mapOf("0,1" to Range("", "1")),
        )
        val res = ConstraintMus.analyzeStaffConflicts(Problem(st))
        assertEquals(1, res.size)
        val core = res[0].core
        assertEquals(3, core.size)
        assertEquals(1, core.countOf(ConstraintMus.RangeCap::class.java))
        assertEquals(2, core.countOf(ConstraintMus.WishPin::class.java))
    }

    /** 職員1人の証明も手動固定を数える（最適化器が必ず守る＝希望固定と同じ需要）。上限1に 2 日固定なら同時に成立しない。 */
    @Test
    fun staffMusCountsManualPinsLikeWishes() {
        val shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", ""))
        val st = state(days = 5, shifts = shifts, staffRange = mapOf("0,1" to Range("", "1")))
            .copy(manualPins = listOf(ManualPin(0, 0, 1), ManualPin(0, 2, 1)))
        val res = ConstraintMus.analyzeStaffConflicts(Problem(st))
        assertEquals(1, res.size)
        val pins = res[0].core.filterIsInstance<ConstraintMus.WishPin>()
        assertEquals(2, pins.size)
        assertTrue("コアの固定は手動固定として名指しする（案内は「固定を外す」）", pins.all { it.manual })
        assertEquals(1, res[0].core.countOf(ConstraintMus.RangeCap::class.java))
    }

    /** 希望と手動固定が混ざっても同じ数え方（どちらも 1 日分の需要）。 */
    @Test
    fun staffMusMixesWishAndManualPin() {
        val shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", ""))
        val st = state(days = 5, shifts = shifts, wishes = mapOf("0,0" to 1), staffRange = mapOf("0,1" to Range("", "1")))
            .copy(manualPins = listOf(ManualPin(0, 3, 1)))
        val pins = ConstraintMus.analyzeStaffConflicts(Problem(st)).single().core.filterIsInstance<ConstraintMus.WishPin>()
        assertEquals(listOf(false, true), pins.sortedBy { it.day }.map { it.manual })
    }

    @Test
    fun staffMusFloorPlusWishesPigeonhole() {
        // T=5でX下限3＋休への固定希望3件 → 需要合計6>5（鳩の巣）。どの1件を外しても5以下=コアは4件で一意。
        val st = state(
            days = 5,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", "")),
            wishes = mapOf("0,0" to 0, "0,1" to 0, "0,2" to 0),
            staffRange = mapOf("0,1" to Range("3", "")),
        )
        val res = ConstraintMus.analyzeStaffConflicts(Problem(st))
        assertEquals(1, res.size)
        val core = res[0].core
        assertEquals(4, core.size)
        assertEquals(1, core.countOf(ConstraintMus.RangeFloor::class.java))
        assertEquals(3, core.countOf(ConstraintMus.WishPin::class.java))
    }

    @Test
    fun staffMusWindowRulePlusWishes() {
        // 窓ルール「X 5日で1回以上」(最小1日) ＋ 全5日が休への固定希望 → 1+5=6>5（鳩の巣）。
        val st = state(
            days = 5,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", "")),
            wishes = mapOf("0,0" to 0, "0,1" to 0, "0,2" to 0, "0,3" to 0, "0,4" to 0),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "1")),
        )
        val res = ConstraintMus.analyzeStaffConflicts(Problem(st))
        assertEquals(1, res.size)
        val core = res[0].core
        assertEquals(6, core.size)
        assertEquals(1, core.countOf(ConstraintMus.WindowRule::class.java))
        assertEquals(5, core.countOf(ConstraintMus.WishPin::class.java))
    }

    @Test
    fun dayMusCoverageBlockedByWishes() {
        // 日0: X必要1人・担当可能な2人とも休への固定希望 → {必要人数, 希望, 希望} の3件が極小コア。
        // 日1は希望なしで充足可能=矛盾なし。
        val st = state(
            days = 2,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "1", "")),
            wishes = mapOf("0,0" to 0, "1,0" to 0),
            staffCount = 2,
        )
        val res = ConstraintMus.analyzeDayConflicts(Problem(st))
        assertEquals(1, res.size)
        assertEquals(0, res[0].day)
        val core = res[0].core
        assertEquals(3, core.size)
        assertEquals(1, core.countOf(ConstraintMus.DayNeed::class.java))
        assertEquals(2, core.countOf(ConstraintMus.WishPin::class.java))
    }

    @Test
    fun engineFindsWishFreeConflictButGuidanceSuppressesIt() {
        // 2b-3系（上限×窓・希望なし）: エンジンは {上限, 窓ルール} の2件コアを見つけるが、
        // buildGuidance 検査9は既存検査との重複回避のため「希望を含むコアのみ」を出す。
        val st = state(
            days = 10,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", "")),
            staffRange = mapOf("0,1" to Range("", "1")),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")),
        )
        val res = ConstraintMus.analyzeStaffConflicts(Problem(st))
        assertEquals(1, res.size)
        assertEquals(2, res[0].core.size)
        assertEquals(1, res[0].core.countOf(ConstraintMus.RangeCap::class.java))
        assertEquals(1, res[0].core.countOf(ConstraintMus.WindowRule::class.java))
        val issues = V6SanityPort.buildGuidance(st)
        assertTrue("既存の2b-3が担当することの確認", issues.any { it.problem.contains("この人だけではどう配置しても") })
        assertFalse("希望なしコアは検査9から出さない（重複ゼロ）", issues.any { it.problem.contains("同時に成立しません") })
    }

    @Test
    fun guidanceEmitsDayConflictWithWishLabels() {
        val st = state(
            days = 2,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "1", "")),
            wishes = mapOf("0,0" to 0, "1,0" to 0),
            staffCount = 2,
        )
        val issues = V6SanityPort.buildGuidance(st)
        val hit = issues.filter { it.where.contains("必要人数と固定希望の衝突") }
        assertEquals(1, hit.size)
        assertTrue(hit[0].problem.contains("同時に成立しません"))
        assertTrue("コアの希望が名前つきで列挙される", hit[0].problem.contains("希望「"))
        assertTrue("緩和候補として希望調整を提案する", hit[0].fix.contains("希望を1件調整"))
        assertFalse(hit[0].problem, hit[0].problem.contains("個人上限が0"))
    }

    @Test
    fun dayMusNamesStaffLeftOutByZeroCap() {
        // X 必要 1 人: s0 は休の希望固定、s1 は X の上限 0（希望なし＝mayPlace が偽で席に就けない）。証明の前提として s1 を名指しする。
        val st = state(
            days = 1,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "1", "")),
            wishes = mapOf("0,0" to 0),
            staffRange = mapOf("1,1" to Range("", "0")),
            staffCount = 2,
        )
        assertEquals(2, ConstraintMus.analyzeDayConflicts(Problem(st)).single().core.size)
        val hit = V6SanityPort.buildGuidance(st).single { it.where.contains("必要人数と固定希望の衝突") }
        assertEquals(
            "固定された希望の組合せでは、この日の必要人数を満たせません。次の2件は同時に成立しません: " +
                "必要人数「Xに1人」 ・ 希望「s0 1/1(木)=休」。個人上限が0のため置けない人: s1（X）",
            hit.problem,
        )
    }

    @Test
    fun dayMusWishPinOnZeroCapShiftServesItsSeat() {
        // 実データ 10/11 の形: 上限 0 の C へ希望固定した人はその席に就ける（希望固定は mayPlace より優先）。
        val st = state(
            days = 1,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("D", "D", "1", ""), Shift("C", "C", "1", "")),
            wishes = mapOf("0,0" to 0, "1,0" to 2),
            staffRange = mapOf("1,2" to Range("", "0")),
            staffCount = 3,
        )
        val p = Problem(st)
        assertFalse(p.mayPlace(1, 2))
        assertTrue(ConstraintMus.analyzeDayConflicts(p).isEmpty())
        assertFalse(V6SanityPort.buildGuidance(st).any { it.where.contains("必要人数と固定希望の衝突") })
    }

    @Test
    fun staffForcedMinSubtractsDaysPinnedToUnplaceableShift() {
        // Z は上限 0（置けない）だが 2 日が Z の希望固定。残り 3 日は 休≤2・A≤1 で埋まる＝矛盾なし（強制下限は Z の日を差し引く）。
        val st = state(
            days = 5,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "", ""), Shift("Z", "Z", "", "")),
            wishes = mapOf("0,0" to 2, "0,1" to 2),
            staffRange = mapOf("0,0" to Range("", "2"), "0,1" to Range("", "1"), "0,2" to Range("", "0")),
        )
        assertTrue(ConstraintMus.analyzeStaffConflicts(Problem(st)).isEmpty())
        // 希望が 1 日だけなら残り 4 日を 休≤2・A≤1 で埋められない＝真の矛盾は引き続き出る。
        val st1 = st.copy(wishes = mapOf("0,0" to 2))
        assertEquals(1, ConstraintMus.analyzeStaffConflicts(Problem(st1)).size)
    }

    /** 31 日・休の上限 9・「休 7日で2回以上」。休の希望 4・5・12〜14 日と、別のシフトの希望 10・11 日（1 始まり）。 */
    private fun restWindowState(restHi: String) = state(
        days = 31,
        shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", "")),
        wishes = mapOf("0,3" to 0, "0,4" to 0, "0,11" to 0, "0,12" to 0, "0,13" to 0, "0,9" to 1, "0,10" to 1),
        staffRange = mapOf("0,0" to Range(restHi, restHi)),
        cons1 = listOf(C1Row("7", "休", "2")),
    )

    @Test
    fun windowMinimumCountsWishPositions() {
        // 位置を見ない最少は 8 回（上限 9 に収まる）。希望の日付を含めると 10 回要る＝上限 9 と期間の制約と希望は両立しない。
        assertEquals(8, SmartInitialScheduler.minDaysForFullCompliance(31, listOf(7 to 2)))
        val p = Problem(restWindowState("9"))
        val core = ConstraintMus.analyzeStaffConflicts(p).single().core
        assertTrue(core.any { it is ConstraintMus.RangeCap && it.shift == 0 && it.hi == 9 })
        assertTrue(core.any { it is ConstraintMus.WindowRule && it.shift == 0 })
        assertTrue(core.any { it is ConstraintMus.WishPin })
        assertEquals("希望・固定の日付を含めると、期間の制約を満たすには「休」が最少10回要ります。上限は9回です",
            V6SanityPort.staffConflictReason(p, core) { if (it == 0) "休" else "X" })
        // 全部の希望を見ても 10 回＝上限 10 なら両立する（誤検知しない）。
        val allPins = (0 until 31).mapNotNull { j -> p.wish[0][j].takeIf { it >= 0 }?.let { ConstraintMus.WishPin(0, j, it) } }
        assertEquals(10, ConstraintMus.windowNeed(p, 0, listOf(ConstraintMus.WindowRule(0, 7, 2)) + allPins))
        assertTrue(ConstraintMus.analyzeStaffConflicts(Problem(restWindowState("10"))).isEmpty())
    }

    @Test
    fun windowBlockedByOtherWishesIsAConflict() {
        // 「休 4日で1回以上」の 4 日がすべて別のシフトの希望＝その窓は満たせない（上限が無くても矛盾）。
        val st = state(
            days = 10,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", "")),
            wishes = mapOf("0,2" to 1, "0,3" to 1, "0,4" to 1, "0,5" to 1),
            cons1 = listOf(C1Row("4", "休", "1")),
        )
        val p = Problem(st)
        val sc = ConstraintMus.analyzeStaffConflicts(p).single()
        assertEquals(1, sc.core.countOf(ConstraintMus.WindowRule::class.java))
        assertEquals(4, sc.core.countOf(ConstraintMus.WishPin::class.java))
        assertEquals(null, ConstraintMus.windowNeed(p, 0, sc.core))
    }

    @Test
    fun noConflictYieldsNothing() {
        val st = state(
            days = 5,
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "1", "")),
            wishes = mapOf("0,0" to 1),
            staffRange = mapOf("0,1" to Range("1", "5")),
            staffCount = 2,
        )
        assertTrue(ConstraintMus.analyzeStaffConflicts(Problem(st)).isEmpty())
        assertTrue(ConstraintMus.analyzeDayConflicts(Problem(st)).isEmpty())
    }
}
