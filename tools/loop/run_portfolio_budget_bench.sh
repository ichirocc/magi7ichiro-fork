#!/bin/bash
# ポートフォリオ経路（algorithm=PORTFOLIO）のA/Bベンチ（tools/loop/PortfolioBudgetBench.kt、backlog#34）をホスト JVM で走らせる。
#   tools/host/hosttest.sh                                  # 先にエンジンを /tmp/magi-hostbuild へビルド
#   tools/loop/run_portfolio_budget_bench.sh results/portfoliobudget1.csv [seeds=5] [budgetSec=90] [workers=4]
# [2026-09-21/backlog#28] PORTFOLIO_BENCH_FEATURE=rsifocusrotation で rsiFocusRotationPersist のA/Bに切替
#   （既定はroleBudgetFit）。runRsi自体がRSI/RSI_PLUS/PORTFOLIOでしか呼ばれずLoopBenchでは測れない。
set -e
export LANG=C.utf8 LC_ALL=C.utf8   # POSIX ロケールだと日本語リテラルが化ける（hosttest.sh 参照）
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
HOSTOUT=${MAGI_HOST_OUT:-/tmp/magi-hostbuild}
L=${MAGI_HOST_LIBS:-$HOME/.cache/magi-host-libs}; KV=2.3.21; CV=1.8.1
KC="$L/kotlin-compiler-embeddable-$KV.jar:$L/kotlin-stdlib-$KV.jar:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-13.0.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar"
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar:$HOSTOUT/main"
OUT=$HOSTOUT/portfoliobudgetbench; rm -rf "$OUT"; mkdir -p "$OUT"
java -Xmx2g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 -Xfriend-paths="$HOSTOUT/main" -cp "$CP" -d "$OUT" "$HERE/PortfolioBudgetBench.kt" 2>&1 | grep -E "^e: |error:" && exit 1
java -Xmx3g -Dfile.encoding=UTF-8 -cp "$CP:$OUT" probe.PortfolioBudgetBenchKt "$ROOT/app/src/test/resources" "${1:-$HERE/results/portfoliobudget.csv}" "${2:-5}" "${3:-90}" "${4:-4}" 2>&1 | grep --line-buffered -v JAVA_TOOL
