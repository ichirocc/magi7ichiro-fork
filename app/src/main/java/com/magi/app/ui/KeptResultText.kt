package com.magi.app.ui

import java.util.Locale
import kotlin.math.roundToLong

/**
 * keep-best で前回を維持したときの文言。比べる順は `betterReport`（必須→重み→合計）なので、
 * 決め手になった項目と重みを必ず見せる（合計だけ減って維持されると理由が読めない）。
 */
object KeptResultText {
    data class Score(val hard: Long, val weighted: Double, val total: Int)

    private fun fmt(v: Long) = String.format(Locale.ROOT, "%,d", v)
    private fun w(s: Score) = fmt(s.weighted.roundToLong())
    private fun parts(s: Score) = "必須${s.hard}・重み${w(s)}・合計${s.total}"

    fun reason(now: Score, prev: Score): String = when {
        now.hard != prev.hard -> if (now.hard > prev.hard) "必須が多いため" else "必須が少ないため"
        now.weighted != prev.weighted -> if (now.weighted > prev.weighted) "重みが大きいため" else "重みが小さいため"
        now.total != prev.total -> if (now.total > prev.total) "合計が多いため" else "合計が少ないため"
        else -> "同じ点数のため"
    }

    fun screen(now: Score, prev: Score): String =
        "今回（${parts(now)}）は前回（${parts(prev)}）より改善しませんでした（${reason(now, prev)}。比べる順＝必須→重み→合計）。前回の結果を維持します。"

    fun log(prefix: String, now: Score, prev: Score): String =
        "$prefix: 今回 ${parts(now)} は前回 ${parts(prev)} 以下に改善せず（${reason(now, prev)}）→ 前回を維持"

    /** 維持の文言に理由を1文足す（入口で上限0のセルを外して必須が増えたとき等）。 */
    fun withNote(base: String, note: String?): String = if (note == null) base else "$base $note"
}
