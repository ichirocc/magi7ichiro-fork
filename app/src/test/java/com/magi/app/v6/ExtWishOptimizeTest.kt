package com.magi.app.v6

import com.magi.app.model.ExtWish
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拡張希望 第 8 節: 最適化器は禁止のシフトを新しく置かない（候補を作る前に捨てる）。
 * 敵対的な禁止＝禁止なしで最適化器が実際に置いたセルと値をそのまま禁止にし、入力の値を禁止にした既存の違反も混ぜる。
 * 最終番兵が段を戻した（候補生成の漏れ）ことも、後処理の段が禁止を置いたことも失敗として名指しする。
 */
class ExtWishOptimizeTest {
    private fun load(name: String): MagiState = StateParser.parse(javaClass.getResource("/$name")!!.readText())!!

    /** 禁止なしの最適化結果 [out0] から敵対的な拡張希望を作る（保存規則 sanitize を通す）。 */
    private fun adversarial(st: MagiState, input: Array<IntArray>, out0: Array<IntArray>): MagiState {
        val p = Problem(st)
        val start = java.time.LocalDate.parse(st.startDate)
        val kigou = st.shifts.map { it.kigou }
        var cur = st
        var n = 0
        for (i in 0 until p.S) for (j in 0 until p.T) {
            if (p.wish[i][j] >= 0 || p.pinned(i, j)) continue
            val ban = when {
                out0[i][j] != input[i][j] && (i + j) % 2 == 0 -> out0[i][j]   // 最適化器が置きたい値
                (i * 7 + j) % 11 == 0 -> input[i][j]                           // 入力に既にある違反
                else -> continue
            }
            if (ban !in 0 until p.K) continue
            val r = ExtWishRules.sanitize(cur, ExtWish(i, listOf(start.plusDays(j.toLong()).toString()), listOf(kigou[ban])))
            if (r.saved != null) { cur = cur.copy(extWishes = cur.extWishes + r.saved!!); n++ }
        }
        assertTrue("禁止が作れていない", n > 10)
        return cur
    }

    private fun check(label: String, st: MagiState, input: Array<IntArray>, out: Array<IntArray>, logs: List<MirrorLog>, offenders: Map<String, Int>) {
        val p = Problem(st)
        val base = HardRepairCore.clearCappedCells(st, p.withManualPins(normalizeSchedule(input, p))).first
        val fresh = p.extBanNewCells(base, out)
        val sentinel = logs.filter { "拡張希望の禁止が" in it.message }.map { it.message }
        assertTrue("$label: 最終盤面に禁止の新規配置 $fresh", fresh.isEmpty())
        assertTrue("$label: 最終番兵が段を戻した＝候補生成の漏れ $sentinel 後処理の段=$offenders", sentinel.isEmpty())
        assertTrue("$label: 後処理の段が禁止を新しく置いた $offenders", offenders.isEmpty())
    }

    private fun withProbe(base: () -> Array<IntArray>, p: Problem, body: () -> Unit): Map<String, Int> {
        val offenders = LinkedHashMap<String, Int>()
        var prev: Array<IntArray>? = null
        // 段ごとの責任: 直前の段の盤面から値が変わり、新しい値が禁止のセルの数。
        V6HotfixPasses.PostChain.stageProbe = { key, work ->
            val n = p.extBanNewCells(prev ?: base(), work).size
            if (n > 0) offenders.merge(key, n, Int::plus)
            prev = work.copy2D()
        }
        try { body() } finally { V6HotfixPasses.PostChain.stageProbe = null }
        return offenders
    }

    @Test fun optimizerNeverPlacesABannedShiftAcrossAlgorithms() = runBlocking {
        for (file in listOf("sample_state_v6.json", "sept2026_state.json")) {
            val st0 = load(file)
            val input = st0.schedule.toIntArray2D()
            val out0 = V6FinalPort.handleOptimize(st0, input.copy2D(), secondsRaw = 2, workers = 1,
                requestedAlgorithm = V6Algorithm.V5, allowImpossible = true, seed = 3L).schedule
            val st = adversarial(st0, input, out0)
            val p = Problem(st)
            val base = HardRepairCore.clearCappedCells(st, p.withManualPins(normalizeSchedule(input, p))).first
            for (algo in listOf(V6Algorithm.V5, V6Algorithm.ALNS, V6Algorithm.RSI, V6Algorithm.RSI_PLUS, V6Algorithm.PORTFOLIO)) {
                var res: V6FinalPort.ActionResult? = null
                val offenders = withProbe({ base }, p) {
                    res = runBlocking { V6FinalPort.handleOptimize(st, input.copy2D(), secondsRaw = 2, workers = 2,
                        requestedAlgorithm = algo, allowImpossible = true, seed = 5L) }
                }
                check("$file $algo", st, input, res!!.schedule, res!!.logs, offenders)
            }
        }
    }

    /** [3.653.0] 拡張希望の違反は必須（重み 8000＝希望と同じ）＝入力に既にある違反を探索が解消する（旧: 採点外で残った）。 */
    @Test fun existingViolationsAreRepairedNowThatTheyAreHard() = runBlocking {
        val st0 = load("sept2026_state.json")
        val input = st0.schedule.toIntArray2D()
        val p0 = Problem(st0)
        val start = java.time.LocalDate.parse(st0.startDate)
        var st = st0
        for (i in 0 until p0.S) for (j in 0 until p0.T) {
            if (p0.wish[i][j] >= 0 || p0.pinned(i, j) || (i * 7 + j) % 11 != 0 || input[i][j] !in 0 until p0.K) continue
            val r = ExtWishRules.sanitize(st, ExtWish(i, listOf(start.plusDays(j.toLong()).toString()), listOf(st0.shifts[input[i][j]].kigou)))
            if (r.saved != null) st = st.copy(extWishes = st.extWishes + r.saved!!)
        }
        val before = UnifiedViolationChecker.check(st, input)
        val n0 = before.breakdown["extWish"] ?: 0
        assertTrue("入力の違反が作れていない ($n0)", n0 > 10)
        assertEquals(n0, before.extWishCells.size)
        assertEquals(n0, before.hard)   // sept2026 の入力盤面は必須 0
        val out = V6FinalPort.handleOptimize(st, input.copy2D(), secondsRaw = 3, workers = 2,
            requestedAlgorithm = V6Algorithm.V5, allowImpossible = true, seed = 7L).schedule
        val after = UnifiedViolationChecker.check(st, out)
        val n1 = after.breakdown["extWish"] ?: 0
        // 拡張希望の違反は先に解ける（負荷なし 3 秒で 23→0）。玉突きで出る禁止の並びは時間で減る（10 秒 3・30 秒 1）＝時間制なので緩く見る。
        assertTrue("拡張希望の違反が残りすぎ $n1/$n0 ${after.extWishCells}", n1 * 4 <= n0)
        assertTrue("必須が減っていない ${before.hard}→${after.hard}", after.hard < before.hard)
    }

    /** 既定 OFF の研磨・玉突きも含めて後処理を全部 ON にした決定的モード。 */
    @Test fun postProcessingWithEveryFlagOnNeverPlacesABannedShift() {
        val saved = PolishGate.snapshot()
        val savedCascade = PolishGate.softCascade
        try {
            PolishGate.restore(saved.mapValues { (_, v) -> if (v is Boolean) true else v } + mapOf(
                "hardEjectionChainEarly" to true, "allEjectionChainEarly" to true, "allEjectionChainFinal" to true,
                "hardEjectionChainRetry" to true, "allEjectionChainAfterRepair" to true))
            PolishGate.softCascade = true
            for (file in listOf("sample_state_v6.json", "sept2026_state.json")) {
                val st0 = load(file)
                val input = st0.schedule.toIntArray2D()
                val out0 = V6HotfixPasses.runPostOptimization(st0, input.copy2D(), "t", seed = 1L, deadlineMs = EngineClock.nowMs() + 3_600_000L,
                    params = V6HotfixPasses.PostOptimizationParams(deterministic = true, softCascadeEnabled = true, c1LnsMaxEvaluations = 5_000, personalLnsMaxEvaluations = 5_000)).schedule
                val st = adversarial(st0, input, out0)
                val p = Problem(st)
                val base = p.withManualPins(normalizeSchedule(input, p))
                var out: Array<IntArray>? = null
                val offenders = withProbe({ base }, p) {
                    out = V6HotfixPasses.runPostOptimization(st, input.copy2D(), "t", seed = 1L, deadlineMs = EngineClock.nowMs() + 3_600_000L,
                        params = V6HotfixPasses.PostOptimizationParams(deterministic = true, softCascadeEnabled = true, c1LnsMaxEvaluations = 5_000, personalLnsMaxEvaluations = 5_000)).schedule
                }
                assertTrue("$file: 後処理の段が禁止を新しく置いた $offenders", offenders.isEmpty())
                assertTrue("$file: 後処理の最終盤面", p.extBanNewCells(base, out!!).isEmpty())
            }
        } finally {
            PolishGate.restore(saved)
            PolishGate.softCascade = savedCascade
        }
    }
}
