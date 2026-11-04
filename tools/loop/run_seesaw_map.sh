#!/bin/bash
# シーソーの地図（docs/seesaw.md §6.1 の S1、測定用）。族ごとに「その族の重みだけ ×2 の腕」と「今の重みの腕」を同時に走らせる（同じ負荷で比べる、
# tools/loop/README.md）。今の重みの腕は族の数だけ走るので、揺れ σ の見積もりにも使える。
#   tools/loop/run_seesaw_map.sh <出力ディレクトリ> [族…（既定 c1 high c3m c3mn fair apt）]
#   環境変数: MAGI_WS_SEEDS（既定 1,2,3,4,5,6）・MAGI_WS_FIXTURES（既定 6 fixture すべて）・MAGI_MAP_SECS（既定 60）
set -e
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd); OUT=$(mkdir -p "$1" && cd "$1" && pwd); shift
FAMS=${*:-c1 high c3m c3mn fair apt}
export MAGI_WS_SEEDS=${MAGI_WS_SEEDS:-1,2,3,4,5,6}
export MAGI_WS_FIXTURES=${MAGI_WS_FIXTURES:-blocked_covu_state.json,full_coverage_state.json,golden_state.json,oct2026_grid_state.json,sample_state_v6.json,sept2026_state.json}
SECS=${MAGI_MAP_SECS:-60}
for fam in $FAMS; do
  [ -s "$OUT/P-$fam.csv" ] && [ $(wc -l < "$OUT/P-$fam.csv") -gt 1 ] && { echo "済: $fam"; continue; }
  WT="$OUT/wt-$fam"; [ -d "$WT" ] || git -C "$ROOT" worktree add -q --detach "$WT" HEAD
  git -C "$WT" checkout -q -- app && python3 "$HERE/seesaw_arm.py" "$WT" "$fam" 2
  MAGI_WS_BOARDS="$OUT/boards" MAGI_HOST_OUT="$OUT/build-Q-$fam" bash "$HERE/run_weight_swap_probe.sh" "$ROOT" "$OUT/Q-$fam.csv" "Q" "$SECS" 1 > "$OUT/Q-$fam.log" 2>&1 &
  MAGI_WS_BOARDS="$OUT/boards" MAGI_HOST_OUT="$OUT/build-P-$fam" bash "$HERE/run_weight_swap_probe.sh" "$WT" "$OUT/P-$fam.csv" "P_${fam}2x" "$SECS" 1 > "$OUT/P-$fam.log" 2>&1 &
  wait
  rm -rf "$OUT/build-Q-$fam" "$OUT/build-P-$fam"
  echo "完了: $fam $(($(wc -l < "$OUT/Q-$fam.csv")-1)) 対"
done
