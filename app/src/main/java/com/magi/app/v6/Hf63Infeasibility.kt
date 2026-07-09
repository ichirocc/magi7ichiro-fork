package com.magi.app.v6

/**
 * [HF63] Infeasibility-Aware Adaptive Deprioritization — faithful port of the Web V6
 * standalone module (`magi_v6_web.html`, const `HF63`).
 *
 * 目的（業務担当者の核心要望）: 「データ問題があっても最適化できるアルゴリズム」。
 * 各制約族の改善を追跡し、INFEAS_STALL_ITERS 反復のあいだ改善が無い族を
 * 「構造的に充足不能（infeasible-likely）」と推定して penalty 上限(maxLam)を縮小
 * （HARD は /8、SOFT は /4）。これにより、満たせない制約に最適化リソースを浪費せず、
 * 達成可能な制約へ集中できる。改善を検出したらフラグは解除（self-correction）。
 *
 * 制約 index（VBA gLam(0..13) と一致）:
 *  0=C1 1=C2 2=C3 3=C3n 4=C3m 5=C3mn 6=C41 7=C42 8=CovU 9=CovO 10=Pref 11=LimMin 12=LimMax 13=Apt
 * HARD 族（V5仕様）: 3=C3n, 8=CovU, 10=Pref（V5_HARD_KEYS と一致）。
 *
 * 注: Web 同様、これは独立モジュール（探索の重み系へは未配線）。ネイティブ engine は
 * 動的重みを持つため、将来 SA のスコアリングへ配線して 5分内の品質改善に使える（要計測）。
 * 現状は「構造的に不能な制約族の検出」を診断/ログへ供給する用途で安全に利用できる。
 */
class Hf63Infeasibility {
    companion object {
        const val INFEAS_STALL_ITERS = 5000        // 不可能性判定の iter 閾値
        const val INFEAS_HARD_CAP_DIV = 8          // HARD infeas で LAM_MAX/8
        const val INFEAS_SOFT_CAP_DIV = 4          // SOFT infeas で LAM_MAX/4
        const val LAM_HARD_MAX_INT = 50000         // HARD λ 上限 = 50 x SCALE
        const val LAM_SOFT_MAX_INT = 10000         // SOFT λ 上限 = 10 x SCALE
        const val N_CONSTRAINTS = 14
        const val SENTINEL = 2147483647            // この iter で追跡しない印（Long.MaxValue 相当）
        val HARD_INDICES = setOf(3, 8, 10)         // c3n, covU, pref
        val CNAMES = listOf(
            "C1", "C2", "C3", "C3n", "C3m", "C3mn", "C41", "C42",
            "CovU", "CovO", "Pref", "LimMin", "LimMax", "Apt",
        )
        /** UnifiedViolationChecker の breakdown キー → HF63 index（無いものは追跡しない）。 */
        val KEY_TO_INDEX: Map<String, Int> = mapOf(
            "c1" to 0, "c2" to 1, "c3" to 2, "c3n" to 3, "c3m" to 4, "c3mn" to 5,
            "c41" to 6, "c42" to 7, "covU" to 8, "covO" to 9, "pref" to 10,
            "low" to 11, "high" to 12,
        )
    }

    private val gBestCurV = IntArray(N_CONSTRAINTS) { Int.MAX_VALUE }
    private val gLastImproveIter = IntArray(N_CONSTRAINTS)
    private val gInfeasibleLikely = BooleanArray(N_CONSTRAINTS)

    fun reset() {
        for (c in 0 until N_CONSTRAINTS) {
            gBestCurV[c] = Int.MAX_VALUE
            gLastImproveIter[c] = 0
            gInfeasibleLikely[c] = false
        }
    }

    /** 制約 c の改善状況を追跡し、不可能性を判定する（VBA UpdateInfeasibilityState 等価）。 */
    fun update(c: Int, curV: Int, gIter: Int) {
        if (c !in 0 until N_CONSTRAINTS) return
        if (curV < gBestCurV[c]) {
            gBestCurV[c] = curV
            gLastImproveIter[c] = gIter
            if (gInfeasibleLikely[c]) gInfeasibleLikely[c] = false   // self-correction
        } else if (curV == 0) {
            // 一度でも充足(0)に到達した族は「不能」ではない。再違反に備え停滞カウンタを進めておく
            // （これを怠ると、0到達後に摂動で再違反した瞬間 gIter-旧改善 が即 STALL を超え、
            //  解けた族を誤って infeasible 判定し RSI focus から外してしまう）。
            gLastImproveIter[c] = gIter
        } else if (gIter - gLastImproveIter[c] >= INFEAS_STALL_ITERS) {
            // curV>0 かつ STALL 反復改善なし。focus 回避/診断のため deprioritize する。
            gInfeasibleLikely[c] = true                               // 構造的下限推定 → deprioritize
        }
    }

    /** 全14制約を1回で更新（SENTINEL の族はスキップ）。 */
    fun updateBatch(curvArr: IntArray, gIter: Int) {
        val n = minOf(N_CONSTRAINTS, curvArr.size)
        for (c in 0 until n) {
            if (curvArr[c] == SENTINEL) continue
            update(c, curvArr[c], gIter)
        }
    }

    /** UnifiedViolationChecker の breakdown から更新（族→indexへマップ）。 */
    fun updateFromBreakdown(breakdown: Map<String, Int>, gIter: Int) {
        for ((key, idx) in KEY_TO_INDEX) {
            update(idx, breakdown[key] ?: 0, gIter)
        }
    }

    /** 制約 c の動的 penalty 上限（infeasible-likely なら縮小、整数除算）。 */
    fun maxLam(c: Int): Int {
        val isHard = c in HARD_INDICES
        val baseMax = if (isHard) LAM_HARD_MAX_INT else LAM_SOFT_MAX_INT
        if (c !in 0 until N_CONSTRAINTS || !gInfeasibleLikely[c]) return baseMax
        val div = if (isHard) INFEAS_HARD_CAP_DIV else INFEAS_SOFT_CAP_DIV
        return baseMax / div
    }

    fun maxLamBatch(): IntArray = IntArray(N_CONSTRAINTS) { maxLam(it) }

    /** 探索スコアへ掛ける重み係数（1.0=平常 / infeasible は HARD 0.125・SOFT 0.25）。配線時に使用。 */
    fun weightFactor(c: Int): Double {
        if (c !in 0 until N_CONSTRAINTS || !gInfeasibleLikely[c]) return 1.0
        return if (c in HARD_INDICES) 1.0 / INFEAS_HARD_CAP_DIV else 1.0 / INFEAS_SOFT_CAP_DIV
    }

    fun isInfeasibleLikely(c: Int): Boolean = c in 0 until N_CONSTRAINTS && gInfeasibleLikely[c]
    fun infeasibleCount(): Int = gInfeasibleLikely.count { it }

    /** 構造的に充足不能と推定された制約族名（診断/ログ用）。 */
    fun infeasibleFamilies(): List<String> =
        (0 until N_CONSTRAINTS).filter { gInfeasibleLikely[it] }.map { CNAMES[it] }

    /** infeasible-likely な族の breakdown キー集合（探索の focus 回避に使用）。 */
    fun infeasibleBreakdownKeys(): Set<String> =
        KEY_TO_INDEX.filterValues { gInfeasibleLikely[it] }.keys
}
