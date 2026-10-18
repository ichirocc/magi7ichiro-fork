package com.magi.app.ui

/**
 * [3.719.0 視覚弱点者対応 第1便] 文字拡大・コントラストの純ロジック。Composable は `LocalDensity.current.fontScale` と
 * `Color.luminance()` を渡すだけ＝ホスト JVM のテストが同じ数で検算できる。表示のみ・採点不変。
 */

/** 端末の文字拡大がこの倍率以上なら、1 行で切っていた文を 1 行増やす（M3 の「大」＝1.3×）。 */
internal const val A11Y_WRAP_FONT_SCALE = 1.3f

/** 1 行＋「…」の文の行数。拡大 1.3× 以上は [base]+1 行（名前・ボタンの語が「…」で消えないため）。 */
internal fun a11yMaxLines(fontScale: Float, base: Int = 1): Int = if (fontScale >= A11Y_WRAP_FONT_SCALE) base + 1 else base

/** WCAG 2.x の相対輝度（`#rrggbb`）。Compose の `Color.luminance()` と同じ式＝テストが同じ数で検算するための写し。 */
internal fun wcagLuminance(hex: String): Float {
    val s = hex.removePrefix("#")
    fun ch(i: Int): Float {
        val c = s.substring(i, i + 2).toInt(16) / 255f
        return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    return 0.2126f * ch(0) + 0.7152f * ch(2) + 0.0722f * ch(4)
}

/** 2 つの相対輝度のコントラスト比（(明+0.05)/(暗+0.05)、1 以上）。 */
internal fun contrastRatio(lumA: Float, lumB: Float): Float = (maxOf(lumA, lumB) + 0.05f) / (minOf(lumA, lumB) + 0.05f)

internal fun contrastRatio(hexA: String, hexB: String): Float = contrastRatio(wcagLuminance(hexA), wcagLuminance(hexB))

/** セル地と surface のコントラストがこれ未満なら、違反の無いセルにも 1dp の輪郭を引く（似た明度の隣のセルと切り分ける）。 */
internal const val OUTLINE_CONTRAST_MIN = 1.5f

internal fun lowContrastOutline(lumBg: Float, lumSurface: Float): Boolean = contrastRatio(lumBg, lumSurface) < OUTLINE_CONTRAST_MIN

/** `MagiAccent.orange`（要調整の札・バッジ）と要調整の既定の違反色。白地 3.99:1・#E5E5E5 で 3.16:1（UI 部品の 3:1 を満たす）。
 *  旧 #E08A1E（2.68:1）/#F59E0B（2.15:1）は白地の文字・枠として足りなかった。表示のみ＝保存済みのユーザー指定色は不変。 */
internal const val ACCENT_ORANGE_HEX = "#B86E00"
internal const val SOFT_VIO_DEFAULT_HEX = ACCENT_ORANGE_HEX
