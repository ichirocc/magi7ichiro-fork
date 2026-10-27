package com.magi.app.ui

import androidx.lifecycle.viewModelScope
import com.magi.app.v6.WishTrial
import com.magi.app.v6.copy2D
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// [MagiViewModel] の「この希望を取り消したら」試算（docs/s5_wish_trial.md §6・§8）。本体から extension 関数として抽出（全面改修 P6c）。
//   試算の状態（wishTrialJob/wishTrialSeq/trialCtx/trialControl/trialResults）と、state を書き換える確定 cancelWishAndRebuild は本体に残す。

internal fun MagiViewModel.ctxMatches(c: MagiViewModel.TrialCtx?): Boolean {
    val st = state ?: return false
    val b = currentSchedule ?: return false
    return c != null && c.st === st && c.boardKey == boardKey(b)
}

/** 1 行の試算を始める。本実行・背景実行の最中は始めない（I11）。盤面は複製して渡す（I1）。 */
fun MagiViewModel.startWishTrial(i: Int, j: Int) {
    val st = state ?: return
    val b = currentSchedule ?: return
    if (optimizeInFlight()) return
    val k = st.wishes["$i,$j"] ?: return
    val bKey = boardKey(b)
    val ctx = trialCtx?.takeIf { it.st === st && it.boardKey == bKey }
        ?: MagiViewModel.TrialCtx(st, bKey).also { trialCtx = it; trialControl = null; trialResults.clear() }
    wishTrialJob?.cancel()
    val seq = ++wishTrialSeq
    val board = b.copy2D()
    val ctl0 = trialControl
    _ui.update { it.copy(wishTrialBusy = "$i,$j") }
    wishTrialJob = viewModelScope.launch {
        try {
            val (ctl, out) = withContext(Dispatchers.Default) {
                val stop = { !isActive }
                val c = ctl0 ?: WishTrial.control(st, board, stop)
                c to (if (c is WishTrial.ControlOutcome) WishTrial.trial(st, board, i, j, c.control, stop) else c)
            }
            if (seq != wishTrialSeq || trialCtx !== ctx) return@launch
            if (ctl !is WishTrial.Stopped) trialControl = ctl
            if (out != null && out !is WishTrial.Stopped) trialResults["$i,$j,$k"] = out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logOp("W", "希望の試算 失敗: ${e.javaClass.simpleName}: ${e.message}")
            if (seq == wishTrialSeq && trialCtx === ctx) trialResults["$i,$j,$k"] = WishTrial.Unavailable("計算の途中で止まりました。もう一度お試しください")
        } finally {
            if (seq == wishTrialSeq) _ui.update { it.copy(wishTrialBusy = null, wishTrialRev = it.wishTrialRev + 1) }
        }
    }
}

/** 試算ジョブの取消だけ（CPU を返す）。結果は消さない＝正しさは読むときの照合が守る（§8）。 */
fun MagiViewModel.cancelWishTrial() {
    wishTrialJob?.cancel()
    ++wishTrialSeq
    if (_ui.value.wishTrialBusy != null) _ui.update { it.copy(wishTrialBusy = null) }
}

/** 行の表示。読むたびに ctx を照合し、ずれていれば [WishTrialView.Stale]（結果は消さない）。 */
internal fun MagiViewModel.wishTrialFor(i: Int, j: Int, k: Int): WishTrialView {
    if (_ui.value.wishTrialBusy == "$i,$j") return WishTrialView.Busy
    val o = trialResults["$i,$j,$k"] ?: return WishTrialView.None
    val c = trialCtx
    if (c == null || !ctxMatches(c) || state?.wishes?.get("$i,$j") != k) return WishTrialView.Stale
    return WishTrialView.Ready(o, WishTrialToken(c.st, c.boardKey, i, j, k, o as? WishTrial.Result))
}

/** いまの (state, 盤面) の対照。Rk < H0 ならダイアログの先頭行を出す（§5）。 */
internal fun MagiViewModel.wishTrialControlFor(): WishTrial.Control? =
    if (ctxMatches(trialCtx)) (trialControl as? WishTrial.ControlOutcome)?.control else null

/** 直近の確定の結果 1 行。確定の後の (state, 盤面) から変わったら出さない（§9）。 */
internal fun MagiViewModel.wishCancelOutcomeLine(): String? =
    _ui.value.wishCancelOutcome?.takeIf { ctxMatches(cancelOutcomeCtx) }?.line
