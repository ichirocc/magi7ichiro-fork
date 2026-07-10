package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
                "通常はシフト既定の必要数を使います（既定は『年次マスター → 基本情報 → シフト』で設定）。特定の日だけ人数を変えたいときに追加します。",
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
            onApply = { k, j, p1, p2 -> vm.setNeedDay(k, j, p1, p2); dialog = null },
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
    onApply: (Int, Int, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var k by remember { mutableStateOf(init.k) }
    // [カレンダー形式/スクショ指摘] 日はテキスト入力でなく月のカレンダーから1タップ選択（青囲い指示）。
    var day by remember { mutableStateOf(init.j + 1) }
    var p1 by remember { mutableStateOf(init.p1) }
    var p2 by remember { mutableStateOf(init.p2) }
    var open by remember { mutableStateOf(false) }
    val ok = k in shifts.indices && day in 1..maxDay && (p1.isNotBlank() || p2.isNotBlank())
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            DialogConfirmButton("適用", enabled = ok, onClick = { if (ok) onApply(k, day - 1, p1.trim(), p2.trim()) })
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
                Text("日（タップで選択・${day}日）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DayPickerGrid(startDate = startDate, maxDay = maxDay, selected = day, onSelect = { day = it })
                NumberStepper("最低人数", p1, { p1 = it }, min = 0, blankLabel = "既定")
                NumberStepper("上限人数", p2, { p2 = it }, min = 0, blankLabel = "既定")
            }
        },
    )
}

/** [カレンダー形式] 月内の日を曜日整列グリッドから1タップで選ぶ（片手一本指・キーボード不要）。
 *  週の並びは**日曜始まり**（ユーザー指示・紙のカレンダー慣習）。日=赤/土=青で週の手掛かりを添える。 */
@Composable
internal fun DayPickerGrid(startDate: String, maxDay: Int, selected: Int?, onSelect: (Int) -> Unit) {
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
        val cells: List<Int?> = List(sdow) { null } + (1..maxDay).toList()
        cells.chunked(7).forEach { wk ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                wk.forEach { d ->
                    if (d == null) Box(Modifier.weight(1f).height(40.dp))
                    else {
                        val sel = selected == d
                        Box(
                            Modifier.weight(1f).height(40.dp)
                                .background(if (sel) cs.primary else cs.surfaceVariant, RoundedCornerShape(8.dp))
                                .clickable { onSelect(d) }
                                .semantics { contentDescription = "${d}日を選択" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$d", style = MaterialTheme.typography.bodyMedium,
                                color = if (sel) cs.onPrimary else cs.onSurface,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                repeat(7 - wk.size) { Box(Modifier.weight(1f).height(40.dp)) }
            }
        }
    }
}
