package com.magi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ws1 (初期設定) editor card. Edits the problem definition: period length (days),
 * use2 flag, shifts / groups / staff (rename + per-field edit, append-add), and the
 * group×shift bucket. Each change re-dimensions the working table consistently
 * (MagiViewModel.ws1* -> Ws1Ops) and re-runs the check; saving emits the full state.
 * Remove operations are deferred to a later increment.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Ws1Card(ui: UiState, vm: MagiViewModel) {
    val v = vm.ws1() ?: return
    var dialog by remember { mutableStateOf<Ws1Dialog?>(null) }
    var daysText by remember(v.days) { mutableStateOf(v.days.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("基本情報（スタッフ・シフト・グループ）", style = MaterialTheme.typography.titleMedium)
            Text("変更すると表を作り直し、すぐ問題がないか調べ直します。", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary)

            // --- period ---
            Spacer(Modifier.height(10.dp))
            Text("期間", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("${v.startDate} 〜 ${v.endDate}", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                W1Field("日数(1-31)", daysText, Modifier.width(130.dp)) { daysText = it }
                EditRowButton(onClick = { daysText.toIntOrNull()?.let { vm.ws1ResizeDays(it) } }, text = "変更")
            }

            // --- use2 ---
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("必要人数の2パターン目を使う（特殊な月用・通常はOFF）", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = v.use2, onCheckedChange = { vm.ws1SetUse2(it) })
            }
            Divider()

            // --- shifts ---
            Spacer(Modifier.height(8.dp))
            Text("シフト (${v.shifts.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            v.shifts.forEachIndexed { k, s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.kigou}  ${s.name}  (最低 ${s.need1.ifBlank { "-" }}人 / 上限 ${s.need2.ifBlank { "-" }}人)",
                        fontSize = 14.sp, modifier = Modifier.weight(1f))
                    EditRowButton(onClick = { dialog = Ws1Dialog.EditShift(k, s.name, s.kigou, s.need1, s.need2) })
                    if (v.shifts.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        DeleteRowButton(onClick = { dialog = Ws1Dialog.ConfirmDelete("shift", k, "シフト ${s.kigou}") })
                    }
                }
            }
            AddRowButton("シフト追加", onClick = { dialog = Ws1Dialog.AddShift })
            AddRowButton("一括追加", onClick = { dialog = Ws1Dialog.BulkAddShift })   // [⛏12]
            Divider()

            // --- groups ---
            Spacer(Modifier.height(8.dp))
            Text("グループ (${v.groups.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            v.groups.forEachIndexed { g, gr ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${gr.kigou}  ${gr.name}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    EditRowButton(onClick = { dialog = Ws1Dialog.EditGroup(g, gr.name, gr.kigou) })
                    if (vm.ws1CanRemoveGroup(g)) {
                        Spacer(Modifier.width(6.dp))
                        DeleteRowButton(onClick = { dialog = Ws1Dialog.ConfirmDelete("group", g, "グループ ${gr.kigou}") })
                    }
                }
            }
            AddRowButton("グループ追加", onClick = { dialog = Ws1Dialog.AddGroup })
            Divider()

            // --- staff ---
            Spacer(Modifier.height(8.dp))
            Text("スタッフ (${v.staff.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            v.staff.forEachIndexed { i, st ->
                val gk = v.groups.getOrNull(st.groupIdx)?.kigou ?: "?"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${st.name}  [グループ $gk]", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    EditRowButton(onClick = { dialog = Ws1Dialog.EditStaff(i, st.name, st.groupIdx) })
                    if (v.staff.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        DeleteRowButton(onClick = { dialog = Ws1Dialog.ConfirmDelete("staff", i, st.name) })
                    }
                }
            }
            AddRowButton("スタッフ追加", onClick = { dialog = Ws1Dialog.AddStaff })
            AddRowButton("一括追加", onClick = { dialog = Ws1Dialog.BulkAddStaff })   // [⛏12]
            Divider()

            // --- groupShift bucket ---
            Spacer(Modifier.height(8.dp))
            Text("担当できるシフト（グループ × シフト）", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            // [見やすさ] 横スクロール(12シフトで画面外)をやめ、群ごとに群名を行頭＋チップを FlowRow で折り返す。
            //   選択中＝塗り＋✓（色だけに依存しない手がかり）、未選択＝外枠。
            v.groups.forEachIndexed { g, gr ->
                Spacer(Modifier.height(4.dp))
                Text("${gr.kigou}  ${gr.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    v.shifts.forEachIndexed { k, s ->
                        val on = v.groupShift.getOrNull(g)?.getOrNull(k) == 1
                        FilterChip(
                            selected = on,
                            onClick = { vm.ws1SetGroupShift(g, k, !on) },
                            label = { Text(s.kigou, fontFamily = FontFamily.Monospace) },
                            leadingIcon = if (on) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                        )
                    }
                }
            }

            // --- groupShiftApt（適切回数）: Web版「グループ別 担当シフトと適切回数」相当 ---
            // 担当ON のシフトだけ、1人あたり期間内目標回数を −/＋ で設定（空欄＝目標なし）。
            Spacer(Modifier.height(12.dp))
            Text("適切回数（任意・1人あたり目標）", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("ONのシフトに目標回数を設定すると、最適化が各人をその回数に近づけます（空欄＝目標なし）",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.groups.forEachIndexed { g, gr ->
                val onShifts = v.shifts.indices.filter { v.groupShift.getOrNull(g)?.getOrNull(it) == 1 }
                if (onShifts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("${gr.kigou}  ${gr.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        onShifts.forEach { k ->
                            val apt = v.groupShiftApt.getOrNull(g)?.getOrNull(k) ?: ""
                            AptStepper(label = v.shifts[k].kigou, value = apt,
                                onChange = { vm.ws1SetGroupApt(g, k, it) })
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is Ws1Dialog.EditShift -> ShiftDialog("シフト編集", d.name, d.kigou, d.need1, d.need2,
            { n, kg, n1, n2 -> vm.ws1EditShift(d.k, n, kg, n1, n2); dialog = null }, { dialog = null })
        Ws1Dialog.AddShift -> ShiftDialog("シフト追加", "", "", "", "",
            { n, kg, n1, n2 -> vm.ws1AddShift(n, kg, n1, n2); dialog = null }, { dialog = null })
        is Ws1Dialog.EditGroup -> GroupDialog("グループ編集", d.name, d.kigou,
            { n, kg -> vm.ws1EditGroup(d.g, n, kg); dialog = null }, { dialog = null })
        Ws1Dialog.AddGroup -> GroupDialog("グループ追加", "", "",
            { n, kg -> vm.ws1AddGroup(n, kg); dialog = null }, { dialog = null })
        is Ws1Dialog.EditStaff -> StaffDialog("スタッフ編集", d.name, d.groupIdx, v.groups.map { it.kigou },
            { n, gi -> vm.ws1EditStaff(d.i, n, gi); dialog = null }, { dialog = null })
        Ws1Dialog.AddStaff -> StaffDialog("スタッフ追加", "", 0, v.groups.map { it.kigou },
            { n, gi -> vm.ws1AddStaff(n, gi); dialog = null }, { dialog = null })
        Ws1Dialog.BulkAddShift -> BulkAddDialog("シフトを一括追加", "記号を改行で複数入力（例: 休 / Dﾃ / A4）。記号がそのまま名称になります。", null,
            { lines, _ -> lines.forEach { vm.ws1AddShift(it, it, "", "") }; dialog = null }, { dialog = null })
        Ws1Dialog.BulkAddStaff -> BulkAddDialog("スタッフを一括追加", "名前を改行で複数入力。全員を既定グループに追加します（後で個別変更可）。", v.groups.map { it.kigou },
            { lines, gi -> lines.forEach { vm.ws1AddStaff(it, gi) }; dialog = null }, { dialog = null })
        is Ws1Dialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = {
                DialogDangerButton("削除", onClick = {
                    when (d.kind) {
                        "shift" -> vm.ws1RemoveShift(d.index)
                        "group" -> vm.ws1RemoveGroup(d.index)
                        "staff" -> vm.ws1RemoveStaff(d.index)
                    }
                    dialog = null
                })
            },
            dismissButton = { DialogDismissButton(onClick = { dialog = null }) },
            title = { Text("削除の確認") },
            text = { Text("${d.label} を削除します。割当やインデックスが再構成されます。よろしいですか？") },
        )
        null -> Unit
    }
}

private sealed interface Ws1Dialog {
    data class EditShift(val k: Int, val name: String, val kigou: String, val need1: String, val need2: String) : Ws1Dialog
    object AddShift : Ws1Dialog
    data class EditGroup(val g: Int, val name: String, val kigou: String) : Ws1Dialog
    object AddGroup : Ws1Dialog
    data class EditStaff(val i: Int, val name: String, val groupIdx: Int) : Ws1Dialog
    object AddStaff : Ws1Dialog
    object BulkAddShift : Ws1Dialog
    object BulkAddStaff : Ws1Dialog
    data class ConfirmDelete(val kind: String, val index: Int, val label: String) : Ws1Dialog
}

@Composable
private fun ShiftDialog(
    title: String, name0: String, kigou0: String, need10: String, need20: String,
    onOk: (String, String, String, String) -> Unit, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(name0) }
    var kigou by remember { mutableStateOf(kigou0) }
    var need1 by remember { mutableStateOf(need10) }
    var need2 by remember { mutableStateOf(need20) }
    W1Shell(title, onClose, { onOk(name, kigou, need1, need2) }, kigou.isNotBlank()) {
        W1Text("記号 (kigou)", kigou) { kigou = it }
        W1Text("名称", name) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            W1Field("最低人数", need1, Modifier.width(140.dp)) { need1 = it }
            W1Field("上限人数(2パターン時)", need2, Modifier.width(140.dp)) { need2 = it }
        }
    }
}

@Composable
private fun GroupDialog(
    title: String, name0: String, kigou0: String,
    onOk: (String, String) -> Unit, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(name0) }
    var kigou by remember { mutableStateOf(kigou0) }
    W1Shell(title, onClose, { onOk(name, kigou) }, kigou.isNotBlank()) {
        W1Text("記号 (kigou)", kigou) { kigou = it }
        W1Text("名称", name) { name = it }
    }
}

@Composable
private fun StaffDialog(
    title: String, name0: String, group0: Int, groupKigou: List<String>,
    onOk: (String, Int) -> Unit, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(name0) }
    var gi by remember { mutableStateOf(group0.coerceIn(0, (groupKigou.size - 1).coerceAtLeast(0))) }
    W1Shell(title, onClose, { onOk(name, gi) }, name.isNotBlank() && groupKigou.isNotEmpty()) {
        W1Text("名称", name) { name = it }
        var open by remember { mutableStateOf(false) }
        Text("グループ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (groupKigou.isEmpty()) {
            // [A7] 鶏卵問題の誘導：グループが無いとスタッフの所属先が決められない（OKは無効）。
            Text("先に「グループ」を追加してください。スタッフはグループに所属します。",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        } else {
            OutlinedButton(onClick = { open = true }) { Text(groupKigou.getOrNull(gi) ?: "(なし)") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                groupKigou.forEachIndexed { idx, kg ->
                    DropdownMenuItem(text = { Text(kg, fontFamily = FontFamily.Monospace) },
                        onClick = { gi = idx; open = false })
                }
            }
        }
    }
}

/**
 * [⛏12] 改行区切りで複数件をまとめて追加する汎用ダイアログ。1件ずつの追加(各4-5tap×N)を
 * 1回の入力に短縮し、ゼロ構築の操作量を削減する。groups!=null のときは既定グループを選ぶ
 * (スタッフ用)。groups==null はグループ選択なし(シフト用)。追加は呼び出し側で既存の
 * ws1AddStaff/ws1AddShift をループ呼びするだけ＝ロジックは不変。
 */
@Composable
private fun BulkAddDialog(
    title: String, hint: String, groups: List<String>?,
    onApply: (List<String>, Int) -> Unit, onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var gi by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val groupOk = groups == null || groups.isNotEmpty()
    W1Shell(title, onClose, { onApply(lines, gi) }, lines.isNotEmpty() && groupOk) {
        Text(hint, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = false, minLines = 3,
            label = { Text("1行に1件", fontSize = 14.sp) }, modifier = Modifier.fillMaxWidth(),
        )
        if (groups != null) {
            if (groups.isEmpty()) {
                Text("先に「グループ」を追加してください。", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
            } else {
                Text("既定のグループ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { open = true }) { Text(groups.getOrNull(gi) ?: "(なし)") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    groups.forEachIndexed { idx, kg ->
                        DropdownMenuItem(text = { Text(kg, fontFamily = FontFamily.Monospace) },
                            onClick = { gi = idx; open = false })
                    }
                }
            }
        }
        if (lines.isNotEmpty()) Text("追加: ${lines.size}件", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun W1Shell(
    title: String, onClose: () -> Unit, onOk: () -> Unit, okEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { DialogConfirmButton("OK", enabled = okEnabled, onClick = onOk) },
        dismissButton = { DialogDismissButton(onClick = onClose) },
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } },
    )
}

@Composable
private fun W1Text(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, fontSize = 14.sp) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun W1Field(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label, fontSize = 14.sp) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** 適切回数のステッパー（記号 −[値]＋）。空欄＝目標なし。1未満は空欄に戻す。 */
@Composable
private fun AptStepper(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
        Text(label, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            val c = value.trim().toIntOrNull() ?: 0
            onChange(if (c <= 1) "" else (c - 1).toString())
        }) { Text("−", fontSize = 16.sp) }
        Text(value.ifBlank { "—" }, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(22.dp), color = MaterialTheme.colorScheme.onSurface)
        TextButton(onClick = {
            val c = value.trim().toIntOrNull() ?: 0
            onChange((c + 1).toString())
        }) { Text("＋", fontSize = 16.sp) }
    }
}
