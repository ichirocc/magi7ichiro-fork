# エンジン／探索フラグ棚卸し（読み取りのみ・2026-10-22）

対象: Android `origin/main` f19fb8cf（3.723.1）、C# `-MAGI_PC` main f34de5e2。どちらのリポジトリも変更していない。
行数は `git archive` で展開した写しを grep と wc で数えた概算。分岐の本体、宣言と KDoc、snapshot/restore の各 1 行を含む。

## 前提（全フラグ共通）

- **@Volatile の理由**: 設定画面（UI スレッド）や `PolishGate.restore`（背景 Worker の再開）が書き、エンジン（Dispatchers.Default や Worker のスレッド）が読む。スレッドをまたいで値を見せるために付いている。実行中に書き換わる経路は見つからなかった。設定のスイッチはどれも `enabled = !ui.running` で、`V6FinalPort` は 3.587.0 から実行の開始時に 5 フラグを `postParams` へ固定する（combineExhaustPairs/lnsAdaptive/aptFairSoftTolerance/countChainPolish と quantitativeRangeEval）。ただし大半のフラグは各パスが `PolishGate` から直接読む。EngineConfig にするなら「実行開始時に 1 回だけ読み取る不変オブジェクト」に揃えるのが自然で、出力は変わらない。
- **背景実行への受け渡し**: `PolishGate.snapshot()/restore()`（V6HotfixPasses.kt 342〜380 行）が 41 個のフラグを inputData に載せている。フラグを消すときはここの鍵も消す。restore は鍵が無ければ値を触らないので、旧版で投入した Work とも互換が保たれる。
- **SearchStrength（3.615.0）**: じっくり＝`combineExhaustPairs`・`wideC3nBreakDays`・`countChainPolish`・`aptFairSoftTolerance` の 4 つを ON、ふつう＝4 つとも OFF（MagiUiState.kt 288〜303 行、C# `UiState.cs` 330〜347 行も同じ）。**この 4 つは否決済みでも削除できない。**
- **画面の実態**: MagiSetupCards に表示されるのは次の項目。ネイティブ加速、Kotlin 照合、仕上げ（softPolish）、探索の強さ、容認 6%（じっくりのときだけ有効）、玉突きで直す（4 択: OFF/PIPELINE/C1/ALL）、入れ替えも使う、採れたら探し直す、必須の違反からも始める、余った時間も使う（ejectionUseLeftover）、c3n 壁で早く止める（c3nWallShortStall）、玉突きの秒数、玉突きの範囲（MONTH/BY_KIND の 2 択）。
  **イベントとセッターは残っているが、どの Composable も発火しない（画面から消えた）もの**: `SetBlockSwapC3nFilter`、`SetLnsAdaptive`、`SetC1MoveARepair`、`SetStallPolishInjection`（後の 2 つは 3.628.0「確実にデメリットがあるものは非表示」による）。
- **C# の状況**: `MagiEngine/V6/PolishGate.cs` にあるのは 26 項目。次は C# に**移植されていない**（README 2026-10-07 行の記録どおり）: 従来の玉突き（c1EjectionChain/allFamilyEjectionChain）、前段・最終の玉突き 5 種、ejectionUseLeftover、softCascade、prePostDescent、stallPolishInjection、PolishGate の lnsAdaptive（C# の ViewModel は `lnsAdaptive: true` を直書き）。C# の設定画面で表示されているのは「容認 6%」だけ。`C1MoveARepairToggle` と `BlockSwapC3nFilterToggle` は `Visibility="Collapsed"`。

凡例: 読み手は main＝本体のうち定義ファイル以外の読み手、test＝JUnit、bench＝tools/loop。

---

## 1. `object PolishGate`（V6HotfixPasses.kt 117〜380 行、41 項目。下表は stageProbe を除く 40 項目）

| # | フラグ | 既定 | どこから設定されるか | 読み手（main / test / bench） | 履歴の判定 | C# | 提案 |
|---|---|---|---|---|---|---|---|
| 1 | wideC3nBreakDays | false | **じっくり** | V6SearchOperators, MagiViewModel, MagiUiState, OpLogText / BitPackGuard, PolishRobustness, SearchStrength, TuningTelemetry3694, WideC3nFixture / – | 最終 OFF 確定（3.449.0・3.463.0「再提案しない」） | WideC3nBreakDays | **EngineConfig に残す**（じっくり経由のため削除不可） |
| 2 | c1MoveARepair | false | 非表示のイベントのみ（3.628.0 で非表示） | C1WindowPolish, MagiViewModel, MagiUiState / C1MoveARepairTest, SearchStrength / – | 否決相当（2026-10-04: 7勝14敗・採用 4/1956）。3.628.0「確実に悪化」 | C1MoveARepair（Collapsed のトグル） | **削除候補**。main 約 30 行（`moveARepairChain` 22 行と分岐・ログ）、UI 約 12 行、テスト 60 行。C# も同量程度。**要ユーザー判断**（3.628.0 の選択は「非表示」で、削除ではなかった） |
| 3 | filterC3nIncrease | true | 非表示のイベント（SetBlockSwapC3nFilter） | AdaptiveBlockSwapPolish, V6FinalPort, MagiViewModel / AdaptiveBlockSwapPolishTest, PolishRobustness, TuningTelemetry3694, V6FinalPortTest / – | 既定 ON（3.518.0）、3.649.0 で厳密な正味差分（出力は OFF 以上） | FilterC3nIncrease | **定着→フラグを外す**。OFF 腕は測定用として EngineConfig に残すのも選択肢。UI の残骸（ui.blockSwapC3nFilter、イベント、セッター）は削除。約 15 行 |
| 4 | rotationDeltaPrescreen | true | コードのみ | C3RotationPolish / PostWasteRemovalTest / WasteRemovalProbe | 出力不変の無駄取り、既定 ON（3.680.0、4 件で盤面一致） | RotationDeltaPrescreen | **定着→フラグを外す**（速度専用。OFF 腕は等価性テストにだけ使われている） |
| 5 | roundPassMemo | true | コードのみ | V6HotfixPasses 内 2 か所 / PostWasteRemovalTest / WasteRemovalProbe | 同上（3.680.0） | RoundPassMemo | **定着→フラグを外す** |
| 6 | hardDeltaPrefilter | true | コードのみ | C1WindowPolish, CyclicSwapWeeklyPolish, HardDelta / HardDeltaPrefilterTest / – | 既定 ON、速度専用で盤面同一（2026-09-25） | HardDeltaPrefilter | **定着→フラグを外す**。HardDeltaPrefilterTest の OFF 比較腕は「測定用」として残すか、テストを書き換える |
| 7 | wishConflictFloorMode（OFF/E0A/E0B） | OFF | コードとベンチ（3.615.0 で UI は常時 OFF） | V6FinalPort, MagiViewModel / StallEscapeSpecTest / HandleOptimizeBench 腕 `wishfloor` | 測定中（3.613.0。2026-10-19 の再測定で区間が 0 をまたぎ、未決） | WishConflictFloorMode | **EngineConfig に「測定用」として残す** |
| 8 | c1EjectionChain | false | **UI「玉突きで直す＝期間の制約から」** | C1EjectionChainPolish, MagiUiState / C1EjectionChainPolishTest, EjectionChainPipelineTest, SearchStrength / LoopBench, probe-EJ, probe-AF | 置き場所の測定で便益が測れず（2026-10-06）、パイプラインが上位（3.656.0） | なし（未移植） | **削除不可（UI から ON になる）**。EjectionChainMode.C1 と一緒に消すなら要ユーザー判断 |
| 9 | allFamilyEjectionChain | false | **UI「すべての違反から」** | 同上と OptimizationRepositoryBgTest、HandleOptimizeBench | 効果は確認できず（3.618.0、p=0.83） | なし | 同上（mode ALL） |
| 10 | ejectionPipelineFocus | BOTH | UI のモードから派生（PIPELINE→BOTH、その他→OFF） | MagiUiState, V6HotfixPasses / EjectionChainPipelineTest, SearchStrength / HandleOptimizeBench, LoopBench | 既定 ON（3.656.0、76勝0負） | EjectionPipelineFocus | **EngineConfig に残す**（UI の 4 択の写像）。C1/SOFT の値は使われていない |
| 11 | ejectionPipelineAfterRepair | true | コードとベンチ | V6HotfixPasses 内 / SearchStrength / HandleOptimizeBench, LoopBench | 既定 ON（3.656.0。false＝C1 LNS 直後で必須増 1、不合格） | EjectionPipelineAfterRepair | **定着→フラグを外す**（false 経路は否決済み。`pipelineOn && !pipelineAtEnd` の分岐を消す） |
| 12 | ejectionPipelineRounds | true | **UI「採れたら探し直す」** | EjectionChainPipeline, MagiSetupCards, MagiUiState, MagiViewModel / SearchStrength / LoopBench | 既定 ON（3.657.0、45勝0負） | EjectionPipelineRounds | 定着しているが画面に出ている。**要ユーザー判断**（3.657.0「フラグは UI で表す」） |
| 13 | ejectionPipelineHardLeg | false | **UI「必須の違反からも始める」** | 同上 | 不合格・既定 OFF（3.657.0） | EjectionPipelineHardLeg | **EngineConfig に残す**（利用者が使う） |
| 14 | ejectionChainSwapMoves | true | **UI「入れ替えも使う」** | EjectionChainPipeline, V6SwapSuggester, MagiUiState, MagiViewModel / SearchStrength / – | 既定 ON（3.618.0 以来） | EjectionChainSwapMoves | **EngineConfig に残す**（UI あり。消すなら要判断） |
| 15 | ejectionChainMaxMillis | 10 000 | **UI 秒数**（HF77、3.659.0） | EjectionChainPipeline, V6SwapSuggester, MagiUiState, MagiViewModel / OptimizationRepositoryBgTest / – | 値は利用者の指示 | EjectionChainMaxMillis（volatile なし） | **EngineConfig に残す**（数値パラメータ） |
| 16 | ejectionUseLeftover | false | **UI「余った時間も使う」** | MagiSetupCards, MagiUiState, MagiViewModel, V6HotfixPasses / – / HandleOptimizeBench | **否決**（3.667.0、16 対で候補切れ） | なし | 否決済みなのに画面に出ている。**要ユーザー判断**（スイッチを消せば削除候補。main 約 12 行、UI 約 15 行） |
| 17 | hardEjectionChainEarly | false | ベンチとテストのみ（`!pipelineOn` のときだけ効く） | V6HotfixPasses 内 / ExtWishOptimizeTest / LoopBench | 測って効果なし（2026-10-06、6勝5敗1分） | なし | **削除候補**（どの経路からも ON にならない） |
| 18 | allEjectionChainEarly | false | 同上 | 同上 | 同上 | なし | **削除候補** |
| 19 | allEjectionChainFinal | false | 同上 | 同上 | 置き場所の測定で他の 4 案に必須増あり（2026-10-06） | なし | **削除候補** |
| 20 | hardEjectionChainRetry | false | 同上 | 同上 | 同上（測定のみ・既定 OFF） | なし | **削除候補** |
| 21 | allEjectionChainAfterRepair | false | 同上 | 同上 | 決定的には 99勝0敗だが実時間で便益が測れず（2026-10-06）→ パイプラインが同じ位置を継いだ（3.656.0） | なし | **削除候補**。17〜21 の 5 本の合計: main 約 65 行（4 分岐で約 45 行、宣言と KDoc で約 15 行、snapshot/restore）。LoopBench の腕、ExtWishOptimizeTest の参照（修正が必要） |
| 22 | softCascade | false | コードのみ（**ベンチの腕も無い**） | SoftCascadePolish, V6HotfixPasses / ExtWishOptimizeTest, SoftCascadePolishTest / – | 3.629.0「既定 OFF・測定が終わるまで」。**測定の記録が無い** | なし | **要ユーザー判断**（測るか消すか）。消すと main 約 345 行（SoftCascadePolish.kt 331 行と分岐・params）、テスト 77 行 |
| 23 | normalStallFraction | 0.9 | コードとベンチ | V6FinalPort / OptimizationRepositoryBg, StallEscapeSpec, V6FinalPortTest / HandleOptimizeBench | 0.9 で据え置き確定（3.447.0・2026-10-07） | NormalStallFraction | **EngineConfig に残す**（数値パラメータ、HF77） |
| 24 | combineExhaustPairs | false | **じっくり** | 10 ファイル（AptFair, C1Repair, C1Window, C3Family, Range, V6FinalPort, V6NativeOptimizer, UI 3 つ） / PolishRobustness, SearchStrength, TuningTelemetry3694 / LoopBench | 最終 OFF 確定（3.519.0「再提案しない、撤去はしない」） | CombineExhaustPairs | **EngineConfig に残す**（じっくり経由のため削除不可） |
| 25 | lnsAdaptive | true | 非表示のイベント（SetLnsAdaptive） | MagiSetupCards（参照のみ）, MagiUiState, MagiViewModel, V6FinalPort, V6NativeOptimizer / PolishRobustness, TuningTelemetry3694 / LoopBench | 既定 ON（3.518.0） | **PolishGate には無い**（ViewModel が true を直書き、Params.LnsAdaptive） | **定着→フラグを外す**。注意: `PostOptimizationParams.lnsAdaptive` の既定は **false** のままで、本番が true を渡している。外すときは Params 側の既定も true にするか、引数ごと消す。LoopBench の腕 `lnsadaptive`（false 対 true）は古い |
| 26 | personSwapKick | true | コードとベンチ | AdaptiveHypothesisEpochPolicy, V6NativeOptimizer / HypothesisEpochPolicy, ManualPin, PersonSwapKick, WishPinStrict, ZeroCapExclusion / PersonSwapBench | 既定 ON（3.519.0） | PersonSwapKick | **定着→フラグを外す**（PersonSwapBench の OFF 腕を残すなら「測定用」） |
| 27 | wishPinStrict | true | コードのみ（「false は旧挙動で、tools/loop の A/B 用」） | 9 ファイル（CovORelief, EliteIntegration, EliteRelinking, HardRepairCore, MirrorCore, Rsi, SaOptimizer, V6NativeOptimizer, V6PortAnalyzer） / ManualPin, PinInvariant, V6PortAnalyzerTest, WishPinStrictTest / – | 利用者決定の規則 A（2026-09-25）で既定 ON | WishPinStrict | **定着→フラグを外す**（業務規則なので切る意味が無い）。false 経路の削除は 9 ファイルにまたがる。WishPinStrictTest の旧挙動比較の書き換えが必要 |
| 28 | aptFairSoftTolerance | false | **じっくり** | AptFairPolish, V6FinalPort, V6NativeOptimizer, UI 3 つ / DeterministicPostChain, PolishRobustness, SearchStrength, SessionRegression, TuningTelemetry3694 / LoopBench | 既定 OFF（3.536.0、HF77 の 6%） | AptFairSoftTolerance | **EngineConfig に残す** |
| 29 | countChainPolish | false | **じっくり** | UI 3 つ, V6FinalPort, V6NativeOptimizer / OptimizationRepositoryBg, PolishRobustness, SearchStrength, TuningTelemetry3694 / – | 不合格・既定 OFF（3.540.0） | CountChainPolish | **EngineConfig に残す** |
| 30 | postChainRollbackCountsZero | false | ベンチ（n9rollback） | V6HotfixPasses 内 3 か所 / StallEscapeSpecTest / LoopBench | 不合格（2026-09-24、p=0.69）。**「フラグとコードは残す」**（#36） | PostChainRollbackCountsZero | 削除候補だが**要ユーザー判断**（history に残す明記あり）。約 10 行 |
| 31 | stallPolishInjection | false | 非表示のイベントのみ（3.628.0） | StallPolishInjection, V6NativeOptimizer, MagiUiState, MagiViewModel / StallEscapeSpec, StallPolishInjectionTest / – | 3.628.0「確実に悪化」（15 組で重み +1291） | なし（C# のテストに名前があるだけ） | **削除候補**。main 約 90 行（V6NativeOptimizer の injector 約 57 行、StallPolishInjection.kt 20 行、UI 約 12 行）、テスト 50 行。**要ユーザー判断**（選択は「非表示」だった） |
| 32 | prePostDescent | false | ベンチ（prepostdescent） | PrePostDescent, V6HotfixPasses / PrePostDescentTest / LoopBench | **否決・「測定用に残す」**（2026-10-07、stall_escape §10） | なし | **要ユーザー判断**（残す明記あり）。消すと main 約 112 行、テスト 93 行 |
| 33 | c3nWallShortStall | false | **UI「c3n 壁で早く止める」** | MagiSetupCards, MagiUiState, MagiViewModel, V6FinalPort / StallEscapeSpec / HandleOptimizeBench `c3nwall` | 既定 OFF に決定（3.673.0、短縮は損） | C3nWallShortStall | **EngineConfig に残す**（UI あり。消すなら要判断） |
| 34 | floorShortStall | true | ベンチ | V6FinalPort / StallEscapeSpec / HandleOptimizeBench | **測定中**（「採否は測ってから」、実機ログ 2026-10-15） | FloorShortStall | **EngineConfig に「測定用」として残す** |
| 35 | c3nWallLegacy | false | ベンチの基準腕 | V6FinalPort / StallEscapeSpec / HandleOptimizeBench | 測定用の HEAD 基準（3.641〜3.643.0） | C3nWallLegacy | **EngineConfig に「測定用」として残す**。stall_escape の計測を閉じるなら削除候補（約 5 行と C3nWallProof.legacy） |
| 36 | lateOpStopPropagation | true | ベンチの切り分け | V6LateOperators / StallEscapeSpec / HandleOptimizeBench | 3.642.0 で既定維持（測定用） | LateOpStopPropagation | **定着→フラグを外す**か、**測定用**として残す（false は HEAD とも一致しない切り分け用） |
| 37 | c3nWallDeepCheck | false | ベンチの腕 `deep` | V6FinalPort / StallEscapeSpec / HandleOptimizeBench | **測定中**（フィクスチャ待ち、§11） | C3nWallDeepCheck | **EngineConfig に「測定用」として残す**（c3nWallShortStall=false のもとでは効き所が狭いので要確認） |
| 38 | adaptiveStall | false | ベンチの腕 `adaptive` | V6FinalPort 3 か所 / StallEscapeSpec / HandleOptimizeBench | **否決**（2026-10-09）。backlog #43③「**腕は残置**」 | AdaptiveStall | **要ユーザー判断**（残す明記あり）。消すと main 約 25 行（adaptiveStallMs とログ） |
| 39 | floorCountsUpperZero | true | ベンチの腕 `floorup0` | V6FinalPort / V6PortAnalyzerTest / HandleOptimizeBench | 既定 ON（3.709.0、時間 −74%・結果同一） | FloorCountsUpperZero | **定着→フラグを外す**（OFF 腕を測定用に残す選択肢あり） |
| 40 | stageProbe（関数） | null | テストのみ | V6HotfixPasses / ExtWishOptimizeTest / – | 計測用の差し込み口 | – | フラグではない。**テスト用フックとして残す** |

**PolishGate の外にあるゲート**

| フラグ | 既定 | 設定元 | 判定 | C# | 提案 |
|---|---|---|---|---|---|
| AptFairPolish.heavySoftGuard | true | **UI「容認 6% で重い違反の増加も許す」**（反転、じっくりのときだけ有効） | 測定（3.637/3.638.0） | HeavySoftGuard | EngineConfig に残す |
| C1EjectionChainPolish.defaultHoleFocus / defaultHoleSoftOnly | true/true | **UI「玉突きの範囲」**（MONTH/BY_KIND。NEARBY は 3.628.0 で非表示） | 3.625.0 | DefaultHoleFocus / DefaultHoleSoftOnly | EngineConfig に残す（NEARBY の enum 値は削除候補） |
| C1EjectionChainPolish.defaultCrossFamily / defaultMaxDepth / defaultNarrow / entryProbe | true/8/false/null | probe のみ（defaultMaxDepth と defaultNarrow は**読み手ゼロ**、定義ファイル内だけ） | 測定用（v1/v2 比較） | DefaultCrossFamily ほか | defaultCrossFamily は**定着→外す**。defaultNarrow と defaultMaxDepth（Config の既定値として使うだけ）は**削除候補**（約 10 行） |
| EjectionChainPipeline.shallowOnly | false | LoopBench の A2/A3 腕 | 測定用 | ShallowOnly | 「測定用」として残すか、削除候補（約 5 行） |
| C1JointLnsPolish.deltaChildEvalDefault | true | なし（3.615.0 で「案 A 常時 ON」） | 既定 ON（3.614.0、p=0.049） | DeltaChildEvalDefault | **定着→外す**（PREREG2 の probe の腕だけが使う） |
| V6SwapSuggester.restoreWishLocked | true | なし（V6SwapSuggesterTest） | 手直し提案の既定 | RestoreWishLocked | 探索フラグではない（提案器）。**定着→外す**のも選択肢 |
| NativeGate.userEnabled / parityCheckEnabled | true/true | **UI**（ネイティブ加速、Kotlin 照合） | 2 層番兵の根幹 | C# は常に非該当 | **残す**（CLAUDE.md「削らない」）。EngineConfig に入れるかは整理の範囲次第 |
| RELAX_TRIAL_ENABLED（const） | false | なし | 3.620.0「画面から外すだけ・コードは残す」（S6）。3.722.0 で承認フロー R1〜R5 を追加 | RelaxTrialEnabled | **探索フラグではない**。対象外として据え置く（利用者決定） |

## 2. PostOptimizationParams の真偽値（V6HotfixPasses.kt 536〜651 行）

本番は `V6FinalPort.kt:875` と `V6NativeOptimizer.kt:894` の 2 か所で生成し、5 項目だけを PolishGate から渡す。ほかはすべて既定値のまま。

| フラグ | 既定 | 設定元 | 判定 | C#（同名 PascalCase） | 提案 |
|---|---|---|---|---|---|
| componentRepairEnabled / componentRepairFinal | true/true | LoopBench（既定の比較） | 既定 ON（3.505.1/3.505.4） | あり | **定着→外す**（ViolationComponentRepairTest と LoopFeatureRegressionTest の OFF 腕は要書き換え） |
| covOReliefEnabled | true | LoopBench `covorelief` | 最終段を採用（3.554.0） | あり | **定着→外す** |
| postChainRunningKeepBest | true | LoopBench `runningkeepbest`/`rkbstruct` | 既定 ON（3.610.0） | あり | **定着→外す**（測定腕を残すなら EngineConfig へ） |
| deterministic | false | ベンチとテスト | 再現性のための仕様（Iteration 7） | あり | **EngineConfig に残す**（ベンチの基盤） |
| c2PolishEnabled / c2PolishReactivate | false | LoopBench | 不合格（iter15・3.582.0）。backlog「**コードは残す**」 | あり | **要ユーザー判断**（削除: C2Polish.kt 81 行、テスト 75 行、分岐 約 8 行） |
| c42FlowPolishEnabled / c42FlowPolishReactivate | false | LoopBench | 不合格（iter18・3.583.0）。「コードは残す」が明記 | あり | **要ユーザー判断**（C42FlowPolish.kt 126 行、テスト 75 行） |
| c1ComponentRepair / c1ComponentRepairReactivate | false | LoopBench | 実データ 4 件で ON=OFF（3.586.0） | あり | 削除候補（**要判断**、#26 一括） |
| c3nMarginLnsEnabled / c3nMarginLnsReactivate | false | LoopBench | ON=OFF（3.586.0） | あり | 削除候補（**要判断**。C3nMarginLnsPolish.kt 146 行、テスト 135 行） |
| countChainEnabled | false | **PolishGate.countChainPolish（じっくり）** | – | あり | **残す**（じっくり経由） |
| countChainReactivate | false | LoopBench | 不合格（3.584.0）。「コードは残す」 | あり | **要判断**（分岐 約 3 行のみ） |
| restZeroWindowLnsEnabled | false | LoopBench `restzero` | **利用者決定**で OFF（3.555.0） | あり | **要ユーザー判断**（RestZeroWindowLns.kt 291 行、テスト 61 行） |
| softCascadeEnabled | =PolishGate.softCascade | – | 1 章の #22 と同じ | なし | 1 章の #22 と同じ |
| postChainRollbackCountsZero | =PolishGate | – | 1 章の #30 と同じ | あり | 1 章の #30 と同じ |
| lnsAdaptive | **false**（本番は true を渡す） | PolishGate | 1 章の #25 と同じ | あり | 1 章の #25 と同じ。既定値のずれを解消する |
| quantitativeRangeEval | false | LoopBench `quantrange` のみ（本番は常に false） | 不合格（iter21・3.512.0） | あり（Problem, Evaluator, DeltaEvaluator ほか） | 削除候補だが**巨大**: main 約 50 ファイルと `magi_native.cpp`、C# の評価器。ほとんどが引数の受け渡しだけの行で、数百行規模。パリティとチェッカーに触れるので**要ユーザー判断・別便を推奨** |
| combineExhaustPairs / aptFairSoftTolerance | false | PolishGate（じっくり） | – | あり | 残す |
| fairAchievementDirection | false | LoopBench | 不合格（3.591.0） | あり（FairAchievementDirectionTest） | 削除候補（AptFairPolish の 2 分岐、約 20 行）。**要判断**（「将来の再評価用に残す」の記載あり） |
| dayAssignIdentityFallback | false | LoopBench | 不合格（3.598.0、ほぼ何もしない）。「フラグは将来の再評価用に残す」 | あり | **要判断**（約 10 行） |

## 3. SaParams / V6OptimizerOptions

| フラグ | 既定 | 設定元 | 判定 | C# | 提案 |
|---|---|---|---|---|---|
| SaParams.softPolish / V6OptimizerOptions.softPolish | false / true | **UI「仕上げ」** | 既定 ON | あり（SoftPolishToggle） | EngineConfig に残す |
| SaParams.conductor | true | false にする経路が無い | MagiConductor 既定 ON | あり | **定着→外す**候補（約 4 行）。履歴は未確認 |
| V6OptimizerOptions.rectSwap | true | 盤面データ `optFlags.rectSwap`（HF532 恒久 ON） | データ由来 | あり | 対象外（保存データの互換） |
| V6OptimizerOptions.tabu | false | ロール割当で自動（BASELINE_REFINE 以外は ON） | 設計の一部 | あり | フラグではない。残す |
| V6OptimizerOptions.postPolish | true | 呼び出し側の構造（handleOptimize は false） | 構造上の切替 | あり | 残す |
| V6OptimizerOptions.quantitativeRangeEval | false | 2 章と同じ | – | – | 2 章と同じ |
| V6OptimizerOptions.rsiFocusRotationPersist | false | PortfolioBudgetBench のみ | 有意差なし（#28）。「フラグ・コードは残す」 | RsiFocusRotationPersist | **要判断**（約 15 行、3 ファイル） |

---

## 4. 提案の集計

| 提案 | 対象 | 削除の概算 |
|---|---|---|
| **A. 定着→フラグを外して常に ON の経路だけ残す**（測定腕が要るものは EngineConfig の「測定用」へ） | filterC3nIncrease(+UI 残骸)、rotationDeltaPrescreen、roundPassMemo、hardDeltaPrefilter、ejectionPipelineAfterRepair、lnsAdaptive(+UI 残骸)、personSwapKick、wishPinStrict、floorCountsUpperZero、lateOpStopPropagation、componentRepairEnabled/Final、covOReliefEnabled、postChainRunningKeepBest、deltaChildEvalDefault、defaultCrossFamily、SaParams.conductor、(restoreWishLocked) | 1 本あたり 3〜15 行。wishPinStrict だけは 9 ファイルにまたがり約 40 行。合計 約 150〜200 行 |
| **B. 削除候補（どの経路からも ON にならない・否決済み・残す明記なし）** | hardEjectionChainEarly、allEjectionChainEarly、allEjectionChainFinal、hardEjectionChainRetry、allEjectionChainAfterRepair、defaultNarrow、defaultMaxDepth（変数のみ）、EjectionChainScope.NEARBY（非表示） | main 約 80 行。LoopBench の腕 5 本。ExtWishOptimizeTest の参照を修正 |
| **B′. 削除候補だがユーザーの確認が要る**（非表示にしただけ、または history に「残す」の明記あり） | c1MoveARepair、stallPolishInjection、prePostDescent、adaptiveStall、postChainRollbackCountsZero、c2Polish*、c42FlowPolish*、c1ComponentRepair*、c3nMarginLns*、countChainReactivate、fairAchievementDirection、dayAssignIdentityFallback、rsiFocusRotationPersist、restZeroWindowLnsEnabled、softCascade（未測定）、quantitativeRangeEval（巨大） | quantitativeRangeEval を除いて main 約 1,500 行、テスト 約 900 行（SoftCascade 345/77、RestZero 約 300/61、C3nMargin 約 150/135、C42Flow 約 135/75、C2 約 90/75、PrePostDescent 約 112/93、stallPolishInjection 約 90/50、c1MoveARepair 約 42/60 ほか） |
| **C. EngineConfig に残す（利用者が使う・じっくり・数値）** | wideC3nBreakDays、combineExhaustPairs、countChainPolish(=countChainEnabled)、aptFairSoftTolerance（以上 4 つがじっくり）、heavySoftGuard、ejectionChainMode(c1EjectionChain/allFamilyEjectionChain/ejectionPipelineFocus)、ejectionPipelineRounds、ejectionPipelineHardLeg、ejectionChainSwapMoves、ejectionChainMaxMillis、ejectionUseLeftover、c3nWallShortStall、defaultHoleFocus/SoftOnly、normalStallFraction、softPolish、NativeGate×2、deterministic | 0 |
| **C′. EngineConfig に「測定用」として残す** | wishConflictFloorMode、floorShortStall、c3nWallLegacy、c3nWallDeepCheck、shallowOnly、entryProbe/stageProbe（フック） | 0 |

**EngineConfig の画面対応（C の主要部分）**

| EngineConfig 名（案） | 既定 | 画面 |
|---|---|---|
| searchStrength → {combineExhaustPairs, wideC3nBreakDays, countChain, aptFairSoftTolerance} | NORMAL＝すべて false | 探索の強さ（ふつう/じっくり） |
| aptFairHeavySoftGuard | true | 容認 6% で重い違反の増加も許す（反転、じっくりのときだけ） |
| ejectionMode（OFF/PIPELINE/C1/ALL） | PIPELINE | 玉突きで直す |
| ejectionSwapMoves | true | 入れ替えも使う |
| ejectionPipelineRounds | true | 採れたら探し直す |
| ejectionPipelineHardLeg | false | 必須の違反からも始める |
| ejectionUseLeftover | false | 余った時間も使う |
| ejectionChainMaxMillis | 10 000 | 玉突きの秒数 |
| ejectionScope（MONTH/BY_KIND） | BY_KIND（UiState の既定。C1EjectionChainPolish の変数の既定 true/true と一致） | 玉突きの範囲 |
| c3nWallShortStall | false | c3n 壁で早く止める |
| softPolish | true | 仕上げ |
| nativeAccel / parityCheck | true/true | ネイティブ加速 / Kotlin 照合 |

## 5. ユーザーの判断が要る事項

1. **画面のスイッチを消すか**: `ejectionUseLeftover`（3.667.0 で否決済みなのに表示中）、`ejectionPipelineRounds`（定着済みだが、3.657.0「フラグは UI で表す」で表示）、`ejectionChainSwapMoves`、`c3nWallShortStall`（3.673.0 で OFF 決定、表示中）、玉突きモードの C1/ALL（従来の常時フル。パイプラインが上位）。消すなら各フラグは B に移る。
2. **非表示にしたスイッチをコードごと消すか**: `c1MoveARepair`・`stallPolishInjection`（3.628.0 の選択は「非表示」）、`blockSwapC3nFilter`・`lnsAdaptive` のイベントとセッターの残骸。C# の Collapsed トグル 2 つも同じ扱いになる。
3. **history に「腕は残す」「コードは残す」と明記された測定腕を消すか**: prePostDescent（「測定用に残す」）、adaptiveStall（「腕は残置」）、postChainRollbackCountsZero・rsiFocusRotationPersist・c2/c42/countChain の Reactivate・dayAssignIdentityFallback・fairAchievementDirection（「将来の再評価用に残す」）。
4. **restZeroWindowLnsEnabled**: 利用者決定で OFF（3.555.0）。ユーザー指示で作った機構なので、消すかどうかは本人に聞く。
5. **softCascade**: 3.629.0「測定が終わるまで既定 OFF」のまま測定の記録もベンチの腕も無い。測るか消すかを決めてもらう。
6. **quantitativeRangeEval**: 否決済み。ただし評価器、C++、C#、チェッカーの引数にまで広がっている。今回の便に入れるか、別便にするか。
7. **定着フラグの測定腕を残すか**（A の各項目）: 特に personSwapKick（PersonSwapBench）、floorCountsUpperZero（`floorup0`）、hardDeltaPrefilter と rotationDeltaPrescreen（等価性テスト）、wishPinStrict（旧挙動の A/B）。腕を消すと、対応する OFF 比較テストの書き換えも必要になる。

## 6. 進め方の注意（出力を変えないために）

- history の「既定 OFF 台帳の整理①〜⑪」（2026-09-25）と同じ手順で進める。1 件ずつ撤去し、ホストテスト、決定的 runPostOptimization（実データ 4 件 × seed 1,2）の**盤面ハッシュ 8/8 一致**、C# の同日同期を確認する。
- A（外す）で既定の挙動が変わりうる箇所:
  - `PostOptimizationParams.lnsAdaptive` の既定は false。ベンチやテストで `PostOptimizationParams()` を素で作ると OFF になる。外して常に ON にすると、そのベンチやテストの出力が変わる（本番は不変）。
  - `wishPinStrict`、`componentRepairEnabled` は false を指定するテストがある。
- `PolishGate.snapshot/restore` の鍵を消すのは安全（restore は無い鍵を無視する）。逆に旧版で投入した Work に消したフラグの鍵が入っていても無視される。
- C# は PascalCase の同名で 26 項目ある。未移植の 10 本（従来の玉突き系、softCascade、prePostDescent、stallPolishInjection、ejectionUseLeftover）は C# 側の作業が要らない。

## 決定（2026-10-22 ユーザー「推奨で」）
フラグ整理の方針（定着＝外す、否決で到達不能＝消す、じっくり・画面・測定中＝EngineConfig）に沿って、判断待ちの 7 点を次のとおり決める。
1. 画面のスイッチ: 否決済みの ejectionUseLeftover・c3nWallShortStall は消す。定着した ejectionPipelineRounds・ejectionChainSwapMoves は常に ON にしてスイッチを消す。玉突きモードの C1／ALL は利用者が選ぶ機能なので残す。
2. 非表示のスイッチ（c1MoveARepair・stallPolishInjection、blockSwapC3nFilter／lnsAdaptive の残骸、C# の Collapsed トグル 2 つ）はコードごと消す。
3. history で「残す」とした腕（prePostDescent・adaptiveStall・postChainRollbackCountsZero・rsiFocusRotationPersist・c2/c42/countChain の Reactivate・dayAssignIdentityFallback・fairAchievementDirection）は消す。消した版を history に残し、戻すときはその版から取り出す。
4. restZeroWindowLnsEnabled（3.555.0 で OFF）は消す。
5. softCascade は測定の記録が無いので消す。
6. quantitativeRangeEval は評価器・C++・C# にまたがるので、P2 の最後の別コミット（P2d）で消す。
7. 定着フラグの OFF 比較腕（PersonSwapBench・floorup0 など）は消す。挙動を確かめるテストは ON の経路の検証に書き換えて残す。
いずれも既定の出力は不変。lnsAdaptive を常に ON にすると `PostOptimizationParams()` を素で作るベンチ・テストの出力が変わるので、それらは本番と同じ既定へ揃える。

## 進捗（この表は 2026-10-22 の棚卸しのまま。片付けた項目をここに足す）
- P2a〜P2c: 1 章 #10〜#16・#23・#24・#25・#28・#29・#33〜#35・#37〜#39 と玉突きの範囲 2 つを `EngineConfig` へ移した（エンジンは入口の写しだけを読む）。
- P2d-①: #17〜#21、`defaultNarrow`・`defaultMaxDepth`、`EjectionChainScope.NEARBY` を削除。
