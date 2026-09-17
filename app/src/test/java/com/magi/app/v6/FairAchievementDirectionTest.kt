package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.590.0/backlog#27①] `PostOptimizationParams.fairAchievementDirection`（既定OFF）を検証する。
 *  OFF時は`fairTarget`の生回数round(平均)がたまたま一致するセルを候補生成から取りこぼす
 *  （3.588.0実測）。ON時は`fairDevOfBucket`の黒箱観測(±1)へ分類を揃え、取りこぼしを解消する。 */
class FairAchievementDirectionTest {
    // 3.588.0で実測したsept2026 g=0,k=0,x=2の構図を再現: 範囲[7,9]/[7,9]/[3,10]・回数7/8/9。
    // 生回数平均は(7+8+9)/3=8=中央の職員(idx1)の回数と一致し「match」判定で候補生成が握り潰される。
    private fun threeMemberState(): MagiState {
        val shifts = listOf(Shift("休み", "休", "", ""), Shift("X", "X", "", ""))
        val t = 9
        val counts = intArrayOf(7, 8, 9)
        val schedule = List(3) { i -> List(t) { j -> if (j < counts[i]) 1 else 0 } }
        val sr = mapOf("0,1" to Range("7", "9"), "1,1" to Range("7", "9"), "2,1" to Range("3", "10"))
        return MagiState(
            startDate = "2026-10-01", endDate = "2026-10-09",
            shifts = shifts, groups = listOf(Group("G", "G")), staff = List(3) { Staff("S$it", 0) }, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = schedule, wishes = emptyMap(), staffRange = sr,
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    // idx1(中央の職員、回数8)自身の回数だけを見る＝OFF時は生回数平均(8)と一致するため候補生成が
    // 一切触れないが、idx0/idx2(生回数平均でも高低が一致する側)はOFFでも動く（実測: applied=3）。
    // 「OFFが何もしない」でなく「OFFはidx1だけを取りこぼす」ことを検証する。
    private fun xCountOfMiddleStaff(st: MagiState, schedule: Array<IntArray>): Int {
        val p = Problem(st)
        return countMatrix(p, schedule)[1][1]
    }

    @Test
    fun offLeavesTheMatchedCellUntouched() {
        val st = threeMemberState()
        val sched = st.schedule.toIntArray2D()

        // maxPasses=1で固定する: 複数パス回すとidx0/idx2の交換でcountsが動きidx1の平均一致が
        // 偶然崩れて次パスで拾われてしまい、分類漏れそのもの(1パス内の欠陥)を隠してしまう。
        val result = AptFairPolish.applyFairPolish(st, sched, maxPasses = 1, fairAchievementDirection = false)
        assertEquals("OFF時は生回数平均が一致するidx1の回数は不変(取りこぼし)", 8, xCountOfMiddleStaff(st, result.newSchedule))
    }

    @Test
    fun onGeneratesCandidatesForTheMatchedCell() {
        val st = threeMemberState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)

        val result = AptFairPolish.applyFairPolish(st, sched, maxPasses = 1, fairAchievementDirection = true)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertTrue("ON時は1パス目から黒箱観測で正しく分類されidx1の回数が動く", xCountOfMiddleStaff(st, result.newSchedule) != 8)
        assertTrue("fairは改善している(悪化しない)", (after.breakdown["fair"] ?: 0) <= (before.breakdown["fair"] ?: 0))
        assertEquals("HARDは悪化しない", 0, after.hard)
    }
}
