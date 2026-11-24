# K 人の組の厳密スイープ（2026-11-24）

`tools/native/cpsat_cohort_sweep.cpp` の測定の生データ。ホスト 4 コア、優先順位の重み、組あたり上限 20 秒。

- `sweep_opt30.txt`: 付属 6 件をホストで 30 秒最適化した盤面（`OptBoards.kt`、`V6FinalPort.handleOptimize`・乱数 1001・4 ワーカー）に K=2・3。
- `improve30_opt30.txt`: 同じ盤面に既存の解き直し（`magi_cpsat::improve`、30 秒・4 スレッド、`improve_run.cpp`）。
- `corun120.txt`: 30 秒の盤面を起点に CP-SAT の全体の解（`magi_cpsat::solve`、120 秒・2 ワーカー＝端末の併走と同じ、`solve_run.cpp`）をかけた盤面で、
  K=2・3 と解き直し 30 秒。盤面は `inject.py` で state JSON に戻し `tools/native/state_to_flat.py` で flat にした。

再現: `OptBoards.kt` はホストの main のクラス（`tools/host/hosttest.sh` の出力）に対してコンパイルして実行。C++ の 3 本は
`docs/cpsat_formal.md`「再実行」の CP-SAT 込みの組み方（`-pthread`）。数値の読みは `docs/cpsat_formal.md`「K 人の組の厳密スイープ」。

`seesaw_fixed.md`: 利用者が貼ったシーソー仕様書の追加（総当たりオラクル等）を照合して 9 か所を直した版。土台は `claude/review-fixes`（b4da2a1c）の
`docs/seesaw.md`。あちらが main に入ったら `docs/seesaw.md` へ重ねる（それまではこの写しが正）。
