#!/bin/bash
# Alternate base/new per (fixture, seed); order flips by seed to cancel drift.
# Usage: build.sh <baseline worktree> base; build.sh <target worktree> new; run.sh; python3 analyze.py g15.csv
export LANG=C.utf8 LC_ALL=C.utf8
G=$(dirname $0); L=$HOME/.cache/magi-host-libs; KV=2.3.21; CV=1.8.1
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar"
R=/home/user/magi7ichiro-fork/app/src/test/resources
OUT=$G/g15.csv; echo "fixture,seed,arm,elapsedMs,hard,weighted,total,hash" > $OUT
for f in golden_state sample_state_v6 blocked_covu_state sept2026_state oct2026_grid_state full_coverage_state; do
  for s in 1 2 3 4 5; do
    if [ $((s % 2)) -eq 1 ]; then arms="base new"; else arms="new base"; fi
    for a in $arms; do
      line=$(java -Xmx3g -cp "$CP:$G/$a" probe.G15BenchKt $R/$f.json $s 60 4 2>/dev/null | grep '^RESULT,' | cut -d, -f2-)
      echo "$f,$s,$a,$line" >> $OUT
    done
  done
done
echo DONE >> $G/g15.done
