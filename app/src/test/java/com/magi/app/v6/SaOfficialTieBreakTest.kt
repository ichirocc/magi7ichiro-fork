package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.570.0/backlog#19] `officialTieBreak` は計測専用の追加追跡＝有効にしても [SaOptimizer.run] の
 *  `schedule`/`score` は不変であることを固定する（崩れると tools/loop のペアベンチの前提が崩れる）。 */
class SaOfficialTieBreakTest {
    private fun state(): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-12",
        shifts = listOf(Shift("休", "休", "0", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "1", ""), Shift("B", "B", "1", "")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("s0", 0), Staff("s1", 0), Staff("s2", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(List(12) { 0 }, List(12) { 0 }, List(12) { 0 }),
        wishes = mapOf("0,1" to 1, "1,4" to 2, "2,9" to 1),
        cons1 = listOf(C1Row("5", "休", "1")),
        cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        staffRange = mapOf("0,0" to Range("2", "6"), "1,0" to Range("2", "6"), "2,0" to Range("2", "6")),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
    )

    @Test fun defaultOffLeavesOfficialFieldsNull() {
        val st = state()
        val p = Problem(st)
        val ev = Evaluator(p)
        val r = runBlocking { SaOptimizer(p, ev).run(SaParams(budgetMs = 300L, workers = 1, seed = 7L)) }
        assertNull("既定OFFでは計測フィールドを埋めない", r.officialBestSchedule)
        assertNull(r.officialBestReport)
    }

    @Test fun enablingItNeverChangesTheReturnedScheduleOrScore() {
        // [3.571.0/レビュー修正] budgetMs（壁時計）で off/on を比較すると、officialTieBreak が flush に足す
        //   UnifiedViolationChecker.check 分のわずかな追加コストが同じ時間内の到達反復数をずらし、
        //   同じ seed でも off/on が別の局面で打ち切られて偽陽性で落ちた（実測 seed=3）。呼出回数で打ち切る
        //   shouldStop に替える＝ループ構造（timeUp の呼出箇所・chain・冷却ラダー）は seed に依存しない
        //   決定的な制御フローなので、off/on は同じ呼出回数で同じ地点まで進み厳密に同一になる。
        val st = state()
        val p = Problem(st)
        fun runOnce(seed: Long, officialTieBreak: Boolean): SaResult {
            val calls = java.util.concurrent.atomic.AtomicInteger(0)
            val params = SaParams(
                budgetMs = 60_000L, workers = 1, seed = seed, officialTieBreak = officialTieBreak,
                shouldStop = { calls.incrementAndGet() > 500 },
            )
            return runBlocking { SaOptimizer(p, Evaluator(p)).run(params) }
        }
        for (seed in 1L..6L) {
            val off = runOnce(seed, officialTieBreak = false)
            val on = runOnce(seed, officialTieBreak = true)
            assertEquals("seed=$seed でスコアが変わった＝計測トグルが選定に混入している", off.score, on.score)
            for (i in st.staff.indices) assertEquals(
                "seed=$seed で盤面が変わった（職員$i）＝計測トグルが選定に混入している",
                off.schedule[i].toList(), on.schedule[i].toList(),
            )
            assertNotNull("officialTieBreak=true では計測フィールドを埋める", on.officialBestSchedule)
            assertNotNull(on.officialBestReport)
        }
    }

    @Test fun officialBestIsATrackedCandidateNotAFabrication() {
        // officialBestReport は betterReport の基準（hard→weightedScore→total）で単調に改善している
        // はず＝最終値が入口盤面（fullEval 未着手の初期解）以下（同等か改善）であることだけ固定する
        // （具体的な盤面はランダム近傍に依存するため、性質のみを検証する）。
        val st = state()
        val p = Problem(st)
        val ev = Evaluator(p)
        val init = p.initialAssignment()
        val initReport = UnifiedViolationChecker.check(st, init)
        val r = runBlocking {
            SaOptimizer(p, ev).run(SaParams(budgetMs = 300L, workers = 2, seed = 3L, officialTieBreak = true))
        }
        val rep = r.officialBestReport!!
        assertTrue(
            "officialBestReport が入口より正式基準で悪化した",
            reportComparator.compare(rep, initReport) <= 0,
        )
    }
}
