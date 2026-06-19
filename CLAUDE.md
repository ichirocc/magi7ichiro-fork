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
  低/高(staffRange) とは別系統（LimMin/LimMax は別画面 ws5）。旧: 目的関数にもチェッカーにも未統合だった。
- **pref**（希望シフト未充足, HARD, 重み9000）/ **groupViol**（群外シフト, HARD, 重み10000）。

weightedScore 階層: groupViol(10000) > pref(9000) > covU(8000) > c3n(7000) > low(90) > high(45) >
c3mn(12) > c1(4) > c3(3) > c3m(2) > c2/c41/c42/c41s/c42s/apt(1) > covO(0.5)。

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

## バックログ / 未対応
1. TallyCard の読取/編集モード完全整合（result専用検査結果の plumbing）。
2. 未レビュー領域の精読: `V6LateOperators`/`V6SearchOperators`/`V6HotfixPasses` 各パス内部, `V6WebCompat`,
   CSV/UI 層。
3. C++/NDK 移植は**不要**の結論（純Kotlin＋被覆対応Δ評価で十分高速）。エンジンは ALNS/Destroy-Repair/
   ChainSwap3-4/C1BlockN/PathRelink/LNS/Reheat/Oscillation/適応的オペレータ重み/希望ロック枝刈り を実装済み。
   §4 ILP matheuristic のみ意図的に未実装。
4. cons3n のデータ重複（Dﾃ→A4 が2行）は二重計上だが最適化器/チェッカーで一貫（SettingIssue が dedup を提案）。

## 直近の状態
versionName=`2.36.0-apt-unify`（versionCode 43）。目的関数統一は covO/range/c3族(単一+複数連)/c1/apt まで完了。
apt(適切回数)は群単位の双方向目標として Evaluator/Delta/Checker の3者へ重み1・L1偏差で統合し、TallyCard/内訳に
不足(赤)/超過(橙)を表示。Δ==フルは Python で確認済み（apt 単体20,000手で mismatch 0）。
次の自然な続きは c2/c41/c42 等の残り soft 族の重み統一（同じ原則で）か、未レビュー領域の精読。
