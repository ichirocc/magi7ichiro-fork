package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.StateParser
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [希望固定の徹底] 規則 A（2026-09-25 利用者決定、P2d でフラグを外して常時）: 盤面ごと・他の盤面から写す経路でも希望固定セルへ希望以外を書かない。
 *
 * 固定具は**希望どうしの衝突**（s0 の 2〜4 日目に休の希望 × 禁止の並び「休→休→休」）。希望を 1 つ崩すと
 * c3n(9000)→pref(8000) で HARD 件数は 1 のまま weighted だけ下がる＝keep-best は崩した盤面を採る。各経路で希望が残ることを固定する。
 */
class WishPinStrictTest {
    private val rest = 0
    private val a = 1
    private val wishDays = listOf(1, 2, 3)

    private fun selfConflictState(schedule: List<List<Int>>): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-05",
        shifts = listOf(Shift("休み", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("早番", "A", "", "")),
        groups = listOf(Group("G0", "G0")),
        staff = listOf(Staff("s0", 0), Staff("s1", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        schedule = schedule,
        wishes = wishDays.associate { "0,$it" to rest },
        staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = listOf(C3Row(listOf("休", "休", "休"))),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    /** 希望どおり（c3n が 1 件）。 */
    private val kept = listOf(listOf(a, rest, rest, rest, a), listOf(rest, a, a, a, rest))
    /** 3 日目の希望を崩した盤面（c3n→pref）。 */
    private val broken = listOf(listOf(a, rest, a, rest, a), listOf(rest, a, a, a, rest))

    private fun brokenWishes(p: Problem, s: Array<IntArray>): List<Pair<Int, Int>> =
        (0 until p.S).flatMap { i -> (0 until p.T).map { j -> i to j } }
            .filter { (i, j) -> p.wishLocked(i, j) && s[i][j] != p.wish[i][j] }

    @Test fun fixtureIsARealSelfConflictWhereBreakingTheWishWins() {
        val st = selfConflictState(kept)
        val keptRep = UnifiedViolationChecker.check(st, kept.toIntArray2D())
        val brokenRep = UnifiedViolationChecker.check(st, broken.toIntArray2D())
        assertEquals(1, keptRep.breakdown["c3n"] ?: 0)
        assertEquals(1, brokenRep.breakdown["pref"] ?: 0)
        assertEquals("HARD 件数は同じ", keptRep.hard, brokenRep.hard)
        assertTrue("崩した方が keep-best に勝つ（ガードが無いと漏れる前提）", betterReport(brokenRep, keptRep))
    }

    @Test fun keepsWishPinsAllowsRestoreAndCarryButNotANewBreak() {
        val st = selfConflictState(kept).copy(
            shifts = listOf(Shift("休み", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("早番", "A", "", ""), Shift("遅番", "B", "", "")),
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        )
        val p = Problem(st)
        val keptBoard = kept.toIntArray2D()
        val brokenBoard = broken.toIntArray2D()
        assertTrue("同じ盤面", p.keepsWishPins(keptBoard, keptBoard))
        assertFalse("希望どおりのセルを崩す", p.keepsWishPins(keptBoard, brokenBoard))
        assertTrue("入力で崩れていたセルを持ち越す", p.keepsWishPins(brokenBoard, brokenBoard))
        assertTrue("入力で崩れていたセルを希望へ戻す", p.keepsWishPins(brokenBoard, keptBoard))
        val other = broken.toIntArray2D().also { it[0][2] = 2 }
        assertFalse("崩れていたセルを別の希望外の値へ動かす", p.keepsWishPins(brokenBoard, other))
    }

    // (i) ELITE_RELINK の入口: 希望セルで食い違う相手へ再結合しても希望が残る。
    @Test fun elitePathRelinkKeepsWishes() {
        val st = selfConflictState(kept)
        val p = Problem(st)
        val on = EliteRelinking.elitePathRelink(st, kept.toIntArray2D(), listOf(broken.toIntArray2D()), { false }).first
        assertEquals(emptyList<Pair<Int, Int>>(), brokenWishes(p, on))
    }

    // (ii) PERSON_SWAP_ILS の摂動: 希望固定の日だけ交換せず、それ以外の日は入れ替わる。
    @Test fun personSwapKickSkipsWishDays() {
        val st = selfConflictState(kept)
        val p = Problem(st)
        for (seed in 1L..3L) {
            val on = kept.toIntArray2D()
            PortfolioSearch.personSwapKick(p, on, Random(seed), pairs = 1)
            assertEquals("seed=$seed: 希望固定の日は動かない", emptyList<Pair<Int, Int>>(), brokenWishes(p, on))
            assertEquals("seed=$seed: 希望の無い日は入れ替わる", listOf(rest, rest, rest, rest, rest), on[0].toList())
            assertEquals("seed=$seed: 相手も希望の日だけ残る", listOf(a, a, a, a, a), on[1].toList())
        }
    }

    private fun elite(st: MagiState, board: List<List<Int>>, bridge: Boolean): AdaptiveElite {
        val s = board.toIntArray2D()
        return AdaptiveElite(s, UnifiedViolationChecker.check(st, s), HypothesisEpochRole.ELITE_RELINK, worker = 1, epoch = 0, bridge = bridge)
    }

    // (iii) エリート統合の端点採用: 希望を崩したエリートは better でも採らない。
    @Test fun eliteIntegrationRejectsAnEndpointWithABrokenWish() {
        val st = selfConflictState(kept)
        val p = Problem(st)
        val out = EliteIntegrationPolish.apply(
            st, kept.toIntArray2D(), listOf(elite(st, broken, bridge = false)), { false },
            EngineClock.nowMs() + 10_000L,
        ).schedule
        assertEquals(emptyList<Pair<Int, Int>>(), brokenWishes(p, out))
    }

    // 端点としては採らない橋渡しエリートでも、それを起点にした relink の中間解が崩れを持ち込まない。
    @Test fun eliteIntegrationRelinkFromABrokenBridgeDoesNotCarryTheBreak() {
        val st = selfConflictState(kept)
        val p = Problem(st)
        val bridgeBoard = listOf(listOf(a, rest, a, rest, a), listOf(a, a, a, a, rest))
        val out = EliteIntegrationPolish.apply(
            st, kept.toIntArray2D(), listOf(elite(st, bridgeBoard, bridge = true)), { false },
            EngineClock.nowMs() + 10_000L,
        ).schedule
        assertEquals(emptyList<Pair<Int, Int>>(), brokenWishes(p, out))
    }

    // 規則 A のセル判定: 希望どおりのセルは動かさない・未反映は希望へだけ。
    @Test fun wishMoveAllowedIsRuleA() {
        val st = selfConflictState(kept).copy(
            shifts = listOf(Shift("休み", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("早番", "A", "", ""), Shift("遅番", "B", "", "")),
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        )
        val p = Problem(st)
        assertFalse("(1) 希望どおり→別の値", p.wishMoveAllowed(0, 1, rest, a))
        assertTrue("(2) 未反映→希望", p.wishMoveAllowed(0, 1, a, rest))
        assertFalse("(3) 未反映→希望でも今の値でもない", p.wishMoveAllowed(0, 1, a, 2))
        assertTrue("希望の無いセル", p.wishMoveAllowed(0, 0, a, 2))
    }

    private val sRest = Shift("休", "休", "", "", com.magi.app.model.ShiftRole.Rest)
    /** X・Y とも 1 日目に A（需要 1 → 1 人過剰）、B は受け皿。X は C（需要 0＝受け皿なし）を希望していて A のまま（未反映）。
     *  separateGroups: X と Y を別グループに置き fair を外す（2 人群では退避 1 セルが fair を 2 件動かし、covO 1 件(10)と同点＝3.647.0 fair 5）。 */
    private fun covOState(separateGroups: Boolean = false): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-01",
        shifts = listOf(sRest, Shift("A", "A", "1", ""), Shift("B", "B", "", ""), Shift("C", "C", "0", "")),
        groups = if (separateGroups) listOf(Group("G", "G"), Group("H", "H")) else listOf(Group("G", "G")),
        staff = listOf(Staff("X", 0), Staff("Y", if (separateGroups) 1 else 0)), use2Patterns = false,
        groupShift = if (separateGroups) listOf(listOf(1, 1, 1, 1), listOf(1, 1, 1, 1)) else listOf(listOf(1, 1, 1, 1)),
        groupShiftApt = if (separateGroups) listOf(listOf("", "", "", ""), listOf("", "", "", "")) else listOf(listOf("", "", "", "")),
        schedule = listOf(listOf(1), listOf(1)), wishes = mapOf("0,0" to 3),
        staffRange = mapOf("0,0" to Range("0", "0"), "1,0" to Range("0", "0")), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(), skillGroups = emptyList(), cons41s = emptyList(),
    )

    // 古泉 10/25 型: 未反映の希望セル（A）を covO 退避で B へ動かさない。希望の無い Y が退く。
    @Test fun covOReliefNeverMovesAnUnreflectedWishCellToAThirdShift() {
        val st = covOState(separateGroups = true)
        val on = CovOReliefPolish.apply(st, st.schedule.toIntArray2D()).newSchedule
        assertEquals("X は A のまま", 1, on[0][0]); assertEquals("Y が B へ", 2, on[1][0])
    }

    // RSI の covO 解消: X は 1・2 日目とも C を希望し「C→C」は禁止（希望どうしの衝突）。1 日目を希望へ戻すと
    // c3n(9000)>pref(8000) で悪化するので、旧挙動は第三のシフトへ逃がしていた。
    @Test fun rsiCovOFreeNeverMovesAnUnreflectedWishCellToAThirdShift() {
        val st = covOState().copy(
            endDate = "2026-08-02",
            shifts = listOf(sRest, Shift("A", "A", "1", ""), Shift("B", "B", "", ""), Shift("C", "C", "", "")),
            schedule = listOf(listOf(1, 3), listOf(1, 0)), wishes = mapOf("0,0" to 3, "0,1" to 3, "1,0" to 1),
            staffRange = emptyMap(), cons3n = listOf(C3Row(listOf("C", "C"))),
        )
        val on = st.schedule.toIntArray2D()
        RsiHypothesisOperators.applyCovOFree(st, on, Random(1))
        assertEquals("X は A のまま", 1, on[0][0])
    }

    // 入口 hf66 と最終番兵の基準: 上限 0 で外すセルが未反映の希望セルなら、埋めシフト（休）でなく希望（B）へ。
    @Test fun cappedCellWithAnUnreflectedWishIsRefilledWithTheWish() {
        val st = covOState().copy(wishes = mapOf("0,0" to 2), staffRange = mapOf("0,1" to Range("0", "0")))
        val board = st.schedule.toIntArray2D()
        assertEquals(2, HardRepairCore.hf66DataHardening(st, board, "t")[0][0])
        assertEquals(2, HardRepairCore.clearCappedCells(st, board).first[0][0])
    }

    // あとから足した希望（盤面は変えない＝setWish と同じ）: 規則 A は反映を妨げない（旧案「値によらず凍結」の退行の再発防止）。
    // 入口 hf67 → 決定的な後処理チェーンを走らせ、足した希望が載る（壁時計の探索は揺れるので測定側で見る）。
    // 共同 LNS の回数は既定だと golden でヒープ 1GB 超（CI のテスト JVM は 512MB）なので絞る。
    @Test fun addedWishIsReflected() {
        val st0 = StateParser.parse(javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText())!!
        val p0 = Problem(st0)
        val board = p0.initialAssignment().also { b -> for (i in 0 until p0.S) for (j in 0 until p0.T) if (p0.wishLocked(i, j)) b[i][j] = p0.wish[i][j] }
        val rng = Random(20260925L)
        var i: Int; var j: Int; var alts: List<Int>
        do {
            i = rng.nextInt(p0.S); j = rng.nextInt(p0.T)
            alts = p0.allowedShiftsForStaff(i).filter { it != board[i][j] && p0.mayPlace(i, it) }
        } while (p0.wish[i][j] >= 0 || alts.isEmpty())
        val k = alts[rng.nextInt(alts.size)]
        val st = st0.copy(wishes = st0.wishes + ("$i,$j" to k), schedule = board.map { it.toList() })
        assertTrue("足した希望は未反映から始まる", Problem(st).wishLocked(i, j) && board[i][j] != k)
        val entry = HardRepairCore.hf67HardRepair(st, board.copy2D(), Random(7)).schedule
        val on = V6HotfixPasses.runPostOptimization(st, entry, "t", seed = 1L, deadlineMs = EngineClock.nowMs() + 3_600_000L,
            params = V6HotfixPasses.PostOptimizationParams(deterministic = true,
                c1LnsMaxEvaluations = 5_000, personalLnsMaxEvaluations = 5_000)).schedule[i][j]
        assertEquals("($i,$j)->$k: 足した希望が載る", k, on)
    }
}
