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
- minSdk=36 (Android 16+), compileSdk/targetSdk=36, java.time ネイティブ可, NDK/desugaring 不使用
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
- **covU**（人員不足, HARD, 重み8000）/ **covO**（人員過剰, SOFT, 重み1.0）。被覆は同日のみ（夜勤繰越なし）。
  ※ covO 重みは 0.5→1.0 に統一（2026-07-13, HF77 明示指示）。旧: 最適化器(Evaluator/Delta/C++)=amount×1.0 に対し
  チェッカー weightedScore のみ×0.5 で factor-2 乖離（族寄与≠weightedScore寄与）。「最適化器を正」として 1.0 で一致。
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
c3mn(12) > c1(4) > c3(3) > c3m(2) > c2/c41/c42/c41s/c42s/apt/fair/weekly/covO(1)。（covO は 2026-07-13 に 0.5→1.0 統一）

> **決定記録（D3, 業務判断）**: apt/weekly/fair の重みは**現状維持（各1）で確定**（業務担当者レビュー済）。
> ws8/ws9 等と同様、以後この3族の重み変更は**再提案しない**（明示的な数値指示があった場合のみ変更）。

## 目的関数の統一（完了。最重要の達成事項）
**最適化器（Evaluator/Delta）とUI/提案（UnifiedViolationChecker）が乖離していた目的関数を統一した。**
原則: **チェッカーを source of truth とし、より正確なモデルへ両者を寄せる**。重みは線形集約点で適用し
Δ×フル整合を維持（`soft = sc*W + ..., sc += dC ⇒ soft_new = soft_old + dC*W`）。

| 乖離 | 解消 | コミット |
|---|---|---|
| (a) covO 最適化器で無罰則 | 最適化器 soft に追加 | 2.28.0 |
| (a') covO 最適化器×1.0 vs チェッカー weightedScore×0.5 の factor-2 乖離 | チェッカーも×1.0 に統一（最適化器を正） | 2026-07-13 |
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

> **決定記録（D6, 標準値vs月別例外）**: 入力アーキテクチャ設計（4か所分割, 2026-07-10）のうち
> **「標準値 vs 月別例外」の差分表示（年間標準のスナップショット保存＋逸脱一覧UI）は実装不要**（業務判断）。
> 現行の「月＝スナップショット」モデルのまま、その月の値を直接編集する運用とする。**再提案しない**。
> 月次チェックリストを作る場合、例外件数は明示的な例外リストが既にある **日別必要人数の例外（needDay）のみ**を数える。

- (3.100.0, 7日間表示): ユーザー明示要件「7日間見えるようにする」。cellW 48dp固定では多くの端末で6日強しか見えず
  週の模様が切れていた→ ScheduleGrid が BoxWithConstraints で **`cellW=((利用可能幅−32−80)÷7).coerceIn(36,48)dp`** を
  動的計算し MagiFlatGrid へ注入（週ページングの cellWpx も同値＝ジャンプ整合）。下限36dp=記号可読性の床（極端に
  狭い端末のみ7日未満に妥協）・上限48dp=広い端末はより多くの日が見える。セル高は48dp維持（片手一本指のタッチ面）。
## ネイティブ加速 第3期: 完全版C++移行（進行中・明示指示 2026-07-13「完全版C++移行できるようにする」）
> 方針は第1/2期と同一: **Kotlin チェッカー/評価器を「正」として温存**し、実行時間を占める残りの Kotlin ホットパスを
> C++ チャンク＋2層番兵（①チャンク自己整合 ②Kotlin fullEval Long== 照合、発火で NativeGate 退化）で置換する。
> 残余ヒートマップ（実機300sログ基準）: ①hf80PostPolish 45s×5並列ワーカー（最大の残り）→ **Stage10 で完了** /
> ②SaOptimizer PhaseB(LAHC, softPolish時のみ) / ③V6LateOperators(Chain3/4/Rect/BlkN, ラウンド境界) /
> ④後処理チェーン研磨(CyclicSwap/C1/C3系, 実測~1s) / ⑤RSI制御層・チェッカー=**軽量O(ラウンド)＋「正」のため対象外**。
> - **Stage10 完了(3.151.0)**: C++ に `PolishState`＋`runPolishChunk`（hf80PostPolish と同一の 11-way オペ構成
>   =単一セル/行内2日swap/同日2者swap/targetedFix×6/copy系DR(violations50%・day50%)＋hard時hf67、同一受理
>   =best-hardゲート＋`polishAcceptN`(acceptWorseScore temp0.15 と同式 exp(-Δ/30))、keep-best、hint=best盤面の
>   violations セル）。JNI 4関数（nativePolishCreate/Chunk/Read/Destroy）・ABI_VERSION=5。Kotlin `hf80PostPolish` は
>   `runPolishChunksNative`（200反復/チャンク・チャンク間で締切/E10停滞/キャンセル確認・best改善チャンクを
>   Kotlin fullEval Long== 照合）を先に試し、完走なら早期return（ログ「PostPolish …（ネイティブ）」）・番兵発火時は
>   照合済みbestを引き継いで従来 Kotlin ループが残り時間を続行（退化不能）。ホスト検証 TEST9: 6シード×25チャンク
>   =status0・自己整合・keep-best単調・hardゲート(63→0)・希望ロック不変。恩恵経路=RSI++ Phase4(45s×5ワーカー)・
>   optimize epilogue・仕上げ(polishOnly)。
> - **Stage11 完了(3.152.0)**: ①**[重要発見] `UiState.softPolish=true`（仕上げ最適化トグル）が既定ON のため、
>   `SaOptimizer:81` の `!params.softPolish` 条件で**既定設定では SA ネイティブ(Stage3)が丸ごと無効**だった
>   （実機ログの「ネイティブ探索=有効」表示でも V5 シード60s と RSI 奇数ラウンドの SA は全部 Kotlin。加速して
>   いたのは ALNS チャンクのみ＝実測+20%はALNS だけの寄与）。②C++ に `LahcState`＋`runLahcChunk`
>   （PhaseB=HARDガード付きLAHC の忠実移植: オペ=PhaseAと同一4種60/20/12/8・受理 candHard<=bestHard &&
>   (cand<=hist[bIt%L] || cand<=cur)・hist更新 cur<hist→hist=cur・keep-best。hist/bIt/bestHard はチャンク跨ぎ保持）。
>   JNI 4関数（nativeLahcCreate/Chunk/Read/Destroy）・ABI_VERSION=6。③SaOptimizer: softPolish 条件を撤去して
>   ネイティブ有効化＋`runWorkerNative` にラダー境界の hardStallMs 判定→`runLahcNative`（4000反復/チャンク・
>   2層番兵・発火時はワーカーごと Kotlin runWorker へ退化）で PhaseA→PhaseB の一方向遷移を移植。
>   ホスト検証 TEST10: 6シード×25チャンク=status0・自己整合・keep-best単調・HARDガード(hard 1〜4→0 単調)。
>   恩恵経路=**既定設定の全 SA フェーズ**（V5シード・高速・RSI 奇数ラウンド・RSI++ Phase1）が初めてネイティブ化。
> - **第3期 完了(3.153.0)**: Stage12(V6LateOperators)/Stage13(後処理チェーン)は**精読・定量の結果、移植しないと確定**。
>   ①コスト実測: LateOperators=ラウンド境界 O(2〜8回)×候補52手×checker(サブms)≈**~100ms/実行**、後処理チェーン=
>   実機 POST 総985〜1328ms＝**合わせて300s予算の~0.5%**。②両者の採否ゲート(gate/gateW/isBetter)は checker の
>   weightedScore/breakdown に直接依存＝C++化にはチェッカー移植が必要で「Kotlinが正」の合意に反する（Stage9/3.139.0 の
>   除外理由と同一）。→ 0.5%の利得のために安全アーキテクチャを壊さない。**実行時間ベースの C++ 移行はこれで完了**
>   （全ホットパス=評価器/SA PhaseA/LAHC PhaseB/ALNS/修復群/hf67/HF80研磨がネイティブ・残りは意図的に Kotlin の軽量制御層）。
>   併せて **NativeBridge 診断行の表示バグ修正**: 有効/無効を `NativeGate.enabled`(番兵のみ)→`usable`(番兵×トグル×ロード)
>   判定へ（旧: 設定トグルOFFの実行でも「ネイティブ探索=有効」と表示され A/B ログの判読を妨げていた。eb7919aa ログで実害確認）。
>   ラベルも実態に同期「有効(SA＋LAHC＋ALNS＋研磨チャンク)」/「無効(設定トグルOFF)」。
> - **(3.154.0, 全体計算の最小化=ユーザー指示「全体計算は必要最低限にする」)**: 番兵の全体計算（チャンク自己整合＋
>   改善チャンクの Kotlin fullEval 照合）は正しさの根幹のため不変とし、**表示/無駄の全体計算だけ**を削減:
>   ①PhaseA/LAHC ワーカーの**非改善チャンク flush の毎回 unflatten を撤去**（flush は localBest<globalBest でしか盤面を
>   読まず非改善では勝てない＝純粋な無駄。直近の照合済み best をキャッシュして渡す）②ALNS runRestartNative の
>   **checker.check＋liveBest 全面コピーを 250ms 周期に間引き**（表示専用。番兵 fullEval は改善毎に維持・restart終端／
>   番兵発火時は syncReport() で最終同期＝ログ精度不変）③**PolishChunk のチャンク頭 collectViolationCells を撤去**
>   （hint は best 基準で生成時＋改善時に更新済み＝毎チャンクの全面スキャンは冗長）④Polish チャンク 200→400反復
>   （チャンク末尾の自己整合フル評価の頻度半減・締切/停滞/キャンセル粒度は ms 級のまま）。全て挙動同一クラスの
>   純減量（hint 鮮度と番兵頻度の意味論は不変）・ホスト TEST1-10 全パスで回帰なし。

## ネイティブ加速 第2期: ALNS/RSI本体のC++化（進行中・明示合意）
> ユーザー指示「ALNS/RSI本体のC++化する」（2026-07-11）。3.139.0 の範囲確定（対象外）を**明示指示で解除**。
> 前提: Kotlin チェッカー/修復系が「正」の原則は維持し、C++ は同値の高速版＋2層番兵（チャンク自己整合
> ＋Kotlin照合、発火で NativeGate 退化）を第1期と同様に必須とする。段階計画:
> - **Stage5 完了(3.140.0)**: C++ に `collectViolationCells`（violations マップの8族=c1窓ラン先頭/
>   c42(s)ペア両セル/c3×4(run先頭・窓先頭・forbidden全セル)/pref/groupViol、count/need系はループ内未使用で対象外）
>   ＋ `GlsPenaltyN`（密配列・augment/moveAug/penalizeWorst/decay80%・lambda200）＋ `glsAcceptN`（SA/GD/Lam 3モード、
>   hard+2超は常に却下）。ホスト検証: セル抽出=重複なし・pref/groupViol完全包含、GLS=augment/moveAug 2000手一致・
>   decay算術一致。まだ未配線（Stage8 で ALNS チャンクが使用）。
> - ~~Stage5~~（完了・上記）: C++ 違反セル抽出（UnifiedViolationChecker の violations マップ相当＝GLSキック/
>   destroyRepairViolations の hint 用。MirrorCore の mark 系を移植・cells のみで breakdown/weighted は不要）
>   ＋ GlsPenalty 移植（penalty行列・augment/moveAug/penalizeWorst(util)/decay/lambda）。
> - **Stage6 完了(3.141.0)**: soft-aware 修復3種（destroyRepairDayAtN=非希望→休destroy+need1不足を
>   marginal soft(個人90/45/apt＋群c41DayMarg)最小の休職員でrepair / StaffAtN=行destroy+被覆穴のみ埋め /
>   ViolationsN=hint最大8セルをmarginal最小へ再割当・空hintはrandomAllowedCell）＋ find*Fix 8種
>   （covO/c2/rangeLow/c41/rangeHigh/c41s/c3Want/apt、c41系は群/スキル群を共通関数でパラメタ化）＋
>   findTargetedFixN（一様シャッフル順）。SaChunk の ssn/dsn が countForStaff/countOnDay に対応。
>   ホスト検証: 修復3種×200試行=wishLocked不変・担当可のみ・変更範囲閉じ込め、finder 500/500発見・全手妥当。
> - ~~Stage6~~（完了・上記）: soft-aware repairs 移植 = destroyRepairDayAt/StaffAt/Violations＋staffCountPenaltyAt＋
>   c41DayMarg（V6NativeOptimizer 1006-1200行）＋ findTargetedFix（8種, V6HotfixPasses）。
> - **Stage7 完了(3.142.0)**: hf67HardRepairN 移植（hf66=範囲外/担当外→先頭担当可 → 実現可能希望の適用 →
>   被覆不足3周充填(bestStaffForCoverage=上限超過500+回数×3+引き抜き不足コスト50・counts周内据え置き=Kotlin同) →
>   range下限充填(乱数タイブレーク)）。in-place 変異・changed数を返す。ホスト検証: 範囲外混入盤面100試行で
>   全セル担当可＋実現可能希望の完全充足、hard 63→48 の修復実効を確認。
> - ~~Stage7~~（完了・上記）: hf67HardRepair 移植（copy系オペの7反復毎・hard>0時のみ呼ばれる修復）。
> - **Stage8 本体完成(3.143.0・未配線)**: C++ に `runAlnsChunk`＋`AlnsState`（GLS・適応重み・Lam温度・best・
>   停滞カウンタをチャンク跨ぎで保持）を実装。runAlns 404-597 の内側ループを 1チャンク=N反復で完走:
>   7オペ（op0-2=copy系destroyRepair＋差分適用/op3-6=直接評価swap/randomCell/targetedFix）・
>   受理3モード(SA/GD/Lam)・opSelect(roulette/thompson)・softFocus(0.30/0.15)・hf67(7反復毎hard>0)・
>   GLSキック(停滞200超・50反復毎・256キック毎decay)・適応重み(64反復毎・反応0.2・下限0.05)。SaChunk に
>   resetBoard(restart境界のcur差替)追加。JNI: nativeAlnsCreate/Chunk/Read/SetCur/Destroy＋ABI_VERSION=4。
>   番兵1層目=チャンク末尾の自己整合(status!=0で退化)。ホスト検証: 3受理×2選択×20チャンクで status=0・
>   cur/best自己整合・keep-best単調（合成問題で 63M→800台の改善実効も確認）。**Kotlin runAlns への配線は
>   Stage8b（次段）**: SaOptimizer.runWorkerNative と同型の退化フォールバック＋2層目のKotlin照合を付す。
> - **Stage8b 完了(3.144.0)**: Kotlin runAlns へ配線。restart 本体の内側 while を `if (!usedNative)` で
>   囲み、ネイティブ可能時は `runRestartNative`（ローカル suspend fun）が 1チャンク=200反復で C++ ALNS を
>   駆動。Kotlin 保持: restart 境界の perturb+hf67・進捗onProgress/liveBest・キャンセル(チャンク間ensureActive)・
>   予算deadline・**2層目番兵**（best 更新チャンクを Kotlin Evaluator.fullEval で Long== 照合、不一致で
>   NativeGate 退化）。problem ハンドルは restart 跨ぎ共有・try/finally で destroy。番兵発火時は false 返しで
>   その restart 以降 Kotlin ループへ。SaOptimizer.runWorkerNative と同型。診断ログ「ネイティブ探索=有効(SA＋
>   ALNSチャンク)」。これで 60s 主経路(RSI→ALNS)の ALNS フェーズが加速。GLS penalty は restart 毎再構築
>   （生スコア最良は別管理＝退化なし）。Kotlin コンパイル検証は CI（assembleDebug）。実機で番兵不発を要確認。
>   **[3.161.0で訂正]** 上記「GLS penalty は restart 毎再構築」は誤り。実装は`runAlns`呼出につき1個の
>   `GlsPenalty`をrestartループの外側で生成し、全restart間で共有（decayのみ希薄化）。再構築されるのは
>   `runAlns`が新規に呼ばれた時（RSI各ラウンド/並列ワーカー等）のみ。globalBestは生スコアで別管理のため
>   受理動学にのみ作用し正しさは不変（keep-best）＝実害なしのHF77（コメント≠実装）訂正。
> - ~~Stage8/8b~~（完了・上記）: ALNSチャンク統合（チャンク=200反復: curReport更新周期に一致。7オペ・適応重み(roulette/
>   Thompson)・受理3モード(SA/GreatDeluge/Lam)・softFocus・wishLocked・GLSキック(50反復毎)を C++ 内で。
>   Kotlin保持: restart境界(perturb+hf67入口)・進捗/liveBest・キャンセル・番兵）。
> - **Stage9 完了(追加移植なし・実測は実機)**: RSI/RSI++ のラウンド内探索本体は `runAlns`(Stage8b) または
>   `runV5`→`SaOptimizer.run`(Stage3) を呼ぶため、**Stage3+8b の配線で既に全探索フェーズが加速済み**（追加の
>   チャンク化コード不要を確認: runRsi:700 が phase=runAlns/runV5、runRsiPlus も seed=runV5＋runRsi/runAlns）。
>   RSI 固有の制御層（focus選択/HF63/rsiGenerateHypothesis/EarlyChain=V6LateOperators/better判定）はラウンド境界で
>   O(2〜8回)しか走らない軽量な Kotlin「正」の層＋チェッカー breakdown 依存のため C++化は対象外（3.139.0 と同方針）。
>   実測は実機ログの TIME行 反復数比較＋NativeBridge行の番兵不発確認で行う（サンドボックスは Android コンパイル不可）。
>   **これで第2期(ALNS/RSI本体のC++化)の移植は完了**。加速経路=V5/高速/RSI/ALNS/RSI++ の全探索フェーズ。
> 各Stageでホスト検証（scratchpad/native_test.cpp 拡張）→CI→実機ログ確認の順。

## ネイティブ加速（C++/NDK, 進行中）
> ユーザー指示「アンドロイドのネイティブ開発言語にして、実行速度改善する」（2026-07-11）。backlog#3 の
> 「C++/NDK 移植は不要」結論を明示指示で解除。**目的=両方（待ち時間短縮＋同時間の品質向上）／範囲=ホットパス
> （Δ評価＋SA内側ループ）のみ**で合意（AskUserQuestion）。方針: **Kotlin 実装を常に正として残し**、C++ は
> 高速版。返却盤面は Kotlin 側フル再評価で照合し不一致なら破棄（退化不能の番兵）。.so ロード失敗時は
> `NativeBridge.available=false` → 全経路 Kotlin フォールバック（JVMユニットテストも従来どおり）。
- (3.136.0, Stage1=足場): NDK/CMake ビルド配線（`app/src/main/cpp/`・ndkVersion 26.1.10909125・
  arm64-v8a のみ・CMake 3.22.1）＋ JNI 疎通（`NativeBridge.nativeAbiVersion` の ABI 照合）＋
  handleOptimize の診断ログに読込可否を1行表示。CI 両ワークフローの sdkmanager に ndk/cmake を追加
  （v6-engine-check は assembleDebug で NDK ビルドも検証）。エンジン動作は完全不変。
- (3.139.0, Stage4=配線完成＋範囲確定): ①**設定トグル「ネイティブ加速（C++）」**を最適化設定に新設
  （UiState.nativeAccel 既定ON・NativeGate.userEnabled と連動・実行中は変更不可）。番兵ゲートとは独立の
  ユーザー意思で、OFF=常に従来Kotlin。②**範囲決定: ALNS/RSI 本体のチャンク化は対象外**（=このプロジェクトの
  ネイティブ化はこれで完了）。理由: runAlns の反復ループは 7反復ごとの hf67HardRepair・200反復ごとの
  UnifiedViolationChecker 再検査・GLSキック(違反セルhint)が Kotlin の「正」実装と分かちがたく、これらの
  C++化は「ホットパス限定・Kotlinが正」の合意に反するエンジン全体移植になる。加速済み経路=V5(≤30s)・
  高速計算・RSI++(≥211s) Phase1種。60s主経路(RSI→ALNS)は Kotlin のまま（差分評価・零アロケ済みで
  1,800万反復/52s の実測性能）。再チャレンジする場合はチェッカー/hf67 の C++ 移植込みの新規合意が必要。
- (3.138.0, Stage3=SAチャンク): SaOptimizer PhaseA の**冷却ラダー1本を1チャンク**として C++ で完走
  （runSaChunk: Kotlin と同じ4オペレータ(single/swapDays/blockFill/LNS)＋Metropolis＋undoバッファ。乱数は
  mt19937_64=経路一致は狙わずスコアと盤面でパリティ）。スコアは**影響スライスの before/after 再計算**による
  差分方式（行族=c1/c2/c3系/pref/range/apt/weekly・日族=c41系/c42系/cov・群族=fair、ssn/dsn/wd を増分維持）。
  **番兵2層**: ①チャンク末尾に C++ 内で fullEval と照合(status!=0=Kotlin側が破棄) ②best 更新チャンクは
  Kotlin Evaluator.fullEval で Long== 照合。どちらか発火で NativeGate が閉じ**そのプロセスは Kotlin へ退化**
  （クラッシュさせない）。Kotlin が保持: 予算/キャンセル(チャンク間)・進捗flush・MagiConductor 境界
  （updateStagnationBulk 新設で停滞を一括反映）・strongPerturb。softPolish(PhaseB=LAHC) 有効時は従来 Kotlin。
  対象経路=SaOptimizer 利用箇所（V5≤30s・高速計算・RSI++ Phase1種）。ALNS 本体は Stage4+。
  **ホスト検証済み**（サンドボックスの clang++ ＋ JNI スタブ）: ランダム2万手で差分==フル一致・12シード×
  ラダーで status=0/スコア照合/keep-best 保持（scratchpad/native_test.cpp）。ABI_VERSION=3。
- (3.137.0, Stage2=C++フル評価器＋実行時パリティ): Evaluator.fullEvalParts を C++ へ忠実移植
  （magi_native.cpp。c1 canDoガード・c3 run-deficit/窓#fire・pref実現可能のみ・range 90/45・apt/fair/weekly
  L1偏差・covU/covO per-cell OR/AND、Math.round は floor(x+0.5) で同一化）。Problem は NativeEval.flatten が
  平坦配列（meta/staff/canDo/wish/needs/ranges/cons/c3/bucket）で1回だけ JNI へ渡す（members は sgrp から
  C++側導出）。**実行時パリティ**: handleOptimize 完了時に採用盤面で C++ vs Kotlin の hard/soft を照合し
  診断ログ1行（一致=µs比較付き / 不一致=W警告＋ネイティブ経路不使用）。ABI_VERSION=2。read-only＝採用結果に
  影響なし・スコアリング不変。JVMテストは available=false で全経路 Kotlin のまま。
- 予定: ~~Stage2~~（完了・上記）→ Stage2旧記述: C++フル評価器（平坦化 Problem を JNI へ1回渡し・Kotlin Evaluator と実行時照合）→
  Stage3=SAチャンク（Δ評価＋受理を C++ で回し返却盤面を Kotlin 再評価）→ Stage4=V6NativeOptimizer 配線
  （設定でON/OFF・フォールバック維持）。

- (3.135.0, 制約の項目名称を下流→上流で統一): 指示「各制約などの項目名称を下流から上流に向かって用語統一する」。
  **下流=違反チップ(breakdownLabels)の語彙を正**とし、上流（編集画面の節タイトル・ダイアログ題・診断ログ）を一致させる
  （違反を見て設定を直しに来たとき同じ名前で見つかるように。単位・補足は括弧で添える）:
  c1「期間の決まり」→**「窓の要件（○日間に△回以上）」**・c2「個人の合計回数」→「個人の合計（回数）」・
  c3m「並び希望」→**「推奨の並び」**・c3mn「並び回避」→**「回避の並び」**・c41(s)「グループ/スキル別の1日の人数」→
  **「群/スキル群のレンジ（1日の人数の下限〜上限）」**・c42(s)「…組み合わせ禁止」→**「群/スキル群ペア禁止（同じ日に不可）」**。
  診断 c3FamilyJp の英字混じり「必須MUST/禁止FORBIDDEN/希望Want/回避Hate」→ 並び4族の日本語名へ（operator_ux の
  「英字記号を画面に出さない」に整合）。groupViol は下流内の分裂（グループ不整合 vs 担当できないシフト）を
  **「担当外シフト」**へ統一。ColorSettingsView のチップ/ピッカー題も生キー(c3n等)→ breakdownLabels の日本語ラベルに。
  AttentionCards の「群レンジ」→「群のレンジ」。セクション注記（C41/C42 の生コード含む）も同語彙へ。文字列のみ・スコア不変。
- (3.134.0, 実機バグ修正=必須違反の枠が白リングだけに見える): 実機報告「違反の枠の色がおかしい」。原因=
  **Modifier.border はチェーンの先が最後=最前面に描かれる**（内側 drawContent 後に自枠を描く）のに、
  violationBorder(hard) がハロー5dp→違反色3dp の順で連結し、**ハローが違反色を完全に覆って白リングだけが見えていた**
  （3.118.0 のコメント「後掛けが上に描かれる」が逆。暗テーマでは暗ハローで目立たず、UD白地固定=3.121.0 で露呈。
  凡例はハロー無し単純枠のため赤く表示され、グリッドと食い違っていた）。違反色を先（最前面）・ハローを後（背面）に
  修正: 外側3dp=違反色/内側2dp=ハロー。破線(vk2)と角マーク(vk3)は同一 draw ブロック内で正順のため影響なし。
  FlatCell/CalendarCell 双方が同ヘルパー経由で同時修正。表示のみ・スコアリング不変。
- (3.133.0, 用語統一=全画面の表記ゆれ解消): 指示「用語統一する」。UI文字列を全数調査し5クラスタを統一:
  ①**「スタッフ」→「職員」**(34件。3.114.0 のドア名「職員管理」・集計「職員別」に整合。**CSV往復キーワードは除外**=
  ScheduleCsvBridge の「スタッフ \\ 日付」ヘッダと looksLikeScheduleCsv の startsWith 判定は同期のため不変。
  「ユニット」は外部フォーマット(ユニット列形式)の語彙のため不変) ②「盤面」→「勤務表」(診断ログ) ③「守れていない約束」
  「必須の条件」→**「必須違反」**(凡例と統一) ④c41説明の「最低／最高」・一括設定の「最低=最高」→**「下限／上限」**
  (ConstraintDialog の入力語と統一。need の「最低人数」は 3.127.0-B の決定どおり維持) ⑤「作る」→**「つくる」**
  (主導線「勤務表をつくる」に統一)。文字列リテラルのみ・スコアリング不変。V6SanityPortTest の assert("担当不可")は部分一致で不変。
- (3.132.0, 違反色の入口一本化=IA重複解消): 違反色の設定が **ShiftColorCard 内の必須色のみの部分入口** と
  **詳細設定（折りたたみ）内の ColorSettingsView（基準色2種＋族別の完全版）** の2か所に分裂し、後者は見つけにくかった。
  ShiftColorCard の「違反の色（必須違反）」節を撤去し、**ColorSettingsView を設定タブのシフトの表示色直後へ移動**
  （詳細設定はログのみに）。編集APIは不変（__vio__ は ColorSettingsView の「必須の基準色」チップから従来どおり変更可）。
  文言・コメントの「詳細設定→違反種別の色」参照も更新。表示・導線のみ＝スコア不変。
  (3.132.1, /code-review 後始末): 未マージ17コミットをインラインレビュー（サブエージェントはAPI上限で不可）。
  実バグ0。残滓2件を修正: 詳細設定の説明文「ログ・違反色トークン」→「ログの確認と出力」（移動後の実内容と整合）、
  AdvancedSettingsSection の未使用 vm パラメータ除去。制約編集の値順ラウンドトリップ（C42 の g/s 写像含む）・
  希望編集の移動意味論・loadAsync(markResult)→makeUi resultFresh 経路は検証で健全を確認。
- (3.131.0, 希望シフトの日入力カレンダー化=バックログ「希望の日範囲選択」解消): WishDialog の日入力（±ステッパー＋
  テキスト、3.112 で「指示範囲外」と据え置いた最後の旧式）を **NeedDayEditor と同じ DayPickerGrid**（日曜始まり・
  タップトグル・**複数日一括**）へ統一。「適用（N日）」で選択日すべてに同じ希望を登録。チップ編集は**移動の意味論**
  （元の日を選択から外す/別スタッフへ付替えると元の希望を削除。同スタッフ×元日が残っていれば setWish 上書き）。
  未使用 import（Button/OutlinedTextField/KeyboardOptions/KeyboardType/width）除去。表示・入力導線のみ＝スコア不変。
- (3.130.0, 実機指摘2件=制約行の編集＋色ピッカー修正 / bg復元配線の完遂): ①**「登録した制約の変更ができない」**:
  ConstraintsCard/SkillConstraintsCard の行は削除のみだった→ **行タップで変更**（追加ダイアログのプリフィル版
  `ConstraintDialog(editIndex)`・確定で同位置を置換）。vm に `constraintRowValues(family,index)`（生値の取得、
  値順=追加ダイアログの入力順）と `updateConstraint(family,index,values)`（cons3系は addCons3 と同じ正規化
  =最初の空白まで・最大5）を新設。全10族（cons1/2/3系4/41(s)/42(s)）対応。mutateConstraints 経由=undo/再検査/保存は
  追加・削除と同一。②**「色ピッカーのレイアウトがおかしい・現在の色が無い・他の色も選択できない」**:
  ColorPickerDialog が 12色を chunked(5)→端数行2個が weight で巨大化していた→ **20色=5×4 の完全グリッド**
  （端数行は空 Spacer で同サイズ維持）。未設定時に「現在の色」がグレー（hexToColor("")のフォールバック）だった→
  **defaultHex（実効の既定色）**を新設し、必須=#BA1A1A/要調整=#E08A1E/族=重大度の基準色を渡して表示＋パレット✓一致。
  パレットに既定色と MagiAccent 系（赤/橙/緑/青/紫/桃/灰）を収載。③3.127.0-③の `loadAsync(markResult)` が
  **本体未配線**（hasResult=false のまま）だった回帰を完遂: markResult 時は hasResult=true＋resultSchedule も設定。
  ①②は表示・編集導線のみ、③は表示フラグのみ＝スコアリング不変。
- (3.129.0, 冗長性見直し=説明・項目名称の短文化): 指示「説明や項目名称の冗長性を見直す」。監査28件のうち
  適用対象を一括置換: 見出しの括弧補足を削除（今月の作成条件/片手モード/仕上げ最適化/まとめて割当 等）、
  重複説明文の削除（SettingIssues/E7チップ操作説明）、長文説明の要点化（FixSuggestion/Breakdown/ConfirmList/
  実働チェック/WishEditor/NeedDayEditor/StaffRangeEditor/SkillGroupEditor/SectionNote①）、「時間予算」→
  「計算の制限時間」。文言のみ・スコアリング不変。
- (3.128.0, 短予算も複合パイプラインへ=実機指摘「60秒予算を1つだけのアルゴリズムで使用」): AUTO の予算プランで
  31〜90s が ALNS 単発（60s=ALNS×1）＝詰まった HARD 族（アリフ c3n 等）を狙う RSI フェーズが一度も走らず、
  仮説5本も同一解に収束していた。**31〜210s を RSI(2/3)→ALNS(1/3) の複合に統一**（既存 RSIThenALNS チェーン
  を短予算へ拡張）。各段は入力比 keep-best 番兵つき＝入力より退化しない（原理採否・3.74.0 と同方針。bench は
  dispatch/RSI focus を模擬できない）。getAlgorithmLabel と V6FinalBridgePortTest のバンドも同期。
- (3.127.1, クラッシュ修正=「閉じても大丈夫」で即落ち): 実機報告。原因=**マニフェスト不足**。Worker は
  setForeground(FOREGROUND_SERVICE_TYPE_DATA_SYNC) で前景化するが、targetSdk 34+ は WorkManager の
  SystemForegroundService **宣言側にも foregroundServiceType="dataSync" のマージが必須**（権限だけでは不足）。
  無いと起動瞬間に MissingForegroundServiceTypeException（サービス側スレッド発生＝runCatching 捕捉不能）で
  アプリごと落ちる。manifest に tools:node="merge" のサービス宣言を追加。
- (3.127.0, 他画面見直し第2弾＋実機指摘5件の一括): 並列3監査（編集タブ3ドア/ホーム・分析/設定・エディタ）＋
  実機スクショ5枚。**実バグ修正**: ①ホーム見出しが人手不足ゼロの必須違反(希望/禁止連続/群)でも「人手が足りない」と
  誤診断＋GuidedFix が空回り→ 不足なし時は「必須の条件がN件残っています」＋分析タブの修復フローへ振り分け
  ②一括シート(割当/希望)が実行中でも適用でき完了時に黙って上書き消失→ !running ゲート＋ボタンが理由を語る
  ③bg最適化の復元結果が「未作成」表示(hasResult=false)→ loadAsync(markResult) で結果扱い。**実機指摘**:
  ④一括割当の矛盾解消=担当外職員を対象から自動除外（担当外違反の大量生成を根元で防止）＋存在しない「公」の
  文言删除 ⑤凡例チップの形バラつき(有/Aｱ/A4=文字種で内在サイズ差)→32dp固定高で均一化＋違反凡例をFlowRow化
  （縦1文字潰れ解消）⑥「今月にする」→**「来月にする」**(setNextMonth。月末に来月分を作る業務)
  ⑦日別必要人数の**複数日一括設定**（DayPickerGrid を Set トグル化・適用（N日））⑧重み表を分析タブ→設定タブ
  （最適化設定の直後）へ移動。**文言/整合**: SetupGuideCard の旧①〜⑤番号と旧スコープ名→新3ドア名へ・NeedDay
  誘導文・editScope コメント・中断メッセージのボタン名不一致・「直し方→」青リンクの WCAG 担保・StaffRange
  チップ色の違反トークン追従（🔴🟠絵文字凡例を廃止）。クリーン確認: applyAlternative/applyFixSuggestion は
  currentSchedule 更新で D7 後も正常・CSV/JSON 出力は編集中盤面・タブジャンプ先は全て正しい。表示・導線のみ＝スコア不変。
- (3.126.0, 「下書きをつくる」撤去): ユーザー判断「下書きをつくる不要です」。思考誘導カードの補助ボタンと
  onDraft パラメータを撤去し、作成導線は「勤務表をつくる」（本最適化）1本に。generateSimple() は API として温存。
- (3.125.0, 全画面再検証=並列3監査の修正): ユーザー指示「他の画面も再検証」で D7残滓/UDコントラスト/文言整合を
  並列監査。**D7残滓=実害ゼロ**(CSV出力は編集中盤面・死に分岐なし)。**UD白地コントラストの実害5件を修正**:
  ①MagiTagChip の生アクセント文字(橙2.7:1/緑3.4:1)→実効背景に compositeOver+ensureReadable ②グリッド今日
  マーカー緑→tertiary ③編集シートの注記(不足解消/超過)を ensureReadable ④実働チェック「！」橙→warnFg
  ⑤編集シート希望行の桃を太字化(大テキスト3:1基準で担保)。**文言整合2件**: ⑥違反種別の色の説明が自己矛盾
  (外観へ誘導)→「下の2チップから」へ ⑦集計の超過地色を __vioSoft__ トークン追従に(グリッドと同じ色言語)。
  クリーン確認: テーマ/読取モードの stale 文言ゼロ・Color.White は全て ensureReadable 済・ハロー白は白地でも
  設計上問題なし(枠色自体が淡地に対比)。表示のみ・スコアリング不変。
- (3.124.0, スタッフ別カレンダーの判読性=実機指摘「レイアウトが見にくい」第2弾): ①全違反を桃地+枠で塗って
  飽和していた（3.99.0 でグリッドは解消済みの残存）→ **グリッドと同じ3段階**（必須=桃地+実線 / 重=破線のみ /
  軽=右上角マークのみ）へ。CalendarCell を vk(0..3) 受けに刷新。②日付ラベル「12/1(月)」×31 の月接頭辞ノイズ→
  「1(月)」＋土青/日赤の曜日色。③D7残滓の掃除（孤児KDoc・UiState の読取モード言及コメント）。表示のみ・スコア不変。
- (3.123.0, 集計レイアウトの白抜け解消=実機指摘「レイアウトが見にくい」): シフト集計の 0セルが cs.surface
  （UD=真っ白）で表に白い穴が空いて見え、市松状のノイズになっていた→ **0セル=surfaceVariant α0.35 の淡色**へ
  （職員別/日別とも）。日別の**全日0のシフト行（未使用シフト）はラベルも淡色・細字に沈め**、使っている行の
  模様を浮かせる（行は消さない＝存在は読める）。表示のみ・スコアリング不変。
- (3.122.0, 違反種別の色=族ごとの個別設定): 実機指摘「違反種別の色を個別に設定できない」。**チップタップ=その族の
  色を個別変更**（新予約キー `__vioFam_<fam>__`、UiState.violationFamilyColorHex）。未設定族は重大度色
  （__vio__/__vioSoft__）へフォールバック＝従来互換。`resolvedVioColor(ui, cls, hard, soft)` が族→重大度の順で解決し、
  グリッド枠/角マーク（vioCls per-cell 配列を新設）・職員カレンダー・編集シートの理由テキストへ即反映。
  基準色2種（必須/要調整）の一括変更チップも下段に併設。情報(灰)族も族色設定可（設定すれば優先）。表示のみ・スコア不変。
- (3.121.0, D8=外観UD固定): ユーザー指示「外観は UD モードのみ」。テーマセレクタ（自動/明/暗/UD）を撤去し
  **UD（高コントラスト・白地）固定**。MainActivity は MagiTheme(3) 直指定＋ステータスバー暗アイコン固定、
  AppearanceCard は 片手モード＋表示モード（かんたん/プロ）のみに。明/暗/UD の配色定義は MagiTheme に温存
  （mode引数経由・復活可能）。表示のみ・スコアリング不変。
- (3.120.0, D7=読取(結果)モード撤去): ユーザー判断「読み取り結果モードは不要。下書き直すモードだけで大丈夫」。
  勤務表タブの ScheduleModeCard(結果/下書き切替)・gridUi差し替え・CellInfoDialog(3.119.0)・hintReadOnly を撤去し、
  **常に直接編集の1本**（タップ=即編集シート）。最適化完了時は schedule==resultSchedule のため結果はそのまま見える。
  結果スナップショットのモデル(resultSchedule/result専用違反マップ=3.96.0)とcommit/copy APIは温存（UI参照ゼロを明記）。
  誤編集の担保は「元に戻す」。**読取/編集の整合問題(backlog#1系)はモード自体の消滅で恒久解消**。
- (3.119.0, 読取モードの理由表示=実機指摘「なぜ例が出ていないんですか？」): 違反理由の表示(3.109.0)は編集シート
  実装のため、既定の**読取(結果)モードではタップしてもヒントだけで「なぜ違反か」が見えなかった**→ 読取タップで
  **見るだけの `CellInfoDialog`**（割当・違反理由の全列挙[重み降順・トークン色]・希望の反映状態・「直すには下書きへ」の案内）。
  変更操作は従来どおり不可。グリッド説明文に「緑リング=反映済み」も追記（前問の混乱対応）。表示のみ・スコア不変。
- (3.118.0, 違反枠のハロー縁取り=実機指摘「禁止の違反の枠が見にくい」): ダークテーマの違反色（淡い赤）が
  桃系セル背景（Cｱ等）と同系色で、c3n の実線枠が埋没していた。`violationBorder(halo: Color?=null)` を拡張し、
  **実線=5dpハロー(surface)の上に3dp違反色**（外3dp色/内2dpハロー）、**破線=下に太めの実線ハローを敷く**
  （隙間・両脇がハロー色に）。FlatCell(vk1/vk2)・CalendarCell が halo=cs.surface を渡す。角マーク 3.105.0 と
  同じ手法＝任意のシフト色上で枠が浮く。凡例はカード地=surface上なので変更不要。表示のみ・スコア不変。
- (3.117.0, 集中モード=Web試作③の移植・最終候補): 違反フィルタバーに **「集中」トグル**（既定OFF・rememberSaveable）。
  ON で**違反(vk>0)・未反映希望(wkk=2)・注目セル以外を淡色化**（休セル後退=3.99.0 と同じ alpha0.30＋onSurfaceVariant＋細字。
  非表示にはしない＝被覆の文脈は読める）。E7=種類の絞り込み・検索=行強調・集中=異常の浮き上がり、の直交3機能が揃った。
  `ViolationFilterBar(focusMode,onFocusMode)`→`ScheduleGrid/MagiFlatGrid(focusMode)`→quiet 判定。対象はメイングリッドのみ。
  表示のみ・スコアリング不変。これで Web試作検証の移植候補は**全て完了**。
- (3.116.0, シフト別不足サマリー=Web試作①の移植): 勤務表グリッド上部に **「人員不足（全31日中）: B4 29日 ・ 有 19日 …」**
  の1行バナー（errorContainer）。needViolations の covU をシフト別に日数集計（多い順・重複日dedup）。
  「どのシフトが慢性的に埋まらないか」を数字で即答＝採用/教育判断の入口。E7 人員バケツOFF時は非表示（covU 表示と整合）。
  read-only・表示のみ・スコアリング不変。Web試作検証の残り候補は「③集中モード（盤面淡色化トグル）」のみ。
- (3.115.0, 実働チェック=年度始めモードの心臓): D5 残スコープの完了。年間マスタードア先頭に `StaffingRealityCard`
  (read-only): シフト別に **担当できる人数(canDo) ・ 月間需要人日(Σ need1+日別例外) ・ 1人あたり回数 ・
  欠勤余裕 = 担当人数 − 日最大需要**（1人欠けても1日の必要人数を揃えられるか）を ✓/！/⚠ で提示。
  「15人いるから大丈夫」ではなく「B1 は実質4人運営」の認識へ誘導する。データは Problem 由来
  (allowedShiftsFor/needCellLimits)＝チェッカーと同じ実効値。需要0のシフトは非表示。表示のみ・スコアリング不変。
  これで時期モード設計の確定スコープ（月末=3.114.0 / 年度始め=本件）は**全て完了**（年度末=D5で不要）。
- (3.114.0, 入口4分割＋月次チェックリスト＋違反ナビ＋見直し候補): ユーザー承認済みの入力アーキテクチャ実装（D5/D6 の
  範囲確定後）。①**編集タブを3ドアへ再編**: 「月次条件(毎月)／職員管理(随時)／年間マスター(制度変更時)」
  (旧: 今月の調整/シフト希望/基本マスター。シフト希望は月次条件へ統合。4か所目=勤務表グリッドは勤務表タブ)。
  **職員管理ドア**=新設 `StaffManageCard`(入職/退職/改名/所属/スキル▼を職員単位で。Ws1Card と同一 vm API の別ビュー・併存)
  ＋StaffRangeCard(個人回数)。②**月次チェックリスト** `MonthlyChecklistCard`(月次条件の先頭): 職員N名/希望M/S名/
  必要人数(標準+日別例外K件=D6準拠)/入力診断N件 を ✓/！で確認→「▶勤務表をつくる」(runV6FullOptimize+ホームへ)。
  ③**違反ナビ**(ScheduleGrid): 表示中(E7フィルタ通過)の違反がある日を ＜前/次＞で巡回、focusCell=(-1,j) 番兵で
  日ヘッダを2.5秒ハイライト。④**見直し候補メモ**: セル編集シートの違反セルに「基本ルールの見直し候補にする」→
  `ReviewMemoCard`(年間マスター先頭)に積む(セッション内のみ・state非保存と明示)。「勤務を変える/今月の例外/土台を直す」
  の3分岐のうち第3の出口を明示化。全て表示・導線のみ＝スコアリング不変。
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

## 禁止連続の枝刈りを任意長へ一般化（三連・五連など, 3.157.0）
ユーザー指摘「三連や五連なども配慮する」。直近3件（`findCovUChain`／`V6LateOperators.c3nHit`／
`CoverageDiagnosis.c3nAt`）の禁止連続(c3n)枝刈りが**すべて「長さ2のペアのみ」**を仮定していた
（`V6LateOperators` の既存コメントにも「長さ2の禁止連続のみ」と明記された既知の狭さ）。実際の
cons3n は `MirrorCore.checkC3Family` の forbidden 分岐で**任意長**（三連・五連等）を正しく評価できるため
（source of truth 側は元から一般的）、枝刈り側だけが取り残されていた。
- **`Problem.makesForbiddenRun(schedule, i, j, newK)`** を新設し単一ソース化: 各 cons3n ルール(長さd)に
  ついて j をカバーする開始位置 s の窓を全部調べ、位置jだけ newK に差し替え残りは現状のまま完全一致(z==d)
  するかを判定（MirrorCore の forbidden 判定と同じ意味論）。他セルは変えない=1手の影響範囲チェックとして正しい。
  枝刈り用途のため、仮に見逃しても最終正しさは常に checker が担保（安全側）。
- 3箇所とも1行で置換: `findCovUChain`/`c3nHit`/`c3nAt` は全て `p.makesForbiddenRun(...)` を呼ぶだけに簡略化
  （`V6LateOperators` は既存コード・`sched` が職員行ごとに独立=同日循環スワップの判定と意味論が一致することを確認）。
  C++側（cons3n を使う fullEval/contribC3RowFam 等）は元々任意長対応済みで変更不要。掃討済み: `cons3` の
  `seq.size==2` フィルタ(HF356「2連続必須の孤立検知」)は別機能で対象外（禁止連続でなく必須連続の孤立検知）。
- ユニットテスト `ChainFillTest`: `makesForbiddenRunDetectsTripleAndQuintuple`（三連/五連の直接検証・positive/negative）
  ＋ `chainFillAvoidsTripleForbiddenRun`（連鎖探索が三連トラップを避けて安全な候補へ着地）を追加。
  スコアリング不変・枝刈りロジックの一般化のみ。
- (3.157.1, 玉突きの三連・五連=多人数連鎖の深さ検証): ユーザー指摘「玉突きの三連、五連なども配慮する」。
  `findCovUChain` は元々 `maxDepth=5`（最大5人の玉突き）に対応済みだったが、テストは深さ1・2のみで
  3人・5人連鎖は未検証だった。各シフトを隣接シフトのみ担当可能な群で一本道につなぎ、末端を過剰配置にした
  盤面で `chainFillSolvesDepth3Cascade`/`chainFillSolvesDepth5Cascade` を追加し、BFS が3手・5手の連鎖を
  正しく1発で見つけること（設計上その深さでしか covU が解消しない一本道のため、乱数シードに依らず一意）を
  検証。あわせて `findCovUChain` の docstring の「長さ2 c3n の前後プルーニング」という古い記述を
  3.157.0 の一般化後の実態（任意長・makesForbiddenRun）に合わせて訂正（HF77: コメント≠実装の解消）。
  スコアリング不変・テスト追加のみ。

## C1研磨への多人数ブロック移動の反映（3.158.0）
ユーザー指摘「C1の研磨にも反映する」。既存4研磨パス（`applyCyclicSwapPolish`/`applyC3SequencePolish`/
`applyBlockRotationPolish`/`applyC1WindowPolish`）は全て本物の checker（`UnifiedViolationChecker.check`→
`isBetter`）で採否判定しており禁止連続は既に任意長で正しい（3.157.0の対象外）ことを確認したうえで、
`applyC1WindowPolish`（C1Polish）固有の別の穴を特定: c1不足の職員 i を窓充足シフト X へ動かす手が、
**「その日 X に既に在勤中の直接交換相手がいる場合」しか試みておらず**、相手がいない/不採用なら諦めていた
（E11で covU に対処した「直接候補が全員ブロックされ玉突きでしか埋まらない」局面と同型の取りこぼし）。
- `applyC1WindowPolish` に E11 の `findCovUChain` をそのまま反映: 直接スワップが不成立のとき、i を X へ
  動かし、空いた旧シフト a の穴を玉突き連鎖で埋め直す（a に need1 が無い/余裕があるなら連鎖不要でそのまま
  採用判定）。i の移動＋連鎖手を1候補としてまとめ、実目的関数(isBetter)で評価。不採用なら連鎖手も含め
  正しく全巻き戻し。`seed: Long = 0x1C1L`（既定値, 決定的）を追加パラメータとして新設（既存呼出は非破壊）。
- **[敵対的レビューで判明した実バグ修正]** `findCovUChain` を「i を x へ動かした直後」に呼ぶため、a を埋め戻す
  候補探索が **i 自身（x→a に戻る）を選び得て、i の移動そのものを打ち消す退行手になり得た**（見つけていなければ
  無限ではないが無駄・意図しない解に着地するリスク）。`findCovUChain` に `exclude: Int = -1` パラメータを新設し
  （全既存呼出はデフォルト維持=非破壊）、C1側から `exclude = i` を渡して自己選択を防止。
- ユニットテスト `c1PolishSolvesViaChainWhenNoDirectSwapPartner`: 直接交換相手が存在しない局面
  （i の担当シフト a を唯一在勤者として持ち、過剰配置の別シフト b から玉突きで補充できる）で、
  旧実装なら頭打ちだった c1 不足が新パスで解消することを検証。解の一意性を保証するため候補を非対称化
  （対称だと乱数シャッフル順で結果が変わりテストがフレークするため）。スコアリング不変・退化不能。

## 敵対的コードレビューで判明した2件の修正（3.159.0）
ユーザー指示「愚直にコードトレースしてコードレビューする」→「修正する」。PR#19マージ後の全差分(12コミット)を
手動で行単位トレースし2件を確認・修正。
- **[CONFIRMED, 正しさ] `findCovUChain.tryComplete` の静的ヘッドカウント補正**: 終端判定(その職員が
  シフトmを抜けても covU が増えないか)が探索開始時点の静的 `cnt[m]` をそのまま使っており、3段以上の
  連鎖では祖先ノードの適用で実際の m のheadcountが変わりうるため不正確だった（呼出3箇所すべてが外側
  keep-best(isBetter/checker)で最終ガードされるため誤採用はしないが、判定自体の精度が甘かった）。
  祖先を辿って m への**「到着」(+1: 祖先の fillShift==m)と「離脱」(-1: 祖先の元シフト==m)を両方**加味した
  真のheadcountで判定するよう修正。**[重要]** 初版修正は到着のみを補正する不完全なものだったが、自己の
  敵対的再検証で「祖先が m から離脱しつつ別の祖先が m へ到着する」3段連鎖（P←Q←M型）だと離脱を見逃して
  headcountを過大評価し、実際には別シフトの covU を悪化させる連鎖を安全と誤判定しうる(false accept)ことを
  発見・出荷前に是正。回帰テスト `chainFillNeverBreaksAnotherShiftViaStaleAncestorCount`
  （P(need1,0人)←Q(need2,2人=a,k1)←M(need1,1人=g) の連鎖で、a:Q→P・g:M→Q・k1:Q→M という手は
  正味 Q から2人抜け1人しか戻らずQを壊すため、正しい修正なら null=見つからないことを固定）を追加。
  既存の depth1/2/3/5 連鎖テストは全て祖先とターゲットのシフトが重複しない一本道構造のため adj=0 で
  不変（手動トレースで確認・退行なし）。スコアリング不変（探索の枝刈り精度向上のみ・最終防波堤は不変）。
- **[CONFIRMED, 表示の整合] CoverageDiagnosis の内訳がcapacityと合わない**: 3.156.0 の4分類
  (空き番/玉突き/希望固定/禁止連続)が「既にこのシフトに在勤中」の職員を素通りしており、
  `free+cascade+pinned+forbid` の合計が `capacity`（担当可能人数）と一致せず表示が混乱を招いていた。
  `already`（在勤中）を明示計上し「担当可能N人（うち在勤中M人）」を追記、内訳4分類は移動候補のみを
  対象とする既存の意味論を維持。読取専用・スコア不変。

## 希望シフトカレンダーのインタラクティブ化（3.168.0）
ユーザーが第3のモックアップ（希望シフト登録画面）を提示。3.167.0の必要人数カレンダーと同じ
方針転換をWishCardにも適用（AskUserQuestionのタイムアウトにより明示確認は取れなかったが、
本セッション内で同型の再設計を2回繰り返し確定済みの方針＝カレンダーをタップ選択の主導線にし
旧モーダルは廃止・全件横断の一覧は確認用に温存、を踏襲）。
- **NeedCalendarCardとの違い**: 必要人数カレンダーは「シフトを選び月間の需要を見る」(シフト軸)
  だったのに対し、希望シフトカレンダーは**「職員を選び月間の希望を見る」(職員軸)**。日セルには
  数値レンジでなく**登録済みシフトの記号チップ**を表示し、色は severity ではなく**シフトの表示色**
  （既存の`ui.shiftColorHex`/`shiftTextHex`＝グリッド・集計と同じ色語彙、`resolveShiftColor`由来）
  をそのまま流用（新規の色システムは作らない）。「1日1個のみ登録」は`wishes["i,j"]`が単一値の
  Mapである既存モデルにより自動的に保証される（追加ロジック不要）。
- 実装（`WishEditor.kt`全面刷新）: 職員チップ（複数可・FlowRow）→`WishMonthGrid`（タップで複数日
  選択、登録済み日はシフト色チップ表示）→下部固定パネル（希望シフト選択・担当外は赤枠+⚠・
  「選択したN日間に適用する」）。旧`WishDialog`は廃止（モーダルでの単発編集をカレンダーへ統合）。
  全職員横断の登録済み一覧はNeedDayCardと同じ理由（カレンダーは1職員ずつしか見えない弱点を補う）
  で確認・削除専用に温存（タップで対象職員へジャンプ、×で削除）。
  `NeedDayEditor.kt`の`DayPickerGrid`（旧WishDialogの唯一の呼出元）は呼出ゼロになったため削除。
  `MagiApp.kt`の`wishQuickAdd`（旧WishDialog自動オープン用）も同時に削除（WishCardが常時展開の
  カレンダーになったため、案内カードからは月次条件タブへの遷移のみで到達可能）。
  新規ロジックなし・既存のwishes/removeWish/setWish呼出と既存シフト色トークンの読取のみ＝スコアリング不変。

## 必要人数カレンダーのインタラクティブ化（3.167.0）
ユーザーが第2のモックアップ（必要人数設定/Dテスト画面）を提示し「あなたが見直します」と再改訂を指示。
3.166.0で作った表示専用の`NeedCalendarCard`から方針を転換するため grilling で5問詰めた:
- **カレンダーをタップ可能にし主要な編集導線にする**（表示専用から転換）。
- **旧NeedDayCard（追加/編集ダイアログ）は廃止**、一覧は「全シフト横断で例外を一目確認・削除する」
  専用ビューとして温存（カレンダーは1シフトずつしか見えない弱点を補う）。
- **「基本の必要人数」（シフト既定need1/need2、従来Ws1Card専任）もこの画面で直接編集可能にする**
  （ユーザー選択・Ws1Cardとの二重入口を許容）。
- **日セルの色分けドットは「現在の勤務表の実際充足度」**（設定値の静的比較ではなく、実際の配置人数
  vs 需要）。TallyCard/グリッドの既存語彙（赤=covU=不足・橙=covO=過剰）にそのまま統一。
- **月送り(<>)ナビは導入しない**（1state=1か月のスナップショットモデル、D6決定と整合。常に現在の
  1か月を表示）。ヘッダの戻る矢印/？/ハンバーガーも既存アプリのCard内蔵ナビ様式に合わないため非導入。

実装（`NeedDayEditor.kt`）: `NeedCalendarCard`にタップ選択(`onToggleDay`、DayPickerGridと同じトグル
方式)＋下部固定パネル（選択日数・最低/最高ステッパー・「選択したN日間に適用する」）を追加。
`NeedMonthGrid`は表示専用から選択可能グリッドへ刷新し、日セルへ`ui.needViolations["k,j"]`
（既存の実効チェック結果、read-only）を突合して赤/橙/緑/灰の4色ドット＋選択時はチェックマーク表示に。
「基本の必要人数」ボックスは`vm.ws1().shifts[k]`から直接表示し、新設`vm.setShiftNeed(k,need1,need2)`
（`ws1EditShift`の狭い版・name/kigouは不変のままneed1/need2だけ更新）でインライン即時編集。
`NeedDayCard`は一覧+削除ボタンのみに簡略化（`NeedDayDialog`/`NeedDayEdit`は呼出ゼロのため削除）。
新規ロジックなし・既存の実効値/チェック結果の読取＋既存setNeedDay/setShiftNeedの呼出のみ＝スコアリング不変。

## 必要人数カレンダー＋希望シフトの既存登録可視化（3.166.0）
ユーザー提示のモックアップ画像（必要人数カレンダー: シフト種類チップ＋月全体カレンダーに実効need
をインライン色分け表示＋設定済/未設定サマリー）の長所を、既存の「日別の必要人数（例外）」画面
（`NeedDayCard`=一覧＋追加ダイアログ）に取り入れるかgrillingで詰めた。ユーザー選択: **既存の一覧＋
ダイアログの操作系は温存し、カレンダー概観を「追加」するのみ**（丸ごと置換ではない）。カレンダーは
**表示専用**（タップ不可、編集は従来どおり下の一覧から）。
- 新規 `NeedCalendarCard`（`NeedDayEditor.kt`）: シフト種類チップ（`InputChip`・1つ選択）で対象シフトを
  絞り込み、月全体を`NeedMonthGrid`（日曜始まり・読取専用）で一覧。各日は`vm.needCellLimits(k,j)`
  （既存API＝シフト既定need1/need2と日別例外needDay1/needDay2を統合済みの実効値、Problemが source）
  をそのまま使い「lo-hi人」チップ(緑・設定済)/「未設定」(グレー)で色分け。設定済N日/未設定N日の
  サマリーも表示。`MagiApp.kt`の月次条件スコープでWishCardとNeedDayCardの間に配置。
  新規ロジックなし・既存の実効値取得APIの読取表示のみ＝スコアリング不変。
- **実機指摘「登録済みの希望シフトが表示されていない」の修正**: 希望シフト追加/編集ダイアログ
  （`WishDialog`）の日選択カレンダー（`DayPickerGrid`）が、今回の選択状態しか示さず、その職員が
  既に登録済みの希望（他の日）が見えなかった。`DayPickerGrid`に`markedDays: Map<Int,String> =
  emptyMap()`（既定=空で既存呼出は無変更）を追加し、`WishDialog`が選択中の職員の既存希望
  （`rows.filter{it.i==idx}.associate{it.day to it.kigou}`）を渡して日番号の下に小さくバッジ表示
  （桃色=`MagiAccent.pink`＝CLAUDE.md定義の「希望」意味色と整合、`ensureReadable`でコントラスト確保）。
  マーク表示時のみセル高を40→50dpへ拡張（バッジ無しの呼出=NeedDayEditor側は40dpのまま影響なし）。
  表示のみ・スコアリング不変。

## 「Dﾃ-Dﾃ」仮説の検証＝隣接日調整の全候補探索を実データで確認（3.165.0）
ユーザー指摘「残り3人（金沢勇輝・モニカ・アリフ）は、夜勤に動かすと別の禁止連続パターン（Dﾃ-Cｳ、Dﾃ-休-Aｱ、
Dﾃ-Cｱ）に触れてしまうのであれば、Dﾃ-Dﾃを検証する」。実機ログ由来の3名を、実データ(state.json, 実際の
cons3n=Dﾃ-A4/Aｱ/Cｵ/Cｱ/B4/Cｳ/B1・Dﾃ-休-A4/Aｱ の3連含む)を Python で忠実リプレイして検証:
- **結論=3名とも既存の `tryFixC3nViaAdjacentDay`（3.163.0）の altOrder 走査で解決可能**（追加実装不要）。
  ただし「同じシフトの繰り返し(Dﾃ-Dﾃ)」自体は万能ではないことも判明: altOrder は休を最優先し、次に
  担当可能シフトを順に試すため「同じシフトの繰り返し」は自然に2番目辺りで試されるが、翌々日が別の
  禁止連続の相手（例: モニカの Dﾃ-休-Aｱ→隣接日を Dﾃ にしても Dﾃ-Aｱ で新たに触れる）だと単体では
  不成立＝**問題を1日先へずらすだけ**になる。しかし実装は1つの alt で諦めず altOrder 全体（休→担当可能
  シフト全種）を試すため、金沢勇輝(Dﾃ-Cｳ)・アリフ(Dﾃ-Cｱ)は日別の翌々日が空いていれば即座に休で解決、
  モニカ(Dﾃ-休-Aｱ)のように繰り返しも直接候補も両方塞がる局面でも「有」等の第三の安全なシフトで解決する
  ことを確認（実データ照合スクリプトで3パターンとも成立を確認）。
- **回帰テスト追加**: `ChainFillTest.chainFillAdjacentFixTriesRepeatShiftThenFallsBackToSafeAlternative`
  （P-N 2連禁止＋P-休-N 3連禁止の最小構成。「繰り返し(P-P)」を試すが P-N で新たな禁止連続に触れ不成立→
  第3のシフトOで解決することを固定）。既存 `chainFillResolvesC3nBlockViaAdjacentDayFix`（P-P-P三連・
  日を変えて解決）とは異なる形（3連パターン・繰り返し失敗→exhaustive探索で解決）をカバーし、実装が
  「最初の1手で諦めない」ことを保証する。ロジック変更なし・検証専用のテスト追加のみ。

## 希望シフト登録の見つけやすさ改善（3.164.0）
ユーザー要望「希望シフトの登録画面の新規追加する。職員選択、シフト種類選択、カレンダーによる複数日選択して、
職員毎に一括して希望シフトが登録変更確認できるようにする」。調査の結果、要望内容（職員選択→シフト種類選択→
カレンダー複数日選択→職員ごとの一括登録・変更・確認）は既存の`WishCard`/`WishDialog`（`WishEditor.kt`、
編集タブ→月次条件）で**単一職員に対してはほぼ完全に実装済み**と判明。確認したところユーザーはこの既存機能を
知らず「見つけやすさの改善」が真のニーズだったため、**新規画面は作らず既存入口の到達性を改善**した:
- `SetupGuideCard`（編集タブ最上部・editScopeに関わらず常時表示）の「希望シフト」行と、
  `MonthlyChecklistCard`（月次条件タブ先頭）の「希望・休暇」行を**タップ可能化**し、タップで
  editScope=0（月次条件）に切替えつつ`WishCard`の希望追加ダイアログへ**直行**する。
  タップ可能な行は「›」を付し文字色をprimaryに変えて可視化（`GuideRow`/`ChecklistRow`にoptionalな
  `onClick`パラメータを追加、非タップ行は従来どおり）。
- `WishCard`に`autoOpenAdd: Boolean = false`/`onAutoOpenConsumed: () -> Unit = {}`を追加し、
  `LaunchedEffect(autoOpenAdd)`で自動的にダイアログを開き即座に消費フラグを戻す（タブ再訪時の
  意図しない再オープンを防止）。`MagiApp`に`wishQuickAdd`状態を新設し両カードへ共通の`openWish`
  コールバックとして配線。新規画面・新規データモデルなし・表示/導線のみ＝スコアリング不変。

## covU多人数連鎖(E11)を禁止連続の回避=隣接日調整へ拡張（3.163.0）
ユーザー指摘「残り1人は動かすと連続禁止ルール（c3n）に触れるため使えないのであれば、連続禁止ルールの
並びにならないようにする」（CoverageDiagnosisの「禁止連続」ブロック候補を見て）。grillingで「隣接日調整
自体が新たな不足/禁止連続を生む場合の扱い」を確認 → **そこも玉突き連鎖として深掘りする**方針で確定。
- `findCovUChain`（`V6SearchOperators.kt`）の `candidates()` で、候補 i が禁止連続(c3n)に触れて除外され
  ていた箇所を拡張。即除外せず `tryFixC3nViaAdjacentDay(i, fillShift)` を試す: 隣接日(j-1/j+1)の i 自身の
  割当を別シフト（休を優先、続けて担当可能シフト一覧）へ変えてパターンを崩せるか、`makesForbiddenRun` で
  day j・day j2 双方の安全性を確認。崩した隣接日の元シフトが covU 悪化を招く場合は、**同じ `findCovUChain`
  を `allowCrossDayFix=false` で1段だけ再帰**し玉突き連鎖として埋め直す（cross-day 再帰は1段のみに制限＝
  無限展開防止。同日内の同一BFS(`maxDepth`)自体は従来どおり最大5人まで）。見つかった追加手は
  `Node.extra: List<IntArray>?` に積み、`tryComplete` の手順収集時に合流する（day j 本体の手＝
  `[(i,j,fillShift)]` はそのまま・day j2 の手＋サブ連鎖は `extra` として付随）。盤面は判定中に一時変更する
  が、成功・失敗いずれの分岐でも呼出前に必ず復元（本関数の「盤面を変更しない」契約は不変）。
- `findCovUChain` に `allowCrossDayFix: Boolean = true` パラメータを新設（全既存呼出はデフォルト維持＝
  非破壊。RSI focus/エピローグ/C1Polish はいずれもこの新機能の恩恵を自動的に受ける）。
- ユニットテスト: 既存 `chainFillAvoidsTripleForbiddenRun` は新機能により結果が非決定的になり得たため
  （bがaの隣接日肩代わりに使え、RNG次第でaかbかが変わる）、bへ day0/day2 の希望固定を追加して隣接日調整
  の対象外に固定し、従来の「三連トラップを避けてbのみ使う」という元の検証意図を保った（決定的に復元）。
  新規 `chainFillResolvesC3nBlockViaAdjacentDayFix`: bをday1のみ希望固定（直接候補から除外）し、aの
  隣接日調整＋玉突き（day0を休へ・bがday0のPを玉突き充填）でcovUとc3nが両方解消することを検証。
  スコアリング不変・退化不能（最終防波堤は既存のkeep-best/isBetter、呼出元は全て変更なし）。

## 対応OSをAndroid 16以降のみに変更（3.162.0）
ユーザー指示「Android 16以降のみ対応する」。`minSdk` を 35(Android 15) → **36(Android 16)** へ引き上げ
（compileSdk/targetSdkは元々36で変更なし）。API 36未満の端末は対象外になる（Google Play配布時は
インストール不可端末が絞られる）。関連コメント（`app/build.gradle.kts`のarm64限定理由・
`OptimizationWorker.kt`のforegroundServiceType注記）を更新。`ForegroundInfo`生成は元々SDK_INT分岐が
無い（常にFOREGROUND_SERVICE_TYPE_DATA_SYNCを指定）ため、コメント修正のみでロジック変更は無し。
CI（android-sdk/release-build/v6-engine-check）は`platforms;android-35`も引き続きインストールするが
実害なし（AGPが不要なら単に使わないだけ・除去は本変更のスコープ外）。

## 未レビュー領域の再監査（3.161.0）
ユーザー指示「未レビュー領域の再監査」。3.84.0以降に積み重なった大量の変更（ネイティブ第1〜3期・E7〜E11・
Gradle9移行等）を対象に、5系統（ネイティブC++/JNI・SA/ALNS/RSI探索本体・修復研磨パス・UI/ViewModel層・
診断分析層）へ並列サブエージェントを起動し再監査。ネイティブ/JNIとSA/ALNS/RSI探索本体は正しさバグ0
（番兵・keep-best・ハンドル破棄とも健全）。以下、CONFIRMEDな指摘を修正:
- **[CONFIRMED, 重大・実害あり] 最適化実行中のセル編集で配列の別名共有によりデータ消失/違反表示不整合**:
  `runV6FullOptimize`/`start`/`runSoftPolish`は`val sched0 = currentSchedule ?: return`で**参照をそのまま**
  保持し数十〜300秒使い続けるが、`setCell`/`setCells`/`cycleCell`/`applyFixSuggestion`は`currentSchedule`を
  **同一配列へin-placeで直接変更**する。実行中にグリッドをタップして編集すると、良化時は編集が無言で
  上書き消失し、劣化時(`worseThanInput`分岐)は編集後の盤面と実行開始時点の`baseReport`(編集前基準)が
  食い違って誤った位置に違反が表示される。3.127.0でバルクシート/希望一括シート/AlternativesCard/
  WishApplyCardには`!ui.running`ガードを追加済みだったが、単発セル編集(ShiftPickerSheet経由の`setCell`)と
  改善提案適用(`applyFixSuggestion`)が対象漏れだった。`setCell`/`setCells`/`cycleCell`/`applyFixSuggestion`
  （`MagiViewModel.kt`）に`_ui.value.running`ガード＋案内メッセージを追加、`FixSuggestionCard`の適用ボタン
  （`MagiDashboardCards.kt`）も`enabled = !ui.running`に統一。
- **[CONFIRMED, 予算超過リスク] 5研磨パスのO(S²)内側ループに`shouldStop()`欠落**: `applyCyclicSwapPolish`
  （k=2/k=3）・`applyC3SequencePolish`・`applyC1WindowPolish`・`applyGroupShiftEqualizePolish`・
  `applyWeeklyEqualizePolish`が、日(j)ループ先頭のみで締切確認し内側の職員×職員(O(S²))二重ループには
  確認が無かった（HF66=2.65.0・BlockRotationPolish=3.84.0で既に修正済みの「内側スキャンでも締切確認する」
  方針の対象漏れ）。職員数が多いデータで締切超過後も1日分のフルスキャンが走り切りうる。各パスの外側
  職員(a)ループ先頭に`shouldStop()`を追加。keep-best不変・時間予算の逸脱のみを解消。
- **[CONFIRMED, 診断バグ] `V6PortAnalyzer.buildStaffProfiles`が休記号改名時に全日を勤務と誤カウント**:
  `rest`をローカルの`indexOfFirst{kigou=="休"}`で再計算しフォールバックが無く、休記号改名で`rest=-1`に
  なると`schedule!=rest`が常に真＝全職員の勤務日数が常に全期間日数と誤表示された。`weekly`で3.103.0に
  修正済みと同型のバグ（対象漏れ）。`Problem.restIdx`（`?:0`フォールバック付き・source of truth）に統一。
- **[CONFIRMED, 診断バグ] `CoverageDiagnosis`が希望と移動先が一致する候補を「希望固定」と誤分類**:
  `diagnoseCoverage`の候補分類は事前フィルタ(`w!=k`除外)により残る候補が`wish==-1`か`wish==k`のみなのに、
  その後`p.wishLocked(i,j)`（=希望が設定されている、の意味）を「動かせない」判定に使っていた。この文脈で
  `wishLocked==true`は必ず`wish==k`（=まさに動かしたい移動先と希望が一致）＝本来は**最も動かすべき候補**
  （移すと covU と pref を同時に解消できる）なのに「固定」表示は意味が逆転していた。この行を削除し
  free/cascade判定へ委ねる（他シフトへの希望固定は既にcapacity計算の外側フィルタで除外済みのため、
  本関数内で「希望固定」に該当する候補はそもそも存在しない）。
- **[CONFIRMED, HF77=コメント≠実装] `runAlns`のGLS penaltyコメント訂正**: 「再構築は restart 毎のみ」と
  コメントされていたが、実装は`runAlns`呼出につき1個の`GlsPenalty`をrestartループの外側で生成し全restart
  間で共有（decayのみ希薄化、再構築は`runAlns`が新規に呼ばれた時のみ）。globalBestは生スコア別管理のため
  正しさは不変（keep-best）＝実害なし・コメントのみ訂正。
- **報告のみ（未修正・判断/測定待ち）**: ①`V6SanityPort`のc1「壁」判定が非休シフトの供給見積りに`need2`
  （covOのSOFT目標）を実質的なハード上限として使っており、covO(重み1.0)を犠牲にc1(重み4)を解消する
  トレードオフが数学的に可能な局面を過大に「構造的不能」と報告しうる（3.76.0の「false wallを出さない」
  設計意図と逆方向）。真の供給上限の再定義（canDo職員数ベース等）を要する設計変更のため、実データでの
  影響確認まで保留。②`CoverageDiagnosis`のneed1のみ判定（need2<need1の逆転データでOR救済を無視、通常
  運用では無害な理論的エッジケース）。③`hf80PostPolish`のE10停滞早期終了が、native→Kotlin番兵発火時に
  native区間の経過時間を引き継がず停滞時計を再スタートする（異常系=番兵不一致時のみ発現、実害は電池/時間
  の節約が一部効かなくなる程度）。④`nativeAlnsSetCur`（JNI関数）がKotlin側から一度も呼ばれていないデッド
  コード（挙動に影響なし、C++変更のコスト対効果が低いため未対応）。

## Gradle 9 移行（3.160.0）
ユーザー指示「Gradle 9移行する」。ビルド基盤を Gradle 8.7/AGP 8.6.0/Kotlin 1.9.24 から
**Gradle 9.3.1 / AGP 9.1.1 / Kotlin 2.3.21（AGP 9 の内蔵Kotlinサポート＋KGPオーバーライド）**へ移行。
（公式ドキュメント確認: AGP 9.1.1 の最小/既定 Gradle は 9.3.1・JDK 17・SDK Build Tools 36.0.0・
同梱 KGP 2.2.10。この組合せは Android 公式リリースノートに明記された自己整合な組み。）
- **`org.jetbrains.kotlin.android` プラグインを撤去**: AGP 9.0+ は Kotlin サポートを内蔵し不要
  （`build.gradle.kts`/`app/build.gradle.kts` 両方）。代わりに **`org.jetbrains.kotlin.plugin.compose`**
  を明示適用（Compose Compiler は Kotlin 2.0+ で独立プラグイン化されたため、使用するKGPの版数と
  一致させる必要がある）。
- **[ユーザー指示「Kotlin 2.3.21以上にする」] KGP を 2.2.10(AGP 9.1.1 既定同梱) → 2.3.21 へオーバーライド**:
  公式手順（`buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21") } }`
  を root `build.gradle.kts` の `plugins{}` より前に追加）で明示上書き。既定より低いKGPを指定した場合は
  AGP が自動で 2.2.10 へ引き上げるが、より高い版数は明示 classpath 指定でのみ有効という公式仕様に準拠。
  Compose Compiler プラグインの版数も 2.3.21 へ追従（Kotlin本体と版数を一致させる必要があるため）。
- **`kotlinOptions{jvmTarget="17"}` を撤去（置換ではなく削除）**: 内蔵Kotlinの `compilerOptions` は
  `android{}` 直下ではなく別の場所にあり、初版で `android.compilerOptions{jvmTarget=...}` として実装した
  ところ CI（release-build.yml）で `Unresolved reference 'compilerOptions'` と実際にビルド失敗、公式ドキュメント
  再確認で誤りと判明（"You don't need to explicitly set jvmTarget... it defaults to
  android.compileOptions.targetCompatibility" に訂正）。**jvmTarget は既存の `compileOptions.
  targetCompatibility=17` から内蔵Kotlinが自動継承するため、ブロックごと削除**が正しい修正（HF77非該当だが
  「CIの実結果で検証する」という本移行自身の方針どおり、誤りをCI失敗で検出→即訂正した実例）。
- **`composeOptions{kotlinCompilerExtensionVersion}` を撤去**: 独立プラグイン化で無効・不要
  （版数は `org.jetbrains.kotlin.plugin.compose` が一元管理）。
- `gradle/wrapper/gradle-wrapper.properties` の distributionUrl を 9.3.1 へ。CI 3ワークフロー
  （android-sdk/release-build/v6-engine-check）は wrapper でなく system Gradle を直接ダウンロードして
  PATH へ通す方式のため、各ワークフローの「Install Gradle」ステップも 9.3.1 へ同時更新（wrapper だけ
  更新しても CI には反映されない構成のため必須）。あわせて `build-tools;36.0.0` を追加インストール
  （AGP 9 の最小要求。既存の 35.0.0 は後方互換のため残置）。
- **意図的に不変**: NDK(26.1.10909125)/CMake(3.22.1) は明示固定のため AGP の既定値変更（NDK既定は
  28.2.13676358 化）の影響を受けない。Compose BOM(2024.09.02)含む依存関係バージョンは今回のスコープ外
  （Gradle/AGP/Kotlin ツールチェーンのみ）。JDK は既存の temurin 17 のままで AGP 9 の最小要求(17)を満たす。
- **検証方針（HF77非該当・ビルド基盤のみ）**: サンドボックスは Android/Kotlin コンパイル不可のため、
  この移行は CI（v6-engine-check.yml の testDebugUnitTest/assembleDebug、release-build.yml の
  assembleRelease＝ネイティブC++含む全ビルド経路）の実結果で検証する。DSL変更は理論上ハイリスクで、
  実際に初版の `android.compilerOptions` が CI 失敗で誤りと判明→上記のとおり訂正済み（この項目自体が
  その検証記録）。

## 人員不足の「なぜ埋まらないか」内訳（CoverageDiag 拡張, 3.156.0）
実機での繰り返しの「なぜ Cｵ/Cｱ が不足するのか」に**アプリ自身が答える**ため、`V6PortAnalyzer.diagnoseCoverage` の
FIXABLE(充足可能)理由を「担当可能N人・M人移せば充足」止まりから**候補の4分類**へ拡張:「移せる候補」(canDo・別シフト
希望でない・現在このシフト未在勤)を **空き番**(休/過剰から直接移せる) / **玉突き**(引くと別の covU=多人数入替が必要) /
**希望固定**(本人希望で固定) / **禁止連続**(移すと c3n) に分けて件数表示。ヒント文も分岐（空き番>0=「直し方を探す」で解消可 /
玉突き>0=ブロック移動が必要 / それ以外=希望調整か担当追加）。読取専用・スコア不変（`reason` 文字列のみ変更＝ログ/カード両方へ反映）。

## 多人数ブロック移動（勤務→勤務連鎖, 3.155.0）
実機 2026-08 データでユーザーが手作業で見つけた covU の解（8/11 モニカ B4→Cｵ／8/17 上條 Cｵ→Cｱ・山本 →Cｵ）を
最適化器が見つけられなかった。**根本原因**: 既存修復オペレータ `destroyRepairDay` は「**休→勤務**」でしか穴を
埋めず、候補が全員 (a)希望ロック (b)単一被覆シフト在勤=引き抜くと玉突き covU (c)禁止連続 に当たる局面で必要な
「**勤務→勤務**（過剰シフト/連鎖から引く）」を試さない。ランダム探索(ChainSwap/destroy-repair)は確率的にしか
踏めず、7500万反復×5並列でも 5仮説すべて必須3で同一収束していた。
- **`findCovUChain`(V6SearchOperators)**: covU セル (k0,j) を同日・多人数の玉突き連鎖で充填する交代連鎖を BFS
  （最短優先）で決定的に探索。「k0 を i が埋める→i が空けたシフト m を次が埋める→…→空けても covU が増えない
  シフト(需要0 or 余裕あり=過剰/休)で終端」。リンク条件=canDo・非wishLocked・長さ2 c3n の前後枝刈り・同一職員/
  シフト再訪なし・深さ≤5。同日内交換=被覆総量保存。返り値=適用手[(i,j,newK)]（盤面不変・採否は呼出側 keep-best）。
- **配線**: ①`rsiGenerateHypothesis` covU/c41/c41s focus の先頭で `applyCovUChains`（全 covU セルに連鎖適用・ラウンド
  better() でゲート）②optimize エピローグで covU>0 のとき keep-best 照合つきの保険パス（ALNS単独/covU非focus経路でも走る・
  ログ `ChainFill`）。スコアリング不変・退化不能（checker 照合）。ユニットテスト `ChainFillTest`（深さ1/深さ2＝8/11・8/17
  相当）で covU=0 到達・hard 非増加を検証。

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

- (3.150.0, 実機ログ定量起因＝高速化＋focus の状況適応3点): ユーザー指示「1.高速化 2.停滞脱出を状況に応じて賢く」。
  実機ログ2本(10/12シフト, 300s AUTO)の定量分析: **HF80 PostPolish が 45s枠を走り切り改善0（40.977s/40.988s の2例）**、
  **RSI round=4 が focus=groupViol(件数0)の空振り~21s**、**c3n focus ×3R 連発(計~63s)で件数不変**(HF63 恒久判定は約3R要)。
  ①**[E8] 件数0の族を focus しない**: `maxViolatedFamily` SOFT フォールバックの `bestCount=-1`→`0`（旧: 非avoid正件数族が
  order に無いと先頭 groupViol=0 が当選→hf67 のクリーン盤面 no-op 仮説で1R空振り）。該当なしは "total"=全違反セル hint の
  汎用修復(destroyRepairViolations は focus 非依存・空ヒントは randomAllowedCell)。②**[E9] 空振り focus の1R冷却**: 候補
  不採用＋focus族件数不変の「完全空振り」ラウンド直後だけ同 focus を回避(c3n→c1→c3n…交互へ多様化)。進展あり/族件数が
  減った(方向有望)場合は冷却しない。恒久除外は従来どおり HF63 のみ・N4 発火条件(dynamicAvoid)には混ぜない。
  ③**[E10] hf80PostPolish の停滞早期終了**: best が枠の1/5(下限3s)無改善で早期 return(ログに「停滞早期終了」併記)。
  keep-best＋入力比番兵で品質不変＝時間/電池のみ節約(2.65.0/2.67.0 と同方針)。3点とも focus/時間配分のみ＝スコアリング
  不変・keep-best 維持（bench は RSI focus/polish を模擬できないため 3.74.0/3.95.0 と同じ原理採否）。
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

- (3.145.0, シンプルデザイン融合①=シフト集計の凡例): 実機スクショのシンプルな集計画面を融合する第1歩。
  TallyLegend を **1行凡例「▼不足 ▲超過 — 対象外」** に統一（旧: 呼出側が「回数が下限/目標未満」等の
  長文ラベルを渡していた4引数版）。タブ名(職員別/日別)が不足/超過の意味を文脈で示すため長文を撤去し、
  詳細はセルタップで出す方針。色見本＋▼▲＝色覚二重符号化は維持。呼出2箇所(職員別/日別)を2引数へ。
  表示のみ・スコアリング不変。**未対応(次段候補)**: 集計期間の読取ラベル・操作ヒントのアイコン化
  （期間バーの<>ナビは月スナップショットモデルと衝突するため read-only ラベルに留める想定）。
- (3.146.0, シンプルデザイン融合②=集計期間ラベル＋操作ヒントのアイコン化): TallyCard に read-only の
  **「集計期間 YYYY年M月D日(曜)〜…」** ラベルを追加（startDate〜startDate+(days-1)・曜日付き・パース失敗時は非表示）。
  スクショの期間バーの <> ナビは月スナップショットモデル（集計は常に現在の全期間）と衝突するため付けず、
  読取ラベルに留める。操作ヒントをアイコン化: 職員別「ⓘ タップで内訳と直し方」／日別「ⓘ タップで内訳と直し方
  ・👆 左右スワイプで他の日」。表示のみ・スコアリング不変。
- (3.147.0, 設定ミス案内から VBA ワークシート符号(wsN)を除去): 実機に出る設定ミス/診断ガイダンスの文言に
  VBA 由来の内部符号「(ws1)〜(ws5)」が22文字列・29箇所で露出していた（例「個人下限を下げる(ws1)か、必要人数を
  増やしてください(ws2)」）。各文はすでに平文で対象を名指し（設定・必要人数・希望・連続パターン設定・個人上下限・
  担当範囲）しているため、符号は冗長な相互参照＝**削除で誤誘導リスクゼロ**。「(ws1/担当範囲)」のみ意味語を残し
  「(担当範囲)」に。対象=V6SanityPort/V6HotfixPasses/MagiDashboardCards/StaffRangeEditor の**文字列リテラルのみ**
  （コメント内の wsN=内部参照は不変。テスト依存なし=grep 確認済み）。paren balance は HEAD と同一（符号は括弧対で
  除去＝中立）。文字列のみ・スコアリング不変。operator_ux「英字符号を画面に出さない」に整合。
- (3.148.0, covO 重みを 0.5→1.0 に統一＝目的関数の最後の乖離を解消): 実機ログ(83cc183a, 12シフト)の算術照合で
  **評価器 soft=1912 vs チェッカー weightedScore 再構成=1903（差9=covO×(1.0−0.5)）**を発見し、コード照合で確定:
  最適化器(`Evaluator.kt:155`/`DeltaEvaluator.kt:87`/`magi_native.cpp` fullEval＋slice)は covO を **amount×1.0** で加算
  していたのに、チェッカー(`MirrorCore.kt:67` weightedScore)だけ **×0.5**。`Evaluator.kt:11` のコメント「covO は checker と
  同重み」が実装(×1.0)と矛盾＝HF77 の「コメント≠実装」を捕捉。原因は soft が Long で 0.5 を表現できず 2.28.0 統一時に
  整数×1.0 で入ったまま残存。keep-best `better()`(`V6NativeOptimizer:1269`)は既にチェッカー(0.5)基準＝**最終選択は正しく
  ランク済**で、ズレは SA 探索コンパスが最下位族を2倍罰する内部のみ＝実効微小。**修正方針(ユーザー選択)**: 真0.5化は
  スコア全体×2＋SA温度/GLS lambda/Lam スケール等の**番兵の無い動学定数**を全連動させる大改修（native 再検証必須・
  取り漏らしで静かに劣化）になるため回避し、**「最適化器を正」として チェッカーを 1.0 に統一**（`MirrorCore:67` の1行、
  最適化器/Delta/C++ は既に 1.0＝変更不要）。これで weightedScore が評価器と一致(1903→1912)し「族寄与==weightedScore寄与」
  の不変条件が復活。副次: `V6WebCompat.severityFromVioKey` の covO を **INFO→WARN**（重み1.0=c2/c41 と同格の実違反、
  集計/グリッドで既に橙=過剰表示のため整合。fair/weekly は「整え・常時非ゼロ」で INFO 維持＝3.85.0 の重大度=重み整合原則）。
  covO の内訳チップ件数(amount)は不変、weightedScore と重大度色のみ変化。C++/native は変更なし＝parity 影響なし。
- (3.149.0, 曜日の偏り/公平化の「場所」表示): 実機指摘「曜日の偏りなどが表示されません」。原因=fair/weekly は
  セル単位でなく**職員(weekly)・群×シフト(fair)単位の偏り**のためチェッカーが `inc()`(件数)のみで `mark()`(セル印)を
  呼ばず、`breakdownLocations` の violationCells 検索が常に0箇所＝「場所情報がありません」だった（かつ意図的に
  グリッドへは出していない＝62セルを枠表示すると 3.99.0 の飽和が再発）。**チェッカー(source of truth)が職員単位の偏り箇所を
  出力する方式**で解決（UI 再計算は restIdx/群/bucket が UiState に無く covO と同型のドリフトを生むため不採用）:
  `ViolationReport.distLocations`("weekly"→[[i,dev]] / "fair"→[[i,k,dev]]、dev降順) を新設し fair/weekly ループで
  偏り職員を収集→UiState→`breakdownLocations` が「職員（曜日の偏り N）」「職員 「シフト」（偏り N）」で整形・タップで
  当該職員へフォーカス。**内訳パネルのみに表示（グリッド不変＝飽和回避）・スコアリング不変**。配線=MirrorCore→
  ViolationReport→UiState→makeUi→breakdownLocations（表示専用フィールド追加、既存構築は全て named 引数＋デフォルトで非破壊）。
