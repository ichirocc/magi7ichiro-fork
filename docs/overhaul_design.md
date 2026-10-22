# MAGI ShiftOptimizer 段階的全面改修 設計書（完全版）

- 版: 2026-10-22 完全版（同日の下書きを置き換え）。決定: ユーザー「全面改修する」（2026-10-22）。
- 対象: `ichirocc/magi7ichiro-fork`（Kotlin が正）。C++ `app/src/main/cpp/magi_native.cpp` と C# `ichirocc/-MAGI_PC` は同値の移植で、各段を同日に同期する。
- 根拠: `docs/architecture.md`・`docs/sudo_model.md`・`docs/business-logic.md`・`docs/data-models.md`・`docs/stall_escape.md`・
  `docs/safety_inventory.md`（Q-02/Q-07 の棚卸し）と 3.723.0 時点のコード。数字は 2026-10-22 の実測。推定には【仮定】と書く。
- 本書の位置づけ: 何を変え、何を変えないか、どの順で、何をもって合格とするかの正。各段の作業記録は従来どおり `docs/history/` に残す。

---

## 1. 目的と非目的

### 1.1 目的
1. 正しさを守る仕組み（チェッカー・番兵・パリティ）はそのままに、**同じ意味を複数の場所で書いている箇所を 1 か所にまとめる**。
   対象は時間予算、探索フラグ、制約の「位置と説明」、ViewModel の責務。
2. 改修の各段で**最適化の結果が変わっていないことを機械的に示せる**ようにする（安全網を先に作る）。
3. テストで守られていない安全機構（棚卸しで見つかった穴）を埋める。
4. Kotlin・C++・C# の対応を 1 対 1 に保ち、C# の同期作業を軽くする。

### 1.2 非目的（この改修ではやらない）
- 重み・閾値・データ値の変更（HF77）。`MirrorKeys.weights` が唯一の正のまま。
- 探索の動き（受理・近傍・停止・配分の式）の変更。変えるものは既定 OFF のフラグで別途測ってから決める（§7）。
- 否決済みの案の再導入（§9.2）。
- 業務仕様の変更。グループ上限0（Q-05）と CSV の職員 ID 列（Q-06）は、ユーザーの決定後に別の段で扱う（§6 P8・P9）。
- 一括の改名・ディレクトリの総入れ替え。C# との対応を追えなくなる。

---

## 2. 現状（2026-10-22、3.723.0）

### 2.1 規模
| 層 | 行数 | 主なファイル（行） |
|---|---|---|
| `v6/`（エンジン） | 31,167 | `V6NativeOptimizer` 2,360・`V6SanityPort` 2,016・`V6FinalPort` 1,533・`V6HotfixPasses` 1,460・`V6PortAnalyzer` 1,233 |
| `ui/` | 23,874 | `MagiViewModel` 4,030（fun/class 209）・`MagiScheduleViews` 2,435・`MagiDashboardCards` 2,137・`CellSheetLogic` 2,012・`MagiApp` 1,584 |
| `model/` | 583 | `MagiState`・`StateParser` |
| テスト | 232 クラス | JUnit（ホスト JVM で全件、CI `v6-engine-check`） |
| C# | 約 99,000（cs） | `MagiViewModel` は partial 14 ファイルに分割済み。最大は `ScheduleView.xaml.cs` 3,168・`EditView.xaml.cs` 2,804 |

### 2.2 すでにある土台（壊さない・作り直さない）
| 土台 | 中身 | 守っているテスト・CI |
|---|---|---|
| 判定の正 | `UnifiedViolationChecker`（`MirrorCore.kt`）。21 族、`breakdown` は `MirrorKeys.all` で初期化 | チェッカー系 JUnit、`ObjectiveParityTest` |
| 目的関数の一致 | `Evaluator`／`DeltaEvaluator` は同じ式。SA が Δ×フルを照合 | Δ=フルのランダム手テスト、C++ `host_parity_bench` |
| 言語をまたぐ一致 | C++ は Kotlin と同じ値（`*_eval_expected.txt`） | CI `native-parity`、`NativeParityFixtureTest` |
| 2 層番兵 | C++ 自己整合＋Kotlin `fullEval` 照合。不一致で `NativeGate` が閉じ Kotlin へ退化 | C++ harness（JUnit では未通過＝§5 の穴） |
| keep-best | `reportComparator`（hard→weightedScore→total）が単一ソース | 比較器テスト |
| 置けるかの判定 | `Problem.mayPlace`／`mayPlaceAt`（候補生成・入口 hf66・最終番兵が共有）、静的表 `c3wBan`・`extBanned` | 番兵・HardDelta 系テスト |
| 最終番兵 | `checkResultWorse`（入力より悪ければ入力へ戻す） | 番兵テスト |
| 保存 | 世代・所有権（3.485〜3.487）、`RunFiles` の原子置換、`BgHandoff`、保存失敗の再試行 | `SaveGateTest` ほか（ViewModel 層の配線は未テスト＝§5） |
| UI の裁定 | `MagiRoot`／`MagiMediator`／`MagiArbiter`／`MagiEvent`（3.558〜3.565 で再構成済み）。派生値は 4 つのビューデータ | ホストテスト白名単 |
| 書き込み口 | `ScheduleWriteSitesTest` が盤面を書き換えてよい関数を固定 | 同テスト |
| 版の採番 | `tools/check_version.py`（U-04、PR #393） | CI `design-lint` |

### 2.3 弱点（コードの根拠つき）
| ID | 弱点 | 根拠 | 影響 |
|---|---|---|---|
| W1 | 時間予算の定義が分散（M-03/M-04） | `V6FinalPort.kt:250-289`（`normalStallMs`）・`:574`（`budgetMs`）・`:868/:888`（締切）、`V6NativeOptimizer.kt:1344`（`SaParams`）、`V6HotfixPasses.kt:907`（60 s 固定）、`EjectionChainPipeline.kt:44`、`CombinatorialRepair.kt:44`。予算・期限の識別子は v6 全体で約 150 か所。時計 `EngineClock.kt`（23 行）に残予算の概念は無い | 配分の不整合（RSI+ が短い予算で全体を超える＝backlog #16）、読み解きの難しさ |
| W2 | 探索フラグが書き換え可能な共有オブジェクト（U-02） | `object PolishGate`（`V6HotfixPasses.kt:117`）に `@Volatile var` が 36 個。読む側は `V6NativeOptimizer` 22・`V6FinalPort` 16・`RsiHypothesisOperators` 13・`V6PortAnalyzer` 11 か所 | テスト間の状態漏れ、実行ごとの設定を再現できない、C# との対応表が無い |
| W3 | 後処理の段が手書きの連鎖 | `V6HotfixPasses` の後処理チェーン（多数の段、走行 keep-best は 3.608.0/3.610.0 で後付け）、`V6FinalPort` | 段の追加・順序変更のたびに keep-best と記録を手で揃える |
| W4 | 制約の「どのセルが何の違反か」を複数層が再解釈 | チェッカーを UI の 5 ファイル（`FixTiers`・`FixExplain`・`CellEditSheet`・`CellSheetLogic`・`MagiViewModel`）が直接呼ぶ。診断（`V6SanityPort`・`V6PortAnalyzer`）と修復（`ViolationComponentRepair`・各 Polish）も独自に位置を出す | 表示・診断・修復で食い違うバグの温床（過去の「取り残し」型） |
| W5 | ViewModel が巨大（Kotlin） | `MagiViewModel.kt` 4,030 行。C# はすでに partial 14 ファイル | 変更の衝突、レビューの難しさ |
| W6 | 安全機構にテストの穴 | `NativeGate` の退化経路、JNI 境界（C++ harness のみ）、保存の再試行と世代の配線（B-03・M-02 も含む） | 退行を CI で捕まえられない |
| W7 | 文書の取り残し | `architecture.md` の「18 違反種」（実際は 21）など | 監査の誤誘導 |

---

## 3. 改修の間ずっと守る条件（合格ゲート）

| # | ゲート | 確かめ方 |
|---|---|---|
| G1 | チェッカーが判定の正 | チェッカー系 JUnit 全件 |
| G2 | Δ＝フル | Δ×フルのランダム手テスト（Kotlin）、C++ `host_parity_bench` |
| G3 | Kotlin↔C++ 一致・golden 不変 | CI `native-parity`・`v6-engine-check`、期待値ファイルの差分 0 行 |
| G4 | keep-best の比較は `reportComparator` だけ | 比較を手書きした箇所の grep が 0 件のまま |
| G5 | 最終番兵・2 層番兵を削らない | 番兵テスト＋§5 で足す `NativeGate` 退化テスト |
| G6 | 保存の世代・所有権 | 保存系テスト＋§5 で足す配線テスト |
| G7 | 重み・パラメータ不変（HF77） | `MirrorKeys.weights`・`Evaluator.fullEvalParts`・`DeltaEvaluator`・`magi_native.cpp`・C# `MirrorKeys.WeightOf` の差分 0 |
| G8 | 作り直す段では結果が変わらない | **盤面ハッシュ一致**（§4）。対象は (a) 後処理の決定的モード（`PostOptimizationParams.deterministic`）で 6 fixture × 固定 seed の最終盤面、(b) 全 fixture のチェッカー出力（違反リストと breakdown）、(c) 診断文のスナップショット |
| G9 | 探索の動きを変えるものは既定 OFF のフラグで入れ、ペア比較で採否 | `tools/loop` の HandleOptimizeBench（6 データ×5 seed 以上、hard 悪化 0・weighted の 95% 区間）。否決なら記録だけ残す |
| G10 | C# 同日同期 | `MagiEngine.Tests`・`MagiApp.ViewModels.Tests` 全件（ログで確認、パイプで切らない）、WinUI ビルド（CI） |
| G11 | 1 段＝1 版、小さな PR | 目安 ±600 行。機械的な移動はそれ以上でもよいが、移動と意味の変更を同じ PR に入れない |
| G12 | 画面に触れた段 | CI `android-sdk`（workflow_dispatch）で実コンパイル、`design-review`、`design_lint` hard 0 |
| G13 | 版の採番 | `tools/check_version.py`（重複・減少を落とす） |

注意（G8 の限界）: `handleOptimize` 全体は時間依存で決定的ではない（`tools/loop/README.md`：後処理の時刻由来の種が残る）。
したがって探索部（SA・ポートフォリオ）の作り直しは G2・G3（評価の一致）と、時間配分のログ比較（§6 P3）で示す。
盤面ハッシュで示せるのは後処理の決定的モードとチェッカー・診断まで。

---

## 4. 安全網（P0 で最初に作る）

1. **`BoardHashFixtureTest`**（Kotlin、C# に同名）: 6 fixture（`golden`・`sample_state_v6`・`blocked_covu`・`sept2026`・
   `oct2026_grid`・`full_coverage`）× seed 3 本で、後処理の決定的モードの最終盤面を `StateFingerprint` でハッシュし、
   `src/test/resources/board_hash.json` と照合する。期待値の作り直しは明示の環境変数でだけ行う。合格条件: 2 回連続で同じ値。
2. **`CheckerSnapshotTest`**: 全 fixture の入力盤面について、チェッカーの違反リスト（族・職員・日）と breakdown を
   スナップショットと照合する。
3. **`DiagnosisSnapshotTest`**: `V6SanityPort` の診断文の一覧をスナップショットと照合する。
4. 所要時間の上限: 3 本合わせてホストテストへの増分 60 秒以内。超える場合は fixture を減らす。

---

## 5. テストの穴を先に埋める（P1）

棚卸し（`docs/safety_inventory.md`）で見つかった穴。改修の前に埋める。
| 穴 | 足すテスト（案） | やり方 |
|---|---|---|
| `NativeGate` の退化経路（`SaOptimizer.kt:180/194`、`V6FinalPort.kt:1134`） | `NativeGateFallbackTest` | 照合の判定を純関数に出し、「不一致なら閉じて Kotlin の結果を使う」を JVM で確かめる（`.so` を読まない） |
| B-03 取消し時の実行フラグ解除 | `OptimizationWorkerReleaseTest.cancelPathReleasesRunningEvenAfterMarkerIsCleared` | 解除の判定を純関数へ |
| M-02 非同期投入の失敗の片付け | `BgEnqueueFailureTest.asyncEnqueueFailureCleansUpOnlyTheCurrentRun` | 判定を純関数へ |
| 保存の再試行と世代の採番の配線 | `SaveRetryWiringTest` | `retrySave` の判定を純関数へ |
| M-01 原子置換を諦めたときの記録 | `NonAtomicFallbackLogTest` | `prevBackupFile` の書き込みにも記録の受け口を渡す |
| U-03 重みの検査が `10_000L`・`1e4` を拾わない | `tools/test_weight_lint.py` | 正規表現を広げ、自己テストを CI で走らせる |

（B-03・M-02・M-01・U-03・L-03 の文言は作業中の分にすでに含めている。）

---

## 6. 段階計画

出力を変えない作り直し（P2〜P6）を先に済ませる。意味やデータ形式を変える段（P8・P9）はユーザーの決定の後に回す。
各段の C# は同日に同じ分け方で入れる。

| 段 | 範囲 | 主なファイル | 合格 | 戻し方 | C# | 規模 |
|---|---|---|---|---|---|---|
| **P0 安全網** | §4 の 3 テスト | `app/src/test/`、`tools/host/` | 決定性（2 回一致）、増分 60 s 以内 | テスト追加のみ＝revert | 同名テスト | S |
| **P1 穴埋め** | §5 | 各判定を純関数へ切り出す箇所 | G1〜G8 | revert | 同等の判定を C# にも | S〜M |
| **P2 EngineConfig** | `PolishGate` の 36 フィールドを不変の `EngineConfig`（data class）に集め、実行開始時に 1 回スナップショット。各パスは引数で読む。移行中は `PolishGate` を `EngineConfig.current()` を作るためのファサードとして残す | `V6HotfixPasses.kt:117〜`、`V6FinalPort`、`V6NativeOptimizer`、`RsiHypothesisOperators`、`V6PortAnalyzer`、設定を渡す `MagiViewModel` | G8、既定値の表テスト（36 個すべて） | PR ごとに revert。ファサードで呼び出し側は無傷 | `EngineConfig.cs` | M（3 PR） |
| **P3 TimeBudget** | 予算を 1 個の `TimeBudget`（`EngineClock` の上に `deadline`・`remaining()`・`split(fraction, floorMs)`）へ。今の式は数値ごと写す（`normalStallMs` の下限 20,000 ms、`postReserve` 20 s 等） | W1 の各ファイル | 決定的モードで G8。時間制では各段の割当 ms をログで前後比較（差 0 であること） | revert | `TimeBudget.cs` | M（2 PR） |
| **P4 PostPipeline** | 後処理チェーンを `Stage { key; run(board, budget, cfg) }` の並びにする（順序も中身も不変）。走行 keep-best と `PostStageRecord` はパイプライン側で一律にかける | `V6HotfixPasses`（partial に合わせる）、`V6FinalPort`、`V6LateOperators` | G4、G8、`PostStageRecord` の並びの一致 | revert | `V6HotfixPasses.*.cs` を同じ分割に | L（4〜5 PR） |
| **P5 ConstraintCatalog** | 制約族ごとに `ConstraintSpec { key; hard; locate(); explain(); repairHints() }` を 1 つ。**採点（重み・式）は持たない**。チェッカーの位置づけ、診断の文、UI の修正提案が共有する | `MirrorCore`（`UnifiedViolationChecker`）、`V6SanityPort`、`V6PortAnalyzer`、`ui/FixTiers`・`FixExplain`・`CellSheetLogic` | G1、G3、§4 の Checker/Diagnosis スナップショット一致 | 族ごとの PR＝1 本ずつ revert | Catalog を同じ族の順で | L（族ごと約 8 PR） |
| **P6 ViewModel 分割** | `MagiViewModel.kt` を、既存の `MagiHandlers` の鎖の区切りに沿って partial 相当（Kotlin は委譲クラス）に分ける。C# の 14 partial と同じ名前・同じ境界にそろえる。`MagiMediator`/`MagiArbiter` はそのまま | `ui/MagiViewModel*.kt`、`MagiHandlers.kt` | G6、G12、ViewModel 系 JUnit | revert | C# はすでに分割済み＝名前の対応表だけ作る | L（画面ごと） |
| **P7 文書の正規化** | `architecture.md` を新構成に書き換え（21 族など取り残しも直す）、`sudo_model.md` の図を更新 | `docs/` | 文書照合（作業記録に方法を残す） | revert | `windows/README.md` | S |
| **P8 グループ上限0**（2026-10-22 決定＝案 A、文書のみで完了） | 付録 A の Q-05。推奨 A（今のまま＋文書化）なら文書だけ。B なら `mayPlace` に c41/c41s の u=0 を含め、既定 OFF で入れて測る | A: `docs/business-logic.md`。B: `Problem`、C++、C#、文書、テスト | A: 文書照合。B: G9 | A: revert。B: フラグ | B のとき同日 | A: S／B: M |
| **P9 職員の同一性**（2026-10-22 決定＝案 A、文書のみで完了） | 付録 A の Q-06。推奨 A（解決済みとして閉じる）なら文書だけ。B なら CSV に任意の「職員ID」列 | A: `docs/data-models.md`。B: `ScheduleCsvBridge`、C# `StaffCsvIO.cs`、VBA 互換 | 往復テスト | revert | B のとき同日 | A: S／B: M |

依存関係: P0 → P1 → P2 → P3 → P4 → P5 → P6 → P7。P8・P9 は決定が出れば P2 以降のどこにでも挟める。
P2 と P3 は互いに独立に近いが、P4 が両方を使うので P4 の前に両方を終える。

---

## 7. 探索の動きを変えたくなったとき

改修の途中で「こうすれば速く・良くなる」が見つかっても、作り直しの PR には入れない。
1. 既定 OFF のフラグ（P2 以降は `EngineConfig` のフィールド）として別の PR で入れる。
2. `tools/loop` の HandleOptimizeBench でペア比較する（6 データ×5 seed 以上、必要なら 10 seed×2 回）。
3. 採否の基準: 必須が 1 件も悪化せず、weighted が平均で悪化しない（95% 区間）。満たさなければ記録だけ残してコードは入れない
   （2026-10-18 の必須同数タイブレーク、2026-10-19 の fair② と同じ扱い）。

---

## 8. C# 同期の進め方
- 各段の Kotlin を main に入れた日に、同じ名前・同じ分割で C# を入れる（`windows/README.md` の「レビュー対応の記録」に理由）。
- C# の更新を止める期間（ユーザー指示）は、Kotlin 側の段を進めてよいが、C# に入れるべき差分を一覧に積む。
  停止が明けたら一覧を上から順に入れ、Kotlin と同じ版の境界で PR を分ける。
- C# だけの改善は「出力が同じ」もの（安全な巻き戻し・性能・型の契約）に限る。

---

## 9. リスクとやらないこと

### 9.1 リスクと対策
| リスク | 対策 |
|---|---|
| 時間依存の経路は G8 で示せない | P3 は決定的モードで G8、時間制は割当 ms のログを前後比較 |
| `@Volatile` をやめると実行中のトグルが効かなくなる | 【仮定】実行中の変更は意図していない。P2 の最初の PR で「実行中に `PolishGate` を書く箇所」を grep し、あれば先に挙動を固定するテストを足す |
| P4・P5 は差分が大きい | 段・族ごとに PR を分け、移動と意味の変更を混ぜない |
| C# の停止で同期が遅れる | §8 の一覧で追跡。停止中は C# 依存の段（P0 の C# 分など）を後追いにする |
| 複数セッションの並行作業 | 版数は取り込む直前に一人が振る。`check_version.py` が重複を落とす |

### 9.2 やらないこと（再提案しない）
- 否決済みの探索案: 戦略的振動、nonlinear restart、GLS スイープ、targeted-perturb、big-destroy、softFocusProb、
  adaptiveEscapeControl、portfolioRoleParallelSa、`wideC3nBreakDays` の既定 ON、適応停滞閾値、余り時間の玉突き、
  E0B、c1MoveARepair、必須同数タイブレーク（族の重み）、fair② 交換走査。
- 決定記録 D3〜D10、E5、ws8/ws9、cons3n の dedup。
- 2 層番兵と `NativeGate` の削除、採用基準の追加、Catalog に比較器や重みを持たせること。

---

## 10. 最初の 3 PR
1. **PR-1（P0）安全網**: `BoardHashFixtureTest`・`CheckerSnapshotTest`・`DiagnosisSnapshotTest`。Kotlin のみで先に入れ、
   C# の同名テストは停止明けに入れる。合格: 2 回連続一致、増分 60 s 以内。
2. **PR-2（P1）穴埋め**: `NativeGateFallbackTest` と `SaveRetryWiringTest`（B-03・M-02・M-01・U-03 は作業中の分で入る）。
3. **PR-3（P2a）`EngineConfig` 導入**: 36 フィールドと同じ既定値の data class と `snapshot()`。まず `normalStallFraction`
   と `floorCountsUpperZero` の 2 つを引数で読む形へ移す。合格: PR-1 のハッシュ一致、既定値の表テスト。

---

## 11. 作業台帳の状態と記録

指摘・作業は次のどれかの状態で管理する（2026-10-22 統合版 v1.0 に合わせる）。
| 状態 | 意味 |
|---|---|
| 未調査 | コード・仕様を確認していない |
| 調査済み | 現状と影響範囲が分かっている |
| 修正中 | 変更を実施している |
| 検証中 | テスト・パリティ・実機確認を進めている |
| 完了 | §3 のゲートと各段の合格条件を満たした |
| 保留 | 仕様決定や外部条件（実機・ユーザー操作）が要る |

- 根拠の無い「修正済み」「検証済み」は書かない。根拠＝コミット・テスト名・CI の結果・計測の CSV。
- 過去の 28 項目（B/L/M/U）と repo 内の旧番号（`docs/changelog.md` の同名番号）を区別する。現状は `docs/safety_inventory.md` が正。
- 各 PR には、Kotlin・C++・C# の変更有無、実行したテストと実行していないテスト、盤面ハッシュの結果、戻し方を書く。

## 12. 完了の定義
- P0〜P7 が main に入り、各段で G1〜G13 を満たした記録が `docs/history/` にある。
- `PolishGate` のグローバル書き込みが 0（`EngineConfig` のみ）、予算の定義が `TimeBudget` 1 か所、後処理が `PostPipeline`、
  UI と診断の違反の位置が `ConstraintCatalog` 経由。
- `MagiViewModel.kt` が C# と同じ境界で分割され、最大ファイルが 1,500 行以下【目安】。
- 棚卸しの「記録あり・テスト無し」が 0。
- C# が全段で同期済み。

---

## 付録 A. Q-05（グループ上限0）・Q-06（職員の同一性）の仕様調査


## Q-05（M-06 グループ上限 0）
結論: 3.723.0 の記録では M-06 を「グループ上限 0 の除外は 3.507.0（個人上限 0）の決定の範囲外」として保留している。ここで言う「グループ上限 0」は **cons41/cons41s の u=0（c410/c41s0）** を指す。グループに関わる 0 は 4 種類あり、意味がそれぞれ違う。

| 表現 | 0 の今の意味 | 最適化器 | チェッカー・UI・診断 |
|---|---|---|---|
| `groupShift[g][k]`（0/1） | 担当不可 | `bucket`/`canDo` で置かない。置けば groupViol 11000 | 群外シフトとして HARD |
| `groupShiftApt[g][k]`（文字列） | "0"＝目標 0 回、""＝未設定（data-models:36） | apt 4 で寄せるだけ。`mayPlace` は見ない | apt0 のセルに右上の印（`zeroAllowCells`、MagiViewState.kt:512） |
| `cons41/cons41s` の u | "0"＝1 日 0 回（business-logic:158。空欄・非数値・負は未設定＝3.687.0） | SOFT の c41 9／c41s 10 として評価するだけ。`mayPlace` は見ない＝置くことがある | c410/c41s0 のセルに印（3.4xx「許容0の超過」）。判定・重みは変えない |
| 「グループ単位の回数」 | 実体はメンバー全員の個人 `staffRange["i,k"]` へ一括で書く操作（`groupRangeSummary`／`clearGroupRangeAll`、MagiViewModelConditions.kt:187/231） | hi=0 なら 3.507.0 により `mayPlace=false`＝**すでに置かない** | 個人上限 0 と同じ扱い |

- `rangeBound` は Problem.kt:320 にある。Problem.kt:268 では、l と u がどちらも空の行は未解決の行として捨てる。
- 保存データで「0＝未設定」の意味で使っている箇所は見つからなかった。未設定は全経路で空文字。ただし、利用者が「c41 の上限 0」を「入れない指定」のつもりで入れたのか、「その日は 0 人が望ましい（SOFT）」のつもりで入れたのかはデータから区別できない。
- 3.507.0 の個人上限 0 の扱い: 最適化器は置かない（`placeable`／`mayPlaceAt`。入口の hf66 と最終番兵の基準もこれ）。判定と表示は変えず、置かれていれば high 25 のまま数える。希望固定が優先する。

### 選択肢
- **A（推奨⭐⭐⭐）現状維持＋文書化**: c41 の u=0 は SOFT の目標のまま。グループ全員に「入れない」を付けたい場合は、すでにある「グループ単位の回数」で上限 0 を入れる（個人上限 0 になり、3.507.0 で置かれなくなる）と business-logic に明記する。互換への影響なし、パリティ作業なし。変更箇所は docs/business-logic.md（:158 と :160 付近）だけ。必要なら設定の見直しに案内文を足す（`V6SanityPort` に、c41 u=0 の行がある組へ「全員に入れないなら個人の上限0へ」という中立の案内を出す）。
- **B（⭐⭐）c41/c41s の u=0 も `mayPlace` から外す**: 該当グループのメンバーは、そのシフトを置けない扱いにする（3.507.0 と同型。判定・重み・表示は不変）。既存データで c41 の u=0 を使っているものは最終盤面が変わるので、実データ 4 件の probe と `tools/loop` での計測が必要（探索動学の変更にあたる）。変更箇所:
  - Problem.kt の `placeable`/`placeableHas`/`staffForShift` を作る処理（cons41 は sgrp、cons41s は ssk で照合）
  - `mayPlace` を使う側は自動で追従する（FixTiers.kt:40、C1Display.kt、`placeableFor`、`ConstraintMus.analyzeDayConflicts`、`zeroCapInShortfall`、`RelaxTrial.upperZeroWalls`／`handPlacedUpperZeroIssue`、`clearedCapZeroStages`、最終番兵の「上限0セルを外した入力」）。ただし「個人の上限0」という文言は族ごとに分ける必要がある
  - C++ の magi_native.cpp に placeable を渡している箇所（同じコミットで変える）
  - C# の Problem.cs と診断（同日に同期）
  - 文書: business-logic と CLAUDE.md の low/high 節
  - テスト: `C1UpperZeroGuardTest` と同型のテストを追加
- **C（⭐）u=0 を HARD 化／新しいフラグ**: 新しい項目（例「グループ入れない指定」）を作るか、重みを変えることになる。前者は data-models の「存在しない項目を創作しない」、後者は HF77 に触れるため、明示の承認が必要になる。非推奨。

利用者に確認したいこと: グループのレンジで上限 0 を入れたとき、「入れない指定（3.507.0 と同じ）」と「なるべく 0（SOFT）」のどちらの意味で使っていますか。（推奨 A。B を選ぶ場合は計測してから採否を決める。）

## Q-06（M-07 職員の同一性）
結論: **安定 ID はすでにある**。`Staff.id: Int`（1 以上、2026-10-13 利用者決定。data-models:66）。M-07 の残りは「ID を新設するかどうか」ではなく、**名前や位置で照合している残りの経路をどう扱うか**の判断になる。

### いまの ID の扱い
- 読込（StateParser.kt:43）: ID が無い・重複（後に出た方）・0 以下のときは、読み込み順に最大値＋1 を振る。古いファイルも読める。
- 保存（StateParser.kt:214/247）: 全経路で `"id"` を書く。
- 職員の追加と名簿 CSV で増えた職員には `nextStaffId` を振る（Ws1Ops.kt:314）。並び替え・改名・削除では ID を保つ。CSV には ID を出さない。
- 相談（ConsultList.kt:103〜）: `staffId` があれば ID だけで引き、見つからなければ開かない。ID の無い古い相談は、氏名で引き、同名がいれば積んだときの並び（`rosterKey`）が同じ場合だけ位置で引く（3.650.0）。
- 変更案のパネル（ChainFixPreview.kt:64）と修正の焦点（MagiScheduleViews.kt:2061）は ID を鍵にする（3.677.0）。
- C#: `MagiState.Staff.Id`、`StaffIds.WithStaffIds`、StateJsonSerializer.cs:119/272/334、`StaffIdTest` で同期済み。

### 名前・位置の照合が残っている箇所
1. 勤務表 CSV の重ね合わせ取込（ScheduleCsvBridge.kt:395〜）: `nameMatchKey` で照合する。同姓同名（`ambiguousKeys`）はスキップして名前を報告する。
2. 名簿 CSV の取込（ScheduleCsvBridge.kt:637〜）: 氏名で照合する。同名はスキップし、未知の氏名は新規の職員として最大値＋1 を振る。
3. JSON 内の index 鍵: `wishes`／`staffRange`／固定セルなどは `"i,j"`／`"i,k"` の形で、職員の位置で引く（data-models:84）。並び替えや削除は編集操作の側で付け替えている。ファイル単体として正しいので、これ自体は問題にならない。
4. `ViolationKey`（ViolationIdentity.kt:9）の a は職員の index。1 回の評価の中だけで使うので問題ない。表示を引き継ぐ箇所は 3.677.0 で ID 化済み。
5. 元に戻す（undo）は state 全体のスナップショットなので、index で一貫している。
6. 指紋（StateFingerprint／rosterKey）: 名前の並びでの判定は、ID の無い古い相談を引くときの補助として残している。

### 失敗する場面
- 同姓同名: JSON と相談は ID で区別できる。CSV は両方スキップして報告する（誤って結ぶことはない）。
- 改名: JSON と相談は追従する。CSV は旧名を見つけられず、名簿 CSV では**別人として新規追加**される（重複が起きうる）。
- 並び替え・行の追加削除: JSON は追従する。CSV は名前で引くので順序の影響は受けない。
- 端末をまたぐ: 別ファイルから作ったデータどうしでは ID が独立に振られる。ID は「同じファイルの系譜の中」でだけ有効。

### 移行設計（残りの選択肢）
- **A（推奨⭐⭐⭐）現状のまま閉じる**: JSON の ID 採番、旧ファイルの自動採番、CSV は名前照合＋曖昧ならスキップ。曖昧なものを自動で結ぶことはない。文書の追記だけで済む。
- **B（⭐⭐）CSV に任意の ID 列を足す**: 名簿 CSV と勤務表 CSV に「職員ID」列を足す。列があれば ID を優先し、無ければ従来の名前照合にする。改名と同名を CSV の往復で救える。CSV の列は変えないという 2026-10-13 の決定を変えることになり、data-models の変更なので**利用者の承認が必要**。VBA 側（.bas、SJIS）との互換も確認が要る。変更箇所: ScheduleCsvBridge の build/parse（勤務表と名簿）、C# の StaffCsvIO.cs と同等の箇所、data-models、往復テスト（3.507.8 の完全再現テスト）。
- **C（⭐）JSON 内の index 鍵（wishes/staffRange 等）を ID 鍵へ移す**: 保存形式を大きく変えることになり、旧形式の読込との二重対応も要る。便益が小さいので非推奨。

利用者に確認したいこと:
1. M-07 は 3.677.0 前後の職員 ID 導入（2026-10-13 の決定）で解決済みとして閉じてよいですか。（推奨 ⭐⭐⭐ はい）
2. 名簿 CSV で改名した人が新規に追加されてしまう点は、許容しますか。それとも CSV に ID 列を足しますか（B。2026-10-13 の「CSV の列は変えない」を変更することになる）。
3. 同名を CSV でスキップする今の動作（報告のみ）のままでよいですか。
