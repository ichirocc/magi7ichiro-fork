# CLAUDE.md — MAGI ShiftOptimizer (Android) 引き継ぎ

> このファイルは Claude Code 向けのプロジェクトメモリです。チャット側で進めた作業の引き継ぎを兼ねます。
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
- **pref**（希望シフト未充足, HARD, 重み9000）/ **groupViol**（群外シフト, HARD, 重み10000）。

weightedScore 階層: groupViol(10000) > pref(9000) > covU(8000) > c3n(7000) > low(90) > high(45) >
c3mn(12) > c1(4) > c3(3) > c3m(2) > c2/c41/c42/c41s/c42s/apt/fair(1) > covO(0.5)。

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

検証はすべて Python で「最適化器の族寄与 == チェッカー weightedScore 寄与」「soft<<1e6（hardゲート安全）」
「Δ==フル」を確認済み。

## ユーザー向け機能（実装済み）
- **FixSuggester**（`V6SwapSuggester.kt`）: 7種の手（単一変更/同日交換/複数変更/連鎖/再最適化窓/3人交換/別日交換）を
  deadline内で探索、(deltaHard, deltaTotal, deltaWeighted) でランク、kind+ops署名で重複排除。
  UI: `FixSuggestionCard`（kindチップ＋差分＋適用ボタン）、`BreakdownCard` から「直し方を探す」。
- **TallyCard**（`MagiScheduleViews.kt`、タブ1の勤務表表下）: 職員別（職員×シフト回数）と日別（シフト×日 人数）。
  **違反ハイライト**: 職員別=countViolations(vio-low赤/vio-high橙)、日別=needViolations(vio-covU赤/vio-covO橙)。
  注: 読取モードで `gridUi.schedule=resultSchedule` に差し替わるため、編集後に読取へ切替えた場合のみ
  集計値と違反マップがズレ得る（新規最適化直後・編集中は整合）。完全整合させるなら result専用の検査結果を
  UiStateに持たせる plumbing が必要（backlog）。

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

## バックログ / 未対応
1. TallyCard の読取/編集モード完全整合（result専用検査結果の plumbing）。
2. 未レビュー領域の精読: `V6LateOperators`/`V6SearchOperators`/`V6HotfixPasses` 各パス内部, `V6WebCompat`,
   CSV/UI 層。
3. C++/NDK 移植は**不要**の結論（純Kotlin＋被覆対応Δ評価で十分高速）。エンジンは ALNS/Destroy-Repair/
   ChainSwap3-4/C1BlockN/PathRelink/LNS/Reheat/Oscillation/適応的オペレータ重み/希望ロック枝刈り を実装済み。
   §4 ILP matheuristic のみ意図的に未実装。
4. cons3n のデータ重複（Dﾃ→A4 が2行）は二重計上だが最適化器/チェッカーで一貫（SettingIssue が dedup を提案）。

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
次の自然な続きは c2/c41/c42 等の残り soft 族の重み統一（同じ原則で）か、未レビュー領域の精読。
