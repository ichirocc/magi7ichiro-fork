package com.magi.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MAGI デザインシステムのトークン補助層（docs/magi_design_system.md）。
 *
 * 一次ソースは Material3 の `MaterialTheme.colorScheme / typography / shapes`。
 * ここでは colorScheme に無い「意味色 / シフト色 / 余白(spacing)」だけを補い、二重管理を避ける。
 * Phase B の共通コンポーネント（MagiTagChip / MagiCalendarMonthView 等）が参照する。
 */
@Immutable
object MagiAccent {
    val blue = Color(0xFF3B82F6)    // 実行中 / 早番
    val green = Color(0xFF22C55E)   // 成功 / 日勤
    val orange = Color(0xFFF59E0B)  // 警告 / 夜勤
    val purple = Color(0xFFA855F7)  // 遅番 / 個人属性
    val pink = Color(0xFFEC4899)    // 希望 / 個人属性
    val red = Color(0xFFEF4444)     // 重大違反 / NG制約
    val gray = Color(0xFF9CA3AF)    // 休み / 無効

    /** 色ピッカー等で提示する既定パレット。 */
    val all: List<Color> = listOf(blue, green, orange, purple, pink, red, gray)
}

/**
 * 警告(warning)セマンティック色。M3 には warning ロールが無いため独自トークンとして集約。
 * 現在のテーマ（surface の明るさ）から明/暗を判定し、container/onContainer を返す（コントラスト確保）。
 * 成功=tertiary、注意=error、主操作=primary はテーマロールを直接使う。
 */
@Composable
fun magiWarnColors(): Pair<Color, Color> {
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (darkSurface) Color(0xFF5B4300) to Color(0xFFFBEAD0)   // 暗: アンバー容器＋淡い文字
    else Color(0xFFFBEAD0) to Color(0xFF6B4E00)                      // 明: 淡いアンバー＋濃い文字
}

// シフト記号→色の既定フォールバックは V6WebCompat.resolveShiftColor が唯一の真実源。
// （以前ここに同等の shiftAccentFallback があったが未配線・二重管理のため削除）

/**
 * 前景（記号・文字）色を背景に対して必ず読めるようにする（WCAG コントラスト比）。
 * ユーザー指定色 [preferred] が背景 [bg] に対して [minRatio] 以上のコントラストを持てば
 * そのまま採用。不足する場合のみ 黒/白 のうち高コントラストな方へフォールバックする。
 * ＊描画時のみの補正で、保存済みの色データ（shiftTextHex 等）は一切書き換えない（HF77 セーフ）。
 * 記号は短い太字グリフのため既定しきい値は 4.5（通常テキスト基準）。
 */
fun ensureReadable(bg: Color, preferred: Color, minRatio: Float = 4.5f): Color {
    fun ratio(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
    if (ratio(bg, preferred) >= minRatio) return preferred
    return if (ratio(bg, Color.White) >= ratio(bg, Color.Black)) Color.White else Color.Black
}

/** 4dp グリッドの余白トークン（docs §2.2）。 */
@Immutable
object MagiSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val section: Dp = 20.dp   // セクション（カード）間
    val screenH: Dp = 16.dp   // 画面左右パディング
}
