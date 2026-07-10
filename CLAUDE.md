# CLAUDE.md — MAGI ShiftOptimizer (Android) 引き継ぎ

> このファイルは Claude Code 向けのプロジェクトメモリです。チャット側で進めた作業の引き継ぎを兼ねます。

> **まず読む（ドキュメント入口）**：設計・仕様・業務ルールは [`README.md`](./README.md) の「ドキュメント目次」から各 `docs/*.md` に分かれています。実装・調査の前にそこで当たりをつけてください。とくに **業務ルール＝[`docs/business-logic.md`](./docs/business-logic.md)**、**データ項目＝[`docs/data-models.md`](./docs/data-models.md)**（存在しない項目を創作しない）。
> **更新ルール（stale 化させない）**：コードを改修したら、影響する文書（特に `business-logic.md` / `data-models.md`）と `README.md` の目次・最終更新を**同じコミットで**更新する。事実が変わりやすい順に独立させているのは、ここを最新に保つだけでハルシネーションの大半を抑えるため。
> 応答は簡潔・結論先出し・日本語。コード識別子は英語のまま。

## プロジェクト概要
看護師/スタッフのシフト表を最適化する Android ネイティブアプリ（Kotlin + Jetpack Compose）。
VBA/Web 版から移植した「MAGI V6」最適化エンジン（SA + ALNS + Tabu + GLS + LNS + VNS + LAHC +
PathRelinking + ChainSwap + 適応的オペレータ重み + RSI++ 等）を内蔵。

- パッケージ/applicationId: `com.magi.app`（namespace も同じ）
- minSdk=35 (Android 15+), compileSdk/targetSdk=36, java.time ネイティブ可, NDK/desugaring 不使用
- リポジトリ: `ichirocc/magi7ichiro`（public）
- UI 制約: **片手一本指**（ドラッグ不可）、**最小デザイン**（冗長な安全表示はエンジン側に持たせ、操作画面は効率優先）
- 全作業・UI 文言は日本語
- **デザイン憲法＝[`docs/DESIGN.md`](./docs/DESIGN.md)**（melta-ui 流の AI-Ready 設計）。トークン一次ソース＝`MainActivity.MagiTheme`
  （色/タイポ/角丸）＋`MagiTokens.kt`（意味色/シフト色/余白）。禁止事項 P1-P4 は `tools/design_lint.py` で機械検査。
  色/角丸/影を変えるときは DESIGN.md の原則（純黒不使用・重い影不使用・任意値禁止・スコア不変）に従う。

## ビルド/検証（重要）
**このサンドボックスは Android も素の Kotlin もコンパイル不可。** Kotlin の検証は GitHub Actions
"Release Build" ワークフロー（`gradle assembleRelease`）でのみ行う。lint は走らない/警告errorなし。
Claude Code 環境に Android SDK があれば直接 `./gradlew assembleRelease` でビルド可。無ければ CI を使う。

- **アルゴリズム検証は python3** で行う（後述の検証ハーネス）。コンパイル不可でもロジックはPythonで等価確認できる。
- CI ログ本体は results-receiver.actions.githubusercontent.com 上にあり取得不可。**コンパイルエラーは
  目視＋静的チェック（波括弧balance・フィールド名照合）で発見**する。`view`/`grep` を駆使。
- CI 監視 API: `api.github.com/repos/ichirocc/magi7ichiro/actions/runs`（name=='Release Build',
  head_branch でフィルタ）。status は `/actions/runs/{id}`、artifacts は `/actions/runs/{id}/artifacts`、
  失敗stepは `/actions/runs/{id}/jobs`。ビルド ~4-5分 → debug-key APK ~10.9MB。
- 変更ごとに versionCode++ と versionName 更新（`app/build.gradle.kts`）。タグ `vX.Y.Z-...` を push。

## アーキテクチャ（主要ファイル）
エンジンは `app/src/main/java/com/magi/app/v6/`:
- `MirrorCore.kt` — **`UnifiedViolationChecker`（UIの違反表示・提案の基準＝source of truth）**。
  `check(state, schedule) -> ViolationReport{violations, needViolations, countViolations, breakdown, hard, total, weightedScore}`。
  `Problem`（`cachedProblem(state)`）, `canDo(i,k)`, `allowedShiftsForStaff(i)`, `countMatrix`, `coverage`,
  `normalizeSchedule`。`MirrorKeys`（hard/soft/all のキー分割）と weightedScore の重み定義もここ。
- `Evaluator.kt` / `DeltaEvaluator.kt` — **最適化器の目的関数**（SA の受理判定）。`Evaluator(p, c3RunMode=true)`。
  Delta は差分評価。`SaOptimizer` が Delta×Full の整合チェック（安全網）を行うため**両者は常に一致させる**。
- `C3Run.kt` — `isSingleShiftSeq(seq)`, `rowDeficit(a,i,k,L)`（単一シフト連の不足評価）。
- `V6FinalPort.kt` — `handleOptimize`（最適化オーケストレーション）, `handleCheck`（UnifiedViolationChecker）。
  最終番兵 `checkResultWorse`（入力より悪化したら入力へ復帰）。
- `V6NativeOptimizer.kt`/`V6HotfixPasses.kt`/`V6LateOperators.kt`/`V6SearchOperators.kt` — 探索本体・各オペレータ。
- `V6SwapSuggester.kt` — **`FixSuggester.suggest(...)`**（ユーザー向け修復提案。7種の手を探索）。
- `Problem.kt` — `C1(day1,shiftIdx,day2)` 等の制約データ型。

UI は `app/src/main/java/com/magi/app/ui/`:
- `MagiApp.kt` — タブ: 0=ようす(ダッシュボード), 1=勤務表(編集+集計), 2=設定, 3=詳細, else=外観/データ。
- `MagiViewModel.kt` — 状態管理。`findFixSuggestions`/`applyFixSuggestion`、`refreshCheck`(currentSchedule検査)。
  ジョブ: `job`/`checkJob`/`fixJob`（連続タップ競合回避）。
- `MagiUiState.kt` — `schedule`, `staffNames`, `staffGroupSymbols`, `shiftSymbols`, `countViolations("i,k")`,
  `needViolations("k,j")`, `resultSchedule`, `breakdown` 等。
- `MagiScheduleViews.kt` — `ScheduleGrid`, `StaffCalendarCard`, **`TallyCard`（シフト集計：職員別/日別＋違反ハイライト）**。
- `MagiDashboardCards.kt` — `BreakdownCard`, `FixSuggestionCard` 等。`MagiTokens.kt` — `MagiAccent`(色)。

## 制約ファミリーと意味（confirmed）
- **c1**（窓制約, SOFT, 重み4）: `C1(day1=窓, shiftIdx=単一シフト, day2=最低数)`。窓day1内にshiftIdxがday2回以上。
  **担当不可スタッフは対象外（canDoガード）**。構造上単一シフトのみ（複数種類変種なし）。
- **c2**（職員別合計, SOFT, 重み1）。
- **c3族**（ws4の列パターン。ws3=希望シフトとは別物）:
  - c3 = MUST/want（SOFT, 重み3）, c3m = Want（SOFT, 重み2）— **非forbidden**。
  - c3n = FORBIDDEN（HARD, 重み7000）, c3mn = Hate（SOFT, 重み12）— **forbidden**。
  - 評価モデル: **非forbiddenの単一シフト連 → run-deficit**（C3Run.rowDeficit。完成runを罰しない）。
    それ以外（複数シフト連 / forbidden）→ **窓マッチ #fire**。
- **c41/c42/c41s/c42s**（群/日 範囲・スキル群変種, SOFT, 重み1）。
- **covU**（人員不足, HARD, 重み8000）/ **covO**（人員過剰, SOFT, 重み0.5）。被覆は同日のみ（夜勤繰越なし）。
  need1=P1, need2=P2。lo=need1, hi=(use2 && need2>=0 ? need2 : need1)。MIN/OR条件は2世代前からの意図的設計。
- **low/high**（staffRange=各職員の各シフト回数の下限/上限, SOFT, 重み90/45。amount計上）。
- **apt**（適切回数=`groupShiftApt[群][シフト]` の**群単位双方向目標**, SOFT, 重み1, L1偏差`|回数-目標|`）。
  担当可シフトのみ有効（`Problem.apt` 構築時に bucket=canDo ガード）。不足=赤(vio-aptLow)/超過=橙(vio-aptHigh)。
  **群目標は個人の staffRange[lo,hi] でクランプ**（範囲外の群目標は到達不能＝解消不能な幻のapt違反を防ぐ。
  例: Dﾃを2-2固定の職員に群目標10 → 2にクランプ）。低/高(staffRange) とは別系統（LimMin/LimMax は別画面 ws5）。
  旧: 目的関数にもチェッカーにも未統合だった。
- **fair**（グループ内公平化, SOFT, 重み1, L1偏差）。群×担当ONシフト(`bucket[g]`)ごとに、メンバー回数の
  `round(平均)` からの L1 偏差和。同群の職員間で各シフト回数を均す。`Problem.groupMembers` 使用、m<2の群は対象外。
  目的関数(Evaluator/Delta)/チェッカー3者に統合。UI内訳チップには出さない（常時非ゼロになりやすいため weightedScore/total
  のみ算入）。旧: 後処理polish(`applyGroupShiftEqualizePolish`)＝目的関数外の「整え」だけだった。
- **weekly**（7日周期(曜日)シフト平準化, SOFT, 重み1, L1偏差）。職員ごとに勤務日(非休)の**曜日別カウント**の
  `round(勤務日数/7)` からの L1 偏差和。weekday(j)=`(dow0+j)%7`（`Problem.dow0`=startDate曜日オフセット %7 /
  `Problem.restIdx`=休index）。「毎週おなじ曜日に偏る」を均す。共通ソース=`weeklyDevOfBucket(wd[7])`。
  Evaluator/Delta/チェッカー3者に統合（fairと同型）。UI内訳では fair と同様に「曜日の偏り」チップに件数表示（場所マップ
  は無し＝タップ先は空、fairと同じ）。旧: 後処理polish(`applyWeeklyEqualizePolish`, 分散指標)だけ＝目的関数外の「整え」
  だった（polishは keep-best/mainNotWorse ガードのため併存＝無害・冗長）。
- **pref**（希望シフト未充足, HARD, 重み9000）/ **groupViol**（群外シフト, HARD, 重み10000）。

weightedScore 階層: groupViol(10000) > pref(9000) > covU(8000) > c3n(7000) > low(90) > high(45) >
c3mn(12) > c1(4) > c3(3) > c3m(2) > c2/c41/c42/c41s/c42s/apt/fair/weekly(1) > covO(0.5)。

> **決定記録（D3, 業務判断）**: apt/weekly/fair の重みは**現状維持（各1）で確定**（業務担当者レビュー済）。
> ws8/ws9 等と同様、以後この3族の重み変更は**再提案しない**（明示的な数値指示があった場合のみ変更）。

## 目的関数の統一（完了。最重要の達成事項）
**最適化器（Evaluator/Delta）とUI/提案（UnifiedViolationChecker）が乖離していた目的関数を統一した。**
原則: **チェッカーを source of truth とし、より正確なモデルへ両者を寄せる**。重みは線形集約点で適用し
Δ×フル整合を維持（`soft = sc*W + ..., sc += dC ⇒ soft_new = soft_old + dC*W`）。

| 乖離 | 解消 | コミット |
|---|---|---|
| (a) covO 最適化器で無罰則 | 最適化器 soft に追加 | 2.28.0 |
| (b) range(low/high) 最適化器が表示HARD | 最適化器 soft化＋重み90/45 | 2.28.0 |
| (c) c3/c3m 単一シフト連: 窓 vs run-deficit（方向相違） | チェッカーも run-deficit化 | 2.31.0 |
| (c) c3/c3m 複数シフト連: +D vs +1, フラット vs 重み | #fire＋重み3/2/12 | 2.32.0 |
| c3n/c3mn | forbidden は両側窓マッチ#fire・c3mn×12 で既に一致 | (2.32.0で副次的) |
| (a) c1: canDoガード無/+d1/フラット | canDoガード＋#fire＋重み4 | 2.35.0 |
| apt(適切回数): 目的関数/チェッカー双方に未統合（事実上死に機能） | 3者にL1偏差×1で統合・双方向目標・違反表示 | 2.36.0 |
| fair(グループ内公平化): 後処理polishのみ＝目的関数外の「整え」 | 3者にL1偏差×1で統合（群×シフトの round(平均) 偏差） | 2.37.0 |
| c41s/c42s: 違反は研磨・検出済みだが内訳UIに列挙漏れ | breakdownLabels/BreakdownGroupに追加し表示 | 2.37.0 |
| weekly(7日周期(曜日)平準化): 後処理polish(分散)のみ＝目的関数外の「整え」 | 3者にL1偏差×1で統合（職員×曜日の round(勤務日/7) 偏差、`weeklyDevOfBucket`共通ソース） | 3.72.0 |

検証はすべて Python で「最適化器の族寄与 == チェッカー weightedScore 寄与」「soft<<1e6（hardゲート安全）」
「Δ==フル」を確認済み。

## ユーザー向け機能（実装済み）
- **FixSuggester**（`V6SwapSuggester.kt`）: 7種の手（単一変更/同日交換/複数変更/連鎖/再最適化窓/3人交換/別日交換）を
  deadline内で探索、(deltaHard, deltaTotal, deltaWeighted) でランク、kind+ops署名で重複排除。
  UI: `FixSuggestionCard`（kindチップ＋差分＋適用ボタン）、`BreakdownCard` から「直し方を探す」。
- **TallyCard**（`MagiScheduleViews.kt`、タブ1の勤務表表下）: 職員別（職員×シフト回数）と日別（シフト×日 人数）。
  **違反ハイライト**: 職員別=countViolations(vio-low赤/vio-high橙)、日別=needViolations(vio-covU赤/vio-covO橙)。
  ~~注: 読取モードで `gridUi.schedule=resultSchedule` に差し替わるため、編集後に読取へ切替えた場合のみ
  集計値と違反マップがズレ得る~~ **→ 3.96.0 で解消（backlog#1 完了）**: UiState に result専用マップ
  (`resultViolationCells/resultNeedViolations/resultCountViolations`, null=未計算→現行へフォールバック) を追加。
  `makeUi` が `schedule.contentDeepEquals(resultSchedule)` の検査時に report から一元充填（resultSchedule 更新サイトは
  全て makeUi(schedule==result, 対応report) を通ることを確認済）。`commitEditingToResult` は現行マップを引き継ぎ
  （refreshCheck 進行中でも完了時 makeUi が自己修復）。読取モードの gridUi は schedule と3マップを同時に差し替え。
  表示のみ・スコアリング不変。

## 業務ルール（厳守）
- **HF77**: パラメータ/重み/データ変更は**業務担当者の明示数値指示＋1件ずつ**のみ。コメントの主張と実装を必ず
  grep で照合。ただし「賢く統一/改善する」等の明示指示は、目的関数統一における重み変更の承認とみなす。
- ws8/ws9 新規シート, Cells()→CodeName 移行, staffタイプ自動判別, LimMax自動設定 = **すべて実装不要**（再提案しない）。
- VBA配布物(.bas)は SJIS(CP932)+CRLF のみ。Unicode は ASCII 代替、コメント矢印は `->`（→不可）。
- 被覆は同日のみ。MIN/OR は意図的設計。covU 構造的不足-2（供給153 vs 需要155）は確定事項。

## 検証ハーネス（Python）
`/tmp/cellfix.py`（サンドボックス内）が state を読み `sched, names, sym, S, T, K, canDo, locked` と
`violations(sc)`（covU/covO/low/high）を提供。`exec(open('/tmp/cellfix.py').read().split('base=violations')[0])`
で再利用。state JSON（`/mnt/user-data/uploads/magi_state_*.json`）: 10職員/31日/12シフト/2026-07。
シフト index: 0:休 1:Pｼ 2:Dﾃ 3:A4 4:Aｱ 5:Pﾅ 6:Cｵ 7:Cｱ 8:B4 9:有 10:Cｳ 11:B1。
cons1=[5日窓休≥1, 14日窓休≥4, 14日窓Dﾃ≥2]。桒澤美幸・大島愛はDﾃ不可。

## 回数設定UIの改善（進行中）
回数設定が複数画面に分散し紛らわしい（#1必要人数/#3グループ別c41(=1日の人数)/#5適切回数apt/#6個人別low-high/#7合計c2/#8連続窓c1）ため、
「⬇️1日の人数 / ➡️1人が月に何回」の軸でレイアウト統合する方針（ユーザー承認済み）。段階実装:
- 第1段(2.42.0, 完了): `StaffRangeCard`(個人別の回数)に**現状回数「今◯」＋過不足色**(🔴vio-low/aptLow ・ 🟠vio-high/aptHigh)を追加。
  説明文を「1か月に何回」に明確化。既存UiStateのみ使用(低リスク)。
- 第2段(2.43.0, 完了): 個人別カードに**適切回数(apt)の実効目標を併記**。ViewModel に `staffCountRules()`(=`CountRuleView`)を新設し、
  staffRange または apt が効く職員×シフトを統合一覧。チップ表記「Dﾃ 2–5 目標3 ・今3」、クランプ時「目標10→2」。
  apt のみ(個人別上下限なし)のセルも行に出し(× は出さない=群目標は群単位)、タップで個人別上下限を追加可能。`Problem(st).apt` を実効値の source。
- 第3段(予定・大): 軸ハブ画面で need/c41(1日)と apt/low-high/c2/c1(月) を分離。**`ConstraintsCard` が c41(1日)＋c1/c2(月) を束ねているため、
  カード分割を伴う大規模リファクタ**。c41 の「◯回」表記を「◯人(1日)」へ。要・別途着手。

## 回数設定画面（スマホ最適化・シフト中心, 2.60.0 Stage1-3）
回数設定(apt=理想 / staffRange=個人の最少最大 / cons41=群の最少最大)を1画面に統合。**シフト軸・個人軸の2タブ**
(`CountSettingsCard` = CountSettingsScreen.kt)。設計方針=「どのシフトを見るか」で業務するため**シフト中心**を主軸。
高密度リスト＋アコーディオン＋**インライン編集(画面遷移ゼロ)**＋固定シフトフィルタ(横スクロール)＋検索(シフト/群/職員名)。
- データ層(ViewModel): `shiftRuleBlocks()`(シフト→群行 最少|理想|最大・個人行 最少|最大) / `staffRuleBlocks()`(職員→シフト行 最少|最大)、
  `setCons41(群,シフト,l,u)`(更新-or-追加, 両空で削除)を新設。編集は ws1SetGroupApt/setStaffRange/setCons41 を直接呼ぶ
  (NumberStepper の +/- が即モデル反映=インライン)。削除はアコーディオン展開時のみ表示。組み合わせ禁止(cons42)は別画面のまま。
- 配置: 年次マスター編集スコープ先頭に CountSettingsCard。既存 Ws1Card/StaffRangeCard/ConstraintsCard は当面併存(安全)。
- 未: 新規行の追加(未設定の群/職員へ限界を新設)・個人タブのシフトフィルタ・"…"メニュー化は次段。
- (2.63.0, 検証で判明した単位混在を修正): **本画面は「回数(月)」軸に純化**。検証で群行の `最少｜理想｜最大` が
  cons41(=群の**1日の人数**, MirrorCore で `for j` 日次カウント `z<l||z>u`)と apt(=1人の**月回数**目標)で**単位混在**と判明。
  ユーザー選択により **cons41 を本画面から除外**(制約画面で編集)し、群=apt月目標のみ「目標 N回」、個人=staffRange「月 最少〜最大回」に。
  併せて ③`shiftRuleBlocks/staffRuleBlocks` を**空ブロック除外**(設定ゼロのシフト/職員の見出しノイズを排除)、④群に「目標は個人の月上下限でクランプ」注記。
  `GroupRule` から min/max/groupKigou を削除。**latent bug: `shiftRuleBlocks` の c41 キー区切りが空白でなく NUL(0x00) だった**のを関数書き換えで除去(内部一貫のため動作はしていた)。

## ドッグフーディング改善（オブジェクト単位, 2.61→）
コンポーネント単位の評価を反映。「見える」は達成済、残りは「直せる」への接続が主レバー。
- (2.61.0, 進捗の見える化): `progressSummary(ui)`(MagiScheduleViews) = 改善N%(initSoft→bestSoft) ・ 残りM:SS ・ X回
  (hard残あり時は ⚠N)。state既存値(initSoft/bestSoft/iters/elapsedMs/budgetSec)のみ・読取専用・スコアリング不変。
  上位バー実行中バッジに改善量併記＋ホーム組立中カード/LiveScheduleCard/操作ログ先頭で共有。
- (2.62.0, 直せる導線): **集計セル(TallyCard)の違反セルをタップ→原因を数字で提示**(職員別=現在/下限/上限/目標、
  日別=現在/必要/適正)＋「直し方を探す」で分析タブの FixSuggester へ遷移(onFix: staffはfocus、日別はnull=全体探索)。
  しきい値は ViewModel `staffCellLimits(i,k)`(rangeLo/rangeHi/apt)・`needCellLimits(k,j)`(need1/need2,use2)で Problem から取得。
  違反内訳カード(BreakdownCard)は既にチップ→場所→onFocusStaff→findFixSuggestions が機能済(2.62で追認)。
  注: TallyCard は gridUi(読取=resultSchedule)の count を使うため、現在値と違反マップのズレ既知事項は継続(backlog#1)。
- 未(ユーザー選択外): 軽い視覚調整(集計の左列固定/説明文ⓘ折りたたみ/やめる・前次ボタン縮小/ロゴ実行中アイコン化)。
- (2.64.0, 全21画面検証→単位・用語の明確化): 並列検証で全画面を engine 意味論と突合(ロジック誤り0・NUL/死にコード0)。実害は表記に集中し、表記のみ修正(ロジック不変):
  A) **cons41/cons41s を「回数」→「1日の人数」**(ConstraintsCard/SkillConstraintsCard の題・行「[l〜u]人/1日」・Addダイアログ題)。engine は `for j` 日次 `z<l||z>u`=1日人数。
  B) need を「必要数1/2」→**「最低人数」「上限人数(2パターン時)」**(Ws1Card 表示/ダイアログ・NeedDayCard 表示/ステッパー)。engine: need1=covU下限, need2=covO上限(use2時)。
  C) CoverageDiagnosisCard「不足N件」→**「不足N人」**(totalShortfall は人数)。
  非対応(検証で誤りでないと確認): SettingIssues の RANGE「…の回数/下限を…」DEMAND「…人に下げる」は既に単位適切=V6SanityPort 不変。
  UX摩擦(希望の日範囲選択・群×シフト表の横スク・満足度尺度・スキル解除・ワーカー用語隠し・集計左列固定)は構造変更を伴い backlog。
- (2.66.0, 群×シフト表の見やすさ): Ws1Card「担当できるシフト(群×シフト)」「適切回数」が横スクロール(12シフトで画面外)だった。
  各群を Row(horizontalScroll) → 群名を行頭＋**FlowRow でチップ折り返し**に。横スクロール不要・群名常時可視。未使用 import 除去。
- (2.68.0, 希望シフトの日付直接入力): 希望追加ダイアログの日が ±ステッパーのみ(1→30で多タップ・スタッフ▼/日± の操作系不統一)
  →直接入力テキストフィールド(1〜maxDay・数字キーボード)＋± に。dayText:String 化で空入力も安全。NeedDayEditor と操作系統一。
- (2.69.0, 計算方式の日本語ラベル): SettingsCard が `${ui.v6Algorithm}`/`alg.name`=生enum(AUTO/V5/ALNS/RSI/RSI_PLUS/PORTFOLIO)を
  表示していた。`v6AlgorithmLabel()` を新設し おまかせ/高速/破壊再構築/違反集中/違反集中＋/並列(複数案) へ。表示のみ・ロジック不変。
- (2.70.0, スキル割当の解除): SkillGroupEditor の職員スキル▼に **「(なし)」(skillIdx=-1)** を追加(どのスキル群にも所属させない)。
  engine は cons41s/cons42s で `ssk[i]==groupIdx(≥0)` ＝ -1 は常に偽=未所属で安全。永続化は素の int 往復(optInt/put・クランプ無)、
  群削除リインデックス(`k>g`)も -1 は不変で安全。既定 skillIdx=0 は変更せず追加のみ。表示は skillIdx<0 で「(なし)」。
  検証で判明し**既に対応済みだった backlog**: ①集計の左列固定=職員名カラムは horizontalScroll の外で既に固定 ②コパイロット文言=
  `topHardFamilyJp`→`hardFamilyJp` で既に日本語(生コード露出なし)。**非対応(意図的)**: 満足度尺度の式変更=表示専用だが業務判断要・保留 /
  EmptyState ボタン順=既存ユーザーは「データを開く」主操作が妥当 / Bottleneck/ようすの説明追加=「説明文は読まれない(④)」原則に反するため見送り。
- (3.75.0, E7 違反フィルタ = 種別トリアージ): 勤務表タブに**制約種別フィルタ**を新設（grilling で要件確定）。18族を作成者の語彙で
  **コース6分類**(人員 covU/covO・希望 pref・連勤 c3系・回数 low/high/apt/c2・群ルール groupViol/c41/c42/c41s/c42s・窓 c1)に束ね、
  **勤務表タブ全面**(グリッドセル/日ヘッダ不足N/Tally職員=回数/Tally日=人員/カレンダーセル)を**1つの共有フィルタ**で絞る。
  複数トグル・初期全ON(引き算)・**件数付きチップ**(breakdown 族合計)でそのまま「まずどれを潰すか」の種別トリアージに。
  実装: `MagiScheduleViews` に `VioBucket`/`vioBuckets`/`familyOfVioClass`(aptLow/aptHigh→apt)/`vioVisible`/`ViolationFilterBar`。
  状態は `MagiApp` 勤務表タブに `rememberSaveable` の Int bitmask(回転/復元で保持)。ScheduleGrid/StaffCalendarCard/TallyCard/
  MagiFlatGrid に `vioEnabled: Set<String>=allVioBucketKeys` を追加し各違反読取を `vioVisible` でゲート。**表示のみ・スコアリング
  不変**(違反自体は不変、表示するかだけ制御)。旧 ScheduleGrid の中途半端な per-family フィルタ(hiddenVio・grid未反映・grid限定)を
  置換。公平/曜日は場所無しでバケツ対象外。E7②(カレンダーへ need/count 追加)は per-staff 面と不整合のためスコープ外(業務選択)。
- (2.67.0, 早期脱出方針): 実機ログで停滞検知が予算上限とほぼ同時(301s)発火＝時間が節約できていなかった。停滞ウォッチドッグ
  (V6FinalPort)の許容を短縮: `minRunMs budget/5→/6(上限60→45s)`, `stallMs budget/4→/6(300s予算 75→50s)`, `stallHardMs budget/6→/8(50→37.5s)`。
  globalBest は生スコア管理＋最後の改善時刻でタイマリセット(フェーズ遷移でもリセット)のため**早期終了でも品質は不変**(無改善時に早く返すだけ)。
- (2.65.0, HF66 残予算ガード): 実機ログで後処理 HF66(職員内再配分)が applied=0 でも 12,999ms を消費し予算超過(301s>300s)→
  「後処理を短縮(残りパス打ち切り)」を誘発していた。原因=HF66 は「1手ごとに全候補をフル check」する高コストパスで
  `shouldStop` を手ごとにしか見ず、締切後も現在手のスキャンを走り切る。対策: ①`runPostOptimization(deadlineMs=hardDeadlineMs)`
  を新設し、HF66 専用上限 `hf66Cap = min(残予算/2, 6s)` を渡す(残り半分を後段研磨へ確保) ②HF66 内に `outOfTime()=shouldStop()||
  now>=deadlineMs` を導入し**内側スキャン(`scan@`ラベル)でも締切確認**して暴走を即中断。keep-best(isBetter採用)のため早期中断
  でも解は退化せず=スコアリング不変・安全。他パスの予算超過打ち切りを防ぐのが狙い。

## 後処理研磨の族カバレッジ（進行中）
後処理(V6HotfixPasses.runPostOptimization)は c1/c3系・low/high・apt・fair は研磨するが、c2・c41/c42・**c41s/c42s**・covO は
専用研磨が無く「評価のみで研磨されない」取りこぼしだった（weightedScoreには算入済）。
- (2.44.0): **c41s/c42s 専用研磨**を新設しフィックスポイント巡回に組込。c41(s) は markNeed で needViolations 側に出て
  群情報が失われ職員セルから拾えないため、アンカーは `Problem.cons41(s)/cons42(s)` から直接算出（違反群の職員を起点）。
  同日2者スワップ(被覆/HARD不変)・`isBetter`採否(keep-best=退化なし)。
- (2.45.0, 完了): 上記を `applyGroupRangePairPolish(skill: Boolean)` に**汎用化(DRY)**し、**c41/c42(通常群)も研磨**（skill=false=sgrp,
  skill=true=ssk）。フィックスポイント巡回に群範囲(c41/c42)＋スキル群(c41s/c42s)の2呼び出しを追加。SoftPolishVerify ログに
  c41/c42/c41s/c42s 増減と採用数(群:n / skill:n)を表示。サンプル最大残違反 c41=124 に直接効く（過拘束分は keep-best で無害）。
- (検証で判明・重要): 同日スワップで直せる単日族(c2/c41/c42/c41s/c42s)は**既存 CyclicSwap が isBetter で total を最小化する過程で既に研磨済**
  （isBetter=hard→total→weighted、total に全 soft 込み）。よって 2.44/2.45 の同日スワップ専用パスは CyclicSwap の部分集合＝
  ほぼ冗長（keep-best で無害・診断ログ充実の効果はあり）。c2 も同様で専用パス不要。covO は HF66(職員内再配分=被覆変更)が
  isBetter で既に扱う。**残る本当の取りこぼしは探索側の focus 漏れだった**。
- (2.46.0, 完了): 探索の `maxViolatedFamily` の order に **c41s/c42s を登録**（従来 c41/c42 のみで RSI が一度もスキル群を
  focus しなかった）。c41s は c41 と同型(1日人数)なので `rsiGenerateHypothesis` の destroyRepairDay 分岐へ、c42s は else
  (destroyRepairViolations)。後処理スワップでは届かない destroy-repair でスキル群を直接攻める。
- (3.74.0, D1/A1 完了): `maxViolatedFamily` を **純・件数最大 → 「解ける HARD 族を件数に関わらず SOFT より先に focus」** へ。
  旧実装は単一の c3n=1 が c1=118 等の高頻度 SOFT に埋もれ RSI が**一度も HARD を狙わない**失敗があった（停滞解析の根本原因）。
  first-pass=order 上の HARD 族(groupViol/covU/pref/c3n)で avoid 外かつ件数>0 の先頭を返す／無ければ従来の非avoid件数最大。
  **hard=0 のとき no-op＝全 soft の一般ケースは不変**。avoid(HF63=構造的に充足困難)の HARD は除外し無駄打ちを避ける
  (解けない HARD は 3.69 hardFloor と同方針で SOFT 研磨へ回す)。目的関数 better() の辞書式(hard<<total<<weighted)に focus を
  整合させる**スコアリング不変**の変更（Evaluator/Delta/Checker/重み 不変）。※探索動学の A/B 原則(nsp_bench)に対しては、
  bench が focus 選択を模擬できない（c3n/c1 制約非実装）ため**実測でなく原理**で採否：hard=0 で不変・HF63 で無駄打ち回避・
  1関数で可逆、という限定的・低リスク設計に依拠。単一呼出(V6NativeOptimizer:607)・テスト非依存。
- (3.94.0, ソフト研磨の網羅的計測 → 候補生成の重み整合): 「ソフト研磨を50%以上に改良」の指示に対し、`tools/nsp_bench.py`
  で **6レバーを網羅 A/B**(golden_state 実データ 24 seeds ＋ 合成 over-constrained)。結論=**現行のソフト研磨は既に構造的下限**:
  - **現行(smart repair day+staff+viol, 2.57-2.59)が既に repair-day 基準比 −54.5%**(golden 24seed 頑健)＝**50%目標は現行実装で達成済**。
  - golden の残差 12.6 は **low/high(重90/45)=0 まで消え、apt(8.8)+covO(3.8, 重み~1)のみ＝構造的**(供給<需要・群apt目標の競合)。
  - 試した in-loop 追加レバーは**すべて不整合か有害**: multiday=golden 微改善だが over-constrained +28%悪化 / worst-removal(日/職員)=
    +162〜375%悪化(過集中で floor を撹拌) / covo-aware=完全中立(不発) / dr比率up(0.35/0.50)=大幅悪化 / in-loop swap=golden +96〜356%悪化
    (貪欲交換が SA を局所最適に固着)。→ **被覆保存スワップは in-loop でなく後処理**が正しい(既存 CyclicSwap が担当済)。
    2.55/2.56/2.57 の「脱出/近傍ヒューリスティクスは中立or有害」教訓を再確認。
  - **採用した唯一の安全・原理的改善**: `applyDayAssignmentPolish` の Hungarian 候補生成 proxy `rangePen` を **3/3 → 90/45**
    (apt=1 は不変)へ整合し目的関数(Evaluator/staffCountPenaltyAt)と一致。keep-best(isBetter@UnifiedViolationChecker)採否のため
    **退化不能=スコアリング不変**。bench では**中立**(単純化 bench の SA が low/high を研磨前に0化するため proxy 差が顕在化しない=
    bench の限界)だが、実機は c1/c3/pref 等の競合族が多く low/high 残差が研磨段へ到達し得る→ 真の目的への proxy 整合が効く見込み。
    HF77 非該当(スコア重みでなく探索内部の候補生成 proxy)。実測ハーネス: `/tmp/.../soft_polish_*.py`, `dayassign_weights.py`(scratchpad)。

## 事前診断（実行前の過拘束検知, 2.47.0 完了）
過拘束データに最適化を走らせても解は無い（出口の無い迷路）→ 数分の無駄。実行前に「何をしても無理」を検知して提示する。
`V6SanityPort.buildGuidance` に**検査6（シフト単位の構造的過拘束）**を追加（既存の SettingIssue channel に載るので新規UI不要・
read-only・ダッシュボードの SettingIssuesCard に常時=実行前に表示）。誤検知回避のため明確に矛盾する2ケースのみ:
- A) 下限の合計 > 必要数(上限)の合計 → 全員の下限を同時に満たせず過剰配置/下限割れが不可避。
- B) **全担当者に上限**があり 上限の合計 < 必要数 → 席を埋めきれず人員不足(covU)。`allCapped` ガードは CLAUDE.md の
  「未設定者は無制限なので誤判定しない」設計と一致（Dﾃ=上限計10だが現状33＝未設定者ありでは発火しない）。
発展: ②**ボトルネック可視化の集約(2.53.0 完了)** = `BottleneckCard`(MagiDashboardCards)。countViolations を職員別、
needViolations を日別に件数集計し多い順 top5 を俯瞰表示(read-only・詳細タブ、BreakdownCard 直後)。セル着色(TallyCard)を補完し
「どこにしわ寄せが集中するか」を一目で提示。③hard→soft の What-if 提案(checkResultWorse で部分的に既存)は未着手。
- (3.98.0, 幻のapt目標検知 = 検査6b): 実機ドッグフーディング(桒澤美幸 B4 目標1・今20)で判明した「担当レパートリーから
  強制される最低回数 > apt目標」をユーザー指示で事前診断に追加。全日はいずれかの担当可シフトで埋まるため
  `count(k) >= T − Σ_{k'≠k,担当可} min(上限(k'),T)` の下界が成立。この強制下限が apt 目標を超えるなら aptHigh は
  何をしても残る＝データ修正が正道と提示（例: 担当={休,B4,有}・休10-10・有1-1・31日 → B4最低20回で目標1は達成不能）。
  他シフトに上限未設定が1つでもあれば下界0以下＝発火しない(保守的・誤検知ゼロ)。golden_state 検証で美幸B4のみ発火
  (他22 aptセル過検知ゼロ)。read-only・SettingIssue channel・スコアリング不変。
- (3.76.0, 壁/ダイヤル分類器 = soft floor signal): ドッグフーディングで判明した「アプリは**解ける soft と構造的 soft 下限を区別しない**
  ＝一番大きい数字が一番追っても無駄になり得る」問題への対処。`buildGuidance` に **検査(2b-2): c1 窓制約の構造的不能(壁)検知**を追加。
  各 cons1 について **供給 vs 需要下界**を比較し、供給<需要なら「窓ルール『X≥n/d日』は構造的に残る＝担当追加かルール緩和」を SettingIssue で提示。
  供給≥需要(=ダイヤル:優先度で減らせる)は正常＝出さない。**conservative**(需要=disjoint窓の下界 / 供給=休窓:S*T−Σ最小work需要・
  作業窓:Σ上限被覆＝供給高め見積り)で **false wall を出さない**向きに丸める＝発火＝真に構造的不能。read-only・スコアリング不変・HF77セーフ。
  実データ検証(golden_state): **Dﾃ≥2/14日=壁**(夜勤スロット31<必要32＝8人×2×2窓)／**休≥5/14日=ダイヤル**(供給155≥需要100)。
  ※重要な副産物: 実装前の接地で「c1=113 は構造的」という当初のドッグフーディング仮説が**反証**された(休供給は+55余剰＝c1 は大半が優先度
  トレードで減らせる)。grilling の「作る前に調べる」が誤前提を捕捉。将来は c3/range への拡張・「窓N件のうち壁M件」の post-run 明示が候補。
- (3.99.0, グリッド判読性=違反3段階＋休後退): 実機スクショ起因(「人間に容易に判読できるようにする」)。全ソフト違反が
  同じ太い橙破線枠で 194件により格子が警告に飽和し必須1件が埋没→ ①**違反3段階表示**(重み階層と表示強度を一致):
  必須=実線 / 重いソフト(low90/high45/c3mn12)=破線 / 軽いソフト(重み≤4)=**右上の小さな角マーク**に降格
  (`isHeavySoftCellViolation` 新設・vioKind 0..3・日ヘッダ下線は vk>=2 で意味不変)。②**休セル後退**: 記号「休」を
  淡色(α0.30)＋細字＋onSurfaceVariant(改名データは-1=従来表示)。③凡例3項目化＋説明文更新。表示のみ・スコア不変。
  対象は MagiFlatGrid(メイングリッド)。StaffCalendarCard の2段階表示は現状維持(密度が低く飽和しない)。
> **決定記録（D4, 対象端末）**: **幅360dp帯の端末は対象外**（業務判断・2026-07-10）。対象は幅~390dp以上
> （コンテンツ幅388dp以上＝7日表示が36dp床に当たらず成立する帯）。悲観検証の「360dpでは7日表示が6日止まり」
> は非問題として扱い、以後360dp向けの縮小最適化（名前列56dp化等）は**再提案しない**。cellW の36dp床は防御的に残置。

> **決定記録（D5, 年度末モード）**: 時期モード設計（年度始め/月末/年度末, 2026-07-10 提案）のうち
> **年度末モード＝年間積算5項目（①年間の偏り ②希望反映率 ③毎月の手修正検出 ④慢性不足シフト ⑤マスター見直し
> チェックリスト）は実装不要**（業務判断）。前提となる月次スナップショットのアーカイブ基盤も不要。**再提案しない**。
> 残る設計スコープ = 月末モード（作成フロー4ステップ再配列）と年度始めモード（実働人数チェック・欠勤耐性）のみ。

- (3.100.0, 7日間表示): ユーザー明示要件「7日間見えるようにする」。cellW 48dp固定では多くの端末で6日強しか見えず
  週の模様が切れていた→ ScheduleGrid が BoxWithConstraints で **`cellW=((利用可能幅−32−80)÷7).coerceIn(36,48)dp`** を
  動的計算し MagiFlatGrid へ注入（週ページングの cellWpx も同値＝ジャンプ整合）。下限36dp=記号可読性の床（極端に
  狭い端末のみ7日未満に妥協）・上限48dp=広い端末はより多くの日が見える。セル高は48dp維持（片手一本指のタッチ面）。
- (3.113.1, 自己見直し=3.112系の色トークン反映漏れ2件): ①編集シートの違反理由テキストの重大度色が
  cs.error/MagiAccent.orange 直書き＝ユーザーが変更した違反色(__vio__/__vioSoft__)が反映されなかった→トークン解決へ。
  ②職員カレンダー(StaffCalendarCard)のソフト違反破線が必須色のまま＝重大度の色分けがカレンダーだけ効いていなかった→
  hard ? __vio__ : __vioSoft__。③vm.start()/runLightOptimize() は 3.112.0 の ActionCard 撤去で UI 参照ゼロ＝
  API として温存しコメントで明示（テスト非依存・削除は保留）。
- (3.113.0, 希望シフトの記号バッジ): ユーザー指示「割付と希望が違っていたら希望シフトをバッジで重ねる」。
  勤務表グリッドの未反映希望(wishKind=2)を、旧・桃ドット（中身が読めない）→**希望シフト記号の桃バッジ**(左下、
  surfaceハロー縁取り・記号フォント=セル記号の70%・ensureReadable)へ。反映済(wishKind=1)は従来の青緑リングのまま。
  読み上げ(cd)にも「希望=記号」を併記、グリッド説明文に「桃バッジ=未反映の希望」を追加。表示のみ・スコア不変。
- (3.112.0, スクショ手書き指摘3系統=撤去・カレンダー化・違反色の変更対応): ユーザーが実機スクショ5枚に赤/青/黄で指示。
  **赤（オブジェクト不要→撤去）**: ①`HeroMetricsRow`(対象人数/対象期間タイル=読込ステータス行と重複、定義ごと撤去)
  ②ホーム`ActionCard`「ほかの作り方」(速く/かんたんは主導線と重複・実行中は全ボタン無効の死に領域。定義ごと撤去。
  **固有機能のバックグラウンド実行だけ SettingsCard(設定タブ最適化設定)へ移設**=`SettingsCard(onBgOptimize)`)
  ③実行中カードの見出し「いま、コンピューターが組んでいます…あと約N分…」(進捗行 progressSummary と重複。headline=""
  ＋isNotBlank ガード)。**青（カレンダー形式）**: 日別の必要人数ダイアログの日入力(テキスト+数字キーボード)→
  **`DayPickerGrid`**(**日曜始まり**=3.112.1でユーザー指示・日赤/土青・1タップ選択・40dp床。勤務表の週ページング
  mondayWeeks=月曜始まりとは別物)。WishEditor の日入力は対象外(指示範囲外)。
  **黄（違反種別の色が変更できない）**: `ColorSettingsView` が read-only 凡例でチップを押しても無反応だった→
  **チップタップでその重大度の色を変更**（必須=既存 `__vio__`・要調整=**新トークン `__vioSoft__`**(shiftColors 予約キー、
  vm.setViolationSoftColor/reset・UiState.violationSoftColorHex)・灰=情報は固定）。要調整色は MagiFlatGrid(破線枠/角マーク/
  日ヘッダ下線)・SearchLegendBar 凡例へ即反映(旧 MagiAccent.orange 直書きをトークン解決に)。ColorPickerDialog を internal 化し
  再利用。外観「違反の色」の説明文も更新。全て表示のみ・スコアリング不変。
- (3.111.0, 残作業④⑤⑥の一括完成=用語統一・違反Set化・日別ジャンプ): ユーザー指示「すべて一括完成させます」。
  ⑤**違反マップSet化**: `ViolationReport.cellFamilies`("i,j"→全違反クラスを重み降順、violations=最重1クラスは後方互換で不変)
  を新設し UiState `violationCellFamilies`(+result版) へ plumbing。効果=①編集シートの違反理由が**全列挙**(重なった c42+c3 等が
  1行ずつ) ②**E7フィルタの整合**: 旧は最重族のバケツOFFで表示中の族が同セルに残っていても枠ごと消えた→ `visibleCellVio`
  (フィルタ通過する最重クラス)で vioKind/カレンダー/違反セル一覧/バケツ件数を判定 ③要確認一覧の sub に全族を「・」列挙
  (行数=箇所数は不変)。SessionRegressionTest で「families 先頭==violations」「軽い族も保持」を回帰固定。
  ⑥**日別ジャンプ**: 要確認一覧の日×シフト項目(人員/群レンジ, staff無し)タップ→勤務表タブの該当日列へスクロール＋
  **日ヘッダを primary 枠で約2.5秒ハイライト**(`focusCell=(-1,j)`=日のみ注目の番兵、行セルには一致しない)。「勤務表→」表記。
  ④**用語統一**: covO ラベル「過剰な配置」→**「人員過剰」**(covU「人員不足」/集計凡例と対)・要確認一覧チップ「過剰・調整」→
  「過剰・**要調整**」(凡例/編集シートの重大度語と統一)・マーク/日別サブの「過」→「過剰」「範囲」→「群レンジ」。
  すべて表示のみ・スコアリング不変(inc/breakdown/weights 不変)。
- (3.110.0, C1/C3のタップ時窓ハイライト): 残作業③。セル編集シートを開くと、そのセルの違反が c1/c3/c3m の場合に
  **違反が指す窓/連の範囲を primary 枠でグリッド上に表示**(シートを閉じると消える)。`vm.violationRange(i,j)`=
  c1は最初の不足窓・c3/c3mは未完成パターン窓または単一シフト連の実範囲を Problem から再計算(read-only)。
  `focusRange`(i,開始日,終了日) を MagiApp→ScheduleGrid→FlatCell へ伝播し focusCell と同枠で描画。
  VBAの「期間を塗る」の利点を、常時でなくタップ時のみ=飽和なしで回収。表示のみ・スコア不変。
- (3.109.0, セルタップで違反理由): 認知ウォークスルー最優先項目。編集シートの状態欄に「⚠ 必須違反: 禁止の並び」
  「△ 要調整: 窓の要件」等を1行表示(violationCells の族→breakdownLabels、3.107 の重み優先で最重の族を保証)。
  従来は枠の意味を要確認一覧/診断ログへ往復しないと理解できなかった「見つける→理解する→直す」の断絶を解消。表示のみ。
- (3.108.0, Web試作の可視化/ナビ移植=ジャンプ＋グループ色帯): Web側試作ログの5機能をネイティブ照合(達成62%)し
  未実装2件を移植。①**セルへのジャンプ**: 要確認一覧のセル違反項目(staff+day保持)タップ→勤務表タブへ切替＋
  該当日列へ hScroll 自動スクロール＋**注目セルを primary 実線3dpで約2.5秒ハイライト後に自動クリア**
  (`focusCell` 状態を MagiApp が保持し ScheduleGrid/MagiFlatGrid/FlatCell へ伝播・LaunchedEffect+delay)。
  回数系項目(日なし)は従来どおり修復フローへ。②**グループ色帯**: 名前列左端4dpに所属群の色帯(出現順に
  黄金角 hsv 自動割当・設定不要)。行追跡の視線ガイド兼用=判読性確率の行追跡72%を補強。表示のみ・スコア不変。
- (3.77.0→3.78.0, 画面修正版の移植融合 ①③): web「画面修正版」を詳細検証しネイティブへ融合（ユーザー承認: 月表=E5は保留維持/
  要確認件数=ロケーション数）。①**週ページング＋横スクロール併用**（ユーザー修正: トグルでなく併用）: `ScheduleGrid` に **前週/次週**
  ボタンを追加し、全日を横スクロールで保持したまま `hScroll.animateScrollTo(週先頭×cellWpx)` で1週ぶんジャンプ。現在週は左端可視日から
  `derivedStateOf` で導出＝自由スクロールにも追従（列は隠さない＝併用）。`mondayWeeks(startDate,days)`(月曜始まり)で週分割。cellW=48 のまま
  ＝1画面≒1週＋スクロールで残り。`MagiFlatGrid(hScroll)` に外部 ScrollState を注入。③**要確認N件**: `ViolationFilterBar` に違反ロケーション数
  (violationCells+needViolations+countViolations の実箇所数)を併記。族fire数(c1=113)でなく作成者が見るべきセル数(golden_state=39)。
  表示のみ・スコアリング不変。
- (3.79.0, 画面修正版 ②検索・凡例の統合折りたたみ): `SearchLegendBar`(既定=閉)を新設し、**検索**(職員名で該当グリッド行を
  太字＋青で強調＝行は隠さず被覆文脈保持)＋**凡例**(ShiftColorLegend＋ViolationLegend)を1折りたたみに集約。グリッド内の凡例は撤去
  (重複回避)。`MagiFlatGrid(nameQuery)`/`ScheduleGrid(nameQuery)` で検索語を伝播。検索状態は勤務表タブに rememberSaveable。
  **E7種別フィルタは折りたたみに入れず独立バーで可視のまま維持**(ユーザー指示)。表示のみ・スコアリング不変。月表=E5は保留維持。
- (3.80.0, 融合仕様 ★1 要確認一覧): 添付 spec `schedule_mobile_fused_minimal.html` の confirm ビューをネイティブへ移植（ユーザー承認:
  E5除外・順序 ★1→2→3→4）。`ConfirmListCard`(MagiDashboardCards)= 散在していた診断を**箇所単位・重大度リスト**で1ハブに統合。
  `confirmItems(ui)` が needViolations(covU/covO/c41/c41s)・countViolations(low/high/c2/aptLow/aptHigh)・violationCells(pref/
  groupViol/c3n/c3/c3m/c3mn/c1/c42/c42s)を個々の項目へ展開し、**不足/過剰/窓**の3重大度マーク付きで列挙（BreakdownCard の族集計を補完）。
  重大度フィルタ(全部/不足・必須/過剰・調整/窓・件数付き)・staff 紐付き項目タップで修復フロー(`vm.findFixSuggestions(i)`)へ・
  設定ミス(settingIssues)あれば先頭に件数導線(→設定タブ)。詳細タブ(3)先頭にヒーロー配置(既存カードは下に併存=安全)。違反ゼロ時は達成表示。
  **表示のみ・スコアリング不変(読取専用)**。フィルタチップは Surface ベース(新規 import 不要)。次段: 2 hero metrics / 3 要確認のみ toggle / 4 日別・人別カード。
- (3.81.0, 融合仕様 ★2/★3/★4): confirm ビューに続く hero/day/staff/alertOnly を移植。**★2 概要ヒーロー** `HeroMetricsRow`(MagiDashboardCards)=
  **対象人数(名)/対象期間(日)/確認事項(件)** の3指標を既存 `BigStat` 再利用で並べ、詳細タブ先頭・要確認一覧の直前に配置。確認事項＝
  violationCells＋needViolations＋countViolations の実箇所数(ConfirmListCard/E7バーと同一定義)。**★3+★4 日別/人別 注意リスト** `AttentionCardsSection`=
  日別(needViolations を日集計・不足/過剰シフト併記)/人別(countViolations＋violationCells を職員集計・行タップで `findFixSuggestions(i)`)を
  MagiSegmentedControl で切替、**「要確認のみ」トグル(既定ON)** で違反0行を隠す＝そのまま triage。**BottleneckCard(top5テキスト・read-only)は
  AttentionCardsSection(全件＋トグル＋タップ修復)の上位互換のため詳細タブから撤去**(~~composable 定義は残置=無害~~
  →3.103.1 で定義も撤去=呼出0)。全て表示のみ・スコアリング不変(読取専用)。
  E5(全月横表)は保留維持。★1→★4 の融合移植これにて一巡。
- (3.82.0, ★1-★4 コードレビュー修正): /code-review(並列 finder×verify)で判明した表示バグを修正(スコアリング不変)。
  ①**ConfirmListCard の迷子フィルタ**: 選択中フィルタの件数がデータ変化で0になるとチップは消えるが `filter` は残り
  空リスト＋見出し件数>0 の迷子に。`effFilter`(件数0なら全部へ戻す)を導入しリスト・チップ選択の両方に適用。
  ②**stale タイトル**: `remember` キーに `staffNames/shiftSymbols/startDate` を追加(職員/シフト改名で行タイトルが古いまま残る)。
  ③**c2 の方向誤表示**: c2(個人の合計)は方向を持たない単一クラス vio-c2 なのに「過」固定だった→ ConfirmList はマーク「計」、
  AttentionCards は方向サフィックスなし(記号のみ)に(下限割れ/上限超過と混同回避)。④`ConfirmItem.shiftSym` 死にフィールド除去。
  非対応(判断): BottleneckCard 定義残置=既に無害と記載済で維持 / AttentionCards の remember 化=毎再構成で再計算するため
  そもそも stale にならず、データ極小で効率影響も無視可 / キー解析の共通化=別スコープ。
- (3.83.0, 見直し=詳細タブの「違反総数」三重表示を解消): 融合カードを旧カードの上に積んだ結果、詳細タブに違反件数が三重化。
  ①**ヒーローの「確認事項(件)」タイル撤去**: 直下 ConfirmListCard ヘッダ「要確認一覧（N件）」と完全に同数＝重複。ヒーローは
  対象人数/対象期間の規模コンテキストに純化(2タイル)、件数は要確認一覧に一本化。②**OverviewDashboard を詳細タブから撤去**:
  「気になる点(=rep.total 総違反リング)」は違反総数の3つ目の見せ方／「注意の日(highRiskDays リング)」は AttentionCardsSection
  日別リストが列挙で上位代替。D2(HARD 三重リング撤去)と同方針で「違反総数」の重複を解消。両 composable 定義は残置(OverviewDashboard は
  未描画の V6RemainingScreens から参照=無害)。CheckSummaryView(守れていない約束=bestHard)/BreakdownCard(族内訳)は固有情報で維持。
  表示のみ・スコアリング不変(読取専用)。

## 停滞脱出の改善（進行中）
探索本体が過拘束データで空転しがちな問題（停滞脱出の質向上）。
- (2.49.0): コードレビューで判明した冗長な後処理パス(applyGroupRangePairPolish)を revert。同日2者スワップ族
  (c41/c42/c41s/c42s/c2)は既存 CyclicSwap が isBetter で total 最小化の過程で研磨済＝専用パスは冗長だった。
  探索 focus 登録(2.46)は真の取りこぼし対策なので維持。
- (2.50.0, 完了): **GLS penalty aging（減衰）**を新設（`GlsPenalty.decay(keepPercent=80)`）。GLS penalty は従来
  増える一方で長期停滞時に肥大化し（観測 36k→64k）受理バイアスが固着していた。一定 kick(`GLS_DECAY_EVERY=256`)
  ごとに penalty を 80% へ減衰し curAug を `augment(cur)` で再同期。**globalBest は生スコア管理のため解の質は退化しない**
  （探索の受理動学のみに作用）。ユニットテスト(GlsPenaltyTest: decayShrinks/decayRemoves)で減衰算術を検証。
- (2.51.0, 完了): **restart摂動の非線形スケジュール**。restart 序盤ほど大きく揺らし(多様化)終盤ほど小さく(intensify)。
  mult=0.6+1.2*(1-frac)^2。摂動のみでスコア不変・globalBest 保持＝退化なし。
- (2.52.0→**2.55.0 で revert**): 戦略的振動(λ係数オシレーション)。受理層で hard を一時割引し実行不可の壁を越える手法。
  PoC(/tmp/osc_poc.py の理想化2盆地+薄い壁)では escape 20/20 だったが、**Python等価ベンチ(tools/nsp_bench.py)で
  現実的NSPでは一貫して悪化(AUC +5%〜+15%)と実測**。理由: 現実の過拘束NSPは feasibility 到達自体が難しく「壁の向こうに
  良い実行可能盆地が無い」ため、振動は限られた予算を実行不可彷徨に浪費するだけ。**「安全(解は退化しない)」は正しいが
  「有益」ではなかった**=安全性と有益性は別物。2.54 の HF63 選択発動も同根のため一括 revert。CRINN流の実測が推論を覆した好例。
  → nonlinear_restart(2.51, 実測で僅か改善)と GLS aging(2.50, 中立・無害)は維持。
- (教訓): 探索動学の変更は **tools/nsp_bench.py の実測報酬で A/B 検証してから**採否する(PoCの理想化landscapeは現実を
  代表しないことがある)。便益が測れない/負なら入れない。
- (2.56.0, 実測結論): nsp_bench.py に **ALNS destroy-repair を追加(高忠実度化)** し再測定。**destroy-repair を入れると
  GLS aging/nonlinear restart/振動 は全て AUC 中立**(repair が支配的で feasibility に容易到達=脱出の出番が消える)。
  GLS keep%/decay_every/lambda・restart係数の**パラメータスイープも全て +0.0%=チューニング価値なし**と実測。
  低忠実度(1セルSA)での「nonlinear_restart改善」は偽信号だった。→ **脱出ヒューリスティクスへの投資は停止**。
  維持判断: GLS aging(2.50)/nonlinear restart(2.51)は中立=無害かつ proxy は短時間簡易のため実機の数百万iter regime
  での penalty 肥大防止の意義が残り得る→維持。**今後の本当のレバーは repair/destroy-repair オペレータの質 と データ側**。
- (2.57.0, 実測駆動の改善): **soft-aware destroy-repair**。`destroyRepairDayAt` は従来ランダム順で被覆穴を埋めるだけ
  (soft無視)だったのを、nsp_bench.py で測った勝ち筋(soft-aware 修復: AUC -24%〜-34%)を移植。非希望セルを休へ destroy →
  各需要を「割当の marginal soft(`staffCountPenaltyAt`=low×90/high×45/apt, Evaluator と同一式)が最小の休スタッフ」で
  repair(休→k のみ=被覆穴を新たに作らない、希望固定は保持)。**探索オペレータの変更でスコアリング不変=Δ×フル無関係**、
  受理(SA/isBetter)が最終採否=安全。脱出ヒューリスティクスが全て中立だった中で実測された唯一の品質レバー。
- (実測・不採用): `destroyRepairStaffAt`/`destroyRepairViolations` の soft-aware 化も nsp_bench.py で測ったが、
  day-repair の上に**上乗せ効果なし**。smart_staff(職員DR)は **over で −23.6%→−9.6% と一貫せず有害**、smart_cell
  (violations相当)は borderline 改善だが over/hard で同等〜微悪化＋proxy が実機(違反セル限定・低頻度)より過大で不忠実。
  → **実装しない**(有害・無効な複雑性を入れない=測定駆動3度目の「やめる」判断)。検証済みの repair レバーは day-repair のみ。
- (実データ追認, nsp_bench `--real`): golden_state(実10職員/31日/10シフト, need=shifts[k].need1, canDo=groupShift,
  staffRange 51セル, apt 23セル, use2)を忠実ロードして A/B。**soft-aware day-repair は最終soft 274→22(12×改善)**=
  実データで大幅な品質向上を確認(2.57 妥当)。ただし**AUC は +24% 悪化**(clear+soft-fill が aggressive で序盤収束が遅い)。
  **指標の教訓: AUC(速度)と final(品質)は実データで乖離。実機は5分・数百万iterの単発最適化で最終品質が成果物=final が主指標**。
  staff+viol は実データで final を 22→12 と更に改善(AUC は +40% 悪化)＝合成の「有害」と逆で、最終品質基準なら小幅改善で
  再検討余地あり(限界効用は小)。合成ベンチの AUC 結論を実データの final で見直す価値がある(脱出機構の再評価含む)。
- (網羅再分析・final品質×実データ, nsp_bench `--real`): 全機構を **final(最終品質)** で再評価(base=R=repair(day) 2.57)。
  **過去の AUC 基準判定が複数覆った**: R+viol **−22.6%**(旧「不忠実で見送り」→改善)、R+staff+viol **−49.5%**(旧「有害」→
  大幅改善・最良級)、R+staff 単独 +52.7%(有害=viol との併用が要), R+oscillation/R+gls+decay **±0%**(inert=撤去/維持で確定),
  **R+nonlinear_restart +101%(旧「中立で維持」→有害!)**。教訓: **製品は final が主指標、AUC でなく final で採否すべき**。
  → 実装方針: **staff+viol soft-aware を実装(−49.5%)、nonlinear_restart(2.51) は revert(+101%有害)**、GLS は inert で維持。
- (2.58.0, 実装完了): final品質×実データの再分析どおり実機へ反映。**`destroyRepairStaffAt`/`destroyRepairViolations` を
  soft-aware 化**(staff-DR=非希望を休へdestroy→被覆穴を marginal soft 最小で repair / violations=違反セルを old→k の
  marginal soft 最小へ再割当。共に `staffCountPenaltyAt`=Evaluator同一式)。**nonlinear_restart(2.51) を revert**(一律
  strength=0.18 へ。final +101% 有害)。全て探索オペレータ/摂動の変更でスコアリング不変=Δ×フル無関係・受理が最終採否=安全。
  これで repair 3種(day/staff/violations)が全て soft-aware に統一。GLS aging(2.50)は inert で維持。
- (2.59.0): **c41-aware day-repair**(設定画面3箇所の上限下限を全て研磨)。soft-aware repair は ①適切回数(apt) ②個人別の
  回数(staffRange low/high) を staffCountPenaltyAt で既に研磨済。3つ目 ③グループ別の回数(cons41 群レンジ)を追加: 
  destroyRepairDayAt の選択 marginal に「群の日次人数レンジ(cons41)」の delta を加味(`c41DayMarg`, grpCnt を当日分維持)。
  **`p.cons41` 空(golden_state 等)ならゼロ overhead で無害**。合成(c41あり)実測: R+staff+viol の over tier で final 47→41・
  AUC -18%→-20.6% の小幅改善・他 tier 中立。スコアリング不変=Δ×フル無関係・受理が最終採否=安全。
  → これで repair が設定3画面の上限下限(apt/個人range/群range)を全て研磨。
- (3.95.0, HARD=0非到達への配慮＝静的covU床の focus 除外): 「HARD=0 に到達しない過拘束 regime でも良い解を出すよう停滞脱出を
  改良」の指示に対し、まず nsp_bench で**真の infeasible 実験**(per-day sum(need)>S で covU 強制。合成 tight=1.0 は destroy-repair
  で hard=0 到達＝infeasible にならないため専用インスタンスを作成)を実施。結論=**動学は既に良好・パラメータ変更は不採用**:
  ①softFocusProb(床での soft 集中率, 現行 0.15)を 0.25/0.35/0.50 に上げても soft は中立〜悪化(0.35+ で +2〜3%)＝現行 0.15 が最適。
  ②targeted-perturb(infeasible 時に uniform 摂動でなく destroy-repair 摂動)は hf67HardRepair が既に摂動後の hard 床を復元済で
  **冗長**(bench 中立・同 hard 床)。③big-destroy(hard 停滞時の多日破壊)も**同 hard 床・soft は誤差内**。全変種が同一 hard 床に到達＝
  hard 最小化は既に解けており、床での soft も最適近傍。→ **測定が支持しないパラメータ/近傍変更はしない**(2.55/2.56 の教訓を再確認)。
  **採用した唯一の改善(原理ベース・3.74.0 と同方針)**: RSI focus の `avoid` に**静的 covU 床**を追加。covU は
  `structuralHardFloor`(有資格全員就けても埋まらない席=forcedCovU)が下限で最適化中に不変。covU がこの床に達したら以後 covU は
  下げられないと**静的に確定**するので、HF63 の動的検知(約3ラウンド無改善を要す)を待たず **round 0 から focus 除外**し、RSI の
  残ラウンドを解ける族(他HARD/SOFT)へ回す(旧: covU=床でも HF63 が flag するまで ~3 ラウンド無駄打ち)。`covU>=床` は恒真ゆえ
  `covU<=床`＝「これ以上不可」を正しく判定。**床=0(構造的不足なし＝HARD=0 到達可能な一般ケース)は no-op＝挙動不変**。focus 選択
  のみでスコアリング不変(keep-best=better() が結果担保)＝退化なし。covU が下限であることは定理(測定不要)。golden(構造的covU=2)で発火。
  ※bench は RSI focus/portfolio を模擬しないため 3.74.0 同様「実測でなく原理」で採否(no-op安全・低リスク・可逆)。
- (3.95.1, 12h見直し=敵対的レビューで判明した3.95.0の相互作用バグを修正): ①**[実バグ] N4早期脱出の常時武装化**: 3.95.0 の
  静的covU床が `avoid` を合流させたため、N4 早期脱出(`stagnantRounds>=2 && avoid.isNotEmpty()`)が構造的covU>0 のデータ
  (golden含む)で **round 0 から常時武装**し、hf63 が何も検知していなくても2停滞ラウンドで RSI が即終了＝「旧N4の厳密な部分
  集合」保証を破壊していた。`dynamicAvoid`(HF63検知のみ)を分離し N4 発火をそれでゲート(focus除外は合流 avoid のまま)。
  ②**[latent] DeltaEvaluator commit() の wStep 非対称**: 3.92.0 の isWork ハードニングが previewMove 側のみで、commit 側
  (L246)は旧式のまま＝範囲外セントネルの仮定下で preview/commit の wdCnt が乖離し得た。同一ガードへ統一(対称性回復)。
  ③covU床コメントの「covU>=床は恒真」を訂正(groupViol 混在時は下回り得るが `<=` 除外が正しい旨)。単位整合
  (breakdown["covU"]=covUCell amount 和 == structuralHardFloor 単位)・checkResultWorse 3節・relink予約・rangePen 90/45 は
  検証で健全を確認。
- (3.101.0, ログ再精査＝c3n focus の no-op 仮説を修正): 実機ログ再精査で「3実行×計10ラウンドの RSI c3n focus で c3n=1 が
  不変→HF63 が c3n を誤 infeasible 判定」を発見。原因=`rsiGenerateHypothesis` が c3n を hf67HardRepair へルーティング
  していたが、hf67 は群外修正(hf66DataHardening)・希望反映・被覆/下限充填のみで**禁止連続(c3n)には一切作用しない**＝
  c3n focus のラウンドが無変化仮説で空転していた。groupViol/pref は hf67 の作用対象なので維持し、**c3n のみ違反セルを
  直接再割当する destroyRepairViolations(else 分岐)へ変更**(c3n セルは violations マップに両端2セルで載る)。仮説は
  ラウンド単位 better() keep-best でゲート済＝退化なし・原理採否(bench は c3n 非実装=3.74.0 と同方針)。
  併せて違反詳細(buildViolationDebug)の aptLow/aptHigh 行に**目標(クランプ後)を併記**(旧: 回数/下限/上限のみで
  「回数4 下限4 上限5 がなぜ違反?」が読めなかった。目標5 が発火理由)。
- (3.102.0, 高速化・高精度化): 実機ログの定量的無駄を3点解消。①**[高精度化] 予算残の追加精製**: 後処理予約枠(budget/12,
  8〜25s)は後処理がフィックスポイント到達で大半未使用のまま返っていた(実機: 予約25s中 実使用0.45s＝約24.5s廃棄=予算の8%)。
  残5s以上＋違反残あり＋停滞早期終了でない場合、最終盤面起点の keep-best 追加精製(ALNS, runAlns入力比番兵つき=退化不能)へ
  回す(`ExtraRefine` ログ・TIME行に「追加精製」列追加)。②**[高速化] destroyRepairStaffAt の被覆事前計算**: 旧 O(T×K×S)
  ≈3100演算/呼の全職員走査を、一度数えて差分更新する O(S×T+T×K)≈620 へ(~5×減・挙動同一)。③**[高速化/零アロケ]
  op0-2 copy系パスのダブルバッファ化**: 毎反復の `cur.copy2D()` 新規確保(数百万回/実行のGC圧)を、スクラッチ盤面への
  arraycopy＋採用時スワップに置換(hf67経由の fixed!==cand 採用時はスクラッチ温存)。②③は挙動同一の純高速化＝同一seed
  同一結果、①は keep-best のみ＝スコアリング不変。
- (3.102.1, 自己監査で判明した①の回帰を修正): ExtraRefine の2回目 `optimize()` が入口で `lastAlternatives` を
  空にするため、本走行ポートフォリオの**「他の案」(最大3件)が ViewModel の captureAlternatives 前に消える**回帰。
  退避→`restoreAlternatives()`(新設・private set のため) で復元。
- (3.102.2, 敵対的監査で判明した latent 3件を修正): 並列監査(スクラッチswap/被覆事前計算/ExtraRefine/c3nルーティング/
  apt表示/hf63時計を敵対検証→**実バグ0**・挙動同一性を全項目で確認)の指摘。①ExtraRefine の上限を**後処理予約枠
  (postReserveMs, 8〜25s)でキャップ**(N4早期脱出等 stagnationFired 以外の早期復帰時に「節約した数分」を食い潰し
  電池/熱の早期終了方針と矛盾していた)。②ExtraRefine 採用時に「採用盤面の集計」行を追加(N3と同型。ログ末尾の
  UnifiedCheck/違反詳細は精製前盤面の診断のままで件数不一致に見えた)。③ViewModel HF63 の時計を callback の
  elapsed(フェーズ境界で巻き戻るローカル時計＝長フェーズ後に族が永久フラグ不能)から**startMs基準の単調壁時計**へ。
- (3.97.0, 実機ログ起因＝再最適化で550秒無駄の根本修正): 実機ログ(2026年8月データ・300s×2回)で「入力(HARD=1/195)を
  一度も上回れず内側番兵が2回とも入力へ復帰＝予算全部無駄」を確認。根本原因=**runV5 だけ退化防止番兵が無い**
  (runAlns=番兵あり・runRsi=入力比keep-best)。RSI++ は Phase1 Seed に runV5 を使うため、SA+hf67修復の劣化
  (実測195→229)が全チェーン(RSI→ALNS→Polish)へ伝播していた。①**runV5 に runAlns と同じ入力比番兵を追加**
  (better(base,result)なら入力を返す=入力が品質床・SA が良解を見つけたら素通し=多様化維持・スコアリング不変)。
  ②**ViewModel HF63 の粗サンプリング補正**(3.93.1と同クラス): 旧 `iters.toInt()`(累積数千万)では閾値5000が
  「約20ms無改善」相当＝違反>0の族ほぼ全てが即 infeasible 判定される9族ノイズ警告だった。経過時間ベース
  (elapsed/10=100単位/秒、5000=50秒無改善)へ補正し、**最終盤面で違反0の族は警告から除外**(破棄された探索トラック
  でしか違反が無かった covO/LimMax まで列挙され誤解を招いていた)。

- (3.106.0, 外部レビュー4件の修正): 提示されたコードレビュー(対象7b22a50)の4件を全て検証→実在確認→修正。
  ①**[P1] 休シフト削除で休日が勤務化**: removeShift が削除セルをハードコード0へ写像(休が index0 でない/休より
  前を消すと勤務へ化ける)。削除セル→**削除後の休index**へ追従＋**休シフト自体の削除は禁止**(no-op、ViewModel が
  理由メッセージ提示)。②**[P1] editStaff が skillIdx を0へ戻す**: `Staff(name,gi)` 再構築→ `copy(name,groupIdx)`
  で保持(名前編集だけで cons41s/c42s 評価が変わっていた)。③**[P1] 重複記号/氏名の解決不一致**: CSV照合の
  associateBy/後勝ちループ(9箇所)を **firstWinsMap(先勝ち=Problem.indexOfFirst と同一解決)** へ統一＋
  **検査8(重複定義の事前診断)**を新設(職員名(空白無視)/シフト/グループ/スキル群記号。read-only・非ブロック=
  既存データは開ける)。④**[P2] bg再開で計算条件が化ける**: 予算秒数/並列数を WorkManager inputData に永続化
  (旧: インメモリのみで kill 後は既定60s/4並列)＋kill後の復元は**途中最良スナップショット優先**(8秒毎退避済み。
  旧: 常に元入力から再スタート)。SessionRegressionTest に removeShift/editStaff の回帰テスト追加。

## バックログ / 未対応
1. ~~TallyCard の読取/編集モード完全整合（result専用検査結果の plumbing）~~ **→ 3.96.0 で完了**（ユーザー向け機能の TallyCard 項参照）。
2. 未レビュー領域の精読: `V6LateOperators`/`V6SearchOperators`/`V6HotfixPasses` 各パス内部, `V6WebCompat`,
   CSV/UI 層。**(3.84.0, 並列監査で一巡・下記参照)**。
3. C++/NDK 移植は**不要**の結論（純Kotlin＋被覆対応Δ評価で十分高速）。エンジンは ALNS/Destroy-Repair/
   ChainSwap3-4/C1BlockN/PathRelink/LNS/Reheat/Oscillation/適応的オペレータ重み/希望ロック枝刈り を実装済み。
   §4 ILP matheuristic のみ意図的に未実装。
4. cons3n のデータ重複（Dﾃ→A4 が2行）は二重計上だが最適化器/チェッカーで一貫（SettingIssue が dedup を提案）。
5. **E5「月全体の俯瞰」= ユーザーの明示 go まで保留**（決定記録）。指数(見やすさ12指標)で唯一70未満(58)だが、
   最低スコア≠最高価値・片手一本指/編集主体との緊張のため、着手も再提案もしない（明示 go があった場合のみ）。

## 未レビュー領域の精読（3.84.0, 並列監査で一巡）
`V6HotfixPasses`/`V6SearchOperators`/`V6LateOperators`/`V6WebCompat`/`ScheduleCsvBridge` を並列エージェントで監査。
探索/後処理は heavily-audited で**深刻な正しさバグ・不変条件破壊は無し**（全 late-operator は同日置換で被覆保存、`wishLocked` は各 mutator でガード、
空データガードあり）。**修正済み(3.84.0)**:
- **CSV 往復バグ(live)** `ScheduleCsvBridge.parse`: `build` が出力する「空行＋集計ヘッダ＋職員名で始まる回数行」を終端せず再取込し
  `matched` 二重化（記号が数値なら勤務表破壊）→ 空行/「集計」で break。
- **予算ガード** `applyBlockRotationPolish`: O(cand³) の全候補フル評価に内側 `shouldStop` が無く締切後も走り切っていた(HF66=2.65.0と同クラス)
  → ai/bi ループ先頭に `shouldStop()` を追加しバーストを O(cand) 以内に。keep-best 保持=品質不変。
- **コメント整合(HF77)** `findTargetedFix` の「6種」→実装8種(covO/c2/low/c41/high/c41s/c3want/apt)に訂正 /
  `staffPacked` 前フィルタの「漏れなく」を訂正(apt/fair/weekly は非集計＝それらのみ改善する手はこぼす＝keep-best 安全)。
- (3.85.0) **色凡例の重大度逆転(live)** `V6WebCompat.severityFromVioKey`: low(90)/high(45)=最重 soft を INFO(灰)、covO(0.5)=最軽を
  WARN(橙) と逆転表示していた(凡例=`ColorSettingsView` は `MagiSetupCards` 詳細設定で live＝当初「死に画面」判定は誤り)。
  重み階層に整合(low/high/c3mn→HIGH, c1/c3/c3m/c2/c41/c42/c41s/c42s/apt→WARN, covO/fair/weekly→INFO)。表示のみ・スコア不変。

**報告のみ(未修正=判断/測定待ち)**:
- ~~`applyDayAssignmentPolish` の rangePen 重み 3/3・apt 1 は Evaluator の 90/45/1 と乖離~~ **→ 3.94.0 で 90/45/1 へ整合(下記)**。
- `staffPacked`/`c3FamCount` が c3/c3m を run-deficit でなく窓#fire でモデル化(前フィルタ限定・keep-best 安全)。
- 平準化研磨(`applyGroupShiftEqualizePolish`/`applyWeeklyEqualizePolish`)は分散指標で目的関数(fair/weekly=L1)と別物＝既知の冗長。
  ~~`weekly` の `restIdx=-1`(休記号改名時) で全シフトを勤務扱いする潜在バグ・`dow0` 再計算~~ **→ 3.103.0 で修正**
  (restIdx/dow0 とも Problem と同一ソースへ統一)。
- **デッドコード**: ~~`V6RemainingScreens`(未描画・外部参照0)＋そこからのみ実呼出の `HeaderBar`/`RingGauge`/`BottomNav`/`FlagsView`/
  `OverviewDashboard`/`OperatorLogView`~~ **→ 3.86.0 で撤去済**(外部参照0を再確認。live な `CheckSummaryView`/`ColorSettingsView` と
  それらが使う `SectionSegment` のみ残置)。**→ 3.87.0 で `V6WebCompat` のスコアベクタ死蔵クラスタも撤去**
  (`classifyHardBreakdown`/`HardBreakdown`/`scoreVecStable`/`betterVec`/`firstDiffTier`/`ScoreVector`=呼出0)。
  `buildWorkbook`/`buildWs2-7` は `V6WebCompatTest` がカバー中のため残置。
- ~~`ScheduleCsvBridge` 各コンポーネント取込の `drop(1)` ヘッダ無検証(ヘッダ無CSVで先頭行黙殺=軽微)~~ **→ 3.103.0 で修正**
  (4サイトとも先頭行が実データ=既知の職員名/制約キーワードなら取り込む。upsert は新規追加の誤登録を防ぐため既知名のみ=保守的)。

## 直近の状態
versionName=`2.41.0-bound-check`（versionCode 48）。目的関数統一は covO/range/c3族(単一+複数連)/c1/apt/fair まで完了。
診断ログに**上下チェック(全シフト網羅)**を追加（`V6SanityPort.buildViolationDebug`）。下限/上限(staffRange)が設定された
全シフトについて個人別の下限割れ(low)/上限超過(high)を担当者ぶん洗い出し、違反0なら「上下OK」も表示（判定は
UnifiedViolationChecker と一致: low=lo!=0&&canDo&&回数<lo / high=回数>hi）。例「上下注意 Dﾃ: 下限割れ0名 / 上限超過1名(福澤 6>5)」。
診断ログに**需給サマリ**を追加（`V6SanityPort.buildViolationDebug` 冒頭）。シフトごとに 需要 / 担当者数 / 個人下限・上限計 /
適切回数(クランプ後)計 / 現状配置 を対比し、過剰(covO)・不足(covU)の要因を1行で示す。下限/上限/適切回数の「計」は
**設定済み職員のみの合計**で、設定者数を併記（例 `上限計10(2/8名)`）。実過不足は「現状 vs 需要」(covO/covU方向)で表示し、
構造的不足は**全担当者に上限がある場合のみ**判定（未設定者は無制限なので「上限計<需要」だけでは不足としない＝誤判定を修正）。
例: 「需給注意 Dﾃ: 需要31 … 現状33 → 現状33>需要31→過剰2(covO)」。読み取り専用。
apt は群目標を個人 staffRange[lo,hi] でクランプし、staffRange固定職員の解消不能な幻のapt違反を除去（golden_stateで
apt偏差 61→28 を確認、上條Dﾃ 2-2固定×群目標10 の誤った赤色が消える）。
fair(グループ内公平化)は群×担当ONシフトの round(平均) からの L1 偏差和として Evaluator/Delta/Checker の3者へ重み1で統合
（後処理polish からの格上げ）。UI内訳には非表示で weightedScore/total のみ算入。c41s/c42s の内訳表示漏れも修正。
Δ==フルは Python で確認済み（fair: within-bucket＋任意シフト計60,000手で mismatch 0／apt: 20,000手で 0）。
（3.72.0）**weekly(7日周期(曜日)シフト平準化)を目的関数へ統合**。職員ごと、勤務日(非休)の曜日別カウントの round(勤務日/7)
からの L1 偏差和として Evaluator/Delta/Checker の3者へ重み1で統合（fair と同型・後処理polish `applyWeeklyEqualizePolish`
からの格上げ、polish は keep-best/mainNotWorse で併存＝無害）。UI内訳は fair 同様「曜日の偏り」チップに件数表示（場所
マップ無し）。共通ソース=`weeklyDevOfBucket(wd[7])`。`Problem` に `restIdx`
（休index）と `dow0`（startDate曜日オフセット%7、weekday(j)=(dow0+j)%7）を追加。Δは勤務/休 反転時のみ発火（DeltaEvaluator が
per-staff 曜日バケット `wdCnt[S][7]` を維持）。Δ==フルは Python で確認済み（80,000手 mismatch 0）＋ DeltaEvaluatorTest が
Kotlin側で full==delta を検証。Golden parity は soft total 非アサートのため不変。
次の自然な続きは c2/c41/c42 等の残り soft 族の重み統一（同じ原則で）か、未レビュー領域の精読。

## スキル / 作業の進め方
- 非自明な変更・新機能・仕様判断に着手する前は **grilling**（`.claude/skills/grilling/SKILL.md`）で要件を壁打ちする。作る前に**一問ずつ**容赦なく質問し、各問に**推奨案＋一行根拠**を添え、コードで分かることは**調べてから**聞く。認識が一致したら3〜5行に要約してから着手。「grill」「詰める」「壁打ち」の合図でも発動。MAGI 固有の必須観点（対象実装 / 変更の種類とHF77 / Level Zero不変条件 / 制約系の特定 / 完了条件）はスキル本文を参照。
