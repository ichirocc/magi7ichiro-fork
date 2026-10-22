> **下書き（2026-10-22）**。根拠は CLAUDE.md とコードの検索。architecture／sudo_model／business-logic／data-models／stall_escape との突き合わせは未了。付録 A は Q-05/Q-06 の仕様調査（コード・データ変更なし）。

# MAGI ShiftOptimizer 段階的全面改修 設計書（2026-10-22 ユーザー決定）

対象: `ichirocc/magi7ichiro-fork`（Kotlin が正）。C++ `magi_native.cpp`、C# `ichirocc/-MAGI_PC` は同値の移植で、各段の同日同期を必須にする。
仮定は【仮定】と書く。M-03/M-04/M-06/M-07/U-02 は依頼側のレビュー番号を指す。repo の `docs/changelog.md` には同じ番号で別の過去指摘があるので、本書では依頼時の意味（後述）で使う。

## 0. 全段に効く不変条件（ゲート）
| # | ゲート | 証明手段 |
|---|---|---|
| G1 | `UnifiedViolationChecker` が真実源 | チェッカー系 JUnit（`tools/host/hosttest.sh`）全緑 |
| G2 | Δ＝フル評価のパリティ | DeltaEvaluator×fullEval のランダム手テスト |
| G3 | Kotlin↔C++ パリティ・golden 不変 | `native-parity.yml`、`v6-engine-check.yml`。golden 期待値の差分 0 行 |
| G4 | keep-best（`reportComparator` 単一ソース） | 比較器を呼ぶ箇所の grep 件数が減ることはあっても、新規の比較式は 0 |
| G5 | 最終番兵（2 層番兵＋`mayPlace` 最終検査） | 番兵テスト、`NativeGate` 閉鎖時の退化テスト |
| G6 | 保存の世代・所有権（runId/`beginRun`） | OptimizationRepository 系テスト |
| G7 | HF77: 重み・パラメータ不変 | `MirrorKeys.weights` と C++ 5 箇所、C# `MirrorKeys.WeightOf` の diff 0 |
| G8 | リファクタ段はエンジン出力不変 | fixture（golden＋実データ 4 件＋tools/loop 34 ケース）× deterministic seed で**最終盤面ハッシュ一致**（`BoardKey`/`StateFingerprint`） |
| G9 | 探索動学の変更は既定 OFF フラグ下のみ | `tools/loop/` ペア比較（34 ケース×10 seed）で採否 |
| G10 | C# 同日同期 | `MagiEngine.Tests` 全緑（パイプで切らずにログで確認） |
| G11 | 1 段＝1 版、PR は小さく | 1 PR の目安は ±600 行以内、機械的移動はそれより大きくてよい |

G8 の基盤は Phase 0 で作る。基盤ができる前にリファクタは始めない。

## 1. 現状の地図と痛点（実測、2026-10-22 HEAD）
規模: `v6/` 31,167 行、`ui/` 23,874 行、`model/` 583 行。

### 1.1 巨大クラス
| ファイル | 行数 | 内容 |
|---|---|---|
| `ui/MagiViewModel.kt` | 4,030 | 209 個の fun/class。全画面の状態、実行制御、保存、相談、修正提案を 1 クラスで持つ（`MagiViewModelConditions.kt` 483 行だけ分割済み） |
| `ui/MagiScheduleViews.kt` | 2,435 | 勤務表の Composable |
| `ui/MagiDashboardCards.kt` | 2,137 | |
| `ui/CellSheetLogic.kt` | 2,012 | セル編集の判定。チェッカーを直接呼ぶ |
| `ui/MagiApp.kt` | 1,584 | ナビ＋画面組み立て |
| `v6/V6NativeOptimizer.kt` | 2,360 | SA/ネイティブ橋渡し＋予算＋診断の集計 |
| `v6/V6SanityPort.kt` | 2,016 | 診断 |
| `v6/V6FinalPort.kt` | 1,533 | 後処理チェーンと予算配分 |
| `v6/V6HotfixPasses.kt` | 1,460 | HF 各パス＋`PolishGate`（グローバル可変フラグ） |
| `v6/V6PortAnalyzer.kt` | 1,233 | |

`UnifiedViolationChecker` は v6 だけでなく `ui/FixTiers.kt`、`FixExplain.kt`、`CellEditSheet.kt`、`CellSheetLogic.kt`、`MagiViewModel.kt` からも直接呼ばれている。制約の意味（どのセルが何の違反か）を UI、診断（SanityPort/PortAnalyzer）、修復（ViolationComponentRepair・各 Polish）がそれぞれ再解釈している。

### 1.2 予算定義の重複（M-03/M-04 相当）
- `V6FinalPort.kt:574` の `budgetMs = seconds*1000`、`:250-289` の `normalStallMs`（`PolishGate.normalStallFraction`、下限 20,000ms）、`:868/:888` の `integrationDeadline`／`hardDeadlineMs`。
- `V6NativeOptimizer.kt:1344` は `SaParams(budgetMs = budgetSec*1000L)` を別に作る。
- `V6HotfixPasses.kt:907` の `C1JointLnsPolish.Config(maxMillis=60_000L, …)`、`EjectionChainPipeline.kt:44` の `deepBudgetMs`、`CombinatorialRepair.kt:44` の `elapsedMs`。
- 予算・期限まわりの識別子は v6 全体で 152 箇所ある（`grep TimeBudget|budgetMs|deadline`）。時計は `EngineClock.kt` にあるが、残予算を一か所で数える仕組みはない。

### 1.3 名前による照合（M-07 相当）
- `model/MagiState.kt:30` の `Staff` は `id` を持ち、`freshStaffId` で採番している。それでも照合が名前に頼る経路が残る。例: `ui/ConsultList.kt:160-164` の `c.staffName == after[at]`、CSV 取込（`ScheduleCsvBridge.kt`、`CsvPartialImport.kt`）の氏名・記号の先勝ち解決。
- `Shift`/`Group` は `id` を持たず、`kigou`/`name` とインデックスで参照している（`MagiState.kt:25-26`）。
- `V6HotfixPasses.kt:362-364` や `OptimizationRepository.kt:98` の enum `it.name ==` は永続化キーなので、ここは問題にしない。

### 1.4 グループ上限 0 の意味（M-06 相当）
- 個人の `staffRange` で hi=0 のときは「最適化器は置かない」と決まっている（`Problem.mayPlace`、3.507.0）。グループ側の上限 0（c41/c42 の hi=0、apt 目標 0）は「範囲の上限が 0」なのか「置かない」なのかが仕様で決まっていない。【仮定】最適化器は今、上限 0 を SOFT の範囲として扱い、候補からは除外していない。Phase 0 で grep して確かめる。
- この意味を変えると出力が変わる。したがってリファクタ段には入れず、仕様決定のあとの段（Phase 6）で扱う。

### 1.5 フラグの散らかり（U-02 相当）
- `object PolishGate`（`V6HotfixPasses.kt:117`）は `@Volatile var` のグローバル。UI トグルをエンジンへ渡すのに使っている。定義元はほかに `V6NativeOptimizer` 22 箇所、`V6FinalPort` 16、`RsiHypothesisOperators` 13、`V6PortAnalyzer` 11。
- 問題は三つある。テスト間でグローバル状態が漏れる。実行ごとの設定を再現できない。C# 側でフラグを同期するときの対応表がない。

## 2. 目標アーキテクチャ
```
model/      MagiState（永続形、id つき）, StateParser, migration
problem/    Problem（不変、mayPlace/mayPlaceAt/extBan/c3wBan）, ProblemBuilder
constraints/ ConstraintCatalog: 制約ファミリーごとに 1 つの ConstraintSpec
             { key, weight（=MirrorKeys 参照）, hard?, locate(cells), explain(), repairHints() }
             UnifiedViolationChecker／診断（SanityPort/PortAnalyzer）／修復（ViolationComponentRepair、UI の FixTiers）が共有する
eval/       Evaluator, DeltaEvaluator, NativeEval（C++ は eval だけを写す）
search/     SearchOrchestrator: TimeBudget（1 個、子予算は split で切り出す）、EngineConfig（不変、PolishGate を置き換える）
            SA/LAHC/ALNS/native 呼び出し
post/       PostPipeline: Stage { key, run(board, budget, cfg) } のリスト。keep-best と PostStageRecord は pipeline 側で一律にかける
            （V6FinalPort/V6HotfixPasses の各パスを Stage に包む。中身は変えない）
persist/    OptimizationRepository（runId 世代・所有権）、JSON の id 移行
ui/         画面ごとの VM: ScheduleVm, SetupVm, ConditionsVm, RunVm, ConsultVm と、共有の SessionStore
```
原則:
- ConstraintCatalog が持つのは「位置と説明」の共有だけ。重みと採点は Evaluator とチェッカーの今の式のまま動かさない（G2/G3/G7）。
- `EngineConfig` は data class で、実行開始時に一度スナップショットする。`PolishGate` は移行期間だけ、`EngineConfig` を作るためのファサードとして残す。
- `TimeBudget` は `EngineClock` の上に `remaining()`、`split(fraction, floorMs)`、`deadline` を持たせる。今ある式は数値まで同じに写す（`normalStallMs` の下限 20,000ms など）。

## 3. 段階計画（1 段＝1 版）
出力不変のリファクタ（P0〜P5）を先に済ませ、データ形式や意味を変える段（P6〜P7）は仕様決定のあとに回す。

| 段 | 範囲 | 主なファイル | 証明 | 戻し方 | C# | 規模 |
|---|---|---|---|---|---|---|
| **P0 安全網** | 盤面ハッシュの fixture ハーネス（deterministic seed、反復回数を固定）、フラグと予算の棚卸し表、上限 0 の現状 grep | `tools/host/`、新テスト `BoardHashFixtureTest`、`docs/` | ハーネスが自分自身と 2 回一致する（決定性） | テストの追加だけなので revert で済む | `MagiEngine.Tests` に同じ fixture のハッシュテストを写す | S |
| **P1 EngineConfig** | `PolishGate` の各フラグを不変の `EngineConfig` に集め、実行開始時にスナップショットを取る。各パスは cfg 引数で読む | `V6HotfixPasses.kt:117〜`、`V6FinalPort`、`V6NativeOptimizer`、`RsiHypothesisOperators`、`MagiViewModel`（設定を渡す箇所） | G8 のハッシュ一致、既定値の表テスト | PR ごとに revert。ファサードがあるので呼び出し側はそのまま | `PolishGate` 相当を `EngineConfig` に写す | M（2〜3 PR） |
| **P2 TimeBudget** | 予算を 1 個にする。今の式は数値ごと移す | `EngineClock.kt`、`V6FinalPort.kt:250-289,574,868,888`、`V6NativeOptimizer.kt:1344`、`V6HotfixPasses.kt:907`、`EjectionChainPipeline`、`CombinatorialRepair` | deterministic モードで G8 一致。時間依存モードでは各段の割当 ms のログを前後で比べる | revert | 同型の `TimeBudget` を作る | M |
| **P3 PostPipeline** | 後処理チェーンを Stage リストにする（順序も中身も不変）。keep-best と記録は pipeline に寄せる | `V6FinalPort`、`V6HotfixPasses`（partial 分割に合わせる）、`V6LateOperators` | G4、G8、`PostStageRecord` の並びの一致 | revert | `V6HotfixPasses.*.cs` を同じ分割にする | L（4〜5 PR） |
| **P4 ConstraintCatalog** | 違反の位置と説明を Catalog に集め、チェッカー・診断・UI 修正提案が共有する | `UnifiedViolationChecker`、`V6SanityPort`、`V6PortAnalyzer`、`ui/FixTiers`・`FixExplain`・`CellSheetLogic` | G1、G3、チェッカー出力（違反リスト）の全 fixture 一致、診断文言のスナップショット一致 | ファミリー単位の PR なので 1 本ずつ revert できる | Catalog を写す | L（制約ファミリーごと、約 8 PR） |
| **P5 VM 分割** | `MagiViewModel` を画面ごとの VM と SessionStore に分ける。UI 層だけで、エンジン出力には触れない | `ui/MagiViewModel*.kt`、`MagiApp.kt`、大きい Composable ファイルの分割 | `android-sdk.yml`（workflow_dispatch）の実コンパイル、`design-review`、ViewModel JUnit。G6 | revert | `MagiApp.ViewModels` を同じ分け方にする | L（画面ごと） |
| **P6 仕様決定後: グループ上限 0** | ユーザーが決めた意味（A: 今のまま SOFT 範囲／B: 個人 hi=0 と同じく `mayPlace` で除外）で実装する | `Problem.mayPlace`、C++、C#、`docs/business-logic.md` | 意図した変更なので golden を更新する。tools/loop のペア比較と実データ 4 件の probe | 既定 OFF フラグ → 計測 → 既定 ON。ON にした版を revert すれば戻る | 同日 | M |
| **P7 仕様決定後: 安定 id** | Shift/Group にも id をつける。相談・CSV 取込・保存は id で照合する。旧 JSON は読込時に移行する | `model/MagiState.kt`、`StateParser`、`ConsultList`、`ScheduleCsvBridge`、`CsvPartialImport` | 旧 JSON の往復テスト、移行前後で盤面ハッシュ一致（照合結果が同じ fixture について） | 読込は旧形式も受け付ける。書き出し形式は版で切り替える | JSON 互換を同日に入れる | M |

P6/P7 で止まって聞く項目（AskUserQuestion）:
- グループ上限 0 の意味。B（置かない）を⭐⭐⭐で推す。個人 hi=0 の 3.507.0 決定と揃うため。
- 同名職員を CSV で取り込むときの解決規則。id 優先で、id が無ければ今の先勝ち、を⭐⭐で推す。

## 4. リスクとやらないこと
- 探索動学には触らない。否決済みの案（戦略的振動、nonlinear restart、GLS スイープ、targeted-perturb、big-destroy、softFocusProb、adaptiveEscapeControl、portfolioRoleParallelSa、`wideC3nBreakDays` の既定 ON）を、リファクタのついでに戻さない。
- 採用基準を増やさない。Catalog に比較器を持たせない。
- 重みを Catalog に移すときに数値を「整理」しない（HF77）。真実源は `MirrorKeys.weights` のままにする。
- 2 層番兵と `NativeGate` を削らない。
- cons3n の dedup、D3〜D10、E5、ws8/ws9 は再提案しない。
- 一括リネームをしない。C# のパリティ追跡ができなくなる。名前と分割は C# と 1 対 1 に保つ。
- リスクは三つ。(a) 時間依存の経路は deterministic でないと G8 で検証できない。P2 は deterministic モードで証明し、通常モードは ms 割当ログで補う。(b) `@Volatile` のグローバルをやめると、実行中にトグルが効かなくなる。【仮定】今は実行中の変更を意図していない。P1 の前に grep で確かめる。(c) P3/P4 は差分が大きいので、ファミリー単位・段単位の PR に分ける。

## 5. 最初の 3 PR
1. **PR-1（P0a）`BoardHashFixtureTest`**: golden と tools/loop の代表 6 ケースを deterministic seed で回し、`StateFingerprint` のハッシュを `src/test/resources/board_hash.json` に固定する。期待値を作り直すときは明示フラグで行う。C# にも同じ fixture のハッシュを入れる（同日）。受け入れ条件: 2 回連続で同じハッシュが出る。hosttest の所要時間の増分は 60 秒以内。
2. **PR-2（P0b）棚卸し**: `PolishGate` の全フィールド（名前、既定値、読む箇所、C# の対応）と、予算・期限の 152 箇所の分類表を `docs/architecture.md` に追記する。グループ上限 0 の現行挙動も確かめて記録する。コードは変えない。
3. **PR-3（P1a）`EngineConfig` 導入**: `data class EngineConfig(...)` に `PolishGate` と同じ既定値を持たせ、`EngineConfig.snapshot()` で今のグローバルから作る。まず `V6FinalPort` の `normalStallFraction` と `wideC3nBreakDays` の 2 フラグだけを cfg 引数で読む形に移す。証明: PR-1 のハッシュ一致と、既定値の表テスト。C#: `EngineConfig.cs` を同日に入れる。

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
