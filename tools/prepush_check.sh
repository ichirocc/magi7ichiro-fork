#!/bin/bash
# push の前に、CI の静的検査（V6 Engine Check の weight_lint と自己テスト、Design Lint）を手元で数秒で通す。
#   ファイルの分割・移動のあとは必ず走らせる（2026-10-24、許可リストの空振りで V6 Engine Check を 4 コミット落とした）。
#   JUnit は `tools/host/hosttest.sh`（約 6 分）、画面は `android-sdk.yml`（workflow_dispatch）で別に確かめる。
set -u
cd "$(dirname "$0")/.."
rc=0
python3 tools/weight_lint.py --summary || rc=1
python3 -m unittest -q tools/test_weight_lint.py || rc=1
python3 tools/design_lint.py > /tmp/prepush_design_lint.txt 2>&1 || { tail -20 /tmp/prepush_design_lint.txt; rc=1; }
[ $rc -eq 0 ] && echo "prepush: OK" || echo "prepush: NG（上の出力を直してから push）"
exit $rc
