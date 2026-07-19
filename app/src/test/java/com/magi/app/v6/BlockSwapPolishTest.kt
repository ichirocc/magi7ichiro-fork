package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BlockSwapPolish] ユーザー指示「15日間まるごと2人交換を実装する」の検証（grillingで確定:
 * 対象=同一担当グループのみ／位置=全オフセットのスライド窓／実行=後処理Polishパス／探索範囲=
 * アンカーなし・同グループ内全ペア×全オフセットを無条件に試す）。
 *
 * 最小盤面: 同一グループ(休/Xとも担当可)のA・Bで T=15(=既定blockLen、オフセットは1通り)。
 * Aは15日ともX(hi=5に対し超過10)・Bは15日とも休(Xは無制限=いくら引き受けても違反なし)。
 * ブロックを丸ごと入替えると、Aは全休(X=0、範囲内)・Bは全X(無制限=違反なし)になり一発で解消する
 * （1日ずつの交換でも解消はできるが、本パスが正しく丸ごと交換を実行し採用することを固定する）。
 */
class BlockSwapPolishTest {

    private fun sameGroupState(wishes: Map<String, Int> = emptyMap()): MagiState {
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("X", "X", "", ""),
        )
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1))   // G0(A,B)=休,Xとも担当可
        val staff = listOf(Staff("A", 0), Staff("B", 0))
        val schedule = listOf(
            List(15) { 1 }, // A = 15日ともX（hi=5に対し超過10）
            List(15) { 0 }, // B = 15日とも休
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-15",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(1) { List(2) { "" } },
            schedule = schedule, wishes = wishes,
            staffRange = mapOf("0,1" to Range(lo = "0", hi = "5")), // Aのみhi設定、Bは無制限
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun blockSwapPolishResolvesHighExcessViaFullBlockExchange() {
        val st = sameGroupState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はAのhigh違反があること", (before.breakdown["high"] ?: 0) > 0)
        assertEquals("初期HARD=0", 0, before.hard)

        val result = V6HotfixPasses.applyBlockSwapPolish(st, sched, blockLen = 15)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("ブロック交換後はhigh=0", 0, after.breakdown["high"] ?: -1)
        assertEquals("HARDは悪化しない(0のまま)", 0, after.hard)
        assertTrue("実際に手が採用されている", result.applied > 0)
        // 丸ごと入替わっていること（Aは全休、Bは全X）を直接確認。
        assertEquals(List(15) { 0 }, result.newSchedule[0].toList())
        assertEquals(List(15) { 1 }, result.newSchedule[1].toList())
    }

    @Test
    fun blockSwapPolishIsNoOpWhenNoSameGroupPair() {
        // 別グループの2人だけ＝ペアが存在しない。
        val base = sameGroupState()
        val st = base.copy(
            groups = listOf(Group("G0", "G0"), Group("G1", "G1")),
            staff = listOf(Staff("A", 0), Staff("B", 1)),
            groupShift = listOf(listOf(1, 1), listOf(1, 1)),
        )
        val sched = st.schedule.toIntArray2D()
        val result = V6HotfixPasses.applyBlockSwapPolish(st, sched, blockLen = 15)
        assertEquals(0, result.applied)
    }

    @Test
    fun blockSwapPolishSkipsBlockWhenWishLockedInside() {
        // Aのday0に希望固定＝唯一のオフセット候補(0..14)がロックされ、交換自体が試行されない。
        val st = sameGroupState(wishes = mapOf("0,0" to 0))
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        val result = V6HotfixPasses.applyBlockSwapPolish(st, sched, blockLen = 15)
        assertEquals("希望固定のため採用0回", 0, result.applied)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)
        assertEquals("盤面・違反とも不変", before.breakdown["high"] ?: 0, after.breakdown["high"] ?: 0)
    }
}
