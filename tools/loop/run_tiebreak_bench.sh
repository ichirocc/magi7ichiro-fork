#!/bin/bash
# [backlog#19] SaOptimizer.officialTieBreak の計測ベンチ（tools/loop/TieBreakBench.kt）をホスト JVM で走らせる。
#   tools/host/hosttest.sh                              # 先にエンジンを /tmp/magi-hostbuild へビルド（MAGI_HOST_OUT で変更可）
#   tools/loop/run_tiebreak_bench.sh out.csv [seeds=5] [budgetMs=20000] [workers=4]
# 実データ4件（app/src/test/resources）× seed × 1 回の実行内で fullEval 基準と betterReport 基準を比較する。
set -e
export LANG=C.utf8 LC_ALL=C.utf8   # POSIX ロケールだと日本語リテラルが化ける（hosttest.sh 参照）
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
HOSTOUT=${MAGI_HOST_OUT:-/tmp/magi-hostbuild}
L=${MAGI_HOST_LIBS:-$HOME/.cache/magi-host-libs}; KV=2.3.21; CV=1.8.1
KC="$L/kotlin-compiler-embeddable-$KV.jar:$L/kotlin-stdlib-$KV.jar:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-13.0.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar"
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar:$HOSTOUT/main"
OUT=$HOSTOUT/tiebreakbench; rm -rf "$OUT"; mkdir -p "$OUT"
java -Xmx2g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 -Xfriend-paths="$HOSTOUT/main" -cp "$CP" -d "$OUT" "$HERE/TieBreakBench.kt" 2>&1 | grep -E "^e: |error:" && exit 1
java -Xmx3g -Dfile.encoding=UTF-8 -cp "$CP:$OUT" probe.TieBreakBenchKt "${1:-$HERE/results/tiebreak.csv}" "${2:-5}" "${3:-20000}" "${4:-4}" "$ROOT/app/src/test/resources" 2>&1 | grep --line-buffered -v JAVA_TOOL
