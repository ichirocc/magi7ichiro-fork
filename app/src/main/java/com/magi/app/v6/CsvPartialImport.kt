package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 引用符が閉じていない勤務表CSV（重ね合わせ取込）を、読めたところまで取り込むかの判断。
 * 画面も ViewModel も持たない純粋な関数だけ（ホストでテストできる）。保留の保持と適用は ViewModel 側。
 */
object CsvPartialImport {
    const val NOTHING_READABLE = "取り込める行がありませんでした（引用符が閉じていません）"
    const val STALE = "盤面が変わったため取込をやめました。もう一度取り込んでください"
    const val CANCELLED = "取込をやめました"
    const val CONFIRM_LABEL = "この部分だけ取り込む"
    const val CANCEL_LABEL = "やめる"

    const val APPLIED_WARNING = "｜⚠ 引用符（\"）が閉じていません。ここから後ろの行は読めていません"

    fun promptText(endLine: Int, matched: Int): String =
        "CSV の ${endLine}行目までは読めました（${matched} 名分）。その先は引用符が閉じていないため読めません。この部分だけ取り込みますか？"

    /** [records]＝改行で終わった行の数、[endLine]＝その最後の行の最終物理行（1 始まり、0＝無し）、[prefixText]＝読めた部分の本文（閉じていれば全文）。 */
    class Readable(val unclosedQuote: Boolean, val records: Int, val endLine: Int, val prefixText: String)

    fun readable(raw: String): Readable {
        val p = parseCsvFull(raw)
        return Readable(p.unclosedQuote, p.readableRows, p.readableEndLine, raw.substring(0, p.readableEndOffset))
    }

    sealed interface Verdict {
        /** 引用符は閉じている＝従来の取込へ。 */
        object WellFormed : Verdict

        /** 引用符が閉じておらず、読めた部分に職員の行が1つも無い＝何も変えずに断る。 */
        object NothingReadable : Verdict

        /** 読めた部分だけの結果（吸い込まれた行は含まない）。確認のあとにそのまま適用できる。 */
        class Ask(val result: ScheduleRunResult, val endLine: Int) : Verdict {
            val matched: Int get() = result.matched
            val prompt: String get() = promptText(endLine, matched)
        }
    }

    fun judge(raw: String, state: MagiState, base: Array<IntArray>): Verdict {
        val r = readable(raw)
        if (!r.unclosedQuote) return Verdict.WellFormed
        val res = ScheduleCsvBridge.parse(r.prefixText, state, base)
        if (res.matched <= 0) return Verdict.NothingReadable
        return Verdict.Ask(res, r.endLine)
    }

    /** 確認待ちの取込。[stateKey]/[boardKey] は結果を作った時点の設定と盤面の指紋。 */
    class Pending(val ask: Verdict.Ask, val stateKey: Long, val boardKey: Long)

    sealed interface Resolution {
        class Apply(val pending: Pending) : Resolution
        object Stale : Resolution
        object NoPending : Resolution
    }

    /** 「この部分だけ取り込む」が押されたとき、いまの指紋が保留の作成時と同じなら適用、違えば捨てる。 */
    fun resolve(pending: Pending?, stateKeyNow: Long, boardKeyNow: Long): Resolution = when {
        pending == null -> Resolution.NoPending
        pending.stateKey != stateKeyNow || pending.boardKey != boardKeyNow -> Resolution.Stale
        else -> Resolution.Apply(pending)
    }
}
