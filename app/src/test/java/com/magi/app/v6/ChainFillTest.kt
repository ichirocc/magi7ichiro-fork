package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // [玉突きの三連] 3人の交代連鎖でしか埋まらない局面: P<-Q<-R<-S（末端Sが過剰=余裕）。
    // a(Q担当) が P へ、b(R担当) が Q へ、c(S担当) が R へ動いて初めて covU=0 になり、
    // どの1人の直接移動でも別の covU を生むだけの「深さ3」を BFS が正しく踏むことを確認する。
    @Test
    fun chainFillSolvesDepth3Cascade() {
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("P", "P", "1", ""), Shift("Q", "Q", "1", ""),
            Shift("R", "R", "1", ""), Shift("S", "S", "1", ""),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"), Group("G3", "G3"))
        // G0=休/P/Q, G1=休/Q/R, G2=休/R/S, G3=休/S（各群は隣接シフトのみ担当可＝連鎖を1本道にする）
        val groupShift = listOf(
            listOf(1, 1, 1, 0, 0),
            listOf(1, 0, 1, 1, 0),
            listOf(1, 0, 0, 1, 1),
            listOf(1, 0, 0, 0, 1),
        )
        val staff = listOf(Staff("a", 0), Staff("b", 1), Staff("c", 2), Staff("d", 3))
        // a=Q, b=R, c=S, d=S（Sが2人＝過剰=末端の余裕）
        val schedule = listOf(listOf(2), listOf(3), listOf(4), listOf(4))
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(4) { List(5) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("P不足(covU>0)が前提", (before.breakdown["covU"] ?: 0) > 0)

        val chain = findCovUChain(p, sched, 1, 0, Random(11))
        assertNotNull("3人連鎖が見つかること", chain)
        assertEquals("深さ3(3手)の連鎖であること", 3, chain!!.size)
        for (mv in chain) sched[mv[0]][mv[1]] = mv[2]

        assertEquals("a=P, b=Q, c=R, d=S(不変)", listOf(1, 2, 3, 4), listOf(sched[0][0], sched[1][0], sched[2][0], sched[3][0]))
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("covU が解消されること", 0, after.breakdown["covU"] ?: 0)
        assertTrue("hard は悪化しない", after.hard <= before.hard)
    }

    // [玉突きの五連] 5人の交代連鎖: P<-Q<-R<-S<-T<-U（末端Uが過剰=余裕）。maxDepth=5 の上限まで
    // BFS が正しく踏み、5手全てを一括で返すことを確認する（深さ3と同型の一本道を1段長くしたもの）。
    @Test
    fun chainFillSolvesDepth5Cascade() {
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("P", "P", "1", ""), Shift("Q", "Q", "1", ""),
            Shift("R", "R", "1", ""), Shift("S", "S", "1", ""), Shift("T", "T", "1", ""), Shift("U", "U", "1", ""),
        )
        val groups = listOf(
            Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"),
            Group("G3", "G3"), Group("G4", "G4"), Group("G5", "G5"),
        )
        // G0=休/P/Q ... G4=休/T/U, G5=休/U（末端）。各群は隣接シフトのみ担当可＝連鎖を1本道にする。
        val groupShift = listOf(
            listOf(1, 1, 1, 0, 0, 0, 0),
            listOf(1, 0, 1, 1, 0, 0, 0),
            listOf(1, 0, 0, 1, 1, 0, 0),
            listOf(1, 0, 0, 0, 1, 1, 0),
            listOf(1, 0, 0, 0, 0, 1, 1),
            listOf(1, 0, 0, 0, 0, 0, 1),
        )
        val staff = listOf(Staff("a", 0), Staff("b", 1), Staff("c", 2), Staff("d", 3), Staff("e", 4), Staff("f", 5))
        // a=Q, b=R, c=S, d=T, e=U, f=U（Uが2人＝過剰=末端の余裕）
        val schedule = listOf(listOf(2), listOf(3), listOf(4), listOf(5), listOf(6), listOf(6))
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(6) { List(7) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("P不足(covU>0)が前提", (before.breakdown["covU"] ?: 0) > 0)

        val chain = findCovUChain(p, sched, 1, 0, Random(13))
        assertNotNull("5人連鎖が見つかること", chain)
        assertEquals("深さ5(5手)の連鎖であること", 5, chain!!.size)
        for (mv in chain) sched[mv[0]][mv[1]] = mv[2]

        assertEquals(
            "a=P,b=Q,c=R,d=S,e=T,f=U(不変)",
            listOf(1, 2, 3, 4, 5, 6),
            listOf(sched[0][0], sched[1][0], sched[2][0], sched[3][0], sched[4][0], sched[5][0]),
        )
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("covU が解消されること", 0, after.breakdown["covU"] ?: 0)
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

    // [三連/五連など任意長への配慮] 長さ2の枝刈りしか見ていないと、三連禁止(P,P,P)を新たに作る手を
    // 素通ししてしまう。a=[P,Q,P]・b=[休,休,休] で day1 の P不足を埋める候補は a/b の2人だが、
    // a の day1 を P にすると day0,1,2=P,P,P で三連禁止に触れる。b は無関係なので安全。
    // findCovUChain が a を枝刈りし、b だけを使う連鎖（1手）に着地することを確認する。
    @Test
    fun chainFillAvoidsTripleForbiddenRun() {
        // shift: 0=休 1=P(need1・cons3n=P,P,P三連禁止) 2=Q
        val shifts = listOf(Shift("休", "休", "", ""), Shift("P", "P", "1", ""), Shift("Q", "Q", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1, 1))
        val staff = listOf(Staff("a", 0), Staff("b", 0))
        val schedule = listOf(
            listOf(1, 2, 1),   // a: P, Q, P（day1 を P にすると P,P,P で三連禁止）
            listOf(0, 0, 0),   // b: 休, 休, 休（day1 を P にしても無関係で安全）
        )
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-03",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(1) { List(3) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(C3Row(listOf("P", "P", "P"))),
            cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        // 前提: day1 は P が0人(covU)・三連はまだ未成立（a は P,Q,P で Q が割って入っている）。
        assertTrue("day1 の P不足(covU)が前提", (before.breakdown["covU"] ?: 0) > 0)
        assertEquals(0, before.breakdown["c3n"] ?: 0)

        val chain = findCovUChain(p, sched, 1, 1, Random(3))
        assertNotNull("day1 の P不足を埋める連鎖が見つかること", chain)
        for (mv in chain!!) sched[mv[0]][mv[1]] = mv[2]

        assertEquals("a の行は不変（三連トラップを避けて動かさない）", listOf(1, 2, 1), sched[0].toList())
        assertEquals("b の day1 が P で埋まる", 1, sched[1][1])
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("covU が解消されること", 0, after.breakdown["covU"] ?: 0)
        assertEquals("三連禁止(c3n)を新たに作らないこと", 0, after.breakdown["c3n"] ?: 0)
    }

    // Problem.makesForbiddenRun 自体の直接検証（三連・五連）。
    @Test
    fun makesForbiddenRunDetectsTripleAndQuintuple() {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("P", "P", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("a", 0))
        fun stateWith(sched: List<Int>, cons3n: List<C3Row>) = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-0${sched.size}",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = List(1) { List(2) { "" } },
            schedule = listOf(sched), wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = cons3n, cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        // 三連禁止(P,P,P)。row = P,休,P,休,休（休=0,P=1）。
        val row3 = listOf(1, 0, 1, 0, 0)
        val p3 = cachedProblem(stateWith(row3, listOf(C3Row(listOf("P", "P", "P")))))
        val sc3 = row3.toIntArray()
        // position1(休)をPにすると positions0..2=P,P,P で三連成立。
        assertTrue("position1をPにすると三連禁止に触れる", p3.makesForbiddenRun(arrayOf(sc3), 0, 1, 1))
        // position3(休)をPにすると positions1..3=休,P,P / positions2..4=P,P,休 のどちらも三連に届かない。
        assertFalse("position3をPにしても三連には届かない", p3.makesForbiddenRun(arrayOf(sc3), 0, 3, 1))

        // 五連禁止(P×5)。row = P,P,休,P,P。position2(休)をPにすると全区間が P,P,P,P,P で五連成立。
        val row5 = listOf(1, 1, 0, 1, 1)
        val p5 = cachedProblem(stateWith(row5, listOf(C3Row(List(5) { "P" }))))
        assertTrue("position2をPにすると五連禁止に触れる", p5.makesForbiddenRun(arrayOf(row5.toIntArray()), 0, 2, 1))
    }
}
