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
    /** 一部だけ解き直す。戻り値 [iters, accepted, optimal, hard, weighted, startWeighted, wallMillis]。終わるまで戻らない。最良の盤面は [nativeCpsatPoll]。 */
    @JvmStatic external fun nativeCpsatImprove(handle: Long, start: IntArray, maxSeconds: Double, threads: Int, seed: Long): LongArray
    /** 戻り値 [version, hard, weighted, weightedBound, hardBound, hardProven]。version が [knownVersion] と違えば [out] に最良の盤面。 */
    @JvmStatic external fun nativeCpsatPoll(handle: Long, knownVersion: Long, out: IntArray): LongArray
    /** 戻り値 [核の数, 各核: minimal, 件数, 件数×(kind, i, j, k, rule)]。[HardCauses.decode] で読む。 */
    @JvmStatic external fun nativeCpsatExplain(
        problemHandle: Long, input: IntArray, maxCores: Int, secondsPerSolve: Double, workers: Int, totalSeconds: Double,
    ): IntArray
}

/**
 * 必須違反の原因（S4、`magi_cpsat::HardExplainer`）。どの核も「この中の 1 件を緩めないと必須違反は消えない」の証明で、
 * 核どうしは互いに素＝核の数は必須の件数の下限。[Core.minimal] はどの 1 件を外しても成り立つと確かめた核。
 */
object HardCauses {
    enum class Kind { WISH, PIN, NEED, FORBIDDEN_SEQ, OUTSIDE, DAY_CAP }
    /** [staff]・[day]・[shift] は無ければ -1。NEED の [rule] は必要人数、FORBIDDEN_SEQ の [rule] は禁止の並びの行。 */
    data class Cause(val kind: Kind, val staff: Int, val day: Int, val shift: Int, val rule: Int)
    data class Core(val causes: List<Cause>, val minimal: Boolean)

    fun decode(flat: IntArray): List<Core> {
        if (flat.isEmpty()) return emptyList()
        val out = ArrayList<Core>(flat[0])
        var x = 1
        repeat(flat[0]) {
            val minimal = flat[x++] == 1
            val n = flat[x++]
            val causes = List(n) {
                val c = Cause(Kind.entries[flat[x]], flat[x + 1], flat[x + 2], flat[x + 3], flat[x + 4]); x += 5; c
            }
            out += Core(causes, minimal)
        }
        return out
    }

    /** [board] を入力の値として原因を探す。使えないビルドでは null。 */
    fun explain(problem: Problem, board: Array<IntArray>, maxCores: Int = 10, totalSeconds: Double = 20.0): List<Core>? {
        if (!CpsatBridge.available) return null
        val handle = runCatching { NativeEval.createHandle(problem) }.getOrDefault(0L)
        if (handle == 0L) return null
        try {
            return decode(CpsatBridge.nativeCpsatExplain(handle, NativeEval.flatten(board), maxCores, 5.0, 2, totalSeconds))
        } finally {
            NativeBridge.nativeDestroyProblem(handle)
        }
    }
}

/**
 * 作成と併走する CP-SAT の 1 回分。[start] で別スレッドに解かせ、[poll] で最良と下限を読み、[finish] で止めて結果を返す。
 * 下限 [weightedBound] は fair を実際の値以下の近似で数えた下限＝チェッカーの weightedScore（fair 込み）以下が常に成り立つ。
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

/**
 * 探索が頭打ちになったあとの解き直し（`magi_cpsat::improve`、`docs/cpsat_formal.md`「解き直し」）。探索の最良の一部（職員数人・数日の窓）だけを
 * 自由にした小さい問題を、[threads] 本が 1 スレッドずつ CP-SAT で解き、エンジンの採点で良くなった盤面だけを採る。
 * 返す盤面は開始の盤面より悪くならない。呼び出し側はチェッカーで採点し直してから使う。
 */
/** 解き直しの進捗を知らせる間隔。 */
const val RESOLVE_TICK_MS = 15_000L

object CpsatResolve {
    /** [board]＝最良の盤面（改善なしなら開始の盤面と同じ内容）、[parts]＝解いた部分の数、[accepted]＝採った数、[optimal]＝最適まで解けた数。 */
    class Outcome(val board: Array<IntArray>, val parts: Long, val accepted: Long, val optimal: Long, val wallMs: Long)

    /** [shouldStop] が真になるか [maxSeconds] が過ぎるまで解き直す。使えないビルド・作れない問題では null。 */
    /** 直前の [run] が `stallStopMs` で止まったか（ログ用）。 */
    @Volatile var stalledOut: Boolean = false
        private set

    /** [onTick] は [RESOLVE_TICK_MS] ごとに、いまの最良（必須の件数・重み付き）で呼ぶ＝画面と操作ログの進捗。 */
    fun run(problem: Problem, start: Array<IntArray>, maxSeconds: Double, threads: Int, seed: Long,
            onTick: ((hard: Long, weighted: Long) -> Unit)? = null, stallStopMs: Long = 0L, shouldStop: () -> Boolean): Outcome? {
        if (!CpsatBridge.available || maxSeconds <= 0.0) return null
        val problemHandle = runCatching { NativeEval.createHandle(problem) }.getOrDefault(0L)
        if (problemHandle == 0L) return null
        val job = CpsatBridge.nativeCpsatCreate(problemHandle)
        if (job == 0L) { NativeBridge.nativeDestroyProblem(problemHandle); return null }
        val flat = NativeEval.flatten(start)
        var stats: LongArray? = null
        stalledOut = false
        val thread = Thread({ stats = runCatching { CpsatBridge.nativeCpsatImprove(job, flat, maxSeconds, threads, seed) }.getOrNull() }, "magi-cpsat-resolve")
            .also { it.isDaemon = true; it.start() }
        var nextTick = EngineClock.nowMs() + RESOLVE_TICK_MS
        val noBoard = IntArray(0)
        var lastVersion = Long.MIN_VALUE
        var lastChangeMs = EngineClock.nowMs()
        while (thread.isAlive) {
            if (shouldStop()) CpsatBridge.nativeCpsatStop(job)
            thread.join(200L)
            // [stallStopMs] 最良が更新されない時間がこれを超えたら止める（0 は止めない）。
            if (stallStopMs > 0L && thread.isAlive) {
                val ver = CpsatBridge.nativeCpsatPoll(job, -1L, noBoard).firstOrNull() ?: lastVersion
                val now = EngineClock.nowMs()
                if (ver != lastVersion) { lastVersion = ver; lastChangeMs = now }
                else if (now - lastChangeMs >= stallStopMs) { stalledOut = true; CpsatBridge.nativeCpsatStop(job) }
            }
            if (onTick != null && thread.isAlive && EngineClock.nowMs() >= nextTick) {
                nextTick += RESOLVE_TICK_MS
                val v = CpsatBridge.nativeCpsatPoll(job, -1L, noBoard)
                if (v.size >= 3 && v[1] >= 0) onTick(v[1], v[2])
            }
        }
        val buf = IntArray(problem.S * problem.T)
        val v = CpsatBridge.nativeCpsatPoll(job, -1L, buf)
        CpsatBridge.nativeCpsatDestroy(job)
        NativeBridge.nativeDestroyProblem(problemHandle)
        val s = stats ?: return null
        if (s.size < 7 || v[1] < 0) return null
        return Outcome(NativeEval.unflatten(buf, problem.S, problem.T), s[0], s[1], s[2], s[6])
    }
}
