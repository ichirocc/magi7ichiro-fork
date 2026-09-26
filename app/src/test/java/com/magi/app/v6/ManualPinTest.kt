package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.ManualPin
import com.magi.app.model.StateParser
import com.magi.app.model.pinAt
import com.magi.app.model.togglePin
import com.magi.app.model.withPinsFollowing
import com.magi.app.ui.PIN_BLOCKED_NOTE
import com.magi.app.ui.cellStatusLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [#41] 手動固定: 最適化器・後処理・1 手の提案・S5/S6 は固定セルを書き換えない。手の編集は可で値が追従する。
 * 採点（hard/soft/pref/weighted）は固定の有無で変わらない。手動固定＞規則 A の「希望へ戻す」＞通常の書き換え。
 */
class ManualPinTest {
    private fun sample(): MagiState =
        StateParser.parse(javaClass.getResourceAsStream("/sample_state_v6.json")!!.bufferedReader().readText())!!

    /** 実データ（sample）に手動固定を置く: 各職員の 4 日おき＋希望と違う値で固定した未反映の希望セル 1 つ（手動＞希望）。 */
    private data class Fixture(val st: MagiState, val board: Array<IntArray>, val conflict: Pair<Int, Int>)

    private fun fixture(): Fixture {
        val st0 = sample()
        val p0 = Problem(st0)
        val board = p0.initialAssignment()
        val (ci, cj) = (0 until p0.S).flatMap { i -> (0 until p0.T).map { j -> i to j } }
            .first { (i, j) -> p0.wishFixed(i, j) && p0.allowedShiftsForStaff(i).any { it != p0.wish[i][j] } }
        board[ci][cj] = p0.allowedShiftsForStaff(ci).first { it != p0.wish[ci][cj] }
        val pins = LinkedHashMap<Pair<Int, Int>, ManualPin>()
        for (i in 0 until p0.S) for (j in (i % 4) until p0.T step 4) pins[i to j] = ManualPin(i, j, board[i][j])
        pins[ci to cj] = ManualPin(ci, cj, board[ci][cj])
        val st = st0.copy(schedule = board.map { it.toList() }, manualPins = pins.values.toList())
        return Fixture(st, board, ci to cj)
    }

    private fun assertPinsHeld(msg: String, st: MagiState, s: Array<IntArray>) {
        for (m in st.manualPins) assertEquals("$msg: (${m.staff},${m.day})", m.shift, s[m.staff][m.day])
    }

    // 受け入れ 1・3: 最適化（入口→探索→後処理→最終番兵）のあとも固定の値のまま。規則 A の ON/OFF・アルゴリズムを問わない。
    @Test fun optimizeKeepsPinnedValuesAcrossFlagsAndAlgorithms() = runBlocking {
        val f = fixture()
        val original = PolishGate.wishPinStrict
        try {
            for (strict in listOf(true, false)) for (algo in listOf(V6Algorithm.V5, V6Algorithm.ALNS)) {
                PolishGate.wishPinStrict = strict
                val res = V6FinalPort.handleOptimize(f.st, f.board.copy2D(), secondsRaw = 2, workers = 1,
                    requestedAlgorithm = algo, allowImpossible = true, seed = 11L)
                assertPinsHeld("strict=$strict $algo", f.st, res.schedule)
                val (ci, cj) = f.conflict
                assertTrue("手動固定は希望へ戻さない", res.schedule[ci][cj] != Problem(f.st).wish[ci][cj])
            }
        } finally {
            PolishGate.wishPinStrict = original
        }
    }

    @Test fun deterministicPostProcessingAndEntryRepairKeepPins() {
        val f = fixture()
        val moved = f.board.copy2D().also { it[f.conflict.first][f.conflict.second] = Problem(f.st).wish[f.conflict.first][f.conflict.second] }
        val entry = HardRepairCore.hf67HardRepair(f.st, moved, Random(7)).schedule
        assertPinsHeld("入口は固定の値へ戻す（希望より強い）", f.st, entry)
        val post = V6HotfixPasses.runPostOptimization(f.st, entry, "t", seed = 1L, deadlineMs = EngineClock.nowMs() + 3_600_000L,
            params = V6HotfixPasses.PostOptimizationParams(deterministic = true, c1LnsMaxEvaluations = 5_000, personalLnsMaxEvaluations = 5_000))
        assertPinsHeld("後処理", f.st, post.schedule)
    }

    // 受け入れ 1（1 手）: 直し方は固定セルを動かす手を出さず、ゲートも固定セルへの手を拒む。
    @Test fun fixSuggesterNeverProposesAPinnedCellAndTheGateRejectsOne() {
        val f = fixture()
        val p = Problem(f.st)
        val sugg = FixSuggester.suggest(f.st, f.board, maxResults = 20, deadlineMs = 4000L)
        for (s in sugg) for (op in s.ops) assertFalse("${s.label} が固定セル (${op.staff},${op.day}) を動かす", p.pinned(op.staff, op.day))
        val m = f.st.manualPins.first()
        val other = p.allowedShiftsForStaff(m.staff).first { it != m.shift }
        val out = FixApplyGate.apply(f.st, f.board, listOf(FixCell(m.staff, m.day, other)))
        assertTrue(out is FixApplyGate.Outcome.Rejected && out.reason.contains("手動固定"))
    }

    @Test fun ruleAPriorityManualPinBeatsReturnToWish() {
        val f = fixture()
        val p = Problem(f.st)
        val (ci, cj) = f.conflict
        val cur = f.board[ci][cj]
        assertTrue(p.wishLocked(ci, cj)); assertEquals(cur, p.lockTo(ci, cj))
        assertFalse("未反映の希望セルでも手動固定なら希望へ戻さない", p.wishMoveAllowed(ci, cj, cur, p.wish[ci][cj], strict = true))
        assertFalse("規則 A を切っても手動固定は動かない", p.wishMoveAllowed(ci, cj, cur, p.wish[ci][cj], strict = false))
        assertTrue(p.wishMoveAllowed(ci, cj, cur, cur, strict = false))
        val cand = f.board.copy2D().also { it[ci][cj] = p.wish[ci][cj] }
        assertFalse(p.keepsWishPins(f.board, cand, strict = false))
        assertTrue(p.keepsWishPins(f.board, f.board, strict = false))
    }

    @Test fun personSwapKickSkipsPinnedDaysEvenWithTheWishGateOff() {
        val f = fixture()
        val p = Problem(f.st)
        for (seed in 1L..4L) {
            val b = f.board.copy2D()
            V6NativeOptimizer.personSwapKick(p, b, Random(seed), pairs = 3, wishPinStrict = false)
            assertPinsHeld("seed=$seed", f.st, b)
        }
    }

    // 受け入れ 6: 採点は固定の有無で同じ（hard/soft/pref/weighted と族の内訳、評価器の生スコア）。
    @Test fun scoringIsIdenticalWithAndWithoutPins() {
        val f = fixture()
        val bare = f.st.copy(manualPins = emptyList())
        val a = UnifiedViolationChecker.check(f.st, f.board.copy2D())
        val b = UnifiedViolationChecker.check(bare, f.board.copy2D())
        assertEquals(b.hard, a.hard); assertEquals(b.soft, a.soft); assertEquals(b.total, a.total)
        assertEquals(b.weightedScore, a.weightedScore, 0.0)
        assertEquals(b.breakdown, a.breakdown)
        assertTrue("固定に当たった違反も数える（pref を含む）", (a.breakdown["pref"] ?: 0) > 0)
        assertEquals(Evaluator(Problem(bare)).fullEval(f.board), Evaluator(Problem(f.st)).fullEval(f.board))
    }

    // 受け入れ 2: 手の編集は可。固定は残り、値が追従する。
    @Test fun manualEditKeepsThePinAndUpdatesTheValue() {
        val st = sample().copy(manualPins = listOf(ManualPin(0, 1, 0), ManualPin(2, 3, 1)))
        val ns = st.withPinsFollowing(listOf(0 to 1), 4)
        assertEquals(ManualPin(0, 1, 4), ns.pinAt(0, 1))
        assertEquals(ManualPin(2, 3, 1), ns.pinAt(2, 3))
        assertTrue("固定に当たらなければ同じ state", st.withPinsFollowing(listOf(5 to 5), 2) === st)
    }

    // 受け入れ 4: 保存→読込で残る。キーが無ければ固定なし。
    @Test fun jsonRoundTripKeepsPinsAndAMissingKeyMeansNone() {
        val st = sample().copy(manualPins = listOf(ManualPin(0, 1, 0), ManualPin(9, 30, 3)))
        val back = StateParser.parse(StateParser.serialize(st, st.schedule.toIntArray2D()))!!
        assertEquals(st.manualPins, back.manualPins)
        assertEquals(emptyList<ManualPin>(), sample().manualPins)
        assertFalse(javaClass.getResourceAsStream("/sample_state_v6.json")!!.bufferedReader().readText().contains("manualPins"))
    }

    // 受け入れ 5: 付け外しと値は MagiState にあるので、元に戻す（MagiState の写しを戻す）で一緒に戻る。
    @Test fun toggleIsReversibleAndCarriesTheValueForUndo() {
        val st = sample()
        val on = st.togglePin(3, 4, 2)
        assertEquals(ManualPin(3, 4, 2), on.pinAt(3, 4))
        val edited = on.withPinsFollowing(listOf(3 to 4), 5)
        assertEquals(5, edited.pinAt(3, 4)!!.shift)
        assertEquals("外す", st.manualPins, on.togglePin(3, 4, 2).manualPins)
        assertEquals("写しを戻せば値も戻る", ManualPin(3, 4, 2), on.pinAt(3, 4))
    }

    @Test fun cellStatusLineSaysAPinnedViolationCannotBeFixed() {
        val f = fixture()
        val (ci, cj) = f.conflict
        val p = Problem(f.st)
        val pinned = cellStatusLine(f.st, p, f.board, ci, cj, listOf("pref"))
        assertTrue(pinned.text, pinned.text.contains(PIN_BLOCKED_NOTE))
        val bare = f.st.copy(manualPins = emptyList())
        assertFalse(cellStatusLine(bare, Problem(bare), f.board, ci, cj, listOf("pref")).text.contains(PIN_BLOCKED_NOTE))
    }

    @Test fun wishTrialExcludesPinnedCells() {
        val f = fixture()
        val (ci, cj) = f.conflict
        assertFalse(VioKeyish(ci, cj) in WishTrial.lockedWishKeys(f.st))
        assertTrue(VioKeyish(ci, cj) in WishTrial.lockedWishKeys(f.st.copy(manualPins = emptyList())))
        assertNull(WishTrial.trial(f.st, f.board, ci, cj))
    }

    @Test fun smartInitialPlacesPinsFirst() = runBlocking {
        val f = fixture()
        val res = V6FinalPort.handleSmartInitial(f.st, allowImpossible = true)
        assertPinsHeld("初期解", f.st, res.schedule)
    }

    @Test fun structureEditsRemapPins() {
        val st = sample().copy(manualPins = listOf(ManualPin(0, 1, 0), ManualPin(2, 30, 3), ManualPin(4, 5, 1)))
        val sched = st.schedule.toIntArray2D()
        val moved = Ws1Ops.moveStaff(st, sched, 0, 1).state
        assertNotNull(moved.pinAt(1, 1)); assertNull(moved.pinAt(0, 1))
        val removed = Ws1Ops.removeStaff(st, sched, 2).state
        assertEquals(listOf(ManualPin(0, 1, 0), ManualPin(3, 5, 1)), removed.manualPins)
        assertEquals("消したシフトの固定は外れ、後ろは詰める", listOf(ManualPin(0, 1, 0), ManualPin(2, 30, 2)), Ws1Ops.removeShift(st, sched, 1).state.manualPins)
        assertEquals("期間の外は落とす", 2, Ws1Ops.resizeDays(st, sched, 30).state.manualPins.size)
    }

    private fun VioKeyish(i: Int, j: Int) = "$i,$j"
}
