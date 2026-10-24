package com.magi.app.v6

/**
 * 1 回の作成の時間予算（全面改修 P3a、`docs/overhaul_detailed_spec.md` §3.3）。開始時に 1 回だけ作り、以後は変えない。
 * 制限時間は探索の時間で、後処理と玉突きの取り分はその後ろに足す（全体の締切＝開始＋制限時間＋後処理＋玉突き）。
 * 式と定数は移す前の `V6FinalPort` と同じ（値を変えない移行。`TimeBudgetTest` が旧式との一致を守る）。
 */
internal class TimeBudget private constructor(val startMs: Long, val searchMs: Long, val postReserveMs: Long, val chainReserveMs: Long) {
    /** 探索の後ろに足す取り分（後処理＋玉突き）。 */
    val reserveMs: Long get() = postReserveMs + chainReserveMs
    val hardDeadlineMs: Long get() = startMs + searchMs + reserveMs

    /** 停滞の見張りの時間の切り方（`docs/stall_escape.md` §5.1）。 */
    fun watchdog(fraction: Double = EngineConfig().normalStallFraction): V6FinalPort.WatchdogBudget =
        V6FinalPort.watchdogBudget(searchMs, startMs, hardDeadlineMs, fraction, postReserveFixedMs = reserveMs)

    /** 全体の締切（制限時間＋取り分）を過ぎたか（ログの「超過」）。 */
    fun overBudget(nowMs: Long): Boolean = nowMs - startMs > searchMs + reserveMs

    companion object {
        fun start(seconds: Int, clock: () -> Long = EngineClock::nowMs): TimeBudget =
            TimeBudget(clock(), seconds.toLong() * 1000L, POST_RESERVE_SEC * 1000L, CHAIN_RESERVE_SEC * 1000L)
    }
}
