package com.magi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.magi.app.v6.PreRunCheck

/** [つくる前の確認] 本実行の前に 1 度だけ出す。行を押すとセルへ（セルを持たない行は入力箇所へ＝3.644.0）、「このままつくる」は 1 タップで始める。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreRunCheckSheet(
    summary: PreRunCheck.Summary,
    ui: UiState,
    onOpenCell: (staff: Int, day: Int, wish: Boolean) -> Unit,
    onOpenLanding: (EditLanding) -> Unit,
    onShowWishes: (() -> Unit)?,
    onFixData: () -> Unit,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val t = remember(summary, ui.staffNames, ui.shiftSymbols, ui.startDate) { preRunSheetText(summary, ui) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DialogHeader("つくる前の確認", onDismiss)
            t.floorHeader?.let { h ->
                Text("■ $h", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(PRE_RUN_FLOOR_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                t.zeroCapNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                t.floorRows.forEach { PreRunRowView(it, onOpenCell, onOpenLanding) }
                if (t.hasWishRows && onShowWishes != null) {
                    OutlinedButton(onClick = onShowWishes, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("ぶつかっている希望を見る") }
                }
            }
            t.rerunHeader?.let { h ->
                Text("■ $h", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text(PRE_RUN_RERUN_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                t.rerunRows.forEach { PreRunRowView(it, onOpenCell, onOpenLanding) }
                t.rerunRows.firstOrNull()?.let { r ->
                    OutlinedButton(onClick = { onOpenCell(r.staff ?: 0, r.day ?: 0, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("該当セルを見る") }
                }
            }
            t.overCapNote?.let { n ->
                Text("■ $PRE_RUN_OVERCAP_HEAD", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text(n, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                t.overCapRows.forEach { PreRunRowView(it, onOpenCell, onOpenLanding) }
            }
            t.wallLine?.let { w ->
                Text("■ 入れないシフト（個人の上限0）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text(w, style = MaterialTheme.typography.bodyMedium)
                t.wallLanding?.let { l ->
                    OutlinedButton(onClick = { onOpenLanding(l) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(landingButtonLabel(l)) }
                }
            }
            ui.preRunRepeatHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            OutlinedButton(onClick = onFixData, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)) { Text("先にデータを直す") }
            Button(onClick = onProceed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("このままつくる") }
        }
    }
}

@Composable
private fun PreRunRowView(row: PreRunRow, onOpenCell: (Int, Int, Boolean) -> Unit, onOpenLanding: (EditLanding) -> Unit) {
    val i = row.staff; val j = row.day; val l = row.landing
    if (i != null && j != null) {
        TextButton(onClick = { onOpenCell(i, j, row.wish) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(row.text, modifier = Modifier.fillMaxWidth())
        }
    } else if (l != null) {
        // セルを持たない行は、その原因の入力箇所へ（押す前に行き先を 1 行で示す）。
        TextButton(onClick = { onOpenLanding(l) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text(row.text)
                Text("→ ${landingButtonLabel(l)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    } else Text(row.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
}
