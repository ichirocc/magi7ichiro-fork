package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.C3wRow
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 希望どうしの衝突（`V6SanityPort.wishSelfConflicts`）。実データ（11 名×31 日・必須 3＝下限）の 3 組を最小の形で写す:
 * 大島・古泉＝休の希望 3 連日×禁止「休→休→休」（c3n）、福澤＝Dﾃ の希望の翌日に休の希望×「休の希望の前日は Dﾃ 禁止」（c3w）。
 */
class WishSelfConflictTest {
    private val rest = 0; private val d = 1; private val a = 2

    private fun state(
        wishes: Map<String, Int>,
        schedule: List<List<Int>> = List(2) { List(7) { a } },
        cons3n: List<C3Row> = listOf(C3Row(listOf("休", "休", "休"))),
        cons3w: List<C3wRow> = emptyList(),
    ) = MagiState(
        startDate = "2026-10-01", endDate = "2026-10-07",
        shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("Dﾃ", "Dﾃ", "", ""), Shift("A", "A", "", "")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("大島", 0), Staff("福澤", 0)),
        use2Patterns = false, groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = emptyList(),
        schedule = schedule, wishes = wishes, staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = cons3n, cons3m = emptyList(),
        cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(), cons3w = cons3w,
    )

    private val restWindow = mapOf("0,2" to 0, "0,3" to 0, "0,4" to 0)

    @Test fun forbiddenWindowFullyWishedIsOneGroupWithAllDays() {
        val gs = V6SanityPort.wishSelfConflicts(state(restWindow + mapOf("1,2" to 0, "1,3" to 0)))
        assertEquals(1, gs.size)
        val g = gs[0]
        assertEquals(0, g.staff); assertEquals("c3n", g.family)
        assertEquals(listOf(2, 3, 4), g.days); assertEquals(listOf(rest, rest, rest), g.shifts)
        assertEquals(listOf("0,2", "0,3", "0,4"), g.wishKeys)
    }

    @Test fun overlappingWindowsAreSeparateAndDuplicateRowsCollapse() {
        val st = state(restWindow + mapOf("0,5" to 0), cons3n = List(2) { C3Row(listOf("休", "休", "休")) })
        assertEquals(listOf(listOf(2, 3, 4), listOf(3, 4, 5)), V6SanityPort.wishSelfConflicts(st).map { it.days })
    }

    @Test fun dayBeforeBanPairIsListedAndSanity1bKeepsItsText() {
        val st = state(mapOf("1,0" to d, "1,1" to 0), cons3n = emptyList(), cons3w = listOf(C3wRow("休", "Dﾃ")))
        val gs = V6SanityPort.wishSelfConflicts(st)
        assertEquals(1, gs.size)
        assertEquals("c3w", gs[0].family); assertEquals(listOf(0, 1), gs[0].days); assertEquals(listOf(d, rest), gs[0].shifts)
        val issue = V6SanityPort.buildGuidance(st).single { it.action == SettingFixAction.REMOVE_WISH }
        assertEquals("福澤 10/1(木) 希望「Dﾃ」→ 10/2(金) 希望「休」", issue.where)
        assertEquals("1,0", issue.wishKey)
    }

    @Test fun sanityNamesStaffAndEveryDayOfTheForbiddenWindow() {
        val issues = V6SanityPort.buildGuidance(state(restWindow)).filter { it.problem.contains("禁止の並び「休→休→休」に希望どうし") }
        assertEquals(1, issues.size)
        assertEquals(IssueKind.WISH, issues[0].kind)
        assertEquals("大島 10/3(土)・10/4(日)・10/5(月) 希望「休→休→休」", issues[0].where)
        assertEquals(SettingFixAction.NONE, issues[0].action)
    }

    @Test fun windowWithOneFreeCellIsNotASelfConflict() {
        assertTrue(V6SanityPort.wishSelfConflicts(state(mapOf("0,2" to 0, "0,4" to 0))).isEmpty())
        assertTrue(V6SanityPort.wishSelfConflicts(state(mapOf("0,2" to 0, "0,3" to a, "0,4" to 0))).isEmpty())
    }

    @Test fun prefCellFindsItsSiblingWishes() {
        // 古泉の形: 最適化器が 10/3 の希望を破って禁止の並びを避けた（pref 1）。10/4・10/5 の希望も取り消し候補。
        val sched = listOf(listOf(a, a, a, rest, rest, a, a), List(7) { a })
        val st = state(restWindow, schedule = sched)
        val rep = UnifiedViolationChecker.check(st)
        assertEquals(1, rep.breakdown["pref"]); assertEquals(0, rep.breakdown["c3n"])
        val siblings = V6SanityPort.wishSelfConflicts(st).filter { "0,2" in it.wishKeys }.flatMap { it.wishKeys }.toSet() - "0,2"
        assertEquals(setOf("0,3", "0,4"), siblings)
    }

    @Test fun selfConflictHardCountsOnePerDisjointGroup() {
        val st = state(restWindow + mapOf("0,5" to 0))
        val p = cachedProblem(st)
        val honored = arrayOf(intArrayOf(a, a, rest, rest, rest, rest, a), IntArray(7) { a })
        assertEquals(mapOf("c3n" to 1), V6SanityPort.wishSelfConflictHard(p, honored))
        assertEquals(2, UnifiedViolationChecker.check(st, honored).breakdown["c3n"])
        // 重なる 2 窓は真ん中の 1 件を破れば両方解ける＝下限は 1。
        val brokeMiddle = arrayOf(intArrayOf(a, a, rest, a, rest, rest, a), IntArray(7) { a })
        assertEquals(mapOf("pref" to 1), V6SanityPort.wishSelfConflictHard(p, brokeMiddle))
    }

    @Test fun hf70DoesNotCountSelfConflictAsHardOtherThanWishes() {
        val st = state(restWindow + mapOf("1,0" to d, "1,1" to 0), cons3w = listOf(C3wRow("休", "Dﾃ")))
        val sched = arrayOf(intArrayOf(a, a, rest, rest, rest, a, a), intArrayOf(d, rest, a, a, a, a, a))
        val rep = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, rep.breakdown["c3n"]); assertEquals(1, rep.breakdown["c3w"])
        val msg = HfSwapPolish.detectHF70Anomalies(st, sched, "t", rep).message
        assertTrue(msg, msg.contains("希望どうしの衝突 2 件"))
        assertFalse(msg, msg.contains("希望以外HARD"))
    }

    @Test fun residualAnalysisPutsSelfConflictInWalls() = runBlocking {
        val st = state(restWindow, schedule = listOf(List(7) { a }, List(7) { a }))
        val res = V6FinalPort.handleOptimize(st, secondsRaw = 1, workers = 1, requestedAlgorithm = V6Algorithm.V5, allowImpossible = true)
        assertEquals(1, res.report.hard)
        val line = res.logs.single { it.tag == "残存分析" }.message
        assertTrue(line, line.substringBefore("／").contains("希望どうしの衝突"))
        val open = line.substringAfter("まだ狙える: ")
        assertFalse(line, open.contains("c3n") || open.contains("pref"))
    }
}
