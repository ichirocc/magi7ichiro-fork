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

    /** 前の勤務表のままにしたときのお知らせ（比べた結果は件数で言う。内部の評価点は出さない）。 */
    fun screen(nowBreakdown: Map<String, Int>, prevBreakdown: Map<String, Int>): String =
        "再作成した案は前の勤務表より良くなかったため採用していません（再作成した案：${reportCountsText(nowBreakdown)}／前の勤務表：${reportCountsText(prevBreakdown)}）。勤務表は変わっていません。"

    const val KEPT_TITLE = "再作成しましたが、前の勤務表のほうが良かったため前のままです"

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

internal const val NOTIFY_RUNNING_TITLE = "勤務表をつくっています"
internal const val NOTIFY_DONE_TITLE = "勤務表ができました"

/** 完了通知の 2 行（BigTextStyle）。1 行目＝必須違反、2 行目＝要調整（pt の族は数えない）と次の一歩。 */
internal fun notifyDoneLines(hard: Int, breakdown: Map<String, Int>): List<String> {
    val soft = com.magi.app.v6.MirrorKeys.soft.filter { unitOf(it) != "pt" }
    val softText = unitTotals(breakdown, soft) ?: "なし"
    return if (hard == 0) listOf("必須違反はありません", "要調整 $softText。配布前の確認で中身を確かめてください")
    else listOf("必須違反 ${unitTotals(breakdown, com.magi.app.v6.MirrorKeys.hard) ?: "${hard}件"}", "要調整 $softText。開いて直し方を確かめてください")
}
