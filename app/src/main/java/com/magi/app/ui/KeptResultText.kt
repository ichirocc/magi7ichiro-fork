package com.magi.app.ui

import java.util.Locale
import kotlin.math.roundToLong

/**
 * keep-best で前回を維持したときの文言。比べる順は `betterReport`（必須→重み付き評価点→合計）なので、
 * 決め手になった項目と重みを必ず見せる（合計だけ減って維持されると理由が読めない）。
 */
object KeptResultText {
    data class Score(val hard: Long, val weighted: Double, val total: Int)

    private fun fmt(v: Long) = String.format(Locale.ROOT, "%,d", v)
    private fun w(s: Score) = fmt(s.weighted.roundToLong())
    private fun parts(s: Score) = "必須${s.hard}・評価点${w(s)}・合計${s.total}"

    fun reason(now: Score, prev: Score): String = when {
        now.hard != prev.hard -> if (now.hard > prev.hard) "必須違反が多いため" else "必須違反が少ないため"
        now.weighted != prev.weighted -> if (now.weighted > prev.weighted) "重み付き評価点が高いため" else "重み付き評価点が低いため"
        now.total != prev.total -> if (now.total > prev.total) "違反の合計が多いため" else "違反の合計が少ないため"
        else -> "同じ点数のため"
    }

    fun screen(now: Score, prev: Score): String =
        "改善が確認できなかったため、前回の勤務表を維持しました（今回の案は採用していません）。理由：${reason(now, prev)}（今回 ${parts(now)}／前回 ${parts(prev)}。比べる順＝必須→評価点→合計）。"

    fun log(prefix: String, now: Score, prev: Score): String =
        "$prefix: 今回 ${parts(now)} は前回 ${parts(prev)} 以下に改善せず（${reason(now, prev)}）→ 前回を維持"

    /** 維持の文言に理由を1文足す（入口で上限0のセルを外して必須が増えたとき等）。 */
    fun withNote(base: String, note: String?): String = if (note == null) base else "$base $note"
}

/** 計算が終わったときの通知（判断支援 UX 仕様 §9）。必須違反 0 件は「計算が終わった」と「配れる」を分けて、配布前確認へ促す。 */
internal fun completedText(hard: Int, breakdown: Map<String, Int>, how: String): String =
    if (hard == 0) "計算が完了しました$how。設定した必須条件を満たしています。配布前の確認に進んでください（要調整 ${unitTotals(breakdown, com.magi.app.v6.MirrorKeys.soft) ?: "なし"}）"
    else "計算が完了しました$how。${reportCountsText(breakdown)}"

/** 採用した結果の件数が入力より増えたのに重み付き評価点が下がったとき、採用の理由を 1 文で（件数だけ見ると悪化に見えるため）。それ以外は null。 */
internal fun adoptedDespiteMoreText(prevTotal: Int, newTotal: Int, prevWeighted: Double, newWeighted: Double): String? =
    if (newTotal > prevTotal && newWeighted < prevWeighted) "件数は +${newTotal - prevTotal} ですが、重い違反が減ったため改善として採用しました" else null
