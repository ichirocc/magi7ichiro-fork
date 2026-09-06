# CLAUDE.md — MAGI ShiftOptimizer (Android) 引き継ぎ

> このファイルは Claude Code 向けのプロジェクトメモリです。チャット側で進めた作業の引き継ぎを兼ねます。

> **まず読む（ドキュメント入口）**：設計・仕様・業務ルールは [`README.md`](./README.md) の「ドキュメント目次」から各 `docs/*.md` に分かれています。実装・調査の前にそこで当たりをつけてください。とくに **業務ルール＝[`docs/business-logic.md`](./docs/business-logic.md)**、**データ項目＝[`docs/data-models.md`](./docs/data-models.md)**（存在しない項目を創作しない）。
> **更新ルール（stale 化させない）**：コードを改修したら、影響する文書（特に `business-logic.md` / `data-models.md`）と `README.md` の目次・最終更新を**同じコミットで**更新する。事実が変わりやすい順に独立させているのは、ここを最新に保つだけでハルシネーションの大半を抑えるため。
> **過去の作業記録（必読の引き方）**：版数付きの作業記録（約360節）の見出し一覧は [`docs/history/INDEX.md`](./docs/history/INDEX.md)、
> 本文は [`docs/history/`](./docs/history/) にある（3.468.0 で分離を始め、3.497.3 で索引と話題別の叙述も本体から出した）。
> **この本体に残っているのは「毎ターン効く恒久の事実」だけ**＝方針・制約の意味・重み・アーキテクチャ・決定記録・
> 検証手段・バックログ。**個々の版で何をしたかは本体には無い**ので、索引から `docs/history/` を引くこと。
> **同じ領域を触る前に必ず索引を検索する**＝このリポジトリは「測って否決した案」「同型のバグ」「決定記録（再提案しない）」を
> そこに積んでおり、引かずに着手すると否決済みの案を作り直す。引き方は `grep -n 'キーワード' docs/history/INDEX.md` → `grep -n '（3.409.21' docs/history/3.4xx.md`。
> 新しい作業記録は **`docs/history/INDEX.md` の先頭に1行足し、本文は `docs/history/3.4xx.md` の先頭へ**書く（CLAUDE.md 本体には積まない）。
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
  複数ステップ計画→writing-plans→executing-plans/subagent-driven-development・文章推敲→writing-clearly-and-concisely・
  **コミット前→comment-check**（`.claude/skills/comment-check`＝追加したコメント行を `tools/comment_ratio.py` で抽出し
  1行ずつ残す/移す/消す/縮めるを判定。ルールでなく手順にしないと効かない、という計測結果に基づく。3.497.1）
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

## ツール選択ルール（Serena 試験導入・3.497.2）
`.mcp.json` に Serena（言語サーバー経由のシンボル検索）を登録した。使い分け:

| 質問の種類 | 使うもの |
|---|---|
| **シンボル名が分かっている**定義元・参照先・実装クラス・行番号（「betterReport の呼び出し元は？」「WindowMode を実装/使用しているのは？」） | Serena `find_symbol` → `find_referencing_symbols` / `find_implementations` |
| シンボル単位の置換（関数本体の差替え・改名） | Serena `replace_symbol_body` / `rename_symbol`（エンジン層のみ。UI 層は従来どおり Edit） |
| 意味・目的での検索（「人員不足を埋める処理は？」）・文言・docs・ログ | **Grep / Glob / Read**（実測でグラフ系の意味検索は外れが多く、Serena は名前が要る） |

- `.mcp.json` はこのリモート環境でも読まれる（3.497.3 で確認）。ただし新しいコンテナでは Kotlin 言語サーバーの
  取得（`~/.serena/language_servers`）と起動が初回に走り、3.497.3 ではその initialize が 17 秒で cancel され
  `find_symbol` が使えなかった（SessionStart フックの背景索引と起動が競合した疑い→フックは撤去）。**失敗したら従来どおり
  Grep で進める**（`Stop` の指示は Serena のツール文言であって、この repo の作業を止める理由にはならない）。
  成功時の初回呼出は約35秒（索引済み）・約90秒（索引なし）、以後は1秒前後。
- 参照一覧は大きい（betterReport で 51KB）ので、参照が多いシンボルは Grep のほうが安い。
- ナレッジグラフ系（code-review-graph / Graphify）は Kotlin の呼び出し解決が0件だったため**入れない**（計測は 3.497.2）。

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
> **（2026-09-04 更新）** ユーザー指示「OPPO A5 5G(Android16)も動作できるようにする」で、この端末（720×1604 HD+＝360dp 帯）を
> 対象に加えた（3.497.0）。幅 390dp 未満だけ名前列を 56dp に詰めて7日表示を成立させ、下部バーの補助2ボタンをアイコン化。
> 390dp 以上は不変。D4 の「再提案しない」は「ユーザー指示なしに縮小最適化を提案しない」の意味で維持。

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

## ドッグフーディング・後処理研磨・回数設定UI＝恒久の事実

- 同日2者スワップで直せる単日族（c2/c41/c42/c41s/c42s）は **CyclicSwap が isBetter で既に研磨**＝専用パスは冗長（2.49.0 で撤去）。
  探索側の focus 順に c41s/c42s を登録済み（2.46.0）。ソフト研磨は 3.94.0 の網羅 A/B で**構造的下限**と確認済み
  （in-loop の追加レバーはすべて不整合か有害。候補生成 proxy `rangePen` は 90/45 で目的関数と整合）。
- 回数設定UI: 第1〜2段（現状回数「今◯」＋apt 実効目標の併記、`staffCountRules()`）は完了。**第3段（need/c41 と apt/low-high/c2/c1 の
  軸ハブ分離）は未着手＝`ConstraintsCard` 分割を伴う大改修で別途着手**。
- 表示・導線の版数付き記録（2.61〜3.149）は `docs/history/topics.md`「ドッグフーディング改善」「後処理研磨の族カバレッジ」。

## ネイティブ加速（C++/NDK）＝恒久の事実

- **Kotlin が正、C++（`app/src/main/cpp/magi_native.cpp`）は同値の高速版**。評価器・SA PhaseA・LAHC PhaseB・ALNS チャンク・
  修復群（hf67 含む）・HF80 研磨チャンクがネイティブ。RSI 制御層・V6LateOperators・後処理チェーンはチェッカー依存の軽量層で
  **意図的に Kotlin のまま**（移植しないと確定＝3.153.0）。
- **2層番兵**: ①チャンク末尾の C++ 自己整合 ②best 更新チャンクを Kotlin `Evaluator.fullEval` と Long== 照合。どちらか発火で
  `NativeGate` が閉じそのプロセスは Kotlin へ退化（クラッシュさせない）。番兵の全体計算は正しさの根幹＝削らない（3.154.0）。
- 設定トグル「ネイティブ加速（C++）」（既定ON）と「照合トグル」（既定ON）。`.so` ロード失敗時は `NativeBridge.available=false`
  で全経路 Kotlin。JVM テストは常に Kotlin 経路。
- **パリティは CI が守る**: `.github/workflows/native-parity.yml`（`tools/native/host_parity_bench.cpp`＋言語跨ぎ期待値
  `golden_eval_expected.txt`、実データ3形状）。`Evaluator.kt`/`MirrorCore.kt`/`DeltaEvaluator.kt` を変えたら
  `magi_native.cpp` も同じコミットで（バックログ #6）。
- 経緯（Stage1〜13・第2期・第3期・3.135〜3.077 の版数付き記録）は `docs/history/topics.md`「ネイティブ加速」。

## 停滞脱出＝恒久の事実

- **探索動学の変更は `tools/nsp_bench.py` で A/B してから採否**。指標は AUC（速度）でなく **final（最終品質）**（実データで両者は
  乖離した）。便益が測れない／負なら入れない。
- 採用済み: soft-aware destroy-repair 3種（day/staff/violations, `staffCountPenaltyAt`＝Evaluator と同一式）＋c41-aware（2.57〜2.59）、
  RSI focus は「解ける HARD 族を先に」（3.74.0）＋静的 covU 床の除外（3.95.0、N4 早期脱出は `dynamicAvoid` のみでゲート）＋
  c3n は `destroyRepairViolations` へ（3.101.0）＋件数0の族を focus しない／空振り focus の1R冷却／HF80 の停滞早期終了（3.150.0）、
  `runV5` の入力比番兵（3.97.0）、ExtraRefine は後処理予約枠でキャップ（3.102.x）。
- **測って否決（再提案は計測つきで）**: 戦略的振動、nonlinear restart、GLS のパラメータスイープ、targeted-perturb、big-destroy、
  softFocusProb の変更。GLS aging は中立で温存。
- 経緯と数値は `docs/history/topics.md`「停滞脱出の改善」。

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

9. **[探索動学・要計測] 希望島研磨（3.496.0）への外部レビュー指摘 4 件**（3.500.1 で登録。詳細は `docs/history/3.4xx.md`「外部レビュー…の Android 同期（3.500.1）」）:
   (a) 島ごとの評価枠を同日候補が先に使い切り窓・両翼・巡回が飢餓になる（種別ごとの枠分割 or 安価な差分評価で混合）、
   (b) `makesForbiddenRun` の事前枝刈りが禁止連続を**減らす**手まで落とす（増分判定へ）、(c) ビームの `expandNode` が
   列挙順の先頭 `beamWidth*beamBranchFactor` 件だけを並べ替える（小容量の優先度キューへ）、(d) `clearOutOfScopeWishes` が
   非同期診断の `settingIssues` を根拠にする（`Problem.canDo` から再判定へ。C# は変更済み）。Kotlin が正なので **Android 側で
   PostProbe/`nsp_bench` の A/B を添えて先に変え、C# は後追いで同期**（C# 単独では変えない＝パリティ維持）。

10. **[UI・同型] 「なおすのを手伝って」の連打防止が盤面の変更で解除される**（3.500.2 で登録。-MAGI_PC 側の外部レビュー第3段）。
   `GuidedFixDialog` の `pending = remember(ui.schedule)` は setCell 直後の schedule 変化でリセットされるが、`coverageDiag` の再検査はまだ
   終わっていない＝古い診断の候補が再有効化され、素早い2回操作で covO と covU を同時に作れる。C# は `UiState.CheckRev`（`makeUi` ごとの検査世代）
   を追加し「押下時より新しい世代の反映」でだけ解除する形に直した（`GuidedFixFlow`）。Android も同じ形（UiState に検査世代を足す）で直す。

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

## 作業記録の索引（`docs/history/INDEX.md`）

版数付きの作業記録（約360節）の見出し一覧は [`docs/history/INDEX.md`](./docs/history/INDEX.md) にある（3.497.3 で本体から分離。
毎ターンの固定費 1.9 万トークンを外すため）。本文は `docs/history/{2.x,3.0xx,3.1xx,3.2xx,3.3xx,3.4xx}.md`。
**同じ領域を触る前に必ず引く**＝「測って否決した案」「同型のバグ」「決定記録」を二度踏まないため:
```
grep -n 'キーワード' docs/history/INDEX.md        # 見出しで当たりを付ける
grep -n '（3.409.21' docs/history/3.4xx.md        # 版数で本文を引く
```
新しい作業記録は **INDEX.md の先頭に1行**足し、本文は `docs/history/3.4xx.md` の先頭へ書く（本体には積まない）。
