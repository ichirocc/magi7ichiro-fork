package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * CP-SAT（OR-Tools）の JNI 境界（`app/src/main/cpp/magi_cpsat_jni.inc`、設計 `docs/cpsat_formal.md`）。
 * OR-Tools を組み込んだビルドの .so だけが入口を持つ。無いビルド・JVM のテストでは [available]=false で、作成は従来どおり動く。
 */
object CpsatBridge {
    val available: Boolean by lazy {
        NativeBridge.available && runCatching { nativeCpsatAvailable() }.getOrDefault(false)
    }

    @JvmStatic external fun nativeCpsatAvailable(): Boolean
    @JvmStatic external fun nativeCpsatCreate(problemHandle: Long): Long
    @JvmStatic external fun nativeCpsatDestroy(handle: Long)
    @JvmStatic external fun nativeCpsatStop(handle: Long)
    /** 戻り値 [status, hard, weighted, hardBound, weightedBound, hardProven, wallMillis]。終わるまで戻らない。 */
    @JvmStatic external fun nativeCpsatSolve(handle: Long, hint: IntArray, maxSeconds: Double, workers: Int, seed: Long, hardCap: Long): LongArray
    /** 戻り値 [version, hard, weighted, weightedBound, hardBound, hardProven]。version が [knownVersion] と違えば [out] に最良の盤面。 */
    @JvmStatic external fun nativeCpsatPoll(handle: Long, knownVersion: Long, out: IntArray): LongArray
}

/**
 * 作成と併走する CP-SAT の 1 回分。[start] で別スレッドに解かせ、[poll] で最良と下限を読み、[finish] で止めて結果を返す。
 * 下限 [weightedBound] は fair を 0 とした緩和の下限＝チェッカーの weightedScore（fair 込み）以下が常に成り立つ。
 */
class CpsatCoRun(private val state: MagiState, private val problem: Problem) {
    data class Snapshot(
        val board: Array<IntArray>?, val hard: Long, val weighted: Long,
        val weightedBound: Long, val hardBound: Long, val hardProven: Boolean,
    )

    private var problemHandle = 0L
    private var job = 0L
    private var thread: Thread? = null
    private var version = -1L
    private val buf = IntArray(problem.S * problem.T)
    @Volatile private var last: Snapshot = Snapshot(null, -1, -1, -1, -1, false)

    /** 解き始める。使えないビルド・作れない問題では false（作成は CP-SAT なしで続ける）。 */
    fun start(hint: Array<IntArray>, maxSeconds: Double, workers: Int, seed: Long): Boolean {
        if (!CpsatBridge.available) return false
        problemHandle = runCatching { NativeEval.createHandle(problem) }.getOrDefault(0L)
        if (problemHandle == 0L) return false
        job = CpsatBridge.nativeCpsatCreate(problemHandle)
        if (job == 0L) { NativeBridge.nativeDestroyProblem(problemHandle); problemHandle = 0L; return false }
        val flat = NativeEval.flatten(hint)
        thread = Thread({ runCatching { CpsatBridge.nativeCpsatSolve(job, flat, maxSeconds, workers, seed, -1L) } }, "magi-cpsat").also {
            it.isDaemon = true; it.start()
        }
        return true
    }

    /** いまの最良と下限（盤面は更新があったときだけ写す）。 */
    fun poll(): Snapshot {
        if (job == 0L) return last
        val v = CpsatBridge.nativeCpsatPoll(job, version, buf)
        val board = if (v[0] != version && v[1] >= 0) NativeEval.unflatten(buf.copyOf(), problem.S, problem.T) else last.board
        version = v[0]
        last = Snapshot(board, v[1], v[2], v[3], v[4], v[5] == 1L)
        return last
    }

    /** 止めて待ち、最後の値を返す。ハンドルはここで解放する。 */
    fun finish(): Snapshot {
        if (job == 0L) return last
        CpsatBridge.nativeCpsatStop(job)
        thread?.join(10_000L)
        val s = poll()
        if (thread?.isAlive != true) {
            CpsatBridge.nativeCpsatDestroy(job)
            NativeBridge.nativeDestroyProblem(problemHandle)
        }
        job = 0L; problemHandle = 0L
        return s
    }

    companion object {
        /** ギャップ（実際の最良と下限の差の割合）。下限が無ければ null。 */
        fun gap(bestWeighted: Double, bound: Long): Double? =
            if (bound < 0 || bestWeighted <= 0.0) null else ((bestWeighted - bound) / bestWeighted).coerceAtLeast(0.0)
    }
}
