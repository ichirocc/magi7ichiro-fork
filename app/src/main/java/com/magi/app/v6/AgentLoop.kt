package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 代行役（AX、backlog #13(f)）の中核: 勤務表の全体から 1 手の変更案を探し、[FixApplyGate] に合格した先頭の案を当てて
 * 探し直す、を案が尽きるまで繰り返す。勤務の変更だけを扱い、希望・設定は変えない（それは人の判断待ちに回す）。
 * 入力の盤面は変えない。各手は正式なチェッカーで辞書式に改善し必須違反を新たに生まない＝結果は入力より悪くならない。
 */
object AgentLoop {
    data class Step(val label: String, val ops: List<FixCell>, val hardBefore: Int, val hardAfter: Int, val totalBefore: Int, val totalAfter: Int)

    enum class Stop { NO_CANDIDATE, ROUND_LIMIT, TIME_LIMIT, CANCELLED }

    class Result(val schedule: Array<IntArray>, val steps: List<Step>, val stop: Stop, val before: ViolationReport, val after: ViolationReport)

    const val MAX_ROUNDS = 12
    const val SEARCH_MS = 6_000L

    fun run(
        state: MagiState,
        schedule: Array<IntArray>,
        deadlineMs: Long,
        maxRounds: Int = MAX_ROUNDS,
        searchMs: Long = SEARCH_MS,
        now: () -> Long = { System.currentTimeMillis() },
        active: () -> Boolean = { true },
        suggest: (MagiState, Array<IntArray>, Long) -> List<FixSuggestion> = { st, s, ms ->
            FixSuggester.suggest(st, s, maxResults = 8, deadlineMs = ms, ejectionChain = true)
        },
    ): Result {
        val p = Problem(state)
        var cur = schedule.copy2D()
        val first = UnifiedViolationChecker.check(state, cur)
        var report = first
        val steps = ArrayList<Step>()
        var stop = Stop.ROUND_LIMIT
        for (round in 0 until maxRounds) {
            if (!active()) { stop = Stop.CANCELLED; break }
            val left = deadlineMs - now()
            if (left <= 0L) { stop = Stop.TIME_LIMIT; break }
            val cands = suggest(state, cur, minOf(searchMs, left))
            val hit = cands.firstNotNullOfOrNull { c ->
                (FixApplyGate.apply(state, cur, c.ops, p, report) as? FixApplyGate.Outcome.Applied)?.let { c to it }
            }
            if (hit == null) { stop = Stop.NO_CANDIDATE; break }
            val (c, ok) = hit
            steps += Step(c.label, c.ops, ok.before.hard, ok.after.hard, ok.before.total, ok.after.total)
            cur = ok.schedule
            report = ok.after
        }
        return Result(cur, steps, stop, first, report)
    }
}
