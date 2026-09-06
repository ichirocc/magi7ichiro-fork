package com.magi.app.v6

import com.magi.app.model.C41Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 違反連結成分修復（Iteration 2 第一弾）。材料は各パスの拒否候補、採用は正式チェッカー＋厳密ピン検査。
 * 盤面は CombinatorialRepairTest と同じ検証済み最小盤面（X の P 超過と Y の D 不足は単独ではタイで不採用、束ねると apt が 2 件消える）。
 */
class ViolationComponentRepairTest {
    private fun isBetterLocal(a: ViolationReport, b: ViolationReport): Boolean {
        if (a.hard != b.hard) return a.hard < b.hard
        if (a.total != b.total) return a.total < b.total
        return a.weightedScore < b.weightedScore
    }

    private fun combineTwoRejectedState(): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("P", "P", "", ""), Shift("Qres", "Qres", "", ""), Shift("D", "D", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("X", 0), Staff("Y", 0), Staff("W1", 0), Staff("W2", 0))
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1, 1)), groupShiftApt = listOf(listOf("", "0", "", "1")),
            schedule = listOf(listOf(1), listOf(2), listOf(0), listOf(0)), wishes = emptyMap(),
            staffRange = mapOf("0,3" to Range("", "0"), "2,3" to Range("", "0"), "3,3" to Range("", "0")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = listOf(C41Row("G0", "Qres", "1", "1")), cons42 = emptyList(),
        )
    }

    @Test
    fun combinesCandidatesRejectedByDifferentPassesIntoOneTransaction() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val before = UnifiedViolationChecker.check(st, work)
        assertEquals(2, before.breakdown["apt"] ?: 0)
        val candX = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 2)), "AptChain", "X")   // apt パスの拒否候補
        val candY = CombinatorialRepair.Candidate(listOf(intArrayOf(1, 0, 3)), "tryRelocate", "Y") // range パスの拒否候補
        run {
            val w = work.map { it.clone() }.toTypedArray(); w[0][0] = 2
            assertFalse("単独は不採用(タイ)", isBetterLocal(UnifiedViolationChecker.check(st, w), before))
        }
        val r = ViolationComponentRepair.repair(st, work, listOf(candX, candY))
        val after = UnifiedViolationChecker.check(st, r.newSchedule)
        assertEquals(1, r.applied)
        assertEquals(0, after.breakdown["apt"] ?: -1)
        assertEquals(0, after.hard)
        assertEquals(2, r.newSchedule[0][0]); assertEquals(3, r.newSchedule[1][0])
        assertTrue(r.logs.first().message, r.logs.first().message.contains("採用1件"))
        assertTrue(r.logs.first().message, r.logs.first().message.contains("k=2"))
    }

    @Test
    fun neverWorsensAndLeavesBoardUntouchedWhenNoTransactionImproves() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val snapshot = work.map { it.clone() }.toTypedArray()
        // 単独でも束ねても悪化する候補（X と W1 を D へ＝staffRange hi=0 を破り high が増える。チェッカーで 3 通り全部 w 悪化を確認済み。
        //   Y→休 は fair が 2 減って c41 の 1 増を上回る＝実は改善なので、この対照には入れない）
        val bad = listOf(
            CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 3)), "a", "X→D"),
            CombinatorialRepair.Candidate(listOf(intArrayOf(2, 0, 3)), "c", "W1→D"),
        )
        val r = ViolationComponentRepair.repair(st, work, bad)
        assertEquals(0, r.applied)
        for (i in snapshot.indices) assertTrue(snapshot[i].contentEquals(r.newSchedule[i]))
        assertEquals(r.beforeTotal, r.afterTotal)
    }

    @Test
    fun anchorSetsPutPatchesTouchingTheViolationFirstAndHelpersSharingAStaffOrDayAfter() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val rep = UnifiedViolationChecker.check(st, work)
        val anchors = ViolationComponentRepair.anchors(rep)
        assertTrue("apt の回数違反が起点になる", anchors.any { it.family.startsWith("apt") && it.staff == 0 })
        fun patch(vararg cells: IntArray) = ViolationComponentRepair.Patch(cells.toList(), "m", "")
        val x = patch(intArrayOf(0, 0, 2))           // X を Qres へ＝起点 apt(X) を触る主候補
        val y = patch(intArrayOf(1, 0, 3))           // Y を D へ＝X と日 0 を共有する助候補
        val far = patch(intArrayOf(3, 0, 1))         // W2＝日 0 を共有するので助候補
        val sets = ViolationComponentRepair.anchorSets(anchors.filter { it.staff == 0 && it.day < 0 }, listOf(x, y, far), cap = 2)
        assertEquals(1, sets.size)
        assertEquals(listOf(0, 1), sets[0].second)   // 主候補が先、助候補は cap まで
        assertTrue(x.overlaps(patch(intArrayOf(0, 0, 3))))
        assertFalse(x.overlaps(y))
    }

    @Test
    fun qualityVectorOrdersHardCountBeforeAnyWeight() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val rep = UnifiedViolationChecker.check(st, work)
        val qv = ViolationComponentRepair.QualityVector.of(rep, changedCells = 3)
        assertEquals(rep.hard, qv.hardCount)
        assertEquals(0.0, qv.hardWeighted, 0.0)
        assertTrue(qv.softWeighted > 0.0)
        assertEquals(3, qv.changedCells)
        val worseHard = qv.copy(hardCount = 1, softWeighted = 0.0, changedCells = 0)
        assertTrue(qv < worseHard)
        val fewerChanges = qv.copy(changedCells = 1)
        assertTrue(fewerChanges < qv)
    }

    @Test
    fun combineAndApplyHandsUnusedCandidatesToTheLeftoverSink() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val before = UnifiedViolationChecker.check(st, work)
        // 同じセルを触る 2 候補＝重複セルで結合されず、どちらも残る。
        val dup = listOf(
            CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 1)), "dup", "1"),
            CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 3)), "dup", "2"),
        )
        val leftover = ArrayList<CombinatorialRepair.Candidate>()
        CombinatorialRepair.combineAndApply(st, work, before, dup, ::isBetterLocal, leftover = leftover)
        assertEquals(2, leftover.size)
    }

    @Test
    fun postOptimizationWithComponentRepairEnabledRunsAndNeverWorsensTheBoard() {
        val st = combineTwoRejectedState()
        val sched = st.schedule.map { it.toIntArray() }.toTypedArray()
        val before = UnifiedViolationChecker.check(st, sched)
        val params = V6HotfixPasses.PostOptimizationParams(componentRepairEnabled = true, maxRounds = 1)
        val r = V6HotfixPasses.runPostOptimization(st, sched.map { it.clone() }.toTypedArray(), "t", seed = 7L, params = params)
        assertTrue(r.report.hard <= before.hard)
        assertTrue(!betterReport(before, r.report))
    }
}
