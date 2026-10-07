package com.magi.app.v6

/**
 * 適応 portfolio の探索中、全体最良が一定時間更新されないときに、後処理チェーンを全体最良の写しへ短い上限で当てる
 * （`PolishGate.stallPolishInjection`、既定 OFF）。採否は `betterReport` の keep-best、採った盤面は全体最良として
 * 次のエポック入口からワーカーへ渡る。ここは発火判定だけの純関数。
 */
internal object StallPolishInjection {
    const val MAX_INJECTIONS = 3
    const val CAP_MS = 6_000L
    private const val MIN_STALL_MS = 20_000L
    private const val MIN_REMAINING_MS = CAP_MS + 2_000L

    fun stallThresholdMs(budgetSec: Int): Long = maxOf(MIN_STALL_MS, budgetSec.coerceAtLeast(1) * 100L)

    fun shouldInject(nowMs: Long, lastImproveMs: Long, deadlineMs: Long, budgetSec: Int, injectionsDone: Int): Boolean =
        injectionsDone < MAX_INJECTIONS &&
            deadlineMs - nowMs >= MIN_REMAINING_MS &&
            nowMs - lastImproveMs >= stallThresholdMs(budgetSec)
}
