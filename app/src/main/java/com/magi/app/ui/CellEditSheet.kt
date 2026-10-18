package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magi.app.model.MagiState
import com.magi.app.v6.UnifiedViolationChecker
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
@OptIn(ExperimentalLayoutApi::class)
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
    tourPos: Pair<Int, Int>? = null,    // 巡回中の（何件目, 全件）＝札「必須 2/5」
    leftHand: Boolean = false,
    relax: RelaxToken? = null,          // [S6] ホームで見つかった組（このセルが窓か手順に入るときだけ渡す）
    relaxNoWall: Boolean = false,       // [S6] 探し終えて組が無い
    relaxStopped: Boolean = false,      // [S6] 試算を途中で止めた
    relaxUnavailable: String? = null,   // [S6] 試算できない理由
    relaxFailed: Boolean = false,       // [S6] 試算が失敗した
    onShowRelax: () -> Unit = {},
    onRetryRelax: () -> Unit = {},
    mode: Int = 0,                      // 0=割当, 1=希望。呼び出し側が持つ（セルを移っても保つ・希望の一覧からは希望で開く）
    onMode: (Int) -> Unit = {},
    expanded: Boolean = true,           // false＝ちら見（3 段・盤面を隠さない）。広げるのは「詳しく ▸」のタップと、希望の一覧から開くとき
    onToggleExpand: () -> Unit = {},
    tourPrev: Pair<Int, Int>? = null,
    canUndo: Boolean = false,           // 勤務表タブの下部バーはシートを開く間は隠れる＝元に戻すをここへ（規則は下部バーと同じ）
    onUndo: () -> Unit = {},
) {
    val (i, j) = cell
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val canDoSet = sheetCanDo(cv.allowedByStaff, i, ui.shiftSymbols.size)
    val current = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
    val wish = ui.wishes["$i,$j"]
    val pinned = VioKey.cell(i, j) in ui.manualPins
    val extKigou = extWishDayKigou(cv.extWishes, i, j, ui.shiftSymbols)
    val name = ui.staffNames.getOrNull(i) ?: i.toString()
    fun sym(k: Int?): String = k?.let { ui.shiftSymbols.getOrNull(it) } ?: "—"
    val c1Marks = remember(ui.c1Shortages) { c1DisplayMarks(ui) }
    val c1Here = ui.c1Shortages.firstOrNull { it.staff == i && j in it.from..it.to }
    val shown = remember(ui.shiftSymbols.size, cv.allowedByStaff) { sheetShifts(ui.shiftSymbols.size, cv.allowedByStaff) }
    // 状態の 1 行・印・回数の 1 行・詳しくは同じ版（盤面・希望・設定・検査世代）から作る。
    val rev = cellSheetRev(ui)
    val needCls = if (current >= 0) ui.needFamilies[VioKey.need(current, j)].orEmpty() else emptyList()
    val countCls = if (current >= 0) ui.countFamilies[VioKey.count(i, current)].orEmpty() else emptyList()
    // このセルが足りない側（当日の自分の勤務の不足・自分の回数の不足）の族は、このセルの問題として赤で出さない。
    val shortSide = remember(rev, cell) { shortSideFamilies(needCls, countCls) }
    val fams = remember(rev, cell) {
        cellStatusFamilies(sheetCellClasses(displayCellClasses(ui, VioKey.cell(i, j), c1Marks), c1Here != null), needCls, countCls)
            .filter { it !in shortSide }
    }
    val shortCaution = remember(rev, cell) {
        if (shortSide.isEmpty()) null else {
            val need = cv.needCellLimits(current, j)
            val nDay = ui.schedule.count { it.getOrNull(j) == current }
            val (lo, _, apt) = cv.staffCellLimits(i, current)
            val useLow = "low" in shortSide && lo != null
            shortSideCaution(name, sym(current), DayText.short(ui.startDate, j),
                if ("covU" in shortSide) need?.let { it.first - (nDay - 1) } else null,
                (ui.schedule.getOrNull(i)?.count { it == current } ?: 1) - 1, if (useLow) "下限" else "目標", if (useLow) lo else apt)
        }
    }
    val status = remember(rev, cell) {
        ui.display?.takeIf { it.freshFor(ui) }?.cellMark(i, j)?.takeIf { it.families == fams }?.let { CellStatus(it.severity, it.oneLine, it.cause) }
            ?: run {
                val st = stateOf() ?: return@remember CellStatus(CellSeverity.NONE, "違反なし")
                cellStatusLine(st, cachedProblem(st), ui.schedule.toIntArray2D(), i, j, fams)
            }
    }
    val dilemma = isWishDilemma(wish, current, status.severity)
    var dilemmaChoice by remember(cell) { mutableIntStateOf(0) } // 0=未選択, 1=他の人で補う, 2=希望は残して割当を変える
    val handoff = relaxHandoff(relax?.result, ui.relaxSearching, relaxNoWall, i, j, relaxStopped, relaxUnavailable, relaxFailed)
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
    // 3 段階（2026-10-14 利用者レビュー）: 0＝問題を確認、1＝自分で勤務を選ぶ、2＝影響を確認して適用。選んだだけでは反映しない。
    var step by remember(cell) { mutableIntStateOf(0) }
    var pickTo by remember(cell) { mutableStateOf<Int?>(null) }
    // 正式な検査（2 回）は描画の外で（統合仕様 DISP-1）。結果が出るまでは「確認しています」＝未評価を安全と見せない。
    val review by produceState<CellChangeReview?>(null, rev, cell, pickTo) {
        value = null
        val k = pickTo ?: return@produceState
        val st = stateOf() ?: return@produceState
        val sched = ui.schedule.toIntArray2D()
        value = withContext(Dispatchers.Default) {
            val targets = listOf(current, k).filter { it >= 0 }.distinct().flatMap { x ->
                listOfNotNull(needFixTarget(ui.needFamilies, x, j, cv.needCellLimits(x, j)), countFixTarget(ui.countViolations, i, x, cv.staffCellLimits(i, x)))
            }
            cellChangeReview(st, sched, i, j, k, ui.staffNames, ui.shiftSymbols, cv::staffCellLimits, cv::needCellLimits, pinned, targets)
        }
    }
    fun choose(k: Int) { if (k != current) { pickTo = k; step = 2; if (!expanded) onToggleExpand() } }
    val topCorner = MaterialTheme.shapes.extraLarge
    Surface(
        modifier = modifier,
        color = cs.surfaceContainerLow,
        shape = topCorner.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        tonalElevation = 2.dp,
    ) {
        // ちら見の「変更案を探す」は大きく開いて、このセルの変更案の一覧を開いた状態から始める。
        var searchOnOpen by remember(cell) { mutableStateOf(false) }
        val target = cellSheetTarget(name, formatDay(ui.startDate, j), current.takeIf { it >= 0 }?.let(::sym))
        val badge = cellSheetBadge(status.severity, tourPos?.first?.takeIf { tourHeading != null }, tourPos?.second ?: 0)
        val peekRecommend = peekRecommendation(relax?.result, i, j)?.takeIf { mode == 0 && it != current && it in canDoSet }
        if (!expanded) PeekBody(
            ui, cell, name, current, wish, mode, leftHand, onEvent, { k -> choose(k) }, onMove, onDismiss, onToggleExpand, onShowRelax,
            canUndo = canUndo, onUndo = onUndo, onMode = onMode, extKigou = extKigou, onWishes = { fixNav.onWishes(i, j) },
            target = target, badge = badge, cause = status.cause, severity = status.severity, tourPrev = tourPrev, tourNext = tourNext,
            picks = peekShifts(shown, canDoSet, current, wish), canDoSet = canDoSet, marks = marks, zeroCaps = zeroCaps,
            recommend = peekRecommend,
            searchLabel = peekSearchAction(mode, status.severity),
            onSearch = { searchOnOpen = true; onToggleExpand() },
        ) else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp)) {
            TextButton(onClick = onToggleExpand, modifier = Modifier.heightIn(min = 48.dp).align(if (leftHand) Alignment.Start else Alignment.End)) {
                Text("小さく表示 ▾")
            }
            val partners = remember(rev, cell) {
                if (status.severity == CellSeverity.NONE) emptyList()
                else stateOf()?.let { st -> violationPartnerDays(cachedProblem(st), ui.schedule.toIntArray2D(), i, j, fams, ui.violationCellFamilies) }.orEmpty()
            }
            // 上段（読むところ）。長ければここだけ縦に送る。下段（操作）は段ごとに固定。
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(CELL_SHEET_TITLE, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (tourNext != null && tourNext != cell && step == 0) {
                        TextButton(onClick = { onMove(tourNext) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("次の違反 ▶") }
                    }
                    SheetUndoButton(canUndo, onUndo)
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "閉じる") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    badge?.let { MagiTagChip(it, if (status.severity == CellSeverity.HARD) cs.error else MagiAccent.orange) }
                    Text(target, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (mode == 0 && step == 1) {
                    // ② 自分で勤務を選ぶ: 選ぶと影響の確認へ（この時点では反映しない）。
                    Text("${DayText.full(ui.startDate, j)}の変更先を選択", style = MaterialTheme.typography.titleSmall)
                    Text("対象：${name}さん　現在：${sym(current)}", style = MaterialTheme.typography.bodyMedium)
                    Text("選ぶと変更後の影響を確認できます。この時点では反映しません。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (shown.any { it !in canDoSet }) Text(NOT_CANDO_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else if (mode == 0 && step == 2 && review == null) {
                    Text(REVIEW_CHECKING_TEXT, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                } else if (mode == 0 && step == 2 && review != null) {
                    // ③ 影響を確認して適用（値はすべて正式な検査で評価した結果）。
                    val rv = review!!
                    Text(PREVIEW_TITLE, style = MaterialTheme.typography.titleSmall)
                    Text(rv.change, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    rv.verdict?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (rv.verdictBad) cs.error else cs.onSurface) }
                    ReviewSection("前後の勤務", listOf(rv.neighbors))
                    ReviewSection("今回の問題", rv.thisCell + rv.othersResolved.map { "解消: $it" })
                    ReviewSection(REVIEW_APPEARED_HEADING, rv.appeared.ifEmpty { listOf("ありません") })
                    Text(rv.hardLine, style = MaterialTheme.typography.bodyMedium)
                    ReviewSection("人数と回数への影響", rv.headcount + rv.counts)
                    ReviewSection("希望・固定への影響", rv.wishPin)
                } else {
                    tourCovULine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    if (mode == 0) {
                        // ① 問題を確認: 1 行目＝状態（重さの色）、2 行目＝一般の説明、今の勤務・並びの日々。ほかの問題は畳む。
                        if (status.severity != CellSeverity.NONE) Text(status.text.removePrefix("⚠ "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                            color = if (status.severity == CellSeverity.HARD) cs.error else cs.onSurface)
                        fams.firstOrNull()?.let { Text(cellRuleLine(it), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                        if (dilemma) Text(wishKeptLine(sym(wish)), style = MaterialTheme.typography.bodySmall)
                        shortCaution?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (fams.isEmpty() && shortCaution == null) Text("このセルに違反はありません。", style = MaterialTheme.typography.bodyMedium)
                        sequenceLine(ui.startDate, ui.schedule.getOrNull(i).orEmpty(), (partners + j).distinct(), ui.shiftSymbols)
                            ?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    } else StatusRow(status, null)
                    var details by remember(cell) { mutableStateOf(false) }
                    // [3.714.0] 問題が 1 つでも説明の文は「詳しく見る」の中（主は「変更案を探す」＝これは文字ボタン）。
                    if (mode == 0 && fams.isNotEmpty() || details) TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(when {
                            fams.size >= 2 -> if (details) "関連する問題を閉じる" else "関連する問題をすべて見る：ほか${fams.size - 1}件"
                            else -> if (details) "閉じる" else CELL_DETAIL_BUTTON
                        })
                    }
                    if (details) {
                        val detailLines = remember(rev, cell) {
                            stateOf()?.let { st -> cellDetailLines(st, cachedProblem(st), ui.schedule.toIntArray2D(), i, j, fams) }.orEmpty()
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            fams.firstOrNull()?.let { Text(cellProblemSentence(it), style = MaterialTheme.typography.bodySmall) }
                            fams.drop(1).forEach { Text("・${cellProblemSentence(it)}", style = MaterialTheme.typography.bodySmall) }
                            detailLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            remember(rev, cell) { stateOf()?.let { st -> relatedCellsLine(st, ui.schedule.toIntArray2D(), i, partners) } }
                                ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                        }
                    }
                    if (mode == 0 && status.severity != CellSeverity.NONE && singleCellHopeless(marks, canDoSet, current)) {
                        Text(SINGLE_CELL_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        remember(marks, rev, cell) { stateOf()?.let { st -> allRiskReason(st, cachedProblem(st), ui.schedule.toIntArray2D(), i, j, marks, canDoSet) } }
                            ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    }
                    if (c1Here != null && c1Here.stuck && mode == 0) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { fixNav.onWishes(i, j) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(wishDayButton(DayText.short(ui.startDate, j))) }
                            OutlinedButton(onClick = { fixNav.onSettings("yr_cons") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(settingsLabelFor("yr_cons")) }
                        }
                    }
                    if (mode == 0 && dilemma && dilemmaChoice == 1) {
                        val focus = FixFocus(null, null, j, exceptStaff = i)
                        FixSearchPanel(ui, cv, focus, onEvent, fixNav, onApplied = {}, compact = true)
                        // 他の人の手が無いとき、同じ違反のもう一方のセルへ（希望は触らない）。
                        if (fixPanelState(ui.running, ui.fixSearching, ui.fixDoneKey, ui.fixFailedKey, focus.key) == FixPanelState.DONE && ui.fixSuggestions.isEmpty()) {
                            partners.forEach { d ->
                                OutlinedButton(onClick = { onMove(i to d) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(partnerCellLabel(ui.startDate, d, partners.size == 1), maxLines = 2) }
                            }
                        }
                    } else if (mode == 0 && !(dilemma && dilemmaChoice == 0)) {
                        Text("どのように調整しますか？", style = MaterialTheme.typography.titleSmall)
                        // 足りない側のセルは、このセルを変えるより当日のその勤務に入れる人を探すのが先。
                        // 直し方の一覧は一度に 1 つだけ開く（片方を開くともう片方は閉じる）。
                        var openPanel by remember(cell) { mutableStateOf(if (searchOnOpen) FixPanelOpen.CELL else FixPanelOpen.NONE) }
                        if ("covU" in shortSide) {
                            if (openPanel != FixPanelOpen.SHORT_SIDE) {
                                val label = shortSideSearchButton(DayText.short(ui.startDate, j), sym(current))
                                val open = { openPanel = FixPanelOpen.SHORT_SIDE }
                                if (shortSideFilled(fams)) Button(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, maxLines = 2) }
                                else OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, maxLines = 2) }
                            }
                            else {
                                ShortageSlotHeader(ui, cv, current, j)
                                FixSearchPanel(ui, cv, FixFocus(null, current, j), onEvent, fixNav, onApplied = {}, compact = true)
                            }
                        }
                        if (openPanel != FixPanelOpen.CELL && status.severity != CellSeverity.NONE) {
                            // 主は 1 つ（INV-09）: 人員不足だけのセルは足りない側の人探しが主。
                            if ("covU" in shortSide && shortSideFilled(fams)) OutlinedButton(onClick = { openPanel = FixPanelOpen.CELL }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(PEEK_SEARCH_BUTTON) }
                            else Button(onClick = { openPanel = FixPanelOpen.CELL }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(PEEK_SEARCH_BUTTON) }
                            Text("この日・前後の日・関連する勤務の変更案を比較します。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        if (openPanel == FixPanelOpen.CELL) {
                            // 重なった問題が 2 件以上なら、どの問題の変更案を見るかを選ぶ。
                            var problem by remember(cell) { mutableStateOf<String?>(null) }
                            problemChoiceHeading(fams.size)?.let { heading ->
                                Text(heading, style = MaterialTheme.typography.bodySmall)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (f in fams) {
                                        val label = breakdownLabels[f] ?: f
                                        if (problem == f) FilledTonalButton(onClick = { problem = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("✓ $label", maxLines = 1) }
                                        else OutlinedButton(onClick = { problem = f }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, maxLines = 1) }
                                    }
                                }
                            }
                            // 選んだ問題（無ければこのセルの問題のどれか）が、このセルで実際に消える案だけを主にする（族の全体の件数では判定しない）。
                            val resolves by produceState<Map<com.magi.app.v6.FixSuggestion, Boolean>?>(null, rev, cell, problem, ui.fixSuggestions) {
                                value = null
                                val st = stateOf() ?: return@produceState
                                val sched = ui.schedule.toIntArray2D()
                                val list = ui.fixSuggestions
                                value = withContext(Dispatchers.Default) {
                                    val before = UnifiedViolationChecker.check(st, sched)
                                    list.associateWith { resolvesAtCell(st, sched, before, it.ops, i, j, problem) }
                                }
                            }
                            // 問題が 2 件以上なら、選ぶまで案を出さない（どの問題を直す案かを先頭の問題で埋めない＝統合仕様 INV-01）。
                            if (fams.size >= 2 && problem == null) Text(PICK_PROBLEM_FIRST_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            else FixSearchPanel(ui, cv, FixFocus(i, null, j), onEvent, fixNav, onApplied = {}, compact = true, family = problem,
                                primaryElsewhere = "covU" in shortSide && shortSideFilled(fams),   // 足りない側の塗りのボタンが上に出ている
                                addresses = { s -> resolves?.get(s) == true }, addressPending = resolves == null,
                                addressLabel = "このセルの" + (problem?.let { breakdownLabels[it] ?: it } ?: "問題"),
                                singleProblem = fams.singleOrNull()?.let { breakdownLabels[it] ?: it })
                        }
                        TextButton(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("自分で勤務を選ぶ") }
                    }
                    if (mode == 1 && wish != null) {
                        wishTabInvolvedLine(sym(wish), fams)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (wish in zeroCaps) Text(wishZeroCapLine(sym(wish)), style = MaterialTheme.typography.bodySmall)
                        Text(if (pinned) WISH_TAB_PINNED_NOTE else WISH_TAB_KEEP_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    if (mode == 0 && status.severity == CellSeverity.HARD) when (handoff) {
                        RelaxHandoff.OFFER -> {
                            Text(relaxHandoffLine(relax!!.result, ui), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = onShowRelax, modifier = Modifier.heightIn(min = 48.dp)) { Text("緩める候補を見る") }
                        }
                        RelaxHandoff.SEARCHING -> Text(RELAX_SEARCHING_TEXT, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        RelaxHandoff.NO_WALL -> Text(RELAX_NO_WALL_TEXT, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        RelaxHandoff.UNAVAILABLE -> Text(relaxUnavailableText(relaxUnavailable.orEmpty()), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        RelaxHandoff.STOPPED, RelaxHandoff.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (handoff == RelaxHandoff.FAILED) RELAX_FAILED_TEXT else RELAX_STOPPED_TEXT, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRetryRelax, modifier = Modifier.heightIn(min = 48.dp)) { Text(RELAX_RETRY_LABEL) }
                        }
                        RelaxHandoff.NONE -> {}
                    }
                    if (mode == 0) {
                        // 回数の目標未満を、勤務を減らす前に見える位置に残す。
                        val counts = remember(rev, i, current, cv) { cellCountLines(ui, i, current, cv::staffCellLimits) }
                        if (showCountSection(ui.countFamilies.keys + ui.countViolations.keys, i, counts)) {
                            Text(COUNT_SECTION_TITLE, style = MaterialTheme.typography.titleSmall)
                            counts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    // 希望・手動固定・ルールの見直しは「ほかの操作」に畳む（主の 2 つの操作を先に見せる）。
                    var moreOps by remember(cell) { mutableStateOf(false) }
                    if (mode == 0 && !(dilemma && dilemmaChoice == 0)) TextButton(onClick = { moreOps = !moreOps }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (moreOps) "ほかの操作を閉じる" else MORE_OPS_LABEL)
                    }
                    if (mode == 0 && moreOps) {
                        OutlinedButton(onClick = { onMode(1) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("希望を確認・登録する") }
                        if (current >= 0) {
                            Text(PIN_TITLE, style = MaterialTheme.typography.titleSmall)
                            Text(PIN_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Board.TogglePin(i, j)) },
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(if (pinned) Icons.Outlined.LockOpen else Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(if (pinned) "手動固定を外す" else "手動固定する", maxLines = 1)
                            }
                        }
                    }
                    if (mode == 0 && moreOps && status.severity != CellSeverity.NONE) {
                        TextButton(onClick = {
                            val famsJp = cellVioClasses(ui, "$i,$j").joinToString("・") { breakdownLabels[it.removePrefix("vio-")] ?: it }
                            onEvent(MagiEvent.Session.AddReviewMemo("$name ${DayText.full(ui.startDate, j)} ${sym(current)}：${famsJp.ifEmpty { status.cause }}"))
                        }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("ルールの見直し候補に入れる")
                        }
                    }
                }
            }
            if (mode == 1) {
                // 希望の画面: ［割当に戻る］と希望の中身。
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onMode(0) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("割当に戻る", maxLines = 1) }
                    val wishText = wishTabLine(wish, current, ui.extBanned[VioKey.cell(i, j)], { k -> sym(k) }, pinned)
                    Text(wishText, style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            if (mode == 0 && step == 0) {
                // 利き手の側に寄せる（右手＝右寄せ、左手＝左寄せ。前日・翌日の順は変えない）。
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, if (leftHand) Alignment.Start else Alignment.End)) {
                    val prev = adjacentDayLabel(ui.startDate, ui.days, j - 1)
                    val next = adjacentDayLabel(ui.startDate, ui.days, j + 1)
                    TextButton(onClick = { onMove(i to j - 1) }, enabled = prev != null, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("${prev ?: "前日"}へ", maxLines = 1)
                    }
                    TextButton(onClick = { onMove(i to j + 1) }, enabled = next != null, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("${next ?: "翌日"}へ", maxLines = 1)
                    }
                }
                // 板挟みの選択肢はこの固定部に置く（上の読むところは縦に送るので、長いと画面の外に隠れた）。
                if (dilemma && dilemmaChoice == 0) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dilemmaChoice = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("他の人で補う（推奨）") }
                    OutlinedButton(onClick = { dilemmaChoice = 2 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("希望は残して別のシフトにする", textAlign = TextAlign.Center)
                    }
                    Text("別のシフトにすると、希望は未反映になります。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if ((mode == 0 && step == 1) || mode == 1) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == 1 && extKigou != null) ExtWishDayRow(extKigou, DayText.short(ui.startDate, j)) { fixNav.onWishes(i, j) }
                cellSheetSlots(shown, canDoSet, leftHand).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { slot ->
                            if (slot == null) { Spacer(Modifier.weight(1f)); return@forEach }
                            val k = slot.shift
                            val sel = if (mode == 0) k == current else k == wish
                            SlotButton(
                                ui, k, slot.canDo, sel,
                                enabled = if (mode == 1) extKigou == null else slot.canDo,
                                recommended = mode == 0 && k in marks.recommended,
                                hardRisk = mode == 0 && k in marks.hardRisk,
                                wishMark = mode == 0 && k == wish && wish != current,
                                zeroCap = k in zeroCaps,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (mode == 0) choose(k)
                                else { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Condition.SetWish(i, j, k)) }
                            }
                        }
                    }
                }
                if (mode == 0) Text(PICK_MARKS_LEGEND, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (mode == 0) OutlinedButton(onClick = { step = 0 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("元の画面に戻る") }
            }
            if (mode == 0 && step == 2) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 適用と戻るは固定（比較の部分だけが縦に送られる）。
                Button(onClick = { pickTo?.let(onPick); step = 0; pickTo = null }, enabled = !ui.running && review != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(APPLY_CHANGE_BUTTON) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    review?.let { r ->
                        val item = consultCellChange(r, i, j, name, isoDate(ui.startDate, j))
                        ConsultButton(isConsulted(ui.consults, item), onUndo = { consultMatch(ui.consults, item)?.let { onEvent(MagiEvent.Session.CancelConsult(it)) } }) { onEvent(MagiEvent.Session.AddConsult(item)) }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { step = 1 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("別の勤務を選ぶ") }
                }
                TextButton(onClick = { step = 0; pickTo = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(KEEP_UNCHANGED_BUTTON) }
            }
            if (mode == 1 && wish != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Condition.RemoveWish(i, j)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(REMOVE_WISH_BUTTON, color = cs.error, maxLines = 1)
                }
            }
            if (step == 0 || mode == 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, if (leftHand) Alignment.Start else Alignment.End)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
            }
            // 画面下端のジェスチャー帯の上に、押せるものを置かない 32dp。
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 確認の画面の 1 節（見出し＋行）。 */
@Composable
private fun ReviewSection(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** 見出し行の「元に戻す」（IconButton 既定の 48dp）。有効条件は下部バーの元に戻すと同じ（呼び出し側が `canUndo && !running` を渡す）。 */
@Composable
private fun SheetUndoButton(enabled: Boolean, onUndo: () -> Unit) {
    IconButton(onClick = onUndo, enabled = enabled) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "元に戻す") }
}

/** 希望モードで拡張希望の指定日を開いたとき（シフトボタンは押せない）: 理由と、拡張希望を見直せる希望の登録への行き先。 */
@Composable
private fun ExtWishDayRow(kigou: List<String>, dayShort: String, onWishes: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(extWishDayNote(kigou), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onWishes, modifier = Modifier.heightIn(min = 48.dp)) { Text(wishDayButton(dayShort), maxLines = 1) }
    }
}

/** ちら見の 3 段: ①違反の 1 行＋✕ ②対象＋前/次 ③（希望を登録中の札・希望を外す・緩める候補の行）＋シフトと詳しく ▸ の 1 行＋⚠ の凡例。高さは中身に合わせる。
 *  緩める候補はこのセルだけを変えず、設定の緩和と手順をまとめて確定するダイアログを開く。 */
@Composable
private fun PeekBody(
    ui: UiState, cell: Pair<Int, Int>, name: String, current: Int, wish: Int?, mode: Int, leftHand: Boolean,
    onEvent: (MagiEvent) -> Unit, onPick: (Int) -> Unit, onMove: (Pair<Int, Int>) -> Unit, onDismiss: () -> Unit, onToggleExpand: () -> Unit,
    onShowRelax: () -> Unit, target: String, badge: String?, cause: String, severity: CellSeverity, tourPrev: Pair<Int, Int>?, tourNext: Pair<Int, Int>?,
    picks: List<Int>, canDoSet: Set<Int>, marks: ShiftMarks, zeroCaps: Set<Int>, recommend: Int?,
    canUndo: Boolean = false, onUndo: () -> Unit = {},
    onMode: (Int) -> Unit = {}, extKigou: List<String>? = null, onWishes: () -> Unit = {},
    searchLabel: String? = null, onSearch: () -> Unit = {},
) {
    val (i, j) = cell
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: k.toString()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 1 行目＝対象、2 行目＝札＋原因（S-2）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(target, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SheetUndoButton(canUndo, onUndo)
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "閉じる") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            badge?.let { MagiTagChip(it, if (severity == CellSeverity.HARD) cs.error else MagiAccent.orange) }
            Text(if (badge == null) "このセルに違反はありません" else cause, style = MaterialTheme.typography.bodyMedium,
                color = if (severity == CellSeverity.HARD) cs.error else cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (tourPrev != null || tourNext != null) {
                TextButton(onClick = { tourPrev?.let(onMove) }, enabled = tourPrev != null && tourPrev != cell, modifier = Modifier.heightIn(min = 48.dp)) { Text("◀ 前") }
                TextButton(onClick = { tourNext?.let(onMove) }, enabled = tourNext != null && tourNext != cell, modifier = Modifier.heightIn(min = 48.dp)) { Text("次 ▶") }
            }
        }
        // 押せる行どうしは 8dp 空ける（赤の［希望を外す］とシフトの押し違いを防ぐ）。
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 希望モードはセルを移っても続く＝ちら見でもシフトを押すと希望になると示し、1 タップで割当へ戻せるようにする。
            if (mode == 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MagiTagChip("希望を登録中", MagiAccent.pink)
                TextButton(onClick = { onMode(0) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("割当に戻る", maxLines = 1) }
            }
            // 希望を外すのは希望の登録中だけ（割当のちら見では、シフトの押し違いで希望を消さない）。
            val showRemove = wish != null && mode == 1
            if (showRemove || recommend != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showRemove) OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Condition.RemoveWish(i, j)) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(REMOVE_WISH_BUTTON, color = cs.error, maxLines = 1) }
                if (recommend != null) OutlinedButton(onClick = onShowRelax, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(relaxPeekLabel(sym(recommend)), maxLines = 1)
                }
            }
            if (mode == 1 && extKigou != null) ExtWishDayRow(extKigou, DayText.short(ui.startDate, j), onWishes)
            // シフトと「詳しく ▸」は 1 行に収める（入る数は peekPickCount）。
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val shownPicks = picks.take(peekPickCount(maxWidth.value.toInt()))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        for (k in if (leftHand) shownPicks.reversed() else shownPicks) {
                            val sel = if (mode == 0) k == current else k == wish
                            SlotButton(
                                ui, k, k in canDoSet, sel, enabled = if (mode == 1) extKigou == null else k in canDoSet,
                                recommended = mode == 0 && k in marks.recommended, hardRisk = mode == 0 && k in marks.hardRisk,
                                wishMark = mode == 0 && k == wish && wish != current, zeroCap = k in zeroCaps,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (mode == 0) { if (k != current) onPick(k) }
                                else { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(MagiEvent.Condition.SetWish(i, j, k)) }
                            }
                        }
                        TextButton(onClick = onToggleExpand, modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp).semantics { contentDescription = "問題の説明と直し方を表示" },
                            contentPadding = PaddingValues(0.dp)) { Text("詳しく ▸", maxLines = 1) }
                    }
                    if (mode == 0 && shownPicks.any { it in marks.hardRisk }) {
                        Text(PEEK_HARD_RISK_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            // 主ボタンはシフト行の下に全幅で 1 つ（S-3・INV-09）。押すとこのセルの変更案の一覧を開いた状態で広げる（INV-01）。
            if (searchLabel != null) Button(onClick = onSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(searchLabel, maxLines = 1) }
        }
        Spacer(Modifier.height(8.dp))
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

/** 固定枠のシフトボタン。選択中はシフトの色のまま太枠＋✓、担当外と押せない枠は灰色（担当外は「外」）、個人の上限0は下に小さく「上限0」（押せる）、印は右上の角（緑の点＝おすすめ、赤の警告＝必須が増える。同時には付かない）。 */
@Composable
private fun SlotButton(
    ui: UiState, k: Int, canDo: Boolean, selected: Boolean, enabled: Boolean, recommended: Boolean, hardRisk: Boolean, wishMark: Boolean,
    zeroCap: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val symbol = ui.shiftSymbols.getOrNull(k) ?: k.toString()
    val live = canDo && enabled
    val bg = if (live) hexToColor(ui.shiftColorHex.getOrNull(k) ?: "") else cs.surfaceVariant
    val fg = if (live) ensureReadable(bg, hexToColor(ui.shiftTextHex.getOrNull(k) ?: "")) else cs.onSurfaceVariant
    val shape = MaterialTheme.shapes.large
    Box(
        modifier
            .heightIn(min = 52.dp)
            .background(bg, shape)
            .then(if (selected) Modifier.border(4.dp, cs.onSurface, shape) else if (!live) Modifier.border(1.dp, cs.outline, shape) else Modifier)
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

/** セルのシートで開いている直し方の一覧（足りない側の人探しか、このセルの変更案か）。 */
private enum class FixPanelOpen { NONE, SHORT_SIDE, CELL }
