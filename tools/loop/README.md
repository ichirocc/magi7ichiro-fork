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

- **機能同等性（§4 の 14 機能）**は `app/src/test/java/com/magi/app/v6/LoopFeatureRegressionTest.kt`（C# は `LoopFeatureRegressionTest.cs`）で
  計測する（3.507.2）。各機能を最小盤面で作り、後処理チェーンを旧腕（`componentRepairEnabled=false`）と新腕（true）の両方で走らせて
  不変条件（人員不足/超過修復・個人上下限・群回数・禁止連・希望固定・希望日前後・同長区間交換・循環交換・担当可・スキル群・Undo・停止・
  月境界・月内完結）を検査する。`tools/host/hosttest.sh` で走る（14 本×2 腕、各 4 秒締切）。
