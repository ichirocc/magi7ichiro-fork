# 差分計算の C++ 一本化・高速化（仕様と現状の対応）

2026-11-10 ユーザーが他社 AI・開発担当者へそのまま渡せる仕様書として提示した方針を、この repo の基準にする。前半（§1〜§6）が仕様、
後半（付録 A〜D）が現行コードとの対応（実名の関数・JNI の経路・テスト・測定）と残りの段。仕様を変えるときは前半を、
進んだら付録を更新する。

## 1. 目的と方針（5 点）
- 目的は MAGI の最適化処理（勤務表の作成）全体の高速化。移植やコードの一本化そのものは目的にしない。
- 通常時の差分計算は C++ に一本化する。SA・LAHC・ALNS・研磨などは共通の C++ 差分エンジンを使い、探索ごとに差分計算を重ねて実装しない。
- C++ のネイティブ性能・ビット演算・メモリ管理を使い、JNI の呼び出しと Kotlin↔C++ の受け渡しを減らす（反復は C++ の中で完結させる）。
- C++ が使えないとき（ライブラリの読み込み失敗・ABI 不一致・初期化失敗・実行時の不整合・JVM のテスト）は Kotlin の `DeltaEvaluator` に自動で切り替える。
  Kotlin 実装は削除せず、切り替え先と検証のために残す。速度の低下は許すが、制約違反と評価の不整合は許さない。
- 既存の制約・評価・探索・採用判定の仕様は一切変えない。

## 2. Kotlin の役割（残すもの）
最適化全体の制御、問題データと勤務表の管理、正式な全体評価（`UnifiedViolationChecker`＝source of truth）、C++ との整合の確認、
C++ が使えないときの切り替え、JNI の連携。`UnifiedViolationChecker` を C++ へ移したり、差分計算で正式な全体評価を置き換えたりしない。

## 3. 変えてはならないもの
- 評価: `UnifiedViolationChecker`・`Evaluator`・`DeltaEvaluator` と全体評価の一致・`MirrorKeys.weights`・HARD/SOFT の区分・罰点の計算・
  `betterReport` とその比較順（必須件数→weightedScore→合計）。
- 制約: 必要人数・希望・手動固定（`manualPins`）・固定セル・希望ロック・個人の上限/下限ほか全部。不変条件 INV-02（上限 0 を自動で引き上げない）・
  INV-03（手動固定を自動で変えない・外さない）・INV-04（通常の探索・研磨で希望ロックのセルを変えない）・INV-05（採用判定の比較順を保つ）。
- 探索: SA・LAHC・ALNS の動作・パラメータ・回数と順序・採用/棄却の条件・最良の保持・Undo/Redo の意味。高速化のために制約を緩める・
  探索の条件を変える・解を採りやすくすることは禁止。
- 構造: `MagiState` のフィールドとスキーマ、Mediator/`MagiArbiter` のイベント経路、HR decision ledger、`MagiPhase` の役割、reviewMemos のセッション内管理。

## 4. 正しさの保証
- 2 段の確認を保つ: ①C++ の自己整合（チャンクの末尾で差分の点数＝全体評価）②Kotlin の正式な全体評価との照合。不一致なら C++ の経路を閉じて
  Kotlin へ切り替え、理由をログに残す。整合を確かめられない探索状態は引き継がない（既存の再開・再初期化の手順を使う）。
- 既存のランダム差分テスト・パリティテスト・ネイティブの自己整合は削らない・弱めない。

## 5. 性能の判定
差分計算単体ではなく、JNI を含む最適化処理全体の時間で判定する。同じ入力・制約・探索条件・環境で複数回測り、単位時間あたりの探索回数・
最終スコア・制約違反数・メモリを比べる。探索回数が増えても最終の品質や制約の遵守が悪化していないことを確かめる。

## 6. 手順と完了条件
手順: 現行の差分計算と呼び出し経路の調査 → 重複・未使用・JNI 境界のコストの調査 → 共通エンジンの構造の確定 → 既存の評価との一致のテスト →
各探索を共通エンジンへ接続 → Kotlin の独立した差分計算を通常経路から外す → 切り替え（フォールバック）の検証 → 前後の性能測定。分からない所は推測で変えず先に調べる。
完了条件: 通常の最適化で差分計算が C++ に一本化され、Kotlin の独立した差分計算が通常経路で使われない。フォールバックが働く。整合が確かめられている。
制約・評価・採用判定・探索動作が変わらない。全体の性能改善が実測され、品質と制約の遵守が悪化していない。既存の安全確認・テストが残っている。

---

## 付録 A. 現行コードの対応（2026-11-10 調査）

### A.1 C++ の共通差分エンジン（`app/src/main/cpp/magi_native.cpp`）
- `struct SaChunk`: 盤面 `a`、回数 `ssn`(職員×シフト)・`dsn`(日×シフト)・曜日 `wd`、ビット集合 `rowMask`(職員×シフト→日)・`dayShiftMask`(日×シフト→職員)、
  規則ごとの現在値の表 `c1Cur`・`c3Cur[4]`・`d41Cur`/`d42Cur`/`d41sCur`/`d42sCur`・`fairCur`、点数 `score`（必須×`SCORE_HARD_UNIT`＋SOFT）。
- `deltaApply(i,j,nw)`: 旧/新シフトを参照する規則だけ（`ShiftIndex` の `byK`）を測り直す。族ごとの範囲は、c1/c3 族が職員 i の行、c41/c42 族が日 j、
  個人回数・apt・weekly が (i,旧)/(i,新) の 2 つ、fair が (群,旧)/(群,新) の 2 つ、被覆が (旧,j)/(新,j)。
- 取り消し記録（3.727.52）: `beginMove`/`commitMove`/`rollbackMove`。書き換えた表の値とセルを記録し、戻すときは書き戻すだけ（Schulte 1999 の trailing）。
- これを使う探索: `runSaChunk`（SA）・`runLahcChunk`（LAHC）・`runAlnsChunk`（ALNS）・`runPolishChunk`（研磨）。修復の候補の見積もり
  `destroyRepairDayAtN`/`destroyRepairStaffAtN`/`destroyRepairViolationsN`・`hf67HardRepairN` も C++。

### A.2 JNI の経路（`NativeBridge.kt`）と切り替え（`NativeGate`）
- 問題: `nativeCreateProblem`/`nativeDestroyProblem`、採点の方式 `nativeSetSoftWeights`、全体評価 `nativeFullEval`。
- 探索のチャンク（1 回の JNI で反復をまとめて回す）: SA `nativeSaChunk`（冷却 1 段ぶん）、LAHC `nativeLahcChunk`（4000 反復）、
  ALNS `nativeAlnsCreate`/`Chunk`（200 反復）/`Read`/`Destroy`、研磨 `nativePolishCreate`/`Chunk`（400 反復）/`Read`/`Destroy`。
- Kotlin 側に残るもの: 再起動の境界（perturb・hf67）、進捗と途中の最良、キャンセル、2 段目の番兵。
- `NativeGate`: `usable`・`enabled`・`userEnabled`・`disable(理由)`・`chunkConsistent`（1 段目＝チャンクの status）・`parityMismatch`（2 段目＝`Evaluator.fullEval` と Long で照合）。
  閉じると以後の実行は Kotlin のループ（`SaOptimizer`・`AlnsSearch`・`SearchPolish` の従来ループ）へ切り替わる。

### A.3 Kotlin の差分計算がまだ通常経路で動く所（一本化の残り）
| 所 | 何をしている | 備考 |
|---|---|---|
| `MembraneCellLns`（後処理の最終段・膜の細胞 LNS） | 5 名×全期間の細胞を焼きなまし。`DeltaEvaluator.apply` が主 | 後処理で最も時間を使う。乱数は `java.util.Random` |
| `C1JointLnsPolish`・`C1EjectionChainPolish`・`C3RotationPolish`・`ViolationComponentRepair` | 候補の差分評価・比較キー | 後処理 |
| `DeltaScreen`（3.727.52）を使う研磨 5 つ | 候補の比較キーを差分で出し、良くならない候補をチェッカーに回さない | 後処理 |
| `AlnsSearch`・`SearchPolish`・`SaOptimizer` | ネイティブ経路の初期点数とフォールバックのループ | 通常は 1 回の `reset` のみ |

探索本体（SA・LAHC・ALNS・研磨のチャンク）はすでに C++。一本化の残りは Kotlin の後処理の差分計算。

## 付録 B. 済んだこと（いずれも出力不変）
| 版 | 中身 | 測定 |
|---|---|---|
| 3.727.49 | Kotlin の差分評価の c3 族のビット経路の表・fair の作業領域の使い回し | 膜の細胞 LNS 1〜3 割短縮 |
| 3.727.52 | C++ の取り消し記録（採らない手を測り直さない）。研磨の候補を差分で選別（`DeltaScreen`） | 焼きなまし・LAHC 1.2〜2 倍速。個人回数の共同 LNS 8〜14 倍速 |
| 3.727.53 | C++: 単一シフトの連の不足をビット（ctz）で数える `rowDeficitBitsN`、並びの範囲の判定を前計算 `c3SeqOk`、修復の見積もりで fair の「変える前の偏差」を (群,シフト) ごとに 1 回だけ数える・候補に依らない項をループの外へ | 命令数 −8.5%（user） |

## 付録 C. 残りの段（案）
1. **膜の細胞 LNS を C++ のチャンクへ**（最大の残り）。`java.util.Random`（48 ビットの線形合同法・`nextInt(bound)`・`nextDouble`）を C++ に同じ式で写し、
   同じ種で Kotlin と同じ盤面を返すことを盤面ハッシュで確かめる（探索動作不変）。番兵は既存チャンクと同じ 2 段。
2. **差分のハンドルを JNI に**: `DeltaScreen`・`C1EjectionChainPolish`・`C3RotationPolish` の候補評価を、候補をまとめて 1 回の JNI で採点する形にする
   （1 手ごとの JNI は往復の固定費で Kotlin より遅くなり得る＝まとめて渡す）。比較キーは `DeltaEvaluator.reportKey` と同じ 3 つ。
3. `C1JointLnsPolish`・`ViolationComponentRepair` の候補評価を 2 の形へ。
4. 各段で前後の全体時間・反復数・最終スコア・違反数を測る（付録 D）。

## 付録 D. テストと測定（実名）
- C++ の全数検証: `tools/native/formal_bounded.cpp`（差分＝全体評価、取り消し記録で戻した状態＝作り直した状態を表まで照合、CP-SAT・説明器）。
- C++↔Kotlin のパリティ: `tools/native/host_parity_bench.cpp`（CI `native-parity.yml`）、`NativeParityFixtureTest`・`ObjectiveParityTest`・`V6WebGoldenParityTest`。
- 切り替え: `NativeGateFallbackTest`・`NativeGateIntegrationTest`・`V6NativeOptimizerChoiceTest`。
- 差分評価: `DeltaEvaluatorTest`・`C1JointLnsDeltaEvalTest`・`DeltaScreenTest`（差分あり/なしで同じ盤面とログ）・`ReviewOptFixesTest.reportKeyMatchesCheckerOnRealData`。
- 最終盤面の固定: `BoardHashFixtureTest`。全部は `tools/host/hosttest.sh`（CI `v6-engine-check.yml`）。
- 出力不変の確認と時間・命令数: `tools/native/runner_hash.cpp`（探索 4 本を固定の種で回して盤面ハッシュと所要を出す。旧版と新版を同じ入力で比べる。命令数は callgrind）。
