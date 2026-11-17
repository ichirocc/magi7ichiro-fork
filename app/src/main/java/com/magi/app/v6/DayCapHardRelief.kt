package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 上限 0 の日を守るせいで残る必須違反を、その日に置いて減らす最終段（3.727.95、2026-11-17 利用者「上限0のせいで必須違反」は不具合）。
 * 最適化器は上限 0 の日に置かない（[Problem.dayCapBanned]）が、それで禁止の並び・人員不足が避けられなくなる盤面がある
 * （実機ログ: 休の上限 0 が 12/29〜31 に続き、古泉さんの Dﾃ→B4 が残った）。上限 0 の日のセルを 1 つその値へ変える手と、
 * そのセルの元の値を同じ日の別の職員へ渡す手を試し、**必須の件数が減るときだけ**採る（上限 0 の日の人員過剰は SOFT のまま数える）。
 */
object DayCapHardRelief {
    class Result(val schedule: Array<IntArray>, val report: ViolationReport, val placed: List<Triple<Int, Int, Int>>)

    fun apply(state: MagiState, schedule: Array<IntArray>, report: ViolationReport, deadlineMs: Long, now: () -> Long = { EngineClock.nowMs() }): Result {
        val p = cachedProblem(state)
        val caps = p.dayCapZero
        if (report.hard <= 0 || caps == null) return Result(schedule, report, emptyList())
        var cur = schedule.copy2D()
        var best = report
        val placed = ArrayList<Triple<Int, Int, Int>>()
        fun free(i: Int, j: Int) = !p.wishLocked(i, j) && !p.pinned(i, j)
        while (best.hard > 0 && now() < deadlineMs) {
            var pick: Pair<Array<IntArray>, ViolationReport>? = null
            var pickCell: Triple<Int, Int, Int>? = null
            for (j in 0 until p.T) for (i in 0 until p.S) {
                if (now() >= deadlineMs || !free(i, j)) continue
                val old = cur[i][j]
                for (k in p.allowedShiftsForStaff(i)) {
                    if (k == old || !caps[j * p.K + k] || p.extWishBanned(i, j, k)) continue
                    val tries = ArrayList<Array<IntArray>>()
                    tries += cur.copy2D().also { it[i][j] = k }
                    for (i2 in 0 until p.S) {
                        if (i2 == i || !free(i2, j) || cur[i2][j] == old || !p.mayPlaceAt(i2, j, old)) continue
                        tries += cur.copy2D().also { it[i][j] = k; it[i2][j] = old }
                    }
                    for (cand in tries) {
                        val r = UnifiedViolationChecker.check(state, cand)
                        if (r.hard < best.hard && (pick == null || betterReport(r, pick.second))) { pick = cand to r; pickCell = Triple(i, j, k) }
                    }
                }
            }
            val got = pick ?: break
            cur = got.first; best = got.second; placed += pickCell!!
        }
        return Result(cur, best, placed)
    }
}
