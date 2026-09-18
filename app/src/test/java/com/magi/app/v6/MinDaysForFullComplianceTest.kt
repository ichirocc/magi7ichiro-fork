package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [3.596.0] `minDaysForFullCompliance`（診断2b-3の壁検知が読む窓ルールの真の最小必要日数）を、
 * 全部分集合を列挙する総当たりオラクルと突き合わせる。旧実装のビットマスクDPは窓長に対し
 * 指数的で実機では落ちるため厳密貪欲へ置換した＝「速いが不正確」への退化を構造的に禁じる。
 * オラクルはDPと独立＝「DPが正しい」という前提を置かずに貪欲の厳密性を確かめられる。
 */
class MinDaysForFullComplianceTest {

    /** 全2^t通りの対象日集合を列挙し、全ルール・全窓を満たすものの最小日数（無ければnull）。 */
    private fun oracle(t: Int, rules: List<Pair<Int, Int>>): Int? {
        val valid = rules.filter { it.first in 1..t && it.second > 0 }
        if (valid.isEmpty()) return null
        var best: Int? = null
        for (mask in 0 until (1 shl t)) {
            val pc = Integer.bitCount(mask)
            if (best != null && pc >= best!!) continue
            var ok = true
            loop@ for ((days, minimum) in valid) {
                for (j0 in 0..(t - days)) {
                    var c = 0
                    for (j in j0 until j0 + days) if ((mask shr j) and 1 == 1) c++
                    if (c < minimum) { ok = false; break@loop }
                }
            }
            if (ok) best = pc
        }
        return best
    }

    private fun singleRules(t: Int): List<List<Pair<Int, Int>>> {
        val out = ArrayList<List<Pair<Int, Int>>>()
        for (w in 1..t) for (m in 1..w) out.add(listOf(w to m))
        return out
    }

    @Test
    fun singleRuleMatchesBruteForceOracleForEveryWindowAndMinimum() {
        for (t in 1..16) {
            for (rules in singleRules(t)) {
                assertEquals(
                    "t=$t rules=$rules",
                    oracle(t, rules),
                    SmartInitialScheduler.minDaysForFullCompliance(t, rules),
                )
            }
        }
    }

    @Test
    fun rulePairsMatchBruteForceOracle() {
        val t = 12
        val singles = singleRules(t)
        for (a in singles.indices) {
            for (b in a + 1 until singles.size) {
                val rules = singles[a] + singles[b]
                assertEquals(
                    "t=$t rules=$rules",
                    oracle(t, rules),
                    SmartInitialScheduler.minDaysForFullCompliance(t, rules),
                )
            }
        }
    }

    @Test
    fun impossibleAndDegenerateInputsReturnNull() {
        // 窓長より多い回数＝どう置いても0違反にできない。
        assertNull(SmartInitialScheduler.minDaysForFullCompliance(10, listOf(3 to 4)))
        // 期間に収まらない窓しか無い＝有効ルールが空。
        assertNull(SmartInitialScheduler.minDaysForFullCompliance(5, listOf(9 to 1)))
        assertNull(SmartInitialScheduler.minDaysForFullCompliance(0, listOf(1 to 1)))
        // 旧DPのビットマスク由来の上限を踏襲（業務上限は31日）。
        assertNull(SmartInitialScheduler.minDaysForFullCompliance(63, listOf(7 to 1)))
    }

    @Test
    fun longWindowsAreAnsweredInsteadOfBlowingUp() {
        // 旧DPは窓18で6.3秒・窓22以上はヒープ3GBでもOOMだった長さ。
        val t0 = System.nanoTime()
        assertEquals(8, SmartInitialScheduler.minDaysForFullCompliance(31, listOf(30 to 8)))
        assertEquals(15, SmartInitialScheduler.minDaysForFullCompliance(31, listOf(2 to 1)))
        assertEquals(8, SmartInitialScheduler.minDaysForFullCompliance(31, listOf(5 to 1, 14 to 4)))
        val ms = (System.nanoTime() - t0) / 1_000_000
        org.junit.Assert.assertTrue("長窓が${ms}msかかった", ms < 1_000)
    }
}
