# CLAUDE.md — MAGI ShiftOptimizer (Android) 引き継ぎ

> このファイルは Claude Code 向けのプロジェクトメモリです。チャット側で進めた作業の引き継ぎを兼ねます。

> **まず読む（ドキュメント入口）**：設計・仕様・業務ルールは [`README.md`](./README.md) の「ドキュメント目次」から各 `docs/*.md` に分かれています。実装・調査の前にそこで当たりをつけてください。とくに **業務ルール＝[`docs/business-logic.md`](./docs/business-logic.md)**、**データ項目＝[`docs/data-models.md`](./docs/data-models.md)**（存在しない項目を創作しない）。
> **更新ルール（stale 化させない）**：コードを改修したら、影響する文書（特に `business-logic.md` / `data-models.md`）と `README.md` の目次・最終更新を**同じコミットで**更新する。事実が変わりやすい順に独立させているのは、ここを最新に保つだけでハルシネーションの大半を抑えるため。
> **過去の作業記録（必読の引き方）**：版数付きの作業記録355節は本文末尾の「作業記録の索引」に原文見出しだけを置き、
> 本文は [`docs/history/`](./docs/history/) にある（毎ターンの読み込み量を減らすため。3.468.0 で分離し 3.470.0 で完了）。
> **この本体に残っているのは「毎ターン効く恒久の事実」だけ**＝方針・制約の意味・重み・アーキテクチャ・決定記録・
> 検証手段・バックログ。**個々の版で何をしたかは本体には無い**ので、索引から `docs/history/` を引くこと。
> **同じ領域を触る前に必ず索引を検索する**＝このリポジトリは「測って否決した案」「同型のバグ」「決定記録（再提案しない）」を
> そこに積んでおり、引かずに着手すると否決済みの案を作り直す。引き方は `grep -n '（3.409.21' docs/history/3.4xx.md`。
> 新しい作業記録は**索引に1行足し、本文は `docs/history/3.4xx.md` の先頭へ**書く（CLAUDE.md 本体には積まない）。
> 応答は簡潔・結論先出し・日本語。コード識別子は英語のまま。

## 教訓メモ（更新して使う）
作業の教訓は [`docs/lessons.md`](./docs/lessons.md) に置く。**新しいメモを作らず、このファイルを更新する**
（該当行を書き換える／表に1行足す／同趣旨の行は統合）。最上部の1行要約も実態に合わせて直す。
内容＝修正した点↔機能した点の対／作る前にやめた判断／測り方／この環境の検証手段と穴／レビューの扱い。

## 基本方針
- 不明な点は積極的に質問する
- 質問する時は常にAskUserQuestionを使って回答させる
- **選択肢にはそれぞれ、推奨度と理由を提示する**
  - 推奨度は⭐の5段階評価

## スキル自動起動（2026-07-18 ユーザー決定）
- **タスク着手前にスキル一覧を確認し、該当スキルを Skill ツールで自動起動する**（superpowers流）:
  新機能/設計→brainstorming・計画深掘り→dig・実装/バグ修正→test-driven-development・
  バグ調査→systematic-debugging・完了宣言前→verification-before-completion・
  複数ステップ計画→writing-plans→executing-plans/subagent-driven-development・文章推敲→writing-clearly-and-concisely
- **genshijin 常時起動（通常レベル）**: 全応答を圧縮体（敬語なし・体言止め・助詞省略可）で書く。
  技術用語/コード識別子は正確維持。破壊的操作警告・セキュリティ説明のみ Auto-Clarity で通常日本語。
  解除は「原始人やめて」「通常モード」の明示指示のみ。
- 実装: `~/.claude/settings.json` の SessionStart フック（`~/.claude/session-bootstrap.md` を注入）＋本節の二重化。
  リモートコンテナは使い捨てのためフックは環境ごと消えうる＝本節が永続側の正。
- **プラグイン正規導入済み（2026-07-25, ユーザー指示）**: genshijin@genshijin v1.4.0（サブスキル6種:
  commit/compress/crew/help/review/stats 付き）・superpowers@superpowers-marketplace v6.2.0（14スキル）・
  dig@kuu-marketplace v3.0.1 を `claude plugin install`（userスコープ＝`~/.claude/plugins/`）で導入。
  環境が再構築されて消えた場合の再導入コマンド:
  ```
  claude plugin marketplace add InterfaceX-co-jp/genshijin && claude plugin install genshijin
  claude plugin marketplace add obra/superpowers-marketplace && claude plugin install superpowers@superpowers-marketplace
  claude plugin marketplace add fumiya-kume/claude-code && claude plugin install dig@kuu-marketplace
  ```
  ※fumiya-kume/claude-code の実マーケット名は `kuu-marketplace`。genshijin はソースリポジトリ直接追加
  （この環境の git proxy では公式ディレクトリ(anthropics系)が解決できないため）。
  ※`~/.claude/skills/` に前セッションの手動コピー版（genshijin/dig/superpowers系16件）が残存＝プラグインと
  重複するが無害（一覧ノイズのみ）。掃除する場合はセッション開始直後にバックアップ退避してから削除。

## プロジェクト概要
看護師/スタッフのシフト表を最適化する Android ネイティブアプリ（Kotlin + Jetpack Compose）。
VBA/Web 版から移植した「MAGI V6」最適化エンジン（SA + ALNS + Tabu + GLS + LNS + VNS + LAHC +
PathRelinking + ChainSwap + 適応的オペレータ重み + RSI++ 等）を内蔵。

- **規模の上限（業務前提・2026-07-28 ユーザー明示）**: **職員は最大30名 / 期間は最大1か月（=31日）**。
  この範囲を超えるデータは想定しない。既存のビット化経路はすべてこの範囲に収まる:
  `C3nBitScan.usable`(T<=64) と C++ `SaChunk` の `useBits`(S<=64 && T<=64) は**実運用では常に真**＝
  スカラーへのフォールバックは防御であって通常経路ではない。`adaptiveBlockLengths` の最大28日も
  「2月まるごと」を1ブロックで扱うための上限で、1か月という前提と整合する。
  新しくビット/固定長配列を使う実装を足すときは 30×31 を基準に見積もってよい。
- パッケージ/applicationId: `com.magi.app`（namespace も同じ）
- minSdk=36 (Android 16+), compileSdk/targetSdk=36, java.time ネイティブ可, NDK/desugaring 不使用
  （※Android 17 会話バブル対応済。compileSdk は 36 のまま。**API 37 の platform SDK は 3.409.12 で stable 公開を確認済み**＝バブル対応(2026-07-15)/3.373.0 の「未公開」は解消。移行手順は下記セクション参照）
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
- `Evaluator.kt` / `DeltaEvaluator.kt` — **最適化器の目的関数**（SA の受理判定）。`Evaluator(p)`（3.393.0 で `c3RunMode` は撤去＝単一シフト連は常に run-deficit）。
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
c3mn(30)=c1(30) > c3(3) > c3m(2) > c2/c41/c42/c41s/c42s/apt/fair/weekly/covO(1)。（covO は 2026-07-13 に 0.5→1.0 統一。
c1 は 4→5→15→**30**、c3mn は 12→15→**30**＝いずれも HF77 明示指示。この行が stale だと監査が誤誘導されるので、
重みを変えたら `MirrorKeys.weights`・`Evaluator.fullEvalParts`・`DeltaEvaluator` の集約式・`magi_native.cpp` の
5箇所・言語跨ぎ期待値3ファイル・`docs/business-logic.md` と**同じコミットで**揃える）

> **決定記録（D3, 業務判断）**: apt/weekly/fair の重みは**現状維持（各1）で確定**（業務担当者レビュー済）。
> ws8/ws9 等と同様、以後この3族の重み変更は**再提案しない**（明示的な数値指示があった場合のみ変更）。
> **（2026-08-02 再確認）** 3.345.0 で weekly の定義をシフト別へ変えて mass が3〜4倍になったため、
> 重みを下げる案（重み付き寄与を旧定義と同程度に戻す）を実測つきで提示したが、業務判断は**重み1のまま**。
> 代償（user_state 相当のデータで c1 が中央 62→84＝1〜2割増、HARD は全データセットで不変）は受容済み。
> weekly を実際に減らすという定義変更の意図を優先する。この件も**再提案しない**。

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

## 決定記録（D3〜D8・E5＝再提案しない）

版数付きの作業記録を `docs/history/` へ分離したため（3.470.0）、その中に埋もれていた決定記録をここへ集約する。
**この節にあるものは業務判断で確定済み＝再提案しない**（明示的な数値指示・明示の go があった場合のみ変更する）。

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

> **決定記録（D3, apt/weekly/fair の重み）**: 各1のまま確定。全文は「制約ファミリーと意味（confirmed）」節にある。

> **決定記録（D7, 読取(結果)モード）**: 「読み取り結果モードは不要。下書きを直すモードだけで大丈夫」（ユーザー判断）。
> 勤務表タブは常に直接編集の1本（3.120.0 で撤去）。結果スナップショットのモデルと API は温存しているが UI 参照はゼロ。
> 誤編集の担保は「元に戻す」。読取/編集の整合問題はモード自体の消滅で恒久解消。**再提案しない**。

> **決定記録（D8, 外観）**: 「外観は UD モードのみ」（ユーザー指示）。テーマセレクタは撤去し UD（高コントラスト・白地）固定
> （3.121.0）。明/暗/UD の配色定義は `MagiTheme` に温存＝復活可能だが、テーマ選択 UI は**再提案しない**。

> **決定記録（E5, 月全体の俯瞰）**: ユーザーの明示 go まで保留。着手も再提案もしない（詳細は「バックログ / 未対応」#5）。

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
  のみでスコアリング不変(keep-best=better() が結果担保)＝退化なし。covU が下限であることは定理(測定不要)。~~golden(構造的covU=2)で発火。~~
  **※[3.362.0 訂正] 現行 golden_state.json は `structuralHardFloor=0`（実測）＝この床は golden では発火しない（no-op）。当時の golden か記述が stale。機構自体は floor>0 のデータで正しく動作する。**
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
2. 未レビュー領域の精読: `V6LateOperators`/`V6SearchOperators`/`V6HotfixPasses` 各パス内部, CSV/UI 層。
   **(3.84.0, 並列監査で一巡＝`docs/history/3.0xx.md`)**。※`V6WebCompat` は 3.393.0 に撤去済み（Web 版は存在しない）。
3. ~~C++/NDK 移植は**不要**の結論（純Kotlin＋被覆対応Δ評価で十分高速）~~ **→ 撤回（3.136〜／第2期・第3期でネイティブ加速＝
   C++フル評価器＋SA/LAHC/ALNS/Polishチャンク＋JNI＋実行時パリティを実装。監査指摘は下記6/7）**。エンジンは ALNS/Destroy-Repair/
   ChainSwap3-4/C1BlockN/PathRelink/LNS/Reheat/Oscillation/適応的オペレータ重み/希望ロック枝刈り を実装済み。
   §4 ILP matheuristic のみ意図的に未実装。
4. cons3n のデータ重複（Dﾃ→A4 が2行）は二重計上だが最適化器/チェッカーで一貫（SettingIssue が dedup を提案）。
5. **E5「月全体の俯瞰」= ユーザーの明示 go まで保留**（決定記録）。指数(見やすさ12指標)で唯一70未満(58)だが、
   最低スコア≠最高価値・片手一本指/編集主体との緊張のため、着手も再提案もしない（明示 go があった場合のみ）。
6. **[ネイティブ・保守性] C++評価器のパリティに自動テスト無し**（3.168.0系精読で判明）。JVM単体テストは arm64 `.so` を
   ロード不可（`NativeBridge.available=false`）→ Kotlin のみ検証。CI(Release Build/Android SDK)は CMake で `.so` を
   ビルドするので**C++コンパイルエラーは捕捉**するが**意味的乖離（重み取り違え等）は捕捉しない**。`Evaluator.kt`（や
   `MirrorCore`/`DeltaEvaluator`）を変えて `magi_native.cpp` を変え忘れると実機で番兵発火→**ネイティブ黙殺（速度退行・誤出力なし）**。
   3.171.0 で緩和策の一つ（ユーザーが明示的に照合を切れる「照合トグル」＋既定ONの維持）を実装。3.172.0 で
   `tools/native/host_parity_bench.cpp`（ホストビルド可能なパリティ+ベンチharness）を追加。
   **→ CI配線 完了（`docs/history/3.1xx.md` の「ネイティブパリティのCI自動化」3.178.0）**。`.github/workflows/native-parity.yml` が
   pull_request→main / push→main / 手動 で harness を g++ ビルド・実行し、mismatch>0 で非ゼロ終了＝ジョブ失敗。
   これで Evaluator.kt を変えて magi_native.cpp を変え忘れる意味的乖離が自動検出される。~~**残課題**: harness の
   合成問題は S<=64/T<=64・乱数生成で、実データ形状の網羅ではない（fixture 拡充は将来課題）~~
   **→ 3.357.0 で言語跨ぎパリティも追加**（旧: 照合は C++ scalar vs C++ bit-op ＝同じ言語どうしで、
   両方の C++ 経路を一貫して変えれば通ってしまった。`golden_eval_expected.txt` を Kotlin テストと
   `--expect=` の両側から固定し、片側だけの変更が必ず落ちるようにした）
   **→ 3.199.0 でフィクスチャ拡充**（`tools/native/state_to_flat.py`＝実 state JSON→flat 変換を新設し、
   CI が golden_state.json の実データ問題でも照合＋合成 builder に休シフト range/apt/c1/c2・実現不可能希望・
   -1/非canDo混入盤面を追加。この拡充が実バグ=SaChunk の -1 未対応を実際に捕捉した）。
   **→ 3.362.0 で2つ目の実データ形状 sample_v6 を追加**（golden は入力盤面 hard=0＝C++ の HARD族パスを
   実データで一度も exercise しないため、sample_state_v6 の hard=15 盤面を第2 fixture に。`--expect` を
   flat と出現順で対応づけ1回のベンチで両形状を言語跨ぎ照合。詳細は「パリティネットへ2つ目の…」節）。
   ~~**残課題**: 合成問題は S<=64/T<=64・乱数生成で、実データ形状の網羅ではない（fixture 拡充は将来課題）~~
   ~~残: real/user 相当の「構造的 covU が床超で blocked-now」な形状は依然 repo に無い~~
   **→ 3.409.15 で解消**（2026-08 実運用 state の**匿名化版** `blocked_covu_state.json`＝covU=4 が床0を超えて
   blocked-now、を第3フィクスチャとして追加。言語跨ぎ照合＋形状の回帰テストつき）。
7. ~~**[ネイティブ・堅牢性] 群index無検証のOOB（潜在）**（3.168.0系精読で判明）。探索オペレータ約13箇所が
   `p.bucket[p.sgrp[i]]`／`grpCnt[sgrp[i]*K+k]` を sgrp範囲未検証で使用しており、不正な groupIdx が渡ると
   C++側はUB（bucket=範囲外読み・grpCnt=範囲外**書込=ヒープ破壊**）でSIGSEGVし得た（Kotlin側は例外→
   runCatchingで安全退化するのと非対称）~~ **→ 3.171.0 で解消**（`nativeCreateProblem` に sgrp 一括範囲検証を
   追加し、外れていればハンドル生成自体を拒否=0返却。既存の「handle==0=native不可→Kotlinへ安全退化」という
   確立済みの契約にそのまま乗るため Kotlin 側の変更は不要）。
8. ~~**[軽微] SAチャンク自己整合の非対称**（3.168.0系精読で判明）。`runSaChunk` の番兵は `full != curVal` のみ。
   他3ランナー（LAHC/ALNS/Polish）は `curVal != st.score` の相互検査も持つ~~ **→ 3.179.0 で解消**
   （`runSaChunk` 末尾を `full != curVal || curVal != st.score` に対称化。受理時 curVal=st.score・revert で復元の
   ため通常は恒真＝挙動不変、不整合時のみ status=1 で Kotlin 退化。ホスト parity harness で compile+mismatch=0 確認）。

## スキル / 作業の進め方
- 画面（`ui/` の Composable）を実装・改修したら **design-review**（`.claude/skills/design-review/SKILL.md`）で
  規約からの逸脱をレビューする。**`/code-review`（正しさのバグ）とは対象も根拠も違う**＝こちらは
  `docs/DESIGN.md` / `operator_ux.md` / `screen_spec.md` / `ux_test_checklist.md` / CLAUDE.md の D決定 と
  `tools/design_lint.py` を根拠に、形・文言・トークン・アクセシビリティ・冗長を見る。severity は
  MUST/SHOULD/IMO/nits/Q で、**根拠を示せないものは MUST/SHOULD にしない**。D決定(D3〜D8/E5)と HF77 に
  触れる指摘は severity を付けず `Q` へ落とす。エンジン・重み・採否は対象外。
  出典は dachi023 氏の gist（Figma+Notion+Storybook 前提）で、この repo にはその3点が無いため
  **根拠の層をリポジトリ内文書と design_lint へ置き換えて移植**した（design-context は Figma/Notion 取得が
  本体で、無いと grilling の劣化版になるため移植しない）。
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

## 作業記録の索引（詳細は `docs/history/`）

版数付きの作業記録（355節）は**この索引だけを常時読み込み**、本文は `docs/history/` に置く
（毎ターンのコンテキスト固定費を減らすため。3.468.0 で分離を始め、3.470.0 で本体から全部出した）。
バケツは版数で分かれる: `2.x.md` / `3.0xx.md` / `3.1xx.md` / `3.2xx.md` / `3.3xx.md` / `3.4xx.md`。
**同じ領域を触る前に必ずここを検索する**＝過去に測って否決した案・同型のバグ・決定記録を二度踏まないための索引。
本文の引き方: `grep -n '（3.409.21' docs/history/3.4xx.md` のように版数で引き、その節を読む。
見出しは原文のまま（版数・補足・ユーザー指示の引用を落とさない）。

- 群×シフト担当可否を2次元マトリックスへ再設計＝固定ヘッダ・セル全面タップ・行/列一括（3.476.0, ユーザー提示案・Windows11版と同時対応）  → `docs/history/3.4xx.md`
- 論理的不具合の並列監査（6角度）＝実在30件を修正・5件は複雑さ/確度から見送り明記（3.475.0, ユーザー指示「論理的不具合も修正する」）  → `docs/history/3.4xx.md`
- /code-review（3.473.0の自己検証, 8並列finder）＝実在9件を全部修正、うち1件は3.473.0自身の退行（3.474.0, ユーザー指示「不具合が全て修正する」）  → `docs/history/3.4xx.md`
- /code-review（3.472.0の自己検証）＝2件を追加修正（3.473.0）  → `docs/history/3.4xx.md`
- 外部レビュー100件を並列検証し、実在4件を修正（3.472.0, ユーザー提示「添付は何%正しいか」＋「問題点のコードをすべて実装する」）  → `docs/history/3.4xx.md`
- 分析タブを1画面へ再構築＝分類の軸を「族」から「診断」へ（3.471.0, ユーザー提示のモック＋grilling の合意）  → `docs/history/3.4xx.md`
- CLAUDE.md 本体から版数付き作業記録を全部出した＝固定費 202k→54k-tok（3.470.0, ユーザー選択「残り148節も分離」）  → `docs/history/3.4xx.md`
- ビット幅の境界を周辺へ当て直す＝窓口の迂回1件と署名パック幅の無保証1件（3.469.0, ユーザー指示「周辺や類似する箇所を再検証する」）  → `docs/history/3.4xx.md`
- CLAUDE.md の作業記録を索引化＝毎ターンの固定費を 422k→202k へ（3.468.0, ユーザー回答「困りごと＝コンテキスト圧迫」）  → `docs/history/3.4xx.md`
- ドッグフーディング＝③統合の直後に editRev 取り残しを発見・是正（3.467.0, ユーザー指示「ドッグフーディング検証する」）  → `docs/history/3.4xx.md`
- 編集タブ「③ 回数（1人あたり）」の3枚カードを1枚へ統合（3.466.0, ユーザー指示「冗長性を賢くシンプルデザインに深く考え直す」）  → `docs/history/3.4xx.md`
- covO（人員過剰）重み 1.0→5.0（3.465.0, ユーザー明示数値指示＝HF77一時保留のうえ確定）  → `docs/history/3.4xx.md`
- 統合カード(ViolationHubCard)の達成表示・展開状態を修正＝外部レビューP1-01/P2-01（3.464.0）  → `docs/history/3.4xx.md`
- wideC3nBreakDays を既定OFFで最終確定＝ユーザー指示「AB評価」への回答（3.463.0）  → `docs/history/3.4xx.md`
- fixture の shiftColors に専用テストを追加＝外部レビュー L-01（3.462.0, receiving-code-review規律で検証のうえ実施）  → `docs/history/3.4xx.md`
- 冗長な「チップの枠」トグルを撤去＝常時表示へ一本化（3.461.0, ユーザー指示「冗長性をシンプルデザインにする」）  → `docs/history/3.4xx.md`
- 色ピッカーを36色(6×6)へ拡張＋チップ枠トグルの文言修正（3.460.0, ユーザー指示「6×6の36色にしてください」＋実機報告「シフト種別の枠が勤務表に反映しない」）  → `docs/history/3.4xx.md`
- 分析タブ3カードの統合＋違反フィルタの有効活用（3.459.0, ユーザー指示「1，2をあなたが賢く深く考え判断して修正する」）  → `docs/history/3.4xx.md`
- シフト種別チップの枠線を選択式に＋希望バッジ凡例の統合＋グリッドキャプションの重複解消（3.458.0, ユーザー指示3件）  → `docs/history/3.4xx.md`
- 勤務表グリッドの通常セル枠線を選択式に（3.457.0, ユーザー指示「違反以外の普通の枠の表示有無を選択できるようにする。デフォルトは枠表示無し」）  → `docs/history/3.4xx.md`
- 既定OFFの3トグルをこのサンドボックス上で再測定（3.456.0, ユーザー指示「既定OFFのトグル3つ…全てあなたの仮想環境で再測定」）  → `docs/history/3.4xx.md`
- テスト用サンプルデータの既定シフト表示色を設定（3.455.0, ユーザー提示の11シフト配色案）  → `docs/history/3.4xx.md`
- 色ピッカーをユーザー手指定の25色へ再び全面差替え（3.454.0, ユーザー提示の別の25色表）  → `docs/history/3.4xx.md`
- 色ピッカーをユーザー手指定の25色へ全面差替え＝アンカー制約と両立させて再配置（3.453.0, ユーザー提示の25色表）  → `docs/history/3.4xx.md`
- 色ピッカーの隣接差異を15%→25%へ強化＝ヒルクライム局所探索で再設計（3.452.0, ユーザー指示「各色は色彩など25%づつ変更する」）  → `docs/history/3.4xx.md`
- HF80戦略的振動のOOM根本修正＝localBestImprovementをEvaluatorベースの候補生成へ（3.451.0, ユーザー指示「根本的に修正する」）  → `docs/history/3.4xx.md`
- largeHeap 追加＝後処理HF80のタイトループOOMを既定ヒープ緩和で対処（3.450.0, ユーザー提示の実機ログ12月データから調査）  → `docs/history/3.4xx.md`
- wideC3nBreakDays を4件目の実データで再測定＝符号不一致で既定OFF据え置きを確定（3.449.0, ユーザー提示の実機ログ3本＋state.json直接アップロード）  → `docs/history/3.4xx.md`
- 色ピッカーの識別性と警告色の実選択可能性を確保（3.448.0, ユーザー提示のスクショ2枚から）  → `docs/history/3.4xx.md`
- 背景実行の開始トランザクション3件を原子化＋normalStallFraction再測定を完了（3.447.0）  → `docs/history/3.4xx.md`
- 続く2表(13+15項目)の検証＝dWeekly自己復元窓にも同型の穴を発見・修正、残りは反証つきで対応不要（3.446.0）  → `docs/history/3.4xx.md`
- 外部レビュー8件の検証＝実在1件のみ・DeltaEvaluator.previewMoveへtry/finally（3.445.0, receiving-code-review規律で全件検証）  → `docs/history/3.4xx.md`
- 週送り/違反ジャンプを画面下部へ＋行列クロスハイライト（3.444.0, ユーザー提示の再設計案から）  → `docs/history/3.4xx.md`
- シフト集計の違反バッジをピル形に＝視認性改善（3.443.0, ユーザー提示の目標デザイン画像から）  → `docs/history/3.4xx.md`
- 勤務表グリッドの土日・祝日色分け＋祝日データの外部ファイル化（3.441.0, ユーザー提示の目標デザイン画像から）  → `docs/history/3.4xx.md`
- M8/M9を明示指示で解消＝allowBackupの無効化とsaveNow()の観測性強化（3.440.0, ユーザー指示「賢く深く考え修正する」）  → `docs/history/3.4xx.md`
- 勤務表タブのレイアウトをモックアップへ合わせる＝週ラベルの年月併記＋違反凡例のカジュアル化（3.439.0, ユーザー提示の目標デザイン画像から）  → `docs/history/3.4xx.md`
- CSV追加行の埋めシフトと JNI の cons1/cons2 検証＝並行セッションの 3.438.0 が残した3件（3.442.0）  → `docs/history/3.4xx.md`
- 外部提示の「レビュー文書」を検証＝停止後に running が永久固着する実バグを発見・修正（3.438.0）  → `docs/history/3.4xx.md`
- MagiViewModel のコメントに残っていた絶対行番号参照4件を是正（3.437.0, 自律監査の続き）  → `docs/history/3.4xx.md`
- 必要人数(need1/need2)だけ下限>上限の入力時ガードが無かった（3.436.0, 自律監査の続き）  → `docs/history/3.4xx.md`
- 完了メッセージが内部識別子を出し、開始と語彙が食い違っていた（3.435.0, 自律監査の続き）  → `docs/history/3.4xx.md`
- 職員削除の確認ダイアログに「勤務・希望も消えます」警告が無いドアがあった（3.434.0, 自律監査の続き）  → `docs/history/3.4xx.md`
- シフト/グループ削除確認ダイアログの文言整理（3.433.0, 自律監査＝design-review 手順を適用）  → `docs/history/3.4xx.md`
- 色ピッカーの25色を淡いパステル調へ＋選択チェック印を太字化（3.432.0, ユーザー指示「淡い中間色 25色＋ 濃色太字テキスト」＋実機スクショ）  → `docs/history/3.4xx.md`
- 勤務表タブの「違反セル：他75件」棒読みリストを撤去（3.431.0, ユーザー指示「冗長を賢く見直す。人間が見やすいデザインにする。」＋実機スクショ4枚）  → `docs/history/3.4xx.md`
- 色ピッカーを25色（5×5）へ拡張（3.430.0, ユーザー指示「25色設定にする」＋実機スクショ）  → `docs/history/3.4xx.md`
- シフト/グループ削除の確認ダイアログへ影響件数を表示＝外部レポートR-03（3.429.0, 実機ログ＋100件統合レポートの検証から）  → `docs/history/3.4xx.md`
- 100件レビューの未確認70件を全部当てて、実在した4件を直す（3.428.0）  → `docs/history/3.4xx.md`
- ペア禁止の行を読める形にして二重表示を撤去＝④⑤の説明レイヤーも整理（3.427.0, ユーザー指示「冗長を賢く直す」＋実機スクショ2枚）  → `docs/history/3.4xx.md`
- 並行ブランチの後始末を選別統合＝版番号の取り違えと文書の実装乖離（3.425.0, ユーザー指示「mainでマージする」）  → `docs/history/3.4xx.md`
- A/B結果を確定＝`normalStallFraction` は既定0.9のまま据え置き（3.423.0, 3.422.0 Part B のフォローアップ）  → `docs/history/3.4xx.md`
- 3.422.0 が到達可能な帯まで無計測で厳格化していた＝予算基準の復元と入力検証（3.424.0, /code-review 3件）  → `docs/history/3.4xx.md`
- 停滞ウォッチドッグ「通常」分岐の閾値を実際の探索区間へ合わせる＋A/B用の可変割合を新設（3.422.0）  → `docs/history/3.4xx.md`
- 別ブランチ発PRの棚卸し＝x8ygvy/rsmp2p は版番号衝突で盲目マージせず選別統合（3.421.0）  → `docs/history/3.4xx.md`
- P10 が baseline超過を検出＝removeShift 自身の記号比較を一本化（3.420.0, ラチェット実効）  → `docs/history/3.4xx.md`
- 同じ穴が探索の入口にもあった＝埋める規則を1箇所へ（3.419.0）  → `docs/history/3.4xx.md`
- 空きマスを「担当できないシフト」で埋めていた（3.418.0, 3.417.0 の掃討で発見）  → `docs/history/3.4xx.md`
- 記号の字面でシフトを分類・除外する経路を撤去（3.417.0, 受領した中立化パッチ2本の再検証）  → `docs/history/3.4xx.md`
- 「休」を通常のシフト定義へ統一＝編集ガード2つの撤廃と全域監査（3.416.0, ユーザー明示方針）  → `docs/history/3.4xx.md`
- 「休」シフトの記号改名に入口ガードが無かった（3.415.0, 外部レビュー撤回文書 R-04）  → `docs/history/3.4xx.md`
- CSV取込が期間を推定して黙って確定していた（3.414.0, 100件レビュー I-02）  → `docs/history/3.4xx.md`
- CSVの取込で黙って消えていた2つ＝未知の群記号と閉じない引用符（3.413.0, 100件レビュー第4巡）  → `docs/history/3.4xx.md`
- 期間より長い窓の要件が評価も警告もされず消えていた（3.412.0, 100件レビュー第3巡）  → `docs/history/3.4xx.md`
- 100件レビューの第2巡＝残り6件を直し、44件は「未検証」と正直に数える（3.411.0）  → `docs/history/3.4xx.md`
- 外部レビュー100件を実コードに当てて、実在した項目だけ直す（3.410.0, ユーザー指示「すべて修正する」）  → `docs/history/3.4xx.md`
- トグル A/B の第2ラウンド＝240s で3件とも測り切った（3.409.25, docs のみ）  → `docs/history/3.4xx.md`
- 回避の並び(c3mn)と窓の要件(c1)の重みを 15→30（3.409.24, ユーザー明示数値指示＝HF77）  → `docs/history/3.4xx.md`
- 並列監査の残り2件＝制約 index の入口ガードと「1日あたり上限」の前提（3.409.23）  → `docs/history/3.4xx.md`
- ネイティブ修復器が Kotlin から5世代ぶん取り残されていた（3.409.22, 外部レビューを全件検証して修正）  → `docs/history/3.4xx.md`
- 既定OFFトグル2つを単体 A/B で測って削除（3.409.21, ユーザー選択「両方削除」）  → `docs/history/3.4xx.md`
- C41/C42/C41s/C42s の説明を別々の制約として書き分け（3.409.20, ユーザー指示）  → `docs/history/3.4xx.md`
- /design-review＝3.409.18 の自分の変更に SHOULD 1件（3.409.19）  → `docs/history/3.4xx.md`
- ペア禁止の説明を人間に分かる形へ＝聞き返された3点を修正（3.409.18, ユーザー選択で4件全部）  → `docs/history/3.4xx.md`
- 予算超過を観測可能にする＝2本目の実機ログで13回中5回が300秒予算を474〜959秒まで超過（3.409.17）  → `docs/history/3.4xx.md`
- covU-blocked の実データを匿名化して第3フィクスチャへ＝backlog#6 の残りを解消（3.409.15）  → `docs/history/3.4xx.md`
- 実機ログ(3.409.14・2026-09データ)の検分＝表示の自己矛盾3件を修正（3.409.16）  → `docs/history/3.4xx.md`
- 制約10族の「詳しい説明」をアプリへ＝既定で閉じた ⓘ 展開（3.409.14, ユーザー指示「詳しく説明をアプリにも追加」）  → `docs/history/3.4xx.md`
- /code-review 7件を全て修正＝作ったばかりの P9 に本物の穴が3つあった（3.409.13）  → `docs/history/3.4xx.md`
- この環境で実行できないと言っていたものを、実行できる形に置き換える（3.409.12）  → `docs/history/3.4xx.md`
- 判断待ち4件を決着＝c1 の表示強度は「件数」でなく「セル数」で測る（3.409.11）  → `docs/history/3.4xx.md`
- 残した3つを片付ける＝λ上限は「配線できない」と確定／DS の ✅ を機械検査へ（3.409.10）  → `docs/history/3.4xx.md`
- 提示レビュー P0-P3 の照合＝広域ビームのピン合流漏れと停止伝播の回帰（3.409.9）  → `docs/history/3.4xx.md`
- 呼出0の関数6つを撤去＝「4つの編集入口」は最初から3つだった（3.409.8）  → `docs/history/3.4xx.md`
- 族→日本語名の表を、テストできる場所へ（3.409.7）  → `docs/history/3.4xx.md`
- ラチェットを 0 まで下げる＝任意の角丸と生 hex を tier へ（3.409.6）  → `docs/history/3.4xx.md`
- 「baseline 監視」が監視になっていなかった＝P2/P4 のラチェット化（3.409.5）  → `docs/history/3.4xx.md`
- 外側ワーカーの実効並列度をログへ＋検証器が空振りしていた件（3.409.4）  → `docs/history/3.4xx.md`
- 画面の語彙を記録された決定へ揃える（3.409.3, /design-review の SHOULD 3件）  → `docs/history/3.4xx.md`
- タッチ域チェックリストの誤った ✅ を実測して訂正（3.409.2）  → `docs/history/3.4xx.md`
- 死んだ配管の撤去と、生きている配管の配線（3.409.1, ユーザー指示「配管と配線する」「スタブなどを実装する」）  → `docs/history/3.4xx.md`
- 検査自身が守れていなかった＝P6 の複数行見落としと、そこに隠れていた3件（3.409.0, /code-review）  → `docs/history/3.4xx.md`
- 操作ログに実行IDが無く、複数回実行後の書き出しが自己矛盾に見えた＋停滞監視が並列で恒久的に無効化されていた（3.408.0）  → `docs/history/3.4xx.md`
- 同梱の見本データが文字化けしたまま出荷されていた＋テストの永続的な誤検出（3.407.0, ユーザー指示「文字化けを修正する」「500テストの断捨離する」）  → `docs/history/3.4xx.md`
- 実機ログと受領した不具合一覧を裏取りして6件（3.406.0）  → `docs/history/3.4xx.md`
- シートが守れない約束をしていた（3.405.0, 監査 high の最後の1件）  → `docs/history/3.4xx.md`
- 旗の名前が「最適化」だったせいで、同じ性質の3ジョブが旗を立て忘れていた（3.404.0）  → `docs/history/3.4xx.md`
- 下限>上限を、あとから叱るのでなく入力時に止める（3.403.0, 監査 D-1）  → `docs/history/3.4xx.md`
- 止める手段が消える／押せる行と押せない行が同じカードに混ざる（3.402.0, Nielsen 監査の残り2件）  → `docs/history/3.4xx.md`
- 「なおすのを手伝って」が診断と正反対の約束をしていた（3.401.0）  → `docs/history/3.4xx.md`
- 操作の返事が返ってこない群を潰した＋3.399.0 の自分の回帰（3.400.0）  → `docs/history/3.3xx.md`
- Nielsen 10原則の並列監査＝確認できた最上位2件を直した（3.399.0）  → `docs/history/3.3xx.md`
- 日本語テンプレート食い込みを機械の検査にした＋取消の無いダイアログ（3.398.0）  → `docs/history/3.3xx.md`
- 残していた貼り紙2件の「形」を作った（3.397.0）  → `docs/history/3.3xx.md`
- 貼り紙を剥がして形に語らせる（3.396.0, ユーザー提示の設計原則）  → `docs/history/3.3xx.md`
- 違反チェッカーを −23% 高速化＝出力は1ビットも変えずに（3.395.0, ユーザー指示「高速化対応する」）  → `docs/history/3.3xx.md`
- ちらつき対策が既定経路で効いていなかった＝測り直して修正（3.394.0, /code-review 6件）  → `docs/history/3.3xx.md`
- Web互換の撤去・最適化中のちらつき・死んだ配管の始末（3.393.0, ユーザー指示3点）  → `docs/history/3.3xx.md`
- 論理的問題の横断監査＝旗の固着・無言の編集・矛盾するデッド述語（3.392.0, ユーザー指示「すべての論理的問題点などを修正する」）  → `docs/history/3.3xx.md`
- 実現不能な希望を「固定」と誤扱いする穴を9箇所修正（3.391.0, ユーザー指示「不具合を全て修正する」）  → `docs/history/3.3xx.md`
- SUDO モデルを実装から起こす（3.389.0, ユーザー提示「SUDOモデリング」）  → `docs/history/3.3xx.md`
- 所有権を失った実行が新しい実行を恒久的に無効化していた（3.388.0, /code-review 11件の検証）  → `docs/history/3.3xx.md`
- 埋められない穴をログで観測可能にする（3.387.0, ユーザー指示「残っている、埋められない穴などログ強化する」）  → `docs/history/3.3xx.md`
- Worker の「コメントだけの再発防止」をテストへ＝RunFiles 抽出（3.386.0, ユーザー指示「修正する」）  → `docs/history/3.3xx.md`
- 途中最良の publish が「評価」と「盤面」で食い違う競合を修正（3.385.0, 外部レビューの検証から）  → `docs/history/3.3xx.md`
- 既定 OFF トグルの「見直しの条件」を明文化＝腐らせない（3.384.0, R-09 解消）  → `docs/history/3.3xx.md`
- 「検証できないと見送った項目」をログで検証可能にする（3.383.0, ユーザー指示）  → `docs/history/3.3xx.md`
- 終端ログの保証を全経路へ＋Error も拾う＋族分類の取りこぼしを機械固定（3.382.0, ユーザー指示「修正する」「完了・停止・失敗のいずれも記録されるログ強化」）  → `docs/history/3.3xx.md`
- 停止処理が丸ごと飛んでいた＝ハンドラ全体を NonCancellable で包む（3.381.0, 実機ログで原因特定）  → `docs/history/3.3xx.md`
- covO の違反詳細が「場所数」を「件数」として出していた（3.380.0, 添付ログから）  → `docs/history/3.3xx.md`
- need1直参照の第4世代（修復オペレータ2つ）＋最適化診断がログから消える（3.379.0）  → `docs/history/3.3xx.md`
- デバッグできるログへ＝スコア収支・改善の軌跡・沈黙していた追加精製（3.378.0, ユーザー指示「デバッグできるようにログを強化する」）  → `docs/history/3.3xx.md`
- 残存分析が「もう直せない covU」を見落としていた＋Watchdog の時間軸混在（3.377.0, 実機ログ 2026-08-15 から）  → `docs/history/3.3xx.md`
- 並列を本当に動かす＝HARD=0 到達時の「残りを即キャンセル」を撤廃（3.376.0, ユーザー指示「ワーカー、並列が本当に動くようにする」）  → `docs/history/3.3xx.md`
- HARD=0 入力で8並列が実質1並列に潰れる＋残存分析の二重計上＋停滞ログに反復数（3.375.0, ユーザー指示「ログから新しい不具合を見つける」「停滞脱出のログを強化する。イテ回数と時間を出す」）  → `docs/history/3.3xx.md`
- 希望ロックで達成不能な適切回数を事前診断する＝検査6d（3.374.0, ユーザー指示「全て修正する」）  → `docs/history/3.3xx.md`
- 実機ログ(2026-08-15)起因の2件＋compileSdk 37 の可否確認（3.373.0, ユーザー指示「修正する？」で4件全選択）  → `docs/history/3.3xx.md`
- 3.371.0 の /code-review 指摘4件を検証して修正（3.372.0）  → `docs/history/3.3xx.md`
- 並列SAの本格再有効化＋soft全族の完全差分（3.371.0, ユーザー指示「並列SAの本格再有効化する」「soft全族の完全差分する」）  → `docs/history/3.3xx.md`
- needFamilies 新設＝covU/c41系の重なりで場所一覧が件数より少なく見える穴を解消＋CI download の無防備さを是正（3.370.0, ユーザー指示「同様な問題などあるか?」）  → `docs/history/3.3xx.md`
- /code-review 全コード＝need2単独定義セル見落としの第3世代を発見・修正（3.369.0, ユーザー指示「すべてのフルコードを/code-review する」）  → `docs/history/3.3xx.md`
- 族数「18種」の docs 取り残しを19種へ横断修正（3.368.0, 3.202.0 の兄弟 docs への波及完了）  → `docs/history/3.3xx.md`
- 重み値コメントのドリフト＋c1 表示昇格の判断点（3.367.0, 3.366.0 の sibling-bug 掃討を重み定数へ拡張）  → `docs/history/3.3xx.md`
- 外部レポート L1-L10 の周辺検証＝keep-best 順序コメントのドリフト12件を訂正（3.366.0, ユーザー「周辺も検証する」）  → `docs/history/3.3xx.md`
- 共有ネイティブハンドルの並列安全性を実行テストで示す（3.365.0, 別ブランチ x8ygvy から選択的に取り込み）  → `docs/history/3.3xx.md`
- c1「壁」判定の need2 依存を実データ計測で false wall と確定・正直化（3.364.0, backlog#4 解消）  → `docs/history/3.3xx.md`
- 直近コード（3.352-3.360）の焦点レビュー＝clean 確認＋stale fact 1件訂正（3.363.0）  → `docs/history/3.3xx.md`
- パリティネットへ2つ目の実データ形状 sample_v6 を追加（3.362.0, backlog#6「実データ形状の網羅」）  → `docs/history/3.3xx.md`
- covU-blocked 早期終了を実データ多seed A/B で確認却下（3.361.0 の再オープン条件を満たし、却下を補強）  → `docs/history/3.3xx.md`
- covU-blocked のウォッチドッグ配線を実測して却下（3.361.0, ユーザー指示「修正する」＝#1 の A/B）  → `docs/history/3.3xx.md`
- 提出された静的解析レポートの照合＝実コードに当たったのは1件（3.360.3）  → `docs/history/3.3xx.md`
- main のビルドを直す＝LocusIdCompat の import が誤っていた（3.360.2）  → `docs/history/3.3xx.md`
- ログのヘッダに版と実行環境を書く＋PORTFOLIO の合計iterと最良更新回数（3.360.0, ユーザー提示のログ強化仕様）  → `docs/history/3.3xx.md`
- 残りのピン計測外3箇所を測って決着（3.359.0, ユーザー指示「残り作業を最適化する」）  → `docs/history/3.3xx.md`
- 「どの日が塞いでいるか」をログへ＋パリティ不一致の次の一手（3.358.0, ユーザー指示「ログ強化する」）  → `docs/history/3.3xx.md`
- Kotlin↔C++ の言語跨ぎパリティを CI へ（3.357.0, 外部レポートの P0 主張を検証して判明した本当の穴）  → `docs/history/3.3xx.md`
- 設定トグルが実際に何をしたかをログへ＋詰まった理由の可視化（3.356.0, ユーザー指示「オプションを減らせるようにログ強化する」）  → `docs/history/3.3xx.md`
- weekly の構造床と大きな族の職員別集約をログへ（3.355.0, ユーザー指示「ログ強化する」）  → `docs/history/3.3xx.md`
- 「まだ狙える」に構造的な apt を入れていた＋6b/6c の断定を実態へ（3.354.0, 実機ログから）  → `docs/history/3.3xx.md`
- 回数の違反が診断から消えていた＝countFamilies 新設（3.353.0, 実機ログから）  → `docs/history/3.3xx.md`
- keep-best の順序を写す実装をなくす＝`reportComparator` 単一ソース（3.352.0）  → `docs/history/3.3xx.md`
- 予算按分の敵対検証＝壁は再現せず、代わりに wishLocked の取り残し19箇所（3.351.0）  → `docs/history/3.3xx.md`
- 最終LNS 2本のピン却下を計測へ配線（3.350.0, 外部レビュー #1 の再指摘を実測して採用）  → `docs/history/3.3xx.md`
- エリート統合の敵対検証＝実バグ0・観測性2件（3.349.2）  → `docs/history/3.3xx.md`
- 業務前提（30名・1か月）をコードで確認する＋提示レポート21件の検証（3.349.0）  → `docs/history/3.3xx.md`
- 3.347.0 の報告のみ2件を消化（3.348.0）  → `docs/history/3.3xx.md`
- 「ピン破り」の誤ラベルで主因族が隠れていた（3.347.0, 新領域の敵対検証）  → `docs/history/3.3xx.md`
- 停滞ラッチが降りない＋ワーカーの片肺運転（3.346.0, 実機ログ 2026-08-03 から）  → `docs/history/3.3xx.md`
- 休を通常のシフト種として扱う＋weekly を7日周期のシフト平準化へ（3.345.0, ユーザー明示指示）  → `docs/history/3.3xx.md`
- 人員不足診断の「充足可能」と「どう組んでも解消できません」の矛盾を解消（3.344.0）  → `docs/history/3.3xx.md`
- 禁止連続診断が「崩せる」と誤主張していた＝隣接日調整にも pref の代金を勘定（3.343.0）  → `docs/history/3.3xx.md`
- C1共同LNS が改善0のまま8秒を使い切っていた＝停滞打ち切り（3.342.0）  → `docs/history/3.3xx.md`
- 早期終了で余った予算を soft へ回す案を測って否決（3.341.1, 敵対レビュー A5）  → `docs/history/3.3xx.md`
- 複合手を原子化＝「意図を果たさない大きな破壊」をやめる（3.341.0, 敵対レビュー A2 を測って採用）  → `docs/history/3.3xx.md`
- C1広域ビームが探索を長く回すほど成果を捨てていた＝最良保持と停滞打ち切り（3.340.0）  → `docs/history/3.3xx.md`
- 後処理のパス別テレメトリ＝時間の行き先を見えるようにする（3.339.0, 敵対レビュー A4 の計測半分）  → `docs/history/3.3xx.md`
- ピンの不変条件を試験にする＝何が本当に守っているのかを実測（3.338.0, 敵対レビュー A2）  → `docs/history/3.3xx.md`
- 目的関数の二重管理を試験で塞ぐ＝Checker と Evaluator のパリティ（3.337.0, 敵対レビュー A1）  → `docs/history/3.3xx.md`
- ピンガードの抜け穴・c1ブーストの weighted 迂回・殻の失敗パス（3.336.0, 敵対レビュー）  → `docs/history/3.3xx.md`
- 探索の成果物を実行ごとの持ち物にする＋後段オペレータの比較が total へ落ちていなかった（3.335.0, 外部レビュー P1 2件）  → `docs/history/3.3xx.md`
- SA/LAHC の近傍が希望固定セルを触っていた＝手の35%が空振り（3.334.0, 3.333.0 の残り1件を計測して採用）  → `docs/history/3.3xx.md`
- 制約CSVの全置換ガード・完了後の実行中固着・指紋の行境界（3.333.0, 外部レビュー5件）  → `docs/history/3.3xx.md`
- 適応ポートフォリオのログが2つの母集団を1行に混ぜていた（3.332.0, 実機A/Bログから）  → `docs/history/3.3xx.md`
- C1頭打ち診断が最後の巡だけを見ていた＋結合探索の検査順（3.331.0, 実機ログ 2026-12 から）  → `docs/history/3.3xx.md`
- 新しい安全機構をテストできる場所へ移す（3.330.0, レビュー3回が挙げた「テスト不足」への回答）  → `docs/history/3.3xx.md`
- 入力の意味論を一本化＝休index・CSV全置換・所有権の残り（3.329.0, 外部レビュー第3回）  → `docs/history/3.3xx.md`
- 実行中の編集で全ガードが外れる欠陥＝running の二重用途を解消（3.328.0, 外部レビュー再確認）  → `docs/history/3.3xx.md`
- 実行の所有権と入力の検証＝外部レビュー High 5件（3.327.0, ユーザー指示「修正する」）  → `docs/history/3.3xx.md`
- 残作業3件を対応＝規則単位の診断・全パス計上・回数固定の緩和導線（3.326.0, ユーザー指示「1,2,3を賢く考え対応する」）  → `docs/history/3.3xx.md`
- 診断の紐付けと粒度を仕上げる＝原因未確定の明示・横断集計の分離・改名（3.325.0, ユーザー指示6項目）  → `docs/history/3.3xx.md`
- 診断表示の整合性を修正＝外部レビュー5件を全件確認して直す（3.324.0）  → `docs/history/3.3xx.md`
- 専用証明探索は実データで1件も証明できないと実測→代わりに緩和の根拠を出す（3.323.0, 優先順③の再設計）  → `docs/history/3.3xx.md`
- C1が直せなかった理由を構造化してUIへ＝3.321.0の一般化を実データで訂正（3.322.0, 優先順②「C-1診断表示」）  → `docs/history/3.3xx.md`
- 不採用理由を5分類で構造化＝ピン破りが研磨の最大の壁と判明（3.321.0, ユーザー指示「拒否理由を別々に構造化して記録する」）  → `docs/history/3.3xx.md`
- 制約行の無言除外を全族で可視化＋「休」不在の警告＋CI トリガー（3.320.0, 外部レビューの照合から3件）  → `docs/history/3.3xx.md`
- destroy-repair の marginal cost に canDo ガードを追加（3.319.0, 外部レビューの照合から1件）  → `docs/history/3.3xx.md`
- c42 の自己ペア／順序重複の除去と groupViol の HARD 統一（3.318.0, ユーザー明示指示）  → `docs/history/3.3xx.md`
- 分散指標の平準化2パスを撤去＝目的関数と指標が一致していなかった（3.317.0）  → `docs/history/3.3xx.md`
- 休の下限合計チェックが必ず誤警告していた（3.316.0, 診断を実データに当てて発見）  → `docs/history/3.3xx.md`
- C1厳密窓修復の探索を実採否と揃える＝厳密ピン・c3n を目的関数へ（3.315.0, ユーザー指示「次へ進める」）  → `docs/history/3.3xx.md`
- レビュー積み残し5件の解消（3.314.0, ユーザー指示「修正する」）  → `docs/history/3.3xx.md`
- free repair の締切確認と UI 指標の単位統一（3.313.0, 6本目のレビュー）  → `docs/history/3.3xx.md`
- C1合同LNSの「構造下限」からSOFT個人回数を除外（3.312.0, 5本目のレビューの新規項目 N-01）  → `docs/history/3.3xx.md`
- 禁止連続診断の偽 PINNED 修正ほか4件（3.311.0, 4本目のレビュー）  → `docs/history/3.3xx.md`
- PERSONAL_RSI の focus 選択を A/B して否決（3.310.1, ユーザー指示「あなたがABテストをする」）  → `docs/history/3.3xx.md`
- C1TemporalDp に状態数の安全弁（3.310.0, 3本目のレビューの新規項目）  → `docs/history/3.3xx.md`
- 外部レビュー2本の検証と確認できた4件の修正（3.309.0）  → `docs/history/3.3xx.md`
- 適応ポートフォリオの敵対フルコードトレース（3.308.2, ユーザー指示「敵対フルコードトレースする」）  → `docs/history/3.3xx.md`
- アップロード版の優秀な部分を部分融合（3.308.0, ユーザー指示「優秀な部分を部分融合する」）  → `docs/history/3.3xx.md`
- 3.307.0/3.308.0 の敵対検証＝自分の主張3件を反証して修正（3.308.1, ユーザー指示「敵対検証する」）  → `docs/history/3.3xx.md`
- 役割別worker秒のログ化＋秒予算再配分の否決（3.307.0, ユーザー指示「あなたが賢く考える」）  → `docs/history/3.3xx.md`
- 適応ポートフォリオの停滞脱出を既定OFFのトグルで温存（3.306.0, ユーザー選択「b」）  → `docs/history/3.3xx.md`
- staffPacked の重みドリフトと比較順序を修正（3.305.0, 外部提示コードを検証のうえ採用）  → `docs/history/3.3xx.md`
- 禁止連続の崩し範囲を設定トグルへ配線＋A/B実測（3.304.0, ユーザー指示「接続する。配線する。仮想的テストする。ABテストを仮想的にする」）  → `docs/history/3.3xx.md`
- 禁止連続をパターン全域で崩す＋Polish系の水平展開（3.303.0, ユーザー指示「C3nは前後日と当日も他の勤務シフトに変更できるようにアルゴリズムを賢く昇華する」「各制約のPolish系を相互に水平展開する」）  → `docs/history/3.3xx.md`
- 研磨の「不採用」に主因の族名を併記（3.302.0, ユーザー指示「ログ強化する」）  → `docs/history/3.3xx.md`
- 3.301.0 の論理的検証＝休の判定が旧実装で死んでいたことが判明（3.301.1）  → `docs/history/3.3xx.md`
- 目標（適切回数）の検算を設定画面へ＝オペレーターの思考誘導（3.301.0, ユーザー指示「オペレーターの思考を誘導してください」）  → `docs/history/3.3xx.md`
- アルゴリズム台帳の新設と未実施2件の実施（3.299.0 / 3.300.0, ユーザー提示の台帳案→「両方」）  → `docs/history/3.3xx.md`
- c3n 事前フィルタを PolishGate 経由で配線（3.298.0, ユーザー指示「配線する」）  → `docs/history/3.2xx.md`
- 壁になっている禁止の並びを名指しして緩める導線（3.297.0, ユーザー指示「1」＝3.296.0 の残り案(1)）  → `docs/history/3.2xx.md`
- c3n 事前フィルタを既定OFFへ（3.296.0, ユーザー指示「巡回交換の c3n フィルタを外す」）  → `docs/history/3.2xx.md`
- 境界 c3n の事前フィルタ＝構造的な不採用要因を両方とも0にする（3.295.0, ユーザー指示「(b) も入れる」）  → `docs/history/3.2xx.md`
- ピン保存交換＝ブロック巡回交換の最大の壁を除去（3.294.0, ユーザー指示「a」＝3.293.0 の次の手(a)）  → `docs/history/3.2xx.md`
- ブロック巡回交換の「採用0の理由」をログ化（3.293.0, ユーザー質問「採用ゼロの内訳を教えてください」）  → `docs/history/3.2xx.md`
- ブロック交換を可変長の巡回交換（3者・多者）へ一般化（3.292.0, ユーザー指示「三者交換、多者交換なども追加する」）  → `docs/history/3.2xx.md`
- ブロック交換の候補生成を「希望固定日は据え置き」へ緩和（3.291.0, ユーザー明示指示）  → `docs/history/3.2xx.md`
- 可変長ブロック交換（AdaptiveBlockSwap, 3.290.0, ユーザー提供パッチを検証のうえ統合）  → `docs/history/3.2xx.md`
- keep-best統一の取り残し修正＝UI層2経路・テスト不変条件・退避の原子性（3.289.0, 外部レビュー3件＋自己発見1件）  → `docs/history/3.2xx.md`
- 判断ログの3軸強化＋RSIラウンド行のスパム抑制（3.288.0, ユーザー指示「時間/回数/状態の3軸をログ強化する。ログスパム対応する」）  → `docs/history/3.2xx.md`
- keep-best比較順の統一＝第2キーを total→weightedScore へ（3.287.0, ユーザー指示「停滞に至るまでの改善の質を賢く高める」→AskUserQuestionで「isBetterをweighted優先化」を明示選択）  → `docs/history/3.2xx.md`
- 画面間冗長性の解消4件（3.286.0, ユーザー指示「各画面と各オブジェクトの一覧表を作成し、画面間の冗長性をシンプルにする」→「フルコードトレースしてフルコードレビューする」でD追加）  → `docs/history/3.2xx.md`
- 判断設計監査の改善3件（3.285.0, 全5画面監査の「改善して再テスト」項目→「マージする」）  → `docs/history/3.2xx.md`
- 外部リポジトリ全体レビューの検証と採用5件の実装（3.284.0, ユーザー「何%正しいか?」→「強化修正する」）  → `docs/history/3.2xx.md`
- 3.283.0実機検証＝5連リリース全機能の実効確認＋フェーズ行全滅の自己回帰を修正（3.283.1）  → `docs/history/3.2xx.md`
- ログ観測性の強化＋スパムログ対策（3.283.0, ユーザー指示「ログ解析出来ない箇所はログ強化する。スパムログ対策する」）  → `docs/history/3.2xx.md`
- 新領域のログ並列監査＝適応ポートフォリオ改善判定の恒真化ほか一括修正（3.282.0, ユーザー指示「新領域もログ解析する。不具合など修正する。コスト無視する」）  → `docs/history/3.2xx.md`
- 停滞脱出レビューで確定した2欠陥の修正＝c3n構造壁の動的床＋HF63エポック横断共有（3.281.0, ユーザー指示「停滞脱出は賢く適切かログ解析してコードレビューする」→「新しい不具合を修正しマージする」）  → `docs/history/3.2xx.md`
- 禁止連続(c3n)の「なぜ崩せないか」診断＝ForbiddenDiag新設（3.280.0, ユーザー指示「実装する、実装コスト無視する」）  → `docs/history/3.2xx.md`
- 3.279.0セルフレビュー指摘5件の後始末（3.279.1, ユーザー指示「コードレビューする」→「修正する」）  → `docs/history/3.2xx.md`
- 外部レビューC1-01〜C1-12の検証と修正（3.279.0, ユーザー指示「不具合など修正する」）  → `docs/history/3.2xx.md`
- 敵対監査で実証した2クラッシュ＋正しさ/実効性バグの一括修正（3.278.0, ユーザー指示「新しい論理的な不具合などを見つける」→「すべて修正する」）  → `docs/history/3.2xx.md`
- c1Deltaをload-bearing化=exact net c1 deltaへ格上げし候補順位付けへ接続（3.277.0, ユーザー選択「c1Deltaもload-bearing化」）  → `docs/history/3.2xx.md`
- index駆動C1修復オペレータ新設=screenCell/c1Deltaを実駆動する経路（3.276.0, ユーザー指示「接続する」→AskUserQuestionで「screenCellを新規オペレータへ」）  → `docs/history/3.2xx.md`
- C1研磨アーキテクチャを図どおりに寄せる=Index/Operators façade/Delta Prefilter を新設（3.275.0, ユーザー提示のパイプライン図→「賢く実装する。実装コスト無視する」）  → `docs/history/3.2xx.md`
- 3.273.0のA4/診断3件を敵対監査で確認・修正（3.274.0, 実機ログ4fca3273→ユーザー指示「敵対監査」）  → `docs/history/3.2xx.md`
- C1 Repair Analysis + 厳密窓修復（A1-A6, 3.273.0, ユーザー指示「A1からA6を実装する。コスト無視する」）  → `docs/history/3.2xx.md`
- Constraint IR + MUS＝矛盾の最小説明エンジン（3.272.0, v8構想第1段・ユーザー指示「あなたが賢く実装する。実装コスト無視する」）  → `docs/history/3.2xx.md`
- 実機ログの敵対解析＝共同LNS恒常飢餓の解消＋実行入口3種の穴を修正（3.271.0, ユーザー指示「ログ解析して新しい論理的な不具合などを見つける。敵対検証をする」）  → `docs/history/3.2xx.md`
- 新規論理不具合の並列監査＝3件を確認・修正（3.270.0, ユーザー指示「新しい論理的な不具合などを見直す」）  → `docs/history/3.2xx.md`
- 後処理タイミングログの「HF66」誤表示を修正（3.269.0, 実機ログ精読で発見）  → `docs/history/3.2xx.md`
- C1JointLnsPolish/EliteIntegrationPolishの2件を賢く改良（3.268.0、外部レビュー評価の続き＋ユーザー指示「賢くアルゴリズムをし直す」）  → `docs/history/3.2xx.md`
- destroyRepair系のsoft-aware修復にweekly/fairを統合（3.267.0、ユーザー指示「残作業と残不具合と残提案を最適化する。実装コスト無視する」）  → `docs/history/3.2xx.md`
- 適応的仮説ポートフォリオ＝8並列仮説が同一解へ収束する問題への対応（3.266.0、外部パッチ受領・検証のうえ手作業で統合）  → `docs/history/3.2xx.md`
- Joint LNS予算按分の折半を既定比按分へ訂正（3.265.0、ユーザー質問「予算配分は適切か?」への自己検証）  → `docs/history/3.2xx.md`
- 外部コードレビュー(C1JointLnsPolish/PersonalBalanceJointLnsPolish)の受領・検証・修正（3.264.0）  → `docs/history/3.2xx.md`
- 600秒改善ゼロの停滞を実データで解剖＝covU「玉突き」診断の楽観バイアスを修正（3.263.0, ユーザー指示「600秒で改善ゼロという停滞そのものを調査」「新たに深く網羅的に改善する」）  → `docs/history/3.2xx.md`
- 初期解生成のC1残差=真の構造的衝突と確認＋2b-3診断のfalse negativeを修正（3.262.0, ユーザー指示「初期解生成でC1違反をゼロにする」）  → `docs/history/3.2xx.md`
- 初期解生成が「既に充足済みの盤面」でno-opになっていた実バグを修正（3.261.0, ユーザー実機報告「初期解生成後にC1違反になる。初期解生成が何度も出来ない」）  → `docs/history/3.2xx.md`
- AptPolish/FairPolishの自己振替が1パスにつき1単位しか解消できなかった欠陥を修正（3.260.0, ユーザー指摘「大島が違反研磨で来てない」）  → `docs/history/3.2xx.md`
- 初期解生成のC1が個人上限(rangeHi)を無視していた欠陥を修正（3.259.0, ユーザー実機ログ提示「初期解生成のC1は適切か?」）  → `docs/history/3.2xx.md`
- 初期解生成＝C1複数規則の反映を検証（3.258.0, ユーザー指摘「C1は複数ある。初期解生成に反映できるか?」）  → `docs/history/3.2xx.md`
- 初期解生成(賢い版)＝SmartInitialScheduler新設＋専用ボタン（3.257.0, ユーザー指示「初期解生成のアルゴリズムを新たに賢く作る」）  → `docs/history/3.2xx.md`
- staffRange厳密ピン(lo==hi)保護＝exactPinRegression新設（3.256.0, ユーザー指示「厳密ピン保護rangeAvoidの実装」）  → `docs/history/3.2xx.md`
- C1JointLnsPolish・PersonalBalanceJointLnsPolish新設＝受領・実測検証のうえ最終研磨として追加（3.255.0）  → `docs/history/3.2xx.md`
- C1研磨5系統の整理＝C1TemporalFlowPolish新設でC1TemporalSwapPolish/BeamC1PolishV2を置換（3.254.0）  → `docs/history/3.2xx.md`
- c1(窓の要件)重み5→15（3.253.0, ユーザー明示数値指示）＋Free系リペア(covO/c41/c41s/c42/c42s)を実チェッカーによるkeep-best gateへ全面刷新  → `docs/history/3.2xx.md`
- 「大嶋と美幸の違反研磨は適切か?」への回答＝Free系リペア(covO/c41/c41s/c42/c42s)の欠陥を発見・全面修正（3.253.0）  → `docs/history/3.2xx.md`
- BeamC1PolishV2の停滞脱出=候補走査順のseed多様化（3.252.0, ユーザー指摘「停滞脱出しないのか?」）  → `docs/history/3.2xx.md`
- C1広域ビーム研磨=applyC1BeamPolish新設（3.251.0, 外部パッチ受領→重大な欠陥を発見・修正のうえ適用）  → `docs/history/3.2xx.md`
- C1協調ビーム研磨=BeamC1PolishV2新設（3.250.0, 外部パッチ受領・検証のうえ適用）  → `docs/history/3.2xx.md`
- 汎用玉突き結合フレームワーク新設＋c1/c3mn重み変更（3.249.0）  → `docs/history/3.2xx.md`
- c1(窓の要件)重み4→5・c3mn(回避の並び)重み12→15（3.249.0, ユーザー明示数値指示）  → `docs/history/3.2xx.md`
- RSI focus選択でweeklyの優先順位をaptより下げる（3.248.0, ユーザー明示指示）  → `docs/history/3.2xx.md`
- C1専用の時系列DP研磨を新設＝2箇所以上の同時移設でしか越えられない局所最適に対応（3.247.0, 外部パッチ受領・検証のうえ適用）  → `docs/history/3.2xx.md`
- 手Fを隣接日連動型へ拡張＝上條洋平のDﾃ3→2回で判明した穴を修正（3.246.0）  → `docs/history/3.2xx.md`
- RangePolishに柔軟日フロー(手F)を新設＝日別シフト多重集合も変えられる最小費用フロー（3.245.0, 外部パッチ受領・検証のうえ適用）  → `docs/history/3.2xx.md`
- RangePolishに日単位最小費用完全割当(手M)を新設＝任意長循環の玉突きに対応（3.244.0, 外部パッチ受領・検証のうえ適用）  → `docs/history/3.2xx.md`
- countViolationsのapt表示優先度をweight1.0扱いへ（3.243.0, ユーザー明示数値指示）  → `docs/history/3.2xx.md`
- staffRange上限違反の構造的判定＋代用要員提示（3.242.0）  → `docs/history/3.2xx.md`
- 専用freeオペレータの改善がdestroyRepairDayで相殺される順序バグを修正（3.241.0）  → `docs/history/3.2xx.md`
- RSI5ラウンド完全停滞の修正＝destroyRepairStaffの摂動過大を是正（3.240.0）  → `docs/history/3.2xx.md`
- RangePolishのペアスワップ新設＋maxViolatedFamily最終ラウンド枠の固定順バグ修正（3.239.0）  → `docs/history/3.2xx.md`
- C1研磨に「職員内シフト配置の全ペア網羅再配置」を追加＝手R3（3.238.0）  → `docs/history/3.2xx.md`
- 進捗の「残り時間」表示が繰り返しリセットされる不具合修正（3.237.0）  → `docs/history/3.2xx.md`
- C1Polish頭打ち理由の可視化＋休の適切回数チェックを実質的上限へ差替え（3.236.0）  → `docs/history/3.2xx.md`
- FairPolish・C3PatternPolish新設＋実機報告2件の修正（3.235.0）  → `docs/history/3.2xx.md`
- c42/c42s専用repair新設＝covO・c41,c41sと同型の穴を横展開（3.233.0）  → `docs/history/3.2xx.md`
- findCovUChainのmaxDepth既定を引き上げ＝depth5の壁を撤廃（3.232.0）  → `docs/history/3.2xx.md`
- ドッグフーディング4トピックの一括改善（3.228.0〜3.231.0）  → `docs/history/3.2xx.md`
- c1違反の職員×窓ルール別件数をログへ出力（3.227.0）  → `docs/history/3.2xx.md`
- covOの自動解消に隣接日調整を追加＝禁止連続で全候補が塞がる局面を突破（3.226.0）  → `docs/history/3.2xx.md`
- 仮説数の固定上限(5)を撤廃＝ワーカー設定まで仮説を増やす（3.225.0）  → `docs/history/3.2xx.md`
- 外部の並行/並列処理レビュー(9件)を実装（3.224.0）  → `docs/history/3.2xx.md`
- AptPolish新設＝適切回数(apt)専用の研磨パス（3.223.0）  → `docs/history/3.2xx.md`
- RangePolishの頭打ち理由をログ可視化＋制約編集(cons3mn等)の削除が反映されない実機バグ修正（3.222.0）  → `docs/history/3.2xx.md`
- ラウンド跨ぎで同一seed固定だった頭打ちを解消＝roundSeed新設（3.221.0, 「なぜゼロにならないのか」）  → `docs/history/3.2xx.md`
- 15日ブロック丸ごと2人交換研磨=BlockSwapPolish新設（3.220.0）  → `docs/history/3.2xx.md`
- 研磨ログに対象/残存職員名を追加（3.219.0）  → `docs/history/3.2xx.md`
- findCovUChain に rangeAvoid（新規range違反の後回し）を追加＝頭打ちの根本原因を修正（3.218.0）  → `docs/history/3.2xx.md`
- 停滞時間をログへ出力（3.217.0）  → `docs/history/3.2xx.md`
- 「他の制約は大丈夫か」監査→c3/c3m・c41/c41sへも玉突き連鎖を横展開（3.216.0）  → `docs/history/3.2xx.md`
- 玉突き連鎖(findCovUChain)をlow/high(個人回数)研磨へ横展開＝RangePolish新設（3.215.0）  → `docs/history/3.2xx.md`
- 玉突き連鎖(findCovUChain)をc3mn(回避,SOFT)研磨へ横展開＝C3mnPolish新設（3.214.0）  → `docs/history/3.2xx.md`
- 3.213.0の自己監査で発見した見落とし＝hard許容ゲートの旧スケール残置を修正（3.213.1）  → `docs/history/3.2xx.md`
- 外部レビュー再検証文書の8件を一括修正（3.213.0）  → `docs/history/3.2xx.md`
- 3.211.0の敵対的フルコードレビュー→主要修正の一括適用（3.212.0）  → `docs/history/3.2xx.md`
- 余剰ワーカーの実質的活用＝RSI/RSI++のSAチェーン数拡張＋ALNS多チェーン新設（3.211.0）  → `docs/history/3.2xx.md`
- countViolations(markCount)を重み優先へ統一（3.210.0, 「新領域も敵対検証する」の追加防御）  → `docs/history/3.2xx.md`
- 停滞脱出アルゴリズムのゼロベース敵対検証で発見したc41/c41sの専用repair欠如を修正（3.209.0）  → `docs/history/3.2xx.md`
- apt(適切回数)も同型の恒久的starvationを起こしていたことを確認・修正（3.208.0, 「他も検証する」）  → `docs/history/3.2xx.md`
- covO周期枠が典型的な短いRSIフェーズで空振りする実効性不足を修正（3.207.0）  → `docs/history/3.2xx.md`
- 同種のanchor選定シャドーイングをC3系研磨・回転研磨(C1Rotate/C3Rotate)にも横展開（3.206.0）  → `docs/history/3.2xx.md`
- C1研磨のanchor選定が重み優先シャドーイングで職員を取りこぼす実バグを修正（3.205.0）  → `docs/history/3.2xx.md`
- 人員過剰(covO)を探索フォーカスへ組み込み実際に解消する（3.204.0, 3.203.0診断の恒久対応）  → `docs/history/3.2xx.md`
- 人員過剰(covO)の「なぜ減らないか」診断を新設（3.203.0, ログ強化）  → `docs/history/3.2xx.md`
- docs/business-logic.md のドリフト修正（3.202.0, 「残不具合などを修正する」の一環）  → `docs/history/3.2xx.md`
- FixSuggester の改善提案リストが同一の盤面変化を複数回表示する不具合を修正（3.202.0）  → `docs/history/3.2xx.md`
- C1研磨・手Bの玉突き連鎖に c1Pref 優先付けを追加（3.201.0、外部検証の追認）  → `docs/history/3.2xx.md`
- C1研磨アルゴリズムの再設計＝回数保存移設プリミティブの追加（3.200.0）  → `docs/history/3.2xx.md`
- ネイティブSAチャンクの-1セル未対応＝実データで番兵発火（高速化の根本修正, 3.199.0）  → `docs/history/3.1xx.md`
- 交互最適化（Alternating Optimization）をソフト制約研磨に追加（3.198.0）  → `docs/history/3.1xx.md`
- weekly（曜日平準化）研磨の穴を長方形交換で埋める（3.197.0）  → `docs/history/3.1xx.md`
- 操作ログのフェーズ遷移・必須改善ログが巻き戻り時計で欠落するバグ修正（3.196.0）  → `docs/history/3.1xx.md`
- ホーム〜設定タブの冗長性一巡監査（3.195.0）  → `docs/history/3.1xx.md`
- 勤務表タブ「不一致だけ抽出」の撤去＋希望バッジのcanDo不整合修正（3.194.0）  → `docs/history/3.1xx.md`
- 勤務表タブの「職員別カレンダー」撤去（3.193.0）  → `docs/history/3.1xx.md`
- 設定画面のテキストアート再現＋「おまかせ」解決先の表示（3.192.0）  → `docs/history/3.1xx.md`
- 設定タブの用語重複解消：「並列ワーカー」と「並列(複数案)」（3.191.0）  → `docs/history/3.1xx.md`
- 同種の再構成バグの横展開（3.190.0, ユーザー指示「他の画面も再検索してください」）  → `docs/history/3.1xx.md`
- 「③ 回数（1人あたり）」+/- が同一画面で反映されない実機バグ修正（3.189.0）  → `docs/history/3.1xx.md`
- 設定タブ「最適化設定」のオプション集約（3.188.0）  → `docs/history/3.1xx.md`
- 希望シフト登録も「4つの情報」に集約（3.187.0）  → `docs/history/3.1xx.md`
- 必要人数設定を「4つの情報」に集約（3.186.0）  → `docs/history/3.1xx.md`
- HARD残でもSOFTをRSI focusできるようにする（3.183.0, 実データ検証で根本特定）  → `docs/history/3.1xx.md`
- 下流→上流ディープリンク「設定で直す」（3.182.0, 3.180.0 タスク2の完了）  → `docs/history/3.1xx.md`
- Android 16並行/並列監査＋16KBページ対応（3.181.0）  → `docs/history/3.1xx.md`
- 希望/必要人数カレンダーのレイアウト刷新＋未設定導線（3.180.0）  → `docs/history/3.1xx.md`
- バックログB/C の消化（番兵対称化・停滞時計・デッドコード除去, 3.179.0）  → `docs/history/3.1xx.md`
- ネイティブパリティのCI自動化（backlog#6 解消, 3.178.0）  → `docs/history/3.1xx.md`
- メインスレッド負荷の削減=表示解析の並列化＋起動I/Oの並行化（3.176.0）  → `docs/history/3.1xx.md`
- allowedShiftsFor をキャッシュ経由に統一（メインスレッド負荷削減, 3.175.0）  → `docs/history/3.1xx.md`
- SaChunk の c3 窓マッチもビット化（3.174.0, 3.172.0の続き）  → `docs/history/3.1xx.md`
- 版数の重複ラベルを全数走査＝真の衝突は 3.173.0 の1件・注記で正史化（3.426.0）  → `docs/history/3.3xx.md`
- CoverageDiagnosis の need2 単独定義セル見落とし修正（3.173.0）  → `docs/history/3.1xx.md`
- Android 17 会話バブル対応（2026-07-15・PR#27。※当時の版数ラベル「3.173.0」は重複）  → `docs/history/3.1xx.md`
- SaChunk のビット化評価（c1窓・c41/c42/c41s/c42s の O(1) 化, 3.172.0）  → `docs/history/3.1xx.md`
- ネイティブ照合トグル＋監査#7 SIGSEGV修正（3.171.0）  → `docs/history/3.1xx.md`
- weekly/fairも同じ理由でRSI探索focusに追加（3.170.0, 「apt以外は大丈夫か」への回答）  → `docs/history/3.1xx.md`
- apt(適切回数)をRSI探索focusに追加（3.169.0, 「公平化のズレ」実機report対応）  → `docs/history/3.1xx.md`
- 希望シフトカレンダーのインタラクティブ化（3.168.0）  → `docs/history/3.1xx.md`
- 必要人数カレンダーのインタラクティブ化（3.167.0）  → `docs/history/3.1xx.md`
- 必要人数カレンダー＋希望シフトの既存登録可視化（3.166.0）  → `docs/history/3.1xx.md`
- 「Dﾃ-Dﾃ」仮説の検証＝隣接日調整の全候補探索を実データで確認（3.165.0）  → `docs/history/3.1xx.md`
- 希望シフト登録の見つけやすさ改善（3.164.0）  → `docs/history/3.1xx.md`
- covU多人数連鎖(E11)を禁止連続の回避=隣接日調整へ拡張（3.163.0）  → `docs/history/3.1xx.md`
- 対応OSをAndroid 16以降のみに変更（3.162.0）  → `docs/history/3.1xx.md`
- 未レビュー領域の再監査（3.161.0）  → `docs/history/3.1xx.md`
- Gradle 9 移行（3.160.0）  → `docs/history/3.1xx.md`
- 敵対的コードレビューで判明した2件の修正（3.159.0）  → `docs/history/3.1xx.md`
- C1研磨への多人数ブロック移動の反映（3.158.0）  → `docs/history/3.1xx.md`
- 禁止連続の枝刈りを任意長へ一般化（三連・五連など, 3.157.0）  → `docs/history/3.1xx.md`
- 人員不足の「なぜ埋まらないか」内訳（CoverageDiag 拡張, 3.156.0）  → `docs/history/3.1xx.md`
- 多人数ブロック移動（勤務→勤務連鎖, 3.155.0）  → `docs/history/3.1xx.md`
- 未レビュー領域の精読（3.84.0, 並列監査で一巡）  → `docs/history/3.0xx.md`
- 回数設定画面（スマホ最適化・シフト中心, 2.60.0 Stage1-3）  → `docs/history/2.x.md`
- 事前診断（実行前の過拘束検知, 2.47.0 完了）  → `docs/history/2.x.md`
- 2.41.0 時点のスナップショット（当時の記録・現在の版ではない）  → `docs/history/2.x.md`
- native/JNI 層への外部レビュー2本の検証（コード変更なし, ユーザー提示「フルコードレビュー」「深いレビュー」）  → `docs/history/3.4xx.md`
- 後処理研磨の「Range 先頭化」を3データセット A/B で否決（敵対検証ケース6の続き・実データ受領）  → `docs/history/3.3xx.md`
