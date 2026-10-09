# 自律改善ループのベンチ（`LoopBench.kt` / `gate.py`）

ユーザー提示の「勤務表生成アルゴリズム 自律改善ループエンジニアリング仕様」（2026-09-06）の Step 2〜3 を機械化したもの。
新方式＝希望島研磨＋違反アンカー窓交換（`PostOptimizationParams` 既定）、旧方式＝その 2 パスを 0 にした同じ後処理チェーン。
測定範囲は後処理研磨のみ（ユーザー選択）。初期解は短い V5 最適化で作り、両腕に同一盤面を渡す。

- ケース: 合成 42（小 8×14 / 中 16×28 / 大 30×31 × 正常/過密/充足不能/希望集中/禁止連集中/
  cons2不足2件以上(c2deficit)/cons42違反あり(c42pair) × 2）＋実データ 4
  （`app/src/test/resources` の golden / sample_v6 / blocked_covu / sept2026）。各 10 seed、時間予算 10/20/40 s。
  [3.524.0] c2deficit・c42pair は backlog#12(b) の `C2Polish`/`C42FlowPolish`「検証不能」（cons2不足が
  最大1件・cons42が全ケース空だった）を解消するために追加（既存5分類は無変更）。
- CSV 列: `case,size,cat,seed,arm,ms,timeout,exception,oob,mismatch,hard,hardW,softW,wishRate,changed,total,weighted,peakMB,hash,repro`
  （`repro` は seed 0 の新方式を再実行した結果が同一盤面か）。
- `gate.py` は辞書式（必須ゼロ→必須件数→必須加重→ソフト加重→希望充足→変更セル）で新旧を比べ、
  仕様 §4 のゲート（退行ゼロ／品質 ≥10%／速度 ≥10%／安定性）を判定する。
- **統計ゲート（2026-09-22、ユーザー指示「基準の見直し」で併記）**: 必須退行 0・必須件数が増えた試行 0 かつ
  辞書式の勝敗に対する両側符号検定 p<0.05 で勝ち越し。品質・速度 ≥10% は後処理パスでは構造的に届かず
  歴代 44 件で一度も満たされていない（最大 +1.84%）ため。旧ゲートは比較のため残す。
  **新ゲートは今後の合否判定専用**で、既採用の腕（例: 勝ち越し大だが必須増 1 試行で不合格になる iter4〜7 の
  成分修復系）を自動で戻す根拠にはしない（再評価のメモに留める）。再判定で合格したのは iter8（C3 選択日ペア交換、
  勝11/負1）のみ（aptFairSoftTolerance ON 条件の running keep-best は途中まで合格だったが、完走後に充足不能ケースの必須増5件で不合格）。
  3.610.0: iter8 は現行コードで再測定すると 229/230 が同一盤面（勝1/負0）で不合格。running keep-best は構造床>0 で働かない
  条件をつけて合格（勝108/負54・必須増0）→既定 ON。
- **別々に走らせたベンチの CSV どうしを突き合わせない**。決定的モードは後処理だけで、初期解は時間予算の最適化器＝
  負荷で変わる（同じ設定の旧腕でも 100〜180/230 組しか一致しない）。比べたい 2 条件は同じベンチの 2 腕にする。
- `V6FinalPort.handleOptimize(seed = …)` で最適化器の種を指定できる（既定 null・0 は時刻由来）。並列ワーカー・壁時計予算・
  後処理の時刻由来の種は残る＝種を固定しても盤面は再現しない。`LoopBench` は後処理だけの決定的モードなので使わない。
- 壁時計予算のハーネス（`PortfolioBudgetBench`・`HandleOptimizeBench`）は同一設定でもばらつく。
  HandleOptimizeBench の実質 A/A（フラグが経路上効かない 3 件）で品質 ±1.6%・時間 ±37% を観測した＝
  これ未満の差は効果と読まない。

結果と判定は `docs/history/3.4xx.md`「自律改善ループ Iteration 1」を参照（`results/iter1.csv` が生データ）。

- **再開（3.507.6）**: 出力 CSV が既にあれば済みの (case,seed,arm) を飛ばして追記する＝同じコマンドで続きから走る。このサンドボックスは
  セッションが無操作だと VM が止まりバックグラウンドの JVM が消えるので、長いベンチは前景の待機（10 分ずつ）で見守るか、再開前提で回す。
- **決定的モード（3.507.3）**: `MAGI_BENCH_DETERMINISTIC=1 tools/loop/run_bench.sh …` で両腕とも `PostOptimizationParams.deterministic=true`
  （ms キャップ・締切・残り時間の判定を回数上限へ。共同 LNS は `maxEvaluations`＝C1 90,000・個人 60,000）。同じ入力・seed なら同じ盤面＝
  `repro` 列が他ジョブの負荷に依存しない。実機は既定 OFF（予算を使い切る）。
- **機能同等性（§4 の 14 機能）**は `app/src/test/java/com/magi/app/v6/LoopFeatureRegressionTest.kt`（C# は `LoopFeatureRegressionTest.cs`）で
  計測する（3.507.2）。各機能を最小盤面で作り、後処理チェーンを旧腕（`componentRepairEnabled=false`）と新腕（true）の両方で走らせて
  不変条件（人員不足/超過修復・個人上下限・群回数・禁止連・希望固定・希望日前後・同長区間交換・循環交換・担当可・スキル群・Undo・停止・
  月境界・月内完結）を検査する。`tools/host/hosttest.sh` で走る（14 本×2 腕、各 4 秒締切）。
- **S5 机上試験（2026-09-24）**: `tools/loop/run_s5probe.sh out.csv [本計算秒=30] [試算秒=3] [希望上限=6]`（`S5Probe.kt`）。
  本計算の盤面で必須違反に関わる希望を 1 件ずつ外し、短い試算（配置固定・1手探索・短い最適化・短い後処理）の必須件数と、
  同じ希望を外した本計算（正解）・外さずにやり直した本計算（G0）を比べる。壁時計予算なので値は負荷で揺れる。
- **HARD 残存の分類（2026-10-03）**: `tools/loop/run_hard_residual_probe.sh <dir> fixtures 60,120 1,2,3`（`HardResidualProbe.kt`、合成は `synth:<id の一部>`）。
- **取り逃し監査（2026-10-09、3.652.0、測定のみ）**: `tools/loop/run_missed_move_probe.sh <out.csv> [seeds=1] [maxLen=7] [ケース名の部分一致=""] [mode=det|ho] [秒=120] [workers=4]`（`MissedMoveProbe.kt`）。
  実データ 5 件の最終盤面で、N1＝1 セルの変更・N2＝同じ日の 2 人の入れ替え・N3＝2 人の連続 2〜maxLen 日の交換を全列挙し、正式な採否（`betterReport`）で
  改善になる手の数・最良の手・違反セルに当たる手（anchored）を CSV に書く。固定条件は最適化器と同じ（`wishLocked`・`mayPlaceAt`）。盤面は mode=ho＝本番の入口
  `handleOptimize`（時間制）、det＝LoopBench と同じ決定論モード（回数上限で切れるので取り逃しが多めに出る＝判定は ho で読む）。時間制なので計測中は他の重い処理を走らせない。
- **経験的な c3n 壁の探索（2026-10-09、3.643.0 手順②）**: `tools/loop/run_wall_fixture_probe.sh <dir> [id の部分一致=""] [seed 変種=3] [予算秒=4] [方式=AUTO]`（`WallFixtureProbe.kt`）。合成ケース（`Cases.specs`）を短い予算で最適化し、最終盤面の禁止連続を `diagnoseForbiddenRuns` で診断。全セル塞がり（`allBlocked`）かつ希望固定でない（`!allBlockedCertified`）盤面を `wall_<id>_{refuted|confirmed}_state.json` に書く（refuted＝`c3nWallRefutedByOneMove` が必須を減らす手を見つけた＝`c3nWallDeepCheck` の計測に使える盤面）。`wall_probe.csv` に全ケースの hard・内訳・各セルの逃げ道（FR/CH/AD/PI/BL）。
  最終盤面の HARD を族・職員・日で並べ、既存の床（`structuralHardFloor`・`wishConflictFloorParts`・`ForbiddenDiag`・`PreRunCheck`）で分類し、
  床を超えた盤面に `FixSuggester`/`ViolationComponentRepair` を当てる。厳密な最小 HARD は `HardFloorExport.kt`（export/verify）＋
  `hard_floor_cpsat.py`（ortools、置ける範囲は mayPlace と canDo の 2 通り。解は checker で照合）。結果は `docs/history/3.4xx.md`。

## HandleOptimizeBench（`run_handleoptimize_bench.sh`）の腕と集計（3.641.0〜3.643.0）

- `MAGI_HO_FEATURE`: 空＝ExtraRefine 省略の A/B。`c3nwall`＝c3n 壁の短縮を外す。`head`＝HEAD の壁判定と試行中の停止確認を切る。`deep`＝経験的な c3n 壁を 1 手探索で反証する（`PolishGate.c3nWallDeepCheck`）。`adaptive`＝適応閾値（`PolishGate.adaptiveStall`、通常分岐を直近の改善間隔の最大×3 まで縮める。2026-10-09 に否決）。
- `MAGI_HO_FIXTURES`（カンマ区切り）、`MAGI_HO_SEEDS`（seed の列）、`MAGI_HO_REPEATS`（同じ seed・同じ腕の反復。CSV 末尾に `rep` 列）、`MAGI_HO_LOGTAGS`＋`MAGI_HO_LOGFILE`（Watchdog・EarlyStop などのエンジンログを run ごとに追記）。
- 集計: `python3 tools/loop/ho_stats.py results/x.csv [results/y.csv ...] [--metric total]`。差は off−on（正＝既定が悪い）。平均・95% ブートストラップ区間・符号検定・rep 間の揺れ（プール SD と最大範囲）・決定／未決と、区間を 0 から外すのに要る対の数を出す。
- 規則: 腕の差を読む前に、同じ seed・同じ腕の揺れを測る。workers 4 は壁時計に依存し、seed を固定しても軌跡が変わる（2026-10-08 の診断で weighted ±100〜325、停止時刻 ±215 s）。区間が 0 を含むなら未決とし、既定を動かさない。
