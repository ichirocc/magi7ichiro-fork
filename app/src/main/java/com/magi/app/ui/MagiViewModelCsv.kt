package com.magi.app.ui

import androidx.lifecycle.viewModelScope
import com.magi.app.v6.csvAmbiguousText
import com.magi.app.v6.CsvPartialImport
import com.magi.app.v6.ScheduleRunResult
import com.magi.app.v6.ScheduleCsvBridge
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.copy2D
import com.magi.app.model.MagiState
import com.magi.app.model.MojibakeRepair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// [MagiViewModel] の CSV 取込（勤務表の重ね合わせ・部分取込と反映前の確認）。本体から extension 関数として抽出（全面改修 P6e）。
//   盤面の差し替えは本体の commitCsvBoard、失敗時の巻き戻しは本体の rollbackBoardCommit（書き込みは本体だけ）。

fun MagiViewModel.importCsv(rawText: String) {
    // [3.404.0] 旧: 入口ガードが無く、`job = viewModelScope.launch` が走行中の最適化の参照を
    //   **キャンセルせずに上書き**していた＝その最適化は「やめる」で止められないゾンビになる
    //   （3.271.0 が generateSmartInitial で直したのと同型の取り残し）。
    if (runBlockedByInFlight("CSV取込")) return
    val st = state ?: return
    val sched = currentSchedule ?: return
    runCsvImport(st, sched, partial = false) { parseCsvOrAsk(st, sched, rawText) }
}

/** 「この部分だけ取り込む」。指紋が作成時と同じときだけ、通常の取込と同じ適用（[runCsvImport]）へ渡す。 */
fun MagiViewModel.confirmCsvPartialImport() {
    val pending = csvPartialPending ?: run { dropCsvPartial(); return }
    dropCsvPartial()
    if (runBlockedByInFlight("CSV取込")) return
    val st = state; val sched = currentSchedule
    val resolution = if (st != null && sched != null) CsvPartialImport.resolve(pending, stateKey(st), boardKey(sched))
        else CsvPartialImport.Resolution.Stale
    if (resolution is CsvPartialImport.Resolution.Apply && st != null && sched != null)
        runCsvImport(st, sched, partial = true) { resolution.pending.ask.result }
    else notify(CsvPartialImport.STALE, "W")
}

fun MagiViewModel.cancelCsvPartialImport() {
    val had = csvPartialPending != null
    dropCsvPartial()
    if (had) notify(CsvPartialImport.CANCELLED)
}

internal fun MagiViewModel.dropCsvPartial() {
    csvPartialPending = null
    if (_ui.value.csvPartialPrompt != null) _ui.update { it.copy(csvPartialPrompt = null) }
}


internal fun MagiViewModel.dropCsvImport() {
    csvImportPending = null
    if (_ui.value.csvImportPrompt != null) _ui.update { it.copy(csvImportPrompt = null) }
}

/** 確認の「取り込む」。指紋が作成時と同じときだけ、確認済みとして同じ結果を当てる。 */
fun MagiViewModel.confirmCsvImport() {
    val pending = csvImportPending ?: run { dropCsvImport(); return }
    dropCsvImport()
    if (runBlockedByInFlight("CSV取込")) return
    val st = state; val sched = currentSchedule
    if (st == null || sched == null || stateKey(st) != pending.stateKey || boardKey(sched) != pending.boardKey) { notify(CsvPartialImport.STALE, "W"); return }
    runCsvImport(st, sched, partial = false, confirmed = true) { pending.result }
}

fun MagiViewModel.cancelCsvImport() {
    val had = csvImportPending != null
    dropCsvImport()
    if (had) notify(CsvPartialImport.CANCELLED)
}


/** 適用してよい結果を返す。引用符が閉じていないときは確認待ち／断りを画面に出して null。 */
internal suspend fun MagiViewModel.parseCsvOrAsk(st: MagiState, sched: Array<IntArray>, rawText: String): ScheduleRunResult? {
    val text = MojibakeRepair.repair(rawText)
    // [3.282.0] JSON 側(loadAsync)と同じ是正: BOM 除去だけの健全な CSV で誤警告しない。
    if (MojibakeRepair.wasDecoded(rawText, text)) logOp("W", "文字化け（二重エンコード）を自動修復してCSVを取り込みました。元のファイル自体は修復されません")
    when (val v = withContext(Dispatchers.Default) { CsvPartialImport.judge(text, st, sched) }) {
        is CsvPartialImport.Verdict.Ask -> {
            csvPartialPending = CsvPartialImport.Pending(v, stateKey(st), boardKey(sched))
            _ui.update { it.copy(running = false, messageIsError = false, message = null, csvPartialPrompt = v.prompt) }
            logOp("I", "CSV取込 確認待ち: 引用符が閉じていません（${v.endLine}行目まで ${v.matched}名分が読めました）")
            return null
        }
        CsvPartialImport.Verdict.NothingReadable -> {
            _ui.update { it.copy(messageIsError = true, running = false, message = "CSVを取り込めませんでした。${CsvPartialImport.NOTHING_READABLE}。引用符（\"）の閉じ忘れを直してから、もう一度取り込んでください") }
            logOp("W", "CSV取込 失敗: 引用符が閉じていなくて読めた職員の行がないため取込を中止しました")
            return null
        }
        CsvPartialImport.Verdict.WellFormed -> Unit
    }
    return withContext(Dispatchers.Default) { ScheduleCsvBridge.parse(text, st, sched) }
}

/** 取込の適用本体。[produce] が null なら適用しない。[partial]＝確認済みの部分取込（結果メッセージに注意を残す）。 */
internal fun MagiViewModel.runCsvImport(st: MagiState, sched: Array<IntArray>, partial: Boolean, confirmed: Boolean = false, produce: suspend () -> ScheduleRunResult?) {
    _ui.update { it.copy(messageIsError = false, running = true, message = "CSV取込中…") }
    val boardToken = beginBoardJob(MagiPhase.Importing)
    var pushedUndo = false
    var ui0 = _ui.value; var result0 = resultSchedule
    job = viewModelScope.launch {
        try {
            val res = produce() ?: return@launch
            // 取込失敗の明示: 氏名が1件も一致しなければ適用せず、オペレーターに原因を表示する。
            if (res.matched == 0) {
                val why = if (res.ambiguousNames.isNotEmpty())
                    "CSVを取り込めませんでした。${csvAmbiguousText(res.ambiguousNames)}。編集タブ＞職員管理とCSVの両方で名前を区別してから、もう一度取り込んでください"
                else
                    "CSVを取り込めませんでした。一致する職員名がありません。CSVの1列目の氏名と列の並び（氏名, 1日目, 2日目, …）を今のデータに合わせてから、もう一度取り込んでください"
                _ui.update { it.copy(messageIsError = true, running = false, message = why) }
                logOp("W", "CSV取込 失敗: 職員名が0件一致のため取込を中止しました（氏名/列レイアウトを確認）")
                return@launch
            }
            // 反映の前に件数と注意を見せる（部分取込は引用符の確認で既に 1 度聞いた＝重ねて聞かない）。
            if (!partial && !confirmed) {
                val hardNow = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st, sched.copy2D()).hard }
                csvImportPending = MagiViewModel.CsvImportPending(res, stateKey(st), boardKey(sched))
                _ui.update { it.copy(running = false, messageIsError = false, message = null, csvImportPrompt = csvImportPreviewLines(res, st.staff.size, sched, hardNow)) }
                logOp("I", "CSV取込 確認待ち: ${res.matched}/${st.staff.size}名一致 必須=${res.report.hard}")
                return@launch
            }
            ui0 = _ui.value; result0 = resultSchedule
            pushUndo("CSV取込")
            pushedUndo = true
            val pinsMoved = commitCsvBoard(st, sched, res)
            val pinNote = if (pinsMoved > 0) "｜手動固定 ${pinsMoved} 件を取り込んだ値に合わせました" else ""
            val total = st.staff.size
            // [3.410.0/I-01] シフト一覧に無い記号は取り込めない。旧: 黙って読み飛ばしていたため、
            //   誤字や凡例漏れが「休のまま」「元のまま」として静かに混入した。件数と記号を必ず出す。
            // [3.413.0/I-08] 引用符が閉じないCSVは残りの行が丸ごと消える＝「氏名不一致でスキップ」と
            //   区別が付かず部分的な成功に見える。必ず名指しする。
            val quoteWarn = if (partial) CsvPartialImport.APPLIED_WARNING else ""
            val unk = if (res.unknownCells > 0)
                "｜読めない記号 ${res.unknownCells}セル(${res.unknownSymbols.joinToString("・")})は取り込めませんでした"
            else ""
            // [3.592.0] 実日付ヘッダが今の対象期間とズレたまま列位置で取り込んだ場合の警告。
            val dateWarn = if (res.headerDateMismatches > 0)
                "｜⚠ CSVヘッダの日付が今の期間と${res.headerDateMismatches}列ズレています（列の位置で取り込みました）" else ""
            val dupWarn = (if (res.ambiguousNames.isNotEmpty()) "｜⚠ ${csvAmbiguousText(res.ambiguousNames)}" else "") +
                (if (res.duplicateRowNames.isNotEmpty()) "｜⚠ 同じ職員の行が複数あり、後の行で上書きしました: ${res.duplicateRowNames.joinToString("・")}" else "")
            val msg = if (res.matched in 1 until total)
                "CSV取込完了: ${res.matched}/${total}名を更新（${total - res.matched}名は氏名不一致でスキップ）｜必須=${res.report.hard} 合計=${res.report.total}$unk$quoteWarn$dateWarn$dupWarn$pinNote"
            else
                "CSV取込完了: ${res.matched}名を更新｜必須=${res.report.hard} 合計=${res.report.total}$unk$quoteWarn$dateWarn$dupWarn$pinNote"
            pushReport(state ?: st, res.schedule, res.report) { it.copy(
                messageIsError = res.unknownCells > 0 || partial || res.headerDateMismatches > 0 || dupWarn.isNotEmpty(),
                running = false,
                hasResult = true,
                relaxedBoard = false, engineRan = false, ranBeforeEdit = false, keptPrevious = false,   // [3.475.0] CSV取込は手操作扱い
                message = msg,
            ) }
            if (res.matched in 1 until total) {
                logOp("W", "CSV取込 一部のみ反映: ${res.matched}/${total}名一致（${total - res.matched}名は氏名不一致）")
            }
            if (partial) logOp("W", "CSV取込 引用符が閉じていないため、読めた部分だけを取り込みました")
            if (res.unknownCells > 0) {
                logOp("W", "CSV取込 読めない記号 ${res.unknownCells}セル: ${res.unknownSymbols.joinToString("・")}（シフト一覧に無い記号）")
            }
            if (dupWarn.isNotEmpty()) logOp("W", "CSV取込 ${dupWarn.removePrefix("｜⚠ ").replace("｜⚠ ", " / ")}")
            logOp("I", "CSV取込 完了 ${res.matched}名一致 必須=${res.report.hard} 合計=${res.report.total}")
        } catch (e: CancellationException) {
            // [3.592.0] pushUndo済み(=盤面を既に書き換え済み)なら取込前へロールバックする。旧: 診断
            //   (pushReport)の中止・例外を捕まえても内部盤面・自動保存は書き換えたままだった。
            if (pushedUndo) rollbackBoardCommit(st, sched, result0, ui0)
            _ui.update { it.copy(messageIsError = false, running = false, message = "CSV取込を中止しました") }   // [3.404.0]
            throw e
        } catch (e: Throwable) {
            if (pushedUndo) rollbackBoardCommit(st, sched, result0, ui0)
            _ui.update { it.copy(running = false, message = "CSVを取り込めませんでした（${failureWords(e, FailureKind.LOAD)}）。ファイルの中身を確認して、もう一度お試しください", messageIsError = true) }
        } finally {
            endBoardJob(boardToken)
        }
    }
}
