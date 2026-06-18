package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [改善提案] 1回の「同日ペア交換」で違反がどれだけ減るかを評価した候補。
 * staffA と staffB の day 日のシフトを入れ替える（同日のため被覆は不変、個人回数/連続/ペア制約のみ変化）。
 * diff: 家族キー -> 件数差（減少は負）。
 */
data class SwapSuggestion(
    val staffA: Int,
    val staffB: Int,
    val day: Int,
    val nameA: String,
    val nameB: String,
    val shiftA: String,
    val shiftB: String,
    val deltaHard: Int,
    val deltaTotal: Int,
    val diff: List<Pair<String, Int>>,
)

/**
 * 違反を減らす「同日ペア交換」をユーザー向けに列挙する。最適化エンジンの localPairwiseStaffSwap と
 * 同じ評価（canDo 可否・希望ロック保護・UnifiedViolationChecker・(hard,total,weighted)辞書式改善）を用いるが、
 * 貪欲適用せず候補を集めて効果順に返す。読取専用（盤面・データ不変）。
 */
object SwapSuggester {
    fun suggest(
        state: MagiState,
        schedule: Array<IntArray>,
        focusStaff: Int? = null,
        maxResults: Int = 8,
        maxEval: Int = 20000,
        deadlineMs: Long = 2500L,
    ): List<SwapSuggestion> {
        val p = Problem(state)
        if (p.S < 2 || p.T < 1) return emptyList()
        val s = normalizeSchedule(schedule, p)
        val base = UnifiedViolationChecker.check(state, s)
        fun nm(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
        fun sym(k: Int) = if (k >= 0) (state.shifts.getOrNull(k)?.kigou ?: "$k") else "—"

        val found = ArrayList<Pair<SwapSuggestion, Long>>()
        val start = System.currentTimeMillis()
        var evals = 0
        outer@ for (i in 0 until p.S) {
            for (i2 in i + 1 until p.S) {
                if (focusStaff != null && i != focusStaff && i2 != focusStaff) continue
                for (j in 0 until p.T) {
                    if (evals >= maxEval || System.currentTimeMillis() - start > deadlineMs) break@outer
                    if (p.wish[i][j] >= 0 || p.wish[i2][j] >= 0) continue   // 希望ロックは触らない
                    val a = s[i][j]; val b = s[i2][j]
                    if (a == b || !p.canDo(i, b) || !p.canDo(i2, a)) continue
                    val cand = s.copy2D()
                    cand[i][j] = b; cand[i2][j] = a
                    val rep = UnifiedViolationChecker.check(state, cand)
                    evals++
                    val better = rep.hard < base.hard ||
                        (rep.hard == base.hard && rep.total < base.total) ||
                        (rep.hard == base.hard && rep.total == base.total && rep.weightedScore < base.weightedScore)
                    if (!better) continue
                    val diff = ArrayList<Pair<String, Int>>()
                    for (k in (base.breakdown.keys + rep.breakdown.keys)) {
                        val d = (rep.breakdown[k] ?: 0) - (base.breakdown[k] ?: 0)
                        if (d != 0) diff.add(k to d)
                    }
                    diff.sortBy { it.second }   // 減少（負）を先に
                    val sug = SwapSuggestion(i, i2, j, nm(i), nm(i2), sym(a), sym(b),
                        rep.hard - base.hard, rep.total - base.total, diff)
                    val rank = (rep.hard - base.hard).toLong() * 1_000_000L + (rep.total - base.total).toLong()
                    found.add(sug to rank)
                }
            }
        }
        found.sortBy { it.second }
        return found.take(maxResults).map { it.first }
    }
}
