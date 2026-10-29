#!/bin/bash
# build.sh <tree> <out>: compile v6+model+stubs and the bench
export LANG=C.utf8 LC_ALL=C.utf8
T=$1; O=$2; L=$HOME/.cache/magi-host-libs; KV=2.3.21; CV=1.8.1
KC="$L/kotlin-compiler-embeddable-$KV.jar:$L/kotlin-stdlib-$KV.jar:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-13.0.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar"
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar"
A=$T/app/src/main/java/com/magi/app
rm -rf $O; mkdir -p $O
java -Xmx3g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 -cp "$CP" -d $O \
  $(find $A/v6 $A/model -name '*.kt') $T/tools/host/stubs/*.kt $(dirname $0)/G15Bench.kt 2>&1 | grep -E '^e: ' | head
