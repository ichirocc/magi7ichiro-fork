package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ============ 希望/必要人数カレンダーで共有するレイアウト部品（スクショ準拠） ============

/** 開始日(ISO)から「YYYY年M月」。パース失敗は空。 */
internal fun monthLabel(startDate: String): String = try {
    val d = java.time.LocalDate.parse(startDate); "${d.year}年${d.monthValue}月"
} catch (_: Exception) { "" }

/** 開始日(ISO)＋期間内の1始まり日番号 → 「M/D(曜)」。月跨ぎも正しく算出。 */
internal fun dayChipLabel(startDate: String, day1: Int): String = try {
    val d = java.time.LocalDate.parse(startDate).plusDays((day1 - 1).toLong())
    "${d.monthValue}/${d.dayOfMonth}(${listOf("月", "火", "水", "木", "金", "土", "日")[d.dayOfWeek.value - 1]})"
} catch (_: Exception) { "${day1}日" }

/** [静的な月見出し] 「‹ 2025年6月 ›」。矢印は淡色の飾り＝月送りは無し（D6決定=1state=1か月）。 */
@Composable
internal fun MonthHeaderStatic(startDate: String) {
    val cs = MaterialTheme.colorScheme
    val label = monthLabel(startDate)
    if (label.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("‹", style = MaterialTheme.typography.titleMedium, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = 12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = cs.primary, fontWeight = FontWeight.Bold)
        Text("›", style = MaterialTheme.typography.titleMedium, color = cs.outlineVariant, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** ドロップダウンの見た目のアンカー（ラベル＋値＋▼）。DropdownMenu を兄弟に置いて開閉する。 */
@Composable
internal fun SelectorField(label: String, value: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().border(1.dp, cs.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
            Text("▼", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** 「N日選択中」ピル。 */
@Composable
internal fun CountPill(text: String) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/** 「複数日選択 ・ N日選択中 ›」オープナー（タップでボトムシート）。0件時は操作ヒントのみ。 */
@Composable
internal fun MultiSelectOpener(count: Int, onOpen: () -> Unit, onClear: () -> Unit, running: Boolean) {
    val cs = MaterialTheme.colorScheme
    if (count == 0) {
        Text("日をタップして複数選択できます", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = cs.primaryContainer, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).clickable(enabled = !running, onClick = onOpen),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("複数日選択 ・ ${count}日選択中", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
                    Text("›", style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                }
            }
            TextButton(onClick = onClear, enabled = !running) { Text("クリア") }
        }
    }
}

/**
 * [必要人数カレンダー] シフトを1つ選び、月全体の必要人数を見渡し・その場で編集する主画面。
 * [スクショ準拠のレイアウト取り入れ] シフトは**ドロップダウン**、基本の必要人数は**「基本設定」カード**
 * （タップでボトムシート編集）、日をタップして複数選択→「複数日選択 N日選択中 ›」から**モーダルボトムシート**で
 * 一括編集。ボトムシートに**「未設定に戻す」**（選択日の例外を一括削除＝既定へ戻す）を追加。月送りは無し
 * （D6=1state=1か月）。日セルは実際の勤務表充足度で色分け（緑=充足/橙=過剰covO/赤=不足covU/灰=未設定）。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NeedCalendarCard(ui: UiState, vm: MagiViewModel) {
    val v = vm.ws1() ?: return
    if (v.shifts.isEmpty()) return
    var k by remember { mutableStateOf(0) }
    if (k !in v.shifts.indices) k = 0
    val shift = v.shifts[k]
    val cs = MaterialTheme.colorScheme
    var daysSel by remember(k) { mutableStateOf(emptySet<Int>()) }
    var shiftMenu by remember { mutableStateOf(false) }
    var baseSheet by remember { mutableStateOf(false) }
    var applySheet by remember { mutableStateOf(false) }
    val onToggleDay: (Int) -> Unit = { d -> daysSel = if (d in daysSel) daysSel - d else daysSel + d }
    val cells = (0 until ui.days).map { j -> vm.needCellLimits(k, j) to ui.needViolations["$k,$j"] }
    val setCount = cells.count { it.first != null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("必要人数カレンダー", style = MaterialTheme.typography.titleMedium)
            Text(
                "シフトを選び、日をタップして必要人数を一括登録できます。色は現在の勤務表の充足状況です。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    SelectorField(label = "シフト", value = shift.kigou, onClick = { shiftMenu = true })
                    DropdownMenu(expanded = shiftMenu, onDismissRequest = { shiftMenu = false }) {
                        v.shifts.forEachIndexed { idx, s ->
                            DropdownMenuItem(text = { Text(s.kigou) }, onClick = { k = idx; daysSel = emptySet(); shiftMenu = false })
                        }
                    }
                }
                Column(
                    Modifier.weight(1f).border(1.dp, cs.outline, RoundedCornerShape(8.dp))
                        .clickable { baseSheet = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("基本設定", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Text("最低 ${shift.need1.ifBlank { "—" }} / 上限 ${shift.need2.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
            Text(
                "設定済 ${setCount}日 / 未設定 ${ui.days - setCount}日",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
            )
            MonthHeaderStatic(ui.startDate)
            NeedMonthGrid(startDate = ui.startDate, cells = cells, selectedDays = daysSel, onToggle = onToggleDay)
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot(MagiAccent.green, "充足")
                LegendDot(MagiAccent.orange, "過剰")
                LegendDot(MagiAccent.red, "不足")
                LegendDot(cs.outlineVariant, "未設定")
            }
            MultiSelectOpener(count = daysSel.size, onOpen = { applySheet = true }, onClear = { daysSel = emptySet() }, running = ui.running)
        }
    }
    if (baseSheet) {
        BaseNeedSheet(shift.kigou, shift.need1, shift.need2, running = ui.running,
            onApply = { p1, p2 -> vm.setShiftNeed(k, p1, p2) }, onDismiss = { baseSheet = false })
    }
    if (applySheet && daysSel.isNotEmpty()) {
        NeedApplySheet(ui, vm, k, daysSel, shift.need1, shift.need2,
            onDone = { daysSel = emptySet(); applySheet = false }, onDismiss = { applySheet = false })
    }
}

/** 基本の必要人数（シフト既定need1/need2）編集シート。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseNeedSheet(kigou: String, need1: String, need2: String, running: Boolean, onApply: (String, String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var p1 by remember { mutableStateOf(need1) }
    var p2 by remember { mutableStateOf(need2) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("基本の必要人数（${kigou}の既定値）", style = MaterialTheme.typography.titleMedium)
            NumberStepper("最低人数", p1, { p1 = it }, min = 0, blankLabel = "未設定")
            NumberStepper("上限人数", p2, { p2 = it }, min = 0, blankLabel = "未設定")
            Button(onClick = { onApply(p1, p2); onDismiss() }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存") }
        }
    }
}

/** 選択日の必要人数（日別例外）を一括編集するシート。「未設定に戻す」で例外を削除＝既定へ。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeedApplySheet(ui: UiState, vm: MagiViewModel, k: Int, days: Set<Int>, baseN1: String, baseN2: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    var p1 by remember { mutableStateOf(baseN1) }
    var p2 by remember { mutableStateOf(baseN2) }
    val sorted = days.sorted()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountPill("${days.size}日選択中")
                Text("選択した日付の設定を変更", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(sorted.joinToString("・") { dayChipLabel(ui.startDate, it) }, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            NumberStepper("最低人数", p1, { p1 = it }, min = 0, blankLabel = "既定")
            NumberStepper("上限人数", p2, { p2 = it }, min = 0, blankLabel = "既定")
            Text("基本の必要人数：最低 ${baseN1.ifBlank { "未設定" }}人 〜 上限 ${baseN2.ifBlank { "未設定" }}人",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { days.forEach { d -> vm.removeNeedDay(k, d - 1) }; onDone() },
                    enabled = !ui.running,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("未設定に戻す") }
                Button(
                    onClick = { days.forEach { d -> vm.setNeedDay(k, d - 1, p1, p2) }; onDone() },
                    enabled = !ui.running && (p1.isNotBlank() || p2.isNotBlank()),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("${days.size}日に適用") }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [必要人数カレンダーの本体グリッド] 月内の日を曜日整列で並べ、タップで複数日選択できる。
 *  各日は実効need(lo-hi、`vm.needCellLimits`)と現在の勤務表充足度(`ui.needViolations`)から
 *  色分けドットを表示（緑=充足/橙=過剰covO/赤=不足covU/灰=need未設定）。 */
@Composable
private fun NeedMonthGrid(
    startDate: String,
    cells: List<Pair<Pair<Int, Int>?, String?>>,
    selectedDays: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sdow = (startDowMonFirst(startDate) + 1) % 7   // 月曜始まり(0=月)→日曜始まり(0=日)へ変換
    val weekJa = listOf("日", "月", "火", "水", "木", "金", "土")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            weekJa.forEachIndexed { idx, w ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(w, style = MaterialTheme.typography.labelSmall,
                        color = when (idx) { 0 -> MagiAccent.red; 6 -> MagiAccent.blue; else -> cs.onSurfaceVariant })
                }
            }
        }
        val dayCells: List<Int?> = List(sdow) { null } + cells.indices.toList()
        dayCells.chunked(7).forEach { wk ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                wk.forEach { j ->
                    if (j == null) Box(Modifier.weight(1f).height(54.dp))
                    else {
                        val (range, vio) = cells[j]
                        val sel = (j + 1) in selectedDays
                        val dot = when {
                            range == null -> cs.outlineVariant
                            vio == "vio-covU" -> MagiAccent.red
                            vio == "vio-covO" -> MagiAccent.orange
                            else -> MagiAccent.green
                        }
                        Box(
                            Modifier.weight(1f).height(54.dp)
                                .background(if (sel) cs.primaryContainer else cs.surface, RoundedCornerShape(8.dp))
                                .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable { onToggle(j + 1) }
                                .semantics {
                                    contentDescription = "${j + 1}日を" + (if (sel) "選択解除" else "選択") +
                                        (range?.let { "・必要人数${it.first}〜${it.second}人" } ?: "・未設定")
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${j + 1}", style = MaterialTheme.typography.bodyMedium,
                                        color = if (sel) cs.onPrimaryContainer else cs.onSurface,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                    if (sel) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(12.dp))
                                    else Box(Modifier.size(8.dp).background(dot, CircleShape))
                                }
                                Text(
                                    if (range != null) (if (range.first == range.second) "${range.first}人" else "${range.first}-${range.second}人") else "未設定",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                repeat(7 - wk.size) { Box(Modifier.weight(1f).height(54.dp)) }
            }
        }
    }
}

/**
 * ws2 移植: 日別の必要人数（例外）一覧。全シフト横断で例外を一覧確認・削除する専用ビュー
 * （カレンダーは1シフトずつしか見えない弱点を補う）。登録/変更は上の`NeedCalendarCard`へ一本化。
 */
@Composable
fun NeedDayCard(ui: UiState, vm: MagiViewModel) {
    val overrides = vm.needDayOverrides()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("日別の必要人数（例外）一覧", style = MaterialTheme.typography.titleMedium)
            Text(
                "登録・変更は上の必要人数カレンダーから。ここは全シフト横断の一覧確認・削除用です。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (overrides.isEmpty()) {
                Text(
                    "（例外なし — すべて既定値）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                overrides.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${o.kigou}  ${o.j + 1}日  最低 ${o.p1.ifBlank { "-" }}人 / 上限 ${o.p2.ifBlank { "-" }}人",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        DeleteRowButton(onClick = { vm.removeNeedDay(o.k, o.j) }, enabled = !ui.running)
                    }
                }
            }
        }
    }
}

/** 一本指: 数値は「＋ −」の大ボタンで増減（キーボード不要）。空＝既定/なし。同package(StaffRange)と共有。 */
@Composable
internal fun NumberStepper(label: String, value: String, onChange: (String) -> Unit, min: Int, blankLabel: String) {
    val n = value.toIntOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            // [ゼロ設定] 空欄(なし/既定)から「−」で下限値(=0)へ。0で更に「−」を押すと空欄(クリア)へ戻せる。
            onClick = { when { n == null -> onChange(min.toString()); n <= min -> onChange(""); else -> onChange((n - 1).toString()) } },
            modifier = Modifier.height(48.dp).semantics { contentDescription = "$label を減らす" },
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
            Text(if (value.isBlank()) blankLabel else value, style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
            onClick = { onChange(((n ?: (min - 1)) + 1).coerceAtLeast(min).toString()) },
            modifier = Modifier.height(48.dp).semantics { contentDescription = "$label を増やす" },
        ) { Text("＋", style = MaterialTheme.typography.titleLarge) }
    }
}
