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

/**
 * 背景実行の「実行中」の印。Worker は立てた印だけを下ろし、停止（[clear]）は全部を下ろす。
 * 1 つの真偽値だと、停止の後に立った旧実行の印を誰も下ろせず、置き換えで並んだ新旧の片方が下ろすともう片方の分まで消える。
 */
internal class RunningHolds {
    private var next = 0L
    private val held = HashSet<Long>()

    val any: Boolean get() = held.isNotEmpty()

    fun hold(): Long { val t = ++next; held += t; return t }

    fun release(token: Long) { held -= token }

    fun clear() { held.clear() }
}

/** [3.592.0] 非同期の enqueue 失敗を片付けるか。置き換え済み（別の runId が進行中）なら新しい実行に触れない。 */
internal fun asyncEnqueueFailureCleansUp(failed: Boolean, currentRunId: Long, runId: Long): Boolean = failed && currentRunId == runId
