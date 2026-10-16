package com.magi.app.ui

/**
 * [3.694.0] 操作ログの行の組立（純関数）。実行の境界の行（開始・完了・読込・改善手・期間変更・書き出し）に足す
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
}
