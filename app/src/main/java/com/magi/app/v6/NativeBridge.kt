package com.magi.app.v6

/**
 * [ネイティブ加速 Stage1] C++ (NDK) ライブラリ magi_native との JNI 疎通。
 *
 * 方針（ユーザー指示「ネイティブ開発言語にして実行速度改善」・ホットパス限定で合意）:
 * - Kotlin 実装（Evaluator/DeltaEvaluator/探索）を常に「正」として残す。
 * - C++ 側はホットパス（Δ評価＋SA内側ループ）の高速版。返却盤面は Kotlin 側で
 *   フル再評価し、スコア不一致なら破棄して Kotlin 結果を使う＝退化不能の番兵。
 * - .so のロード失敗（未同梱ABI・JVMユニットテスト等）は available=false となり、
 *   全経路が従来どおり Kotlin で動く。機能差ゼロ・速度のみの最適化に限定する。
 *
 * Stage1 は疎通（ABIバージョン照合）のみ。エンジンからはまだ呼ばれない。
 */
object NativeBridge {
    /** Kotlin 側が期待する ABI バージョン。C++ 側と不一致ならネイティブ経路を使わない。 */
    const val ABI_VERSION = 1

    /** .so がロードでき、ABI が一致するときだけ true（初回参照時に一度だけ判定）。 */
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("magi_native")
            nativeAbiVersion() == ABI_VERSION
        }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeAbiVersion(): Int
}
