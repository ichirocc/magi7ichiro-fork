#!/bin/bash
# 重みの入れ替えの効果（WeightSwapProbe.kt）。ROOT のソースをホストでビルドして 1 腕ぶんを走らせる。
#   MAGI_HOST_OUT=<腕ごとの出力先> tools/loop/run_weight_swap_probe.sh <ROOT（旧重みの worktree か今の repo）> <out.csv> <arm> [秒=60] [workers=1]
set -e
export LANG=C.utf8 LC_ALL=C.utf8
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$1
L=${MAGI_HOST_LIBS:-$HOME/.cache/magi-host-libs}; KV=2.3.21; CV=1.8.1
KC="$L/kotlin-compiler-embeddable-$KV.jar:$L/kotlin-stdlib-$KV.jar:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-13.0.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar"
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar"
OUT=${MAGI_HOST_OUT:?MAGI_HOST_OUT を腕ごとに分ける}; rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/probe"
A=$ROOT/app/src/main/java/com/magi/app
kc(){ java -Xmx3g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 "$@" 2>&1 | grep -E "^e: |error:" && return 1; return 0; }
kc -cp "$CP" -d "$OUT/main" $(find "$A/v6" "$A/model" -name '*.kt') "$ROOT"/tools/host/stubs/*.kt
kc -cp "$CP:$OUT/main" -Xfriend-paths="$OUT/main" -d "$OUT/probe" "$HERE/WeightSwapProbe.kt"
java -Xmx3g -Dfile.encoding=UTF-8 -cp "$CP:$OUT/main:$OUT/probe" probe.WeightSwapProbeKt "$ROOT/app/src/test/resources" "$2" "$3" "${4:-60}" "${5:-1}" 2>&1 | grep --line-buffered -v JAVA_TOOL
