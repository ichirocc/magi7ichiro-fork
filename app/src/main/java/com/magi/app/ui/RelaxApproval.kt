package com.magi.app.ui

import com.magi.app.v6.RelaxTrial

/**
 * [S6] 「設定を緩めたら」の承認フロー R1〜R5（`docs/s6_relax_trial.md` §15）。画面の状態遷移だけの純関数で、エンジンの出力は変えない。
 * R1 試算結果の確認（読み取り専用）→ R2 変更内容の確認 → R3 承認（理由は必須）→ R5 結果。条件を満たさない・変更しない・反映できないは R4 終了。
 */
object RelaxApproval {
    enum class Step { R1, R2, R3, R4, R5 }

    /** R4 の理由。NoWall・Stopped を「解消できない」「0 件の証拠」と言わない（J7）。 */
    enum class EndReason(val text: String) {
        NO_WALL("上限0を外しても、この違反を解く手順は見つかりませんでした。解消できないという意味ではありません。"),
        STOPPED("試算を途中で止めたので、結果はありません。"),
        UNAVAILABLE("この勤務表では試算できません。"),
        NO_GAIN("試算しても必須違反は減りませんでした。"),
        STALE("勤務表か設定が変わりました。もう一度試算してください。"),
        NOT_REPRODUCED("試算の手順を当てても同じ結果になりませんでした。もう一度試算してください。"),
        BLOCKED("いまは反映できません（実行中か、データに問題があります）。"),
        DECLINED("変更しないを選びました。"),
        ROLLED_BACK("反映後の確認が合わなかったので元に戻しました。"),
        SAVE_FAILED("保存できなかったので元に戻しました。"),
    }

    /** 反映の結果（VM が当てて確かめた事実）。 */
    sealed interface Verdict {
        data object Stale : Verdict
        data object NotReproduced : Verdict
        data object Blocked : Verdict
        data class Applied(val postHard: Int) : Verdict
        data object SaveFailed : Verdict
    }

    /**
     * [trialId]・[fingerprint]＝試算とデータの指紋（短く表示）。[decisionId]＝承認を押したときに決まる。
     * [pressedId]＝反映を始めた decisionId（同じ id の 2 回目は何もしない）。[applied]＝承認した変更（前→後）の行。
     */
    data class Flow(
        val step: Step,
        val trialId: String,
        val fingerprint: String,
        val expectedHard: Int = -1,
        val end: EndReason? = null,
        val detail: String? = null,
        val decisionId: String? = null,
        val pressedId: String? = null,
        val postHard: Int = -1,
        val applied: List<String> = emptyList(),
    )

    const val READ_ONLY_HEAD = "読み取り専用・設定はまだ変わっていません"
    const val NOT_APPROVAL_NOTE = "試算で解けたことは変更の承認ではありません"
    const val UNCHANGED_LINE = "設定は変わっていません"
    const val APPROVER_LINE = "承認者: この端末の利用者"
    const val REBUILD_NOTE = "再作成は別の操作です（必要ならこのあと「再作成」を押してください）。"
    const val TO_REVIEW = "変更内容を確認する"
    const val TO_APPROVAL = "承認手続きへ進む"
    const val APPROVE = "承認して反映する"
    const val DECLINE = "変更しない"
    const val CLOSE = "閉じる"

    fun shortId(key: Long): String = java.lang.Long.toHexString(key).padStart(16, '0').takeLast(8)

    fun trialId(stateKey: Long, boardKey: Long): String = shortId(stateKey * 31 + boardKey)

    fun fingerprint(stateKey: Long, boardKey: Long): String = "${shortId(stateKey).take(4)}-${shortId(boardKey).take(4)}"

    /** 試算と押した時刻から決まる id（同じ入力なら同じ id）。 */
    fun decisionId(trialId: String, epochMs: Long): String = shortId(trialId.fold(0L) { h, c -> h * 31 + c.code } * 1_000_003L + epochMs)

    fun improves(outcome: RelaxTrial.Outcome?): Boolean = outcome is RelaxTrial.Result && outcome.rr < outcome.h0

    /** 開いたときの状態。改善のある Result だけが R1、他は理由の分かれた R4。 */
    fun start(outcome: RelaxTrial.Outcome?, fresh: Boolean, trialId: String, fingerprint: String): Flow {
        val base = Flow(Step.R1, trialId, fingerprint, expectedHard = (outcome as? RelaxTrial.Result)?.rr ?: -1)
        return when {
            !fresh || outcome == null -> base.copy(step = Step.R4, end = EndReason.STALE)
            outcome is RelaxTrial.NoWall -> base.copy(step = Step.R4, end = EndReason.NO_WALL)
            outcome is RelaxTrial.Stopped -> base.copy(step = Step.R4, end = EndReason.STOPPED)
            outcome is RelaxTrial.Unavailable -> base.copy(step = Step.R4, end = EndReason.UNAVAILABLE, detail = outcome.reason)
            !improves(outcome) -> base.copy(step = Step.R4, end = EndReason.NO_GAIN)
            else -> base
        }
    }

    /** R1→R2→R3。古くなっていたら R4（何も変えない）。 */
    fun next(f: Flow, fresh: Boolean): Flow = when {
        f.step != Step.R1 && f.step != Step.R2 -> f
        !fresh -> f.copy(step = Step.R4, end = EndReason.STALE)
        f.step == Step.R1 -> f.copy(step = Step.R2)
        else -> f.copy(step = Step.R3)
    }

    fun decline(f: Flow): Flow = if (f.step == Step.R4 || f.step == Step.R5) f else f.copy(step = Step.R4, end = EndReason.DECLINED)

    /** 「承認して反映する」を押せるか: 理由が空でなく、改善のある Result で、試算が今のデータのもの。 */
    fun canApprove(reason: String, outcome: RelaxTrial.Outcome?, fresh: Boolean): Boolean =
        reason.isNotBlank() && improves(outcome) && fresh

    /** 押した: R3 でまだ押していなければ decisionId を刻んで返す。2 回目（同じ決定）は null＝何もしない。 */
    fun press(f: Flow, decisionId: String): Flow? =
        if (f.step != Step.R3 || f.pressedId != null) null else f.copy(decisionId = decisionId, pressedId = decisionId)

    /** 反映後の確認: 当てた後の必須件数が試算の rr と同じときだけ成功。 */
    fun postCheckOk(expected: Int, got: Int): Boolean = expected >= 0 && got == expected

    /** VM の結果から最後の状態へ。成功と言うのは事後確認が合ったときだけ。 */
    fun finish(f: Flow, v: Verdict): Flow = when (v) {
        Verdict.Stale -> f.copy(step = Step.R4, end = EndReason.STALE)
        Verdict.NotReproduced -> f.copy(step = Step.R4, end = EndReason.NOT_REPRODUCED)
        Verdict.Blocked -> f.copy(step = Step.R4, end = EndReason.BLOCKED)
        Verdict.SaveFailed -> f.copy(step = Step.R4, end = EndReason.SAVE_FAILED)
        is Verdict.Applied -> if (postCheckOk(f.expectedHard, v.postHard)) f.copy(step = Step.R5, postHard = v.postHard)
            else f.copy(step = Step.R4, end = EndReason.ROLLED_BACK, postHard = v.postHard)
    }

    /** 操作ログの 1 行（承認）。 */
    fun approvalLog(decisionId: String, trialId: String, reason: String, changes: List<String>): String =
        "S6承認 decisionId=$decisionId trial=$trialId 理由=${reason.trim().replace('\n', ' ')} 前→後 ${changes.joinToString("、")} $APPROVER_LINE"

    /** 操作ログの 1 行（反映）。結果＝成功・失敗・復旧。 */
    fun applyLog(result: String, check: Int): String = "S6反映 結果=$result check=$check"
}
