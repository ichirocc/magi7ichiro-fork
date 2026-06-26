package com.magi.app.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * ws5 移植: 個人別の回数（上下限 = LimMin/LimMax）。
 * staffRange["i,k"] = Range(lo, hi) を編集する。空＝制限なし。
 * モデル(staffRange)・エンジン(ct)は既存のため不変、UI のみ追加。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StaffRangeCard(ui: UiState, vm: MagiViewModel) {
    var dialog by remember { mutableStateOf<StaffRangeEdit?>(null) }
    val rows = vm.staffCountRules()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "各スタッフが各シフトを「1か月に何回」担当するか。上下限（個人別の制約）と適切回数（群の目標）の実効値を1か所で確認できます。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "「目標◯」=適切回数（群目標）。「目標A→B」はAが個人の上下限でBにクランプされた状態。「今◯」=現在の回数。🔴=下限割れ / 🟠=上限超過（適切回数の過不足も色で表示）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rows.isEmpty()) {
                Text(
                    "（設定なし）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // [統合] スタッフ単位に集約し、上下限(個人別)と適切回数(群目標)を同じチップ列に密に一覧。
                //   個人別の上下限ありはタップ＝編集 / ×＝削除。適切回数のみのセルもタップで個人別上下限を追加可能。
                rows.groupBy { it.i }.forEach { (_, list) ->
                    Text(list.first().staffName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { r ->
                            // 「今◯」= 現在の回数（編集中スケジュール）。過不足は countViolations で色分け。
                            val now = ui.schedule.getOrNull(r.i)?.count { it == r.k } ?: 0
                            val vio = ui.countViolations["${r.i},${r.k}"]
                            val range = when {
                                r.lo.isNotBlank() && r.hi.isNotBlank() -> "${r.lo}–${r.hi}"
                                r.hi.isNotBlank() -> "≤${r.hi}"
                                r.lo.isNotBlank() -> "≥${r.lo}"
                                else -> ""
                            }
                            val target = when {
                                r.aptEff < 0 -> ""
                                r.aptRaw >= 0 && r.aptRaw != r.aptEff -> "目標${r.aptRaw}→${r.aptEff}"
                                else -> "目標${r.aptEff}"
                            }
                            val lab = buildList {
                                add(r.kigou)
                                if (range.isNotBlank()) add(range)
                                if (target.isNotBlank()) add(target)
                                add("・今$now")
                            }.joinToString(" ")
                            val chipColors = when (vio) {
                                "vio-low", "vio-aptLow" -> InputChipDefaults.inputChipColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.30f))
                                "vio-high", "vio-aptHigh" -> InputChipDefaults.inputChipColors(containerColor = Color(0xFFF59E0B).copy(alpha = 0.36f))
                                else -> InputChipDefaults.inputChipColors()
                            }
                            InputChip(
                                selected = false,
                                enabled = !ui.running,
                                onClick = { dialog = StaffRangeEdit(r.i, r.k, r.lo, r.hi) },
                                label = { Text(lab) },
                                colors = chipColors,
                                // 適切回数のみ（個人別の上下限なし）のチップは削除対象が無いので × を出さない。
                                trailingIcon = if (r.hasRange) {
                                    {
                                        Icon(Icons.Filled.Close, contentDescription = "削除",
                                            modifier = Modifier.size(18.dp).clickable(enabled = !ui.running) { vm.removeStaffRange(r.i, r.k) })
                                    }
                                } else null,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
            AddRowButton("上下限を追加", onClick = { dialog = StaffRangeEdit(0, 0, "", "") }, enabled = ui.loaded && !ui.running)
        }
    }
    dialog?.let { d ->
        StaffRangeDialog(
            init = d,
            staff = ui.staffNames,
            shifts = vm.shiftKigouList(),
            allowedFor = { idx -> vm.allowedShiftsFor(idx).toHashSet() },
            onApply = { i, k, lo, hi -> vm.setStaffRange(i, k, lo, hi); dialog = null },
            onClose = { dialog = null },
        )
    }
}

internal data class StaffRangeEdit(val i: Int, val k: Int, val lo: String, val hi: String)

@Composable
internal fun StaffRangeDialog(
    init: StaffRangeEdit,
    staff: List<String>,
    shifts: List<String>,
    allowedFor: (Int) -> Set<Int>,
    onApply: (Int, Int, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var i by remember { mutableStateOf(init.i) }
    var k by remember { mutableStateOf(init.k) }
    var lo by remember { mutableStateOf(init.lo) }
    var hi by remember { mutableStateOf(init.hi) }
    var openS by remember { mutableStateOf(false) }
    var openK by remember { mutableStateOf(false) }
    val ok = i in staff.indices && k in shifts.indices && (lo.isNotBlank() || hi.isNotBlank())
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            DialogConfirmButton("適用", enabled = ok, onClick = { if (ok) onApply(i, k, lo.trim(), hi.trim()) })
        },
        dismissButton = { DialogDismissButton(onClick = onClose) },
        title = { DialogHeader("個人別の回数", onClose) },
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
                Text("シフト", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { openK = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(shifts.getOrNull(k) ?: "(選択)")
                    }
                    DropdownMenu(expanded = openK, onDismissRequest = { openK = false }) {
                        // [A6] 選択中スタッフが担当できるシフトのみ表示（担当不可シフトに下限を付ける矛盾を防ぐ。
                        //   guidance も事後検出するが、ここで入力時に防止して二重防御）。
                        val allowed = allowedFor(i)
                        shifts.forEachIndexed { idx, kg ->
                            if (idx in allowed) DropdownMenuItem(text = { Text(kg) }, onClick = { k = idx; openK = false })
                        }
                    }
                }
                NumberStepper("下限", lo, { lo = it }, min = 0, blankLabel = "なし")
                NumberStepper("上限", hi, { hi = it }, min = 0, blankLabel = "なし")
            }
        },
    )
}

// ---- グループ単位の回数（一括）: 選んだグループの全職員に同じ上下限を設定する。
//   内部は既存 staffRange への展開（vm.setGroupRange）＝新制約・スコア評価器の変更なし。 ----
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupRangeCard(ui: UiState, vm: MagiViewModel) {
    var dialog by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "グループ単位でシフトの回数を一括設定します（個人レンジ ws5 に書込み）。既に個人で設定済みの職員は上書きせず保持します。最低=最高の単一値ならグループ別の適切回数(ws1 C)も同時に設定します。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // [適用済み一覧] 一括適用したグループ上下限(全メンバー同一レンジ)を表示。各メンバーの個人の回数にも
            //   展開済みだが、ここでグループ単位に集約して確認・削除できるようにする。×=全員分クリア。
            val applied = vm.groupRangeSummary()
            if (applied.isNotEmpty()) {
                Text("適用中のグループ上下限（${applied.size}件・個人の回数にも展開済み）",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    applied.forEach { gr ->
                        val rangeLab = when {
                            gr.lo.isNotBlank() && gr.hi.isNotBlank() -> "${gr.lo}–${gr.hi}"
                            gr.hi.isNotBlank() -> "≤${gr.hi}"
                            gr.lo.isNotBlank() -> "≥${gr.lo}"
                            else -> ""
                        }
                        InputChip(
                            selected = false,
                            enabled = !ui.running,
                            onClick = { dialog = true },
                            label = { Text("${gr.groupName}·${gr.kigou} $rangeLab（${if (gr.shared >= gr.members) "${gr.members}" else "${gr.shared}/${gr.members}"}名）") },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, contentDescription = "削除",
                                    modifier = Modifier.size(18.dp).clickable(enabled = !ui.running) { vm.clearGroupRange(gr.g, gr.k, gr.lo, gr.hi) })
                            },
                        )
                    }
                }
            }
            AddRowButton("グループに上下限を適用", onClick = { dialog = true }, enabled = ui.loaded && !ui.running)
        }
    }
    if (dialog) {
        GroupRangeDialog(
            groups = vm.groupLabels(),
            shifts = vm.shiftKigouList(),
            allowedFor = { g -> vm.allowedShiftsForGroup(g) },
            memberCount = { g -> vm.groupMemberCount(g) },
            onApply = { g, k, lo, hi -> vm.setGroupRange(g, k, lo, hi); dialog = false },
            onClose = { dialog = false },
        )
    }
}

@Composable
internal fun GroupRangeDialog(
    groups: List<String>,
    shifts: List<String>,
    allowedFor: (Int) -> Set<Int>,
    memberCount: (Int) -> Int,
    onApply: (Int, Int, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var g by remember { mutableStateOf(0) }
    var k by remember { mutableStateOf(0) }
    var lo by remember { mutableStateOf("") }
    var hi by remember { mutableStateOf("") }
    var openG by remember { mutableStateOf(false) }
    var openK by remember { mutableStateOf(false) }
    val allowed = allowedFor(g)
    val ok = g in groups.indices && k in allowed && (lo.isNotBlank() || hi.isNotBlank())
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            DialogConfirmButton("適用", enabled = ok, onClick = { if (ok) onApply(g, k, lo.trim(), hi.trim()) })
        },
        dismissButton = { DialogDismissButton(onClick = onClose) },
        title = { DialogHeader("グループ単位の回数", onClose) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("グループ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { openG = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(groups.getOrNull(g)?.let { "$it（${memberCount(g)}名）" } ?: "(選択)")
                    }
                    DropdownMenu(expanded = openG, onDismissRequest = { openG = false }) {
                        groups.forEachIndexed { idx, n ->
                            DropdownMenuItem(text = { Text("$n（${memberCount(idx)}名）") }, onClick = { g = idx; k = 0; openG = false })
                        }
                    }
                }
                Text("シフト（全員が担当可のもの）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { openK = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(shifts.getOrNull(k)?.takeIf { k in allowed } ?: "(選択)")
                    }
                    DropdownMenu(expanded = openK, onDismissRequest = { openK = false }) {
                        shifts.forEachIndexed { idx, kg ->
                            if (idx in allowed) DropdownMenuItem(text = { Text(kg) }, onClick = { k = idx; openK = false })
                        }
                    }
                }
                NumberStepper("下限", lo, { lo = it }, min = 0, blankLabel = "なし")
                NumberStepper("上限", hi, { hi = it }, min = 0, blankLabel = "なし")
                Text("全員の個人上下限(ws5)に設定し、最低=最高なら適切回数(ws1 C)も同時に設定します（既存の個人設定は上書き）。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
