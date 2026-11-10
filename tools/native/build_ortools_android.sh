#!/bin/bash
# OR-Tools（CP-SAT だけ）を Android 向けに静的ライブラリとしてビルドし、prefix へ入れる。CI（ortools-android.yml）とローカルで共用。
#   tools/native/build_ortools_android.sh <ndk> <ortools-src> <prefix> [abi=arm64-v8a] [jobs=nproc]
# 依存（abseil・protobuf・re2・zlib・eigen）は OR-Tools の BUILD_DEPS で同じツールチェーンから作る。protoc はホスト用を
#   OR-Tools の cmake/host.cmake が別に作る（クロスコンパイルで protoc を端末向けに作っても実行できないため）。
set -euo pipefail
NDK=$1; SRC=$2; PREFIX=$3; ABI=${4:-arm64-v8a}; JOBS=${5:-$(nproc)}
BUILD=${BUILD_DIR:-$SRC/build-android-$ABI}
# 付属の実行ファイル solve・vector_bin_packing は静的リンクで Gurobi 連携の記号が解決できず失敗する（アプリには不要）。
#   Android 向けでは作らない・入れない（OR-Tools 側の CMake を書き換える。何度走らせても同じ結果）。
python3 - "$SRC" <<'PY'
import sys, re, pathlib
src = pathlib.Path(sys.argv[1])
for rel, tgt in [("ortools/linear_solver/CMakeLists.txt", "solve"), ("ortools/packing/CMakeLists.txt", "vector_bin_packing")]:
    p = src / rel; s = p.read_text()
    if "MAGI_SKIP_" + tgt in s: continue
    s = s.replace(f"add_executable({tgt})", f"if(NOT ANDROID) # MAGI_SKIP_{tgt}\nadd_executable({tgt})", 1)
    i = s.index(f"install(TARGETS {tgt}"); j = s.index(")", i) + 1
    s = s[:j] + "\nendif() # MAGI_SKIP_" + tgt + s[j:]
    p.write_text(s)
PY
cmake -S "$SRC" -B "$BUILD" -G "Unix Makefiles" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-34 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_DEPS=ON -DBUILD_CXX=ON \
  -DBUILD_PYTHON=OFF -DBUILD_JAVA=OFF -DBUILD_DOTNET=OFF -DBUILD_SAMPLES=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTING=OFF -DBUILD_DOC=OFF \
  -DBUILD_FLATZINC=OFF -DBUILD_LP_PARSER=OFF -DBUILD_MATH_OPT=OFF \
  -DUSE_COINOR=OFF -DUSE_SCIP=OFF -DUSE_GLPK=OFF -DUSE_HIGHS=OFF -DUSE_PDLP=OFF -DUSE_CPLEX=OFF \
  -DCMAKE_CXX_FLAGS="-fPIC -ffunction-sections -fdata-sections" -DCMAKE_C_FLAGS="-fPIC -ffunction-sections -fdata-sections"
cmake --build "$BUILD" -j "$JOBS"
cmake --install "$BUILD"
echo "ORTOOLS_ANDROID_OK $PREFIX"
