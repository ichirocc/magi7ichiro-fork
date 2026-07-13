package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * [必要人数カレンダー] シフトを1つ選び、月全体の実効need（シフト既定＋日別例外を統合済み、
 * `vm.needCellLimits(k,j)`）を一目で見渡す概観。ユーザー提示のモックアップの長所（①シフト種類
 * チップで対象を絞る ②月全体カレンダーに各日の実効値をインライン色分け表示 ③設定済/未設定の
 * 日数サマリー）を取り入れた。**表示専用**（タップ不可）で、編集は従来どおり下の「日別の必要人数
 * （例外）」の一覧・追加ダイアログで行う（ユーザー選択：一覧の操作系は温存し概観を追加するだけ）。
 * 読取専用・スコアリング不変。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeedCalendarCard(ui: UiState, vm: MagiViewModel) {
    val shifts = vm.shiftKigouList()
    if (shifts.isEmpty()) return
    var k by remember { mutableStateOf(0) }
    if (k !in shifts.indices) k = 0
    val cs = MaterialTheme.colorScheme
    val cells = remember(ui.startDate, ui.days, k, ui.loaded) {
        (0 until ui.days).map { j -> vm.needCellLimits(k, j) }
    }
    val setCount = cells.count { it != null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("必要人数カレンダー", style = MaterialTheme.typography.titleMedium)
            Text(
                "シフトを選ぶと、月全体の必要人数（最低〜上限）を一覧できます（表示のみ）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shifts.forEachIndexed { idx, kg ->
                    InputChip(selected = idx == k, onClick = { k = idx }, label = { Text(kg) })
                }
            }
            Text(
                "設定済 ${setCount}日 / 未設定 ${ui.days - setCount}日",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NeedMonthGrid(startDate = ui.startDate, cells = cells)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(14.dp).background(MagiAccent.green.copy(alpha = 0.30f), RoundedCornerShape(4.dp)))
                Text("設定済(最低-上限)", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(14.dp).background(cs.surfaceVariant, RoundedCornerShape(4.dp)))
                Text("未設定", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** [表示専用] 月内の日を曜日整列グリッドで並べ、各日の実効need(lo-hi)をインライン表示する。
 *  DayPickerGrid（選択トグル用）とは別物＝タップ不可・選択状態を持たない読取専用グリッド。 */
@Composable
private fun NeedMonthGrid(startDate: String, cells: List<Pair<Int, Int>?>) {
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
                    if (j == null) Box(Modifier.weight(1f).height(50.dp))
                    else {
                        val range = cells[j]
                        val bg = if (range != null) MagiAccent.green.copy(alpha = 0.16f) else cs.surfaceVariant.copy(alpha = 0.5f)
                        Box(
                            Modifier.weight(1f).height(50.dp).background(bg, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${j + 1}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                                Text(
                                    if (range != null) (if (range.first == range.second) "${range.first}人" else "${range.first}-${range.second}人") else "未設定",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (range != null) ensureReadable(bg, MagiAccent.green) else cs.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                repeat(7 - wk.size) { Box(Modifier.weight(1f).height(50.dp)) }
            }
        }
    }
}

/**
 * ws2 移植: 日別の必要人数（例外）。
 * 通常はシフト既定の need1/need2 を使うが、特定日だけ必要数を変えたい場合に
 * needDay1/needDay2 の疎な上書きを編集する。モデル・エンジンは不変。
 */
@Composable
fun NeedDayCard(ui: UiState, vm: MagiViewModel) {
    var dialog by remember { mutableStateOf<NeedDayEdit?>(null) }
    val overrides = vm.needDayOverrides()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("日別の必要人数（例外）", style = MaterialTheme.typography.titleMedium)
            Text(
                "特定の日だけ必要人数を変えるときに追加します（通常はシフト既定）。",
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
                        EditRowButton(onClick = { dialog = NeedDayEdit(o.k, o.j, o.p1, o.p2) }, enabled = !ui.running)
                        Spacer(Modifier.width(6.dp))
                        DeleteRowButton(onClick = { vm.removeNeedDay(o.k, o.j) }, enabled = !ui.running)
                    }
                }
            }
            AddRowButton("例外を追加", onClick = { dialog = NeedDayEdit(0, 0, "", "") }, enabled = ui.loaded && !ui.running)
        }
    }
    dialog?.let { d ->
        NeedDayDialog(
            init = d,
            shifts = vm.shiftKigouList(),
            maxDay = ui.days,
            startDate = ui.startDate,
            // [実機指摘「日付を複数設定できない」] カレンダーは複数選択トグル＝選んだ全日に同じ例外を一括登録。
            onApply = { k, days, p1, p2 -> days.forEach { j -> vm.setNeedDay(k, j, p1, p2) }; dialog = null },
            onClose = { dialog = null },
        )
    }
}

/** 一本指: 数値は「＋ −」の大ボタンで増減（キーボード不要）。空＝既定/なし。同package(StaffRange)と共有。 */
@Composable
internal fun NumberStepper(label: String, value: String, onChange: (String) -> Unit, min: Int, blankLabel: String) {
    val n = value.toIntOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            // [ゼロ設定] 空欄(なし/既定)から「−」で下限値(=0)へ。従来は n==null で何も起きず、0にするには
            //   「＋」が必要で「−でゼロにできない」と誤認された。0で更に「−」を押すと空欄(クリア)へ戻せる。
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

private data class NeedDayEdit(val k: Int, val j: Int, val p1: String, val p2: String)

@Composable
private fun NeedDayDialog(
    init: NeedDayEdit,
    shifts: List<String>,
    maxDay: Int,
    startDate: String,
    onApply: (Int, List<Int>, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var k by remember { mutableStateOf(init.k) }
    // [カレンダー形式/スクショ指摘] 日はカレンダーから選択。[複数設定] タップでON/OFFトグル＝複数日へ一括登録。
    var daysSel by remember { mutableStateOf(setOf(init.j + 1)) }
    var p1 by remember { mutableStateOf(init.p1) }
    var p2 by remember { mutableStateOf(init.p2) }
    var open by remember { mutableStateOf(false) }
    val ok = k in shifts.indices && daysSel.isNotEmpty() && (p1.isNotBlank() || p2.isNotBlank())
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            DialogConfirmButton(if (daysSel.size > 1) "適用（${daysSel.size}日）" else "適用", enabled = ok,
                onClick = { if (ok) onApply(k, daysSel.map { it - 1 }.sorted(), p1.trim(), p2.trim()) })
        },
        dismissButton = { DialogDismissButton(onClick = onClose) },
        title = { DialogHeader("日別の必要人数", onClose) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("シフト", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(shifts.getOrNull(k) ?: "(選択)")
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        shifts.forEachIndexed { idx, kg ->
                            DropdownMenuItem(text = { Text(kg) }, onClick = { k = idx; open = false })
                        }
                    }
                }
                Text("日（タップで複数選択できます・${daysSel.size}日選択中）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DayPickerGrid(startDate = startDate, maxDay = maxDay, selectedDays = daysSel,
                    onToggle = { d -> daysSel = if (d in daysSel) daysSel - d else daysSel + d })
                NumberStepper("最低人数", p1, { p1 = it }, min = 0, blankLabel = "既定")
                NumberStepper("上限人数", p2, { p2 = it }, min = 0, blankLabel = "既定")
            }
        },
    )
}

/** [カレンダー形式] 月内の日を曜日整列グリッドからタップで選ぶ（片手一本指・キーボード不要）。
 *  週の並びは**日曜始まり**（ユーザー指示・紙のカレンダー慣習）。日=赤/土=青で週の手掛かりを添える。
 *  [複数設定] selectedDays の Set トグル＝複数日の一括選択に対応（実機指摘「日付を複数設定できない」）。
 *  [既存値の可視化] markedDays(日→短いラベル、例:希望シフト記号)を渡すと、日番号の下に小さく表示する
 *  （実機指摘「登録済みの希望シフトが表示されていない」＝選択中の状態しか見えず既存登録が分からなかった）。
 *  空Map(既定)なら従来どおり表示なし＝呼出元(NeedDayEditor等)は無変更。 */
@Composable
internal fun DayPickerGrid(startDate: String, maxDay: Int, selectedDays: Set<Int>, markedDays: Map<Int, String> = emptyMap(), onToggle: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sdow = (startDowMonFirst(startDate) + 1) % 7   // 月曜始まり(0=月)→日曜始まり(0=日)へ変換
    val weekJa = listOf("日", "月", "火", "水", "木", "金", "土")
    val cellHeight = if (markedDays.isEmpty()) 40.dp else 50.dp
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            weekJa.forEachIndexed { idx, w ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(w, style = MaterialTheme.typography.labelSmall,
                        color = when (idx) { 0 -> MagiAccent.red; 6 -> MagiAccent.blue; else -> cs.onSurfaceVariant })
                }
            }
        }
        val cells: List<Int?> = List(sdow) { null } + (1..maxDay).toList()
        cells.chunked(7).forEach { wk ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                wk.forEach { d ->
                    if (d == null) Box(Modifier.weight(1f).height(cellHeight))
                    else {
                        val sel = d in selectedDays
                        val mark = markedDays[d]
                        val bg = if (sel) cs.primary else cs.surfaceVariant
                        Box(
                            Modifier.weight(1f).height(cellHeight)
                                .background(bg, RoundedCornerShape(8.dp))
                                .clickable { onToggle(d) }
                                .semantics {
                                    contentDescription = "${d}日を" + (if (sel) "解除" else "選択") +
                                        (mark?.let { "・登録済み$it" } ?: "")
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$d", style = MaterialTheme.typography.bodyMedium,
                                    color = if (sel) cs.onPrimary else cs.onSurface,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                if (mark != null) {
                                    Text(mark, style = MaterialTheme.typography.labelSmall,
                                        color = ensureReadable(bg, MagiAccent.pink), maxLines = 1)
                                }
                            }
                        }
                    }
                }
                repeat(7 - wk.size) { Box(Modifier.weight(1f).height(cellHeight)) }
            }
        }
    }
}
