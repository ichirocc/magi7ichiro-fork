package com.magi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import com.magi.app.ui.MagiApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by rememberSaveable { mutableStateOf(0) }
            // [校正] ステータスバー/ナビバーのアイコン明暗をアプリの実テーマに追従させる
            //   （明背景＝暗アイコン）。明テーマで上部が白くアイコンが埋もれる問題を解消。
            val dark = when (themeMode) { 1 -> false; 2 -> true; 3 -> false; else -> isSystemInDarkTheme() }
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            MagiTheme(themeMode) {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .consumeWindowInsets(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MagiApp(themeMode = themeMode, onThemeMode = { themeMode = it })
                }
            }
        }
    }
}

@Composable
private fun MagiTheme(mode: Int = 0, content: @Composable () -> Unit) {
    // mode: 0=システム / 1=ライト / 2=ダーク / 3=高コントラスト(ユニバーサルデザイン)
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val highContrast = lightColorScheme(
        primary = Color(0xFF005048), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF003C36), onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF0B3A5E), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCFE0EE), onSecondaryContainer = Color(0xFF001A2E),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE8EDF0), onSurfaceVariant = Color(0xFF1A2226),
        error = Color(0xFF8C0009), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD4), onErrorContainer = Color(0xFF2D0001),
        outline = Color(0xFF000000),
    )
    // [Material 3 トーナル配色 / Material Theme Builder 相当]
    //   種色＝ダスティブルー(主)・ラベンダー(副)・セージ(三次)、ニュートラルを暖色(クリーム)へ傾けたプランナー調。
    //   全ロールをトーンから導出し、各 on*/container の WCAG コントラストを実測済（本文4.5:1・UI3:1 を全て充足）。
    val colors = if (mode == 3) highContrast else if (dark) darkColorScheme(
        primary = Color(0xFFB9C3FF), onPrimary = Color(0xFF1A2D60),
        primaryContainer = Color(0xFF324478), onPrimaryContainer = Color(0xFFDEE1FF),
        secondary = Color(0xFFC3C3F0), onSecondary = Color(0xFF2B2D60),
        secondaryContainer = Color(0xFF424478), onSecondaryContainer = Color(0xFFE1E0FF),
        tertiary = Color(0xFF98D6AE), onTertiary = Color(0xFF003823),
        tertiaryContainer = Color(0xFF1C5138), onTertiaryContainer = Color(0xFFB2F1C9),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF14130E), onBackground = Color(0xFFE8E2D6),
        surface = Color(0xFF14130E), onSurface = Color(0xFFE8E2D6),
        surfaceVariant = Color(0xFF48473F), onSurfaceVariant = Color(0xFFCAC7B8),
        surfaceTint = Color(0xFFB9C3FF), scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF3B3833), surfaceDim = Color(0xFF14130E),
        surfaceContainerLowest = Color(0xFF0F0E0A), surfaceContainerLow = Color(0xFF1C1B16),
        surfaceContainer = Color(0xFF201F1A), surfaceContainerHigh = Color(0xFF2B2925),
        surfaceContainerHighest = Color(0xFF36342F),
        inverseSurface = Color(0xFFE8E2D6), inverseOnSurface = Color(0xFF313029), inversePrimary = Color(0xFF4A63B8),
        outline = Color(0xFF948F80), outlineVariant = Color(0xFF48473F),
    ) else lightColorScheme(
        primary = Color(0xFF4A63B8), onPrimary = Color(0xFFFFFFFF),           // 主操作: ダスティブルー
        primaryContainer = Color(0xFFDEE1FF), onPrimaryContainer = Color(0xFF00164B),
        secondary = Color(0xFF595A8E), onSecondary = Color(0xFFFFFFFF),       // 補助: ラベンダー
        secondaryContainer = Color(0xFFE1E0FF), onSecondaryContainer = Color(0xFF161A4C),
        tertiary = Color(0xFF2E6A4F), onTertiary = Color(0xFFFFFFFF),         // 成功: セージ
        tertiaryContainer = Color(0xFFB2F1C9), onTertiaryContainer = Color(0xFF00210F),
        error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),               // 注意: M3標準（高コントラスト）
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFBF8F2), onBackground = Color(0xFF221F19),     // 暖色クリーム紙＋温かいチャコール
        surface = Color(0xFFFBF8F2), onSurface = Color(0xFF221F19),
        surfaceVariant = Color(0xFFE5E1D8), onSurfaceVariant = Color(0xFF4A4640),
        surfaceTint = Color(0xFF4A63B8), scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFBF8F2), surfaceDim = Color(0xFFDCD9CF),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFBF7F0),
        surfaceContainer = Color(0xFFF6F2EB), surfaceContainerHigh = Color(0xFFF1ECE4),
        surfaceContainerHighest = Color(0xFFECE7DF),
        inverseSurface = Color(0xFF37352F), inverseOnSurface = Color(0xFFF3EFE6), inversePrimary = Color(0xFFB9C3FF),
        outline = Color(0xFF7B7768), outlineVariant = Color(0xFFCCC7BA),
    )
    // 見出し大・本文静か・数値最大（添付テイスト）。spはシステム文字サイズ設定に追従。
    val t = Typography(
        displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
        headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
        titleSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp),
        // [判読性・全画面底上げ] 実機で「文字が小さい」との指摘を受け本文/ラベル層を +1sp 底上げ（最小tier=14sp）。
        //   密な表の折返しを避けるため見出し層(title Medium/Large・headline・display)は据え置き。1箇所で全画面横断。
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
        bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
        bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        // labelSmall はチップ/凡例/補足/違反内訳の下限。11sp(Material既定)→13→14sp へ継続底上げ。
        labelSmall = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    )
    // 柔らかいカード(20dp)・タイル(24dp)・ピル(round)。
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
    MaterialTheme(colorScheme = colors, typography = t, shapes = shapes, content = content)
}
