package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun ColorSettingsView(ui: UiState, vm: MagiViewModel) {
    // [色変更/スクショ指摘] 旧版は read-only の凡例で、チップを押しても何も起きず「色を変更出来ない」と誤解を
    //   招いていた（カード題も「違反種別の色」）。チップタップでその重大度の色を変更できるように:
    //   必須=既存トークン __vio__（外観の「違反の色」と同一）/ 要調整=新トークン __vioSoft__。灰=情報は固定。
    //   変更はグリッド枠・角マーク・日ヘッダ下線・凡例へ即反映。表示のみ・スコアリング不変。
    var pick by remember { mutableStateOf<String?>(null) }   // "hard" / "soft"
    SectionSegment("違反種別の色", "重大度の色凡例。チップをタップで 必須/要調整 の色を変更（灰=情報は固定）") {
        val cs = MaterialTheme.colorScheme
        val hardBg = ui.violationColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: Color(0xFFBA1A1A)
        val softBg = ui.violationSoftColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: MagiAccent.orange
        val keys = MirrorKeys.all
        keys.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    val sev = V6WebCompat.severityFromVioKey(key)
                    // [色覚/日本語化] 色に依らない文字バックアップ。英語enum(CRITICAL/…)ではなく日本語の重大度語で示す。
                    val sevJp = when (sev) { "CRITICAL" -> "必須"; "HIGH", "WARN" -> "要調整"; else -> "情報" }
                    val count = ui.breakdown[key] ?: 0
                    val bg = when (sev) {
                        "CRITICAL" -> hardBg
                        "HIGH", "WARN" -> softBg
                        else -> cs.surfaceVariant   // INFO
                    }
                    // [コントラスト] ユーザー色でも読めるよう WCAG で文字色を保証（白が不足なら黒へ）。
                    val fg = if (sev == "CRITICAL" || sev == "HIGH" || sev == "WARN") ensureReadable(bg, Color(0xFFFFFFFF)) else cs.onSurfaceVariant
                    val editable = sev != "INFO" && !ui.running
                    Box(
                        Modifier
                            .weight(1f)
                            .background(bg, RoundedCornerShape(8.dp))
                            .then(if (editable) Modifier.clickable { pick = if (sev == "CRITICAL") "hard" else "soft" } else Modifier)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("$key\n$sevJp" + (if (count > 0) " ·$count" else ""), fontSize = 12.sp, textAlign = TextAlign.Center, color = fg) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    when (pick) {
        "hard" -> ColorPickerDialog(
            kigou = "必須違反",
            currentHex = ui.violationColorHex,
            onPick = { hex -> vm.setViolationColor(hex); pick = null },
            onReset = { vm.resetViolationColor(); pick = null },
            onClose = { pick = null },
        )
        "soft" -> ColorPickerDialog(
            kigou = "要調整",
            currentHex = ui.violationSoftColorHex,
            onPick = { hex -> vm.setViolationSoftColor(hex); pick = null },
            onReset = { vm.resetViolationSoftColor(); pick = null },
            onClose = { pick = null },
        )
    }
}
