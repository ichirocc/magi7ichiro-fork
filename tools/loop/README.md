# 自律改善ループのベンチ（`LoopBench.kt` / `gate.py`）

ユーザー提示の「勤務表生成アルゴリズム 自律改善ループエンジニアリング仕様」（2026-09-06）の Step 2〜3 を機械化したもの。
新方式＝希望島研磨＋違反アンカー窓交換（`PostOptimizationParams` 既定）、旧方式＝その 2 パスを 0 にした同じ後処理チェーン。
測定範囲は後処理研磨のみ（ユーザー選択）。初期解は短い V5 最適化で作り、両腕に同一盤面を渡す。

- ケース: 合成 30（小 8×14 / 中 16×28 / 大 30×31 × 正常/過密/充足不能/希望集中/禁止連集中 × 2）＋実データ 4
  （`app/src/test/resources` の golden / sample_v6 / blocked_covu / sept2026）。各 10 seed、時間予算 10/20/40 s。
- CSV 列: `case,size,cat,seed,arm,ms,timeout,exception,oob,mismatch,hard,hardW,softW,wishRate,changed,total,weighted,peakMB,hash,repro`
  （`repro` は seed 0 の新方式を再実行した結果が同一盤面か）。
- `gate.py` は辞書式（必須ゼロ→必須件数→必須加重→ソフト加重→希望充足→変更セル）で新旧を比べ、
  仕様 §4 のゲート（退行ゼロ／品質 ≥10%／速度 ≥10%／安定性）を判定する。

結果と判定は `docs/history/3.4xx.md`「自律改善ループ Iteration 1」を参照（`results/iter1.csv` が生データ）。
