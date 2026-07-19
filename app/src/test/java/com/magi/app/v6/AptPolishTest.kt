package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AptPolish] ユーザー指示「専用の研磨パスAptPolish的なものを賢く深く網羅的に作る」の検証
 * （grillingで確定: ①自己振替最優先 ②同一グループ内の相互交換 ③RangePolish型の玉突きチェーン）。
 */
class AptPolishTest {

    // 手①: 単一職員が同一シフト内でaptHigh(X)とaptLow(Y)を同時に持つ最小盤面。
    // 休/X/Yとも need 無し(被覆制約ゼロ)＝自己振替が構造的に無償で成立する。
    private fun selfSwapState(): MagiState {
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("X", "X", "", ""),
            Shift("Y", "Y", "", ""),
        )
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1, 1))
        val groupShiftApt = listOf(listOf("", "1", "3")) // X目標1・Y目標3
        val staff = listOf(Staff("A", 0))
        val schedule = listOf(listOf(1, 1, 2, 2)) // A: X,X,Y,Y（Xは目標1に対し2=超過、Yは目標3に対し2=不足）
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-04",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = groupShiftApt,
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun aptPolishResolvesViaSelfSwapWhenSameStaffHasOppositeImbalance() {
        val st = selfSwapState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はapt違反があること", (before.breakdown["apt"] ?: 0) > 0)
        assertEquals("初期HARD=0", 0, before.hard)

        val result = V6HotfixPasses.applyAptPolish(st, sched)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("自己振替後はapt=0", 0, after.breakdown["apt"] ?: -1)
        assertEquals("HARDは悪化しない", 0, after.hard)
        assertTrue("実際に手が採用されている", result.applied > 0)
    }

    // 手②: 同一グループの2職員が同一シフトで逆方向のapt不均衡を持つ最小盤面（自身の中には
    // 逆方向シフトが無いため自己振替は成立せず、相互交換のみが解となる）。
    private fun mutualSwapState(): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1))
        val groupShiftApt = listOf(listOf("", "1")) // X目標1（休は目標なし＝自己振替の相手になり得ない）
        val staff = listOf(Staff("A", 0), Staff("B", 0))
        val schedule = listOf(
            listOf(1, 1), // A = X,X（目標1に対し2=超過1）
            listOf(0, 0), // B = 休,休（目標1に対し0=不足1）
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-02",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = groupShiftApt,
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun aptPolishResolvesViaMutualSwapWithSameGroupMember() {
        val st = mutualSwapState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はapt違反(AがaptHigh・BがaptLow)があること", (before.breakdown["apt"] ?: 0) > 0)

        val result = V6HotfixPasses.applyAptPolish(st, sched)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("相互交換後はapt=0", 0, after.breakdown["apt"] ?: -1)
        assertEquals("HARDは悪化しない", 0, after.hard)
        assertTrue("実際に手が採用されている", result.applied > 0)
        // 被覆総量保存＝両者の担当日数の合計(X日数)は不変であることも確認。
        val xCountBefore = st.schedule.sumOf { row -> row.count { it == 1 } }
        val xCountAfter = result.newSchedule.sumOf { row -> row.count { it == 1 } }
        assertEquals("Xの総日数(=被覆総量)は保存される", xCountBefore, xCountAfter)
    }

    // 手③: 自己振替/相互交換の相手が構造的に存在しない単一方向のaptHighを、玉突きチェーンで解消する。
    // Aが唯一のX担当可能者でXを独占(need1=1)。Bは需要のない別シフトZに在勤中(いつでも動かせる)。
    private fun chainState(): MagiState {
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("X", "X", "1", ""),
            Shift("Y", "Y", "", ""), // Aの逃げ先
            Shift("Z", "Z", "", ""), // Bの現在地
        )
        val groups = listOf(Group("GA", "GA"), Group("GB", "GB"))
        val groupShift = listOf(
            listOf(1, 1, 1, 0), // GA(A)=休,X,Y
            listOf(1, 1, 0, 1), // GB(B)=休,X,Z
        )
        val groupShiftApt = listOf(
            listOf("", "1", "", ""), // GA: X目標1(Yは目標なし=自己振替の相手なし)
            listOf("", "", "", ""),  // GB: apt対象なし
        )
        val staff = listOf(Staff("A", 0), Staff("B", 1))
        val schedule = listOf(
            listOf(1, 1), // A = X,X（目標1に対し2=超過1）
            listOf(3, 3), // B = Z,Z（需要なし=いつでも動かせる）
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-02",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = groupShiftApt,
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun aptPolishResolvesSingleDirectionViaChainWhenNoSelfOrMutualPartner() {
        val st = chainState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はapt違反があること", (before.breakdown["apt"] ?: 0) > 0)
        assertEquals("初期HARD=0(AがXを単独充足)", 0, before.hard)

        val result = V6HotfixPasses.applyAptPolish(st, sched, seed = 1L)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("玉突き適用後はapt=0", 0, after.breakdown["apt"] ?: -1)
        assertEquals("HARDは悪化しない", 0, after.hard)
        assertEquals("Xの被覆(covU)は悪化しない", 0, after.breakdown["covU"] ?: -1)
        assertTrue("実際に手が採用されている", result.applied > 0)
    }

    @Test
    fun aptPolishIsNoOpWhenNoAptTargetsSet() {
        val st = selfSwapState().copy(groupShiftApt = listOf(listOf("", "", "")))
        val sched = st.schedule.toIntArray2D()
        val result = V6HotfixPasses.applyAptPolish(st, sched)
        assertEquals(0, result.applied)
    }
}
