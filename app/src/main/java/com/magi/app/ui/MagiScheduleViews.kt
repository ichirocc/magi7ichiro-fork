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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.splineBasedDecay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.roundToInt

/**
 * CSVのバイト列を文字列へ復号する。妥当な UTF-8 ならそれを採用し、そうでなければ日本の Excel CSV で
 * 一般的な CP932(Shift-JIS) とみなす。先頭の BOM は除去する。これにより Shift-JIS の勤務表CSVが
 * 文字化けせず取り込める（UTF-8 として bytes を読むと壊れていた）。
 */

/** 実行中の進捗サマリ文字列: 改善率(初期soft→現best) ・ 残り時間 ・ 探索数。読取専用。 */
internal fun progressSummary(ui: UiState): String {
    val parts = ArrayList<String>(3)
    parts += when {
        ui.bestHard > 0L -> "未解決 ⚠${ui.bestHard}"
        ui.initSoft > 0L -> {
            val pct = ((ui.initSoft - ui.bestSoft) * 100L / ui.initSoft).coerceAtLeast(0L)
            "改善 ${pct}% (${ui.initSoft}→${ui.bestSoft})"
        }
        else -> "改善 –"
    }
    val secLeft = ((ui.budgetSec * 1000L - ui.elapsedMs).coerceAtLeast(0L) / 1000L)
    parts += "残り %d:%02d".format(secLeft / 60, secLeft % 60)
    val it = ui.iters
    val iterStr = when {
        it >= 1_000_000L -> "%.1fM回".format(it / 1_000_000.0)
        it >= 1_000L -> "${it / 1_000L}K回"
        else -> "${it}回"
    }
    parts += iterStr
    return parts.joinToString("  ・  ")
}

@Composable
internal fun LiveScheduleCard(ui: UiState) {
    if (!ui.running || ui.liveSchedule.isEmpty()) return
    var show by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // [進捗の見える化] 改善率/残り時間/探索数を常時1行で。エンジニア向けログより「あと何分・どれだけ良くなった」を優先。
            Text(progressSummary(ui), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.primary)
            val cur = ui.liveSchedule
            // 変化セル検出: 前回スナップショットとの差分。holder(非state)で保持し再合成ループを避ける。
            val prevHolder = remember { arrayOfNulls<List<List<Int>>>(1) }
            val changed = remember(cur) {
                val set = HashSet<Int>()
                val p = prevHolder[0]
                if (p != null && p.size == cur.size) {
                    for (i in cur.indices) {
                        val a = p[i]; val b = cur[i]
                        if (a.size == b.size) for (j in b.indices) if (a[j] != b[j]) set.add(i * 100000 + j)
                    }
                }
                prevHolder[0] = cur
                set
            }
            TextButton(onClick = { show = !show }, modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(if (show) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(if (show) "途中経過を隠す" else "途中経過を見る（組んでいる様子）")
            }
            if (show) {
                Text("状態遷移  赤枠＝今回変化 (${changed.size})", fontSize = 11.sp, color = cs.onSurfaceVariant)
                Column(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    cur.forEachIndexed { i, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            row.forEachIndexed { j, k ->
                                val color = if (k < 0) cs.surfaceVariant else hexToColor(ui.shiftColorHex.getOrNull(k) ?: "")
                                val isChanged = changed.contains(i * 100000 + j)
                                Box(
                                    Modifier
                                        .size(11.dp)
                                        .background(color, RoundedCornerShape(2.dp))
                                        .then(if (isChanged) Modifier.border(2.dp, cs.error, RoundedCornerShape(2.dp)) else Modifier),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [operator_ux §5] 「なおすのを手伝って」対話。人手不足を1タップで埋める誘導フロー。
 * いまの診断(coverageDiag)から「充足可能」な不足枠を1つ取り上げ、入れられる職員を大ボタンで提示。
 * タップ→反映(setCell, Undo可)→診断が更新され次の枠へ自動で進む。埋められない枠は理由つきで提示。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShiftPickerSheet(
    ui: UiState,
    vm: MagiViewModel,
    cell: Pair<Int, Int>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val (i, j) = cell
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current   // [一貫性G2] 希望操作にも触覚を付ける（割当と対称）
    val sheetState = rememberModalBottomSheetState()
    val allowed = remember(cell) { vm.allowedShiftsFor(i).toList() }
    val current = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
    val wish = ui.wishes["$i,$j"]
    var mode by remember(cell) { mutableIntStateOf(0) } // 0=割当, 1=希望
    val name = ui.staffNames.getOrNull(i) ?: i.toString()
    fun sym(k: Int?): String = k?.let { ui.shiftSymbols.getOrNull(it) } ?: "—"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DialogHeader("$name ・ ${j + 1}日", onDismiss)
            // 凝縮ステータス: 現在の割当 + 希望
            Surface(color = cs.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("現在の割当  ${sym(current)}", style = MaterialTheme.typography.bodyMedium)
                    val wt = if (wish == null) "希望  未登録"
                        else "希望  ${sym(wish)}" + (if (wish == current) "（反映済）" else "（未反映）")
                    Text(wt, style = MaterialTheme.typography.bodyMedium,
                        color = if (wish != null && wish != current) MagiAccent.pink else cs.onSurfaceVariant)
                }
            }
            // 希望どおりにする（割当モード・未反映・担当可のときだけ）= 最頻操作を1タップ
            if (mode == 0 && wish != null && wish != current && wish in allowed) {
                Button(onClick = { onPick(wish) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("希望どおり ${sym(wish)} にする")
                }
            }
            // 割当/希望 トグル
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("割当を変更", "希望を変更").forEachIndexed { idx, label ->
                    val selSeg = mode == idx
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp)
                            .background(if (selSeg) cs.primaryContainer else cs.surfaceVariant, RoundedCornerShape(12.dp))
                            .clickable { mode = idx },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (selSeg) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            fontWeight = if (selSeg) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Text(
                if (mode == 0) "タップで割当を即変更。" else "タップで希望を登録/変更（即確定）。「外」=担当外（登録可・配置で違反）。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
            )
            val opts = if (mode == 0) (if (allowed.isNotEmpty()) allowed else ui.shiftSymbols.indices.toList())
                       else ui.shiftSymbols.indices.toList()
            opts.chunked(4).forEach { rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowKeys.forEach { k ->
                        val symbol = ui.shiftSymbols.getOrNull(k) ?: k.toString()
                        val sel = if (mode == 0) k == current else k == wish
                        val ng = mode == 1 && k !in allowed
                        val bg = if (sel) cs.primary else hexToColor(ui.shiftColorHex.getOrNull(k) ?: "")
                        val fg = if (sel) cs.onPrimary else hexToColor(ui.shiftTextHex.getOrNull(k) ?: "")
                        // [結果プレビュー] 割当モードのみ: 現在/希望/不足解消/超過 を注記（needViolations から確実に判定）。
                        val noteParts = ArrayList<String>()
                        var noteWarn = false
                        if (mode == 0) {
                            if (k == current) noteParts.add("現在") else if (k == wish) noteParts.add("希望")
                            when (ui.needViolations["$k,$j"]) {
                                "vio-covU" -> noteParts.add("不足解消")
                                "vio-covO" -> { noteParts.add("超過"); noteWarn = true }
                            }
                        }
                        val note = noteParts.joinToString("・")
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .background(bg, RoundedCornerShape(16.dp))
                                .then(if (ng) Modifier.border(2.dp, cs.error, RoundedCornerShape(16.dp)) else Modifier)
                                .clickable {
                                    if (mode == 0) onPick(k) else { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.setWish(i, j, k); onDismiss() }
                                }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(symbol + (if (ng) " 外" else ""), color = if (ng) cs.error else fg, fontWeight = FontWeight.Bold)
                                if (note.isNotEmpty()) {
                                    Text(note, style = MaterialTheme.typography.labelSmall,
                                        color = if (noteWarn) MagiAccent.orange else if (sel) cs.onPrimary else MagiAccent.green)
                                }
                            }
                        }
                    }
                    repeat(4 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            // 希望を削除（希望モード・登録済みのみ）
            if (mode == 1 && wish != null) {
                OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.removeWish(i, j); onDismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Text("希望を削除（希望なし）", color = cs.error)
                }
            }
        }
    }
}


@Composable
internal fun StaffCalendarCard(ui: UiState, onCellClick: (Int, Int) -> Unit) {
    if (ui.schedule.isEmpty() || ui.staff == 0) return
    var staffIdx by remember { mutableIntStateOf(0) }
    val si = staffIdx.coerceIn(0, (ui.staff - 1).coerceAtLeast(0))
    val row = ui.schedule.getOrNull(si) ?: return
    val labels = ui.v6?.dayRisks?.map { it.label } ?: (0 until ui.days).map { "${it + 1}日" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("スタッフ別カレンダー", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { staffIdx = (si - 1).floorMod(ui.staff) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("前") }
                OutlinedButton(onClick = { staffIdx = (si + 1).floorMod(ui.staff) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("次") }
            }
            Text(
                "${ui.staffNames.getOrNull(si) ?: si} / ${ui.staffGroupSymbols.getOrNull(si) ?: ""} — タップで担当可能シフトを巡回",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            val vioColor = ui.violationColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: MaterialTheme.colorScheme.error
            row.indices.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { j ->
                        val k = row.getOrNull(j) ?: -1
                        val symbol = if (k < 0) "·" else ui.shiftSymbols.getOrNull(k) ?: k.toString()
                        val vioVal = ui.violationCells["$si,$j"]
                        val vio = vioVal != null
                        val hard = isHardCellViolation(vioVal)
                        CalendarCell(labels.getOrNull(j) ?: "${j + 1}日", symbol, vio, hard, vioColor, Modifier.weight(1f)) {
                            onCellClick(si, j)
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}


@Composable
internal fun CalendarCell(label: String, symbol: String, violation: Boolean, hard: Boolean, vioColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (violation) cs.errorContainer else cs.surfaceVariant
    val labelFg = if (violation) cs.onErrorContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant
    val symbolFg = if (violation) cs.onErrorContainer else cs.onSurface
    // [UD/WCAG 4.1.2] 色・形に依存しない読み上げ名。
    val a11y = "$label シフト ${symbol.ifBlank { "なし" }}" + (if (violation) (if (hard) "・必須違反" else "・要調整") else "") + "、タップで変更"
    Box(
        modifier
            .height(58.dp)
            .padding(horizontal = 2.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .then(if (violation) Modifier.violationBorder(hard, vioColor, 14.dp) else Modifier)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, color = labelFg, maxLines = 1)
            Text(symbol, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = symbolFg, maxLines = 1)
        }
    }
}


@Composable
internal fun ScheduleModeCard(
    editing: Boolean,
    canRead: Boolean,
    onSelect: (Boolean) -> Unit,
    onCommit: () -> Unit,
    onCopy: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // [校正] ラベルを平易化し「見るだけ/直す」を明示。選択中の区分で状態は分かるため、
            //   重複する「編集中・未確定」バッジは廃止し、説明を1行に集約（冗長解消）。
            MagiSegmentedControl(
                options = listOf("結果（見るだけ）", "下書き（直す）"),
                selected = if (editing) 1 else 0,
                onSelect = { onSelect(it == 1) },
            )
            Text(
                if (editing) "いまは下書きを直しています。よければ［結果に反映］。最適化もこの下書きが起点です。"
                else "確定した結果を見ています（このままでは変えられません）。直すには「下書き（直す）」へ。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (editing && canRead) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onCommit, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("結果に反映") }
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("結果を複製") }
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleGrid(
    ui: UiState, onCellClick: (Int, Int) -> Unit, proMode: Boolean = false,
    onBulkSet: (Collection<Pair<Int, Int>>, Int) -> Unit = { _, _ -> },
) {
    val cs = MaterialTheme.colorScheme
    val vioColor = ui.violationColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: cs.error
    // [一括編集] 円柱は1セル編集。まとめて変更するダイアログの開閉。
    var showBulk by rememberSaveable { mutableStateOf(false) }
    // [違反フィルタ] グリッドのセル違反を種別ごとに表示/非表示。hiddenVio に入れた家族キーを隠す。
    //   violationCells の値は "vio-<famKey>" なので接頭辞を外して種別を取り出す。自己完結（VM不要）。
    var hiddenVio by remember { mutableStateOf(setOf<String>()) }
    val vioTypes = remember(ui.violationCells) {
        ui.violationCells.values.map { it.removePrefix("vio-") }.distinct()
    }
    val vioCounts = remember(ui.violationCells) {
        ui.violationCells.values.groupingBy { it.removePrefix("vio-") }.eachCount()
    }
    fun shown(v: String?) = v != null && v.removePrefix("vio-") !in hiddenVio
    // [凡例の冗長対策] 実線/破線・シフト色キーは作成者には暗記済みのことが多いので既定で畳む。
    var legendOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("勤務表", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // [一括編集] まとめて変更は多数セルを上書きする上級操作のため、プロ表示時のみ。範囲×対象×シフトをダイアログで一括指定。
            if (proMode) {
                OutlinedButton(onClick = { showBulk = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("まとめて割当（一括編集）")
                }
            }
            // [違反フィルタ＋内訳] 種別ごとの件数つきチップで「どの違反が何件か」を勤務表上で一覧化。
            //   タップで表示/非表示（隠した種別はセル枠が消える）。1種別に絞れば誰の何日かが直接見える。
            if (vioTypes.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("違反の内訳（タップで絞り込み）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    if (hiddenVio.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { hiddenVio = emptySet() }) {
                            Text("すべて表示", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    vioTypes.forEach { t ->
                        FilterChip(
                            selected = t !in hiddenVio,
                            onClick = { hiddenVio = if (t in hiddenVio) hiddenVio - t else hiddenVio + t },
                            label = { Text("${breakdownLabels[t] ?: t} ${vioCounts[t] ?: 0}", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                // [誰が・いつ] 表示中の違反セルを「名前 d日」で列挙（最大8件）。7日表示で画面外の違反も把握でき、
                //   1種別に絞ると場所が一目で分かる。グリッドを端から探さずに済む。
                val shownLocs = ui.violationCells.entries.filter { shown(it.value) }.mapNotNull { e ->
                    val p = e.key.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val j = p.getOrNull(1)?.toIntOrNull()
                    if (i == null || j == null) null else "${ui.staffNames.getOrNull(i) ?: "#$i"} ${j + 1}日"
                }
                if (shownLocs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    val more = if (shownLocs.size > 8) " 他${shownLocs.size - 8}件" else ""
                    Text("場所：${shownLocs.take(8).joinToString("、")}$more",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            // [凡例] 参照情報（実線/破線の意味・シフト色キー）は作成者には暗記済みのことが多く、常時表示は冗長。
            //   既定で畳み、タップで展開する（情報密度・スクロール量を優先）。新規/応援スタッフは開けば確認できる。
            if (ui.violationCells.isNotEmpty() || ui.shiftSymbols.any { it.isNotBlank() }) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { legendOpen = !legendOpen }) {
                    Text(if (legendOpen) "凡例を隠す ▾" else "凡例（色・記号の意味）▸",
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
                if (legendOpen) {
                    if (ui.violationCells.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        ViolationLegend(vioColor)
                    }
                    ShiftColorLegend(ui.shiftSymbols, ui.shiftColorHex, ui.shiftTextHex)
                }
            }
            Spacer(Modifier.height(12.dp))
            MagiFocusCylinder(ui, onCellClick)
            if (showBulk) AssignBulkSheet(ui, onBulkSet) { showBulk = false }
        }
    }
}

/** 期間開始日の曜日インデックス（0=月..6=日）。解析不能なら 0。 */

internal fun startDowMonFirst(startDate: String): Int = try {
    (LocalDate.parse(startDate).dayOfWeek.value - 1).coerceIn(0, 6)
} catch (_: Exception) { 0 }

/** 違反セルの凡例（実線=必須 / 破線=要調整）。非色手がかりの意味を必ず示す。 */

@Composable
internal fun ViolationLegend(vioColor: Color, vioSoftColor: Color = MagiAccent.orange) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(width = 22.dp, height = 16.dp).border(3.dp, vioColor, RoundedCornerShape(4.dp)))
            Text("赤・実線＝必須違反", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(width = 22.dp, height = 16.dp).violationBorder(false, vioSoftColor, 4.dp))
            Text("橙・破線＝要調整", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * [色キー] シフトの色凡例。各シフトの記号を「実際のグリッド色」で表示し、色→シフトを即座にひける。
 *  シフト作成者は記号・名称を熟知している前提のため、記号=名称の説明ではなく、グリッドの着色を
 *  解読するための色対応を提供する（色覚配慮で記号文字も併記＝色＋形の二重符号化）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShiftColorLegend(symbols: List<String>, colorHex: List<String>, textHex: List<String>) {
    val items = symbols.indices.filter { symbols[it].isNotBlank() }
    if (items.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Column {
        Spacer(Modifier.height(8.dp))
        Text("シフトの色", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { i ->
                val bg = hexToColor(colorHex.getOrNull(i) ?: "")
                val fg = hexToColor(textHex.getOrNull(i) ?: "")
                Box(Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(symbols[i], style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * 違反値("vio-<family>")が必須(HARD)系か判定。色に依らない手がかり(実線/破線)の切替に使う。
 * ハード族の一覧は MirrorKeys.hard を唯一の真実源とする（ここで列挙し直すと将来の追加/改名で乖離する）。
 */

internal fun isHardCellViolation(v: String?): Boolean =
    v != null && MirrorKeys.hard.any { v.contains(it) }

/** 違反セルの非色手がかり: HARD=実線枠、SOFT=破線枠（色覚多様性／モノクロ印刷でも区別可能）。
 *  [校正] 色付きセル上でも埋もれないよう枠を太く（3dp）。 */

internal fun Modifier.violationBorder(hard: Boolean, color: Color, radiusDp: androidx.compose.ui.unit.Dp): Modifier =
    if (hard) {
        this.border(3.dp, color, RoundedCornerShape(radiusDp))
    } else {
        this.drawBehind {
            val stroke = 3.dp.toPx()
            val r = radiusDp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
            )
        }
    }


/** [希望の一括操作] 対象範囲(曜日/期間全体) × 対象(全員/1名) × 希望シフト。登録/クリア。誤操作防止で明示確定。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WishBulkSheet(ui: UiState, vm: MagiViewModel, presetWeekday: Int, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    val days = ui.days
    val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")
    val startDow = startDowMonFirst(ui.startDate)
    var scope by remember { mutableIntStateOf(0) }                       // 0=この曜日, 1=期間全体
    var weekday by remember { mutableIntStateOf(presetWeekday.coerceIn(0, 6)) }
    var staffSel by remember { mutableIntStateOf(-1) }                   // -1=全職員, else staff index
    var picked by remember { mutableIntStateOf(-1) }                     // 選択中の希望シフト
    var showStaff by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    val targetDays = if (scope == 1) (0 until days).toList()
        else (0 until days).filter { (startDow + it) % 7 == weekday }
    val allowed = if (staffSel >= 0) vm.allowedShiftsFor(staffSel).toList() else emptyList()
    val targetName = if (staffSel >= 0) (ui.staffNames.getOrNull(staffSel) ?: "$staffSel") else "全職員"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DialogHeader("希望シフトの一括操作", onDismiss)
            Text("対象範囲", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("この曜日", "期間全体").forEachIndexed { idx, label ->
                    val s = scope == idx
                    Box(Modifier.weight(1f).heightIn(min = 44.dp)
                        .background(if (s) cs.primaryContainer else cs.surfaceVariant, RoundedCornerShape(12.dp))
                        .clickable { scope = idx }, contentAlignment = Alignment.Center) {
                        Text(label, color = if (s) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            fontWeight = if (s) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            if (scope == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    weekdays.forEachIndexed { idx, wd ->
                        val s = weekday == idx
                        Box(Modifier.weight(1f).heightIn(min = 36.dp)
                            .background(if (s) cs.primary else cs.surfaceVariant, RoundedCornerShape(10.dp))
                            .clickable { weekday = idx }, contentAlignment = Alignment.Center) {
                            Text(wd, color = if (s) cs.onPrimary else cs.onSurfaceVariant,
                                fontWeight = if (s) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Text("対象 ${targetDays.size}日。既存の希望は上書き。", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text("対象（誰に）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 40.dp)
                    .background(if (staffSel < 0) cs.primary else cs.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { staffSel = -1 }, contentAlignment = Alignment.Center) {
                    Text("全職員", color = if (staffSel < 0) cs.onPrimary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).heightIn(min = 40.dp)
                    .background(if (staffSel >= 0) cs.primary else cs.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { showStaff = true }, contentAlignment = Alignment.Center) {
                    Text(if (staffSel >= 0) "職員：$targetName" else "職員を選ぶ",
                        color = if (staffSel >= 0) cs.onPrimary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
            Text("希望シフト（タップで選択）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            ui.shiftSymbols.indices.toList().chunked(3).forEach { rowKeys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowKeys.forEach { k ->
                        val sel = picked == k
                        val ng = staffSel >= 0 && k !in allowed
                        Box(Modifier.weight(1f).heightIn(min = 48.dp)
                            .background(if (sel) cs.primaryContainer else cs.surface, RoundedCornerShape(12.dp))
                            .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else if (ng) cs.error else cs.outline, RoundedCornerShape(12.dp))
                            .clickable { picked = k }, contentAlignment = Alignment.Center) {
                            Text((ui.shiftSymbols.getOrNull(k) ?: "$k") + (if (ng) " 外" else ""),
                                color = if (ng) cs.error else cs.onSurface, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    repeat(3 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (scope == 1 && staffSel < 0) confirmClearAll = true
                    else { vm.clearWishesForDays(if (staffSel < 0) null else staffSel, targetDays); onDismiss() }
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("この範囲を希望なしに", color = cs.error)
                }
                Button(onClick = {
                    if (picked in ui.shiftSymbols.indices) {
                        vm.setWishesForDays(if (staffSel < 0) null else staffSel, targetDays, picked); onDismiss()
                    }
                }, enabled = picked in ui.shiftSymbols.indices && targetDays.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("適用（${targetDays.size}件）")
                }
            }
            Text("※ 期間全体×全職員の「希望なし」は全削除（確認あり）。元に戻すで取消可。",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
    if (showStaff) {
        AlertDialog(
            onDismissRequest = { showStaff = false },
            confirmButton = {},
            dismissButton = { DialogDismissButton(onClick = { showStaff = false }, text = "閉じる") },
            title = { DialogHeader("職員を選ぶ", { showStaff = false }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ui.staffNames.forEachIndexed { idx, n ->
                        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { staffSel = idx; showStaff = false },
                            contentAlignment = Alignment.CenterStart) {
                            Text(n, Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            },
        )
    }
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            confirmButton = { DialogDangerButton("すべて削除", onClick = { confirmClearAll = false; vm.clearAllWishes(); onDismiss() }) },
            dismissButton = { DialogDismissButton(onClick = { confirmClearAll = false }) },
            title = { Text("すべての希望を削除") },
            text = { Text("登録済みの希望をすべて削除します。割当には影響しません。元に戻すで復元できます。") },
        )
    }
}

/** [割当の一括操作] 対象範囲(曜日/期間全体) × 対象(全員/1名) × シフト → まとめて割当。公・休で休みも設定可。元に戻すで取消可。円柱から起動。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssignBulkSheet(ui: UiState, onBulkSet: (Collection<Pair<Int, Int>>, Int) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    val days = ui.days
    val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")
    val startDow = startDowMonFirst(ui.startDate)
    var scope by remember { mutableIntStateOf(1) }        // 0=この曜日, 1=期間全体
    var weekday by remember { mutableIntStateOf(0) }
    var staffSel by remember { mutableIntStateOf(-1) }    // -1=全職員, else staff index
    var picked by remember { mutableIntStateOf(-1) }      // 選択中のシフト
    var showStaff by remember { mutableStateOf(false) }
    val targetDays = if (scope == 1) (0 until days).toList()
        else (0 until days).filter { (startDow + it) % 7 == weekday }
    val targetStaff = if (staffSel >= 0) listOf(staffSel) else ui.schedule.indices.toList()
    val targetName = if (staffSel >= 0) (ui.staffNames.getOrNull(staffSel) ?: "$staffSel") else "全職員"
    val cellCount = targetStaff.size * targetDays.size
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DialogHeader("割当の一括操作", onDismiss)
            Text("対象範囲", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("この曜日", "期間全体").forEachIndexed { idx, label ->
                    val s = scope == idx
                    Box(Modifier.weight(1f).heightIn(min = 44.dp)
                        .background(if (s) cs.primaryContainer else cs.surfaceVariant, RoundedCornerShape(12.dp))
                        .clickable { scope = idx }, contentAlignment = Alignment.Center) {
                        Text(label, color = if (s) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            fontWeight = if (s) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            if (scope == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    weekdays.forEachIndexed { idx, wd ->
                        val s = weekday == idx
                        Box(Modifier.weight(1f).heightIn(min = 36.dp)
                            .background(if (s) cs.primary else cs.surfaceVariant, RoundedCornerShape(10.dp))
                            .clickable { weekday = idx }, contentAlignment = Alignment.Center) {
                            Text(wd, color = if (s) cs.onPrimary else cs.onSurfaceVariant,
                                fontWeight = if (s) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Text("対象（誰に）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 40.dp)
                    .background(if (staffSel < 0) cs.primary else cs.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { staffSel = -1 }, contentAlignment = Alignment.Center) {
                    Text("全職員", color = if (staffSel < 0) cs.onPrimary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).heightIn(min = 40.dp)
                    .background(if (staffSel >= 0) cs.primary else cs.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { showStaff = true }, contentAlignment = Alignment.Center) {
                    Text(if (staffSel >= 0) "職員：$targetName" else "職員を選ぶ",
                        color = if (staffSel >= 0) cs.onPrimary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
            Text("対象 ${targetStaff.size}名 × ${targetDays.size}日 = ${cellCount}マス。既存の割当は上書き。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text("シフト（タップで選択。公・休で休みにできます）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            ui.shiftSymbols.indices.toList().chunked(3).forEach { rowKeys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowKeys.forEach { k ->
                        val sel = picked == k
                        Box(Modifier.weight(1f).heightIn(min = 48.dp)
                            .background(if (sel) cs.primaryContainer else cs.surface, RoundedCornerShape(12.dp))
                            .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(12.dp))
                            .clickable { picked = k }, contentAlignment = Alignment.Center) {
                            Text(ui.shiftSymbols.getOrNull(k) ?: "$k",
                                color = hexToColor(ui.shiftTextHex.getOrNull(k) ?: ""), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    repeat(3 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Button(onClick = {
                if (picked in ui.shiftSymbols.indices && cellCount > 0) {
                    val cells = targetStaff.flatMap { i -> targetDays.map { j -> i to j } }
                    onBulkSet(cells, picked); onDismiss()
                }
            }, enabled = picked in ui.shiftSymbols.indices && cellCount > 0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("この${cellCount}マスに一括割当")
            }
            Text("※ 選択したマスを上書きします。元に戻すで取消可。", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
    if (showStaff) {
        AlertDialog(
            onDismissRequest = { showStaff = false },
            confirmButton = {},
            dismissButton = { DialogDismissButton(onClick = { showStaff = false }, text = "閉じる") },
            title = { DialogHeader("職員を選ぶ", { showStaff = false }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ui.staffNames.forEachIndexed { idx, n ->
                        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { staffSel = idx; showStaff = false },
                            contentAlignment = Alignment.CenterStart) {
                            Text(n, Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            },
        )
    }
}

/** 期間開始日 + j(0始まり) → "M/D"。解析失敗時は "(j+1)日"。 */
internal fun dayMD(startDate: String, j: Int): String = try {
    val d = LocalDate.parse(startDate).plusDays(j.toLong())
    "${d.monthValue}/${d.dayOfMonth}"
} catch (e: Exception) { "${j + 1}日" }

/** [不一致だけ抽出] 違反・希望未反映だけを凝縮表示。希望未反映行はタップで該当セル編集へ直行。 */
@Composable
internal fun MismatchExtractCard(ui: UiState, onOpenCell: (Int, Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val coverage = ui.needViolations.entries.mapNotNull { (key, cls) ->
        val pp = key.split(","); val k = pp.getOrNull(0)?.toIntOrNull(); val j = pp.getOrNull(1)?.toIntOrNull()
        if (k == null || j == null) return@mapNotNull null
        val tag = when (cls) { "vio-covU" -> "不足"; "vio-covO" -> "超過"; else -> return@mapNotNull null }
        "${dayMD(ui.startDate, j)} ${ui.shiftSymbols.getOrNull(k) ?: k} $tag"
    }
    val counts = ui.countViolations.entries.mapNotNull { (key, cls) ->
        val pp = key.split(","); val i = pp.getOrNull(0)?.toIntOrNull(); val k = pp.getOrNull(1)?.toIntOrNull()
        if (i == null || k == null) return@mapNotNull null
        val tag = when (cls) { "vio-low" -> "少"; "vio-high" -> "多"; else -> return@mapNotNull null }
        "${ui.staffNames.getOrNull(i) ?: i} ${ui.shiftSymbols.getOrNull(k) ?: k}$tag"
    }
    val unmet = ui.wishes.entries.mapNotNull { (key, w) ->
        val pp = key.split(","); val i = pp.getOrNull(0)?.toIntOrNull(); val j = pp.getOrNull(1)?.toIntOrNull()
        if (i == null || j == null) return@mapNotNull null
        val cur = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
        if (cur == w) return@mapNotNull null
        Triple(i, j, "${ui.staffNames.getOrNull(i) ?: i} ${dayMD(ui.startDate, j)} 希望${ui.shiftSymbols.getOrNull(w) ?: w}→${ui.shiftSymbols.getOrNull(cur) ?: "—"}")
    }
    if (coverage.isEmpty() && counts.isEmpty() && unmet.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("不一致だけ抽出", style = MaterialTheme.typography.titleMedium)
            if (coverage.isNotEmpty()) {
                Text("人数の過不足（${coverage.size}）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(coverage.joinToString(" ・ "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            if (counts.isNotEmpty()) {
                Text("適切回数の範囲外（${counts.size}）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(counts.joinToString(" ・ "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            if (unmet.isNotEmpty()) {
                Text("希望シフト未反映（${unmet.size}）— タップで修正", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MagiAccent.pink)
                unmet.forEach { (i, j, txt) ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { onOpenCell(i, j) }, contentAlignment = Alignment.CenterStart) {
                        Text(txt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun Int.floorMod(m: Int): Int = if (m == 0) 0 else ((this % m) + m) % m

// ============================================================================
// 大規模UI改良: ユニバーサルデザイン + スマホ特化シェル (ボトムナビ + ステータスヒーロー)
// ============================================================================

// ============================================================================
// [集計] 各職員・各日のシフト集計（Excel版の右側=職員別 / 下側=日別 を再現）
// 表示中スケジュール(gridUi)から countMatrix / coverage 相当を算出して表で示す。
// 片手一本指: 横スクロール（rememberScrollState）でシフト列/日列を送る。
// ============================================================================
@Composable
internal fun TallyCard(ui: UiState, vm: MagiViewModel, onFix: (Int?, Int?) -> Unit = { _, _ -> }) {
    val k = ui.shiftSymbols.size
    val s = ui.schedule.size
    val t = ui.days
    if (s == 0 || k == 0 || t == 0) return
    val cs = MaterialTheme.colorScheme
    // [直せる導線] 違反セルをタップ→原因(必要/下限/上限/目標 と現在)を数字で提示し「直し方を探す」へ。
    var detail by remember { mutableStateOf<TallyDetailUi?>(null) }
    // 職員別: perStaff[i][k] = スタッフ i がシフト k を担当した回数
    val perStaff = remember(ui.schedule, k) {
        Array(s) { i -> IntArray(k).also { c -> ui.schedule[i].forEach { v -> if (v in 0 until k) c[v]++ } } }
    }
    // 日別: perDay[j][k] = 日 j にシフト k へ配置された人数
    val perDay = remember(ui.schedule, k, t) {
        Array(t) { j -> IntArray(k).also { c -> for (i in 0 until s) { val v = ui.schedule[i].getOrNull(j) ?: -1; if (v in 0 until k) c[v]++ } } }
    }
    // 違反ハイライト色（Excel版の色分けに対応）: 不足=赤 / 過剰=橙。
    // 職員別は countViolations["i,k"](vio-low/vio-high=人数範囲)、日別は needViolations["k,j"](vio-covU/vio-covO=被覆)で判定。
    // [M6統一] 不足=vioColor(ユーザー設定色に連動・既定 赤)、超過=橙。グリッド/ヒートバーと同じ2色言語。
    val critC = ui.violationColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: Color(0xFFEF4444)
    // [M2] 塗り飽和度を上げ暗テーマ・屋外グレアでの視認性を確保（0.30/0.36→0.45/0.50）。
    //   数字は太字化済(第5段)のため濃い塗りでも可読。
    val shortBg = critC.copy(alpha = 0.45f)
    val overBg = MagiAccent.orange.copy(alpha = 0.50f)
    var mode by rememberSaveable { mutableStateOf(0) }   // 0=職員別 / 1=日別
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("シフト集計", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            MagiSegmentedControl(options = listOf("職員別", "日別"), selected = mode, onSelect = { mode = it })
            Spacer(Modifier.height(12.dp))
            if (mode == 0) {
                Text("各職員が対象期間に各シフトを担当した回数（左右にスクロール）",
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                TallyLegend(shortBg, overBg, "回数が下限未満", "上限超過")
                Spacer(Modifier.height(8.dp))
                val labW = 100.dp; val cw = 40.dp; val rh = 34.dp
                Row {
                    Column {
                        TallyBox(labW, rh, cs.surfaceVariant, false) {
                            Text("職員", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                        for (i in 0 until s) TallyBox(labW, rh, cs.surfaceVariant, true) {
                            val nm = ui.staffNames.getOrNull(i) ?: "$i"
                            val gp = ui.staffGroupSymbols.getOrNull(i) ?: ""
                            Text(if (gp.isBlank()) nm else "$nm·$gp", style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // [D1] 期間合計の見出し（勤務表グリッドの「シフト別の合計」をここへ一本化）。
                        TallyBox(labW, rh, cs.surfaceVariant, true) {
                            Text("計（期間）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (kk in 0 until k) Column {
                            val bg = tallyHex(ui.shiftColorHex.getOrNull(kk)) ?: cs.surfaceVariant
                            val fg = tallyHex(ui.shiftTextHex.getOrNull(kk)) ?: cs.onSurfaceVariant
                            TallyBox(cw, rh, bg, false) {
                                Text(ui.shiftSymbols[kk], style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
                            }
                            for (i in 0 until s) {
                                val v = perStaff[i][kk]
                                val vio = ui.countViolations["$i,$kk"]
                                val cbg = when (vio) { "vio-low", "vio-aptLow" -> shortBg; "vio-high", "vio-aptHigh" -> overBg; else -> if (v == 0) cs.surface else cs.surfaceVariant }
                                // [M3 色覚安全] 不足=▼ / 超過=▲ を数字に前置＝色に依らず方向が判る（色覚多様性・モノクロ印刷対応）。
                                val glyph = when (vio) { "vio-low", "vio-aptLow" -> "▼"; "vio-high", "vio-aptHigh" -> "▲"; else -> "" }
                                TallyBox(cw, rh, cbg, false, onClick = if (vio != null) ({ detail = staffViolDetail(vm, ui, i, kk, v, vio) }) else null) {
                                    if (v != 0 || vio != null) Text("$glyph$v", style = MaterialTheme.typography.bodySmall, color = cs.onSurface, fontWeight = if (vio != null) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                }
                            }
                            // [D1] シフト別の期間合計（列合計）。グリッドの重複行を廃止しここへ集約。
                            TallyBox(cw, rh, cs.surfaceVariant, false) {
                                Text("${(0 until s).sumOf { perStaff[it][kk] }}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                            }
                        }
                    }
                }
            } else {
                Text("各日に各シフトへ配置されている人数（左右にスクロール）",
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                TallyLegend(shortBg, overBg, "人員不足", "人員過剰")
                Spacer(Modifier.height(8.dp))
                val labW = 84.dp; val cw = 34.dp; val rh = 34.dp
                Row {
                    Column {
                        TallyBox(labW, rh, cs.surfaceVariant, false) {
                            Text("シフト", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                        for (kk in 0 until k) {
                            val bg = tallyHex(ui.shiftColorHex.getOrNull(kk)) ?: cs.surfaceVariant
                            val fg = tallyHex(ui.shiftTextHex.getOrNull(kk)) ?: cs.onSurfaceVariant
                            TallyBox(labW, rh, bg, true) {
                                Text(ui.shiftSymbols[kk], style = MaterialTheme.typography.bodySmall, color = fg, maxLines = 1)
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (j in 0 until t) Column {
                            TallyBox(cw, rh, cs.surfaceVariant, false) {
                                Text("${j + 1}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
                            }
                            for (kk in 0 until k) {
                                val v = perDay[j][kk]
                                val vio = ui.needViolations["$kk,$j"]
                                val cbg = when (vio) { "vio-covU" -> shortBg; "vio-covO" -> overBg; else -> if (v == 0) cs.surface else cs.surfaceVariant }
                                // [M3 色覚安全] 人員不足=▼ / 過剰=▲ を数字に前置。色に依らず方向が判る。
                                val glyph = when (vio) { "vio-covU" -> "▼"; "vio-covO" -> "▲"; else -> "" }
                                TallyBox(cw, rh, cbg, false, onClick = if (vio != null) ({ detail = dayViolDetail(vm, ui, kk, j, v, vio) }) else null) {
                                    if (v != 0 || vio != null) Text("$glyph$v", style = MaterialTheme.typography.bodySmall, color = cs.onSurface, fontWeight = if (vio != null) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            detail?.let { d ->
                AlertDialog(
                    onDismissRequest = { detail = null },
                    title = { Text(d.title) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            d.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    },
                    confirmButton = {
                        DialogConfirmButton("直し方を探す", onClick = { val f = d.focus; val sh = d.shift; detail = null; onFix(f, sh) })
                    },
                    dismissButton = { DialogDismissButton(onClick = { detail = null }, text = "閉じる") },
                )
            }
        }
    }
}

/** [直せる導線] 集計セルの違反詳細。focus=直す対象スタッフ(日別はnull=全体探索)。 */
private data class TallyDetailUi(val title: String, val lines: List<String>, val focus: Int?, val shift: Int? = null)

/** 職員別セル(i,k): 現在回数と 下限/上限/目標 の差を数字で。 */
private fun staffViolDetail(vm: MagiViewModel, ui: UiState, i: Int, k: Int, count: Int, vio: String): TallyDetailUi {
    val (lo, hi, apt) = vm.staffCellLimits(i, k)
    val name = ui.staffNames.getOrNull(i) ?: "$i"
    val sym = ui.shiftSymbols.getOrNull(k) ?: "$k"
    val lines = ArrayList<String>()
    lines += "現在 ${count}回"
    when (vio) {
        "vio-low" -> if (lo != null) lines += "下限 ${lo}回 → ${(lo - count).coerceAtLeast(0)}回 不足"
        "vio-high" -> if (hi != null) lines += "上限 ${hi}回 → ${(count - hi).coerceAtLeast(0)}回 超過"
        "vio-aptLow" -> if (apt != null) lines += "目標 ${apt}回 → ${(apt - count).coerceAtLeast(0)}回 不足"
        "vio-aptHigh" -> if (apt != null) lines += "目標 ${apt}回 → ${(count - apt).coerceAtLeast(0)}回 超過"
    }
    return TallyDetailUi("$name ・ $sym", lines, i, k)
}

/** 日別セル(k,j): 現在人数と 必要数レンジ の差を数字で。 */
private fun dayViolDetail(vm: MagiViewModel, ui: UiState, k: Int, j: Int, count: Int, vio: String): TallyDetailUi {
    val limits = vm.needCellLimits(k, j)
    val sym = ui.shiftSymbols.getOrNull(k) ?: "$k"
    val lines = ArrayList<String>()
    lines += "現在 ${count}人"
    if (limits != null) {
        val (lo, hi) = limits
        when (vio) {
            "vio-covU" -> lines += "必要 ${lo}人 → ${(lo - count).coerceAtLeast(0)}人 不足"
            "vio-covO" -> lines += "適正 ${hi}人 → ${(count - hi).coerceAtLeast(0)}人 過剰"
        }
    }
    return TallyDetailUi("$sym ・ ${j + 1}日", lines, null, k)
}

private fun tallyHex(hex: String?): Color? = if (hex.isNullOrBlank()) null else hexToColor(hex)

/** シフト集計の違反ハイライト凡例（不足=赤 / 過剰=橙）。 */
@Composable
private fun TallyLegend(shortBg: Color, overBg: Color, shortLabel: String, overLabel: String) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(13.dp).background(shortBg, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(4.dp))
        Text("▼ $shortLabel", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(13.dp).background(overBg, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(4.dp))
        Text("▲ $overLabel", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun TallyBox(
    w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, bg: Color, start: Boolean,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(Modifier.width(w).height(h).padding(1.dp)) {
        Box(
            Modifier.fillMaxSize().background(bg, RoundedCornerShape(8.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .then(if (start) Modifier.padding(horizontal = 6.dp) else Modifier),
            contentAlignment = if (start) Alignment.CenterStart else Alignment.Center,
        ) { content() }
    }
}

// ===== 集中モード（HUD円柱フィッシュアイ・カレンダー）=====
// MAGI HUD コンセプトから移植：日付を円柱面に投影し焦点日を虫眼鏡状に拡大。
// x(u)=RAD*sin(clamp(u*ANG))+AMP*tanh(u/WID) / 明度=0.64+0.36*cos。遠い日ほど細く暗い。
// 違反枠(HARD実線/SOFT破線)・違反ドット・希望ドット(緑=反映/桃=未反映)は既存ロジックを流用。
@Composable
internal fun MagiFocusCylinder(ui: UiState, onCellClick: (Int, Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val days = ui.days.coerceAtLeast(1)
    val staffCount = ui.schedule.size
    val vioColor = ui.violationColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: cs.error
    val tm = rememberTextMeasurer()
    var savedDay by rememberSaveable { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val rot = remember { Animatable(savedDay.coerceIn(0, days - 1).toFloat()) }
    LaunchedEffect(days) { rot.updateBounds(0f, (days - 1).coerceAtLeast(0).toFloat()) }
    // [perf] 回転値の整数日のみ購読。アニメ中の毎フレーム再コンポーズを防ぎ、日が変わる時だけラベル/ボタンを更新（描画は別途rot.valueで毎フレーム）。
    val td by remember(days) { derivedStateOf { rot.value.roundToInt().coerceIn(0, days - 1) } }
    fun goToDay(d: Int) {
        val t = d.coerceIn(0, days - 1)
        savedDay = t
        scope.launch { rot.animateTo(t.toFloat(), tween(320)) }
    }

    val ANG = (7.7 * kotlin.math.PI / 180.0).toFloat()
    val RAD = 205f; val AMP = 5.8f; val WID = 0.35f
    val HALF = (kotlin.math.PI / 2.0).toFloat()
    fun cl(x: Float) = if (x < -HALF) -HALF else if (x > HALF) HALF else x
    fun sx(u: Float) = RAD * kotlin.math.sin(cl(u * ANG)) + AMP * kotlin.math.tanh(u / WID)
    fun br(u: Float) = 0.64f + 0.36f * kotlin.math.cos(cl(u * ANG))

    val dens = androidx.compose.ui.platform.LocalDensity.current
    val rowHpx = with(dens) { 40.dp.toPx() }
    val headHpx = with(dens) { 26.dp.toPx() }
    val nameWpx = with(dens) { 60.dp.toPx() }
    val scale = dens.density
    val totalH = with(dens) { (headHpx + rowHpx * staffCount).toDp() }
    // [perf] テキストは事前計測してキャッシュ（描画ごとの measure を避け、回転アニメのガタつきを防ぐ）。
    val todayIdx = remember(ui.startDate, days) {
        runCatching {
            val off = (LocalDate.now().toEpochDay() - LocalDate.parse(ui.startDate).toEpochDay()).toInt()
            if (off in 0 until days) off else -1
        }.getOrDefault(-1)
    }
    val dayLayouts = remember(days, cs.onSurfaceVariant, ui.startDate, todayIdx) {
        val sdow = startDowMonFirst(ui.startDate)
        (0 until days).map { d ->
            val dow = (sdow + d) % 7  // 0=月..6=日
            val col = when { d == todayIdx -> MagiAccent.green; dow == 5 -> MagiAccent.blue; dow == 6 -> MagiAccent.red; else -> cs.onSurfaceVariant }
            tm.measure((d + 1).toString(), TextStyle(fontSize = 9.sp, color = col))
        }
    }
    val symLayouts = remember(ui.shiftSymbols, ui.shiftTextHex) {
        ui.shiftSymbols.indices.map { k ->
            tm.measure(ui.shiftSymbols.getOrNull(k) ?: "", TextStyle(fontSize = 13.sp, color = hexToColor(ui.shiftTextHex.getOrNull(k) ?: "")))
        }
    }
    val nameLayouts = remember(ui.staffNames, cs.onSurface) {
        ui.staffNames.map { tm.measure(it, TextStyle(fontSize = 11.sp, color = cs.onSurface), maxLines = 1) }
    }
    val dragStepPx = with(dens) { 40.dp.toPx() }
    if (staffCount == 0) { Text("勤務表データがありません。", color = cs.onSurfaceVariant); return }

    // [融合] 7日/カレンダーの要点を集中へ取り込む: 日別の出勤人数・不足数、曜日、日ジャンプ帯のスクロール。
    val restIdx = ui.shiftSymbols.indexOfFirst { it == "休" || it == "公" }
    val dayPresent = remember(ui.schedule, days, restIdx) {
        IntArray(days) { d -> ui.schedule.count { val k = it.getOrNull(d) ?: -1; k >= 0 && k != restIdx } }
    }
    val dayShort = remember(ui.v6, days) {
        IntArray(days) { d -> ui.v6?.dayRisks?.getOrNull(d)?.shortage ?: 0 }
    }
    val jumpDow = startDowMonFirst(ui.startDate)
    val weekdayJa = listOf("月", "火", "水", "木", "金", "土", "日")
    val jumpScroll = rememberScrollState()
    val chipStepPx = with(dens) { 40.dp.toPx() } // 実ステップ=チップ幅36dp＋間隔4dp（38dpのままだと月末へ向け追従がズレる）
    LaunchedEffect(td) { jumpScroll.animateScrollTo(((td - 2).coerceAtLeast(0) * chipStepPx).toInt()) }

    // [perf] フレーム毎の重い処理を事前計算で排除（色文字列パース / "i,d"文字列キーのMap探索 / sin・cos・tanh）。
    val shiftColorsC = remember(ui.shiftColorHex) { ui.shiftColorHex.map { hexToColor(it) } }
    val pinkC = Color(0xFFEC4899)
    // [M1 校正] 細線で埋もれる問題を直接解消。HARD枠 2→3dp・SOFT破線 2→2.5dp・中空リング 1.5→2dp。
    val vioStrokePx = with(dens) { 2.dp.toPx() }
    val hardStrokePx = with(dens) { 3.dp.toPx() }
    val softStrokePx = with(dens) { 2.5f.dp.toPx() }
    val vioDotR = with(dens) { 3.dp.toPx() }
    val hardStroke = remember(hardStrokePx) { Stroke(width = hardStrokePx) }
    val softStroke = remember(softStrokePx) { Stroke(width = softStrokePx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(softStrokePx * 2.4f, softStrokePx * 1.6f))) }
    val thinStroke = remember(vioStrokePx) { Stroke(width = vioStrokePx) }
    // [M6 統一] SOFT/要調整=橙・HARD=vioColor(赤系)。3表＋ヒートバー共通の2色言語（赤=必須/不足, 橙=要調整/超過）。
    val vioSoftColor = MagiAccent.orange
    // 違反/希望はセル配列へ展開（0=なし、違反:1=HARD/2=SOFT、希望:1=一致/2=不一致）。
    val vioKind = remember(ui.violationCells, staffCount, days) {
        Array(staffCount) { i -> IntArray(days) { d ->
            val v = ui.violationCells["$i,$d"]
            if (v == null) 0 else if (isHardCellViolation(v)) 1 else 2
        } }
    }
    val wishKind = remember(ui.wishes, ui.schedule, staffCount, days) {
        Array(staffCount) { i -> IntArray(days) { d ->
            val wk = ui.wishes["$i,$d"]
            if (wk == null) 0 else { val k = ui.schedule.getOrNull(i)?.getOrNull(d) ?: -1; if (wk == k) 1 else 2 }
        } }
    }
    // [違反可視化] 日別の違反件数（ヒートバー・日付帯の点・ヘッダ表示用）。
    val dayVioH = remember(ui.violationCells, staffCount, days) { IntArray(days) { d -> (0 until staffCount).count { i -> vioKind[i][d] == 1 } } }
    val dayVioS = remember(ui.violationCells, staffCount, days) { IntArray(days) { d -> (0 until staffCount).count { i -> vioKind[i][d] == 2 } } }
    // 円柱投影(sx)・列幅・明るさを u の関数として事前計算しLUT化。描画は配列参照のみ（三角関数を毎フレーム呼ばない）。
    val uMax = 14f
    val lutStep = 0.02f
    val lutN = ((2f * uMax) / lutStep).toInt() + 1
    fun lerpLut(a: FloatArray, u: Float): Float { val f = (u + uMax) / lutStep; val i = f.toInt(); return if (i < 0) a[0] else if (i >= lutN - 1) a[lutN - 1] else a[i] + (a[i + 1] - a[i]) * (f - i) }
    // [fit] 画面幅に応じて円柱を横に収める係数（設計値を超える拡大はせず、狭い端末でのみ縮めて端の日の見切れを防ぐ）。RAD_eff = RAD*fit 相当。
    fun fitFactor(widthPx: Float): Float {
        val halfAvail = (widthPx - nameWpx) / 2f
        val designExtent = (RAD + AMP) * scale
        val marginPx = 8f * scale
        return if (designExtent > halfAvail - marginPx && designExtent > 0.01f) ((halfAvail - marginPx) / designExtent).coerceIn(0.2f, 1f) else 1f
    }
    // 投影sx・列幅に fit(縮小係数)をあらかじめ畳み込み、描画ループの *fit と sx() 呼び出しを排除。
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // [見た目] 合成時に幅(constraints.maxWidth)が確定するので初回フレームから fit を正しく適用し、原寸→縮小の1フレームちらつきを防止。fit/LUTは幅・回転変化時のみ再計算。
    val fit0 = if (constraints.maxWidth > 0) fitFactor(constraints.maxWidth.toFloat()) else 1f
    // [中央列幅] フォーカス日(中央)の列幅を 38..40dp（密度スケール）に収める。fit を比率調整し、円柱の形状は保ったまま中央列を目標幅へスケール。
    val centerW0 = (sx(0.5f) - sx(-0.5f)) * scale * fit0
    val cwMin = with(dens) { 38.dp.toPx() }
    val cwMax = with(dens) { 40.dp.toPx() }
    val fit = if (centerW0 > 0.01f) fit0 * (centerW0.coerceIn(cwMin, cwMax) / centerW0) else fit0
    val lutSx = remember(scale, fit) { FloatArray(lutN) { sx(-uMax + it * lutStep) * scale * fit } }
    val lutW = remember(scale, fit) { FloatArray(lutN) { (sx(-uMax + it * lutStep + 0.5f) - sx(-uMax + it * lutStep - 0.5f)) * scale * fit } }
    val lutBr = remember(scale) { FloatArray(lutN) { br(-uMax + it * lutStep) } }
    // [境界パン] 焦点日を常に中央へ置くと境界日(1日/末日)で片側が空き、上部の全幅日付帯とも列が合わない。
    //   存在する端の日がグリッド枠端に来るよう水平パンして空白を詰める（中央域は pan=0＝従来の中央拡大を維持）。
    //   描画とタップ判定の両方で同一値を使う（片方だけだと日の当たり判定がズレる）。
    fun panXAt(r0: Float, widthPx: Float): Float {
        val cX = nameWpx + (widthPx - nameWpx) / 2f
        val uL = (0f - r0).coerceAtLeast(-13f)
        val uR = (days - 1 - r0).coerceAtMost(13f)
        val leftEdge = cX + lerpLut(lutSx, uL) - lerpLut(lutW, uL) / 2f
        val rightEdge = cX + lerpLut(lutSx, uR) + lerpLut(lutW, uR) / 2f
        return when {
            leftEdge > nameWpx -> nameWpx - leftEdge
            rightEdge < widthPx -> widthPx - rightEdge
            else -> 0f
        }
    }

    Column {
        Text(
            "\u2299 集中モード：横スワイプで回転→最寄りの日に吸着。日付帯で任意の日へジャンプ（不足日は赤枠＋不足数）。ヘッダに出勤人数・不足・違反を表示。上の細バーは月全体の違反マップ（濃赤=必須/淡赤=要調整、タップで移動）。日付帯の赤点=違反のある日。中央の日のセルをタップで修正。土=青/日=赤/本日=緑。希望は左下ドット（反映済=青緑リング/未反映=桃塗り）。",
            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { goToDay(td - 1) }, enabled = td > 0, modifier = Modifier.height(48.dp)) { Text("\u25c0 前日") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${td + 1}日（${weekdayJa[(jumpDow + td) % 7]}） / 全${days}日", style = MaterialTheme.typography.titleSmall)
                Text(
                    "出勤 ${dayPresent[td]}人" + (if (dayShort[td] > 0) "  ・  不足 ${dayShort[td]}" else "") + (if (dayVioH[td] + dayVioS[td] > 0) "  ・  違反 ${dayVioH[td] + dayVioS[td]}" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dayShort[td] > 0 || dayVioH[td] + dayVioS[td] > 0) cs.error else cs.onSurfaceVariant,
                )
            }
            Button(onClick = { goToDay(td + 1) }, enabled = td < days - 1, modifier = Modifier.height(48.dp)) { Text("翌日 \u25b6") }
        }
        // [違反可視化: 全月ヒートバー] 31日を常時一目。濃赤=必須(HARD)違反のある日、淡赤=要調整(SOFT)のみ、枠=選択日。タップでその日へ移動。
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .pointerInput(days) { detectTapGestures { off -> goToDay((off.x / (size.width / days.toFloat())).toInt().coerceIn(0, days - 1)) } }
                .semantics { contentDescription = "違反ヒートバー。濃い赤は必須違反のある日、薄い赤は要調整のみの日。タップした位置の日へ移動します。" }
        ) {
            val segW = size.width / days
            val gap = kotlin.math.min(1.dp.toPx(), segW * 0.15f)
            drawRoundRect(cs.surfaceVariant.copy(alpha = 0.45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f))
            for (d in 0 until days) {
                val hc = when { dayVioH[d] > 0 -> vioColor; dayVioS[d] > 0 -> vioSoftColor; else -> null }
                if (hc != null) drawRect(hc, topLeft = Offset(d * segW + gap / 2f, 0f), size = Size(segW - gap, size.height))
            }
            drawRoundRect(cs.onSurface.copy(alpha = 0.85f), topLeft = Offset(td * segW, 0f), size = Size(segW, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()), style = Stroke(1.dp.toPx()))
        }
        // [融合: 日ジャンプ＋不足マーカー] 任意の日へ即移動。土=青/日=赤/本日=緑、不足日は赤枠＋不足数、選択日を強調。
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(jumpScroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in 0 until days) {
                val dow = (jumpDow + d) % 7
                val dcol = when { d == todayIdx -> MagiAccent.green; dow == 5 -> MagiAccent.blue; dow == 6 -> MagiAccent.red; else -> cs.onSurfaceVariant }
                val sel = d == td
                val sh = dayShort[d]
                Column(
                    Modifier
                        .background(if (sel) cs.primaryContainer else Color.Transparent, MaterialTheme.shapes.small)
                        .then(if (sh > 0) Modifier.border(1.dp, cs.error, MaterialTheme.shapes.small) else Modifier)
                        .clickable { goToDay(d) }
                        .width(36.dp)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${d + 1}", fontSize = 12.sp, color = if (sel) cs.onPrimaryContainer else dcol, fontWeight = if (d == todayIdx) FontWeight.Bold else FontWeight.Normal)
                    // 違反点: 塗り=必須(HARD)あり / 輪郭=要調整(SOFT)のみ（不足の赤枠とは独立）
                    if (dayVioH[d] > 0) Box(Modifier.size(6.dp).background(vioColor, RoundedCornerShape(50)))
                    else if (dayVioS[d] > 0) Box(Modifier.size(6.dp).border(1.5.dp, vioSoftColor, RoundedCornerShape(50)))
                    else Spacer(Modifier.height(6.dp))
                    if (sh > 0) Text("不足$sh", fontSize = 8.sp, color = cs.error, maxLines = 1)
                    else Spacer(Modifier.height(2.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(totalH)
                .semantics { contentDescription = "集中カレンダー。${td + 1}日が中央。横スワイプで回転（指を離すと慣性で最寄りの日に吸着）、中央の日のセルをタップで修正画面を開きます。" }
                .pointerInput(days, staffCount) {
                    detectTapGestures { off ->
                        val cur = rot.value.roundToInt().coerceIn(0, days - 1)
                        val centerX = nameWpx + (size.width.toFloat() - nameWpx) / 2f
                        val pan = panXAt(rot.value, size.width.toFloat())   // [境界パン] 描画と同一のパンで当たり判定
                        var best = cur; var bd = Float.MAX_VALUE
                        for (d in 0 until days) {
                            val dd = kotlin.math.abs((centerX + pan + lerpLut(lutSx, d - rot.value)) - off.x)
                            if (dd < bd) { bd = dd; best = d }
                        }
                        val i = ((off.y - headHpx) / rowHpx).toInt()
                        // 中央(選択中)の日のセルをタップ → 修正画面。側面の日をタップ → その日を中央へ回す。
                        if (best == cur && i in 0 until staffCount) onCellClick(i, best) else goToDay(best)
                    }
                }
                .pointerInput(days) {
                    val decay = splineBasedDecay<Float>(this)
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking(); scope.launch { rot.stop() } },
                        onDragEnd = {
                            val vx = tracker.calculateVelocity().x
                            scope.launch {
                                // 慣性で流す → 最寄りの日に吸着（dragStepPx を1日ぶんの感度に流用）
                                if (kotlin.math.abs(vx) > 1f) rot.animateDecay(-vx / dragStepPx, decay)
                                val n = rot.value.roundToInt().coerceIn(0, days - 1)
                                rot.animateTo(n.toFloat(), tween(180)); savedDay = n
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                val n = rot.value.roundToInt().coerceIn(0, days - 1)
                                rot.animateTo(n.toFloat(), tween(180)); savedDay = n
                            }
                        }
                    ) { change, amt ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { rot.snapTo((rot.value - amt / dragStepPx).coerceIn(0f, (days - 1).coerceAtLeast(0).toFloat())) }
                    }
                }
        ) {
            val centerX = nameWpx + (size.width - nameWpx) / 2f
            val surfaceC = cs.surface
            val cr = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            val ch = rowHpx - 2f
            val r0 = rot.value
            val panX = panXAt(r0, size.width)   // [境界パン] 境界日で空白を詰める（中央域は0）
            for (d in 0 until days) {
                val u = d - r0
                if (u < -13f || u > 13f) continue
                val w = lerpLut(lutW, u)
                if (w < 0.7f) continue
                val cx = centerX + panX + lerpLut(lutSx, u)
                val bri = lerpLut(lutBr, u)
                val left = cx - w / 2f
                val rectW = maxOf(1f, w - 1f)
                if (w > 13f) {
                    dayLayouts.getOrNull(d)?.let { r ->
                        val ty = headHpx / 2f - r.size.height / 2f
                        drawText(r, topLeft = Offset(cx - r.size.width / 2f, ty))
                        if (d == todayIdx) drawRoundRect(MagiAccent.green, topLeft = Offset(cx - 7f, ty + r.size.height + 1f), size = Size(14f, 2.5f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
                    }
                }
                for (i in 0 until staffCount) {
                    val k = ui.schedule.getOrNull(i)?.getOrNull(d) ?: -1
                    val top = headHpx + i * rowHpx
                    val base = if (k < 0) cs.surfaceVariant else (shiftColorsC.getOrNull(k) ?: cs.surfaceVariant)
                    drawRoundRect(color = dimColor(base, bri, surfaceC), topLeft = Offset(left, top + 1f), size = Size(rectW, ch), cornerRadius = cr)
                    if (w > 22f && k >= 0) {
                        symLayouts.getOrNull(k)?.let { r ->
                            drawText(r, topLeft = Offset(cx - r.size.width / 2f, top + ch / 2f - r.size.height / 2f))
                        }
                    }
                    val vk = vioKind[i][d]
                    if (vk != 0) {
                        val hard = vk == 1
                        val vcol = if (hard) vioColor else vioSoftColor   // [M6統一] HARD=赤 / SOFT=橙
                        drawRoundRect(color = vcol, topLeft = Offset(left, top + 1f), size = Size(rectW, ch), cornerRadius = cr, style = if (hard) hardStroke else softStroke)
                        if (w > vioDotR * 4f) {
                            val c = Offset(left + w - (vioDotR + 3f), top + vioDotR + 3f)
                            if (hard) drawCircle(vcol, vioDotR, c) else { drawCircle(cs.surface, vioDotR, c); drawCircle(vcol, vioDotR, c, style = thinStroke) }
                        }
                    }
                    val wk = wishKind[i][d]
                    if (wk != 0 && w > vioDotR * 4f) {
                        val wc = Offset(left + vioDotR + 3f, top + ch - (vioDotR + 3f))
                        // [色覚安全] 一致/不一致を色(teal/pink)だけでなく形でも区別（第2色覚で teal/pink は同化する）。
                        //   未反映(要対応)=塗り＝目立つ / 反映済(満足)=中空リング＝控えめ。満足済みの視覚ノイズも低減。
                        if (wk == 2) drawCircle(pinkC, vioDotR, wc)
                        else { drawCircle(cs.surface, vioDotR, wc); drawCircle(cs.tertiary, vioDotR, wc, style = thinStroke) }
                    }
                }
            }
            for (i in 0 until staffCount) {
                val top = headHpx + i * rowHpx
                drawRect(cs.surface, topLeft = Offset(0f, top), size = Size(maxOf(1f, nameWpx - 2f), rowHpx))
                nameLayouts.getOrNull(i)?.let { r ->
                    drawText(r, topLeft = Offset(4f, top + rowHpx / 2f - r.size.height / 2f))
                }
            }
        }
    }
    }
}

private fun dimColor(c: Color, b: Float, bg: Color): Color {
    val t = if (b < 0f) 0f else if (b > 1f) 1f else b
    // 遠い日ほど背景色へブレンド(大気遠近)。明背景では淡く、暗背景では暗側へ溶け込み、両モードで奥行きが出る。
    return Color(
        red = bg.red + (c.red - bg.red) * t,
        green = bg.green + (c.green - bg.green) * t,
        blue = bg.blue + (c.blue - bg.blue) * t,
        alpha = c.alpha,
    )
}
