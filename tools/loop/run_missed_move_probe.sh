#!/bin/bash
# 取り逃し監査（tools/loop/MissedMoveProbe.kt、3.652.0）をホスト JVM で走らせる。測定のみ（探索の動きは変えない）。
#   tools/host/hosttest.sh                                  # 先にエンジンを /tmp/magi-hostbuild へビルド
#   tools/loop/run_missed_move_probe.sh results/missed.csv [seeds=1] [maxLen=7] [ケース名の部分一致=""] [mode=det|ho] [秒=120] [workers=4]
#   ho＝本番の入口 handleOptimize（時間制）の盤面。det＝LoopBench と同じ決定論モード（回数上限で切れる＝取り逃しが多めに出る）。
set -e
export LANG=C.utf8 LC_ALL=C.utf8   # POSIX ロケールだと日本語リテラルが化ける（hosttest.sh 参照）
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
HOSTOUT=${MAGI_HOST_OUT:-/tmp/magi-hostbuild}
L=${MAGI_HOST_LIBS:-$HOME/.cache/magi-host-libs}; KV=2.3.21; CV=1.8.1
KC="$L/kotlin-compiler-embeddable-$KV.jar:$L/kotlin-stdlib-$KV.jar:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-13.0.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar"
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar:$HOSTOUT/main"
OUT=$HOSTOUT/missedmoveprobe; rm -rf "$OUT"; mkdir -p "$OUT"
java -Xmx2g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 -Xfriend-paths="$HOSTOUT/main" -cp "$CP" -d "$OUT" "$HERE/MissedMoveProbe.kt" 2>&1 | grep -E "^e: |error:" && exit 1
java -Xmx3g -Dfile.encoding=UTF-8 -cp "$CP:$OUT" probe.MissedMoveProbeKt "$ROOT/app/src/test/resources" "${1:-$HERE/results/missed.csv}" "${2:-1}" "${3:-7}" "${4:-}" "${5:-det}" "${6:-120}" "${7:-4}" 2>&1 | grep --line-buffered -v JAVA_TOOL
