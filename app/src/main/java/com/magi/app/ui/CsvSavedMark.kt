package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.v6.ScheduleCsvBridge
import com.magi.app.v6.copy2D

/**
 * [3.644.0/UX-05・仕様 5.4] 勤務表 CSV の「保存済み」の印。書き出した**文字列**そのもの（2026-10-25 まではその 32 ビットのハッシュ＝衝突すると違う内容を保存済みと言い得た）と、その時の state・盤面を持つ。保存の成功は
 * 書き出し時の文脈に結ぶ＝書き出しのあと（ピッカーや書き込みの間）に盤面が変わっても、変わった盤面を「保存済み」と言わない。
 * Android に依存しない（host テスト `CsvSavedMarkTest`）。
 */
class CsvSavedMark(private val buildCsv: (MagiState, Array<IntArray>) -> String = ScheduleCsvBridge::build) {
    /** 盤面は写しを持ち、中身で比べる（64 ビットの指紋だけで「同じ」と言わない）。 */
    private class Ctx(val st: MagiState, val board: Array<IntArray>, val text: String)
    private var exportCtx: Ctx? = null
    private var savedCtx: Ctx? = null
    private var checkKey: Pair<MagiState, Array<IntArray>>? = null
    private var checkText: String? = null

    fun noteExport(st: MagiState, sched: Array<IntArray>, csv: String) { exportCtx = Ctx(st, sched.copy2D(), csv) }

    /** 保存の成功。書き出した文字列の文脈に結ぶ。無ければ今の内容（旧経路の互換）。 */
    fun noteSaved(st: MagiState?, sched: Array<IntArray>?) {
        savedCtx = exportCtx ?: if (st != null && sched != null) Ctx(st, sched.copy2D(), buildCsv(st, sched)) else null
    }

    /** 別のデータや別の月に移ったら、前の CSV の保存は今の内容と関係がない＝印を消す。 */
    fun clear() { exportCtx = null; savedCtx = null }

    /**
     * 今の内容が保存した CSV と同じか（保存の印が無ければ null）。state と盤面が同じなら作らずに同じ、違えば CSV をもう一度作って比べる
     * （設定だけの変更は CSV を変えない）。
     */
    fun savedMatches(st: MagiState?, sched: Array<IntArray>?): Boolean? {
        val c = savedCtx ?: return null
        if (st == null || sched == null) return false
        if (c.st === st && c.board.contentDeepEquals(sched)) return true
        val k = checkKey
        if (k == null || k.first !== st || !k.second.contentDeepEquals(sched)) { checkKey = st to sched.copy2D(); checkText = buildCsv(st, sched) }
        return checkText == c.text
    }

    /** 保存した内容と今の内容を区別する 1 行（保存の印または時刻が無ければ null）。 */
    fun line(at: String?, st: MagiState?, sched: Array<IntArray>?): String? {
        if (at == null) return null
        val m = savedMatches(st, sched) ?: return null
        return if (m) "この内容で勤務表CSVを保存済みです（$at）。"
        else "$at に保存した勤務表CSVは、今の内容と違います（配るなら保存し直してください）。"
    }
}
