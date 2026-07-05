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

/**
 * CSVのバイト列を文字列へ復号する。妥当な UTF-8 ならそれを採用し、そうでなければ日本の Excel CSV で
 * 一般的な CP932(Shift-JIS) とみなす。先頭の BOM は除去する。これにより Shift-JIS の勤務表CSVが
 * 文字化けせず取り込める（UTF-8 として bytes を読むと壊れていた）。
 */

@Composable
internal fun GuidedFixDialog(ui: UiState, vm: MagiViewModel, onDismiss: () -> Unit, onRerun: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shortfalls = ui.coverageDiag?.shortfalls ?: emptyList()
    val target = shortfalls.firstOrNull { it.verdict == CoverageVerdict.FIXABLE && it.miss > 0 }
    val infeasible = shortfalls.filter { it.verdict == CoverageVerdict.INFEASIBLE }
    val allDone = target == null && infeasible.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allDone) "直し終わりました！" else "なおすのを手伝います") },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    target != null -> {
                        Text("${target.dayLabel} の「${target.shiftSymbol}」が ${target.miss}人 足りません。",
                            fontWeight = FontWeight.Bold)
                        Text("この日に動かせる人がいます。だれかを「${target.shiftSymbol}」に入れますか？",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        val cands = remember(target.dayIndex, target.shiftIndex, ui.coverageDiag) {
                            vm.shortageFixCandidates(target.dayIndex, target.shiftIndex)
                        }
                        if (cands.isEmpty()) {
                            Text("いま動かせる人がいません。別の日を見直すか、データを確認してください。", color = cs.error)
                        } else {
                            cands.take(8).forEach { c ->
                                Button(
                                    onClick = { vm.setCell(c.staffIndex, target.dayIndex, target.shiftIndex) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp),
                                ) {
                                    val tail = if (c.fromRest) "（休み）" else ""
                                    // 長い氏名でも切れないよう2行まで折り返し（文字欠け防止）。
                                    Text("${c.name}$tail を「${target.shiftSymbol}」に入れる",
                                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text("入れたら「元に戻す」でいつでも取り消せます。", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                    }
                    infeasible.isNotEmpty() -> {
                        Text("これ以上は自動で埋められません。", fontWeight = FontWeight.Bold)
                        infeasible.take(4).forEach {
                            Text("・${it.dayLabel}「${it.shiftSymbol}」：${it.reason}",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Text("人を増やすか、担当できるシフトや希望を見直すと直せます。", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    else -> {
                        Text("人手が足りない日はなくなりました。仕上げにもう一度つくると全体が整います。")
                    }
                }
            }
        },
        confirmButton = {
            if (allDone) DialogConfirmButton("もう一度つくる", onClick = onRerun)
            else DialogDismissButton(onClick = onDismiss, text = "閉じる")
        },
        // [dogfooding] 修正中は「閉じる」だけ（やめる＝同じ動作の重複ボタンを排除）。完了時のみ第2ボタンを出す。
        dismissButton = { if (allDone) DialogDismissButton(onClick = onDismiss, text = "閉じる") },
    )
}

/** [operator_ux §3] 思考誘導カードの1状態分のプラン（文言・色・大ボタン・補助）。 */

internal class OpNextPlan(
    val container: Color, val fg: Color, val headline: String,
    val bigLabel: String, val bigAction: () -> Unit, val bigEnabled: Boolean,
    val helperLabel: String?, val helperAction: () -> Unit,
)

/**
 * [operator_ux §3] 思考誘導ホームの「次にやること」カード。
 * IT中学生レベルのオペレーター向け：専門用語ゼロ・大ボタン1つ・色で意味（緑=できた/黄=もう少し/赤=気をつけて）。
 * いまの状態（未作成／組立中／配れる／もう少し／埋められない）で文言と主ボタンが自動で変わる。
 */

@Composable
internal fun OperatorNextActionCard(
    ui: UiState,
    onMake: () -> Unit,      // 勤務表をつくる（最適化）
    onDraft: () -> Unit,     // 下書きをつくる（簡易作成）
    onStop: () -> Unit,      // やめる（停止）
    onExport: () -> Unit,    // 印刷・書き出し / そのまま配る（CSV書き出し）
    onSchedule: () -> Unit,  // 中身を見る（勤務表へ）
    onFix: () -> Unit,       // なおすのを手伝って（勤務表で手直し）
    onSetup: () -> Unit,     // データを見直す（編集へ）
) {
    val cs = MaterialTheme.colorScheme
    val infeasible = ui.coverageDiag?.allInfeasible == true
    val shortDays = ui.coverageDiag?.shortfalls?.map { it.dayIndex }?.distinct()?.size ?: 0
    val worstDay = ui.coverageDiag?.shortfalls?.firstOrNull()?.dayLabel

    // [M3] 成功=tertiary / 注意=error / 主操作=primary はテーマロール。警告のみ独自トークンに集約。
    val (amber, onAmber) = magiWarnColors()

    val plan = when {
        ui.running -> {
            val remainMin = ((ui.budgetSec * 1000L - ui.elapsedMs).coerceAtLeast(0L) / 60_000L) + 1
            // [校正] 「やめる」は下部コマンドバーに常設済み。カード側の補助ボタンは重複のため出さない。
            OpNextPlan(cs.primaryContainer, cs.onPrimaryContainer,
                "いま、コンピューターが組んでいます。\nあと約 ${remainMin} 分。閉じても大丈夫です。",
                "", {}, false, null, onStop)
        }
        !ui.hasResult -> OpNextPlan(cs.primaryContainer, cs.onPrimaryContainer,
            "② ボタンひとつで、勤務表を作ります。",
            "勤務表をつくる", onMake, true, "下書きをつくる", onDraft)
        ui.bestHard == 0L -> OpNextPlan(cs.tertiaryContainer, cs.onTertiaryContainer,
            "③ できました！ そのまま配れます。",
            "印刷・書き出し", onExport, true, "中身を見る", onSchedule)
        infeasible -> OpNextPlan(cs.errorContainer, cs.onErrorContainer,
            "このデータでは、ここは埋められません。" + (worstDay?.let { "（例：$it）" } ?: ""),
            "データを見直す", onSetup, true, "未充足のまま書き出す", onExport)
        else -> OpNextPlan(amber, onAmber,
            "もう少しです。" + (worstDay?.let { "$it が人手不足です。" } ?: "人手が足りない日があります。"),
            "なおすのを手伝って", onFix, true, "もう一度つくる", onMake)
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = plan.container)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // [HUD段2] フェーズ名バッジ（探索/完成/狩猟）。既存の状態分岐に名前を与えるだけ。
            //   未最適化→探索 / HARD=0→完成 / HARD>0(infeasible含む)→狩猟。実行中は非表示（カードが別表示）。
            if (!ui.running) {
                val (phName, phColor) = when {
                    !ui.hasResult -> "探索" to MagiAccent.blue
                    ui.bestHard == 0L -> "完成" to MagiAccent.green
                    else -> "狩猟" to MagiAccent.orange
                }
                Box(Modifier.background(phColor, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(phName, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Text(plan.headline, style = MaterialTheme.typography.titleLarge, color = plan.fg, fontWeight = FontWeight.Bold)
            // 数字は必ず言葉つきで意味を添える（operator_ux §6）。
            Text(
                "人手が足りない日：${shortDays}日 ・ できあがり度（全体の完成度）：${ui.satisfaction}%",
                style = MaterialTheme.typography.bodyMedium, color = plan.fg,
            )
            if (ui.running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = plan.fg)
                    // [進捗の見える化] 「組み立て中…」だけでなく 改善率/残り時間/探索数 を出す。
                    Text(progressSummary(ui), color = plan.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (plan.bigEnabled) {
                Button(onClick = plan.bigAction, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(plan.bigLabel, style = MaterialTheme.typography.titleMedium)
                }
            }
            plan.helperLabel?.let { hl ->
                // [校正] 補助操作もテキストリンクではなく外枠ボタンに（カード地色でも見えるよう枠色=前景色）。
                OutlinedButton(
                    onClick = plan.helperAction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = plan.fg),
                    border = BorderStroke(1.dp, plan.fg.copy(alpha = 0.5f)),
                ) { Text(hl) }
            }
        }
    }
}

/** [対象月の選択] 勤務表を作る月を前月/翌月/今月で選ぶ。変更でその月の日数に合わせて表を作り直す。 */

@Composable
internal fun CopilotCard(ui: UiState, onGoEdit: () -> Unit, onSoftPolish: () -> Unit = {}) {
    // [冗長性削減] できあがり度・進捗は OperatorNextActionCard が表示するため、ここは助言/警告だけに専念。
    val cs = MaterialTheme.colorScheme
    val show = ui.impossibleWishCount > 0 || ui.copilotHint != null || (ui.polishExhausted && !ui.running)
    if (!show) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 担当外など実現不能な希望の警告（Web版の担当外希望警告に相当）
            if (ui.impossibleWishCount > 0) {
                Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⚠ 実現できない希望が ${ui.impossibleWishCount} 件（担当外シフトなど）。配布前に見直しを。",
                            color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onGoEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("希望シフトを編集") }
                    }
                }
            }
            // ガチャ操作の助言＋修正導線（NextActionBar相当）
            ui.copilotHint?.let {
                Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("💡 $it", color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onGoEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("編集タブで見直す") }
                    }
                }
            }
            if (ui.polishExhausted && !ui.running) {
                Surface(color = cs.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✓ 必須条件は満たしています。残りの『できれば守りたい条件』は自動で整えて減らせます（必須は壊しません）。難しければ勤務表タブでの手修正が早い場合があります。",
                            color = cs.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSoftPolish, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("自動で整える") }
                            OutlinedButton(onClick = onGoEdit, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("手修正") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 人員不足(covU)が残るときだけ表示する原因診断カード。
 * 各不足枠を「充足不可（データ上どう割り当てても埋まらない）」か
 * 「充足可能（枠は足りる＝最適化が未到達）」に切り分けて、配布前の判断材料にする。
 */

@Composable
internal fun CoverageDiagnosisCard(ui: UiState) {
    val diag = ui.coverageDiag ?: return
    if (!diag.hasShortage) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("人員不足の原因", style = MaterialTheme.typography.titleMedium)
            val headline = when {
                diag.allInfeasible -> "不足 ${diag.totalShortfall} 人は全て充足不可。今のデータでは満たせません（想定内）。"
                diag.infeasibleSlots == 0 -> "不足 ${diag.totalShortfall} 人は枠が足りています。再実行や設定の見直しで解消し得ます。"
                else -> "不足 ${diag.totalShortfall} 人 — 充足不可 ${diag.infeasibleSlots} 枠 / 充足可能 ${diag.fixableSlots} 枠。"
            }
            Text(headline, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            for (s in diag.shortfalls.take(6)) {
                val infeasible = s.verdict == CoverageVerdict.INFEASIBLE
                val container = if (infeasible) cs.errorContainer else cs.secondaryContainer
                val onContainer = if (infeasible) cs.onErrorContainer else cs.onSecondaryContainer
                Surface(color = container, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${s.dayLabel}  ${s.shiftSymbol}  必要${s.need}/現状${s.got}（不足${s.miss}）",
                                color = onContainer, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            MagiTagChip(
                                text = if (infeasible) "充足不可" else "充足可能",
                                color = if (infeasible) MagiAccent.red else MagiAccent.blue,
                            )
                        }
                        Text(s.reason, color = onContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (diag.shortfalls.size > 6) {
                Text("ほか ${diag.shortfalls.size - 6} 枠（詳細はログ出力を参照）",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (diag.relaxations.isNotEmpty()) {
                Surface(color = cs.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("解けるようにするには（担当追加の案）", color = cs.onTertiaryContainer, style = MaterialTheme.typography.titleSmall)
                        for (r in diag.relaxations.take(4)) {
                            Text("・$r", color = cs.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("※ 担当追加の提案です。設定変更は行いません（採否はご判断ください）。",
                            color = cs.onTertiaryContainer.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}


/**
 * [設定ミスの誘導修正] 制約・希望シフトの入力間違いを「どこが・なぜ・どう直すか」で具体的に提示する。
 * CoverageDiagnosisCard（人員不足の原因）と同じ作りで、配布前に設定を直せるようにするのが目的。
 */
@Composable
internal fun SettingIssuesCard(ui: UiState, onFix: (com.magi.app.v6.SettingIssue) -> Unit, onGoEdit: () -> Unit) {
    val issues = ui.settingIssues
    if (issues.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("設定の見直し（${issues.size}件）", style = MaterialTheme.typography.titleMedium)
            Text("制約や希望シフトの入力に、直したほうがよい点があります。", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            for (s in issues.take(6)) {
                val label: String
                val tagColor: androidx.compose.ui.graphics.Color
                when (s.kind) {
                    com.magi.app.v6.IssueKind.WISH -> { label = "希望"; tagColor = MagiAccent.blue }
                    com.magi.app.v6.IssueKind.CONSTRAINT -> { label = "制約"; tagColor = MagiAccent.red }
                    com.magi.app.v6.IssueKind.DEMAND -> { label = "必要人数"; tagColor = MagiAccent.red }
                    com.magi.app.v6.IssueKind.RANGE -> { label = "回数"; tagColor = MagiAccent.orange }
                }
                Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MagiTagChip(text = label, color = tagColor)
                            Text(s.where, color = cs.onErrorContainer, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        }
                        Text(s.problem, color = cs.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        Text("→ ${s.fix}", color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        if (s.actionLabel.isNotEmpty()) {
                            Button(onClick = { onFix(s) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                                Text(s.actionLabel)
                            }
                        }
                    }
                }
            }
            if (issues.size > 6) {
                Text("ほか ${issues.size - 6} 件（詳細はログ出力を参照）", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            OutlinedButton(onClick = onGoEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("設定・希望を編集する") }
        }
    }
}

@Composable
internal fun V6DashboardCard(v6: V6PortReport?) {
    if (v6 == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("V6 1ヶ月俯瞰", fontWeight = FontWeight.Bold)
            Text(
                "人員の穴・負荷の偏り・入力ミスを勤務表から直接集計します。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            // §5.4 上部: 充足率(coverage%)を大数値ゲージで（必要人数のうち満たせた割合）
            v6.coveragePct?.let { pct ->
                val tint = if (pct >= 100) MaterialTheme.colorScheme.tertiary else if (pct >= 90) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                MagiScoreGauge(
                    score = pct,
                    max = 100,
                    label = "人員充足率",
                    sub = "必要人数 ${v6.demand} のうち満たせた割合",
                    accent = tint,
                )
                // [D3-full案A] 「できあがり度(全体の完成度)」と「人員充足率(人員の一側面)」は別指標。
                //   役割の違いを明示し、片方を他方の内訳と誤認させない(架空分解を避ける)。
                Text(
                    "※全体の完成度は「できあがり度」（ホーム）で確認。ここは人員の充足のみ。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BigStat("HARD Core", v6.hardCore.toString(), Modifier.weight(1f))
                BigStat("Guard", v6.hardGuard.toString(), Modifier.weight(1f))
                BigStat("充足", v6.coveragePct?.let { "$it%" } ?: "-", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (v6.topRiskShortage > 0) "最優先: ${v6.topRiskLabel} に不足 ${v6.topRiskShortage} 枠" else "最優先: 人員不足なし",
                color = if (v6.topRiskShortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                v6.dayRisks.forEach { d -> RiskChip(d.label, d.shortage, d.detail) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Apt=${"%.2f".format(v6.aptPenalty)} / Equalize=${"%.2f".format(v6.equPenalty)} / Demand=${v6.demand} / covU=${v6.covU}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (v6.sanityWarnings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                v6.sanityWarnings.take(3).forEach {
                    Text("⚠ $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("負荷プロフィール", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            v6.staffProfiles.take(5).forEach { st ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${st.name} ${st.groupSymbol}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("違反${st.violationCount} / 出勤${st.workCount} / ${st.workloadText}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}


/**
 * [N2/⛏11] 重み表カード。weightedScore の内部重み（MirrorKeys.weights）をそのまま描画し、
 * 「できあがり度/スコア」の根拠（どの違反が何倍効くか）を上級者が逆算できるようにする。
 * 重みは最適化器と同じマップを参照＝表示と最適化器が常に一致（統一思想の延長）。プロ表示時のみ。
 */
@Composable
internal fun WeightTableCard() {
    fun fmt(w: Double): String = if (w == w.toLong().toDouble()) w.toLong().toString() else w.toString()
    val sorted = MirrorKeys.weights.entries.sortedByDescending { it.value }
    val hard = sorted.filter { it.value >= 1000.0 }
    val soft = sorted.filter { it.value < 1000.0 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("重み表（最適化器と一致）", fontWeight = FontWeight.Bold)
            Text("スコアの内部重み。大きいほど優先して直されます。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("絶対に守る（HARD）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            hard.forEach { (k, w) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(breakdownLabels[k] ?: k, modifier = Modifier.weight(1f))
                    Text("×${fmt(w)}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
                }
            }
            Text("できれば守る（SOFT）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            soft.forEach { (k, w) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(breakdownLabels[k] ?: k, modifier = Modifier.weight(1f))
                    Text("×${fmt(w)}", fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}


@Composable
internal fun RiskChip(label: String, shortage: Int, detail: String) {
    val cs = MaterialTheme.colorScheme
    val (warnBg, warnFg) = magiWarnColors()
    val bg: Color; val fg: Color
    when {
        shortage <= 0 -> { bg = cs.tertiaryContainer; fg = cs.onTertiaryContainer }
        shortage == 1 -> { bg = warnBg; fg = warnFg }
        else -> { bg = cs.errorContainer; fg = cs.onErrorContainer }
    }
    Box(
        Modifier
            .width(76.dp)
            .background(bg, RoundedCornerShape(16.dp))
            .padding(horizontal = 7.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, color = fg, maxLines = 1)
            Text(if (shortage > 0) "不足$shortage" else "OK", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg)
            if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = fg.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** 内訳の家族キー → 日本語ラベル（BreakdownCard と FixSuggestionCard で共用）。 */
internal val breakdownLabels: Map<String, String> = mapOf(
    "groupViol" to "グループ不整合", "pref" to "希望違反", "covU" to "人員不足", "c3n" to "禁止の並び",
    "low" to "下限割れ", "high" to "上限超過", "apt" to "適切回数のズレ", "fair" to "公平化のズレ",
    "c1" to "窓の要件", "c2" to "個人の合計", "c3" to "必須の並び", "c3m" to "推奨の並び",
    "c3mn" to "回避の並び", "c41" to "群のレンジ", "c42" to "群ペア",
    "c41s" to "スキル群のレンジ", "c42s" to "スキル群ペア", "covO" to "過剰な配置",
)

/**
 * [分析→場所] 内訳の家族キー(low/covO/c42 等)から、その違反の「場所」と関係スタッフindexを返す。
 *  - count系(low/high/c2)   : スタッフ名「シフト」 / staff=i        (countViolations: i,k)
 *  - 被覆系(covU/covO/c41/c41s): 日付「シフト」 / staff=null         (needViolations: k,j)
 *  - セル系(c1/c3/c3n/c3m/c3mn/c42/c42s/pref/groupViol): スタッフ名 日付=実シフト / staff=i (violationCells: i,j)
 * staff!=null の項目はタップで「そのスタッフが関わる交換」を探せる。
 */
internal fun breakdownLocations(famKey: String, ui: UiState): List<Pair<String, Int?>> {
    fun nm(i: Int) = ui.staffNames.getOrNull(i) ?: "#$i"
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "$k"
    val want = "vio-$famKey"
    return when (famKey) {
        "low", "high", "c2" -> ui.countViolations.entries.filter { it.value == want }.mapNotNull {
            val p = it.key.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val k = p.getOrNull(1)?.toIntOrNull()
            if (i == null || k == null) null else ("${nm(i)} 「${sym(k)}」" to i)
        }
        // 適切回数(apt) は不足=vio-aptLow / 超過=vio-aptHigh の2クラスで countViolations(i,k) に入る。
        "apt" -> ui.countViolations.entries.filter { it.value == "vio-aptLow" || it.value == "vio-aptHigh" }.mapNotNull {
            val p = it.key.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val k = p.getOrNull(1)?.toIntOrNull()
            if (i == null || k == null) null else ("${nm(i)} 「${sym(k)}」" to i)
        }
        "covU", "covO", "c41", "c41s" -> ui.needViolations.entries.filter { it.value == want }.mapNotNull {
            val p = it.key.split(","); val k = p.getOrNull(0)?.toIntOrNull(); val j = p.getOrNull(1)?.toIntOrNull()
            if (k == null || j == null) null else ("${dayMD(ui.startDate, j)} 「${sym(k)}」" to null)
        }
        else -> ui.violationCells.entries.filter { it.value == want }.mapNotNull {
            val p = it.key.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val j = p.getOrNull(1)?.toIntOrNull()
            if (i == null || j == null) null else {
                val cell = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
                ("${nm(i)} ${dayMD(ui.startDate, j)}=${if (cell >= 0) sym(cell) else "—"}" to i)
            }
        }
    }
}

/**
 * [ボトルネック可視化] 違反が集中している「職員」と「日」を集約ランキングで俯瞰する read-only 診断。
 * セル単位の着色(TallyCard)を補完し「どこにしわ寄せが来ているか」を一目で示す。countViolations(職員×シフト)
 * を職員ごと、needViolations(シフト×日)を日ごとに件数集計し多い順に上位を提示。データ・重み不変。
 */
@Composable
internal fun BottleneckCard(ui: UiState, proMode: Boolean = false) {
    if (ui.countViolations.isEmpty() && ui.needViolations.isEmpty()) return
    fun nm(i: Int) = ui.staffNames.getOrNull(i) ?: "#$i"
    val perStaff = HashMap<Int, Int>()
    for (key in ui.countViolations.keys) {
        val i = key.substringBefore(",").toIntOrNull() ?: continue
        perStaff[i] = (perStaff[i] ?: 0) + 1
    }
    val perDay = HashMap<Int, Int>()
    for (key in ui.needViolations.keys) {
        val j = key.substringAfter(",").toIntOrNull() ?: continue
        perDay[j] = (perDay[j] ?: 0) + 1
    }
    val topStaff = perStaff.entries.sortedByDescending { it.value }.take(5)
    val topDay = perDay.entries.sortedByDescending { it.value }.take(5)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (proMode) "ボトルネック" else "ボトルネック（しわ寄せの集中箇所）", style = MaterialTheme.typography.titleMedium)
            Text(
                "違反が集中している職員・日です。ここの設定（担当範囲・希望・必要人数）を見直すと全体が解けやすくなります。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (topStaff.isNotEmpty()) {
                Text("職員別（回数の違反が多い順）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    topStaff.joinToString("  ・  ") { "${nm(it.key)}(${it.value})" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (topDay.isNotEmpty()) {
                Text("日別（人数の過不足が多い順）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    topDay.joinToString("  ・  ") { "${dayMD(ui.startDate, it.key)}(${it.value})" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun BreakdownCard(ui: UiState, onFocusStaff: (Int) -> Unit = {}, proMode: Boolean = false) {
    val labels = breakdownLabels
    var criticalOnly by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val onTapChip: (String) -> Unit = { k -> expanded = if (expanded == k) null else k }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("違反の内訳", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("重大のみ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Switch(checked = criticalOnly, onCheckedChange = { criticalOnly = it })
            }
            // [明確性I1] チップの数値は「ペナルティ量」。熟練者向けプロ表示では注記を省く。
            if (!proMode) {
                Text("数値はペナルティの大きさ（不足・超過の合計や並び違反の回数）。タップで実際の場所（セル）を表示します。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BreakdownGroup(if (proMode) "必須" else "必須（満たすべき）", listOf("groupViol", "pref", "covU", "c3n"), 2, ui, labels, expanded, onTapChip)
            if (!criticalOnly) {
                BreakdownGroup("人数の範囲", listOf("low", "high", "apt"), 1, ui, labels, expanded, onTapChip)
                BreakdownGroup(if (proMode) "任意" else "任意（できれば）", listOf("c1", "c2", "c3", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covO", "fair"), 0, ui, labels, expanded, onTapChip)
            }
            expanded?.let { key ->
                val cs = MaterialTheme.colorScheme
                val locs = breakdownLocations(key, ui)
                val name = labels[key] ?: key
                Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$name の場所（${locs.size}箇所）", style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f))
                            TextButton(onClick = { expanded = null }) { Text("閉じる") }
                        }
                        when {
                            locs.isEmpty() && ui.running -> Text("実行中です。確定後にここへ場所が表示されます。", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                            locs.isEmpty() -> Text("場所情報がありません。", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                            else -> {
                                locs.forEach { (txt, staff) ->
                                    if (staff != null) {
                                        Text("$txt　→直し方を探す", style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onFocusStaff(staff) })
                                    } else {
                                        Text(txt, style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
                                    }
                                }
                                if (ui.running) Text("※ 実行中のため確定前の値です（確定後に最新化）", style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
internal fun BreakdownGroup(title: String, keys: List<String>, severity: Int, ui: UiState, labels: Map<String, String>, expanded: String?, onTap: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        keys.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key -> SeverityChip(labels[key] ?: key, ui.breakdown[key] ?: 0, severity, key, expanded == key, onTap, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}


@Composable
internal fun SeverityChip(label: String, count: Int, severity: Int, famKey: String, expanded: Boolean, onTap: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val active = count > 0
    val container: Color; val onContainer: Color
    when {
        !active -> { container = cs.surfaceVariant; onContainer = cs.onSurfaceVariant }
        severity >= 2 -> { container = cs.errorContainer; onContainer = cs.onErrorContainer }
        severity == 1 -> { container = cs.secondaryContainer; onContainer = cs.onSecondaryContainer }
        else -> { container = cs.primaryContainer; onContainer = cs.onPrimaryContainer }
    }
    val shape = MaterialTheme.shapes.small
    var m = modifier.heightIn(min = 48.dp)
    if (expanded) m = m.border(2.dp, onContainer.copy(alpha = 0.7f), shape)
    if (active) m = m.clickable { onTap(famKey) }
    Surface(color = container, shape = shape, modifier = m) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = onContainer,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = onContainer)
            if (active) {
                Spacer(Modifier.width(2.dp))
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = onContainer, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * [B1] 勤務表の「結果(読取ws6)／編集中(ws7)」モード切替カード。
 * 既存の 7日/カレンダー/1ヶ月 切替の上に置く。結果モードは誤編集防止のため読取専用。
 */

@Composable
internal fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}


/** 改善手の種類 → (チップ文言, 色)。 */
private fun fixKindTag(k: com.magi.app.v6.FixKind): Pair<String, androidx.compose.ui.graphics.Color> = when (k) {
    com.magi.app.v6.FixKind.CHANGE -> "変更" to MagiAccent.green
    com.magi.app.v6.FixKind.CHANGE_MULTI -> "複数変更" to MagiAccent.green
    com.magi.app.v6.FixKind.SWAP -> "交換" to MagiAccent.blue
    com.magi.app.v6.FixKind.SWAP_XDAY -> "別日交換" to MagiAccent.blue
    com.magi.app.v6.FixKind.SWAP_MULTI -> "3人交換" to MagiAccent.purple
    com.magi.app.v6.FixKind.CHAIN -> "連鎖" to MagiAccent.red
    com.magi.app.v6.FixKind.WINDOW -> "再最適化" to MagiAccent.orange
}

@Composable
internal fun FixSuggestionCard(ui: UiState, onSearch: () -> Unit, onApply: (com.magi.app.v6.FixSuggestion) -> Unit, proMode: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = "改善の提案" + if (ui.fixFocusName.isNotBlank()) "：${ui.fixFocusName} 関連" else ""
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (ui.fixSearching) {
                    Text("探索中…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else {
                    TextButton(onClick = onSearch) { Text(if (ui.fixSuggestions.isEmpty()) "探す" else "全体で再探索") }
                }
            }
            if (!proMode) {
                Text("違反を減らす1手を効果順に提案します。「変更」は1マスを別の勤務に、「交換」は2人の同日を入れ替えます。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            when {
                ui.fixSearching -> Text("候補を探しています。少しお待ちください。", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                ui.fixSuggestions.isEmpty() -> Text("候補がありません。「探す」を押すか、上の違反の場所をタップしてください。\n※1手で直せない違反（下限が競合する等の構造的不足）は、設定(ws1)の見直しが根本解です。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                else -> ui.fixSuggestions.forEach { s ->
                    val (tag, tagColor) = fixKindTag(s.kind)
                    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MagiTagChip(text = tag, color = tagColor)
                                Text(s.label, style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f))
                            }
                            val diffTxt = s.diff.joinToString("・") { (k, d) ->
                                "${breakdownLabels[k] ?: k} ${if (d < 0) "−${-d}" else "+$d"}"
                            }
                            val totalTxt = if (s.deltaTotal <= 0) "−${-s.deltaTotal}" else "+${s.deltaTotal}"
                            Text("違反 $totalTxt" + if (diffTxt.isNotBlank()) "（$diffTxt）" else "",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
                            Button(onClick = { onApply(s) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                                Text("この手を適用")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AlternativesCard(ui: UiState, onApply: (Int) -> Unit) {
    if (ui.alternatives.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("他の案（${ui.alternatives.size}）", style = MaterialTheme.typography.titleMedium)
            Text("並列探索で見つかった、採用案以外の候補です。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ui.alternatives.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { onApply(i) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text("採用") }
                }
            }
        }
    }
}


@Composable
internal fun WishApplyCard(ui: UiState, onApply: () -> Unit) {
    if (!ui.loaded) return
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("希望シフトを反映", fontWeight = FontWeight.Bold)
                Text("登録済みの希望を勤務表へ上書きします（元に戻せます）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onApply, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text("希望を反映する") }
        }
    }
}

