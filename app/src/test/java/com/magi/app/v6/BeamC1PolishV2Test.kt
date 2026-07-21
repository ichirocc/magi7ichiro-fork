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
 * [C1協調ビーム研磨] `BeamC1PolishV2` 単体の検証。盤面は事前に Python 等価実装
 * （/tmp/.../beam_c1_search3.py 相当）で網羅探索し、①同日swapを1回だけ試すと必ずタイ/悪化になり
 * 単独では採用されない ②2回の同日swap（別々の日、同じ2人）を束ねると初めて c1 が解消する、
 * という条件を数値的に確認済みの最小盤面を使う。既存の手A(同日2人swap)は1手ずつ試して即座に
 * 不採用・巻き戻すだけで combinable(3.249.0) にも記録しないため、この種の「単独で試したことのない
 * 同日手の組合せ」は本パスでしか解消できないことを固定する。
 */
class BeamC1PolishV2Test {
    // 休=0(Y)・X=1。T=7日, cons1="5日窓X>=2"。職員0(target)が窓[1-5]でX不足(1<2)。
    // 職員1・2はどちらも窓充足済みだが、職員0の不足日(1,3,4,5)にXを持つ日が一部あり、単独スワップ
    // すると相手側で新たな不足を生む(タイ)。日0での職員0<->1の追加swapで初めて全体が解消する。
    private fun coordinatedBundleState(): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("target", 0), Staff("partner1", 0), Staff("partner2", 0))
        val schedule = listOf(
            listOf(1, 0, 1, 0, 0, 0, 1),
            listOf(0, 1, 0, 0, 1, 1, 0),
            listOf(0, 1, 0, 1, 0, 0, 1),
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-07",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun beamPolishResolvesC1DeficiencyOnlyViaTwoDaySwapBundle() {
        val st = coordinatedBundleState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期はcons1窓不足が1件", 1, before.breakdown["c1"] ?: 0)
        assertEquals("初期HARD=0", 0, before.hard)

        val result = BeamC1PolishV2.apply(st, sched)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("協調ビーム研磨後はc1=0", 0, after.breakdown["c1"] ?: -1)
        assertEquals("HARDは悪化しない(0のまま)", 0, after.hard)
        assertTrue("実際に束が採用されている", result.applied > 0)

        // 単独の同日swapは必ず2セル(同じ日)しか変えない。今回の解は2つの異なる日にまたがる
        // 同日swapの束でしか成立しないことをPythonで確認済み＝差分が2日以上にまたがることで
        // 「単一の同日swapでは解けない」を検証する。
        val changedDays = (0 until 7).count { j ->
            (0 until 3).any { i -> sched[i][j] != result.newSchedule[i][j] }
        }
        assertTrue("変更は複数日にまたがる(単一同日swapでは解けない証拠)", changedDays >= 2)

        // 同日swap/3人回転のみで構成される手＝日別シフト多重集合(covU/covOの前提)は不変。
        for (j in 0 until 7) {
            val before2 = (0 until 3).map { sched[it][j] }.sorted()
            val after2 = (0 until 3).map { result.newSchedule[it][j] }.sorted()
            assertEquals("day=${j}の日別シフト多重集合は保存される", before2, after2)
        }
    }

    @Test
    fun beamPolishIsNoOpWhenNoC1Deficiency() {
        val st = coordinatedBundleState().copy(cons1 = emptyList())
        val sched = st.schedule.toIntArray2D()
        val result = BeamC1PolishV2.apply(st, sched)
        assertEquals(0, result.applied)
    }

    // [停滞脱出, ユーザー指摘「停滞脱出しないのか?」への対応] collectAnchors/generateMoves の候補走査
    // 順をseed由来のRandomでシャッフルするよう変更（旧: 常に職員index昇順の固定順でmaxAnchors/
    // maxDirectDonors/maxRotationsPerAnchorの上限に達し打ち切られていたため、候補がその上限を超える
    // データでは毎ラウンド同じ候補だけが試され続け、切り捨てられた側は永遠に試されなかった）。
    // ホストJVM実測(golden_state.json/sample_state_v6.json)ではこの修正単独でのヒット率変化は
    // 確認できなかった（診断: 既存パスが既に同種の候補をほぼ汲み尽くしているため。CLAUDE.md
    // 3.252.0参照）が、seedの有無に関わらず安全（keep-best退化不能）であることを広く固定する。
    @Test
    fun beamPolishNeverRegressesAcrossManySeeds() {
        val st = coordinatedBundleState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        for (seed in 0L until 30L) {
            val result = BeamC1PolishV2.apply(st, sched, seed = seed * 131L + 17L)
            val after = UnifiedViolationChecker.check(st, result.newSchedule)
            assertTrue(
                "seed=$seed: total悪化(before=${before.total}, after=${after.total})は許されない",
                after.total <= before.total,
            )
            assertEquals("seed=$seed: HARDは不変", before.hard, after.hard)
        }
    }
}
