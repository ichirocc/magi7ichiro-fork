package com.magi.app.ui

import androidx.lifecycle.viewModelScope
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.FixSuggester
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.copy2D
import com.magi.app.v6.restShiftIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// [MagiViewModel] の直し方（変更案の探索・適用・通知の元に戻す）。本体から extension 関数として抽出（全面改修 P6a）。
//   指紋と探索ジョブの状態（fixBoardKey/fixStateKey/fixSearchKey/fixJob/fixSeq）は本体に残す。

/**
 * [改善提案] 違反を減らす「1手（変更/交換）」を探索して UI に提示する。
 * focusStaff != null のときはそのスタッフが関わる手だけに絞る（違反タップ起点）。重い処理のため非同期。
 */
fun MagiViewModel.findFixSuggestions(focusStaff: Int? = null, focusShift: Int? = null, focusKey: String = "", exceptStaff: Int? = null, day: Int? = null, quick: Boolean = false, allDays: Boolean = false, auto: Boolean = false) {
    if (auto && _ui.value.agentRunning) return   // 代行が当て終えた盤面で、ホームの自動の探索がもう一度走る
    val st = state ?: return
    val sched = currentSchedule ?: return
    val focusName = focusStaff?.let { st.staff.getOrNull(it)?.name } ?: ""
    val snap = sched.copy2D()
    fixBoardKey = boardKey(snap)
    fixStateKey = stateKey(st)
    // [3.392.0] 旧実装は catch が1つも無く、探索が例外で終わると `fixSearching=true` が**永久に残った**
    //   （「直し方を探す」が探索中のまま戻らない）。3.382.0 が長い4経路で潰した「旗を立てて確実に戻さない」
    //   型の残り。`seq` を持つのは refreshCheck と同じ理由＝`cancel()` は非同期なので、後続の探索が
    //   `fixSearching=true` を立てた**後**に古いジョブの後始末が走ると、新しい探索の旗を消してしまう。
    val seq = ++fixSeq
    fixJob?.cancel()   // 連続タップ時の前探索を破棄（古い結果で UI を上書きしない）
    fixSearchKey = focusKey
    _ui.update { it.copy(fixSearching = true, fixFocusName = focusName, fixDoneKey = "", fixFailedKey = "", fixCancelledKey = "") }
    fixJob = viewModelScope.launch {
        try {
            // セルのシートは締切 3 秒（手は 1 件でよい。8 秒待たせると答えが無いときも長い＝3.646.0 実機報告）。ホーム・分析は 8 秒。
            val deadline = if (quick) FIX_SEARCH_QUICK_MS else FIX_SEARCH_MS
            var timedOut = false
            // 集計・日の印から来た依頼は、見ている問題（回数・その日の人数）を変える案を先に（2026-10-14 利用者レビュー:
            //   11/3 の不足に別の日の案だけが並んだ）。広めに集めて並べ替え、上位 8 件を返す。
            val cv0 = conditionsViewOf(st, cachedProblem(st))
            val target = if (exceptStaff == null) panelTarget(_ui.value, cv0, FixFocus(focusStaff, focusShift, day, allDays = allDays)) else null
            // [3.697.0] 起点は画面の見出しと同じ語（対象＝fixTargetLabel、職員＝氏名、どちらも無ければ全体）。
            val searchStartMs = System.currentTimeMillis()
            val origin = target?.let { fixTargetLabel(it, opSy(it.shift), st.startDate) } ?: focusName.ifBlank { OpLogText.FIX_ORIGIN_ALL }
            logOp("I", OpLogText.fixSearchStartLine(origin, auto))
            var tiers: FixTierView? = null
            val list = withContext(Dispatchers.Default) {
                (if (exceptStaff != null && day != null) {
                    fixesByOthers(FixSuggester.suggest(st, snap, focusStaff = null, focusShift = focusShift, maxResults = 40, deadlineMs = deadline, ejectionChain = true), day, exceptStaff).take(8)
                } else if (target != null) {
                    // 入り口の範囲の中の 1 セルの変更を先に評価し、探索の案と合わせて段に分ける。
                    val entry = entryMoves(st, snap, target, restShiftIndex(st))
                    val found = rankForTarget(FixSuggester.suggest(st, snap, focusStaff = focusStaff, focusShift = focusShift, maxResults = 40, deadlineMs = deadline, ejectionChain = true),
                        snap.map { it.toList() }, target).take(8)
                    classifyTiers(st, snap, target, entry, found, cv0::needCellLimits).copy(key = focusKey).also { tiers = it }.items.map { it.sug }
                } else FixSuggester.suggest(st, snap, focusStaff = focusStaff, focusShift = focusShift, maxResults = 8, deadlineMs = deadline, ejectionChain = true))
                    .also { timedOut = FixSuggester.lastTimedOut }
            }
            if (seq != fixSeq) return@launch   // 後続の探索が始まっている＝古い結果で上書きしない
            // 盤面を差し替えるジョブの最中は書き戻さず探し直しもしない（完了後の盤面で探し直す）。
            if (optimizeInFlight()) { _ui.update { it.copy(fixSearching = false) }; return@launch }
            // [思考誘導S0/レビュー] 探索中に盤面か設定が変わったら（元に戻す・セル編集・実行の完了など）、古い盤面の
            //   結果と「探索済み」を書き戻さず、今の盤面で探し直す。
            val curSched = currentSchedule; val curSt = state
            if (curSched == null || curSt == null || boardKey(curSched) != boardKey(snap) || stateKey(curSt) != stateKey(st)) {
                _ui.update { it.copy(fixSearching = false) }
                if (curSched != null && curSt != null) findFixSuggestions(focusStaff, focusShift, focusKey, exceptStaff, day, quick, allDays, auto)
                return@launch
            }
            // [3.716.0] 鍵に quick（セルのシートか全面か）を含め、自動の探索は数えない（開いただけで「続けて」にしない）。
            emptyStreak = nextEmptySearchStreak(emptyStreak, "$focusKey|$origin|$quick", boardKey(snap), stateKey(st), list.size, auto)
            val repeatKey = if ((emptyStreak?.count ?: 0) >= 2) focusKey else null
            _ui.update { it.copy(fixSuggestions = list, fixTiers = tiers, fixSearching = false, fixTimedOut = timedOut, fixFocusName = focusName, fixSearched = focusName.isBlank(), fixDoneKey = focusKey,
                fixEmptyRepeatKey = repeatKey) }
            logOp("I", OpLogText.fixSearchEndLine(list.size, System.currentTimeMillis() - searchStartMs, auto))
            // [S6 §8] 全体の 1 手探索が必須を減らす候補なしで終わったら、背景で設定の壁を探す。
            if (focusName.isBlank() && list.none { it.deltaHard < 0 } && _ui.value.bestHard > 0) startRelaxTrial()
        } catch (e: CancellationException) {
            if (seq == fixSeq) _ui.update { it.copy(fixSearching = false) }
            throw e
        } catch (e: Throwable) {
            logOp("W", "直し方の探索に失敗: ${e.javaClass.simpleName}: ${e.message}")
            if (seq == fixSeq) _ui.update { it.copy(messageIsError = true, fixSearching = false, fixFailedKey = focusKey, message = "${if (focusName.isBlank()) "" else "${focusName}さんの"}直し方を探せませんでした（${failureWords(e, FailureKind.ENGINE)}）。もう一度お試しください") }
        }
    }
}

/** 通知の「元に戻す」。その操作がまだ元に戻すの先頭にあるときだけ戻す（後の別の操作は戻さない）。 */
fun MagiViewModel.undoNotice(n: OpNotice) {
    if (noticeUndoApplies(undoStack.lastOrNull()?.serial, n.undoSerial)) undo()
    else _ui.update { it.copy(messageIsError = true, message = "このあとに別の操作があるため、通知からは戻せません（「元に戻す」ボタンで順に戻せます）") }
}

fun MagiViewModel.clearOpNotice(id: Long) {
    _ui.update { if (it.opNotice?.id == id) it.copy(opNotice = null) else it }
}

/** 走行中の直し方の探索を捨てる（世代を進めるので、完了間際の結果も書き戻さない）。 */
fun MagiViewModel.cancelFixSearch() {
    ++fixSeq
    fixJob?.cancel()
    fixJob = null
    if (_ui.value.fixSearching) _ui.update { it.copy(fixSearching = false, fixCancelledKey = fixSearchKey) }
}

/**
 * [改善提案] 改善手を適用（ops のセル代入を一括反映）。Undo 可・自動再診断・自動保存。適用したら true（断ったら false、理由は message）。
 * [fixTarget] の持ち回り: 枠や問題を見て出した案（案の一覧・不足枠の 1 手・相談に積んだ案）は対象を運び、その数が減らない手は断る。
 * ホームと分析タブの「この手を使う」は全体の研磨の案＝[globalPolish]（対象を持たず、全体の評価と [com.magi.app.v6.FixApplyGate] だけで判定）。
 * 対象が null で [globalPolish] も無い案は断る（呼び手がどちらかを明示する）。
 * [fromPreview] の案は案を出したときの指紋が必須＝[originBoard] が 0 なら断る（全体の指紋で代わりに通さない）。
 */
fun MagiViewModel.applyFixSuggestion(s: FixSuggestion, originBoard: Long = fixBoardKey, originState: Long = fixStateKey, focusKey: String? = null, fixTarget: FixTarget? = null,
                       fromPreview: Boolean = false, globalPolish: Boolean = false): Boolean {
    val st = state ?: return false
    if (optimizeInFlight()) { _ui.update { it.copy(message = busyEditMessage(), messageIsError = true) }; return false }
    val sched = currentSchedule ?: return false
    if (s.ops.isEmpty()) return false
    (fixOriginRefusal(originBoard, fromPreview) ?: fixTargetRefusal(fixTarget, globalPolish))?.let { msg ->
        logOp("W", OpLogText.fixRefusalLine(s.label, msg))   // [3.694.0] 断った案も操作ログに残す（画面の文言は不変、G2）
        _ui.update { it.copy(messageIsError = true, message = msg) }; return false
    }
    // [3.475.0/論理監査] 提案は計算時の盤面/設定に対する差分。旧: 指紋を持たず、その後のセル編集・
    //   元に戻す・別データ読込・職員削除のあとでも同じ ops をそのまま書き込んでいた（staff/day/toShift が
    //   別の実体を指す）。一致しなければ適用せず再探索を促す。toShift の上限（K）も未検査だった。
    //   [3.650.0] 照合は案を出したときの指紋（相談に積んだ案は自分の指紋を運ぶ＝あとの探索で上書きされた全体の指紋で通さない）。
    val boardNow = boardKey(sched); val stateNow = stateKey(st)   // [3.694.0] 照合と「適用」「見送り」の行が同じ値を使う
    if (originBoard != 0L && (originBoard != boardNow || originState != stateNow)) {
        logOp("W", OpLogText.fixMismatchLine(s.label, originBoard, boardNow, originState, stateNow))
        _ui.update { it.copy(messageIsError = true, fixSuggestions = emptyList(), fixTiers = null, fixSearched = false, stalledHardFamilies = emptyList(),
            message = "勤務表か設定が変わったため、この提案は適用できません。「変更案を探す」をもう一度押してください") }
        return false
    }
    // 見ている問題の数が減らない案は、全体の評価が良くなっても当てない（統合仕様 INV-03。案の一覧の主一覧と同じ判定）。
    if (!improvesTarget(fixTarget, sched.map { it.toList() }, s.ops)) {
        logOp("W", "改善手を見送り: ${s.label}（見ている問題が減らない）")
        _ui.update { it.copy(messageIsError = true, fixSuggestions = emptyList(), fixTiers = null, fixSearched = false, stalledHardFamilies = emptyList(),
            message = NOT_IMPROVING_TARGET_MESSAGE) }
        return false
    }
    // [3.509.4/自動化方針] 適用直前に仮盤面で完全再評価し、辞書式で改善しない・固定を崩す提案は反映しない。
    val gate = com.magi.app.v6.FixApplyGate.apply(st, sched, s.ops)
    if (gate !is com.magi.app.v6.FixApplyGate.Outcome.Applied) {
        val why = (gate as com.magi.app.v6.FixApplyGate.Outcome.Rejected).reason
        logOp("W", "改善手を見送り: ${s.label}（$why）")
        _ui.update { it.copy(messageIsError = true, fixSuggestions = emptyList(), fixTiers = null, fixSearched = false, stalledHardFamilies = emptyList(),
            message = "この提案は見送りました（$why）。「変更案を探す」で探し直してください") }
        return false
    }
    pushUndo(s.label)
    val applied = gate.schedule
    commitFixBoard(st, applied)
    autoSave()
    val seenMs = if (fromPreview && chainPreviewOpenedAtMs > 0L) System.currentTimeMillis() - chainPreviewOpenedAtMs else null
    chainPreviewOpenedAtMs = 0L
    logOp("I", OpLogText.fixLine(s.label, gate.before.hard, gate.after.hard, gate.before.total, gate.after.total,
        st.startDate, st.endDate, st.dayCount, boardNow, boardKey(applied), stateNow).let { l -> seenMs?.let { OpLogText.withSeenSeconds(l, it) } ?: l })
    _ui.update { it.copy(
        messageIsError = false,
        hasResult = true,
        relaxedBoard = false, engineRan = false, ranBeforeEdit = it.engineRan || it.ranBeforeEdit,   // [3.475.0] 提案の適用は手操作扱い（局所1手のみ、フルの計算ではない）
        schedule = applied.map { it.toList() },
        fixSuggestions = emptyList(), fixTiers = null, fixSearched = false, stalledHardFamilies = emptyList(),   // 適用後は候補をクリア（盤面が変わるため再探索を促す）
        appliedFix = focusKey?.let { k -> AppliedFix(k, s.label, s.ops, sched.map { r -> r.toList() }, applied.map { r -> r.toList() }, fixTarget,
            gate.before.hard, gate.after.hard, gate.before.soft, gate.after.soft, gate.before.breakdown, gate.after.breakdown) },
        fixOutcome = FixOutcome(fixOutcomeText(fixDisplayLabel(s.label), gate.before.hard, gate.after.hard, unitTotals(gate.before.breakdown, com.magi.app.v6.MirrorKeys.soft) ?: "なし", unitTotals(gate.after.breakdown, com.magi.app.v6.MirrorKeys.soft) ?: "なし", gate.after.breakdown, gate.before.breakdown) + fixResultDetail(gate.before, gate.after) +
            (if (monthEdgeTouched(s.ops.map { it.day }, applied.firstOrNull()?.size ?: 0, maxPatternLen(st))) "$MONTH_EDGE_NOTE。" else "")),
    ) }
    // 通知は 2 行まで＝必須が動いた手は必須だけ、動かない手は合計だけを添える（両方の数はホームの結果の行とログ）。
    postOpNotice("${fixDisplayLabel(s.label)} を適用しました（" + (if (gate.before.hard != gate.after.hard) "必須違反 ${gate.before.hard}→${gate.after.hard}"
        else "要調整 ${unitTotals(gate.before.breakdown, com.magi.app.v6.MirrorKeys.soft) ?: "なし"}→${unitTotals(gate.after.breakdown, com.magi.app.v6.MirrorKeys.soft) ?: "なし"}") + "）")
    fixOutcomeCtx = state?.let { MagiViewModel.TrialCtx(it, boardKey(applied)) }
    refreshCheck()
    return true
}
