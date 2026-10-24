# MAGI ShiftOptimizer 全面改修 設計書（完全版・最終 v5・机上検証済み）

この文書は、MAGI ShiftOptimizer の全面改修について、**何を・なぜ・どの順で・何をもって合格とするか**を決めた正本です。
この repo を初めて読む開発者や、他社の AI が、この 1 冊だけで改修に参加できるように、背景・用語・根拠となるコードの場所まで書いています。

- 版: v4（2026-10-22、最終）。v3 に、S1 の対象となる既存機能の棚卸し（§2.5）、意義の反映の自己検査（§2.6）、コミットごとの確認表（§14.1）を足したもの。v3 は v2 を机上検証し（§15）、用語と背景を補って書き直したもの。
- 決定者: リポジトリの所有者（以下「利用者」）。本文の「決定」はすべて利用者の指示で、日付を添えています。
- 正本の優先順位: 業務ルールは `docs/business-logic.md`、データ項目は `docs/data-models.md`、アプリの振る舞いの不変条件は `docs/invariants.md`、
  重みの値は `MirrorKeys.weights`（`app/src/main/java/com/magi/app/v6/MirrorCore.kt`）が正です。本書はそれらを変えません。
- コードの場所は `ファイル:行` で書いています。行番号は 2026-10-22 の main（3.723.2、`2dcc15d1`）時点です。

---

## 1. この文書を読む前に知っておくこと

### 1.1 アプリの概要
MAGI ShiftOptimizer は、看護師の勤務表（シフト表）を自動で作る Android アプリです。職員・シフトの種類・必要人数・希望・各種のルール
（制約）を入力すると、内蔵の最適化エンジン（MAGI V6）が 1 か月分の勤務表を作り、ルール違反を数えて画面に示します。
規模の前提は**職員 30 名以内・31 日以内**です。

同じアプリが 3 つの実装で存在します。
| 実装 | リポジトリ | 役割 |
|---|---|---|
| Kotlin（Android） | `ichirocc/magi7ichiro-fork` | **正（source of truth）**。仕様はここで決まる |
| C++（ネイティブ） | 同 repo の `app/src/main/cpp/magi_native.cpp` | 評価と焼きなましの高速化。Kotlin と同じ値を返すことを CI が検査 |
| C#（Windows） | `ichirocc/-MAGI_PC`（WinUI 3） | Kotlin の同値移植。Kotlin の変更を同日に写す |

### 1.2 用語
| 用語 | 意味 |
|---|---|
| 盤面（board / schedule） | 職員×日の 2 次元配列。値はシフトの番号。未割当は −1 |
| 違反の族（family） | ルールの種類。21 族ある（例: covU＝人員不足、c3n＝禁止の並び、pref＝希望が叶っていない） |
| 必須（HARD） | 6 族: groupViol・covU・c3n・c3w・pref・extWish。これが 0 で「配れる」 |
| 要調整（SOFT） | 残り 15 族。重み付きで少ないほど良い |
| weightedScore | Σ（族の件数×重み）。重みは `MirrorKeys.weights` だけが持つ |
| チェッカー | `UnifiedViolationChecker`（`MirrorCore.kt`）。違反を数える唯一の正。画面の表示も採否もこれに従う |
| 目的関数 | 最適化器が使う評価（`Evaluator`・`DeltaEvaluator`）。チェッカーと同じ式で、Δ（差分）とフル（全件）の値が一致する |
| keep-best | より良い盤面だけを採る規則。比較は「必須の件数 → weightedScore → 違反の総数」の辞書順で、`reportComparator` だけが実装する |
| 最終番兵 | `checkResultWorse`（`V6FinalPort.kt:1520`）。最終結果が入力より悪ければ入力へ戻す |
| 2 層番兵・NativeGate | C++ の自己整合検査と Kotlin のフル評価との照合。食い違えば `NativeGate` が閉じて Kotlin の経路へ戻る（`SaOptimizer.kt:180/194`、`V6FinalPort.kt:1134`）。誤った出力ではなく速度低下として現れる |
| mayPlace | 最適化器がその職員にそのシフトを置いてよいか。担当可能かつ個人の上限 0 でない（`Problem.mayPlace`）。候補生成・入口・最終番兵が共有する |
| 後処理（post-processing） | 探索の後に走る研磨・修復の段の連鎖（`V6HotfixPasses`・`V6FinalPort`） |
| 決定的モード | 後処理だけを時刻に依存せず回すモード（`PostOptimizationParams.deterministic`、`V6HotfixPasses.kt:611`）。同じ入力なら同じ盤面になる |
| HF77 | 変更の規律。重み・閾値・データ値は、利用者の明示の数値指示なしに変えない |
| 版 | `app/build.gradle.kts` の `versionCode`／`versionName`。1 つの変更の単位 |

### 1.3 いまの構造（層）
```
MainActivity
  └ MagiApp（画面の骨格・5 タブ）
       └ MagiRoot ─ 描画値を配る → 各 Composable（受け身の表示）
              ↑ MagiEvent を上げる
         MagiMediator（段階機械）＋ MagiArbiter（今その操作を通してよいか）
              └ MagiHandlers の鎖 → MagiViewModel（状態・実行・保存・相談・修正提案のハブ）
                    ├ MagiState（JSON の形そのもの） ⇄ StateParser / ScheduleCsvBridge
                    └ V6NativeOptimizer（探索） → Problem / Evaluator / DeltaEvaluator / C++
                          └ V6FinalPort.handleOptimize（予算・後処理・最終番兵）
OptimizationWorker（WorkManager の前景サービス）が背景で最適化を回す
```
UI 層は 3.558.0〜3.565.0 で「受け身の表示＋裁定の一元化」に作り直し済みです（`docs/architecture.md`）。

---

## 2. 全面改修の定義・意義・受け入れ基準

### 2.1 定義
全面改修とは、**アプリの外から見た振る舞い（最適化の結果・判定・画面の意味・保存形式）を変えずに、内部の構造を全層にわたって作り直すこと**です
（2026-10-22 利用者決定「全面改修する」）。
- 範囲: エンジン（探索の設定・時間予算・後処理）、制約の「どこが何の違反か」の扱い、ViewModel、テストの仕組み、文書。Kotlin・C++・C# の 3 実装すべて。
- 進め方: **一度にすべて置き換える**（2026-10-22 利用者決定）。統合ブランチ（作業ブランチ `claude/merge-wub4fq`）の上で全段を作り切り、
  全ゲートを満たしてから main へ 1 回で入れる。統合ブランチの中は段ごとにコミットを分ける（どこで結果が変わったかを後から二分探索できるように）。
- 含まないもの: 重み・閾値の変更（HF77）、探索の動きの変更、業務仕様・データ形式の変更。これらは別の手続きで扱う（§11）。

### 2.2 意義と受け入れ基準（2026-10-22 利用者指示）
「同等以上」は、**改修を始めた時点の main（基準版。SHA を固定する）より悪くならないことを、測って示す**という意味です。
| # | 意義 | 比べるもの | 合格の線 |
|---|---|---|---|
| S1 | 既存機能の同等以上 | 画面の操作・判定・診断・保存・取込の結果 | 既存の Kotlin・C# のテストが全件通る。チェッカー出力・診断文のスナップショットが一致。機能を減らすのは決定済みのフラグ整理（§10）だけ |
| S2 | 既存の高精度以上 | 違反の判定と点数 | 違反リスト・族ごとの件数・weightedScore が全 fixture で完全一致。Δ＝フル、Kotlin＝C++ |
| S3 | 既存の最適化の同等以上 | 最終盤面の質 | 決定的モードの盤面ハッシュが一致。時間制は基準版と対で比べ、必須の悪化 0、weighted の平均差の 95% 区間の上端が 0 以下 |
| S4 | 最適化の時間の同等以上（遅くならない） | 同じ予算での経過時間 | 経過時間の中央値が基準版以下（許容 +2% かつ +1 秒） |
| S5 | パフォーマンスの同等以上 | 評価の速さ・メモリ | `tools/native/host_parity_bench.cpp` の 1 秒あたりの手の数が基準版以上（許容 −2%）。ホストテストの所要時間、実機の最大ヒープが基準版以下 |
| S6 | 責務の整理整頓 | 責務の置き場所 | §13 の完了の定義を満たす |

許容幅（+2%・+1 秒・−2%）は計測の揺れを吸収するための値で、利用者が承認しています（2026-10-22「推奨で」）。

### 2.3 意義から仕様への追跡表（意義が設計に反映されていることの確かめ）
6 つの意義それぞれについて、どの段で何をし、どの検査で守り、どこで最終的に測るかを 1 対 1 で結びます。
表の空欄（どの段でも守られていない意義）が無いこと、各段が少なくとも 1 つの意義に寄与していることを、本書の変更のたびに確かめます。

| 意義 | 脅かす可能性のある段 | その段での守り方（各コミット） | 最終の測り方（§12） | 落ちたときの扱い |
|---|---|---|---|---|
| S1 機能 | P2（フラグ整理でスイッチを消す）・P5（修正提案の位置）・P6（ViewModel 分割） | 既存テスト全件、チェッカー・診断のスナップショット一致、画面の実コンパイル。消す機能は §10 の決定分だけで、消すコミットに決定の版を書く | Kotlin・C# の全テスト、`android-sdk`、WinUI ビルド | 消してよいと決めていない機能が減っていたら、そのコミットを戻す |
| S2 精度 | P5（ConstraintCatalog）・P2d（`quantitativeRangeEval` の撤去） | 違反リスト・breakdown・weightedScore のスナップショット一致、Δ＝フル、`native-parity` | 全 fixture で完全一致 | 1 件でも違えば原因を直す（期待値を作り直さない） |
| S3 最適化の質 | P2〜P4（設定・予算・後処理の移し替え） | 決定的モードの盤面ハッシュ一致（P0 の 18 本） | `HandleOptimizeBench` 6 データ×5 seed×基準版と対、必須の悪化 0、weighted の 95% 区間の上端 ≤ 0 | 悪化と言える差が出たら、段ごとのコミットを二分探索して原因の段を直す |
| S4 時間 | P3（予算）・P4（パイプラインの呼び出しの重さ） | 時間制の割当 ms ログの前後一致、予算・締切の値の一致 | 経過時間の中央値 ≤ 基準版＋2% かつ＋1 秒 | 同上 |
| S5 性能 | P2（設定の受け渡しを毎回の手で読まない）・P4（Stage の包み）・P5（Catalog の呼び出し） | 評価の内側の繰り返しに新しい割り当て・仮想呼び出しを入れない（コードレビューの観点）。`host_parity_bench` の手の数／秒を段ごとに参考測定 | 手の数／秒 ≥ 基準版−2%、ホストテスト時間・実機の最大ヒープ ≤ 基準版 | 内側の繰り返しの変更を戻す |
| S6 整理整頓 | 全段 | 段ごとに §13 の該当項目（フラグ・予算・後処理・制約の位置・ViewModel）が 1 か所に寄ったことを grep で確かめる | §13 の完了の定義 | 未達の項目を残したまま main に入れない |

各段が寄与する意義（逆引き）:
| 段 | 主に進める意義 | 主に守る意義 |
|---|---|---|
| P0 安全網 | ―（測るための土台） | S1〜S5 の比較を可能にする |
| P1 穴埋め | S6（テスト無しの機構を 0 に） | S1・S2 |
| P2 EngineConfig・フラグ整理 | S6 | S1・S3・S5 |
| P3 TimeBudget | S6 | S3・S4 |
| P4 PostPipeline | S6 | S3・S4・S5 |
| P5 ConstraintCatalog | S6 | S1・S2・S5 |
| P6 ViewModel 分割 | S6 | S1 |
| P7 文書 | S6 | ― |

### 2.4 測り方の手順（再現できるように固定する）
- 基準版: 統合ブランチを切った時点の main の SHA を本書 §4.4 に書く。比較はすべてこの SHA と統合ブランチの先端の対で行う。
- データ: `app/src/test/resources/` の 6 fixture。実機データを使う場合は利用者から受け取った JSON を固定して使い、ファイル名と SHA-256 を記録する。
- S3・S4: `tools/loop/HandleOptimizeBench.kt` を、基準版と先端の同じ seed（1〜5）・同じ予算（60 秒）・workers 4 で交互に走らせる
  （同じ機械・他の重い処理を止める）。集計は対ごとの差（先端−基準版）の平均と 95% 区間（t 分布）、経過時間の中央値。
- S5: `host_parity_bench` を各 5 回走らせ中央値を比べる。ホストテスト時間は `tools/host/hosttest.sh` の所要時間を各 3 回。実機の最大ヒープは利用者の実機ログ（同じデータ・同じ予算）。
- 結果の CSV とコマンドを `tools/loop/results/` に置き、`docs/history/` に要約と合否を書く。

### 2.5 既存機能の棚卸し（S1 の対象）
「既存機能の同等以上」を確かめる対象を、画面仕様（`docs/screen_spec.md`）の章立てに沿って固定します。改修の後、この表のすべての行が「同じ」でなければなりません。
| # | 機能 | 主なコード | 守るもの（自動） | 実機で見るもの |
|---|---|---|---|---|
| F1 | 未読込・読込（JSON）・保存・自動保存 | `StateParser`、`RunFiles`、保存の世代 | 往復テスト、保存系テスト（G6） | 読込→保存→再読込で同じ |
| F2 | CSV 取込・書き出し（勤務表・名簿・制約） | `ScheduleCsvBridge`、`CsvPartialImport` | CSV 往復テスト、`CsvSavedMarkTest` | 取込の完了メッセージ |
| F3 | 勤務表をつくる（前景・背景・中断復帰） | `V6FinalPort.handleOptimize`、`OptimizationWorker`、`BgHandoff` | P0 の盤面ハッシュ、G15 | 実行中の表示・中断からの復帰 |
| F4 | 違反の判定と内訳（21 族） | `UnifiedViolationChecker` | チェッカーのスナップショット（G1・G8） | ― |
| F5 | 設定ミスの診断・つくる前の確認 | `V6SanityPort`、`PreRunCheck`、`ConstraintMus` | 診断文のスナップショット、`PreRunCheckTextTest` | ― |
| F6 | ホーム（主カード・状態の 1 行・停止の説明） | `MagiDashboardCards`、`StopExplanation` | ビューデータのテスト、`StopExplanationBoundaryTest` | 画面の文言 |
| F7 | 勤務表タブ（グリッド・問題パネル・セル編集シート） | `MagiScheduleViews`、`SchedulePanel`、`CellEditSheet`、`CellSheetLogic` | `CellSheetLogicTest`・`Review37xxTest` 群、`ScheduleWriteSitesTest` | セルの印・パネル・シート |
| F8 | 変更案（探す・確認・適用）・連鎖の変更案 | `V6SwapSuggester`（`FixSuggester`）、`FixApplyGate`、`ChainFixPreview` | 適用ゲートのテスト | 案の一覧と適用 |
| F9 | 希望の試算（S5）・設定を緩める試算と承認（S6、非表示） | `WishTrial`、`RelaxTrial`、`RelaxApproval`、`RelaxRollback` | `RelaxApprovalTest`・`RelaxRollbackTest`、試算の純粋性テスト | S6 は非表示のまま |
| F10 | 相談（保留） | `ConsultList` | 相談のテスト（ID で引く） | ― |
| F11 | 編集タブ（月次条件・職員管理・年間マスター・各エディタ） | `Ws1Editor` ほか、`MagiConstraintsView`、`MagiConditionsView` | エディタの入力検査のテスト | 入力・並び替え |
| F12 | 分析タブ | `AnalysisTriage`、`V6PortAnalyzer` | 分析の集約のテスト | ― |
| F13 | 設定（時間・並列・探索の強さ・玉突き・外観・データ） | `MagiSetupCards`、`SearchStrength` | 設定の既定値の表テスト（P2a） | フラグ整理で消すスイッチは §10 の決定分だけ |
| F14 | 視覚対応（拡大・読み上げ・コントラスト） | `A11y`、各 Composable | `design_lint`、A11y のテスト | TalkBack・文字拡大 |
| F15 | 操作ログ・端末ログ | `logOp` ほか | ログの行の形式のテスト（S6 の結果行など） | 実機ログの読み方が変わらない |

### 2.6 意義の反映の自己検査（反証の試み）
意義が仕様に反映されていない可能性を、こちらから探して潰した記録です。
| 想定した抜け | 検査の結果 | 対処（本書の該当箇所） |
|---|---|---|
| S1: 「機能」の範囲があいまいで、消えても気づかない | v3 では範囲が無かった | §2.5 の機能棚卸し F1〜F15 を追加 |
| S2: 期待値を作り直して一致させてしまう | 起こりうる | §2.3 で「期待値を作り直さない」、§6.1 で作り直しは明示の環境変数でだけ |
| S3: 決定的モードしか盤面で比べられず、探索部の劣化を見逃す | 起こりうる | §3.3 の限界を明記し、§12 で時間制の統計比較（G15）を必須に |
| S3: seed や予算を変えて有利に比べる | 起こりうる | §2.4 で基準版と同じ seed・予算・並列数・同じ機械・交互実行に固定 |
| S4: 予算そのものを短くして速く見せる | 起こりうる | §2.2 で「予算・締切の値は基準版と同じ」、P3 で数値ごと移す |
| S5: 画面の応答・起動を測る手段が決まっていない | ホストでは測れない | 実機ログでの確認を §12 の手順 4 に入れ、利用者に同じデータ・同じ予算の実機ログを依頼する（外部依存として明記） |
| S5: 評価の内側に重い処理を入れても機能テストは通る | 起こりうる | §2.3 で「内側の繰り返しに割り当て・仮想呼び出しを入れない」をコミットごとの観点に、`host_parity_bench` を段ごとに参考測定 |
| S6: 整理したつもりで古い経路が残る | 起こりうる | §13 の各項目を grep で確かめる（`PolishGate` の書き込み 0 など）、§14.1 の確認表 |
| 全体: 途中で基準を緩めて先に進む | 起こりうる | §12・§8 で「合格の線は緩めない」、G14 で一括の最終判定 |
| 全体: C# が同等でなくなる | 起こりうる | G10、§9.1 の積み残しが空であることを完了の定義に |

---

### 2.7 改修の背景と課題（なぜ改修するのか）
機能追加を重ねた結果、正しさは番兵とテストで守れている一方、**変更の費用**が上がっています。根拠は §4.3（W1〜W7）、要点は次の 4 つです。
| 課題 | いま何が詰まっているか | 改修で目指す状態（完了基準は §13） |
|---|---|---|
| 設定の散在（W1・W2） | 時間予算が 7 ファイルに分散、探索フラグ 39 個が共有の可変オブジェクト。実行ごとの設定を再現できない | 予算は `TimeBudget` 1 か所、設定は不変の `EngineConfig` を実行ごとに渡す。フラグは §10 で半減以下 |
| 後処理の手書き連鎖（W3） | 段を足すたびに keep-best と記録を手で揃える | `PostPipeline` が段の keep-best・記録・打ち切りを共通に持つ |
| 違反位置の再解釈（W4） | UI・診断・修復が別々に位置を出し、「取り残し」型のバグが繰り返す | `ConstraintCatalog` を唯一の出どころにする |
| 巨大 ViewModel・テストの穴・文書の取り残し（W5〜W7） | 4,030 行の 1 ファイル、退化経路が CI で通らない、文書の数が実装とずれる | 最大 1,500 行【目安】、退化経路のテスト、文書の数を grep で検査 |

ボトルネックは「実行時の速さ」ではなく「安全に変えられる速さ」です。したがって性能・精度は**上げることを目標にせず、落とさないことを合格条件**にします（§2.2 の S2〜S5）。

### 2.8 変えない前提（改修の外側の固定条件）
次は改修の間、一切変えません。変えたくなったら改修とは別の手続き（§11）に出します。
| 区分 | 固定する内容 | 確かめ方 |
|---|---|---|
| 入出力 | 保存 JSON の項目と意味（`docs/data-models.md`）、既存 JSON の読込互換、CSV の列・種別タグ（`群回数`/`群組合せ禁止` を含む）、CSV 書き出しの内容 | 既存 JSON の往復テスト、CSV 取込・書き出しのテスト（§2.5 F1・F2） |
| 業務ルール | 21 族の定義・重み（`MirrorKeys.weights`）・HARD/SOFT の別、被覆は同日のみ、MIN/OR、`mayPlace`/`mayPlaceAt` の意味、決定記録 D3〜D10 | G2・G3（チェッカーのスナップショット）、weight_lint |
| 採用基準 | `reportComparator`（hard→weightedScore→total）、例外は `aptFairSoftTolerance` のみ | 盤面ハッシュ（G5）、grep で比較器の単一ソースを確認 |
| 探索の動き | 手の種類・確率・温度・再起動の規則・各段の順序 | 決定的モードの盤面ハッシュ一致、時間制の対比較（S3） |
| 規模 | 職員 30 名・31 日まで（ビット化経路が常に有効） | 境界 fixture（§2.9） |
| 実行環境 | Android minSdk/targetSdk 36、arm64-v8a のみ、NDK 26.1.10909125、Kotlin/JVM 17。Windows は .NET 8 + WinUI 3 | ビルド設定の差分が 0 |
| 3 実装の関係 | Kotlin が正、C++ は同値（native-parity）、C# は同日同期（停止中は §9.1 に積む） | native-parity CI、C# のゴールデン |
| 画面 | 片手一本指（ドラッグは決められた 3 か所だけ）、用語「グループ」、UD 固定の外観、画面の意味と文言 | 診断文のスナップショット、design_lint、§12 の実機確認 |
| 番兵 | 2 層番兵・`NativeGate`・最終番兵 `checkResultWorse` を削らない | `docs/safety_inventory.md` の 13 機構が全て残ることを grep |

### 2.9 仕様の網羅性チェック（例外・境界・非機能・テスト）
各段の終わりと §12 で、次の表を全行「済」にします。1 行でも空欄なら main へ入れません。
| 観点 | 確かめる内容 | 担当の段・テスト |
|---|---|---|
| 例外: 中断 | 実行中のキャンセル・画面回転・背景移行で、盤面が実行前か最良のどちらかに必ず戻る | P3・P6、`BgHandoff` 系テスト |
| 例外: ネイティブの不一致 | C++ と Kotlin が食い違ったとき `NativeGate` が閉じ、Kotlin で同じ結果を返す | P1 `NativeGateFallbackTest` |
| 例外: 保存失敗 | 書き込み失敗で再試行し、元のファイルを壊さない | P1 `SaveRetryWiringTest` |
| 例外: 不正な入力 | 壊れた JSON・列の欠けた CSV・範囲外の数値で、落ちずに理由を表示する | 既存の取込テスト＋P0 の診断スナップショット |
| 例外: 予算切れ | 各段が締切を守り、超えた段は打ち切って keep-best の結果を返す | P3 `TimeBudget` の単体テスト |
| 境界: 規模 | 職員 1 名・30 名、1 日・28/29/30/31 日、シフト種別の最小・最大 | P0 の fixture に境界 2 件を足す |
| 境界: 値 | 上限 0（`mayPlace`）、希望固定だけの職員、需要 0 の日、解なし（構造的不足） | 既存テスト＋チェッカーのスナップショット |
| 境界: 予算 | 画面で選べる最短・既定・最長の予算で、段の配分が破綻しない（backlog #16） | P3 |
| 非機能: 時間・速さ・メモリ | S4・S5 の測定（§2.4） | §12 |
| 非機能: 決定性 | 決定的モードで同じ入力から同じ盤面 | G5 |
| 非機能: 並行性 | エンジンの設定が実行中に書き換わらない（`EngineConfig` が不変） | P2 のテスト＋`PolishGate` への書き込み 0 を grep |
| 非機能: 利用しやすさ | TalkBack・文字拡大・端末幅（D4 の OPPO A5 5G を含む） | §12 の実機確認 |
| テスト要件 | 既存テストを消さない（消すのは §10 の OFF 比較の腕だけ）、期待値を作り直さない、Kotlin のテストを C# へ 1 対 1 で写す | G1、§2.6、§9.1 |

## 3. 改修の間ずっと守る条件（合格ゲート）

### 3.1 ゲート一覧
| # | ゲート | なぜ必要か | 確かめ方 |
|---|---|---|---|
| G1 | チェッカーが判定の正 | 画面・採否・診断がすべてチェッカーに従っているので、ここが動くと全部が変わる | チェッカー系 JUnit 全件 |
| G2 | Δ＝フル | 最適化器は差分で評価するので、差分と全件がずれると誤った手を採る | Δ×フルのランダム手テスト、`host_parity_bench` |
| G3 | Kotlin＝C++、golden 不変 | C++ が別の値を返すと NativeGate が閉じて遅くなる | CI `native-parity`・`v6-engine-check`、期待値ファイルの差分 0 行 |
| G4 | 比較は `reportComparator` だけ | 比較を手で書き写すと、写した側だけ取り残される（過去に 4 回起きた） | 比較を手書きした箇所の grep が 0 件 |
| G5 | 最終番兵・2 層番兵を削らない | 正しさの最後の砦 | 番兵テスト、`NativeGateFallbackTest`（§6.2） |
| G6 | 保存の世代・所有権 | 古い実行が新しい保存を上書きする事故を防ぐ（3.485.0〜3.487.0） | 保存系テスト、`SaveRetryWiringTest`（§6.2） |
| G7 | 重み・パラメータ不変（HF77） | 利用者の明示指示なしに変えない決まり | `MirrorKeys.weights`・`Evaluator.fullEvalParts`・`DeltaEvaluator`・`magi_native.cpp`・C# `MirrorKeys.WeightOf` の差分 0 |
| G8 | 作り直しでは結果が変わらない | 全面改修の大前提 | §6.1 の 3 つのスナップショット |
| G9 | 探索の動きを変えるなら別手続き | 改修と性能改善を混ぜると原因を切り分けられない | §11 |
| G10 | C# 同日同期 | 2 アプリの振る舞いを揃える | `MagiEngine.Tests`・`MagiApp.ViewModels.Tests` 全件（ログで確認）、WinUI ビルド |
| G11 | 統合ブランチの中は段ごとにコミット | 結果が変わったときに二分探索できるように | 1 段＝1 コミット以上。移動と意味の変更を同じコミットに入れない |
| G12 | 画面に触れたら実コンパイル | Compose はホストでコンパイルできない | CI `android-sdk`（workflow_dispatch）、`tools/design_lint.py` の必須違反 0 |
| G13 | 版の採番 | 並行作業で番号が衝突した実例がある | `tools/check_version.py`（CI `design-lint`） |
| G14 | 一括で入れる | 利用者決定 | 統合ブランチの先端で G1〜G16 と S1〜S6 をすべて満たしてから main へ 1 回で |
| G15 | 最適化の質と時間 | S3・S4 | `tools/loop/HandleOptimizeBench.kt` で基準版と対で比較 |
| G16 | 性能 | S5 | `host_parity_bench`・ホストテスト時間・実機ログ |

### 3.2 アプリの不変条件（`docs/invariants.md`）との対応
ゲートは「改修の進め方」の条件、INV は「アプリの振る舞い」の条件です。改修の各コミットは、触れる INV を表で確かめ、その検証 T を走らせます。
| ゲート | 主に守る INV |
|---|---|
| G1・G2・G3 | INV-11（言語間の一致） |
| G4 | INV-05（採否の比較は 1 つ）、INV-09（悪い結果を採らない） |
| G5 | INV-04（希望固定を動かさない）、INV-09 |
| G6 | INV-07（試算は保存へ副作用なし） |
| G7 | INV-12（HF77） |
| G8 | INV-01〜INV-04（希望・上限 0・手動固定を自動で変えない） |

### 3.3 G8 の限界（重要）
`handleOptimize` 全体は時間に依存し、seed を固定しても同じ盤面にはなりません（`tools/loop/README.md`：後処理の時刻由来の種が残る）。
したがって「結果が変わらない」を盤面ハッシュで示せるのは、**後処理の決定的モード**と**チェッカー・診断の出力**までです。
探索部（焼きなまし・ポートフォリオ）の作り直しは、G2・G3（評価の一致）と、時間配分のログの前後比較、最後の G15 の統計比較で示します。

---

## 4. 現状

### 4.1 規模（2026-10-22）
| 層 | 行数 | 大きいファイル（行） |
|---|---|---|
| `v6/`（エンジン） | 31,167 | `V6NativeOptimizer` 2,360・`V6SanityPort` 2,016・`V6FinalPort` 1,533・`V6HotfixPasses` 1,460・`V6PortAnalyzer` 1,233 |
| `ui/` | 23,874 | `MagiViewModel` 4,030・`MagiScheduleViews` 2,435・`MagiDashboardCards` 2,137・`CellSheetLogic` 2,012・`MagiApp` 1,584 |
| `model/` | 583 | `MagiState`・`StateParser` |
| テスト | 232 クラス | ホスト JVM で全件（`tools/host/hosttest.sh`）、CI `v6-engine-check` |
| C# | 約 99,000 行 | `MagiViewModel` は partial 14 ファイルに分割済み |

### 4.2 すでにある土台（壊さない）
| 土台 | 中身 |
|---|---|
| 判定の正 | `UnifiedViolationChecker`。`breakdown` は `MirrorKeys.all` の 21 キーで初期化 |
| 目的関数 | `Evaluator`／`DeltaEvaluator`。SA が Δ×フルを照合 |
| 言語間の一致 | `native-parity` CI、`NativeParityFixtureTest`、`*_eval_expected.txt` |
| 2 層番兵 | C++ 自己整合＋Kotlin 照合、`NativeGate` |
| keep-best | `reportComparator` |
| 置けるかの判定 | `Problem.mayPlace`／`mayPlaceAt`、静的表 `c3wBan`・`extBanned` |
| 最終番兵 | `checkResultWorse` |
| 保存 | 世代・所有権、`RunFiles` の原子置換、`BgHandoff`、保存失敗の再試行 |
| UI の裁定 | `MagiRoot`／`MagiMediator`／`MagiArbiter`／`MagiEvent` |
| 書き込み口 | `ScheduleWriteSitesTest`（盤面を書き換えてよい関数を固定） |
| 版の採番 | `tools/check_version.py` |
| 安全機構の棚卸し | `docs/safety_inventory.md` |

### 4.3 弱点（机上検証済みの根拠つき）
| ID | 弱点 | 根拠 | 影響 |
|---|---|---|---|
| W1 | 時間予算の定義が分散 | `V6FinalPort.kt:259`（`normalStallMs`、下限 20,000 ms）・`:574`（`budgetMs`）・`:868`／`:888`（締切）・`:21/:23`（後処理と玉突きの枠 各 10 秒）、`V6NativeOptimizer.kt:1344`（`SaParams` の予算）、`V6HotfixPasses.kt:905-908`（共同 LNS の上限。通常は残り時間の按分、決定的モードだけ 60 秒）、`EjectionChainPipeline.kt:44`、`CombinatorialRepair.kt:44`。`EngineClock.kt`（23 行）は `nowMs`・`remainingMs` を持つが、予算を分け合う仕組みは無い | 配分の不整合（短い予算で RSI+ が持ち時間を超える＝backlog #16）、読み解きの難しさ |
| W2 | 探索フラグが書き換え可能な共有オブジェクト | `object PolishGate`（`V6HotfixPasses.kt:117`）に `var` が 39 個（うち `@Volatile` 36）。ほかに `PostOptimizationParams` の真偽値が約 25 個。UI スレッドと背景の復元が書き、エンジンのスレッドが読む。実行中に書き換わる経路は無い（スイッチは実行中は無効） | テスト間の状態漏れ、実行ごとの設定を再現できない、C# との対応表が無い |
| W3 | 後処理の段が手書きの連鎖 | `V6HotfixPasses` の後処理チェーン。走行中の keep-best は 3.608.0／3.610.0 で後付け | 段の追加・順序変更のたびに keep-best と記録を手で揃える |
| W4 | 「どのセルが何の違反か」を複数層が再解釈 | チェッカーを UI の 5 ファイル（`CellEditSheet`・`CellSheetLogic`・`FixExplain`・`FixTiers`・`MagiViewModel`）が直接呼ぶ。診断（`V6SanityPort`・`V6PortAnalyzer`）と修復（`ViolationComponentRepair`・各 Polish）も独自に位置を出す | 表示・診断・修復の食い違い（過去の「取り残し」型のバグ） |
| W5 | ViewModel が巨大（Kotlin） | `MagiViewModel.kt` 4,030 行 | 変更の衝突、レビューの難しさ |
| W6 | 安全機構にテストの穴 | `NativeGate` の退化経路は JVM のテストで一度も通っていない（ホストが `.so` を読めない）、保存の再試行の配線 | 退行を CI で捕まえられない |
| W7 | 文書の取り残し | `docs/architecture.md` に「18 違反種」（実際は 21） | 監査の誤誘導 |

### 4.4 進捗（2026-10-22）
| 項目 | 状態 |
|---|---|
| 版の採番検査（U-04） | 完了（PR #393） |
| 安全機構の棚卸し・フラグの棚卸し | 完了（`docs/safety_inventory.md`・`docs/flag_inventory.md`） |
| テストの穴埋めの一部（B-03・M-02・M-01・U-03・L-03） | 完了（3.723.2） |
| P0〜P7 | 未着手（統合ブランチで行う） |
| P8・P9（グループ上限 0・職員の照合） | 完了（案 A、文書のみ） |
| C# 同期 | 保留（2026-10-22 から 24 時間停止）。積み残しは §9.1 |

---

## 5. 目標の構造

```
model/       MagiState（保存形・Staff.id あり）, StateParser
problem/     Problem（不変。mayPlace/mayPlaceAt/c3wBan/extBanned）
constraints/ ConstraintCatalog — 族ごとの ConstraintSpec（位置と説明だけ。採点は持たない）
eval/        Evaluator, DeltaEvaluator, NativeEval（C++ は eval だけを写す）
search/      SearchOrchestrator — EngineConfig（不変）, TimeBudget（1 個）
post/        PostPipeline — Stage の並び。keep-best と記録はパイプラインが一律にかける
persist/     OptimizationRepository（世代・所有権）, RunFiles
ui/          MagiRoot/Mediator/Arbiter（既存）＋ 境界ごとの ViewModel（C# の partial と同じ名前）
```

主な型の形（案。名前は C# と 1 対 1 に揃える）:
```kotlin
// 実行開始時に 1 回だけ作り、実行中は変えない。既定値は今の PolishGate と同じ。
data class EngineConfig(
    val normalStallFraction: Double = 0.9,
    val floorCountsUpperZero: Boolean = true,
    // … §10 の整理後に残るフィールドだけ
) { companion object { fun snapshot(): EngineConfig = TODO("移行期間は PolishGate から作る") } }

// 予算は 1 個。子の予算は split で切り出す。今の式（下限 20,000 ms、各枠 10 秒など）は数値ごと移す。
class TimeBudget(val startMs: Long, val deadlineMs: Long) {
    fun remaining(now: Long = EngineClock.nowMs()): Long = EngineClock.remainingMs(deadlineMs, now)
    fun split(fraction: Double, floorMs: Long): TimeBudget = TODO()
}

// 後処理の 1 段。採否（keep-best）と記録（PostStageRecord）は段ではなくパイプラインが行う。
interface Stage { val key: String; fun run(board: Array<IntArray>, budget: TimeBudget, cfg: EngineConfig): Array<IntArray> }

// 族ごとに 1 つ。重みと点数の計算は持たない（それは Evaluator とチェッカーのまま）。
interface ConstraintSpec { val key: String; val hard: Boolean
    fun locate(report: ViolationReport): List<Cell>; fun explain(cell: Cell): String; fun repairHints(cell: Cell): List<Hint> }
```

---

## 6. 段階計画（統合ブランチの中の順番）

依存関係: P0 → P1 → P2 → P3 → P4 → P5 → P6 → P7。P8・P9 は完了済み。全段が揃ったら §12 の最終検証を経て main へ 1 回で入れる。

### 6.1 P0 安全網（最初のコミット）
基準版で次の 3 つの期待値を作り、以後のすべてのコミットで一致を確かめます。
1. `BoardHashFixtureTest`: 6 fixture（`app/src/test/resources/` の `golden_state`・`sample_state_v6`・`blocked_covu_state`・`sept2026_state`・
   `oct2026_grid_state`・`full_coverage_state`）で、決定的モードの最終盤面を `StateFingerprint`（`StateFingerprint.kt:26`）でハッシュし、
   `snapshots/board_hash.txt` と照合する。期待値の作り直しは `MAGI_UPDATE_SNAPSHOTS=1` のときだけ。seed は 1 本（決定的モードでは seed が盤面に
   効かず、seed 1〜3 が全 fixture で同じハッシュだった。3 本では 3 クラスで 66 秒＝増分の上限を超えた）。
2. `CheckerSnapshotTest`: 全 fixture の入力盤面について、違反リスト（族・職員・日）と breakdown を照合する。
3. `DiagnosisSnapshotTest`: `V6SanityPort` の診断文の一覧を照合する。
合格: 2 回続けて同じ値になる（決定性）。ホストテストの増分は 60 秒以内。C# にも同名のテストを置く。
済み（2026-10-22）: 期待値は main `4e8fe3a` から生成、2 回走行で一致・3 クラスで約 26 秒。C# 側は停止中のため §9.1 の積み残し。

### 6.2 P1 テストの穴埋め
| 穴 | 足すテスト | やり方 |
|---|---|---|
| NativeGate の退化経路 | `NativeGateFallbackTest` | 照合の判定を純関数に切り出し、「不一致なら閉じて Kotlin の結果を使う」を JVM で確かめる |
| 保存の再試行の配線 | `SaveRetryWiringTest` | 再試行の判定を純関数へ |
（B-03・M-02・M-01・U-03・L-03 は 3.723.2 で済み。）
済み（2026-10-22）: 2 層番兵の判定を `NativeGate.chunkConsistent`（1 層目）と `NativeGate.parityMismatch`（2 層目、照合 OFF は素通し）へ
切り出し、SA・LAHC・ALNS・研磨の 8 か所を置き換えた（閉じる理由の文言は不変）。ホストは `.so` を読めないので、テストは判定・ゲートの閉鎖・
閉じた後の Kotlin SA の結果がフル評価と一致し入力より悪くないことまで。保存は `SaveFailNotifier.report` が「ダイアログ・通知/ログ・書き直し
（`retryLater`）」を返し、VM の `reportAutoSave`・`persistNow` はそれを反映するだけ。

### 6.3 P2 EngineConfig とフラグ整理
| コミット | 中身 |
|---|---|
| P2a | `EngineConfig` と `snapshot()`。既定値の表テスト。`normalStallFraction`・`floorCountsUpperZero` を引数で読む形へ |
| P2b | 停滞・床・壁の系統と、後処理の系統を移す |
| P2c | 玉突きの系統を移す。`PolishGate` への書き込み口を設定画面だけに絞る |
| P2d | フラグ整理（§10）。1 件ずつ盤面ハッシュの一致を確かめる |

P2a 済み（2026-10-22）: `EngineConfig`（`normalStallFraction`・`floorCountsUpperZero`）と `EngineConfig.snapshot()`。`handleOptimize(config = snapshot())`
が入口で 1 回だけ写し、`watchdogBudget`・床の判定はそこから読む。`normalStallMs`/`watchdogBudget` の省略時は PolishGate でなく本番既定。T-6 は `EngineConfigTest`。
P2b 済み（2026-10-22）: 停滞・床・壁（`c3nWallShortStall`・`floorShortStall`・`c3nWallLegacy`・`c3nWallDeepCheck`・`adaptiveStall`）と後処理
（`combineExhaustPairs`・`lnsAdaptive`・`aptFairSoftTolerance`・`countChainPolish`）を `EngineConfig` へ。`postParams()` が `PostOptimizationParams` を作り、
`V6OptimizerOptions.config` で探索側（研磨の注入）にも同じ写しを渡す。エンジン内でこの 9 項目を `PolishGate` から読む箇所は 0。
P2c 済み（2026-10-22）: 玉突きの 11 項目（4 択の写像・入れ替え・探し直し・必須から・余った時間・秒数・範囲 2 つ）を `EjectionFlags` にまとめ、
`EngineConfig.ejection` → `PostOptimizationParams.ejection` → パイプライン・従来の玉突き・手直し提案（`FixSuggester.suggest(ejection=)`）へ引数で渡す。
`EjectionChainPipeline.Config`・`C1EjectionChainPolish.Config` の既定は大域でなく定数。`EjectionChainPipeline` は `EngineConfig` が型を持つため public に。
書き込み口: エンジン内の書き込みは 0。残るのは設定画面（VM のセッターと `MagiUiState` の強さ・玉突きの適用）と背景 Worker の `PolishGate.restore` だけで、
restore は P2d で残りのフラグ（`personSwapKick` など、まだ `PolishGate` から読む）を片付けた後に `EngineConfig` の復元へ置き換える。
P2d で消す 5 本（`hardEjectionChainEarly` など）は移さず `PolishGate` のまま。
P2d は 4 コミットに分ける: ① 到達不能の玉突き 5 本と測定用の変数（flag_inventory 4 章 B） ② 定着フラグを常に ON（A と決定 1・2・7）
③ 否決の腕の削除（B′ と決定 3〜5） ④ `quantitativeRangeEval`（決定 6、C++ も同じコミット、C# は §9.1）。各コミットで P0 スナップショット一致。
P2d-① 済み（2026-10-22）: `hardEjectionChainEarly`・`allEjectionChainEarly`・`allEjectionChainFinal`・`hardEjectionChainRetry`・
`allEjectionChainAfterRepair` の 4 分岐と宣言・snapshot/restore の鍵、`C1EjectionChainPolish.defaultMaxDepth`/`defaultNarrow`（既定値を定数へ）、
`EjectionChainScope.NEARBY`（画面に出ていない・保存しない）。LoopBench の 5 腕、ExtWishOptimizeTest の鍵。P0 スナップショット一致。
P2d-② は ②a エンジン内のフラグ ②b 後処理の引数 ②c `wishPinStrict` ②d 画面のスイッチ に分ける。
P2d-②a 済み（2026-10-22）: 常に ON にしてフラグを外した＝`rotationDeltaPrescreen`・`roundPassMemo`・`hardDeltaPrefilter`・`lateOpStopPropagation`・
`ejectionPipelineAfterRepair`（前の置き場所の分岐ごと）・`personSwapKick`（7 役割のローテーション固定）・`floorCountsUpperZero`（床は常に上限0込み）・
`C1JointLnsPolish.deltaChildEvalDefault`（Config の引数ごと）・`C1EjectionChainPolish.defaultCrossFamily`（v1 の候補生成ごと）・`FixSuggester.restoreWishLocked`。
OFF 比較のテストは ON の経路の検証（再現性・正式評価との一致・HARD を増やさない）へ書き換え、`PersonSwapBench`・`WasteRemovalProbe` と OFF 腕を削除。P0 スナップショット一致。
P2d-②b 済み（2026-10-22）: `PostOptimizationParams` の `componentRepairEnabled`・`componentRepairFinal`・`covOReliefEnabled`・`postChainRunningKeepBest`
（`PostChain` の引数ごと）・`lnsAdaptive`（`PolishGate`・`EngineConfig`・UI の非表示イベントとセッター・「設定の効き」の語ごと）を外した。
`PostOptimizationParams()` を素で作るベンチ・テストも本番と同じ共同 LNS の自動調整になる（§10 の注意）。P0 の盤面ハッシュはこれで変わらない
（基準版 `4e8fe3a` で lnsAdaptive=true にして作り直した期待値が md5 で同一＝作り直し不要を確認）。LoopBench の既定比較は廃止（機能名の指定が必須）。
P2d-②c 済み（2026-10-22）: `wishPinStrict`（規則 A、利用者決定 2026-09-25）を常時に。`Problem.wishMoveAllowed`・`keepsWishPins` の `strict` 引数と、
7 ファイル（V6NativeOptimizer・HardRepairCore・RsiHypothesisOperators・EliteIntegrationPolish・EliteRelinking・CovOReliefPolish・V6PortAnalyzer）の
`wishPinStrict` 引数を外した。`WishPinStrictTest`・`ManualPinTest`・`V6PortAnalyzerTest` の旧挙動（OFF）の比較は削除し、ON の検査だけ残す。P0 スナップショット一致。
P2d-②d 済み（2026-10-22、決定 1・A）: 画面のスイッチ 4 つを外した＝定着の「入れ替えも1手として使う」（`ejectionChainSwapMoves`、`C1EjectionChainPolish.Config.swapMoves` ごと常時）・
「採れたら探し直す」（`ejectionPipelineRounds`、`EjectionChainPipeline.Config.repeatRounds` ごと常時）、否決の「余った時間も玉突きに使う」（`ejectionUseLeftover`）・
「禁止の並びだけ残ったら早めに切り上げる」（`c3nWallShortStall`）。後者で到達しなくなった `c3nWallLegacy`・`c3nWallProven()`・`C3nWallProof.legacy()` も削除
（`c3nWallDeepCheck` は残存診断 `diagnoseBoard` から届くので残す。純関数 `effectiveStallMs` の壁の引数は仕様テストのため据え置き、本番は false を渡す。
`C3nWallProof.bound()` はテストからだけ呼ばれる＝③で片付ける）。非表示の `blockSwapC3nFilter`（`filterC3nIncrease`）は常時にしてイベント・セッター・状態ごと削除、
「設定の開始時」の事前フィルタは常に ON と出す。UI の状態・イベント・ハンドラ・説明文の定数も削除。P0 スナップショット一致・影響クラス 81 件合格。
P2d-③ 済み（2026-10-22、決定 2〜5）: 否決・測定用の腕をコードごと削除＝研磨 7 ファイル（`SoftCascadePolish`・`PrePostDescent`・`C2Polish`・`C42FlowPolish`・
`C3nMarginLnsPolish`・`RestZeroWindowLns`・`StallPolishInjection`）とそのテスト、`c1MoveARepair`（手A の禁止連続修復）、`adaptiveStall`（適応閾値と改善間隔の記録）、
`postChainRollbackCountsZero`、`rsiFocusRotationPersist`（Hf63 の持続カウンタ）、`c1ComponentRepair`（`exactWindow(useComponents)` の成分経路。`components()`・
`solveComponent` は単独窓修復と分析テストが使うので残す）、`fairAchievementDirection`、`dayAssignIdentityFallback`、各 `Reactivate` と `targetFamiliesRemain`、
UI の非表示イベント・セッター・状態 2 つ（`c1MoveARepair`・`stallPolishInjection`）、`PortfolioBudgetBench`。`countChainEnabled`（じっくり）は残す。
戻すときはこの直前のコミットから取り出す。P0 スナップショット一致・影響クラス 62 件合格。残り: `C3nWallProof.bound()` 系（②d で本番から外れた）は ④ の後に片付ける。
P2d-④ 済み（2026-10-22、決定 6）: `quantitativeRangeEval`（c2・c41・c41s の量的評価、3.512.0 否決・本番は常に false）を撤去。チェッカー・評価器・Δ評価器・
C++（`magi_native.cpp` の三項 8 か所・`c2Amount`/`rangeDistance`、TOC 再生成）の分岐を false 側へ畳み、`Problem`・`cachedProblem`・`ProblemCache` の鍵・
45 ファイルの引数と `PostOptimizationParams`/`V6OptimizerOptions`/`handleOptimize` の引数を外した。JNI の meta[7] は ABI を保つため 0 固定で渡し、C++ は読まない。
`QuantitativeRangeEvalTest`・`EliteIntegrationQuantitativeTest` を削除。`CheckerReportDigestTest` の期待値は基準版 `4e8fe3a` を qre=false だけで走らせても同じ（作り直し不要）。
P0 スナップショット・チェッカー等価・Δ評価・ネイティブ期待値（ホストの g++ で native-parity と同じ手順、0 不一致）を確認。C# は §9.1 #11。
P2 の後片付け（2026-10-22）: ②d で本番から外れた `C3nWallProof.bound()`・生存盤面との対応づけ・`c3nWallSameReport` とそのテスト 5 本を削除
（`C3nWallProof` は残存分析の `diagnoseBoard` だけ）。消したフラグを指していた古いコメント 4 か所を直した。
P2 の残り: エンジン内で `PolishGate` を直接読むのは `wideC3nBreakDays`（探索の内側 `V6SearchOperators` と「設定の効き」）だけ。探索の奥まで引数を通すので
P3（TimeBudget で探索の引数を整える段）で `EngineConfig` へ移す。背景 Worker の `PolishGate.restore` はそれまで残す。

### 6.4 P3 TimeBudget
| コミット | 中身 |
|---|---|
| P3a | `TimeBudget` 型と、`V6FinalPort` の予算（`budgetMs`・`normalStallMs`・締切）の移行 — **済**（2026-10-24。`TimeBudget.start`・`watchdog`・`overBudget`、旧式との一致は `TimeBudgetTest`＝T-8） |
| P3b | `SaParams`、共同 LNS、`EjectionChainPipeline`、`CombinatorialRepair` を子予算へ — **済（後処理の取り分）**（2026-10-24。HF67・HF66・共同 LNS の確保・c1 共同 LNS の按分・c1 玉突き連鎖・個人共同 LNS・玉突きパイプラインの深い段を `BudgetShare` の 4 つの式へ。`CombinatorialRepair` は時間の予算を持たず評価回数だけ＝対象なし。`SaParams` の時間は探索の締切（`TimeBudget`）から渡る＝式は無い） |
合格: 決定的モードで G8。時間制は各段の割当 ms のログが前後で同じ。

### 6.5 P4 PostPipeline
P4a（`Stage` 型とパイプライン）→ P4b（前段: 入口・hf66／hf67）→ P4c（中段: 各 Polish・共同 LNS）→ P4d（修復: `ViolationComponentRepair`・退避）→
P4e（終段: 玉突き、最終番兵の前）。各コミットで、段の順序・中身を変えず、`PostStageRecord` の並びが同じこと。
- **P4b 済**（2026-10-24、出力不変）: 前段（HF80→HF67→HF66）を `runPreStages` に切り出し、`PostChain.timed(stage)` で段の名前を `PostStages` から渡す。T-10 一致。
- **P4c 済**（2026-10-24、出力不変）: 中段（厳密日割当→巡回研磨→曜日・交互→c1 共同 LNS→従来の玉突き→個人回数の共同 LNS）を `runMidStages` に。T-10 一致。
- **P4d・P4e 済**（2026-10-24、出力不変）: 終段（違反起点修復（最終）→玉突きパイプライン→人員過剰の退避）を `runFinalStages` に。`runPostOptimization` は前段・中段・終段の 3 呼び出しと最終検査・ログだけになった。T-10 一致。

### 6.6 P5 ConstraintCatalog
族ごとにコミットを分けます。順は HARD 6 族 → 個人回数（low・high）→ c1・c2 → c3 系 → グループ（c41・c42・c41s・c42s）→ covO → 分布（fair・apt・weekly）。
HARD を先にして、診断と修正提案の食い違いを早く潰します。合格: G1・G3、チェッカーと診断のスナップショット一致。
- **P5a 済**（2026-10-24）: `ConstraintCatalog`（21 族・必須・位置の空間 CELL/COUNT/NEED/DIST、重みは `MirrorKeys.weights` を読むだけ）と T-11（`ConstraintCatalogTest`＝MirrorKeys・チェッカーの記録先との一致）。画面の「違反の場所一覧」（`breakdownLocations`）の族の場合分けを表の空間から引く形へ。残りの消費者（診断・修復・セルのシート）は族ごとに移す。
- **P5b 済**（2026-10-24、出力不変）: HARD 族。`ConstraintCatalog.hardKeys`（必須 6 族）・`hardCellKeys`（セルに記録される必須 5 族）を足し、各所が持っていた同じ組の写しを表から引く形へ（`C1EjectionChainPolish.HARD_FAMILIES`・セルのシートの固定の注意 `pinRegisterHint`・確認一覧の「必須」印）。T-11 で旧の組と一致を確認。
- **P5c 済**（2026-10-24、出力不変）: 回数（low・high・apt・c2）と偏り（fair・weekly）。`countKeys`・`distKeys`・`keysIn` を足し、E7 フィルタの「回数」バケツ・バケツ外の族（`VioBuckets`）と RSI の職員単位の破壊の分岐（`RsiHypothesisOperators`）を表から引く。

### 6.7 P6 ViewModel の分割
`MagiViewModel.kt` を、C# の partial と同じ名前・同じ境界で分けます:
Background・CellSheet・Csv・Diagnostics・Editing・FixSuggestions・Optimize・Persistence・RelaxTrial・Restore・SmartInitial・WishTrial・Ws1。
`MagiMediator`／`MagiArbiter` はそのままです。合格: G6・G12、ViewModel のテスト。
- P6a（2026-10-24、出力不変）: FixSuggestions を `MagiViewModelFixSuggestions.kt` へ（`findFixSuggestions`・`applyFixSuggestion`・`cancelFixSearch`・`undoNotice`・`clearOpNotice` を extension 関数に）。指紋と探索ジョブの状態は本体に残し、拡張から触るメンバ（`pushUndo`・`boardKey`・`undoStack` など 17 個）を private から internal へ。ViewModel はホストでコンパイルされないので `android-sdk` で確認。
- P6b（2026-10-24、出力不変）: RelaxTrial を `MagiViewModelRelaxTrial.kt` へ（試算の開始・取消・照合 6 種・承認フロー 5 種）。試算の状態と、盤面を書き換える確定 `relaxAndApply`・`restoreBeforeRelax` は本体に残す（state/currentSchedule の書き込みは本体だけ）。
- P6c（2026-10-24、出力不変）: WishTrial を `MagiViewModelWishTrial.kt` へ（試算の開始・取消・行の表示・対照・確定の結果行と `ctxMatches`）。state を書き換える確定 `cancelWishAndRebuild` は本体に残す。

### 6.8 P7 文書の正規化
`docs/architecture.md`・`docs/sudo_model.md` を新しい構造に合わせ、取り残し（21 族など）を直します。

### 6.9 P8・P9（完了済み）
- P8 グループ上限 0: グループのレンジ（c41/c41s）の上限 0 は SOFT の目標のまま。全員に「入れない」を付けるときは「グループ単位の回数」で上限 0（個人の上限 0 になり、最適化器は置かない）。`docs/business-logic.md`。
- P9 職員の照合: `Staff.id` で照合済み。CSV は名前で照合し、同姓同名はスキップして報告。`docs/data-models.md`。
詳細は付録 A。

---

## 7. 段ごとに走らせる CI
| 段 | design-lint | v6-engine-check | native-parity | android-sdk | C#（停止明け） |
|---|---|---|---|---|---|
| P0・P1 | ○ | ○ | ○ | ― | MagiEngine.Tests |
| P2・P3・P4 | ○ | ○ | ○ | 設定を渡す画面に触れたら ○ | MagiEngine.Tests |
| P5 | ○ | ○ | ○ | ○ | 両テスト＋WinUI |
| P6 | ○ | ○ | ― | ○ | MagiApp.ViewModels.Tests＋WinUI |
| P7 | ○ | ― | ― | ― | ― |

---

## 8. リスクと対策
| リスク | 対策 |
|---|---|
| 差分がとても大きく、どこで結果が変わったか分からなくなる | P0 の 3 スナップショットを最初に作り、段ごとのコミットで二分探索できるようにする（G11） |
| 統合ブランチを作っている間、ほかの修正を main に入れにくい | 入れた場合は統合ブランチへ取り込み、衝突を解く。版は取り込む直前に 1 人が振る |
| 時間依存の経路は盤面ハッシュで示せない | §3.3 のとおり、評価の一致・割当ログ・最後の統計比較で示す |
| `@Volatile` をやめると実行中のトグルが効かない | 実行中に書き換わる経路は無いことを確認済み（スイッチは実行中は無効）。`EngineConfig` を実行開始時に 1 回読めば出力は変わらない |
| C# の停止で同期が遅れる | §9.1 の一覧で追跡し、停止明けに上から入れる |
| 途中で合格の線を満たさない | 原因を直して測り直す。合格の線は緩めない |

---

## 9. C# 同期と複数セッションの運用

### 9.1 C# の積み残し（2026-10-22）
| # | 中身 | Kotlin の版 |
|---|---|---|
| 1 | 職員 1 人の証明に手動固定、複数シフト連の矛盾の検出 | 3.723.0 |
| 2 | 個人の上限 0 の中立表示（`SettingIssue.ZeroCap`・`FixItems`・文言） | 3.723.1 |
| 3 | 期間を超える c1/c2 行の警告、`cons2InputError` の期間上限 | 3.723.1 |
| 4 | S6 承認の復旧（`RelaxRollback`）、試算中／失敗の理由、全経路で結果行 | 3.723.2 |
| 5 | つくる前の確認の呼び名（`PinSourceWord`） | 3.723.2 |
| 6 | 違反一覧の不変化（`ViolationChecker.cs` の `BuildFamilyMaps`） | 3.723.2 |
| 7 | M-01 相当の穴が C# の原子保存にあるかの確認 | 3.723.2 |
| 8 | C# 見直しの 3 件（タブ離脱でパネルを閉じる・人員不足の文言・S6 で古い試算の「閉じる」） | C# 単独 |
| 9 | P0 の C# 版安全網 | P0 |
| 10 | P1 の C# 版（`SaveFailNotifier.Report`＋`SaveRetryWiringTest`、NativeGate は C# に無ければ対象外） | P1 |
| 11 | P2 の C# 版（`EngineConfig`、P2d で外した・消したフラグ: `RotationDeltaPrescreen`・`RoundPassMemo`・`HardDeltaPrefilter`・`LateOpStopPropagation`・`EjectionPipelineAfterRepair`・`PersonSwapKick`・`FloorCountsUpperZero`・`DeltaChildEvalDefault`・`DefaultCrossFamily`・`RestoreWishLocked` ほか後続） | P2 |

### 9.2 複数セッションでの運用
- 作業は版数を付けずに仮のコミットで作る。取り込む直前に 1 人が最新の main へ載せ直し、版を振り、1 版 1 コミットにまとめる（squash）。
- 古い土台の PR は `check_version.py` が落とす。
- 残件の正は本書 §4.4・§9.1 と `docs/safety_inventory.md`。

---

## 10. フラグの整理（2026-10-22 利用者決定。詳細は `docs/flag_inventory.md`）
| 分類 | 扱い |
|---|---|
| 既定 ON で採用が定着したもの | フラグと設定画面のスイッチを外し、常に ON の経路だけ残す |
| 否決・OFF が確定し、どこからも ON にならないもの | コードの経路ごと消す（過去の「腕は残す」より今回の決定を優先。消した版を history に残す） |
| 「じっくり」や画面から ON になるもの・測定中 | EngineConfig に残す |
注意:
- 「探索の強さ」の「じっくり」（`SearchStrength.THOROUGH`）は `combineExhaustPairs`・`wideC3nBreakDays`・`countChainPolish`・`aptFairSoftTolerance` を ON にするので、否決済みでも消せない。
- `quantitativeRangeEval` は評価器・C++・C# の引数に広がっているので、P2d の最後の別コミットで消す。
- `lnsAdaptive` を常に ON にすると、`PostOptimizationParams()` を素で作るベンチ・テストの出力が変わる（本番は変わらない）。それらを本番と同じ既定へ揃える。

---

## 11. 範囲の外の変更の扱い
- **探索の動きを変えたいとき**: 改修のコミットには入れない。既定 OFF のフラグ（`EngineConfig` のフィールド）として別に入れ、
  `HandleOptimizeBench` で 6 データ×5 seed 以上を対で比べ、必須の悪化 0・weighted が平均で悪化しない場合だけ採る。否決なら記録だけ残す
  （2026-10-18 の必須同数タイブレーク、2026-10-19 の fair② と同じ扱い）。
- **重み・閾値**: HF77。利用者の明示の数値指示＋1 件ずつ。
- **業務仕様・データ形式**: 利用者の決定の後に別の版で。
- **再提案しないもの**: 戦略的振動、nonlinear restart、GLS スイープ、targeted-perturb、big-destroy、softFocusProb、adaptiveEscapeControl、
  portfolioRoleParallelSa、`wideC3nBreakDays` の既定 ON、適応停滞閾値、余り時間の玉突き、E0B、c1MoveARepair、必須同数タイブレーク、
  fair② 交換走査、決定記録 D3〜D10、E5、ws8/ws9、cons3n の dedup。2 層番兵と NativeGate の削除、採用基準の追加、Catalog に比較器や重みを持たせること。

---

## 12. main へ入れる前の最終検証
統合ブランチの先端で、次を順に満たしたら main へ 1 回で入れます。
1. G1〜G13 の CI がすべて緑（Kotlin・C++・画面のコンパイル・C# のテストと WinUI ビルド）。
2. P0 の 3 スナップショットが基準版と一致。
3. G15: `HandleOptimizeBench` で基準版と対で比較し、S3・S4 の合格の線を満たす。
4. G16: `host_parity_bench` の手の数／秒、ホストテスト時間が S5 の線を満たす。実機ログ（利用者から受け取る。同じデータ・同じ予算）で最大ヒープと起動・画面の応答が基準版以下。
5. §2.5 の機能棚卸し F1〜F15 の実機確認（利用者）。
6. §13 の完了の定義を満たす。
1 つでも満たさなければ入れません。

---

## 13. 完了の定義
- P0〜P7 が統合ブランチで揃い、§12 を満たして main に入った。
- `PolishGate` のグローバルな書き込みが 0（`EngineConfig` だけ）、予算の定義が `TimeBudget` 1 か所、後処理が `PostPipeline`、
  UI と診断の違反の位置が `ConstraintCatalog` 経由。
- `MagiViewModel.kt` が C# と同じ境界で分割され、最大ファイルが 1,500 行以下【目安】。
- 棚卸しの「記録あり・テスト無し」が 0。
- C# が同期済み（§9.1 の一覧が空）。
- `docs/invariants.md` の INV が守られ、該当する T が CI で走っている。
- §2.8 の変えない前提が全て保たれ、§2.9 の網羅性チェックが全行「済」。

---

## 14. 作業台帳の状態と本書の変更手順
- 状態は 未調査・調査済み・修正中・検証中・完了・保留 の 6 つ。根拠（コミット・テスト名・CI・計測の CSV）の無い「修正済み」「検証済み」は書かない。
- 過去の 28 項目（B/L/M/U）と repo の旧番号（`docs/changelog.md`）を区別する。現状は `docs/safety_inventory.md` が正。
- 各コミットには、Kotlin・C++・C# の変更有無、実行したテストと実行していないテスト、盤面ハッシュの結果、戻し方を書く。
- 段の範囲・順序・ゲートを変えるときは本書を同じコミットで更新し、`docs/history/` に理由を残す。

### 14.1 コミットごとの確認表（統合ブランチの各コミットに書く）
```
段: P_ / コミットの目的:
触れる INV: INV-__（docs/invariants.md）
意義への影響: 進める S_ / 守る S_（§2.3）
Kotlin / C++ / C# の変更: あり・なし / あり・なし / あり・なし（C# は停止中なら §9.1 に積む）
走らせたテスト: hosttest（件数・結果）、P0 スナップショット（一致・不一致）、native-parity、android-sdk（画面に触れたとき）
走らせていないテストと理由:
host_parity_bench（参考）: 手の数／秒 ____（基準版 ____）
消した機能・フラグ: なし / §10 の決定 ____（版 ____）
戻し方: このコミットを revert
```

---

## 15. 机上検証の記録（2026-10-22、v2 → v3）
v2 の主張をコードと突き合わせた結果です。
| 主張 | 結果 | 対応 |
|---|---|---|
| `normalStallMs` の下限 20,000 ms（`V6FinalPort.kt`） | 正しい（`:259` の関数、`coerceAtLeast(20_000L)`） | 行番号を `:259` に訂正 |
| `V6HotfixPasses.kt:907` の 60 秒固定が予算の分散の一例 | **不正確**。60 秒は決定的モードだけで、通常は残り時間を按分した上限（`:905-908`） | W1 の記述を訂正 |
| `EngineClock` に残予算の概念は無い | **不正確**。`remainingMs` はある。無いのは予算を分け合う仕組み | W1 の記述を訂正、`TimeBudget` は `remainingMs` を使う形に |
| 後処理と玉突きの枠 各 10 秒 | 正しい（`V6FinalPort.kt:21/:23`） | 追記 |
| NativeGate の退化経路（`SaOptimizer.kt:180/194`、`V6FinalPort.kt:1134`） | 正しい | ― |
| チェッカーを直接呼ぶ UI の 5 ファイル | 正しい | ― |
| `checkResultWorse`・`StateFingerprint`・`ScheduleWriteSitesTest`・`host_parity_bench.cpp` の存在 | 正しい（`V6FinalPort.kt:1520`、`StateFingerprint.kt:26`） | 行番号を追記 |
| `PolishGate` の数 | v1 の 36 は誤り。`var` 39（`@Volatile` 36） | v2 で訂正済み |
| 決定的モードは後処理だけ | 正しい（`PostOptimizationParams.deterministic`、`tools/loop/README.md`） | §3.3 に明記 |
| fixture 6 件の存在 | 正しい（`app/src/test/resources/`） | ― |
| `docs/invariants.md` の INV 体系 | 存在する（v2 で対応表を追加） | ― |

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
