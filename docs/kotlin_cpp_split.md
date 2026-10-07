# Kotlin と C++ の棲み分け

> 最終更新: 2026-10-07（3.627.0 時点のコードで数え直し）。初版は別ブランチ `x8ygvy` の 3.367.0（2026-08-06）。
>
> **ここに書くのは実装済みの事実と実測値だけ。** 構想・提案は `docs/algorithm_portfolio.md` の「未実施の提案」へ。
> この文書が実装からずれると「C++ に無い関数を C++ にあると思って直す」事故が起きる。数字を変えたら数え直す。

---

## 1. 判断規則（新しいコードをどちらに置くか）

問いは 1 つ。**そのコードは `UnifiedViolationChecker` の `ViolationReport`（違反の場所・族・weightedScore）を読むか。**

- **読む** → **Kotlin**（例外なし）。チェッカーが正（source of truth）で、C++ へ複製すると第 2 の意味論ができてドリフトする。
  研磨パス・診断・採否ゲート・UI の違反表示はすべてここ。
- **読まない**、かつ **反復が百万回オーダー**（SA/LAHC/ALNS/研磨チャンクの最内周）→ **C++ の候補**。2 層番兵（§4）を必ず付ける。
- **読まない**が反復は数回〜数千回（ラウンド境界の制御・後処理チェーン）→ **Kotlin**。移しても予算の 1% に届かない（§3）。

「差分評価を速くしたいから C++ へ」は理由にならない。過去の実測（§3）では差は言語ではなくビット化から来た。

---

## 2. いま C++ にあるもの

`app/src/main/cpp/magi_native.cpp` **2,695 行**（C++ ソースはこの 1 ファイルのみ）。Kotlin の `v6` パッケージは **29,108 行**。

### JNI 境界＝`NativeBridge.kt` の `external fun` **17 個**（`ABI_VERSION = 7`）

| 群 | 関数 | 役割 |
|---|---|---|
| 疎通 | `nativeAbiVersion` | Kotlin の `ABI_VERSION` と照合。不一致なら使わない |
| 問題 | `nativeCreateProblem` / `nativeDestroyProblem` | 平坦化した問題を 1 回だけ渡してハンドル化。読み取り専用で全ワーカーが共有 |
| 評価 | `nativeFullEval` | フル評価（hard/soft と族別の内訳）。実行時のパリティ照合に使う |
| SA | `nativeSaChunk` | 冷却ラダー 1 本＝1 チャンク |
| ALNS | `nativeAlnsCreate` / `Chunk` / `Read` / `Destroy` | GLS・適応重み・温度をチャンクをまたいで保持 |
| 研磨 | `nativePolishCreate` / `Chunk` / `Read` / `Destroy` | HF80 相当の研磨オペ |
| LAHC | `nativeLahcCreate` / `Chunk` / `Read` / `Destroy` | 履歴受理＋HARD ガード |

### 内部

- `fullEvalParts` — フル評価。番兵のオラクルなので**スカラーのまま**。族別の内訳スロットは Kotlin `MirrorKeys.all` と同じ順（3.524.0）。
  内訳は照合用の数だけで、違反の場所・族を持つ `ViolationReport` は C++ に無い。
- `SaChunk` — 差分評価の中核。`S <= 64 && T <= 64`（業務前提の 30 名・31 日は常にこちら）で c1 窓・c41/c42 系をビット演算で評価。
  4 つのランナー（SA/LAHC/ALNS/研磨）が同じ `SaChunk` を使う＝差分評価の実装は 1 つ。
- 修復系（destroy-repair・HF67・GLS ペナルティ等）は Evaluator と同じ重みの marginal cost で**候補を作るだけ**で、採否はしない。

---

## 3. Kotlin が持つもの（移さない理由）

- **`UnifiedViolationChecker`**（正）— Kotlin の `check(` 呼び出しは **207 箇所**。研磨の採否・UI の違反表示・直し方の提案・診断がすべて読む。
  戻り値は場所・族ごとのマップ束で、JNI 越しに毎回組み立てる費用が評価そのものより重い。
- **研磨・修復パス** — `V6HotfixPasses*.kt` と独立ファイル約 30 本（`C1JointLnsPolish`・`ViolationComponentRepair`・`WishIslandPolish`・
  `C1EjectionChainPolish`・`CountChainPolish` など）。すべて「候補を作る → チェッカーで評価 → `betterReport` ＋ `exactPinRegression` で採否」。
- **診断**（`ForbiddenRunDiagnosis`・`CoverageDiagnosis`・`C1RepairAnalysis`・`ConstraintMus`・`V6SanityPort`）— 読み取り専用で、実行時間に占める割合は小さい。
- **制御層**（`V6FinalPort` の予算・停滞監視・最終番兵、`V6NativeOptimizer` の適応ポートフォリオ）— ラウンド境界で数回〜数千回しか走らない。

### 境界を支えた実測（いずれも当時の値）

- 差分評価のスループット（3.366.0、golden、x86-64 ホスト、20M 手×3 回の中央値）: C++ スカラー 0.60 / Kotlin `DeltaEvaluator` 0.58 / C++ ビット演算 1.17 M 手/秒。
  ＝言語差は 3%、2 倍の差はビット化。**その後 3.622.0 で Kotlin 側の c1・c3 窓もビット演算にした（9〜22% 高速、出力不変）ので、この表の Kotlin 値は古い。** 再測定はしていない。
- チェッカーのコスト: 実行時間の 5〜7%。
- 移植しなかった箇所（3.153.0）: `V6LateOperators` 約 100ms、後処理チェーン約 1〜1.3 秒＝300 秒予算の約 0.5%（現在の後処理は 5〜9 秒で、多くはチェッカー依存の研磨）。

---

## 4. 壊してはいけない不変条件

1. **2 層番兵** — `NativeGate.disable(` は Kotlin 側に **11 箇所**。
   - C++ 内の自己整合（SA・LAHC・ALNS・研磨のチャンク末尾で `fullEvalParts` と照合、4 箇所）
   - Kotlin 照合（同じ 4 チャンク＋起動時のフル評価で `Evaluator.fullEval` と比較、5 箇所）
   - 状態生成の失敗（ALNS・LAHC、2 箇所）
   どれが発火しても `NativeGate` が閉じ、そのプロセスは Kotlin へ退化する＝**誤った勤務表を出さず、遅くなるだけ**。
2. **言語をまたぐパリティを CI が守る** — `app/src/test/resources/golden_eval_expected.txt`（現在 `hard=0 / soft=8990` と族別の値）を
   Kotlin の `NativeParityFixtureTest` と C++ の harness（`--expect=`）の両側から固定する。重み・族を変えるときは両方を直してからこのファイルを更新する。
3. **共有ハンドルは読み取り専用** — 問題ハンドル 1 本を最大 8 ワーカーで共有する。C++ の問題データに書き込み可能なメンバを足すとこの前提が壊れる。
   破棄は全ワーカーを止めて待ってから（3.289.0）。
4. **ホストでビルドできる状態を保つ** — JNI 部を `#ifndef MAGI_HOST_TEST` で囲み、`tools/native/host_parity_bench.cpp` が同じ `.cpp` を
   g++ だけでビルドする（`.github/workflows/native-parity.yml`）。
5. **範囲外の盤面値は -1** — `normalizeSchedule` の -1 を C++ 側で配列添字に使う前に必ず範囲を確かめる（3.199.0 のヒープ破壊）。

---

## 5. 移植しないと決めた項目（再提案しない）

| 対象 | 決定 | 根拠 |
|---|---|---|
| `UnifiedViolationChecker` | Kotlin のまま | チェッカーが正＝番兵の前提。マップ束の JNI 整列が利得を相殺 |
| `V6LateOperators`・後処理チェーン | 移植しない（3.153.0） | 予算に占める割合が小さく、採否がチェッカー依存 |
| 研磨・修復パス | Kotlin のまま | 採否がチェッカー依存 |
| 診断 | Kotlin のまま | 読み取り専用・割合が小さい |
| `fullEvalParts` のビット化 | しない | 番兵のオラクル。速いほうを正にしない |

## 関連

- `docs/algorithm_portfolio.md`（どの手がどこで走るか）／ `docs/business-logic.md`（重みと判定条件の正）／ `docs/v6_engine_native_port.md`（Web→Kotlin の移植）
