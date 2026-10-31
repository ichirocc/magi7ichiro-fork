#!/bin/bash
export LANG=C.utf8 LC_ALL=C.utf8
G=$(dirname $0); L=$HOME/.cache/magi-host-libs
CP="$L/kotlin-stdlib-2.3.21.jar:$L/kotlinx-coroutines-core-jvm-1.8.1.jar:$L/json-20240303.jar"
R=/home/user/magi7ichiro-fork/app/src/test/resources; OUT=$G/w14.txt; : > $OUT
for f in golden_state sample_state_v6 blocked_covu_state sept2026_state oct2026_grid_state full_coverage_state; do
  for s in 1 2 3; do
    if [ $((s % 2)) -eq 1 ]; then arms="old14 new14"; else arms="new14 old14"; fi
    for a in $arms; do
      r=$(java -Xmx3g -cp "$CP:$G/$a" probe.G15BenchKt $R/$f.json $s 60 4 2>/dev/null | grep -E '^(BD|RESULT)' | tr '\n' ' ')
      echo "$f $s $a $r" >> $OUT
    done
  done
done
echo DONE >> $OUT
