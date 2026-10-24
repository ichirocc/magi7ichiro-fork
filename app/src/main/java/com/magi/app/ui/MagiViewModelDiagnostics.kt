package com.magi.app.ui

import androidx.lifecycle.viewModelScope
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.V6PortAnalyzer
import com.magi.app.v6.V6SanityPort
import com.magi.app.model.MagiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// [MagiViewModel] の診断（重い解析の並列実行・結果の反映・ログの圧縮・人員過剰の深追い）。本体から extension 関数として抽出（全面改修 P6f）。
//   診断の全文の退避は本体の commitDiag、UiState の組み立ては本体の makeUi（書き込みは本体だけ）。

/**
 * 診断ログのスパム抑制。RSI/ALNS の各ラウンド・各リスタート・EarlyChain などで同種の行が大量に
 * 出るため、(1) 連続する重複行を「×N」に畳み、(2) それでも上限を超える場合は頭7割＋尾3割に圧縮する。
 * 全文が必要な場合は「ログ出力（テキスト/JSON）」で取得する想定。
 */
internal fun MagiViewModel.compressDiagLogs(lines: List<String>, cap: Int = 200): List<String> {
    if (lines.size <= 1) return lines
    val collapsed = ArrayList<String>(lines.size)
    var i = 0
    while (i < lines.size) {
        var j = i + 1
        while (j < lines.size && lines[j] == lines[i]) j++
        val n = j - i
        collapsed.add(if (n > 1) "${lines[i]}  ×$n" else lines[i])
        i = j
    }
    if (collapsed.size <= cap) return collapsed
    val head = cap * 7 / 10
    val tail = cap - head
    val out = ArrayList<String>(cap + 1)
    out.addAll(collapsed.subList(0, head))
    out.add("… 中略 ${collapsed.size - head - tail} 行省略（全文は「ログ出力」で取得） …")
    out.addAll(collapsed.subList(collapsed.size - tail, collapsed.size))
    return out
}

/**
 * makeUi が必要とする4つの重い解析を Dispatchers.Default 上で「並列」に実行する。
 * 4パス（analyze / build / diagnoseCoverage / buildViolationDebug）は同じ不変入力にのみ依存し
 * 相互参照しない純関数なので、別コアで同時実行でき、壁時計時間が sum(パス) → max(パス) に短縮される
 * （最重量は全制約走査の buildViolationDebug）。coroutineScope により、いずれかの失敗・呼び出し元の
 * キャンセルは兄弟 async へ確実に伝播する（構造化並行性）。
 */
internal suspend fun MagiViewModel.analyzeParallel(
    st: MagiState,
    schedule: Array<IntArray>,
    report: ViolationReport,
): MagiViewModel.Analysis = coroutineScope {
    val v6D       = async(Dispatchers.Default) { V6PortAnalyzer.analyze(st, schedule, report) }
    val sanityD   = async(Dispatchers.Default) { V6SanityPort.build(st, schedule) }
    // 人員不足(covU)または人員過剰(covO)が残る場合のみ原因診断（どの日/シフトが「充足不可」か
    // 「未到達」か／過剰がなぜ動かせないか）を算出しログに残す。
    // [3.515.1] covOの深追い(FixSuggester)はここでは行わない＝pushReportが完了後に別ジョブで行う
    // （3.515.0のregression: ここに混ぜるとedit-lock解除と経過時間表示が深追い終了まで遅れていた）。
    val coverageD = async(Dispatchers.Default) {
        V6PortAnalyzer.diagnoseCoverage(st, schedule, report).takeIf { it.hasShortage || it.hasSurplus }
    }
    // [3.280.0] 禁止連続(c3n)が残る場合のみ「なぜ崩せないか」診断（CoverageDiag の c3n 版）。
    val forbiddenD = async(Dispatchers.Default) {
        if ((report.breakdown["c3n"] ?: 0) > 0)
            V6PortAnalyzer.diagnoseForbiddenRuns(st, schedule).takeIf { it.hasRuns }
        else null
    }
    // [デバッグ] 制約違反を家族ごとに「場所＋実値(必要/現状, 回数/下限上限, 誰/何日/シフト)」で出力。
    val vioDebugD = async(Dispatchers.Default) { V6SanityPort.buildViolationDebug(st, schedule, report) }

    // v6Logs は sanity/coverageDiag/forbiddenDiag に依存 → 依存先だけ先に await（依存グラフを尊重）
    val sanity = sanityD.await()
    val coverageDiag = coverageD.await()
    val forbiddenDiag = forbiddenD.await()
    val v6Logs = listOf("[I] LoadDataBit: ${sanity.loadDataBitSummary}") + sanity.warns.map { "[W] SanityCheck: $it" } + sanity.notes.map { "[I] V6Port: $it" } + sanity.duplicateSeqConstraints.take(4).map { "[W] DuplicateSeq: $it" } + sanity.guidance.take(12).map { "${if (it.neutral) "[I] 設定の案内" else "[W] 設定ミス"}: ${it.where} — ${it.problem} → ${it.fix}" } +
        // 12 件で切ると主因が無言で落ちる（実機ログ: 13 件目の「上限 0 に希望 12 件」が high の主因だった）＝残りは件数と見出しだけ出す。
        (if (sanity.guidance.size > 12) listOf("[I] 設定の案内: ほか ${sanity.guidance.size - 12} 件（${sanity.guidance.drop(12).joinToString("・") { it.where }}）＝全件はホームの設定の見直し") else emptyList()) + (coverageDiag?.logLines() ?: emptyList()) + (forbiddenDiag?.logLines() ?: emptyList())
    val mappedDiag = report.logs.map { "[${it.level}] ${it.tag}: ${it.message}" }
    MagiViewModel.Analysis(
        v6 = v6D.await(),
        sanity = sanity,
        coverageDiag = coverageDiag,
        forbiddenDiag = forbiddenDiag,
        v6Logs = v6Logs,
        rawDiagLogs = v6Logs + mappedDiag + vioDebugD.await(),   // 出力用の全文（非圧縮）。表示は圧縮版を使う。
    )
}

/**
 * 重い解析(analyzeParallel)を Default で並列実行し、その結果を StateFlow へ反映する共通経路。
 * 共有可変(rawDiagLogs)の書き込みと _ui.update はメインスレッドで行い、背景スレッドからは書かない
 * （＝レース不能・単一ライタ）。全 makeUi 呼び出しをこの1経路へ集約する。
 * @param nonCancellable 停止(keep-best)経路から呼ぶ場合 true＝スコープキャンセル後も解析を完了させる。
 */

/** 個人上限0のセル（希望固定を除く）を外した盤面の採点。外すセルが無ければ null（呼出側は生の盤面の採点を使う）。 */
internal fun MagiViewModel.capZeroClearedReport(st: MagiState, sched: Array<IntArray>): ViolationReport? {
    val (cleared, n) = com.magi.app.v6.HardRepairCore.clearCappedCells(st, sched)
    return if (n > 0) UnifiedViolationChecker.check(st, cleared) else null
}
internal suspend fun MagiViewModel.pushReport(
    st: MagiState,
    schedule: Array<IntArray>,
    report: ViolationReport,
    nonCancellable: Boolean = false,
    /** [3.379.0] エンジン実行の結果を押すときだけ非 null（"最適化" 等）。診断を退避する印。 */
    runLabel: String? = null,
    transform: (UiState) -> UiState = { it },
) {
    val analysis =
        if (nonCancellable) withContext(NonCancellable) { analyzeParallel(st, schedule, report) }
        else analyzeParallel(st, schedule, report)
    commitDiag(analysis.rawDiagLogs, runLabel)
    // 印と一文の写しは重いので main の CAS の外（Default）で 1 回だけ作る。CAS の間に別の更新が入って写しが
    //   古くなったら付けない（画面は同じ式で数え直す displayHardText 等へ戻る）。
    val base0 = _ui.value
    val snapResult = withContext(if (nonCancellable) NonCancellable + Dispatchers.Default else Dispatchers.Default) {
        runCatching { buildDisplaySnapshot(st, makeUi(st, schedule, report, analysis, transform(base0))) }
    }
    snapResult.exceptionOrNull()?.let { if (it is CancellationException) throw it; logOp("W", "表示の写しを作れませんでした: ${it.javaClass.simpleName}: ${it.message}") }
    val snap = snapResult.getOrNull()
    _ui.update { base -> makeUi(st, schedule, report, analysis, transform(base)).let { u ->
        u.copy(display = snap?.takeIf { d -> d.freshFor(u) }, draftStage = if (runLabel != null) runLabel == "下書きづくり" else base.draftStage) } }
    resolvePendingGuidedFix(st, schedule, report, analysis.coverageDiag)
    deepCovODiagFollowUp(st, schedule, report, runLabel)
}

/** [3.515.1] covOの深追い(FixSuggester)をpushReport本体を待たせない別ジョブで行う（経緯: history
 *  3.515.1）。盤面/設定が変わっていれば結果は捨てる（[boardKey]/[stateKey]）。rawDiagLogsは更新しない。 */
internal fun MagiViewModel.deepCovODiagFollowUp(st: MagiState, schedule: Array<IntArray>, report: ViolationReport, runLabel: String?) {
    if (runLabel == null || (report.breakdown["covO"] ?: 0) <= 0) return
    val bKey = boardKey(schedule); val sKey = stateKey(st)
    viewModelScope.launch(Dispatchers.Default) {
        val deep = runCatching { V6PortAnalyzer.diagnoseCoverage(st, schedule, report, deepSurplus = true) }.getOrNull() ?: return@launch
        withContext(Dispatchers.Main) {
            if (currentSchedule?.let { boardKey(it) } == bKey && state?.let { stateKey(it) } == sKey) {
                _ui.update { it.copy(coverageDiag = deep.takeIf { d -> d.hasShortage || d.hasSurplus }) }
            }
        }
    }
}
