# [ネイティブ加速 Stage1] MAGI エンジンのホットパス（Δ評価＋SA内側ループ）を段階的に C++ 化する
# ための足場。Stage1 は疎通（ABIバージョン返却）のみで、機能・スコアリングへの影響はゼロ。
# .so のロードに失敗した端末/ビルドでは NativeBridge.available=false → 従来 Kotlin パスへ
# フォールバックするため、このライブラリはいつ欠けても安全（速度のみの最適化に限定する方針）。
cmake_minimum_required(VERSION 3.22.1)
project(magi_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(magi_native SHARED magi_native.cpp)

# エンジンの内側ループ向け: 最適化最大・例外/RTTI 不使用（JNI 境界では戻り値で結果を返す設計）。
target_compile_options(magi_native PRIVATE -O3 -fno-exceptions -fno-rtti -Wall)

find_library(log-lib log)
target_link_libraries(magi_native ${log-lib})

# [Android 16 / 16KBメモリページ対応] NDK r26 は 16KB アライメントを既定にしないため（既定化は
# r27/r28 以降）、16KB ページの Android 16 端末では 4KB アライメントの .so がロードできず、
# NativeGate.available=false で Kotlin フォールバック＝クラッシュはしないが native 加速が丸ごと
# 失われる（＝対象プラットフォームで速度退行）。lld に 16KB の max-page-size を明示し、4KB/16KB
# 両ページ端末でロード可能にする（4KB 端末でも無害）。target_link_options は CMake 3.13+（本プロジェクト
# 3.22.1）で利用可。
target_link_options(magi_native PRIVATE "-Wl,-z,max-page-size=16384")

# CP-SAT（OR-Tools）による下限・ギャップ・説明（docs/cpsat_formal.md）。この ABI 向けに組んだ OR-Tools の install prefix が
#   渡されたときだけ組み込む（tools/native/build_ortools_android.sh、CI は ortools-android.yml）。無ければ従来どおり＝Kotlin 側は
#   CpsatBridge.available=false で CP-SAT を使わない。
set(MAGI_ORTOOLS_PREFIX "" CACHE PATH "OR-Tools install prefix for this ABI")
if(MAGI_ORTOOLS_PREFIX AND EXISTS "${MAGI_ORTOOLS_PREFIX}/lib/cmake/ortools")
  list(APPEND CMAKE_PREFIX_PATH "${MAGI_ORTOOLS_PREFIX}")
  list(APPEND CMAKE_FIND_ROOT_PATH "${MAGI_ORTOOLS_PREFIX}")
  find_package(ortools CONFIG REQUIRED)
  target_compile_definitions(magi_native PRIVATE MAGI_WITH_CPSAT OR_PROTO_DLL=)
  # OR-Tools（protobuf・abseil）のヘッダは RTTI と例外を前提にする＝この .so だけ有効にする（エンジンの戻り値設計は不変）。
  target_compile_options(magi_native PRIVATE -frtti -fexceptions)
  target_compile_features(magi_native PRIVATE cxx_std_17)
  target_link_libraries(magi_native ortools::ortools)
  message(STATUS "MAGI: CP-SAT enabled from ${MAGI_ORTOOLS_PREFIX}")
endif()
