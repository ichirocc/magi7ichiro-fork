package com.magi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [3.720.0 3c-1・S-1] 職員管理と年間マスターの見出しカード（題・副題・点・主ボタン）。中身は [editHeaderPlan]。
 * [onPrimary] があるときだけ主ボタンを出す（職員管理の主「職員を追加する」は一覧カードの中）。塗りは主が OPEN_MASTER のときだけ（INV-09）。
 */
@Composable
internal fun ScopeHeaderCard(plan: EditHeaderPlan, onPrimary: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(plan.title, style = MaterialTheme.typography.titleMedium)
            Text(plan.subtitle, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            plan.points.forEach { Text("・$it", style = MaterialTheme.typography.bodyMedium) }
            plan.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error) }
            if (onPrimary != null && plan.primary != null) {
                if (plan.primary == EditHeaderPrimary.OPEN_MASTER) {
                    Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(OPEN_MASTER_BUTTON) }
                } else {
                    OutlinedButton(onClick = onPrimary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(OPEN_MASTER_BUTTON) }
                }
            }
        }
    }
}

/** 担当できる人数が 1 日に要る最大の人数に届かないシフトの数（[StaffingRealityCard] と同じ数え方）。 */
internal fun shortShiftCount(ui: UiState, cv: ConditionsView): Int {
    if (!ui.loaded) return 0
    val canDo = IntArray(ui.shiftSymbols.size)
    for (i in ui.staffNames.indices) cv.allowedShiftsFor(i).forEach { k -> if (k in canDo.indices) canDo[k]++ }
    return ui.shiftSymbols.indices.count { k ->
        var mx = 0
        for (j in 0 until ui.days) { val lo = cv.needCellLimits(k, j)?.first ?: 0; if (lo > mx) mx = lo }
        mx > 0 && canDo[k] < mx
    }
}
