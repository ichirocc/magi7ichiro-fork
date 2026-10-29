package com.magi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.V6FinalPort
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
internal fun MonthPickerCard(ui: UiState, onEvent: (MagiEvent) -> Unit, bare: Boolean = false) {
    if (!ui.loaded) return
    val cs = MaterialTheme.colorScheme
    val label = remember(ui.startDate) {
        runCatching { val d = LocalDate.parse(ui.startDate); "${d.year}年 ${d.monthValue}月" }.getOrNull()
    }
    MagiCardOrPlain(bare) { pad ->
        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("対象の月", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onEvent(MagiEvent.Structure.ShiftMonth(-1)) }, enabled = !ui.running,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("前の月") }
                Text(label ?: "未設定", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onEvent(MagiEvent.Structure.ShiftMonth(1)) }, enabled = !ui.running,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("次の月") }
            }
            // [実機指摘] 月末に「来月」の表を作る業務フローに合わせ、ワンタップは来月へ。
            OutlinedButton(onClick = { onEvent(MagiEvent.Structure.SetNextMonth) }, enabled = !ui.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("来月にする") }
        }
    }
}


@Composable
internal fun GuideRow(label: String, value: String, done: Boolean?, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (onClick != null) Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick) else Modifier,
    ) {
        Text(when (done) { null -> "—"; true -> "✓"; false -> "・" }, color = if (done == true) cs.primary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value + (if (onClick != null) " ›" else ""), color = if (onClick != null) cs.primary else cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}


/**
 * [3.188.0 オプション集約] 主要（毎回の判断材料になる項目）だけを常時表示: 計算の制限時間・計算方式・
 * バックグラウンドでつくる。技術系（並列ワーカー・ネイティブ加速・Kotlin照合・仕上げ最適化＝一般の運用では
 * 触らない内部チューニング）は既存の「詳細設定（上級者向け）」（`AdvancedSettingsSection`）へ移動。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsCard(ui: UiState, onEvent: (MagiEvent) -> Unit, onBgOptimize: () -> Unit = {}) {
    MagiGroup(SETTINGS_HEADING) {
        Column(Modifier.padding(16.dp)) {
            // [3.714.0] 組む時間の 1 行＋主ボタン。仕上げの秒数と注記は「詳しく」の中。見出しは入れ物の外（iOS の一覧の形、2026-10-23）。
            Spacer(Modifier.height(8.dp))
            Text(budgetShortLine(ui.budgetSec), style = MaterialTheme.typography.bodyMedium)
            var budgetMore by rememberSaveable { mutableStateOf(false) }
            MagiTextButton(onClick = { budgetMore = !budgetMore }, modifier = Modifier.heightIn(min = 48.dp)) { DisclosureText(DIAG_DETAILS_LABEL, budgetMore) }
            if (budgetMore) {
                Text(budgetStatusLine(ui.budgetSec, com.magi.app.v6.POST_RESERVE_SEC + com.magi.app.v6.CHAIN_RESERVE_SEC), style = MaterialTheme.typography.bodySmall)
                // 仕上げと玉突きは 1 本の締切を分け合う（それぞれに決まった秒数が残る約束ではない）。改善が止まると早く終わる。
                Text("仕上げと玉突きは共通の枠です。改善が止まると早く終わります。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            var picking by rememberSaveable { mutableStateOf(false) }
            val m = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            // 保存失敗の間は上の「もう一度保存する」が主操作（INV-09）。
            if (ui.saveState == SaveState.Failed) OutlinedButton(onClick = { picking = true }, enabled = !ui.running, modifier = m) { Text(BUDGET_CHANGE_BUTTON) }
            else Button(onClick = { picking = true }, enabled = !ui.running, modifier = m) { Text(BUDGET_CHANGE_BUTTON) }
            if (picking) BudgetPickerSheet(ui.budgetSec, onPick = { onEvent(MagiEvent.Settings.SetBudget(it)); picking = false }, onDismiss = { picking = false })
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(8.dp))
            // [移設] ホームの「ほかの作り方」カード撤去（ユーザー赤囲い指示）に伴い、固有機能の
            //   バックグラウンド実行だけをここ（実行条件＝予算の設定と同じ場所）へ移設。
            OutlinedButton(onClick = onBgOptimize, enabled = ui.loaded && !ui.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("閉じてもつくり続ける") }
            Text("できたら通知します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 「組む時間を選ぶ」シート（3.706.0 S1）。候補は [budgetPresets]、［この時間にする］で決める。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BudgetPickerSheet(currentSec: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var chosen by rememberSaveable { mutableIntStateOf(currentSec) }
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { MagiSheetGrabber(sheetState) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("組む時間を選ぶ", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sec in budgetPresets(currentSec)) {
                    val sel = sec == chosen
                    val label = if (sec == currentSec && sec !in BUDGET_TILE_SECS) "いまの値 ${sec}秒" else budgetText(sec)
                    Surface(onClick = { chosen = sec }, shape = MagiCorners.capsule,
                        color = if (sel) cs.primaryContainer else cs.surfaceVariant,
                        border = if (sel) BorderStroke(2.dp, cs.primary) else null,
                        modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = label + if (sel) "（選択中）" else "" }) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp)) {
                            Text(label, style = MaterialTheme.typography.titleSmall, color = if (sel) cs.onPrimaryContainer else cs.onSurface)
                        }
                    }
                }
            }
            Button(onClick = { onPick(chosen) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("この時間にする") }
        }
    }
}

private val BUDGET_TILE_SECS = setOf(10, 60, 120, 180, 240, MAX_BUDGET_SEC)

/** 設定タブの保存失敗の帯（3.706.0 S4＋F-3）。1 文と塗りの［もう一度保存する］。 */
@Composable
internal fun SettingsSaveFailedBanner(onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(HOME_SAVE_FAILED_LINE, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(SAVE_RETRY_BUTTON) }
        }
    }
}

/** 最適化方式の 6 択（詳細設定の中）。「おまかせ」なら今の時間で動く方式を添える。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlgorithmChoiceSection(ui: UiState, onEvent: (MagiEvent) -> Unit) {
    Column {
        Text("最適化方式: ${v6AlgorithmLabel(ui.v6Algorithm)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // [3.192.0 情報不足の解消] 「おまかせ」選択中は実際に動く方式が最適化の制限時間から自動決定される
        // （V6FinalPort.optimizationPlan/getAlgorithmLabelと同一ロジック）が、画面には「おまかせ」としか
        // 出ず、今の時間設定で何が動くか見えなかった。現在の budgetSec での解決結果を併記する（表示のみ）。
        if (ui.v6Algorithm == V6Algorithm.AUTO) {
            val resolved = V6FinalPort.getAlgorithmLabel(ui.budgetSec)
            Text("→ 今の設定(${ui.budgetSec}秒)では ${resolved.icon} ${resolved.name}（${resolved.desc}）が動きます",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            V6Algorithm.values().forEach { alg ->
                val selected = ui.v6Algorithm == alg
                if (selected) {
                    Button(onClick = { onEvent(MagiEvent.Settings.SetAlgorithm(alg)) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(v6AlgorithmLabel(alg)) }
                } else {
                    OutlinedButton(onClick = { onEvent(MagiEvent.Settings.SetAlgorithm(alg)) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(v6AlgorithmLabel(alg)) }
                }
            }
        }
    }
}

/** [3.188.0 オプション集約] 技術系チューニング（並列ワーカー・ネイティブ加速・Kotlin照合・仕上げ最適化）。
 *  `SettingsCard` から「詳細設定（上級者向け）」へ移動した項目。一般の運用では既定値のままで問題ない。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptimizationTuningSection(ui: UiState, onEvent: (MagiEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("同時に計算する数: ${ui.workers}", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onEvent(MagiEvent.Settings.SetWorkers((ui.workers - 1).coerceAtLeast(1))) },
                enabled = !ui.running && ui.workers > 1, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "同時に計算する数を減らす" }) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Text("${ui.workers}", style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
            Button(onClick = { onEvent(MagiEvent.Settings.SetWorkers((ui.workers + 1).coerceAtMost(16))) },
                enabled = !ui.running && ui.workers < 16, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "同時に計算する数を増やす" }) { Text("＋", style = MaterialTheme.typography.titleLarge) }
        }
        // [仮説数上限撤廃・ユーザー指示] 旧: 仮説数は5固定・超過ワーカーは仮説内並列度へ配分。
        //   現在は設定値がそのまま並列に探索する仮説（案）の数になる（下限2、上限=設定値自体）。
        //   表示はエンジン本体と同じ HypothesisPlanning.hypothesisCount から導出し独立再計算による乖離を防ぐ。
        run {
            val cores = Runtime.getRuntime().availableProcessors()
            val hyp = com.magi.app.v6.HypothesisPlanning.hypothesisCount(ui.workers)
            // [3.372.0/レビュー修正] コア数を超える設定では、runMultiWorker 経路（破壊再構築/違反集中/
            //   違反集中＋）は仮説を増やさず spawn 数をコア数まで畳み、余剰は各仮説の内部並列へ回す
            //   （3.371.0）。旧表示は hyp をそのまま「仮説（案）の数」と言い切っており実挙動と食い違った。
            val spawn = com.magi.app.v6.HypothesisPlanning.hypothesisSpawnPlan(ui.workers, hyp).first
            val overNote = if (ui.workers > cores)
                "端末のコア数(${cores})を超えるとコアを奪い合い、電池と発熱が増えます。" +
                    (if (spawn < hyp) "なお「組み替え」「違反集中」「違反集中＋」では、コア数を超える分は" +
                        "案を増やさず各案の内部並列へ回します（同時に走る案は${spawn}）。" else "")
            else ""
            Text(
                "※「高速」（30秒以下のおまかせを含む）では、この値が同時に走る本数です。" +
                    "それ以外の方式では設定値がそのまま並べて試す仮説（案）の数（${hyp}）になります。$overNote",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // [3.719.0 読み上げ] スイッチの行は行ごと toggleable（role=Switch）＋ mergeDescendants＝TalkBack が「名前・説明・ON/OFF」を 1 つに読む。
        //   Switch 自身は onCheckedChange=null（行が受ける）。以下のスイッチの行はすべて同じ形。
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = ui.nativeAccel, enabled = !ui.running, role = Role.Switch, onValueChange = { onEvent(MagiEvent.Settings.SetNativeAccel(it)) }).semantics(mergeDescendants = true) {}) {
            Column(Modifier.weight(1f)) {
                Text("ネイティブ加速（C++）", style = MaterialTheme.typography.bodyMedium)
                Text("内側の計算を高速版で行います。結果は常に照合し、食い違えば従来方式に戻ります。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.nativeAccel, onCheckedChange = null, enabled = !ui.running)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = ui.nativeParity, enabled = !ui.running && ui.nativeAccel, role = Role.Switch, onValueChange = { onEvent(MagiEvent.Settings.SetNativeParity(it)) }).semantics(mergeDescendants = true) {}) {
            Column(Modifier.weight(1f)) {
                Text("Kotlin照合", style = MaterialTheme.typography.bodyMedium, color = if (ui.nativeParity) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Text(
                    if (ui.nativeParity)
                        "高速版の結果を毎回Kotlin実装で検証。不一致なら自動で従来方式に戻ります（推奨・既定）。"
                    else
                        "⚠ 照合せず高速版の結果を使います（検証用）。誤った勤務表になることがあります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.nativeParity) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Switch(checked = ui.nativeParity, onCheckedChange = null, enabled = !ui.running && ui.nativeAccel)
        }
        // [3.409.21] 「行き詰まりからの立て直し方」「PORTFOLIOロール内並列SA」の2トグルは、単体 A/B
        //   （各15ペア・基準は測定前に固定）で中立と確定したため機構ごと削除した（PolishGate 冒頭の記録参照）。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = ui.softPolish, enabled = !ui.running, role = Role.Switch, onValueChange = { onEvent(MagiEvent.Settings.SetSoftPolish(it)) }).semantics(mergeDescendants = true) {}) {
            Switch(checked = ui.softPolish, onCheckedChange = null, enabled = !ui.running)
            Spacer(Modifier.width(8.dp))
            Text("仕上げ最適化", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = ui.agentAuto, enabled = !ui.running, role = Role.Switch, onValueChange = { onEvent(MagiEvent.Settings.SetAgentAuto(it)) }).semantics(mergeDescendants = true) {}) {
            Switch(checked = ui.agentAuto, onCheckedChange = null, enabled = !ui.running)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(AGENT_TOGGLE_LABEL, style = MaterialTheme.typography.bodyMedium)
                Text(AGENT_TOGGLE_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Text("探す強さ", style = MaterialTheme.typography.titleSmall)
            Text("じっくり: 時間いっぱい粘り、細かい違反をもう一歩減らします。結果が良くなるとは限りません。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchStrength.values().forEach { s ->
                    val label = searchStrengthLabel(s)
                    if (ui.searchStrength == s) {
                        Button(onClick = { onEvent(MagiEvent.Settings.SetSearchStrength(s)) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onEvent(MagiEvent.Settings.SetSearchStrength(s)) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = ui.aptFairToleranceUnguarded, enabled = !ui.running && ui.searchStrength == SearchStrength.THOROUGH, role = Role.Switch, onValueChange = { onEvent(MagiEvent.Settings.SetAptFairToleranceUnguarded(it)) }).semantics(mergeDescendants = true) {}) {
                Column(Modifier.weight(1f)) {
                    Text("容認 6% で重い違反の増加も許す", style = MaterialTheme.typography.bodyMedium)
                    Text(APT_FAIR_UNGUARDED_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.aptFairToleranceUnguarded, onCheckedChange = null, enabled = !ui.running && ui.searchStrength == SearchStrength.THOROUGH)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Text("玉突きで直す（月全体）", style = MaterialTheme.typography.titleSmall)
            Text(EJECTION_CHAIN_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 4 択で語も長く、幅 390dp では 1 行に収まらないので折り返す（行の間も 8dp 空ける＝押し間違いを防ぐ）。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EjectionChainMode.values().forEach { m ->
                    val label = ejectionChainLabel(m)
                    if (ui.ejectionChain == m) {
                        Button(onClick = { onEvent(MagiEvent.Settings.SetEjectionChain(m)) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onEvent(MagiEvent.Settings.SetEjectionChain(m)) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                    }
                }
            }
            // 次はパイプライン（「見込みのある所だけ」）にだけ効く。
            val pipelineOn = !ui.running && ui.ejectionChain == EjectionChainMode.PIPELINE
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = ui.ejectionPipelineHardLeg, enabled = pipelineOn, role = Role.Switch, onValueChange = { onEvent(MagiEvent.Settings.SetEjectionPipelineHardLeg(it)) }).semantics(mergeDescendants = true) {}) {
                Column(Modifier.weight(1f)) {
                    Text("必須の違反からも始める", style = MaterialTheme.typography.bodyMedium)
                    Text(EJECTION_HARD_LEG_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.ejectionPipelineHardLeg, onCheckedChange = null, enabled = pipelineOn)
            }
            Text("2手目以降を探す範囲", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EJECTION_CHAIN_SCOPES_SHOWN.forEach { sc ->
                    val on = !ui.running && ui.ejectionChain != EjectionChainMode.OFF
                    val label = ejectionChainScopeLabel(sc)
                    if (ui.ejectionChainScope == sc) {
                        Button(onClick = { onEvent(MagiEvent.Settings.SetEjectionChainScope(sc)) }, enabled = on, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onEvent(MagiEvent.Settings.SetEjectionChainScope(sc)) }, enabled = on, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                    }
                }
            }
            Text("1回あたりの上限時間（残り時間の半分を超えません）", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EJECTION_CHAIN_SECONDS.forEach { sec ->
                    val on = !ui.running && ui.ejectionChain != EjectionChainMode.OFF
                    if (ui.ejectionChainSeconds == sec) {
                        Button(onClick = { onEvent(MagiEvent.Settings.SetEjectionChainSeconds(sec)) }, enabled = on, modifier = Modifier.heightIn(min = 48.dp)) { Text("${sec}秒") }
                    } else {
                        OutlinedButton(onClick = { onEvent(MagiEvent.Settings.SetEjectionChainSeconds(sec)) }, enabled = on, modifier = Modifier.heightIn(min = 48.dp)) { Text("${sec}秒") }
                    }
                }
            }
        }
    }
}

internal val EJECTION_CHAIN_SCOPES_SHOWN = EjectionChainScope.entries

internal fun ejectionChainScopeLabel(s: EjectionChainScope): String = when (s) {
    EjectionChainScope.MONTH -> "月全体"
    EjectionChainScope.BY_KIND -> "必須は月全体・他は前後7日"
}

internal val EJECTION_CHAIN_SECONDS = listOf(3, 6, 10)

internal const val EJECTION_HARD_LEG_NOTE = "人員不足など必須の違反からも玉突きを試します（「見込みのある所だけ」のとき）"
internal const val EJECTION_CHAIN_NOTE ="「見込みのある所だけ」は浅く試して直せた所だけを深く探します。ほかの 2 つは広く探すので時間がかかります"
/** 「じっくり」の公平化/適切回数の容認（他ソフト +6%）は、期間の制約・下限/上限・人員過剰などが 1 件でも増える手を採らない。その歯止めを外す測定スイッチ。 */
internal const val APT_FAIR_UNGUARDED_NOTE = "試験中。じっくりのときだけ、期間の制約などが1件増える手も採ります（測定では効果なし・既定OFF）"

internal fun ejectionChainLabel(m: EjectionChainMode): String = when (m) {
    EjectionChainMode.OFF -> "しない"
    EjectionChainMode.PIPELINE -> "見込みのある所だけ"
    EjectionChainMode.C1 -> "期間の制約から"
    EjectionChainMode.ALL -> "すべての違反から"
}

internal fun searchStrengthLabel(s: SearchStrength): String = when (s) {
    SearchStrength.NORMAL -> "ふつう"
    SearchStrength.THOROUGH -> "じっくり"
}

/** [見やすさ] 計算方式(V6Algorithm)の一般向け日本語ラベル。技術名(AUTO/RSI 等)は操作者に不明なため。 */
internal fun v6AlgorithmLabel(alg: V6Algorithm): String = when (alg) {
    V6Algorithm.AUTO -> "おまかせ"
    V6Algorithm.V5 -> "高速"
    // [3.551.0/自動化方針 #13(i)] 旧「破壊再構築」。内部の比喩（destroy-repair）を利用者に見せない。
    V6Algorithm.ALNS -> "組み替え"
    V6Algorithm.RSI -> "違反集中"
    V6Algorithm.RSI_PLUS -> "違反集中＋"
    // [3.191.0 用語重複解消] 「並列ワーカー」（同時実行数=workers）と「並列(複数案)」が同じ「並列」を
    //   冠していて意味が重複して見えた。本方式はworkers本のうち各hypothesisへ異なるアルゴリズムを
    //   割り当てる（portfolioAlgoFor）＝並列度そのものではなく「方式を組み合わせる」戦略のため改称。
    V6Algorithm.PORTFOLIO -> "方式ミックス"
}


// [3.112.0 撤去] ActionCard（ほかの作り方: 速くつくる/かんたんに/閉じても大丈夫）はユーザー赤囲い指示で撤去。
//   速く/かんたんは主導線（思考誘導カード onMake/onDraft）と重複、バックグラウンド実行は SettingsCard へ移設。

/**
 * [3.707.0 E-2] 月次条件の見出しカード（旧「今月の作成条件」チェックリスト＋区分の 1 行を統合）。題・副題・点 3 つ・主ボタン 1 つ・副の文字ボタン。
 * 中身は [editHeaderPlan]（純関数）。点の希望・必要人数は同じ画面のカードへ寄せるだけ（選んだ日は消さない＝U02）。
 * 例外件数は D6 に従い、明示的な例外リストを持つ「日別必要人数の例外」のみを数える。
 */
@Composable
internal fun EditHeaderCard(
    ui: UiState, plan: EditHeaderPlan, wishNoInput: Int,
    onOpenWish: () -> Unit, onOpenNeed: () -> Unit, onWishConflicts: () -> Unit, inner: (@Composable () -> Unit)? = null) {
    if (!ui.loaded) return
    val cs = MaterialTheme.colorScheme
    // [3.483.0 E-3] 入力診断の中身をこの場で開く（旧「（ホームに詳細）」＝ホームへ往復させていた）。
    var issuesOpen by rememberSaveable { mutableStateOf(false) }
    var issuesAll by rememberSaveable { mutableStateOf(false) }
    val fixList = com.magi.app.v6.fixItems(ui.settingIssues)
    val issues = fixList.size
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            inner?.invoke()
            Text(plan.title, style = MaterialTheme.typography.titleMedium)
            Text(plan.subtitle, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            val (wishLabel, needLabel, issueLabel) = plan.points.map { it.substringBefore(' ') to it.substringAfter(' ') }.let { Triple(it[0], it[1], it[2]) }
            // [3.643.0] 登録の有無だけを数える（✓ で「集め終わった」と読まれないよう判定は出さない）。
            ChecklistRow(wishLabel.first, wishLabel.second, ok = null, onClick = onOpenWish)
            if (wishNoInput > 0) Text("未入力の ${wishNoInput}人が「希望なし」か「まだ聞いていない」かは、ここでは分かりません。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp))
            ChecklistRow(needLabel.first, needLabel.second, ok = !needLabel.second.startsWith("標準が未設定"), onClick = onOpenNeed)
            ChecklistRow(issueLabel.first, issueLabel.second + (if (issues == 0) "" else if (issuesOpen) " ▾" else " ▸"), ok = issues == 0,
                onClick = if (issues > 0) ({ issuesOpen = !issuesOpen }) else null)
            if (issuesOpen && issues > 0) {
                val shown = if (issuesAll) fixList else fixList.take(SETTING_ISSUE_PREVIEW)
                shown.forEach { iss ->
                    Text("・${iss.where}：${iss.problem}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp))
                }
                if (shown.size < issues) SettingIssuesShowAll(issues - shown.size) { issuesAll = true }
            }
            // Q10: 塗りは EDIT_NEED のときだけ。RERUN（下部の再作成）・PANEL（入力パネル）の間は外枠＝画面の塗りは 1 つ（INV-09）。
            if (plan.primary == EditHeaderPrimary.EDIT_NEED) {
                Button(onClick = onOpenNeed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(EDIT_NEED_BUTTON) }
            } else if (plan.primary != null) {
                OutlinedButton(onClick = onOpenNeed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(EDIT_NEED_BUTTON) }
            }
            if (plan.secondary == EditHeaderSecondary.WISH_CONFLICTS) {
                MagiTextButton(onClick = onWishConflicts, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(WISH_CONFLICTS_BUTTON) }
            }
        }
    }
}

@Composable
private fun ChecklistRow(label: String, value: String, ok: Boolean?, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (onClick != null) Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick) else Modifier,
    ) {
        // ok=null＝判定しない行（数だけ見せる）
        Text(when (ok) { null -> "—"; true -> "✓"; false -> "！" }, color = when (ok) { null -> cs.onSurfaceVariant; true -> cs.tertiary; false -> cs.error }, fontWeight = FontWeight.Bold)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value + (if (onClick != null) " ›" else ""), color = if (onClick != null) cs.primary else cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** [3.643.0] 対象の月を移す前の確認。引き継ぐもの（日番号で残る）と消えるもの（日付で持つ拡張希望の期間外ぶん等）を見せ、希望を残すか消すかを選ぶ。 */
@Composable
internal fun MonthMoveConfirmDialog(plan: MonthMovePlan, onEvent: (MagiEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { onEvent(MagiEvent.Structure.CancelMonthMove) },
        title = { Text("${plan.year}年 ${plan.month}月 に移りますか？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                monthMoveLines(plan).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                monthMoveKeepNote(plan)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                Text("前の月の希望を新しい月に持ち越さないときは「希望を消して移る」を選びます。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogOptionButton("希望を残して移る", onClick = { onEvent(MagiEvent.Structure.ConfirmMonthMove(clearWishes = false)) }, recommended = true)
                DialogOptionButton("希望を消して移る", onClick = { onEvent(MagiEvent.Structure.ConfirmMonthMove(clearWishes = true)) }, danger = true)
                // 「やめる」も同じ列に（dismiss の枠に分けると、縦に積んだ列と横に並べられて重なる）。
                DialogDismissButton(onClick = { onEvent(MagiEvent.Structure.CancelMonthMove) }, text = "移らない")
            }
        },
    )
}

/**
 * [年度始めモード/実働チェック] 「15人いるから大丈夫」ではなく、シフトごとの実働体制で見る（D5 残スコープ）。
 * Q=担当できる人数(canDo)・D=月間需要人日(Σ日次必要数)・日最大=1日に同時に必要な最大人数。
 * 欠勤余裕 = Q − 日最大（1人欠けてもその日の必要人数を揃えられるか）。read-only・スコアリング不変。
 * データは Problem 由来（`ConditionsView` の担当可否 / 必要人数レンジ＝日別例外込み）＝チェッカーと同じ実効値。
 */
@Composable
internal fun StaffingRealityCard(ui: UiState, cv: ConditionsView, bare: Boolean = false) {
    if (!ui.loaded) return
    val cs = MaterialTheme.colorScheme
    val days = ui.days
    val staffN = ui.staffNames.size
    val canDoCount = IntArray(ui.shiftSymbols.size)
    for (i in 0 until staffN) cv.allowedShiftsFor(i).forEach { k -> if (k in canDoCount.indices) canDoCount[k]++ }
    data class RowV(val sym: String, val q: Int, val d: Int, val maxNeed: Int)
    val rows = ui.shiftSymbols.indices.mapNotNull { k ->
        var d = 0; var mx = 0
        for (j in 0 until days) { val lo = cv.needCellLimits(k, j)?.first ?: 0; d += lo; if (lo > mx) mx = lo }
        if (d == 0) null else RowV(ui.shiftSymbols[k], canDoCount[k], d, mx)
    }
    if (rows.isEmpty()) return
    MagiCardOrPlain(bare) { pad ->
        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("この体制で回るか", style = MaterialTheme.typography.titleMedium)
            Text("足りないシフトは①で担当を増やすか、必要人数を見直します。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            rows.forEach { r ->
                val slack = r.q - r.maxNeed
                val (mark, col) = when {
                    slack < 0 -> "⚠" to cs.error
                    // [UD監査] 橙(2.7:1)は白地の文字色として不足→警告トークンの濃色(fg)へ。
                    slack == 0 -> "！" to magiWarnColors().second
                    else -> "✓" to cs.tertiary
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(mark, color = col, fontWeight = FontWeight.Bold)
                    Text(r.sym, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp), maxLines = 1)
                    Text(
                        staffingRowText(r.q, r.maxNeed),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * [見直し候補] 月次の修正（セル編集シート）から送られた「基本ルールの見直し候補」メモの一覧。
 * 年間マスターを開いたとき最初に目に入る位置に置き、「毎月同じ手修正→制度の直し」への橋にする。
 * セッション内メモ（state 非保存）＝アプリ終了で消える旨を明示。read-only・スコアリング不変。
 */
@Composable
internal fun ReviewMemoCard(ui: UiState, onEvent: (MagiEvent) -> Unit) {
    if (ui.reviewMemos.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("見直し候補（${ui.reviewMemos.size}件）", style = MaterialTheme.typography.titleMedium)
            Text("修正中に印を付けたルール見直しのメモです（アプリを閉じると消えます）。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            ui.reviewMemos.forEachIndexed { idx, memo ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(memo, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    DeleteRowButton(onClick = { onEvent(MagiEvent.Session.RemoveReviewMemo(idx)) }, text = "済みにする")
                }
            }
        }
    }
}


@Composable
internal fun AdvancedSettingsSection(
    ui: UiState,
    onEvent: (MagiEvent) -> Unit,
    onExportLog: () -> Unit,
    onExportJson: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }   // 既定は閉
    MagiGroup(null) {
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
                    // [3.188.0 オプション集約] 並列/ネイティブ/仕上げの技術系チューニングを SettingsCard から
                    //   ここへ移設したため、説明文を「ログのみ」から実態に同期。
                    Text("計算方式・並列数・ネイティブ加速・仕上げの調整とログの確認。一般の運用では触りません。",
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
                    //   ここから撤去し、分析タブ(プロ)に一本化。
                    // [IA重複解消 3.132系] ColorSettingsView（違反種別の色）はシフトの表示色の直後
                    //   （設定タブ上部）へ移動＝色設定を1か所に。ここはログのみ。
                    // [3.188.0 オプション集約] 並列ワーカー/ネイティブ加速/Kotlin照合/仕上げ最適化は
                    //   一般の運用では触らない内部チューニングのため SettingsCard からここへ移動。
                    AlgorithmChoiceSection(ui, onEvent)
                    OptimizationTuningSection(ui, onEvent)
                    LogsCard(ui = ui, onExportLog = onExportLog, onExportJson = onExportJson)
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
            OutlinedButton(onClick = onExportLog, enabled = hasAny, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("テキストで書き出す") }
            OutlinedButton(onClick = onExportJson, enabled = hasAny, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("JSONで書き出す") }
            // 操作ログ（監査・新しい順）
            Text("操作ログ（新しい順 ${ui.opLog.size}件）", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            if (ui.opLog.isEmpty()) {
                Text("操作履歴なし", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        .background(cs.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ui.opLog.take(60).forEach { line ->
                            val warn = line.contains("[W]") || line.contains("[E]")
                            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                color = if (warn) cs.error else cs.onSurface)
                        }
                    }
                }
            }
            // 診断ログ（エンジン）
            if (ui.logs.isNotEmpty()) {
                Text("診断ログ", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                ui.logs.take(6).forEach {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DataActionsCard(
    ui: UiState,
    onOpenJson: () -> Unit, onSample: () -> Unit, onSaveJson: () -> Unit,
    onOpenCsv: () -> Unit, onSaveCsv: () -> Unit, onCheck: () -> Unit,
    onSaveStaffCsv: () -> Unit = {}, onSaveWishesCsv: () -> Unit = {}, onSaveConstraintsCsv: () -> Unit = {},
    onSaveShiftColorsCsv: () -> Unit = {},
    onRestorePrev: () -> Unit = {},
    onRetrySave: () -> Unit = {},
) {
    val ok = ui.loaded && !ui.running
    // [バージョン表示] インストール済みAPKの versionName/versionCode を実行時に取得して表示（例: CSVのBOM対応は 2.90.0 以降）。
    val ctx = LocalContext.current
    val versionLabel = remember(ctx) {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("不明")
    }
    // [3.529.0] 自動保存の状態は変化がある間だけ。失敗は時間のカードの上（SettingsSaveFailedBanner）に出す＝ここでは重ねない（3.706.0 S4）。
    val saveNote = when (ui.saveState) { SaveState.Dirty -> "未保存の変更があります（まもなく自動保存します）"; SaveState.Saving -> "保存中…"; else -> null }
    // [判断設計監査 #3] 開く=現データの置換。置換直前の1世代退避へ戻す導線（押すたび入れ替わる）。
    val restoreNote = if (ui.prevBackupAvailable) "「開く前のデータに戻す」はもう一度押すと、今のデータと入れ替わります。" else null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MagiGroup("データ", footer = listOfNotNull(restoreNote, saveNote).joinToString("\n").ifEmpty { null }) {
            MagiGroupRow("データを開く", enabled = !ui.running, onClick = onOpenJson)
            MagiGroupRow("サンプルで試す", enabled = !ui.running, onClick = onSample)
            if (ui.prevBackupAvailable) MagiGroupRow("開く前のデータに戻す", enabled = !ui.running, onClick = onRestorePrev)
            MagiGroupRow("データを保存する", enabled = ok, onClick = onSaveJson)
            MagiGroupRow("いま診断し直す", enabled = ok, divider = false, onClick = onCheck)
        }
        // 種類別の書き出し 4 つは畳む（データの常時の行を減らす）。
        var kindsOpen by remember { mutableStateOf(false) }
        MagiGroup("CSV", footer = "バージョン: $versionLabel") {
            MagiGroupRow("CSVを取り込む", enabled = ok, onClick = onOpenCsv)
            MagiGroupRow("CSVを書き出す", enabled = ok, onClick = onSaveCsv)
            MagiGroupRow("種類別のCSVを書き出す", trailing = if (kindsOpen) "▾" else "▸", divider = kindsOpen, open = kindsOpen, onClick = { kindsOpen = !kindsOpen })
            if (kindsOpen) {
                MagiGroupRow("　職員一覧", enabled = ok, onClick = onSaveStaffCsv)
                MagiGroupRow("　希望シフト", enabled = ok, onClick = onSaveWishesCsv)
                MagiGroupRow("　各制約", enabled = ok, onClick = onSaveConstraintsCsv)
                MagiGroupRow("　シフト色", enabled = ok, divider = false, onClick = onSaveShiftColorsCsv)
            }
        }
    }
}


@Composable
internal fun AppearanceCard(
    oneHand: Boolean = false, onOneHand: (Boolean) -> Unit = {},
    proMode: Boolean = false, onProMode: (Boolean) -> Unit = {},
    plainCellBorder: Boolean = false, onPlainCellBorder: (Boolean) -> Unit = {},
    leftHand: Boolean = false, onLeftHand: (Boolean) -> Unit = {}, inner: (@Composable () -> Unit)? = null) {
    // [D8/UD固定] 配色セレクタ（自動/明/暗/UD）はユーザー判断で撤去。テーマは UD（高コントラスト）固定。
    MagiGroup(SETTINGS_DISPLAY_HEADING) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("配色はユニバーサルデザイン（高コントラスト）固定です。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = oneHand, role = Role.Switch, onValueChange = onOneHand).semantics(mergeDescendants = true) {}) {
                Switch(checked = oneHand, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text("片手モード", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            // [通常セルの枠線] 違反の無いセルに常時1dp輪郭を付けるか（既定=付けない）。付けると同色が
            //   並ぶセル同士の境目が分かりやすくなる一方、格子全体が線で埋まり違反枠が目立ちにくくなる。
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = plainCellBorder, role = Role.Switch, onValueChange = onPlainCellBorder).semantics(mergeDescendants = true) {}) {
                Switch(checked = plainCellBorder, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text("勤務表の通常セルに枠線を表示", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Text("セル編集シートの利き手", style = MaterialTheme.typography.titleSmall)
            MagiSegmentedControl(options = listOf("右手", "左手"), selected = if (leftHand) 1 else 0, onSelect = { onLeftHand(it == 1) })
            // [プロ編集] 表示モード。プロ＝数値診断（生指標）を前面に。今後さらに高密度編集を拡張予定。
            Text("表示モード", style = MaterialTheme.typography.titleSmall)
            MagiSegmentedControl(options = listOf("かんたん", "プロ"), selected = if (proMode) 1 else 0, onSelect = { onProMode(it == 1) })
            // [3.483.0 C-3] 何が変わるかを1行で（旧: 無説明のトグル）。
            Text("プロにすると、分析タブの詳しい数値のカードと、勤務表の「まとめて割当」を表示します。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inner?.invoke()
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

@OptIn(ExperimentalFoundationApi::class)   // bringIntoView は実験的 API（見出しを画面内へ寄せる, 3.642.0）
@Composable
internal fun CollapsibleSection(
    title: String,
    stateKey: String,
    initiallyExpanded: Boolean = false,
    // [UX監査#1] 診断カードからの誘導が①以外の節に着地しない問題への対処。呼び出し側が対象の
    //   stateKeyをここへ渡すと、その節だけ強制的に開く（一致しなければ何もしない＝挙動不変）。
    forceExpandKey: String? = null,
    onForceExpandConsumed: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    val headingRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(forceExpandKey) {
        if (forceExpandKey == stateKey) {
            expanded = true
            // [UX監査 中3] 強制展開は見出しを画面の外に残しうる。展開後の配置を1フレーム待ってから、見出しを画面内へ寄せる。
            //   消費（キーの変更）は寄せ終えてから＝先に消すと再構成で effect が取り消され、寄せずに終わる（3.646.0 L05）。
            withFrameNanos { }
            headingRequester.bringIntoView()
            onForceExpandConsumed()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        // 節の見出しは一覧の 1 行と同じ形（地・角丸・右端の ▸／▾、iOS の一覧の形、2026-10-23）。
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).bringIntoViewRequester(headingRequester).clickable { expanded = !expanded }
                .semantics { stateDescription = disclosureState(expanded) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics { })
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
