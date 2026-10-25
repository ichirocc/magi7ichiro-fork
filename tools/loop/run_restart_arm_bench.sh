#!/bin/bash
# 作り直しの起点の対比較（tools/loop/RestartArmBench.kt）。
#   tools/loop/run_restart_arm_bench.sh <repo> <buildDir> <out.csv> [秒=60] [workers=1] [build]   # build で本体と bench をビルド
#   MAGI_HO_FIXTURES / MAGI_HO_SEEDS で絞る。集計は ho_stats.py <out.csv> と <out>.best.csv。
set -e
export LANG=C.utf8 LC_ALL=C.utf8
ROOT=$1; OUT=$2; CSV=$3; SEC=$4; WK=$5
L=$HOME/.cache/magi-host-libs; KV=2.3.21; CV=1.8.1
KC="$L/kotlin-compiler-embeddable-$KV.jar:$L/kotlin-stdlib-$KV.jar:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-13.0.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar"
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar"
if [ "$6" = build ]; then
  rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/bench"
  A=$ROOT/app/src/main/java/com/magi/app
  HERE=$ROOT/tools/host
  eval "$(grep '^MAIN_SRC=' "$ROOT/tools/host/hosttest.sh")"   # ホストテストと同じ一覧（ui の純粋ロジック・stubs）
  java -Xmx3g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 -cp "$CP" -d "$OUT/main" $MAIN_SRC 2>&1 | grep -E "^e: |error:" && exit 1
  java -Xmx2g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 -Xfriend-paths="$OUT/main" -cp "$CP:$OUT/main" -d "$OUT/bench" "$ROOT/tools/loop/RestartArmBench.kt" 2>&1 | grep -E "^e: |error:" && exit 1
  echo built
fi
java -Xmx3g -Dfile.encoding=UTF-8 -cp "$CP:$OUT/main:$OUT/bench" probe.RestartArmBenchKt "$ROOT/app/src/test/resources" "$CSV" "$SEC" "$WK" 2>&1 | grep --line-buffered -v JAVA_TOOL
