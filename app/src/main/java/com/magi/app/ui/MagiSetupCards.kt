package com.magi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import java.time.LocalDate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.CoverageVerdict
import com.magi.app.v6.MirrorKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/**
 * CSVのバイト列を文字列へ復号する。妥当な UTF-8 ならそれを採用し、そうでなければ日本の Excel CSV で
 * 一般的な CP932(Shift-JIS) とみなす。先頭の BOM は除去する。これにより Shift-JIS の勤務表CSVが
 * 文字化けせず取り込める（UTF-8 として bytes を読むと壊れていた）。
 */

@Composable
internal fun MonthPickerCard(ui: UiState, vm: MagiViewModel) {
    if (!ui.loaded) return
    val cs = MaterialTheme.colorScheme
    val label = remember(ui.startDate) {
        runCatching { val d = LocalDate.parse(ui.startDate); "${d.year}年 ${d.monthValue}月" }.getOrNull()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("対象の月", style = MaterialTheme.typography.titleMedium)
            Text("勤務表を作る月です。変えると、その月の日数に合わせて表を作り直します。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.shiftMonth(-1) }, enabled = !ui.running,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("前の月") }
                Text(label ?: "未設定", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.shiftMonth(1) }, enabled = !ui.running,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("次の月") }
            }
            OutlinedButton(onClick = { vm.setThisMonth() }, enabled = !ui.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("今月にする") }
        }
    }
}


@Composable
internal fun SetupGuideCard(ui: UiState, vm: MagiViewModel) {
    if (!ui.loaded) return
    val c = vm.setupCounts()
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("初期設定の手順", style = MaterialTheme.typography.titleMedium)
            // [E1] 番号順(①〜⑤)とeditScopeの不一致を解消：毎月触る項目と年次マスター項目を分けて表示。
            //   ①基本情報・④制約・⑤回数範囲は「年次マスター」scopeにあり、月次では編集しない。
            Text("── 毎月 ──", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            GuideRow("② 希望シフト", "${c.wishes}件", c.wishes > 0)
            GuideRow("③ 必要人数", if (c.needDay > 0) "${c.needDay}件（個別指定）" else "シフト既定のみ", true)
            Text("── 年次マスター（制度・人員が変わったときだけ）──", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            GuideRow("① 基本情報", "${c.days}日 / ${c.staff}名 / ${c.shifts}シフト / ${c.groups}グループ", c.days > 0 && c.staff > 0 && c.shifts > 0)
            GuideRow("④ 制約", "${c.constraints}件", true)
            GuideRow("⑤ 個人の回数範囲", "${c.ranges}件", true)
            val next = when {
                c.staff == 0 || c.shifts == 0 -> "基本情報（スタッフ／シフト）を整えましょう。"
                c.wishes == 0 -> "次に『希望シフト』を登録すると できあがり度 が上がります。"
                else -> "準備OK。ホームの『勤務表をつくる』で勤務表を作成しましょう。"
            }
            Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text("次の一手: $next", color = cs.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}


@Composable
internal fun GuideRow(label: String, value: String, done: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (done) "✓" else "・", color = if (done) cs.primary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
internal fun SettingsCard(ui: UiState, vm: MagiViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("最適化設定", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("並列ワーカー（同時に計算する数）: ${ui.workers}")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setWorkers((ui.workers - 1).coerceAtLeast(1)) },
                    enabled = !ui.running && ui.workers > 1, modifier = Modifier.height(48.dp).semantics { contentDescription = "同時計算数を減らす" }) { Text("−", fontSize = 20.sp) }
                Text("${ui.workers}", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                Button(onClick = { vm.setWorkers((ui.workers + 1).coerceAtMost(16)) },
                    enabled = !ui.running && ui.workers < 16, modifier = Modifier.height(48.dp).semantics { contentDescription = "同時計算数を増やす" }) { Text("＋", fontSize = 20.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Text("時間予算（計算の制限時間・上限5分／停滞時はさらに早期終了）: ${ui.budgetSec} 秒")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setBudget((ui.budgetSec - 60).coerceAtLeast(10)) },
                    enabled = !ui.running && ui.budgetSec > 10, modifier = Modifier.height(48.dp)) { Text("− 60秒") }
                Text("${ui.budgetSec} 秒", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
                Button(onClick = { vm.setBudget((ui.budgetSec + 60).coerceAtMost(MAX_BUDGET_SEC)) },
                    enabled = !ui.running && ui.budgetSec < MAX_BUDGET_SEC, modifier = Modifier.height(48.dp)) { Text("＋ 60秒") }
            }
            Text("計算方式: ${v6AlgorithmLabel(ui.v6Algorithm)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                V6Algorithm.values().forEach { alg ->
                    val selected = ui.v6Algorithm == alg
                    if (selected) {
                        Button(onClick = { vm.setV6Algorithm(alg) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(v6AlgorithmLabel(alg)) }
                    } else {
                        OutlinedButton(onClick = { vm.setV6Algorithm(alg) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(v6AlgorithmLabel(alg)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = ui.softPolish,
                    onCheckedChange = { vm.setSoftPolish(it) },
                    enabled = !ui.running,
                )
                Spacer(Modifier.width(8.dp))
                Text("仕上げ最適化（品質を磨く）", fontSize = 13.sp)
            }
            Spacer(Modifier.height(14.dp))
            // [バージョン表示] インストール済みAPKの versionName/versionCode を実行時に取得して表示。
            //   これでユーザーが「今どの版か」を確認できる（例: CSVのBOM対応は 2.90.0 以降）。
            val ctx = LocalContext.current
            val versionLabel = remember(ctx) {
                runCatching {
                    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    "${pi.versionName} (${pi.longVersionCode})"
                }.getOrDefault("不明")
            }
            Text("バージョン: $versionLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [見やすさ] 計算方式(V6Algorithm)の一般向け日本語ラベル。技術名(AUTO/RSI 等)は操作者に不明なため。 */
private fun v6AlgorithmLabel(alg: V6Algorithm): String = when (alg) {
    V6Algorithm.AUTO -> "おまかせ"
    V6Algorithm.V5 -> "高速"
    V6Algorithm.ALNS -> "破壊再構築"
    V6Algorithm.RSI -> "違反集中"
    V6Algorithm.RSI_PLUS -> "違反集中＋"
    V6Algorithm.PORTFOLIO -> "並列(複数案)"
}


@Composable
internal fun ActionCard(ui: UiState, vm: MagiViewModel, onBgOptimize: () -> Unit = {}) {
    // [冗長性削減] 主操作「勤務表をつくる」は思考誘導カード＋下部バーが担うため、ここは
    //   「ほかの作り方」(速く/かんたん) と「閉じても続ける(バックグラウンド)」だけに絞る。進捗/停止も他で表示。
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ほかの作り方", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.start() }, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("速くつくる") }
                OutlinedButton(onClick = { vm.runLightOptimize() }, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("かんたんに") }
            }
            // [冗長性F1] ソフト研磨ボタンは CopilotCard（必須充足後の文脈付き案内）と重複のため撤去。
            //   ON/OFFの自動研磨は設定の「仕上げ最適化」スイッチ、手動起動は Copilot 側に一本化。
            OutlinedButton(onClick = onBgOptimize, enabled = ui.loaded && !ui.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("閉じても大丈夫（あとで通知でお知らせ）") }
        }
    }
}


@Composable
internal fun AdvancedSettingsSection(
    ui: UiState,
    vm: MagiViewModel,
    onExportLog: () -> Unit,
    onExportJson: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }   // 既定は閉
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("詳細設定（上級者向け）", style = MaterialTheme.typography.titleMedium)
                    Text("ログ・違反色トークン。一般の運用では触りません。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // [Planner テイスト] グリフ「▾/▸」をやめ、やわらかい丸の中に整ったアイコンシェブロン。
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "閉じる" else "開く",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    // [冗長性] 旧 FlagsView（計算方式＋仕上げ最適化）は「最適化設定」カードと完全重複の
                    //   ため撤去。設定の操作は「最適化設定」に一本化。
                    // [冗長性J1] V6DashboardCard（1ヶ月俯瞰・生指標）は分析タブ「プロ」表示と重複のため
                    //   ここから撤去し、分析タブ(プロ)に一本化。ここはログ・違反色トークンのみ。
                    LogsCard(ui = ui, onExportLog = onExportLog, onExportJson = onExportJson)
                    ColorSettingsView(ui)
                }
            }
        }
    }
}



@Composable
internal fun LogsCard(ui: UiState, onExportLog: () -> Unit, onExportJson: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hasAny = ui.opLog.isNotEmpty() || ui.logs.isNotEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ログ", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onExportLog, enabled = hasAny, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("テキスト出力") }
                OutlinedButton(onClick = onExportJson, enabled = hasAny, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("JSON出力") }
            }
            // 操作ログ（監査・新しい順）
            Text("操作ログ（新しい順 ${ui.opLog.size}件）", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            if (ui.opLog.isEmpty()) {
                Text("操作履歴なし", color = cs.onSurfaceVariant, fontSize = 12.sp)
            } else {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        .background(cs.surfaceVariant, RoundedCornerShape(12.dp)).padding(10.dp),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ui.opLog.take(60).forEach { line ->
                            val warn = line.contains("[W]") || line.contains("[E]")
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = if (warn) cs.error else cs.onSurface)
                        }
                    }
                }
            }
            // 診断ログ（エンジン）
            if (ui.logs.isNotEmpty()) {
                Text("診断ログ", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                ui.logs.take(6).forEach {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}


@Composable
internal fun DataActionsCard(
    ui: UiState,
    onOpenJson: () -> Unit, onSample: () -> Unit, onSaveJson: () -> Unit,
    onOpenCsv: () -> Unit, onSaveCsv: () -> Unit, onCheck: () -> Unit,
    onSaveStaffCsv: () -> Unit = {}, onSaveWishesCsv: () -> Unit = {}, onSaveConstraintsCsv: () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("データ", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenJson, enabled = !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("データを開く") }
                OutlinedButton(onClick = onSample, enabled = !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("サンプル") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveJson, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("データを保存") }
                OutlinedButton(onClick = onCheck, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("問題がないか調べる") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("CSV取込") }
                OutlinedButton(onClick = onSaveCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("CSV出力") }
            }
            Text("コンポーネント別 出力（取込種別と対・往復用）",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveStaffCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("スタッフ") }
                OutlinedButton(onClick = onSaveWishesCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("希望") }
                OutlinedButton(onClick = onSaveConstraintsCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("制約") }
            }
        }
    }
}


@Composable
internal fun AppearanceCard(
    themeMode: Int, onThemeMode: (Int) -> Unit, oneHand: Boolean = false, onOneHand: (Boolean) -> Unit = {},
    proMode: Boolean = false, onProMode: (Boolean) -> Unit = {},
) {
    val options = listOf("自動", "明", "暗", "UD")   // [B5/A8] 4セグメント用の短ラベル(=システム/ライト/ダーク/高コントラスト)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("外観", style = MaterialTheme.typography.titleMedium)
            Text("見やすさに合わせて配色を選べます。",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = oneHand, onCheckedChange = onOneHand)
                Spacer(Modifier.width(8.dp))
                Text("片手モード（内容を下方に寄せて親指で届きやすく）", fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            // [B5/A8] 2×2ボタン → 4セグメント（自動/明/暗/UD）。themeMode の index 対応は不変。
            MagiSegmentedControl(options = options, selected = themeMode, onSelect = onThemeMode)
            // [プロ編集] 表示モード。プロ＝数値診断（生指標）を前面に。今後さらに高密度編集を拡張予定。
            Text("表示モード", style = MaterialTheme.typography.titleSmall)
            MagiSegmentedControl(options = listOf("かんたん", "プロ"), selected = if (proMode) 1 else 0, onSelect = { onProMode(it == 1) })
        }
    }
}

/**
 * [E6案A/⛏?] 折りたたみ可能なセクション。年次マスターの長大スクロールを、不要なカードを
 * 畳んで削減する。展開状態は rememberSaveable(stateKey) で保持し、回転/再構成でも維持(P4対策)。
 * アンカージャンプ(案B)は LazyColumn 変換が要るため別途・実機目視前提。
 */
@Composable
internal fun SectionNote(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
internal fun CollapsibleSection(
    title: String,
    stateKey: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(if (expanded) "▼ 閉じる" else "▶ 開く", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
