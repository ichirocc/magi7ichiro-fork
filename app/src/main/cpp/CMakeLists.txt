# [ネイティブ加速 Stage1] MAGI エンジンのホットパス（Δ評価＋SA内側ループ）を段階的に C++ 化する
# ための足場。Stage1 は疎通（ABIバージョン返却）のみで、機能・スコアリングへの影響はゼロ。
# .so のロードに失敗した端末/ビルドでは NativeBridge.available=false → 従来 Kotlin パスへ
# フォールバックするため、このライブラリはいつ欠けても安全（速度のみの最適化に限定する方針）。
cmake_minimum_required(VERSION 3.22.1)
project(magi_native CXX)

add_library(magi_native SHARED magi_native.cpp)

# エンジンの内側ループ向け: 最適化最大・例外/RTTI 不使用（JNI 境界では戻り値で結果を返す設計）。
target_compile_options(magi_native PRIVATE -O3 -fno-exceptions -fno-rtti -Wall)

find_library(log-lib log)
target_link_libraries(magi_native ${log-lib})
