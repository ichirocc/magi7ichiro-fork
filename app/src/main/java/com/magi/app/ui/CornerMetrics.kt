package com.magi.app.ui

/**
 * iOS 26 の同心の角（2026-10-29）: 内側の角＝外側の角−余白。カード（shapes.medium）の中の面は shapes.small。
 * Compose に依らない数だけ（ホストのテストで確かめる）。
 */
object CornerMetrics {
    const val INNER_DP = 10
    const val CARD_INSET_DP = 16
    const val CARD_DP = INNER_DP + CARD_INSET_DP
    const val SHEET_DP = 28
}

/** 外側の角 [outer] から余白 [inset] だけ内側に置いた面の角（小さすぎる角は [min] で止める）。 */
internal fun concentricCornerDp(outer: Int, inset: Int, min: Int = 4): Int = (outer - inset).coerceAtLeast(min)
