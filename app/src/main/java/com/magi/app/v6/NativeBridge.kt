package com.magi.app.v6

/**
 * [ネイティブ加速] C++ (NDK) ライブラリ magi_native との JNI 境界。
 *
 * 方針（ユーザー指示「ネイティブ開発言語にして実行速度改善」・ホットパス限定で合意）:
 * - Kotlin 実装（Evaluator/DeltaEvaluator/探索）を常に「正」として残す。
 * - C++ 側はホットパスの高速版。実行時に Kotlin Evaluator と同一盤面のパリティ照合を行い
 *   （NativeEval.parityCheck）、一致した場合のみネイティブ経路を使う。
 * - .so のロード失敗（未同梱ABI・JVMユニットテスト等）は available=false となり、
 *   全経路が従来どおり Kotlin で動く。機能差ゼロ・速度のみの最適化に限定する。
 *
 * Stage2: 平坦化 Problem の受け渡し（nativeCreateProblem）＋ C++ フル評価器（nativeFullEval）。
 */
object NativeBridge {
    /** Kotlin 側が期待する ABI バージョン。C++ 側と不一致ならネイティブ経路を使わない。 */
    const val ABI_VERSION = 3

    /** .so がロードでき、ABI が一致するときだけ true（初回参照時に一度だけ判定）。 */
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("magi_native")
            nativeAbiVersion() == ABI_VERSION
        }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeAbiVersion(): Int

    /** 平坦化 Problem からネイティブ側の問題を構築（レイアウトは NativeEval.flatten と一致）。0=失敗。 */
    @JvmStatic
    external fun nativeCreateProblem(
        meta: IntArray, staff: IntArray, canDo: IntArray, wish: IntArray,
        needs: IntArray, ranges: IntArray, cons: IntArray, c3: IntArray,
        bucket: IntArray,
    ): Long

    @JvmStatic
    external fun nativeDestroyProblem(handle: Long)

    /** C++ フル評価（Evaluator.fullEvalParts と同値であるべき）。戻り値 [hard, soft]。失敗時 [-1,-1]。 */
    @JvmStatic
    external fun nativeFullEval(handle: Long, schedule: IntArray): LongArray

    /** [Stage3] SA チャンク（冷却ラダー1本）。cur/best は S*T 平坦・status==0 のときのみ書き戻し。
     *  戻り値 [status, curScore, bestScore, iters, improvedInChunk, tailIters]。 */
    @JvmStatic
    external fun nativeSaChunk(
        handle: Long, cur: IntArray, best: IntArray, bestScore: Long, seed: Long,
        t0: Double, tf: Double, alpha: Double, chain: Int,
    ): LongArray
}

/**
 * [退化不能設計] ネイティブ経路のセッション内ゲート。番兵（チャンク自己整合 or Kotlin照合）が
 * 一度でも発火したら以後このプロセスでは Kotlin のみで走る（クラッシュでなく退化）。
 * 理由は診断ログ（V6FinalPort の NativeBridge 行）に出す。
 */
object NativeGate {
    /** ユーザーの設定トグル（設定タブ「ネイティブ加速」）。番兵ゲート enabled とは独立。 */
    @Volatile var userEnabled: Boolean = true
    @Volatile var enabled: Boolean = true
        private set
    @Volatile var reason: String? = null
        private set

    fun disable(why: String) { enabled = false; reason = why }

    /** ネイティブSAチャンクを使ってよいか（ロード成功×番兵ゲート開×ユーザーON）。 */
    val usable: Boolean get() = enabled && userEnabled && NativeBridge.available
}
