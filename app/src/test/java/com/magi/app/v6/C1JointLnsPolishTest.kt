package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.255.0, 受領・検証のうえ適用] C1JointLnsPolish単体の検証。実データ(golden_state.json/
 * sample_state_v6.json、ホストJVM実行)で、既存パイプライン(Window+C1TemporalFlowPolish+BeamWide等)
 * 適用後にも追加で改善を見つけること(sample_state_v6.jsonでHARD 5->4)を確認済み。
 */
class C1JointLnsPolishTest {
    @Test
    fun resolvesSimpleC1DeficiencyWithSameDaySwapPartner() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G", "G"))
        val staff = listOf(Staff("target", 0), Staff("partner", 0))
        val target = listOf(1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0)
        val partner = target.map { if (it == 1) 0 else 1 }
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-11",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = listOf(target, partner),
            wishes = emptyMap(),
            // [3.256.0, 厳密ピン保護追加に伴う訂正] 実際に見つかる同日swap束は target の X 回数を
            //   4→6 へ変える（窓[6-10]の充足には既存4回の再配置でなく純増が必要と判明・手計算で確認済み）。
            //   旧 Range("4","4")（意図せぬ厳密ピン）は新設の exactPinRegression 保護に正しく拒否される
            //   ため、本テストの主旨（同日swap束によるc1解消）に無関係な下限4のみへ緩和。
            staffRange = mapOf("0,1" to Range("4", "")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, before.breakdown["c1"] ?: 0)
        assertEquals(0, before.hard)

        val out = C1JointLnsPolish.apply(
            st, sched,
            C1JointLnsPolish.Config(maxMillis = 2000L, maxRestarts = 2, maxDepth = 3),
        )
        val after = UnifiedViolationChecker.check(st, out.newSchedule)
        assertEquals(0, after.breakdown["c1"] ?: -1)
        assertEquals(0, after.hard)
        assertTrue("何らかの手が採用されている", out.applied > 0)
        assertTrue("totalが真に改善する", after.total < before.total)
    }

    @Test
    fun neverProposesAMoveThatWouldCreateAForbiddenRunAndStillFixesTheReachableWindow() {
        // [賢く再構成] generateMovesにc3n(禁止連続)事前フィルタを追加。day0=Y固定(希望)・day1にX
        // を置くと「Y,X」禁止連続になる構成で、①day1へは絶対にXが置かれない(HARD不変・c3n=0のまま)
        // ②同時に別窓(day1-2/day2-3)はday2へXを置くことで正しく解消できる、の両方を確認する。
        // 事前フィルタが無くても最終正しさ(isFinalCandidate+defensive re-check)は保たれる設計だが、
        // これは「事前に弾いても解ける能力を失っていない」ことの回帰ガード。
        val shifts = listOf(Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("X", "X", "", ""), Shift("Y", "Y", "", ""))
        val groups = listOf(Group("G", "G"))
        val staff = listOf(Staff("target", 0))
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-04",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)),
            groupShiftApt = listOf(listOf("", "", "")),
            schedule = listOf(listOf(2, 0, 0, 0)),
            wishes = mapOf("0,0" to 2),
            staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "2", shiftKigou = "X", day2 = "1")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(C3Row(listOf("Y", "X"))),
            cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("開始時にc1違反があること", (before.breakdown["c1"] ?: 0) > 0)
        assertEquals(0, before.breakdown["c3n"] ?: 0)

        val out = C1JointLnsPolish.apply(
            st, sched,
            C1JointLnsPolish.Config(maxMillis = 3000L, maxRestarts = 3, maxDepth = 3),
        )
        val after = UnifiedViolationChecker.check(st, out.newSchedule)
        assertEquals("c3nは一切発生しないこと", 0, after.breakdown["c3n"] ?: 0)
        assertEquals(0, after.hard)
        assertEquals("day1はXへ絶対に置かれない(禁止連続を作るため)", 0, out.newSchedule[0][1])
        assertTrue("day1-2/day2-3窓はday2のXで解消され、c1違反が減ること", (after.breakdown["c1"] ?: 0) < (before.breakdown["c1"] ?: 0))
    }

    @Test
    fun isNoOpWhenNoCons1Rules() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G", "G"))
        val staff = listOf(Staff("a", 0))
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-03",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(0, 1, 0)), wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val out = C1JointLnsPolish.apply(st, sched)
        assertEquals(0, out.applied)
    }

    /**
     * [3.312.0] 「構造下限」は c1 の**真の**下限でなければならない。個人回数(low/high)は SOFT で、
     * c1(15) より重いだけであって禁止ではない。旧実装は `rangeHi` を count の硬い上限として DP に
     * 課しており、「rangeHi を超えない範囲での c1 最小値」を返していた＝真の下限より大きくなり、
     * `best.c1 <= lowerBound` の早期終了と「構造下限到達」のログを誤って発火させていた。
     *
     * 反例: T=7・「4日窓で X を1回以上」・X の個人上限 0。X を1つも置かなければ 4窓すべて違反で
     * c1=4（weighted 60）。中央に X を1つ置けば c1=0・high=1（weighted 45）＝betterReport は
     * こちらを選ぶ。したがって c1 の下限は 0 であって 4 ではない。
     */
    @Test
    fun structuralLowerBoundIgnoresSoftPersonalCaps() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-07",
            shifts = shifts, groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = listOf(List(7) { 0 }),
            wishes = emptyMap(),
            staffRange = mapOf("0,1" to Range("", "0")),   // X の個人上限 0（SOFT）
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "4", shiftKigou = "X", day2 = "1")),
            cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = Problem(st)
        assertEquals(
            "SOFT の個人上限は c1 の構造下限を押し上げてはいけない",
            0, C1JointLnsPolish.structuralC1LowerBound(p),
        )
    }

    /** 構造下限は、希望固定を守った全割当の総当たり最小と一致する（同じ希望の並びの職員は同じ値を足す）。 */
    @Test
    fun structuralLowerBoundMatchesBruteForceUnderWishes() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val t = 10
        val lockedY = setOf(1, 2, 3, 6)
        val lockedX = setOf(8)
        val wishes = HashMap<String, Int>()
        for (i in 0..1) { for (j in lockedY) wishes["$i,$j"] = 0; for (j in lockedX) wishes["$i,$j"] = 1 }
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-10",
            shifts = shifts, groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = List(2) { List(t) { 0 } },
            wishes = wishes,
            staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "4", shiftKigou = "X", day2 = "2")),
            cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        var best = Int.MAX_VALUE
        for (bits in 0 until (1 shl t)) {
            val x = BooleanArray(t) { (bits shr it) and 1 == 1 }
            if (lockedY.any { x[it] } || lockedX.any { !x[it] }) continue
            var viol = 0
            for (start in 0..t - 4) if ((start until start + 4).count { x[it] } < 2) viol++
            best = minOf(best, viol)
        }
        assertTrue(best > 0)
        assertEquals(2 * best, C1JointLnsPolish.structuralC1LowerBound(Problem(st)))
    }

    /**
     * [3.342.0] 停滞打ち切り（`patienceMs`）を入れても keep-best は壊れない。
     *
     * 打ち切りは「最良が更新されないまま時間が過ぎたら止める」だけなので、最悪でも root がそのまま
     * 返る。**単調性（patience を伸ばすほど良くなる）は時間ベースで実行速度に依存するため固定しない**
     * （3.340.0 のビームは回数ベースだったので単調性まで固定できたが、ここは別）。
     */
    @Test
    fun patienceNeverProducesAResultWorseThanTheInput() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G", "G"))
        val staff = listOf(Staff("target", 0), Staff("partner", 0))
        val target = listOf(1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0)
        val partner = target.map { if (it == 1) 0 else 1 }
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-11",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = listOf(target, partner),
            wishes = emptyMap(),
            staffRange = mapOf("0,1" to Range("4", "")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        for (patience in listOf(1L, 50L, 2_000L, 0L)) {
            val out = C1JointLnsPolish.apply(
                st, sched,
                C1JointLnsPolish.Config(maxMillis = 2000L, maxRestarts = 2, maxDepth = 3, patienceMs = patience),
            )
            val after = UnifiedViolationChecker.check(st, out.newSchedule)
            assertTrue(
                "patience=$patience で入力より悪化した: " +
                    "${before.hard}/${before.weightedScore}/${before.total} -> " +
                    "${after.hard}/${after.weightedScore}/${after.total}",
                !betterReport(before, after),
            )
        }
    }

    /** [3.654.0/外部レビュー] 本人の別日を戻す手（自己日交換）は、j に x を置くだけなら禁止の並びになっても、両方のセルを当てた行では
     *  並びが消える。旧: generateMoves の冒頭で「j に x」だけを見て、この goal の候補を丸ごと捨てていた（合法な改善手の取りこぼし）。 */
    @Test
    fun selfDaySwapSurvivesWhenOnlyTheSingleCellPlacementWouldBeForbidden() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-03",
            shifts = shifts, groups = listOf(Group("G", "G")), staff = listOf(Staff("target", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1, 0, 0)),   // X, Y, Y
            wishes = mapOf("0,2" to 0),           // 3 日目は Y の希望
            staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "2", shiftKigou = "X", day2 = "1")),
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(C3Row(listOf("X", "X"))), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        assertEquals(1, UnifiedViolationChecker.check(st, sched).breakdown["c1"])
        val out = C1JointLnsPolish.apply(st, sched, C1JointLnsPolish.Config(maxMillis = 2000L, maxRestarts = 2, maxDepth = 3))
        val after = UnifiedViolationChecker.check(st, out.newSchedule)
        assertEquals(listOf(0, 1, 0), out.newSchedule[0].toList())   // Y, X, Y
        assertEquals(0, after.breakdown["c1"])
        assertEquals(0, after.hard)
    }

    /** [3.655.0/外部レビュー No.8] c1 がすべて構造下限なら目標は「対象なし」（旧: 何もしていないのに「到達」）。 */
    @Test
    fun targetIsNotApplicableWhenEveryC1IsStructural() {
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-02",
            shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", "")), groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(0, 0)),
            wishes = mapOf("0,0" to 0, "0,1" to 0),   // 2 日とも Y の希望＝2 日窓の X は置けない
            staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row(day1 = "2", shiftKigou = "X", day2 = "1")),
            cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val out = C1JointLnsPolish.apply(st, st.schedule.toIntArray2D())
        val msg = out.logs.single().message
        assertEquals(0, out.applied)
        assertTrue(msg, msg.contains("目標=対象なし"))
        assertTrue(msg, msg.contains("停止=構造下限到達"))
    }
}
