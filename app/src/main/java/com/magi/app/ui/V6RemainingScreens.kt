package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.V6WebCompat

// [3.86.0 デッドコード撤去] 未描画の合成画面 `V6RemainingScreens` と、そこからのみ実呼出だった
//   HeaderBar / RingGauge / OverviewDashboard / FlagsView / OperatorLogView / BottomNav を撤去
//   （外部参照0を確認。並列監査 3.84.0 の報告に基づく）。他画面で live な CheckSummaryView（分析タブ）と
//   ColorSettingsView（詳細設定）、およびそれらが使う SectionSegment のみ残置。表示のみ・スコアリング不変。

@Composable
fun SectionSegment(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun CheckSummaryView(ui: UiState, proMode: Boolean = false) {
    SectionSegment("チェック概要", if (proMode) null else "問題がないかの確認") {
        val status = if (ui.bestHard == 0L) (if (proMode) "配れます" else "配れます（守るべき約束はすべて守れています）") else (if (proMode) "未解決 ${ui.bestHard}" else "もう少し（守れていない約束 ${ui.bestHard}）")
        Text(status, fontWeight = FontWeight.Bold,
            color = if (ui.bestHard == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        // [校正] 生の操作ログは「ようす」に出さない（B2 平易/ B8 最小限）。詳細はログ＝詳細設定の「操作ログ」へ集約。
    }
}

@Composable
fun ColorSettingsView(ui: UiState) {
    SectionSegment("違反種別の色", "重大度の色凡例（赤=必須 / 橙=要調整 / 灰=情報）") {
        val cs = MaterialTheme.colorScheme
        val keys = MirrorKeys.all
        keys.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    val sev = V6WebCompat.severityFromVioKey(key)
                    // [色覚/日本語化] 色に依らない文字バックアップ。英語enum(CRITICAL/…)ではなく日本語の重大度語で示す。
                    val sevJp = when (sev) { "CRITICAL" -> "必須"; "HIGH", "WARN" -> "要調整"; else -> "情報" }
                    // [不具合修正] 旧版は「重大度の色凡例」なのに色が primary(ブランド緑)＝違反有無で、重大度と無関係だった。
                    //   緑は普遍的に「OK」の意で違反表示に不適＋統一パレット(赤=必須/橙=要調整)とも乖離。
                    //   重大度で静的に色分けし(凡例=データ非依存の参照)、現在件数は末尾に併記する。
                    val count = ui.breakdown[key] ?: 0
                    val bg = when (sev) {
                        // [コントラスト] 白文字 on 0xEF4444 は 3.76:1 で WCAG AA 不足のため、濃い赤(約5.9:1)へ。
                        "CRITICAL" -> Color(0xFFBA1A1A)
                        "HIGH", "WARN" -> MagiAccent.orange
                        else -> cs.surfaceVariant   // INFO
                    }
                    val fg = when (sev) {
                        "CRITICAL" -> Color(0xFFFFFFFF)
                        "HIGH", "WARN" -> Color(0xFF231400)
                        else -> cs.onSurfaceVariant
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .background(bg, RoundedCornerShape(8.dp))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("$key\n$sevJp" + (if (count > 0) " ·$count" else ""), fontSize = 12.sp, textAlign = TextAlign.Center, color = fg) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
