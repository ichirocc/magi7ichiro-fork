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

/**
 * 後処理の各段の取り分（全面改修 P3b）。式は移す前の各所と同じ（値を変えない移行。`TimeBudgetTest` が旧式との一致を守る）。
 * 取り分の決め方をここ 1 か所に集め、各段は残り時間をこの関数に渡して自分の締切を受け取るだけにする。
 */
internal object BudgetShare {
    /** 残りの半分、ただし上限まで（HF67・HF66・共同 LNS 2 本の確保・玉突きの深い段）。 */
    fun half(remainingMs: Long, capMs: Long): Long = (remainingMs / 2).coerceAtMost(capMs)

    /** 残りの 4 分の 1、0〜上限（c1 の玉突き連鎖）。 */
    fun quarter(remainingMs: Long, maxMs: Long): Long = (remainingMs / 4).coerceIn(0L, maxMs)

    /** 残り（上限 [clampMs]）を [partMaxMs]:[totalMaxMs] で按分、ただし [partMaxMs] まで（c1 共同 LNS＝既定 8:6、3.255.0）。 */
    fun proportional(remainingMs: Long, clampMs: Long, partMaxMs: Long, totalMaxMs: Long): Long =
        if (totalMaxMs <= 0L) 0L else (remainingMs.coerceAtMost(clampMs) * partMaxMs / totalMaxMs).coerceAtMost(partMaxMs)

    /** 残り全部、ただし上限まで（個人回数の共同 LNS）。 */
    fun upTo(remainingMs: Long, capMs: Long): Long = remainingMs.coerceAtMost(capMs)
}
