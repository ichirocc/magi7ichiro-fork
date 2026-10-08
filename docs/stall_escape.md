# 停滞脱出（停滞の検知・早期終了・脱出手）の台帳

> 探索が「改善しなくなった」ときに何が起きるかを、**層ごと**に 1 か所へまとめた資料（2026-10-08・3.638.0 時点のコードで照合）。
> 経緯の本文は `docs/history/topics.md`「停滞脱出の改善」と `docs/history/INDEX.md`（「停滞」「ウォッチドッグ」「早期終了」で grep）、
> 既定 OFF と否決の一覧は `docs/algorithm_portfolio.md`。ここは**現行の規則と数値**だけを書く。数値を変えたらここも同じコミットで直す。
> C# 移植（`ichirocc/-MAGI_PC`）は同値＝`V6FinalPort.Watchdog.cs`・`V6FinalPort.HandleOptimize.cs`。

## 0. 一枚で

| 層 | 停滞の定義 | 停滞したら | 品質への影響 |
|---|---|---|---|
| **A. 実行全体のウォッチドッグ**（`V6FinalPort.handleOptimize`） | 最良（hard→weightedScore→total）が `effStall` ms 更新されない | 探索を打ち切り後処理へ（追加精製は省く） | なし（keep-best。時間と電池だけ節約） |
| **B. 適応ポートフォリオ**（`V6NativeOptimizer` エポックループ） | 自己エリートが 1 エポック改善しない／他ワーカーと距離 ≤2 セル | 役割を回す・強度を上げる（W0 は不動） | なし（全体最良は別管理） |
| **C. RSI ラウンド**（`runRsi`） | ラウンドの keep-best が無改善 | focus の 1R 冷却→HF63 が族を外す→狙える族が尽きたら早期終了 | なし |
| **D. SA/LAHC ラダー**（`SaOptimizer`・C++ `SaChunk`） | HARD が `hardStallMs`=2.5 s 下がらない／ラダー境界 | PhaseB（LAHC）へ一方向遷移／reset-to-best 再加熱 | なし |
| **E. 後処理の各パス** | パスごとの patience／採用 0 の巡 | そのパスを打ち切る・巡を終える | なし（入力比番兵） |

共通の原則: **停滞は停止条件ではなく「別の手を要求する信号」**（B）、ただし **A は唯一の停止**。どの層も keep-best と入力比番兵で
「停滞で早く返しても品質は下がらない」を守る。探索動学の変更は測ってから（`tools/loop/` のペア比較）。

## 1. 層 A: 実行全体のウォッチドッグ（`V6FinalPort.handleOptimize`）

### 1.1 時間の切り方（予算 `budgetMs`）

| 名前 | 式 | 300 s 予算 | 60 s 予算 | 役割 |
|---|---|---|---|---|
| `minRunMs` | `budget/6`、8〜45 s、予算以下 | 45 s | 10 s | これより前は発火しない |
| `postReserveMs` | `budget/12`、8〜25 s、予算の半分以下 | 25 s | 8 s | 後処理の予約枠。探索は `searchDeadlineMs = hardDeadline − postReserve` で止まる |
| `stallMs`（通常） | `normalStallMs`＝`budget × normalStallFraction(0.9)`、下限 20 s。その値が探索区間 `searchWindowMs` 以上なら `searchWindow × 0.9` へ | 270 s | 46.8 s（52 s 区間へのフォールバック） | 解ける HARD が残る局面の閾値 |
| `stallHardMs`（頭打ち） | `budget/8`、下限 15 s | 37.5 s | 15 s | HARD が床に着いた局面の閾値 |
| `phaseGraceMs` | `budget/40`、2〜15 s | 7.5 s | 2 s | 始まったばかりのフェーズを即殺しない**遅延** |
| `STALL_OVERRIDE_FACTOR` | 2 | — | — | 無改善が閾値の 2 倍なら猶予に関わらず発火 |

`normalStallFraction` は `PolishGate` の `@Volatile`（既定 0.9、UI トグル無し、`PolishGate.snapshot` に載る）。(0,1) の有限値だけ受ける。

### 1.2 発火判定（純関数、`V6FinalPortTest`）

```
watchdogStagnationFired(now, startMs, minRunMs, lastPhaseChangeMs, phaseGraceMs, lastBestImproveMs, effStall)
  = now − start > minRunMs
    ∧ now − lastBestImprove > effStall
    ∧ (now − lastPhaseChange > phaseGraceMs ∨ now − lastBestImprove > effStall × 2)
```

- 時計は 2 本に分ける。`lastBestImproveMs` は**最良が更新された時刻**（フェーズ遷移ではリセットしない）。`lastPhaseChangeMs` は
  進捗文字列の変化。旧実装の `max(両者)` は 20〜90 s ごとのフェーズ遷移で 270 s に届かなかった（3.230.0）。
- フェーズ猶予は**拒否権ではない**。並列 8 ワーカーが 1 本のフェーズ文字列を共有して猶予が永久に塞がる事故（実機 2026-08-19、
  275 s 無改善で未発火）を 3.408.0 で「閾値の 2 倍で上書き発火」に降格した。
- 最良の比較は `better`（hard→weightedScore→total の辞書式、3.287.0 で第 2 キーを total→weightedScore へ）。

### 1.3 実効閾値 `effStall`＝「頭打ち」の判定（`effectiveStallMs`）

```
basePlateau    = (bestHard ≤ hardFloor ∧ nonCovUHard = 0) ∨ wishReached
c3nWallPlateau = nonCovUHard > 0 ∧ nonCovUAllC3n ∧ bestHard ≤ hardFloor + nonCovUHard ∧ c3nWallProven
effStall       = (basePlateau ∨ c3nWallPlateau) ? stallHardMs : stallMs
```

| 床 | 何が「解けない」と言えるか | 算出 |
|---|---|---|
| 構造的 covU 床 `hardFloor` | 有資格者を全員就けても埋まらない席。構造だけで決まり最適化中に不変 | `V6SanityPort.structuralHardFloor`、1 回だけ |
| 非 covU HARD＝0 | groupViol/pref/c3n が残る間は床に見えても「解ける HARD」＝長い閾値で粘る | best と同時に捕捉（`bestNonCovUHard`） |
| c3n 壁 `c3nWallProven` | 残る非 covU HARD が c3n だけで、`ForbiddenDiag`（`V6PortAnalyzer.diagnoseForbiddenRuns`）が全 run の塞がりを証明 | 停滞が `stallHardMs` を超えた後・best 世代ごとに 1 回（約 20 ms）、`(version,result)` を単一 Atomic で保持（3.592.0） |
| 希望衝突の床（E0） | HARD がちょうど希望由来の床で、残る HARD が全て希望由来（`hardAllWishOrigin`） | `wishFloorReached`。配線は `PolishGate.wishConflictFloorMode ≠ OFF` のときだけ（既定 OFF。E0B は後処理の研磨も省く） |

長い閾値を避ける判断は**証明つきの床だけ**。「実現不能希望の件数」を床にした旧版は、pref から対称除外される分が HARD に寄与せず
解ける HARD を早々に諦めていたので、構造的 covU へ是正した。

### 1.4 発火したあと

- `stagnationFired` が立ち、探索は返る。後処理（`runPostOptimization`）は `hardDeadlineMs` まで使える（停滞では止めない）。
- **追加精製 `ExtraRefine`**（後処理予約枠の未使用分で最終盤面起点の keep-best ALNS、3.102.0）は
  `残り ≥ 5 s ∧ 違反が残る ∧ 停止要求なし ∧ 停滞発火なし ∧ ¬structuralHardResidual` のときだけ。上限は `min(残り, postReserveMs)`。
  停滞発火で省くのは「無改善なら早く返す」方針を壊さないため。`extraRefineRequirePostHardDrop`（既定 OFF、backlog #35）は
  「残る HARD が解けないと証明済み」でも省く版＝実質 A/A で信号なし。
- `stagnationFired` は**ラッチ**（一度立つと降りない）。並列ワーカーの `shouldStop` は改善が届けば偽へ戻るので、適応ポートフォリオは
  `stopIsFinal`（締切・キャンセルだけ単調）で「確認窓つきの再確認」をする（3.346.1）。ログは探索終了時点のスナップショットで揃える（3.377.0）。
- 診断ログ `Watchdog` 行: 実効閾値の種別（通常=長／plateau=短／c3n壁=短／希望衝突の床=短）、停滞秒、未発火の理由、進捗報告ぶんの反復数
  （真の総量の 49〜59%＝桁の区別だけに使う、3.375.1）。

### 1.5 据え置きが決まっている値（再提案は計測つきで）

| 値 | 決定 | 根拠 |
|---|---|---|
| `normalStallFraction` 0.9（300 s で 270 s） | 据え置き（2026-10-07「推奨で」） | 0.5 は 3.423.0 で 6 勝 3 敗・非有意、blocked_covu 型に絞っても再現せず（3.447.0）。外部提案「60〜90 s」は前提不成立＝浮いた時間は後処理へ回らない・実機 #1 は 80 s の空白の後に最終改善 |
| 早期終了そのもの | 維持 | 外すと weighted 中央 −3.5%（p≈0.075、非有意）で時間 2.3 倍（3.341.1） |
| 余った予算を soft 研磨へ | 否決（3.341.1） | 時間が余るのは hard=0 のときだけ。穏当版も 2/5 |
| covU-blocked 専用の早期終了 | 却下（3.361.0、実データ多 seed A/B） | 便益が出ない |
| `stallMs = budget/6`（旧 50 s） | 戻さない | HARD=1 を 50 s で諦め残り 250 s を捨てた（歴史的後悔） |

## 2. 層 B: 適応ポートフォリオの再配属（`AdaptiveHypothesisEpochPolicy`）

8 ワーカーの各エポック（量子 5 s／改善直後 8 s、RSI+ は 35 s／45 s）で:

- `improvedThisEpoch = better(eliteReport, preEpochEliteReport)`＝正式比較器。HARD が 1 件減れば weighted/total が同点・悪化でも改善。
  escape ロールが摂動入口に勝っただけでは改善と数えない（3.282.0）。
- `shouldReassign`: W0 は不動。他は「最寄りの他ワーカーと距離 ≤2 セル（盆地の重複）」または「無改善かつ `stagnantEpochs ≥ 1`」で再配属。
  W4 は最初の plateau で ELITE_RELINK へ、以後固定。他 6 本は 6（`personSwapKick` で 7）の脱出役割を機械巡回。
- `intensityFor`: 強度は再配属 2 回ごとに +1（上限 +3）。最初の停滞は「別の考え方を試す」段階で、いきなり最大近傍へ注がない。
- 改善直後の長い量子は**役割が変わった直後は受け取れない**（`carriesImprovingQuantum`、3.308.0）。
- 締切・停止済みならロールを始めない／成果を回収してから締切判定（3.600.0）。

既定 OFF: **停滞時の後処理差し込み `StallPolishInjection`**（`PolishGate.stallPolishInjection`、設定タブからは 3.628.0 で非表示）。
全体最良が `max(20 s, 予算×0.1)` 改善しないとき最良の写しへ後処理を 6 s 上限で当て、良ければ全体最良へ（1 実行 3 回まで）。
採用 32/43 回だが最終は 8 勝 7 敗・重み合計 +1291＝便益が測れず OFF（2026-10-07）。

撤去済み: 残差ベースの 4 段停滞脱出 `adaptiveEscapeControl`／`StagnationEscapeController`（3.306.0→3.409.21 単体 A/B 中立で撤去）、
ロール内並列 SA `portfolioRoleParallelSa`（同）。

## 3. 層 C: RSI ラウンドの focus と早期終了（`runRsi`）

| 機構 | 規則 | 版 |
|---|---|---|
| focus 選択 | 解ける HARD 族を先に（`RsiFocusSelection.maxViolatedFamily`）。件数 0 の族は focus しない（E8）。SOFT へのフォールバックあり | 3.74.0・3.150.0 |
| 静的 covU 床の除外 | covU が `structuralHardFloor` に達したら round 0 から focus 候補から外す（`avoid`） | 3.95.0 |
| 空振り focus の 1R 冷却（E9） | 候補不採用かつ focus 族の件数が減らない「完全空振り」の直後だけ同じ focus を 1 ラウンド避ける。進展ありなら冷却しない | 3.150.0 |
| HF63 の学習 | 直前ラウンドで**実際に focus した族だけ**に投入量 `effortIters` を加算し、`INFEAS_STALL_ITERS`=5000 で「構造的に充足不能」と推定→`dynamicAvoid`。改善を検出したら解除。重みには触れない | 3.213.0・3.231.0・3.409.10 |
| `effortIters` | ラウンド数に応じて動的（`attemptsTarget = ceil((rounds−2)/2)` を 2 で下駄）＝詰んだ族の除外が「残り 2 ラウンドを振り向けられる」タイミングで成立する | 3.231.0 |
| N4 早期終了 | `stagnantRounds ≥ 2 ∧ dynamicAvoid ≠ ∅` のとき、ピボット先が `total` か件数 0 なら終了。狙える SOFT が残れば続ける（HARD 残のまま SOFT 研磨は keep-best で安全） | 3.95.1 |
| EarlyChain | ラウンド境界で `V6LateOperators`（Chain3/4・Rect・BlkN）を当てる | HF361/528/541 移植 |
| c3n の修復先 | `destroyRepairViolations`（hf67 は c3n に作用しないため） | 3.101.0 |

N4 は **動的検知（HF63）だけ**でゲートする。静的 covU 床を混ぜると構造的 covU>0 のデータで round 0 から常時武装し、旧 N4 の
「厳密な部分集合」保証を破る（3.95.1 の実バグ）。

## 4. 層 D: SA/LAHC ラダーと ALNS の再起動

- `SaOptimizer`（Kotlin）と C++ `SaChunk` は同値。`hardStallMs`=2.5 s HARD が下がらなければ PhaseB（LAHC ソフト研磨）へ一方向遷移。
  ラダー境界では reset-to-best 再加熱（`MagiConductor` があれば STRONG_PERTURB／SCALE_TEMP／REHEAT／NOOP の報酬学習）。
- ALNS の再起動摂動は**一律 `strength = 0.18 × explore`**（0.05〜0.6）。非線形スケジュール（2.51.0）は実データ final で +101% 悪化し revert（2.58.0）。
- GLS penalty aging: `GLS_DECAY_EVERY`=256 kick ごとに 80% へ減衰（2.50.0）。ベンチでは中立＝無害で温存（長時間の肥大化防止）。
- 戦略的振動（λ オシレーション、2.52.0）は現実的 NSP で一貫して悪化し 2.55.0 で revert。

## 5. 層 E: 後処理パスの打ち切り

| パス | 停滞の定義 | 動き |
|---|---|---|
| HF80 PostPolish（E10） | best が枠の 1/5（下限 3 s）無改善 | 早期 return。native 区間で無改善なら起点を `started` に引き継ぐ（3.150.0） |
| C1 共同 LNS | `patienceMs` 更新なし／評価数上限 | 打ち切り（ログ「最良が N ms 更新されず打ち切り」、3.342.0） |
| C1 広域ビーム | 最良保持と停滞打ち切り | 3.340.0 |
| 巡回研磨クラスタ | 1 巡で採用 0（`roundApplied == 0`） | 巡を終える（joint 局所最適） |
| 後処理チェーン全体 | `totalApplied == 0` | 停滞検知（N9: 巻き戻したパスの `applied` も数える。0 と数える版 `postChainRollbackCountsZero` は 230 ペアで差なし＝既定 false） |

撤去済み: 採用 0 の巡で LNS・VCR の幅を 2 倍にして再試行する `stallEscalation`（3.511.1 全件無変化、2026-09-25 撤去）。
C3 の 3 者ブロック回転は「主手が 1 手も採れなかった巡と最終巡」だけの脱出手へ格下げ（3.300.0）。

## 6. 測って否決・机上で否決（再提案は計測つきで）

| 案 | 結果 | 記録 |
|---|---|---|
| 戦略的振動（λ オシレーション） | AUC +5〜15% 悪化 | 2.55.0 |
| nonlinear restart | final +101% 悪化 | 2.58.0 |
| GLS パラメータスイープ | 全て +0.0% | 2.56.0 |
| targeted-perturb／big-destroy／softFocusProb 変更 | 同じ hard 床・soft 誤差内 | 3.95.0 |
| 早期終了の余りを soft 研磨へ | 穏当版 2/5 | 3.341.1 |
| covU-blocked 早期終了 | 却下 | 3.361.0 |
| 「修復途中の進捗」を停滞判定に加える | 正式比較器が既に HARD 優先で拾う＝実装不要 | backlog #26（2026-09-22） |
| 同点の盤面を足場にした停滞脱出（プラトー探索 B） | A（後処理を直接）に 10 s 3 勝 27 敗・30 s 9 勝 19 敗 | 2026-10-07 |
| 停滞時の後処理差し込み | 最終 8 勝 7 敗・重み +1291 | 2026-10-07（既定 OFF 温存） |
| `normalStallFraction` 0.5／外部提案 60〜90 s | 非有意・前提不成立 | 3.423.0・3.447.0・2026-10-07 |
| 残差ベース 4 段脱出・ロール内並列 SA | 単体 A/B 中立 | 3.409.21 撤去 |

開いている案: 同点以上を受理する歩き→後処理（プラトー探索 C、A に 32-10／36-6）は**別案として測る**（2026-10-07）。
停滞時の探索半径・職員数・窓長の段階的拡大は backlog #13(a)（測定待ち）。

## 7. 判定に使うログの行

- `Watchdog` 行＝層 A（実効閾値の種別・停滞秒・発火の有無・未発火の理由）。
- `AdaptivePortfolio` 行＝層 B（再配属回数・合計 iter）。`RunMAGI_RSI` 行＝層 C（改善したラウンドと最終ラウンドだけ、焦点の履歴は末尾「戦略変更」1 行）。
- `HF80`／`ExtraRefine` 行＝層 E と追加精製（走らなかった理由を明記）。
- `設定の効き` 行（`TuningTelemetry.summary`）＝トグルがその実行で何をしたか。毎回「観測なし」のトグルは消してよい（3.409.21 の判断基準）。

## 8. 変えるときの手順

1. 層を特定し、この表の数値・規則を直す（Kotlin → C++ → C# の順、同日）。
2. 層 A・B の純関数はテストを更新する（`V6FinalPortTest`・`WishConflictFloorTest`・`HypothesisEpochPolicyTest`、C# は `V6FinalPortWatchdogTest`）。
3. 探索動学なら `tools/loop/run_bench.sh` のペア比較（`MAGI_BENCH_FEATURE`）で採否。実データでは AUC でなく **final** を見る。
4. 採否を `docs/algorithm_portfolio.md`（既定 OFF 表・廃止表・否決表）と `docs/history/` に書き、この資料の該当表へ 1 行足す。
