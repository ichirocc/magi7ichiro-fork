package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.V6WebCompat
import com.magi.app.v6.V6FinalPort
import kotlin.math.roundToInt

/** Remaining V6 Web UI concepts, represented as Compose-native panels. */
@Composable
fun V6RemainingScreens(ui: UiState, vm: MagiViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HeaderBar(ui)
        OverviewDashboard(ui)
        CheckSummaryView(ui)
        FlagsView(ui, vm)
        ColorSettingsView(ui)
        OperatorLogView(ui)
        BottomNav(ui, vm)
    }
}

@Composable
fun HeaderBar(ui: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .width(38.dp)
                    .height(38.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("M", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary) }
            Column(Modifier.weight(1f)) {
                Text("MAGI 勤務表", fontWeight = FontWeight.Bold)
                Text("ネイティブ最適化エンジン", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (ui.running) "RUN" else "READY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = if (ui.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
fun RingGauge(label: String, value: Int, max: Int, modifier: Modifier = Modifier) {
    val pct = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(78.dp)
                .height(78.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                .border(8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f + pct * 0.55f), RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value.toString(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("${(pct * 100).roundToInt()}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun OverviewDashboard(ui: UiState) {
    // [D2] 「守れない約束(HARD件数)」は直下の CheckSummaryView が言葉で示すため、ここの重複リングを廃止。
    //   別指標の「気になる点(総違反)」「注意の日(高リスク日)」のみ表示し、HARDの三重表示を解消。
    SectionSegment("ようす（俯瞰）", "気になる点・注意したい日") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            RingGauge("気になる点", ui.totalViolations, (ui.staff * ui.days).coerceAtLeast(1), Modifier.weight(1f))
            RingGauge("注意の日", ui.v6?.highRiskDays ?: 0, ui.days.coerceAtLeast(1), Modifier.weight(1f))
        }
    }
}

@Composable
fun CheckSummaryView(ui: UiState) {
    SectionSegment("チェック概要", "問題がないかの確認") {
        val status = if (ui.bestHard == 0L) "配れます（守るべき約束はすべて守れています）" else "もう少し（守れていない約束 ${ui.bestHard}）"
        Text(status, fontWeight = FontWeight.Bold,
            color = if (ui.bestHard == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        // [校正] 生の操作ログは「ようす」に出さない（B2 平易/ B8 最小限）。詳細はログ＝詳細設定の「操作ログ」へ集約。
    }
}

@Composable
fun FlagsView(ui: UiState, vm: MagiViewModel) {
    SectionSegment("計算方式と詳細設定", "アルゴリズムと最適化オプション") {
        // [校正] OutlinedButton では選択中が分からず「選べない」ように見えていた。
        //   FilterChip にし、選択中＝塗り＋✓で明示（タップで切替わるのが分かる）。
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.magi.app.v6.V6Algorithm.values().forEach { alg ->
                FilterChip(
                    selected = ui.v6Algorithm == alg,
                    onClick = { vm.setV6Algorithm(alg) },
                    enabled = !ui.running,
                    label = { Text(alg.name, fontSize = 11.sp) },
                    leadingIcon = if (ui.v6Algorithm == alg) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = ui.softPolish, onCheckedChange = vm::setSoftPolish, enabled = !ui.running)
            Spacer(Modifier.width(8.dp))
            Text("仕上げ最適化（品質を磨く）")
        }
        val label = V6FinalPort.getAlgorithmLabel(ui.budgetSec)
        Text("選択時間 ${ui.budgetSec}s → ${label.icon} ${label.name}（${label.tech}）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ColorSettingsView(ui: UiState) {
    SectionSegment("違反種別の色", "違反カテゴリと重大度の色凡例") {
        val keys = MirrorKeys.all
        keys.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    val sev = V6WebCompat.severityFromVioKey(key)
                    val active = (ui.breakdown[key] ?: 0) > 0
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("$key\n$sev", fontSize = 10.sp, textAlign = TextAlign.Center, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun OperatorLogView(ui: UiState) {
    SectionSegment("操作ログ", "最近の処理ログ") {
        // [進捗の見える化] 実行中は専門ログより先に 改善率/残り時間/探索数 を1行で示す。
        if (ui.running) Text(progressSummary(ui), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (ui.logs.isEmpty()) Text("ログなし", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ui.logs.take(12).forEach { line ->
            Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BottomNav(ui: UiState, vm: MagiViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.refreshCheck() }, enabled = ui.loaded && !ui.running) { Text("確認") }
            OutlinedButton(onClick = { vm.generateSimple() }, enabled = ui.loaded && !ui.running) { Text("作成") }
            Button(onClick = { vm.runV6FullOptimize() }, enabled = ui.loaded && !ui.running) { Text("RUN") }
            OutlinedButton(onClick = { vm.start() }, enabled = ui.loaded && !ui.running) { Text("SA") }
            OutlinedButton(onClick = { vm.runLightOptimize() }, enabled = ui.loaded && !ui.running) { Text("軽量") }
            if (ui.running) OutlinedButton(onClick = { vm.stop() }) { Text("停止") }
        }
    }
}
