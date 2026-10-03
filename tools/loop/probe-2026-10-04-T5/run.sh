#!/bin/bash
# usage: run.sh <fixture_json_name...>  e.g. run.sh sample_state_v6.json  (resumable; out.csv is appended)
set -e
R=$(cd "$(dirname "$0")/../../.." && pwd); D=$R/tools/loop/probe-2026-10-04-T5
W=${W:-/tmp/t5w}; mkdir -p $W
L=/root/.cache/magi-host-libs
[ -f $L/kotlin-stdlib-2.3.21.jar ] || { echo "run tools/host/hosttest.sh once to fetch libs"; exit 1; }
if [ ! -d $W/main/com ]; then
  ( cd $R && MAGI_HOST_OUT=$W/hb bash tools/host/hosttest.sh >/dev/null 2>&1 || true ); cp -r $W/hb/main $W/main
fi
KC=$(ls $L/*.jar | tr '\n' ':'); CP="$L/kotlin-stdlib-2.3.21.jar:$L/kotlinx-coroutines-core-jvm-1.8.1.jar:$L/json-20240303.jar"
[ -f $W/probe/probe/T5ProbeKt.class ] || LANG=C.utf8 java -Xmx3g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 -cp "$CP:$W/main" -Xfriend-paths=$W/main -d $W/probe $D/Probe.kt
cd $R && exec nice -n 19 env LANG=C.utf8 java -Xmx3g -cp "$CP:$W/main:$W/probe" probe.T5ProbeKt app/src/test/resources $W/out.csv $W/passlogs.tsv "$@"
