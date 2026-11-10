package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 研磨の候補を差分で採点する（差分が主、全体計算＝チェッカーは採る盤面の確認だけ）。[DeltaEvaluator] を最後に見た盤面に
 * 付いていかせ、次の盤面とは違うセルだけを当てて比較キー（必須件数・weightedScore・合計＝[betterReport] と同じ 3 つ）を読む。
 * キーがチェッカーと一致することは `DeltaScreenTest`・`ReviewOptFixesTest.reportKeyMatchesCheckerOnRealData` が見る。
 * 未割当（[0,K) の外）を含む盤面は差分の対象外で、[key] は null（呼び出し側はチェッカーへ回す）。
 */
internal class DeltaScreen(private val p: Problem) {
    private val de = DeltaEvaluator(p)
    private val mirror = Array(p.S) { IntArray(p.T) }
    private var synced = false

    fun key(board: Array<IntArray>): LongArray? {
        if (!enabled || !inRange(board)) return null
        if (!synced) {
            de.reset(board)
            for (i in 0 until p.S) System.arraycopy(board[i], 0, mirror[i], 0, p.T)
            synced = true
        } else {
            for (i in 0 until p.S) {
                val b = board[i]; val m = mirror[i]
                for (j in 0 until p.T) if (b[j] != m[j]) { de.apply(i, j, b[j]); m[j] = b[j] }
            }
        }
        return de.reportKey()
    }

    /** [board] が [best] より良い（[betterReport] の順）ときだけチェッカーの報告を返す。良くならなければ null（全体計算をしない）。 */
    fun checkIfBetter(state: MagiState, board: Array<IntArray>, best: ViolationReport): ViolationReport? {
        val k = key(board) ?: return UnifiedViolationChecker.check(state, board).takeIf { betterReport(it, best) }
        if (compareReportKeys(k[0], k[1].toDouble(), k[2].toInt(), best.hard.toLong(), best.weightedScore, best.total) >= 0) return null
        return UnifiedViolationChecker.check(state, board)
    }

    private fun inRange(s: Array<IntArray>): Boolean {
        if (s.size < p.S) return false
        for (i in 0 until p.S) {
            val row = s[i]
            if (row.size < p.T) return false
            for (j in 0 until p.T) if (row[j] !in 0 until p.K) return false
        }
        return true
    }

    companion object {
        /** false＝差分を使わず従来どおり全部チェッカーで（同じ結果になることをテストが照合する）。 */
        @Volatile var enabled: Boolean = true

        /** 比較キーだけを持つ報告（[betterReport]・[reportComparator] が読む 3 つ以外は空）。採る盤面は呼び出し側がチェッカーで確かめ直す。 */
        fun keyReport(k: LongArray): ViolationReport = ViolationReport(
            violations = emptyMap(), needViolations = emptyMap(), countViolations = emptyMap(),
            breakdown = emptyMap(), total = k[2].toInt(), hard = k[0].toInt(), soft = 0, weightedScore = k[1].toDouble(),
        )
    }
}
