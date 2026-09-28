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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magi.app.model.MagiState
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.formatDay
import com.magi.app.v6.toIntArray2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 勤務表のセル編集シート（画面下の固定パネル。引っ張る操作は無い＝片手一本指）。
 * 上から: 見出し・1 行の状態・直し方・詳しく（読む）→ 割当｜希望｜回数 → 前日/翌日 → シフトボタン（固定配置）→ 閉じる → 32dp の余白。
 * 閉じるのは「閉じる」・戻る・シートの外のタップ。別のセルをタップするとそのセルへ移る（呼び出し側）。
 */
@Composable
internal fun CellEditSheet(
    ui: UiState,
    cv: ConditionsView,
    onEvent: (MagiEvent) -> Unit,
    cell: Pair<Int, Int>,
    stateOf: () -> MagiState?,
    onPick: (Int) -> Unit,
    onMove: (Pair<Int, Int>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    fixNav: FixNav = FixNav(),
    tourNext: Pair<Int, Int>? = null,
    tourHeading: String? = null,        // 巡回中の見出し「必須違反 2 / 5 ・ 禁止の並び …」（違反単位）
    tourCovULine: String? = null,       // 巡回に入らない人員不足の件数
    leftHand: Boolean = false,
    relax: RelaxToken? = null,          // [S6] ホームで見つかった組（このセルが窓か手順に入るときだけ渡す）
    relaxNoWall: Boolean = false,       // [S6] 探し終えて組が無い
    onShowRelax: () -> Unit = {},
    mode: Int = 0,                      // 0=割当, 1=希望。呼び出し側が持つ（セルを移っても保つ・希望の一覧からは希望で開く）
    onMode: (Int) -> Unit = {},
    expanded: Boolean = true,           // false＝ちら見（3 段・盤面を隠さない）。広げるのは「すべてのシフト ▾」のタップだけ
    onToggleExpand: () -> Unit = {},
    tourPrev: Pair<Int, Int>? = null,
) {
    val (i, j) = cell
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val allowed = cv.allowedShiftsFor(i)
    val canDoSet = allowed.ifEmpty { ui.shiftSymbols.indices.toSet() }
    val current = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
    val wish = ui.wishes["$i,$j"]
    val pinned = VioKey.cell(i, j) in ui.manualPins
    val name = ui.staffNames.getOrNull(i) ?: i.toString()
    fun sym(k: Int?): String = k?.let { ui.shiftSymbols.getOrNull(it) } ?: "—"
    val c1Marks = remember(ui.c1Shortages) { c1DisplayMarks(ui) }
    val c1Here = ui.c1Shortages.firstOrNull { it.staff == i && j in it.from..it.to }
    val shown = remember(ui.shiftSymbols.size, cv.allowedByStaff) { sheetShifts(ui.shiftSymbols.size, cv.allowedByStaff) }
    // 状態の 1 行・印・回数の 1 行・詳しくは同じ版（盤面・希望・設定・検査世代）から作る。
    val rev = cellSheetRev(ui)
    val fams = remember(rev, cell) {
        cellStatusFamilies(
            sheetCellClasses(displayCellClasses(ui, VioKey.cell(i, j), c1Marks), c1Here != null),
            if (current >= 0) ui.needFamilies[VioKey.need(current, j)].orEmpty() else emptyList(),
            if (current >= 0) ui.countFamilies[VioKey.count(i, current)].orEmpty() else emptyList(),
        )
    }
    val status = remember(rev, cell) {
        val st = stateOf() ?: return@remember CellStatus(CellSeverity.NONE, "違反なし")
        cellStatusLine(st, cachedProblem(st), ui.schedule.toIntArray2D(), i, j, fams)
    }
    val dilemma = isWishDilemma(wish, current, status.severity)
    var dilemmaChoice by remember(cell) { mutableIntStateOf(0) } // 0=未選択, 1=他の人で補う, 2=希望は残して割当を変える
    val handoff = relaxHandoff(relax?.result, ui.relaxSearching, relaxNoWall, i, j)
    var marks by remember(cell) { mutableStateOf(ShiftMarks()) }
    LaunchedEffect(cell, rev) {
        marks = ShiftMarks()
        val st = stateOf() ?: return@LaunchedEffect
        val sched = ui.schedule.toIntArray2D()
        if (i !in sched.indices || j !in sched[i].indices) return@LaunchedEffect
        val cands = canDoSet.sorted()
        marks = withContext(Dispatchers.Default) {
            val job = this
            evaluateShiftMarks(st, sched, i, j, status.severity, cands, stillWanted = { job.isActive })
        }
    }
    val zeroCaps = remember(rev, i) { stateOf()?.let { st -> zeroCapShifts(cachedProblem(st), i) }.orEmpty() }
    val countLine = remember(rev, i) {
        stateOf()?.let { st -> staffCountShort(st, cachedProblem(st), ui.schedule.toIntArray2D(), i, ui.countFamilies) }.orEmpty()
    }
    val topCorner = MaterialTheme.shapes.extraLarge
    Surface(
        modifier = modifier,
        color = cs.surfaceContainerLow,
        shape = topCorner.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        tonalElevation = 2.dp,
    ) {
        if (!expanded) PeekBody(
            ui, cell, name, current, wish, mode, leftHand, onEvent, onPick, onMove, onDismiss, onToggleExpand,
            heading = tourHeading ?: status.text.removePrefix("⚠ "), severity = status.severity, tourPrev = tourPrev, tourNext = tourNext,
            picks = peekShifts(shown, canDoSet, current, wish), canDoSet = canDoSet, marks = marks, zeroCaps = zeroCaps,
            recommend = peekRecommendation(relax?.result, i, j)?.takeIf { mode == 0 && it != current && it in canDoSet },
        ) else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp)) {
            TextButton(onClick = onToggleExpand, modifier = Modifier.heightIn(min = 48.dp).align(if (leftHand) Alignment.Start else Alignment.End)) {
                Text("すべてのシフト ▴")
            }
            // 上段（読むところ）: 見出し・1 行の状態・直し方（手 1 件／理由 2 行）。長ければここだけ縦に送る。
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$name ・ ${formatDay(ui.startDate, j)}", style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (tourNext != null && tourNext != cell) {
                        TextButton(onClick = { onMove(tourNext) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("次の違反 ▶") }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "閉じる") }
                }
                tourHeading?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
                tourCovULine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                StatusRow(status, if (dilemma) wishKeptLine(sym(wish)) else null)
                val partners = remember(rev, cell) {
                    if (status.severity != CellSeverity.HARD) emptyList()
                    else stateOf()?.let { st -> violationPartnerDays(cachedProblem(st), ui.schedule.toIntArray2D(), i, j, fams, ui.violationCellFamilies) }.orEmpty()
                }
                if (mode == 0) remember(rev, cell) { stateOf()?.let { st -> relatedCellsLine(st, ui.schedule.toIntArray2D(), i, partners) } }
                    ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                if (mode == 0 && status.severity != CellSeverity.NONE && singleCellHopeless(marks, canDoSet, current)) {
                    Text(SINGLE_CELL_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    remember(marks, rev, cell) { stateOf()?.let { st -> allRiskReason(st, cachedProblem(st), ui.schedule.toIntArray2D(), i, j, marks, canDoSet) } }
                        ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                }
                if (c1Here != null && c1Here.stuck && mode == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { fixNav.onWishes(i) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("希望を見る") }
                        OutlinedButton(onClick = { fixNav.onSettings("yr_cons") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(settingsLabelFor("yr_cons")) }
                    }
                }
                if (mode == 0 && dilemma && dilemmaChoice != 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { dilemmaChoice = 1 }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("他の人で補う（推奨）") }
                        OutlinedButton(onClick = { dilemmaChoice = 2 }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("希望は残して別のシフトを割り当てる（希望は未反映になります）", maxLines = 3)
                        }
                    }
                    if (dilemmaChoice == 1) {
                        val focus = FixFocus(null, null, j, exceptStaff = i)
                        FixSearchPanel(ui, cv, focus, onEvent, fixNav, onApplied = {}, compact = true)
                        // 他の人の手が無いとき、同じ違反のもう一方のセルへ（希望は触らない）。
                        if (fixPanelState(ui.running, ui.fixSearching, ui.fixDoneKey, ui.fixFailedKey, focus.key) == FixPanelState.DONE && ui.fixSuggestions.isEmpty()) {
                            partners.forEach { d ->
                                OutlinedButton(onClick = { onMove(i to d) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(partnerCellLabel(ui.startDate, d, partners.size == 1), maxLines = 2) }
                            }
                        }
                    }
                } else if (mode == 0 && status.severity != CellSeverity.NONE) {
                    FixSearchPanel(ui, cv, FixFocus(i, null, j), onEvent, fixNav, onApplied = {}, compact = true)
                }
                if (mode == 1 && wish != null) {
                    wishTabInvolvedLine(sym(wish), fams)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (wish in zeroCaps) Text(wishZeroCapLine(sym(wish)), style = MaterialTheme.typography.bodySmall)
                    Text(WISH_TAB_KEEP_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (mode == 0 && status.severity == CellSeverity.HARD) when (handoff) {
                    RelaxHandoff.OFFER -> {
                        Text(relaxHandoffLine(relax!!.result, ui), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onShowRelax, modifier = Modifier.heightIn(min = 48.dp)) { Text("緩める候補を見る") }
                    }
                    RelaxHandoff.SEARCHING -> Text(RELAX_SEARCHING_TEXT, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    else -> {}
                }
                var details by remember(cell) { mutableStateOf(false) }
                TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (details) "詳しく ▲" else "詳しく ▼")
                }
                if (details) {
                    val detailLines = remember(rev, cell) {
                        stateOf()?.let { st -> cellDetailLines(st, cachedProblem(st), ui.schedule.toIntArray2D(), i, j, fams) }.orEmpty()
                    }
                    val staffLines = remember(rev, i, cv) { staffCountLines(ui, i, cv::staffCellLimits) }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("このセルの違反", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        if (detailLines.isEmpty()) Text("違反はありません。", style = MaterialTheme.typography.bodySmall)
                        detailLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("この職員の回数・偏り", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        if (staffLines.isEmpty()) Text("回数・偏りの違反はありません。", style = MaterialTheme.typography.bodySmall)
                        staffLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (status.severity != CellSeverity.NONE) {
                    TextButton(onClick = {
                        val famsJp = cellVioClasses(ui, "$i,$j").joinToString("・") { breakdownLabels[it.removePrefix("vio-")] ?: it }
                        onEvent(MagiEvent.Session.AddReviewMemo("$name ${j + 1}日=${sym(current)}：${famsJp.ifEmpty { status.cause }}"))
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("ルールの見直しへ")
                    }
                }
            }
            // 1 行: ［割当］［希望］の切替（ボタンの位置は動かない）＋ 補足（希望の中身・回数）は控えめの色で。
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("割当", "希望").forEachIndexed { idx, label ->
                    val selSeg = mode == idx
                    Box(
                        Modifier.heightIn(min = 48.dp)
                            .background(if (selSeg) cs.primaryContainer else cs.surfaceVariant, MaterialTheme.shapes.small)
                            .clickable { onMode(idx) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (selSeg) cs.onPrimaryContainer else cs.onSurfaceVariant, maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = if (selSeg) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                val wishText = "希望 ${if (wish == null) "—" else sym(wish)}（${wishTabState(wish, current)}）" + (if (pinned) "・手動固定" else "")
                Text(wishText, style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            // 回数は全幅で 2 行まで（字数の見積もりは COUNT_LINE_EM）。
            if (countLine.isNotEmpty()) Text("回数 $countLine", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            // 利き手の側に寄せる（右手＝右寄せ、左手＝左寄せ。前日・翌日の順は変えない）。
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, if (leftHand) Alignment.Start else Alignment.End)) {
                val prev = adjacentDayLabel(ui.startDate, ui.days, j - 1)
                val next = adjacentDayLabel(ui.startDate, ui.days, j + 1)
                OutlinedButton(onClick = { onMove(i to j - 1) }, enabled = prev != null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("◀ ${prev ?: "前日"}", maxLines = 1)
                }
                OutlinedButton(onClick = { onMove(i to j + 1) }, enabled = next != null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("${next ?: "翌日"} ▶", maxLines = 1)
                }
            }
            val showGrid = mode == 1 || !dilemma || dilemmaChoice == 2
            if (showGrid) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cellSheetSlots(shown, canDoSet, leftHand).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { slot ->
                            if (slot == null) { Spacer(Modifier.weight(1f)); return@forEach }
                            val k = slot.shift
                            val sel = if (mode == 0) k == current else k == wish
                            SlotButton(
                                ui, k, slot.canDo, sel,
                                enabled = mode == 1 || slot.canDo,
                                recommended = mode == 0 && k in marks.recommended,
                                hardRisk = mode == 0 && k in marks.hardRisk,
                                wishMark = mode == 0 && k == wish && wish != current,
                                zeroCap = k in zeroCaps,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (mode == 0) { if (k != current) onPick(k) }
                                else { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Condition.SetWish(i, j, k)) }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 閉じるは利き手の側（右手＝右、左手＝左）。0=閉じる, 1=［割当］では手動固定の付け外し／［希望］では希望を取り消す
                for (b in if (leftHand) listOf(0, 1) else listOf(1, 0)) when (b) {
                    0 -> FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("閉じる") }
                    else -> if (mode == 0 && current >= 0) {
                        OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Board.TogglePin(i, j)) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Icon(if (pinned) Icons.Outlined.LockOpen else Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(if (pinned) "手動固定を外す" else "手動固定する", maxLines = 1)
                        }
                    } else if (mode == 1 && wish != null) {
                        OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Condition.RemoveWish(i, j)) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("希望を取り消す", color = cs.error, maxLines = 1)
                        }
                    }
                }
            }
            // 画面下端のジェスチャー帯の上に、押せるものを置かない 32dp。
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** ちら見の 3 段: ①違反の 1 行＋✕ ②対象＋前/次 ③操作 1 行（希望を取り消す・推奨・上位 4 シフト・他 ▸）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeekBody(
    ui: UiState, cell: Pair<Int, Int>, name: String, current: Int, wish: Int?, mode: Int, leftHand: Boolean,
    onEvent: (MagiEvent) -> Unit, onPick: (Int) -> Unit, onMove: (Pair<Int, Int>) -> Unit, onDismiss: () -> Unit, onToggleExpand: () -> Unit,
    heading: String, severity: CellSeverity, tourPrev: Pair<Int, Int>?, tourNext: Pair<Int, Int>?,
    picks: List<Int>, canDoSet: Set<Int>, marks: ShiftMarks, zeroCaps: Set<Int>, recommend: Int?,
) {
    val (i, j) = cell
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: k.toString()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(heading, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                color = if (severity == CellSeverity.HARD) cs.error else cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "閉じる") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$name ・ ${formatDay(ui.startDate, j)}", style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (tourPrev != null || tourNext != null) {
                TextButton(onClick = { tourPrev?.let(onMove) }, enabled = tourPrev != null && tourPrev != cell, modifier = Modifier.heightIn(min = 48.dp)) { Text("◀ 前") }
                TextButton(onClick = { tourNext?.let(onMove) }, enabled = tourNext != null && tourNext != cell, modifier = Modifier.heightIn(min = 48.dp)) { Text("次 ▶") }
            }
        }
        // 幅が足りないときは折り返す（横スクロールしない）。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            if (wish != null) OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Condition.RemoveWish(i, j)) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("希望を取り消す", color = cs.error, maxLines = 1) }
            if (recommend != null) FilledTonalButton(onClick = { onPick(recommend) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("推奨: ${sym(recommend)} に変更", maxLines = 1)
            }
            for (k in if (leftHand) picks.reversed() else picks) {
                val sel = if (mode == 0) k == current else k == wish
                SlotButton(
                    ui, k, k in canDoSet, sel, enabled = mode == 1 || k in canDoSet,
                    recommended = mode == 0 && k in marks.recommended, hardRisk = mode == 0 && k in marks.hardRisk,
                    wishMark = mode == 0 && k == wish && wish != current, zeroCap = k in zeroCaps,
                    modifier = Modifier.width(56.dp),
                ) {
                    if (mode == 0) { if (k != current) onPick(k) }
                    else { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Condition.SetWish(i, j, k)) }
                }
            }
            TextButton(onClick = onToggleExpand, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "すべてのシフト ▾" }) { Text("他 ▸") }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun StatusRow(status: CellStatus, wishLine: String?) {
    val (bg, icon, fg) = magiSeverityColors(status.severity)
    Surface(color = bg, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (status.severity == CellSeverity.NONE) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null, tint = icon, modifier = Modifier.size(20.dp))
            Column {
                if (wishLine != null) Text(wishLine, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = fg)
                Text(status.text.removePrefix("⚠ "), style = MaterialTheme.typography.bodyMedium, color = fg,
                    fontWeight = if (wishLine == null) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 固定枠のシフトボタン。選択中はシフトの色のまま太枠＋✓、担当外は灰色で「外」、個人の上限0は下に小さく「上限0」（押せる）、印は右上の角（緑の点＝おすすめ、赤の警告＝必須が増える。同時には付かない）。 */
@Composable
private fun SlotButton(
    ui: UiState, k: Int, canDo: Boolean, selected: Boolean, enabled: Boolean, recommended: Boolean, hardRisk: Boolean, wishMark: Boolean,
    zeroCap: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val symbol = ui.shiftSymbols.getOrNull(k) ?: k.toString()
    val bg = if (canDo) hexToColor(ui.shiftColorHex.getOrNull(k) ?: "") else cs.surfaceVariant
    val fg = if (canDo) ensureReadable(bg, hexToColor(ui.shiftTextHex.getOrNull(k) ?: "")) else cs.onSurfaceVariant
    val shape = MaterialTheme.shapes.large
    Box(
        modifier
            .heightIn(min = 52.dp)
            .background(bg, shape)
            .then(if (selected) Modifier.border(4.dp, cs.onSurface, shape) else if (!canDo) Modifier.border(1.dp, cs.outline, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = symbol + (if (!canDo) " 担当外" else "") + (if (zeroCap) " 個人の上限0（入れない指定）" else "") + (if (selected) " 選択中" else "") + (if (recommended) " おすすめ" else "") + (if (hardRisk) " 必須の違反が増える" else "")
            }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text((if (selected) "✓ " else "") + symbol + (if (!canDo) " 外" else ""), color = fg, fontWeight = FontWeight.Bold, maxLines = 1)
            if (zeroCap) Text("上限0", style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
            if (wishMark) Text("希望", style = MaterialTheme.typography.labelSmall, color = ensureReadable(bg, MagiAccent.pink))
        }
        if (hardRisk) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = cs.error,
                modifier = Modifier.align(Alignment.TopEnd).size(14.dp).background(cs.surface, CircleShape).padding(1.dp))
        }
        if (recommended) {
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(12.dp).background(cs.surface, CircleShape).padding(2.dp)
                .background(MagiAccent.green, CircleShape))
        }
    }
}
