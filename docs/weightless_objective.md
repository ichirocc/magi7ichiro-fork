# 重みを使わない目的関数の設計書（優先順位の段＋件数の許容ライン）

- 状態: **設計のみ・未実装**（2026-11-01、ユーザー「この方向で設計書に起こす」。同日「論文から検索して論文の概念を取り入れる」で §3.1 の C1〜C11 を追補）。コード・既定出力は不変。
- 現行の重み（`MirrorKeys.weights`、21 族）と HF77 は、この設計が段階 P3 で利用者に選ばれるまでそのまま有効。
- 経緯: 「各制約の重みを動的変更にしたい、または重みをなくしたい」→ ②探索中の動的重みを再考 → 英語圏の文献を調べて
  「重みを使わない最適化」へ組み直した（2026-11-01 の会話。要点は §3）。
- 読み手: この repo で実装する人と AI。用語は `docs/business-logic.md`・`docs/stall_escape.md` §3 に合わせる。

## 1. 目的

利用者が触る抽象的な重み（c1=90、high=50 …）をやめ、次の 2 つで「良い勤務表」を決める。

1. **優先順位の段**（数段）＝どの種類の違反を先に減らすか。
2. **段ごとの許容ライン**＝「ここまでなら許せる」を件数・回数の単位で書く。

狙いは 3 つ。

- 重みの数値は意味が読めない（high 50 と c3mn 25 の比が業務で何を意味するか説明できない）。件数のラインなら読める。
- 重み付き和は職員間の公平を保証しない（§3-1）。公平は「最も不利な人から順に比べる」（leximin）で直接扱う。
- HF77（明示の数値指示）を、意味の分かる単位（件・回）で受け取れるようにする。

## 2. 変えない前提

- 必須（HARD）6 族の範囲（`MirrorKeys.hard`＝groupViol・covU・c3n・c3w・pref・extWish）と、HARD を最初に比べること。
- 各族の**数え方**（`UnifiedViolationChecker` が source of truth。c3 の run-deficit、窓マッチ、apt の到達範囲クランプ、D9 など）。
  変えるのは「数えた結果をどう比べるか」だけ。fair は数え方も変える（§4.4、決定事項 Q4）。
- 希望固定・手動固定・上限 0 を置かない（`mayPlace`）・拡張希望の禁止（`mayPlaceAt`）。被覆は同日のみ。
- keep-best（保持した最良を悪化候補で置き換えない）と、比較の単一ソース（`reportComparator`）。比較器の中身が変わるだけで、
  「比較は 1 か所で定義し写さない」は維持（`MirrorCore.kt` のコメントにある取り残しの前科）。
- Kotlin が正、C++ と C# は同値の移植。2 層番兵（C++ 自己整合＋Kotlin `fullEval` 照合）は削らない。
- 片手一本指・ドラッグ不可（段の並べ替えは上下ボタン）。

## 3. 文献の根拠（2026-11-01 調査。本文を読めたものは無く、要旨と抜粋に基づく）

1. **重み付き和が主流で、その問題も指摘されている。** 勤務表の多目的最適化 52 本のレビュー（Böðvarsdóttir & Stidsen, J. Scheduling 2025）では、
   多くが重み付き和で 1 目的へ畳む。重み付き和は公平を保証せず、職員ごとの出来に大差が残りうる（KU Leuven・Portsmouth の公平性研究）。
2. **辞書式（段の順に最適化）。** Boolean multilevel optimization（Argelich・Lynce・Marques-Silva, IJCAI 2009／Ann. Math. AI 2011）:
   段に分けた基準を順に最適化し、巨大な重みで 1 つの和に畳む素朴な方式は効率が悪く大規模に伸びないと報告。MAGI の `SCORE_HARD_UNIT`（1e9）はこの畳み方。
3. **許容ライン付きの辞書式（本設計の土台）。** Rihm & Baumann（J. Scheduling 2018）: 目標からのずれを整数の受容度に写し、受容度の順に辞書式の目標計画法で解く。
   実データで市販ソフトを上回ったと報告。Böðvarsdóttir・Smet・Vanden Berghe・Stidsen（EJOR 2021）: 第 1 段で許容ラインを自動推定し、第 2 段で辞書式の目標計画法を解いて妥協解を出す。
   **推定の具体的な手順は未確認**（プレプリントのホストへ届かなかった。§9 Q8）。
4. **パレート。** Burke・Li・Qu（Ann. OR 2012）は局所探索の評価に優越関係を使う。族 15 個のままでは比べられない案が大半になるので、
   使うなら軸を 3〜4 本にまとめる（多目的最適化の一般的な知見。今回の調査では確かめていない）。
5. **制約の階層。** Borning ら（1992）: 段ごとに比べ方（各制約を個別に見る／段内の和で見る）を選ぶ枠組み。
6. **重みをデータから決める。** 手作りの勤務表で守られている割合から重みを決める（Mihaylov・Smet・Vanden Berghe 2015・2016）、逆最適化（Bärmann ら ICML 2017 ほか）。
   本設計では採らない（§8 の将来）。
7. **探索内部の動的な重み。** 重み付き MaxSAT の局所探索（NuWLS-c／USW-LS、SAT 2024。MaxSAT Evaluation 2023 anytime 部門で全 4 部門 1 位と報告）。
   本設計では**利用者に見せない探索の道具**としてだけ使う（§5.3）。

### 3.1 取り入れる概念と、この設計への写し方（2026-11-01 追補）

本文はどれも読めていない（論文のホストがこのセッションの通信設定で届かない）。検索で得た要旨・抜粋に書かれていることだけを取り入れ、
抜粋に無い細部は「MAGI 側の案」と明記する。

| # | 概念（出典） | 抜粋で確かめた内容 | この設計への写し方 |
|---|---|---|---|
| C1 | 目標と許容ライン（Böðvarsdóttir ら MISTA 2019） | 目標と許容ラインを導入。4 つの目標に順位を付け、一般の許容ラインを 0 に置いてから**事例ごとにアルゴリズムで調整** | §4.2 のライン既定 0 と、§5.4 の事例ごとの提案 |
| C2 | ラインは事例ごと・**職員ごと**（同 EJOR 2021） | 「許容ラインは事例ごとかつ看護師ごと」。違反数が増えるほど受容度が下がる | §4.2a 職員ごとのライン（T1 の族） |
| C3 | 受容度の順に辞書式（Rihm & Baumann 2018） | ずれを整数の受容度に写し、**受容度の低い順に**目的を扱う辞書式の目標計画法 | §4.2b 受容度の段（族ではなく「ずれの大きさ」で段を作る変種） |
| C4 | 現実的な出来の予測（同 EJOR 2021） | 事例ごとのラインの推定で、利用者の入力は最小限に、解の質の現実的な予測も得る。事例研究ではラインからのずれがほぼ無い勤務表を数分で出した | §6 の「この月の見込み」表示 |
| C5 | 段ごとに解いて固定（BMO、Argelich ら 2009） | 段の順に最適化。巨大な重みで和に畳む素朴な方式は効率が悪い | §5.2（既存） |
| C6 | leximax（Cabral・Janota・Manquinho, SAT 2022） | 目的のあいだに優先が無いとき、目的どうしの小さな交換でパレート最適な妥協解を得る基準 | §4.2c 段の中の別案（和ではなく leximax、Q2） |
| C7 | leximin の計算（Bouveret & Lemaître, AI 2009） | 5 つのアルゴリズムを比較し、平均では AtLeast 型が最良、次いで Sort 型。leximin は多重集合の順序の制約として書ける | §4.4 の比較の実装（Sort 型＝並べ替えて比べる）と、将来の厳密化 |
| C8 | 公平を SA で扱う（Mühlenthaler & Wanka, Ann. OR 2016） | max-min の公平は 2 案の順位だけを決め差の大きさを持たないので、SA には**エネルギー差の関数**が要る。3 種を提案して比較 | §4.4a fair の SA 用の差分（3 種の中身は未確認＝MAGI 側の案） |
| C9 | 動的な節の重み（NuWLS, AAAI 2023 とその系譜） | HARD と SOFT で更新の条件を変える。HARD の増分を大きく、SOFT の重みには上限。確率 sp で満たされた節の上乗せ分を減らす（平滑化）。SOFT の初期重みも設定。USW は実行可能な局所最適で全 SOFT の重みを上げる。SPB は増分を 1 から δ>1 倍ずつ増やす（NuWLS 本体の規則と同じかは未確認） | §5.3 の規則 |
| C10 | 優越関係で評価する SA（Burke・Li・Qu, Ann. OR 2012） | 重み付き和の評価と、より多様なパレート集合へ向かう優越関係の評価の 2 つの SA。目的 9 本で非劣解が最大 2000 個になる | 全部のパレートは見せない根拠。§6 の交換の提示は 2〜3 案に絞る |
| C11 | ラインから重みを裏で決める（Behind-the-Scenes Weight Tuning, 2020） | 測れる目標を使って重みを自動で決める | §7.3 の比較の腕（ラインから内部の重みを作る、重み付き和の探索）＝層別化と比べる基準腕 |

## 4. 定義

### 4.1 段（既定案。並びは決定事項 Q1）

| 段 | 名前（画面） | 族 | 現行の重み（参考） |
|---|---|---|---|
| T0 | 必須 | groupViol, covU, c3n, c3w, pref, extWish | 8000〜11000 |
| T1 | 職員ひとりずつ | low, high, c3mn | 120, 50, 25 |
| T2 | 期間・並び・グループ | c1, c3, c3m, c41, c42, c41s, c42s | 90, 15, 6, 9, 9, 10, 10 |
| T3 | 目標と偏り | apt, c2, covO, weekly, fair | 4, 4, 10, 2, 5 |

- 族をどの段に置くかと段の並びは、利用者が変えられる（T0 は固定。§6）。
- 現行の重みの大小と大筋で一致させた（c1=90 は T2 の先頭、covO=10 は T3）。完全には一致しないので、出力は変わる（§7）。

### 4.2 段の中の量

族 f の**量** v_f は、チェッカーが今 `breakdown` に数えている値（件数・回数の amount）をそのまま使う。単位は族ごとの自然な単位（件・回）。

利用者は段 T の各族に**許容ライン** θ_f ≥ 0（整数、既定 0）を書ける。段 T の鍵は次の順に比べる。

1. **超過量** E_T = Σ_{f∈T} max(0, v_f − θ_f)（ラインを超えた分の合計）。
2. **残り** R_T = Σ_{f∈T} min(v_f, θ_f)（ライン内に収まった分の合計）。
3. **族の並び**（段の中で上にある族から v_f を辞書式に比べる）＝同点の最後の決め手。

注意: 1・2 の「合計」は、同じ段の中では 1 件を 1 件として足す＝**段の中では等しい重み 1**。重みを完全に無くすと
「c1 が 1 件減って c3 が 5 件増えた」を比べられない（パレートで比べられない案が残る）ので、全順序にするために明示的にこう決める。
段の中を辞書式にしたい族は、段を分けて上下に並べればよい（例: c1 だけを T2 の上の段へ）。

#### 4.2a 職員ごとのライン（C1・C2）

- T1 の族（low・high・c3mn）は職員ごとに量がある。ラインも職員ごとに持てるようにする: θ_{f,i}（職員 i）。既定は族のライン θ_f と同じ。
- E_T・R_T は職員ごとの max(0, v_{f,i} − θ_{f,i})・min(v_{f,i}, θ_{f,i}) の合計にする。
- 例: 「A さんは上限超過 1 回まで可（本人了承）、ほかの人は 0」。
- low・high の上下限そのもの（staffRange）を書き換えるのとは別物。上下限は「目標」、ラインは「目標からのずれをどこまで許すか」（C1 の目標と許容ラインの区別）。

#### 4.2b 受容度の段（C3、決定事項 Q10）

族ごとに段を決める代わりに、**ずれの大きさごと**に段を作る変種。

- 各族に「受容度の帯」を書く。例: 上限超過 0 件＝十分、1 件＝許せる、2 件以上＝許せない。期間の制約 0〜2 件＝十分、3〜4 件＝許せる、5 件以上＝許せない。
- 比較は「許せない」の件数の合計 → 「許せる」の件数の合計 → 「十分」の中の残り、の順（受容度の低い順に辞書式＝Rihm & Baumann の順序）。
- 族の段（§4.1）より表現力が高い。「c1 が 5 件になるくらいなら上限超過 1 件のほうがよい、ただし 2 件は駄目」を、重みなしで書ける。
- 帯の数は 3（十分・許せる・許せない）に固定する案（画面を簡単に保つため。Rihm & Baumann は 0〜100 の整数）。
- 族の段と併用するときは、段の中で受容度の帯を使う。

#### 4.2c 段の中を leximax で比べる別案（C6、Q2）

- 段の中の族の超過量を、族のライン θ_f で割った**超過率** e_f = max(0, v_f − θ_f) / max(1, θ_f) にする。
- 超過率を大きい順に並べたベクトルを辞書式で比べる（最も超えている族を先に減らす）。
- 「1 件＝1 件で足す」（§4.2 の既定案）と違い、件数の多い族が段を支配しない。代わりに、合計がかなり増えても最悪の族を 1 つ良くする案が勝つことがある。

### 4.3 盤面どうしの比較（新しい `reportComparator`）

```
key(report) = (T0.hardKey, T1.E, T1.R, T1.lex, T2.E, T2.R, T2.lex, T3.E, T3.R, T3.fair, T3.lex, total)
better(a, b) = key(a) <lex key(b)
```

- **T0.hardKey**: 今の第 1 キー（HARD の合計件数）を据え置く。その次は族ベクトルの辞書式（groupViol → covU → c3n=c3w の和 → pref=extWish の和）。
  今の第 2 キー（weightedScore）が HARD どうしを重みで比べている部分を、重みの大小と同じ順の辞書式に置き換える。
  3.573.0 の問題（covU を 1 件減らして c3n を 1 件増やしても合計が同じなら改善と見なされうる）は、合計が同じなら族の順で比べるので、covU の減少が採られる。
  これは今の weightedScore（covU 10000 > c3n 9000）と同じ向き。
- **total** は最後の決め手として据え置く（今の第 3 キー）。
- 鍵はベクトルで持ち、Long 1 本へ畳まない（§5.2）。

### 4.4 公平（fair）を leximin にする（決定事項 Q4）

今の fair は L1 偏差（達成率モード v2、基準は重み付き中央値＝L-07）。これを次へ替える。

- グループ×担当シフトごとに、各職員の偏差（今の `round(|達成率−基準|×幅)`）を大きい順に並べたベクトルを作る。
- 2 つの盤面を、全グループのベクトルをつないで大きい順に並べ直したものの辞書式で比べる（最も不利な人の偏差が小さい方が良い。同じなら 2 番目、…）。
- T3 の鍵では `T3.fair` として E・R の後に置く（fair の超過量・残りには、今の L1 の値を v_fair として入れる）。
- 比較の実装は「並べ替えて比べる」（Bouveret & Lemaître の Sort 型に当たる。C7）。グループ×シフトの人数は 30 名以下なので、並べ替えの費用は小さい。
- 基準（中央値の定義）を変えるかは別問題（L-07 は「設計どおり」と記録済み、`docs/safety_inventory.md`）。leximin は基準の選び方への感度が L1 より小さい見込みだが、未測定。

#### 4.4a fair の SA 用の差分（C8。中身は MAGI 側の案）

leximin は 2 案の順位を決めるが、差の大きさを持たない。SA の受理確率 e^{−Δ/T} には大きさが要る（Mühlenthaler & Wanka の指摘）。
論文の 3 種の定義は読めていないので、次の 3 案を P2 で測る。

1. **最初に違う位置の差**: 並べた 2 つのベクトルで最初に違う位置 k の差 d_k。小さく単純だが、下位の改善を見ない。
2. **べき和**: Σ d^p（p は 2〜4）。p が大きいほど leximin に近づく（KU Leuven の公平性の研究ロードマップにある「勤務時間を 2 乗する」型の重みと同系）。
3. **今の L1 偏差のまま**: SA の受理には今の L1 を使い、採否（keep-best）だけ leximin。探索の動学は今と同じで、判定だけ変わる。

最初は 3（出力の変化が判定だけに限られ、切り分けやすい）。

### 4.5 族単独の到達値（理想点）

段 T の族 f について、上の段を今の最良の値に固定したまま f だけを最小化した値 v_f* を**到達値**と呼ぶ（ヒューリスティックで求める上界。証明された下限ではない）。
許容ラインの提案（§5.4）と、段どうしのぶつかり方の表示（§6）に使う。

## 5. 探索での扱い

### 5.1 原則

- 最終判定・keep-best・後処理の採否・画面の変更案（`FixApplyGate`）は、すべて §4.3 の比較器だけを使う。
- 探索の内部（SA・LAHC・ALNS の受理、修復の候補選び、研磨の見積り）は 1 本の数で差分を比べる必要があるので、§5.2・§5.3 の方法を使う。
  内部の数は利用者に見せず、最終判定にも使わない。

### 5.2 段の順に解く（層別化、BMO 型）

1. 段 T0 を最小化する（今の探索そのまま。HARD の扱いは今と同じ）。
2. 段 T1 を最小化する。受理の条件は「T0 の鍵を悪くしない」（ハードな拒否）。差分は T1 の (E, R) だけを 1 本の数にした値。
3. T2、T3 と順に同じことをする。
4. 各段の持ち時間は全体の予算から配る（配分は決定事項 Q6。初期案は残り時間を段の数で等分し、早く止まった段の余りを次へ回す）。
5. 段の中では (E, R) を `E × M + R`（M = Σ_{f∈T} θ_f ＋ 1。R は定義から 0〜Σθ_f に収まる）で 1 本の数にする。
   これは**近似ではなく (E, R) の辞書式と完全に一致する**（族の並びの決め手は 1 本の数に入れず、採否の比較器だけで見る）。
   段をまたいで 1 本に畳むと上界の積が Long を超えうるので畳まない。

今の探索との違い: 今は 1e9×HARD＋重み付き SOFT を 1 本で最小化しているので、T1〜T3 を同時に動かせる。層別化では、後の段の改善に前の段の悪化を使えない。
これは辞書式の定義そのもので、「T1 を 1 件悪くして T2 を 10 件良くする」は新しい比較器では悪化になる。

### 5.3 段の中の動的な重み（探索の道具、利用者には見せない）

- 段の中で、局所最適に詰まったとき残っている違反の係数を上げ、時間とともに戻す（NuWLS 型の節の重み付け）。受理の判定にだけ使う。
- 規則（C9 の抜粋に沿う。数値は P2 で決める）:
  1. 局所最適（改善する手が無い）に着いたら、確率 1−sp で、残っている違反の係数を上げる。今の段より上の段の違反（T0 を含む）には大きい増分 h_inc、今の段の違反には小さい増分 s_inc。
  2. 確率 sp では逆に、満たされている違反の上乗せ分を 1 段ずつ下げる（平滑化）。
  3. 今の段の係数には上限（初期値の数倍）を置く。上の段の係数には上限を置かない（上の段を悪くしない規則を探索の中でも強める）。
  4. 今の段の違反がライン内に収まった局所最適（USW の「実行可能な局所最適」に当たる）では、今の段の係数を一斉に 1 段上げる。
  5. 増分は固定でなく、局所最適が続くたびに δ>1 倍に増やす変種も測る（SPB 型）。
- 係数の上限は元の 1 件＝1 の数倍に抑え、段の中の意味（§4.2）から外れないようにする。
- 構造的に解けない違反（covU の構造床 `structuralHardFloor`、希望どうしの衝突 `wishSelfConflicts`、証明相当の c3n 壁）は係数を凍結する（際限なく膨らむのを防ぐ）。
- 既存の GLS（`GlsPenalty`、割り当て単位・深刻さ 1.0 固定・セルを持つ違反だけ）とは別物。置き換えるか併用するかは測ってから（§7）。

### 5.4 許容ラインの初期値（Böðvarsdóttir 型、手順の細部は Q8）

- 第 1 段: 実データで各族の到達値 v_f*（§4.5）を求める。
- 第 2 段: 提案値 θ_f = v_f* ＋ 余裕（余裕は利用者が決める。既定 0）を画面に出し、利用者が確定する。確定した値は HF77 の明示数値指示として扱う。
- 自動で確定はしない（HF77）。

### 5.5 研磨・修復の重複実装

`PenaltyWeights` を掛けている 4 ファイル（`DestroyRepairMarginalCost`・`RangePolish`・`C1TemporalFlowPolish`・`DayAssignmentPolish`）と `AptFairPolish`
は、候補選びに重み付きの見積りを使っている。新方式では「今の段の (E, R) の差分」と「上の段を悪くしないか」の 2 つに置き換える。
`PolishGate.aptFairSoftTolerance`（apt/fair 研磨限定の採用例外）は、新方式では T3 の中の比較に吸収されるので撤去の候補（Q7）。

## 6. 画面

- 設定の「直す優先順位」（`WeightTableCard`）を、段の編集に置き換える。段ごとに族の行を並べ、行に許容ラインの数と［▲］［▼］（段の移動）。T0 は表示だけ。
- 各族の行に「今の盤面の件数／到達値／ライン」を並べる（例:「期間の制約 3 件（ここまで減らせる 1 件・ライン 2 件）」）。
- **この月の見込み**（C4）: 作成の前に、到達値（§4.5）から段ごとの見込みを出す。例:「この月は、期間の制約が 1 件くらいまで減らせる見込みです」。
  作成のあとは見込みとの差を出す（見込みどおり／見込みより N 件多い）。見込みは推定なので「見込み」と書き、保証と言わない。
- 結果カードは段ごとに「ライン内／超過 N」を出す。今の「重み N」表記（`ChangeSummary` の改善・悪化の行）は件数表記にする。
- 交換の提示: ある段のラインを 1 ゆるめた案を背景で作り、「上限超過を 1 件許すと、期間の制約が 3 件減ります」と並べる（採否は利用者）。
- 表示の強さ（`heavySoftFamilies`、`ShiftAppearance.severityFromVioKey`）は、重みの大小ではなく段の位置で決める（T0＝必須の色、T1＝注意の強、以下は弱）。
- 文言は「グループ」で統一、「重み」という語は画面から消す。

## 7. 影響範囲・移行・測定

### 7.1 コード（実在を確認した識別子）

| 範囲 | 対象 | 変えること |
|---|---|---|
| 比較器 | `MirrorCore.kt` の `reportComparator`・`compareReportKeys`・`betterReport` | §4.3 の鍵 |
| 報告 | `ViolationReport`（`breakdown` は今のまま） | 段の鍵を持つ（weightedScore は旧方式のために残す） |
| 評価器 | `Evaluator.fullEvalParts`・`DeltaEvaluator`・C++ `magi_native.cpp`（評価器と `SaChunk`） | 段ごとの (E, R) と fair の leximin を出す。番兵は段の鍵で照合 |
| 探索 | `SaOptimizer`・`AlnsSearch`・`RsiSearch`・`PortfolioSearch`・`SearchPolish` | §5.2 の層別化と §5.3 |
| 研磨 | §5.5 の 5 ファイル、`V6HotfixPasses` 配下 | 採否を新しい比較器へ（ほとんどは `betterReport` 経由で自動） |
| 採否・表示 | `FixApplyGate`・`ChangeSummary`・`heavySoftFamilies`・`severityFromVioKey`・`WeightTableCard` | §6 |
| 期待値 | `ObjectiveParityTest`、言語跨ぎ期待値（`*_eval_expected.txt`、C# 側のミラーも） | 段の鍵の期待値を足す |
| データ | 勤務表の JSON | 段の割り当て・許容ライン・方式（旧／新）の 3 項目を足す（**今は存在しない項目**。`docs/data-models.md` に足すのは実装の版で） |
| C# | `-MAGI_PC` の同じ箇所 | 同日に同期 |

### 7.2 移行

- 方式を「重み（今）」と「優先順位」の選択式にし、既定は「重み」のまま始める（P3 まで）。旧方式のデータは読み込みで「重み」になる。
- 「優先順位」への既定の切り替えは、§7.3 の測定と利用者の見比べのあと、利用者の決定で。

### 7.3 測り方と受け入れ基準

weighted score は新方式では意味を失うので、新旧の比較には使えない。次で測る。

- **出力を変えない測定（P0）**: 実データ 6 fixture で、今の出力の段ごとの鍵と、各族の到達値 v_f* を表にする。段どうしのぶつかり方（ある族を到達値まで下げると他の族がどれだけ増えるか）を見る。
- **同じ比較器での 2 腕比較（P2）**: 新方式の探索（層別化＋§5.3）と、今の探索の出力を**新しい比較器で**比べる。決定的後処理の 2 腕比較と、多 seed（120 秒・20 対以上、`tools/loop/ho_stats.py` の 95% 区間）。
  事前基準: 新方式が 6 fixture 中 4 件以上で良く、T0 の悪化 0、所要時間の増加が予算の 10% 以内。
  腕は 3 つ: ①今の探索（今の重み）、②ラインから内部の重みを作った重み付き和の探索（C11 の型。辞書式を近似する重みは段の上界から作る）、
  ③層別化＋§5.3。どれも出力を**新しい比較器で**採点する。②より③が良くなければ、層別化は入れず②で済ませる（作りが小さい）。
- **利用者の見比べ（P3）**: 同じデータの新旧の勤務表を並べ、どちらを配りたいかを利用者が選ぶ。新旧で好みが割れる族があれば、段の割り当てかラインを直す。
- 探索の動学の変更（§5.2・§5.3）は既存の規則どおり測ってから採否。否決済みの案（`docs/stall_escape.md` §11）と同型のものは入れない。

## 8. 段階計画

| 段階 | 内容 | 出力 |
|---|---|---|
| P0 | 測定のみ: 今の出力の段の鍵、族の到達値、ぶつかり方の表（ツールは `tools/loop/` に） | 不変 |
| P1 | 新しい比較器を並行して計算しログに出す（採否には使わない）。fair の leximin も並行計算 | 不変 |
| P2 | 探索の層別化と段の中の動的な重み（既定 OFF のフラグ）。§7.3 の 2 腕比較 | 既定は不変 |
| P3 | 画面（段の編集・ライン・段ごとの結果）と JSON の 3 項目。方式の選択式（既定は重み） | 利用者が選んだときだけ変わる |
| P4 | ラインの提案（到達値）と交換の提示 | 同上 |
| P5 | C# 同期（各段階と同日）、利用者の決定で既定を切り替え、旧方式の撤去を判断 | 決定後に変わる |
| 将来 | 手直しの記録から段・ラインを提案する（§3-6 の逆最適化。提案に留め、確定は利用者） | — |

## 9. 決定事項（利用者の判断が要るもの）

- Q1 段の並びと族の割り当て（§4.1 の案でよいか。特に c3mn を T1、covO を T3 に置く点）。
- Q2 段の中を「1 件＝1 件で足す」（§4.2）でよいか。辞書式にしたい族はあるか。
- Q3 T0 の中を族の辞書式（groupViol → covU → c3n・c3w → pref・extWish）にしてよいか（今の weightedScore と同じ向き）。
- Q4 fair を leximin にするか（§4.4）。基準（中央値）の定義は今のままでよいか。
- Q5 許容ラインの既定を 0 にするか、到達値（§5.4）にするか。
- Q6 段ごとの時間配分（§5.2 の 4）。
- Q7 `aptFairSoftTolerance` を新方式で撤去してよいか。
- Q8 Böðvarsdóttir ら（EJOR 2021）の本文を読んで §5.4 の手順を確かめる（セッションの通信設定で `people.cs.kuleuven.be` を許可するか、本文を貼る）。
- Q9 既定を「優先順位」へ切り替える条件（§7.3 の見比べのあと）。
- Q10 族の段（§4.1）に加えて、受容度の帯（§4.2b、十分・許せる・許せないの 3 帯）を持つか。
- Q11 職員ごとのライン（§4.2a）を持つか。持つなら対象は T1 の族だけでよいか。
- Q12 作成前の「この月の見込み」（§6）を出すか。

## 10. 参考文献

- Böðvarsdóttir, Stidsen. A review of multi-objective optimization methods for personnel rostering problems. J. Scheduling (2025). https://link.springer.com/article/10.1007/s10951-025-00845-0
- Rihm, Baumann. Staff assignment with lexicographically ordered acceptance levels. J. Scheduling 21 (2018) 167–189. https://link.springer.com/article/10.1007/s10951-017-0525-1
- Böðvarsdóttir, Smet, Vanden Berghe, Stidsen. Achieving compromise solutions in nurse rostering by using automatically estimated acceptance thresholds. EJOR 292(3) (2021) 980–995. https://www.sciencedirect.com/science/article/abs/pii/S0377221720309632
- Argelich, Lynce, Marques-Silva. On Solving Boolean Multilevel Optimization Problems. IJCAI 2009. https://arxiv.org/pdf/0904.0019
- Marques-Silva, Argelich, Graça, Lynce. Boolean lexicographic optimization: algorithms & applications. Ann. Math. AI (2011). https://www.doi.org/10.1007/S10472-011-9233-2
- Burke, Li, Qu. Pareto-based optimization for multi-objective nurse scheduling. Ann. OR 196 (2012). https://people.cs.nott.ac.uk/pszrq/files/AOR09.pdf
- A study on fairness objectives for nurse rostering (KU Leuven). https://lirias.kuleuven.be/retrieve/176620
- Investigation of fairness measures for nurse rostering (Portsmouth). https://researchportal.port.ac.uk/en/publications/investigation-of-fairness-measures-for-nurse-rostering/
- Borning ら. Constraint hierarchies（解説: Barták, Guide to Constraint Programming）. https://kti.mff.cuni.cz/~bartak/constraints/hierarchies.html
- Mihaylov, Smet, Vanden Berghe. 手作り勤務表から重みを導く（ORBEL 27 要旨）. https://www.orbel.be/orbel27/pdf/abstract283.pdf
- Bärmann, Pokutta, Schneider. Emulating the Expert: Inverse Optimization through Online Learning. ICML 2017. http://proceedings.mlr.press/v70/barmann17a/barmann17a.pdf
- Böðvarsdóttir, Smet, Vanden Berghe, Stidsen. A modeling methodology to support nurse rostering practitioners. MISTA 2019. https://lirias.kuleuven.be/retrieve/0f9bb0f9-1b99-453a-a71a-7db8f4eb4f31
- Behind-the-Scenes Weight Tuning for applied nurse rostering (2020). https://www.sciencedirect.com/science/article/abs/pii/S221169232030045X
- Cabral, Janota, Manquinho. SAT-Based Leximax Optimisation Algorithms. SAT 2022. https://drops.dagstuhl.de/opus/volltexte/2022/16703/
- Bouveret, Lemaître. Computing leximin-optimal solutions in constraint networks. Artificial Intelligence 173(2) (2009) 343–364（IJCAI 2007 版 https://ijcai.org/Proceedings/07/Papers/008.pdf）
- Mühlenthaler, Wanka. Fairness in academic course timetabling. Ann. OR 239 (2016) 171–188（PATAT 2012 版 https://patatconference.org/patat2012/proceedings/2_8.pdf）
- Chu, Cai, Luo. NuWLS: Improving Local Search for (Weighted) Partial MaxSAT by New Weighting Techniques. AAAI 2023. https://ojs.aaai.org/index.php/AAAI/article/view/25505/25277
- Rethinking the Soft Conflict Pseudo Boolean Constraint on MaxSAT Local Search Solvers（SPB-MaxSAT）. IJCAI 2024. https://ijcai.org/proceedings/2024/220
- Enhancing MaxSAT Local Search via a Unified Soft Clause Weighting Scheme. SAT 2024. https://drops.dagstuhl.de/storage/00lipics/lipics-vol305-sat2024/LIPIcs.SAT.2024.8/LIPIcs.SAT.2024.8.pdf
