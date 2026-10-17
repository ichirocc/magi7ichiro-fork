package com.magi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.CoverageShortfall
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

/** [思考誘導S3→S5] 必須違反に関わる希望と、人員不足の日に別の勤務の希望がある人を並べる。行を押すとセル、「希望を外したら？」で試算・確定（§5）。
 *  試算の結果は VM が ctx つきで持ち、ここは読むたびに問い合わせる（古ければ隠す＝§8）。 */
@Composable
internal fun WishConflictDialog(
    ui: UiState,
    vm: MagiViewModel,
    onDismiss: () -> Unit,
    onOpenCell: (Int, Int) -> Unit,
    onConfirm: (WishTrialToken) -> Unit,
    onRebuild: () -> Unit,
    relaxFound: Boolean = false,    // [S6] 設定の壁の組がある＝改善なしの行から「希望を残したまま、設定を緩めて試す」へ
    onShowRelax: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val cands = remember(ui.violationCellFamilies, ui.wishes, ui.lockedWishKeys, ui.wishSelfConflicts, ui.coverageDiag, ui.staffNames, ui.shiftSymbols) {
        wishTrialCandidates(ui)
    }
    // 閉じる・行を押してセルへ移る・Activity の作り直し、どの閉じ方でもここ 1 か所で試算を止める（§8）。
    DisposableEffect(Unit) { onDispose { vm.cancelWishTrial() } }
    val control = vm.wishTrialControlFor()   // 非 State の読み取り＝ui（wishTrialRev）が変われば描き直される
    var expanded by remember { mutableStateOf(setOf<Pair<Int, Int>>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ぶつかっている希望") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (cands.isEmpty) Text("いま必須違反に関わる希望はありません。")
                else {
                    control?.let { wishTrialKeepOnlyText(it) }?.let { line ->
                        Text(line, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = onRebuild, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("再作成") }
                    }
                    if (cands.direct.isNotEmpty()) {
                        // [3.714.0] 塗りは 1 つ＝「希望のまま」は最初の希望の前日の行だけ（閉じる＝何も変えない）。
                        val firstC3w = cands.direct.firstOrNull { it.c3wLine != null }
                        cands.direct.forEach { WishTrialRowView(it, ui, vm, onOpenCell, onConfirm, relaxFound, onShowRelax, onKeep = if (it == firstC3w) onDismiss else null) }
                    }
                    if (cands.shortfall.isNotEmpty()) {
                        Text("人員不足の日に、別の勤務の希望がある人", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        cands.shortfall.forEach { g ->
                            Text(g.header, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            val slot = g.day to g.shift
                            val rows = if (slot in expanded) g.rows else g.rows.take(WISH_TRIAL_GROUP_LIMIT)
                            rows.forEach { WishTrialRowView(it, ui, vm, onOpenCell, onConfirm, relaxFound, onShowRelax) }
                            if (rows.size < g.rows.size) {
                                TextButton(onClick = { expanded = expanded + slot }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("ほか ${g.rows.size - rows.size}人")
                                }
                            }
                        }
                    }
                }
                Text(WISH_KEEP_FOOTER, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { DialogDismissButton(onClick = onDismiss, text = "閉じる") },
    )
}

/** [S5] 候補 1 行＝「名前 ・ N日　理由」（押すとセル）と、その下の試算・結果・確定。 */
@Composable
private fun WishTrialRowView(
    row: WishTrialRow,
    ui: UiState,
    vm: MagiViewModel,
    onOpenCell: (Int, Int) -> Unit,
    onConfirm: (WishTrialToken) -> Unit,
    relaxFound: Boolean,
    onShowRelax: () -> Unit,
    onKeep: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    val c3w = row.c3wLine != null
    // 既定の丸い端の形は複数行の高さで左上の字を切るので、カードと同じ角丸にする。
    TextButton(onClick = { onOpenCell(row.staff, row.day) }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        // [3.696.0] 札を右に並べると名前の先頭が欠けた（端末報告「福澤が欠けている」）＝文は全幅、札はその下。
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // [3.712.0] 1 行目は名前と日、2 行目に短い理由（利用者仕様）。
            Text(wishRowHead(row.name, DayText.short(ui.startDate, row.day)), modifier = Modifier.fillMaxWidth())
            Text(row.c3wLine ?: row.reason, style = small, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            if (row.wishOnly && onKeep == null) MagiTagChip(text = WISH_ONLY_TAG, color = MagiAccent.orange)
        }
    }
    if (onKeep != null) Button(onClick = onKeep, modifier = Modifier.padding(start = 4.dp).heightIn(min = 48.dp)) { Text(WISH_KEEP_BUTTON) }
    val k = ui.wishes["${row.staff},${row.day}"]
    if (!row.locked || k == null) {
        Text(if (row.pinned) WISH_TRIAL_PINNED else WISH_TRIAL_NOT_LOCKED, style = small, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    } else {
        val view = vm.wishTrialFor(row.staff, row.day, k)
        val canTrial = ui.wishTrialBusy == null && !ui.running
        when (view) {
            WishTrialView.Busy -> Text("試算しています…", style = small, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            WishTrialView.None -> WishTrialButton(canTrial) { vm.startWishTrial(row.staff, row.day) }
            WishTrialView.Stale -> {
                Text("勤務表が変わりました。もう一度試算してください。", style = small, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                WishTrialButton(canTrial) { vm.startWishTrial(row.staff, row.day) }
            }
            is WishTrialView.Ready -> {
                wishTrialText(view.outcome)?.let { Text(it, style = small, modifier = Modifier.padding(start = 12.dp)) }
                if (relaxFound && wishTrialNoGain(view.outcome)) {
                    TextButton(onClick = onShowRelax, modifier = Modifier.padding(start = 4.dp).heightIn(min = 48.dp)) { Text(WISH_TO_RELAX_LABEL) }
                }
                if (view.token.result != null) {
                    TextButton(
                        onClick = { onConfirm(view.token) },
                        enabled = !ui.running,
                        colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                        modifier = Modifier.padding(start = 4.dp).heightIn(min = 48.dp),
                    ) { Text("希望を外して再作成する") }
                }
            }
        }
    }
    // [3.645.0/仕様 5.3] 本人や上長に確認してから決める＝対象と検討内容を相談中の一覧へ（取消も勤務の変更もしない）。積んだら「相談中」。
    val consult = consultWish(row.name, DayText.short(ui.startDate, row.day), k?.let { ui.shiftSymbols.getOrNull(it) }, row.reason, row.staff, row.day, isoDate(ui.startDate, row.day))
    ConsultButton(isConsulted(ui.consults, consult), Modifier.padding(start = 4.dp), held = consultMatch(ui.consults, consult)?.held == true,
        onHold = { vm.addConsult(consult.copy(held = true)) }, onUndo = { consultMatch(ui.consults, consult)?.let { vm.cancelConsult(it) } },
        label = if (c3w) WISH_CONSULT_SHORT else CONSULT_BUTTON, holdLabel = if (c3w) WISH_HOLD_SHORT else CONSULT_HOLD_BUTTON) { vm.addConsult(consult) }
}

@Composable
private fun WishTrialButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(start = 4.dp).heightIn(min = 48.dp)) {
        Text("希望を外したら？")
    }
}

/** [S6] 設定を緩める候補（`docs/s6_relax_trial.md` §5）。盤面は見せず手順を言葉で出し、押したときだけ当てる。
 *  確定の前に全部の変更（設定・手順の全セル）を読める＝窓の外の手も畳むだけで隠さない。 */
@Composable
internal fun RelaxTrialDialog(
    ui: UiState,
    token: RelaxToken?,
    onDismiss: () -> Unit,
    onConfirm: (RelaxToken) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    val t = if (token == null) null else remember(token, ui.schedule, ui.staffNames, ui.shiftSymbols) { relaxTrialText(token.result, ui) }
    var showAll by remember(token) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t?.dialogTitle ?: "例外として上限を緩める候補") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (token == null || t == null) Text("勤務表か設定が変わりました。もう一度試算してください。")
                else {
                    Text(RELAX_WISH_LINE, fontWeight = FontWeight.Bold)
                    Text(t.hardLine, fontWeight = FontWeight.Bold)
                    Text(t.scaleLine, style = small, color = cs.onSurfaceVariant)
                    if (t.prerequisiteRows.isNotEmpty()) {
                        Text(RELAX_PREREQ_HEAD, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Text(RELAX_PREREQ_WHY, style = small, color = cs.onSurfaceVariant)
                        t.prerequisiteRows.forEach { Text("・$it", style = small) }
                    }
                    Text(RELAX_SET_HEAD, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(t.lead, style = MaterialTheme.typography.bodyMedium)
                    t.rows.forEach { Text("・$it", style = small) }
                    Text(t.solveNote, style = small, color = cs.onSurfaceVariant)
                    Text("手順", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    t.moveLines.forEach { Text(it, style = small) }
                    if (t.otherMoves > 0) {
                        if (showAll) t.otherMoveLines.forEach { Text(it, style = small) }
                        else TextButton(onClick = { showAll = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("ほか ${t.otherMoves}セル（タップですべて表示）")
                        }
                    }
                    t.keepNote?.let { Text(it, style = small, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                    Text("元に戻すで設定と勤務表をまとめて戻せます。", style = small, color = cs.onSurfaceVariant)
                }
            }
        },
        // 主ボタンは confirmButton の列の先頭（3.704.0 G-3: スクロールの中に置かない）。
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (token != null && t != null) DialogConfirmButton("上限を緩め、手順を当てる", onClick = { onConfirm(token) }, enabled = !ui.running)
                DialogDismissButton(onClick = onDismiss, text = "閉じる")
            }
        },
    )
}

/** [UX監査 高2] ホームの「なおし方」の対象枠。ホームの文言とダイアログが必ず同じ枠を指すよう、ここ1か所で決める。 */
internal fun guidedFixTarget(shortfalls: List<CoverageShortfall>): CoverageShortfall? =
    shortfalls.firstOrNull { it.verdict == CoverageVerdict.FIXABLE && it.miss > 0 && !it.blockedNow }

// 着地先（EditLanding・landingFor・landingButtonLabel）は ui/EditLanding.kt（3.644.0 でつくる前の確認と共有）。

/** [3.644.0/UX-03] 複数人の入替を当てる前に、変わる人・日・勤務（前 → 後）と必須の増減を一覧で見せる。当てるのは確認のあと（元に戻せる）。 */
@Composable
internal fun ChainFixPreviewDialog(p: ChainFixPreview, onApply: () -> Unit, onDismiss: () -> Unit, consulted: Boolean = false, onConsult: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PREVIEW_TITLE) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.applyError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error, fontWeight = FontWeight.Bold) }
                // [3.713.0] 最初は「Nか所変わります」と変わるセルの行だけ。目的・回数・判断材料・注記は「詳しく」の中。
                Text(previewCountLine(p.suggestion.ops.size), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                if (p.rows.isEmpty()) p.changes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                p.rows.forEach { r -> Text(previewRowLine(r.staff, r.date, r.before, r.after), style = MaterialTheme.typography.bodyMedium) }
                var more by remember { mutableStateOf(false) }
                TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (more) "閉じる" else "詳しく") }
                if (more) {
                p.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
                // 目的 → 変更する勤務（職員ごとの表）→ 対象の数の変化 → ほかの影響と確認が必要なこと（2026-10-14 利用者レビュー）。
                p.purpose?.let {
                    Text("今回の目的", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                } ?: Text(p.suggestion.label, style = MaterialTheme.typography.bodyMedium)
                p.rows.forEach { r ->
                    if (r.impact.isNotEmpty()) Text("${r.staff} ${r.date} 人数：${r.impact}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (r.wish.isNotEmpty()) Text("${r.staff} ${r.date} ${r.wish}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                p.effect.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(p.hardLine, style = MaterialTheme.typography.bodyMedium)
                if (p.countNotes.isNotEmpty()) {
                    Text("この組み合わせで変わる回数", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    p.countNotes.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
                }
                Text("判断に必要な情報", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text("・" + newHardLine(p.newHard), style = MaterialTheme.typography.bodySmall)
                val (wishLine, consultLine) = previewWishLines(p.rows)
                Text("・$wishLine", style = MaterialTheme.typography.bodySmall)
                p.caution?.let { Text("・$it", style = MaterialTheme.typography.bodySmall) }
                Text("・$consultLine", style = MaterialTheme.typography.bodySmall)
                Text(PREVIEW_GATE_NOTE,
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text(CONSULT_NOT_CONTACT_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        },
        // 第 2 の操作と「今は変更しない」も主ボタンの下に重ねる（MonthMoveConfirmDialog と同じ形）。dismiss の枠に分けると横に入り切らず重なった。
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogConfirmButton(applyCountButton(p.suggestion.ops.size), onClick = onApply)
                ConsultButton(consulted, Modifier, onClick = onConsult)
                DialogDismissButton(onClick = onDismiss, text = if (p.fromList) BACK_TO_FIXES_BUTTON else KEEP_CURRENT_BUTTON)
            }
        },
    )
}

/** 「相談事項に登録」。積んだあとは「相談中」の札＝結果を形で返す（シートやダイアログの下では Snackbar が見えない。
 *  無効ボタンの文字は 38% で状態色の基準 3:1 を割るので、札にする＝ux_test_checklist A1）。 */
@Composable
internal fun ConsultButton(consulted: Boolean, modifier: Modifier = Modifier, held: Boolean = false, onHold: (() -> Unit)? = null, onUndo: (() -> Unit)? = null,
                           label: String = CONSULT_BUTTON, holdLabel: String = CONSULT_HOLD_BUTTON, onClick: () -> Unit) {
    // [3.696.0] 札の隣に「取り消す」＝積んだ直後の押し間違いを戻せる（端末報告「相談中を元に戻せない」）。
    if (consulted) Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MagiTagChip(text = if (held) CONSULT_HELD else CONSULT_DONE, color = MagiAccent.orange)
        if (onUndo != null) TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text(CONSULT_UNDO_BUTTON) }
    }
    else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        if (onHold != null) TextButton(onClick = onHold, modifier = Modifier.heightIn(min = 48.dp)) { Text(holdLabel) }
    }
}

/** [3.645.0/仕様 5.3] 相談してから決める判断の一覧（ホーム）。対象と検討内容を後から再確認できる。出力は止めない。 */
@Composable
internal fun ConsultCard(ui: UiState, onOpenCell: (Int, Int) -> Unit, onRemove: (ConsultItem) -> Unit, onResumeChain: (ConsultItem) -> Unit = {}, onToggleHold: (ConsultItem) -> Unit = {},
                         answerOf: (ConsultItem) -> ConsultAnswer = { it.answer }, onCycleAnswer: (ConsultItem) -> Unit = {}) {
    if (ui.consults.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val heldN = ui.consults.count { it.held }
            Text("相談中（${ui.consults.size - heldN}件）" + (if (heldN > 0) "・保留（${heldN}件）" else ""), style = MaterialTheme.typography.titleMedium)
            Text("関係者に確認してから決める項目です。確認できたら「済」を押してください（アプリを閉じると消えます。書き出しは止めません）。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            ui.consults.forEach { c ->
                // 対象は職員 ID（無ければ氏名）と実日付で今のデータに引き直す（月を移した・職員を消した・並べ替えたあとに別のセルを開かない）。
                val cell = if (c.chain != null) null else consultCell(c, ui.startDate, ui.staffNames, ui.days, ui.staffIds)
                val chainOk = c.chain != null && consultChainResumable(c.chain, ui.startDate, ui.shiftSymbols, ui.days)
                val note = consultTargetNote(c, ui.startDate, ui.staffNames, ui.shiftSymbols, ui.days, ui.staffIds)
                // [3.696.0] 操作は文の下の 1 行に（右に 3 つ並べると文が 3 分の 1 幅に潰れて縦に伸びた＝端末報告「縦に長すぎる」）。
                Column(Modifier.fillMaxWidth()) {
                    Text((if (c.held) "【$CONSULT_HELD】" else "") + c.subject, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(c.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = cs.error)
                    // 回答は記録だけ（了承でも違反は減らさない）。押すたびに 回答待ち→了承→不可。
                    TextButton(onClick = { onCycleAnswer(c) }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(consultAnswerLine(c, answerOf(c)), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        if (cell != null) {
                            TextButton(onClick = { onOpenCell(cell.first, cell.second) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(CONSULT_OPEN) }
                        } else if (chainOk) {
                            TextButton(onClick = { onResumeChain(c) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(CONSULT_RESUME) }
                        }
                        TextButton(onClick = { onToggleHold(c) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (c.held) "相談に戻す" else CONSULT_HOLD_BUTTON) }
                        DeleteRowButton(onClick = { onRemove(c) }, text = "済")
                    }
                }
            }
        }
    }
}

@Composable
internal fun GuidedFixDialog(
    ui: UiState,
    /** 不足枠ごとの候補は「開いたときにその枠だけ」求めるもので、表にして持ち回るものではない
     *  ＝この画面だけが問い合わせを残す。盤面の書き換えは onEvent（裁定を通る）。 */
    vm: MagiViewModel,
    onEvent: (MagiEvent) -> Unit,
    onDismiss: () -> Unit,
    onGoEdit: (EditLanding?) -> Unit,
    onTour: (() -> Unit)? = null,   // 終わりの段でほかの必須が残るとき、巡回の先頭を開く（H-5）
    tourCount: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    val shortfalls = ui.coverageDiag?.shortfalls ?: emptyList()
    // [3.401.0] 旧: target を `verdict == FIXABLE && miss > 0` だけで選び、無条件に「この日に動かせる人が
    //   います」と断言していた。しかし `verdict` は「担当できる人数 >= 必要数」という**静的判定**で、
    //   いまの希望・盤面では埋められない枠(blockedNow)も FIXABLE のまま残る（3.344.0 の意図的な区別）。
    //   その結果、**同じホーム画面の CoverageDiagnosisCard が「いまの希望のままでは埋められません」と
    //   言っている枠に対して、この画面だけが「動かせる人がいます」と正反対の約束をしていた**。
    //   押しても必須違反は減らず、何度押しても同じ日が出続ける。→ blockedNow は target にしない。
    val target = guidedFixTarget(shortfalls)
    val blocked = shortfalls.filter { it.miss > 0 && it.blockedNow && it.verdict != CoverageVerdict.INFEASIBLE }
    val infeasible = shortfalls.filter { it.verdict == CoverageVerdict.INFEASIBLE }
    // blocked を数えないと「直し終わりました」と言ってしまう（旧より悪い嘘になる）。
    val allDone = target == null && blocked.isEmpty() && infeasible.isEmpty()
    val cands = remember(target?.dayIndex, target?.shiftIndex, ui.coverageDiag) {
        target?.let { vm.shortageFixCandidates(it.dayIndex, it.shiftIndex) } ?: emptyList()
    }
    val stage = when {
        allDone -> GuidedStage.DONE
        target != null && cands.isNotEmpty() -> GuidedStage.CANDIDATES
        target?.chainVerified == true -> GuidedStage.CHAIN
        else -> GuidedStage.BLOCKED
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(guidedFixTitle(stage, target)) },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    target != null -> {
                        guidedStatusPoints(target.need, target.got, cands.size).forEach { Text("・$it", style = MaterialTheme.typography.bodyMedium) }
                        // [3.714.0] 副操作は文字ボタン（その日の希望・担当の設定へ）。
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onDismiss(); onGoEdit(shortageWishLanding(target)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(SHORTAGE_WISH_BUTTON) }
                            TextButton(onClick = { onDismiss(); onGoEdit(shortageSkillLanding()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(SHORTAGE_SKILL_BUTTON) }
                        }
                        LaunchedEffect(target.dayIndex, target.shiftIndex) { vm.logUi(OpLogText.UiOp.GuidedOpen(target.dayLabel, target.shiftSymbol, cands.size)) }
                        val gcv = remember(ui) { vm.conditionsView() }
                        when {
                            cands.isNotEmpty() -> {
                                // [UX監査 高1] 単独の移動（だれか1人を入れる）。押すと確認の画面へ（直接は書かない）。当てるときは
                                //   指紋照合・FixApplyGate・INV-03 を通る＝連打で2人目が満たした枠へ入ることも、古い盤面へ書くこともない。
                                val showAll = remember(target.dayIndex, target.shiftIndex) { androidx.compose.runtime.mutableStateOf(false) }
                                // [UX監査 中5] 件数を出し、既定の8人を超えたら「すべて表示」で9人目以降も選べる。
                                val shown = if (showAll.value) cands else cands.take(GUIDED_FIX_PREVIEW)
                                shown.forEach { c ->
                                    val from = ui.schedule.getOrNull(c.staffIndex)?.getOrNull(target.dayIndex)?.takeIf { it >= 0 }
                                    val fromSym = from?.let { ui.shiftSymbols.getOrNull(it) }
                                    Button(
                                        onClick = {
                                            vm.noteGuidedFix(target.dayIndex, target.shiftIndex, target.dayLabel, target.shiftSymbol)
                                            vm.previewShortageCandidate(c.staffIndex, target.dayIndex, target.shiftIndex, "${c.name}さんを${target.dayLabel}の「${target.shiftSymbol}」に入れる")
                                        },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp),
                                    ) {
                                        // 1 行に収める（折り返すと角の丸みで先頭の字が欠ける）。長い氏名は省略する。
                                        Text(guidedCandidateLabel(c.name, target.shiftSymbol),
                                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    guidedCandidateImpact(fromSym, ui.schedule.getOrNull(c.staffIndex)?.count { it == from } ?: 0,
                                        from?.let { gcv.staffCellLimits(c.staffIndex, it).first })
                                        ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                                }
                                if (cands.size > GUIDED_FIX_PREVIEW) {
                                    TextButton(onClick = { showAll.value = !showAll.value }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text(if (showAll.value) "一部だけ表示" else "すべて表示（ほか${cands.size - GUIDED_FIX_PREVIEW}人）")
                                    }
                                }
                                Text("押すと、当てる前に変更と影響を確認できます。入れたあとも「元に戻す」で取り消せます。",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                val consult = consultShortage(target.dayLabel, target.shiftSymbol, cands.map { it.name })
                                ConsultButton(isConsulted(ui.consults, consult), Modifier.fillMaxWidth(), held = consultMatch(ui.consults, consult)?.held == true,
                                    onHold = { vm.addConsult(consult.copy(held = true)) }, onUndo = { consultMatch(ui.consults, consult)?.let { vm.cancelConsult(it) } }) { vm.addConsult(consult) }
                            }
                            target.chainVerified -> {
                                // [UX監査 高1] 1人を動かすだけでは埋まらないが、複数人の入替で埋まると分析が確かめた枠。
                                //   [3.644.0/UX-03] 当てる前に一覧（だれの・どの日の・何→何と必須の増減）を見せる＝ChainFixPreviewDialog。
                                Text("1人を動かすだけでは埋まりません。複数人の入れ替えで埋められます。",
                                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                                Button(
                                    onClick = {
                                        onDismiss()
                                        vm.prepareShortageChainFix(target.dayIndex, target.shiftIndex,
                                            "（玉突き）${target.dayLabel} の「${target.shiftSymbol}」を複数人の入れ替えで埋める")
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                ) {
                                    Text("入れ替えの一覧と影響を見る", textAlign = TextAlign.Center)
                                }
                            }
                            else -> {
                                // [UX監査 高1] 玉突きでも埋まらない枠は、人を動かす手ではなく条件を変える話。原因に対応する区分へ。
                                Text(target.reason, color = cs.error, style = MaterialTheme.typography.bodyMedium)
                                val landing = landingFor(target)
                                Button(onClick = { onDismiss(); onGoEdit(landing) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(landingButtonLabel(landing))
                                }
                            }
                        }
                    }
                    infeasible.isNotEmpty() -> {
                        Text("${infeasible.map { it.dayIndex }.distinct().size}日 は、これ以上は自動で埋められません。", fontWeight = FontWeight.Bold)
                        infeasible.take(4).forEach {
                            // [3.712.0] 行は事実と札だけ（理由の長文はホームの診断カードの「詳しく」）。
                            Text("・${shortageRowLine(it.dayLabel, it.shiftSymbol, it.miss)}　${shortageRowTag(it.verdict == CoverageVerdict.INFEASIBLE, it.blockedNow)}",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Text("担当できる職員（担当できるシフト）や希望を見直すと直せます。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        val infeasibleLanding = landingFor(infeasible.first())
                        Button(onClick = { onDismiss(); onGoEdit(infeasibleLanding) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(landingButtonLabel(infeasibleLanding))
                        }
                    }
                    blocked.isNotEmpty() -> {
                        // [3.401.0] 「動かせる人がいる」枠が無くなったが、埋まっていない枠は残っている状態。
                        //   ここで「直し終わりました」と言うのが旧実装の嘘だった。診断が調べた理由をそのまま出す。
                        Text("いまの希望・担当のままでは埋められない日が ${blocked.map { it.dayIndex }.distinct().size}日 残っています。", fontWeight = FontWeight.Bold)
                        blocked.take(4).forEach {
                            // [3.712.0] 行は事実と札だけ（理由の長文はホームの診断カードの「詳しく」）。
                            Text("・${shortageRowLine(it.dayLabel, it.shiftSymbol, it.miss)}　${shortageRowTag(it.verdict == CoverageVerdict.INFEASIBLE, it.blockedNow)}",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Text("再作成しても、この日は同じ結果になります。希望を1件調整する（編集タブ＞月次条件）か、担当できるシフトを増やしてください（編集タブ＞年間マスター①）。",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        val blockedLanding = blocked.firstNotNullOfOrNull { landingFor(it) }
                        Button(onClick = { onDismiss(); onGoEdit(blockedLanding) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(landingButtonLabel(blockedLanding))
                        }
                    }
                    else -> {
                        Text("人員不足の日はなくなりました。")
                        otherHardAfterShortage(ui.breakdown)?.let { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                            if (onTour != null && tourCount > 0) Button(onClick = { onDismiss(); onTour() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(guidedTourButton(tourCount), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        },
        // [3.480.0 ホームAIリデザイン] 「再作成」はここにも出していたが、常設の固定フッター
        // (BottomCommandBar)と重複する導線だった（grilling決定#3=フッターに一本化）。ここは常に「閉じる」のみ。
        confirmButton = { DialogDismissButton(onClick = onDismiss, text = "閉じる") },
    )
}

/** [operator_ux §3] 思考誘導カードの1状態分のプラン（文言・色・大ボタン・補助）。 */

internal class OpNextPlan(
    val container: Color, val fg: Color, val headline: String,
    val bigLabel: String, val bigAction: () -> Unit, val bigEnabled: Boolean,
    val helperLabel: String?, val helperAction: () -> Unit,
    val body: String? = null, val note: String? = null,   // 見出しの下の本文と注記
    val bigColors: Pair<Color, Color>? = null,   // 主ボタンの地と文字。null＝primary（段の地色と 3:1 に届かない段だけ渡す）
)

/**
 * [operator_ux §3] 思考誘導ホームの「次にやること」カード。
 * IT中学生レベルのオペレーター向け：専門用語ゼロ・大ボタン1つ・色で意味（緑=できた/黄=もう少し/赤=気をつけて）。
 * いまの状態（未作成／組立中／配れる／もう少し／埋められない）で文言と主ボタンが自動で変わる。
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OperatorNextActionCard(
    ui: UiState,
    onMake: () -> Unit,      // 勤務表をつくる（最適化）
    // [3.126.0 撤去] onDraft（下書きをつくる=簡易作成）はユーザー判断で撤去。主導線は「勤務表をつくる」1本。
    onSmartInitial: () -> Unit,  // [新設] 初期解を作る（希望→C1優先の下書き。本最適化は続けない）
    onStop: () -> Unit,      // やめる（停止）
    onExport: () -> Unit,    // 印刷・書き出し / そのまま配る（CSV書き出し）
    onSchedule: () -> Unit,  // 中身を見る（勤務表へ）
    onPreDist: () -> Unit = onExport,   // 配布前確認（必須違反 0 件の主ボタン。仕様 §10）
    onFix: () -> Unit,       // なおし方を見る（勤務表で手直し）
    onSetup: () -> Unit,     // データを見直す（編集へ）
    onLanding: (EditLanding?) -> Unit = {},   // [UX監査 中4] 原因のある「データを見直す」は対応する区分・節へ（null＝編集の先頭）
    onShowMove: () -> Unit = {},    // [思考誘導S0] 直す1手を見る
    onApplyMove: (com.magi.app.v6.FixSuggestion) -> Unit = {},   // [3.643.0] カードに出した手をこの場で当てる（押した時点で選び直さない＝統合仕様 INV-01）
    onAutoSearch: () -> Unit = {},  // [3.643.0] 必須違反が残り未探索なら 1 回だけ直し方を探す（旧 SmartActionCard の自動探索）
    onShowWishes: () -> Unit = {},  // [思考誘導S0/S3] ぶつかっている希望を見る（WishConflictDialog）
    onShowList: () -> Unit = {},    // [思考誘導S0] 問題を見る（分析タブ）
    outcomeLine: String? = null,    // [S5 §9] 直近の「希望を外して再作成」の結果（VM が鮮度を照合済み）
    savedLine: String? = null,      // 勤務表 CSV の保存状態（保存済み／今の内容と違う）。直した結果の行とは別に出す（両方あれば両方）
    consultLine: String? = null,    // [3.645.0/仕様 5.3] 相談中の件数（未確認事項）。完成の見出しは変えず、書き出しも止めない
    relax: RelaxToken? = null,      // [S6] いまのデータで見つかった設定の壁の組（VM が鮮度を照合済み。null＝無い）
    onShowRelax: () -> Unit = {},
    onStopRelax: () -> Unit = {},
    relaxStopped: Boolean = false,
    relaxFailed: Boolean = false,
    onRetryRelax: () -> Unit = {},
    stateOf: () -> com.magi.app.model.MagiState? = { null },   // 見出しで、手が解消する必須違反を名指しするための今の状態
    creation: CreationTop? = null,   // 自動作成の直後: カード上端の 2 行（作成の完了と次に確認する問題）
    tourNext: String? = null,        // 必須違反の巡回の先頭（残りの一覧・探索中の段の対象）
    onRetrySave: () -> Unit = {},    // 保存の失敗の行の「もう一度保存する」
    onOpenConsults: () -> Unit = {}, // 作成中の相談の行の「開く」（相談の一覧を出す）
) {
    val cs = MaterialTheme.colorScheme
    val infeasible = ui.coverageDiag?.allInfeasible == true
    val topFix = ui.fixSuggestions.firstOrNull { it.deltaHard < 0 }
    val topResolved = topFixResolvedLabels(ui)   // VM が盤面・手ごとに作る（main で検査しない）
    // [S5 §2.1] 関わる希望（S5a の行か S5b の行）があるか。WISH・FLOOR の分岐がこれを見る。
    val wishCands = remember(ui.violationCellFamilies, ui.wishes, ui.lockedWishKeys, ui.wishSelfConflicts, ui.coverageDiag) { wishTrialCandidates(ui) }
    val shortDays = ui.coverageDiag?.shortfalls?.map { it.dayIndex }?.distinct()?.size ?: 0
    val worstDay = ui.coverageDiag?.shortfalls?.firstOrNull()?.dayLabel
    // [UX監査 高2] 「なおし方を見る」の日は、開くダイアログと同じ枠から取る（ホームとダイアログの日を一致させる）。
    val guidedTarget = guidedFixTarget(ui.coverageDiag?.shortfalls ?: emptyList())
    val pinnedLanding = ui.coverageDiag?.shortfalls?.firstOrNull { it.wishPinned.isNotEmpty() }?.let { landingFor(it) }
    val infeasibleLanding = ui.coverageDiag?.shortfalls?.firstOrNull { it.verdict == CoverageVerdict.INFEASIBLE }?.let { landingFor(it) }
    // 充足不可の S5b 版は重複除去の前（S5a の行に畳まれた人も含む）で決め、例の日も希望で固定された人がいる枠から取る。
    val hasPinned = ui.coverageDiag?.shortfalls?.any { it.wishPinned.isNotEmpty() } == true
    val wishDay = ui.coverageDiag?.shortfalls?.firstOrNull { it.wishPinned.isNotEmpty() }?.dayLabel
    // 主ボタンに最初の対象を添える（押した先は変えない）。
    fun staffName(i: Int) = ui.staffNames.getOrNull(i) ?: "職員${i + 1}"
    val firstWish = wishCands.direct.firstOrNull() ?: wishCands.shortfall.firstOrNull()?.rows?.firstOrNull()
    val firstMove = ui.fixSuggestions.firstOrNull { it.deltaHard < 0 }?.ops?.firstOrNull()

    // [M3] 成功=tertiary / 注意=error / 主操作=primary はテーマロール。警告のみ独自トークンに集約。
    val (amber, onAmber) = magiWarnColors()
    // [3.643.0] 旧 SmartActionCard の自動探索をここへ。副作用は候補リストの計算だけ＝自動で始めても盤面は変わらない。
    LaunchedEffect(ui.schedule, ui.bestHard, ui.fixFocusName, ui.hasResult, ui.running) {
        if (!ui.running && ui.hasResult && ui.bestHard > 0 &&
            (ui.fixFocusName.isNotBlank() || (!ui.fixSearching && ui.fixSuggestions.isEmpty() && !ui.fixSearched))) onAutoSearch()
    }

    val hasMove = ui.fixSuggestions.any { it.deltaHard < 0 }
    val hp = homePlan(ui.running, ui.hasResult, ui.bestHard == 0L, infeasible, hasPinned, guidedTarget != null,
        hasMove, ui.fixFocusName.isBlank(), ui.fixSearching, ui.fixSearched, relax != null, ui.stalledHardFamilies.isNotEmpty(), !wishCands.isEmpty,
        ui.saveState == SaveState.Failed, ui.consults.size, draft = ui.draftStage && ui.engineRan)
    val kind = hp.kind
    // 段の対象は 1 か所で決め、主ボタンの括弧と「次に確認する問題」の両方に使う（INV-01）。
    val shortage = guidedTarget?.let { shortageHeadline(it) }
    val nextTarget = creationNextTarget(kind,
        shortage = guidedTarget?.let { "${it.dayLabel} ${it.shiftSymbol}" },
        move = firstMove?.let { "${staffName(it.staff)} ${DayText.short(ui.startDate, it.day)}" },
        wish = firstWish?.let { "${staffName(it.staff)} ${DayText.short(ui.startDate, it.day)}" },
        relax = relax?.let { relaxTarget(it.result, ui) }?.let { "${it.name} ${it.span}" },
        tour = tourNext ?: creation?.next)
    val wishLabel = homeTargetLabel(HOME_WISH_BUTTON, nextTarget, null)
    val hardNow = hardText(ui.breakdown, ui.bestHard)
    val plan = when (kind) {
        // [校正] 「やめる」は下部コマンドバーに常設済み。見出し文は下の進捗行と重複のため出さない（ユーザー赤囲い指示）。
        HomePlanKind.RUNNING -> OpNextPlan(cs.primaryContainer, cs.onPrimaryContainer, "", "", {}, false, null, onStop)
        HomePlanKind.MAKE -> OpNextPlan(cs.primaryContainer, cs.onPrimaryContainer, "",
            "勤務表をつくる", onMake, true, "希望と期間の制約で下書きをつくる", onSmartInitial,
            bigColors = cs.onPrimaryContainer to cs.primaryContainer)
        HomePlanKind.DRAFT -> OpNextPlan(cs.primaryContainer, cs.onPrimaryContainer, homeDraftStatus(hardNow, ui.bestHard),
            "勤務表をつくる", onMake, true, HOME_SCHEDULE_HELPER, onSchedule,
            bigColors = cs.onPrimaryContainer to cs.primaryContainer)
        HomePlanKind.PRE_DIST -> OpNextPlan(cs.tertiaryContainer, cs.onTertiaryContainer, "",
            HOME_PRE_DIST_BUTTON, onPreDist, true, HOME_SCHEDULE_HELPER, onSchedule,
            // [3.509.4/自動化方針] 前後比較（変更人数・セル数・希望充足・個人回数）の 1 行は主ボタンの下。
            body = ui.runSummary, note = if (ui.impossibleWishCount > 0) "担当外の希望が ${ui.impossibleWishCount} 件あります。" else null)
        HomePlanKind.WISH_INFEASIBLE -> OpNextPlan(cs.errorContainer, cs.onErrorContainer,
            "いまの希望のままでは、ここは埋められません。" + (wishDay?.let { "（例：$it）" } ?: ""),
            wishLabel, onShowWishes, true, "データを見直す", { onLanding(pinnedLanding) })
        HomePlanKind.DATA_INFEASIBLE -> OpNextPlan(cs.errorContainer, cs.onErrorContainer,
            "このデータでは、ここは埋められません。" + (worstDay?.let { "（例：$it）" } ?: ""),
            "データを見直す", { onLanding(infeasibleLanding) }, true, "未充足のまま書き出す", onPreDist)
        // [思考誘導S0] 未完成は「足りる？→1手ある？→希望が関わる？」の順に答え、主ボタンを1つだけ出す（段の順は homePlan）。
        HomePlanKind.GUIDED_FIX ->
            OpNextPlan(amber, onAmber, shortage?.headline ?: "",
                homeTargetLabel("なおし方を見る", nextTarget, null), onFix, true, null, onSetup)
        HomePlanKind.APPLY_MOVE -> {
            // [3.643.0] 旧「AIの解決提案」カードをここへ統合＝同じ問題について、手と効果（分かったこと）と「この手を使う」（操作）を 1 枚に。
            val top = ui.fixSuggestions.first { it.deltaHard < 0 }
            val (hardLine, caution) = fixImpactLines(top)
            // 必須の変化（族ごと）と増える要調整だけ（件・回・人・pt を 1 つの合計に足さない＝統合仕様 INV-07）。補助は確認の画面を開くだけ。
            OpNextPlan(amber, onAmber, homeFixHeadline(hardNow, topResolved),
                homeTargetLabel("直す1手を見る", nextTarget, null), onShowMove, true, REVIEW_CHANGE_BUTTON, { onApplyMove(top) },
                body = fixDisplayLabel(top.label),
                note = (hardChangeLine(top.diff) ?: hardLine) + (caution?.let { "\n$it" } ?: ""))
        }
        HomePlanKind.SEARCHING ->
            OpNextPlan(amber, onAmber, "直し方を探しています…", "", {}, false, null, onSetup)
        // [S6 §2.1] 必須違反の一部が利用者自身の設定（上限 0）で塞がれているときだけ、希望の段より先に出す。
        HomePlanKind.RELAX -> {
            val t = relaxCardText(relax!!.result, ui)
            OpNextPlan(amber, onAmber, t.headline,
                "緩める候補を見る", onShowRelax, true, if (wishCands.isEmpty) null else "ぶつかっている希望を見る", onShowWishes,
                body = t.body, note = t.note)
        }
        // [思考誘導S4] 下限の宣言は保守的に: 1手の探索を終えて候補が無く、必須族が長く改善せず残り、希望が関わるときだけ。
        HomePlanKind.WISH_FLOOR ->
            OpNextPlan(amber, onAmber,
                if (ui.relaxSearching) "" else "これ以上は減らせない見込みです",
                wishLabel, onShowWishes, true, "このまま書き出す", onPreDist)
        HomePlanKind.WISHES ->
            OpNextPlan(amber, onAmber, "希望とルールがぶつかっています",
                wishLabel, onShowWishes, true, null, onSetup)
        HomePlanKind.LIST -> OpNextPlan(amber, onAmber, nextTarget ?: "",
            HOME_LIST_BUTTON, onShowList, true, null, onSetup)
    }
    // 並びは純関数 `homeCardSections` が決める＝ここは区画ごとの中身を描くだけ（C# と 1 対 1）。
    val sections = homeCardSections(hp, ui)
    val saveFailed = ui.saveState == SaveState.Failed
    val statusLines = homeStatusLines(kind, plan.headline, consultLine, savedLine, saveFailed)
    val body = MaterialTheme.typography.bodyMedium

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = plan.container)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (sec in sections) when (sec) {
                HomeSection.CREATION -> if (creation != null) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    creationTopLines(nextTarget, creation.kept, headlineTarget = nextTarget).forEach { Text(it, style = body, color = plan.fg) }
                }
                HomeSection.HEADLINE -> Text(homeHeadline(kind, ui.startDate), style = MaterialTheme.typography.titleLarge, color = plan.fg, fontWeight = FontWeight.Bold)
                // 札は見出しと同じ語で検査の結果だけを言う（「完成」とは言わない＝人の確認と分ける）。件と人は足さない（INV-07）。
                HomeSection.BADGES -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in listOf(homeHardPill(ui.bestHard, hardNow))) {
                        Box(Modifier.background(MagiAccent.orange, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
                            // [コントラスト] 白文字は淡い原色(緑/橙)で2.2:1と不足するため WCAG 保証（不足時のみ黒へ）。
                            Text(b, style = MaterialTheme.typography.labelMedium, color = ensureReadable(MagiAccent.orange, Color.White), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HomeSection.STATUS -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 段の 1 文は大きく、保存の失敗の行（段に関係なく先頭）は本文の太字。
                    val lead = statusLines.indexOfFirst { it != HOME_SAVE_FAILED_LINE }
                    statusLines.forEachIndexed { i, l ->
                        Text(l, style = if (i == lead && kind != HomePlanKind.PRE_DIST) MaterialTheme.typography.titleMedium else body, color = plan.fg,
                            fontWeight = if (l == HOME_SAVE_FAILED_LINE) FontWeight.Bold else null)
                    }
                    if (saveFailed) OutlinedButton(onClick = onRetrySave, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = plan.fg), border = BorderStroke(1.dp, plan.fg)) { Text(SAVE_RETRY_BUTTON) }
                    // 作成直後の自動探索はカードの中の文字ボタンで止める（下部バーは外枠のまま。止められる性質は 3.402.0 のまま）。
                    if (kind == HomePlanKind.SEARCHING) TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text("やめる", color = plan.fg) }
                }
                HomeSection.PROGRESS -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = plan.fg)
                        // [進捗の見える化] 何をしているかの語を先頭に。progressSummary（改善率/残り時間/探索数）はそのまま。
                        Text("勤務表を組んでいます ・ ${progressSummary(ui)}", color = plan.fg, style = body, modifier = Modifier.weight(1f))
                    }
                    // [3.714.0] 作成中も相談の 1 行から一覧を開ける（文字ボタン、塗りは増やさない）。
                    if (consultLine != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(consultLine, color = plan.fg, style = body, modifier = Modifier.weight(1f))
                        TextButton(onClick = onOpenConsults, modifier = Modifier.heightIn(min = 48.dp)) { Text(CONSULT_OPEN, color = plan.fg) }
                    }
                }
                HomeSection.PRIMARY -> {
                    ui.sameResult?.takeIf { sameResultVisible(it, ui.editRev, ui.schedule) }?.let { Text(sameResultNotice(it.hard), style = body, color = plan.fg) }
                    val (bigBg, bigFg) = plan.bigColors ?: (cs.primary to cs.onPrimary)
                    Button(onClick = plan.bigAction, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = bigBg, contentColor = bigFg)) {
                        Text(plan.bigLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // [3.713.0] 補助操作は文字ボタン（塗りは主ボタン 1 つ）。段に補助が無ければ「表で全体を見る」。
                HomeSection.HELPER -> TextButton(
                    onClick = if (plan.helperLabel != null) plan.helperAction else onSchedule,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(plan.helperLabel ?: HOME_SCHEDULE_HELPER, color = plan.fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                HomeSection.OTHERS -> if (creation != null) TextButton(onClick = creation.onOthers, modifier = Modifier.heightIn(min = 48.dp)) { Text(HOME_OTHERS_BUTTON, color = plan.fg) }
                HomeSection.DETAIL -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    plan.body?.let { Text(it, style = body, color = plan.fg) }
                    plan.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = plan.fg) }
                    if (outcomeLine != null) Text(outcomeLine, style = body, color = plan.fg)
                    if (savedLine != null && savedLine !in statusLines) Text(savedLine, style = body, color = plan.fg)
                    // [3.643.0] 探索がどう終わったか（停滞で早く終えた理由・残る必須の性質）。内部名は出さない（StopExplanation）。次の一手は主ボタンが言う。
                    if (ui.hasResult) ui.stopSummary?.let { s -> stopExplanationOf(s, ui.breakdown)?.let { e -> Text(e.line, style = MaterialTheme.typography.bodySmall, color = plan.fg) } }
                    if (ui.relaxSearching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(RELAX_SEARCHING_TEXT, style = MaterialTheme.typography.bodySmall, color = plan.fg, modifier = Modifier.weight(1f))
                            TextButton(onClick = onStopRelax, modifier = Modifier.heightIn(min = 48.dp)) { Text("やめる", color = plan.fg) }
                        }
                    } else if ((relaxStopped || relaxFailed) && ui.bestHard > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (relaxFailed) RELAX_FAILED_TEXT else RELAX_STOPPED_TEXT, style = MaterialTheme.typography.bodySmall, color = plan.fg, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRetryRelax, modifier = Modifier.heightIn(min = 48.dp)) { Text(RELAX_RETRY_LABEL, color = plan.fg) }
                        }
                    }
                }
                // [3.480.0 ホームAIリデザイン] 文言（正直さ）は変えず「でき具合：N%（残りM件）」＋バー。注記は既定折りたたみ（ⓘ）。
                // [3.483.0 H-2] 必須0のときは残りの単位を調整件数へ。
                HomeSection.SATISFACTION -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val remainingLabel = homeRemainingLabel(ui.bestHard, shortDays, ui.breakdown, ui.hardWishConflict)
                    Text("でき具合：${ui.satisfaction}%（${remainingLabel}）", style = body, color = plan.fg, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(
                        progress = { ui.satisfaction.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp),
                        color = plan.fg, trackColor = plan.fg.copy(alpha = 0.2f),
                    )
                    if (ui.bestHard > 0L) Text(HOME_SOFT_NOTE, style = MaterialTheme.typography.bodySmall, color = plan.fg)
                }
                HomeSection.INFO -> {
                    var detailOpen by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { detailOpen = !detailOpen },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            if (detailOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null, tint = plan.fg, modifier = Modifier.size(16.dp),
                        )
                        Text(if (detailOpen) "ⓘ 詳しい説明を閉じる" else "ⓘ でき具合の意味", style = MaterialTheme.typography.bodySmall, color = plan.fg)
                    }
                    // [判断設計監査 #1/#2] 数字の根拠（できあがり度の意味）と結果採用の意味（反映済み・取消可・確定は書き出し時）を1文字も変えず折りたたみへ収納。
                    if (detailOpen) {
                        Text(
                            "※でき具合＝最初からの違反の減り具合（必須違反が残る間は最大55%）。" +
                                "結果は下書きに反映しました。「元に戻す」で取り消せます。確定は書き出すときです。",
                            style = MaterialTheme.typography.bodySmall, color = plan.fg,
                        )
                    }
                }
                // [3.261.0] 初期解生成へ常に戻れる小さな常設リンク（元に戻すで取消可能・破壊的でない）。
                // [3.483.0 H-4] 押すと今の結果を捨てて初期解へ戻る操作なので、その結果と戻し方を文言に含める。
                HomeSection.DRAFT_LINK -> Column(Modifier.fillMaxWidth()) {
                    TextButton(onClick = onSmartInitial, modifier = Modifier.fillMaxWidth()) { Text("下書きから作り直す") }
                    Text("今の結果は「元に戻す」で戻せます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

/**
 * [3.480.0 ホームAIリデザイン] 診断カードの「エンジン内部の観測メトリクス」（却下件数の内訳・
 * 計測範囲の注記など）を既定で折りたたむ開閉行。ConstraintEditor.kt の ConstraintHelpExpander と
 * 同じ開閉パターン＝**文言は一切変えず、隠す場所を変えるだけ**（過去の教訓＝断定的な言い換えは危険、
 * を踏まない。3.263.0/3.322.0/3.401.0）。
 */
@Composable
private fun DiagDetailToggle(
    open: Boolean,
    onToggle: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    closedText: String = "ⓘ 詳細（内部の記録）",
    openText: String = "ⓘ 詳細を閉じる",
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null, tint = tint, modifier = Modifier.size(16.dp),
        )
        Text(if (open) openText else closedText, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** [対象月の選択] 勤務表を作る月を前月/翌月/今月で選ぶ。変更でその月の日数に合わせて表を作り直す。 */

@Composable
internal fun CopilotCard(
    ui: UiState,
    onGoEdit: () -> Unit,
    // 希望の編集は月次条件（D7）＝編集タブの今の節に任せない。
    onEditWishes: () -> Unit = {},
    onSoftPolish: () -> Unit = {},
) {
    // [冗長性削減] できあがり度・進捗は OperatorNextActionCard が表示するため、ここは助言/警告だけに専念。
    //   担当外の希望は主カードの見出しと「設定の見直し」が言う（ここで 3 度目を言わない）。
    val cs = MaterialTheme.colorScheme
    val show = ui.copilotHint != null || (ui.polishExhausted && !ui.running)
    if (!show) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ガチャ操作の助言＋修正導線（NextActionBar相当）
            ui.copilotHint?.let {
                Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("💡 $it", color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onGoEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("編集タブで見直す") }
                    }
                }
            }
            if (ui.polishExhausted && !ui.running) {
                Surface(color = cs.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSoftPolish, modifier = Modifier.heightIn(min = 48.dp)) { Text(SOFT_POLISH_LINK, color = cs.onTertiaryContainer) }
                        Text(SOFT_POLISH_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
                    }
                }
            }
        }
    }
}

/**
 * 人員不足(covU)が残るときだけ表示する原因診断カード。
 * 各不足枠を「充足不可（データ上どう割り当てても埋まらない＝証明つき）」か
 * 「充足可能（枠は足りる＝最適化が未到達）」、「試した入れ替えでは埋まらなかった（限定探索の不発＝証明ではない）」に切り分ける。
 */

@Composable
internal fun CoverageDiagnosisCard(ui: UiState, onCancelWish: (Int, Int) -> Unit = { _, _ -> }) {
    val diag = ui.coverageDiag ?: return
    if (!diag.hasShortage && !diag.hasSurplus) return
    val cs = MaterialTheme.colorScheme
    var allShortfalls by remember { mutableStateOf(false) }
    var allSurpluses by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (diag.hasShortage) {
                Text("人員不足", style = MaterialTheme.typography.titleMedium)
                Text(shortageCardLine(diag.shortfalls.map { it.dayIndex }.distinct().size, diag.shortfalls.any { it.verdict != CoverageVerdict.INFEASIBLE && !it.blockedNow }),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                // [3.344.0] 「充足可能N枠」と言いながら各枠の説明が「いまの希望のままでは解消できません」
                //   という矛盾を解消する。枠の数（静的に足りているか）と、いま実際に埋められるかは別軸。
                val headline = when {
                    diag.allInfeasible -> "不足 ${diag.totalShortfall} 人はすべて、担当できる人が足りません。今のデータでは満たせません。" +
                        "担当できる職員を確認すると直せます（編集タブ＞年間マスター①）。"
                    diag.allBlockedNow && diag.searchMissSlots == 0 -> "不足 ${diag.totalShortfall} 人は、いまの希望・担当のままでは埋められません。" +
                        "希望を1件調整する（編集タブ＞月次条件）か、担当を追加してください（編集タブ＞年間マスター①）。"
                    diag.allBlockedNow -> "不足 ${diag.totalShortfall} 人は、試した入れ替えでは埋まりませんでした（埋められないと決まったわけではありません）。" +
                        "再作成するか、希望を1件調整する（編集タブ＞月次条件）か、担当を追加すると埋まりやすくなります（編集タブ＞年間マスター①）。"
                    diag.provenBlockedSlots > 0 -> "不足 ${diag.totalShortfall} 人。うち ${diag.provenBlockedSlots} 枠は" +
                        "いまの希望のままでは埋められません。その枠は希望を1件調整する（編集タブ＞月次条件）か、担当を追加すると直せます（編集タブ＞年間マスター①）。" +
                        "残りは再作成すると解消する場合があります。"
                    diag.searchMissSlots > 0 -> "不足 ${diag.totalShortfall} 人。うち ${diag.searchMissSlots} 枠は試した入れ替えでは埋まりませんでした。" +
                        "再作成すると解消する場合があります。"
                    diag.infeasibleSlots == 0 -> "不足 ${diag.totalShortfall} 人。$SHORTAGE_ENOUGH_TEXT"
                    else -> "不足 ${diag.totalShortfall} 人。${SHORTAGE_LABEL_INFEASIBLE} ${diag.infeasibleSlots} 枠、${SHORTAGE_LABEL_FIXABLE} ${diag.fixableSlots} 枠。" +
                        "動かせば埋まる枠は再作成すると解消する場合があります。担当できる人が足りない枠は担当できる職員を確認すると直せます（編集タブ＞年間マスター①）。"
                }
                // [3.712.0] 見出しの長い説明は「詳しく」の中へ。
                if (details) Text(headline, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                val shownShortfalls = if (allShortfalls) diag.shortfalls else diag.shortfalls.take(6)
                for (s in shownShortfalls) {
                    val infeasible = s.verdict == CoverageVerdict.INFEASIBLE
                    val container = if (infeasible) cs.errorContainer else cs.secondaryContainer
                    val onContainer = if (infeasible) cs.onErrorContainer else cs.onSecondaryContainer
                    Surface(color = container, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(shortageRowLine(s.dayLabel, s.shiftSymbol, s.miss),
                                    color = onContainer, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                MagiTagChip(
                                    text = shortageRowTag(infeasible, s.blockedNow),
                                    color = when {
                                        infeasible -> MagiAccent.red
                                        s.blockedNow -> MagiAccent.orange
                                        else -> MagiAccent.blue
                                    },
                                )
                            }
                            shortageRowNote(infeasible, s.blockedNow)?.let { Text(it, color = onContainer, style = MaterialTheme.typography.bodySmall) }
                            if (details) Text(s.reason, color = onContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (shownShortfalls.size < diag.shortfalls.size) SettingIssuesShowAll(diag.shortfalls.size - shownShortfalls.size) { allShortfalls = true }
                if (diag.relaxations.isNotEmpty()) {
                    Surface(color = cs.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("解けるようにするには（担当追加の案）", color = cs.onTertiaryContainer, style = MaterialTheme.typography.titleSmall)
                            for (r in diag.relaxations.take(4)) {
                                Text("・$r", color = cs.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("※ 担当追加の提案です。設定は変えません。採用するときは編集タブ＞年間マスター①で担当を追加してください。",
                                color = cs.onTertiaryContainer.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (diag.hasSurplus) {
                Text("人員過剰がなぜ減らないか", style = MaterialTheme.typography.titleMedium)
                Text("過剰 ${diag.totalSurplus} 人", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                val shownSurpluses = if (allSurpluses) diag.surpluses else diag.surpluses.take(6)
                for (s in shownSurpluses) {
                    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${s.dayLabel}  ${s.shiftSymbol}  必要${s.need}/現状${s.got}（過剰${s.excess}）",
                                color = cs.onSecondaryContainer, style = MaterialTheme.typography.titleSmall)
                            // [3.406.0] 主因は画面では日本語で出す（ログは生キー＝C1Plateau と同じ規約）。
                            val famJp = s.blockedFamily?.let { breakdownLabels[it] ?: it }
                            Text((if (details) s.reason else surplusShortReason(s.reason)) + (famJp?.let { "（主因: $it）" } ?: ""),
                                color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodySmall)
                            // [3.492.0] 「希望固定N人」の中身を名指しし、その場で希望を取り消せる（データ修正の導線）。
                            for (i in s.pinnedStaff) {
                                OutlinedButton(
                                    onClick = { onCancelWish(i, s.dayIndex) },
                                    enabled = !ui.running,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text("${ui.staffNames.getOrNull(i) ?: "#$i"}さんの希望（${s.shiftSymbol}）を外す", color = cs.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                if (shownSurpluses.size < diag.surpluses.size) SettingIssuesShowAll(diag.surpluses.size - shownSurpluses.size) { allSurpluses = true }
            }
            DiagDetailToggle(details, { details = !details }, closedText = DIAG_DETAILS_LABEL, openText = DIAG_DETAILS_CLOSE)
        }
    }
}


/**
 * [3.280.0] 禁止連続(c3n)の「なぜ崩せないか」診断カード。CoverageDiagnosisCard（人員不足/過剰の原因）の
 * c3n 版＝同じ作り。必須違反が残っているのに探索が進まないとき、「構造的に不能（希望調整が必要）」か
 * 「多段手が必要（再実行で解消し得る）」かを違反 run ごとに示す。読取専用・スコア不変。
 */
@Composable
internal fun ForbiddenRunDiagnosisCard(ui: UiState, onRelaxRule: (String) -> Unit = {}) {
    val diag = ui.forbiddenDiag ?: return
    if (!diag.hasRuns) return
    val cs = MaterialTheme.colorScheme
    var allRuns by remember { mutableStateOf(false) }
    var confirmRule by remember { mutableStateOf<Pair<String, Int>?>(null) }   // 並びと、その崩せない件数
    var details by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("禁止の並びがなぜ崩せないか", style = MaterialTheme.typography.titleMedium)
            val headline = if (diag.allBlocked) {
                "残り ${diag.totalRuns} 件は全て塞がっています。今の希望・担当のままでは崩せません。"
            } else {
                "残り ${diag.totalRuns} 件。崩す手が残っている並びがあります。"
            }
            Text(headline, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            val shownRuns = if (allRuns) diag.runs else diag.runs.take(6)
            for (r in shownRuns) {
                val blocked = !r.escapable
                val container = if (blocked) cs.errorContainer else cs.secondaryContainer
                val onContainer = if (blocked) cs.onErrorContainer else cs.onSecondaryContainer
                Surface(color = container, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${r.staffName}  ${r.seqLabel}",
                                color = onContainer, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            MagiTagChip(
                                text = forbiddenRunTag(!blocked),
                                color = if (blocked) MagiAccent.red else MagiAccent.blue,
                            )
                        }
                        Text(forbiddenRunCellsLine(r.cells.map { it.dayLabel to it.shiftSymbol }), color = onContainer, style = MaterialTheme.typography.bodySmall)
                        // [3.712.0] セルごとの判定と理由は「詳しく」の中だけ。玉突きの定型文は行に出さず末尾に 1 回。
                        if (details) {
                        val cellsTxt = r.cells.joinToString(" ・ ") { c ->
                            val tag = when (c.escape) {
                                com.magi.app.v6.ForbiddenCellEscape.FREE -> "崩せる"
                                com.magi.app.v6.ForbiddenCellEscape.CHAIN -> "玉突きで崩せる"
                                com.magi.app.v6.ForbiddenCellEscape.ADJACENT -> "隣接日調整で崩せる"
                                com.magi.app.v6.ForbiddenCellEscape.PINNED -> "本人の希望"
                                com.magi.app.v6.ForbiddenCellEscape.BLOCKED -> "塞がり"
                            }
                            "${c.dayLabel} ${c.shiftSymbol}=$tag"
                        }
                        Text(cellsTxt, color = onContainer, style = MaterialTheme.typography.bodySmall)
                        if (r.hint != com.magi.app.v6.V6PortAnalyzer.FORBIDDEN_CHAIN_HINT) Text(r.hint, color = onContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (shownRuns.size < diag.runs.size) SettingIssuesShowAll(diag.runs.size - shownRuns.size) { allRuns = true }
            if (details && diag.runs.any { it.hint == com.magi.app.v6.V6PortAnalyzer.FORBIDDEN_CHAIN_HINT }) Text(FORBIDDEN_RETRY_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            DiagDetailToggle(details, { details = !details }, closedText = DIAG_DETAILS_LABEL, openText = DIAG_DETAILS_CLOSE)

            // [壁の名指しと緩和] 「崩せない」判定の run を並び（ルール）ごとに集約し、そのルールを
            //   その場で削除できるようにする。制約画面へ行って行を探す往復を省く導線。
            //   崩す手が残っている並びは探索で解けうるので出さない（先にルールを消させない）。
            val walls = diag.runs.filter { !it.escapable }
                .groupBy { it.seqLabel }
                .toList()
                .sortedByDescending { it.second.size }
            if (walls.isNotEmpty()) {
                HorizontalDivider()
                Text("崩せない原因の並び", style = MaterialTheme.typography.titleSmall)
                Text("この並びを禁止しているかぎり、必須違反は残ります。ルールを外すとその場で再チェックします（元に戻せます）。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                for ((seqLabel, rows) in walls) {
                    val who = rows.map { it.staffName }.distinct()
                    val whoTxt = who.take(3).joinToString("・") + if (who.size > 3) " ほか${who.size - 3}名" else ""
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(seqLabel, style = MaterialTheme.typography.titleSmall)
                            Text("${rows.size}件（$whoTxt）", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = { confirmRule = seqLabel to rows.size },
                            enabled = !ui.running,
                            colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                        ) { Text("この並びの禁止を解除") }
                    }
                }
            }
        }
    }
    confirmRule?.let { (seq, n) ->
        AlertDialog(
            onDismissRequest = { confirmRule = null },
            title = { Text("禁止の並びを解除しますか？") },
            text = { Text("「$seq」の禁止を、全員について外します（いま崩せない並び ${n}件）。外すとその場で再チェックします。元に戻すで取り消せます。") },
            confirmButton = { DialogDangerButton("解除する", onClick = { confirmRule = null; onRelaxRule(seq) }) },
            dismissButton = { DialogDismissButton(onClick = { confirmRule = null }, text = "解除しない") },
        )
    }
}


/**
 * [窓の要件が直せなかった理由 / 3.322.0] 直近の最適化で c1 研磨が候補を却下した記録を、
 * 職員×シフトごとに理由つきで見せる。CoverageDiag（人員不足）・ForbiddenDiag（禁止連続）に続く3枚目。
 *
 * この2枚と違い**盤面から再計算できない**（根拠が「研磨が実際に候補を作って却下した」観測のため）。
 * したがって「構造的に不能」とは言わない — 言えるのは「いまの設定で、**試した**手が却下された」までで、
 * 試していない手の存在は否定しない。回数の幅を見直せば通る可能性が残る、という含みを文言で明示する。
 *
 * [3.325.0] 回数固定の横断集計（研磨パス全体の観測）は c1 固有の話ではないので
 * [PinFixedImpactCard] へ分離した。このカードは c1 の理由だけを扱う。
 */
@Composable
internal fun C1PlateauCard(ui: UiState, onGoEdit: () -> Unit = {}) {
    val diag = ui.c1Plateau ?: return
    val cs = MaterialTheme.colorScheme
    // [3.325.0] c1 が残っているのに却下の観測が1件も無い場合。研磨が起点を取れなかった／後続パスが
    //   別の窓を直して観測分だけ消えた、などで起こる。ここで理由を語ると観測していないことを語ることに
    //   なるので、「原因未確定」と明示して次の一手だけ示す。
    if (diag.causeUnknown) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("期間の制約が残っています（原因未確定）", style = MaterialTheme.typography.titleMedium)
                Text("残り ${diag.remainingC1} 件。今回の整えでは、この残りについて直し方を試した記録が" +
                    "残っていません。原因は特定できていません。再作成すると記録が取れる場合があります。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        return
    }
    if (!diag.hasEntries) return
    var detailOpen by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("期間の制約がなぜ直せなかったか", style = MaterialTheme.typography.titleMedium)
            // [3.480.0 ホームAIリデザイン] 旧: この注記は常時表示だった。grilling決定#1どおり
            // 文言は変えず、既定折りたたみへ移す（内容は下の DiagDetailToggle 展開時のみ表示）。
            if (detailOpen) {
                // [3.324.0→3.328.0] 断定を外す。3.326.0 で内訳は**決まりごと**に分けたので、
                //   「まとめて数えている」という 3.324.0 当時の注記はもう実態と合わない。
                Text("※ 直近の最適化で試した直し方の記録です。期間の制約は職員・シフト・決まりごとに分けています" +
                    "（同じ決まりの中に複数の期間がある場合はまとめて数えています）。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            val shown = if (showAll) diag.entries else diag.entries.take(6)
            for (e in shown) {
                val pin = e.cause == com.magi.app.v6.C1PlateauCause.PIN_CONSTRAINED
                val container = if (pin) cs.errorContainer else cs.secondaryContainer
                val onContainer = if (pin) cs.onErrorContainer else cs.onSecondaryContainer
                Surface(color = container, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(e.label, color = onContainer,
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            MagiTagChip(
                                text = when (e.cause) {
                                    com.magi.app.v6.C1PlateauCause.PIN_CONSTRAINED -> "回数固定で却下"
                                    com.magi.app.v6.C1PlateauCause.SCORE_TRADEOFF -> "他とのトレードオフ"
                                    com.magi.app.v6.C1PlateauCause.NO_CANDIDATE -> "この直し方では候補なし"
                                },
                                color = if (pin) MagiAccent.red else MagiAccent.blue,
                            )
                        }
                        // 根拠の内訳。「試した手が何件あって、何で落ちたか」を数で示す（推測でなく観測）。
                        // [3.480.0] エンジン内部の観測メトリクスのため既定は折りたたみ（DiagDetailToggle）。
                        if (detailOpen) {
                            val parts = ArrayList<String>()
                            if (e.rejectedByPin > 0) parts.add("回数固定で却下 ${e.rejectedByPin}件")
                            if (e.rejectedByScore > 0) parts.add("総合評価で却下 ${e.rejectedByScore}件")
                            if (e.noCandidate > 0) parts.add("候補なし ${e.noCandidate}件")
                            Text(parts.joinToString(" ・ "), color = onContainer, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(e.recommendedAction { fam -> breakdownLabels[fam] ?: fam },
                            color = onContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (shown.size < diag.entries.size) SettingIssuesShowAll(diag.entries.size - shown.size) { showAll = true }
            DiagDetailToggle(detailOpen, onToggle = { detailOpen = !detailOpen })
            if (diag.pinConstrained > 0) {
                TextButton(onClick = onGoEdit, enabled = !ui.running) { Text("個人の回数を見直す") }
            }
        }
    }
}


/**
 * [回数固定の影響 / 3.325.0] 「回数を固定している（下限＝上限）ことだけが理由で却下された候補試行」の
 * 横断集計。C1PlateauCard から分離した理由は2つ:
 *  - この観測は c1 固有ではない（実データでは適切回数・公平化・連続パターンの研磨が大半を占める）。
 *    c1 が 0 でも回数固定の影響はあり得るので、c1 の診断に従属させると出せなくなる。
 *  - c1 の内訳（職員×シフト）と横断集計（研磨の試行回数）は粒度も母集団も違う。同じカードに混ぜると
 *    どちらの数字なのか読めない。
 *
 * **数字の読み方 [3.327.0 で範囲を訂正]**: 全手数でも改善予測でもない。計測しているのは後処理研磨のうち
 * `V6HotfixPasses` の19パス＋最終LNS 2本だけで、`EliteIntegration`/
 * `C1TemporalFlow`/`CombinatorialRepair` の10箇所と、ピン保護を持たない探索本体(SA/ALNS/LAHC)は計測外。
 * 最大4巡を重複排除せず加算した「計測済みの候補試行数」で、言えるのは
 * 「少なくとも N 回、回数固定だけが却下の理由だった」まで。
 * 0 は「緩めても変わらない」の証明にはならない（計測外の経路がある）。
 */
@Composable
internal fun PinFixedImpactCard(
    ui: UiState,
    onGoEdit: () -> Unit = {},
    onRelax: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
) {
    val attempts = ui.observedPinBlockedAttempts
    if (attempts <= 0) return
    val cs = MaterialTheme.colorScheme
    var detailOpen by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("回数の固定が最適化に与えた影響", style = MaterialTheme.typography.titleMedium)
            Text("回数を固定していることだけが理由で見送られた試行が、少なくとも $attempts 回ありました。" +
                "これらは他の条件では採用できる手でした。",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            // [3.480.0 ホームAIリデザイン] 計測範囲の注記は文言を変えず既定折りたたみへ（grilling決定#1）。
            DiagDetailToggle(detailOpen, onToggle = { detailOpen = !detailOpen })
            if (detailOpen) {
                // [3.324.0/外部レビュー] 幅は決め打ちしない（実測で ±1 が良い月と ±3 が良い月があり優劣が
                //   逆転した）。件数の性質も正直に添える。
                Text("※ 全部の手数ではなく、仕上げの整えのうち計測できた範囲の試行回数です（同じ手を複数回数えている" +
                    "場合があります）。この数が 0 でも、緩めて変わらないとは限りません。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text("試すときは、対象の職員とシフト、そして緩める幅を決めて、変更する前と後を見比べてください。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            // [3.326.0] どのピンが止めたかを対象別に出し、その場で1段だけ緩められるようにする。
            //   幅は決め打ちせず下限側・上限側を別々に選ばせる（実測で ±1 と ±3 の優劣が逆転したため）。
            //   押すと設定が変わるので「元に戻す」で戻せることを添える。
            if (ui.pinTargets.isNotEmpty()) {
                HorizontalDivider()
                Text("止めていた回数固定", style = MaterialTheme.typography.titleSmall)
                val shown = if (showAll) ui.pinTargets else ui.pinTargets.take(5)
                for (t in shown) {
                    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${t.staffName} ${t.shiftKigou}：${t.pinnedCount}回に固定（${t.attempts}回の試行を止めました）",
                                color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // [3.328.0/外部レビュー] 0回に固定されている行では「下限を1下げる」が
                                //   0 でクランプされて無操作になる（押しても何も起きないボタンだった）。
                                //   下げられるときだけ出す。
                                if (t.pinnedCount > 0) {
                                    TextButton(onClick = { onRelax(t.staff, t.shift, -1, 0) }, enabled = !ui.running) {
                                        Text("下限を1下げる（${t.pinnedCount - 1}〜${t.pinnedCount}）")
                                    }
                                }
                                TextButton(onClick = { onRelax(t.staff, t.shift, 0, 1) }, enabled = !ui.running) {
                                    Text("上限を1上げる（${t.pinnedCount}〜${t.pinnedCount + 1}）")
                                }
                            }
                        }
                    }
                }
                if (shown.size < ui.pinTargets.size) SettingIssuesShowAll(ui.pinTargets.size - shown.size) { showAll = true }
                Text("押すと設定が変わります。「元に戻す」で戻せます。効果は再作成すると分かります。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            TextButton(onClick = onGoEdit, enabled = !ui.running) { Text("個人の回数を見直す") }
        }
    }
}


/**
 * [設定ミスの誘導修正] 制約・希望シフトの入力間違いを「どこが・なぜ・どう直すか」で具体的に提示する。
 * CoverageDiagnosisCard（人員不足の原因）と同じ作りで、配布前に設定を直せるようにするのが目的。
 */
@Composable
internal fun SettingIssuesCard(
    ui: UiState,
    onFix: (com.magi.app.v6.SettingIssue) -> Unit,
    // [UX監査#4] 一覧は重要な順に整列済み＝先頭の種別を編集タブの節誘導に使う（無ければnull＝従来どおりtabのみ）。
    onGoEdit: (com.magi.app.v6.IssueKind?) -> Unit,
    onClearWishes: () -> Unit = {},
    // 一括クリアが実際に消す件数（VM の canDo 判定）。行の種別で数えると希望どうしの c3w 衝突まで混ざる。
    wishClearCount: Int = 0,
) {
    val issues = ui.settingIssues
    if (issues.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    // [3.480.0 ホームAIリデザイン] 旧: N件を常時カード表示で縦に並べ、6件超は「ほかN件」表記のみだった
    // （既にtake(6)で頭打ちだったが、それでも6枚のカードが常時スクロールを占領していた）。
    // ①「担当外の希望」は同型行がまとまりやすいので一括クリアを先頭に置く ②一覧は既定折りたたみ
    // （DiagDetailToggle・付随事項として扱う）へ。
    var detailOpen by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // 分析タブの同じ項目と同じ要調整の色（赤は必須違反だけ）。
    val (warnBg, warnFg) = magiWarnColors()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("設定の見直し（${issues.size}件）", style = MaterialTheme.typography.titleMedium)
            DiagDetailToggle(
                detailOpen, onToggle = { detailOpen = !detailOpen },
                closedText = "ⓘ 一覧を見る（${issues.size}件）", openText = "ⓘ 一覧を閉じる",
            )
            if (detailOpen) {
                // まとめて外すのは一覧を開いて中身を見たあとだけ。
                if (wishClearCount > 1) OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                ) { Text(bulkClearWishesLabel(wishClearCount)) }
                val shown = if (showAll) issues else issues.take(SETTING_ISSUE_PREVIEW)
                for (s in shown) {
                    val label: String
                    val tagColor: androidx.compose.ui.graphics.Color
                    when (s.kind) {
                        com.magi.app.v6.IssueKind.WISH -> { label = "希望"; tagColor = MagiAccent.blue }
                        com.magi.app.v6.IssueKind.CONSTRAINT -> { label = "制約"; tagColor = MagiAccent.red }
                        com.magi.app.v6.IssueKind.DEMAND -> { label = "必要人数"; tagColor = MagiAccent.red }
                        com.magi.app.v6.IssueKind.RANGE -> { label = "回数"; tagColor = MagiAccent.orange }
                    }
                    Surface(color = warnBg, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MagiTagChip(text = label, color = tagColor)
                                Text(s.where, color = warnFg, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            }
                            Text(s.problem, color = warnFg, style = MaterialTheme.typography.bodySmall)
                            Text("→ ${s.fix}", color = warnFg, style = MaterialTheme.typography.bodyMedium)
                            if (s.actionLabel.isNotEmpty()) {
                                Button(onClick = { onFix(s) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                                    Text(s.actionLabel)
                                }
                            }
                        }
                    }
                }
                if (shown.size < issues.size) SettingIssuesShowAll(issues.size - shown.size) { showAll = true }
            }
            OutlinedButton(onClick = { onGoEdit(issues.firstOrNull()?.kind) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("設定・希望を編集する") }
        }
    }
    if (confirmClear && wishClearCount > 0) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("担当外の希望をまとめて消しますか？") },
            text = { Text("担当外の希望 ${wishClearCount}件 をまとめて削除します。勤務表の割当は変わりません。元に戻すで取り消せます。") },
            confirmButton = { DialogDangerButton("まとめて消す", onClick = { confirmClear = false; onClearWishes() }) },
            dismissButton = { DialogDismissButton(onClick = { confirmClear = false }, text = "消さない") },
        )
    }
}

/** 設定の見直しの一覧で最初に出す件数（重要な順に整列済み）。残りは [SettingIssuesShowAll] で開く。 */
internal const val SETTING_ISSUE_PREVIEW = 6
/** [UX監査 中5] 不足枠の候補の既定表示件数（超えたら「すべて表示」で9人目以降も選べる）。 */
internal const val GUIDED_FIX_PREVIEW = 8

@Composable
internal fun SettingIssuesShowAll(hidden: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text("すべて表示（ほか ${hidden}件）") }
}

@Composable
internal fun V6DashboardCard(v6: V6PortReport?) {
    if (v6 == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("1ヶ月の俯瞰（生指標）", style = MaterialTheme.typography.titleMedium)
            Text(
                "人員の穴・負荷の偏り・入力ミスを勤務表から直接集計します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            // §5.4 上部: 充足率(coverage%)を大数値ゲージで（必要人数のうち満たせた割合）
            v6.coveragePct?.let { pct ->
                val tint = if (pct >= 100) MaterialTheme.colorScheme.tertiary else if (pct >= 90) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                MagiScoreGauge(
                    score = pct,
                    max = 100,
                    label = "人員充足率",
                    sub = "必要人数 ${v6.demand} のうち満たせた割合",
                    accent = tint,
                )
                // [D3-full案A] 「でき具合(全体の完成度)」と「人員充足率(人員の一側面)」は別指標。
                //   役割の違いを明示し、片方を他方の内訳と誤認させない(架空分解を避ける)。
                Text(
                    "※全体の完成度は「でき具合」（ホーム）で確認。ここは人員の充足のみ。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                // [UX監査P0/U6,H7] 英字の内部指標名を画面に出さない（operator_ux.md）。hardCore/hardGuardは
                //   単一のbreakdownLabelsキーに対応しない集計値なので、内訳の意味に沿った日本語を直接あてる。
                // 必須違反はホーム等と同じ全必須（担当外シフトを含む）＝担当外はその内数として並べる。
                BigStat("必須違反", (v6.hardCore + v6.hardGuard).toString(), Modifier.weight(1f))
                BigStat("うち" + (breakdownLabels["groupViol"] ?: "groupViol"), v6.hardGuard.toString(), Modifier.weight(1f))
                BigStat("充足", v6.coveragePct?.let { "$it%" } ?: "-", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (v6.topRiskShortage > 0) "最優先: ${v6.topRiskLabel} に不足 ${v6.topRiskShortage} 枠" else "最優先: 人員不足なし",
                color = if (v6.topRiskShortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            // [3.286.0 冗長性C] 日別リスクチップ列（dayRisks）は AttentionCardsSection（日別リスト＝全件＋
            //   要確認のみトグル＋タップ修復）が上位互換のため撤去（3.195.0 で保留した次点候補の実施）。
            Spacer(Modifier.height(10.dp))
            Text(
                "${breakdownLabels["apt"] ?: "apt"}=${"%.2f".format(v6.aptPenalty)} / " +
                    "${breakdownLabels["fair"] ?: "fair"}=${"%.2f".format(v6.equPenalty)} / " +
                    "必要人数=${v6.demand} / ${breakdownLabels["covU"] ?: "covU"}=${v6.covU}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (v6.sanityWarnings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                v6.sanityWarnings.take(3).forEach {
                    Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            // [3.286.0 冗長性C] 負荷プロフィール（staffProfiles top5）は AttentionCardsSection（人別リスト）が
            //   上位互換のため撤去。固有の生指標（充足率/HARD Core/Guard/Apt/Equalize/sanity警告）のみ残す。
        }
    }
}


/**
 * [N2/⛏11] 重み表カード。weightedScore の内部重み（MirrorKeys.weights）をそのまま描画し、
 * 「できあがり度/スコア」の根拠（どの違反が何倍効くか）を上級者が逆算できるようにする。
 * 重みは最適化器と同じマップを参照＝表示と最適化器が常に一致（統一思想の延長）。プロ表示時のみ。
 */
@Composable
internal fun WeightTableCard() {
    fun fmt(w: Double): String = if (w == w.toLong().toDouble()) w.toLong().toString() else w.toString()
    val sorted = MirrorKeys.weights.entries.sortedByDescending { it.value }
    val hard = sorted.filter { it.value >= 1000.0 }
    val soft = sorted.filter { it.value < 1000.0 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // [3.396.0] 旧: 「重み表（最適化器と一致）」＋「スコアの内部重み。大きいほど…」＝
            //   初見の人は「重み？ 最適化器？」と聞き返す。見出しと**並び順そのもの**（上から重い順）が
            //   読み方を教える形にし、説明の一文は落とした。英字コード(HARD/SOFT)も作り手語彙なので外す
            //   （日本語が既に同じことを言っている＝operator_ux「英字符号を画面に出さない」）。
            // [3.532.0] タイトルは呼出元の CollapsibleSection ヘッダが担う（設定タブの静音化・二重見出し回避）。
            Text("上にあるものから先に直します。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("絶対に守る", style = MaterialTheme.typography.titleSmall)
            hard.forEach { (k, w) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(breakdownLabels[k] ?: k, modifier = Modifier.weight(1f))
                    Text("×${fmt(w)}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
                }
            }
            Text("できれば守る", style = MaterialTheme.typography.titleSmall)
            soft.forEach { (k, w) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(breakdownLabels[k] ?: k, modifier = Modifier.weight(1f))
                    Text("×${fmt(w)}", fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}


// [3.286.0 冗長性C] RiskChip は dayRisks チップ列の撤去で呼出0となったため削除。


// [3.409.7] 内訳の家族キー → 日本語ラベル（breakdownLabels）は BreakdownLabels.kt へ移した。
//   Compose に依存しない表なので、切り出して「MirrorKeys.all と過不足なく一致する」をテストで固定する。

/**
 * [分析→場所] 内訳の家族キー(low/covO/c42 等)から、その違反の「場所」と関係スタッフindexを返す。
 *  - count系(low/high/c2)   : スタッフ名「シフト」 / staff=i        (countViolations: i,k)
 *  - 被覆系(covU/covO/c41/c41s): 日付「シフト」 / staff=null         (needViolations: k,j)
 *  - セル系(c1/c3/c3n/c3m/c3mn/c42/c42s/pref/groupViol): スタッフ名 日付=実シフト / staff=i (violationCells: i,j)
 * staff!=null の項目はタップで「そのスタッフが関わる交換」を探せる。
 */
internal fun breakdownLocations(famKey: String, ui: UiState): List<Pair<String, Int?>> {
    fun nm(i: Int) = ui.staffNames.getOrNull(i) ?: "#$i"
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "$k"
    val want = "vio-$famKey"
    // [/code-review, 3.111.0/3.353.0と同根の第3キー空間] 重い族(covU8000/low90/high45等)と同じセルに
    //   軽い族(apt/c2/c41/c41s等)が重なると、単一クラスの countViolations/needViolations からは消える
    //   （内訳の件数は breakdown で正しいのに、タップ→場所一覧だけ件数より少なく見える）。*Families
    //   （全クラス保持）を先に見て、無ければ単一クラス版へフォールバック（読込直後等ui未充填時の保険）。
    fun countHits(target: String): List<String> =
        if (ui.countFamilies.isNotEmpty()) ui.countFamilies.entries.filter { target in it.value }.map { it.key }
        else ui.countViolations.entries.filter { it.value == target }.map { it.key }
    fun needHits(target: String): List<String> =
        if (ui.needFamilies.isNotEmpty()) ui.needFamilies.entries.filter { target in it.value }.map { it.key }
        else ui.needViolations.entries.filter { it.value == target }.map { it.key }
    return when (famKey) {
        "low", "high", "c2" -> countHits(want).mapNotNull {
            val p = it.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val k = p.getOrNull(1)?.toIntOrNull()
            if (i == null || k == null) null else ("${nm(i)} 「${sym(k)}」" to i)
        }
        // 適切回数(apt) は不足=vio-aptLow / 超過=vio-aptHigh の2クラスで countViolations(i,k) に入る。
        "apt" -> (countHits("vio-aptLow") + countHits("vio-aptHigh")).distinct().mapNotNull {
            val p = it.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val k = p.getOrNull(1)?.toIntOrNull()
            if (i == null || k == null) null else ("${nm(i)} 「${sym(k)}」" to i)
        }
        "covU", "covO", "c41", "c41s" -> needHits(want).mapNotNull {
            val p = it.split(","); val k = p.getOrNull(0)?.toIntOrNull(); val j = p.getOrNull(1)?.toIntOrNull()
            if (k == null || j == null) null else ("${dayMD(ui.startDate, j)} 「${sym(k)}」" to null)
        }
        // [場所表示] fair/weekly はセル単位でなく職員×シフト単位の偏り。distLocations から整形。
        // [3.345.0] weekly も職員×シフト（旧: 職員のみ）。休も1シフトとして出る。
        "weekly" -> (ui.distLocations["weekly"] ?: emptyList()).mapNotNull { e ->
            val i = e.getOrNull(0) ?: return@mapNotNull null; val k = e.getOrNull(1) ?: return@mapNotNull null
            val dev = e.getOrNull(2) ?: 0
            "${nm(i)} 「${sym(k)}」（曜日の偏り ${dev}）" to i
        }
        "fair" -> (ui.distLocations["fair"] ?: emptyList()).mapNotNull { e ->
            val i = e.getOrNull(0) ?: return@mapNotNull null; val k = e.getOrNull(1) ?: return@mapNotNull null; val dev = e.getOrNull(2) ?: 0
            "${nm(i)} 「${sym(k)}」（偏り ${dev}）" to i
        }
        else -> {
            val hits = if (ui.violationCellFamilies.isNotEmpty())
                ui.violationCellFamilies.entries.filter { want in it.value }.map { it.key }
            else ui.violationCells.entries.filter { it.value == want }.map { it.key }
            hits.mapNotNull {
                val p = it.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val j = p.getOrNull(1)?.toIntOrNull()
                if (i == null || j == null) null else {
                    val cell = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
                    ("${nm(i)} ${dayMD(ui.startDate, j)}=${if (cell >= 0) sym(cell) else "—"}" to i)
                }
            }
        }
    }
}

// [死にコード整理] BottleneckCard は 3.81.0 で AttentionCardsSection(全件＋トグル＋タップ修復)が上位互換となり
//   詳細タブから撤去済み。呼出0の composable 定義もここで撤去した（履歴は git にある）。

// [3.112.0 撤去] HeroMetricsRow（対象人数/対象期間の2タイル）はユーザー赤囲い指示で撤去。
//   読込ステータス行「読込完了: N名/N日/Nシフト」と重複する固定値で、トリアージに寄与しないため。

/** [★1/E1] 要確認一覧の1項目。個々の違反箇所を重大度マーク付きで表す（web「画面修正版」confirm ビュー移植）。 */
private data class ConfirmItem(
    val kind: Int,          // 0=不足/必須(bad) 1=過剰/調整(warn) 2=窓(c1)
    val mark: String,       // バッジ内グリフ（2文字以内）
    val title: String,      // 場所（職員名 / 日「シフト」）
    val sub: String,        // 族ラベル（人員不足・下限割れ 等）
    val staff: Int?,        // タップ修復のフォーカス職員（null=全体探索）
    val day: Int?,          // [ジャンプ] セル違反の日index（null=セルに紐付かない項目）
    val order: Int,         // 並び（kind→場所）
    // [下流→上流ディープリンク] pref違反→希望シフト登録(該当職員) / covU・covO→必要人数カレンダー(該当シフト)。
    //   null=そのカレンダーへの「設定で直す」導線を出さない。
    val wishStaff: Int? = null,
    val needShift: Int? = null,
    /**
     * [3.471.0] この項目に重なっている**生の族キー**（`MirrorKeys.all`）。`sub` は複数族を「・」で
     * 連結した表示ラベルなので突合に使えない（`AnalysisTriageCard` が族ごとに場所を数件だけ出すのに要る）。
     */
    val families: List<String> = emptyList(),
)

/**
 * [★1/E1] 各違反マップ（needViolations/countViolations/violationCells）を個々の「要確認」項目に展開。
 * BreakdownCard が族単位の集計なのに対し、こちらは spec「画面修正版」confirm ビューと同型の箇所単位リスト。
 * 表示のみ・スコアリング不変（読取専用）。
 */
private fun confirmItems(ui: UiState): List<ConfirmItem> {
    fun nm(i: Int) = ui.staffNames.getOrNull(i) ?: "#$i"
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "$k"
    val out = ArrayList<ConfirmItem>()
    // 被覆・群レンジ: needViolations "k,j" -> vio-covU/covO/c41/c41s（日×シフト）
    for ((key, cls) in ui.needViolations) {
        val p = key.split(","); val k = p.getOrNull(0)?.toIntOrNull() ?: continue; val j = p.getOrNull(1)?.toIntOrNull() ?: continue
        val fam = cls.removePrefix("vio-")
        // [④用語統一] 過剰マークは「過剰」（凡例/集計と同語）。[⑥日別ジャンプ] day=j で勤務表の該当日列へ飛べる。
        val (mark, kind) = when (fam) { "covU" -> "不足" to 0; "covO" -> "過剰" to 1; else -> "要調整" to 1 }
        out += ConfirmItem(kind, mark, "${dayMD(ui.startDate, j)}「${sym(k)}」", breakdownLabels[fam] ?: fam, null, j, kind * 100000 + j * 100 + k,
            needShift = if (fam == "covU" || fam == "covO") k else null, families = listOf(fam))
    }
    // 個人回数: countViolations "i,k" -> vio-low/high/c2/aptLow/aptHigh（職員×シフト）
    for ((key, cls) in ui.countViolations) {
        val p = key.split(","); val i = p.getOrNull(0)?.toIntOrNull() ?: continue; val k = p.getOrNull(1)?.toIntOrNull() ?: continue
        val fam = cls.removePrefix("vio-")
        // c2(個人の合計) は方向を持たない単一クラス vio-c2 → 「過」ではなく中立マーク「計」（誤って過剰と表示しない）。
        val (mark, kind) = when (fam) { "low", "aptLow" -> "不足" to 0; "c2" -> "計" to 1; else -> "過剰" to 1 }
        val labelFam = when (fam) { "aptLow", "aptHigh" -> "apt"; else -> fam }
        out += ConfirmItem(kind, mark, nm(i), "${sym(k)}・${breakdownLabels[labelFam] ?: labelFam}", i, null, kind * 100000 + 40000 + i * 100 + k,
            families = listOf(labelFam))
    }
    // セル違反: violationCells "i,j" -> vio-pref/groupViol/c3n/c3/c3m/c3mn/c1/c42/c42s（職員×日=実シフト）
    for ((key, cls) in ui.violationCells) {
        val p = key.split(","); val i = p.getOrNull(0)?.toIntOrNull() ?: continue; val j = p.getOrNull(1)?.toIntOrNull() ?: continue
        val fam = cls.removePrefix("vio-")
        val cell = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
        val cellSym = if (cell >= 0) sym(cell) else "—"
        val (mark, kind) = when (fam) {
            "c1" -> "期間" to 2   // 族名「期間の制約」の短縮（46dp の枠に収める）
            "pref", "groupViol", "c3n", "c3w", "extWish" -> "必須" to 0
            else -> "要調整" to 1
        }
        // [Set化] 同セルに重なった族は sub に全列挙（重み降順）。行数=箇所数は不変（見出し件数の意味を保つ）。
        val famsAll = (ui.violationCellFamilies[key] ?: listOf(cls)).map { it.removePrefix("vio-") }
        val sub = famsAll.joinToString("・") { breakdownLabels[it] ?: it }
        out += ConfirmItem(kind, mark, "${nm(i)} ${dayMD(ui.startDate, j)}=$cellSym", sub, i, j, kind * 100000 + 80000 + j * 100 + i,
            wishStaff = if ("pref" in famsAll) i else null, families = famsAll)
    }
    return out.sortedWith(compareBy({ it.kind }, { it.order }))
}


/** [★1] 要確認一覧の1行（spec confirmCard 同型：マークバッジ＋タイトル＋族＋メタ）。 */
@Composable
private fun ConfirmRow(
    item: ConfirmItem, onFocusStaff: (Int) -> Unit, onShowCell: (Int, Int) -> Unit, onShowDay: (Int) -> Unit,
    onFixWish: (Int, Int?) -> Unit = { _, _ -> }, onFixNeed: (Int, Int?) -> Unit = { _, _ -> },
) {
    val cs = MaterialTheme.colorScheme
    val (warnBg, warnFg) = magiWarnColors()
    val (bg, fg) = when (item.kind) {
        0 -> cs.errorContainer to cs.onErrorContainer
        1 -> warnBg to warnFg
        else -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    // [⑥日別ジャンプ] staff無し・day有り（人員/群レンジ=日×シフト）は勤務表の該当日列へ移動できる。
    val dayOnly = item.staff == null && item.day != null
    val clickable = item.staff != null || dayOnly
    var m = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    if (clickable) m = m.clickable {
        val s = item.staff; val d = item.day
        when { s != null && d != null -> onShowCell(s, d); s != null -> onFocusStaff(s); d != null -> onShowDay(d) }
    }.semantics { contentDescription = "${item.title} ${item.sub}・" + (if (dayOnly) "タップで勤務表の該当日へ" else "タップで直し方を探す") }
    val ws = item.wishStaff
    val ns = item.needShift
    Column(Modifier.fillMaxWidth()) {
    Surface(color = cs.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = m) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = bg, shape = MaterialTheme.shapes.small) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    // 3 字（要調整）は 1 段小さい字で 46dp の枠に収める。
                    Text(item.mark, color = fg, style = if (item.mark.length >= 3) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.sub, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // [3.483.0 A-3] 行の押下で起きることをそのまま言う。
            if (clickable) Text(if (dayOnly) "勤務表→" else if (item.day != null) "勤務を開く" else "直し方を探す", style = MaterialTheme.typography.labelMedium,
                color = ensureReadable(cs.surfaceVariant, MagiAccent.blue))
        }
    }
    // [下流→上流ディープリンク] 設定の節の名を本文の下に全幅で（行本体の押下とは別の操作）。
    //   なおし方の画面は日を指定して開けないので、人員不足の行は「勤務表でこの日を見る」と言う。
    if (ns != null && dayOnly) TextButton(onClick = { onShowDay(item.day!!) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("勤務表でこの日を見る") }
    when {
        ws != null -> TextButton(onClick = { onFixWish(ws, item.day) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(issueSettingsLabel(com.magi.app.v6.IssueKind.WISH)) }
        ns != null -> TextButton(onClick = { onFixNeed(ns, item.day) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(issueSettingsLabel(com.magi.app.v6.IssueKind.DEMAND)) }
    }
    }
}




/**
 * [3.471.0/分析タブ再構築] 分析タブを**スクロール最小の1画面**にまとめたカード。
 *
 * 旧 3枚統合カード（3.459.0）は「一般/プロ」「一覧/日別・人別/内訳」「全部/不足/過剰/窓」「族チップ6種」と
 * **4層の切り替え**を積み、そのうえで 0 件の項目まで並べていたため縦が極端に伸びていた。
 * ここでは切り替えを全部やめ、上から下へ流れる1本にする。
 *
 * ## 分類は族でなく「データを直さない限り消えるか」（根拠は [analysisTriage] に集約）
 * 「連勤・並びは自動で直るので手動修正は不要」と族の名前で断定すると、3.263.0 / 3.322.0 / 3.344.0 で
 * 直してきた楽観バイアスが戻る（c1・c3n はデータ次第で最適化では消せない）。上段へ上げるのは
 * **必須違反**と、**診断（settingIssues / forbiddenDiag / c1Plateau / coverageDiag）が構造的に残ると
 * 判定したもの**だけ。中段は「エンジンが挑戦する項目」であって「直さなくてよい」ではない旨を注記する。
 *
 * ## 同種を畳む
 * 設定の破綻は「古泉・Dﾃ」「山本・Dﾃ」…と1人1行で伸びていたのを種類ごとの1行へ集約する。
 * ただし**必須違反だけは場所を最大3件そのまま出す**（どのセルかが分からないと直しに行けない）。
 *
 * 表示のみ・読み取り専用＝スコアリング/エンジンは完全に不変。
 */
@Composable
internal fun AnalysisTriageCard(
    ui: UiState,
    onFocusStaff: (Int) -> Unit,
    // [UX監査#4] 引数は設定の見直しの種類。呼出側が編集タブの対象節へ変換する。
    onGoEdit: (com.magi.app.v6.IssueKind?) -> Unit,
    onShowCell: (Int, Int) -> Unit,
    onShowDay: (Int) -> Unit,
    onFixWish: (Int, Int?) -> Unit,   // 職員と、行の日（希望の登録をその日を選んだ状態で開く）
    onFixNeed: (Int, Int?) -> Unit,   // シフトと、行の日
    summary: AnalysisSummary,
    onPrimary: (AnalysisPrimary) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (warnBg, warnFg) = magiWarnColors()
    val t = remember(ui.breakdown, ui.settingIssues, ui.coverageDiag, ui.forbiddenDiag, ui.c1Plateau, ui.engineRan) { analysisTriage(ui) }
    val items = remember(ui.violationCells, ui.violationCellFamilies, ui.needViolations, ui.countViolations, ui.schedule, ui.staffNames, ui.shiftSymbols, ui.startDate) { confirmItems(ui) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("分析", style = MaterialTheme.typography.titleMedium)
            // 状態の段: 一文・3 点まで・主ボタン 1 つ（[analysisSummary]）。
            Text(summary.sentence, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            summary.points.forEach { Text("・$it", style = MaterialTheme.typography.bodyMedium) }
            summary.primaryLabel?.let { label ->
                Button(onClick = { onPrimary(summary.primary) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(label) }
            }

            if (!t.hasAnything && ui.schedule.isNotEmpty() && !ui.running) {
                Text("確認事項はありません（すべての条件を満たしています）。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.tertiary)
            }

            // 必須違反（配る前に直す）と、設定を変えないと消えない項目を分ける。
            val (hardRows, otherBlockers) = t.blockers.partition { it.family != null && it.family in MirrorKeys.hard }
            if (hardRows.isNotEmpty()) Text(TRIAGE_HARD_HEADING, style = MaterialTheme.typography.titleSmall, color = cs.error)
            hardRows.forEach { row ->
                TriageRowLine(row, cs.errorContainer, cs.onErrorContainer)
                val fam = row.family!!
                // 必須違反は「どのセルか」が分からないと直しに行けないので場所を数件だけ出す。
                val locs = items.filter { fam in it.families }
                locs.take(3).forEach { ConfirmRow(it, onFocusStaff, onShowCell, onShowDay, onFixWish, onFixNeed) }
                if (locs.size > 3) Text("ほか ${locs.size - 3}か所", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (otherBlockers.isNotEmpty() || t.issues.isNotEmpty()) {
                Text(TRIAGE_SETTINGS_HEADING, style = MaterialTheme.typography.titleSmall, color = cs.error)
                otherBlockers.forEach { row ->
                    TriageRowLine(row, cs.errorContainer, cs.onErrorContainer)
                }
                t.issues.forEach { row ->
                    Surface(color = warnBg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${row.label} ${row.count}件", style = MaterialTheme.typography.bodyMedium, color = warnFg, fontWeight = FontWeight.SemiBold)
                            if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.labelMedium, color = warnFg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onGoEdit(row.kind) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(issueSettingsLabel(row.kind)) }
                        }
                    }
                }
            }

            if (t.searching.isNotEmpty()) {
                Text(
                    if (t.computed) "作成後に残っている項目" else "作成で減らせる見込みの項目（まだ作成していません）",
                    style = MaterialTheme.typography.titleSmall,
                )
                Surface(color = cs.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        t.searching.forEach { row ->
                            Row {
                                Text("・${row.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${row.count}${row.unit}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            }
                        }
                        Text("※${t.searchNote}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }

            // 0件の族は畳む＝旧実装は「人員0」「希望0」…が画面の半分を占めていた。
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showSummary = !showSummary },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(conditionStatusTitle(t.okFamilies.size, t.busyFamilies.size),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(if (showSummary) "閉じる ∧" else "全${t.okFamilies.size + t.busyFamilies.size}項目を展開 ∨",
                    style = MaterialTheme.typography.labelMedium, color = cs.primary)
            }
            if (showSummary) {
                if (t.okFamilies.isNotEmpty()) Text("✔ 守れている（${t.okFamilies.size}項目）: " + t.okFamilies.joinToString(" / "),
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                if (t.busyFamilies.isNotEmpty()) Text("⚠ 残っている（${t.busyFamilies.size}項目）: " + t.busyFamilies.joinToString(" / "),
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }

        }
    }
}

/** 上段の1行（族の見出し＋件数＋診断が語った理由）。理由が空なら何も主張しない。 */
@Composable
private fun TriageRowLine(row: TriageRow, bg: Color, fg: Color) {
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${row.label} ${row.count}${row.unit}",
                style = MaterialTheme.typography.bodyMedium, color = fg, fontWeight = FontWeight.SemiBold)
            if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}




@Composable
internal fun BreakdownGroup(title: String, keys: List<String>, severity: Int, ui: UiState, labels: Map<String, String>, expanded: String?, onTap: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        keys.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key -> SeverityChip(labels[key] ?: key, ui.breakdown[key] ?: 0, severity, key, expanded == key, onTap, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}


@Composable
internal fun SeverityChip(label: String, count: Int, severity: Int, famKey: String, expanded: Boolean, onTap: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val active = count > 0
    val container: Color; val onContainer: Color
    when {
        !active -> { container = cs.surfaceVariant; onContainer = cs.onSurfaceVariant }
        severity >= 2 -> { container = cs.errorContainer; onContainer = cs.onErrorContainer }
        severity == 1 -> { container = cs.secondaryContainer; onContainer = cs.onSecondaryContainer }
        else -> { container = cs.primaryContainer; onContainer = cs.onPrimaryContainer }
    }
    val shape = MaterialTheme.shapes.small
    var m = modifier.heightIn(min = 48.dp)
    if (expanded) m = m.border(2.dp, onContainer.copy(alpha = 0.7f), shape)
    if (active) m = m.clickable { onTap(famKey) }
        .semantics { contentDescription = "$label $count 件" + (if (expanded) "・展開中" else "・タップで場所を表示") }
    Surface(color = container, shape = shape, modifier = m) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = onContainer,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = onContainer)
            if (active) {
                Spacer(Modifier.width(2.dp))
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = onContainer, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // [B7] 5桁等の大きな値でも2行に折り返して崩れないよう1行固定＋省略（hardCore/Guard の大値は稀）。
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}


/** 改善手の種類 → (チップ文言, 色)。 */
internal fun fixKindTag(k: com.magi.app.v6.FixKind): Pair<String, androidx.compose.ui.graphics.Color> = when (k) {
    com.magi.app.v6.FixKind.CHANGE -> "変更" to MagiAccent.green
    com.magi.app.v6.FixKind.CHANGE_MULTI -> "複数変更" to MagiAccent.green
    com.magi.app.v6.FixKind.SWAP -> "交換" to MagiAccent.blue
    com.magi.app.v6.FixKind.SWAP_XDAY -> "別日交換" to MagiAccent.blue
    com.magi.app.v6.FixKind.SWAP_MULTI -> "3人交換" to MagiAccent.purple
    com.magi.app.v6.FixKind.CHAIN -> "連鎖" to MagiAccent.red
    com.magi.app.v6.FixKind.WINDOW -> "再最適化" to MagiAccent.orange
    com.magi.app.v6.FixKind.EJECT -> "玉突き" to MagiAccent.red
}

@Composable
internal fun FixSuggestionCard(ui: UiState, onSearch: () -> Unit, onApply: (com.magi.app.v6.FixSuggestion) -> Unit, proMode: Boolean = false,
                               onConsult: (com.magi.app.v6.FixSuggestion) -> Unit = {}, onCancelConsult: (ConsultItem) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    // [3.483.0 A-2] ホームの「AIの解決提案」は必須違反が残っていれば自動で探すのに、ここは「探す」を押すまで空
    //   ＝同じエンジンで挙動が違った。同じ条件（結果あり・必須>0・未探索）で自動探索を共有する。
    //   ソフトだけの盤面は従来どおり手動（探索コストに見合う候補が少ない）。
    LaunchedEffect(ui.schedule, ui.bestHard) {
        if (ui.hasResult && ui.bestHard > 0L && !ui.running && !ui.fixSearching &&
            ui.fixSuggestions.isEmpty() && !ui.fixSearched && ui.fixFocusName.isBlank()) onSearch()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = "改善の提案" + if (ui.fixFocusName.isNotBlank()) "：${ui.fixFocusName} 関連" else ""
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (ui.fixSearching) {
                    Text("探しています…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else {
                    TextButton(onClick = onSearch) { Text(if (ui.fixSuggestions.isEmpty()) "探す" else "全体でもう一度探す") }
                }
            }
            if (!proMode) {
                Text("違反を減らす1手を効果順に提案します。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            when {
                ui.fixSearching -> Text("候補を探しています。少しお待ちください。", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                ui.fixSuggestions.isEmpty() -> Text(NO_FIX_SUGGESTION_TEXT,
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                else -> ui.fixSuggestions.forEach { s ->
                    val (tag, tagColor) = fixKindTag(s.kind)
                    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val (hardLine, caution) = fixImpactLines(s)
                            Text(fixBenefitLine(s) ?: hardLine, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MagiTagChip(text = tag, color = tagColor)
                                Text(fixDisplayLabel(s.label), style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f))
                            }
                            caution?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer) }
                            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                // [3.645.0/仕様 5.3] 当てずに相談してから決める＝手と効果を相談中の一覧へ。
                                ConsultButton(isConsulted(ui.consults, consultFix(s)), onUndo = { consultMatch(ui.consults, consultFix(s))?.let { onCancelConsult(it) } }) { onConsult(s) }
                                // 押しても確認の画面が開くだけ＝見るだけの外枠ボタン（塗りの主は画面に 1 つ、INV-09）。
                                OutlinedButton(onClick = { onApply(s) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(REVIEW_CHANGE_BUTTON)
                                }
                            }
                        }
                    }
                }
            }
            if (!ui.fixSearching && ui.fixSuggestions.isNotEmpty()) Text(APPLY_UNDO_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * [3.480.0 ホームAIリデザイン] 「他の案」を単純なボタン列からセグメントコントロール（タブ切替）へ。
 * grilling決定#4のとおり `ui.alternatives` は要約文字列のみ（案ごとの盤面フルコピーはキャッシュされて
 * いない）ため、タブ切替は「上の表示だけ差し替えるプレビュー」ではなく既存の `applyAlternative`
 * （Undo付きの完全コミット）をそのまま呼ぶ＝**タップ＝即時反映**。
 */
@Composable
internal fun AlternativesCard(ui: UiState, onApply: (Int) -> Unit) {
    if (ui.alternatives.isEmpty()) return
    // 適用中の案は VM が持つ（元に戻すで一覧ごと戻るため）。
    val selected = ui.alternativeApplied
    val allWorse = alternativesAllWorse(ui.alternativesWorse, ui.alternatives.size)
    var open by remember(ui.alternatives) { mutableStateOf(!allWorse) }
    var confirm by remember(ui.alternatives) { mutableStateOf<Int?>(null) }
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (open) "閉じる" else "開く") { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("他の案（${ui.alternatives.size}）", style = MaterialTheme.typography.titleMedium)
                    if (allWorse) Text(ALTERNATIVES_ALL_WORSE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Text(if (open) "閉じる" else "開く", color = cs.primary, style = MaterialTheme.typography.bodyMedium)
            }
            if (open) {
                Text("案を押すと、反映する前に確かめます（反映後も「元に戻す」で取り消せます）。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                MagiSegmentedControl(
                    options = ui.alternatives.indices.map { "案${it + 1}" },
                    selected = selected,
                    onSelect = { i -> if (i != selected) confirm = i },
                )
                ui.alternatives.forEachIndexed { i, s ->
                    Text(s, style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == selected) cs.onSurface else cs.onSurfaceVariant)
                }
            }
        }
    }
    confirm?.let { i ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("案${i + 1}を反映しますか？") },
            text = { Text(ui.alternatives.getOrNull(i).orEmpty() + if (ui.alternativesWorse.getOrNull(i) == true) "\n" + ALTERNATIVES_ALL_WORSE + "（元に戻すで取り消せます）" else "") },
            confirmButton = { DialogConfirmButton("反映する", onClick = { confirm = null; onApply(i) }) },
            dismissButton = { DialogDismissButton(onClick = { confirm = null }, text = "反映しない") },
        )
    }
}


/** [3.648.0] 希望まわりの 2 操作（反映／一括）を 1 枚に。旧: 見出し＋説明＋ボタンのカードと別の 48dp ボタンの 2 段（約 150dp）→ 約 90dp。 */
@Composable
internal fun WishActionsRow(ui: UiState, onApply: () -> Unit, onBulk: () -> Unit) {
    if (!ui.loaded) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("希望シフト（反映は登録済みの希望を勤務表へ上書きします。元に戻せます）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onApply, enabled = !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("希望を反映する") }
                OutlinedButton(onClick = onBulk, enabled = !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("希望の一括操作") }
            }
        }
    }
}

@Composable
private fun PreDistRowView(r: PreDistRow) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Text(r.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
        Text((when (r.ok) { true -> "✓ "; false -> "! "; null -> "" }) + r.value,
            color = if (r.ok == false) cs.error else cs.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 配布前の確認（仕様 §10）。上に必須違反・相談・保存、要調整と注記は「詳しく」に畳む。閉じる手段は「戻る」だけ（3.704.0）。 */
@Composable
internal fun PreDistributionDialog(rows: List<PreDistRow>, periodLine: String, onExport: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var details by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(PRE_DIST_TITLE)
                Text(periodLine, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preDistributionTop(rows).forEach { PreDistRowView(it) }
                Text(PRE_DIST_EDGE_FOOT, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (details) "詳しくを閉じる" else "詳しく") }
                if (details) {
                    preDistributionDetails(rows).forEach { PreDistRowView(it) }
                    Text(MONTH_EDGE_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text(PRE_DIST_NOTE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDismiss(); onExport() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("配ると決めて書き出す") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("戻る") }
            }
        },
    )
}
