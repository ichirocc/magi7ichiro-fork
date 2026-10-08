# 停滞脱出 仕様書（他社 AI 向け・引き渡し版）

| 項目 | 内容 |
|---|---|
| 対象 | MAGI Native（Kotlin/Android、`ichirocc/magi7ichiro-fork`）と同値の C#（`ichirocc/-MAGI_PC`）。C++ `magi_native.cpp` は層 D の同値移植 |
| 照合した実装 | Android `66efc91`（3.642.0、作業ブランチ `claude/merge-wub4fq`。main の 3.641.0 は `80d664d`）、C# `7cfe73e`（同ブランチ、基準 `53d2335`） |
| 現行の正 | `docs/stall_escape.md`（実装準拠仕様）と、上のコミットのコード。本書と食い違えばそちらが正 |
| 本書の構成 | **DEFAULT（現行）** と **PROPOSAL（提案・未実装・既定 OFF）** を節で分ける。PROPOSAL は §5 だけ |
| 採否の正 | `MirrorCore.betterReport`（単一ソース `reportComparator`）＝ `(hard, weightedScore, total)` の辞書式。小さい方が良い |

**他社 AI への指示**

1. 実装の既定動作は常に DEFAULT。PROPOSAL はフラグ OFF のとき存在しないものとして扱う。
2. 数値・規則を変えるときは、コードと `docs/stall_escape.md` と本書を同じコミットで直す（Kotlin → C++ → C# の順、同日）。
3. §12 の否決表と同型の変更を、測定なしで入れない。
4. 重み・閾値・探索幅は明示の数値指示が無い限り変えない（HF77）。
5. 本書の数値は、各行に書いた定数名・関数名で確かめてから使う。確かめずに引用しない。

---

## 0. 敵対検証の要約（本書の前提）

| 検証の結論 | 本書への反映 |
|---|---|
| 構造床と c3n 局所壁が同じ短い閾値を使うのは、根拠の強さに対して歪みがある | §4.4 に既知のギャップとして明記。§5 の動機 |
| 固定の中間閾値（例 B/4）は短い予算で差が出ず、否決帯に近い | 採用しない。§5 は追加待機 M の因子方式 |
| 「大域ロールが必ず走る」は過大 | 効果の主張を「最大およそ M の追加待機」に限定 |
| 未診断を短縮すると危険 | 未診断・診断 false は normalStall のまま |
| 層 B の量子変更は否決同型の危険 | PROPOSAL 本体から外す |
| ログは制御より先 | §10 に観測を独立の章で置く。3.640.0〜3.642.0 で多くは実装済み |
| keep-best は入力比の下限だけ | 「粘れば得られた改善」は非保証と明記（§1.2 P4） |
| 成功指標 | 後処理後の final（hard / weightedScore / total / 時間）を測定の正とする |
| 評価が同じでも盤面は同じとは限らない（3.642.0 で判明） | 壁の証拠は同じ報告参照で結ぶ（§4.3、K9） |

---

## 1. 目的と原則

### 1.1 目的

探索・研磨が改善しなくなったときに、別の手へ切り替える（層 B〜E）か、探索を終えて後処理へ渡す（層 A）。時間と電池を節約しつつ、採用解が入力より悪化しないことを守る。

### 1.2 原則

| ID | 原則 |
|---|---|
| P1 | 停滞は「別の手を要求する信号」。例外は層 A による探索停止だけ |
| P2 | 採用は常に `betterReport`。探索器が独自の優劣順を持たない |
| P3 | keep-best と入力比番兵により、早期終了しても入力より悪化させない |
| P4 | 「早期終了しなければ得られた改善」との同等性は保証しない（`docs/stall_escape.md` §3.3） |
| P5 | 根拠の強さと停止の強さをそろえる（PROPOSAL の方向） |
| P6 | 探索動学の変更は測定後。HF77 |
| P7 | 実行全体の停止権限は層 A だけ |

---

## 2. 用語

| 用語 | 定義 | 実装 |
|---|---|---|
| hardFloor | 構造的 covU 床。有資格者を全員就けても埋まらない席数。実行中は不変 | `V6SanityPort.structuralHardFloor` |
| nonCovUHard | groupViol + pref + c3n + c3w | `WatchdogBest.bestNonCovUHard` |
| normalStall | 解ける HARD が残る局面の長い停滞閾値 | `V6FinalPort.normalStallMs` |
| shortStall | 頭打ち局面の短い停滞閾値 | `watchdogBudget(...).stallHardMs` |
| c3nWallProven | 現最良の各 c3n run が局所手で動かないという診断。**大域不能の証明ではない** | `V6PortAnalyzer.diagnoseForbiddenRuns`（`allBlocked`）、選択は `V6FinalPort.C3nWallProof` |
| c3nWallPlateau | 内訳が実質 c3n だけ、かつ床条件、かつ c3nWallProven | `V6FinalPort.effectiveStallMs` |
| stagnationFired | 層 A 発火のラッチ。**改善で降りる**（永続ではない。3.346.0） | `WatchdogBest.stagnationFired`、`observe` が降ろす |
| shouldStop | 締切・キャンセル・停滞。停滞分は改善で偽に戻る（非単調） | `V6FinalPort.handleOptimize` のローカル |
| stopIsFinal | 締切またはキャンセルだけ（単調） | 同上 |
| liveBest | 探索が公開する生存盤面と、その報告 | `V6NativeOptimizer.publishLiveBest`（CAS）、`LiveBestSnapshot(report, board)` |
| globalBest / elite | 実行全体の最良／各ワーカーの自己最良 | `runAdaptivePortfolio` |

---

## 3. アーキテクチャ（五層）

```
handleOptimize
  ├─ 層 A: 予算・床・監視・発火（探索停止信号）           V6FinalPort
  └─ 探索
       └─ 層 B: ポートフォリオ（役割再配属）               V6NativeOptimizer.runAdaptivePortfolio / AdaptiveHypothesisEpochPolicy
            ├─ 層 C: RSI（focus / HF63 / N4）              runRsi / RsiFocusSelection / Hf63Infeasibility
            └─ 層 D: SA/LAHC                               SaOptimizer / C++ SaChunk
  探索終了後
  ├─ 統合                                                  EliteIntegrationPolish
  ├─ 層 E: 後処理（パス打ち切り）                           V6HotfixPasses.runPostOptimization 配下
  └─ ExtraRefine（条件付き。停滞発火後は走らない）
```

| 層 | 観測対象 | 停滞時の動作 | 停止範囲 |
|---|---|---|---|
| A | 進捗報告で観測した全体最良 | 探索停止信号 | 探索全体。統合・後処理はその後も走る |
| B | 自己エリート、他ワーカーとの盤面距離 | 役割変更・強度上昇 | 止めない |
| C | ラウンド最良・focus 族の件数・HF63 履歴 | focus の冷却・回避・終了 | その `runRsi` 呼び出し |
| D | 内部スコアの HARD 部分・ラダー境界 | LAHC へ移行・再加熱 | その探索内部 |
| E | パス最良・採用数 | パス／巡を打ち切る | そのパス／巡 |

PORTFOLIO（層 B）は AUTO の 211 s 以上でしか選ばれない。31〜210 s は RSI→ALNS、30 s 以下は V5 だけ。

---

## 4. 層 A — DEFAULT（現行）

### 4.1 時間パラメータ

実装は純関数 `V6FinalPort.watchdogBudget`（表の固定は `StallEscapeSpecTest`）。B = budgetMs、S = startMs、D = hardDeadlineMs。単位 ms、整数除算は切り捨て。

```
minRun       = min(clamp(B/6,  8000, 45000), B)
postReserve  = min(clamp(B/12, 8000, 25000), B/2)
searchEnd    = max(D − postReserve, S + minRun)
searchWindow = searchEnd − S
f            = PolishGate.normalStallFraction（既定 0.9、0 < f < 1）
raw          = max(toLong(B × f), 20000)
normalStall  = raw                                       if raw < searchWindow
             = max(toLong(searchWindow × f), 20000)      otherwise          （V6FinalPort.normalStallMs）
shortStall   = max(B/8, 15000)                                               （stallHardMs）
phaseGrace   = clamp(B/40, 2000, 15000)
k_ov         = V6FinalPort.STALL_OVERRIDE_FACTOR = 2
```

| 量 | B = 300 s | B = 60 s |
|---|---|---|
| minRun | 45 s | 10 s |
| postReserve | 25 s | 8 s |
| normalStall | 270 s | 46.8 s（区間フォールバック） |
| shortStall | 37.5 s | 15 s |
| phaseGrace | 7.5 s | 2 s |

20 s／15 s の下限があるため、短い予算では閾値が探索区間に届かず、締切だけで止まることがある（例 20 s 予算）。

### 4.2 改善監視（`WatchdogBest`）

- 監視判定は `V6FinalPort.progressImproved`。`betterReport` と別契約で、weightedScore にだけ 1e-6 の許容差を置く（3.289.0）。候補採用へは広げない。
- 改善 → 最良値・`lastBestImproveMs`・観測反復数・非 covU 内訳・`bestVersion`・`bestReport` を更新し、停滞ラッチを降ろす（`observe`。`progressLock` 内）。
- フェーズ文字列の変化 → `lastPhaseChangeMs` だけ更新。`lastBestImproveMs` には触れない。

### 4.3 頭打ち述語（`V6FinalPort.effectiveStallMs`）

```
nonCovUHard    = groupViol + pref + c3n + c3w
nonCovUAllC3n  = groupViol == 0 ∧ pref == 0 ∧ c3w ≤ wishC3wProven ∧ c3n > 0
basePlateau    = (bestHard ≤ hardFloor ∧ nonCovUHard == 0) ∨ wishReached
c3nWallPlateau = nonCovUHard > 0 ∧ nonCovUAllC3n ∧ bestHard ≤ hardFloor + nonCovUHard ∧ c3nWallProven
```

`wishC3wProven` は `PolishGate.wishConflictFloorMode == OFF`（既定）なら 0。`wishReached` は既定 OFF では配線されない。

**c3nWallProven（3.642.0 の規則。実装 `V6FinalPort.C3nWallProof`）**

- 診断は `V6PortAnalyzer.diagnoseForbiddenRuns`。現最良の各 c3n run で、単セル変更・短い玉突き・隣接日調整がすべて不成立なら true（約 20 ms）。多人多日の大域入れ替えは試さない。**局所壁であり大域証明ではない。**
- 診断の対象は生存盤面 `V6NativeOptimizer.liveBestSnapshot`。使うのは、その報告が最良の報告 `WatchdogBest.bestReport` と**同じ参照**のときだけ（`c3nWallSameReport`、`C3nWallProof.bound`）。値が同じだけの別の報告は使わない（評価が同じでも盤面は別でありうる）。
- 診断は盤面の内容で鍵をとる（`BoardKeyedFlag`）。結果は必ずその盤面のもの。
- 診断の前後で生存盤面・最良の報告・版のどれかが変わったら、その判定は使わない。
- 対応が取れない間は、同じ `bestVersion` で対応が取れていた判定だけを持ち越す（段の境界で `optimize()` が生存盤面を空にしても失わない）。版が変われば持ち越さない。
- 対応が取れない時間は短縮を使わない＝停止は遅れる側に倒れる（fail-closed）。
- 診断の開始は、内訳条件が成立し `stalled > shortStall` になった後。
- 測定の基準腕 `PolishGate.c3nWallLegacy = true` は 3.641.0 までの判定（版ごとに一度、生存盤面を対応の検査なしに診断。`C3nWallProof.legacy`）。

### 4.4 実効閾値（DEFAULT）

```
effStall = shortStall    if PolishGate.c3nWallShortStall ∧ (basePlateau ∨ c3nWallPlateau)
         = shortStall    if ¬c3nWallShortStall ∧ basePlateau
         = normalStall   otherwise
```

`PolishGate.c3nWallShortStall`（既定 true）を false にすると c3n 壁の短縮だけが外れる（3.641.0 の測定スイッチ）。

**DEFAULT の既知のギャップ（欠陥ではなく仕様の歪み）**

- 構造床（強い根拠）と c3n 局所壁（弱い根拠）が同じ shortStall を使う。
- 診断の開始と発火の閾値が同じ目盛りなので、壁認定の後の追加探索はほぼ 0 になりうる。
- 停止範囲は探索全体なので、未消化の層 B ロールもまとめて終わる。

### 4.5 発火（`V6FinalPort.watchdogStagnationFired` と `WatchdogBest.fireIfGeneration`）

```
stalled = now − lastBestImproveMs
fired   = now − S > minRun
        ∧ stalled > effStall
        ∧ (now − lastPhaseChangeMs > phaseGrace ∨ stalled > k_ov × effStall)
```

- すべて厳密な `>`。ちょうど閾値では発火しない。
- 判定の世代（`bestVersion`）を先に取り、発火は `progressLock` 内で同じ世代のときだけ確定する（3.642.0）。判定と発火の間に改善が届けば発火しない。
- 一つのラッチは一度だけ記録する。記録は時刻・反復数・猶予上書きの有無（`byOverride`）・壁の使用（`stagnationWall`）。後続の判定は上書きしない（3.642.0）。
- ラッチは改善で降りる（`observe`）。

### 4.6 停止の伝達と事後

| 信号 | 意味 | 実装 |
|---|---|---|
| shouldStop | 締切・キャンセル・停滞。非単調になりうる | `handleOptimize` のローカル |
| stopIsFinal | 締切・キャンセルだけ。単調 | 同上 |
| confirmStop | 単調なら即確定。それ以外は最大 `STOP_CONFIRM_MS` = 5 000 ms、`STOP_CONFIRM_POLL_MS` = 250 ms 間隔で再確認し、偽に戻れば続行 | `V6NativeOptimizer.confirmStop` |
| 後期演算 | `V6LateOperators.improve` は入口で `shouldStop` を見る。試行ごとの確認は `PolishGate.lateOpStopPropagation`（既定 true）。false は試行ごとの確認だけを切る（入口は残る） | 3.642.0 |

- 後処理は D まで続けられる（停滞で一括停止しない）。
- ExtraRefine: `extraMs = min(D − postEndMs, postReserve) ≥ 5000` かつ `¬stopRequested ∧ ¬stagnationFired ∧ post.report.total > 0 ∧ ¬structuralHardResidual`。`structuralHardResidual` は `extraRefineRequirePostHardDrop`（引数、既定 false）のときだけ評価する。走らなかった理由は `ExtraRefine` 行に出る。

---

## 5. 層 A — PROPOSAL（未実装・既定 OFF）

**STATUS: PROPOSAL。コードには無い。測定が終わるまで DEFAULT にしない。**

### 5.1 動機

DEFAULT の「c3nWallPlateau → shortStall」を、根拠の弱さに合わせて緩める。

### 5.2 測定済みの両端

この提案は、すでに測った二つの端の間を補間するものである。

| 腕 | 意味 | 測定（3.641.0、sample_v6×5 seed、同一 seed、workers 4） |
|---|---|---|
| M = 0 | DEFAULT（短縮あり） | 基準 |
| M = normalStall − shortStall | 短縮なし（`c3nWallShortStall = false`） | 60 s: 短縮なしが 5 勝 0 敗・−1.6%、時間 1.75 倍。120 s: 2 勝 3 敗・平均 +8.8（損失なし）、時間 2.6 倍。300 s: 4 勝 1 敗・−0.7%、時間 2.35 倍、p≈0.19 |

端の結論は「品質は非有意に良く、時間は 1.7〜2.6 倍」。中間の M に、時間を少し足して品質を得る点があるかは未測定。

### 5.3 制御式（フラグ ON のときだけ）

実験定数（例。固定の真理ではない）: B_min = 120 000 ms、M ∈ {15 000, 30 000, …}、8 000 ≤ M ≤ normalStall − shortStall。

```
effStall = shortStall          if basePlateau（wishReached を含む。既定 OFF）
         = shortStall + M      if c3nWallPlateau ∧ c3nWallProven ∧ B ≥ B_min
         = normalStall         otherwise
```

otherwise に必ず含めるもの: 未診断、診断 false、B < B_min、壁の不成立。フラグ OFF は §4.4 とビット互換。フラグは `PolishGate` に置く（仮称 `c3nWallExtraWaitMs`、0 = OFF）。`PolishGate.snapshot`／`restore` に載せる。

### 5.4 書いてはいけない効果

- 「LARGE_DESTROY が必ず 1 回以上走る」などの保証。
- 許される表現: 「壁の成立後、最大およそ M ms の追加の無改善待機が入りうる」。

### 5.5 測定要件（PROPOSAL の出荷条件）

| 項目 | 内容 |
|---|---|
| 道具 | `tools/loop/HandleOptimizeBench.kt`（`MAGI_HO_FEATURE` の腕、同一 seed）、集計は `tools/loop/gate.py` |
| 比較 | 同一入力・seed・予算。腕: DEFAULT 対 M の数水準 |
| 正の指標 | 後処理後 final の hard / weightedScore / total と壁時計 |
| 母集団 | `Watchdog` 行で c3n 壁が効いた run を主解析。その他は回帰監視 |
| 帯 | 60 s・120 s（RSI→ALNS）・300 s（PORTFOLIO）。各 5 seed 以上 |
| 揺れ | 同一 seed・同一腕を反復して揺れを先に測る。workers 4 は壁時計に依存し seed を固定しても軌跡が変わる（2026-10-08 の診断: weighted ±100〜325、停止時刻 ±215 s）。腕の差が揺れを超えないなら未決とし、既定を動かさない || 勝ち | 壁母集団で final が改善し、全体に有害な回帰がない。事前に決めた基準で判定する |
| 引き分け・負け | DEFAULT 維持。§12 に 1 行 |

---

## 6. 層 B — 再配属（DEFAULT）

実装 `AdaptiveHypothesisEpochPolicy`（テスト `HypothesisEpochPolicyTest`）。層 B は実行を止めない。

### 6.1 スロット

| スロット | 役割方針 |
|---|---|
| 0 | BASELINE_REFINE。再配属しない（`shouldReassign` が常に false） |
| 4 | 初回の再配属で ELITE_RELINK に固定。`reassignments` は増え続け強度に効く |
| 他 | 脱出役割を巡回 |

巡回列: `DAY_BLOCK_ALNS → HARD_FAMILY_RSI → HARD_DEBT_RSI_PLUS → LARGE_DESTROY_ALNS → PERSONAL_RSI → MAX_DISTANCE_RSI_PLUS`。`PolishGate.personSwapKick`（既定 true）なら末尾に `PERSON_SWAP_ILS`。8 ワーカーなら開始時から 6 本が脱出役。脱出役の時間比率が高いのは初期配置の帰結で、再配属過多ではない。

### 6.2 量子（`AdaptiveHypothesisEpochPolicy` の定数）

| 系統 | 基礎 | 改善直後 |
|---|---|---|
| 通常 | `BASE_QUANTUM_SEC` = 5 | `IMPROVING_QUANTUM_SEC` = 8 |
| RSI+ | `RSI_PLUS_BASE_QUANTUM_SEC` = 35 | `RSI_PLUS_IMPROVING_QUANTUM_SEC` = 45 |

役割が変わった直後は改善直後の長い量子を引き継がない（`carriesImprovingQuantum(improvedThisEpoch, roleChanged)`）。役割間の秒の偏りは意図的（是正しない）。

### 6.3 改善と再配属（`shouldReassign`）

```
improvedThisEpoch = betterReport(eliteAfter, eliteBefore)     // 自己エリート同士。摂動入口に勝っただけでは改善でない
stagnantEpochs    = improvedThisEpoch ? 0 : previous + 1
reassign          = slot ≠ 0 ∧ (nearestOtherDistance ≤ DUPLICATE_DISTANCE_CELLS(2) ∨ (¬improvedThisEpoch ∧ stagnantEpochs ≥ 1))
intensity         = base(role) + min(max(reassignments, 0) / 2, 3)
```

- 距離は trajectory 間のセル差（安価な近似）。改善したエポックにも距離条件は適用される。
- 基礎強度: BASELINE 0／ELITE_RELINK・DAY_BLOCK・HARD_FAMILY・PERSONAL・PERSON_SWAP 1／HARD_DEBT 2／LARGE_DESTROY・MAX_DISTANCE 3。
- 「無改善 2 エポックまで遅らせる」は測定で否決済み（§12）。

### 6.4 理由ラベル（観測用）

| ラベル | 条件 |
|---|---|
| DISTANCE | 距離だけ |
| STAGNATION | 無改善だけ |
| BOTH | 両方 |
| NONE | 再配属なし |

ログ `AdaptivePortfolio` 行には再配属回数と合計反復が出る。理由別の集計は未実装（§10）。

### 6.5 停止との境界

開始前に停止・締切を確認し、戻った成果を回収してから締切で break する（3.600.0。回収前に break すると締切間際の改善を捨てる）。探索全体の停止は層 A だけ。

---

## 7. 層 C — RSI（DEFAULT）

- focus（`RsiFocusSelection.maxViolatedFamily`）: 回避されず件数が正の HARD を `groupViol, covU, pref, c3n, c3w` の**順序**で選ぶ（HARD 間の件数最大ではない）。残る HARD が無ければ apt／covO の周期枠（`rotationRound % 3`）、それ以外は順序表 `groupViol,covU,pref,c3n,c3w,low,high,c41,c41s,c2,covO,c42,c42s,apt,weekly,fair,c1,c3,c3m,c3mn` で回避されない正の最大件数。件数 0 は選ばない。候補なしは `total`。
- 冷却（E9）: 不採用で、focus が `total` 以外、候補の focus 件数が入口以上なら、その focus を次の 1 ラウンドだけ冷却（`cooldownFocus`）。件数を減らしたが総合で負けた場合は冷却しない。
- HF63（`Hf63Infeasibility.updateFromBreakdownFocused`）: 追跡するのは `KEY_TO_INDEX` の 13 族（`c1 c2 c3 c3n c3m c3mn c41 c42 covU covO pref low high`）。実際に focus した族に `effortIters`（`HypothesisPlanning.rsiHf63EffortIters`）を積み、`INFEAS_STALL_ITERS` = 5000 以上で充足困難と推定。改善または 0 到達で解除。未追跡の族（groupViol・c3w・c41s・c42s・apt・weekly・fair）は回避に入らない。
- 回避集合（`RsiFocusSelection.avoidSets`）: `dynamicAvoid = hf63.infeasibleBreakdownKeys()`（内部キー）、`avoid = dynamicAvoid ∩ HARD`（covU 床が正なら covU を追加）、`focusAvoid = avoid + cooldownFocus`。SOFT を恒久回避へ入れない。
- N4（`runRsi` 内）: `stagnantRounds ≥ 2 ∧ dynamicAvoid ≠ ∅` のとき `pivot = maxViolatedFamily(bestReport, avoid)` を取り、`pivot == total ∨ breakdown[pivot] == 0` なら**その runRsi だけ**終了。静的 covU 床は N4 の武装判定に混ぜない。
- HF63 の履歴は残存分析の注記に**内部キー**で出す（3.642.0）。族の分類には使わない。

---

## 8. 層 D — SA/LAHC（DEFAULT）

- `SaOptimizer`（C++ `SaChunk` と同値）。`softPolish = true` のときだけ、HARD 部分が `hardStallMs` = 2 500 ms 改善しなければ PhaseB（LAHC、`lahcLen` = 200）へ一方向に移る。`softPolish = false`（既定）では移らない。
- 2 500 ms の無改善は HARD 下限の証明ではない。
- ラダー境界: `MagiConductor` が無ければ reset-to-best 再加熱。あれば STRONG_PERTURB／SCALE_TEMP／REHEAT／NOOP を報酬で選ぶ。
- 実行全体の停止ではない。

---

## 9. 層 E — 後処理（DEFAULT）

| 対象 | 停滞の目安 | 動作 | 実装 |
|---|---|---|---|
| HF80 PostPolish | best が枠の 1/5（下限 3 s）無改善 | 早期 return | `V6NativeOptimizer.kt` の後処理研磨の入口: `stallMs = max(3000L, seconds × 1000L / 5)` |
| C1 共同 LNS | 最良が `Config.patienceMs`（既定 4 000）更新なし、または評価数上限 | 打ち切り | `C1JointLnsPolish` |
| C1 広域ビーム | 最良更新の停滞 | 打ち切り | 3.340.0 |
| 巡回クラスタ | 1 巡で採用 0（`roundApplied == 0`） | 巡を終える | `runPostOptimization` |
| チェーン全体 | `totalApplied == 0` | 記録。巻き戻したパスの採用も既定では数える（`PolishGate.postChainRollbackCountsZero = false`） | `PostChain` |

全パス keep-best。絶対締切 D を共有し、各パスは自前の予算を持つ。前段の余りは後段が使える。

---

## 10. 観測（挙動不変。実装済みと未実装を分ける）

**実装済み**

| 行 | 層 | 読めること |
|---|---|---|
| `Watchdog` | A | 実効閾値の種別（通常=長／plateau=短／c3n壁=短／希望衝突の床=短）、停滞秒、発火の有無、未発火の理由、c3n 壁の確認回数と「生存盤面の更新のうち最良の報告と対応しない」回数（3.642.0） |
| `EarlyStop` | A | 発火種別＝通常／猶予上書き（3.640.0）、発火時に壁を使ったか（`stagnationWall`、3.642.0） |
| `AdaptivePortfolio` | B | 再配属回数・合計反復 |
| `RunMAGI_RSI` | C | 改善したラウンドと最終ラウンド、末尾「戦略変更」1 行に focus の遷移、N4 の早期終了 |
| `HF80`／`ExtraRefine` | E・追加精製 | 停滞早期終了の有無、走らなかった理由（`stagnated` を含む） |
| `設定の効き`（`TuningTelemetry.summary`） | 横断 | トグルがその実行で何をしたか |

**未実装（入れるなら挙動不変で）**

- `Watchdog`: `plateauKind` の機械可読な列挙、`c3nDiag: not_run | false | true`（ms つき）、発火時の探索残り ms。
- `AdaptivePortfolio`: 再配属の理由別カウント（DISTANCE／STAGNATION／BOTH）、globalBest の経過 ms、強度上限到達の回数。

---

## 11. 不変条件

| ID | 内容 | 固定するテスト |
|---|---|---|
| K1 | 採用は `betterReport` だけ | `V6FinalPortTest` ほか |
| K2 | 入力より悪い解を採用しない | 各番兵のテスト |
| K3 | `wishLocked`／上限 0（`mayPlace`）／手動固定の保護 | `FixApplyGate`・候補生成のテスト |
| K4 | 実行全体の停止は層 A だけ | 設計 |
| K5 | c3nWallProven を大域証明と呼ばない・扱わない | 文書 |
| K6 | PROPOSAL を測定なしで DEFAULT にしない | 運用 |
| K7 | 否決表と同型を測定なしで復活させない | 運用 |
| K8 | Kotlin／C# は同日同期。文書は同コミット | 運用 |
| K9 | 壁の証拠は生存盤面の報告が最良の報告と同じ参照のときだけ使う。値の一致では使わない | `StallEscapeSpecTest`（`c3nWallBindsOnlyToTheSameReportObject` ほか 5 件） |
| K10 | 停滞ラッチは改善で降り、確定した記録は一度だけ書く | `StallEscapeSpecTest`（`repeatedDecisionDoesNotOverwriteALatchedFire`） |

C# 側は `MagiEngine.Tests/V6/StallEscapeSpecTest.cs`・`V6FinalPortWatchdogTest.cs` が同じ内容を固定する。

---

## 12. 否決・禁止（再提案は新規測定つきで）

| 案 | 状態 | 根拠 |
|---|---|---|
| `normalStallFraction` 0.5／全体 60〜90 s 固定 | 否決・据え置き | 3.423.0・3.447.0、非有意 |
| 早期終了の廃止 | 維持（廃止しない） | 外すと weighted 中央 −3.5%（非有意）で時間 2.3 倍（3.341.1） |
| 余り探索の soft 研磨 | 否決 | 3.341.1 |
| 無改善再配属を 2 エポックに | 否決 | 実データで +291 悪化、p≈0.19 |
| 残差ベース 4 段脱出／ロール内並列 SA | 撤去 | 3.409.21 中立 |
| `stallEscalation`（幅 2 倍） | 撤去 | 3.511.1 全件無変化 |
| 役割量子の均等化 | 是正しない | 設計 |
| 旧 `stallMs = B/6` | 禁止 | HARD=1 を 50 s で諦め残り 250 s を捨てた |
| `mid = max(short, B/4)` の固定採用 | 却下 | 敵対検証（短予算で退化） |
| 3.642.0 の証拠の対応（同じ参照）と試行ごとの停止確認を戻す | 否決（既定を維持） | 300 s: weighted 差の平均 −49（既定が良い 3/5）、total −11（4/5）。120 s: weighted −65（4/5）、total −4（3/5）。hard は全 run で 1。事前基準「既定が 5 seed 中 3 以上で悪化なら戻す」を満たさない（2026-10-08）。再実行の診断: 悪化した 4 ペアは再現せず（同一 seed・同一腕で weighted 最大 325 の揺れ、8 run とも同じ規則で停止）。腕の差は揺れの範囲＝優劣は未決。既定は正しさ（競合の修正）の理由で維持 |

---

## 13. 実装チェックリスト（他社 AI 用）

**DEFAULT**

- [ ] `effectiveStallMs` が basePlateau／c3nWallPlateau で short、それ以外は normal。`c3nWallShortStall = false` で壁の短縮だけが外れる
- [ ] 発火式が minRun・stalled・phaseGrace／×2 の三条件、すべて厳密不等号
- [ ] 発火は判定時の `bestVersion` と一致するときだけ、`progressLock` 内で確定する。記録は一度だけ
- [ ] 壁の証拠は同じ報告参照のときだけ。診断は盤面の内容で鍵。前後で変われば使わない。同じ版だけ持ち越す
- [ ] c3n 診断が局所手だけであることのコメントと文書
- [ ] 層 B: slot 0 非再配属、距離 ≤ 2、stagnantEpochs ≥ 1、強度は `min(reassignments/2, 3)` 加算
- [ ] 層 B: 改善は自己エリート同士の `betterReport`
- [ ] 層 C: N4 は `runRsi` ローカルだけ。HF63 の記録は内部キー
- [ ] 層 D: PhaseB への移行は `softPolish = true` のときだけ
- [ ] 停滞発火後は ExtraRefine を走らせない
- [ ] 後期演算は入口で停止を見る。試行ごとの確認は `lateOpStopPropagation`

**PROPOSAL（フラグ ON のときだけ）**

- [ ] c3nWall ∧ proven ∧ B ≥ B_min で short + M
- [ ] 未診断／false／小予算は normal
- [ ] base は short のまま
- [ ] フラグ OFF で DEFAULT とビット一致するテスト

**測定**

- [ ] 後処理後 final を正とする。同一 seed。腕ごとに時間も記録
- [ ] 壁の母集団を分離する
- [ ] 基準（何 seed 中いくつで戻すか）を測る前に決める

---

## 14. 一行定義

停滞脱出とは、五層で「改善が止まった後に手を変えるか、探索をやめるか」を決める仕組みである。DEFAULT では構造床と c3n 局所壁の両方で短い停止閾値を使い、後者は根拠が弱いという既知の歪みがある。3.642.0 から壁の証拠は生存盤面と最良の報告の同じ参照で結び、停滞の記録は一度だけ書く。PROPOSAL は後者に限り追加待機 M を測定用に足す（未実装・既定 OFF・未診断は短縮しない）。層 B は止めずに再配属だけを行う。

---

本書は、現行動作・既知のギャップ・提案・否決・観測・不変条件を一つにそろえたものである。コードと矛盾する場合は、DEFAULT についてコードと `docs/stall_escape.md` を優先し、PROPOSAL は未実装・OFF として扱う。測定の生データは `tools/loop/results/`（gitignore 済み）、集計と判断は `docs/history/3.4xx.md` の 3.641.0・3.642.0 の節。
