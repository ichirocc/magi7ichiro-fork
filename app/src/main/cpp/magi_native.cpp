#include <jni.h>

// [ネイティブ加速 Stage1] 疎通のみ。ABI バージョンを返し、Kotlin 側 NativeBridge が
// ロード成否と互換性を確認する。Kotlin 側の期待値（NativeBridge.ABI_VERSION）と
// 一致しない場合はネイティブ経路を使わない＝バージョンずれの安全弁。
// 以降の Stage で fullEval / SA チャンクをここへ追加していく（Kotlin 実装が常に正、
// 返却盤面は Kotlin 側でフル再評価して不一致なら破棄＝退化不能の番兵方針）。
extern "C" JNIEXPORT jint JNICALL
Java_com_magi_app_v6_NativeBridge_nativeAbiVersion(JNIEnv*, jclass) {
    return 1;
}
