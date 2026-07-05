package com.magi.app.ui

import com.magi.app.toHankakuKigou

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * [回数設定画面 / スマホ最適化] シフト軸・個人軸の2タブ。高密度リスト＋アコーディオン＋インライン編集。
 * apt(理想)・cons41(群の最少/最大)・staffRange(個人の最少/最大) を1画面に統合（組み合わせ禁止は別画面）。
 * 設計方針: 「どのシフトを見るか」で業務するため シフト中心 を主軸に。片手操作・画面遷移ゼロ。
 */
@Composable
fun CountSettingsCard(ui: UiState, vm: MagiViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }       // 0=シフト 1=個人
    var query by rememberSaveable { mutableStateOf("") }
    var shiftFilter by rememberSaveable { mutableStateOf(-1) }  // -1=全部
    var openBlock by rememberSaveable { mutableStateOf("") }    // 展開中アコーディオン
    var editRow by rememberSaveable { mutableStateOf("") }      // インライン編集中の行
    var showAddRange by rememberSaveable { mutableStateOf(false) }  // [追加導線] 個人の上下限 追加ダイアログ
    val q = query.trim()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("回数設定（シフト軸 / 個人軸）", style = MaterialTheme.typography.titleMedium)
            MagiSegmentedControl(listOf("シフト", "個人"), tab, onSelect = { tab = it; openBlock = ""; editRow = "" })
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("検索（シフト・グループ・職員名）") }, modifier = Modifier.fillMaxWidth(),
            )
            if (tab == 0) {
                // シフトフィルター（横スクロール・上部固定）
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = shiftFilter == -1, onClick = { shiftFilter = -1 }, label = { Text("全部") })
                    vm.shiftKigouList().forEachIndexed { k, kg ->
                        FilterChip(selected = shiftFilter == k, onClick = { shiftFilter = k }, label = { Text(kg) })
                    }
                }
                ShiftAxisList(ui, vm, q, shiftFilter, openBlock, { openBlock = it }, editRow, { editRow = it })
            } else {
                StaffAxisList(ui, vm, q, openBlock, { openBlock = it }, editRow, { editRow = it })
            }
            // [追加導線] この画面には個人の上下限を新規追加する手段が無かった（既存行はタップで編集/削除できるが
            //   手がかりが無く、未設定シフトやレンジ皆無の職員は一覧に出ないため到達不能だった）。
            //   職員×シフトの上下限をここから追加できるようにする（職員ドロップダウンで未表示の職員も選べる）。
            AddRowButton("個人の上下限を追加", onClick = { showAddRange = true }, enabled = ui.loaded && !ui.running)
        }
    }
    if (showAddRange) {
        StaffRangeDialog(
            init = StaffRangeEdit(0, 0, "", ""),
            staff = ui.staffNames,
            shifts = vm.shiftKigouList(),
            allowedFor = { idx -> vm.allowedShiftsFor(idx).toHashSet() },
            onApply = { i, k, lo, hi -> vm.setStaffRange(i, k, lo, hi); showAddRange = false },
            onClose = { showAddRange = false },
        )
    }
}

/** 値の密表示「最少｜理想｜最大」または「最少｜最大」。空は - 。 */
private fun cell(v: String) = v.ifBlank { "-" }

@Composable
private fun BlockHeader(label: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onToggle() }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (open) "▼" else "▶", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ShiftAxisList(
    ui: UiState, vm: MagiViewModel, q: String, shiftFilter: Int,
    openBlock: String, onOpen: (String) -> Unit, editRow: String, onEdit: (String) -> Unit,
) {
    val blocks = vm.shiftRuleBlocks().filter { b ->
        (shiftFilter == -1 || shiftFilter == b.k) &&
            (q.isBlank() || b.kigou.contains(q) || b.name.contains(q) ||
                b.groups.any { it.groupName.contains(q) } || b.indivs.any { it.staffName.contains(q) })
    }
    if (blocks.isEmpty()) {
        Text("（設定なし）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    blocks.forEach { b ->
        val bk = "shift-${b.k}"
        BlockHeader("${toHankakuKigou(b.kigou)}  ${b.name}", openBlock == bk) { onOpen(if (openBlock == bk) "" else bk) }
        if (openBlock == bk) {
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (b.groups.isNotEmpty()) {
                    Text("グループ（1か月の目標回数）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("※ 目標は各職員の月 最少〜最大 でクランプされます", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    b.groups.forEach { g ->
                        val rk = "g-${b.k}-${g.g}"
                        Text(
                            "${g.groupName}   目標 ${if (g.ideal.isBlank()) "なし" else "${g.ideal}回"}   ✎",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !ui.running) { onEdit(if (editRow == rk) "" else rk) }.padding(vertical = 6.dp),
                        )
                        if (editRow == rk) {
                            NumberStepper("目標", g.ideal, { vm.ws1SetGroupApt(g.g, b.k, it) }, 0, "なし")
                            if (g.ideal.isNotBlank()) TextButton(onClick = { vm.ws1SetGroupApt(g.g, b.k, ""); onEdit("") }, enabled = !ui.running) { Text("削除") }
                        }
                    }
                }
                if (b.indivs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("個人（1か月 最少｜最大）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    b.indivs.forEach { iv ->
                        val rk = "i-${b.k}-${iv.i}"
                        Text(
                            "${iv.staffName}   ${cell(iv.min)}〜${cell(iv.max)}回   ✎",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !ui.running) { onEdit(if (editRow == rk) "" else rk) }.padding(vertical = 6.dp),
                        )
                        if (editRow == rk) {
                            NumberStepper("最少", iv.min, { vm.setStaffRange(iv.i, b.k, it, iv.max) }, 0, "なし")
                            NumberStepper("最大", iv.max, { vm.setStaffRange(iv.i, b.k, iv.min, it) }, 0, "なし")
                            TextButton(onClick = { vm.removeStaffRange(iv.i, b.k); onEdit("") }, enabled = !ui.running) { Text("削除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaffAxisList(
    ui: UiState, vm: MagiViewModel, q: String,
    openBlock: String, onOpen: (String) -> Unit, editRow: String, onEdit: (String) -> Unit,
) {
    val blocks = vm.staffRuleBlocks().filter { b ->
        q.isBlank() || b.name.contains(q) || b.rows.any { it.kigou.contains(q) }
    }
    if (blocks.isEmpty()) {
        Text("（設定なし）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    blocks.forEach { b ->
        val bk = "staff-${b.i}"
        BlockHeader(b.name, openBlock == bk) { onOpen(if (openBlock == bk) "" else bk) }
        if (openBlock == bk) {
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("シフトごとの回数（1か月 最少｜最大）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                b.rows.forEach { r ->
                    val rk = "s-${b.i}-${r.k}"
                    Text(
                        "${toHankakuKigou(r.kigou)}   ${cell(r.min)}〜${cell(r.max)}回   ✎",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !ui.running) { onEdit(if (editRow == rk) "" else rk) }.padding(vertical = 6.dp),
                    )
                    if (editRow == rk) {
                        NumberStepper("最少", r.min, { vm.setStaffRange(b.i, r.k, it, r.max) }, 0, "なし")
                        NumberStepper("最大", r.max, { vm.setStaffRange(b.i, r.k, r.min, it) }, 0, "なし")
                        TextButton(onClick = { vm.removeStaffRange(b.i, r.k); onEdit("") }, enabled = !ui.running) { Text("削除") }
                    }
                }
            }
        }
    }
}
