package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
 * 一本指対応: スタッフ/希望シフトはタップで選ぶプルダウン、日は ＋− ステッパー。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WishCard(ui: UiState, vm: MagiViewModel) {
    var dialog by remember { mutableStateOf<WishEdit?>(null) }
    val rows = vm.wishOverrides()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("希望シフト", style = MaterialTheme.typography.titleMedium)
            Text(
                "各スタッフが特定の日に希望するシフトを登録します（できれば叶える＝任意）。勤務表の割当変更とは別です。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rows.isEmpty()) {
                Text(
                    "（希望なし）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // [校正] スタッフ名の繰り返しを排除し、スタッフ単位に集約。各希望は
                //   コンパクトなチップ「N日 記号」（タップ＝編集 / ×＝削除）で密に一覧。
                rows.groupBy { it.i }.forEach { (_, list) ->
                    Text(list.first().staffName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { r ->
                            InputChip(
                                selected = false,
                                enabled = !ui.running,
                                onClick = { dialog = WishEdit(r.i, r.j, r.k) },
                                label = { Text("${r.day}日 ${r.kigou}") },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = "削除",
                                        modifier = Modifier.size(18.dp).clickable(enabled = !ui.running) { vm.removeWish(r.i, r.j) })
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
            AddRowButton("希望を追加", onClick = { dialog = WishEdit(0, 0, 0) }, enabled = ui.loaded && !ui.running)
        }
    }
    dialog?.let { d ->
        WishDialog(
            initI = d.i,
            initJ = d.j,
            initK = d.k,
            days = ui.days,
            staff = ui.staffNames,
            shifts = vm.shiftKigouList(),
            allowedFor = { idx -> vm.allowedShiftsFor(idx).toHashSet() },
            onApply = { i, j, k -> vm.setWish(i, j, k); dialog = null },
            onClose = { dialog = null },
        )
    }
}

private data class WishEdit(val i: Int, val j: Int, val k: Int)

@Composable
private fun WishDialog(
    initI: Int,
    initJ: Int,
    initK: Int,
    days: Int,
    staff: List<String>,
    shifts: List<String>,
    allowedFor: (Int) -> Set<Int>,
    onApply: (Int, Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    val maxDay = days.coerceAtLeast(1)
    var i by remember { mutableStateOf(initI) }
    // [見やすさ/効率] 日は ± だけでなく直接入力もできる(1->30で多タップを回避)。NeedDayEditor と同じ操作系。
    var dayText by remember { mutableStateOf((initJ + 1).coerceIn(1, maxDay).toString()) }
    val day = dayText.toIntOrNull()
    var k by remember { mutableStateOf(initK) }
    var openS by remember { mutableStateOf(false) }
    val ok = i in staff.indices && k in shifts.indices && day != null && day in 1..maxDay
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            DialogConfirmButton("適用", enabled = ok, onClick = { if (ok) onApply(i, day!! - 1, k) })
        },
        dismissButton = { DialogDismissButton(onClick = onClose) },
        title = { DialogHeader("希望シフト", onClose) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("スタッフ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { openS = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(staff.getOrNull(i) ?: "(選択)")
                    }
                    DropdownMenu(expanded = openS, onDismissRequest = { openS = false }) {
                        staff.forEachIndexed { idx, n ->
                            DropdownMenuItem(text = { Text(n) }, onClick = { i = idx; openS = false })
                        }
                    }
                }
                Text("日", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dayText = ((day ?: 1) - 1).coerceAtLeast(1).toString() }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "日を減らす" }) { Text("−") }
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { dayText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("1〜$maxDay") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                    Button(onClick = { dayText = ((day ?: 0) + 1).coerceAtMost(maxDay).toString() }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "日を増やす" }) { Text("＋") }
                }
                Text("希望シフト", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // [web版参考 HF205/HF211] 担当可能シフトは大ボタンで選択。担当範囲外（勤務表不可）は
                //   赤枠＋⚠（色だけに依存しない手がかり）で明示し誤選択を防ぐ。範囲外も選択は可能
                //   （意図的な範囲外希望＝不可能希望として別途警告）。プルダウンより一覧性が高い。
                val allowed = allowedFor(i)
                val cs = MaterialTheme.colorScheme
                shifts.indices.chunked(4).forEach { rowIdxs ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowIdxs.forEach { idx ->
                            val sel = k == idx
                            val ng = idx !in allowed
                            val borderColor = when { sel -> cs.primary; ng -> cs.error; else -> cs.outline }
                            val bg = if (sel) cs.primaryContainer else cs.surface
                            val fg = when { sel -> cs.onPrimaryContainer; ng -> cs.error; else -> cs.onSurface }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .background(bg, RoundedCornerShape(12.dp))
                                    .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                                    .clickable { k = idx }
                                    .semantics { contentDescription = (shifts.getOrNull(idx) ?: "") + (if (ng) "・担当外" else "") + (if (sel) "・選択中" else "") }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    (shifts.getOrNull(idx) ?: "") + if (ng) " ⚠" else "",
                                    color = fg, textAlign = TextAlign.Center,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                        repeat(4 - rowIdxs.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                val curNg = k in shifts.indices && k !in allowed
                if (curNg) {
                    Text("⚠「${shifts.getOrNull(k)}」はこのスタッフの担当外です。希望は登録できますが、配置すると違反になります。",
                        style = MaterialTheme.typography.labelSmall, color = cs.error)
                }
            }
        },
    )
}
