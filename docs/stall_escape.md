# MAGI 停滞検知・脱出・終了制御 — 実装準拠仕様

## 1. この文書の位置づけ

- 対象: `ichirocc/magi7ichiro-fork` の Android/Kotlin エンジン（C++ `magi_native.cpp` と C# `ichirocc/-MAGI_PC` は同値の移植。C# は `V6FinalPort.Watchdog.cs`・`V6FinalPort.HandleOptimize.cs`）。
- 照合した実装: `80d664d`（3.641.0）を基準に、3.642.0・3.643.0 の変更（作業ブランチ `claude/merge-wub4fq`。3.643.0 は `8980004` 以降、コミットは `docs/history/3.4xx.md` の 3.643.0 の節）を反映した。条件式・呼出関係・状態更新・関連テストを静的に照合した。性能は §11 の測定だけに基づく。
  読む時点で main が進んでいれば `git diff 0fc4f93..HEAD -- app/src/main/java/com/magi/app/v6/ app/src/main/java/com/magi/app/ui/StopExplanation.kt`（`0fc4f93`＝この文書が反映した最後のエンジン変更。エンジンを変えたコミットでこの SHA も直す）で §14 の根拠ファイルに差分が無いことを先に確かめる。
- 読み手: 他社 AI（実装・移植・レビュー）。会話履歴なしで、この文書とリポジトリだけから検証・実装・判断できることを目指す。人のレビューにも同じ条件が効く。
- 目的: 現行実装の記述を本体とする。提案は §5.8 に状態ラベル（未実装／実装済み既定 OFF）を付けて分け、本文と混ぜない。閾値や重みをここで変えない（HF77）。
- 「優秀」の判定基準（2026-10-08 のブリーフィングで決定）: ①照合＝全ての主張と数値を、固定したコミットの関数名・定数名・テスト名で確かめられる。②実装＝停滞脱出の制御（層 A の全部と、層 B〜E の停滞に関わる判断・定数・順序）を DEFAULT としてこの文書だけから再実装できる（§13.2 のチェックリストで検証）。ロール内部の演算子（§6 の入口盤面と探索パラメータ、§7 の focus 別オペレータ、§8 の温度ラダー、§9 のパスの中身）は範囲外＝`docs/algorithm_portfolio.md` とコード。③判断＝提案は DEFAULT と分け、採否の規則（§13 の 4、事前基準・揺れを先に測る・対 8 以上・95% 区間）を書く。④安全＝不変条件（§13.1）が全てテスト名で固定されている。⑤更新＝変更手順と変更履歴があり、コードと同じコミットで直す。加えて、会話履歴を持たない別のエージェントが文書とコードだけを照合して矛盾を見つけない（矛盾 0 で合格）。
- 他社 AI への指示: (1) 実装の既定動作は常に DEFAULT。§5.8 の提案はフラグ OFF では存在しないものとして扱う。(2) 数値・規則を変えるときはコード・テスト・この文書を同じコミットで直す。(3) §11 の否決表と同型の変更を測定なしで入れない。(4) 重み・閾値・探索幅は明示の数値指示が無い限り変えない。(5) 本書の数値は定数名・関数名で確かめてから使う。
- 読解規則: コメントや過去資料の設計意図と条件式が違うときは**条件式を優先する**。「保証」「推定」「未検証」を区別する。
- 数値・規則を変えたら、この文書を**同じコミットで**直す（Kotlin → C++ → C# の順、同日）。経緯の本文は `docs/history/`、採否の台帳は `docs/algorithm_portfolio.md`。

## 2. 最初に理解すること

**一行定義**: 停滞脱出とは、五層で「改善が止まった後に手を変えるか、探索をやめるか」を決める仕組みである。DEFAULT では構造床と c3n 局所壁の両方で短い停止閾値を使い、後者は根拠が弱いという既知の歪みがある（§5.7）。3.642.0 から壁の証拠は生存盤面と最良の報告の同じ参照で結び、停滞の記録は一度だけ書く。3.643.0 は壁を証明相当と経験的に分ける段階化を既定 OFF で持つ（§5.8）。層 B は止めずに再配属だけを行う。

停滞脱出は単一のアルゴリズムではない。観測対象と寿命が違う 5 つの層が、入れ子で動く。

| 層 | 実装 | 観測対象 | 停滞時の操作 | 止まる範囲 |
|---|---|---|---|---|
| A 実行全体の監視 | `V6FinalPort.handleOptimize` | 進捗報告で観測した最良 | 探索停止信号 | **探索だけ**。統合・後処理はその後も走る |
| B 適応ポートフォリオ | `V6NativeOptimizer.runAdaptivePortfolio`・`AdaptiveHypothesisEpochPolicy` | ワーカーの自己エリート、他ワーカーとの盤面距離 | 再配属・強度変更 | 止めない（停止確認または締切まで続く） |
| C RSI | `runRsi`・`RsiFocusSelection`・`Hf63Infeasibility` | ラウンド最良・focus 族の件数・HF63 履歴 | focus の冷却・回避・切替 | その `runRsi` 呼び出し |
| D SA/LAHC | `SaOptimizer`・C++ `SaChunk` | 内部スコアの HARD 部分・ラダー境界 | LAHC へ移行・再加熱 | その探索内部のフェーズ |
| E 後処理 | `V6HotfixPasses.runPostOptimization` 配下 | パス最良・採用数・個別予算 | パス・巡を打ち切る | そのパス／巡 |

「A→B→C→D→E を順に試す 5 段階」ではない。B の中で C と D が動き、A は外から停止条件を供給する。探索方式によっては走らない層がある（AUTO の計画では PORTFOLIO は予算 211 s 以上でしか選ばれない。設定画面の「方式ミックス」で `V6Algorithm.PORTFOLIO` を明示選択したときは任意の予算で走る）。

## 3. 用語と契約

### 3.1 用語

| 用語 | 意味 |
|---|---|
| report | `ViolationReport`。`hard`・`weightedScore`・`total`・族別 `breakdown` |
| HARD | `MirrorKeys.hard` = `groupViol`（群外）, `c3n`（禁止連続）, `covU`（人員不足）, `pref`（希望未充足）, `c3w`（希望前日の禁止） |
| SOFT | それ以外の 15 族。HARD が下限でも改善余地が残る |
| globalBest / elite / trajectory | ポートフォリオ全体の最良／ワーカーごとの保持最良／探索軌跡側の盤面（elite と同じとは限らない） |
| focus | RSI が次に狙う族。`total` は「全違反セル起点の汎用修復」 |
| epoch / round | ワーカーの役割実行単位／RSI 内部の探索単位 |
| keep-best | 保持済み最良を悪化候補で置き換えない。比較は §3.2 |
| hardFloor | 構造的 covU 床。有資格者を全員就けても埋まらない席数。実行中は不変。`V6SanityPort.structuralHardFloor` |
| nonCovUHard | groupViol + pref + c3n + c3w。`WatchdogBest.bestNonCovUHard` |
| normalStall / shortStall | 解ける HARD が残る局面の長い閾値／頭打ち局面の短い閾値。`V6FinalPort.normalStallMs`／`watchdogBudget(...).stallHardMs` |
| c3nWallProven | 現最良の各 c3n run が局所手で動かないという診断。**大域不能の証明ではない**。`V6PortAnalyzer.diagnoseForbiddenRuns`（`allBlocked`）、証拠の選択は `V6FinalPort.C3nWallProof` |
| stagnationFired | 層 A 発火のラッチ。**改善で降りる**（永続ではない）。`WatchdogBest.stagnationFired`、`observe` が降ろす |
| shouldStop / stopIsFinal | 締切・キャンセル・停滞（非単調）／締切・キャンセルだけ（単調）。`handleOptimize` のローカル |
| liveBest | 探索が公開する生存盤面とその報告。`V6NativeOptimizer.publishLiveBest`（CAS）、`LiveBestSnapshot(report, board)` |

### 3.2 候補採用と停滞時計は別契約

候補の正式比較は `betterReport`（`MirrorCore.kt` のトップレベル関数。`object MirrorCore` は無い。単一ソースは同じファイルの `reportComparator`）。小さい方が良い、厳密な辞書式。

```
key(report)        = (hard, weightedScore, total)
betterReport(a, b) = key(a) <lex key(b)
```

層 A の**進捗監視だけ**は、数値揺れで時計が戻り続けないよう weightedScore に 1e-6 の許容差を置く（`V6FinalPort.kt` の `progressWatch`、3.289.0）。

```
improved = h < bh
        ∨ (h == bh ∧ w < bw − 1e-6)
        ∨ (h == bh ∧ w ≤ bw + 1e-6 ∧ t < bt)
```

weightedScore は実際には整数値（`MirrorKeys.weights` は全て整数、apt/fair/weekly の偏差も整数に丸める）なので、1e-6 は浮動小数の合算誤差だけを吸収し、実在の改善・悪化（最小差 2）を取りこぼすことはない。
この監視判定を `betterReport` と同一に書き換えない。逆にこの許容差を候補採用へ広げない。SA 内部は評価器のパックされた Long スコア（`hard×1e9 + soft`）で判定するので、全ての内部移動が上の 3 キーで判定されるわけでもない。

採用には比較以外の保護条件がある: 拡張希望の禁止 `keepsExtBan`、希望固定の規則 A `keepsWishPins`、上限 0 の `mayPlace`。後処理の apt/fair 研磨だけは既定 OFF の例外 `aptFairSoftTolerance`（他 SOFT +6% 容認）を持つ。

### 3.3 保証の範囲

- keep-best と各番兵（`runAlns`／`runV5`／後処理の入力比番兵）が守るのは**保持済み最良の非悪化**。早期終了しなかった場合に得られたかもしれない改善との同等性は保証しない＝早期終了は品質と時間・電池の交換。
- HARD 下限への到達は SOFT の最適性ではない。「品質への影響なし」という過去の表現はこの意味で読む。

## 4. 「改善不能」の根拠の強さ

| 根拠 | 実際に分かること | 分からないこと |
|---|---|---|
| `V6SanityPort.structuralHardFloor` | 有資格者を全員就けても埋まらない席＝構造上避けられない covU の下限。最適化中に不変 | 他 HARD・SOFT の最適性 |
| `V6PortAnalyzer.diagnoseForbiddenRuns`（`allBlocked`） | 現在盤面の各 run で、単セル変更・玉突き・隣接日調整の不成立。診断文が「複数日にまたがる 2 人の入れ替えは試していない」と明記 | 全勤務表空間での不能 |
| HF63 | focus した族に概算投入量を積んでも履歴最小を更新できない | 構造的不可能性、未追跡族の状態 |
| `V6PortAnalyzer.c3nWallRefutedByOneMove`（3.643.0、`c3nWallDeepCheck` ON のとき） | `FixSuggester` の 1 手（上限 2 s）に必須を厳密に減らす手が無い | 不能の証明ではない。局所手の全滅より強い証拠 |
| 一定時間無改善 | 観測期間に改善がなかった | 将来も改善しないこと |

識別子 `c3nWallProven` は残っているが、意味は「限定した手の壁判定」。希望固定だけで説明できる個別ケース（`wishConflictC3wCount`）と一般の `allBlocked` を一括して数学的証明と呼ばない。

## 5. 層 A — 実行全体の停止監視

実装: `V6FinalPort.handleOptimize`、純関数 `watchdogBudget`（§5.1 の表）／`normalStallMs`／`effectiveStallMs`／`watchdogStagnationFired`／`wishFloorReached`／`progressImproved`（§3.2）（`StallEscapeSpecTest`・`V6FinalPortTest`・`WishConflictFloorTest`）。

### 5.1 時間の切り方

単調時計のミリ秒（`EngineClock.nowMs`）。`B=budgetMs`, `S=startMs`, `D=hardDeadlineMs`。整数除算は切り捨て。

```
minRun       = min(clamp(B/6, 8000, 45000), B)
postReserve  = min(clamp(B/12, 8000, 25000), B/2)
searchEnd    = max(D − postReserve, S + minRun)
searchWindow = searchEnd − S
raw          = max(toLong(B × fraction), 20000)
normalStall  = raw                                        if raw < searchWindow
             = max(toLong(searchWindow × fraction), 20000) otherwise
shortStall   = max(B/8, 15000)
phaseGrace   = clamp(B/40, 2000, 15000)
fraction     = PolishGate.normalStallFraction（既定 0.9、有限かつ 0 < f < 1、違反は require で落とす）
```

| 予算 | 探索区間 | minRun | normalStall | shortStall | phaseGrace | postReserve |
|---:|---:|---:|---:|---:|---:|---:|
| 300 s | 275 s | 45 s | 270 s | 37.5 s | 7.5 s | 25 s |
| 60 s | 52 s | 10 s | 46.8 s（フォールバック） | 15 s | 2 s | 8 s |

20 s／15 s の下限があるため、予算が短いと閾値が探索区間内に届かないことがある（例: 20 s 予算は区間 12 s に対し通常 20 s・短い閾値 15 s＝停滞発火は起きず締切だけで止まる）。「フォールバックすれば必ず発火し得る」とは読まない。`StallEscapeSpecTest` がこの表を固定する。

### 5.2 改善報告で更新する状態

§3.2 の監視判定が真になると、最良値・`lastBestImproveMs`・観測反復数・非 covU 内訳・世代番号を更新し、同時に

```
stagnationFired = false; stagnationDurationMs = −1; stagnationIters = −1
```

**`stagnationFired` は永続ラッチではない**（3.346.0 で是正。実装は `V6FinalPort.WatchdogBest.observe`／`fire`）。フェーズ文字列の変化は別時計 `lastPhaseChangeMs` だけを更新し、`lastBestImproveMs` には触れない。

3.642.0: `fire` は同じラッチで一度だけ記録する（後続の判定が時刻・反復数・壁の記録を上書きしない）。確定時の壁の判定を `stagnationWall` に残し、ログの「c3n壁=短…」と EarlyStop 行はこの値を読む（判定の最後の値は読まない）。発火は `fireIfGeneration` で、判定時の `bestVersion` と一致したときだけ `progressLock` 内で確定する。

### 5.3 通常閾値と短い閾値の選択

```
nonCovUHard    = groupViol + pref + c3n + c3w
nonCovUAllC3n  = groupViol == 0 ∧ pref == 0 ∧ c3w ≤ wishC3wProven ∧ c3n > 0
basePlateau    = (bestHard ≤ hardFloor ∧ nonCovUHard == 0) ∨ wishReached
c3nWallPlateau = nonCovUHard > 0 ∧ nonCovUAllC3n ∧ bestHard ≤ hardFloor + nonCovUHard ∧ c3nWallProven
effStall       = (basePlateau ∨ c3nWallPlateau) ? shortStall : normalStall
```

- `hardFloor` は §4 の構造的 covU 床（1 回だけ算出）。「非 covU HARD = 0」を併せるのは、群外の過配置が covU を床より見かけ上へこませても、解ける groupViol が残る間は長い閾値で粘るため。
- `wishC3wProven` は `wishConflictFloorMode == OFF`（既定）なら 0。ON のときは希望衝突由来の c3w 件数なので、`nonCovUAllC3n` は文字通り「c3n 以外が全部 0」ではない。
- `c3nWallProven` の診断（約 20 ms）は、内訳条件を満たし無改善が `shortStall` を超えた後に遅延実行する。3.642.0 から `C3nWallProof` が次の規則で証拠を選ぶ（既定）。
  - 生存盤面（`V6NativeOptimizer.liveBestSnapshot`）の報告が最良の報告 `wd.bestReport` と**同じ参照**のときだけ、その盤面を診断する（`C3nWallProof.bound`）。値が同じだけの別の報告は使わない。
    生存盤面は `publishLiveBest` が出すが、呼出は `ownsStatics(runSlot())`（スロット無しの直接呼出か、そのスロットが最新の run。`handleOptimize` 経由では常にスロットがある）のときだけ＝古い run が残す盤面は証拠にならない。
  - 診断は盤面の内容で鍵をとる（`BoardKeyedFlag`）。結果は必ずその盤面のものになる。
  - 診断の前後で生存盤面・最良の報告・版のいずれかが変わったら、その判定は使わない。
  - 対応が取れない間は、同じ最良版で対応が取れていた判定だけを持ち越す（段の境界で `optimize()` が生存盤面を空にしても失わない）。版が変われば持ち越さない。
  - 既知の限界: 同じ報告参照は同じ盤面とみなせるが、値の一致では別盤面を区別できない。対応が取れない時間は壁の短縮を使わないので、停止は遅れる側（fail-closed）に倒れる。その代償は §11 の測定で判断する。
  - 測定の基準腕 `PolishGate.c3nWallLegacy=true` は HEAD（版ごとに一度、生存盤面を一致の検査なしに診断）。
  - 3.643.0（`PolishGate.c3nWallDeepCheck`、既定 false）: 壁を証明相当（全 run の全セルが希望固定、`ForbiddenRunDiagnosis.allBlockedCertified`）と経験的（探索手の全滅）に分け、経験的な壁は `c3nWallRefutedByOneMove` が手を見つけないときだけ `c3nWallProven` とする。OFF では従来どおり `allBlocked` だけで判定する。反証は壁でない方向にしか働かない。C# の `FixSuggester.Suggest` には `ejectionChain` が無いので ON のときの反証は C# の方が弱い（既定 OFF では同値。`windows/README.md` 2026-10-08 の記録）。
- `c3nWallPlateau` は限定した手の壁判定（§4）で**閾値を短縮する**（300 s 予算で 270 s → 37.5 s）だけで、即停止ではない。動機は実機ログ「c3n=1 のまま 150 s 無改善でも 270 s 閾値で発火不能」（3.281.0）。早期終了全体の品質差は 3.341.1 で非有意（§11）。多セル交換で崩せる c3n を短縮で取り逃がす可能性は残る。3.641.0 で測った（§11、sample_v6×5 seed）: 120 s では損失なし、60 s では短縮なしが 1.6% 良い（時間 1.75 倍）、300 s では 0.7% 良い（時間 2.35 倍、非有意）。測定スイッチは `PolishGate.c3nWallShortStall`。
- `wishReached` = `floor > 0 ∧ hard == floor ∧ hardAllWishOrigin(...)`。既定 OFF では短縮判定へ配線されない。ON の副作用（解ける HARD を残したまま短縮）は「残る HARD が全て希望由来」を条件に含めることで防ぐ設計。実機 A/B は HARD=床の盤面待ち。
- 「実現不能希望の件数」を床にした旧版は、pref から対称除外される分が HARD に寄与せず解ける HARD を早々に諦めていた＝構造的 covU へ是正済み。

### 5.4 発火式と境界

```
stalled = now − lastBestImproveMs
fired   = now − S > minRun
        ∧ stalled > effStall
        ∧ (now − lastPhaseChangeMs > phaseGrace ∨ stalled > effStall × STALL_OVERRIDE_FACTOR(2))
```

- 全て `>`。ちょうど閾値では発火しない。
- フェーズ猶予は「始まったばかりのフェーズを即殺しない」**遅延**であって拒否権ではない。並列 8 ワーカーが 1 本のフェーズ文字列を共有して猶予が永久に塞がった事故（実機 2026-08-19、275 s 無改善で未発火）を、3.408.0 で「閾値の 2 倍で上書き発火」に降格した。
- 上書き条件 `stalled > effStall × 2` はフェーズ更新の頻度に依存しない（`stalled` だけで決まる）＝フェーズ文字列がどれだけ頻繁に変わっても、無改善が閾値の 2 倍に達すれば発火する。通常発火と上書き発火の比率は未集計（`Watchdog` 行の「未発火の理由」から集計できる）。
- 旧実装の `max(lastBestImprove, lastPhaseChange)` 単一時計は、20〜90 s ごとのフェーズ遷移で 270 s に届かなかった（3.230.0）。
- `shouldStop` は `now ≥ searchEnd ∨ キャンセル` を先に判定する。締切による停止と停滞発火は別の理由。
- 発火時に「猶予の中で 2 倍に達した」かを `WatchdogBest.fire(byOverride)` に記録し、`EarlyStop` 行に「発火種別=通常／猶予上書き」と出す（3.640.0）＝比率は実機ログから数える。
- 3.642.0: 確定したラッチの記録は一度だけ書く。その後の判定の反転（生存盤面の入れ替わり）は `shouldStop` の戻り値には残りうるが、記録（時刻・反復数・`stagnationWall`）は書き換えない。

### 5.5 並列探索への伝達

- `shouldStop`: 締切・キャンセル・停滞のいずれか。改善で停滞条件が消えるので**非単調**。
- `stopIsFinal`: 締切またはキャンセルだけ（単調）。
- `V6NativeOptimizer.confirmStop`: 単調停止は即確定。そうでなければ最大 `STOP_CONFIRM_MS`=5 000 ms、`STOP_CONFIRM_POLL_MS`=250 ms 間隔で再確認し、`shouldStop` が偽へ戻れば続行（一瞬のシグナルで片肺運転にしない、3.346.1）。
- 停止は協調的。締切の瞬間に全処理が強制終了する保証はない。
- 3.642.0: 後期演算（`V6LateOperators.improve`）は `shouldStop` を試行ごとに見る（`PolishGate.lateOpStopPropagation=true`）。停止要求が立てば 1 手も受理せず返る。`false` は試行ごとの確認だけを切る（入口の確認は両腕に残るので HEAD とは完全に一致しない）。測定の切り分けにだけ使う（§10）。

### 5.6 探索後

探索結果 → 候補統合 → 後処理 → 条件付き追加精製 → 最終選択・安全確認。A の発火は後処理を一括キャンセルしない。後処理は `D` までを締切に受け取るが、各パスが自前の予算・上限・打ち切りを持つ。`postReserve` は探索から確保する枠であり、後処理を常にその秒数で固定するタイマーではない。

追加精製 `ExtraRefine`（後処理予約枠の未使用分で最終盤面起点の keep-best ALNS、3.102.0）:

```
extraMs  = min(D − postEndMs, postReserve)
canExtra = ¬stopRequested ∧ ¬stagnationFired ∧ post.report.total > 0 ∧ ¬structuralHardResidual
実行     = extraMs ≥ 5000 ∧ canExtra
走らせ方 = ALNS・予算 max(5, extraMs/1000) s・workers = min(workers, HypothesisPlanning.MAX_HYPOTHESES=5)・keep-best（`betterReport` で post に勝ったときだけ採用）
```

`structuralHardResidual` は `extraRefineRequirePostHardDrop`（`handleOptimize` 引数、既定 false）が true のときだけ評価する追加ゲート。走らなかったときは `ExtraRefine` 行に理由を残す。希望床 E0B は専用条件で研磨を省き `minimalPost` を使う。

残存分析（`residualLog`）の「証明済みの壁」は、最終盤面の内容で鍵をとった診断（約 20 ms、`C3nWallProof.diagnoseBoard`。探索中と同じ `BoardKeyedFlag` を共有するので、最後に診断した生存盤面と同じ内容ならその判定を返す）で、探索中の時点の判定とは別の根拠（3.642.0）。

終わり方の要約（3.643.0、`V6FinalPort.StopSummary`、`ActionResult.stop`）: 早期終了の有無（`stagnationFired`）・種別 `StopKind`・使用秒・予算秒・探索終了時の無改善秒（Watchdog 行と同じ `tChain1 − lastImpAtSearchEnd`）・残る必須件数。種別の条件と順序は Watchdog 行の「実効閾値の種別」と同じ（Watchdog 行の複合ラベル「plateau+希望衝突の床」は PLATEAU_FLOOR に畳む）: 発火なし → DEADLINE、`bestHard ≤ hardFloor ∧ nonCovUHard == 0` → PLATEAU_FLOOR、`wishOn ∧ wishReachedEnd` → WISH_FLOOR、`stagnationWall ∧ nonCovUAllC3n` → 最終盤面の `diagnoseForbiddenRuns(...).allBlockedCertified` で C3N_WALL_CERTIFIED か C3N_WALL_EMPIRICAL（`c3nWallDeepCheck` と無関係に常に分ける。壁で止まったときだけ診断する）、それ以外 → NORMAL_STALL。ログ `StopSummary` に同じ値を 1 行出す。画面は `ui/StopExplanation.kt`（Windows は `StopExplanation.cs`）が一文と次の一手に変換してホームの結果カードに出す。採否・探索・停止には配線しない＝出力（盤面）不変。

### 5.7 DEFAULT の既知のギャップ（欠陥ではなく仕様の歪み）

- 構造床（強い根拠）と c3n 局所壁（弱い根拠。§4）が同じ `shortStall` を使う。
- 診断の開始（`stalled > shortStall`）と発火の閾値が同じ目盛りなので、壁認定の後の追加探索はほぼ 0 になりうる。
- 停止範囲は探索全体なので、未消化の層 B ロールもまとめて終わる。
- 3.641.0・3.642.0・3.643.0 の測定（§11）では、この歪みによる害は検出されていない。測定の精度（同一 seed の揺れ SD 約 100）が腕の差を飲み込むことも分かった。

### 5.8 PROPOSAL（提案。既定の動作ではない）

| 提案 | 状態 | 内容 |
|---|---|---|
| A: 追加待機 M | 未実装 | `c3nWallPlateau ∧ c3nWallProven ∧ B ≥ B_min` のとき `effStall = shortStall + M`（例 B_min=120 000 ms、M∈{15 000, 30 000, …}、8 000 ≤ M ≤ normalStall − shortStall）。未診断・診断 false・小予算は normalStall のまま。両端は測定済み: M=0 が DEFAULT、M=normalStall−shortStall が `c3nWallShortStall=false`（§11 の c3n 行: 60 s −1.6%・時間 1.75 倍、120 s 差なし・2.6 倍、300 s −0.7%・2.35 倍、いずれも非有意）。言える効果は「壁の成立後、最大およそ M ms の追加の無改善待機が入りうる」まで |
| B: 根拠の段階化 | 実装済み・既定 OFF（`PolishGate.c3nWallDeepCheck`、3.643.0） | `wall = allBlocked ∧ (allBlockedCertified ∨ ¬c3nWallRefutedByOneMove(board, 2000 ms))`。証明相当の壁（全セル希望固定）は従来どおり短縮。経験的な壁は 1 手探索（`FixSuggester`、`Probe.C3N_WALL_DEEP_MS`）でも必須を減らす手が無いときだけ短縮。反証は壁でない方向にしか働かない。同じ盤面の診断は一度だけ（`BoardKeyedFlag` が直列化）。観測は `Watchdog` 行の「1手探索の反証 N 回／確認 M 回」。測定はベンチの腕 `deep`。sample_v6 の残る必須は希望衝突由来（証明相当）なので、このフィクスチャでは A/A になる＝経験的な壁が出るフィクスチャで測る。探索（2026-10-09、`tools/loop/WallFixtureProbe.kt`、§11）: 実データ 4 件と合成 174 盤面で、壁条件（covU が構造床・残りは c3n だけ）に乗る壁は希望固定の証明相当だけ＝経験的で反証できる壁のフィクスチャは無い。計測は、画面の説明が C3N_WALL_EMPIRICAL を出した実機の盤面を待つ。言える効果は「1 手で崩せる壁を壁と呼ばなくなる」まで |
| C: 適応閾値 | 実装済み・既定 OFF・**計測で否決**（`PolishGate.adaptiveStall`、3.643.0。腕は再計測用に残す） | `adaptive = clamp(max(直近 ADAPTIVE_STALL_WINDOW=8 個の改善間隔) × ADAPTIVE_STALL_FACTOR=3, shortStall, normalStall)`（間隔が ADAPTIVE_STALL_MIN_GAPS=3 個未満なら無し）、`effStall = min(effStall_DEFAULT, adaptive)`（`V6FinalPort.adaptiveStallMs`・`effectiveStallMs(adaptiveMs)`）。間隔は `WatchdogBest.observe` が改善と改善の間だけ記録する（開始から最初の改善までは含めない）。縮めるのは通常分岐だけ＝床・壁の短い閾値より短くはならず、発火式の三条件（§5.4）はそのまま。動機: 通常閾値は予算の 9/10（300 s で 270 s）で実質発火しない（§5.7）。危険: 序盤の密な改善のあと大域ロールが遅い改善を出す局面を切る＝短縮方向にしか働かないので、測定で負ければ入れない。観測は `Watchdog` 行の「通常=長…s→適応…s」と「・適応閾値=…（改善間隔 N 個・最大 X s×3）」。測定（2026-10-09、blocked_covu・120 s・5 seed×2 rep、§11）: 適応は 15〜21 s の閾値で 40〜61 s に止まり（時間 −71 s）、weighted は +412 悪化（95% 区間 [−744, −74]＝決定・既定が良い）、必須は同じ 4。序盤の密な改善間隔が後半の遅い改善を切る＝動機のギャップは埋まるが品質を落とす。既定 OFF のまま否決。再提案は係数・窓・予算を変えて測ってから |

出荷条件（両方）: §13 の 4 の作法で測り、事前に決めた基準を満たすこと。引き分け・負けは DEFAULT 維持で §11 に 1 行。

敵対検証の要約（提案の前提。2026-10-08）: 床と局所壁が同じ short なのは根拠の強さに対して歪み＝A・B の動機。固定の中間閾値（B/4 など）は短い予算で差が出ず採用しない。「大域ロールが必ず走る」は過大。未診断を短縮するのは危険＝normalStall のまま。層 B の量子変更は否決同型の危険＝提案本体から外す。ログは制御より先（§12）。keep-best は入力比の下限だけ＝「粘れば得られた改善」は非保証（§3.3）。成功指標は後処理後の final。評価が同じでも盤面は同じとは限らない＝壁の証拠は同じ報告参照で結ぶ（§5.3、K9）。

## 6. 層 B — 適応ポートフォリオ

役割スロットは `floorMod(workerIndex, 8)`。以下は 8 スロットの説明で、ワーカー数が常に 8 という意味ではない。

### 6.1 役割と初期配置

| スロット | 初期役割 | 再配属後 |
|---|---|---|
| 0 | BASELINE_REFINE | 不動（`shouldReassign` が常に false） |
| 4 | BASELINE_REFINE | 初回再配属で ELITE_RELINK、以後も同役割。ただし `reassignments` は増え続け強度に効く |
| 1,2,3,5,6,7 | 巡回列の異なる開始位置 | 再配属ごとに次の役割 |

巡回列: `DAY_BLOCK_ALNS → HARD_FAMILY_RSI → HARD_DEBT_RSI_PLUS → LARGE_DESTROY_ALNS → PERSONAL_RSI → MAX_DISTANCE_RSI_PLUS`。`PolishGate.personSwapKick`（既定 true）なら末尾に `PERSON_SWAP_ILS` が付き 7 役割。
役割の決め方（`AdaptiveHypothesisEpochPolicy.assignmentFor(index, reassignments)`）: `slot = index mod 8`。slot 0 → BASELINE_REFINE、slot 4 → `reassignments == 0` なら BASELINE_REFINE、以後 ELITE_RELINK。その他 → `roles[(baseEscapeOffset(slot) + reassignments) mod roles.size]`、`baseEscapeOffset` は slot 1→0・2→1・3→2・5→3・6→4・7→5。算法は DAY_BLOCK/LARGE_DESTROY が ALNS、HARD_FAMILY/PERSONAL が RSI、他は RSI_PLUS。8 ワーカーなら開始時から 6 本が脱出役なので、脱出役の時間比率の高さだけで「再配属過多」と判断しない。

### 6.2 再配属と強度

```
improvedThisEpoch = betterReport(eliteAfter, eliteBeforeEpoch)   // 自己エリート同士。摂動入口に勝っただけでは改善でない（3.282.0）
stagnantEpochs    = improvedThisEpoch ? 0 : previous + 1
reassign          = slot ≠ 0 ∧ (nearestOtherDistance ≤ DUPLICATE_DISTANCE_CELLS(2) ∨ (¬improvedThisEpoch ∧ stagnantEpochs ≥ 1))
```

距離は trajectory 間のセル差（`RoleDiversityHelpers.scheduleDistance` = `AdaptiveEliteArchive.scheduleDistance`: 両盤面の重なる範囲で値が違うセルの数＋行数・列数の差ぶん。意味的な近さではなく安価なセル一致）。重複条件は改善したエポックにも適用される（多様性は別の不変条件）。再配属で `reassignments++`・`stagnantEpochs=0`。
「無改善 1 エポックで再配属」は攻撃的に見えるが、`≥ 2` へ遅らせる案は測って否決した（3 データ×2 seed・45 s・8 ワーカー: fixture 4 勝、実データ real3 で +291 悪化、p≈0.19。脱出役の時間比率は初期配置で決まり `shouldReassign` では変わらない＝`docs/algorithm_portfolio.md`「再配属を 2 エポック連続で未改善まで遅らせる」）。

強度 = 役割の基礎値 + `min(max(reassignments,0)/2, 3)`。基礎値: BASELINE 0／ELITE_RELINK・DAY_BLOCK・HARD_FAMILY・PERSONAL・PERSON_SWAP 1／HARD_DEBT 2／LARGE_DESTROY・MAX_DISTANCE 3。「2 回失敗してから強度を上げる」設計。

### 6.3 エポック予算と成果回収

- 量子（`AdaptiveHypothesisEpochPolicy` の定数）: RSI_PLUS は基礎 `RSI_PLUS_BASE_QUANTUM_SEC`=35 s・改善直後 `RSI_PLUS_IMPROVING_QUANTUM_SEC`=45 s、他は `BASE_QUANTUM_SEC`=5 s・`IMPROVING_QUANTUM_SEC`=8 s。残り秒数以下にクランプ。改善直後の長い量子は**役割が変わった直後は受け取れない**（`carriesImprovingQuantum`）。再配属分岐では W4 も基礎量子へ戻す。
- 役割内部の workers は 1。開始前に停止・締切を確認し、戻った成果を**回収してから**締切を判定して break（3.600.0。回収前に break すると締切間際の改善解を捨てる）。
- globalBest と自己 elite は別管理。採用は `betterReport` と配置保護を通す。

## 7. 層 C — RSI の focus 切替と打ち切り

### 7.1 HF63 の学習範囲

`Hf63Infeasibility.updateFromBreakdownFocused` が追跡する族は `KEY_TO_INDEX` の 13 個だけ:

```
c1 c2 c3 c3n c3m c3mn c41 c42 covU covO pref low high
```

groupViol・c3w・c41s・c42s・apt・weekly・fair は学習しない（配列名 `CNAMES` に Apt があっても index に無い）。学習しない族は `dynamicAvoid` に入らない＝**常に focus 可能**で、N4 のピボット候補にも残る（保守側。HARD の groupViol/pref は hf67 の決定的修復で直るので充足困難の学習対象にする必要が薄く、c3w は 3.542.0 の新族で未追加）。各追跡族について、

- 観測値が過去最小より小さい → 最小更新・停滞投入量 0・推定解除
- 観測値 0 → 停滞投入量 0・推定解除（3.592.0）
- それ以外で `key == focusedKey` → 停滞投入量 += `effortIters`、`INFEAS_STALL_ITERS`=5000 以上で `infeasibleLikely=true`
- focus 外かつ非改善 → 何もしない

目的関数の重みには触れない（λ上限は 3.409.10 で撤去済み）。

```
attemptsTarget = max(2, (max(0, rounds − 2) + 1) / 2)     // 整数除算、HypothesisPlanning.rsiHf63EffortIters
effortIters    = (5000 + attemptsTarget − 1) / attemptsTarget
```

`effortIters` はラウンド数から決まる換算量で、実測反復数でも経過ミリ秒でもない。ラウンド先頭で前回 focus を更新し、最終ラウンド分はループ後に更新する。

### 7.2 回避集合は 3 種類

```
dynamicAvoid = hf63.infeasibleBreakdownKeys()
avoid        = dynamicAvoid ∩ MirrorKeys.hard；covUFloor > 0 ∧ covU ≤ covUFloor なら covU を追加（静的 covU 床、3.95.0）
focusAvoid   = avoid + cooldownFocus（存在すれば）
```

HF63 は SOFT の推定値を持ち得るが、**SOFT を恒久回避へ入れる実装ではない**（`RsiFocusSelection.avoidSets`、`StallEscapeSpecTest` で固定）。

ラウンドの枠（`runRsi`）: `rounds = max(2, min(8, budgetSec/30 + 2))`、1 ラウンドの秒数 `per = max(1, budgetSec / rounds)`（偶数ラウンドの ALNS・奇数ラウンドの V5 に渡す予算）。`covUFloor = V6SanityPort.structuralHardFloor(state)`（層 A の `hardFloor` と同じ関数）は `avoidSets` の静的 covU 床。

### 7.3 focus 選択（`RsiFocusSelection.maxViolatedFamily`）

1. 回避されておらず件数が正の HARD を、`groupViol, covU, pref, c3n, c3w` の**順序**で選ぶ（HARD 間の最大件数ではない）。HARD は 1 件でも必須なので件数比較に意味が無く、旧・件数最大では c3n=1 が c1=118 等の SOFT に埋もれて RSI が一度も HARD を狙わなかった（3.74.0）。順序は RSI 内の修復経路（`rsiGenerateHypothesis`: groupViol/pref は hf67、covU は `destroyRepairDay`×6＋covU 連鎖（E11）、c3n/c3w は `destroyRepairViolations`）に沿う。
2. 残る HARD が無ければ apt／covO の周期枠: apt は `rotationRound % 3 == 1`、covO は `== 2`、最終ラウンドは両方が候補。`round ≥ 0`（通常の呼出し。負の round は周期枠を使わない呼出し）で、回避されず件数が正であること。
3. 両方が候補なら件数の少ない方、同数なら covO。
4. それ以外は順序表 `groupViol,covU,pref,c3n,c3w,low,high,c41,c41s,c2,covO,c42,c42s,apt,weekly,fair,c1,c3,c3m,c3mn` から、回避されない正の最大件数（同数は先のキー）。件数 0 の族は選ばない（E8）。
5. weekly が選ばれても apt が正で回避外なら apt に置換。候補なしは `total`。

`rotationRound` は既定で `runRsi` の round。`rsiFocusRotationPersist=true`（既定 false）のときだけ HF63 側カウンタで呼出しをまたぐ。

### 7.4 ラウンド後の処理

- 候補は focus に応じた仮説生成の後、0 始まり偶数ラウンドで ALNS、奇数で V5。境界で `V6LateOperators`（EarlyChain: Chain3/4・Rect・BlkN）を当てる。
- `betterReport` と `keepsExtBan` を満たして採用 → `stagnantRounds=0`・`cooldownFocus=null`。
- 不採用 → `stagnantRounds++`。focus が `total` 以外で候補の focus 件数が入口以上なら、その focus を**次の 1 ラウンドだけ**冷却（E9）。focus 件数を減らしたが総合で負けた場合は冷却しない。
- N4 打ち切り:

```
if stagnantRounds ≥ 2 ∧ dynamicAvoid ≠ ∅:
    pivot = maxViolatedFamily(bestReport, avoid, …)      // focusAvoid ではなく avoid
    if pivot == total ∨ breakdown[pivot] == 0: RSI を終了
    else: 続行（SOFT が残れば HARD 残のまま SOFT を研磨。keep-best が HARD 悪化を防ぐ）
```

発火ゲートは**動的検知だけ**。静的 covU 床を混ぜると構造的 covU>0 のデータで round 0 から常時武装し、旧 N4 の「厳密な部分集合」保証を破る（3.95.1 の実バグ）。1 ラウンド冷却を恒久枯渇と誤認しない。
HF63 の推定は構造的不能の証明ではないが、N4 が止めるのは**その `runRsi` 呼び出しだけ**で、ポートフォリオ内では役割が回って別の手に移る＝推定を過信した場合の損失は 1 ロールぶんに限られる。未追跡の族（groupViol/c3w/apt/weekly/fair 等）や SOFT が正なら pivot になるので、「学習していない族が残っているのに枯渇と判定する」ことはない。

### 7.5 状態の寿命

| 状態 | 寿命・所有者 |
|---|---|
| `stagnantRounds`・`cooldownFocus`・`lastFocus` | 各 `runRsi` 呼出しでローカル |
| HF63 | 通常は呼出しローカル。ポートフォリオではワーカー専属インスタンスをエポック間で共有（3.281.0）。ワーカー間では共有しない |

## 8. 層 D — SA/LAHC ラダーと再起動

- `SaOptimizer`（Kotlin）と C++ `SaChunk` は同値。`softPolish=true` かつ HARD 部分が `hardStallMs`=2 500 ms 改善しなければ PhaseB（LAHC）へ**一方向**に移る。時計 `lastHardImprove` は HARD の改善でだけ戻る。
- 2 500 ms 無改善は HARD 下限の証明ではない。コメントの「床到達」は切替方針の略記。native 経路の判定粒度はチャンク境界（ms 級）。
- ラダー境界: `MagiConductor` が無ければ reset-to-best 再加熱。あれば STRONG_PERTURB／SCALE_TEMP（現在盤面を保持し次ラダーで再加熱、温度倍率は掛けない）／REHEAT／NOOP を報酬で選ぶ。
- ALNS の再起動摂動は一律 `strength = 0.18 × explore`（0.05〜0.6）。非線形スケジュール（2.51.0）は実データ final で +101% 悪化し revert（2.58.0）。
- GLS penalty aging: `GLS_DECAY_EVERY`=256 kick ごとに 80% へ減衰（2.50.0）。ベンチでは中立＝長時間の肥大化防止として温存。
- 過去に否決した λ オシレーション（2.52.0→2.55.0 revert）と、現行後処理の `HF80StrategicOscillation`（漸増摂動の研磨）は別物。

## 9. 層 E — 後処理の打ち切り

パスの順序と責務は `docs/algorithm_portfolio.md`「後処理」。停滞制御に関わる点だけ:

| パス | 停滞の定義 | 動き |
|---|---|---|
| C1 共同 LNS | 最良が `Config.patienceMs`（既定 4 000）更新なし、または評価数上限 | 打ち切り（3.342.0）。`patienceMs ≤ 0` で時間 patience 無効。決定的モードは別設定 |
| C1 広域ビーム | 最良保持と停滞打ち切り | 3.340.0 |
| 巡回研磨クラスタ | 1 巡で採用 0（`roundApplied == 0`） | 巡を終える（joint 局所最適） |
| HF80 戦略的振動（`applyHF80StrategicOscillation`） | （停滞の定義なし） | 最大 `maxCycles`=3 サイクル。`shouldStop` だけで止まる。停滞早期 return は無い |
| 後処理チェーン全体 | （停滞検知なし） | チェーン全体の採用 0 を停滞と見る分岐は 2026-09-25 に `stallEscalation`・`ClusterOutcome.totalApplied` と共に撤去（§10）。残るのは巡単位の `roundApplied == 0` だけ。`PostChain` は巻き戻したパスの `applied` も既定では数える（`postChainRollbackCountsZero=false`。0 と数える版は 230 ペアで差なし） |

E10 の停滞早期終了（best が枠の 1/5（下限 3 s）無改善で早期 return、native 区間で無改善なら時計の起点を `started` に引き継ぐ＝枠は 3.150.0、起点の引き継ぎは 3.179.0）は層 E ではなく `V6NativeOptimizer.hf80PostPolish` にある。呼出元は `runRsiPlus` の Phase 4（層 B のロール内）・`softPolishOnly`・`optimize()` 末尾（`options.postPolish`。`handleOptimize` は false 固定）だけで、後処理チェーンからは呼ばれない。

予算の相互作用: 後処理は絶対時刻の全体締切 `D` を受け、巡回クラスタは `clusterStop` の自前締切、共同 LNS はその中で patience／評価数上限で止まる。前段が早く終わった分は後段がそのまま使える（締切は絶対時刻、予約枠は探索から確保する枠で後処理内の配分ではない）。
採用数は最終盤面の純改善数ではない。後処理の時間削減と最終品質の非劣性は別々に測る。

## 10. 既定 OFF・実験機構（`80d664d` と 3.642.0・3.643.0 の既定）

| 機構 | 既定 | 本体と分けて読む点 |
|---|---|---|
| `PolishGate.stallPolishInjection` | false（設定タブからは 3.628.0 で非表示） | 全体最良の最終改善と最終試行の遅い方（`max(lastGlobalImproveMs, lastTryMs)`）から `max(20 000, budgetSec×100)` ms 経ったとき、最良の写しへ後処理を 6 000 ms 上限で当て、良ければ全体最良へ。回数 3 未満・探索残り 8 000 ms 以上が条件（`StallPolishInjection.shouldInject`） |
| `PolishGate.wishConflictFloorMode` | OFF | 希望床の短縮終了（E0A）／E0B は最小後処理へ |
| `extraRefineRequirePostHardDrop` | false（引数） | 残存 HARD が §4 の床のときだけ追加精製を省く。実質 A/A で信号なし（backlog #35） |
| `V6OptimizerOptions.rsiFocusRotationPersist` | false | 周期枠を `runRsi` 呼出しをまたいで継続（有意差なし、backlog #28） |
| `PolishGate.postChainRollbackCountsZero` | false | 巻き戻したパスの採用数を 0 と数える |
| `PolishGate.aptFairSoftTolerance` | false（「じっくり」で ON） | apt/fair 限定の採用例外。`AptFairPolish.heavySoftGuard`（既定 true、3.637.0）は無害化②の測定スイッチ |
| `PolishGate.prePostDescent` | false | 後処理前の局所降下 |
| `PolishGate.normalStallFraction` | 0.9（UI なし） | §5.1 の `fraction` |
| `PolishGate.c3nWallShortStall` | true（UI なし） | false で c3n 壁による短縮を外す（測定用、3.641.0） |
| `PolishGate.c3nWallLegacy` | false（UI なし） | true で HEAD の c3n 壁判定（版ごと固定・生存盤面の診断・一致の検査なし）。測定の基準腕（3.642.0、§5.3） |
| `PolishGate.lateOpStopPropagation` | true（UI なし） | false で後期演算の試行ごとの停止確認を切る（入口の確認は残るので HEAD とは完全に一致しない）。測定の切り分け（3.642.0、§5.5） |
| `PolishGate.c3nWallDeepCheck` | false（UI なし） | true で経験的な c3n 壁を 1 手探索（上限 2 s）で反証し、手が無いときだけ短縮に使う。ベンチの腕 `deep`（3.643.0、§5.3） |
| `PolishGate.adaptiveStall` | false（UI なし・計測で否決） | true で通常分岐の停滞閾値を直近の改善間隔の最大×3（[短, 通常] に挟む）まで縮める。ベンチの腕 `adaptive`（3.643.0、§5.8 C・§11） |

撤去済みで現行仕様ではないもの: 残差ベース 4 段脱出 `adaptiveEscapeControl`／`StagnationEscapeController`（3.409.21 単体 A/B 中立）、ロール内並列 SA `portfolioRoleParallelSa`（同）、採用 0 の巡で LNS・VCR を 2 倍にする `stallEscalation`（3.511.1 全件無変化、2026-09-25 撤去）。

## 11. 据え置きが決まっている値と否決した案（再提案は計測つきで）

| 値・案 | 決定 | 根拠（測定条件） |
|---|---|---|
| 3.643.0 の精度計測（既定 対 HEAD 相当、120 s） | 未決＝2 つの腕は区別できない。既定は正しさ（競合の修正）の理由で維持 | sample_v6・120 s・10 seed × 2 rep・workers 4・同一 seed（`MAGI_HO_REPEATS`、`tools/loop/ho_stats.py`）: 20 対、weighted 差（既定−相当）の平均 −15.6、95% ブートストラップ区間 [−90, +59]、既定が悪い 9／良い 11（符号検定 p=0.82）、hard は全 run で 1。同一 seed の rep 間の揺れ: プール SD 92（既定）／110（相当）、最大範囲 258。この差と揺れで区間を 0 から外すには約 480 対。元の 5 対と診断の 2 対を合わせた 27 対でも区間 [−64, +71] |
| 3.642.0 の証拠の対応（同じ参照）と後期演算の試行中の停止確認 | 既定を維持（2026-10-08）。事前基準（既定が 5 seed 中 3 以上で悪化なら戻す）を満たさない | sample_v6・300 s・5 seed・workers 4・同一 seed。既定（off）対 HEAD 相当（on: `c3nWallLegacy=true`・試行中の停止確認を切る。入口の確認は両腕に残る）。weighted 差（既定−相当）の平均 −49（既定が良い 3/5、悪い 2/5、−273〜+87）、total 差の平均 −11（既定が良い 4/5）。hard は全 run で 1。既定の壁時計は平均約 207 s、相当は約 142 s。有意差の検定はなし。120 s（AUTO では RSI→ALNS の 31〜210 s 帯）も同条件で測った（`tools/loop/results/handleoptimize_head_sample120_2026-10-08.csv`）: weighted 差（既定−相当）の平均 −65（既定が良い 4/5、悪い 1/5、−202〜+21）、total 差の平均 −4（既定が良い 3/5）、hard は全 run で 1、壁時計は既定 約 52 s・相当 約 45 s。こちらも基準を満たさないので既定を維持。再実行の診断（同日）: 悪化した 4 ペアは再現せず、同一 seed・同一腕でも weighted 最大 325・停止時刻最大 215 s の揺れ（workers 4 は壁時計依存）。8 run とも同じ規則で停止（c3n 壁=短・通常発火・4 ワーカー同時離脱）。腕の差は揺れの範囲＝優劣は未決。既定は事前基準と正しさ（競合の修正）の理由で維持。決めるなら 120 s×20 seed 以上か workers=1。下の c3n 行は 3.641.0 の実装（値の一致による対応）の数値。 |
| c3n 壁による短縮（`c3nWallPlateau`） | 据え置き（2026-10-08、`PolishGate.c3nWallShortStall` 既定 true） | sample_v6×5 seed・同一 seed・workers 4: 120 s は短縮なし 2 勝 3 敗・平均 +8.8 で損失なし（時間 46→119 s）、60 s は短縮なし 5 勝 0 敗・−1.6%（時間 33→58 s）、300 s は短縮なし 4 勝 1 敗・−0.7%（時間 127→299 s、p≈0.19）。緩めるなら時間と品質の交換＝業務判断 |
| `normalStallFraction` 0.9 | 据え置き（2026-10-07） | 0.5 は 3.423.0 で 6 勝 3 敗・非有意（3 fixture×2 条件×3 反復＝18 run、RSI・workers=1・60 s）、blocked_covu 型 15 ペアでも再現せず（3.447.0）。外部提案「60〜90 s」は前提不成立（余りは後処理へ回らない、実機 #1 は 80 s の空白後に最終改善） |
| 早期終了そのもの | 維持 | 外すと weighted 中央 −3.5%（U 検定 p≈0.075、非有意）で時間 2.3 倍（3.341.1: golden 120 s×5 回） |
| 余った予算を soft 研磨へ | 否決 | 時間が余るのは hard=0 のときだけ（120 s・workers=4・実データ 3 件）。穏当版も「5 回中 4 回以上が現行中央値より良い」に対し 2/5（3.341.1） |
| covU-blocked 専用の早期終了 | 却下 | 実データ多 seed A/B で便益なし（3.361.0） |
| `stallMs = budget/6`（旧 50 s） | 戻さない | HARD=1 を 50 s で諦め残り 250 s を捨てた |
| 戦略的振動／nonlinear restart／GLS スイープ／targeted-perturb／big-destroy／softFocusProb | 否決 | 2.55.0・2.58.0・2.56.0・3.95.0 |
| 「修復途中の進捗」を停滞判定へ | 実装不要 | `improvedThisEpoch` が正式比較器で HARD 優先に拾っている（backlog #26） |
| 同点の盤面を足場にした停滞脱出（プラトー探索 B） | 否決 | 停滞盤面 15 枚（5 データ×3 seed）×3 反復、同じ盤面・同じ持ち時間: 後処理直行に 10 s 3 勝 27 敗・30 s 9 勝 19 敗（2026-10-07） |
| 適応閾値（`adaptiveStall`、§5.8 C） | 既定 OFF 温存＝**否決** | blocked_covu・120 s・5 seed×2 rep（`handleoptimize_adaptive_blockedcovu120_2026-10-09.csv`）: 10 対で weighted 差（既定−適応）の平均 −412、95% 区間 [−744, −74]、既定が良い 7／悪い 3、必須は全 run 4、時間 −71 s（適応は 40〜61 s で停止、閾値 15〜21 s）。品質を落として時間を買う＝入れない（2026-10-09） |
| 根拠の段階化（`c3nWallDeepCheck`） | 既定 OFF 温存（計測不能） | sample_v6 の壁は証明相当＝A/A。合成 174 盤面（4 s×126・30 s×48）でも壁条件に乗る経験的な壁は 0 件、反証できた盤面は covU が床より多い未収束だけ（2026-10-09、`wall_probe_*_2026-10-09.csv`） |
| 停滞時の後処理差し込み | 既定 OFF 温存 | 240 s／300 s（PORTFOLIO は 211 s 以上でしか選ばれない）: 採用 32/43 回だが最終 8 勝 7 敗・重み +1291・HARD 退行 0（2026-10-07） |
| 残差ベース 4 段脱出／ロール内並列 SA | 撤去 | 単体 A/B 各 15 ペア（3 データセット、1 プロセス=1 実行）で中立（3.409.21） |
| 再配属を 2 エポック連続無改善まで遅らせる | 否決 | 3 データ×2 seed・45 s・8 ワーカー: fixture 4 勝、実データで +291 悪化、p≈0.19（§6.2） |
| 同点以上を受理する歩き→後処理（プラトー探索 C＝後処理前の貪欲な局所降下 `PrePostDescent`） | 否決・既定 OFF で測定用に残す | 決定的 LoopBench 46×3 で 91 勝 23 敗だが、実時間 handleOptimize 15 組で ON 4 勝 11 敗・平均 weighted +110（2026-10-07） |

開いている案: 停滞時の探索半径・職員数・窓長の段階的拡大は backlog #13(a)。

## 12. 判定に使うログの行

| 行 | 層 | 読めること |
|---|---|---|
| `Watchdog` | A | 実効閾値の種別（通常=長／plateau=短／c3n壁=短／希望衝突の床=短）・停滞秒・発火の有無・未発火の理由・進捗報告ぶんの反復数（真の総量の 49〜59%、桁の区別にだけ使う）・c3n 壁の確認回数と、生存盤面の更新のうち最良の報告と対応しなかった回数（3.642.0）・`adaptiveStall` ON なら適応閾値と改善間隔（§5.8 C） |
| `AdaptivePortfolio` | B | 再配属回数・合計 iter |
| `RunMAGI_RSI` | C | 改善したラウンドと最終ラウンド、末尾「戦略変更」1 行に focus の遷移、N4 の早期終了 |
| `HF80`（`PostPolish` 行）／`ExtraRefine` | B（RSI_PLUS の Phase 4）・softPolish／追加精製 | 停滞早期終了の有無、走らなかった理由。チェーンの `HF80`（`SO applied=` 行）は E で、採用の有無とサイクル数だけ |
| `StopSummary` | A（出力） | 終わり方の種別（DEADLINE／PLATEAU_FLOOR／WISH_FLOOR／C3N_WALL_CERTIFIED／C3N_WALL_EMPIRICAL／NORMAL_STALL）・早期終了の有無・使用秒／予算秒・探索終了時の無改善秒・残る必須件数＝画面の説明と同じ値（3.643.0） |
| `設定の効き`（`TuningTelemetry.summary`） | 横断 | トグルがその実行で何をしたか。毎回「観測なし」のトグルは消してよい |

## 13. レビューと変更の進め方

1. 対象 SHA を固定する。main が進んだら参照箇所の差分から確認する。
2. 問題は層（A〜E）・観測対象・状態の所有者・時間単位で記述する。発火条件だけでなく解除条件と停止範囲を確認する。
3. 「現行コードの事実」「過去測定」「推論」「提案」を分ける。改善不能の根拠は §4 の 4 種に分ける。
4. 制御変更は 1 件ずつ、同じ入力・seed・予算で最終 hard・weightedScore・total・経過時間を比較する（`tools/loop/run_bench.sh`、`MAGI_BENCH_FEATURE`）。実行中の採用数や AUC では採否を決めない。実データでは final を見る。壁時計のハーネス（`HandleOptimizeBench`）は同じ seed でも揺れる（workers 4）ので、`MAGI_HO_REPEATS` で同じ seed・同じ腕を反復し、`tools/loop/ho_stats.py` で rep 間の揺れと対の差の 95% 区間を出す。区間が 0 を含むなら「未決」として既定を動かさない（3.643.0）。
5. 重み・閾値・探索幅を文書整理のついでに変えない（HF77＝明示数値指示＋1 件ずつ）。
6. 層 A・B の純関数を変えたらテスト（`StallEscapeSpecTest`＝この文書の表と境界、`V6FinalPortTest`・`WishConflictFloorTest`・`HypothesisEpochPolicyTest`・`Hf63InfeasibilityTest`・`StallPolishInjectionTest`、C# `StallEscapeSpecTest`・`V6FinalPortWatchdogTest`）を同じコミットで更新し、採否を `docs/algorithm_portfolio.md` と `docs/history/` へ書く。
   `stagnationFired` が改善で降りること（`WatchdogBest.observe`）と `runRsi` の `avoid` に SOFT が入らないこと（`RsiFocusSelection.avoidSets`）も `StallEscapeSpecTest` が固定する。

推奨レビューケース:

- `stalled == effStall` は未発火、超過後に他条件込みで発火する。
- フェーズ遷移が頻繁でも 2 倍超過で猶予を上書きできる。
- 停滞発火後の改善でフラグと停滞記録が解除される。
- 監視の 1e-6 許容差と候補の厳密比較を区別できる。
- c3n の局所壁判定を多セル交換で崩せる例がないか。
- focus 外の族へ HF63 停滞量を加算しない。0 到達で推定解除する。SOFT を恒久回避しない。
- W4 の再配属 2 回目以降でも強度・量子の規則を満たす。
- 締切で戻った成果を、回収前の break で捨てない。

### 13.1 不変条件（テストで固定）

| ID | 内容 | 固定するテスト |
|---|---|---|
| K1 | 採用は `betterReport` だけ | `FixApplyGateTest`・`WishPinStrictTest`・`Hf80StrategicOscillationTest`・`StopSemanticsTest`（`V6FinalPortTest` は発火式と閾値だけ） |
| K2 | 入力より悪い解を採用しない | 各番兵のテスト（`runAlns`／`runV5`／後処理の入力比） |
| K3 | `wishLocked`／上限 0（`mayPlace`）／手動固定の保護 | `FixApplyGate`・候補生成のテスト |
| K4 | 実行全体の停止は層 A だけ | 設計（§2） |
| K5 | `c3nWallProven` を大域証明と呼ばない・扱わない | 文書（§4） |
| K6 | 提案を測定なしで DEFAULT にしない | 運用（§13 の 4） |
| K7 | 否決表と同型を測定なしで復活させない | 運用（§11） |
| K8 | Kotlin／C# は同日同期。文書は同コミット | 運用（§1） |
| K9 | 壁の証拠は生存盤面の報告が最良の報告と同じ参照のときだけ使う。値の一致では使わない | `StallEscapeSpecTest`（`c3nWallBindsOnlyToTheSameReportObject`、`c3nWallProofUsesOnlyTheLiveBoardThatIsTheBestReport`、`c3nWallProofCarriesItsVerdictAcrossAStageBoundaryOnly`、`c3nWallProofRefusesAVerdictWhenTheLiveBoardChangesDuringDiagnosis`、`c3nWallLegacyArmDiagnosesTheLiveBoardOncePerVersion`、`c3nWallProofCountsEachLiveBoardUpdateOnceNotEachPoll`） |
| K10 | 停滞ラッチは改善で降り、確定した記録は一度だけ書く | `StallEscapeSpecTest`（`fireIsRefusedWhenAnImprovementInterleavesBetweenDecisionAndFire`、`repeatedDecisionDoesNotOverwriteALatchedFire`） |
| K11 | 証明相当の壁は全セル希望固定だけ。1 手探索の反証は必須が減る手があるときだけ真 | `V6PortAnalyzerTest`（`forbiddenRunCertificateIsOnlyTheAllWishPinnedRun`、`oneMoveRefutationFindsAMoveOnlyWhereHardCanDrop`） |

C# は `MagiEngine.Tests/V6/StallEscapeSpecTest.cs`・`V6FinalPortWatchdogTest.cs`・`V6PortAnalyzerForbiddenTest.cs` が同じ内容を固定する。

### 13.2 実装チェックリスト（他社 AI 用）

DEFAULT

- [ ] `effectiveStallMs` が basePlateau／c3nWallPlateau で short、それ以外は normal。`c3nWallShortStall=false` で壁の短縮だけが外れる
- [ ] 発火式が minRun・stalled・phaseGrace／×2 の三条件、すべて厳密不等号（§5.4）
- [ ] 発火は判定時の `bestVersion` と一致するときだけ `progressLock` 内で確定し、記録は一度だけ（§5.2・§5.4）
- [ ] 壁の証拠は同じ報告参照のときだけ。診断は盤面の内容で鍵。前後で変われば使わない。同じ版だけ持ち越す（§5.3）
- [ ] c3n 診断が局所手だけであることの明記（§4）
- [ ] 層 B: slot 0 非再配属、距離 ≤ `DUPLICATE_DISTANCE_CELLS`(2)、`stagnantEpochs ≥ 1`、強度は `min(reassignments/2, 3)` 加算（§6.2）
- [ ] 層 B: 改善は自己エリート同士の `betterReport`（§6.2）
- [ ] 層 C: N4 は `runRsi` ローカルだけ。HF63 の記録は内部キー（§7）
- [ ] 層 D: PhaseB への移行は `softPolish=true` のときだけ（§8）
- [ ] 停滞発火後は ExtraRefine を走らせない（§5.6）
- [ ] 後期演算は入口で停止を見る。試行ごとの確認は `lateOpStopPropagation`（§5.5）
- [ ] 層 B: 役割は `assignmentFor` の slot→開始位置（§6.1）、距離は `scheduleDistance`（セル不一致数、§6.2）
- [ ] 層 C: `rounds`・`per`・`covUFloor` は §7.2 冒頭の式。周期枠は `round ≥ 0`（§7.3）
- [ ] 層 E に停滞検知は無い（巡単位の `roundApplied == 0` だけ）。E10 は `hf80PostPolish` 側（§9）
- [ ] 終わり方の要約 `StopSummary` は出力専用。採否・探索・停止に配線しない（§5.6）

PROPOSAL（フラグ ON のときだけ）

- [ ] B: `c3nWallDeepCheck` ON で証明相当の壁は短縮、経験的な壁は反証が無いときだけ短縮。OFF で DEFAULT と一致
- [ ] A: `c3nWall ∧ proven ∧ B ≥ B_min` で short + M。未診断／false／小予算は normal。base は short のまま。OFF で DEFAULT とビット一致
- [ ] C: `adaptiveStall` ON で通常分岐だけ `min(normal, clamp(max(間隔)×3, short, normal))`。間隔 3 個未満は無効。床・壁の短縮はそのまま。OFF で DEFAULT と一致（`adaptiveMs = null`）

測定

- [ ] 後処理後 final を正とする。同一 seed。腕ごとに時間も記録（§13 の 4）
- [ ] 同じ seed・同じ腕を反復して揺れを先に測る。対 8 未満は判定しない。区間が 0 を含めば未決
- [ ] 基準（何 seed 中いくつで戻すか）を測る前に決める

## 14. 根拠ファイル（`80d664d` と 3.642.0・3.643.0 の差分）

`app/src/main/java/com/magi/app/v6/`: `V6FinalPort.kt`・`V6NativeOptimizer.kt`・`AdaptiveHypothesisEpochPolicy.kt`・`MirrorCore.kt`・`Hf63Infeasibility.kt`・`HypothesisPlanning.kt`・`RsiFocusSelection.kt`・`V6PortAnalyzer.kt`・`V6SanityPort.kt`・`SaOptimizer.kt`・`V6HotfixPasses.kt`・`C1JointLnsPolish.kt`・`StallPolishInjection.kt`・`V6LateOperators.kt`・`EliteIntegrationPolish.kt`・`AdaptiveEliteArchive.kt`・`RoleDiversityHelpers.kt`、`app/src/main/java/com/magi/app/ui/StopExplanation.kt`、`app/src/main/cpp/magi_native.cpp`。
テスト: `app/src/test/java/com/magi/app/v6/` の `V6FinalPortTest.kt`・`WishConflictFloorTest.kt`・`HypothesisEpochPolicyTest.kt`・`Hf63InfeasibilityTest.kt`・`StallPolishInjectionTest.kt`・`StallEscapeSpecTest.kt`・`V6LateOperatorsTest.kt`・`EliteIntegrationQuantitativeTest.kt`・`V6PortAnalyzerTest.kt`・`ZeroCapExclusionTest.kt`、`app/src/test/java/com/magi/app/ui/StopExplanationTest.kt`。
資料: `docs/algorithm_portfolio.md`（採否の台帳）・`docs/history/topics.md`「停滞脱出の改善」・`docs/history/INDEX.md`・`docs/lessons.md` #39/#42。
