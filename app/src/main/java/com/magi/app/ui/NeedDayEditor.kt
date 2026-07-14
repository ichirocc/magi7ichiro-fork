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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * [必要人数カレンダー] シフトを1つ選び、月全体の必要人数を見渡し・その場で編集する主画面。
 * ユーザー提示の第2モックアップ（必要人数設定/Dテスト）に基づき、方針を「表示専用」から転換:
 * カレンダー自体をタップして複数日を選び、下部固定パネルで一括編集する（旧NeedDayDialogを吸収）。
 * ①シフト種類チップ ②「基本の必要人数」（シフト既定need1/need2）をこの画面で直接編集
 *   （Ws1Cardと入口が二重になるがユーザー選択） ③日セルは実際の勤務表充足度で色分け
 *   （緑=充足/橙=過剰=covO/赤=不足=covU/灰=need未設定。TallyCard/グリッドの赤=covU・橙=covOと同じ
 *   語彙に統一。covUは呼び名「不足」・covOは「過剰」でNeedDayCard/CoverageDiagnosis等と揃える）
 *   ④タップで複数日選択→下部パネル「選択したN日間に適用する」で一括登録（DayPickerGridと同じ
 *   トグル選択の考え方をこのカード専用の色分けグリッドに統合）。月送りは導入しない（1state=1か月の
 *   スナップショットモデル、D6決定と整合）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeedCalendarCard(ui: UiState, vm: MagiViewModel) {
    val v = vm.ws1() ?: return
    if (v.shifts.isEmpty()) return
    var k by remember { mutableStateOf(0) }
    if (k !in v.shifts.indices) k = 0
    val shift = v.shifts[k]
    val cs = MaterialTheme.colorScheme
    var daysSel by remember(k) { mutableStateOf(emptySet<Int>()) }
    var selP1 by remember { mutableStateOf(shift.need1) }
    var selP2 by remember { mutableStateOf(shift.need2) }
    val onToggleDay: (Int) -> Unit = { d ->
        val wasEmpty = daysSel.isEmpty()
        daysSel = if (d in daysSel) daysSel - d else daysSel + d
        if (wasEmpty && daysSel.isNotEmpty()) { selP1 = shift.need1; selP2 = shift.need2 }
    }
    val cells = (0 until ui.days).map { j -> vm.needCellLimits(k, j) to ui.needViolations["$k,$j"] }
    val setCount = cells.count { it.first != null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("必要人数カレンダー", style = MaterialTheme.typography.titleMedium)
            Text(
                "シフトを選び、日をタップして必要人数を一括登録できます。色は現在の勤務表の充足状況です。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                v.shifts.forEachIndexed { idx, s -> InputChip(selected = idx == k, onClick = { k = idx }, label = { Text(s.kigou) }) }
            }
            // [基本の必要人数] シフト既定need1/need2をこの画面で直接編集（インライン・即時反映）。
            Column(
                Modifier.fillMaxWidth().border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("基本の必要人数（${shift.kigou}の既定値）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                NumberStepper("最低人数", shift.need1, { vm.setShiftNeed(k, it, shift.need2) }, min = 0, blankLabel = "未設定")
                NumberStepper("上限人数", shift.need2, { vm.setShiftNeed(k, shift.need1, it) }, min = 0, blankLabel = "未設定")
            }
            Text(
                "設定済 ${setCount}日 / 未設定 ${ui.days - setCount}日",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NeedMonthGrid(startDate = ui.startDate, cells = cells, selectedDays = daysSel, onToggle = onToggleDay)
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot(MagiAccent.green, "充足")
                LegendDot(MagiAccent.orange, "過剰")
                LegendDot(MagiAccent.red, "不足")
                LegendDot(cs.outlineVariant, "未設定")
            }
            if (daysSel.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(cs.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${daysSel.size}日選択中の設定を変更", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { daysSel = emptySet() }, enabled = !ui.running) { Text("選択をクリア") }
                    }
                    NumberStepper("最低人数", selP1, { selP1 = it }, min = 0, blankLabel = "既定")
                    NumberStepper("上限人数", selP2, { selP2 = it }, min = 0, blankLabel = "既定")
                    Text(
                        "基本の必要人数：最低 ${shift.need1.ifBlank { "未設定" }}人 〜 上限 ${shift.need2.ifBlank { "未設定" }}人",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            daysSel.forEach { d -> vm.setNeedDay(k, d - 1, selP1, selP2) }
                            daysSel = emptySet()
                        },
                        enabled = !ui.running && (selP1.isNotBlank() || selP2.isNotBlank()),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("選択した${daysSel.size}日間に適用する") }
                }
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
 *  色分けドットを表示（緑=充足/橙=過剰covO/赤=不足covU/灰=need未設定）。選択中の日は
 *  チェックマーク＋枠線で示す（DayPickerGridと同じ「タップでトグル」操作系）。 */
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
 * ws2 移植: 日別の必要人数（例外）一覧。
 * 通常はシフト既定の need1/need2 を使うが、特定日だけ必要数を変えたい場合に
 * needDay1/needDay2 の疎な上書きを編集する。モデル・エンジンは不変。
 * [必要人数カレンダーへ編集導線を集約] 登録/変更は上の`NeedCalendarCard`（タップ選択→一括適用）へ
 * 一本化。本カードは**全シフト横断で例外を一覧できる**（カレンダーは1シフトずつしか見えない弱点を
 * 補う）確認・削除専用に簡略化（追加/編集ダイアログは廃止）。
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

