#!/bin/bash
# 経験的な c3n 壁を持つ盤面の探索（tools/loop/WallFixtureProbe.kt）をホスト JVM で走らせる（先に tools/host/hosttest.sh でビルド）。
#   tools/loop/run_wall_fixture_probe.sh <出力dir> [idの部分一致=""] [seed変種=3] [予算秒=4] [方式=AUTO]   # 出力は tools/loop/README.md
set -e
export LANG=C.utf8 LC_ALL=C.utf8
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
HOSTOUT=${MAGI_HOST_OUT:-/tmp/magi-hostbuild}
L=${MAGI_HOST_LIBS:-$HOME/.cache/magi-host-libs}; KV=2.3.21; CV=1.8.1
KC="$L/kotlin-compiler-embeddable-$KV.jar:$L/kotlin-stdlib-$KV.jar:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-13.0.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar"
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar:$HOSTOUT/main"
OUT=$HOSTOUT/wallprobe
if [ ! -f "$OUT/probe/wall/WallFixtureProbeKt.class" ] || [ "$HERE/WallFixtureProbe.kt" -nt "$OUT/probe/wall/WallFixtureProbeKt.class" ]; then
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -Xmx2g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 -Xfriend-paths="$HOSTOUT/main" -cp "$CP" -d "$OUT" "$HERE/LoopBench.kt" "$HERE/WallFixtureProbe.kt" 2>&1 | grep -E "^e: |error:" && exit 1
fi
java -Xmx3g -Dfile.encoding=UTF-8 -cp "$CP:$OUT" probe.wall.WallFixtureProbeKt "$@" 2>&1 | grep --line-buffered -v JAVA_TOOL
