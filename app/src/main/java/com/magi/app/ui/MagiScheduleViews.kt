package com.magi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import java.time.LocalDate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.CoverageVerdict
import com.magi.app.v6.MirrorKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

/**
 * CSVのバイト列を文字列へ復号する。妥当な UTF-8 ならそれを採用し、そうでなければ日本の Excel CSV で
 * 一般的な CP932(Shift-JIS) とみなす。先頭の BOM は除去する。これにより Shift-JIS の勤務表CSVが
 * 文字化けせず取り込める（UTF-8 として bytes を読むと壊れていた）。
 */

@Composable
internal fun LiveScheduleCard(ui: UiState) {
    if (!ui.running || ui.liveSchedule.isEmpty()) return
    var show by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val cur = ui.liveSchedule
            // 変化セル検出: 前回スナップショットとの差分。holder(非state)で保持し再合成ループを避ける。
            val prevHolder = remember { arrayOfNulls<List<List<Int>>>(1) }
            val changed = remember(cur) {
                val set = HashSet<Int>()
                val p = prevHolder[0]
                if (p != null && p.size == cur.size) {
                    for (i in cur.indices) {
                        val a = p[i]; val b = cur[i]
                        if (a.size == b.size) for (j in b.indices) if (a[j] != b[j]) set.add(i * 100000 + j)
                    }
                }
                prevHolder[0] = cur
                set
            }
            TextButton(onClick = { show = !show }, modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(if (show) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(if (show) "途中経過を隠す" else "途中経過を見る（組んでいる様子）")
            }
            if (show) {
                Text("状態遷移  赤枠＝今回変化 (${changed.size})", fontSize = 11.sp, color = cs.onSurfaceVariant)
                Column(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    cur.forEachIndexed { i, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            row.forEachIndexed { j, k ->
                                val color = if (k < 0) cs.surfaceVariant else hexToColor(ui.shiftColorHex.getOrNull(k) ?: "")
                                val isChanged = changed.contains(i * 100000 + j)
                                Box(
                                    Modifier
                                        .size(11.dp)
                                        .background(color, RoundedCornerShape(2.dp))
                                        .then(if (isChanged) Modifier.border(2.dp, cs.error, RoundedCornerShape(2.dp)) else Modifier),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [operator_ux §5] 「なおすのを手伝って」対話。人手不足を1タップで埋める誘導フロー。
 * いまの診断(coverageDiag)から「充足可能」な不足枠を1つ取り上げ、入れられる職員を大ボタンで提示。
 * タップ→反映(setCell, Undo可)→診断が更新され次の枠へ自動で進む。埋められない枠は理由つきで提示。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShiftPickerSheet(
    ui: UiState,
    vm: MagiViewModel,
    cell: Pair<Int, Int>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val (i, j) = cell
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    val allowed = remember(cell) { vm.allowedShiftsFor(i).toList() }
    val current = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
    val wish = ui.wishes["$i,$j"]
    var mode by remember(cell) { mutableIntStateOf(0) } // 0=割当, 1=希望
    val name = ui.staffNames.getOrNull(i) ?: i.toString()
    fun sym(k: Int?): String = k?.let { ui.shiftSymbols.getOrNull(it) } ?: "—"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$name ・ ${j + 1}日", style = MaterialTheme.typography.titleMedium)
            // 凝縮ステータス: 現在の割当 + 希望
            Surface(color = cs.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("現在の割当  ${sym(current)}", style = MaterialTheme.typography.bodyMedium)
                    val wt = if (wish == null) "希望  未登録"
                        else "希望  ${sym(wish)}" + (if (wish == current) "（反映済）" else "（未反映）")
                    Text(wt, style = MaterialTheme.typography.bodyMedium,
                        color = if (wish != null && wish != current) MagiAccent.pink else cs.onSurfaceVariant)
                }
            }
            // 希望どおりにする（割当モード・未反映・担当可のときだけ）= 最頻操作を1タップ
            if (mode == 0 && wish != null && wish != current && wish in allowed) {
                Button(onClick = { onPick(wish) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("希望どおり ${sym(wish)} にする")
                }
            }
            // 割当/希望 トグル
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("割当を変更", "希望を変更").forEachIndexed { idx, label ->
                    val selSeg = mode == idx
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp)
                            .background(if (selSeg) cs.primaryContainer else cs.surfaceVariant, RoundedCornerShape(12.dp))
                            .clickable { mode = idx },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (selSeg) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            fontWeight = if (selSeg) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Text(
                if (mode == 0) "タップで割当を即変更。" else "タップで希望を登録/変更（即確定）。「外」=担当外（登録可・配置で違反）。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
            )
            val opts = if (mode == 0) (if (allowed.isNotEmpty()) allowed else ui.shiftSymbols.indices.toList())
                       else ui.shiftSymbols.indices.toList()
            opts.chunked(4).forEach { rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowKeys.forEach { k ->
                        val symbol = ui.shiftSymbols.getOrNull(k) ?: k.toString()
                        val sel = if (mode == 0) k == current else k == wish
                        val ng = mode == 1 && k !in allowed
                        val bg = if (sel) cs.primary else hexToColor(ui.shiftColorHex.getOrNull(k) ?: "")
                        val fg = if (sel) cs.onPrimary else hexToColor(ui.shiftTextHex.getOrNull(k) ?: "")
                        // [結果プレビュー] 割当モードのみ: 現在/希望/不足解消/超過 を注記（needViolations から確実に判定）。
                        val noteParts = ArrayList<String>()
                        var noteWarn = false
                        if (mode == 0) {
                            if (k == current) noteParts.add("現在") else if (k == wish) noteParts.add("希望")
                            when (ui.needViolations["$k,$j"]) {
                                "vio-covU" -> noteParts.add("不足解消")
                                "vio-covO" -> { noteParts.add("超過"); noteWarn = true }
                            }
                        }
                        val note = noteParts.joinToString("・")
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .background(bg, RoundedCornerShape(16.dp))
                                .then(if (ng) Modifier.border(2.dp, cs.error, RoundedCornerShape(16.dp)) else Modifier)
                                .clickable {
                                    if (mode == 0) onPick(k) else { vm.setWish(i, j, k); onDismiss() }
                                }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(symbol + (if (ng) " 外" else ""), color = if (ng) cs.error else fg, fontWeight = FontWeight.Bold)
                                if (note.isNotEmpty()) {
                                    Text(note, style = MaterialTheme.typography.labelSmall,
                                        color = if (noteWarn) MagiAccent.orange else if (sel) cs.onPrimary else MagiAccent.green)
                                }
                            }
                        }
                    }
                    repeat(4 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            // 希望を削除（希望モード・登録済みのみ）
            if (mode == 1 && wish != null) {
                OutlinedButton(onClick = { vm.removeWish(i, j); onDismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Text("希望を削除（希望なし）", color = cs.error)
                }
            }
        }
    }
}


@Composable
internal fun StaffCalendarCard(ui: UiState, onCellClick: (Int, Int) -> Unit) {
    if (ui.schedule.isEmpty() || ui.staff == 0) return
    var staffIdx by remember { mutableIntStateOf(0) }
    val si = staffIdx.coerceIn(0, (ui.staff - 1).coerceAtLeast(0))
    val row = ui.schedule.getOrNull(si) ?: return
    val labels = ui.v6?.dayRisks?.map { it.label } ?: (0 until ui.days).map { "${it + 1}日" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("スタッフ別カレンダー", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { staffIdx = (si - 1).floorMod(ui.staff) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("前") }
                OutlinedButton(onClick = { staffIdx = (si + 1).floorMod(ui.staff) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("次") }
            }
            Text(
                "${ui.staffNames.getOrNull(si) ?: si} / ${ui.staffGroupSymbols.getOrNull(si) ?: ""} — タップで担当可能シフトを巡回",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            val vioColor = ui.violationColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: MaterialTheme.colorScheme.error
            row.indices.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { j ->
                        val k = row.getOrNull(j) ?: -1
                        val symbol = if (k < 0) "·" else ui.shiftSymbols.getOrNull(k) ?: k.toString()
                        val vioVal = ui.violationCells["$si,$j"]
                        val vio = vioVal != null
                        val hard = isHardCellViolation(vioVal)
                        CalendarCell(labels.getOrNull(j) ?: "${j + 1}日", symbol, vio, hard, vioColor, Modifier.weight(1f)) {
                            onCellClick(si, j)
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}


@Composable
internal fun CalendarCell(label: String, symbol: String, violation: Boolean, hard: Boolean, vioColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (violation) cs.errorContainer else cs.surfaceVariant
    val labelFg = if (violation) cs.onErrorContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant
    val symbolFg = if (violation) cs.onErrorContainer else cs.onSurface
    // [UD/WCAG 4.1.2] 色・形に依存しない読み上げ名。
    val a11y = "$label シフト ${symbol.ifBlank { "なし" }}" + (if (violation) (if (hard) "・必須違反" else "・要調整") else "") + "、タップで変更"
    Box(
        modifier
            .height(58.dp)
            .padding(horizontal = 2.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .then(if (violation) Modifier.violationBorder(hard, vioColor, 14.dp) else Modifier)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, color = labelFg, maxLines = 1)
            Text(symbol, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = symbolFg, maxLines = 1)
        }
    }
}


@Composable
internal fun ScheduleModeCard(
    editing: Boolean,
    canRead: Boolean,
    onSelect: (Boolean) -> Unit,
    onCommit: () -> Unit,
    onCopy: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // [校正] ラベルを平易化し「見るだけ/直す」を明示。選択中の区分で状態は分かるため、
            //   重複する「編集中・未確定」バッジは廃止し、説明を1行に集約（冗長解消）。
            MagiSegmentedControl(
                options = listOf("結果（見るだけ）", "下書き（直す）"),
                selected = if (editing) 1 else 0,
                onSelect = { onSelect(it == 1) },
            )
            Text(
                if (editing) "いまは下書きを直しています。よければ［結果に反映］。最適化もこの下書きが起点です。"
                else "確定した結果を見ています（このままでは変えられません）。直すには「下書き（直す）」へ。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (editing && canRead) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onCommit, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("結果に反映") }
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("結果を複製") }
                }
            }
        }
    }
}


@Composable
internal fun ScheduleGrid(
    ui: UiState, onCellClick: (Int, Int) -> Unit, proMode: Boolean = false,
    onBulkSet: (Collection<Pair<Int, Int>>, Int) -> Unit = { _, _ -> },
) {
    val cs = MaterialTheme.colorScheme
    val vioColor = ui.violationColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: cs.error
    val win = 7
    // [プロ編集] プロ表示は高密度1ヶ月俯瞰を既定に（かんたんは7日表示）。
    var gridMode by rememberSaveable { mutableStateOf(if (proMode) 2 else 0) } // 0=7日 / 1=カレンダー / 2=1ヶ月俯瞰
    // [プロ一括編集] 1ヶ月俯瞰での複数選択（i*100000+j のキー集合）。
    val proSel = remember { mutableStateListOf<Long>() }
    var page by rememberSaveable { mutableStateOf(0) }
    val maxPage = if (ui.days <= win) 0 else (ui.days - 1) / win
    val cur = page.coerceIn(0, maxPage)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("勤務表", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // 表示切替: §4.5 セグメント（7日=大きく編集 / カレンダー=月の俯瞰 / 1ヶ月=全セル色確認）
            MagiSegmentedControl(
                options = listOf("7日表示", "カレンダー", "1ヶ月"),
                selected = gridMode,
                onSelect = { gridMode = it },
            )
            if (ui.violationCells.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ViolationLegend(vioColor)
            }
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints {
                val totalW = maxWidth
                if (gridMode == 0) {
                    val staffW = 64.dp
                    val sumW = 44.dp   // [集計] 右端「勤務」計の列幅
                    val cellW = ((totalW - staffW - sumW) / win).coerceIn(34.dp, 80.dp)
                    val startDay = cur * win
                    val endDay = minOf(startDay + win, ui.days)
                    Column(
                        Modifier.pointerInput(cur, maxPage) {
                            var dx = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (dx > 60f) page = (cur - 1).coerceAtLeast(0)
                                    else if (dx < -60f) page = (cur + 1).coerceAtMost(maxPage)
                                    dx = 0f
                                },
                            ) { _, amount -> dx += amount }
                        },
                    ) {
                        Text("◀ ▶ かスワイプで日付を送り、セルをタップでシフト選択。",
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { page = (cur - 1).coerceAtLeast(0) }, enabled = cur > 0,
                                modifier = Modifier.height(48.dp)) { Text("◀ 前") }
                            Text("${startDay + 1}〜${endDay}日 / 全${ui.days}日",
                                style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f))
                            Button(onClick = { page = (cur + 1).coerceAtMost(maxPage) }, enabled = cur < maxPage,
                                modifier = Modifier.height(48.dp)) { Text("次 ▶") }
                        }
                        Spacer(Modifier.height(10.dp))
                        // [集計] 休/公以外を「勤務」とみなす。各スタッフの勤務数・各日の出勤数・シフト別合計を表示。
                        val restIdx = ui.shiftSymbols.indexOfFirst { it == "休" || it == "公" }
                        Row {
                            Column {
                                HeaderCell("スタッフ", staffW)
                                ui.schedule.indices.forEach { i ->
                                    StaffCell(ui.staffNames.getOrNull(i) ?: i.toString(),
                                        ui.staffGroupSymbols.getOrNull(i) ?: "", w = staffW)
                                }
                                Box(Modifier.width(staffW).height(30.dp).padding(2.dp), contentAlignment = Alignment.CenterStart) {
                                    Text("各日の出勤", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                                }
                            }
                            Column {
                                Row { for (j in startDay until endDay) HeaderCell((j + 1).toString(), cellW) }
                                ui.schedule.forEachIndexed { i, row ->
                                    Row {
                                        for (j in startDay until endDay) {
                                            val k = row.getOrNull(j) ?: -1
                                            val vioVal = ui.violationCells["$i,$j"]
                                            val vio = vioVal != null
                                            val hard = isHardCellViolation(vioVal)
                                            val symbol = if (k < 0) "·" else ui.shiftSymbols.getOrNull(k) ?: k.toString()
                                            val bg = if (k < 0) cs.surface else hexToColor(ui.shiftColorHex.getOrNull(k) ?: "")
                                            val fg = if (k < 0) cs.onSurfaceVariant else hexToColor(ui.shiftTextHex.getOrNull(k) ?: "")
                                            val wk = ui.wishes["$i,$j"]
                                            val wsym = wk?.let { ui.shiftSymbols.getOrNull(it) }
                                            val wmet = wk != null && wk == k
                                            Cell(symbol, vio, hard, bg, fg, vioColor, w = cellW, wishSym = wsym, wishMet = wmet) { onCellClick(i, j) }
                                        }
                                    }
                                }
                                // 各日の出勤人数（休/公を除く）。不足日は赤＋⚠で強調＝違反箇所が一目で分かる。
                                Row {
                                    for (j in startDay until endDay) {
                                        val working = ui.schedule.count { (it.getOrNull(j) ?: -1).let { k -> k >= 0 && k != restIdx } }
                                        val short = ui.v6?.dayRisks?.getOrNull(j)?.shortage ?: 0
                                        Box(Modifier.width(cellW).height(30.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                                            Text(if (short > 0) "$working⚠" else "$working",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (short > 0) cs.error else cs.onSurface,
                                                fontWeight = if (short > 0) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                        }
                                    }
                                }
                            }
                            Column {
                                HeaderCell("勤務", sumW)
                                ui.schedule.forEach { row ->
                                    val work = row.count { it >= 0 && it != restIdx }
                                    Box(Modifier.width(sumW).height(52.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                                        Text("$work", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                                    }
                                }
                                Box(Modifier.width(sumW).height(30.dp))
                            }
                        }
                        // [集計] シフト別の合計（期間全体）。横スクロールで全シフト確認。
                        Spacer(Modifier.height(10.dp))
                        Text("シフト別の合計（期間）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ui.shiftSymbols.indices.forEach { kk ->
                                val tot = ui.schedule.sumOf { row -> row.count { it == kk } }
                                if (tot > 0) {
                                    Box(
                                        Modifier
                                            .background(hexToColor(ui.shiftColorHex.getOrNull(kk) ?: ""), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text("${ui.shiftSymbols.getOrNull(kk) ?: ""} $tot",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = hexToColor(ui.shiftTextHex.getOrNull(kk) ?: ""), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                } else if (gridMode == 1) {
                    // §4.12 カレンダー: 週列の月グリッド。各日にシフト色ピル＋不足マーカー。
                    MagiCalendarMonthView(ui) { dayIdx ->
                        page = dayIdx / win
                        gridMode = 0
                    }
                } else {
                    val staffW = 60.dp
                    val d = ui.days.coerceAtLeast(1)
                    val cellW = ((totalW - staffW) / d).coerceAtLeast(6.dp)
                    Column {
                        if (proMode) {
                            // [プロ一括編集] セルを複数タップで選択 → シフトをタップで一括設定。
                            Text("プロ：セル/スタッフ名(行)/日付(列)をタップで複数選択し、下のシフトで一括変更（日付タップで列選択）。",
                                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    proSel.clear()
                                    for (i in ui.schedule.indices) for (j in 0 until ui.days) proSel.add(i * 100000L + j)
                                }, modifier = Modifier.heightIn(min = 40.dp)) { Text("すべて選択") }
                                OutlinedButton(onClick = {
                                    val cur = proSel.toHashSet()
                                    proSel.clear()
                                    for (i in ui.schedule.indices) for (j in 0 until ui.days) {
                                        val key = i * 100000L + j
                                        if (key !in cur) proSel.add(key)
                                    }
                                }, modifier = Modifier.heightIn(min = 40.dp)) { Text("選択を反転") }
                                if (proSel.isNotEmpty()) {
                                    OutlinedButton(onClick = { proSel.clear() }, modifier = Modifier.heightIn(min = 40.dp)) { Text("選択解除") }
                                }
                            }
                            if (proSel.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text("選択 ${proSel.size} マス → このシフトに一括：", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ui.shiftSymbols.indices.forEach { kk ->
                                        Box(
                                            Modifier.background(hexToColor(ui.shiftColorHex.getOrNull(kk) ?: ""), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    onBulkSet(proSel.map { (it / 100000L).toInt() to (it % 100000L).toInt() }, kk)
                                                    proSel.clear()
                                                }.padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) { Text(ui.shiftSymbols.getOrNull(kk) ?: "", color = hexToColor(ui.shiftTextHex.getOrNull(kk) ?: ""), maxLines = 1) }
                                    }
                                }
                            }
                        } else {
                            Text("月全体を色で確認できます。日付をタップするとその日の7日表示へ移動します。",
                                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Column {
                                Box(Modifier.width(staffW).height(22.dp))
                                ui.schedule.indices.forEach { i ->
                                    // [プロ一括編集] スタッフ名タップ＝その行(全日)をまとめて選択/解除。
                                    val rowMod = if (proMode) Modifier.clickable {
                                        val keys = (0 until ui.days).map { j -> i * 100000L + j }
                                        if (keys.all { proSel.contains(it) }) proSel.removeAll(keys)
                                        else keys.forEach { if (!proSel.contains(it)) proSel.add(it) }
                                    } else Modifier
                                    Box(Modifier.width(staffW).height(20.dp).padding(horizontal = 4.dp).then(rowMod),
                                        contentAlignment = Alignment.CenterStart) {
                                        Text(ui.staffNames.getOrNull(i) ?: i.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Column {
                                Row {
                                    for (j in 0 until ui.days) {
                                        Box(Modifier.width(cellW).height(22.dp)
                                            .semantics { contentDescription = if (proMode) "${j + 1}日（タップで列を選択）" else "${j + 1}日（タップで7日表示へ）" }
                                            .clickable {
                                                if (proMode) {
                                                    // 日付タップ＝その列(全スタッフ)をまとめて選択/解除。
                                                    val keys = ui.schedule.indices.map { i -> i * 100000L + j }
                                                    if (keys.all { proSel.contains(it) }) proSel.removeAll(keys)
                                                    else keys.forEach { if (!proSel.contains(it)) proSel.add(it) }
                                                } else { page = j / win; gridMode = 0 }
                                            },
                                            contentAlignment = Alignment.Center) {
                                            if (cellW >= 14.dp) Text((j + 1).toString(),
                                                style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                        }
                                    }
                                }
                                ui.schedule.forEachIndexed { i, row ->
                                    Row {
                                        for (j in 0 until ui.days) {
                                            val k = row.getOrNull(j) ?: -1
                                            val vioVal = ui.violationCells["$i,$j"]
                                            val vio = vioVal != null
                                            val hard = isHardCellViolation(vioVal)
                                            val bg = if (k < 0) cs.surfaceVariant else hexToColor(ui.shiftColorHex.getOrNull(k) ?: "")
                                            val symA = if (k < 0) "未割当" else (ui.shiftSymbols.getOrNull(k) ?: "")
                                            val staffA = ui.staffNames.getOrNull(i) ?: ""
                                            val selKey = i * 100000L + j
                                            val sel = proMode && proSel.contains(selKey)
                                            Box(Modifier.width(cellW).height(20.dp).padding(0.5.dp)
                                                .background(bg, RoundedCornerShape(4.dp))
                                                .then(if (vio) Modifier.violationBorder(hard, vioColor, 4.dp) else Modifier)
                                                .then(if (sel) Modifier.border(2.dp, cs.primary, RoundedCornerShape(4.dp)) else Modifier)
                                                .semantics { contentDescription = "$staffA ${j + 1}日 $symA" + if (vio) (if (hard) "（必須違反）" else "（要調整）") else "" + if (sel) "・選択中" else "" }
                                                .clickable {
                                                    if (proMode) { if (!proSel.remove(selKey)) proSel.add(selKey) }
                                                    else { page = j / win; gridMode = 0 }
                                                })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 期間開始日の曜日インデックス（0=月..6=日）。解析不能なら 0。 */

internal fun startDowMonFirst(startDate: String): Int = try {
    (LocalDate.parse(startDate).dayOfWeek.value - 1).coerceIn(0, 6)
} catch (_: Exception) { 0 }

/**
 * §4.12 月カレンダー: 週列（月〜日）の月グリッド。各日に「その日に多いシフト」の色ピルと、
 * 人員不足マーカー（赤・実線=必須）を出す。日付タップでその週の7日表示へ。
 */

@Composable
internal fun MagiCalendarMonthView(ui: UiState, onDayClick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val days = ui.days
    if (days <= 0 || ui.schedule.isEmpty()) {
        Text("勤務表がありません。", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        return
    }
    val restIdx = ui.shiftSymbols.indexOfFirst { it == "休" || it == "公" }
    val startDow = startDowMonFirst(ui.startDate)
    val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")
    // 日別シフト人数を一度だけ算出（schedule 変化時のみ再計算）。各セルで S×K を再走査しない。
    val k = ui.shiftSymbols.size
    val dayCounts = remember(ui.schedule, k, days) {
        Array(days) { d ->
            val counts = IntArray(k)
            ui.schedule.forEach { row ->
                val s = row.getOrNull(d) ?: -1
                if (s in 0 until k) counts[s]++
            }
            counts
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text("日付をタップでその週の7日表示へ。色はその日に多いシフト、赤枠は人員不足です。",
            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            weekdays.forEachIndexed { i, w ->
                // [校正②] 曜日ヘッダは低輝度でも判別できるよう一段大きく・太く、平日も onSurface で濃く。
                val wc = when (i) { 5 -> MagiAccent.blue; 6 -> MagiAccent.red; else -> cs.onSurface }
                Text(w, Modifier.weight(1f), color = wc, textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        val totalCells = startDow + days
        val rows = (totalCells + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 7) {
                    val dayIdx = r * 7 + c - startDow
                    if (dayIdx < 0 || dayIdx >= days) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        DayShiftCell(ui, dayIdx, restIdx, dayCounts[dayIdx], Modifier.weight(1f)) { onDayClick(dayIdx) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** §4.12 月カレンダーの1日分セル。日番号＋不足マーカー＋上位シフトの色ピル。 */

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DayShiftCell(ui: UiState, dayIdx: Int, restIdx: Int, counts: IntArray, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shortage = ui.v6?.dayRisks?.getOrNull(dayIdx)?.shortage ?: 0
    // [校正] その日の「すべての」シフトを表示（休は除く）。多い順に小ピルを折り返して並べる
    //   （従来は上位2件だけ＝一部しか見えなかった）。セルは中身に合わせて伸びる。
    val present = counts.indices
        .filter { it != restIdx && counts[it] > 0 }
        .sortedByDescending { counts[it] }
    Box(
        modifier
            .height(96.dp)
            .background(cs.surfaceVariant, MaterialTheme.shapes.medium)
            .then(if (shortage > 0) Modifier.border(2.dp, cs.error, MaterialTheme.shapes.medium) else Modifier)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${dayIdx + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = cs.onSurface, modifier = Modifier.weight(1f))
                if (shortage > 0) {
                    Box(Modifier.size(6.dp).background(cs.error, RoundedCornerShape(3.dp)))
                }
            }
            Spacer(Modifier.height(2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                present.forEach { k ->
                    ShiftEventPill(
                        symbol = ui.shiftSymbols.getOrNull(k) ?: "",
                        count = counts[k],
                        bg = hexToColor(ui.shiftColorHex.getOrNull(k) ?: ""),
                        fg = hexToColor(ui.shiftTextHex.getOrNull(k) ?: ""),
                    )
                }
            }
            if (shortage > 0 && present.isEmpty()) {
                Text("不足$shortage", style = MaterialTheme.typography.labelSmall, color = cs.error, maxLines = 1)
            }
        }
    }
}

/** §4.12 シフトの小ピル（カレンダー日セル内）。シフト色で塗り、記号＋人数。中身幅で複数並ぶ。 */

@Composable
internal fun ShiftEventPill(symbol: String, count: Int, bg: Color, fg: Color) {
    Box(
        Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Text("$symbol$count", color = fg, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 違反セルの凡例（実線=必須 / 破線=要調整）。非色手がかりの意味を必ず示す。 */

@Composable
internal fun ViolationLegend(vioColor: Color) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(width = 22.dp, height = 16.dp).border(3.dp, vioColor, RoundedCornerShape(4.dp)))
            Text("実線＝必須違反", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(width = 22.dp, height = 16.dp).violationBorder(false, vioColor, 4.dp))
            Text("破線＝要調整", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * [screen_spec #12/#168] 詳細設定（上級者/開発者向け・折りたたみ・既定=閉）。
 * 実験フラグ(FlagsView) / ログ(LogsCard) / 色トークン(ColorSettingsView) を 1 か所に隔離する。
 * 通常運用に不要な項目を分析・設定の主導線から外し、誤操作と画面の汚れを防ぐ。
 */

@Composable
internal fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(width).height(40.dp).padding(2.dp)) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}


@Composable
internal fun StaffCell(name: String, symbol: String, w: androidx.compose.ui.unit.Dp = 92.dp) {
    Box(Modifier.width(w).height(52.dp).padding(2.dp)) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (symbol.isNotBlank()) Text(symbol, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * 違反値("vio-<family>")が必須(HARD)系か判定。色に依らない手がかり(実線/破線)の切替に使う。
 * ハード族の一覧は MirrorKeys.hard を唯一の真実源とする（ここで列挙し直すと将来の追加/改名で乖離する）。
 */

internal fun isHardCellViolation(v: String?): Boolean =
    v != null && MirrorKeys.hard.any { v.contains(it) }

/** 違反セルの非色手がかり: HARD=実線枠、SOFT=破線枠（色覚多様性／モノクロ印刷でも区別可能）。
 *  [校正] 色付きセル上でも埋もれないよう枠を太く（3dp）。 */

internal fun Modifier.violationBorder(hard: Boolean, color: Color, radiusDp: androidx.compose.ui.unit.Dp): Modifier =
    if (hard) {
        this.border(3.dp, color, RoundedCornerShape(radiusDp))
    } else {
        this.drawBehind {
            val stroke = 3.dp.toPx()
            val r = radiusDp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
            )
        }
    }


@Composable
internal fun Cell(text: String, violation: Boolean, hard: Boolean, bg: Color, fg: Color, vioColor: Color, w: androidx.compose.ui.unit.Dp = 52.dp, wishSym: String? = null, wishMet: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // [UD/WCAG 4.1.2] スクリーンリーダー用の読み上げ名（色・形だけに依存しない）。
    val wishA11y = wishSym?.let { "・希望" + it + (if (wishMet) "(反映済)" else "(未反映)") } ?: ""
    val a11y = "シフト ${text.ifBlank { "なし" }}" + (if (violation) (if (hard) "・必須違反" else "・要調整") else "") + wishA11y + "、タップで変更"
    Box(Modifier.width(w).height(52.dp).padding(2.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(bg, RoundedCornerShape(12.dp))
                .then(if (violation) Modifier.violationBorder(hard, vioColor, 12.dp) else Modifier)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = a11y },
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = fg, textAlign = TextAlign.Center, maxLines = 1)
            // 違反は色に加え形でも示す: HARD=塗りドット / SOFT=中空リング。
            // [校正] 色付きセル上でも埋もれないよう拡大(11dp)＋背景色のリングで縁取りしコントラスト確保。
            if (violation) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(11.dp)
                        .background(if (hard) vioColor else cs.surface, RoundedCornerShape(6.dp))
                        .border(if (hard) 1.5.dp else 2.5.dp, if (hard) cs.surface else vioColor, RoundedCornerShape(6.dp)),
                )
            }
            // [希望表示融合] 希望シフトをセル右下に小バッジ表示。緑=反映済(割当==希望) / 桃=未反映。
            if (wishSym != null) {
                val wbg = if (wishMet) MagiAccent.green else MagiAccent.pink
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(1.dp)
                        .background(wbg, RoundedCornerShape(4.dp))
                        .border(1.dp, cs.surface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 3.dp),
                ) {
                    Text(wishSym, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}


/** [希望の一括操作] 対象範囲(曜日/期間全体) × 対象(全員/1名) × 希望シフト。登録/クリア。誤操作防止で明示確定。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WishBulkSheet(ui: UiState, vm: MagiViewModel, presetWeekday: Int, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    val days = ui.days
    val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")
    val startDow = startDowMonFirst(ui.startDate)
    var scope by remember { mutableIntStateOf(0) }                       // 0=この曜日, 1=期間全体
    var weekday by remember { mutableIntStateOf(presetWeekday.coerceIn(0, 6)) }
    var staffSel by remember { mutableIntStateOf(-1) }                   // -1=全職員, else staff index
    var picked by remember { mutableIntStateOf(-1) }                     // 選択中の希望シフト
    var showStaff by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    val targetDays = if (scope == 1) (0 until days).toList()
        else (0 until days).filter { (startDow + it) % 7 == weekday }
    val allowed = if (staffSel >= 0) vm.allowedShiftsFor(staffSel).toList() else emptyList()
    val targetName = if (staffSel >= 0) (ui.staffNames.getOrNull(staffSel) ?: "$staffSel") else "全職員"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("希望シフトの一括操作", style = MaterialTheme.typography.titleMedium)
            Text("対象範囲", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("この曜日", "期間全体").forEachIndexed { idx, label ->
                    val s = scope == idx
                    Box(Modifier.weight(1f).heightIn(min = 44.dp)
                        .background(if (s) cs.primaryContainer else cs.surfaceVariant, RoundedCornerShape(12.dp))
                        .clickable { scope = idx }, contentAlignment = Alignment.Center) {
                        Text(label, color = if (s) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            fontWeight = if (s) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            if (scope == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    weekdays.forEachIndexed { idx, wd ->
                        val s = weekday == idx
                        Box(Modifier.weight(1f).heightIn(min = 36.dp)
                            .background(if (s) cs.primary else cs.surfaceVariant, RoundedCornerShape(10.dp))
                            .clickable { weekday = idx }, contentAlignment = Alignment.Center) {
                            Text(wd, color = if (s) cs.onPrimary else cs.onSurfaceVariant,
                                fontWeight = if (s) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Text("対象 ${targetDays.size}日。既存の希望は上書き。", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text("対象（誰に）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 40.dp)
                    .background(if (staffSel < 0) cs.primary else cs.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { staffSel = -1 }, contentAlignment = Alignment.Center) {
                    Text("全職員", color = if (staffSel < 0) cs.onPrimary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).heightIn(min = 40.dp)
                    .background(if (staffSel >= 0) cs.primary else cs.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { showStaff = true }, contentAlignment = Alignment.Center) {
                    Text(if (staffSel >= 0) "職員：$targetName" else "職員を選ぶ",
                        color = if (staffSel >= 0) cs.onPrimary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
            Text("希望シフト（タップで選択）", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            ui.shiftSymbols.indices.toList().chunked(3).forEach { rowKeys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowKeys.forEach { k ->
                        val sel = picked == k
                        val ng = staffSel >= 0 && k !in allowed
                        Box(Modifier.weight(1f).heightIn(min = 48.dp)
                            .background(if (sel) cs.primaryContainer else cs.surface, RoundedCornerShape(12.dp))
                            .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else if (ng) cs.error else cs.outline, RoundedCornerShape(12.dp))
                            .clickable { picked = k }, contentAlignment = Alignment.Center) {
                            Text((ui.shiftSymbols.getOrNull(k) ?: "$k") + (if (ng) " 外" else ""),
                                color = if (ng) cs.error else cs.onSurface, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    repeat(3 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (scope == 1 && staffSel < 0) confirmClearAll = true
                    else { vm.clearWishesForDays(if (staffSel < 0) null else staffSel, targetDays); onDismiss() }
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("この範囲を希望なしに", color = cs.error)
                }
                Button(onClick = {
                    if (picked in ui.shiftSymbols.indices) {
                        vm.setWishesForDays(if (staffSel < 0) null else staffSel, targetDays, picked); onDismiss()
                    }
                }, enabled = picked in ui.shiftSymbols.indices && targetDays.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("適用（${targetDays.size}件）")
                }
            }
            Text("※ 期間全体×全職員の「希望なし」は全削除（確認あり）。元に戻すで取消可。",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
    if (showStaff) {
        AlertDialog(
            onDismissRequest = { showStaff = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showStaff = false }) { Text("閉じる") } },
            title = { Text("職員を選ぶ") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ui.staffNames.forEachIndexed { idx, n ->
                        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { staffSel = idx; showStaff = false },
                            contentAlignment = Alignment.CenterStart) {
                            Text(n, Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            },
        )
    }
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            confirmButton = { TextButton(onClick = { confirmClearAll = false; vm.clearAllWishes(); onDismiss() }) { Text("すべて削除", color = cs.error) } },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("キャンセル") } },
            title = { Text("すべての希望を削除") },
            text = { Text("登録済みの希望をすべて削除します。割当には影響しません。元に戻すで復元できます。") },
        )
    }
}

/** 期間開始日 + j(0始まり) → "M/D"。解析失敗時は "(j+1)日"。 */
internal fun dayMD(startDate: String, j: Int): String = try {
    val d = LocalDate.parse(startDate).plusDays(j.toLong())
    "${d.monthValue}/${d.dayOfMonth}"
} catch (e: Exception) { "${j + 1}日" }

/** [不一致だけ抽出] 違反・希望未反映だけを凝縮表示。希望未反映行はタップで該当セル編集へ直行。 */
@Composable
internal fun MismatchExtractCard(ui: UiState, onOpenCell: (Int, Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val coverage = ui.needViolations.entries.mapNotNull { (key, cls) ->
        val pp = key.split(","); val k = pp.getOrNull(0)?.toIntOrNull(); val j = pp.getOrNull(1)?.toIntOrNull()
        if (k == null || j == null) return@mapNotNull null
        val tag = when (cls) { "vio-covU" -> "不足"; "vio-covO" -> "超過"; else -> return@mapNotNull null }
        "${dayMD(ui.startDate, j)} ${ui.shiftSymbols.getOrNull(k) ?: k} $tag"
    }
    val counts = ui.countViolations.entries.mapNotNull { (key, cls) ->
        val pp = key.split(","); val i = pp.getOrNull(0)?.toIntOrNull(); val k = pp.getOrNull(1)?.toIntOrNull()
        if (i == null || k == null) return@mapNotNull null
        val tag = when (cls) { "vio-low" -> "少"; "vio-high" -> "多"; else -> return@mapNotNull null }
        "${ui.staffNames.getOrNull(i) ?: i} ${ui.shiftSymbols.getOrNull(k) ?: k}$tag"
    }
    val unmet = ui.wishes.entries.mapNotNull { (key, w) ->
        val pp = key.split(","); val i = pp.getOrNull(0)?.toIntOrNull(); val j = pp.getOrNull(1)?.toIntOrNull()
        if (i == null || j == null) return@mapNotNull null
        val cur = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
        if (cur == w) return@mapNotNull null
        Triple(i, j, "${ui.staffNames.getOrNull(i) ?: i} ${dayMD(ui.startDate, j)} 希望${ui.shiftSymbols.getOrNull(w) ?: w}→${ui.shiftSymbols.getOrNull(cur) ?: "—"}")
    }
    if (coverage.isEmpty() && counts.isEmpty() && unmet.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("不一致だけ抽出", style = MaterialTheme.typography.titleMedium)
            if (coverage.isNotEmpty()) {
                Text("人数の過不足（${coverage.size}）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(coverage.joinToString(" ・ "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            if (counts.isNotEmpty()) {
                Text("適切回数の範囲外（${counts.size}）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(counts.joinToString(" ・ "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            if (unmet.isNotEmpty()) {
                Text("希望シフト未反映（${unmet.size}）— タップで修正", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MagiAccent.pink)
                unmet.forEach { (i, j, txt) ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { onOpenCell(i, j) }, contentAlignment = Alignment.CenterStart) {
                        Text(txt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun Int.floorMod(m: Int): Int = if (m == 0) 0 else ((this % m) + m) % m

// ============================================================================
// 大規模UI改良: ユニバーサルデザイン + スマホ特化シェル (ボトムナビ + ステータスヒーロー)
// ============================================================================
