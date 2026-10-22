package com.magi.app.work

/** 背景実行の投入から反映し終えるまでの編集ガード。`running` は Worker の実行中しか立たないので、その前後の窓を塞ぐ。main からのみ触る。 */
internal class BgEditGuard {
    var pendingRunId: Long = 0L
        private set

    val closed: Boolean get() = pendingRunId != 0L

    fun begin(runId: Long) { pendingRunId = runId }

    /** [runId] の実行が保留中のときだけ開ける（置き換えられた旧実行の終了で新実行のガードを開けない）。 */
    fun end(runId: Long) { if (runId != 0L && runId == pendingRunId) pendingRunId = 0L }

    fun release() { pendingRunId = 0L }
}

/** 評価を別スレッドで待つ前の写し。待つ間に実行の世代・入力・盤面のどれかが変わっていたら結果を当てない。 */
internal class BgApplySnapshot(
    private val runId: Long,
    private val stateRef: Any?,
    private val stateKey: Long,
    board: Array<IntArray>?,
) {
    private val board: Array<IntArray>? = board?.map { it.copyOf() }?.toTypedArray()

    fun unchanged(runId: Long, stateRef: Any?, stateKey: Long, board: Array<IntArray>?): Boolean =
        runId == this.runId && stateRef === this.stateRef && stateKey == this.stateKey &&
            sameBoard(this.board, board)

    private fun sameBoard(a: Array<IntArray>?, b: Array<IntArray>?): Boolean =
        if (a == null || b == null) a == null && b == null else a.contentDeepEquals(b)
}

/** 復元元（結果・途中最良）は、反映した状態の保存が成功してから消す。失敗なら残す。消したら true。 */
internal suspend fun clearAfterSaved(save: suspend () -> Boolean, clear: () -> Unit): Boolean {
    if (!save()) return false
    clear()
    return true
}

/** [3.438.0] 停止経路で実行中を降ろす責任を引き受けるか。片付けでマーカーが消えた後の再判定に頼らない（ReviewFixes3442Test 参照）。 */
internal fun cancelPathReleases(ownedAtCancel: Boolean): Boolean = ownedAtCancel

/** finally で `setRunning(false)` を呼ぶか。`ownsNow` は引き受け済みなら読まない。 */
internal fun shouldReleaseRunning(releasedByMe: Boolean, ownsNow: () -> Boolean): Boolean = releasedByMe || ownsNow()

/** [3.592.0] 非同期の enqueue 失敗を片付けるか。置き換え済み（別の runId が進行中）なら新しい実行に触れない。 */
internal fun asyncEnqueueFailureCleansUp(failed: Boolean, currentRunId: Long, runId: Long): Boolean = failed && currentRunId == runId
