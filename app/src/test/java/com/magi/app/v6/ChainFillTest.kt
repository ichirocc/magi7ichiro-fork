package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [E11] findCovUChain（多人数ブロック移動）の検証。実機 2026-08 データでユーザーが手作業で見つけた
 * 「勤務→勤務」連鎖（既存の休→勤務修復では踏めない）を、決定的な連鎖探索が解けることを確認する。
 */
class ChainFillTest {

    // 8/17 相当（深さ2連鎖）: covU=P。P可能者(上條)はQに在勤 → Qを空けると covU → Qは山本(Rに在勤)が埋め、
    // Rは過剰なので山本が抜けても充足。期待: 上條 Q→P, 山本 R→Q の2手。
    private fun depth2State(): MagiState {
        // shift: 0=休(need無) 1=P(need1) 2=Q(need1) 3=R(need1)
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("P", "P", "1", ""),
            Shift("Q", "Q", "1", ""),
            Shift("R", "R", "1", ""),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"))
        // G0=休/P/Q, G1=休/Q/R, G2=休/R
        val groupShift = listOf(
            listOf(1, 1, 1, 0),
            listOf(1, 0, 1, 1),
            listOf(1, 0, 0, 1),
        )
        // 上條∈G0(Qに在勤), 山本∈G1(Rに在勤), X∈G2(Rに在勤→Rを過剰に), Y∈G2(休)
        val staff = listOf(Staff("上條", 0), Staff("山本", 1), Staff("X", 2), Staff("Y", 2))
        val schedule = listOf(
            listOf(2), // 上條 = Q
            listOf(3), // 山本 = R
            listOf(3), // X = R（R が2人＝過剰）
            listOf(0), // Y = 休
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift,
            groupShiftApt = List(3) { List(4) { "" } },
            schedule = schedule,
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun chainFillSolvesDepth2Deadlock() {
        val st = depth2State()
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()

        // 前提: P(shift1) が covU、既存の休→勤務修復では踏めない（休のYはPを担当不可＝G2）。
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はP不足(covU>0)であること", (before.breakdown["covU"] ?: 0) > 0)

        // 連鎖探索（seed 固定で決定的）。P=shift1, j=0。
        val chain = findCovUChain(p, sched, 1, 0, Random(42))
        assertNotNull("勤務→勤務の連鎖が見つかること", chain)
        assertTrue("2手以内の連鎖", chain!!.size in 1..2)

        // 適用して covU=0・hard 非増加を確認（keep-best 相当の妥当性）。
        for (mv in chain) sched[mv[0]][mv[1]] = mv[2]
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("連鎖適用後は covU=0", 0, after.breakdown["covU"] ?: 0)
        assertTrue("hard は悪化しない", after.hard <= before.hard)
    }

    // 8/11 相当（深さ1）: covU=Cｵ、唯一の Cｵ可能者が過剰シフト B4 に在勤 → 1手で covU/covO 同時解消。
    @Test
    fun chainFillSolvesDepth1FromOvercoveredShift() {
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("Co", "Co", "1", ""),   // need1=1
            Shift("B4", "B4", "1", ""),   // need1=1（現状2＝過剰）
        )
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1, 1))   // 全員 休/Co/B4 可
        val staff = listOf(Staff("モニカ", 0), Staff("a", 0), Staff("b", 0))
        val schedule = listOf(
            listOf(2), // モニカ = B4
            listOf(2), // a = B4（B4 が2人＝過剰）
            listOf(0), // b = 休
        )
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(1) { List(3) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        // Co(shift1) covU。ただし 休のbが Co を担当可能なので、休→勤務でも解けるが、連鎖は過剰B4からの
        // 1手（深さ1）も踏める。ここでは連鎖が非nullで covU を解消することを確認（どの1手でも可）。
        val chain = findCovUChain(p, sched, 1, 0, Random(7))
        assertNotNull(chain)
        for (mv in chain!!) sched[mv[0]][mv[1]] = mv[2]
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals(0, after.breakdown["covU"] ?: 0)
        assertTrue(after.hard <= before.hard)
    }
}
