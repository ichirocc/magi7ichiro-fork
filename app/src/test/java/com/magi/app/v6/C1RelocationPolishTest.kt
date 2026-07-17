package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [C1研磨の再設計] applyC1WindowPolish に追加した回数保存移設プリミティブ（手R1=鏡像長方形・手R2=自己2日swap）
 * の検証。golden_state の残差解剖（Python実測、CLAUDE.md参照）で判明した「回数固定職員には追加系の手A/手Bが
 * 必ず棄却され、移設だけが唯一の改善手」という局面を、手計算で検証済みの最小盤面で再現する。
 * 2シフト・4日の最小構成（D=2窓・N=1回以上）を使い、各テストは「手A(同日スワップ)は不採用/不可能」
 * 「新設の手だけが採用される」ことを前提条件として固定する（乱数非依存・決定的）。
 */
class C1RelocationPolishTest {

    private fun shifts() = listOf(
        Shift("Y", "Y", "", ""),   // index0 = 汎用シフト（need無し）
        Shift("X", "X", "", ""),  // index1 = cons1 対象シフト（need無し・c1のみで縛る）
    )

    /**
     * 手R1（鏡像長方形）検証用の盤面。D=2,N=1窓・T=4日。
     * i(職員0)=[X,X,Y,Y]: 手計算済み内訳 — 窓[0,1]=2X(余剰,day0安全) / 窓[1,2]=1X(day1は窓[1,2]がz=1<=n=1で危険=非donor)
     *   / 窓[2,3]=0X(不足=deficient)。donors={day0}のみ。
     * i2(職員1)=[Y,Y,X,X]: 窓[0,1]=0X(deficient) / 窓[1,2]=1X(ok) / 窓[2,3]=2X(ok)。
     * 手A(同日j=2のみ交換)を手計算すると: i 1→0fire(-1)・i2 1→2fire(+1)=総和±0で不採用（isBetterが拒否）。
     * 手R1(day0とday2を同時に交換)は: i 1→0fire(-1)・i2 1→1fire(±0, 窓[0,1]解消/窓[1,2]新規で相殺)=総和-1で採用。
     * 両職員とも同一グループ・両シフト担当可、needもwishもcons3nも無し＝covU/HARD不変。
     */
    private fun mirrorState(): MagiState {
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("i", 0), Staff("i2", 0))
        val schedule = listOf(
            listOf(1, 1, 0, 0),   // i:  X,X,Y,Y
            listOf(0, 0, 1, 1),   // i2: Y,Y,X,X
        )
        return MagiState(
            startDate = "2026-01-01", endDate = "2026-01-04",
            shifts = shifts(), groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = schedule,
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "2", shiftKigou = "X", day2 = "1")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun c1PolishAppliesMirrorRectangleWhenSameDaySwapIsRejected() {
        val st = mirrorState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期 HARD=0（covU/c3n無し）", 0, before.hard)
        assertTrue("初期 c1>0", (before.breakdown["c1"] ?: 0) > 0)

        val res = V6HotfixPasses.applyC1WindowPolish(st, sched, maxPasses = 1)
        val after = UnifiedViolationChecker.check(st, res.newSchedule)

        assertTrue("鏡像長方形が採用されたこと（手計算どおり同日スワップは総和±0で不採用のはず）", res.applied > 0)
        assertTrue("c1 が減少したこと", (after.breakdown["c1"] ?: 0) < (before.breakdown["c1"] ?: 0))
        assertEquals("HARD 不変(=0)", 0, after.hard)
        assertTrue("鏡像交換のログが記録されていること", res.logs.any { it.message.contains("鏡像:1") || it.message.contains("鏡像:2") })
        // 鏡像交換は両職員の総シフト回数（多重集合）を保存する（i, i2 とも X/Y の総数が不変）。
        fun countsOf(sc: Array<IntArray>, i: Int): Pair<Int, Int> {
            val cx = sc[i].count { it == 1 }; val cy = sc[i].count { it == 0 }
            return cx to cy
        }
        val (bx0, by0) = countsOf(sched, 0); val (ax0, ay0) = countsOf(res.newSchedule, 0)
        val (bx1, by1) = countsOf(sched, 1); val (ax1, ay1) = countsOf(res.newSchedule, 1)
        assertEquals("職員0の X 回数保存", bx0, ax0); assertEquals("職員0の Y 回数保存", by0, ay0)
        assertEquals("職員1の X 回数保存", bx1, ax1); assertEquals("職員1の Y 回数保存", by1, ay1)
    }

    /**
     * 手R2（自己2日swap）検証用の盤面。D=2,N=1窓・T=4日。
     * i(職員0)=[X,X,Y,Y]（mirrorStateと同一行）: donors={day0}・deficient window=[2,3](day2)。
     * i2(職員1)は別グループでXを担当不可（groupShift=[1,0]）＝全日Yに固定＝手A/手R1の相手候補が構造上ゼロ
     *   （i2は常にX不保持のため work[i2][j]==X が成立しない）。よって手R2（自己内の付け替え）だけが唯一の解。
     * 手計算: i 単独で day0(X)→Y・day2(Y)→X の入替えで 1fire→0fire（窓[2,3]解消・窓[0,1]は2X→1Xで依然ok）。
     */
    private fun selfSwapState(): MagiState {
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"))
        val staff = listOf(Staff("i", 0), Staff("bystander", 1))
        val schedule = listOf(
            listOf(1, 1, 0, 0),   // i:         X,X,Y,Y
            listOf(0, 0, 0, 0),   // bystander: Y,Y,Y,Y（Xを担当不可＝手A/R1の相手になり得ない）
        )
        return MagiState(
            startDate = "2026-01-01", endDate = "2026-01-04",
            shifts = shifts(), groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1), listOf(1, 0)),
            groupShiftApt = List(2) { List(2) { "" } },
            schedule = schedule,
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "2", shiftKigou = "X", day2 = "1")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun c1PolishAppliesSelfSwapWhenNoOtherStaffCanTakeTheShift() {
        val st = selfSwapState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期 HARD=0", 0, before.hard)
        assertTrue("初期 c1>0", (before.breakdown["c1"] ?: 0) > 0)

        val res = V6HotfixPasses.applyC1WindowPolish(st, sched, maxPasses = 1)
        val after = UnifiedViolationChecker.check(st, res.newSchedule)

        assertTrue("自己swapが採用されたこと（相方候補が存在しないため手A/R1は不可能）", res.applied > 0)
        assertEquals("c1 が解消されたこと（0まで）", 0, after.breakdown["c1"] ?: 0)
        assertEquals("HARD 不変(=0)", 0, after.hard)
        assertTrue("自己swapのログが記録されていること", res.logs.any { it.message.contains("自己:1") })
        // 自己swapは職員0自身のX/Y総回数を保存する。
        val cx0Before = sched[0].count { it == 1 }; val cx0After = res.newSchedule[0].count { it == 1 }
        assertEquals("職員0の X 回数保存", cx0Before, cx0After)
    }

    @Test
    fun c1PolishIsNoOpWhenAlreadySatisfied() {
        // 全窓が既に充足済み（X,X,X,X）なら anchorStaff が空になり即終了・採用0。
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("i", 0))
        val schedule = listOf(listOf(1, 1, 1, 1))
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-04",
            shifts = shifts(), groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = schedule,
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "2", shiftKigou = "X", day2 = "1")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val res = V6HotfixPasses.applyC1WindowPolish(st, sched)
        assertEquals("既に充足済みでは採用0(no-op)", 0, res.applied)
    }
}
