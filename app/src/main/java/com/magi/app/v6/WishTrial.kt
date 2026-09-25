package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [S5] 「この希望を取り消したら」試算（`docs/s5_wish_trial.md` §3・§4・§7）。
 * 同じ盤面で確実に消える分 a と、違反起点修復（VCR、既定 Params・候補プール空＝決定的）で減る見込み b を、
 * 希望を残したまま同じ修復を回した対照（Rk）を差し引いて出す。入力は書かない。結果は数値だけ（盤面を持たない＝仮盤禁止）。
 */
object WishTrial {
    /** 対照（希望を残したまま）。同じ (state, 盤面) なら希望の行によらず同じ＝呼び出し側で使い回せる。 */
    data class Control(val h0: Int, val rk: Int)

    sealed interface Outcome
    data class Result(
        val h0: Int, val hx: Int, val rk: Int, val rr: Int,
        val a: Int, val att: Int, val aPrime: Int, val b: Int,
        val pKeep: Int, val pCancel: Int,
    ) : Outcome
    /** 試算できない盤面（未割当セル・休み無し設定など）。0 件とは言わない（I10）。 */
    data class Unavailable(val reason: String) : Outcome
    /** 止められた試算。部分結果を見込みにしない（I9）。 */
    data object Stopped : Outcome

    /** 対照 (H0, Rk)。試算できなければ [Unavailable]、止められたら [Stopped]。 */
    fun control(state: MagiState, schedule: Array<IntArray>, shouldStop: () -> Boolean = { false }): Outcome {
        unavailableReason(state, schedule)?.let { return Unavailable(it) }
        val h0 = UnifiedViolationChecker.check(state, schedule.copy2D()).hard
        val rk = vcrHard(state, schedule, shouldStop) ?: return Unavailable("休みシフトが設定されていません")
        if (shouldStop()) return Stopped
        return ControlOutcome(Control(h0, rk))
    }

    /** [control] の成功値（Outcome として返すための包み）。 */
    data class ControlOutcome(val control: Control) : Outcome

    /**
     * 希望 (staff, day) を取り消した試算。希望が無い・wishLocked でなければ null（試算の対象外）。
     * [control] を渡さなければここで計算する。
     */
    fun trial(
        state: MagiState,
        schedule: Array<IntArray>,
        staff: Int,
        day: Int,
        control: Control? = null,
        shouldStop: () -> Boolean = { false },
    ): Outcome? {
        val key = "$staff,$day"
        if (!state.wishes.containsKey(key)) return null
        val p = cachedProblem(state, false)
        if (staff !in 0 until p.S || day !in 0 until p.T || !p.wishLocked(staff, day)) return null
        unavailableReason(state, schedule)?.let { return Unavailable(it) }
        val ctl = control ?: when (val c = control(state, schedule, shouldStop)) {
            is ControlOutcome -> c.control
            else -> return c
        }
        val st2 = state.copy(wishes = state.wishes - key)
        val hx = UnifiedViolationChecker.check(st2, schedule.copy2D()).hard
        val rr = vcrHard(st2, schedule, shouldStop) ?: return Unavailable("休みシフトが設定されていません")
        if (shouldStop()) return Stopped
        val a = ctl.h0 - hx
        val pKeep = minOf(ctl.h0, ctl.rk)
        val pCancel = minOf(hx, rr)
        val att = pKeep - pCancel
        return Result(ctl.h0, hx, ctl.rk, rr, a, att, minOf(a, maxOf(0, att)), maxOf(0, att - a), pKeep, pCancel)
    }

    /** wishLocked の希望のキー（"i,j"）。担当できない勤務の希望は入らない＝試算の対象外（§2.2）。 */
    fun lockedWishKeys(state: MagiState): Set<String> {
        val p = cachedProblem(state, false)
        return state.wishes.keys.filterTo(LinkedHashSet()) { key ->
            val (i, j) = key.split(",").map { it.trim().toIntOrNull() ?: -1 }.let { (it.getOrNull(0) ?: -1) to (it.getOrNull(1) ?: -1) }
            i in 0 until p.S && j in 0 until p.T && p.wishLocked(i, j)
        }
    }

    /** VCR は未割当セルがあると黙って何もしない（0 と区別できない）ので、先に弾く。 */
    private fun unavailableReason(state: MagiState, schedule: Array<IntArray>): String? {
        val p = cachedProblem(state, false)
        if (schedule.size != p.S || schedule.any { it.size != p.T }) return "勤務表の大きさが設定と合いません"
        if (schedule.any { row -> row.any { it !in 0 until p.K } }) return "未割当のセルがあります"
        return null
    }

    /** 本実行の入口と同じ clear のあと VCR（§4 の定数）。clear が例外（休み無し）なら null。 */
    private fun vcrHard(state: MagiState, schedule: Array<IntArray>, shouldStop: () -> Boolean): Int? {
        val cleared = try { HardRepairCore.clearCappedCells(state, schedule.copy2D()).first } catch (e: Exception) { return null }
        val r = ViolationComponentRepair.repair(state, cleared, emptyList(), ViolationComponentRepair.Params(), shouldStop, quantitativeRangeEval = false)
        return r.report?.hard ?: UnifiedViolationChecker.check(state, r.newSchedule).hard
    }
}
