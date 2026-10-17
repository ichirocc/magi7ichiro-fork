package com.magi.app.ui

/**
 * [3.694.0] 操作ログの行の組立（純関数）。[3.697.0] 画面の操作の行と「操作の要約（迷いの指標）」もここ。実行の境界の行（開始・完了・読込・改善手・期間変更・書き出し）に足す
 * 期間・人数・指紋・取得元の語と区切り「 ・ 」をここだけで決める（C# `OpLogText` と同じ文字列）。
 * 指紋は既存の [com.magi.app.v6.StateFingerprint]（状態＝`of`、盤面＝`ofBoard`）を 16 桁の 16 進で出す＝新しいハッシュは作らない。
 * 日付は `startDate`/`endDate` の ISO 文字列（年を含む。画面の [DayText] の M/D(曜) とは別）。
 */
internal object OpLogText {
    /** 操作ログのリングの上限（`MagiViewModel.logOp`。値は 3.378.0 以来の 1000 のまま）。 */
    const val OP_LOG_CAP = 1000
    private const val SEP = " ・ "

    fun hex16(v: Long): String = java.lang.Long.toHexString(v).padStart(16, '0')

    fun period(startDate: String, endDate: String, days: Int): String = "$startDate〜$endDate（${days}日）"

    /** 上限を超えて捨てる古い行の数（`logOp` の `while` と同じ式）。 */
    fun droppedBy(size: Int, cap: Int = OP_LOG_CAP): Int = maxOf(0, size - cap)

    /** 書き出しの見出しの直後の注記。「実行#」は含めない（書き出しの見出しだけが実行番号の範囲を言う）。 */
    fun dropNote(startedAt: String, dropped: Int, cap: Int = OP_LOG_CAP): String =
        "※この記録は $startedAt からの分です。上限${cap}件を超えて捨てた古い操作は $dropped 件です。"

    /** 書き出しの状態行の直後の 1 行。データが無ければ「期間=なし」。 */
    fun exportStateLine(startDate: String?, endDate: String?, days: Int, stateKey: Long?, boardKey: Long?): String {
        if (startDate == null || endDate == null || stateKey == null) return "期間=なし"
        return "期間=${period(startDate, endDate, days)}${SEP}状態指紋=${hex16(stateKey)}" + (boardKey?.let { "${SEP}盤面指紋=${hex16(it)}" } ?: "")
    }

    /** 実行の頭（最適化・初期解生成・背景実行）の行。盤面指紋は入力の盤面。 */
    fun startLine(head: String, startDate: String, endDate: String, days: Int, staff: Int, stateKey: Long, boardKey: Long): String =
        "$head${SEP}期間=${period(startDate, endDate, days)}${SEP}職員=${staff}名${SEP}状態指紋=${hex16(stateKey)}${SEP}盤面指紋=${hex16(boardKey)}"

    /** 完了行の末尾に結果の盤面指紋。開始行の盤面指紋（入力）と並べて「同じ入力から出た結果か」を読む（同じ結果かは時間予算で揺れるので保証しない）。 */
    fun withBoard(line: String, boardKey: Long): String = "$line${SEP}盤面指紋=${hex16(boardKey)}"

    fun loadLine(staff: Int, days: Int, shifts: Int, startDate: String, endDate: String, stateKey: Long, boardKey: Long, fromRestore: Boolean): String =
        "読込 ${staff}名/${days}日/${shifts}シフト${SEP}期間=${period(startDate, endDate, days)}${SEP}状態指紋=${hex16(stateKey)}${SEP}盤面指紋=${hex16(boardKey)}" +
            "${SEP}取得元=${if (fromRestore) "起動時の復元" else "利用者の読込"}"

    fun fixLine(label: String, hardBefore: Int, hardAfter: Int, totalBefore: Int, totalAfter: Int,
                startDate: String, endDate: String, days: Int, boardBefore: Long, boardAfter: Long, stateKey: Long): String =
        "改善手を適用: $label（必須 $hardBefore→$hardAfter・合計 $totalBefore→$totalAfter）${SEP}期間=${period(startDate, endDate, days)}" +
            "${SEP}盤面指紋=${hex16(boardBefore)}→${hex16(boardAfter)}${SEP}状態指紋=${hex16(stateKey)}"

    /** 案を出したときの指紋と今の指紋が違って断った行（画面の文言は変えない）。 */
    fun fixMismatchLine(label: String, boardOrigin: Long, boardNow: Long, stateOrigin: Long, stateNow: Long): String =
        "改善手を見送り: $label（案を出したときと違う: 盤面指紋 案=${hex16(boardOrigin)} 今=${hex16(boardNow)}${SEP}状態指紋 案=${hex16(stateOrigin)} 今=${hex16(stateNow)}）"

    fun fixRefusalLine(label: String, reason: String): String = "改善手を見送り: $label（案の出どころが違う: $reason）"

    /** 期間変更の行に、適用しようとする期間（要求）。前の期間は読込と期間の行で追える。拒否されれば直後に W の拒否行が出る。 */
    fun withRequestedPeriod(line: String, startDate: String, endDate: String, days: Int): String = "$line${SEP}要求期間=${period(startDate, endDate, days)}"

    fun strengthLine(label: String, f: SearchStrength.Flags): String =
        "設定変更: 探索の強さ → $label（結合探索を粘り強く=${onOff(f.combineExhaustPairs)}・禁止連続の崩し範囲=${onOff(f.wideC3nBreakDays)}" +
            "・回数連鎖研磨=${onOff(f.countChainPolish)}・公平化/適切回数の他ソフト許容(6%)=${onOff(f.aptFairSoftTolerance)}）"

    /** 実行の開始点で読んだ設定（語は `TuningTelemetry.startSettings`）。実際に使えたかは「設定の効き」が示す。 */
    fun settingsStartLine(words: String): String = "設定の開始時: $words"

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    // ===== [3.697.0] 画面の操作（迷いの指標）。利用者の操作ごとに 1 行＝時刻からしか推定できなかった迷いを行で読む =====
    val TAB_NAMES = listOf("ホーム", "勤務表", "編集", "分析", "設定")

    /** 画面側（Composable）が `vm.logUi` に渡す操作。文字列はここ（[uiLine]）だけが組む。 */
    sealed interface UiOp {
        data class Tab(val tab: Int) : UiOp
        data class TourStart(val total: Int) : UiOp
        /** [at] は最後に見ていた件の添字（0 起点）。 */
        data class TourEnd(val at: Int) : UiOp
        data object PreRunOpen : UiOp
        data object PreRunCheckConditions : UiOp
        data object PreRunClose : UiOp
        data object PreDistOpen : UiOp
        data object PreDistExport : UiOp
        data object PreDistBack : UiOp
        data class GuidedOpen(val dayLabel: String, val shiftSymbol: String, val candidates: Int) : UiOp
        data object GuidedClose : UiOp
    }

    /** 巡回の終了は開始時の総数 [tourTotal] と組にする（前・次ごとには出さない）。 */
    fun uiLine(op: UiOp, tourTotal: Int = 0): String = when (op) {
        is UiOp.Tab -> "画面: ${TAB_NAMES.getOrElse(op.tab) { "#${op.tab}" }}"
        is UiOp.TourStart -> "巡回 開始: 必須違反 ${op.total}か所"
        is UiOp.TourEnd -> "巡回 終了: ${op.at + 1}/$tourTotal"
        UiOp.PreRunOpen -> "作成前の確認: 開く"
        UiOp.PreRunCheckConditions -> "作成前の確認: 条件を確認する"
        UiOp.PreRunClose -> "作成前の確認: 閉じる"
        UiOp.PreDistOpen -> "配布前の確認: 開く"
        UiOp.PreDistExport -> "配布前の確認: 書き出す"
        UiOp.PreDistBack -> "配布前の確認: 戻る"
        is UiOp.GuidedOpen -> "人員不足の手伝い: 開く ${op.dayLabel.substringBefore('(')} ${op.shiftSymbol} 候補 ${op.candidates}人"
        UiOp.GuidedClose -> "人員不足の手伝い: 閉じる（適用なし）"
    }

    /** セル 1 つの手直し。[md] は画面の M/D（`DayText.short`）、空欄は [EMPTY_CELL]。 */
    fun cellChangeLine(name: String, md: String, from: String, to: String): String = "手で変更: $name $md $from→$to"
    const val EMPTY_CELL = "空"
    fun cellsChangeLine(count: Int, symbol: String): String = "手で変更: ${count}セル → ${symbol}（一括）"

    /** 置き換える前の必須（`bestHard`）と下書きの後の必須（「初期解生成 完了」と同じ数）。件数そのまま＝単位の表記ではない。 */
    fun draftReplaceLine(hardBefore: Long, hardAfter: Int): String = "下書きで置き換え（確認済み）: 必須 ${hardBefore}件→${hardAfter}件"

    /** 起点が対象も職員も持たないとき（ホームの「変更案を探す」）。 */
    const val FIX_ORIGIN_ALL = "全体"
    /** [auto]＝作成の完了で自動に走った探索。利用者の操作と分け、迷いの指標（[hesitation]）に数えない。 */
    fun fixSearchStartLine(origin: String, auto: Boolean = false): String = if (auto) "案を探す（自動）: $origin" else "案を探す: $origin"
    fun fixSearchEndLine(count: Int, elapsedMs: Long, auto: Boolean = false): String {
        val head = if (auto) "案（自動）: " else "案: "
        return if (count > 0) "$head${count}件（${sec(elapsedMs)}秒）" else "${head}なし（${sec(elapsedMs)}秒）"
    }
    /** 前回の作成と同じ設定から同じ盤面が出たとき。 */
    fun sameResultLine(boardKey: Long): String = "同じ結果（盤面指紋 ${hex16(boardKey)}）"

    fun previewOpenLine(label: String): String = "変更の確認: 開く $label"
    fun previewDismissLine(label: String): String = "変更の確認: 今は変更しない $label"
    fun consultKeepLine(label: String): String = "相談に残す: $label"
    /** 「改善手を適用」の末尾＝変更の確認を開いてから当てるまで。開いていなければ呼ばない。 */
    fun withSeenSeconds(line: String, elapsedMs: Long): String = "$line${SEP}案を見てから ${sec(elapsedMs)}秒"

    private fun sec(ms: Long): Long = ms / 1000

    const val HESITATION_HEAD = "==== 操作の要約（迷いの指標）===="
    private const val NONE = "—"
    /** 「結果の直後」＝「最適化 完了」からこの時間内の「下書きで置き換え（確認済み）」。 */
    const val RIGHT_AFTER_MS = 60_000L

    /** 操作ログの行から数えた迷いの指標。[lines] は `OpLogEntry.message`（時刻・レベルの接頭辞なし）、[times] は同じ添字の時刻。順は問わない。 */
    data class Hesitation(
        val manualChanges: Int, val undo: Int, val redo: Int,
        val searches: Int, val searchesWithResult: Int, val searchesEmpty: Int, val applied: Int, val notNow: Int, val consulted: Int,
        val autoRuns: Int, val drafts: Int, val draftsRightAfterResult: Int,
        val closedWithoutApply: Int,
        val longestGapMs: Long?, val longestGapFromMs: Long, val longestGapToMs: Long,
        val medianSeenSec: Long?,
    ) {
        fun lines(): List<String> {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN)
            return listOf(
                "手で変更 ${manualChanges}回${SEP}元に戻す ${undo}回${SEP}やり直し ${redo}回",
                "案を探した ${searches}回（案あり ${searchesWithResult}・なし ${searchesEmpty}）${SEP}適用 ${applied}回${SEP}今は変更しない ${notNow}回${SEP}相談に残す ${consulted}回",
                "自動作成 ${autoRuns}回${SEP}下書き ${drafts}回（結果の直後 ${draftsRightAfterResult}回）",
                "確認の画面を閉じた（適用なし）${closedWithoutApply}回",
                if (longestGapMs == null) "最長の空白 $NONE"
                else "最長の空白 ${longestGapMs / 60_000}分（${fmt.format(java.util.Date(longestGapFromMs))}〜${fmt.format(java.util.Date(longestGapToMs))}）",
                "案を見てから適用までの中央値 ${medianSeenSec?.let { "${it}秒" } ?: NONE}",
            )
        }
    }

    private val seenSecRe = Regex("""案を見てから (\d+)秒""")

    fun hesitation(lines: List<String>, times: List<Long>): Hesitation {
        require(lines.size == times.size)
        val order = lines.indices.sortedBy { times[it] }
        val msgs = order.map { lines[it] }; val ts = order.map { times[it] }
        var lastResultAt = Long.MIN_VALUE
        var rightAfter = 0
        for (i in msgs.indices) {
            if (msgs[i].startsWith("最適化 完了")) lastResultAt = ts[i]
            if (msgs[i].startsWith("下書きで置き換え（確認済み）") && lastResultAt != Long.MIN_VALUE && ts[i] - lastResultAt <= RIGHT_AFTER_MS) rightAfter++
        }
        var gap: Long? = null; var gapFrom = 0L; var gapTo = 0L
        for (i in 1 until ts.size) { val d = ts[i] - ts[i - 1]; if (gap == null || d > gap) { gap = d; gapFrom = ts[i - 1]; gapTo = ts[i] } }
        val seen = msgs.filter { it.startsWith("改善手を適用:") }.mapNotNull { seenSecRe.find(it)?.groupValues?.get(1)?.toLongOrNull() }.sorted()
        val median = when {
            seen.isEmpty() -> null
            seen.size % 2 == 1 -> seen[seen.size / 2]
            else -> (seen[seen.size / 2 - 1] + seen[seen.size / 2]) / 2
        }
        fun n(prefix: String) = msgs.count { it.startsWith(prefix) }
        return Hesitation(
            manualChanges = n("手で変更:"), undo = n("元に戻す"), redo = n("やり直し"),
            searches = n("案を探す:"), searchesWithResult = n("案: ") - n("案: なし"), searchesEmpty = n("案: なし"),
            applied = n("改善手を適用:"), notNow = n("変更の確認: 今は変更しない"), consulted = n("相談に残す:"),
            autoRuns = n("最適化 開始"), drafts = n("下書きで置き換え（確認済み）"), draftsRightAfterResult = rightAfter,
            closedWithoutApply = n("人員不足の手伝い: 閉じる") + n("作成前の確認: 閉じる") + n("配布前の確認: 戻る"),
            longestGapMs = gap, longestGapFromMs = gapFrom, longestGapToMs = gapTo,
            medianSeenSec = median,
        )
    }

    fun hesitationSummary(lines: List<String>, times: List<Long>): List<String> = hesitation(lines, times).lines()
}
