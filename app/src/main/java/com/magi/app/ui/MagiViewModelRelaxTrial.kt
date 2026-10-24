package com.magi.app.ui

import androidx.lifecycle.viewModelScope
import com.magi.app.v6.RelaxTrial
import com.magi.app.v6.copy2D
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// [MagiViewModel] の「設定を緩めたら」試算と承認フロー（docs/s6_relax_trial.md §6・§8・§15）。本体から extension 関数として抽出（全面改修 P6b）。
//   試算の状態（relaxJob/relaxSeq/relaxCtx/relaxResult ほか）と、盤面を書き換える確定 relaxAndApply は本体に残す。

internal fun MagiViewModel.relaxCtxNow(): MagiViewModel.RelaxCtx? {
    val st = state ?: return null
    val b = currentSchedule ?: return null
    return MagiViewModel.RelaxCtx(stateKey(st), boardKey(b))
}

/** 背景で起点 3 件まで試算する（§8）。同じ ctx で済んでいる・走っているなら何もしない。 */
internal fun MagiViewModel.startRelaxTrial() {
    if (!RELAX_TRIAL_ENABLED) return
    val st = state ?: return
    val b = currentSchedule ?: return
    if (optimizeInFlight()) return
    val ctx = MagiViewModel.RelaxCtx(stateKey(st), boardKey(b))
    if (relaxCtx == ctx && (relaxResult != null || relaxJob?.isActive == true)) return
    relaxJob?.cancel()
    val seq = ++relaxSeq
    relaxCtx = ctx; relaxResult = null; relaxStoppedCtx = null; relaxFailedCtx = null
    val board = b.copy2D()
    _ui.update { it.copy(relaxSearching = true) }
    relaxJob = viewModelScope.launch {
        try {
            val out = withContext(Dispatchers.Default) { val stop = { !isActive }; RelaxTrial.firstWall(st, board, shouldStop = stop) }
            if (seq != relaxSeq) return@launch
            if (out !is RelaxTrial.Stopped) relaxResult = out
            if (out is RelaxTrial.Result) logOp("I", "S6 試算: 起点 ${out.staff + 1}/${out.day + 1} 組${out.relaxes.size} 前提${out.prerequisite.size} ${out.h0}/${out.rk}/${out.rkH}/${out.rr}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logOp("W", "設定の緩和の試算 失敗: ${e.javaClass.simpleName}: ${e.message}")
            if (relaxOutcomeApplies(seq, relaxSeq, ctx, relaxCtxNow())) relaxFailedCtx = ctx
        } finally {
            if (seq == relaxSeq) _ui.update { it.copy(relaxSearching = false, relaxRev = it.relaxRev + 1) }
        }
    }
}

/** 試算を止める（結果は出さない）。利用者の「やめる」と盤面ジョブの入口から呼ぶ。 */
fun MagiViewModel.cancelRelaxTrial() {
    if (relaxJob?.isActive == true) relaxStoppedCtx = relaxCtx
    relaxJob?.cancel()
    ++relaxSeq
    if (_ui.value.relaxSearching) _ui.update { it.copy(relaxSearching = false) }
}

/** いまのデータで見つかった組（無い・古いなら null）。読むたびに照合する。 */
internal fun MagiViewModel.relaxTrialFor(): RelaxToken? {
    val c = relaxCtx ?: return null
    val r = relaxResult as? RelaxTrial.Result ?: return null
    return if (relaxCtxNow() == c) RelaxToken(c.stateKey, c.boardKey, r) else null
}

/** いまのデータで探し終えて組が無かった（NoWall）。走っている・未着手・古い・試算不可なら false。 */
internal fun MagiViewModel.relaxNoWall(): Boolean = relaxCtx != null && relaxCtx == relaxCtxNow() && relaxResult is RelaxTrial.NoWall

/** いまのデータでは試算できない理由（未割当のセルなど）。無い・古いなら null。 */
internal fun MagiViewModel.relaxUnavailable(): String? =
    (relaxResult as? RelaxTrial.Unavailable)?.reason?.takeIf { relaxCtx != null && relaxCtx == relaxCtxNow() }

/** いまのデータで試算が失敗した（結果なし）。「もう一度試す」を出す。 */
internal fun MagiViewModel.relaxFailed(): Boolean = relaxFailedCtx != null && relaxFailedCtx == relaxCtxNow() && relaxResult == null

/** いまのデータで試算を途中で止めた（結果なし）。「もう一度試す」を出す。 */
internal fun MagiViewModel.relaxStopped(): Boolean = relaxStoppedCtx != null && relaxStoppedCtx == relaxCtxNow() && relaxResult == null

/** 止めた試算を利用者の操作でやり直す。 */
fun MagiViewModel.retryRelaxTrial() = startRelaxTrial()

/** 直近の確定の結果 1 行。確定の後のデータから変わったら出さない（§9）。 */
internal fun MagiViewModel.relaxDoneLine(): String? = relaxDone?.takeIf { it.first == relaxCtxNow() }?.second

/** 承認フローを開く（§15）。いまのデータの試算だけを R1 に出し、それ以外は理由つきの R4。 */
internal fun MagiViewModel.openRelaxFlow() {
    val c = relaxCtx
    val now = relaxCtxNow()
    val fresh = c != null && c == now
    val outcome = relaxResult ?: if (relaxStoppedCtx != null && relaxStoppedCtx == now) RelaxTrial.Stopped else null
    val searching = fresh && relaxResult == null && relaxJob?.isActive == true
    val failed = relaxFailed()
    val k = c ?: now ?: MagiViewModel.RelaxCtx(0L, 0L)
    _ui.update { it.copy(relaxFlow = RelaxApproval.start(outcome, fresh, RelaxApproval.trialId(k.stateKey, k.boardKey), RelaxApproval.fingerprint(k.stateKey, k.boardKey), searching, failed)) }
}

internal fun MagiViewModel.relaxFlowNext() {
    val f = _ui.value.relaxFlow ?: return
    _ui.update { it.copy(relaxFlow = RelaxApproval.next(f, relaxTrialFor() != null)) }
}

internal fun MagiViewModel.relaxFlowDecline() {
    val f = _ui.value.relaxFlow ?: return
    _ui.update { it.copy(relaxFlow = RelaxApproval.decline(f)) }
}

internal fun MagiViewModel.closeRelaxFlow() { _ui.update { it.copy(relaxFlow = null) } }

/** R3「承認して反映する」。理由が空・古い・押し済みなら何もしない。承認を記録してから反映し、結果で R5 か R4。 */
internal fun MagiViewModel.approveRelax(reason: String) {
    val f = _ui.value.relaxFlow ?: return
    val token = relaxTrialFor()
    if (!RelaxApproval.canApprove(reason, token?.result, token != null)) {
        if (f.step == RelaxApproval.Step.R3 && token == null) _ui.update { it.copy(relaxFlow = RelaxApproval.finish(f, RelaxApproval.Verdict.Stale)) }
        return
    }
    val r = token!!.result
    val names = _ui.value
    val changes = (r.prerequisite + r.relaxes).map { x -> "${names.staffNames.getOrNull(x.staff) ?: "職員${x.staff + 1}"} ${names.shiftSymbols.getOrNull(x.shift) ?: "?"} 上限0→${x.newHi}" } +
        listOf("手順${r.moves.size}セル")
    val pressed = RelaxApproval.press(f, RelaxApproval.decisionId(f.trialId, System.currentTimeMillis()))?.copy(applied = changes) ?: return
    _ui.update { it.copy(relaxFlow = pressed) }
    logOp("I", RelaxApproval.approvalLog(pressed.decisionId!!, pressed.trialId, reason, changes))
    agentDecisionApproved()
    val v = relaxAndApply(token)
    _ui.update { it.copy(relaxFlow = RelaxApproval.finish(pressed, v)) }
}
