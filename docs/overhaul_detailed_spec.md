# MAGI ShiftOptimizer 全面改修 詳細設計仕様書（完全版・机上検証済み、2026-10-22）

この文書は `docs/overhaul_design.md`（方針・ゲート・段階計画）の**下位の詳細仕様**です。方針書が「何を・どの順で・何をもって合格とするか」を決め、
本書は「新しい構造が具体的にどう動き、どの型を持ち、どの例外をどう扱い、何をテストすれば完了か」を決めます。
両者が食い違ったら方針書が正で、本書を直します。コードの行番号は 2026-10-22 の main で照合しました（机上検証は §8）。

記号: 【確定】＝コードで確かめた事実、【設計】＝改修で作るもの、【見積】＝計算や推定による値（測定で置き換える）。

---

## 0. 入力情報（改修の背景・意義）

依頼のひな形の例（「処理速度の 10 倍化」「アルゴリズム刷新」）は**この改修の目標ではありません**。利用者の決定（2026-10-22）は次のとおりです。

### 0.1 現行のボトルネック・技術的負債【確定】
| ID | 負債 | 根拠 |
|---|---|---|
| W1 | 時間予算の定義が 7 か所以上に散在 | `V6FinalPort.kt:259`（停滞判定の下限 20,000 ms）・`:574`（`budgetMs`）・`:21/:23`（後処理と玉突きの枠 各 10 秒）、`V6HotfixPasses.kt:905-908`、`EjectionChainPipeline.kt:44`、`CombinatorialRepair.kt:44`、`V6NativeOptimizer.kt:1344` |
| W2 | 探索設定が書き換え可能な共有オブジェクト | `object PolishGate`（`V6HotfixPasses.kt:117`）に `var` 39 個、`PostOptimizationParams`（`:536`）に真偽値 約 25 個 |
| W3 | 後処理が手書きの連鎖 | `runPostOptimization`（`V6HotfixPasses.kt:813`）が 30 段前後を `chain.adopt(chain.timed(...))` で直列に並べ、段ごとに予算・停止・決定的モードの分岐を手書き |
| W4 | 違反の位置を複数層が再解釈 | UI 5 ファイル・診断 2 ファイル・修復群が `UnifiedViolationChecker` の出力を独自に読み替える |
| W5 | ViewModel が巨大 | `MagiViewModel.kt` 4,030 行 |
| W6 | 安全機構にテストの穴 | `NativeGate`（`NativeBridge.kt:98`）の退化経路が JVM テストで未通過、保存の再試行の配線 |
| W7 | 文書と実装のずれ | `docs/architecture.md` の「18 違反種」（実際は 21） |

**本質のボトルネックは実行時の速さではなく「安全に変えられる速さ」**です。正しさは番兵とテスト（ホスト JUnit 230 ファイル）で守れていますが、
1 つの変更が 3 実装（Kotlin・C++・C#）と複数層に波及し、取り残しのバグが繰り返しています。

### 0.2 達成すべき本質的ゴール（2026-10-22 利用者指示）
| # | 意義 | 合格の線（基準版＝改修開始時の main の SHA と比べる） |
|---|---|---|
| S1 | 既存機能の同等以上 | 既存テスト全件合格、チェッカー・診断のスナップショット一致、機能の削減は決定済みのフラグ整理だけ |
| S2 | 既存の高精度以上 | 違反リスト・族別件数・weightedScore が全 fixture で完全一致、Δ＝フル、Kotlin＝C++ |
| S3 | 最適化の同等以上 | 決定的モードの盤面ハッシュ一致、時間制は対比較で必須の悪化 0・weighted 平均差の 95% 区間上端 ≤ 0 |
| S4 | 最適化の時間の同等以上 | 同じ予算で経過時間の中央値が基準版以下（許容 +2% かつ +1 秒） |
| S5 | パフォーマンスの同等以上 | 1 秒あたりの評価手数が基準版以上（許容 −2%）、ホストテスト時間・実機最大ヒープが基準版以下 |
| S6 | 責務の整理整頓 | 設定・予算・後処理・違反の位置・画面状態がそれぞれ 1 か所に集まる（§6.3） |

---

## 1. 改修アーキテクチャ方針と意義のトレーサビリティ

### 1.1 方針
1. **振る舞いを凍結し、構造だけを置き換える。** 探索の手・確率・温度・段の順序・採用基準・重みは 1 ビットも変えない。変えると S2/S3 の
   完全一致が使えなくなり、「改修で悪くなっていない」ことを証明できなくなるため。
2. **暗黙の共有状態を明示の値に置き換える。** 実行ごとに不変の設定と予算を作り、エンジンへ引数で渡す。
3. **同じ形の手書きを 1 つの枠に寄せる。** 後処理の段、違反の位置、画面の状態をそれぞれ 1 つの型の上に載せる。
4. **一度に置き換え、段ごとにコミットする。** 統合ブランチで全段を作り切り、全ゲートを満たしたら main へ 1 回で入れる。
   コミットを分けるのは、結果が変わったときに二分探索で原因を特定するため。

### 1.2 課題 → 新設計 → 検証の対比表
| 課題 | 新設計 | 解決の仕組み | 守る意義 | 検証 |
|---|---|---|---|---|
| W1 予算の散在 | `TimeBudget`（§3.3） | 全体の締切から探索・後処理・玉突きの枠を 1 か所で配り、各段は `slice()` で受け取るだけ | S4・S6 | 決定的モードの盤面ハッシュ一致、各段の締切順守テスト、境界予算テスト |
| W2 可変の共有設定 | `EngineConfig`（§3.2） | UI の設定を実行開始時に 1 回だけ不変の値へ写し、エンジンは引数でしか読まない | S1・S3・S6 | `PolishGate` への書き込み 0 を grep、同じ設定から同じ盤面 |
| W3 手書きの連鎖 | `PostPipeline` と `Stage`（§2.3） | 段は「名前・有効条件・予算の取り分・本体」だけを持ち、keep-best・記録・停止・決定的分岐は枠が持つ | S3・S6 | 段の列が基準版と同順であることのテスト、後処理後の盤面ハッシュ一致 |
| W4 違反位置の再解釈 | `ConstraintCatalog` と `ConstraintSpec`（§3.4） | 族ごとに「重み・HARD/SOFT・位置の出し方・表示語」を 1 つの表に置き、UI・診断・修復は表を引く | S1・S2・S6 | チェッカーと診断文のスナップショット一致 |
| W5 巨大 ViewModel | C# と同じ境界での分割（§3.5） | 最適化・編集・保存・相談・分析の 5 つの持ち主に分け、`MagiViewModel` は束ねるだけ | S1・S6 | 既存 UI 層テスト全件、最大ファイル 1,500 行以下【目安】 |
| W6 テストの穴 | `NativeGateFallbackTest`・`SaveRetryWiringTest` | 退化経路と再試行の配線を JVM で通す | S1 | 新テストが CI で走る |
| W7 文書のずれ | 数の自動検査 | 族の数などを `ConstraintCatalog` から生成または grep で照合 | S6 | `design-lint.yml` の検査 |

### 1.3 技術的な選定根拠
| 選んだもの | 退けた案 | 理由 |
|---|---|---|
| 不変の `EngineConfig` を引数で渡す | DI フレームワーク、スレッドローカル | 依存が増えず C# へ同じ形で写せる。スレッドローカルは並列ワーカーへの受け渡しで漏れる |
| `TimeBudget` を絶対時刻の締切で持つ | 残り秒数の受け渡し | 既存の `deadlineMs`・`EngineClock.remainingMs` と同じ意味なので、置き換えても値が変わらない |
| `Stage` を関数値とデータで表す | 段ごとのクラス継承 | 段は 30 前後あり、差は本体と取り分だけ。継承にすると C# の partial 分割と対応しにくい |
| 振る舞いの凍結＋スナップショット比較 | 改修と同時に探索を改良 | 改良は HF77 と「測ってから採否」の手続きに従う別作業。混ぜると悪化の原因を切り分けられない |
| 一括置き換え | 段ごとに main へ入れる | 利用者決定。途中の版が main に出ないので、新旧が混ざった状態を利用者が使わない |
| C++ は構造を変えない | C++ も同時に再構成 | C++ は評価器と SA の内側だけで、W1〜W7 の大半が Kotlin 側にある。native-parity が同値を守る |

---

## 2. 完全版 処理フロー・アルゴリズム詳細

### 2.1 全体の流れ（「つくる」1 回）
```
[UI] 開始ボタン
  → MagiViewModel.optimize()                         … 実行中なら二重起動しない（optimizeInFlight）
  → OptimizationWorker（前景サービス）
  → V6FinalPort.handleOptimize(state, schedule, seconds, algorithm, workers, …)
       ① 前処理（§2.2）
       ② 主処理: V6NativeOptimizer.optimize()（§2.4）
       ③ 後処理: runPostOptimization() → PostPipeline（§2.3）
       ④ 最終番兵: checkResultWorse(inputReport, finalReport)（§2.5）
  → 結果を UiState へ。中断時はスナップショットから戻す
```

### 2.2 前処理（厳密な手順）【確定・改修後も同じ順】
| 手順 | 現行の位置（`V6FinalPort.kt`） | 内容 | 改修後 |
|---|---|---|---|
| P-1 | `:526-533` | 職員 0 名・期間 0 日を弾く。`seconds` を `[1, MAX_OPTIMIZE_SEC=300]` へ丸める | 同じ。`EngineConfig.build` の検証へ移す |
| P-2 | `:534-535` | 実現できない希望があり、利用者が許可していなければ止める | 同じ |
| P-3 | `:537` | `Problem` を作る（キャッシュ） | 同じ |
| P-4 | `:539` | 手動固定セルを固定の値へ揃える | 同じ |
| P-5 | `:542` | 個人上限 0 のセルを外す（`clearCappedCells`） | 同じ |
| P-6 | `:543` | 番兵の基準となる入力の採点（`UnifiedViolationChecker.check`） | 同じ |
| P-7 | `:547-566` | 予算に応じた計画を選ぶ（下表）、`V6OptimizerOptions` を作る | 計画の選択を `EngineConfig` に入れる |
| P-8 | `:573-577` | 開始時刻・予算・全体締切（予算＋後処理 10 秒＋玉突き 10 秒）を決める | `TimeBudget.start()` が行う |

計画の帯（`optimizationPlan`、`V6FinalPort.kt:153`）【確定】:
| 予算 s | 計画 |
|---|---|
| s ≤ 30 | V5（SA） |
| 31 ≤ s ≤ 210 | RSI（2/3）→ ALNS（1/3）、再起動 2 |
| 211 ≤ s | Portfolio（ALNS/RSI/RSI++ の異種並列） |
（`OptimizationPlan.ALNS` は型として残るが AUTO では選ばれない。利用者がアルゴリズムを明示したときはその予算全部をそれに使う。改修では帯を変えない。）

### 2.3 後処理パイプライン（`PostPipeline`）【設計】

#### 2.3.1 段の定義
```kotlin
data class Stage(
    val label: String,                 // 記録に出す名前。現行の chain.timed の第 1 引数と同じ文字列
    val key: String,                   // 第 2 引数（ログのタグ）と同じ
    val enabled: (EngineConfig, ViolationReport) -> Boolean,
    val share: BudgetShare,            // 取り分の規則（§3.3）。現行の式をそのまま写す
    val body: (StageContext, Array<IntArray>) -> StageResult,
)
```
段の列は**現行の `runPostOptimization` と同じ順**に並べたリストで、名前は `chain.timed` の文字列を変えずに使う
（例: 「後処理 厳密日割当」「後処理 期間要件(c1)共同LNS」「後処理 違反起点修復(最終)」「後処理 全SOFT玉突き連鎖」）。

#### 2.3.2 1 段の実行（状態遷移）
```
          ┌──────────── enabled=false ────────────┐
READY ──▶ CHECK ──▶ RUN ──▶ EVAL ──▶ ADOPT / REJECT ──▶ RECORD ──▶ 次の段
            │        │ 締切・取消                 │
            │        └──────▶ ABORT（作業盤面を捨てる）─┘
            └ shouldStop()=true → PIPELINE_STOP（以降の段を飛ばし、最良盤面を返す）
```
- **CHECK**: `shouldStop()`（取消 or 全体締切）なら全体停止。`enabled` が偽なら記録だけ残して次へ。
- **RUN**: 現在の最良盤面の複製に対して `body` を呼ぶ。`body` は `StageContext.deadlineMs` と `shouldStop` を守る。
- **EVAL**: 結果盤面を `UnifiedViolationChecker` で採点（現行の `PostChain.adopt` と同じ）。
- **ADOPT/REJECT**: `reportComparator`（hard → weightedScore → total）で最良より良ければ採用。例外は `aptFairSoftTolerance` が
  有効な apt/fair 研磨だけ（現行どおり）。
- **RECORD**: 所要時間・採用の有無・族別の差を `MirrorLog` へ。文言は現行と同じ。
- **ABORT**: `body` が例外を投げたら作業盤面を捨て、最良盤面のまま次の段へ（§4.3 E-6）。

#### 2.3.3 ループ制御
- 巡回研磨クラスタ（`runPolishCluster`）は「改善が出る間は繰り返す」ループ。改修では回数・止め方を変えず、クラスタ全体を 1 つの複合段
  （`CompositeStage`）として扱い、内側に同じ `Stage` の列を持つ。
- 共同 LNS の「短い試行で採用が無ければ止める」（`lnsAdaptive`）は段の本体の中に残す。`lnsAdaptive` は常時 ON へ整理（`docs/flag_inventory.md`）。
- 決定的モードでは時間でなく評価回数で止める（現行の `maxEvaluations`、共同 LNS の 60 秒上限）。`StageContext.deterministic` で分岐する。

### 2.4 主処理（探索）
探索本体（SA・ALNS・RSI・Portfolio、C++ の `SaChunk`）は**改修しません**。変えるのは入口だけです。
- 現行: `V6OptimizerOptions` と `PolishGate` の読み取りが混在。
- 改修後: `V6NativeOptimizer.optimize(problem, config: EngineConfig, budget: TimeBudget.Slice, progress)`。中で読む値は現行と同じ。
- 停滞の早期脱出（`stallMs`・`stallHardMs`・`phaseGraceMs`）の計算式は `TimeBudget` へ移すが、式と定数は同じ。

### 2.5 最終番兵【確定・変えない】
`checkResultWorse`（`V6FinalPort.kt:1520`）が入力の採点と結果の採点を比べ、HARD・weightedScore・total のどれかが悪化していれば入力へ戻す。
2 層番兵（C++ の自己整合と Kotlin の `fullEval` 照合）と `NativeGate` もそのまま残す。

### 2.6 データ変換パイプライン
```
MagiState（JSON の写し） ──Problem.build──▶ Problem（数値化した静的表）
schedule: List<List<String>>（記号） ──normalizeSchedule──▶ Array<IntArray>（シフト番号、[職員][日]）
Array<IntArray> ──UnifiedViolationChecker──▶ ViolationReport（violations / needViolations / countViolations / *Families / hard / weightedScore / total）
ViolationReport ──ConstraintCatalog──▶ 画面の枠色・内訳チップ・診断文
Array<IntArray> ──記号へ戻す──▶ 保存 JSON と CSV 書き出し
```
改修で変わるのは「ViolationReport → 画面」の段だけです（読み替えの出どころを `ConstraintCatalog` に一本化）。各段の入出力の型は変えません。

### 2.7 計算量の見積もりとボトルネック対策
記号: S＝職員数（≤30）、T＝日数（≤31）、K＝シフト種別数、R＝規則（c1・c3 族など）の総数、N＝S×T（≤930）。
| 処理 | 時間計算量【見積】 | 空間 | 改修での扱い |
|---|---|---|---|
| フル評価（`Evaluator.fullEval`・チェッカー） | O(N + S·K + T·K + Σ規則の窓長) ≒ O(N·(1 + R/S)) | O(N + S·K + T·K) | 変えない |
| Δ評価（1 手） | O(影響する窓の長さ)。c3n はビット走査（`C3nBitScan`）で O(T/64) | O(1) 追加 | 変えない |
| SA の 1 秒あたりの手数 | 数十万〜数百万手（端末と盤面で変わる） | — | S5 で −2% 以内を確認 |
| 後処理の各段 | 段ごとに締切で頭打ち＝予算に比例 | O(N) の作業盤面 1〜2 枚 | `Stage` 化で盤面複製を 1 段 1 回に揃える |
| `EngineConfig` の作成 | O(フラグ数)、実行ごとに 1 回 | 数百バイト | 新規。探索の内側では読まない |
| `ConstraintCatalog` の参照 | O(1)（族 21 の表） | 定数 | 新規。描画のたびに引くが表の参照だけ |

ボトルネック対策:
- **内側のループに新しい処理を入れない。** `EngineConfig` の値は探索開始前にローカル変数へ写す（現行の `PolishGate` 読み取りと同じ回数以下）。
- **段の枠のオーバーヘッド**は 1 段あたり盤面の複製 1 回＋採点 1 回で、現行の `PostChain.adopt` と同じ。段の数も同じ。
- **測定**: 段ごとに `host_parity_bench.cpp` とホストのベンチで参考値を取り、S5 の線を割ったらそのコミットで止めて原因を直す。

---

## 3. 入出力およびデータ構造定義

### 3.1 変更不可な入出力とのマッピング【確定】
| 外部の形 | 内部の形 | 往復の保証 |
|---|---|---|
| 保存 JSON（`MagiState`、`model/MagiState.kt:72`。項目は `docs/data-models.md`） | `MagiState` → `Problem` | 既存 JSON を読み、そのまま保存して差分 0（改修前後で同じ） |
| CSV 取込（種別タグ `群回数`/`群組合せ禁止` を含む） | `MagiState` の該当項目 | 既存の取込テストが全件合格 |
| CSV 書き出し | 記号の勤務表＋内訳 | 改修前後で同じ入力からバイト一致 |
| 勤務表（記号の 2 次元リスト） | `Array<IntArray>`（シフト番号） | `normalizeSchedule` と逆変換で往復一致 |
| 画面に出す違反（枠色・チップ・診断文） | `ViolationReport` | 診断文のスナップショット一致 |
| C# の JSON・CSV | 同じ形式 | C# のゴールデンテスト |

### 3.2 `EngineConfig`【設計】
```kotlin
data class EngineConfig(
    val algorithm: V6Algorithm,          // AUTO なら plan から決まる
    val plan: OptimizationPlan,
    val workers: Int,
    val strength: SearchStrength,        // ふつう / じっくり
    val deterministic: Boolean,
    val search: SearchFlags,             // PolishGate の探索フラグのうち残すもの
    val post: PostFlags,                 // PostOptimizationParams の真偽値と上限値
) {
    companion object {
        fun build(state: MagiState, ui: UiSettings, seconds: Int): EngineConfig  // 唯一の作り方。検証もここ
    }
}
```
- 規則: 実行開始時に 1 回だけ作る。作った後は変えない。エンジンの中から `PolishGate` を読まない。
- `SearchStrength.THOROUGH` は現行どおり `combineExhaustPairs`・`wideC3nBreakDays`・`countChainPolish`・`aptFairSoftTolerance` を ON にする。
- 既定値は現行の本番既定と同じ。ベンチが素の `PostOptimizationParams()` を使っている箇所は本番既定へ揃える（`lnsAdaptive` の常時 ON で出力が変わるため）。

### 3.3 `TimeBudget`【設計】
```kotlin
class TimeBudget private constructor(val startMs: Long, val searchMs: Long, val postReserveMs: Long, val chainReserveMs: Long) {
    val hardDeadlineMs: Long get() = startMs + searchMs + postReserveMs + chainReserveMs  // 現行 :577 と同じ式
    fun searchSlice(): Slice
    fun postSlice(nowMs: Long): Slice
    fun share(rule: BudgetShare, nowMs: Long): Slice  // 段の取り分。現行の式（半分・8:6 按分・/4・上限値）を写す
    data class Slice(val deadlineMs: Long, val deterministic: Boolean)
    companion object { fun start(seconds: Int, clock: () -> Long = EngineClock::nowMs): TimeBudget }
}
```
- 現行の予算の式（`normalStallMs` の下限 20,000 ms、後処理と玉突きの枠 各 10 秒、共同 LNS の残り半分と 8:6 按分、玉突きの残り/4 と上限）は
  **値を変えずに**ここへ集める。
- 時計は引数で差し替えられる（テストで締切の境界を作るため）。

### 3.4 `ConstraintCatalog` と `ConstraintSpec`【設計】
```kotlin
data class ConstraintSpec(
    val key: String,            // "c1" "covU" など。ViolationReport のキーと同じ
    val weight: Double,         // MirrorKeys.weights から読む（ここに数値を書かない）
    val hard: Boolean,
    val space: KeySpace,        // CELL / COUNT / NEED（違反の位置のキー空間）
    val label: String,          // 画面の語（「期間の制約」など。現行の文言と同じ）
    val locate: (ViolationReport) -> List<Location>,
)
object ConstraintCatalog { val all: List<ConstraintSpec> /* 21 件 */ }
```
- 重みの単一の出どころは `MirrorKeys.weights` のまま。`ConstraintCatalog` は重みを複製しない。
- 移行は族ごとに 1 コミット（方針書 §6.6 の順）。各コミットで診断文のスナップショットが一致すること。

### 3.5 ViewModel の分割【設計】
| 持ち主 | 責務 | 現行の場所 |
|---|---|---|
| `OptimizeController` | 開始・取消・進捗・結果の反映・背景からの復帰 | `MagiViewModel` の optimize 系 |
| `EditController` | セル編集・固定・元に戻す | 編集系 |
| `PersistenceController` | 自動保存・読込・取込・書き出し・再試行 | 保存系 |
| `ConsultController` | 相談中（D10）・変更案・試算と承認 | 相談・変更案系 |
| `AnalysisController` | 分析タブの派生値 | 分析系 |
`MagiViewModel` はこれらを束ね、`UiState` を 1 本にまとめるだけにします。C# の `MagiApp.ViewModels` も同じ境界で分けます（停止明け）。

### 3.6 変えない内部型【確定】
`Problem`（`v6/Problem.kt:20`）、`ViolationReport`（`v6/MirrorCore.kt:26`）、`DeltaEvaluator`（`v6/DeltaEvaluator.kt:17`）、
`UnifiedViolationChecker`（`v6/MirrorCore.kt:181`）、C++ の `SaChunk` と評価器。

---

## 4. 境界値・例外処理・整合性バリデーション

### 4.1 エッジケース一覧
| ID | 場面 | 期待する振る舞い | 現行の扱い【確定】／改修後 |
|---|---|---|---|
| B-1 | 職員 0 名・期間 0 日（JSON・CSV 取込で外部から入る） | 「つくる」を始めず理由を出す | `V6FinalPort.kt:526-533` のガード。改修後は `EngineConfig.build` で同じ文言 |
| B-2 | 職員 1 名・30 名、期間 1 日・28〜31 日 | 落ちずに結果を返す。30 名・31 日でビット化経路が有効 | 境界 fixture を P0 で追加 |
| B-3 | 予算 1 秒・300 秒超 | `[1, 300]` へ丸める | `:533`。改修後も同じ |
| B-4 | 個人上限 0 | 最適化器は置かない。番兵の基準は上限 0 を外した入力 | `clearCappedCells`。変えない |
| B-5 | 実現できない希望 | 利用者が許可しなければ止める | `confirmDespiteImpossibleWishes`。変えない |
| B-6 | 需要 0 の日・構造的不足（covU が解けない） | 最良の盤面を返し、不足は違反として表示 | 変えない |
| B-7 | 壊れた JSON・列の欠けた CSV・範囲外の数値 | 落ちずに理由を表示し、現在の状態を壊さない | 既存の取込処理。改修で文言を変えない |
| B-8 | 二重起動 | 実行中は開始しない | `optimizeInFlight`。`OptimizeController` へ移す |
| B-9 | 実行中の取消・画面回転・背景移行 | 盤面は実行前か最良のどちらか | `BgHandoff`。`OptimizeController` へ移す |
| B-10 | 実行中の設定変更 | その実行には効かない | 現行は「実行中はスイッチ無効」で守る。改修後は `EngineConfig` が不変なので構造で守る |
| B-11 | ネイティブ `.so` が読めない・C++ と Kotlin の不一致 | `NativeGate` が閉じ、Kotlin で同じ結果を返す（遅くなるだけ） | 変えない。P1 でテスト追加 |

### 4.2 エラーハンドリング仕様
| ID | 失敗 | リトライ | ロールバック | 通知 |
|---|---|---|---|---|
| E-1 | 入力の検証失敗（B-1・B-5） | しない | 不要（何も変えていない） | 理由を画面に出す |
| E-2 | 探索中の例外 | しない | 実行前の盤面に戻す | 「最適化失敗」と原因を出す（現行文言） |
| E-3 | ネイティブの不一致 | しない（その実行は Kotlin へ切り替え） | 不一致の盤面を捨てる | ログのみ（利用者には速度低下として現れる） |
| E-4 | 締切切れ | しない | 不要。その時点の最良を返す | 進捗表示が終わる |
| E-5 | 取消 | しない | 最良または実行前 | 取消の表示 |
| E-6 | 後処理の段の例外 | しない | その段の作業盤面を捨て、最良のまま次の段へ | ログに段名と例外 |
| E-7 | 結果が入力より悪い | しない | 入力へ戻す（`checkResultWorse`） | 理由をログに出す |
| E-8 | 保存の失敗 | する（現行の回数と間隔） | 一時ファイルに書いてから置き換えるので元のファイルは壊れない | 失敗が続けば画面に出す |
| E-9 | 背景キューへの登録失敗 | しない | 登録前の状態へ戻す（`asyncEnqueueFailureCleansUp`） | 画面に出す |

### 4.3 机上検証（例外処理）
| 確かめたこと | 結果 |
|---|---|
| E-6 を足しても出力が変わらないか | 現行の `PostChain` は段の例外を外へ投げ、`handleOptimize` の catch で E-2 になる。E-6 は「その段だけ捨てて続ける」なので**出力が変わる可能性がある**。→ 方針: P4 では現行と同じく外へ投げる挙動を保ち、E-6 は改修の外（§11 の別手続き）とする。上表の E-6 は**改修後の目標ではなく将来案**として扱い、P4 の受け入れには使わない |
| E-7 の判定順が `reportComparator` と同じか | `checkResultWorse` は hard → weighted → total（`:1525-1531`）。`betterReport` と同順。一致 |
| B-10 を構造で守ると挙動が変わらないか | 現行も実行中の書き換え経路は無い（スイッチは実行中は無効）。値の読み取り時点が「開始時の写し」に変わるだけで、読む値は同じ。一致 |
| E-8 の再試行が配線されているか | 配線のテストが無い（W6）。P1 の `SaveRetryWiringTest` で確かめるまで「未検証」とする |
| B-11 の退化が JVM で通るか | ホストは `.so` を読めないため、退化経路を強制する試験用の口を P1 で足す。足すまで「未検証」 |

---

## 5. 非機能要件とテスト完了基準（DoD）

### 5.1 性能目標値
| 項目 | 目標 | 測り方 |
|---|---|---|
| 実行時間 | 同じ予算で経過時間の中央値が基準版以下（+2% かつ +1 秒まで許容）。予算の上限は全体で「予算＋20 秒」を超えない（現行の全体締切） | 6 fixture × seed 1〜5 × 60 秒、ワーカー 4、基準版と交互に実行（方針書 §2.4） |
| 評価の速さ | 1 秒あたりの手数が基準版の 98% 以上 | `tools/native/host_parity_bench.cpp` とホストのベンチ |
| メモリ | 実機の最大ヒープが基準版以下 | 利用者の実機ログ（同じデータ・同じ予算） |
| 最適化の質 | 必須の悪化 0、weighted 平均差の 95% 区間上端 ≤ 0 | 上と同じ実行の結果 |
| テスト時間 | `tools/host/hosttest.sh` の所要時間が基準版以下（揺れ +2%） | CI のログ |

10 倍化などの**速さの上積みは目標にしません**（0.2 のとおり）。

### 5.2 リグレッション防止の検証観点リスト
| # | 種別 | 観点 | テスト | 段 |
|---|---|---|---|---|
| T-1 | 単体 | 全 fixture でチェッカーの出力が基準版と一致 | `CheckerSnapshotTest` | P0 |
| T-2 | 単体 | 診断文が基準版と一致 | `DiagnosisSnapshotTest` | P0 |
| T-3 | 結合 | 決定的モードの最終盤面ハッシュが一致 | `BoardHashFixtureTest` | P0 |
| T-4 | 単体 | Δ評価＝フル評価（無作為の手で） | 既存の Delta 系テスト | 全段 |
| T-5 | 結合 | Kotlin＝C++ | native-parity CI | 全段 |
| T-6 | 単体 | `EngineConfig.build` が現行の本番既定と同じ値を作る（フラグごと） | 新設 | P2 |
| T-7 | 単体 | `PolishGate` への書き込みが 0 | grep の CI 検査 | P2 |
| T-8 | 単体 | `TimeBudget` の各取り分が現行の式と同じ値（予算 1・30・31・60・210・211・300 秒、決定的モード） | 新設 | P3 |
| T-9 | 単体 | 各段が締切を守る（時計を差し替えて境界を作る） | 新設 | P3 |
| T-10 | 結合 | 段の名前の列が基準版と同順 | 新設（記録の段名を比べる） | P4 |
| T-11 | 単体 | `ConstraintCatalog` の 21 件が `MirrorKeys.weights` と一致 | 新設＋weight_lint | P5 |
| T-12 | 結合 | UI 層の既存テストが全件合格 | 既存 | P6 |
| T-13 | 結合 | 退化経路で同じ結果 | `NativeGateFallbackTest` | P1 |
| T-14 | 結合 | 保存の再試行の配線 | `SaveRetryWiringTest` | P1 |
| T-15 | 結合 | JSON の往復で差分 0、CSV 書き出しのバイト一致 | 既存＋新設 | P0 |
| T-16 | 結合 | 境界 fixture（B-2）で落ちない・番兵を通る | 新設 | P0 |
| T-17 | 統計 | 時間制の対比較（5.1） | `tools/loop/` | 最終 |
| T-18 | 実機 | 機能の棚卸し F1〜F15、TalkBack、端末幅 | 利用者の確認 | 最終 |
| T-19 | C# | Kotlin のテストを 1 対 1 で写し、C# のゴールデンが一致 | `MagiEngine.Tests` | 停止明け |

### 5.3 机上検証（テスト観点）
| 確かめたこと | 結果 |
|---|---|
| T-3 が探索の劣化を捕まえるか | 決定的モードは後処理だけ（`PostOptimizationParams.deterministic`）。探索本体の劣化は捕まえない → T-17 を必須にして補う（方針書 §3.3） |
| T-8 の予算点で全帯を通るか | 30/31 秒と 210/211 秒が計画の帯の境界、1 秒と 300 秒が丸めの境界。帯の内側に 60 秒。→ T-8 の予算点を 1・30・31・60・210・211・300 秒に直す |
| T-6 で `lnsAdaptive` の常時 ON が検出されるか | 本番既定は ON なので一致する。素の `PostOptimizationParams()` を使うベンチだけ出力が変わる → ベンチを本番既定へ揃えるコミットを P2 で分ける |
| スナップショットの期待値を作り直してしまう危険 | 作り直しは明示の環境変数があるときだけ許す。CI では作り直さない（方針書 §2.6） |
| C++ を変えないのに T-5 が要るか | Kotlin 側の評価器の呼び方が変わるため要る。native-parity は全段で走らせる |

### 5.4 完了の定義（DoD）
1. 方針書 §3 のゲート G1〜G16 をすべて満たす。
2. 本書の T-1〜T-17 が CI で合格し、T-18 を利用者が確認した。
3. 5.1 の性能目標をすべて満たす。
4. `PolishGate` への書き込みが 0、予算の定義が `TimeBudget` だけ、後処理が `PostPipeline`、違反の位置が `ConstraintCatalog` 経由、
   最大ファイルが 1,500 行以下【目安】。
5. 4.3・5.3 の「未検証」が 0。
6. C# の積み残し（方針書 §9.1）が空（C# の更新停止が明けてから）。

---

## 6. 付録

### 6.1 用語
| 語 | 意味 |
|---|---|
| keep-best | 新しい盤面が最良より良いときだけ採用する規則。比較は `reportComparator` |
| 決定的モード | 時間でなく評価回数で止め、同じ入力から同じ盤面を返す後処理のモード |
| 基準版 | 改修を始めた時点の main。SHA を固定して比較に使う |
| fixture | テスト用の固定の入力データ |

### 6.2 段と担当の早見表
| 段 | 作るもの | 主なテスト |
|---|---|---|
| P0 | スナップショットと境界 fixture | T-1・T-2・T-3・T-15・T-16 |
| P1 | 退化経路と保存の再試行のテスト | T-13・T-14 |
| P2 | `EngineConfig`、フラグ整理 | T-6・T-7 |
| P3 | `TimeBudget` | T-8・T-9 |
| P4 | `PostPipeline` | T-10 |
| P5 | `ConstraintCatalog` | T-11 |
| P6 | ViewModel の分割 | T-12 |
| P7 | 文書の正規化 | 数の検査 |

### 6.3 責務の置き場所（改修後）
| 責務 | 置き場所 |
|---|---|
| 探索と後処理の設定 | `EngineConfig` |
| 時間の配分 | `TimeBudget` |
| 後処理の段の順序・採用・記録 | `PostPipeline` |
| 違反の族の定義・位置・表示語 | `ConstraintCatalog`（重みは `MirrorKeys.weights`） |
| 違反の判定 | `UnifiedViolationChecker`（変えない） |
| 画面の状態 | 5 つの Controller と `MagiViewModel` |

---

## 7. 変更履歴
- 2026-10-22 初版（完全版）。ユーザーが示した 5 観点（トレーサビリティと選定根拠、処理フローと計算量、入出力とデータ構造、境界値と例外の机上検証、
  非機能要件とテスト観点の机上検証）で作成。

## 8. 机上検証の記録
- 行番号と型名を 2026-10-22 の main で照合した: `V6FinalPort.kt:153/501/526-577/1520`、`V6HotfixPasses.kt:117/536/687/813/905-908`、
  `MirrorCore.kt:26/181`、`DeltaEvaluator.kt:17`、`Problem.kt:20`、`MagiState.kt:72`、`NativeBridge.kt:98`。
- 計画の帯を照合して 1 件訂正（AUTO に ALNS 単独の帯は無い。31〜210 秒は RSI→ALNS）。T-8 の予算点を帯の両側へ直した。
- `PostChain`（`V6HotfixPasses.kt:687`）に try/catch が無いことを確かめた（段の例外は外へ出る）。
- 例外の検証で 1 件、出力が変わる案を見つけて改修の外へ出した（4.3 の E-6）。
- 計算量は見積であり、S5 の測定で置き換える。
