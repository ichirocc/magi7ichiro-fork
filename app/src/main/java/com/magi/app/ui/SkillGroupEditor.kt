package com.magi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [スキルグループ新設・年次マスター] ユニットグループとは別の第2分類を編集する。担当可否には影響せず、
 * スキル別の回数(cons41s)/組み合わせ禁止(cons42s) だけが参照する。1人1スキル。
 */
@Composable
internal fun SkillGroupCard(ui: UiState, v: Ws1View, skillRuleCount: Int, onEvent: (MagiEvent) -> Unit) {
    if (!ui.loaded) return
    val cs = MaterialTheme.colorScheme
    val skills = v.skillGroups
    val staff = v.staff
    var dialog by remember { mutableStateOf<SkillDlg?>(null) }
    // [破壊操作ガード] スキル群削除は所属職員を未所属(-1)へ戻す高影響操作。確認を挟む。
    var confirmDelete by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // [3.409.18] 状態依存の1行（ルール0本で何にも効いていない状態を見せる。スキルは勤務グループとは別の独立した分類）。
        Text("担当シフトとは別の分類です（1人1スキル）。スキルグループのルールだけが使います。",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        if (skills.isNotEmpty()) {
            Text(if (skillRuleCount == 0) "ルールが無いので、この分類は勤務表に影響しません。ルールはこの下の欄で作れます。"
                else "スキルグループのルール ${skillRuleCount}件がこの分類を使っています。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        val enabled = !ui.running
        val rows = ArrayList<@Composable () -> Unit>()
        skills.forEachIndexed { g, sg ->
            rows += { GroupedRow("${sg.kigou}  ${sg.name}", enabled = enabled, onClick = { dialog = SkillDlg.Edit(g, sg.name, sg.kigou) }) {
                GroupedDeleteButton(enabled = enabled) { confirmDelete = g } } }
        }
        rows += { GroupedAddRow("スキルグループを追加する", enabled = enabled) { dialog = SkillDlg.Add } }
        // [3.482.0] 職員ごとの割当は「職員管理」の ▼ に一本化（ここは分類の定義だけ）。
        val footer = if (skills.isEmpty()) null else
            "職員へのスキル割当は「職員管理」の各職員の ▼ で設定します（割当済み ${staff.count { it.skillIdx in skills.indices }}/${staff.size}名）。"
        GroupedSection(null, footer = footer, rows = rows)
    }

    when (val d = dialog) {
        SkillDlg.Add -> SkillGroupDialog("スキルグループ追加", "", "", onOk = { n, k -> onEvent(MagiEvent.Structure.AddSkillGroup(n, k)); dialog = null }, onClose = { dialog = null })
        is SkillDlg.Edit -> SkillGroupDialog("スキルグループ編集", d.name, d.kigou, onOk = { n, k -> onEvent(MagiEvent.Structure.EditSkillGroup(d.g, n, k)); dialog = null }, onClose = { dialog = null })
        null -> {}
    }

    confirmDelete?.let { g ->
        val name = skills.getOrNull(g)?.let { "${it.kigou} ${it.name}" } ?: "このスキルグループ"
        // [3.429.0/R-03] cons41s/cons42s の参照件数も見せる（削除自体は従来どおり進められる）。
        val refs = v.skillGroupRefCount(g)
        val members = staff.count { it.skillIdx == g }
        val memberNote = if (members > 0) "所属していた職員${members}名のスキルは「(なし)」に戻ります（スキルグループのルールの対象から外れます）。" else "所属している職員はいません。"
        val refNote = if (refs > 0) " このスキルグループを使うルールが${refs}件あります。削除すると、そのルールは使われなくなります。" else ""
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { DialogHeader("スキルグループを削除しますか？", { confirmDelete = null }) },
            text = { Text("「$name」を削除します。${memberNote}元に戻すで取り消せます。$refNote") },
            confirmButton = { DialogDangerButton("削除する", onClick = { onEvent(MagiEvent.Structure.RemoveSkillGroup(g)); confirmDelete = null }) },
            dismissButton = { DialogDismissButton(onClick = { confirmDelete = null }, text = "削除しない") },
        )
    }
}

private sealed interface SkillDlg {
    object Add : SkillDlg
    data class Edit(val g: Int, val name: String, val kigou: String) : SkillDlg
}

@Composable
private fun SkillGroupDialog(title: String, name0: String, kigou0: String, onOk: (String, String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(name0) }
    var kigou by remember { mutableStateOf(kigou0) }
    // [3.646.0 U01] 入力の途中で閉じるときは確認（他の編集ダイアログと同じ）。
    val dirty = name != name0 || kigou != kigou0
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestClose: () -> Unit = { if (dirty) confirmDiscard = true else onClose() }
    if (confirmDiscard) DiscardConfirmDialog(onDiscard = { confirmDiscard = false; onClose() }, onContinue = { confirmDiscard = false })
    AlertDialog(
        onDismissRequest = requestClose,
        confirmButton = { DialogConfirmButton("保存する", enabled = kigou.isNotBlank(), onClick = { if (kigou.isNotBlank()) onOk(name, kigou) }) },
        dismissButton = { DialogDismissButton(onClick = requestClose) },
        title = { DialogHeader(title, requestClose) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = kigou, onValueChange = { if (it.length <= 4) kigou = it }, label = { Text("記号（例: N）") }, singleLine = true)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名前（例: 看護）") }, singleLine = true)
            }
        },
    )
}
