package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * ws3 移植: 希望シフト wishes["i,j"]=シフトindex（スタッフ i が j 日目に希望するシフト）。
 * 採点は pref（hard1 のソフト寄り）。モデル(wishes)・エンジン(pref)は既存のため不変、UI のみ追加。
 * 注意: これは「希望」であり、勤務表セルの「割当」変更とも、cons3系（連勤の並び）とも別概念。
 *
 * [必要人数カレンダーと同じ方針への刷新] ユーザー提示の第3モックアップ（希望シフト登録）を元に、
 * 表示専用のリスト＋モーダルダイアログから、**カレンダー自体をタップする主要編集導線**へ刷新。
 * 職員を1人選ぶと月全体の登録済み希望が一目で見え（シフト色チップ）、日をタップして複数選択→
 * 下部固定パネルで一括登録できる（旧WishDialogを吸収・廃止）。全職員横断のチップ一覧は
 * NeedDayCardと同じ理由（カレンダーは1職員ずつしか見えない弱点を補う）で確認・削除専用に温存。
 * 「1日1個のみ登録」はwishes["i,j"]が単一値のMapである既存モデルにより自動的に保証される。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WishCard(ui: UiState, vm: MagiViewModel) {
    val staff = ui.staffNames
    val shifts = vm.shiftKigouList()
    if (staff.isEmpty() || shifts.isEmpty()) return
    var i by remember { mutableStateOf(0) }
    if (i !in staff.indices) i = 0
    var daysSel by remember(i) { mutableStateOf(emptySet<Int>()) }
    var selK by remember { mutableStateOf(0) }
    val cs = MaterialTheme.colorScheme
    val allowed = vm.allowedShiftsFor(i).toHashSet()
    val rows = vm.wishOverrides()
    val myRows = rows.filter { it.i == i }
    val marked = myRows.associate { it.day to it.k }
    val byShift = myRows.groupingBy { it.k }.eachCount()

    val onToggleDay: (Int) -> Unit = { d -> daysSel = if (d in daysSel) daysSel - d else daysSel + d }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("希望シフト登録", style = MaterialTheme.typography.titleMedium)
            Text(
                "職員を選び、日をタップして希望シフトを一括登録できます。1日につき1つのみ登録できます。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                staff.forEachIndexed { idx, n -> InputChip(selected = idx == i, onClick = { i = idx }, label = { Text(n) }) }
            }
            Text(
                "設定日数 ${myRows.size}日" + shifts.indices.filter { byShift.containsKey(it) }
                    .joinToString("") { " ・ ${shifts[it]} ${byShift[it]}日" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WishMonthGrid(
                startDate = ui.startDate, maxDay = ui.days, marked = marked,
                shiftKigou = shifts, shiftColorHex = ui.shiftColorHex, shiftTextHex = ui.shiftTextHex,
                selectedDays = daysSel, onToggle = onToggleDay,
            )
            if (daysSel.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(cs.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${daysSel.size}日選択中の希望シフトを変更", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { daysSel = emptySet() }, enabled = !ui.running) { Text("選択をクリア") }
                    }
                    Text("希望シフトを選択（1日1個のみ）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    // [web版参考 HF205/HF211] 担当可能シフトは大ボタンで選択。担当範囲外（勤務表不可）は
                    //   赤枠＋⚠（色だけに依存しない手がかり）で明示し誤選択を防ぐ。範囲外も選択は可能
                    //   （意図的な範囲外希望＝不可能希望として別途警告）。背景はシフトの表示色で識別。
                    shifts.indices.chunked(4).forEach { rowIdxs ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            rowIdxs.forEach { idx ->
                                val sel = selK == idx
                                val ng = idx !in allowed
                                val bg = hexToColor(ui.shiftColorHex.getOrElse(idx) { "" })
                                val fg = hexToColor(ui.shiftTextHex.getOrElse(idx) { "" })
                                val borderColor = when { ng -> MagiAccent.red; sel -> cs.primary; else -> cs.outline }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = 52.dp)
                                        .background(bg, MaterialTheme.shapes.small)
                                        .border(if (sel) 3.dp else 2.dp, borderColor, MaterialTheme.shapes.small)
                                        .clickable { selK = idx }
                                        .semantics { contentDescription = (shifts.getOrNull(idx) ?: "") + (if (ng) "・担当外" else "") + (if (sel) "・選択中" else "") }
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (sel) Icon(Icons.Filled.Check, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                                        Text(
                                            (shifts.getOrNull(idx) ?: "") + if (ng) " ⚠" else "",
                                            color = fg, textAlign = TextAlign.Center,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                            repeat(4 - rowIdxs.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (selK !in allowed) {
                        Text("⚠「${shifts.getOrNull(selK)}」はこの職員の担当外です。希望は登録できますが、配置すると違反になります。",
                            style = MaterialTheme.typography.labelSmall, color = cs.error)
                    }
                    Button(
                        onClick = {
                            daysSel.forEach { d -> vm.setWish(i, d - 1, selK) }
                            daysSel = emptySet()
                        },
                        enabled = !ui.running,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("選択した${daysSel.size}日間に適用する") }
                }
            }
            // [全職員横断の一覧] カレンダーは1職員ずつしか見えない弱点を補う確認・削除専用ビュー。
            if (rows.isNotEmpty()) {
                Text("登録済み希望（全職員）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                rows.groupBy { it.i }.forEach { (_, list) ->
                    Text(list.first().staffName, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { r ->
                            InputChip(
                                selected = false,
                                enabled = !ui.running,
                                onClick = { i = r.i },
                                label = { Text("${r.day}日 ${r.kigou}") },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = "削除",
                                        modifier = Modifier.size(32.dp).clickable(enabled = !ui.running) { vm.removeWish(r.i, r.j) }.padding(7.dp))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [希望シフトカレンダーの本体グリッド] 月内の日を曜日整列で並べ、タップで複数日選択できる。
 *  各日は登録済みの希望（marked: 日→シフトindex）をシフトの表示色チップ（記号入り）で示す。
 *  選択中の日はチェックマーク＋枠線で示す（NeedCalendarCardのNeedMonthGridと同じ操作系）。 */
@Composable
private fun WishMonthGrid(
    startDate: String,
    maxDay: Int,
    marked: Map<Int, Int>,
    shiftKigou: List<String>,
    shiftColorHex: List<String>,
    shiftTextHex: List<String>,
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
        val dayCells: List<Int?> = List(sdow) { null } + (1..maxDay).toList()
        dayCells.chunked(7).forEach { wk ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                wk.forEach { d ->
                    if (d == null) Box(Modifier.weight(1f).height(56.dp))
                    else {
                        val sel = d in selectedDays
                        val k = marked[d]
                        Box(
                            Modifier.weight(1f).height(56.dp)
                                .background(if (sel) cs.primaryContainer else cs.surface, RoundedCornerShape(8.dp))
                                .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable { onToggle(d) }
                                .semantics {
                                    contentDescription = "${d}日を" + (if (sel) "選択解除" else "選択") +
                                        (k?.let { "・希望登録済み${shiftKigou.getOrNull(it) ?: ""}" } ?: "")
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("$d", style = MaterialTheme.typography.bodyMedium,
                                        color = if (sel) cs.onPrimaryContainer else cs.onSurface,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                    if (sel) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(12.dp))
                                }
                                if (k != null) {
                                    val chipBg = hexToColor(shiftColorHex.getOrElse(k) { "" })
                                    val chipFg = hexToColor(shiftTextHex.getOrElse(k) { "" })
                                    Box(Modifier.background(chipBg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                                        Text(shiftKigou.getOrNull(k) ?: "", style = MaterialTheme.typography.labelSmall, color = chipFg, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
                repeat(7 - wk.size) { Box(Modifier.weight(1f).height(56.dp)) }
            }
        }
    }
}
