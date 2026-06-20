package com.magi.app.ui

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
            Text("個人別の回数（上下限・適切回数）", style = MaterialTheme.typography.titleMedium)
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
            onApply = { i, k, lo, hi -> vm.setStaffRange(i, k, lo, hi); dialog = null },
            onClose = { dialog = null },
        )
    }
}

private data class StaffRangeEdit(val i: Int, val k: Int, val lo: String, val hi: String)

@Composable
private fun StaffRangeDialog(
    init: StaffRangeEdit,
    staff: List<String>,
    shifts: List<String>,
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
        title = { Text("個人別の回数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        shifts.forEachIndexed { idx, kg ->
                            DropdownMenuItem(text = { Text(kg) }, onClick = { k = idx; openK = false })
                        }
                    }
                }
                NumberStepper("下限", lo, { lo = it }, min = 0, blankLabel = "なし")
                NumberStepper("上限", hi, { hi = it }, min = 0, blankLabel = "なし")
            }
        },
    )
}
