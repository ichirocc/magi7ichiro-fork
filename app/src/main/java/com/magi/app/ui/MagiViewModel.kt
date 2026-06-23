package com.magi.app.ui

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.magi.app.v6.Evaluator
import com.magi.app.v6.LightMirrorOptimizer
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.Problem
import com.magi.app.v6.SaOptimizer
import com.magi.app.v6.SaParams
import com.magi.app.v6.ScheduleCsvBridge
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.V6PortAnalyzer
import com.magi.app.v6.SettingIssue
import com.magi.app.v6.SettingFixAction
import com.magi.app.v6.FixSuggester
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.CoverageDiagnosis
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.V6NativeOptimizer
import com.magi.app.v6.V6SanityPort
import com.magi.app.v6.Hf63Infeasibility
import com.magi.app.v6.Ws1Ops
import com.magi.app.v6.Ws1Result
import com.magi.app.v6.allowedShiftsForStaff
import com.magi.app.v6.canDo
import com.magi.app.v6.copy2D
import com.magi.app.v6.toIntArray2D
import com.magi.app.v6.withSchedule
import com.magi.app.work.OptimizationRepository
import com.magi.app.work.OptimizationWorker
import com.magi.app.model.Range
import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.MagiState
import com.magi.app.model.MojibakeRepair
import com.magi.app.model.StateParser
import com.magi.app.v6.V6WebCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 勤務表最適化のタイムアウト上限（秒）。高精度を保ったまま5分(300s)以内に圧縮。
 * 唯一の真実源はエンジン層の [com.magi.app.v6.MAX_OPTIMIZE_SEC]。UI 側はそれを参照し、
 * UI 設定の上限とエンジンの頭打ちが乖離しないようにする。
 */
const val MAX_BUDGET_SEC = com.magi.app.v6.MAX_OPTIMIZE_SEC

class MagiViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var originalJson: String? = null
    private var state: MagiState? = null
    private var currentSchedule: Array<IntArray>? = null
    private var resultSchedule: Array<IntArray>? = null
    private var job: Job? = null
    private var checkJob: Job? = null
    private var fixJob: Job? = null   // [競合解消] 改善提案探索。連続タップ時に前探索をキャンセルし古い結果で上書きしない
    private var checkSeq = 0L

    // ===== [v2.22] 自動保存・復元（端末内）と「元に戻す」 =====
    private val autosaveFile get() = getApplication<Application>().filesDir.resolve("magi_autosave.json")
    private var hydrated = false           // 復元完了前の自動保存を抑止（Web HF514 と同思想）
    private var saveJob: Job? = null
    private data class UndoSnap(val st: MagiState, val sched: Array<IntArray>)
    private val undoStack = ArrayDeque<UndoSnap>()
    private val redoStack = ArrayDeque<UndoSnap>()   // [Web反映] undo で退避→redo で復元（手動修正ループ）
    private fun snapNow(): UndoSnap? {
        val st = state ?: return null; val sc = currentSchedule ?: return null
        return UndoSnap(st, Array(sc.size) { sc[it].clone() })
    }

    // ===== プロセス強制終了の耐性: 実行中マーカー（中断検知 / 仕様書 §3.4 補完） =====
    // 実行開始時にマーカーを書き、正常終了で消す。プロセスがkillされるとマーカーが残るので、
    // 次回起動時に「前回の計算は中断された（入力は自動保存済み）」と気づかせ、再実行へ導く。
    private val runMarkerFile get() = getApplication<Application>().filesDir.resolve("magi_run_marker.json")
    private fun writeRunMarker(mode: String) {
        runCatching {
            val o = org.json.JSONObject()
            o.put("startedAt", System.currentTimeMillis())
            o.put("mode", mode) // "fg" | "bg"
            o.put("budgetSec", _ui.value.budgetSec)
            o.put("workers", _ui.value.workers)
            o.put("algorithm", _ui.value.v6Algorithm.name)
            runMarkerFile.writeText(o.toString())
        }
    }
    private fun clearRunMarker() { runCatching { if (runMarkerFile.exists()) runMarkerFile.delete() } }
    fun dismissInterrupted() {
        _ui.value = _ui.value.copy(interruptedRun = false, interruptedInfo = null)
        OptimizationWorker.clearFiles(getApplication<Application>())   // [C1] 破棄で途中状態ファイルを削除
    }

    // ===== 操作ログ（監査）: 追記式・新しい順・時刻/レベル付き =====
    data class OpLogEntry(val timeMs: Long, val level: String, val message: String)
    private val opLog = ArrayDeque<OpLogEntry>()
    // 診断ログの「非圧縮・全文」を保持（画面表示は compressDiagLogs で圧縮、出力はこちらの全文を使う）。
    private var rawDiagLogs: List<String> = emptyList()
    private val opLogFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)

    /** 操作ログに1件追記し、UIへ反映（新しい順、最大300件）。 */
    private fun logOp(level: String, message: String) {
        opLog.addFirst(OpLogEntry(System.currentTimeMillis(), level, message))
        while (opLog.size > 1000) opLog.removeLast()
        _ui.value = _ui.value.copy(opLog = opLog.map { "${opLogFmt.format(java.util.Date(it.timeMs))} [${it.level}] ${it.message}" })
    }

    // 操作再現用デコード（現stateを参照。staff/shift一覧は操作中に不変）。
    private fun opNm(i: Int): String = state?.staff?.getOrNull(i)?.name ?: "#$i"
    private fun opSy(k: Int): String = state?.shifts?.getOrNull(k)?.kigou ?: "#$k"
    private fun opDays(days: List<Int>): String = if (days.size <= 10) days.joinToString(",") { "${it + 1}日" } else "${days.size}日分"

    init {
        // 起動時: 前回の自動保存があれば復元（無ければ何もしない）
        viewModelScope.launch {
            val txt = withContext(Dispatchers.IO) {
                runCatching { autosaveFile.takeIf { it.exists() }?.readText() }.getOrNull()
            }
            // 中断検知: 実行中マーカーが残っていれば前回の計算は完遂前に終了（プロセスkill等）。
            // loadAsync より先にフラグを立て、makeUi の base 経由で保持させる。
            val marker = withContext(Dispatchers.IO) {
                runCatching { runMarkerFile.takeIf { it.exists() }?.readText() }.getOrNull()
            }
            // [C1] 完了結果ファイルがあれば、bg最適化はUI不在でも完走済み＝結果を最優先で採用する。
            val resultTxt = withContext(Dispatchers.IO) {
                runCatching { OptimizationWorker.resultFile(getApplication<Application>()).takeIf { it.exists() }?.readText() }.getOrNull()
            }
            if (!resultTxt.isNullOrBlank()) {
                clearRunMarker()
                OptimizationWorker.clearFiles(getApplication<Application>())
                if (state == null) loadAsync(resultTxt)   // initialAssignment が state.schedule を返すため結果が復元される
                logOp("I", "前回のバックグラウンド最適化の結果を反映しました")
            } else {
                // [#4/C1] 中断時、途中最良解のスナップショットがあれば「途中結果から再開」する。
                val snapTxt = withContext(Dispatchers.IO) {
                    runCatching { OptimizationWorker.snapshotFile(getApplication<Application>()).takeIf { it.exists() }?.readText() }.getOrNull()
                }
                if (marker != null) {
                    val hasSnap = !snapTxt.isNullOrBlank()
                    val info = if (hasSnap)
                        "前回の計算は中断されましたが、途中までの最良の勤務表から再開できます。『もう一度つくる』で仕上げられます。"
                    else runCatching {
                        val o = org.json.JSONObject(marker)
                        val modeJp = if (o.optString("mode") == "bg") "バックグラウンド" else ""
                        "前回の${modeJp}計算は完了前に中断されました。入力は自動保存済みです。もう一度実行できます。"
                    }.getOrNull() ?: "前回の計算は完了前に中断されました。入力は自動保存済みです。"
                    _ui.value = _ui.value.copy(interruptedRun = true, interruptedInfo = info)
                    clearRunMarker()
                    logOp("W", if (hasSnap) "前回の中断を検知（途中結果あり＝再開可）" else "前回の計算の中断を検知しました（入力は復元済み）")
                }
                if (state == null) {
                    // 途中最良解を優先して復元（無ければ自動保存の入力）。
                    val resumeTxt = snapTxt?.takeIf { it.isNotBlank() } ?: txt
                    if (!resumeTxt.isNullOrBlank()) loadAsync(resumeTxt)
                    if (!snapTxt.isNullOrBlank()) OptimizationWorker.clearFiles(getApplication<Application>())   // 消費後は掃除
                }
            }
            hydrated = true
        }
        // バックグラウンド最適化（WorkManager）の進捗・結果を購読して画面へ反映（仕様書 §6.3）
        viewModelScope.launch {
            OptimizationRepository.progress.collect { p ->
                if (p != null && _ui.value.running) {
                    _ui.value = _ui.value.copy(
                        bestHard = p.hard.toLong(), bestSoft = p.soft.toLong(),
                        totalViolations = p.total, iters = p.iters, elapsedMs = p.elapsedMs,
                        message = "バックグラウンド ${p.phase}",
                    )
                }
            }
        }
        viewModelScope.launch {
            OptimizationRepository.result.collect { r -> if (r != null) applyBgResult(r) }
        }
    }

    /** バックグラウンド（WorkManager / Expedited）で最適化を開始。完了時に通知＋画面反映。 */
    fun runInBackground() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (_ui.value.running) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo()
        OptimizationRepository.clear()
        OptimizationWorker.clearFiles(getApplication<Application>())   // [C1] 旧途中状態を掃除（Workerが開始時に再保存）
        OptimizationRepository.request = st0 to sched0.copy2D()
        OptimizationRepository.seconds = _ui.value.budgetSec
        OptimizationRepository.workers = _ui.value.workers
        val work = androidx.work.OneTimeWorkRequestBuilder<OptimizationWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        androidx.work.WorkManager.getInstance(getApplication())
            .enqueueUniqueWork(OptimizationWorker.UNIQUE, androidx.work.ExistingWorkPolicy.REPLACE, work)
        _ui.value = _ui.value.copy(running = true, hasResult = false, interruptedRun = false, interruptedInfo = null, message = "バックグラウンドで最適化を開始しました（完了時に通知）")
        writeRunMarker("bg")
        logOp("I", "バックグラウンド最適化 開始 (予算${_ui.value.budgetSec}s, 並列${_ui.value.workers})")
    }

    private fun applyBgResult(r: OptimizationRepository.BgResult) {
        val st0 = state ?: return
        // [再実行 keep-best] 背景完了結果が前回採用解より悪化なら前回を維持（前景と同じ方針）。
        val prev = resultSchedule
        if (prev != null) {
            val prevReport = UnifiedViolationChecker.check(st0, prev)
            val newHard = r.report.hard.toLong(); val newTotal = r.report.total
            val worse = newHard > prevReport.hard.toLong() || (newHard == prevReport.hard.toLong() && newTotal > prevReport.total)
            if (worse) {
                val kept = prev.copy2D()
                currentSchedule = kept
                resultSchedule = kept
                state = st0.withSchedule(kept)
                autoSave()
                _ui.value = makeUi(state ?: st0, kept, prevReport, _ui.value.copy(
                    running = false, hasResult = true,
                    message = "今回(必須$newHard/合計$newTotal)は前回(必須${prevReport.hard}/合計${prevReport.total})より改善せず。前回の結果を維持しました。",
                ))
                logOp("I", "バックグラウンド: 今回 必須$newHard/合計$newTotal は前回 以下に改善せず → 前回を維持")
                clearRunMarker()
                OptimizationWorker.clearFiles(getApplication<Application>())
                OptimizationRepository.request = null
                OptimizationRepository.publishResult(null)
                return
            }
        }
        val sched = r.schedule.copy2D()
        currentSchedule = sched
        resultSchedule = sched
        state = st0.withSchedule(sched)
        autoSave()
        _ui.value = makeUi(state ?: st0, sched, r.report, _ui.value.copy(
            running = false, hasResult = true,
            message = "バックグラウンド最適化 完了: 必須=${r.report.hard} 合計=${r.report.total}",
        ))
        logOp("I", "バックグラウンド最適化 完了 必須=${r.report.hard} 合計=${r.report.total}")
        lastResultHard = r.report.hard.toLong()
        clearRunMarker()
        OptimizationWorker.clearFiles(getApplication<Application>())   // [C1] 完了で途中状態ファイルを削除
        // 消費したらクリア（再生成時の二重適用を防ぐ）
        OptimizationRepository.request = null
        OptimizationRepository.publishResult(null)
    }

    /** 1.2秒デバウンスで状態をアプリ専用領域に保存。失敗は黙殺（次回操作で再試行）。 */
    private fun autoSave() {
        if (!hydrated) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            val json = exportJson() ?: return@launch
            withContext(Dispatchers.IO) { runCatching { autosaveFile.writeText(json) } }
        }
    }

    private fun pushUndo() {
        val snap = snapNow() ?: return
        undoStack.addLast(snap)
        while (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()   // 新しい操作は redo 履歴を無効化（標準的な undo/redo 挙動）
        _ui.value = _ui.value.copy(canUndo = true, canRedo = false)
    }

    private fun clearUndo() {
        undoStack.clear()
        redoStack.clear()
        _ui.value = _ui.value.copy(canUndo = false, canRedo = false)
    }

    /** 直前の編集・取込・計算開始前の状態へ戻す（最大30段）。現在状態は redo へ退避。 */
    fun undo() {
        if (_ui.value.running) return
        val snap = undoStack.removeLastOrNull() ?: return
        snapNow()?.let { redoStack.addLast(it) }   // [Web反映] 現在をやり直し用に退避
        state = snap.st
        currentSchedule = Array(snap.sched.size) { snap.sched[it].clone() }
        _ui.value = _ui.value.copy(structureEdited = true, canUndo = undoStack.isNotEmpty(), canRedo = true, message = "1つ前に戻しました")
        logOp("I", "元に戻す")
        refreshCheck()
        autoSave()
    }

    /** [Web反映] 元に戻した操作をやり直す。手動修正のループ（修正→戻す→やり直し）を支える。 */
    fun redo() {
        if (_ui.value.running) return
        val snap = redoStack.removeLastOrNull() ?: return
        snapNow()?.let { undoStack.addLast(it) }
        state = snap.st
        currentSchedule = Array(snap.sched.size) { snap.sched[it].clone() }
        _ui.value = _ui.value.copy(structureEdited = true, canUndo = true, canRedo = redoStack.isNotEmpty(), message = "やり直しました")
        logOp("I", "やり直し")
        refreshCheck()
        autoSave()
    }

    fun load(json: String) = loadAsync(json)

    /**
     * [⛏6] ゼロから作る起点。最小の有効データ(1シフト/1グループ/1スタッフ/31日)を
     * 既存の load() 経路(StateParser→validate→Problem→makeUi)にそのまま流す。サンプル
     * (assets/sample_state_v6.json)と同じ構造を最小化したものなので、専用の初期化ロジックを
     * 持たず実行時の不整合リスクを抑える。読み込み後はユーザーが編集タブ(年次マスター)で
     * シフト/グループ/スタッフを一括追加して育てる想定。
     */
    fun initBlankState() {
        val days = 31
        val sched = (0 until days).joinToString(",") { "0" }
        val seed = """
            {"startDate":"2026-01-01","endDate":"2026-01-31",
            "shifts":[{"name":"休み","kigou":"休","need1":"","need2":""}],
            "groups":[{"name":"グループA","kigou":"A"}],
            "staff":[{"name":"職員1","groupIdx":0}],
            "use2Patterns":true,
            "groupShift":[[1]],"groupShiftApt":[[""]],
            "cons1":[],"cons2":[],"cons3":[],"cons3n":[],"cons3m":[],"cons3mn":[],"cons41":[],"cons42":[],
            "wishes":{},"staffRange":{},"needDay1":{},"needDay2":{},
            "schedule":[[$sched]]}
        """.trimIndent()
        load(seed)
    }

    fun loadAsync(rawJson: String) {
        val json = MojibakeRepair.repair(rawJson)
        val repaired = json !== rawJson
        job?.cancel()
        _ui.value = _ui.value.copy(running = true, message = "読込中…")
        job = viewModelScope.launch {
            try {
                if (repaired) logOp("W", "文字化け（二重エンコード）を自動修復して読み込みました")
                val loaded = withContext(Dispatchers.Default) {
                    val st = StateParser.parse(json)
                    validate(st)?.let { return@withContext Result.failure<LoadedProblem>(IllegalArgumentException(it)) }
                    val p = Problem(st)
                    val ev = Evaluator(p)
                    val init = p.initialAssignment()
                    val baseEval = ev.split(ev.fullEval(init))
                    val report = UnifiedViolationChecker.check(st, init)
                    Result.success(LoadedProblem(st, init, baseEval.first, baseEval.second, report))
                }
                loaded.fold(
                    onSuccess = { lp ->
                        originalJson = json
                        state = lp.state.withSchedule(lp.schedule)
                        currentSchedule = lp.schedule.copy2D()
                        resultSchedule = null
                        clearUndo()
                        autoSave()
                        _ui.value = makeUi(
                            st = lp.state,
                            schedule = lp.schedule,
                            report = lp.report,
                            base = _ui.value.copy(
                                loaded = true,
                                running = false,
                                hasResult = false,
                                constraintsEdited = false,
                                structureEdited = false,
                                staff = lp.state.staffCount,
                                days = lp.state.dayCount,
                                shifts = lp.state.shiftCount,
                                groups = lp.state.groupCount,
                                use2 = lp.state.use2Patterns,
                                initHard = lp.nativeHard,
                                initSoft = lp.nativeSoft,
                                iters = 0,
                                itersPerSec = 0,
                                elapsedMs = 0,
                                message = "読込完了: ${lp.state.staffCount}名 / ${lp.state.dayCount}日 / ${lp.state.shiftCount}シフト",
                            ),
                        )
                        logOp("I", "読込 ${lp.state.staffCount}名/${lp.state.dayCount}日/${lp.state.shiftCount}シフト")
                    },
                    onFailure = {
                        _ui.value = _ui.value.copy(running = false, message = "読込失敗: ${it.message}")
                    },
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "読込失敗: ${e.message}")
            }
        }
    }

    /** Returns a human-readable error message if the state is structurally invalid, else null. */
    private fun validate(st: MagiState): String? {
        if (st.staffCount == 0) return "staff が空です"
        if (st.dayCount == 0) return "schedule が空です"
        if (st.shiftCount == 0) return "shifts が空です"
        if (st.groupCount == 0) return "groups が空です"
        if (st.schedule.size != st.staffCount) return "schedule の行数が staff 数と一致しません"
        if (st.groupShift.size < st.groupCount) return "groupShift の行数が groups より少ないです"
        st.groupShift.forEachIndexed { g, row ->
            if (row.size < st.shiftCount) return "groupShift[$g] の列数が shifts より少ないです"
            if (row.take(st.shiftCount).none { it == 1 }) return "groupShift[$g] に担当可能シフトがありません"
        }
        st.groupShiftApt.forEachIndexed { g, row ->
            if (g < st.groupCount && row.isNotEmpty() && row.size < st.shiftCount) return "groupShiftApt[$g] の列数が shifts より少ないです"
        }
        st.staff.forEachIndexed { i, s ->
            if (s.groupIdx !in 0 until st.groupCount) return "staff[$i].groupIdx が範囲外です (${s.groupIdx})"
        }
        st.schedule.forEachIndexed { i, row ->
            if (row.size != st.dayCount) return "schedule[$i] の日数が不揃いです"
            row.forEachIndexed { j, k ->
                if (k != -1 && k !in 0 until st.shiftCount) return "schedule[$i][$j] のシフト番号が範囲外です ($k)"
            }
        }
        return null
    }

    /**
     * [native堅牢化] 最適化・生成の実行前に構造を検証する。期間/スタッフ/シフトの不整合や
     * 未割当グループ・範囲外シフト等があれば、クラッシュさせず理由を表示して中止する
     * （添付資料 doc#5/#6/#7 起因の事故をネイティブ側でも明示的に防止）。
     */
    private fun ensureValidForRun(st: MagiState, sched: Array<IntArray>): Boolean {
        val err = validate(st.withSchedule(sched)) ?: return true
        _ui.value = _ui.value.copy(running = false, message = "実行できません: $err。編集内容を確認してください")
        return false
    }

    fun setWorkers(n: Int) { val v = n.coerceIn(1, 16); _ui.value = _ui.value.copy(workers = v); logOp("I", "設定変更: 並列数 → $v") }
    // タイムアウト上限は5分(300s)。エンジンは budgetMs を全フェーズで厳守し、超過しない（停滞時はさらに早期終了）。
    fun setBudget(sec: Int) { val v = sec.coerceIn(10, MAX_BUDGET_SEC); _ui.value = _ui.value.copy(budgetSec = v); logOp("I", "設定変更: 予算 → ${v}秒") }
    fun setSoftPolish(b: Boolean) { _ui.value = _ui.value.copy(softPolish = b); logOp("I", "設定変更: ソフト研磨 → ${if (b) "ON" else "OFF"}") }
    fun setV6Algorithm(a: V6Algorithm) { _ui.value = _ui.value.copy(v6Algorithm = a); logOp("I", "設定変更: 方式 → $a") }

    fun refreshCheck() {
        val st = state ?: return
        val sched = currentSchedule?.copy2D() ?: return
        val seq = ++checkSeq
        checkJob?.cancel()
        _ui.value = _ui.value.copy(running = true, message = "違反チェック中…")
        checkJob = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleCheck(st, sched)
                if (seq != checkSeq) return@launch   // [review #6] a newer check started; drop stale result
                _ui.value = makeUi(st, res.schedule, res.report, _ui.value.copy(
                    running = false,
                    message = "違反チェック完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ))
                logOp("I", "違反チェック 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == checkSeq) _ui.value = _ui.value.copy(running = false, message = "違反チェック失敗: ${e.message}")
            }
        }
    }

    fun generateSimple() {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (!ensureValidForRun(st, sched)) return
        pushUndo()
        _ui.value = _ui.value.copy(running = true, hasResult = false, message = "簡易作成中…")
        job = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleSimple(st.withSchedule(sched), allowImpossible = true)
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                _ui.value = makeUi(state ?: st, res.schedule, res.report, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    iters = 0,
                    itersPerSec = 0,
                    elapsedMs = 0,
                    message = "簡易作成完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ))
                logOp("I", "簡易作成 完了 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "簡易作成失敗: ${e.message}")
            }
        }
    }

    fun start() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (_ui.value.running) return
        if (!ensureValidForRun(st0, sched0)) return
        val runState = st0.withSchedule(sched0)
        val p = Problem(runState)
        val ev = Evaluator(p)
        val params = SaParams(
            workers = _ui.value.workers,
            budgetMs = _ui.value.budgetSec * 1000L,
            softPolish = _ui.value.softPolish,
        )
        pushUndo()
        _ui.value = _ui.value.copy(running = true, hasResult = false, message = "高速計算中…")
        var lastUiMs = 0L
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    SaOptimizer(p, ev).run(params) { pr ->
                        val now = System.currentTimeMillis()
                        if (now - lastUiMs >= 200) {
                            lastUiMs = now
                            val (h, s) = ev.split(pr.bestScore)
                            val ips = if (pr.elapsedMs > 0) pr.totalIters * 1000 / pr.elapsedMs else 0
                            _ui.value = _ui.value.copy(
                                bestHard = h,
                                bestSoft = s,
                                iters = pr.totalIters,
                                itersPerSec = ips,
                                elapsedMs = pr.elapsedMs,
                            )
                        }
                    }
                }
                val report = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(runState, res.schedule) }
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = runState.withSchedule(res.schedule)
                val ips = if (res.elapsedMs > 0) res.totalIters * 1000 / res.elapsedMs else 0
                _ui.value = makeUi(state ?: runState, res.schedule, report, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    iters = res.totalIters,
                    itersPerSec = ips,
                    elapsedMs = res.elapsedMs,
                    message = "高速計算完了: 必須=${report.hard} 合計=${report.total} (${res.totalIters}反復, ${res.elapsedMs}ms)",
                ))
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は途中(未採用)盤面ではなく直前に確定していた入力解を保持し表示も整合させる。
                val kept = sched0.copy2D()
                val keptReport = withContext(NonCancellable + Dispatchers.Default) {
                    UnifiedViolationChecker.check(runState, kept)
                }
                currentSchedule = kept
                resultSchedule = kept
                state = runState.withSchedule(kept)
                _ui.value = makeUi(state ?: runState, kept, keptReport, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                ))
                throw e
            } catch (e: Exception) {
                // [review D] 失敗時は進捗中に書き込んだメトリクス（反復数・速度・経過）を消す。
                //   「事故前データ」を失敗メッセージの脇に残さない。hasResult は開始時に false 済み。
                _ui.value = _ui.value.copy(
                    running = false, hasResult = false,
                    iters = 0, itersPerSec = 0, elapsedMs = 0,
                    message = "最適化失敗: ${e.message}",
                )
            } finally {
                if (_ui.value.running) _ui.value = _ui.value.copy(running = false)
            }
        }
    }

    fun runLightOptimize() {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (_ui.value.running) return
        if (!ensureValidForRun(st, sched)) return
        pushUndo()
        _ui.value = _ui.value.copy(running = true, hasResult = false, message = "軽量最適化中…")
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) { LightMirrorOptimizer.optimize(st, sched, _ui.value.budgetSec.toDouble()) }
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                val ips = if (res.elapsedMs > 0) res.iterations * 1000 / res.elapsedMs else 0
                _ui.value = makeUi(state ?: st, res.schedule, res.report, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    iters = res.iterations,
                    itersPerSec = ips,
                    elapsedMs = res.elapsedMs,
                    message = "軽量最適化完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ))
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は途中(未採用)盤面ではなく直前に確定していた入力解を保持し表示も整合させる。
                val kept = sched.copy2D()
                val keptReport = withContext(NonCancellable + Dispatchers.Default) {
                    UnifiedViolationChecker.check(st, kept)
                }
                currentSchedule = kept
                resultSchedule = kept
                state = st.withSchedule(kept)
                _ui.value = makeUi(state ?: st, kept, keptReport, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                ))
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "軽量最適化失敗: ${e.message}")
            }
        }
    }

    // 操作コパイロット用: 直前の実行設定と結果（ガチャ操作検知に使用）
    private var lastSettingsSig: String? = null
    private var lastResultHard: Long = -1L
    private var lastTopHardFamily: String? = null

    private fun hardFamilyJp(key: String): String = when (key) {
        "covU" -> "人員不足（必要人数）"
        "c3n" -> "禁止の並び（連勤など）"
        "pref" -> "希望シフト"
        "groupViol" -> "担当できないシフト"
        "low" -> "個人の回数下限"
        "high" -> "個人の回数上限"
        else -> key
    }

    private fun topHardFamilyJp(breakdown: Map<String, Int>): String? {
        val keys = listOf("covU", "c3n", "pref", "groupViol", "low", "high")
        val top = keys.maxByOrNull { breakdown[it] ?: 0 } ?: return null
        return if ((breakdown[top] ?: 0) > 0) hardFamilyJp(top) else null
    }

    fun runV6FullOptimize() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (_ui.value.running) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo()
        val sig = "${_ui.value.budgetSec}|${_ui.value.workers}|${_ui.value.v6Algorithm}|${_ui.value.softPolish}"
        val hint = if (sig == lastSettingsSig && lastResultHard > 0L)
            "前回と同じ設定での再実行です。最大の未解決は『${lastTopHardFamily ?: "未解決の制約"}』。編集タブでこれを1つ緩めると改善の可能性が高いです。"
        else null
        lastSettingsSig = sig
        _ui.value = _ui.value.copy(running = true, hasResult = false, copilotHint = hint, alternatives = emptyList(), liveSchedule = emptyList(), interruptedRun = false, interruptedInfo = null, message = "計算エンジン実行中…")
        logOp("I", "最適化 開始 (予算${_ui.value.budgetSec}s, 並列${_ui.value.workers}, 方式${_ui.value.v6Algorithm})")
        writeRunMarker("fg")
        OptimizationWorker.clearFiles(getApplication<Application>())   // [C1] fg実行ではbg途中状態は無関係＝掃除
        val startMs = System.currentTimeMillis()
        // HF63: 探索の改善ストリームを追跡し、構造的に充足困難な制約族を検出（重み系は非改変＝安全）。
        val hf63 = Hf63Infeasibility()
        // 最適化中ログ強化用のスロットル状態（操作ログへマイルストーンだけを残しスパムを防ぐ）。
        var liveHard = Long.MAX_VALUE
        var livePhase = ""
        var lastPhaseLogMs = -10_000L
        var lastHardLogMs = -10_000L
        job = viewModelScope.launch {
            try {
                // [再実行 keep-best] 実行開始時の入力解(sched0)の違反を評価し、完了時の採用判定の基準にする。
                //   sched0 はデータ編集直後なら新データの初期解なので、編集をまたいでも公平な基準になる。
                val baseReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, sched0) }
                val res = V6FinalPort.handleOptimize(
                    state = st0,
                    schedule = sched0.copy2D(),
                    secondsRaw = _ui.value.budgetSec,
                    workers = _ui.value.workers,
                    softPolish = _ui.value.softPolish,
                    requestedAlgorithm = _ui.value.v6Algorithm,
                    allowImpossible = true,
                ) { phase, report, iters, elapsed ->
                    val rep = report
                    if (rep != null) hf63.updateFromBreakdown(rep.breakdown, iters.toInt())
                    _ui.value = _ui.value.copy(
                        bestHard = rep?.hard?.toLong() ?: _ui.value.bestHard,
                        bestSoft = rep?.soft?.toLong() ?: _ui.value.bestSoft,
                        totalViolations = rep?.total ?: _ui.value.totalViolations,
                        // 実行中も breakdown をライブ更新（export時に hard と breakdown が食い違う不整合を防ぐ）
                        breakdown = if (rep != null) emptyBreakdown + rep.breakdown else _ui.value.breakdown,
                        iters = iters,
                        itersPerSec = if (elapsed > 0) iters * 1000 / elapsed else 0,
                        elapsedMs = elapsed,
                        // [DefragLiveView] 計算中の最良盤面をライブ表示用に反映（節目で更新される）。
                        liveSchedule = V6NativeOptimizer.liveBest ?: _ui.value.liveSchedule,
                        message = "V6 $phase 実行中…",
                    )
                    // ---- 最適化中ログ強化（スロットル付き）----
                    // フェーズ遷移と「必須違反が減った瞬間」だけを操作ログへ。頻度上限を設けてスパムを防ぐ。
                    val base = phase.substringAfter("/ ").trim().ifEmpty { phase }
                    if (base != livePhase && elapsed - lastPhaseLogMs >= 2_500) {
                        logOp("I", "探索フェーズ: $base（${elapsed / 1000}秒経過）")
                        livePhase = base; lastPhaseLogMs = elapsed
                    }
                    if (rep != null && rep.hard.toLong() < liveHard) {
                        if (rep.hard == 0 || elapsed - lastHardLogMs >= 1_500) {
                            logOp("I", "必須違反 残り${rep.hard}件 に改善（${elapsed / 1000}秒・合計${rep.total}）")
                            lastHardLogMs = elapsed
                        }
                        liveHard = rep.hard.toLong()
                    }
                }
                // [再実行 keep-best] 完了結果が入力より悪化(必須↑ or 同必須で合計↑)なら、入力解を維持して通知する。
                //   「もう一度つくる」を繰り返したとき、稀に多様化フェーズ等で入力より悪い解が返り、それを採用して
                //   良い結果(例 HARD=1)を捨てる事象があった(実機ログで確認)。入力以上の結果のみ採用する。
                val newHard = res.report.hard.toLong(); val newTotal = res.report.total
                val baseHard = baseReport.hard.toLong(); val baseTotal = baseReport.total
                val worseThanInput = newHard > baseHard || (newHard == baseHard && newTotal > baseTotal)
                if (worseThanInput) {
                    val kept = sched0.copy2D()
                    currentSchedule = kept
                    autoSave()
                    resultSchedule = kept
                    state = st0.withSchedule(kept)
                    _ui.value = makeUi(state ?: st0, kept, baseReport, _ui.value.copy(
                        running = false,
                        hasResult = true,
                        itersPerSec = if (_ui.value.elapsedMs > 0) _ui.value.iters * 1000 / _ui.value.elapsedMs else 0,
                        message = "今回(必須$newHard/合計$newTotal)は前回(必須$baseHard/合計$baseTotal)より改善しませんでした。前回の結果を維持します。",
                    ))
                    logOp("I", "再実行: 今回 必須$newHard/合計$newTotal は前回 必須$baseHard/合計$baseTotal 以下に改善せず → 前回を維持")
                    lastResultHard = baseHard
                } else {
                    currentSchedule = res.schedule.copy2D()
                    autoSave()
                    resultSchedule = res.schedule.copy2D()
                    state = st0.withSchedule(res.schedule)
                    _ui.value = makeUi(state ?: st0, res.schedule, res.report, _ui.value.copy(
                        running = false,
                        hasResult = true,
                        itersPerSec = if (_ui.value.elapsedMs > 0) _ui.value.iters * 1000 / _ui.value.elapsedMs else 0,
                        message = "最適化（${res.phase}）完了: 必須=${res.report.hard} 合計=${res.report.total} (${System.currentTimeMillis() - startMs}ms)",
                    ))
                    lastResultHard = newHard
                }
                lastTopHardFamily = if (res.report.hard > 0) topHardFamilyJp(res.report.breakdown) else null
                logOp(if (res.report.hard == 0) "I" else "W", "最適化 完了 必須=${res.report.hard} 合計=${res.report.total} (${res.phase})")
                // HF63 検出: 5000反復改善のない制約族＝データ上満たせない可能性が高い（業務担当者へ提示）。
                if (hf63.infeasibleCount() > 0) {
                    logOp("W", "構造的に充足が難しい制約を検出: ${hf63.infeasibleFamilies().joinToString(", ")}（データの見直しを推奨）")
                }
                captureAlternatives()
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は実行中の(未採用の)途中盤面ではなく、直前に確定していた
                //   入力解(sched0)をそのまま保持し、表示の違反数も実際の盤面に合わせる。これにより
                //   「必須=0だったのに停止したら必須が増えて見える」不整合を防ぐ（完了時のkeep-bestと同じ思想）。
                val kept = sched0.copy2D()
                val keptReport = withContext(NonCancellable + Dispatchers.Default) {
                    UnifiedViolationChecker.check(st0, kept)
                }
                currentSchedule = kept
                resultSchedule = kept
                state = st0.withSchedule(kept)
                _ui.value = makeUi(state ?: st0, kept, keptReport, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                ))
                logOp("I", "停止: 直前の勤務表 必須=${keptReport.hard}/合計=${keptReport.total} を保持")
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "V6最適化失敗: ${e.message}")
            } finally {
                clearRunMarker()  // 正常終了・停止・失敗いずれでもマーカーを消す（中断のみ残す）
                if (_ui.value.running) _ui.value = _ui.value.copy(running = false)
            }
        }
    }

    /**
     * [ソフト研磨のみ] 現在の勤務表をHARDガード付きで局所研磨し、SOFT違反だけを削る。
     *   「もう一度つくる」と違い破壊/多様化を行わないため必須が一時的に増えることはなく、
     *   keep-best により入力より悪い結果は採用しない（HARD=0 を壊さない）。
     */
    fun runSoftPolish() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (_ui.value.running) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo()
        _ui.value = _ui.value.copy(running = true, hasResult = false, liveSchedule = emptyList(), message = "ソフト研磨中…")
        logOp("I", "ソフト研磨 開始 (予算${_ui.value.budgetSec}s)")
        val startMs = System.currentTimeMillis()
        job = viewModelScope.launch {
            try {
                val baseReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, sched0) }
                val polished = withContext(Dispatchers.Default) {
                    V6NativeOptimizer.softPolishOnly(st0, sched0.copy2D(), _ui.value.budgetSec)
                }
                val polishedReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, polished) }
                // softPolishOnly は退化防止済みだが、VM側でも (必須, 合計) で入力以上のみ採用（保険）。
                val worse = polishedReport.hard > baseReport.hard ||
                    (polishedReport.hard == baseReport.hard && polishedReport.total > baseReport.total)
                val finalSched = if (worse) sched0.copy2D() else polished.copy2D()
                val finalReport = if (worse) baseReport else polishedReport
                currentSchedule = finalSched
                autoSave()
                resultSchedule = finalSched
                state = st0.withSchedule(finalSched)
                val gain = baseReport.total - finalReport.total
                _ui.value = makeUi(state ?: st0, finalSched, finalReport, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    message = if (gain > 0)
                        "ソフト研磨 完了: 合計 ${baseReport.total} → ${finalReport.total}（-$gain）必須=${finalReport.hard} (${System.currentTimeMillis() - startMs}ms)"
                    else
                        "ソフト研磨 完了: これ以上の削減は見つかりませんでした（合計=${finalReport.total} 必須=${finalReport.hard}）。残りは構造的要因の可能性。",
                ))
                logOp("I", "ソフト研磨 完了 必須=${finalReport.hard} 合計=${finalReport.total}（${if (gain > 0) "-$gain" else "増減なし"}）")
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は直前の確定盤面を保持し表示も整合させる。
                val kept = sched0.copy2D()
                val keptReport = withContext(NonCancellable + Dispatchers.Default) {
                    UnifiedViolationChecker.check(st0, kept)
                }
                currentSchedule = kept
                resultSchedule = kept
                state = st0.withSchedule(kept)
                _ui.value = makeUi(state ?: st0, kept, keptReport, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                ))
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "ソフト研磨失敗: ${e.message}")
            } finally {
                if (_ui.value.running) _ui.value = _ui.value.copy(running = false)
            }
        }
    }

    fun stop() { job?.cancel(); checkJob?.cancel(); fixJob?.cancel(); clearRunMarker() }

    /** Shift indices a staff member may take (for the cell-edit bottom sheet). */
    fun allowedShiftsFor(i: Int): IntArray {
        val st = state ?: return IntArray(0)
        return Problem(st).allowedShiftsForStaff(i)
    }

    /** 入力ガイド（月次/年次の入力手順）用の各項目の件数。 */
    data class SetupCounts(
        val days: Int, val staff: Int, val shifts: Int, val groups: Int,
        val wishes: Int, val needDay: Int, val constraints: Int, val ranges: Int, val use2: Boolean,
    )
    fun setupCounts(): SetupCounts {
        val st = state ?: return SetupCounts(0, 0, 0, 0, 0, 0, 0, 0, false)
        val cons = st.cons1.size + st.cons2.size + st.cons3.size + st.cons3n.size +
            st.cons3m.size + st.cons3mn.size + st.cons41.size + st.cons42.size
        return SetupCounts(
            st.dayCount, st.staffCount, st.shiftCount, st.groupCount,
            st.wishes.size, st.needDay1.size + st.needDay2.size, cons, st.staffRange.size, st.use2Patterns,
        )
    }

    /** 担当外（そのスタッフのグループで担当不可）な希望の件数。希望で上書き時の確認に使う。 */
    fun wishOutOfScopeCount(): Int {
        val st = state ?: return 0
        val p = Problem(st)
        var n = 0
        for ((key, k) in st.wishes) {
            val i = key.split(',').getOrNull(0)?.toIntOrNull() ?: continue
            if (i in 0 until p.S && k in 0 until p.K && !p.canDo(i, k)) n++
        }
        return n
    }

    /**
     * 希望シフトを勤務表へ上書き反映（Web版の「希望で上書き」相当）。担当外の希望は
     * [includeOutOfScope]=true のときのみ反映。Undo・操作ログ付き。
     */
    fun applyWishes(includeOutOfScope: Boolean) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val p = Problem(st)
        pushUndo()
        var applied = 0
        var oos = 0
        for ((key, k) in st.wishes) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (i !in 0 until p.S || j !in 0 until p.T || k !in 0 until p.K) continue
            val can = p.canDo(i, k)
            if (!can && !includeOutOfScope) continue
            if (i in sched.indices && j in sched[i].indices && sched[i][j] != k) {
                sched[i][j] = k
                applied++
                if (!can) oos++
            }
        }
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        val note = if (oos > 0) "（担当外 ${oos}件含む）" else ""
        logOp(if (oos > 0) "W" else "I", "希望を勤務表へ反映 ${applied}件$note")
        _ui.value = _ui.value.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "希望を反映: ${applied}件$note",
        )
        refreshCheck()
    }

    private var alternativeScheds: List<Array<IntArray>> = emptyList()

    /** 直近の並列最適化で得た「他の案」を取り込み、サマリをUIへ反映。 */
    private fun captureAlternatives() {
        val st = state ?: return
        val alts = V6NativeOptimizer.lastAlternatives.map { it.copy2D() }
        alternativeScheds = alts
        val summaries = alts.mapIndexed { idx, sch ->
            val rep = UnifiedViolationChecker.check(st, sch)
            "案${idx + 1}: 必須=${rep.hard} 合計=${rep.total}"
        }
        _ui.value = _ui.value.copy(alternatives = summaries)
    }

    /** 「他の案」を勤務表へ適用（Undo・操作ログ付き）。 */
    fun applyAlternative(i: Int) {
        val st = state ?: return
        val sch = alternativeScheds.getOrNull(i)?.copy2D() ?: return
        pushUndo()
        currentSchedule = sch
        resultSchedule = sch
        state = st.withSchedule(sch)
        autoSave()
        val rep = UnifiedViolationChecker.check(state ?: st, sch)
        _ui.value = makeUi(state ?: st, sch, rep, _ui.value.copy(hasResult = true, message = "他の案 ${i + 1} を適用"))
        logOp("I", "他の案 ${i + 1} を適用 必須=${rep.hard} 合計=${rep.total}")
    }

    /** Set a specific shift in a cell (bottom-sheet picker). */
    fun setCell(i: Int, j: Int, shift: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (i !in sched.indices || j !in sched[i].indices) return
        if (sched[i][j] == shift) return
        pushUndo()
        sched[i][j] = shift
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.value = _ui.value.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "${st.staff.getOrNull(i)?.name ?: i} / ${j + 1}日 を ${st.shifts.getOrNull(shift)?.kigou ?: shift} に変更",
        )
        logOp("I", "編集: ${opNm(i)} ${j + 1}日 → ${opSy(shift)}")
        refreshCheck()
    }

    /** [プロ一括編集] 複数セル(i,j)を1シフトへ一括設定。Undoは1回・再チェックも1回（keep-best互換）。 */
    fun setCells(cells: Collection<Pair<Int, Int>>, shift: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        var changed = 0
        var first = true
        for ((i, j) in cells) {
            if (i !in sched.indices || j !in sched[i].indices) continue
            if (sched[i][j] == shift) continue
            if (first) { pushUndo(); first = false }
            sched[i][j] = shift
            changed++
        }
        if (changed == 0) return
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.value = _ui.value.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "${changed}マスを ${st.shifts.getOrNull(shift)?.kigou ?: shift} に一括変更",
        )
        logOp("I", "一括編集: ${changed}マス → ${opSy(shift)}")
        refreshCheck()
    }

    /** [operator_ux §5] 「なおすのを手伝って」用：ある不足枠(日×シフト)に1タップで入れられる候補職員。 */
    data class FixCandidate(val staffIndex: Int, val name: String, val groupSymbol: String, val fromRest: Boolean)
    fun shortageFixCandidates(dayIndex: Int, shiftIndex: Int): List<FixCandidate> {
        val st = state ?: return emptyList()
        val sched = currentSchedule ?: return emptyList()
        val p = Problem(st)
        if (shiftIndex !in 0 until p.K || dayIndex !in 0 until p.T) return emptyList()
        val rest = st.shifts.indexOfFirst { it.kigou == "休" }
        val out = ArrayList<FixCandidate>()
        for (i in 0 until p.S) {
            if (i !in sched.indices || dayIndex !in sched[i].indices) continue
            if (!p.canDo(i, shiftIndex)) continue            // 担当できないシフトは出さない
            if (sched[i][dayIndex] == shiftIndex) continue   // すでにそのシフト
            val w = p.wish[i][dayIndex]
            if (w in 0 until p.K && w != shiftIndex) continue // 別シフトへ希望固定
            val g = st.staff.getOrNull(i)?.groupIdx ?: -1
            out.add(FixCandidate(i, st.staff.getOrNull(i)?.name ?: "#$i", st.groups.getOrNull(g)?.kigou ?: "", sched[i][dayIndex] == rest))
        }
        // 休みの人（動かしやすい）を先頭に。
        out.sortBy { if (it.fromRest) 0 else 1 }
        return out
    }

    fun cycleCell(i: Int, j: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (i !in sched.indices || j !in sched[i].indices) return
        val p = Problem(st)
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isEmpty()) return
        val old = sched[i][j]
        val idx = allowed.indexOf(old)
        val next = allowed[(idx + 1).floorMod(allowed.size)]
        pushUndo()
        sched[i][j] = next
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.value = _ui.value.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "${st.staff.getOrNull(i)?.name ?: i} / ${j + 1}日 を ${st.shifts.getOrNull(next)?.kigou ?: next} に変更",
        )
        logOp("I", "編集: ${opNm(i)} ${j + 1}日 → ${opSy(next)}")
        refreshCheck()
    }

    /** [B1] 編集中(ws7) を結果(ws6) として確定する。表示・違反は不変なのでスナップショットのみ更新。 */
    fun commitEditingToResult() {
        val cur = currentSchedule ?: return
        resultSchedule = cur.copy2D()
        _ui.value = _ui.value.copy(
            resultSchedule = cur.map { it.toList() },
            hasResultSnapshot = true,
            message = "編集中の内容を「結果」として確定しました",
        )
        logOp("I", "編集中→結果に確定")
    }

    /** [B1] 結果(ws6) を編集中(ws7) に複製（編集中は破棄、元に戻すで取消可）。 */
    fun copyResultToEditing() {
        val st = state ?: return
        val res = resultSchedule ?: return
        pushUndo()
        val sched = res.copy2D()
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.value = _ui.value.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "「結果」を編集中に複製しました（元に戻すで取消可）",
        )
        refreshCheck()
        logOp("I", "結果→編集中に複製")
    }

    /** [B1] 読取専用モードでセル編集が試行された時の案内。 */
    fun hintReadOnly() {
        _ui.value = _ui.value.copy(message = "「結果」は読取専用です。修正は「編集中」モードに切り替えてください。")
    }

    // ---- constraint editing (ws3-5) -------------------------------------------

    /** A constraint family with its rows rendered for display (key used for add/remove). */
    data class ConstraintFamilyView(val key: String, val title: String, val rows: List<String>)

    fun shiftKigouList(): List<String> = state?.shifts?.map { it.kigou } ?: emptyList()

    // ---- ws2: 日別の必要人数（例外） needDay1/needDay2 の疎な上書きを編集 ----
    data class NeedDayView(val k: Int, val j: Int, val kigou: String, val p1: String, val p2: String)

    fun needDayOverrides(): List<NeedDayView> {
        val st = state ?: return emptyList()
        val keys = (st.needDay1.keys + st.needDay2.keys).toSet()
        return keys.mapNotNull { key ->
            val parts = key.split(",")
            if (parts.size != 2) return@mapNotNull null
            val k = parts[0].toIntOrNull() ?: return@mapNotNull null
            val j = parts[1].toIntOrNull() ?: return@mapNotNull null
            NeedDayView(k, j, st.shifts.getOrNull(k)?.kigou ?: k.toString(), st.needDay1[key] ?: "", st.needDay2[key] ?: "")
        }.sortedWith(compareBy({ it.j }, { it.k }))
    }

    fun setNeedDay(k: Int, j: Int, p1: String, p2: String) {
        val st = state ?: return
        val key = "$k,$j"
        val nd1 = st.needDay1.toMutableMap()
        val nd2 = st.needDay2.toMutableMap()
        if (p1.isBlank()) nd1.remove(key) else nd1[key] = p1.trim()
        if (p2.isBlank()) nd2.remove(key) else nd2[key] = p2.trim()
        logOp("I", "需要設定: ${opSy(k)} ${j + 1}日 → P1=${p1.ifBlank { "-" }} P2=${p2.ifBlank { "-" }}")
        applyStructure(st.copy(needDay1 = nd1, needDay2 = nd2))
    }

    fun removeNeedDay(k: Int, j: Int) {
        val st = state ?: return
        val key = "$k,$j"
        logOp("I", "需要削除: ${opSy(k)} ${j + 1}日"); applyStructure(st.copy(needDay1 = st.needDay1 - key, needDay2 = st.needDay2 - key))
    }

    // ---- ws5: 個人別の回数（LimMin/LimMax） staffRange["i,k"]=Range(lo,hi) を編集 ----
    data class StaffRangeView(val i: Int, val k: Int, val staffName: String, val kigou: String, val lo: String, val hi: String)

    fun staffRangeOverrides(): List<StaffRangeView> {
        val st = state ?: return emptyList()
        return st.staffRange.mapNotNull { (key, r) ->
            val parts = key.split(",")
            if (parts.size != 2) return@mapNotNull null
            val i = parts[0].toIntOrNull() ?: return@mapNotNull null
            val k = parts[1].toIntOrNull() ?: return@mapNotNull null
            StaffRangeView(i, k, st.staff.getOrNull(i)?.name ?: i.toString(), st.shifts.getOrNull(k)?.kigou ?: k.toString(), r.lo, r.hi)
        }.sortedWith(compareBy({ it.i }, { it.k }))
    }

    fun setStaffRange(i: Int, k: Int, lo: String, hi: String) {
        val st = state ?: return
        val key = "$i,$k"
        val m = st.staffRange.toMutableMap()
        if (lo.isBlank() && hi.isBlank()) m.remove(key) else m[key] = Range(lo.trim(), hi.trim())
        logOp("I", "個人レンジ: ${opNm(i)} ${opSy(k)} → ${if (lo.isBlank() && hi.isBlank()) "削除" else "${lo.ifBlank { "?" }}〜${hi.ifBlank { "?" }}"}")
        applyStructure(st.copy(staffRange = m))
    }

    fun removeStaffRange(i: Int, k: Int) {
        val st = state ?: return
        logOp("I", "個人レンジ削除: ${opNm(i)} ${opSy(k)}"); applyStructure(st.copy(staffRange = st.staffRange - "$i,$k"))
    }

    /** [直せる導線] 集計セル(職員別)の違反詳細用しきい値: 下限/上限(staffRange)・目標(apt実効)。未設定は null。 */
    fun staffCellLimits(i: Int, k: Int): Triple<Int?, Int?, Int?> {
        val st = state ?: return Triple(null, null, null)
        val p = cachedProblem(st)
        if (i !in 0 until p.S || k !in 0 until p.K) return Triple(null, null, null)
        val lo = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE || it == 0) null else it }
        val hi = p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) null else it }
        val apt = p.apt[i][k].let { if (it < 0) null else it }
        return Triple(lo, hi, apt)
    }

    /** [直せる導線] 集計セル(日別)の必要数レンジ lo..hi（need1/need2）。lo<0(対象外)は null。 */
    fun needCellLimits(k: Int, j: Int): Pair<Int, Int>? {
        val st = state ?: return null
        val p = cachedProblem(st)
        if (k !in 0 until p.K || j !in 0 until p.T) return null
        val lo = p.need1[k][j]
        if (lo < 0) return null
        val hi = if (p.use2 && p.need2[k][j] >= 0) p.need2[k][j] else lo
        return lo to hi
    }

    /** [回数センター] 個人別の回数(上下限)と適切回数(apt)を職員×シフトで統合した一覧。
     *  staffRange または apt(実効=担当可＆クランプ後)が効くセルのみ返す。aptEff=実効目標(-1=なし),
     *  aptRaw=群目標の生値(-1=なし。aptEff と異なればクランプされている)。hasRange=個人別の上下限あり。 */
    data class CountRuleView(
        val i: Int, val k: Int, val staffName: String, val kigou: String,
        val lo: String, val hi: String, val aptEff: Int, val aptRaw: Int, val hasRange: Boolean,
    )

    fun staffCountRules(): List<CountRuleView> {
        val st = state ?: return emptyList()
        // [レビュー#1] 再描画毎に呼ばれるため cachedProblem で Problem 再構築(高コスト)を避ける。
        val p = cachedProblem(st)
        val rows = mutableListOf<CountRuleView>()
        for (i in 0 until p.S) {
            val g = st.staff.getOrNull(i)?.groupIdx ?: continue
            for (k in 0 until p.K) {
                val r = st.staffRange["$i,$k"]
                val hasRange = r != null && (r.lo.isNotBlank() || r.hi.isNotBlank())
                val aptEff = p.apt[i][k]
                if (!hasRange && aptEff < 0) continue
                val aptRaw = st.groupShiftApt.getOrNull(g)?.getOrNull(k)?.trim()?.toIntOrNull() ?: -1
                rows.add(
                    CountRuleView(
                        i, k, st.staff[i].name, st.shifts.getOrNull(k)?.kigou ?: k.toString(),
                        r?.lo ?: "", r?.hi ?: "", aptEff, if (aptEff >= 0) aptRaw else -1, hasRange,
                    )
                )
            }
        }
        return rows.sortedWith(compareBy({ it.i }, { it.k }))
    }

    // ---- [回数設定画面] シフト軸 / 個人軸の統合ビュー（apt=月の目標 / staffRange=個人の月 最少・最大）----
    //   cons41(群の1日人数)は「回数(月)」とは別軸のため本画面では扱わない（制約画面で編集）。
    data class GroupRule(val g: Int, val groupName: String, val ideal: String)
    data class IndivRule(val i: Int, val staffName: String, val min: String, val max: String)
    data class ShiftRuleBlock(val k: Int, val kigou: String, val name: String, val groups: List<GroupRule>, val indivs: List<IndivRule>)

    /** シフトタブ用: 各シフトの「群の回数(最少|理想|最大)」「個人の回数(最少|最大)」を集約。設定のある行のみ。 */
    fun shiftRuleBlocks(): List<ShiftRuleBlock> {
        val st = state ?: return emptyList()
        return st.shifts.mapIndexed { k, sh ->
            // [apt 0設定] そのシフトを担当できる群(groupShift==1)を全て出す。未設定群は ideal="" のまま渡し、
            //   UI で「なし」から ＋/− で 0 以上を設定可能にする(従来は設定済みの群しか出ず入口が無かった)。
            //   apt は canDo 群のみ有効なので担当不可の群は除外。
            val groups = st.groups.indices.mapNotNull { g ->
                val canDo = st.groupShift.getOrNull(g)?.getOrNull(k) == 1
                if (!canDo) null
                else GroupRule(g, st.groups[g].name, st.groupShiftApt.getOrNull(g)?.getOrNull(k)?.trim() ?: "")
            }
            val indivs = st.staff.indices.mapNotNull { i ->
                val r = st.staffRange["$i,$k"]
                if (r == null || (r.lo.isBlank() && r.hi.isBlank())) null
                else IndivRule(i, st.staff[i].name, r.lo, r.hi)
            }
            ShiftRuleBlock(k, sh.kigou, sh.name, groups, indivs)
        }
            // 設定済みのシフトだけ表示(どれかの群に目標がある or 個人レンジがある)。表示シフト内なら未設定群も 0 から設定可。
            //   これで休/有のような未設定シフトの雑音を避けつつ、A4 等の表示シフト内で未設定群に ＋/− を出せる。
            .filter { b -> b.groups.any { it.ideal.isNotBlank() } || b.indivs.isNotEmpty() }
    }

    data class StaffShiftRule(val k: Int, val kigou: String, val min: String, val max: String)
    data class StaffRuleBlock(val i: Int, val name: String, val rows: List<StaffShiftRule>)

    /** 個人タブ用: 各職員の「シフトごとの回数(最少|最大)」を集約。設定のある行のみ。 */
    fun staffRuleBlocks(): List<StaffRuleBlock> {
        val st = state ?: return emptyList()
        return st.staff.mapIndexed { i, sf ->
            val rows = st.shifts.indices.mapNotNull { k ->
                val r = st.staffRange["$i,$k"]
                if (r == null || (r.lo.isBlank() && r.hi.isBlank())) null
                else StaffShiftRule(k, st.shifts[k].kigou, r.lo, r.hi)
            }
            StaffRuleBlock(i, sf.name, rows)
        }.filter { it.rows.isNotEmpty() }
    }

    // ---- ws3 移植: 希望シフト wishes["i,j"]=シフトindex（採点=pref/hard1。割当やcons3系とは別。UIのみ・モデル/エンジン不変）----
    data class WishView(val i: Int, val j: Int, val staffName: String, val day: Int, val kigou: String, val k: Int)

    fun wishOverrides(): List<WishView> {
        val st = state ?: return emptyList()
        return st.wishes.mapNotNull { (key, k) ->
            val parts = key.split(",")
            if (parts.size != 2) return@mapNotNull null
            val i = parts[0].toIntOrNull() ?: return@mapNotNull null
            val j = parts[1].toIntOrNull() ?: return@mapNotNull null
            WishView(i, j, st.staff.getOrNull(i)?.name ?: i.toString(), j + 1, st.shifts.getOrNull(k)?.kigou ?: k.toString(), k)
        }.sortedWith(compareBy({ it.i }, { it.j }))
    }

    fun setWish(i: Int, j: Int, k: Int) {
        val st = state ?: return
        val m = st.wishes.toMutableMap()
        m["$i,$j"] = k
        logOp("I", "希望設定: ${opNm(i)} ${j + 1}日 → ${opSy(k)}")
        applyStructure(st.copy(wishes = m))
    }

    fun removeWish(i: Int, j: Int) {
        val st = state ?: return
        logOp("I", "希望削除: ${opNm(i)} ${j + 1}日")
        applyStructure(st.copy(wishes = st.wishes - "$i,$j"))
    }

    /** [一括] スタッフ(null=全員)×日群に希望 k を一括設定。Undo1回・再チェック1回。 */
    fun setWishesForDays(staffIdx: Int?, days: List<Int>, k: Int) {
        val st = state ?: return
        if (days.isEmpty() || k !in st.shifts.indices) return
        val m = st.wishes.toMutableMap()
        val staffRange = if (staffIdx != null) listOf(staffIdx) else st.staff.indices.toList()
        for (i in staffRange) for (j in days) if (i in st.staff.indices && j in 0 until st.dayCount) m["$i,$j"] = k
        logOp("I", "希望一括: ${if (staffIdx != null) opNm(staffIdx) else "全員"} ${opDays(days)} → ${opSy(k)}")
        applyStructure(st.copy(wishes = m))
    }

    /** [一括] スタッフ(null=全員)×日群の希望を一括削除。 */
    fun clearWishesForDays(staffIdx: Int?, days: List<Int>) {
        val st = state ?: return
        if (days.isEmpty()) return
        val m = st.wishes.toMutableMap()
        val staffRange = if (staffIdx != null) listOf(staffIdx) else st.staff.indices.toList()
        for (i in staffRange) for (j in days) m.remove("$i,$j")
        if (m.size == st.wishes.size) return
        logOp("I", "希望クリア: ${if (staffIdx != null) opNm(staffIdx) else "全員"} ${opDays(days)}")
        applyStructure(st.copy(wishes = m))
    }

    /** [一括] すべての希望を削除。 */
    fun clearAllWishes() {
        val st = state ?: return
        if (st.wishes.isEmpty()) return
        logOp("I", "希望全クリア")
        applyStructure(st.copy(wishes = emptyMap()))
    }

    // ---- colors: シフトの表示色 shiftColors[kigou]="#rrggbb"（表示専用）----
    data class ShiftColorView(val kigou: String, val name: String, val hex: String, val custom: Boolean)

    fun shiftColorList(): List<ShiftColorView> {
        val st = state ?: return emptyList()
        return st.shifts.map { sh ->
            val ov = st.shiftColors[sh.kigou]
            ShiftColorView(sh.kigou, sh.name, V6WebCompat.resolveShiftColor(sh.kigou, sh.name, ov), !ov.isNullOrBlank())
        }
    }

    fun setShiftColor(kigou: String, hex: String) {
        val st = state ?: return
        if (kigou.isBlank()) return
        val m = st.shiftColors.toMutableMap()
        m[kigou] = hex.trim()
        applyStructure(st.copy(shiftColors = m))
    }

    fun resetShiftColor(kigou: String) {
        val st = state ?: return
        applyStructure(st.copy(shiftColors = st.shiftColors - kigou))
    }
    /** [違反色] 違反セルの枠/マーカー色。予約キー "__vio__" に保存（状態スキーマ非変更）。 */
    fun setViolationColor(hex: String) {
        val st = state ?: return; if (hex.isBlank()) return
        applyStructure(st.copy(shiftColors = st.shiftColors + ("__vio__" to hex.trim())))
    }
    fun resetViolationColor() {
        val st = state ?: return
        applyStructure(st.copy(shiftColors = st.shiftColors - "__vio__"))
    }
    fun groupKigouList(): List<String> = state?.groups?.map { it.kigou } ?: emptyList()

    /** [冗長除去/データ密度] 1日人数の上下限 [l〜u] を意味で圧縮して短く表す。見出しが「人数(上下限)」の
     *  文脈を担うので、行は記号のみで足りる。l==u=ちょうどN / 下限のみ=N以上 / 上限のみ=N以下 / 両方=l〜u。 */
    private fun boundLabel(l: String, u: String): String {
        val lo = l.ifBlank { null }; val hi = u.ifBlank { null }
        return when {
            lo != null && hi != null && lo == hi -> "ちょうど$lo"
            lo != null && hi != null -> "$lo〜$hi"
            lo != null -> "$lo 以上"
            hi != null -> "$hi 以下"
            else -> "制限なし"
        }
    }

    fun constraintFamilies(): List<ConstraintFamilyView> {
        val st = state ?: return emptyList()
        fun seq(p: List<String>) = p.filter { it.isNotBlank() }.joinToString(" -> ").ifEmpty { "(空)" }
        return listOf(
            ConstraintFamilyView("cons1", "期間の決まり（何日かの間の必要数）",
                st.cons1.map { "${it.shiftKigou}   ${it.day1}日で${it.day2}回以上" }),
            ConstraintFamilyView("cons2", "個人の合計回数",
                st.cons2.map { "${it.shiftKigou}   合計${it.count}回以上" }),
            ConstraintFamilyView("cons3", "必須の並び", st.cons3.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3n", "禁止の並び", st.cons3n.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3m", "推奨の並び", st.cons3m.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3mn", "回避の並び", st.cons3mn.map { seq(it.pattern) }),
            ConstraintFamilyView("cons41", "グループ別の1日の人数（群・シフト → 人数）",
                st.cons41.map { "${it.groupKigou}・${it.shiftKigou}   ${boundLabel(it.l, it.u)}" }),
            ConstraintFamilyView("cons42", "グループの組み合わせ禁止（同じ日に不可）",
                st.cons42.map { "${it.g1Kigou}・${it.s1Kigou}  ✕  ${it.g2Kigou}・${it.s2Kigou}" }),
        )
    }

    /** [スキルグループ専用ルール] C41s/C42s。スキルグループ定義の直下に co-locate して表示する。 */
    fun skillConstraintFamilies(): List<ConstraintFamilyView> {
        val st = state ?: return emptyList()
        return listOf(
            ConstraintFamilyView("cons41s", "スキル別の1日の人数（スキル・シフト → 人数）",
                st.cons41s.map { "${it.groupKigou}・${it.shiftKigou}   ${boundLabel(it.l, it.u)}" }),
            ConstraintFamilyView("cons42s", "スキルの組み合わせ禁止（同じ日に不可）",
                st.cons42s.map { "${it.g1Kigou}・${it.s1Kigou}  ✕  ${it.g2Kigou}・${it.s2Kigou}" }),
        )
    }

    fun skillGroupKigouList(): List<String> = state?.skillGroups?.map { it.kigou } ?: emptyList()
    fun addCons41s(groupKigou: String, shiftKigou: String, l: String, u: String) {
        val st = state ?: return
        logOp("I", "制約追加(スキル群回数): $groupKigou $shiftKigou ${l.trim()}〜${u.trim()}"); mutateConstraints(st.copy(cons41s = st.cons41s + C41Row(groupKigou, shiftKigou, l.trim(), u.trim())))
    }
    fun addCons42s(g1: String, g2: String, s1: String, s2: String) {
        val st = state ?: return
        logOp("I", "制約追加(スキル群組合せ禁止): ${g1}${s1} & ${g2}${s2}"); mutateConstraints(st.copy(cons42s = st.cons42s + C42Row(g1, g2, s1, s2)))
    }

    fun addCons1(day1: String, shiftKigou: String, day2: String) {
        val st = state ?: return
        logOp("I", "制約追加(連勤/休): ${day1.trim()}日に${shiftKigou}${day2.trim()}回以上"); mutateConstraints(st.copy(cons1 = st.cons1 + C1Row(day1.trim(), shiftKigou, day2.trim())))
    }

    fun addCons2(shiftKigou: String, count: String) {
        val st = state ?: return
        logOp("I", "制約追加(cons2): $shiftKigou ${count.trim()}"); mutateConstraints(st.copy(cons2 = st.cons2 + C2Row(shiftKigou, count.trim())))
    }

    fun addCons41(groupKigou: String, shiftKigou: String, l: String, u: String) {
        val st = state ?: return
        logOp("I", "制約追加(群回数): $groupKigou $shiftKigou ${l.trim()}〜${u.trim()}"); mutateConstraints(st.copy(cons41 = st.cons41 + C41Row(groupKigou, shiftKigou, l.trim(), u.trim())))
    }

    /** [回数設定UI] (群,シフト) を一意キーに cons41 を更新-or-追加。l/u 両方空なら削除。重複行は1本に集約。 */
    fun setCons41(groupKigou: String, shiftKigou: String, l: String, u: String) {
        val st = state ?: return
        val ll = l.trim(); val uu = u.trim()
        val rest = st.cons41.filterNot { it.groupKigou == groupKigou && it.shiftKigou == shiftKigou }
        val next = if (ll.isBlank() && uu.isBlank()) rest else rest + C41Row(groupKigou, shiftKigou, ll, uu)
        logOp("I", "制約更新(群回数): $groupKigou $shiftKigou → ${if (ll.isBlank() && uu.isBlank()) "削除" else "${ll.ifBlank { "?" }}〜${uu.ifBlank { "?" }}"}")
        mutateConstraints(st.copy(cons41 = next))
    }

    fun addCons42(g1: String, g2: String, s1: String, s2: String) {
        val st = state ?: return
        logOp("I", "制約追加(群組合せ禁止): ${g1}${s1} & ${g2}${s2}"); mutateConstraints(st.copy(cons42 = st.cons42 + C42Row(g1, g2, s1, s2)))
    }

    fun addCons3(family: String, pattern: List<String>) {
        val st = state ?: return
        // Level Zero loads cons3 by reading day columns until the first blank (truncate at
        // first blank, max 5 days), not by removing all blanks. Match that here.
        val pat = pattern.map { it.trim() }.takeWhile { it.isNotEmpty() }.take(5)
        if (pat.isEmpty()) return
        logOp("I", "制約追加($family): ${pat.joinToString("→")}")
        mutateConstraints(
            when (family) {
                "cons3" -> st.copy(cons3 = st.cons3 + C3Row(pat))
                "cons3n" -> st.copy(cons3n = st.cons3n + C3Row(pat))
                "cons3m" -> st.copy(cons3m = st.cons3m + C3Row(pat))
                "cons3mn" -> st.copy(cons3mn = st.cons3mn + C3Row(pat))
                else -> return
            }
        )
    }

    fun removeConstraint(family: String, index: Int) {
        val st = state ?: return
        logOp("I", "制約削除: $family[$index]")
        fun <T> List<T>.without(i: Int) = filterIndexed { idx, _ -> idx != i }
        mutateConstraints(
            when (family) {
                "cons1" -> st.copy(cons1 = st.cons1.without(index))
                "cons2" -> st.copy(cons2 = st.cons2.without(index))
                "cons3" -> st.copy(cons3 = st.cons3.without(index))
                "cons3n" -> st.copy(cons3n = st.cons3n.without(index))
                "cons3m" -> st.copy(cons3m = st.cons3m.without(index))
                "cons3mn" -> st.copy(cons3mn = st.cons3mn.without(index))
                "cons41" -> st.copy(cons41 = st.cons41.without(index))
                "cons42" -> st.copy(cons42 = st.cons42.without(index))
                "cons41s" -> st.copy(cons41s = st.cons41s.without(index))
                "cons42s" -> st.copy(cons42s = st.cons42s.without(index))
                else -> return
            }
        )
    }

    /** Apply an edited state (constraints changed), then re-run the unified check on the current table. */
    private fun mutateConstraints(newState: MagiState?) {
        val ns = newState ?: return
        state = ns
        _ui.value = _ui.value.copy(constraintsEdited = true)
        refreshCheck()
    }

    // ---- ws1 initial setup ----------------------------------------------------

    /** Snapshot of the ws1 (初期設定) data for the editor. Recomputed per call (cheap). */
    data class Ws1View(
        val startDate: String, val endDate: String, val days: Int, val use2: Boolean,
        val shifts: List<Shift>, val groups: List<Group>, val staff: List<Staff>,
        val groupShift: List<List<Int>>,
        val groupShiftApt: List<List<String>>,
    )

    fun ws1(): Ws1View? {
        val st = state ?: return null
        val days = currentSchedule?.firstOrNull()?.size ?: st.dayCount
        return Ws1View(st.startDate, st.endDate, days, st.use2Patterns, st.shifts, st.groups, st.staff, st.groupShift, st.groupShiftApt)
    }

    private fun applyStructure(ns: MagiState) {
        pushUndo()
        state = ns
        _ui.value = _ui.value.copy(structureEdited = true)
        refreshCheck()
        autoSave()
    }

    /** 構造変更(ns)を適用し、再チェック後に独自の完了メッセージを表示（コンポーネント別取込で使用）。 */
    private fun applyStructureWithMessage(ns: MagiState, doneMessage: String) {
        pushUndo()
        state = ns
        autoSave()
        val sched = currentSchedule?.copy2D()
        if (sched == null) { _ui.value = _ui.value.copy(structureEdited = true, message = doneMessage); return }
        val seq = ++checkSeq
        checkJob?.cancel()
        _ui.value = _ui.value.copy(running = true, structureEdited = true, message = "$doneMessage（違反チェック中…）")
        checkJob = viewModelScope.launch {
            try {
                val r = V6FinalPort.handleCheck(ns, sched)
                if (seq != checkSeq) return@launch
                _ui.value = makeUi(ns, r.schedule, r.report, _ui.value.copy(running = false, message = "$doneMessage｜必須=${r.report.hard} 合計=${r.report.total}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == checkSeq) _ui.value = _ui.value.copy(running = false, message = "$doneMessage（チェック失敗: ${e.message}）")
            }
        }
    }

    /**
     * [ワンタップ修正] 設定の見直しカードの1ボタンで、該当する設定ミスをその場で直す。
     * 画面遷移・スクロール・行探し不要。applyStructure 経由なので Undo 可・自動再診断・自動保存される。
     */
    fun applySettingFix(issue: SettingIssue) {
        val s = state ?: return
        val ns: MagiState? = when (issue.action) {
            SettingFixAction.REMOVE_WISH -> {
                val key = issue.wishKey ?: return
                if (!s.wishes.containsKey(key)) return
                s.copy(wishes = s.wishes - key)
            }
            SettingFixAction.DELETE_DUP_SEQ -> {
                val fam = issue.seqFamily ?: return
                val key = issue.seqKey ?: return
                fun delOne(rows: List<C3Row>): List<C3Row> {
                    var done = false
                    val res = ArrayList<C3Row>(rows.size)
                    for (row in rows) {
                        val joined = row.pattern.filter { it.isNotBlank() }.joinToString("→")
                        if (!done && joined == key) { done = true; continue }
                        res.add(row)
                    }
                    return res
                }
                when (fam) {
                    "c3" -> s.copy(cons3 = delOne(s.cons3))
                    "c3n" -> s.copy(cons3n = delOne(s.cons3n))
                    "c3m" -> s.copy(cons3m = delOne(s.cons3m))
                    "c3mn" -> s.copy(cons3mn = delOne(s.cons3mn))
                    else -> return
                }
            }
            SettingFixAction.ZERO_RANGE_LO, SettingFixAction.CLAMP_RANGE_LO -> {
                val key = issue.rangeKey ?: return
                val cur = s.staffRange[key] ?: Range("", "")
                s.copy(staffRange = s.staffRange + (key to Range(issue.newLo ?: cur.lo, cur.hi)))
            }
            SettingFixAction.CAP_DEMAND -> {
                val k = issue.demandShiftIdx ?: return
                val cap = issue.demandCap ?: return
                val sh = s.shifts.getOrNull(k) ?: return
                val n1 = sh.need1.trim().toIntOrNull()
                val n2 = sh.need2.trim().toIntOrNull()
                val newN1 = if (n1 != null && n1 > cap) cap.toString() else sh.need1
                val newN2 = if (n2 != null && n2 > cap) cap.toString() else sh.need2
                if (newN1 == sh.need1 && newN2 == sh.need2) return
                val list = s.shifts.toMutableList()
                list[k] = sh.copy(need1 = newN1, need2 = newN2)
                s.copy(shifts = list)
            }
            SettingFixAction.NONE -> null
        }
        if (ns != null) applyStructure(ns)
    }

    /**
     * [改善提案] 違反を減らす「1手（変更/交換）」を探索して UI に提示する。
     * focusStaff != null のときはそのスタッフが関わる手だけに絞る（違反タップ起点）。重い処理のため非同期。
     */
    fun findFixSuggestions(focusStaff: Int? = null, focusShift: Int? = null) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val focusName = focusStaff?.let { st.staff.getOrNull(it)?.name } ?: ""
        val snap = sched.copy2D()
        fixJob?.cancel()   // 連続タップ時の前探索を破棄（古い結果で UI を上書きしない）
        _ui.value = _ui.value.copy(fixSearching = true, fixFocusName = focusName)
        fixJob = viewModelScope.launch {
            val list = withContext(Dispatchers.Default) {
                FixSuggester.suggest(st, snap, focusStaff = focusStaff, focusShift = focusShift, maxResults = 8)
            }
            _ui.value = _ui.value.copy(fixSuggestions = list, fixSearching = false, fixFocusName = focusName)
        }
    }

    /** [改善提案] 改善手を1タップで適用（ops のセル代入を一括反映）。Undo 可・自動再診断・自動保存。 */
    fun applyFixSuggestion(s: FixSuggestion) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (s.ops.isEmpty()) return
        for (op in s.ops) {
            if (op.staff !in sched.indices || op.day !in sched[op.staff].indices || op.toShift < 0) return
        }
        pushUndo()
        for (op in s.ops) sched[op.staff][op.day] = op.toShift
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.value = _ui.value.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            fixSuggestions = emptyList(),   // 適用後は候補をクリア（盤面が変わるため再探索を促す）
            message = "改善手を適用: ${s.label}",
        )
        refreshCheck()
    }

    private fun applyStructure(r: Ws1Result) {
        pushUndo()
        state = r.state
        // [review 4b] Ws1Result の schedule を防御コピーして取り込む。Undo は pushUndo() の
        // 事前クローンで保護されるが、currentSchedule を以降 in-place 編集する経路があるため、
        // 全 schedule 取り込み口を copy2D() で統一して別名共有を断つ。
        currentSchedule = r.schedule.copy2D()
        _ui.value = _ui.value.copy(structureEdited = true)
        refreshCheck()
        autoSave()
    }

    fun ws1EditShift(k: Int, name: String, kigou: String, need1: String, need2: String) {
        val st = state ?: return
        applyStructure(Ws1Ops.editShift(st, k, name.trim(), kigou.trim(), need1.trim(), need2.trim()))
    }

    fun ws1EditGroup(g: Int, name: String, kigou: String) {
        val st = state ?: return
        applyStructure(Ws1Ops.editGroup(st, g, name.trim(), kigou.trim()))
    }

    fun ws1EditStaff(i: Int, name: String, groupIdx: Int) {
        val st = state ?: return
        applyStructure(Ws1Ops.editStaff(st, i, name.trim(), groupIdx))
    }

    fun ws1SetGroupShift(g: Int, k: Int, allowed: Boolean) {
        val st = state ?: return
        applyStructure(Ws1Ops.setGroupShift(st, g, k, allowed))
    }

    /** グループ別シフトの適切回数（1人あたり期間内目標。空欄＝目標なし）を設定。 */
    fun ws1SetGroupApt(g: Int, k: Int, value: String) {
        val st = state ?: return
        applyStructure(Ws1Ops.setGroupApt(st, g, k, value))
    }

    fun ws1SetUse2(on: Boolean) {
        val st = state ?: return
        applyStructure(Ws1Ops.setUse2(st, on))
    }

    fun ws1AddShift(name: String, kigou: String, need1: String, need2: String) {
        val st = state ?: return
        if (kigou.isBlank()) return
        applyStructure(Ws1Ops.addShift(st, name.trim(), kigou.trim(), need1.trim(), need2.trim()))
    }

    fun ws1AddGroup(name: String, kigou: String) {
        val st = state ?: return
        if (kigou.isBlank()) return
        applyStructure(Ws1Ops.addGroup(st, name.trim(), kigou.trim()))
    }

    fun ws1AddStaff(name: String, groupIdx: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.addStaff(st, sched, name.trim(), groupIdx))
    }

    fun ws1ResizeDays(newT: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.resizeDays(st, sched, newT))
    }

    /** [対象月の選択] 開始日を指定年月の1日にし、その月の日数へ整える（endDate/希望/必要人数も追従）。 */
    fun setMonth(year: Int, month1to12: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val first = runCatching { java.time.LocalDate.of(year, month1to12, 1) }.getOrNull() ?: return
        logOp("I", "期間変更: ${year}年${month1to12}月"); applyStructure(Ws1Ops.resizeDays(st.copy(startDate = first.toString()), sched, first.lengthOfMonth()))
    }

    /** 現在の開始日から相対的に月を移動（-1=前月 / +1=翌月）。開始日が不明なら端末の今月を起点。 */
    fun shiftMonth(delta: Int) {
        val base = runCatching { java.time.LocalDate.parse(_ui.value.startDate) }.getOrNull()
            ?: java.time.LocalDate.now().withDayOfMonth(1)
        val m = base.withDayOfMonth(1).plusMonths(delta.toLong())
        setMonth(m.year, m.monthValue)
    }

    /** 端末の今月へ設定。 */
    fun setThisMonth() {
        val now = java.time.LocalDate.now()
        setMonth(now.year, now.monthValue)
    }

    // ---- スキルグループ（年次マスター・新C41s/C42s 専用） -----------------------
    fun skillGroups(): List<Group> = state?.skillGroups ?: emptyList()
    fun addSkillGroup(name: String, kigou: String) {
        val st = state ?: return; if (kigou.isBlank()) return
        logOp("I", "スキル区分追加: ${name.trim()}(${kigou.trim()})"); applyStructure(st.copy(skillGroups = st.skillGroups + Group(name.trim(), kigou.trim())))
    }
    fun editSkillGroup(g: Int, name: String, kigou: String) {
        val st = state ?: return
        logOp("I", "スキル区分編集: [$g] → ${name.trim()}(${kigou.trim()})"); applyStructure(st.copy(skillGroups = st.skillGroups.mapIndexed { i, x -> if (i == g) Group(name.trim(), kigou.trim()) else x }))
    }
    fun removeSkillGroup(g: Int) {
        val st = state ?: return
        // 削除した群を参照する職員は 0 へ、後ろの群は1つ詰める。残った cons*s の参照外れは Problem 解決時に無視。
        val newStaff = st.staff.map { s -> val k = s.skillIdx; s.copy(skillIdx = if (k == g) 0 else if (k > g) k - 1 else k) }
        logOp("I", "スキル区分削除: [$g]"); applyStructure(st.copy(skillGroups = st.skillGroups.filterIndexed { i, _ -> i != g }, staff = newStaff))
    }
    fun setStaffSkill(i: Int, skillIdx: Int) {
        val st = state ?: return
        logOp("I", "スキル割当: ${opNm(i)} → 区分[$skillIdx]"); applyStructure(st.copy(staff = st.staff.mapIndexed { idx, s -> if (idx == i) s.copy(skillIdx = skillIdx) else s }))
    }

    fun ws1CanRemoveGroup(g: Int): Boolean = state?.let { Ws1Ops.canRemoveGroup(it, g) } ?: false

    fun ws1RemoveShift(k: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.removeShift(st, sched, k))
    }

    fun ws1RemoveStaff(i: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.removeStaff(st, sched, i))
    }

    fun ws1RemoveGroup(g: Int) {
        val st = state ?: return
        if (!Ws1Ops.canRemoveGroup(st, g)) return
        applyStructure(Ws1Ops.removeGroup(st, g))
    }

    /** Current JSON to export. ws1 edits -> full serialize; constraint edits -> overwrite cons; else schedule only. */
    fun exportJson(): String? {
        val sched = currentSchedule ?: resultSchedule ?: return null
        val st = state
        if (_ui.value.structureEdited && st != null) return StateParser.serialize(st, sched)
        val orig = originalJson ?: return null
        return if (_ui.value.constraintsEdited && st != null) StateParser.exportWithEdits(orig, st, sched)
        else StateParser.exportWithSchedule(orig, sched)
    }

    fun exportCsv(): String? {
        val st = state ?: return null
        val sched = currentSchedule ?: return null
        return ScheduleCsvBridge.build(st, sched)
    }

    /** コンポーネント別エクスポート（取込種別と対。出力→編集→取込で往復可）。 */
    fun exportStaffCsv(): String? = state?.let { com.magi.app.v6.StaffCsvIO.build(it) }
    fun exportWishesCsv(): String? = state?.let { com.magi.app.v6.WishesCsvIO.build(it) }
    fun exportConstraintsCsv(): String? = state?.let { com.magi.app.v6.ConstraintsCsvIO.build(it) }

    /** Operator log as a plain-text file (mirrors the Web "ログ出力"). */
    fun exportLogs(): String? {
        val ops = _ui.value.opLog
        // 出力は全文（非圧縮）。画面表示は圧縮版だが、監査用にはロスレスの rawDiagLogs を使う。
        val logs = rawDiagLogs.ifEmpty { _ui.value.logs }
        if (ops.isEmpty() && logs.isEmpty()) return null
        val ts = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date())
        return buildString {
            append("MAGI ログ (Native)  出力: ").append(ts).append('\n')
            append("状態: ${_ui.value.staff}名/${_ui.value.days}日 ・ 必須=${_ui.value.bestHard} 合計=${_ui.value.totalViolations}\n")
            append("\n==== 操作ログ（新しい順 ${ops.size}件）====\n")
            ops.forEach { append(it).append('\n') }
            append("\n==== 診断ログ（全文 ${logs.size}件）====\n")
            logs.forEach { append(it).append('\n') }
        }
    }

    /** 操作ログ・診断ログ・現在の違反サマリを構造化JSONで書き出す（監査用）。 */
    fun exportLogsJson(): String? {
        if (_ui.value.opLog.isEmpty() && _ui.value.logs.isEmpty()) return null
        val o = org.json.JSONObject()
        o.put("exportedAt", System.currentTimeMillis())
        o.put("staff", _ui.value.staff); o.put("days", _ui.value.days); o.put("shifts", _ui.value.shifts)
        o.put("hard", _ui.value.bestHard); o.put("soft", _ui.value.bestSoft); o.put("total", _ui.value.totalViolations)
        o.put("satisfaction", _ui.value.satisfaction)
        o.put("opLog", org.json.JSONArray().apply { _ui.value.opLog.forEach { put(it) } })
        o.put("diagLog", org.json.JSONArray().apply { rawDiagLogs.ifEmpty { _ui.value.logs }.forEach { put(it) } })
        o.put("breakdown", org.json.JSONObject().apply { _ui.value.breakdown.forEach { (k, v) -> put(k, v) } })
        return o.toString(2)
    }

    /**
     * CSV取込の振り分け。病院などの「勤務表テンプレCSV」(ユニット/スタッフ/凡例を含む完全な1ヶ月表) は
     * 新規データセットとして丸ごと取り込む（[RosterCsvImport]）。それ以外は、既存データへ勤務表だけを
     * 重ねる従来の取込([importCsv])に回す（既存データが無ければ案内のみ）。
     */
    fun importCsvSmart(rawText: String) {
        val text = MojibakeRepair.repair(rawText)
        if (com.magi.app.v6.RosterCsvImport.detect(text)) {
            val st = runCatching { com.magi.app.v6.RosterCsvImport.parse(text) }.getOrNull()
            if (st != null) {
                // 凡例(記号一覧)が無いとシフトが「休」1種のみになり全セルが公休化する。
                // 取り込まず原因をオペレーターに表示する（Excel保存で凡例が消えるケース）。
                if (st.shiftCount <= 1) {
                    _ui.value = _ui.value.copy(message = "CSV取込失敗: シフト記号（凡例）が見つかりません。テンプレCSV末尾の『記号 / 時刻 …』一覧が削除されていないかご確認ください（Excelで開いて保存すると消える場合があります）。元のファイルをそのまま取り込んでください。")
                    logOp("W", "勤務表CSV取込 中止: 凡例なし（シフト${st.shiftCount}種のみ→全公休化を防止）")
                    return
                }
                logOp("I", "勤務表CSVを新規取込: ${st.staffCount}名 / ${st.dayCount}日 / ${st.shiftCount}シフト / ${st.groupCount}ユニット")
                load(StateParser.serialize(st, st.schedule.toIntArray2D()))
                return
            }
            // テンプレらしいが解析不能 → 既存取込にフォールバック（または案内）。
        }
        // ユニット列形式（凡例なし: ユニット,No,役職,氏名,1,2,…）の勤務表CSV → 新規データセットとして取込。
        if (com.magi.app.v6.FlatRosterCsvImport.detect(text)) {
            val st = runCatching { com.magi.app.v6.FlatRosterCsvImport.parse(text) }.getOrNull()
            if (st != null) {
                logOp("I", "勤務表CSV(ユニット列形式)を新規取込: ${st.staffCount}名 / ${st.dayCount}日 / ${st.shiftCount}シフト / ${st.groupCount}ユニット")
                load(StateParser.serialize(st, st.schedule.toIntArray2D()))
                return
            }
            _ui.value = _ui.value.copy(message = "CSV取込失敗: ユニット列形式と判定しましたが解析できませんでした。ヘッダ行（ユニット, No, 役職, 氏名, 1, 2, …）と氏名列をご確認ください。")
            logOp("W", "勤務表CSV(ユニット列形式)取込 失敗: 解析不能")
            return
        }
        if (state == null) {
            _ui.value = _ui.value.copy(message = "このCSVを読み込めませんでした。先に『データを開く』で基本データを読み込むか、勤務表テンプレCSVをご利用ください。")
            return
        }
        importCsv(rawText)
    }

    /**
     * 勤務表テンプレCSVを、利用者の選択（勤務表 or 希望シフト）で新規データとして取り込む。
     *  - asWishes=false: 本表を初期割り当て(勤務表)として読み込む。
     *  - asWishes=true : 本表をスタッフの希望として読み込み、勤務表は空(全公休)で開始（最適化で尊重）。
     */
    fun importRosterAs(rawText: String, asWishes: Boolean) {
        val text = MojibakeRepair.repair(rawText)
        val st = runCatching { com.magi.app.v6.RosterCsvImport.parse(text, asWishes) }.getOrNull()
        if (st == null) {
            _ui.value = _ui.value.copy(message = "このCSVを読み込めませんでした。形式をご確認ください。")
            return
        }
        if (st.shiftCount <= 1) {
            _ui.value = _ui.value.copy(message = "CSV取込失敗: シフト記号（凡例）が見つかりません。テンプレCSV末尾の『記号 / 時刻 …』一覧が削除されていないかご確認ください（Excelで保存すると消える場合があります）。")
            logOp("W", "${if (asWishes) "希望シフト" else "勤務表"}CSV取込 中止: 凡例なし（シフト${st.shiftCount}種のみ）")
            return
        }
        val kind = if (asWishes) "希望シフト" else "勤務表"
        logOp("I", "${kind}として新規取込: ${st.staffCount}名 / ${st.dayCount}日 / ${st.shiftCount}シフト / ${st.groupCount}ユニット" +
            if (asWishes) "（希望${st.wishes.size}件）" else "")
        load(StateParser.serialize(st, st.schedule.toIntArray2D()))
    }

    fun importCsv(rawText: String) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val text = MojibakeRepair.repair(rawText)
        _ui.value = _ui.value.copy(running = true, message = "CSV取込中…")
        job = viewModelScope.launch {
            try {
                if (text !== rawText) logOp("W", "文字化け（二重エンコード）を自動修復してCSVを取り込みました")
                val res = withContext(Dispatchers.Default) { ScheduleCsvBridge.parse(text, st, sched) }
                // 取込失敗の明示: 氏名が1件も一致しなければ適用せず、オペレーターに原因を表示する。
                if (res.matched == 0) {
                    _ui.value = _ui.value.copy(
                        running = false,
                        message = "CSV取込失敗: 一致する職員名がありませんでした（0名）。CSVの1列目の氏名が現在のデータと一致しているか、列レイアウト（氏名, 1日目, 2日目, …）をご確認ください。",
                    )
                    logOp("W", "CSV取込 失敗: 職員名が0件一致のため取込を中止しました（氏名/列レイアウトを確認）")
                    return@launch
                }
                pushUndo()
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                val total = st.staff.size
                val msg = if (res.matched in 1 until total)
                    "CSV取込完了: ${res.matched}/${total}名を更新（${total - res.matched}名は氏名不一致でスキップ）｜必須=${res.report.hard} 合計=${res.report.total}"
                else
                    "CSV取込完了: ${res.matched}名を更新｜必須=${res.report.hard} 合計=${res.report.total}"
                _ui.value = makeUi(state ?: st, res.schedule, res.report, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    message = msg,
                ))
                if (res.matched in 1 until total) {
                    logOp("W", "CSV取込 一部のみ反映: ${res.matched}/${total}名一致（${total - res.matched}名は氏名不一致）")
                }
                logOp("I", "CSV取込 完了 ${res.matched}名一致 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "CSV取込失敗: ${e.message}")
            }
        }
    }

    /** [コンポーネント別取込] スタッフ一覧CSV（氏名,グループ,スキル）。氏名一致で所属群/スキルのみ更新（追加/削除なし）。 */
    fun importStaffCsv(rawText: String) {
        val st = state ?: run { _ui.value = _ui.value.copy(message = "先にデータを開いてください（スタッフ一覧は既存データに重ねます）"); return }
        val text = MojibakeRepair.repair(rawText)
        val res = runCatching { com.magi.app.v6.StaffCsvIO.parse(text, st) }.getOrNull()
        if (res == null) {
            _ui.value = _ui.value.copy(message = "スタッフ一覧の取込失敗: 氏名が一致しませんでした。形式『氏名,グループ,スキル』と、氏名が現在のデータと一致しているかご確認ください。")
            logOp("W", "スタッフ一覧CSV取込 失敗: 一致0件")
            return
        }
        val (ns, matched) = res
        logOp("I", "スタッフ一覧CSV取込: ${matched}名の所属群/スキルを更新")
        applyStructureWithMessage(ns, "スタッフ一覧を取込: ${matched}名の所属群/スキルを更新")
    }

    /** [コンポーネント別取込] 希望シフトCSV（氏名,日,希望シフト）。氏名一致で希望を全置換。 */
    fun importWishesCsv(rawText: String) {
        val st = state ?: run { _ui.value = _ui.value.copy(message = "先にデータを開いてください（希望シフトは既存データに重ねます）"); return }
        val text = MojibakeRepair.repair(rawText)
        val res = runCatching { com.magi.app.v6.WishesCsvIO.parse(text, st) }.getOrNull()
        if (res == null) {
            _ui.value = _ui.value.copy(message = "希望シフトの取込失敗: 取り込める行がありません。形式『氏名,日,希望シフト』と、氏名・シフト記号が一致しているかご確認ください。")
            logOp("W", "希望シフトCSV取込 失敗: 0件")
            return
        }
        val (ns, count) = res
        logOp("I", "希望シフトCSV取込: ${count}件を反映（全置換）")
        applyStructureWithMessage(ns, "希望シフトを取込: ${count}件を反映（既存の希望は置換）")
    }

    /** [コンポーネント別取込] 各制約CSV（種別タグ付き）。制約一式＋個人レンジを置換。 */
    fun importConstraintsCsv(rawText: String) {
        val st = state ?: run { _ui.value = _ui.value.copy(message = "先にデータを開いてください（各制約は既存データに重ねます）"); return }
        val text = MojibakeRepair.repair(rawText)
        val res = runCatching { com.magi.app.v6.ConstraintsCsvIO.parse(text, st) }.getOrNull()
        if (res == null) {
            _ui.value = _ui.value.copy(message = "各制約の取込失敗: 取り込める行がありません。1列目の種別（連勤/禁止連続/群組合せ禁止/個人レンジ 等）をご確認ください。")
            logOp("W", "各制約CSV取込 失敗: 0件")
            return
        }
        val (ns, count) = res
        logOp("I", "各制約CSV取込: ${count}件を反映（制約一式を置換）")
        applyStructureWithMessage(ns, "各制約を取込: ${count}件を反映（既存の制約・個人レンジは置換）")
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }

    /**
     * 診断ログのスパム抑制。RSI/ALNS の各ラウンド・各リスタート・EarlyChain などで同種の行が大量に
     * 出るため、(1) 連続する重複行を「×N」に畳み、(2) それでも上限を超える場合は頭7割＋尾3割に圧縮する。
     * 全文が必要な場合は「ログ出力（テキスト/JSON）」で取得する想定。
     */
    private fun compressDiagLogs(lines: List<String>, cap: Int = 200): List<String> {
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

    private fun makeUi(st: MagiState, schedule: Array<IntArray>, report: ViolationReport, base: UiState): UiState {
        val groupSymbols = st.staff.map { staff -> st.groups.getOrNull(staff.groupIdx)?.kigou ?: "" }
        val v6 = V6PortAnalyzer.analyze(st, schedule, report)
        val sanity = V6SanityPort.build(st, schedule)
        // 人員不足(covU)が残る場合のみ原因診断（どの日/シフトが「充足不可」か「未到達」か）を算出しログに残す。
        val coverageDiag = V6PortAnalyzer.diagnoseCoverage(st, schedule, report).takeIf { it.hasShortage }
        val v6Logs = listOf("[I] LoadDataBit: ${sanity.loadDataBitSummary}") + sanity.warns.map { "[W] SanityCheck: $it" } + sanity.notes.map { "[I] V6Port: $it" } + sanity.duplicateSeqConstraints.take(4).map { "[W] DuplicateSeq: $it" } + sanity.guidance.take(12).map { "[W] 設定ミス: ${it.where} — ${it.problem} → ${it.fix}" } + (coverageDiag?.logLines() ?: emptyList())
        val mappedDiag = report.logs.map { "[${it.level}] ${it.tag}: ${it.message}" }
        // [デバッグ] 制約違反を家族ごとに「場所＋実値(必要/現状, 回数/下限上限, 誰/何日/シフト)」で出力。
        val violationDebug = V6SanityPort.buildViolationDebug(st, schedule, report)
        rawDiagLogs = v6Logs + mappedDiag + violationDebug   // 出力用の全文（非圧縮）。表示は下で圧縮版を使う。
        // 満足度(0-100): 初期からの違反削減率。HARD未解決の間は上限を抑える。
        val initTotal = (base.initHard + base.initSoft).coerceAtLeast(1L)
        val ratio = (1.0 - report.total.toDouble() / initTotal).coerceIn(0.0, 1.0)
        val sat = if (report.hard > 0) (ratio * 55).toInt() else (40 + (ratio * 60).toInt()).coerceIn(0, 100)
        return base.copy(
            staff = st.staffCount,
            days = st.dayCount,
            shifts = st.shiftCount,
            groups = st.groupCount,
            use2 = st.use2Patterns,
            bestHard = report.hard.toLong(),
            bestSoft = report.soft.toLong(),
            totalViolations = report.total,
            weightedScore = report.weightedScore,
            breakdown = emptyBreakdown + report.breakdown,
            violationCells = report.violations,
            needViolations = report.needViolations,
            countViolations = report.countViolations,
            logs = v6Logs + compressDiagLogs(mappedDiag),
            staffNames = st.staff.map { it.name },
            staffGroupSymbols = groupSymbols,
            shiftSymbols = st.shifts.map { it.kigou },
            shiftColorHex = st.shifts.map { V6WebCompat.resolveShiftColor(it.kigou, it.name, st.shiftColors[it.kigou]) },
            shiftTextHex = st.shifts.map { V6WebCompat.pickTextColor(V6WebCompat.resolveShiftColor(it.kigou, it.name, st.shiftColors[it.kigou])) },
            violationColorHex = st.shiftColors["__vio__"] ?: "",
            schedule = schedule.map { it.toList() },
            wishes = st.wishes,
            resultSchedule = resultSchedule?.map { it.toList() } ?: emptyList(),   // [B1] 確定結果(ws6)
            hasResultSnapshot = resultSchedule != null,                            // [B1]
            v6 = v6,
            satisfaction = sat,
            // 研磨の限界: 必須は解決済みだが微調整が残る → 手修正の検討を促す
            polishExhausted = report.hard == 0 && report.total > 0,
            // 解決したらガチャ助言は消す
            copilotHint = if (report.hard == 0) null else base.copilotHint,
            // 担当外など実現不能な希望（Web版の担当外希望警告に相当）
            impossibleWishCount = sanity.impossibleWishes.size,
            // 人員不足(covU)の原因診断（充足不可/充足可能の切り分け）。不足が無ければ null。
            coverageDiag = coverageDiag,
            settingIssues = sanity.guidance,
            startDate = st.startDate,
        )
    }

    private data class LoadedProblem(
        val state: MagiState,
        val schedule: Array<IntArray>,
        val nativeHard: Long,
        val nativeSoft: Long,
        val report: ViolationReport,
    )
}

private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m
