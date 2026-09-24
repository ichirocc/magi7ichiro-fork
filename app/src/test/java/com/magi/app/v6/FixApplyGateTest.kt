package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提案の適用直前に仮盤面で再評価し、改善しない・固定を崩す提案は入力の盤面を変えずに拒否する。 */
class FixApplyGateTest {
    private val REST = 0; private val A = 1

    private fun state(wishes: Map<String, Int> = emptyMap(), ranges: Map<String, Range> = emptyMap()) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-02",
        shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "1", "1")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0), Staff("s1", 0)), use2Patterns = true,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        // 1 日目は A が 0 人（不足 1）、2 日目は A が 2 人（過剰 1）
        schedule = listOf(listOf(REST, A), listOf(REST, A)), wishes = wishes, staffRange = ranges,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )
    private fun sched(st: MagiState) = Array(st.staffCount) { st.schedule[it].toIntArray() }

    /** [3.573.0/外部レビュー指摘・実データで再現] covU を1件解消する代わりに禁止連(c3n)を1件新規発生させる
     *  提案は、HARD合計が同値（1=1）でweightedScoreが改善（covU10000→c3n9000）するため、
     *  newHardFamilyViolation ガードが無ければ betterReport だけで Applied になっていた
     *  （docs/automation.md「禁止連の新規違反なし」が実装と食い違っていた実例）。 */
    @Test fun resolvingOneHardFamilyByIntroducingAnotherIsRejected() {
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-02",
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "1", "1")),
            groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0), Staff("s1", 0)), use2Patterns = true,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            // s0: day0=A・day1=休（covU: day1のAが0人で1件不足）。s1は常に休。
            schedule = listOf(listOf(A, REST), listOf(REST, REST)),
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(com.magi.app.model.C3Row(listOf("A", "A"))),   // A の2連続を禁止
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val s = sched(st)
        val before = UnifiedViolationChecker.check(st, s)
        assertEquals(1, before.breakdown["covU"]); assertEquals(0, before.breakdown["c3n"])
        // s0 の day1 を休→A にすると covU は解消するが、s0 は day0 も A なので禁止連(c3n)が新規発生する。
        val r = FixApplyGate.apply(st, s, listOf(FixCell(0, 1, A)))
        assertTrue("HARD合計は改善(1→1・weightedも改善)するがc3nが新規発生する提案は拒否すべき", r is FixApplyGate.Outcome.Rejected)
        r as FixApplyGate.Outcome.Rejected
        assertEquals(1, r.after?.breakdown?.get("c3n"))
        assertEquals(REST, s[0][1])   // 入力は不変
    }

    /** 外部レビュー N10: 提案の生成側も回数固定（下限＝上限）を崩す手を出さない＝出した提案は適用ゲートを通る。 */
    @Test fun suggesterDoesNotProposePinBreakingOps() {
        val st = state(ranges = mapOf("0,1" to Range("1", "1"), "1,1" to Range("1", "1"))); val s = sched(st)
        val sugs = FixSuggester.suggest(st, s)
        assertTrue(sugs.none { sug -> sug.ops.size == 1 && sug.ops[0].day == 0 && sug.ops[0].toShift == A })
        for (sug in sugs) {
            val r = FixApplyGate.apply(st, s, sug.ops)
            assertTrue("${sug.label}: $r", r is FixApplyGate.Outcome.Applied)
        }
    }

    /** 外部レビュー N10: ミニ再最適化（WINDOW）も回数固定を崩す組み合わせを提案しない。
     *  s0 は A、s1 は B しか担当できず、1 日目の不足を埋める唯一の 2 人組み合わせが両者の回数固定を崩す。 */
    @Test fun windowSuggestionDoesNotBreakPins() {
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-02",
            shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "1", "1"), Shift("B", "B", "1", "1")),
            groups = listOf(Group("G0", "G0"), Group("G1", "G1")), staff = listOf(Staff("s0", 0), Staff("s1", 1)), use2Patterns = true,
            groupShift = listOf(listOf(1, 1, 0), listOf(1, 0, 1)), groupShiftApt = listOf(listOf("", "", ""), listOf("", "", "")),
            schedule = listOf(listOf(REST, A), listOf(REST, 2)), wishes = emptyMap(),
            staffRange = mapOf("0,1" to Range("1", "1"), "1,2" to Range("1", "1")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val s = sched(st)
        val sugs = FixSuggester.suggest(st, s)
        for (sug in sugs) {
            val r = FixApplyGate.apply(st, s, sug.ops)
            assertTrue("${sug.kind} ${sug.label}: $r", r is FixApplyGate.Outcome.Applied)
        }
    }

    @Test fun improvingOpsAreAppliedToACopy() {
        val st = state(); val s = sched(st)
        val r = FixApplyGate.apply(st, s, listOf(FixCell(0, 0, A)))
        assertTrue(r is FixApplyGate.Outcome.Applied)
        r as FixApplyGate.Outcome.Applied
        assertTrue(r.after.hard < r.before.hard)
        assertEquals(A, r.schedule[0][0]); assertEquals(REST, s[0][0])   // 入力は不変
    }

    @Test fun nonImprovingAndPinBreakingOpsAreRejected() {
        val st = state(); val s = sched(st)
        // 2 日目の過剰(covO, SOFT)を減らす手は hard 同値・重み減＝改善として通る
        assertTrue(FixApplyGate.apply(st, s, listOf(FixCell(0, 1, REST))) is FixApplyGate.Outcome.Applied)
        // 既に反映済みの手を再提案しても盤面は同じ＝改善なしで拒否
        val s2 = sched(st); s2[0][0] = A
        val same = FixApplyGate.apply(st, s2, listOf(FixCell(0, 0, A)))
        assertTrue(same is FixApplyGate.Outcome.Rejected)
        assertArrayEquals(intArrayOf(A, A), s2[0])
        // 希望固定セルを変える提案は拒否
        val locked = state(wishes = mapOf("0,0" to REST))
        assertTrue(FixApplyGate.apply(locked, sched(locked), listOf(FixCell(0, 0, A))) is FixApplyGate.Outcome.Rejected)
        // 厳密ピン（s0 の A は 1〜1）を 2 にする提案は拒否（hard は減るが固定を崩す）
        val pinned = state(ranges = mapOf("0,$A" to Range("1", "1")))
        val pr = FixApplyGate.apply(pinned, sched(pinned), listOf(FixCell(0, 0, A)))
        assertTrue(pr is FixApplyGate.Outcome.Rejected)
    }
}
