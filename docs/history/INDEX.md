# 作業記録の索引（見出し一覧）

- 全面改修の最終検証 G15・G16 の測定（2026-10-29、版なし・測定のみ、ユーザー「最終検証の測定」「推奨で」）: 3.723.4 と main（重みだけ旧値へ戻した写し）の対比較 60 組。必須の悪化 0、時間 −0.8%（合格）、weighted 平均 −36.8 だが 95% 区間 [−102, +29]＝上端 0 以下の線は未達。C++ の bit 演算 Δ 適用は −1.6〜−2.4%（3.726.0・3.727.1 の機能修正の分、改修の構造変更は ±0）＝許容 −2% の境目。blocked_covu の +91 は同じ seed の再走で 3 組とも新が良く揺れと判断（weekly だけ新がやや多い＝要観察）。合格の線（区間の上端 ≤0）は緩めず保留＝許容値は業務担当者の数値指示待ち  → `docs/overhaul_design.md` §4.4
- **3.727.13（2026-10-29、ユーザー「アップル社の最新のアプリのインターフェースを取り入れる」→ 選択「通知を浮くカプセルに」「触覚の手応えを揃える」）**: Snackbar を `MagiNoticeCapsule`（浮く角丸・影なし・角 26dp）に。触覚を `MagiHaptic` の確定／拒否へ（セルの変更 9 か所の LongPress→確定、失敗の通知と「つくる」の終わり）。長押しのメニュー・シートの段止まりは不採用。`HapticRulesTest`  → `docs/history/3.4xx.md`
- **3.727.12（2026-10-29、ユーザー「次」→ S5 の進め方で選択「入口だけ足す」）**: UI 整理仕様 S5。月次条件の見出しカードの下に「回数の目標 ›」（年間マスター③へ着地、`EDIT_COUNT_ROW`）。仕様どおり月次へ回数を移す案は、制度の設定と毎月の設定が混ざり 3 分割（誤編集の防止）と衝突するため不採用。これで S1〜S7 の合格条件 A1〜A7 はすべて満たす（A7＝全消去の操作は無い）。出力不変。C# 同日同期
- **3.727.11（2026-10-29、ユーザー「次」＝UI 整理仕様 S7）**: 設定タブは既に「計算の詳しい項目・実験的なスイッチは詳細設定に畳む」形だったので、差だけを直した: 見出し「設定」→「計算」・「外観」→「表示」、シフトの表示色と違反種別の色を「色 ▸」に畳む（`SETTINGS_COLORS_HEADING`）。全消去の操作は無い＝A7 は該当なし。残りは S5（編集タブ、年間と月次の混在の懸念あり）。出力不変。C# 同日同期（色と直す優先順位を Expander に）
- **3.727.10（2026-10-29、ユーザー「次」→ 選択「S1 ホームの仕上げ」）**: UI 整理仕様 S1 の残り。問題の一覧を見る段（LIST）の主ボタンを「1を開く（アリフの並び）」（`homeOpenFirstLabel`）＝次にやることの先頭のセルのシートへ、他の段は不変。ⓘ でき具合の意味と下書きから作り直すをカード末尾の「…」メニューへ（`HomeSection.DRAFT_LINK` 廃止、説明の文は不変）。S7・S5 は未着手。出力不変。C# 同日同期。`NextItemsTest`
- **3.727.9（2026-10-29、ユーザー提示の画面案 5 枚「画像を熟読する」→ 選択「ホーム『次にやること 3件』」「問題一覧の行の形」「セルのシートを『直す手』中心に」）**: ホームに必須違反の上位 3 件（`homeNextItems`、札＝直せる見込み／希望のまま／確認が必要）、問題一覧は大きな題・2 段の行・［開く］・要調整を畳む、セルのちら見に関係する日の札と「直す手」（`ShiftMarks.best`）＋［この手を使う（元に戻せます）］・希望／手動固定の行。画面案の誤り（火曜の重複・D班/Dテ・4 タブ・作文の理由）は採らない。あわせて 3.727.8 の角の数を `CornerMetrics`（純 Kotlin）へ分けホストのテストで組めるように。出力不変。`NextItemsTest`  → `docs/history/3.4xx.md`
- **3.727.8（2026-10-29、ユーザー「最新のアップル社の最新のアプリのインターフェースを全体的に取り入れる」→ 選択「角丸の同心化＋カプセル」「下部の操作を1つの帯に」）**: 同心の角（`CornerMetrics`・`concentricCornerDp`）＝カードの中の面 10dp・カード 26dp・シート 28dp、状態の札・絞り込み・選ぶ札・セグメントはカプセル。勤務表の統合バーの週送り・元に戻す・やり直しを 1 本のカプセルに束ねる。ガラスの半透明は D8（UD 固定）で除外。出力不変。C# 同日同期。`MagiCornersTest`  → `docs/history/3.4xx.md`
- **3.727.7（2026-10-28、ユーザー提示「MAGI UI Declutter Specification (S1–S7)」→「外れている 3 つから」）**: 合格条件 A1〜A7 を照合（A1・A3・A5・A7 は満たす／A7 は全消去の操作自体が無い）し、外れていた 3 つを直した。①A2（UI-S2）勤務表の種別フィルタは 3 つ（人員・希望・回数）＋［その他 ▸］、外している札は閉じていても出す（`visibleVioBuckets`）②A4（UI-S4）下部バーの［◀違反 k/N］［違反▶］と切替チップ［違反の日を順に見る］を撤去＝問題は［必須 N ▼］とシートの［次の違反 ▶］から（`BottomBarMode.MERGED_VIOLATION`・`ScheduleNavState.vioMode/vioDays/navIdx` を削除）③A6（UI-S6）分析タブの主ボタン（`AnalysisPrimary`＝ホームと同じ「次の問題へ」の形）を外し、分析の主は変更案のカードに。S1 ホームの 3 行・S5 編集タブの組み替えは 2 便目。`VioBucketsVisibleTest`・`Review3705Test` 更新。画面のみ・出力不変
- **3.727.6（2026-10-28、外部レビュー「不具合・論理的問題・リスク一覧」10 件の照合）**: ①No.1 盤面指紋 `StateFingerprint.ofBoard` は `h*31+v` で行の区切りも大きさも混ぜず `[1,0]`/`[0,31]` 等が作りの上で衝突した＝「計算中に盤面が変わったか」の照合（代行・案の適用・試算・下書きの確認ほか約 20 か所）が誤って同じと見得た→行数・各行の長さ・各値を 1 つずつ強く混ぜる形に（`ofBoardSeparatesTheOldStructuralCollisions`、golden 更新。操作ログの盤面指紋の値はこの版から変わる）②No.5 Worker の入力の退避が書けなかった（false）ときも「入力退避」と成功を記録していた→W で「復元できない」と記録 ③No.8 指紋のテストに拡張希望が漏れていた→足し、`MagiState` に欄が増えたら落ちる `everyStateFieldIsCoveredOrDeliberatelyExcluded`。ほか（No.2 所有権・No.3 CI・No.4 直接書きは記録済み・No.6/7/10 は設計・運用どおり・No.9 は 3.727.4 で一本化）は変更なし。C# も同日同期
- **3.727.5（2026-10-28、外部レビュー UI①「問題を見つけてから直すまでの導線」、ユーザー「次」）**: 勤務表タブの問題一覧へセルのシートの「一覧へ」で戻ると、一覧はいつも先頭から出て、見ていた問題を直して消えていると選んだ行も無くなった（次の問題を自分で探し直す）→`listResumeIndex`＝見ていた行が残ればそれ、消えていれば同じ位置に繰り上がった次の問題（末尾を越えたら最後）。選んだ行まで一覧を送り（`rememberLazyListState`）、繰り上がった行を選んだときは表もそのセルへ送る。`ListResumeTest` 3 件。画面のみ・出力不変
- Worker の停止・失敗の片付けを所有者の錠の中へ（2026-10-28、3.727.4、外部レビュー P0「Worker の実行所有権を最後まで一貫させる」→「推奨で」）: 停止・失敗の出口は `ownsFiles()` を見てから錠の外で `clearFiles` していた＝その間に始まった新しい実行 B の入力と所有権の印まで旧実行 A が消せた（完了の出口 `releaseIfOwner` は錠の中で一続き＝停止と失敗だけ取り残し）→`RunFiles.clearIfOwner`（確認と削除を `OWNERSHIP_LOCK` の中で）。進捗の間引きと所有権の喪失（単調）を `ProgressGate` に出してホストでテスト可能に。`WorkerReplaceRaceTest` 6 件＝A を B で置き換えたあと、A の遅れた進捗・途中最良の退避・結果の保存・完了／停止／失敗の片付けのどれも B を変えない、B は普通に完了できる。出力不変
- 作成中の分析タブを閉じている間の作成にも（2026-10-28、3.727.4、残件）: 3.727.2 の「作成中は作成前の診断を並べない」は `running && keepScreenOn` で判定していた＝閉じている間の作成（Worker）は画面を点けたままにしないので古い行が並んだ→`engineBuilding(ui)`＝前景の盤面ジョブか `bgRunning`（Worker の running を受けた欄）。短い違反チェックの running は含めない。`EngineBuildingTest`。C# `BuildingNow` も `_bgRunId` を見る（同日同期）
- 3 者回転・ブロック交換の前フィルタに c3w・extWish（2026-10-28、3.727.4、ユーザー「残件対応する」）: `C3RotationPolish.staffObjective` は HARD を c3n だけ数えていた＝希望の前日禁止・拡張希望の違反を減らす手でも部分目的が改善しないと見てフル checker を呼ばずに捨て得た。両族をセル単位の静的表（`c3wBanned`・`extBanned`、チェッカーと同じ）で数えて HARD と重みに足した。6 fixture の最終盤面ハッシュ不変（`BoardHashFixtureTest`・`PostStageOrderTest` 合格）。C# `V6HotfixPasses.ComputeStaffObjective` も同日に同期
- **3.727.4（2026-10-28、ユーザー「画面の数を数える。画面の統一感を揃える」→「8dp に寄せる」）**: 画面を実装から数え直し＝A 8・B 12・C 74（パネル 2・シート 4・ダイアログ 55・バブル 1、`docs/screen_inventory_textart.md` §1.0）。統一感の監査（見出しの文字の段・カードの内側の余白・ボタンの高さ・文字だけのボタン・閉じる系の外枠・開閉の言い方は規則どおり）で外れていたのは余白の 4dp 刻みだけ＝125 か所。6・10dp を 8dp、14dp を 12dp に寄せた（19 ファイル 100 行）。部品の内側の寸法 20 件は残し、`design_lint.py` P14（baseline 20 のラチェット）で増えないようにした。画面のみ・出力不変。外部レビュー P1「比較基準を全経路で統一」の照合で、置き換え前の勤務表の比較 `preDraftBetter` が採否の比較器を写し直していた（重みを 1e-9 の幅で同値扱い＝`reportComparator` は完全一致）→比較器の中身を `compareReportKeys` に出し、`reportComparator` と `preDraftBetter` の両方がそれを呼ぶ（出力不変）。ほかの経路（最終番兵・研磨・代行・別案）は `betterReport` か、文書に書いた例外（apt/fair の容認・平らな手の受理）だった
- **3.727.3（2026-10-27、作業ブランチ、ユーザー「推奨で」→「論理的問題点とworkerの不具合を見直しする」）**: ①下書きで置き換える前の勤務表を覚え（設定の指紋・必須の単位つきの語・重み・合計）、あとの作成の結果がそれに負けたらホームに「下書きに置き換える前の勤務表のほうが良い結果です」と［置き換え前の勤務表に戻す］（元に戻すで取り消せる）。3.727.0 実機ログで元 43337 を下書きに置き換え、4 回の作成が 43563〜43642 で負けていた。比較は必須→重み→合計（`preDraftBetter`）、勝てば忘れる、読込・取込・設定変更で捨てる。表示のみ＝出力不変 ②Worker: 置き換え（REPLACE）で旧 Worker が止まるまでに publish した途中最良（`liveBest` は実行をまたぐ 1 つの置き場）を、新しい実行が kill 復旧の退避に新しい状態と組で書けた（職員数が違えば復元不能）→形が同じときだけ使う（`liveBestShaped`、ライブ表示も同じ）③Worker: 探索設定（PolishGate）を各自「始めたときの値」へ戻していたため、重なると後の Worker が覚えた旧 Worker の投入値が最後に残り画面の設定と食い違った→最初に借りた Worker が元の値を覚え最後に返した Worker だけが戻す（`GateLease`）。`ScheduleWriteSitesTest` の許可に `restorePreDraft`、`PreDraftNoticeTest`・`GateLeaseTest`・`LiveBestPublishTest.shapedLiveBestRejectsAnotherRunsBoard`
- **3.727.2（2026-10-27、作業ブランチ・残件、ユーザー「残件対応する」）**: backlog「実機画面の見直しで残ったもの」の 2 件を手当て。①分析タブは作成中（`running && keepScreenOn`＝下書き・作成・仕上げのジョブ）に作成前の診断の行・場所を並べず「作成が終わると、問題の一覧を今の勤務表で調べ直します。」の 1 行だけ（今の数と食い違っていた）②セルのシートの上段（読むところ）が入りきらないとき、下に「続きを読む ▼」＝押すと 3/4 画面ぶん送る（行の途中で切れて下が空き、送れると分からなかった）。表示のみ＝出力不変
- **3.727.1（2026-10-27、作業ブランチ・実機画面 5 枚の見直し、ユーザー「推奨で」）**: ①盤面を差し替えるジョブ（作成・下書き・読込・取込）の開始で代行の記録を消す＝下書きから作り直した計算中に前の盤面の「5 手を当てました」と押せない「代行の分を元に戻す」が濃い緑の上にほぼ見えない色で残っていた（`beginBoardJob`）②代行の見出しの要調整は動いた単位 1 つ（「147→140件」、`softHeadDelta`）、全単位は「当てた手」の中（`agentSoftDetail`）③下の通知はカードの文を繰り返さず「代行で N 手を当てました」だけ（`agentNotice`）④作成のあと代行・案探しだけが動いている間の停止ボタンは「代行をやめる」「探すのをやめる」（`stopButtonLabel`）⑤日ごとのようすに凡例「● 人員不足 ・ 数字 必須違反のか所」⑥上部の札を「必須Nか所・不足M人を見る」へ縮める（2 行に折り返していた）。でき具合（満足度）の式は業務判断待ちで変えない。表示のみ＝出力不変
- **3.727.0（2026-10-27、作業ブランチ・実機確認用）**: 重みの入れ替え（c1 50→90・c3mn 90→50）を端末で確かめるために版を上げる（ユーザー「重みの入れ替えを進める」→「端末で試せる APK を出す」）。main へはまだ入れない。この上の「版なし」の各行（main 3.726.0 の取り込み以後）がこの版に入る
- 実機画面の見直しの残り 4 件の照合（2026-10-27、版なし）: 案なしの枠の［閉じる］が載せるダイアログの閉じると 2 つ並び、操作が説明の前だった→`NoFixBlock` の全面版は自分の閉じるを出さず、理由の行のあとに［問題一覧］。作成中のホームの診断カード（開始時点の数）は現行コードで出る経路が無い（3.696.0 から作成中は診断の束を出さない）。シート上段の途中切れ・名前の先頭の欠けは原因の推定のみ（backlog に記録）
- 低優先の残件 2 つ（2026-10-27、版なし・出力不変）: ①重みの記述のドリフト＝c1 50→90・c3mn 90→50 のあと現行値として旧値を書いていた `docs/screen_spec.md` の SOFT 表（並びも重み順に）と `docs/sudo_model.md` の階層を直した（版つきの経緯は残す。コード・テストのコメントには現行値の旧値なし）②`PolishGate.snapshot` の照合＝PolishGate 自身のフラグは全部載っていた。外の測定スイッチ `EjectionChainPipeline.shallowOnly`（既定 false・UI なし）だけ載っておらず、背景実行で既定へ戻っていた→snapshot/restore に載せた（既定は不変＝出力不変）。`StallEscapeSpecTest.outsideGateSwitchesRoundTripThroughSnapshot`
- 厳密窓探索の偽の「証明済みの壁」の照合（2026-10-27、版なし・コード変更なし）: backlog の残件は 3.726.0（main）の `leaves > 0` 条件と再現テスト `unrealizedWishOutsideTheMultisetMustNotClaimAWall` で解消済みだった（取り込み済み・7510662 の V6 Engine Check で合格）。完全配置を作れない日がある成分は葉が 0＝`exhaustive=false`。採否（best/patch）は葉でだけ更新＝探索の動きは不変。backlog の行に取り消し線
- backlog「C++ の取り残し（hf67 内 hf66 段）」の照合（2026-10-27、版なし・コード不変）: 3.726.0（C-03）で既に Kotlin `hf66DataHardening`／`refill`／`fillShiftIndex` に揃っていた（埋めシフト・上限0セル・手動固定・希望固定・拡張希望の禁止）。backlog の記載だけ解消へ
- main 取り込み後の V6 Engine Check の失敗を直す（2026-10-27、版なし）: main から入った `CheckKeepsDisplayTest.pushReportAppliesItWithTheCurrentState` は `MagiViewModel.kt` の本文で `pushReport` を探すが、作業ブランチでは P6f で `MagiViewModelDiagnostics.kt` の拡張関数へ移っていた＝探す先を直した（実装の `.keepDisplayOf(st, state)` は取り込み時に移植済み）。ホスト全 1582 件のうち失敗はこの 1 件だけ
- 重みの入れ替え（c1 50→90・c3mn 90→50）の効果の記録（2026-10-27、測定のみ・製品コード不変）: 旧重み b2cfa8d と新重み d830377 を同じ `WeightSwapProbe`（handleOptimize・仕上げ最適化 ON・実データ 4 件×seed 1〜3×60 秒×workers 1、族の件数で比較）。新−旧の平均: c1 −16.6 [−28.8, −5.8]（11/12 で減、sept2026 75.7→25.3）、low +3.8 [+1.0, +6.8]、high +4.6 [+0.8, +8.8]、c3m +2.1・c3mn +0.4・fair −1.4・c42 −0.8（区間 0 をまたぐか境界）、total −5.8 [−13.5, +2.7]、必須の差 0（12/12）。期間の制約が大きく減り、その代わりに個人の下限・上限の超過が少し増えた＝重みの順（low 120 > c1 90 > c3mn 50 > high 25）どおりの交換。結果は `tools/loop/results/2026-10-27-weight-swap/`（gitignore）、`tools/loop/run_weight_swap_probe.sh`・`weight_swap_stats.py`
- iOS のヘルスケアの「ハイライト」（2026-10-27、版なし・出力不変、ユーザー「推奨で」）: 作成・自動で整えるのあと、ホームに「ハイライト」＝この作成で必須違反・要調整が単位ごとにいくつからいくつになったか（`runDeltaText`、変化が無ければ出さない＝同じ結果の注記が受け持つ。寿命は runSummary と同じ）。残りの候補の照合: Numbers の見出し固定は 3.481.0 で済み（日ヘッダ・名前の列）、ヒントは「説明文は読まれない」原則と合わない、Wallet（他の案）とショートカット（作成のあとの代行）は同じ働きが既にある＝入れない
- iOS のリマインダー・天気・時計の形（2026-10-27、版なし・出力不変、ユーザー「推奨で」＝アプリ一覧から借りられる形の ⭐3 つ）: ①配布前の確認の行を丸い印（済み＝塗った丸に ✓・要対応＝赤い輪・未評価＝灰の輪、読み上げに語）にし、要対応を上・済みを下（`preDistributionOrder`）②ホームに「日ごとのようす」＝人員不足の日は赤の丸・必須違反のセルの数、押すとその日の勤務表へ（`HomeDayStrip`・`dayStripMarks`。天気の帯は横に送るが、横スクロールを必須にしない決まり（operator_ux）なので問題のある日だけを折り返して並べ、問題の無い日は数で言う）③作成中の輪を回り続ける印から、予算＋後処理の枠に対する経過の輪（40dp、`runProgressFraction`＝「残り M:SS」と同じ枠）。`IosPatternsTest`
- iOS 26 から上部の帯の縮みとシートの段（2026-10-27、版なし・出力不変、ユーザー「新しいアップル社の新しいアプリのインターフェースを取り入れる」→ grilling 2 問とも推奨案）: ①下へ送ると上部の帯を薄く＝MAGI の札と自動作成の状態の行を隠し、上下の余白 12→4dp（画面名・状態の札・保存の語は残す。下部タブの縮みと同じ `navCollapsed`＝読み上げ中は縮めない）②下から出るシート 4 つ（組む時間・日の必要人数・作成前の確認・職員×シフト）は半分で開き、上端のつまみ（48dp）をタップで全体⇄半分（`MagiSheetGrabber`。引き上げは片手一本指と合わない）。セルのシートは従来どおり「詳しく ▸」のタップ
- 勤務表 CSV の書き出しと「保存済み」の照合（2026-10-27、版なし、外部レビュー P2）: ①CSV を別スレッドで作るあいだ、セルの編集が同じ盤面の配列をその場で書き換える＝途中の編集が混ざった CSV になり得た→入力の固定は main（盤面の写し）、文字列化は Default（`exportCsvDeferred`、JSON の `exportJsonDeferred` と同じ形）②保存済みの近道が盤面の 64 ビットの指紋だけで「同じ」と言っていた（[1,0] と [0,31] は同じ指紋）→写しの盤面を中身で比べる。`CsvSavedMarkTest` に 2 件（書き出し後の同じ配列への編集・指紋の衝突）
- main 3.726.0 の取り込み（2026-10-27、版なし、ユーザー「推奨で」）: 別のセッションが同じ残件（#49）を並行して直して main に入っていた。二重の直し方は main に寄せた: 巡回は `tourCursor`（`tourRetarget`・`TourRetargetTest` を外す）、走行中の検査が表示を戻すのは `keepDisplayOf`（`restartStaleCheck` を外す）、背景実行の実行中は `RunningHolds`（`releaseRunningAtEnd` を外す）、進捗の錠は main の 1 つ、俯瞰カードの必須は main の「N件・人員不足M人」（`v6HardStat` を外す。目安の回数・ばらつきをチェッカーの件数にする `v6MetricLine` は残す）。main の案探しの時間切れの戻り値（`suggestResult`）は分けたファイル `MagiViewModelFixSuggestions.kt` へ、`keepDisplayOf` は `MagiViewModelDiagnostics.kt` の `pushReport` へ移した
- 重みの入れ替え c1 50→90・c3mn 90→50（2026-10-26、版なし、HF77 ユーザー明示指示「期間の制約 (c1) 90、回避の並び (c3mn) 50にする」）: `MirrorKeys.weights`・`Evaluator`・`DeltaEvaluator`・`magi_native.cpp`（評価器と SaChunk）・言語跨ぎ期待値 5 ファイル（soft＝旧＋40×(c1−c3mn)）・チェッカーのスナップショット（件数は不変・weighted だけ）・報告の要約ハッシュ・T-10 の段の並び（重みで探索が変わる＝基準を取り直した）・希望の試算の期待値・`ConstraintRepairInference` の許容（c1×3×2＝540）。表示は c3mn を HIGH→WARN（c1 と同じ注意。c1 は件数最多で飽和回避のため WARN のまま）。C#（-MAGI_PC）は停止中で未同期＝言語跨ぎ期待値の C# 側の複製は旧値のまま  → `docs/business-logic.md` §2
- じっくりの apt/fair 6% 許容 ON/OFF の対比較（2026-10-26、測定のみ・コード不変、旧重み c1=50・c3mn=90 の c1f5ae6 で測定）: 実データ 4 件×seed 1〜3×60 秒、workers 4×2 回＝24 対で weighted の差 区間 [−98, +104]・p=1.0、workers 1＝12 対も区別できない、必須の差 0。許容が働いたのは ON 24 実行中 9（sept2026・oct2026_grid のみ）。益も害も測れない＝既定は変えない、同じ手の再採用止めも入れない。ベンチに `MAGI_HO_FEATURE=tol`  → `docs/backlog.md`
- 外部レビュー（main 39a639e 基準）の照合（2026-10-26、版なし）: ①下書きの確認を消してから実行中で断る＝作業ブランチで解消済み（dd59807）②確認用の下書きを作っている間に盤面・設定が変わる／別の実行が始まる／もう一度押されると、古い盤面の確認文が後から出た→出す直前に指紋・実行中・世代（`draftConfirmSeq`）を確かめ、古ければ出さない ③作成の完了で `running=false` を先に下ろし、他の案の検査（Default）を待つ間だけ画面は操作できるのに裁定は実行中で断る窓があった→採用・維持の分岐では下ろさず、finally の `endBoardJob` の後に下ろす（代行の起動もロック解除の後になる）
- 分析タブ「1ヶ月の俯瞰（生指標）」の数の食い違い（2026-10-25、版なし、上部の札の件の続きで点検）: ①必須違反を件と人で足した「4」→ほかの画面と同じく単位ごと（「1件・3人」、`v6HardStat`）②「目安の回数との差=14.07／グループ内の回数のばらつき=299.33」は `V6PortAnalyzer` の別の式（二乗の相対偏差・分散）の小数を、チェッカーの族と同じ名前で出していた（チェッカーは apt 20・fair 31）→チェッカーの件数に（`v6MetricLine`）。巡回のボタン「必須違反Nか所を順に見る」は巡回できるセルの数で、押した先の動きと合う＝変えない
- 上部の札の必須の数が合わない（2026-10-25、版なし、実機スクショ「必須違反1か所を見る」と分析カードの「4 必須違反」）: 札の N は巡回できるセルの数（c3w 1）で、人員不足 3 人は巡回に入らない。札に人員不足の人数を並べる（`topBarTourLabel(n, covU)`、か所と人は足さない）
- 同じ結果のあとの作成は下書きから（2026-10-25、版なし、ユーザー「改良する」→入れ方は「次の作り直しだけ下書きから」を選択）: 「前回と同じ結果」が見えている間の次の作成 1 回だけ下書きを起点にし、今の勤務表より良いときだけ採る（VM の既存の keep-best＝今の盤面との比較をそのまま使う）。注記・維持／採用の文言・操作ログ 1 行を追加。S5 と背景実行は対象外。計測は 15 対（6 勝 0 敗）で、30 対への追加計測は利用上限で中断中  → `docs/screen_spec.md`
- 作り直しの起点の追加測定（2026-10-26、測定のみ）: seed 4〜9（workers 1）を足し「同じ結果」の対を 30 に。良いときだけ下書きを採ると 17 勝 0 敗 13 分（p<0.0001）、weighted +223 [+122, +336]＝採る判断（実装は未着手）  → `docs/backlog.md` 探索動学
- 作り直しの起点の対比較（2026-10-25、測定のみ・製品コード不変）: `RestartArmBench`（実データ 4 件×seed 3×60 秒×workers 1・4、探索の強さは両腕とも既定）。下書きで置き換えるのは未決、下書きから作り直して良いときだけ採るのは w1 で決定（weighted +223、8 勝 0 敗）・w4 未決、「同じ結果」の状況に絞ると 15 対 6 勝 0 敗。効くのは必須 0 の盤面。実装前に 30 対で再確認  → `docs/backlog.md` 探索動学
- 実機ログの照合 その 3（2026-10-25、版なし、3.725.0 の実行 #11〜#18）: #14 で合計 404→413 と悪化に見えた（最終番兵は必須→重み→合計で入力に負けない＝重みは下がっているはず、だが完了の行に重みが無く読めない）→「最適化 完了」「バックグラウンド最適化 完了」の行に `重み=` を足した。観察のみ: 同じ盤面で方式 ALNS・代行 OFF・玉突き（すべての違反・月全体）・じっくり・予算 180 秒と 8 回変えても必須 4・合計 404〜413 で頭打ち、180 秒でも 46 秒で停止（必須が希望衝突の床に到達→短い閾値 22 秒、最終改善は経過 0 秒・543 万回転改善なし）＝時間を足しても動かない盤面。容認採用（apt 20→19・合計 407→409）が同じ手で 4 回並ぶのは既知（許容の手がチェーンの keep-best で戻され、次の巡で同じ手を再び採る）。起点の対比較（測定中）の結果待ち
- 実機ログの照合 その 2（2026-10-25、版なし、3.725.0 の実行 #8〜#10）: 代行の操作ログが「合計 404→406」と悪化に見えた（採否は必須→重み→合計なので合計が増えても重みが下がれば当てる）→1 手ごとの行に「重み a→b」を足した（`AgentLoop.Step.weightedBefore/After`）。観察のみ: 同じ下書き（盤面指紋 ffaf44）から じっくり＝合計 386、ふつう＝406（下書きからの作り直しの効果と探索の強さが混ざっている＝起点の対比較は強さを揃えて測る）／下書きで置き換えると良かった結果（386）が消える（確認は必須の件数だけ。元に戻すで戻る）／同じ盤面の作り直し #10 は 33 秒で変化なし
- 実機ログの照合（2026-10-25、版なし、3.725.0 の実行 7 回）: 下書き（必須57）の直後に代行が走り、5 秒後に始めた作成と 25 秒 CPU を取り合って結果を捨てていた→代行は下書きのあとは走らせない（承認の再開は除く）、盤面を差し替えるジョブの開始（`beginBoardJob`）で代行も止める。あわせて「同じ盤面から作り直すと同じ結果、下書きからだと SOFT が下がった」を backlog の探索動学へ（1 例のみ・測ってから）
- 背景実行の停止で実行中が残る窓を塞ぐ（2026-10-25、版なし、backlog「画面・VM」）: Worker は所有権確認の後、try の前（前景化より前）で無条件に `setRunning(true)` していた。①前景化（suspend）で停止の CancellationException が出ると try/finally の外＝降ろされない ②停止がマーカーを消したあとに立てると、停止経路も finally も所有者でないので降ろさない。どちらも `optimizeInFlight()` が真のまま固着し編集が断られ続ける。try の中で所有しているときだけ立て、終わりに `releaseRunningAtEnd`（自分が立てて誰も所有していない＝`activeRunId()==0` なら降ろす、置き換えなら触らない）。`OptimizationWorkerReleaseTest` に 3 件
- 進捗コールバックの競合（2026-10-25、版なし・出力不変）: `V6FinalPort` はユーザーの onProgress をロックの外で呼び、ポートフォリオ・仮説のワーカーのスレッドから同時に届く。前景（`runV6FullOptimize`）は間引きの時刻・同名フェーズの窓の `HashMap`・`Hf63Infeasibility` の配列を、背景（`OptimizationWorker`）は間引きの時刻・所有権の旗・破棄数をロックなしで読み書きしていた（`logOp` は `@Synchronized`、`_ui.update` は CAS で元から安全）→コールバック本体を実行ごとの `progressLock` で直列化
- 盤面を差し替える前景ジョブの終了経路と空白状態の二重開始の照合（2026-10-25、版なし、外部レビュー中優先）: 読込・CSV 取込・下書き・最適化・研磨の 5 種は成功・失敗（Throwable）・中止のどれでも `finally` で `endBoardJob` を通り（MagiViewModel.kt 1260・1570・2135・2418、MagiViewModelCsv.kt 176）、各終了経路で running を下げる＝漏れなし。空白状態の「データを開く／見本で試す／新規につくる」はすべて `vm.load`→`loadAsync` の入口 `runBlockedByInFlight` を通り、最初の読み込みが同期で `phases` を閉じるので連打の 2 回目は断られる。直したのは 1 件: 起動時の復元（背景の実行が続いている最中の `loadAsync(fromRestore=true)`）が終わりに running を false に固定し、背景が走っているのに画面のボタンが開いていた（購読は変化しか拾わない）→読み込みの終わりの running は `bgInFlight()`（背景の実行・投入ガードだけ）で戻す
- 確認待ちの間に状態が変わる経路の外部レビュー（2026-10-25、版なし）: ①月を移す確認の「移らない」が `StructureEdit` に分類され、確認のあとに作成が始まると断られて閉じられなかった→`CancelMonthMove` は `Always`（`MagiMediatorTest.confirmDialogCanBeClosedEvenAfterARunStarted`）②下書きで置き換える確認は、確認を消してから実行中ガードで断っていた→ガードを先に（断ったら確認は残る）。空白状態の 3 ボタン・終了経路のトークン解放は既存の守りを確認
- 状態遷移の外部レビューの照合 3 件を直す（2026-10-25、版なし）: ①違反の巡回で直した直後の「次へ」が 1 件飛ぶ＝一覧の作り直しで位置の番号だけが残っていた→`tourRetarget`（族・職員・日で同じ違反を引き当て、見ていた違反が消えたら元の並びで後ろの最初の残りを「次」、見出しと「必須 i/N」はその間出さない。`TourRetargetTest`）②走っている違反チェックが手動固定・表示色の変更前の状態で表示を作り直して戻す→`togglePin`・`applyDisplayOnly` は検査が走っていれば今の状態で調べ直す ③実行中の設定（予算・並列数・方式・ソフト研磨・ネイティブ加速）は画面では押せないが VM が受けていた（予算は実行中の残り時間表示、ネイティブ加速は走行中の SA に効く）→VM でも `structuralEditBlocked` で断る。採らない: 中央裁定の Settings=Always は既存（代行の ON/OFF は終了時に読む＝実行中も変えてよい）
- T-17 時間制の対比較（2026-10-25、測定のみ）: 39a639e vs 8ea0ba1、実データ 4 件×seed 3×60 秒×workers 4×2 回＝24 対。必須の悪化 0、weighted・total は区別できない（p=0.286）、経過時間は上限内  → `docs/overhaul_detailed_spec.md` T-17
- 保存の再試行を実ファイルで（2026-10-25、版なし・挙動不変）: 自動保存・即時保存・「もう一度保存する」の書き込み本体を `SaveGate.writeTextIfLatest` へ出し、ViewModel はそれを呼ぶだけに。`SaveRetryFileTest` が一時ディレクトリで失敗→再試行で書き直し→古い世代を捨てる、を確かめる。safety_inventory の機構の穴 2 を解決へ（残る未解決は M-04 のみ）
- M-04 RSI+ の予算超過を直す（2026-10-25、版なし）: RSI_PLUS 単独は予算 1・10・30 秒でいずれも約 36 秒（フェーズ下限 10/10/10/5 の合計 35 秒）と実測。`rsiPlusPhaseSecs` に按分を出し、合計が予算を超えるときだけ予算の締切でも止める→ 2.3・11.0・31.0 秒。按分と手の選び方は不変、41 秒以上は合計＝予算で従来の経路、ポートフォリオの RSI++ ロール（35 秒）は先に来る `stopRole` の締切が判定を決めるので不変（`RsiPlusPhasePlanTest`、`BudgetOverrunTest`）
- 外部レビューの照合 3 件を直す（2026-10-25、版なし）: ①Worker の設定の取り違え＝PolishGate の書き戻しと `EngineConfig` の写しを `synchronized(PolishGate)` でひとまとめに取り `handleOptimize(config=)` へ渡す（終わりの書き戻しも同じ錠）②月の移動の確認の鮮度＝確定時に `monthMovePlan` を数え直し、件数が変わっていたら移さず確認し直す（`MONTH_MOVE_STALE`）③CSV の保存済みの判定を 32 ビットのハッシュから本文の比較へ。採らない: `StateFingerprint.ofBoard` の行の境目（盤面は長方形で行長が同じ＝衝突しない）、C42FlowPolish（既定 OFF・文書化済み）、freshStaffId・running フラグ・ボタンの連打（既存の所有権・入口の検査で守られている）
- 監査の残件 3 件を並列で（2026-10-25、版なし・出力不変）: ①機構の穴 1＝`NativeGate.saSeamForTest`（本番は null）で偽のネイティブを差し込み、照合 NG／自己整合 NG でゲートが閉じて Kotlin へ退化し結果が入力に負けない・正直なら開いたまま、を実際の SA の経路で（`NativeGateIntegrationTest`）②M-01＝原子保存をあきらめた書き込みを `NonAtomicWrites` に必ず残し操作ログへ ③U-05＝CLAUDE.md の重みの経緯を business-logic.md §2 へ。残る未解決は M-04（計測待ち）と保存の再試行の実ファイル
- SOFT の桁あふれを防ぐ・監査資料の再照合（2026-10-25、版なし、外部レビュー P1 の照合）: 個人の下限 1 億で差分評価が必須を 43 と誤って返すことを再現（フル評価は止まる）。実行前の検証 `Ws1Ops.countSettingError`（回数の設定 ≤ 1 万＝30 名×12 シフトが全部下限割れでも SOFT 約 4.3 億）と、`DeltaEvaluator.scoreFrom` の桁の検査（`SoftOverflowGuardTest`）。`safety_inventory.md` に再照合の節（M-03・M-06・M-07・U-01・U-03・U-04 を解決・対応へ、S-01 を追加）
- 全面改修 P4f（2026-10-25、版なし・出力不変）: 後処理の段を `PostStep`＋`PostChain.runSteps` に（前段・中段・終段。巡回研磨クラスタの中は残り）。T-10・BoardHash・T-9・T-16 一致  → `docs/overhaul_design.md` §6.5
- 全面改修 P10e と P10 の仕様（2026-10-24、版なし・出力不変、外部の仕様レビューを反映）: 共有の小関数を `SearchCommon.kt` へ出し、探索器から入口への import を 0 に（一方向の依存）。§6.8b に挙動不変の範囲（絶対不変・実質不変・差を許す）、残す基準、各ファイルの役割（肯定形）、据え置きの理由、最終構造、設計の完了条件を追記  → `docs/overhaul_design.md` §6.8b
- 全面改修 P7（2026-10-24、版なし・文書のみ）: architecture.md・sudo_model.md を P3〜P6e・P10 の構造に合わせて正規化（21 族、探索器の分割、EngineConfig/T-7、ConstraintCatalog、PenaltyWeights、Staff.id、weekly の現行式、休の識別、タブ名）  → `docs/overhaul_design.md` §6.8
- T-15・T-16 と、やり直し・許可リストの対策（2026-10-24、版なし、ユーザー「マルチスレッドでのやり直し対策と許可リスト不足の対策する」）: 並列のエージェントで `StateRoundTripTest`（JSON 往復・CSV 書き出しの冪等）と `BoundaryFixtureTest`（B-2 の境界）を作成（T-9 は先行コミット）。worktree が main から作られ古い版で書かれた・続けて push して CI が中止になった反省から、fork に HEAD を checkout させる・push は 1 回・`tools/prepush_check.sh` を `docs/environment.md` と CLAUDE.md に。weight_lint は断片が別ファイルへ移っても失敗にせず移った先を注意として出す（`test_weight_lint.py` に自己テスト）。`StateParser.DERIVED_KEYS` を internal にしてテストが写しを持たない
- 修復の候補選び・研磨の見積りの重みを PenaltyWeights に（2026-10-24、版なし・出力不変、外部レビュー「評価式を 5 箇所にコピペ」の照合→「推奨で」）: destroy-repair/polish 系 4 ファイル・17 か所の重みの数値（low/high/apt/fair/weekly/covU/covO）を `MirrorKeys.weights` から初期化時に 1 回読む定数に。式の形（下限 0 を数えない等の前提の違い）は変えない。weight_lint の許可 11 行を外した。あわせて P10b 以来 V6 Engine Check を落としていた weight_lint の許可（`iter % 120L`・`iter % 50L`）のファイル名を AlnsSearch.kt に直した（lessons 追記）
- T-7 を CI に（2026-10-24、版なし、外部レビュー「DoD T-1〜T-19 を CI に繋ぐ」の照合）: `PolishGateAccessTest`＝エンジンで `PolishGate.` を参照するのは `EngineConfig.kt` だけ。照合: T-1〜T-6・T-8・T-10・T-11・T-13・T-14 は既に実在して V6 Engine Check で全件回る、`safety_inventory.md` は 28 項目すべてに固定テストと CI 列あり（「CI に紐付いていない」は誤り）。残る T-9（各段が締切を守る）・T-15（JSON 往復・CSV バイト一致）・T-16（境界 fixture）・T-17（統計）・T-18（実機）・T-19（C#、停止中）
- 全面改修 P6e（2026-10-24、版なし・出力不変）: ViewModel の CSV 取込を `MagiViewModelCsv.kt` へ。盤面の書き込みは本体の `commitCsvBoard` だけ  → `docs/overhaul_design.md` §6.7
- 全面改修 P10d（2026-10-24、文書のみ）: 採否の合成を `docs/adoption.md` に（盤面を採る 10 経路 × 比較・固定・回数固定・拡張希望・必須）。漏れなし。P10 完了  → `docs/adoption.md`
- 全面改修 P10c（2026-10-24、版なし・出力不変）: 探索直後の研磨を `SearchPolish.kt` へ（後処理チェーンとは別物と明記）  → `docs/overhaul_design.md` §6.8b
- 全面改修 P10b（2026-10-24、版なし・出力不変）: 探索を PortfolioSearch・AlnsSearch・RsiSearch の 3 ファイルへ。V6NativeOptimizer は 2,160→585 行  → `docs/overhaul_design.md` §6.8b
- 公平化の偏差・禁止の並びの数え上げの無駄を除く（2026-10-24、版なし・出力不変、同上の続き）: `fairDevOfBucket` は回数を 1 人 1 回だけ数え、合計だけの呼び出し（差分評価・フル評価・修復の候補選び）は内訳のリストを作らない（`members = false`）。`C1DeltaPrefilter.staffC3nFires` は最初の不一致で打ち切る（ビット走査の答え合わせの正本なのでビットには替えない）。公平化・差分評価・ビット走査のテスト 59 件と BoardHash・T-10 一致
- 正式チェッカーの並びの照合をビット演算に・禁止の並びの判定を早期打ち切りに（2026-10-24、版なし・出力不変、ユーザー「ビット演算などで高速化する」）: JFR（後処理の決定的モード）で `UnifiedViolationChecker.check` が 36%、うち `checkC3Family` が自己 10%、`Problem.makesForbiddenRun` が自己 6%。並びの窓（複数シフト・禁止）を職員×シフトの勤務日ビットの AND と popcount に（マスクは check ごとに 1 回・4 族で共有、開始日の昇順に印＝数・場所・順が同じ。単一シフト連と T>64 は従来の走査）。`makesForbiddenRun` は seq[j−s]≠newK の窓を飛ばし、最初の不一致で打ち切る（判定は不変）。チェッカー単体 実データ 5 件で 11〜32% 短縮（6 名×14 日は ±0）、後処理テスト全体の CPU サンプル 2,284→1,706（checkC3Family 234→14・makesForbiddenRun 136→76）。`C3FamilyBitTest`（6 fixture×ランダム盤面 20）でビットとスカラーの一致、BoardHash・T-10・チェッカーのスナップショット一致
- 全面改修 P10a（2026-10-24、版なし・出力不変、ユーザー「推奨で」＝外部の構造レビューから P10 探索器の分割を追加）: 実行状態を `OptimizerRuntime.kt` へ  → `docs/overhaul_design.md` §6.8b
- 勤務表 CSV の取込の確認（2026-10-24、版なし、Creator Studio 風の設計案の照合→「推奨で」）: 反映の前に「人数と変わるセル／必須違反の前後／注意」を見せ、「取り込む」で同じ結果を当てる（指紋が違えば断る）。設計案のほかの画面（ホームの作業 4 枚・勤務変更／保存前差分／中止の確認・結果の保存）は重複ボタン・自動保存・D7・片手の段数とぶつかるので採らない  → `docs/screen_inventory_textart.md` 5.13
- 日の配置のシート（2026-10-24、版なし、ユーザー提示のスマホ設計案の照合→「推奨で」＝画面04・06）: 日ヘッダをどの日でも開けるようにし、シフトごとの人数/必要人数・状態と入っている職員を縦に並べる（職員タップ＝セルのシート、‹ › で前後の日）。設計案のうち MAGI に無いデータ（曜日の可否・職員の有効状態・メモ・結果の履歴など）を前提にする画面と、D7 などの決定とぶつかる画面は採らない  → `docs/screen_inventory_textart.md` 5.12
- 全面改修 P3 の残り（2026-10-24、版なし・出力不変）: `wideC3nBreakDays` を `EngineConfig` の写しに入れ、探索の奥まで引数で渡す（実行中に「探索の強さ」を切り替えてもその実行には効かない）。外部の静的レビューの照合: 差分評価の HARD/SOFT 分離（soft の桁あふれ）は `Evaluator.fullEval` の `check` が 3.336.0 から契約として検査済み＝不具合ではない  → `docs/overhaul_design.md` §6.3
- 外部の敵対検証（3.725.0）の照合（2026-10-24、版なし）: 重みの 4 箇所一致は確認済みと同じ。指摘 4 件のうち直したのは玉突きパイプラインの終了理由だけ（深い探索が走らなかった焦点を `deep_done` でなく `probe_only` と書く）。BUG-1「2 巡目で予算枯渇＝実質 1 巡」は過大（深い探索の上限は焦点と巡の合計で、2 巡目以降も浅・中の予察と採用は走る。配分を巡ごとに変えるのは探索動学の変更＝測ってから）。BUG-2「深さ 3〜4 の手が出ない」は誤り（`branching=(2,1)` の 3 手目以降は分岐 1 で出る）。BUG-3 は既定値の出所が `EjectionFlags` 1 か所で本番は設定の写しを渡す＝不具合ではない。BUG-4・shallowDepth・holeSoftOnly などの数値は HF77（明示の数値指示＋測定）待ち
- 全面改修 P6d（2026-10-24、版なし・出力不変）: ViewModel の変更の確認の画面（3 経路）を `MagiViewModelFixSuggestions.kt` へ  → `docs/overhaul_design.md` §6.7
- 全面改修 P6c（2026-10-24、版なし・出力不変）: ViewModel の「この希望を取り消したら」試算を `MagiViewModelWishTrial.kt` へ  → `docs/overhaul_design.md` §6.7
- 全面改修 P5c（2026-10-24、版なし・出力不変）: 回数・偏りの族の組を `ConstraintCatalog` から引く（E7 フィルタ・RSI の職員単位の破壊）  → `docs/overhaul_design.md` §6.6
- 全面改修 P6b（2026-10-24、版なし・出力不変）: ViewModel の「設定を緩めたら」試算と承認フローを `MagiViewModelRelaxTrial.kt` へ  → `docs/overhaul_design.md` §6.7
- 全面改修 P4d・P4e（2026-10-24、版なし・出力不変）: 後処理の終段（最終の修復・玉突きパイプライン・人員過剰の退避）を `runFinalStages` に。T-10 一致  → `docs/overhaul_design.md` §6.5
- 全面改修 P4c（2026-10-24、版なし・出力不変）: 後処理の中段を `runMidStages` に切り出した。T-10 一致  → `docs/overhaul_design.md` §6.5
- 全面改修 P4b（2026-10-24、版なし・出力不変）: 後処理の前段（HF80・HF67・HF66）を `runPreStages` に切り出した。T-10 一致  → `docs/overhaul_design.md` §6.5
- 全面改修 P5b（2026-10-24、版なし・出力不変）: 必須の族の組（6 族・セルの 5 族）を `ConstraintCatalog` から引く（C1 玉突き・固定の注意・確認一覧）  → `docs/overhaul_design.md` §6.6
- 全面改修 P6a（2026-10-24、版なし・出力不変）: ViewModel の直し方（変更案の探索・適用）を `MagiViewModelFixSuggestions.kt` へ extension 関数として分けた  → `docs/overhaul_design.md` §6.7
- 全面改修 P4a・T-10・P5a（2026-10-24、版なし・出力不変）: 後処理の段の名前を `PostStages` に集約、T-10（段の並び・採用数を基準版 39a639e と照合）、`ConstraintCatalog`（21 族の位置の空間）と T-11、場所一覧の族の場合分けを表から引く  → `docs/overhaul_design.md` §6.5〜6.6
- 全面改修 P3b（2026-10-24、版なし・出力不変）: 後処理の各段の取り分の式（半分・4 分の 1・按分・上限まで）を `BudgetShare` に集め、HF67・HF66・共同 LNS・玉突きの 7 か所から呼ぶ。旧式との一致を `TimeBudgetTest`  → `docs/overhaul_design.md` §6.4
- 全面改修 P3a（2026-10-24、版なし・出力不変）: `TimeBudget`（開始時に 1 回だけ作る予算の型）を新設し、`V6FinalPort` の開始時刻・制限時間・取り分・全体の締切・停滞の見張り・超過の判定をそこから読む。旧式との一致を `TimeBudgetTest`（T-8、予算 1・30・31・60・210・211・300 秒）  → `docs/overhaul_design.md` §6.4
- 残件 #49 の並列対応（4 担当）と実機画面の見直し（2026-10-26、3.726.0、ユーザー「マルチスレッドで残件対応する。推奨で」「画面を熟読して見直しする」）: 【背景実行】①実行中を Worker ごとの印の集合に（C-01、`OptimizationRepository.holdRunning/releaseRunning/clearRunning`＝`RunningHolds`）。停止が所有権確認と実行中の立ち上げの間に入ると running が残り編集・実行が拒否され続けた。印 1 つだと遅れて立てた旧い実行が新しい実行の running を消すので集合。`OptimizationWorkerReleaseTest` 4 件。②作成の進捗コールバック（並列ワーカーから同時）を 1 つの錠で守る（U1-2）。③改善案探索の「時間切れ」を呼び出しごとの戻り値に（`FixSuggester.suggestResult`、静的 `lastTimedOut` を廃止、V61-04）。【C++】④hf67 の hf66 段を Kotlin 3.428.0/3.507.0 に追従（埋めシフト＝`fillShiftIndex`、個人上限0のセルを外す、希望固定は希望へ。C-03）。本番は Kotlin の入口で清掃済み＝実データ 6 件×5 seed×ALNS 3 受理で出力不変（126 行一致）、ネイティブ単独でも Kotlin 規則と一致。host `runHf66FillTest`・`ZeroCapExclusionTest.entryHardeningMatchesNativeFixture`。⑤ALNS の copy 系が拡張希望の禁止で捨てた候補の moveAug で受理・学習していた→Kotlin の extBad と同じく止める（C-05、実データでは到達せず出力不変、host `runAlnsBanDiscardTest`）。C-06（c41DayMarg の重みなし）は Kotlin と同式＝不具合でない（重みは HF77）。【探索】⑥厳密窓修復が完全割当を 1 つも作れない成分でも exhaustive=true（偽の「証明済み壁」）→葉が 1 つ以上のときだけ（v63-F1、盤面不変、`C1RepairAnalysisTest.unrealizedWishOutsideTheMultisetMustNotClaimAWall`）。否決: c3 回転の前フィルタに c3w/extWish を数える案（V61-03）＝実データ 6 件で最終盤面・hard・weighted が同一＝便益が測れない。SoftCascade の回数固定は 3.724.0 でファイルごと削除済み。【画面】⑦巡回の位置を項目の鍵（族・職員・日）で追う（`tourCursor`、U2-02）＝直した直後の［次］が 1 件飛ぶ・前が消えると前後とも押せない不具合。前後は項目の位置で動く＝同じセルが 2 件の違反に属すると［次 ▶］が灰色だった不具合（実機「必須違反 1/26」）も解消。`TourCursorTest` 7 件。⑧走行中の検査結果が後の手動固定・表示色を巻き戻した→差がそれだけなら今の固定・色を残す（`keepDisplayOf`、U1-1、`CheckKeepsDisplayTest` 3 件）。⑨分析の俯瞰の「必須違反」が件数と人員不足の人数を足していた（上部「1か所」に対し「4」）→「必須違反 N件」「人員不足 M人」に分ける。⑩「グループのレンジが未設定です」を赤の警告から注記（ログのみ）へ。⑪編集の帰り札「なおし方」→「直し方」（他の画面と統一）。画面レビューの残り（実機の確認待ち）: シート上段の本文が行の途中で切れる・案なしの閉じるが 2 つ・作成中の診断カードが開始時点の数を出す、は backlog #49。C# 同期要。  → `docs/history/3.4xx.md`
- weekly 長方形交換の候補基準を厳密目標 回数/7 に（2026-10-23、測定のみ・否決・コード不採用、backlog #49）: `CyclicSwapWeeklyPolish.applyWeeklyRebalancePolish` の過剰/過少曜日の選びが旧 `round(回数/7)`（回数≤3 は過少の曜日が無い）なのを、評価と同じ 7×曜日回数 と 回数 の比較に変える案。実データ 6 件（golden・sample_v6・blocked_covu・sept2026・oct2026_grid・full_coverage）を保存盤面から決定的後処理（seed 5）で旧新 2 腕: **最終盤面 6/6 一致**（ハッシュ同一）。パス単体は 5 件同一、full_coverage だけ新が悪い（weighted 287,626→298,396、旧は大きい 1 手・新は小さい 4 手を先に採る first-improvement の経路差）。便益が測れない＋単体で負＝入れない（CLAUDE.md の採否基準）。反例テスト（2 回とも同じ曜日のシフト）は通るが、実データでは届かない。実機ログ（3.723.2）の「WeeklyRebalance 採用 0」は他のパスが先に weekly を下げた結果と読める。  → `docs/history/3.4xx.md`
- **3.725.0（2026-10-24）**: iOS から設定・職員一覧の一覧の形、カレンダーの形を取り入れ、全体の視覚弱点者対応（開閉の印を読み上げない・状態を読む・拡大で切れない・読み上げ中は下部タブを縮めない）。この下の「版なし」の行がこの版に入る  → `docs/history/3.4xx.md`
- 視覚弱点者対応を全体で（2026-10-24、3.725.0・出力不変）: `DisclosureText`（見た目は「{名前} ▸／▾」、読み上げは名前と「開いています／閉じています」）を開閉の 20 か所に、一覧の行の › と節の見出しの印は読み上げない、カレンダーの丸は最小 32dp（拡大で数字がはみ出さない）、TalkBack 中は下部タブを縮めない  → `docs/history/3.4xx.md`
- カレンダーを iOS のカレンダーの形に（2026-10-24、版なし・出力不変）: 希望・必要人数の月の表から枠と ✓ を外し、選んだ日は塗った丸、週の間に細い線  → `docs/screen_inventory_textart.md` 5.11
- 職員一覧を iOS の一覧の形に（2026-10-24、版なし・出力不変）: 職員の行を 1 つの入れ物・行の間に細い線・見出しと説明は外  → `docs/screen_inventory_textart.md` 5.10
- 設定を iOS の一覧の形に（2026-10-23、版なし・出力不変）: `MagiGroup`/`MagiGroupRow`、データの外枠ボタン 10 個を一覧の行に、設定・外観・色・詳細の見出しを入れ物の外へ、節の見出しを行の形に  → `docs/screen_inventory_textart.md` 5.10
- **3.724.0（2026-10-23）**: 全面改修 P0〜P2（安全網のスナップショット・EngineConfig・否決済みの腕と量的評価の撤去）＋画面の整理（重複ボタン・見切れ・説明文・ボタンの 3 段・カードの統合・浮く下部タブ）＋AX 代行役（作成の直後に安全な勤務の変更を自動で当てる・自動で整える・判断待ち）。この下の「版なし」の各行がこの版に入る  → `docs/history/3.4xx.md`
- 浮く下部タブ（2026-10-23、3.724.0・出力不変）: iOS 26 のタブバーから「画面の端から浮く角丸の帯」「下へ送ると今のタブだけに縮む」を取り入れる（ガラスの半透明は UD 固定と両立しないので不透明・境界線）。あわせて区切り線を `HorizontalDivider` に統一、設定の種類別 CSV 4 ボタンを畳む  → `docs/history/3.4xx.md`
- オブジェクトの数・種類・同じ名前の棚卸し（2026-10-23、版なし・出力不変）: 同じ名前で行き先が違う「開く」「保存する」を言い分け  → `docs/screen_inventory_textart.md` 5.9
- カードの数を減らす（2026-10-23、版なし・出力不変）: 代行→主カード、対象の月→月次の見出し、例外一覧→必要人数、体制→年間マスターの見出し、表示色→外観（常時 5 枚減）  → `docs/screen_inventory_textart.md` 5.8
- エッジデータで検証（2026-10-23、版なし）: 代行役のエッジテスト 7 件・研磨の例外でも当てた手を残す・セルのシートの対象/原因の行を 2 行に・hosttest.sh に AgentText を追加  → `docs/screen_inventory_textart.md` 5.7
- 全タブのカードを骨組みのテキストアートで見直し（2026-10-23、版なし・出力不変）: 設定ミスの行ボタンを外枠・希望の衝突の行き先の重複・「元に戻せます」の繰り返し・長い注記 5 件  → `docs/screen_inventory_textart.md` 5.4〜5.6
- テキストアートで机上検証（2026-10-23、版なし・出力不変）: ホーム・セルのシート・設定を再現し 4 件を直す（代行のカードの言い直し・空の輪のカード・実行中の文・前後の日ボタンの幅）  → `docs/screen_inventory_textart.md` 第5章
- 冗長の削減（2026-10-23、版なし・出力不変）: 説明文 48 件を短く、ホームの診断カード 5 枚を「原因の診断（N件） ▸」1 つに畳む  → `docs/history/3.4xx.md`
- AX 代行役 第3段（2026-10-23、版なし）: 代行のカードに判断待ちの数（希望の見直し・設定を緩める候補・設定ミスの直し）、承認したら作成を待たずに代行が続きを回す  → `docs/history/3.4xx.md`
- AX 代行役 第2段（2026-10-23、版なし）: 1 手の案が尽きて必須が残れば「自動で整える」を 1 回だけ試し、差分を同じゲートに通して当てる（backlog #13(g)）  → `docs/history/3.4xx.md`
- AX 代行役 第1段（2026-10-23、版なし）: 作成の直後に必須違反が残れば安全ゲート合格の勤務の変更を自動で当てる `AgentLoop`＋ホームのカード・設定スイッチ・Undo 1 回（backlog #13(f)）  → `docs/history/3.4xx.md`
- 行き先の語「開く/見る」と「直し方」の表記（2026-10-23、版なし・出力不変）  → `docs/history/3.4xx.md`
- ボタンの整理整頓（2026-10-23、版なし・出力不変）: 248 個を棚卸し、戻る/閉じるを補助へ・開閉の言い方を「▸/▾」に統一・探索の中止を「探すのをやめる」  → `docs/history/3.4xx.md`
- 文字ボタンに薄い背景（2026-10-23、版なし・出力不変）: TextButton 86 個を `MagiTextButton`（onSurface 8% の背景）へ。塗り＞外枠＞薄い背景の 3 段  → `docs/history/3.4xx.md`
- 1 画面に同じボタンを 2 つ置かない（2026-10-23、版なし・出力不変）: セルのシートの ✕ と［閉じる］、ホームの［やめる］2 つ、［勤務表をつくる］カードと下部バー、［個人の回数を見直す］2 枚のカード  → `docs/history/3.4xx.md`
- 画面の冗長・見切れ・文言（2026-10-23、版なし・出力不変）: セルのシートの前後の日ボタン重複を削除、［自分で勤務を選ぶ］を固定部へ、上部の札の「評価：」を外し 2 行折り返し、案なしの文を短く  → `docs/history/3.4xx.md`
- 全面改修 P2d-④（2026-10-22、版なし・本番の出力不変）: quantitativeRangeEval（量的評価モード、3.512.0 否決）を Kotlin 45 ファイル・C++ から撤去。JNI meta[7] は 0 固定（ABI 不変）。QuantitativeRangeEvalTest 削除  → `docs/overhaul_design.md` §6.3
- 全面改修 P2d-③（2026-10-22、版なし・本番の出力不変）: 否決・測定用の腕を削除（SoftCascadePolish・PrePostDescent・C2Polish・C42FlowPolish・C3nMarginLnsPolish・RestZeroWindowLns・StallPolishInjection の 7 ファイル、c1MoveARepair、adaptiveStall、postChainRollbackCountsZero、rsiFocusRotationPersist、c1ComponentRepair、fairAchievementDirection、dayAssignIdentityFallback、各 Reactivate、PortfolioBudgetBench）。戻すときはこの直前のコミットから  → `docs/overhaul_design.md` §6.3
- 全面改修 P2d-②d（2026-10-22、版なし・本番の出力不変）: 画面のスイッチ 4 つ（入れ替えも1手として使う・採れたら探し直す＝常時、余った時間も玉突きに使う・禁止の並びだけ残ったら早めに切り上げる＝削除）と c3nWallLegacy・C3nWallProof.legacy、非表示の blockSwapC3nFilter（filterC3nIncrease 常時）を削除  → `docs/overhaul_design.md` §6.3
- 全面改修 P2d-②c（2026-10-22、版なし・出力不変）: wishPinStrict（規則 A）を常時にして PolishGate のフラグ・wishMoveAllowed/keepsWishPins の strict 引数・7 ファイルの wishPinStrict 引数を削除。旧挙動の比較テストを削除  → `docs/overhaul_design.md` §6.3
- 全面改修 P2d-②b（2026-10-22、版なし・本番の出力不変）: 後処理の定着引数 5 本（componentRepairEnabled/Final・covOReliefEnabled・postChainRunningKeepBest・lnsAdaptive）を外す。lnsAdaptive は UI の残骸・「設定の効き」の語も削除。素の PostOptimizationParams() も本番と同じ自動調整に（P0 盤面ハッシュは基準版で同一を確認）  → `docs/overhaul_design.md` §6.3
- 全面改修 P2d-②a（2026-10-22、版なし・出力不変）: 定着フラグ 10 本を常に ON にしてフラグと OFF 経路を削除（rotationDeltaPrescreen・roundPassMemo・hardDeltaPrefilter・lateOpStopPropagation・ejectionPipelineAfterRepair・personSwapKick・floorCountsUpperZero・deltaChildEvalDefault・defaultCrossFamily（v1 候補生成）・restoreWishLocked）。OFF 比較テストは ON 経路の検証へ、PersonSwapBench・WasteRemovalProbe を削除  → `docs/overhaul_design.md` §6.3
- 全面改修 P2d-①（2026-10-22、版なし・出力不変）: どこからも ON にならない玉突き 5 本（hardEjectionChainEarly・allEjectionChainEarly・allEjectionChainFinal・hardEjectionChainRetry・allEjectionChainAfterRepair）と C1EjectionChainPolish.defaultMaxDepth/defaultNarrow、EjectionChainScope.NEARBY を削除。戻すときはこの直前のコミットから取り出す  → `docs/overhaul_design.md` §6.3
- 全面改修 P2c（2026-10-22、版なし・出力不変）: 玉突きの 11 項目を `EjectionFlags` へ（`EngineConfig.ejection`→`PostOptimizationParams.ejection`→パイプライン・従来の玉突き・手直し提案）。Config の既定は定数、`EjectionChainPipeline` を public に。エンジン内の PolishGate 書き込み 0  → `docs/overhaul_design.md` §6.3
- 全面改修 P2b（2026-10-22、版なし・出力不変）: 停滞・床・壁の 5 項目と後処理の 4 項目を `EngineConfig` へ、`EngineConfig.postParams()`、`V6OptimizerOptions.config`  → `docs/overhaul_design.md` §6.3
- 全面改修 P2a（2026-10-22、版なし・出力不変）: `EngineConfig` 新設（normalStallFraction・floorCountsUpperZero）、`handleOptimize` の入口で PolishGate を 1 回だけ写す。`EngineConfigTest`（T-6）  → `docs/overhaul_design.md` §6.3
- 全面改修 P0 安全網・P1 テストの穴埋め（2026-10-22、版なし・出力不変）: CheckerSnapshot/DiagnosisSnapshot/BoardHashFixture の期待値を main 4e8fe3a から生成（盤面ハッシュは seed 1 本・fixture 読込の javaClass 不具合を修正）。`NativeGate.chunkConsistent`/`parityMismatch` へ 2 層番兵の判定を切り出し 8 か所を置換、`SaveFailNotifier.report`（SaveOutcome）へ保存結果の反映を切り出し。`NativeGateFallbackTest`・`SaveRetryWiringTest`  → `docs/overhaul_design.md` §6.1・§6.2
- 並列トレースの残りから 3 件（2026-10-23、3.723.4、ユーザー「残件対応する」、出力不変）: ①C++ `jround` が `floor(x+0.5)` で、0.49999999999999994 を 1 にしていた（Java `Math.round` は 0）→端数で比べる（境界 12 値で Java と一致を確認、旧式は 2 値で不一致）。fair の偏差の丸めがずれると番兵が閉じて Kotlin へ退化していた。TOC も再生成（行番号がずれていた）。②JNI 入口で dow0（0..6）と bucket のシフト添字（0..K−1）を検査し、外れたらハンドルを作らない（dow0 は wd 配列への書き込みの添字）。Kotlin は常に範囲内を渡す＝正規の入力は不変。③「案が無い理由」（`noFixReasons`）と禁止の並びの診断が手動固定を見ていなかった→「手で固定したマスです」と書き、固定条件の区分に入れる（旧: 手動固定だけが原因でも「調べた範囲で無い」）。c3n 診断は手動固定のセルを「本人希望」と呼び「本人と相談して希望を調整」と案内していた→「手で固定」「勤務表で固定を外す」（3.723.2 の `pinSourceWord` と同型の取り残し）。テスト: `GridDisplayMarksTest.noFixReasonsNameManualPinsApartFromWishes`・`V6PortAnalyzerTest.manuallyPinnedForbiddenRunIsNotCalledAWish`。native parity（ローカル）緑。見送り: 背景実行の running 残り（C-01）は実行ごとの印が要る設計変更＝backlog #49 に残す。実機ログ（3.723.2、Pixel 10 Pro XL）で 3.723.3 の予算ログの不整合と、Aｱ（担当 4 名）の「案なし」が走査の打ち切りと合う形を確認。曜日平準化の長方形交換が採用 0（weekly 67 件が配置で減らせる）＝#49 の旧目標式を測る根拠。C# 同期要。  → `docs/history/3.4xx.md`
- 全ファイル並列トレース（8 グループ・164 ファイル、指摘 54 件）の検証と是正 9 件（2026-10-23、3.723.3、ユーザー「推奨で」「進める」）: ①`CovOReliefPolish`（既定 ON の最終段「人員過剰の退避」）だけが研磨パス共通の `adoptionGate` を通らず、回数固定（staffRange lo==hi）を崩す 1 セル手を重みで採っていた→`adoptionGate` へ（残存理由に「回数固定」）。②`AptPolish` の相互交換だけ `mayPlace` を見ていなかった（FairPolish の同名関数にはある、3.621.2 と同型）。今の重みでは上限超過 25 が交換の得を上回り、届く反例は作れなかった＝防御の揃え（テストなし）。③職員一覧 CSV の取込が同姓同名で飛ばした氏名を結果へ渡さず、画面の警告が出なかった（全行が同姓同名なら「0件」と誤案内）。④`entryMoves` が置けるシフトの無いセル（担当外・上限0）で走査ごと止まり、後ろの 1 セル案と「適用できません」の行が欠けた。⑤回数マトリクスが最新の設定のシフト数で再検査前の回数表を読み、シフト追加の直後に範囲外で落ちた。⑥背景実行の前景化の失敗が `CancellationException` を飲んでいた。⑦設定の直し方「グループのレンジの下限を上限へ」が対象行の消えた後も変更なしの state を返し、空の Undo 段と「修正を適用」が残った。⑧予算配分のログが「総 N s = 探索 N s + 後処理 20 s」と合わず、現フェーズの猶予を「かつ」と書いていた（3.408.0 から遅延だけ）。⑨SOFT 桁溢れの警告が旧 HARD 単位 1,000,000 のまま→`SCORE_HARD_UNIT`。テスト: ①③④⑦に 1 件ずつ（修正を戻すとこの 4 件が落ち、残りは通る＝教訓 #30）。⑤⑥⑧⑨は Compose・Worker・ログ・閾値で host テストの外。ホストテスト 1,550 件 緑。出力が変わるのは①②の、回数固定・上限0を崩す手を採っていた盤面だけ（守るべき条件の取りこぼしの是正＝探索の手の追加ではない）。Kotlin のみ（C++ に該当パスなし）。C# 同期要。後回し（計測・C++・画面の設計が要る）は `docs/backlog.md` #49。トレースの出力はセッションの scratchpad のみ（repo に入れない）。  → `docs/history/3.4xx.md`
- 全面改修の詳細設計仕様書を新設（2026-10-22、ユーザーのひな形 5 観点、版なし）: docs/overhaul_detailed_spec.md。課題 W1〜W7 と新設計の対比・選定根拠、前処理 P-1〜P-8・PostPipeline の状態遷移・計算量、入出力の写像と EngineConfig/TimeBudget/ConstraintCatalog の型、境界 B-1〜B-11・例外 E-1〜E-9 の机上検証（段の例外を捨てて続ける案は出力が変わるので改修の外へ）、性能目標・テスト観点 T-1〜T-19・DoD。机上検証で計画の帯を訂正（AUTO の 31〜210 秒は RSI→ALNS）。
- 全面改修の設計書を v5 に（2026-10-22、ユーザーが示した 4 観点「背景と課題・目指す状態と完了基準・変えない前提・網羅性チェック」、版なし）: §2.7 改修の背景と課題（ボトルネックは安全に変えられる速さ）、§2.8 変えない前提（入出力・業務ルール・採用基準・探索の動き・規模・実行環境・3 実装・画面・番兵）、§2.9 網羅性チェック（例外・境界・非機能・テスト要件）、完了の定義に両者を追加。
- 全面改修の設計書を完全版・最終 v4 に（2026-10-22、ユーザー「意義が仕様に反映できている確率を上げて仕様を完全版に」、版なし）: S1 の対象を固定する既存機能の棚卸し F1〜F15（コード・自動検査・実機確認）、意義の反映の自己検査（期待値の作り直し・有利な比較・予算の短縮・内側の重い処理・基準を緩める等の抜けを想定して対処を明記）、最終検証に実機ログと機能棚卸しの実機確認を追加、コミットごとの確認表。
- 全面改修の設計書を v3 に（2026-10-22、ユーザー「机上検証して他社AIが理解しやすい詳しい文章に」「意義が仕様に反映できている確率を上げる」、版なし）: 用語・現状の層の図・型の形を追加、v2 の主張をコードと照合して 2 件訂正（共同 LNS の 60 秒は決定的モードだけ、EngineClock は remainingMs を持つ）、意義 S1〜S6 から段・検査・最終の測り方・落ちたときの扱いへの追跡表と逆引き、測り方の手順（基準版の SHA・データ・seed・集計）、机上検証の記録（§15）。
- 段階的全面改修の設計書を v2 に（2026-10-22、ユーザー「完全版にアップグレードする」「一度にすべて置き換える」「全面改修の意義」、版なし）: §0 定義・§0.1 意義と受け入れ基準 S1〜S6（基準版より悪くならないことを測って示す）・G14 一括で入れる・G15 質と時間・G16 性能、進捗、INV との対応、各段のコミット分割、CI の対応表、C# の積み残し、複数セッションの運用、本書の変更手順。PolishGate を 39 に訂正。
- backlog 小物 4 件（3.723.2、出力不変）: #46 T02 `CsvSavedMark` 切り出し＋host テスト／#42(h)⑤ `ViolationReport` の 1 件クラス列を `listOf` で読み取り専用に／#32 Kotlin 側にも意図的の注記／#26 業務重みと腕選択・予算配分を分ける指針を `docs/stall_escape.md` §6 へ  → `docs/history/3.4xx.md`
- つくる前の確認の呼び名を固定の出どころで分ける（2026-10-22、3.723.2、画面のみ・出力不変）: 日の証明・本人の証明の行は核の `WishPin` を見て、希望だけ→「本人の希望」、手動固定だけ→「手で固定したマス」、両方→「希望と手で固定したマス」（純関数 `pinSourceWord`）。3.717.0 から証明が手動固定も数えるのに一律「本人の希望」と言っていた。テスト `PreRunCheckTextTest.pinSourceWord_distinguishesWishAndManualPin`。C# 同期要。
- 安全性の棚卸しの穴埋め 5 件（2026-10-22、3.723.2、テストと記録のみ＝出力不変）: B-03 停止経路の実行中解除を `cancelPathReleases`/`shouldReleaseRunning`（`work/BgHandoff.kt`）へ切り出し `OptimizationWorkerReleaseTest`。M-02 非同期 enqueue 失敗の片付け判定を `asyncEnqueueFailureCleansUp` へ切り出し `BgEnqueueFailureTest`。M-01 前のデータの退避（`prevBackupFile`）で原子置換を諦めたら操作ログへ W（`onNonAtomic` の既定 `{}` はテストの呼び出しが多いため据え置き）。U-03 `weight_lint.py` が `10_000L`・`1e4`・`9.0E3` を拾う＋自己テスト `tools/test_weight_lint.py`（v6-engine-check で実行）、`fmtIter` の万の桁を抑止に追加。L-03 `MirrorEngineTest` の weekly 下限の KDoc を 3.526.0 の式へ。  → `docs/history/3.4xx.md`
- S6 承認フローのレビュー指摘対応（2026-10-22、3.723.2）: 復旧（事後確認の不一致・保存失敗）が画面の欄を変えたまま残していた＝`hasResult`・`engineRan`・`ranBeforeEdit`・`structureEdited`・`runSummary`・`stopSummary`・`relaxedBoard`（false 固定）・`resultSchedule`（null のまま）・`pushUndo` の副作用（redo 消去・30 段超の最古段の脱落・改善提案／他の案／`stalledHardFamilies` の消去）。反映の前に純データ `ui/RelaxRollback.kt`（UiState の控え・Undo/Redo の段・結果盤面・他の案）を取り、`restoreBeforeRelax` がそのまま戻す（確定の 1 行 `relaxDone` も前の値へ）。`openRelaxFlow` は試算中を「試算がまだ終わっていません」（SEARCHING）、失敗を「試算に失敗しました。もう一度試算してください」（FAILED）と分ける（旧: どちらも STALE「勤務表か設定が変わりました」）。主ボタンは R4 の「閉じる」1 つのまま（INV-09）。操作ログは relaxAndApply のどの終わり方でも「S6反映 結果=…」を 1 行残す（旧: データなし・実行中・データに問題・段を積めないの 4 経路は無記録、`RelaxApplyLogSitesTest` が各 return を照合）。`RelaxRollbackTest` 3 件・`RelaxApprovalTest` +1、ホストテストの ui 一覧に `RelaxRollback`。C# は 24 時間の一時停止中のため未同期（再開後に写す）。`RELAX_TRIAL_ENABLED=false` のまま・出力不変  → `docs/history/3.4xx.md`
- 個人の上限0を「対象外（上限0）」と中立表示・期間を超える c1/c2 行の取込警告（2026-10-22、3.723.1、ユーザー決定「推奨で」、backlog #11・#42(h)⑥）: 画面のみ・出力不変。  → `docs/history/3.4xx.md`
- 文書の照合（2026-10-22、出力不変）（版上げなし・文書/コメント/未使用関数のみ）: stall_escape の wishC3wProven・wishReached・§10 表に floorCountsUpperZero／floorShortStall、business-logic・backlog #42(b)/#43②/#44④・screen_spec・README の目次と重み21種・最終更新の順、未使用の SetupGuideCard を削除  → `docs/history/3.4xx.md`
- Q-05/Q-06 を推奨案 A で決定（2026-10-22、ユーザー「推奨で」、出力不変・版なし）: 群レンジの上限 0 は SOFT 目標のまま（入れない指定は「グループ単位の回数」の上限 0）＝business-logic、M-07 は Staff.id で解決済みとして閉じ CSV の名前照合の扱いを明記＝data-models。設計書の P8・P9 は文書だけで完了。
- 段階的全面改修の設計書（完全版）を追加（2026-10-22、ユーザー決定「全面改修する」、出力不変・版なし）: `docs/overhaul_design.md`＝ゲート（チェッカーが真実源・Δ=フル・native-parity/golden・keep-best・最終番兵・保存世代・HF77・盤面ハッシュ一致）、段階 P0〜P7（P0〜P5 は出力不変のリファクタ、P6 グループ上限0・P7 識別子は仕様決定後）、付録 A に Q-05/Q-06 の仕様調査。
- versionCode/versionName の採番検査を CI に（2026-10-22、U-04、出力不変・版なし）: `tools/check_version.py` を Design Lint ジョブに追加（main 以外）。この変更が版を変えたときだけ main の先端と比べ、versionName が変わったのに versionCode が増えていない・versionCode が減った・名前が同じで code だけ変わった を落とす（並行セッションが同じ番号で先にマージした場合も検出）。版を変えない docs/tools の変更は検査しない。
- 28 項目の残課題の裏取りと、取り残し 2 件の是正（2026-10-22、3.723.0、ユーザー提示の不具合一覧 28 項目・診断のみ＝出力不変）: ①L-06 の取り残し＝3.717.0 は日の証明（`analyzeDayConflicts`）と置ける人数だけに手動固定を数え、**職員 1 人の証明**（`ConstraintMus.analyzeStaffConflicts`）は `wishFixed` のままだった→`lockedPin`（`wishLocked`/`lockTo`）へ。上限 1 に 2 日の手動固定が「同時に成立しない」と出る（`ConstraintMusTest` 2 件、戻すと落ちる）。②L-04＝必須・推奨の並びと禁止の並びの矛盾は単一シフト連どうし（3.534.0）しか見ていなかった→複数シフトの並びの中に禁止の並びがそのまま入る組（例「A→休→A」と「休→A」）も `mustForbiddenSeqIssues` で案内（`V6SanityPortTest` 2 件）。③文書のドリフト: c3w の「希望(`wishLocked`)」→実装どおり `wishFixed`（手動固定は含まない）。  → `docs/history/3.4xx.md`
- fair 研磨の正味差分つき同日 2 人交換走査（2026-10-19、測定のみ・中止・コード不採用、backlog#27②）（10seed×2rep の延長測定を途中 52/100 対で打ち切り: Δweighted 平均 −57・95%CI [−136,+21]・勝30/負22・必須悪化0＝効果が測れない。ユーザー決定「効果がないので中止」2026-10-19。コードは main に入れない＝記録のみ、C# 同期も不要）: `PolishGate.fairExchangeScan`＝`applyFairPolish` の末尾で、全ペア×全日の同日交換のうち関わる群×シフトの `fairDevOfBucket` 合計が下がる手を改善量順に正式 checker（`toleratedBetter`＋厳密ピン）で採否。Kotlin 後処理のみ＝C++ 変更なし。HandleOptimizeBench `fairx` 6件×5seed×60秒・workers 4: hard 悪化 0/30、weighted 平均 −117.5（95%CI 約 [−229,−7]）、勝15/負8/同7（dev1017 は 5/5 同一）。ただし fair 件数の平均は下がらない（oct2026_grid 29.6→32.0）＝便益は経路効果。採用推奨（基準充足）だが既定化は再測定の後  → `docs/history/3.4xx.md`
- E0 の再測定（floorCountsUpperZero 既定 ON 後、2026-10-19、測定のみ・既定 OFF のまま）: `HandleOptimizeBench` に腕 `wishfloor`（OFF/E0A/E0B の 3 腕）と盤面ハッシュ列を追加。dev1017・blocked_covu・sample_v6・oct2026_grid × seed1〜5 × 60s（workers4）と dev1017 × seed1〜3 × 300s。HARD は全 69 対で同一（悪化 0）。60s の Δweighted（対 OFF、n=20）E0A −57.8 [95%CI −183,+68]（悪化 7・改善 8）、E0B −71.0 [−216,+74]（悪化 8・改善 7）＝0 をまたぐ。盤面一致は dev1017 の 5 本のみ（全腕同一、床 4 で上限0込みの頭打ちが既に効く）。壁時計 E0A −0.7s（n.s.）、E0B −1.8s（60s）／−5.2s（300s、OFF でも約 80s で停止）。結論: OFF 維持（E0A は効果を検出できず、E0B は時間だけ）。  → `docs/history/3.4xx.md`
- S6 承認ダイアログのデザインレビュー対応（2026-10-19、3.722.1）: SHOULD 3 件を画面だけで直す＝見出しに heading()、R4 の終了理由・R5 の「反映後の確認」に liveRegion（同じダイアログ内で段が替わっても読み上げる）、「試算の条件」「手順（勤務表への当て方）」の畳みに stateDescription（開いています／閉じています、見た目のラベルは不変）。C# は R2 の「勤務表に当てる手順（…）」の規模の添え書きが欠けていたのを Android にそろえ、終了理由・反映後の確認に LiveSetting、畳みに ItemStatus。決定ラベル・`RELAX_TRIAL_ENABLED=false` は不変。出力不変  → `docs/history/3.4xx.md`
- S6 確定を承認フロー R1〜R5 に分ける（2026-10-18、3.722.0、ユーザー仕様＋決定「§14 Q3 維持・権限なし（承認者＝この端末の利用者）・記録は操作ログだけ」）: `ui/RelaxApproval.kt`（純関数、`RelaxApprovalTest` 9 件、C# 1 対 1）。R1 読み取り専用の試算結果→R2 変更内容（前→後・当てる手順の全セル）→R3 承認（理由必須）→R5 結果、条件外・変更しない・古い・再現しない・事後確認不一致・保存失敗は理由の分かれた R4。反映後 `check.hard≠rr` か保存失敗なら Undo 段を外して (st,B) へ戻し「S6反映 結果=復旧」。同じ decisionId の 2 回目は何もしない。`RELAX_TRIAL_ENABLED=false` のまま（画面には出ない）。画面と操作ログのみ・出力不変  → `docs/history/3.4xx.md`
- 停止説明の境界テスト（2026-10-18、3.721.2 上、テストのみ・版上げなし）: `StopExplanationBoundaryTest`（Kotlin・C# 1 対 1、4 件）。後処理で必須が減った／床の後に盤面が変わった／最終必須 0 の 3 境界で、文が採用した最終結果の数だけを言うことを固定。読んだ結果、不整合なし＝`remainingHard` は `finalReport.hard`（採用段）、breakdown も同じ採用レポート、入力維持の分岐は `stopSummary` を null のまま（両言語同じ）  → `docs/history/3.4xx.md`
- サンプルデータのシフト色を 36 色パレット（大区分 6 色相×明度 6 段）から割り付け（2026-10-18、3.721.2、ユーザー指示「サンプルデータのシフト色を変更する」）: データのみ・出力不変（shiftColors は表示専用、指紋に入らない）。assets の sample_state_…
- 問題一覧の選んだ行が別の問題へ飛ぶ不具合（2026-10-18、3.721.1）: `problemPickedIndex` に `pickedFamily` を足し、行番号のマス・族一致 → 同じマス・族の先頭 → そのマスの先頭の順で引く（`MagiApp` の `listPickFamily`）。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 探索の必須同数タイブレークを族の重みで（2026-10-18、測定のみ・コード不採用）: `PolishGate.searchHardFamilyTiebreak`（既定OFF）＝評価器（Kotlin `Evaluator`/`DeltaEvaluator`・C++ `fullEvalParts`/`SaChunk`、JNI meta[8]）が soft へ Σ(必須族の重み×件数) を足す。必須件数の桁は不変、静的上限 `Problem.hardTiebreakBound` が 5e8 以上なら無効。HandleOptimizeBench `hardtie` 6件×5seed×60秒＝hard 差 0/30・weighted 平均 −20 [−101,+61]・勝11/負12…（sample は +151 で一貫して悪化）、dev1017 300秒×3 は同一＝**既定OFF維持**（backlog #19 の 2 度目の否決）  → `docs/history/3.4xx.md`
- 再作成の主を譲る（2026-10-18、3.721.0）: 残る必須違反がすべて「何度つくっても残る」床以内で前回から何も変わっていない（`sameResultVisible`）間は、下部の「再作成」を外枠にし、塗りの主を「変更案を探す」（探し終えて案が無ければ「問題一覧」）へ（`rerunDemoted`・`rerunFooter`、床は `SameResultNote.floor`＝`PreRunCheck.floorCount`）。INV-09 維持。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 編集タブ②（2026-10-18、3.720.0、計画 §2 の最終便）: 職員管理・年間マスターの見出しカード（`editHeaderPlan` scope1/2・`ScopeHeaderCard`・`staffingRowText`）、`SetupGuideCard` を全区分で撤去、①を既定で畳む（`yr_ws1` の着地）、ボタンの動詞化、回数のシートの塗り 1 つ（`raiseCapButton`）、問題一覧の選択を行で持つ（`problemPickedIndex`、INV-01）。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 視覚弱点者対応 第1便＝拡大・読み上げ・コントラスト（2026-10-18、3.719.0）: ユーザー指示「視覚弱点者対応する」、決定「第1便: 拡大・読み上げ・コントラスト」。文字拡大に追従（`a11yMaxLines`・`homeTargetLabel(fontScale)`・勤務表の記号 `(セル幅−4dp)/2` 上限・`ScheduleLayoutMetrics.growth`＝1.3× まで不変）、読み上げ（liveRegion・スイッチの行 `toggleable`・セルの `stateDescription`・`DialogHeader` の heading）、コントラスト（`MagiAccent.orange`/要調整の既定色 #E08A1E,#F59E0B → #B86E00、セル地が surface と 1.5:1 未満なら輪郭）。第2便＝WinUI のセルの UIA・キーボード・ハイコントラスト。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 問題パネル（2026-10-18、3.718.0）: 勤務表タブの下 1/3 のパネル 1 つがセルの編集と問題一覧を切り替える（`PanelMode`・`nextPanelMode`・`panelMaxHeightDp`＝画面高÷3、`problemRows`＝必須→要調整、`ProblemListPanel`、`SchedulePanel`＝モードレスの共通の殻、凡例・一括 2 つもパネルへ、確認のダイアログは 1/3 に揃えてモーダルのまま、「一覧へ」）。ユーザー指示「制約違反を見ながら勤務表を修正したい」「勤務表の上の画面は 1/3 程度に抑える」＋仕様文（1/3 以内・背面ロック無し）＋「スマホ指可動域と指操作導線と目線」「指の動きとオブジェクトを遮蔽しない」、決定「1/3固定・今の閉じ方のまま」。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 日の証明と置ける人数に手動固定を数える（2026-10-18、3.717.0）: 3.709.0 の監査で確認。希望衝突の床の日の証明（`ConstraintMus.analyzeDayConflicts`/`dayProofsWithoutZeroCap`/`dayShortProofs`）と `placeableFor`（構造的 covU 床・配布不可・担当者不足）が「席を埋められる人」を希望固定だけで数え、上限 0 のシフトへの手動固定（最適化器は守る）を数えていなかった → `wishLocked`/`lockTo` で希望固定と同じに扱う。人員不足の診断の capacity・再作成で外れるセルも同じ基準。出力不変（keep-best・最終番兵は不変）、変わるのは手動固定のある盤面の早期終了の時機と診断の文（コアの「手動固定「…」」）  → `docs/history/3.4xx.md`
- 論理監査の是正（2026-10-18、3.716.0）: 3.711.0〜3.715.1 の論理監査で見つかった 11 件。問題一覧の 1 行 `problemLine` は前置き（名前と日）を残して型の側を「…」で切る（INV-01）、希望の行「希望と違う勤務です」、禁止の並びは全日を「→」でつなぐ、案なしの 1 行は締切なら「時間内に見つかりませんでした」、続けて 0 件は人の操作だけ数える（`auto`・鍵に `quick`）、セルのシートの塗りは 1 つ（INV-09）、「前回と同じ結果」は維持の実行で出さず・読込で消え・鍵は状態指紋、シートを開いたときの探索は `auto=true`、札は検査済みのときだけ、分析の一文「必須0件」、C# のみ `TourProblemLine` の族名と人員不足の札の 3 色。画面・記録のみ・出力不変  → `docs/history/3.4xx.md`
- 本日分のコードレビューの是正（2026-10-17、3.715.1）: 3.708.1〜3.715.0 の差分を /code-review で見直し。作成中の相談の一覧を「開く」で出したあと `consultOpen` が戻らず、次の作成中も一覧が出続けていた（3.714.0）→ 作成が終わったら畳む。`homeHeadline` の使われない `startDate` 引数を削除（3.713.0 で定数化した名残）。C# 同日（`_consultOpen`・`HomeHeadline`）。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 不要なテストと使われない関数の削除（2026-10-17、3.715.0）: ユーザー指示。本番から呼ばれない `homeBadges`・`homeMonthHeadline`・`violationTour`・`nextTourCell`・`peekHeading`・`countGapLine`・`staffCountLines`・`MojibakeRepair.looksMojibake` とそのテスト、Review36xx/37xx の重複 assert 6 行を削除（C# も同期）。`involvedWishes` は `tools/loop/S5Probe.kt` が使うので残す。出力不変  → `docs/history/3.4xx.md`
- 短い骨格の残り（2026-10-17、3.714.0）: 3.713.0 で見送ったデザイン例の項目。問題一覧の 1 行 `problemLine`（族ごとの型、18 字）＋主「直し方を見る」、人員不足の「希望を見る」「担当を編集」、希望の前日の行 `c3wShortLine`＋主「希望のまま」・「相談」「保留」、案なしの主「問題一覧」＋「閉じる」、セルの `cellRuleLine`＋「詳しく見る」、設定「設定／組む時間 1分」、編集「条件／希望・人数・基本」、作成中の相談の「開く」、未作成の見出し「まだ作っていません」・未読込「データを開く」。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 短い骨格をデザイン例に合わせる（2026-10-17、3.713.0）: ユーザー提示のUIデザイン例（10 画面）に合わせ、見出し 12 字・説明 1 行 22 字・塗り 1 つ・副操作は文字。ホーム「今月の判断」＋札「必須 残りN件／必須は満たしています」＋問題 1 行「10/10のAｱが1人不足」＋「必須を見る」／「配布前の確認」、相談「相談中 N件」、変更の確認「変更の確認／Nか所変わります／行」＋詳しく、案なし「案はありません」、作成完了「作成が終わりました」、分析「必須3件 · 要調整12件」。決定済みのボタンの語は維持。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 見出し＋1 行＋主ボタン（2026-10-17、3.712.0）: 利用者仕様「画面・ダイアログは見出し（12 字まで）＋説明 1 行（22 字まで）＋主ボタン 1 つ、長い定型文は「詳しく」へ」。診断カードの行・ぶつかっている希望・実行中のホーム・案のカード・当てたあとの帯。決まっているボタンの語は維持。画面のみ・出力不変。
- ホームの主ボタンと自動の案探し・同じ結果の注記（2026-10-17、3.711.0）: 主ボタン「ぶつかっている希望を見る（アリフ 10/8）」が折り返す → 対象を前置き「アリフ 10/8の希望を見る」、18 字を超えれば元の文言（`homeTargetLabel`）、1 行・省略記号。作成完了の自動の案探しを「案を探す（自動）: 全体」と記録し操作の要約から外す（`FindFixSuggestions.auto`）。前回と同じ状態指紋・盤面指紋なら「前回と同じ結果です。…」（`sameResultNotice`）と「同じ結果（盤面指紋 …）」。同じ対象が続けて 0 件なら「この対象には、いまの条件で直せる変更案がありません。」（`nextEmptySearchStreak`）。画面とログのみ・出力不変  → `docs/history/3.4xx.md`
- ボタンの文言を 1 行に収める（2026-10-17、3.710.0）: 3.708.1 の名字の欠け（丸い端のボタンで 2 行目以降の左上が切れる）の横展開。ユーザー決定「文を短くして 1 行に収める」（形は変えない）。約 20 か所の文言を縮め、注記はボタンの下の一文へ（`SOFT_POLISH_NOTE` 新設など）、氏名入りは 1 行・省略記号。文章の行ボタン（作成前の確認の行・相談の答え）だけは短くできないので `shapes.small`。並んだ 2 ボタンは FlowRow、書き出し 2 つは縦積み。上部バー: 巡回「必須違反Nか所を見る」（`topBarTourLabel`）・状態「自動作成済み/手で変更あり/手で編集/自動作成前」・「保存できていません」。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 早期終了の床を上限0込みに（既定 ON、2026-10-17、3.709.0）: `PolishGate.floorCountsUpperZero` を true へ。実機データ（11人・31日・必須4＝希望衝突1＋上限0込みの日の証明3）で腕 `floorup0`・300秒・3 seed・workers 4: off 298/297/296 秒 → on 78/78/78 秒、必須/合計/重み 4/411/42337 は全 seed で同一。フィクスチャで床が変わるのは oct2026_grid だけ（床 4→6）で、60 秒×5 seed は両腕とも必須 9＝床に届かず判定不能（発火なし）。結果 CSV は `tools/loop/results/handleoptimize_floorup0_*_2026-10-17.csv`。探索の時間だけが変わる  → `docs/history/3.4xx.md`
- ぶつかっている希望の行で名字が欠ける（2026-10-17、3.708.1）: 端末報告「福沢の名字が欠けている」。行のボタンが既定の丸い端（高さの半分の角丸）で、3 行になると左上の字を切っていた（3.696.0 の札の配置替えでは残った）→ `shape = MaterialTheme.shapes.small`。あわせて要約の「うち計算では消せない N件」が「人員不足の日に別の勤務の希望 N人」の内訳に読めたので、件数の行の括弧へ（`wishConflictSummary`、2 点まで）。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 画面文言を yomiyasu の規則で見直す（2026-10-17、3.708.0）: 「/yomiyasu all」アプリの画面文言すべて。ダッシュ 8・単位の後ろの空白 11・英字前後の空白 4・比喩動詞「効く」3・硬い漢語 2・「＝」つなぎ 2・装飾ダッシュ見出し 2・言い換えカッコ 1・「壊れる」1・「です。」の連続 1＝35 か所。「ラベル：本文」形と「壊れています」（ファイル）は据え置き。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 編集タブ① 月次条件の見出しカード（2026-10-17、3.707.0）: 8秒理解UI 第9便（計画の 3.701.0 節、Android 列）。E-2 `editHeaderPlan`・`EditHeaderPrimary`（EDIT_NEED／OPEN_MASTER／ADD_STAFF／RERUN／PANEL）・`EditHeaderCard`／3a-1・3a-2・3a-5 SetupGuideCard を月次で出さない・題「月次条件（年月）」・点 3 つ・「名」→「人」（仮定）／3a-3・3b-6 Q10 塗り 1 つ `editBarSecondary`／3a-4 `wishScrollKey`・`needScrollKey`／3a-6 帯を固定 `EditReturn.problem`・`editReturnLines`・`editReturnProblem`／3a-7・3a-8・3b-1〜5・W-1 `wishConflictSummary`。画面のみ・出力不変。Review3707Test  → `docs/history/3.4xx.md`
- 設定タブの「組む時間を変える」（2026-10-17、3.706.0）: 8秒理解UI 第8便（計画の 3.700.0 節、Android 列）。S1・S7 `budgetPresets(currentSec)`＋シート「組む時間を選ぶ」・±60秒撤去（既定・範囲は不変）／S2 `budgetText`・`budgetStatusLine`（preRunPlanLine と共有）／S3 設定タブの下部バーを外枠／S4＋F-3 `SettingsSaveFailedBanner`・データカードの失敗文を隠す／S5・S6 動詞ボタン・「種類別のCSVを書き出す」・版の表示を末尾・詳細設定の説明。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 分析タブの状態の段と主ボタン（2026-10-16、3.705.0）: 8秒理解UI 第7便（計画の 3.699.0 節、Android 列）。A1・A5・A6 `analysisSummary(ui, tourCount, fixResolved)`（一文 6 通り＝boardStatusLines と同じ区別・3 点まで・札「計算済み／未計算」と実行中の注記を撤去）／A2 主ボタン 1 つ `AnalysisPrimary`（TOUR／GUIDED／NONE、主があれば下部バーを外枠）／A4 `FixResolvedUnion`・`fixResolvedUnionOf`・`resolvedHardKeys`・`analysisFixResolved`（Default で和集合、schedule＋ops で鮮度照合、未探索は出さない）／A8・A9 注記の文言・`issueSettingsLabel` の全幅ボタン・「（構造的に残ると判定）」撤去／見落とし＝セルの行で editingCell、人員不足の行は［勤務表でこの日を見る］に改名／A10 screen_spec §08・A.7・operator_ux §4.4。画面のみ・出力不変。Review3705Test。
- 配布前の確認・作成前のシート・ダイアログの型（2026-10-16、3.704.0）: 8秒理解UI 第6便（計画の 3.698.0 節、Android 列）。P-1 `preDistributionTop`/`preDistributionDetails`・`PreDistRow.primary`・`preDistributionPeriodLine`・脚注 `PRE_DIST_EDGE_FOOT`／✕ を外し「戻る」だけ／R-1 ボタンを下端に固定・節のボタンは TextButton／R-3 旧名を `PRE_RUN_TITLE` に・戻ったら項目なしでもシート／R-4 `preRunConditionLine(floor, rerun, overCaps, pins)`／Q3 `preRunProceedLabel`／G-1 `DialogOptionButton`／G-2 既定「閉じる」・「担当内だけ反映する」／G-3 緩める試算の主を confirmButton へ／G-4 `ConfirmDialog`＋`confirmDialogPoints`（3 点まで）。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 人員不足の手伝いと変更の確認（2026-10-16、3.703.0）: 8秒理解UI 第5便（計画の 3.697.0 節、Android 列）。H-1/2 `guidedFixTitle`・`guidedStatusPoints`／H-3 `guidedCandidateLabel`／H-5 設定へは塗り・終わりの主［残っている必須違反を順に見る］／H-6 `ShortageSlotHeader`／D-1/2 VM `openPreview`＋`fillPreview`・`previewSummaryLine`／D-4 閉じるは 1 つ／D-6 `ChainFixPreview.title` 削除／Q4 ［この変更を適用する］。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 勤務表タブの状態の 1 行とセルの確認シート（2026-10-16、3.702.0）: 8秒理解UI 第4便（計画の 3.696.0 節、Android 列）。人員不足の帯→`scheduleStateLine` の 1 行（5 状態・23 字）／必須違反の一覧に単位ごとの件数と主［上から順に見る］／セルのシートに題「このマスの確認」・対象の行 `cellSheetTarget`・札 `cellSheetBadge`／ちら見の主［変更案を探す］を常に 1 つ（`peekSearchAction`、3.699.0 の全部 ⚠ のときだけの条件を置き換え）／緩める候補・人探し（別の問題があるとき）・前日翌日閉じるを副へ。C# 専用の G2-2/8/9 は移植側。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 決定 A4 休は特別扱いしない（2026-10-16）: 休の必要 0 で休の全員が人員過剰になっても covO の数え方は変えない・表示も隠さない。避けたいときは必要人数を空欄に（再提案しない）  → `docs/history/3.4xx.md`
- 保存の失敗を 8 秒で知らせる（2026-10-16、3.701.0）: 8秒理解UI 第3便（計画の 3.695.0 節）。上部の札の左に error 色の「自動保存できていません」（`topBarSaveWord`、押すとダイアログ）／保存が成功から失敗に変わった瞬間にダイアログを 1 回（題「自動保存できませんでした」、やり直しも失敗したら「まだ保存できていません」、主［もう一度保存する］副［閉じる］。`SaveFailNotifier`・`UiState.saveFailDialog`）／主カードの状態の行の先頭に「自動保存できていません。端末の空き容量を確かめてください」＋外枠の［もう一度保存する］（`homeStatusLines(..., saveFailed)`、作成の行の中の再試行は外す）／通知文 `SAVE_FAILED_NOTICE`／反映の帯の再試行は失敗中だけ塗り／勤務表 CSV の書き出し失敗で配布前の確認を開き直す（`csvExportReopensPreDist`）／396dp 未満は札の下の行へ自動作成の状態を畳む（`topBarStatusWrapped`）。Q5＝SAVE_FAILED の段は足さない。画面のみ・出力不変  → `docs/history/3.4xx.md`
- D03 争点カード（2026-10-16、3.700.0）: 8秒理解UI の利用者決定 1「入れる（既存の案から組む）」。日の人員の不足（必須）の変更案の一覧の先頭に、主一覧（T1→T2 の既存の順）のうち見ている問題を減らす上位 2 件を「推奨A: …」「理由: 必要 2人を満たします（不足 1人 → 0人）」「案B: …」と並べ、［推奨Aで進む］（塗り）［案Bにする］で各案と同じ変更の確認（D01）へ。純関数 `contestCard(target, label, main, schedule, staffNames, shiftSymbols)`→`ContestCard(title, a, b?)`（`CellSheetLogic.kt`）。理由は案を盤面へ当てて数え直した `CountEffect.afterNote`/`gapLine`。案 1 件なら A だけ、0 件ならカードなし（従来の `NoFixBlock`）。新しい探索・採用基準の変更なし＝出力不変  → `docs/history/3.4xx.md`
- 画面精査の是正（2026-10-16、3.699.0）: 端末の画面 17 枚の精査（3.692.0 時点）。A1 日から開いた変更案の一覧はその日のセルに触れる案だけ（`listableFix`・`fixListDay`）／A2 見ている問題を減らさない案は一覧に出さない（適用の門と同じ `improvesTarget`、「別の問題を改善する案」の畳みは対象のあるときは出さない）／A3 過剰の 1 人を移す案の題「X さんを A から 休 へ移して過剰を減らす」（`surplusMoveTitle`）／A4 休の必要 0 で全員が過剰に見える件は評価（covO の数え方）の変更になるため止めて利用者判断待ち／A5 ちら見で候補が全部 ⚠ なら「変更案を探す」1 つ（`peekHopelessAction`）／D08 下書きの確認に「必須 N件→M件」（下書きは決定的＝先に 1 回作り、置き換えでその結果を使う）／下書き直後の主ボタンは「勤務表をつくる」（`HomePlanKind.DRAFT`）。画面のみ・出力不変  → `docs/history/3.4xx.md`
- ホームの主カードを 見出し → 状態 2 行 → 主ボタン 1 つ に（2026-10-16、3.698.0）: 8秒理解UI 第2便（利用者決定 4＝Figma S01 の形）。並びは純関数 `homeCardSections`（作成の行→見出し→札→状態 2 行→主→補助→ほかの問題→詳細→でき具合→ⓘ→下書き）、見出し `homeHeadline`（「2026年10月の判断」・番号②③を外す）、札 `homeBadges`（INV-07）、状態 `homeStatusLines`、人員不足の 1 文 `shortageHeadline`、段の対象 `creationNextTarget`（主ボタンの括弧と同じ＝INV-01）。語: 「残っている問題を見る」「配布前の確認をする」「表で全体を見る」「変更内容を確認する」「勤務表を組んでいます ・」「勤務表を開く」。帯は外枠、中断の題を直す。画面のみ・出力不変  → `docs/history/3.4xx.md`
- 操作ログで人間の迷いを読む（2026-10-16、3.697.0）: 画面の操作を操作ログへ（画面:／手で変更:／下書きで置き換え（確認済み）: 必須 a件→b件／案を探す:→案:／変更の確認:／相談に残す:／改善手を適用 … 案を見てから N秒／巡回 開始・終了／作成前の確認:／配布前の確認:／人員不足の手伝い:）＋書き出しに「操作の要約（迷いの指標）」（`OpLogText.hesitationSummary`、JSON `hesitation`）。画面側は `vm.logUi(UiOp)` の 1 入口。出力不変 → `docs/history/3.4xx.md`
- 端末報告 3 件（2026-10-16、3.696.0）: 「相談中」の札の隣に「取り消す」（`cancelConsult`、済とは別）・ぶつかっている希望の行の札を文の下へ（名前の先頭が欠けた）・実行中のホームは診断の束を出さない（縦に長すぎる）・相談中カードの操作を文の下の 1 行へ。画面のみ  → `docs/history/3.4xx.md`
- 端末ログの強化（2026-10-16、3.694.0）: 実機ログで G1〜G8 に答える行を足す（出力不変）＝「最適化 開始」を #N 付きで 期間=・職員=・状態指紋=・盤面指紋= つきに＋「設定の開始時」（`TuningTelemetry.startSettings`）、「初期解生成 開始」、完了行・「改善手を適用」・「読込（取得元=）」に盤面指紋（`StateFingerprint.ofBoard`＝旧 `boardKey`）、見送りの W 行、「期間変更」の 要求期間=、探索の強さの 4 フラグ（`SearchStrength.flags()`）、書き出しの期間・指紋行と捨て件数の注記（`OP_LOG_CAP`）、診断ログ「容認採用」（先頭 5 件、`TuningTelemetry.noteTolerated`）、CoverageDiag の（根拠: …）（`CoverageShortfall.basis()`）。純関数 `OpLogText`。時間ゲートは hash 16/16 一致  → `docs/history/3.4xx.md`
- 8秒理解UI 第1便＝数の単位の誤り（2026-10-16、3.693.0）: 実行中の札 `runningBadgeLabel`「組んでいます 必須1件・2人」（必須 0 なら「組んでいます」）・反映の帯の「（Nか所）」を巡回できる数（`FixNav.tourCount`）に・適用の通知 `fixOutcomeText` を前後とも単位ごと・分析の「ほか Nか所」・コロンを全角（INV-07）。計画の全文は `docs/history/ui8s_plan_3.693.0.md`（決定待ち Q1〜Q11 は §3）  → `docs/history/3.4xx.md`
- 人員不足の診断の根拠を揃える（2026-10-15、3.692.0）: 設定ミス診断の日の証明（上限0・希望固定を置けない側）を `CoverageDiag` も使う（`ConstraintMus.dayShortProofs`・`CoverageShortfall.dayProof`→充足不可「この希望と上限のままでは埋まりません」）・残存分析の「実証済み」は証明つきの枠だけ・HF70 に「うち人員不足 N 件はこの希望と上限のままでは埋まらない」・早期終了の床を上限0込みにする `PolishGate.floorCountsUpperZero`（既定 OFF・ベンチ腕 `floorup0`、測定は保留）・下書きの置き換えの確認をログに  → `docs/history/3.4xx.md`
- 画面の文言の仕上げ（2026-10-15、3.691.0）: ボタンを動詞（削除する/削除しない・反映しない・移らない・取り込まない 等）・確認の題を「？」の問い・CSV 取込の失敗を「〜できませんでした。原因。次の行動」・「ご確認ください」を次の行動へ、68 件（表 `docs/history/l10n_3.691.0.md`）  → `docs/history/3.4xx.md`
- 適用経路の強化（2026-10-15、3.690.0）: 拒否理由の族名の既定「必須条件」・`previewOrApplyFix`→`previewFixSuggestion`（`fixTarget` を運ぶ、相談の `ChainTarget.fixTarget`）・確認の画面からの適用は指紋 0 を断る（`fixOriginRefusal`）・断られても確認の画面を残す（`applyError`）・ホームの段を `homePlan` へ  → `docs/history/3.4xx.md`
- 画面の文言を読みやすく（2026-10-15、3.689.0）: yomiyasu の提案 34 件（取込・保存の断り・ヘルプ・設定の注記・通知の「最適化」→「勤務表をつくる」）・通知チャンネル／ショートカット／バブルの表示名「勤務表の最適化」→「勤務表づくり」（ID は不変）  → `docs/history/3.4xx.md`
- 画面の「探索」をふつうの言葉へ（2026-10-15、3.688.0）: 組む時間・探しています・探すのを止めました・探す強さ・今回探した範囲では（operator_ux §2 に追加）／CI: 作業ブランチの main と同一・後れの push で V6 Engine Check を飛ばす（a2f2d32）  → `docs/history/3.4xx.md`
- コードレビュー対応（2026-10-15、3.687.0）: 不足枠の候補を正式な前後の検査で採点してから確認の画面へ・希望の取消に診断の指紋（`wishDiagnosisFingerprint`）と職員 ID・「充足不可」は担当できる人数<必要数の証明つきだけ（限定探索の不発は `searchMiss`＝「試した入れ替えでは埋まらなかった」）・`explainExtras` を初め/主だけ/全部の鍵の因果で判定・まとめた付随を先頭の人に寄せない・c41/c41s の負の値を未設定に（負の上限が全日違反だった）・門の説明・色の札の単位・確認の行を職員 ID で束ねる  → `docs/history/3.4xx.md`
- コードレビュー対応（2026-10-15、3.686.0）: keptPrevious を結果の差し替え全経路で外す・initHard の基準に盤面差し替えの回数・`entryMoves` を `mayPlaceAt` で絞り Problem/before を 1 回・作成前シートの指紋に外れる手置きセル・他の案の比べを betterReport で・表示の写しを CAS の外で・ホーム見出しの解消行を VM へ・回転研磨の差分評価に抜き取り検査（出力不変）ほか  → `docs/history/3.4xx.md`
- 改善提案で崩した希望を戻す（2026-10-15、3.685.0）: `FixSuggester` の単一変更だけ、`wishLocked` かつ `lockTo` から外れたセルに「縛る値へ戻す」候補（`restoreWishLocked`、既定 ON）。実データ 4 件×崩し 1〜3 で戻す手が上位 8 件に 0→1〜3、時間差なし。  → `docs/history/3.4xx.md`
- 印と一文を 1 か所で（2026-10-15、3.684.0）: `DisplaySnapshot` を検査結果の反映（makeUi）で 1 回作り、上部の札・必須一覧の件数・巡回・セルの状態の 1 行が同じ写しを読む（古ければ従来の計算へ）  → `docs/history/3.4xx.md`
- 画面の言葉と並びの監査（2026-10-15、3.683.0）: セルのシートの状態の 1 行と「ほかの操作」・案が無いときの 1 行・職員の勤務状況は行を選んでから・希望への行き先を 1 つ・作成結果を主カードの上端へ・前のままにした札・作成前の 1 行・分析の上段を 2 つに・他の案の比べ・通知 2 行・人員不足の札・設定の先頭に作る時間（画面のみ）  → `docs/history/3.4xx.md`
- 残りの UX 監査（2026-10-15、3.682.0）: 足りない側のセルを赤くしない・確認の画面の一文と悪くなる問題・［今は変更しない］・反映の帯の順と次の一歩・回数の案の根拠と移すだけ・ホームと分析の見出しを効き目から・案の名と通知の言葉（画面のみ）  → `docs/history/3.4xx.md`
- 検証済みの指摘の修正（2026-10-15、3.681.0）: 人員不足の候補ボタンを確認の画面経由に・候補と診断を `mayPlaceAt`・古い診断の希望は取り消さない・玉突きの断定をやめる・止めた探索の［もう一度探す］・確認の画面に対象の前後・指紋に職員 ID・目標も確定で書く（B9 は測定待ち）  → `docs/history/3.4xx.md`
- 後処理の出力不変の無駄取り 2 件（2026-10-15、3.680.0）: 3者回転の差分評価による前判定（`PolishGate.rotationDeltaPrescreen`）と巡回研磨の採用 0 パスの再実行省略（`roundPassMemo`）、既定 ON・実データ 4 件で最終盤面一致  → `docs/history/3.4xx.md`
- 入り口の考え方と変更案を揃える（2026-10-15、3.679.0）: 範囲の中の 1 セルの案（Tier 1）を探索より先に、付随の変更の理由をチェッカーで、移すだけの案と理由のない案は畳む、下書きで置き換える前の確認  → `docs/history/3.4xx.md`
- 画面の言い方の整理（2026-10-15、3.678.0）: 必須違反の数を単位ごと（`hardText`）、でき具合の基準を同じデータの再作成で据え置き、自動作成の「済み（そのあと手で変更あり）」、相談の未完了は回答待ちと保留だけ、確認の見出しを 1 つに  → `docs/history/3.4xx.md`
- 実際に動いた方式の表示・変更案のパネルを職員 ID で・下限到達の短縮の測定腕（2026-10-15、3.677.0）  → `docs/history/3.4xx.md`
- 単位を足さない表示（2026-10-14、3.676.0）: 件・人・回・pt を 1 つの数に足さない（要調整・必須違反・案の変化・通知）、族の単位を内訳の数え方どおりに  → `docs/history/3.4xx.md`
- 統合仕様の残件（2026-10-14、3.675.0）: 保存に失敗したら「もう一度保存する」、案が無い理由で時間切れを分ける、相談の操作を番号でなく項目で  → `docs/history/3.4xx.md`
- 統合仕様（表示基盤と思考誘導）の 1〜4（2026-10-14、3.674.0）: 対象が減らない案は適用しない・未評価を「なし」と言わない、表示した問題＝操作する問題、主ボタンは画面に 1 つ、セルのシートの正式な検査を描画の外へ  → `docs/history/3.4xx.md`
- 残件（2026-10-14、3.673.0）: 両日の不足をまとめて調整する案（日ごとの不足の合計）、その日の勤務を見る、c3n 壁の短縮を既定 OFF に（sample 24 対 +0.85%・決定、60 s の損失の回収）  → `docs/history/3.4xx.md`
- 自動作成と半自動修正の分担（2026-10-14、3.672.0）: 担当者が判断する 4 か所を図で、作成前シートを毎回、作成後の結果カード、確認の画面と適用後の文言  → `docs/history/3.4xx.md`
- 開いた問題と探す対象を結び付ける（2026-10-14、3.671.0）: 日別の個人別検索で日付を保持、セルのシートは「このセルで実際に消える」案を主に、1 セルの案も確認の画面、新たな必須違反・必要な相談  → `docs/history/3.4xx.md`
- セルのシートを 3 段階に（2026-10-14、3.670.0）: 問題を確認 → 自分で勤務を選ぶ（即反映しない）→ 影響を確認して適用、手動固定は説明つきで分ける、上部の札は自動作成と評価に分ける  → `docs/history/3.4xx.md`
- どの日の不足を直す案かと別の日への影響（2026-10-14、3.669.0）: 勤務の人員シートは日を選んでから、別の日への影響・回数の変化・適用後の「変更を反映しました」、休の過剰は設定の意味を先に  → `docs/history/3.4xx.md`
- 勤務変更案を「今回の問題がどう変わるか」から（2026-10-14、3.668.0）: 見ている問題を変える案だけを主一覧に・確認の画面・追加候補日、フラグを設定画面に、c3n 壁の短縮停止は 16 対で未決（既定不変）  → `docs/history/3.4xx.md`
- 60 秒の損失を後半処理で取り返す案（2026-10-14、3.667.0）: 余った時間を玉突きの深い探索へ回す案を 16 対で否決（候補切れで時間を使い切れない）  → `docs/history/3.4xx.md`
- 時間モデルの残り（2026-10-13、3.666.0）: 保存完了・玉突きの全体時間のログ、60 s 対 300 s（60 s が約 1% 悪い・必須同じ）、通常閾値の割合 0.5（未決）  → `docs/history/3.4xx.md`
- 違反セルの半自動修正仕様の第 3 段（2026-10-13、3.665.0）: セルのシートの問題の選択、相談の回答（セッション内・案が変わると回答待ち）、月の境目は未評価と明示  → `docs/history/3.4xx.md`
- 違反セルの半自動修正仕様の第 2 段（2026-10-13、3.664.0）: 変更の一覧に勤務名と希望への影響、ボタン名（この変更を適用／相談事項に登録／今は変更しない）、必須違反 0 件の通知と「探索時間」を最新の仕様の文言へ  → `docs/history/3.4xx.md`
- 違反セルの半自動修正仕様の第 1 段（2026-10-13、3.663.0）: 違反の同一性（種類＋職員＋日付／種類＋シフト＋日付）で、別の場所に必須違反を新たに生む改善案を出さない・当てない、当てたあとの内訳（解消・新たに生じた問題・残り）  → `docs/history/3.4xx.md`
- main 3.660.0 の外部レビューの是正（2026-10-13、3.662.0）: 読み込みの型検証（表・配列・整数・勤務番号の範囲）、職員 ID の上限、完了通知の「配布できます」廃止、停滞ログの時刻の分離、説明と文言の校正  → `docs/history/3.4xx.md`
- 判断支援 UX 仕様の第 2 段（2026-10-13、3.661.0）: ［保留する］（相談と別に数える・違反は減らさない）、改善案が見つからない理由の 4 区分（矛盾・時間不足・固定条件・探索範囲内）  → `docs/history/3.4xx.md`
- 判断支援 UX 仕様の第 1 段（2026-10-13、3.660.0）: 不変の職員 ID と相談の ID 照合（UX-02）、通知の決まり文句（§9）、配布前確認の 1 画面（§10）  → `docs/history/3.4xx.md`
- 外部レビューの高優先の不具合 5 件（2026-10-13、3.659.1）: 全体保存が古い評価情報を書き戻す、型不正の条件・上下限を空として読む、休みなしのグループ追加で担当全 OFF、唯一の担当シフトを削除できる、指紋の一覧の境界  → `docs/history/3.4xx.md`
- 既定の時間を探索 60 秒＋後処理 10 秒＋玉突き 10 秒へ（2026-10-13、3.659.0、利用者指示）: budgetSec 300→60、後処理 10 秒と玉突き 10 秒を予算の外に足す（旧: 予算の 1/12・8〜25 秒を中から、玉突き上限 6 秒）  → `docs/history/3.4xx.md`
- 全画面の文言を 5W2H と人への配慮で照合（2026-10-13、3.659.0、利用者「規則を足して全文言を直す」＋提案「日本人の現場思考」）: 12 範囲・58 ファイル。失敗の文に直す場所、日付は DayText、英字を画面から外す、氏名＋さん、希望は「調整する／外す」、人員不足はシフト名。ついでに読み上げ・内訳の誤り 5 件  → `docs/history/3.4xx.md`
- 実機の割り当てられない・ダイアログの重なり・題の折り返しと回避の並びの説明（2026-10-10、3.658.0）: 板挟みの選択肢をシートの固定部へ、縦積みのダイアログは取消も同じ列に（5 つ）、入れ替えの題から件数、色の題は長い名前だけ小さい字。希望だけでできた回避の並び（`wishAvoidRuns`）を設定の案内と残存分析の「もう直せない」へ。5W2H の規則を文書に（照合は次の版）  → `docs/history/3.4xx.md`
- 玉突きパイプラインの「採れたら探し直す」を既定 ON・「必須の違反からも始める」を設定に（2026-10-10、3.657.0、利用者「推奨で」「フラグはUIで表す」）: 従来の常時フル対パイプラインの勝12/負100 を分解（1 巡だけ・予察が狭い・必須の起点が無い）。決定的 LoopBench で探し直し 勝45/負0・必須増 0（既定 ON）。必須の焦点は `Origin.HARD` が c1 の起点を混ぜていた不具合を直して測り直し、新しい既定の上で 勝17/負10・必須増 1＝不合格（既定 OFF・設定で選べる）  → `docs/history/3.4xx.md`
- 全画面の design-review と修正（2026-10-10、3.657.0、`/design-review ALL`・「残件対応する」）: 8 範囲、MUST 8（順に見るが人員不足だけで無反応・UD の下部ナビ・ちら見の希望モード・拡張希望の×・制約の削除の無言・コントラスト 3 件）と SHOULD 約 55 を直した。結果の文が検査の文に上書きされる型は opNotice へ。Q 10 件は利用者の判断待ち  → `docs/history/3.4xx.md`
- 設定「玉突きで直す」の design-review（2026-10-10、3.656.1、画面のみ・出力不変）: 折り返した 4 択の行の間に 8dp（A3、行が隙間 0 で接していた）。選択肢の語の型・上限秒の表示・既定 ON を画面に出す例外は利用者の判断待ち
- 玉突き連鎖パイプラインを既定 ON（2026-10-10、3.656.0、利用者提示の仕様＋「実装し条件を満たせば既定ON」）: 索引→浅い予察（深さ 2）→中段（深さ 4）→当たりの起点だけ深く探す→採用キュー。最終の違反起点修復の後に置く（C1 共同 LNS の直後は必須増 1 で不合格）。決定的 LoopBench 138 対（出荷構成）で OFF 比 勝76/負0・必須増 0、A1＝従来の常時フルが空振りの 24 対で玉突きの所要 2,052→122 ms・必須の差 0、A3＝浅い予察だけ比 勝67/負0。従来の常時フルは品質が上（勝12/負100）で設定に残す。設定「玉突きで直す」は 4 択・既定「見込みのある所だけ」。C# 同日（Kotlin と出力一致）。実時間 15 対は未決（必須の差 0。golden の悪化は探索の揺れ＝パイプラインは 6 回とも空振り・約 0.1 秒）。ベンチの腕が範囲を月全体に固定していた罠は lessons #62
- 後処理の途中の最良を最終番兵の候補に＝測って採らない（2026-10-10、測定のみ・コード不変、利用者の選択）: 構造床>0 で走行 keep-best を切っている盤面向けに、段の後の最良を最終番兵の候補へ足す案（経路不変）。決定的 LoopBench 既定 138・許容ON 230・じっくり 138 対すべて同一、よく探索した床>0 の 36 本も発火 0（最終盤面が全段の最良）＝便益が測れず入れない。3.645.0 の差し戻しは「じっくり」の許容と床>0 の組合せで、玉突きの改善は救えない形。3.653.0 の main の ✗ はブランチ側の取り消された CI（再実行で緑、lessons §4）
- 停止理由・上限到達のログの是正（2026-10-10、3.655.0、出力不変、外部レビュー No.3/4/8/9/12/15/16、利用者「推奨で」）: C1・個人回数の共同 LNS は打ち切りの理由をループを抜けた時点で決める（旧: 正式な再検査の後に評価し直した）、c1 がすべて構造下限なら目標は「対象なし」、個人回数は「対象 N/M人」。C1 玉突き連鎖の終了理由を「起点なし／巡上限／改善なし・未巡回N箇所／改善なし」に分け、起点ごとの評価上限に当たった数を出す。範囲研磨は試行上限の打ち切りを「日割当上限」、パス数を「パスN/3」。C# は手M/手F のピン却下の計数（Kotlin 3.475.0）と残存理由の併記（3.491.0）の同期漏れも直した
- 共同 LNS の複数セル手を交換後の行で禁止の並び判定（2026-10-10、3.654.0、外部レビュー No.2＋反例、利用者「推奨で」）: C1 共同 LNS は「j に x」で goal ごと捨てていたのを、行 i を j だけ変える手に限定し自己日交換は 2 セルを当てた行で判定、本人・同日の移送の重複を除去。個人回数の共同 LNS は交換後の盤面で判定。決定論 LoopBench 46 ケース×2 seed で勝41/負4・p<0.0001・必須増 0（充足不能で必須減）＝採用
- 拡張希望の違反を必須の族へ（2026-10-09、3.653.0、HF77 明示指示「拡張希望の重みは希望シフトと同じにする」）: `extWish`（HARD・重み 8000＝pref と同じ）を `MirrorKeys` の末尾に追加（21 族・HARD 6）。チェッカー・Evaluator・Δ・HardDelta・C++（評価器・SaChunk・違反セル）・native-parity（flat に禁止の 3 つ組）・期待値 5 ファイル。必須を部分的に数える近道 9 か所を更新（lessons #60）、RSI の重点は HARD の末尾。拡張希望の無いデータは出力不変、ある盤面では入力の違反を探索が解消する
- 組み合わせ探索の取り逃し監査（2026-10-09、3.652.0、測定のみ）: `MissedMoveProbe`（最終盤面で 1 セル・同日 2 人・2 人の連続 2〜7 日を全列挙し betterReport で改善手を数える）。実データ 5 件で必須を減らす手は無し。既定の 300 秒で残る手は重み付き −1〜−24（3/5 件）、120 秒・決定論モードでは多く残る＝判定は本番の入口・既定の予算で（lessons #61）。末尾の全列挙降下は要判断
- 外部レビューの残件（2026-10-09、3.651.0、出力不変、利用者「残件対応する」）: 集計の見出し行を本体の上端に留める（`StickyHeaderTable`、backlog #47 解消）、拡張希望の×を線で描いて記号に接しない（`ExtCrossMark`、凡例の抜けも）、改名に相談の対象を追従（`followRenameInConsults`、元に戻す・やり直すも）。違反の日ナビの状態表示は 3.648.0 で済み
- 外部レビュー 4 本の推奨の実装（2026-10-09、3.650.0、出力不変、利用者「推奨で」）: シフト集計は既定で展開へ戻し（3.514.0）、希望の 2 操作を集計の上へ。凡例の短い一覧を枠と印に（`CompactMarkLegend`）。編集タブの入力途中の選択を名前・記号で追い、期間が変われば日を消す（`EditPick`）。相談の入れ替えの案に盤面と設定の指紋（古ければ探し直し）、同じ名前の職員は並びが同じ間だけ開く（`rosterKey`）。3.649.0 の通しの測定 15/15 一致を追記
- ブロック巡回交換の事前フィルタを必須の正味差分で判定（2026-10-09、3.649.0、外部レビューの反例）: c3n だけを見るフィルタが c3w 減で必須が減る交換を捨てていた（「ON/OFF で採用結果は変わらない」は c3w 以前の測定）。`HardDelta` の厳密な正味差分に置換、実データ 5 件×7 盤面で最終盤面 35/35 一致、反例だけ新が勝つ。lessons #60
- 勤務表タブの縦配置の見直し（2026-10-09、3.648.0、画面のみ、利用者レビュー）: グリッドの上を道具カード 1 枚 `ScheduleToolsCard`（必須・フィルタのチップ・シフトの色チップ＋［凡例 ▸］シート＋検索、約 340dp→140dp）、シフト集計は既定で折りたたみ（3.514.0 を反転）、希望の 2 操作を 1 行、左上の範囲は見えている列（`visibleDayRange`）、週送りはグリッドが画面外なら戻してから動く（`revealGrid`）。集計ヘッダ固定は backlog #47
- グループ内の回数のばらつき（fair）の重み 2→5（2026-10-09、3.647.0、HF77 明示指示・1 件）: `MirrorKeys.weights`・評価器・Δ・destroy-repair・C++・期待値 5 ファイル・文書を同時更新。副産物＝C++ `weeklyMarginalN`/`fairMarginalN` が 3.522.0 から重みなし（候補順位の乖離、番兵の死角）だったのを Kotlin と同じ重み付きへ。2 人群の covO 退避 1 セルは fair 2 件(10) と同点で採られなくなる（テスト 5 本の前提を直した）
- 外部レビュー 20 項目の照合と是正＋実機報告 2 件（2026-10-09、3.646.0、出力不変）: 正しい 16・部分的 3・誤り 0。相談の対象を氏名・実日付で（B01/B02/T01）、保存済み行を独立表示（B03）、着地に日を運ぶ・寄せる・戻る（L01〜L05）、入力破棄の確認・入力途中の保持・区分ごとのスクロール・新規の対象月は来月（U01〜U04）、必須枠が×とバッジを隠す描画順、セルのシートの直し方は締切 3 秒。判断待ち Q01/Q03、T02/T03。
- 希望タブの拡張希望と design-review の A1（2026-10-09、3.645.2、出力不変、実機報告「モニカの希望がおかしい」＋`/design-review`）: セル編集シートの希望タブが拡張希望だけの日を「未登録」と言った → `wishTabLine` が `ui.extBanned` を読む。「相談中」は無効ボタンでなく `MagiTagChip` の札（A1 コントラスト）。
- 画面の design-review の是正 3 件（2026-10-09、3.645.1、出力不変、ユーザー「残件対応する」）: 「相談してから決める」は積んだら「相談中」（`ConsultButton`・`isConsulted`＝シートの下で Snackbar が見えない）、入れ替えの一覧の第 2 の操作を主ボタンの下へ、「マス」→「セル」・「入替」→「入れ替え」。残りは利用者側（実機確認・Windows の判断 2 件・計測）。
- 思考誘導 UX 仕様の残件 5＝相談してから決める（2026-10-09、3.645.0、出力不変、ユーザー決定「セッション内の相談一覧」「表示だけ・止めない」）: `ConsultItem`／`ui/ConsultList.kt`（希望の行・なおし方の候補・入替の一覧・1 手・つくる前の確認から対象と検討内容を積む）、主カードの「未確認事項 N 件（相談中）」と `ConsultCard`。state 非保存、完成の見出しと CSV 書き出しは不変（D10）。
- 思考誘導 UX 仕様の残件 1〜4（2026-10-09、3.644.0、出力不変、ユーザー提示「MAGI 思考誘導UX仕様（改修提案、参照実装 a80b550）」）: 月変更で消える通常希望を数えて確認（`MonthMovePlan.droppedWishes`・`monthMoveKeepNote`）、複数人の入替の適用前プレビュー（`prepareShortageChainFix`→`ChainFixPreview`→`applyChainPreview`）、CSV 保存済みの印を書き出した文字列に結ぶ（`CsvCtx`、違えば「今の内容と違います」）、つくる前の確認のセルを持たない行から入力箇所へ着地（`ui/EditLanding.kt` の `needShift`・`countCell`・`landingForProofCore`）。残件 5（相談・保留）はユーザー決定待ち＝backlog #45。
- 根拠の精度と測定の精度（2026-10-08、3.643.0、既定不変、ユーザー「根拠の精度と測定の精度に課題をあなたが新しい解決する」）: 壁判定の段階化（証明相当 `allBlockedCertified` と経験的な壁の 1 手探索による反証 `c3nWallRefutedByOneMove`、`PolishGate.c3nWallDeepCheck` 既定 OFF）、`HandleOptimizeBench` の反復 `MAGI_HO_REPEATS` と `tools/loop/ho_stats.py`（揺れ・95% 区間・決定／未決。120 s・20 対で既定 対 HEAD 相当は未決）。停滞脱出仕様を `docs/stall_escape.md` へ一本化（優秀の定義＝5 条件＋第三者検証、引き渡し版は削除）。`docs/history/3.4xx.md` 先頭。画面: 探索の終わり方の説明（`StopSummary`→`StopExplanation`、ホームの結果カード 1 行＋ログ `StopSummary`、出力不変）。第三者の検証で矛盾 2 件（§9 `totalApplied`・E10 の層）→修正と層 B／C の欠落の追記→再検証 合格（矛盾 0、軽微 10 件を反映）。手順②: 経験的な c3n 壁のフィクスチャ探索（合成 174 盤面）＝壁条件に乗る壁は希望由来だけ、`deep` の計測は実機の盤面待ち。手順③: 適応閾値（`adaptiveStall`、既定 OFF）は blocked_covu・120 s・10 対で既定が良い（weighted −412、区間 [−744, −74]）＝否決。再現性はユーザー決定で「ここまで」（仮想時計・回数予算は入れない）。思考誘導 UX の見直し: 準備チェックの希望の行を数だけに、「AIの解決提案」カードを主カードへ統合、直したあとの結果行、完成表現を「必須条件を満たしました」に、月の移動の確認（引き継ぐ／消える）。
- 停滞脱出の外部レビュー残件と画面監査 7 項目（2026-10-08、3.642.0、ユーザー「残件対応する」）: `C3nWallProof`（生存盤面と最良の報告を同じ参照で結ぶ）・`PolishGate.lateOpStopPropagation`（試行中の停止確認。入口は残る）・統合段の量的評価・`guidedFixTarget`/`landingFor`/`applyShortageChainFix`。計測（sample_v6・5 seed）は既定を維持（weighted 差の平均 −49、事前基準は満たさず）。`docs/history/3.4xx.md` 先頭。
- c3n 壁の短縮の機会損失を測定（2026-10-08、3.641.0、出力不変、ユーザー「残件対応する」）: 測定スイッチ `PolishGate.c3nWallShortStall`（既定 true）と `HandleOptimizeBench` の腕 `c3nwall`（seed 固定）。sample_v6×5 seed: 120 s は短縮なし 2 勝 3 敗・平均 +8.8（時間 46→119 s）＝損失なし、60 s は短縮なし 5 勝 0 敗・平均 −180（−1.6%、時間 33→58 s）＝短縮が ALNS 段を含む残り 25 s を切っている、300 s は短縮なし 4 勝 1 敗・平均 −82（−0.7%、時間 127→299 s、p≈0.19）。既定は据え置き（時間と品質の交換＝業務判断）。プラトー探索 C は PrePostDescent（2026-10-07 否決）と同一で仕様 §11 を訂正。  → `docs/history/3.4xx.md`
- 停滞脱出仕様の残件（2026-10-08、3.640.0、出力不変、ユーザー「残件対応する」）: `V6FinalPort.WatchdogBest`（bestHard/最終改善時刻/非 covU 内訳/世代/停滞ラッチを 1 クラスへ、`observe`/`fire`）と `RsiFocusSelection.avoidSets`（avoid＝HARD のみ＋静的 covU 床、focusAvoid＝＋冷却）を閉包から切り出し、§13 の未固定 2 点（ラッチは改善で降りる・SOFT は avoid に入らない）を `StallEscapeSpecTest` に 3 件追加。`EarlyStop` 行に「発火種別=通常／猶予上書き」（敵対検証の未集計項目を実機ログから読めるように）。C# 同日同期。  → `docs/history/3.4xx.md`
- 停滞脱出仕様を実行可能に（2026-10-08、3.639.0、出力不変、ユーザー「停滞脱出仕様を実装する」）: `V6FinalPort.watchdogBudget`（minRun/postReserve/searchWindow/stall/stallHard/phaseGrace を 1 つの純関数へ）・`progressImproved`（1e-6 の監視判定）を抽出し本体から使う。`StallEscapeSpecTest`（13 件）が §5.1 の 300 s/60 s/20 s の表・発火式の厳密境界・STOP_CONFIRM 5 s・HF63 の 13 族・effortIters の式・層 B の量子・既定値を固定。C# 同日同期（`WatchdogBudgetOf`/`ProgressImproved`、`StallEscapeSpecTest.cs`）。  → `docs/history/3.4xx.md`
- 停滞脱出の台帳を実装準拠仕様へ書き直し（2026-10-08、文書のみ、ユーザー「仕様書を書き直してください」）: 外部仕様書の構造（層の地図・比較と監視の契約・保証の範囲・根拠の強さ・状態の寿命・レビュー手順）に、台帳の数値表・据え置き決定・否決一覧・ログ行を統合。main 4af3456 の条件式で全項目を照合。  → `docs/history/3.4xx.md`
- 停滞脱出の台帳の是正 4 点（2026-10-08、文書のみ）: 外部の実装準拠仕様書との照合で、`stagnationFired` は改善で降りる（永続ラッチではない、3.346.0）・進捗監視だけ weightedScore に 1e-6 許容差・c3n 壁は限定した手の壁判定で全空間の証明ではない・「品質への影響なし」は保持済み最良の非悪化の意味。HF63 の追跡 13 族と N4 の avoid が HARD のみである点を明記。  → `docs/history/3.4xx.md`
- 停滞脱出の台帳 `docs/stall_escape.md`（2026-10-08、文書のみ・コード不変）: ウォッチドッグ（閾値の式と 300 s/60 s の実値・頭打ちの床 4 種・発火後）、適応ポートフォリオの再配属、RSI の focus/HF63/N4、SA ラダー、後処理の打ち切り、否決一覧を層 A〜E で 1 か所へ。README 目次・topics・portfolio・backlog から参照。  → `docs/history/3.4xx.md`
- 許容 6% の無害化②を外すスイッチを設定タブへ（2026-10-08、3.638.0、出力不変）: 「容認 6% で重い違反の増加も許す」（じっくりでだけ有効・既定 OFF）。`UiState.aptFairToleranceUnguarded`＝`!AptFairPolish.heavySoftGuard`、`PolishGate.snapshot` に載せ背景実行へ引き継ぐ。C# 同日同期。  → `docs/history/3.4xx.md`
- 許容 6% の無害化②を外す測定（2026-10-08、3.637.0、出力不変）: 測定スイッチ `AptFairPolish.heavySoftGuard`・腕 `aptfairtolunguarded`。画面の盤面では②なしは他の人の apt に予算を使い山本 Dﾃ は 7→8＝目的に当たらない。起点指定の玉突き DFS なら 6% 予算内で 7→3（+0.8%）。230 ペアのベンチは旧 114／新 75（p=0.0056）・速度 −55%＝②は外さない。  → `docs/history/3.4xx.md`
- 実機ログ精査の表示 2 件（2026-10-08、3.636.0）: 設定ミス/案内のログ 12 件打ち切りに「ほか N 件」、追加精製の段名に「追加精製」前置。精査の結論（必須 8 件は設定由来、Aｱ 目標合計超過・Cｱ 上限不足・桒澤 32 日・休の必要人数 0）。  → `docs/history/3.4xx.md`
- 外部レビュー（main 7df54d8 対象）UI-02・UI-03・CFG-01（2026-10-08、3.635.0）: 担当可否はどの群も担当できるシフトを 1 つ以上残す（単一セル・行一括・列一括、休が無い設定でも拒否して理由を表示）、他の案の適用は表示の盤面も同時に差し替え・再計算失敗は未確認と明示、盤面ジョブ開始で走っている違反チェックを失効。  → `docs/history/3.4xx.md`
- 外部レビュー（main d5a166e 対象）DATA-01〜03・NEW-01〜06・UI-01・OLD-01〜03（2026-10-08、3.634.0）: 拡張希望の職員番号・記号・日付の追従（Ws1Ops）、保存可否を既存の件との和集合で日ごとに判定、登録結果を UiState で画面へ返し失敗時は選択を残す。StateFingerprint に項目の識別子・件数・拡張希望。CSV 取込（職員・希望・個人レンジ）は同姓同名を自動で先頭へ割り当てない。SoftCascade の重複登録は採った後・停止判定を候補生成の内側へ・変更セル上限は和集合。玉突きは branching[0] を最初の展開に使い「採用深さ」は手数。  → `docs/history/3.4xx.md`
- 勤務表のセルに拡張希望の印（2026-10-07、3.633.0）: 上端中央に「×」。指定日は控えめな色、割当が禁止のシフトなら違反色。読み上げに「拡張希望（夜・準以外）」「拡張希望の違反」。表のソースは `ExtWishRules.bannedByCell`（UiState.extBanned）。  → `docs/history/3.4xx.md`
- 拡張希望の入力画面（2026-10-07、3.632.0、ユーザー「拡張希望の入力画面を作る」）: 希望シフト登録カードの選択日パネルに「希望にする／以外にする」。以外にするは禁止シフトを複数選び `addExtWishForDays`→保存規則。カレンダーの日に「×記号」、職員ごとの一覧チップ（タップで日を選び直す・✕で削除）。  → `docs/history/3.4xx.md`
- 外部レビュー OPT-01〜04・OBS-01（2026-10-07、3.631.0 に同梱、既定OFFの経路）: 局所降下の事前判定と玉突きの最良記録を正式比較（必須件数→重み付き→総数、`DeltaEvaluator.reportKey`）に揃えた（必須 1→1 で族が入れ替わる改善を捨てていた）。玉突きは評価上限・中断を評価 1 回ごとに確認。停滞時研磨注入は最初の例外の種類と発生箇所をログへ。  → `docs/history/3.4xx.md`
- 拡張希望の禁止を最適化器が置かない（2026-10-07、3.631.0、仕様第 8 節）: Kotlin 約 45 ファイルの候補生成＋C++ 全チャンク（ABI 8）＋最終番兵（段の除外・戻した盤面）。敵対テスト（禁止なしで置いたセルを禁止にして全アルゴリズム・全フラグ ON）で漏れ 0、禁止なしはホスト 1143 件・ネイティブ照合とも不変。  → `docs/history/3.4xx.md`
- 拡張希望（2026-10-07、3.630.0、ユーザー提示の仕様「希望シフトの拡張」）: 基本希望の否定形（職員×日の集合×禁止シフトの集合）。データ・JSON・保存規則（排他・落とすもの）・禁止表・違反件数 `extWishCells`（採点外）・希望保存の拒否。候補生成への適用（`mayPlaceAt`）と C++・画面は次段。  → `docs/history/3.4xx.md`
- 全SOFT玉突き連鎖 `SoftCascadePolish`（2026-10-07、3.629.0、既定OFF・融合仕様による）: 15 ソフト族の違反を場所マップの座標のまま玉にし、8 種の手（起点の族を減らすものだけ）でビーム探索（深さ4・幅16・正式評価500回・500ms）。全節で必須 5 族を族ごとに root 以下、ソフト負債 ≤120。置き場所は人員過剰の退避の直後・HF70 の前。前案 `SoftEjectionChain`（PR #302 初版、修復の直後）は置き換え。  → `docs/history/3.4xx.md`
- 後処理前の局所降下 PrePostDescent を否決（2026-10-07、既定OFF・測定用に残す）: 決定的 LoopBench 46×3 で 91勝23敗24分（HARD 増1・減10）だが、実時間 handleOptimize 15 組で ON 4勝11敗・平均 weighted +110。決定的の勝ちは後処理が回数上限で切られる条件だけの効果で、本番では後処理と探索末尾が同じ 1 手を拾う。  → `docs/history/3.4xx.md`
- 玉突きの枝を 2,1,1… に絞り起点ごとの評価上限を外す `C1EjectionChainPolish.defaultNarrow`（2026-10-07、既定OFF・測定中）: 深さ 8 の展開が 233→15 回（月全体で起点 1 つ約 205 万→13 万評価）。停滞盤面 16 枚×評価 17 万/100 万で 19勝6敗7分・Δweighted 合計 −4,162 vs −3,700・HARD 増 0、起点の調べ切り 33→115〜183。大負け 1（blocked_covu-1 −642→−228）。  → `docs/history/3.4xx.md`
- シフト集計の「計（期間）」の文字欠け（2026-10-07、3.628.3、ユーザー提示のスクショ、表示のみ）: 3.628.1 の Row 化でも「32 ▲1 ›」は 48dp に収まらない。合計と ▲▼ 日数を 2 段に分ける。  → `docs/history/3.4xx.md`
- 外部レビュー（09ecb68 対象）の残り（2026-10-07）: 玉突き連鎖の評価上限が起点づくりの評価を数えず、上限 1 でも 10 回評価していた→起点づくりの内側でも `out()`（上限・中断・時間切れ）を判定。PrePostDescent の同一職員 2 日交換に `mayPlace` 検査が無い件は測定中の作業者へ修正依頼。差し込みの例外・時計分離・所要時間は 3.628.2 で対応済み。  → `docs/history/3.4xx.md`
- 外部レビュー B1〜B4・R2 の対応（2026-10-07、3.628.2）: B1 手動保存が `exportJson()` を Default スレッドで呼び盤面・状態を別スレッドで読んでいた→`exportJsonDeferred()` で main で固定し文字列化だけ別スレッド。B2 背景実行が `PolishGate.restore(gates)` したまま戻さず、プロセス再起動後は画面（既定）と実際の設定がずれ得た→終了時に投入前の値へ戻す。B3/B4 差し込みの例外を不採用と区別して数え、所要時間を集計。R2 差し込みの再試行待ちを最終試行時刻で数え、`lastGlobalImproveMs` は改善時だけ更新。R1/R3 は既定 OFF・非表示のため測定項目として保留。  → `docs/history/3.4xx.md`
- シフト集計の押せるセルの文字欠け（2026-10-07、3.628.1、ユーザー提示のスクショ、表示のみ）: `TallyBox` が数字の枠を右 10dp 削り「›」を重ねていたため、計の 111・30 などが 48dp セルで左右に欠けた。数字と「›」を 1 行の Row で中央に並べる。  → `docs/history/3.4xx.md`
- 確実に悪化方向の設定 3 つを非表示（2026-10-07、3.628.0、ユーザー「確実にデメリットがあるものは非表示にする」＋選択）: 途中で仕上げを試す（15組 重み+1291）・禁止の並びも直す（24組 7勝14敗・採用4/1956）・玉突き範囲「前後7日」（必須増1、BY_KIND が上位互換）。じっくりは今回は残す。既定値・エンジンは不変。C# は禁止の並びの切替を Collapsed（main ea2a383）。  → `docs/history/3.4xx.md`
- 同点の盤面を足場にした停滞脱出（プラトー探索）を机上で否決（2026-10-07、測定のみ・コード不変）: 停滞盤面 15 枚（5 データ×3 seed）×3 反復、同じ盤面・同じ持ち時間で比較。B（同点プール→後処理）は A（後処理を直接）に 10s 3勝27敗15分・30s 9勝19敗17分。同点手は 0〜1.1%（sept2026 は 0）でプールは距離 1〜2 にとどまる。一方で停滞盤面に 1 マスの改善手が 3〜49 残る＝C（同点以上を受理する歩き→後処理）は A に 32-10／36-6、平均差は約 18 点。C は別案として測る。  → `docs/history/3.4xx.md`
- 実機 8 回の設定比較と差し込みログの二重出力の修正（2026-10-07、3.627.0 実機・記録＋診断ログのみ）: 11名・300s・必須は全回9。合計 358〜376（幅18）で、差し込み ON 5回平均 368.8／OFF 3回 364.7、玉突きあり/なしも差なし、全試験スイッチ OFF の #16 が 358 で最良＝どのスイッチも便益なし・既定OFF維持。差し込みの後処理ログが globalLogs を上書きし最終診断に二重に出て「予算超過…短縮」警告が紛れていた→差し替えない（盤面不変）。  → `docs/history/3.4xx.md`
- 未取り込みブランチ 5 本の検証と棲み分け文書の取り込み（2026-10-07、ユーザー「推奨で」、文書のみ）: c1-movea-repair・search-strength・smart-optimal-merge・shiftgfork-rsmp2p は内容が main に入っている。x8ygvy は covU 壁の早期終了（3.361.0 で却下）とその周辺・取り込み済みテスト・古い速度ツールで不要、`docs/kotlin_cpp_split.md` だけ数え直して追加（C++ 2,695 行・Kotlin v6 29,108 行・JNI 17・番兵 11・check 207）。  → `docs/history/3.4xx.md`
- 停滞時の後処理差し込み（2026-10-07、ユーザー「停滞脱出を賢く修正する」、既定OFFのまま・設定タブに試験スイッチ）: 全体最良が max(20s,予算/10) 改善しないとき最良の写しへ後処理を6s上限で当て、良ければ全体最良へ（1実行3回まで）。採用 32/43 回・その場では重み −66〜−1413 だが、最終は 8勝7敗・重み合計 +1291（悪化）・HARD 退行0＝便益が測れず既定OFF。PORTFOLIO は予算211s以上でしか選ばれないため 240s/300s で測定。  → `docs/history/3.4xx.md`
- シートの「直し方を探す」に多段の玉突き連鎖（2026-10-07、3.627.0、ユーザー「玉突き連鎖する」＝山本 Dﾃ 超過のシートで「玉突きの範囲では見つからず」と出た）: 旧 CHAIN は 1 人の下限割れを最大 3 コマ補充するだけ。`FixSuggester.ejection()` で `C1EjectionChainPolish`（全族起点）を走らせ、盤面差分を `FixKind.EJECT` の 1 手に。採用規則は他の手と同じ `tryOps`。  → `docs/history/3.4xx.md`
- 玉突きの探す範囲の既定を「必須は月全体・他は前後7日」（案A）へ（2026-10-07、3.626.0、ユーザー「推奨で」）: 決定的 LoopBench 46×3 で全体版比 74勝25敗39分・必須増0（穴版は必須増1）。実データ4×3×60s は OFF 比 8-4（p≈0.4・非有意）＝玉突き本体は既定OFFのまま。  → `docs/history/3.4xx.md`
- 玉突きの探す範囲を設定タブで選べるように（2026-10-07、3.625.0、ユーザー「フラグをUIで」）: 月全体（既定）／必須は月全体・他は前後7日（案A）／前後7日。`C1EjectionChainPolish.defaultHoleFocus/defaultHoleSoftOnly` を snapshot/restore に載せる。採否は測定中。  → `docs/history/3.4xx.md`
- 停滞の早期終了の閾値（通常 270s＝normalStallFraction 0.9）を据え置き（2026-10-07、ユーザー「推奨で」、コード不変）: 外部提案「60〜90s に短縮し浮いた時間を玉突き/後処理へ」は前提不成立＝早期終了の余りは後処理へ回らない（予約25s固定、3.341.1 で回す案は否決）・玉突きは 6s 上限・実機#1 は 113→193s の空白 80s で 60s 閾値だと最終改善を失う。0.5 割合は 3.423.0 で 6勝3敗・非有意。  → `docs/history/3.4xx.md`
- 玉突き連鎖の上限時間 3→6 秒と設定タブの秒数選択（2026-10-07、3.624.0、ユーザー明示指示「3秒を6秒に」）: `PolishGate.ejectionChainMaxMillis`（既定 6000、背景実行の snapshot/restore に載せる）。5 か所の `coerceIn(0, 3000)` を置換。残り時間の 1/4 上限は据え置き。  → `docs/history/3.4xx.md`
- 勤務表の「必須 N ▼」と必須違反の一覧（2026-10-06、3.623.0、OOUI 第2段・ユーザー「第2段に進む」）: 巡回の `hardViolationItems` を一覧に。希望どうしの衝突の行は「希望のまま」札で後ろへ。行を押すと巡回と同じ移動でセルシート。  → `docs/history/3.4xx.md`
- ホームの OOUI 第1段（2026-10-06、3.622.0、ユーザー「推奨で」、S6 は再導入しない）: 主ボタンに最初の対象（氏名 日付）を添える／「必須 残りN件（うち希望のまま M件）」／ぶつかっている希望の行に「希望のまま」札。  → `docs/history/3.4xx.md`
- 玉突き連鎖の置き場所の決定測定（2026-10-06、測定のみ・既定OFFのまま）: 決定的 LoopBench 46ケース×3 で「最終の違反起点修復の後」が 99勝0敗・必須増0（他4案は必須増あり）。ただし実機と同じ時間制限の handleOptimize（実データ4件×3×60秒）では採用 2/12 回・重み −2/−20 のみ＝便益が測れず既定 OFF。  → `docs/history/3.4xx.md`
- 差分評価の c1・c3 窓をビット演算に（2026-10-06、出力不変）: プロファイルで玉突き連鎖の時間の約7割が `DeltaEvaluator.c1Local/c3Local`。職員×シフトの勤務日ビットと popcount で窓を数える。fixture 4件で盤面ハッシュ一致・玉突き連鎖 0〜26% 短縮。  → `docs/history/3.4xx.md`
- 玉突き連鎖の置き場所と深さの見直し（2026-10-06、測定のみ・既定OFFのまま）: 深さ3（c1起点 4勝7敗1分・全族起点 6勝6敗）、後処理前段の必須起点（6勝5敗1分・採用は c1 だけで必須は減らず）。どれも効果を測れず。  → `docs/history/3.4xx.md`
- 必須の内訳ログ（2026-10-06、3.621.6、仕様 M2・出力不変・C# 同期）: 「必須内訳: 必須N件 = 希望どうしのぶつかり X件 + それ以外 Y件」を残存分析の次に出す。M1/M6 は既存、M3 は測ってから、M4 は候補月 JSON 待ち。  → `docs/history/3.4xx.md`
- 玉突き「すべての違反から」対「しない」のホスト A/B（2026-10-06、測定のみ、3.621.5 の main）: fixture 4件×3 seed×60秒で 7勝5敗（sept2026 は3seedとも同一盤面＝実質1点→5勝5敗）、必須は全対同じ、時間は平均+8秒。効果なし＝既定 OFF のまま。  → `docs/history/3.4xx.md`
- 画面側の採否を上限0を外した入力と比べる・背景再開で探索設定を戻す（2026-10-06、3.621.5、ユーザー決定「推奨で」・C# 同期は前者のみ）: 実機 #9 の「前回を維持」の原因。  → `docs/history/3.4xx.md`
- 背景実行の完了時の片付けが新しい実行のファイルを消しうる（2026-10-06、3.621.4、外部レビュー）: 所有権の確認と削除を錠の中で一続きに（`RunFiles.releaseIfOwner`）。後処理タイミングの区間名を「C1共同LNS+玉突き連鎖」に。  → `docs/history/3.4xx.md`
- 「元に戻る」の全ログ再精査と残りの漏れ・診断の是正（2026-10-06、3.621.3、C# 同期）: C++ 研磨チャンク op0 と C3RunPolish の tryExtend が上限0の勤務を置けた／希望固定セルを「崩せる」と診断／上限0の注記の言い過ぎ／採用した段をログで名指し。  → `docs/history/3.4xx.md`
- PERSON_SWAP_ILS の摂動が個人上限0の勤務を置いていた（2026-10-06、3.621.2、実機ログ・C# 同期）: 同じ群の2名の1ヶ月入れ替えで mayPlace を見ていなかった。3 回の実行がすべて入力へ戻った主因。  → `docs/history/3.4xx.md`
- 上限0の勤務が残った段を捨てて探索の成果を失う不具合と、採用しなかった実行の診断ログ（2026-10-06、3.621.1、実機ログ・C# 同期）: 外した盤面を最終番兵の候補に足す。探索の開始盤面を揃える案は測って否決。  → `docs/history/3.4xx.md`
- 「もう一度つくる」→「再作成」（2026-10-05、3.621.0、ユーザー指示、表示のみ・C# 同期）: ボタン・S5 確定「希望を取り消して再作成」・つくる前の確認「再作成すると外れる」・文中の活用形すべて。  → `docs/history/3.4xx.md`
- 1行で打ち切る 55 か所の机上検証と、勤務表タブ下のバーの主ボタンの切れ（2026-10-05、3.620.3、表示のみ）: 390dp で［◀違反 k/N］［違反▶］［↶］［↷］と並ぶと「もう…」→余白 12dp・2 行折り返し。ほかは仕様どおりの省略か収まる。  → `docs/history/3.4xx.md`
- 画面文言を yomiyasu の観点で読みやすく（2026-10-05、3.620.2、ユーザー指示「全画面」、表示のみ・C# 同期）: 画面の文字列 795 件をリンター＋目視で点検し、AI っぽい書き方の 20 か所前後だけを直した（意味・言い切りの強さは不変）。  → `docs/history/3.4xx.md`
- DreamSearchEngine／ReplayWorld は採らない（2026-10-05、机上評価・未測定、ユーザー「推奨で」）: 採否が betterReport のみの山登り・候補ごとに全体 checker・既存の destroy/repair 3 手の巡回・反復ごとに trace 全体を複製＝現行 V6NativeOptimizer＋後処理の劣化版。Replay は観測済みの最良と手別成績の表で keep-best と重複。取り入れる価値は探索内トレース（手・変更セル・前後評価・棄却理由）の考え方だけ。  → `docs/history/3.4xx.md`
- 統合仕様書 v2 の良い部分を `docs/invariants.md` へ（2026-10-05、文書のみ、ユーザー指示「メリットをマージする」）: INV-01〜12↔T の対応・責務分離・既知差分。S6 確定・C2Polish 撤去済み・Dream 等の食い違いは取り込まず明記。  → `docs/history/3.4xx.md`
- S5 の改善なしの行から「証明」を外す（2026-10-05、3.620.1、机上テストで発見、表示のみ・C# 同期）。  → `docs/history/3.4xx.md`
- 個人の上限0は緩めない＝S6 を画面から外す（2026-10-05、3.620.0、ユーザー決定「画面から外すだけ」、C# 同期）: `RELAX_TRIAL_ENABLED=false`、緩和を誘う文言を設定での見直しへ。コードは残す。  → `docs/history/3.4xx.md`
- 文言見直しの design-review 対応（2026-10-05、3.619.1、表示のみ・C# 同期）: 期間の約束→期間の制約、S6 確定ボタンの説明を外す、回数表凡例を目安に統一。  → `docs/history/3.4xx.md`
- 画面の日本語の見直し（2026-10-05、3.619.0、ユーザーのレビュー P1〜P3 すべて採用、表示のみ・C# 同日同期）: 必須の約束/計算では消えない/証明つき/下限の見込み/〜のズレ/解消度ほか。「期間の制約」は決定語彙のため据え置き。  → `docs/history/3.4xx.md`
- 玉突き連鎖の入れ替え手を画面のスイッチに（2026-10-05、3.618.3、ユーザー指示、既定ON＝出力不変）: `PolishGate.ejectionChainSwapMoves`。  → `docs/history/3.4xx.md`
- 全族起点の玉突き連鎖の合成ベンチ（2026-10-05、測定のみ、既定OFFのまま）: LoopBench 決定的・46 ケース×5 seed で勝166/負9 p<0.0001・必須減9/必須増2（c33 大・充足不能）→統計ゲート不合格（必須増>0）。平均 +2.1 秒。handleOptimize 24 対では差なし。  → `docs/history/3.4xx.md`
- ドッグフーディング（main 719ead1）の照合と文言 2 件（2026-10-05、3.618.2、表示のみ）: DF-A 希望取消後に残る必須の行き先、DF-C セルシートの S6 見込みに残る必須。DF-B（セル→S6）は実装済み。  → `docs/history/3.4xx.md`
- 玉突きの試験スイッチ 2 つを 3 択ボタンに統合（2026-10-05、3.618.1、design-review SHOULD、出力不変）: 「両方ONならこちらだけ」の注記を排他の形へ。  → `docs/history/3.4xx.md`
- 全族起点の玉突き連鎖＋入れ替え手（2026-10-05、3.618.0、既定OFF＋試験スイッチ、効果は確認できず）: `PolishGate.allFamilyEjectionChain`。入れ替えなし OFF対全族 16/6/2 p=0.052 → 入れ替えあり 10/12/2 p=0.83（1回目は揺れ）。同一入口からの連鎖だけでは改善 5/24 回・計 −574。参照プローブの 47975→47933 は入れ替えで再現。外部の修復手続き・理想形仕様は正式評価で covU を増やす（アリフ型 必須5→7、モニカ型 5→6）。  → `docs/history/3.4xx.md`
- 月全体の玉突き連鎖 C1EjectionChainPolish v1/v2（2026-10-04、3.617.0、既定OFF＋試験スイッチ）: OFF対v2 11勝11敗2分 p=1.0 ΔW%+0.68、c1 +32、採用3/24回・c1を減らした採用0。負けは連鎖前に分岐済み（経路のばらつき）。OFF対v1 11/10/3 p=1.0 ΔW%−0.16。  → `docs/history/3.4xx.md`
- 最終番兵で個人上限0（希望でない）の勤務を含む段を外す（2026-10-04、ユーザー承認、通常は出力不変）: `excludeCapZeroStages`＋W ログ、RestZeroWindowLns を mayPlace へ、「手で置いた」文言を中立化。  → `docs/history/3.4xx.md`
- 入口で個人上限0のセルを外して必須が増えたことを知らせる（2026-10-04、表示・ログのみ、ユーザー承認、出力不変）: CapZero ログに必須 a→b、再実行の「前回を維持」に戻れない理由を1文。  → `docs/history/3.4xx.md`
- 設定の探索スイッチ群を「探索の強さ」2 択に統合（2026-10-04、3.615.0、ユーザー決定、ふつう＝出力不変）: じっくり＝combineExhaustPairs/wideC3nBreakDays/countChainPolish/aptFairSoftTolerance を ON。案 A 常時 ON、E0・extraRefine は常時 OFF。  → `docs/history/3.4xx.md`
- C1 研磨・手A の禁止連続修復 `PolishGate.c1MoveARepair`（2026-10-04、既定 OFF・否決相当）: 修復で HARD 中立になる手A候補は 238/385 あるが採用 0（weighted で負ける）。A/B 24 対 ON 7 勝 14 敗 3 分 p=0.19・ΔW% +0.09・試行 1956/採用 4。3.616.0 でユーザー決定により設定に単独スイッチ（既定 OFF）、C# 同期。  → `docs/history/3.4xx.md`
- 重み値を書いたコメントの陳腐化を一掃（2026-10-04、コメントのみ、出力不変）: 3.522.0 以前の重み（pref 9000>c3n 7000 等）を前提にした説明を数値なし表現へ。ForbiddenDiag の PINNED は HARD 件数で判定＝現重みでは weighted 上は破る手が得になりうるが、希望セルは wishLocked で探索が動かさない。文言に「この希望を1件調整すると、全体の点数は良くなります」を添える（表示のみ）。  → `docs/history/3.4xx.md`
- 最終番兵で個人上限0（希望でない）の勤務を含む段を外す（2026-10-04、ユーザー承認、通常は出力不変）: `excludeCapZeroStages`＋W ログ、RestZeroWindowLns を mayPlace へ、「手で置いた」文言を中立化。  → `docs/history/3.4xx.md`
- 入口で個人上限0のセルを外して必須が増えたことを知らせる（2026-10-04、表示・ログのみ、ユーザー承認、出力不変）: CapZero ログに必須 a→b、再実行の「前回を維持」に戻れない理由を1文。  → `docs/history/3.4xx.md`
- 設定の探索スイッチ群を「探索の強さ」2 択に統合（2026-10-04、3.615.0、ユーザー決定、ふつう＝出力不変）: じっくり＝combineExhaustPairs/wideC3nBreakDays/countChainPolish/aptFairSoftTolerance を ON。案 A 常時 ON、E0・extraRefine は常時 OFF。  → `docs/history/3.4xx.md`
- apt/fair 研磨の他ソフト許容がチェーン内 keep-best で巻き戻される（2026-10-04、修正、許容 ON のときだけ挙動変化）: 畳み込みも `toleratedBetter` で判定。  → `docs/history/3.4xx.md`
- 改善を通知する経路で liveBest を publish（2026-10-04、採用、採否・盤面不変）: 適応 portfolio の入口改善/グローバル最良更新・仮説多並列/ALNS 多チェーンの採用盤面。追加コストは改善時のみ数十回。  → `docs/history/3.4xx.md`
- 希望衝突の床に届いても E0 が発火しない不具合（2026-10-04、修正、E0 ON のときだけ挙動変化）: liveBest が best 報告より遅れる経路で古い盤面の「未到達」を保存していた。古い盤面では保存しない・探索終了時は結果の盤面で判定。  → `docs/history/3.4xx.md`
- 後処理チェーンの段ごとの構造化記録（2026-10-04、採用、出力同一）: `V6PostOptimizationResult.stageRecords`。A/B 集計をログの正規表現から構造化データへ。  → `docs/history/3.4xx.md`
- T5/T4 測定（2026-10-04、測定のみ）: handleOptimize 48 回で実時間/予算の最大 1.00＝超過なし（「181 倍」は再現せず）。案 A ON/OFF 24 対は 11-10-3・HARD 増 0。  → `docs/history/3.4xx.md`
- 外部レビュー 4 件の照合と出力不変の防御 3 件（2026-10-04、採用、出力同一）: `DeltaEvaluator.reset` の検証先行・C1 LNS の patience 丸め・ログの目標率表示。C42FlowPolish の z 集計ずれは既定 OFF・探索動学に触れるため未着手。  → `docs/history/3.4xx.md`
- 最適化後に残る HARD の分類（2026-10-03、調査のみ・製品コード不変）: 実データ 6 件×60/120s×3 seed と合成 42 件の計 140 本で、HARD が残る 23 盤面すべて最終 HARD＝CP-SAT の厳密最小（seed・予算で HARD 不変、FixSuggester/VCR の改善手 0）＝探索の取り逃しは 0。既存の床（forcedCovU・日の証明 1 日 1 件・希望衝突）は緩く、日の供給不足（1 日複数・シフト跨ぎ）・上限0（oct 5 件）・被覆と禁止連の取り合い（日またぎ）を数えない。改善案＝日の証明を最小不足量へ（頭打ちに使うなら A/B）。  → `docs/history/3.4xx.md`
- 案 A を既定 ON（3.614.0、ユーザー決定の事前基準を満たした）: PREREG2 の 78 盤面で ON 38/負 24/分 16・p=0.049・ΔW% −0.23 [−0.45, −0.01]・HARD 増 0。`C1JointLnsPolish.deltaChildEvalDefault`＝true・設定の既定と説明文も ON に。余裕は小さく単独で有意な部分集合はない。C# 同日同期。  → `docs/history/3.4xx.md`
- 正式 checker の出力同一の高速化（2026-10-03、採用、出力同一）: `UnifiedViolationChecker.check` の c41/c42/c41s/c42s を日ごとの (グループ, シフト) 人数表の表引きに（c42 は片側 0 人の日を走査しない）、ログ文面を初回の読み取りで作る（型は `List<MirrorLog>` のまま）、`canDo` を表引き・報告マップを再ハッシュなしの容量で。check 1 回 −24〜−30%、C1 共同 LNS（評価 2 万件固定）壁 −26%、後処理（決定的モード）壁 −10%、盤面は全本一致。旧実装の写し `CheckerReferenceV0` との全フィールド照合テストを追加  → `docs/history/3.4xx.md`
- 案 A 順番均衡 A/B の途中集計と負けの詳細理由（2026-10-03、測定のみ・既定 OFF のまま）: 70/78 盤面で ON 37/19/14・p=0.011・ΔW% −0.38 [−0.66, −0.12]・HARD 増 0（最終判定は 78 盤面で）。負け 19 のうち 14 は両腕とも C1 共同 LNS の採用なし＝後段の回ごとのぶれ、ON は結果がそろいやすく OFF のときどきの当たりを取りこぼす（推測）、案 A が絡む負けは 5 で差 0.6% 以下。  → `docs/history/3.4xx.md`
- 案 A の大きな負けペアの原因調査（2026-10-03、測定のみ・既定 OFF 維持）: 上位 4 組の再実行で再現せず（+21.8%→+1.1% 等）、OFF 同士のばらつき σ 5〜6%＝ばらつき。C1 共同 LNS は採用 0 束がほぼ全部＝ON の便益・害とも ≈0。ON 70/32 の理由は未説明（浮いた時間が後段に回る仮説）。  → `docs/history/3.4xx.md`
- スキル yomiyasu の取り込み（2026-10-03、ユーザー指示、コード不変）: 外部 `nanaism/yomiyasu`（8d5abee、MIT）の `skills/yomiyasu/` を `.claude/skills/yomiyasu/` へ写した（AI っぽい日本語の書き直し）。スクリプト 2 本はテキスト処理のみ（通信・外部コマンドなし）を確認。CLAUDE.md のスキル数を 4 に。
- 案 A 既定 ON の判定 A/B（2026-10-03、測定のみ・既定 OFF 維持）: 事前固定基準（HARD 増 0・符号検定 p<0.05・Δweighted% の 95%CI 全域 <0）で pooled 112 組 70/32/10 p=0.0002 だが Δ −0.52% [−1.21, +0.23] が 0 をまたぎ OFF のまま。大きな悪化の対（最大 +21.85%）が残る。  → `docs/history/3.4xx.md`
- design-review による探索スイッチの文言是正（2026-10-03、表示のみ）: 「解けない違反」→「計算では消えない違反」、希望衝突の床の説明に「細かい違反が少し多く残ることがある」（E0A/E0B の A/B で weighted 悪化）。  → `docs/history/3.4xx.md`
- 条件フラグの UI（2026-10-03、ユーザー決定、既定 OFF のまま＝既定の出力は不変）: 最適化設定カードに `C1JointLnsPolish.deltaChildEvalDefault`・`handleOptimize(extraRefineRequirePostHardDrop)`・`PolishGate.wishConflictFloorMode`（OFF/E0A/E0B の 3 択）を既存の 3 スイッチと同じ形で追加。保存しない・実行中は不可。C# 同日同期。  → `docs/history/3.4xx.md`
- C1 共同 LNS の子評価を差分評価へ（案 A、2026-10-03、実装・既定 OFF＝出力不変）: `C1JointLnsPolish.Config.deltaChildEval`（既定 `deltaChildEvalDefault=false`）。子は親へ reset 済みの `DeltaEvaluator`（ワーカーごと）に 1〜3 セルを当てて戻す・残す子だけ盤面を作る・最終の正式 check は不変。G1（全族一致）・G2（決定的モードで最終盤面一致）合格、評価速度 3.1〜5.1 倍。handleOptimize A/B は 60s 13-9・120s 8-15（blocked_covu 0-6）・HARD 増 0＝差を検出できていない／既定 ON の根拠なし。  → `docs/history/3.4xx.md`
- runV5 FULL（生 SA 盤面を最終候補に入れる）否決・再測定しない＋周辺 4 案の不採用（2026-10-02、ユーザー決定、記録のみ・コード不変）: tools/loop ゲート 460 ペア 194-76 だが HARD 増 17 試行（c04-small-dense1 7・c21-medium-wishheavy0 10 に集中）。handleOptimize A/B（10s 64-0／120s 41-22／300s 10-2、HARD 退行 0/140）よりゲートで決める。逆巡回 3 人ローテ・連鎖合成の棄却検査・停滞時計の基準変更・AdaptiveBlockSwap apt×1 も既定 OFF／main に入れない。  → `docs/history/3.4xx.md`
- 「玉突きで直す」提案（CHAIN）の途中判定が最終判定とずれる欠陥（2026-10-02、採用、提案の表示のみ・最適化器の出力・重みは不変）: `FixSuggester.chains()` のラウンド内の最良選びが `betterReport` だけで、covU→c3n の族入替えコマ（HARD 同数・weighted 小）を途中で選び、最後に `tryOps` の `newHardFamilyViolation` で連鎖ごと棄却されて CHAIN 0 件になっていた（机上テスト S4 で確認）。ラウンド内も `tryOps`・`windows()` と同じ規則（`betterReport && newHardFamilyViolation==null && !exactPinRegression`）に揃えた。テスト `chainRoundDoesNotPickCellThatTradesIntoAnotherHardFamily`。加えて `DeltaEvaluator.previewMove` の SOFT 係数を `scoreFrom` と共通の `weightedSoft` に（戻り値は apply で捨てられる古い式の掃除、採点・探索は不変、テスト `previewMoveReturnEqualsScoreAfterCommit`）、古い重みコメント（low/high 90/25→120/25 等）の修正。C# 同日同期。  → `docs/history/3.4xx.md`
- Watchdog/RSIPlus ログの拡充（2026-10-02、採用、ログのみ・探索・採点・停止判定は不変）: Watchdog 行に「入力超えの最終改善=経過Xs／なし」（上限0を外した入力の inputReport に betterReport で勝った最終改善の時刻）、RSIPlus Phase1〜4 行に weighted・ワーカー識別（[仮説N]／[W{i} epoch{n}]）・shouldStop による「(スキップ)」。テスト `RsiPlusPhaseLogTest`・`WishConflictFloorTest`。C# 同日同期。  → `docs/history/3.4xx.md`
- C1 研磨の直接移動が上限0へ置く欠陥（2026-10-01、採用、候補生成の規律のみ・重み・採否基準は不変）: 机上テストで `applyC1IndexChainRepair`／`applyC1WindowPolish`（手B）が個人上限 0 の (職員,シフト) へ置くのを確認（3.507.0 の「上限0は最適化器が置かない」違反）。候補の基準を `canDo`→`Problem.mayPlace`（beam の対象生成も）。テスト `C1UpperZeroGuardTest`。C++ に同等の研磨は無い。C# 同日同期。  → `docs/history/3.4xx.md`
- 論理監査 2 本の確定欠陥の修正（2026-10-01、採用、表示・設定修正・保存の堅牢化のみ・採点・探索は不変）: セル状態行の人員不足/過剰の必要数をチェッカー（covUCell）と同じ実効値に／設定の見直し「下限を0にする・下げる」は古い診断で直したばかりの値を戻さない・空の設定を作らない／persistNow 失敗時に取り消したデバウンス保存を戻す。C# は CSV ヘッダ日付ズレ警告（3.592.0）の取りこぼしを移植。
- E0 の床到達盤面での A/B（2026-10-01、測定のみ・製品コード不変・既定 OFF のまま）: 実データ oct2026_grid に実 S6 経路（`RelaxTrial.firstWall`→`apply`＋`applyMoves`）を当てて HARD=床=4 の入力を作り（rk9/rr4・前提3・組2、床は緩和後も 4）、OFF/E0A/E0B を 5 種×120s・workers4・交互で比較。E0 は全本 44〜52s で発火・HARD4＝床で偽停止0。中央値の壁時計 OFF117s / E0A50s / E0B44s、weighted 38958 / 39008 / 39215（E0B は入力盤面と一致＝探索の成果を採らない）。OFF は 300s でも 120s と同一盤面。推奨: OFF 維持（E0A は採用候補だが n=5・ホストのみ＝端末 A/B が要る）。  → `docs/history/3.4xx.md`
- 勤務表タブの縦寸法（2026-10-01、採用、表示のみ・採点・探索は不変）: 週送り 60dp＋コマンド 80dp の 2 段を 64dp の 1 本へ、シートを開く間はバーを隠し元に戻すを見出し行へ、タブを開くとグリッド上端へ、節見出しとヒントを撤去。見える行数は画面高 915dp で 11→13（シートなし）・8→11（ちら見）。寸法は `ScheduleLayoutMetrics`、テスト `ScheduleLayoutMetricsTest`。
- CSV 取込と手動固定の追従・固定登録時の担当外／必須違反ヒント・手順書 10(c)/16(d) の是正（2026-10-01、採用、採点・探索は不変）: 取込で値が変わったセルの固定は取り込んだ値へ追従（-1／範囲外は外す・同じ undo 段・完了文に件数）／担当外・必須違反のセルに固定すると登録文へ 1 文（挙動は不変）／手順書の期間の制約の文言と CSV 例の行番号を実パーサーに合わせる／ScheduleCsvBridge のコメント整理。→ `docs/history/3.4xx.md`（2026-10-01）
- 手動固定のシフト範囲外追従と期間の制約の表示文の言い過ぎ（2026-09-30、採用、表示と固定の整合のみ・採点・探索は不変）: shift=-1 への追従で固定が範囲外のまま残る→外す／凡例「近づく」・「すべて変えると／いちばん少なくて N 日」・届かない原因に手動固定と上限0／E0 論理レビュー・L18・L17・C42FlowPolish 等は backlog #42 (h) に記録のみ
- 制約CSVのグループ回数・スキルグループ回数の不正な上下限を取込で拒否（2026-09-30、採用、採点・探索は不変）: `abc,xyz` 等が 0〜無制限として受理され前の制約一式を置換していた→個人レンジと同じ共有判定 `rangeCellsOk` で中止。CSV 部分取込の低重大度 4 点（文言の取り違え・手順書 16・旗のコメント）も是正  → `docs/history/3.4xx.md`
- 設定の見直しの「必要数を下げる」が他の日まで動かす・「重複を1つ削除」が消えない（2026-09-30、採用、採点・探索は不変）: 日付つきの不足はその日の例外だけ書く／全日同上限の不足は標準を下げる 1 件／重複削除は最初の空白までの共有鍵。純粋部分を `SettingFixLogic` に切り出して JUnit 化
- 引用符が閉じていない勤務表CSVは読めた部分だけ取り込むか確認してから適用（2026-09-30、採用、採点・探索は不変）: 従来は読めた行を確認なしに適用して警告だけ出していた→利用者決定 B で適用前に「CSV の ○行目までは読めました（N 名分）。…この部分だけ取り込みますか？」［この部分だけ取り込む］［やめる］。純粋な判断 `CsvPartialImport`（読めた範囲・行番号＝引用符の中の改行も数える・確認待ちの指紋）＋ `parseCsvFull` の行追跡、適用は既存の取込の経路（元に戻す1段）。吸い込まれた行は手前のセルも取り込まない。何も読めなければダイアログ無しで断る。C# 同日同期  → `docs/history/3.4xx.md`
- 手動固定の保存漏れと同名職員の CSV 取込（2026-09-30、採用、採点・探索は不変）: 構造・制約の編集がない保存経路（元のファイルへ勤務表だけ差し込む）が手動固定を元のファイルのまま書いていた→今の固定を書く（`StateParser.exportCurrent`）／同じ名前の職員が複数いると勤務表CSVの行が先頭の職員へ入り上書き→その名前の行は取り込まず警告（`ambiguousNames`）、同じ職員の重複行は後勝ちのまま警告。C# は制約編集の経路でも固定が落ちていた＝同日同期  → `docs/history/3.4xx.md`
- 背景実行の保存・復元のデータ消失 4 点（2026-09-30、採用、採点・探索は不変）: 外部レビュー P1-a/b/c・P2 を再現テストで確認して修正。復元元（結果・途中最良）は反映した状態の保存成功後に消す（`clearAfterSaved`・`persistNow`、起動時の復元も同じ）／所有権の確認と置き換えを錠で一続きに（`RunFiles`）／評価を待った後に実行・入力・盤面を再確認（`BgApplySnapshot`）／編集ガードを投入時点で閉じ反映し終えるまで保つ（`BgEditGuard`・`OptimizationRepository.ended`）  → `docs/history/3.4xx.md`
- 外部レビュー 4 点の検証と修正（2026-09-30、採用、表示のみ）: ①担当 0 件の職員でセルシートが全シフトを押せた（`sheetCanDo`＝未読込だけ全部）②c1 の窓で変えられる日＜不足日数でも「届く見込み」→ stuck＋「不足は減ります」③S6 の試算不可（未割当）が「組はありませんでした」→理由を出す ④S6 の例外が黙って消えた→「試算に失敗しました」＋［もう一度試す］、古い失敗は上書きしない。C# 同日同期  → `docs/history/3.4xx.md`
- 表示の食い違い 3 点（2026-09-29、採用、表示のみ）: S6 適用直後の分析注記を「設定を緩めて手順を当てた盤面の概算です（もう一度つくる前）。」に（`UiState.relaxedBoard`）、use2 切替が無いのに残っていた「上限人数(2パターン時)」を「上限人数」に、ちら見［他 ▸］の読み上げ「すべてのシフト ▾」を「すべてのシフトを表示」に。C# 同日同期  → `docs/history/3.4xx.md`
- 前回を維持の文言に重みと決め手（2026-09-29、採用、表示のみ）: 実機「今回 必須5/合計369 は前回 必須5/合計376 以下に改善せず」＝比較は必須→重み→合計なのに重みを出していなかった。`KeptResultText` で必須・重み・合計と決め手（「重みが大きいため」）を出す。比較は不変。C# 同日同期  → `docs/history/3.4xx.md`
- セルシートのちら見の詰め（2026-09-29、採用、表示のみ）: 高さを中身に合わせ、シフトと［他 ▸］を 1 行（`peekPickCount`、360dp 以上は 4）、巡回中は下部バーの違反ナビを隠す、見出しから日付の範囲を落とす、⚠ の凡例 1 行。C# 同日同期  → `docs/history/3.4xx.md`
- checker 出力のダイジェスト回帰テストと残作業の棚卸し（2026-09-29、採用）: `CheckerReportDigestTest`＝4 fixture の乱択盤面 2000 枚で `UnifiedViolationChecker.check` の全出力（logs 除く）を SHA-256 で固定、P1 前（611782b）と後（ef656a5）で同じ値。backlog #42 に残作業を集約
- 正式 checker の割当削減（2026-09-29、採用、出力同一）: `UnifiedViolationChecker.check` の 1 件 53〜56KB→30〜35KB（−38%）。作業配列（正規化盤面・回数・被覆・曜日）をスレッドごとに使い回し、違反マップのキー "a,b" を作り置き、集計用マップをそのまま戻り値に、fair の添字整列を基本型に。120k 盤面の差分で報告全体が一致、C1 共同 LNS 評価 2 万件固定 8 盤面×seed1〜3×3 回×workers1/4 で 144/144 一致。GC 時間 −31〜33%、壁は −5〜6%（揺れの内側）。C++ 変更なし  → `docs/history/3.4xx.md`
- 途中で止めた測定の回収（2026-09-29、記録のみ）: E0 ホスト A/B（oct2026_grid・300s、E0A s1〜5／E0B s1〜6、s6 の E0A は停止）は全 11 本 HARD 9・床 4 に未到達で両群とも頭打ちが発火せず＝差は seed の揺れ（重み 平均 87659±115 対 87609±200、total 369±11 対 377±12）。次点エリート後処理は枠が走ったのが oct s1 の 1 本だけ（次点 w87919 が本線 w87597 に届かず）＝効果未判定のまま。並列効率の 3 回目（w4c、70/72 件）は壁 1855ms で 1363〜2203ms の揺れの内側、割当 53〜54KB/件・盤面一致は不変  → \`docs/history/3.4xx.md\`
- C1 共同 LNS の構造下限の出力同一の高速化（2026-09-29、採用）: 下限は Problem だけで決まるのに適応 LNS の 2 本で 2 回・回数次元（hi=T で死んだ次元）込みの DP を解いていた→回数次元を畳む＋同じ Problem は再利用＋同じ希望の並びの職員をまとめる。下限値・最終盤面・ログは oct/golden/sept/sample×seed1〜3 で完全一致、1 run あたり下限の時間 golden 450〜660→40〜70ms・sample 370〜790→17〜32ms・sept 180〜210→21〜30ms  → `docs/history/3.4xx.md`
- C1 共同 LNS の並列効率の切り分け（2026-09-29、測定のみ、この回で区切り）: 評価 2 万件固定・workers 1〜4・塊 16/256/1024・固定プール・GC 方式を scratch で振り、全設定で盤面 72/72 一致。W4 の評価区間の超過分は 同期・待ち 40%・GC などの停止 31%・単価増（+36%）22%・偏り 7%（仮説、ホスト負荷で揺れ大）。評価 1 件の割当 53KB。塊 256 は −12%→再走で未確定、ParallelGC は 2 回とも −13〜−21%。改善案の仮説＝check の割当削減・塊を大きく  → `docs/history/3.4xx.md`
- C1 共同 LNS の費用内訳 #11（2026-09-29、測定のみ）: 条件＝評価 2 万件固定と実機同等の 1.5s、oct 5 枚＋sept/golden/sample、seed 1〜3×3 回、workers 4/1。正式評価が壁の 82〜84%、root・構造下界・最終再検査 4〜6%（sept/golden/sample は下界 DP が 1 回 0.1〜0.3s）、生成 約 4%・重複検出 約 2%・コピー＋applyMove 約 1%。並列区間の待ち 47%・評価 1 件の費用は 4 スレッドで +20%・全体の速度比 1.82 倍（逐次部分 9.5%）。仮説として支持＝差分評価／評価の再利用・並列効率、支持されない＝コピー削減、弱い＝生成の改善  → `docs/history/3.4xx.md`
- 個人回数の共同 LNS の内訳と評価前の debt 判定 2 案（今回の測定条件では改善を確認できず入れない、2026-09-29、コード変更なし）: 候補の 62%（実機）が debt 除外・重複 1.5%。個人罰点だけで先に落とせるのは debt 除外の 0〜1%、`Evaluator.fullEvalParts` で HARD/total を先に見る案は check と 5 盤面×3 seed で不一致 0・盤面同一だが評価 1 件が check の約 6 割の費用＝壁時計 oct −5〜+10%・他 +15〜35%。この段は締切でなく探索停滞で止まる（実機は初回 1.5s の試行だけ期限）  → `docs/history/3.4xx.md`
- 残る理由の言い分け G3（2026-09-29、表示のみ）: つくる前の確認の「計算では消えない」は日の証明・配布不可を上限0込みで判定していたのに注記は「本人の希望は固定・必要人数は設定どおり」だけ＝実データ 10/2026 では日の証明 4 件すべてが上限0なしでは立たない。上限0が絡む行へ「（入れない指定が絡む）」＋S6 への注記、設定ミス診断 7 にも同じ判定で一文。C1Plateau（却下の記録）は既に「試した」止まりで不能と言っていないことを確認。C# 同日同期  → `docs/history/3.4xx.md`
- 床の外の HARD 1 件の正体（2026-09-29、調査のみ）: 3.612 #1・3.613 #2 の c3n（10/8 Dﾃ→10/9 A4）は CP-SAT の厳密解で、上限 0 の配置を実機の組に限ると最小 5・上限 0 を 2 セル足すと 4（S6「組2・5/10/5/4」と一致）＝設定で不能（上限 0 を緩めれば解ける）。上限 0 を SOFT とみなせば 4。HARD 到達（35s）後も SOFT は後処理後まで −18%。C1 共同 LNS はホストでも同じ経路なら 1.5s・重複 13〜16%＝実機と同じで、割合の差は分母  → `docs/history/3.4xx.md`
- 後処理の実機ログ横断と C1 共同 LNS の評価前重複除去（2026-09-29、コード変更なし）: 既出盤面・同じ親内の重複・debt 超過既知盤面を checker 前に同じ扱いにする案＝評価回数上限の決定的モードで 5 実データ×2 盤面×2 seed の 20/20 で盤面・件数表示一致、checker 呼び出し −14〜−19%・CPU 中央値合計 −13%（ケースで +6%〜−35%）。本番設定（patience 4s）では候補処理数が増えず品質同点＝今回の測定条件では品質・待ち時間の改善を確認できなかった→入れない（律速の推定「逐次の生成側」は #11 で外れと判明）。次点エリートへ後処理（追加精製枠）は効果未判定（60s は停滞早期終了で枠が走らず、走ったのは 150s の 1 対のみ）  → `docs/history/3.4xx.md`
- S6 の配線の穴（2026-09-29、表示のみ、決定 A）: ちら見の［推奨: X に変更］が上限を緩めずに 1 セルだけ当てていた→［例外として緩める候補: X］でダイアログを開く。NO_WALL の 1 行、止めた試算に「試算を止めました」＋［もう一度試す］、セルから確定した後はそのセルを選び直す。C# 同日同期（ホームのカード・S5→S6 の条件は照合して同値）。採点・探索は不変。  → `docs/history/3.4xx.md`
- セルシートのちら見（2026-09-28、表示のみ）: 既定を 3 段（違反の 1 行＋✕／氏名・日＋前/次／希望を取り消す・S6 の既存の推奨・上位 4 シフト・他 ▸）の約 22% に、全体は「すべてのシフト ▾」のタップだけで開く。上位 4＝今の割当→希望→枠の順（`peekShifts`）。採点・探索は不変、C# 同日同期（未 push）。  → `docs/history/3.4xx.md`
- 許容0の超過にセルの印・セルシートに上限0（2026-09-28、表示のみ）: 人員の上限0（covO0）・グループ/スキルグループの上限0（c410/c41s0）・実効の適切回数0（apt0）を右上の角に（`zeroAllowCells`）。原因セルが決まらない族（不足・上限1以上・c2・fair・weekly）は据え置き。セルシートのボタンに「上限0」、回数の行「Cｱ 2回（上限0＝入れない指定）▲」。採点・探索は不変、C# 同日同期（未 push）。  → `docs/history/3.4xx.md`
- 上限0のセルに印（2026-09-28、表示のみ）: 個人の上限0（休を除く）のシフトが入った日を全部要調整の破線に（`zeroCapCells`・`vio-high0`、族 high＝回数チップに従う）。上限1以上の超過は名前の横の ▲ のまま。セルシートは中立の文（希望なら「本人の希望が…」）。凡例 1 行。採点・探索は不変、C# 同日同期（未 push）。  → `docs/history/3.4xx.md`
- E0 希望衝突の床で頭打ち（3.613.0、既定 OFF）: `wishConflictHard`＝「希望と禁止の衝突」を report.hard の単位で数える単一ソース（HF70・残存分析・E0）、床 `wishConflictFloorParts`（区間 DP＋上限 0 に頼らない日の証明）、厳密な到達（HARD＝床かつ全て希望由来）、`PolishGate.wishConflictFloorMode` E0A/E0B（E0B は後処理を `minimalPost` に置換）。事前登録の A/B はホストで床に届かず（HARD 9 vs 床 4）判定不能＝端末水準の盤面から温める A/B が必要。N_mayPlace は端末盤面で健全でない（5＜6）。C# 同期（未 push）。  → `docs/history/3.4xx.md`
- 端末設定 2 件の A/B（2026-09-28、docs のみ）: `aptFairSoftTolerance`（許容 6%）は Δweighted 平均 −68・3 勝 1 敗・HARD 増 0、後処理のみで 1/5 盤面改善＝中立で安全。`combineExhaustPairs`（粘り強く）は後処理ハッシュ 5/5 同一・本走平均 −60（A/A 揺れ 113 内）・実機で 5000 組採用 0＝便益なし、端末 OFF 推奨。両方 ON は平均 −86・2 勝 2 敗。既定はどちらも OFF 維持。  → `docs/history/3.4xx.md`
- つくる前の確認（2026-09-28、表示・導線のみ）: `PreRunCheck`＋`PreRunCheckSheet`。本実行の入口 `runV6FullOptimize`（5 経路の合流点、S5 確定・中断再開は除外）で「計算では消えない」（希望どうし c3n/c3w・反映できない希望・配布不可・証明つきの矛盾（日/本人、コアに希望））か「もう一度つくると外れる」（手置き×上限 0）が 1 件以上のときだけボトムシート。［このままつくる］1 タップ・指紋が変わるまで再表示しない。希望件数＞個人の上限は節「設定上入れないシフトと希望（要調整）」（条件に数えない）、上限 0 の密度は「入れないシフト（個人の上限0）」のヒント 1 行、上限 0 は意図した指定＝「設定ミス」「壁」と呼ばず S6 は「例外として上限を緩める」へ（6e の上限 0 も中立の文）、手動固定の密度は出さない。実データ 9 件＋4 件。採点・探索は不変、C# 同日同期。  → `docs/history/3.4xx.md`

- 行末の印のシート「回数・偏り」の再構成（2026-09-28、表示のみ）: 回数の過不足を 2 列チップ（`Dﾃ  -1回 (3/4)`、個人の下限・上限は `(17/下限20)`、不足=赤系・超過=橙系、色は `countChipColors` の 1 箇所）、曜日の偏りは開閉式（既定は閉じる・0 件は出さない・件数=偏りのあるシフト数）で「休 : 日・火・金・土に集中 (+3)／月・木が少ない (-3)」（e=7×回数−合計、|e|≥4 の曜日を名指し、各側 round(Σ|e|/2/7)）、公平化は「差 1回 : A4, 有」。CLAUDE.md の weekly 式を 3.526.0 の定義へ。採点・探索は不変、C# は同日同期。  → `docs/history/3.4xx.md`

- 連携思考誘導UX（2026-09-27、表示・導線のみ）: セルシートから S6 へ（起点の窓・手順のセルに「緩める候補を見る」）、S6 ダイアログは題・希望不変・「必須違反 5件 → 4件」・変更規模・前提/解消の設定・手順の全件展開、ホームの段は対象と件数を名指し・探索中は「下限」と言わない、S5 改善なしから S6 へ＋「希望は、あなたが選ぶまで取り消しません。」・希望タブで開く、板挟みの相手セル、全 ⚠ の理由、手動固定の語「自動では変更しません」、日付は `DayText`（10/8(木)・10/8・10/8〜10/9）、語彙（人員不足・必須／要調整・希望と違う勤務・本人の希望・節ごとの設定の行き先）、巡回は違反単位「必須違反 2 / 5 ・ 禁止の並び Dﾃ→A4 ・ 10/8〜10/9」。採点・探索は不変、C# は同日同期。  → `docs/history/3.4xx.md`

- 手動固定（backlog #41、2026-09-26）: セル編集シートの［固定する］で最適化器だけがそのセルを書き換えない（`manualPins`）。手動固定＞規則 A の希望へ戻す＞通常の書き換え。`Problem.wishLocked`＝手動固定または実現可能な希望・縛る値 `lockTo`、希望の意味は `wishFixed`。C++ `wishLockedN`/`lockToN`＋JNI 末尾 `[nPin,(i,j,k)*]`。最終番兵は固定を崩した段を外す。採点不変・固定の無い入力で後処理ハッシュ 8/8 一致。受け入れ 7 項目合格。C# 同日同期。  → `docs/history/3.4xx.md`

- #39 初期解の C1 不足度順（2026-09-26、否決）: `SmartInitialScheduler` の C1 をシフト index 順でなく不足度順に埋める案。LoopBench（多 C1 合成 12＋実データ 4、10 seed）で勝ち越し 79/31 だが必須件数が増えた試行 2（m03）で統計ゲート不合格、実データは初期解が同一。差の大半は探索の揺れ。入れない。  → `docs/history/3.4xx.md`

- 希望固定セルの規則 A（2026-09-26）: 最適化器は希望固定セルを「希望へ」か「今のまま」にしかしない（`PolishGate.wishPinStrict`、既定 ON）。盤面ごと写す経路（エリート再結合・全月入替・エリート統合）と「希望どおりの値だけ守る」段（covO 退避・RSI の free 系・入口 hf66/上限 0 の置き換え）を塞いだ。旧 P1（終了時に崩れた希望 0）は未達＝入力時から崩れていた古泉 10/25 が残る。値によらず凍結する案はあとから足した希望が載らなくなるので不採用。測定（240 秒×入力 5 件×種 3×OFF/ON）で開始時に守られていた希望を崩した実行 0・同じ種の HARD 一致・weighted 差は A/A の内＝既定 ON で合格。C++ は不変、C# は同日同期。  → `docs/history/3.4xx.md`

- S6「設定を緩めたら」試算（2026-09-26）: `RelaxTrial`（上限 0 を全部外して解けるか→使った壁→削除フィルタ→組で試算、起点は希望どうしの衝突を除く 3 件まで）、1 手の探索が候補なしのとき背景で回し、見つかれば希望の段より先に「緩める候補を見る」。確定＝上限 0→1＋試算の手順を Undo 1 段（鮮度はデータの指紋、手順で試算の件数を再現できなければ拒否）。手置きの上限 0 は設定の見直しに 1 行。実データで 荒井 Cｵ・アリフ Pｼ、5→4。C# 同日同期。  → `docs/history/3.4xx.md`

- 期間の制約（c1）の表示の再設計（2026-09-26、ユーザー明示 go）: 印は不足窓の中の「そのシフトに変えられる日」だけ（すでにそのシフトの日・ランの先頭には描かない）、重なって続く不足は行の下の帯、セルシートは「期間の約束: …」の文と「すでに数に入っています」、変えられる日の無い窓は「勤務表だけでは満たせません」＋希望を見る／設定を見直す。表示専用（`c1Shortages`）、採点・チェッカーの場所マップ・探索は不変、C# は同日同期。  → `docs/history/3.4xx.md`

- 外部レビュー e07bbd5 の是正（2026-09-26）: 板挟みのボタンを「希望は残して…（希望は未反映になります）」、セルシートの状態・印・回数を 1 つの評価の版（`cellSheetRev`）から、操作の通知（`opNotice`）を検査の文言と分離し元に戻すを操作に結ぶ、直し方探しの明示状態（`fixPanelState`）、［詳しく］に重なった違反・連続 N 区間・職員の回数と偏り、元に戻す/やり直すで希望表示を即時復元。採点・探索は不変、C# は同日同期。  → `docs/history/3.4xx.md`

- セル編集シートの再設計（2026-09-25）: 画面下の固定パネル（スワイプ無し、閉じる／戻る／空白タップ、別セルのタップで移る）、シフトボタンは固定配置（担当外は灰色の「外」）、1 行の状態（原因と相手・日・数、必須＝淡い赤／要調整＝淡いアンバー）、緑の点＝おすすめ・警告＝必須が増える、希望を守っている板挟みは「他の人で補う（推奨）」、即反映＋元に戻す、前日／翌日、「必須違反 N件を順に見る」巡回、セルをシート直上へ自動で寄せる。採点・探索は不変、C# は同日同期。  → `docs/history/3.4xx.md`

- 勤務表の表示専用の印（2026-09-25）: 回数の族は職員名の横の ▼/▲（タップで回数・偏りの内訳、公平化・曜日も）、人員は日付の下に「休▲」とシフト名、期間の制約は窓幅おきに印（`ViolationReport.c1Runs`）、隠れた族は左上の点、セルシートに「この職員の回数・偏り」。チップ「連勤」→「並び」、凡例に形ごとの族名、⑤ 並び・期間の制約、ForbiddenDiag の検証範囲の文言。違反セル・印・集計の内訳は開いた時点で直し方を探して同じシートに手か理由を出す、「計（期間）」に人員の日数。チェッカーの場所マップ・採点・探索は不変、C# は同日同期。  → `docs/history/3.4xx.md`

- 画面層の精読 #2 の残り（2026-09-25）: UI 層 52 件＋取りこぼし 5 件の是正（ViewModel・取込／ホーム・分析／勤務表・月次条件／編集画面）。他の案は当てても残し元に戻すで一覧ごと戻す、同じ行の重複はダイアログで断る（エンジンは dedup しない）、1 つだけの並びは「X（1つだけ）」表示、成り立たない値はダイアログで断る、画面語彙はグループ／スキルグループ、1 操作＝1 undo。2 回目の是正（レビュー 18 件）: 背景結果の反映失敗の後始末、背景の「やめる」を runId ファイルの所有者で判定、停止・失敗の結果の旗、読込の退避は確定後、表示色の undo は結果を外さず対象ごと、並び以外の同じ行を設定の見直しに。探索・目的関数は不変、C# は同日同期。  → `docs/history/3.4xx.md`

- 診断の文言 2 件（2026-09-25）: 設定ミス診断 1c は窓が 1 セル（単独の禁止シフト）なら単数の文言、HF70・残存分析の集計名を「希望どうしの衝突」→「希望と禁止の衝突」（1 件の組も数えるため）。MUS 日別の「証明つき」に上限 0 で席に数えなかった人を名指し。文言のみ＝盤面不変。  → `docs/history/3.4xx.md`

- backlog #38（2026-09-25）: `Staff.skillIdx` の既定を 0→−1（未所属）、スキル群 0 件から最初の 1 群を作るとき全員 −1（`Ws1Ops.addSkillGroup`。3.327.0「自動で書き換えない」からの意図的な逸脱）、検査 2i のコメント是正。0/−1 混在で探索の組分けが変わりうる。盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- 実データ精読の是正・UI 層（2026-09-25）: S5 の候補に希望どうしの衝突の兄弟の希望（古泉 10/26・10/27）を足す（`UiState.wishSelfConflicts`）、設定の見直しの一覧が編集・ホームとも 6 件で切れて全件を見る場所が無かった→「すべて表示」。  → `docs/history/3.4xx.md`

- backlog #36 R1 締結（2026-09-25）: 走行 keep-best の大規模負け越しは無害化前の許容（FairPolish の許容出力の巻き戻し）が原因。現行 main で `rkbstruct` 再測定＝勝45/負11 p<0.0001・必須悪化0・大規模 c42pair/forbidden 10/10 盤面一致・dense 7/3＝事前基準合格。許容 ON は大規模の保存盤面で既定 OFF に 8/26（実機設定の切替は利用者判断）。  → `docs/history/3.4xx.md`

- 実データ精読の是正・エンジン層（2026-09-25）: MUS 日別が上限 0 の希望固定を席に数えず誤「証明つき」（10/11）→希望固定優先、強制下限も同型。希望どうしの禁止の並びの診断 1c、`V6SanityPort.wishSelfConflicts`（1b/1c/HF70/残存分析/S5 兄弟行が共有）、HF70・残存分析の仕分け、SaOptimizer のコメント是正。盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理⑬（2026-09-25）: 否決済み `roleBudgetFit`＋`rsiPlusPhaseBudgets` を撤去（C# 同期）、探索側は 3.600.0 直前と roleBudgetFit 差分なし。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理⑫（2026-09-25）: 否決済み `postChainKeepBestFinalOnly` を撤去（C# 同期）、盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理⑪（2026-09-25）: 否決済み `postChainRunningKeepBestAcceptTies` を撤去（C# 同期）、盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理⑩（2026-09-25）: 否決済み `c41FlowPolishEnabled`＋`C41FlowPolish` を撤去（c42 側は保持明記で残す）、盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理⑨（2026-09-25）: 否決済み `c3PairMaskEnabled`＋`C3PairMaskPolish` を撤去、盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- handleOptimize に任意の `seed` 引数（2026-09-25）: 既定 null＝従来の時刻由来で出力不変、ベンチ用。backlog #38 D2（skillIdx 既定 0）・#39 SI-3（C1 初期解のシフト順依存）を登録（C# 同期）。  → `docs/history/3.4xx.md`
- sudo_model.md 再照合（2026-09-25）: 不変条件を20族・HARD 5族（c3w）・3.556.0 の重みへ、O の実測を 8990/total 409 へ、fair v2・D9・HardDelta・WishTrial/RunMarker を反映（docs のみ、C# 同期）。  → `docs/history/3.4xx.md`
- S5 中断案内で取り消した希望を名指し（2026-09-25）: 実行マーカーに `s5` 文脈、`work/RunMarker.kt`＋`RunMarkerTest`、C# 対象外（backlog #37(b)）。  → `docs/history/3.4xx.md`
- backlog #36 走行 keep-best を末尾だけにする案（2026-09-25）: `postChainKeepBestFinalOnly` 勝11/負36 p=0.0003＝不合格、既定 OFF。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理⑧（2026-09-25）: 否決済み `covOReliefEarly` を撤去（C# 同期）、盤面ハッシュ 8/8 一致。今回の上限 8 件で終了。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理⑦（2026-09-25）: 否決済み `SaParams.officialTieBreak`＋TieBreakBench を撤去、SaOptimizer は導入前とバイト一致。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理⑥（2026-09-25）: 否決済み `debtLaneSlots` を撤去、盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理⑤（2026-09-25）: 否決済み `bestOfK` を撤去、盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理④（2026-09-25）: 否決済み `familyPriorityScoring` を撤去、盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理③（2026-09-25）: 否決済み `stallEscalation` を撤去、盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- S5 T8 言語跨ぎ契約（2026-09-25）: `wish_trial_expected.txt` を Kotlin/C# の `WishTrialCrossLanguageTest` が共有、両言語一致（backlog #37(a)）。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理②（2026-09-25）: 否決済み `useDynamicBlockLens` を撤去、盤面ハッシュ 8/8 一致。  → `docs/history/3.4xx.md`

- 既定OFF台帳の整理①（2026-09-25）: 否決済み `lnsWeightDebt`／`WeightDebt` を撤去、盤面ハッシュ 8/8 一致。台帳 22 件の分類と残した理由を記録。  → `docs/history/3.4xx.md`

- HARD 差分の事前却下を実装（2026-09-25）: `HardDelta`＋`PolishGate.hardDeltaPrefilter`（既定ON）を巡回交換 k=2,3・C1広域ビームへ。決定的後処理 18 盤面×2 回で盤面全一致、合計 227s→132s（−42%）。C# 同日同期。

- アルゴリズム案の机上テスト（2026-09-25）: §1 必須残中の SOFT 研磨カット＝否決（必須増 10〜13・減 0、Fair も HARD を減らす）、§3 HARD 差分の事前却下を巡回交換・C1 広域ビームへ＝後処理 −26%・盤面 18/18 一致で採る候補。

- N9 巻き戻したパスの採用数を0と数える版の A/B（2026-09-24）: postChainRollbackCountsZero（既定false）・n9rollback 腕 勝2/負4 p=0.69 → 不合格・既定不変
- 文書の古い記述の是正（2026-09-24）: backlog #14(a) lnsAdaptive 既定ON・#14 重み現行値・#16 roleBudgetFit 参照・#19 決着・#37 S5 任意残作業／s5 冒頭 C# 同期済み／AP 既定OFF台帳補完＋postChainRunningKeepBest 昇格／automation 否決済み 3 件・pref 8000／CLAUDE.md #2＝UI 層のみ
- weight_lint 抑止キーを行番号→コード断片へ（2026-09-24）: WEIGHT_LINT_EXEMPT を (ファイル, 行の一部) で引き、上の行の編集で抑止がずれない・断片は検出行ちょうど1行（第3要素で行数指定）に一致、空振り/曖昧は fail
- S5 VM・画面のレビュー対応（2026-09-24）: 採用後の例外（遅い失敗）で画面を入力盤面へ戻さず今の盤面で描き直す（文言「作成は終わりましたが…」）・充足不可の S5b 版を重複除去の前の wishPinned で決め、例の日も wishPinned の枠から・描き直しも落ちたときの代替でも盤面を今のものへ（C# 同期）
- 外部レビュー bg 組（2026-09-24）: R6 復元前に届いた背景結果を復元後に受ける（購読を復元の後へ・二重適用と置き換え結果の残留を防ぐ）・N6 Worker へ方式・仕上げ最適化を渡す（RunConfig、C# 同期）
- 外部レビュー修正の監査（2026-09-24）: N10 の取り残し＝ミニ再最適化（WINDOW）が tryOps を通らず回数固定を崩す組み合わせを提案していた→最良の選択で exactPinRegression を弾く（C# 同期、R2/R3/R5/N9/N10 の C# 移植も同日）
- 外部レビュー role 組（2026-09-24）: N1 休みOFFが再読込で戻る（保存を rest/none に・"" は旧JSON扱い・CSV取込で休みを付与、C# 同期）・N2 指紋が Shift.role を読まない・N5 native 変換の restIdx を role 解決に
- 外部レビュー csv-ui 組（2026-09-24）: R2 制約CSVの並びが 5 列で切れる（行末まで読む、C# 同期）・N3 取込完了案内を成功時だけ・N4 c2 の設定リンクを ⑤並びへ
- 外部レビュー R3/R4/R5/N9/N10（2026-09-24）: c3n 診断に c3w を算入・epochOverrunCount の伝播・ExtraRefine 省略判定の covU 床条件・無変更パスに巻き戻し印を付けない・提案生成で回数固定を弾く
- 外部レビュー R1/N8（2026-09-24）: hosttest.sh が JUnit 失敗を終了コードへ伝播しない（tail の 0 を返していた）→PIPESTATUS で伝播・weight_lint の抑止リスト行番号 6 件の陳腐化で exit 1→現行行へ
- S5 試算の VM と画面（2026-09-24）: startWishTrial/wishTrialFor（ctx＝state 参照＋盤面キーを読むたびに照合・1 世代保持）・cancelWishAndRebuild（§6 の 1〜10、Undo 1 段）・startFullOptimize（Undo ラベル/S5 文脈）・停止/失敗の希望維持・元に戻すで stalledHardFamilies 復元・ダイアログの「取り消したら？」・S5b 節・WISH 入口を候補ありへ・充足不可の S5b 版
- S5 試算のエンジン層（2026-09-24）: WishTrial（H0/Hx/Rk/Rr・a'・b、数値だけの結果型、対照は別関数）・CoverageShortfall.wishPinned と充足不可の文の直し・候補行と文言の純粋関数・lockedWishKeys。VM/画面は未着手

- S5「この希望を取り消したら」試算の設計（2026-09-24、未実装）: 確定分＋VCR 見込み（対照差し引き）・取り消し＋本実行を Undo 1 段・机上 v1/v2（0 の行の 10/18 は本当は減る）・敵対的検証 28 件反映・範囲と入口は利用者決定（人手不足の日の希望も最初から・c3w 両方・入口拡大・戻すと入口も戻る）。`docs/s5_wish_trial.md`。

- 許容 ON の無害化（2026-09-24）: 必須族の非増・重い SOFT の増加禁止・対象族の厳密改善。OFF vs 無害化 ON＝ON 63/OFF 52 p=0.35・必須増5（減6）＝段1不合格、既定 OFF 維持（無害化前の有意な不利は解消、実データは同値）。

- 思考誘導 UX（3.612.0）: セルシートの必須先頭化・要調整の折りたたみ、c3 を「守るとよい並び」へ、ホームの主ボタンを
  不足→1手→下限→希望→問題 の順に1つ、1手カードに「必須は減るか」、ぶつかっている希望の一覧。違反20族の位置の種類をテストで固定（2026-09-23）。

- 許容 ON/OFF を現行コードで再測定（2026-09-23）: ON 勝50/OFF 勝84 p=0.004・必須増7＝ON 不利、実データはほぼ中立。既定 OFF 維持。

- backlog#34 凍結対策（3.611.0）: 前景実行中だけ前景サービス（常駐通知・WorkManager）と部分WakeLock、
  画面の離脱/復帰を操作ログへ（2026-09-23、ユーザー決定「前景サービス併用」）。

- running keep-best を構造床条件つきで既定ON（3.610.0）: 許容ON同士230ペアで勝108/負54・必須増0、C#同期。
  iter8は再測定で229/230同一盤面＝昇格見送り。別ベンチCSVの突き合わせ（許容ON vs 既定）は初期解が違い無効と訂正（2026-09-23）。

- #36 running keep-best（許容ON条件で再測定）: 勝112/負72 p=0.004・+28%高速だが充足不能で必須増5件＝統計ゲート不合格、
  既定OFF維持。許容ON自体も既定より有意に良くない（2026-09-22）。

- A/B評価の机上見直し（2026-09-22）: #36は既定設定で構造的に不発動＝無効測定→許容ON条件で再測定、
  #28/roleBudgetFitは有意差なしへ訂正、#35は実質A/A。gate.pyに統計ゲート（退行ゼロ＋符号検定）を併記。

- backlog#34: 実機ログの予算超過3回はプロセス凍結と確定（全ロール同秒数超過＝最後の進捗→完了の空白と一致）、
  エポック超過の診断文を凍結/経路漏れで出し分け（2026-09-22、C#同期）。

- 再発防止4件（3.608.1）: hosttest.shでdesign lint実行・hosttest/loopでLANG=C.utf8強制・
  versionCode 821/versionName更新・lessons§5に「外部レポートの成果物は実在を照合」を追記。

- backlog#21完了: タグビルドのリリースAPKをGitHub Release アセットへ移行（2026-09-22、ユーザー明示go）。
  `publish-release` job新設、書込みトークンは分離、手動実行は従来どおり。実タグpushで動作確認済み
  （途中design_lint.pyのP6誤検知/P10 baseline陳腐化を発見・修正）。
- backlog#26: 停滞判定「修復途中の進捗」を調査・実装不要と確認（2026-09-22）。improvedThisEpochは
  既にbetter()の辞書式比較でHARD優先＝想定した欠陥は無かった（コード変更なし）。
- backlog#34: 181倍量子超過の根本原因調査＝runRsiPlusの4フェーズにフェーズ別実測ms計装を追加（2026-09-22、
  grilling）。サンドボックスではDozeを再現できないため次の実機ログで確証を取る前提の準備。
- backlog#35: extraRefineRequirePostHardDrop(3.600.0)のA/B判定＝既定OFF維持（2026-09-21）。
  新規HandleOptimizeBench.ktで実データ4件測定、方向がケースで割れ信号なし。
- backlog#28: rsiFocusRotationPersistのtools/loop A/B判定＝既定OFF維持（2026-09-21）。
  PortfolioBudgetBenchを一般化しrsifocusrotation腕を追加、実データ4件全てで悪化（golden +2.49%）。
- backlog#36: postChainRunningKeepBest(3.608.0)のtools/loop A/B判定＝既定OFF維持（2026-09-21）。
  46ケース全カテゴリ・実データ4件で新旧完全同点、ゲート不合格。副次的にLoopBenchの起動不能バグ
  （`Cases.build()`のShift role省略でhf66DataHardeningの休シフト必須ガードに抵触）を修正。
- backlog#34: `docs/backlog.md`の更新漏れを是正（2026-09-21）。ポートフォリオ経路ベンチ（3.601.0）の
  整備・roleBudgetFit採否判定（既定OFF維持）は完了済みだったが、backlog.md本文が反映しておらず
  「測定手段の欠落」で未着手に見えていた。history 3.601.0節を引いて追記、コード変更なし。
- 後処理チェーンにrunning keep-best追加（3.608.0、既定OFF/測定中、backlog#34系）: grillingで方針確定。
  実データでCovOReliefPolishの良い一手がチェーン内の別パスの悪化に道連れでpickBestStageに棄却される
  問題を実証。`PostChain`にbestWork/bestReportを追加し`postChainRunningKeepBest`フラグでガード。
  flag OFF時は挙動完全不変。全パスの結果型に`report`フィールド追加（新規check呼び出しゼロ）。
  hosttest 826件緑。既定ON昇格はtools/loopベンチ待ち（測定手段の整備が前提）。→ `docs/history/3.4xx.md`
- 実機ログ調査（3.607.0）: ユーザーデータ調査で10/25のA4過剰・希望違反はcons3n「休休休」禁止パターンとの
  構造的衝突と特定（正しい挙動）。副次的に見つけた不具合＝Sentinel発火時、post.report.logsが棄却盤面の
  観測であることを行単位で明示していなかった問題を`annotateStaleLogsIfRegressed`で修正。ログを削らない
  方針は不変。hosttest 824件緑。→ `docs/history/3.4xx.md`
- UX机上テストP2の2件を修正（3.606.0）: `SettingIssuesCard`/`AnalysisTriageCard`の「設定へ」を族キー/
  IssueKind別に年間マスター④⑤へも自動展開するよう拡張。CSV「データ全体（新規）」取込直後に
  `ImportGuidanceBanner`で次の一手を案内。残りP2項目（勤務表フィルタ既定・適用後自動再探索等）は
  副作用のリスクを検討し見送り。→ `docs/history/3.4xx.md`
- UX机上テストP1の2件を修正（3.605.0）: `GuidedFixDialog`の候補0行き止まりに「データを見直す」を追加。
  `CollapsibleSection`に`forceExpandKey`を追加しC1頭打ち/ピン影響カードからの誘導が年間マスター③へ
  自動展開するように。E2(HARD同点)はbacklog#19で測定済み、E4(休の散在)は事実誤りと確認・対応不要。
  → `docs/history/3.4xx.md`
- UX机上テストP0の3件を修正（3.604.0）: ホームのamberカードで人手不足0＋未完成のとき主ボタンが1つも
  出ない不具合を修正（必ず1つ出す）。V6診断カードの英字「HARD Core/Guard/Apt/Equalize/Demand/covU」を
  日本語へ（breakdownLabels再利用）。U16（休の改名警告）はbacklog#24で構造的に解消済みと確認・対応不要。
  → `docs/history/3.4xx.md`
- backlog#24完了（3.603.0）: restShiftIndex根本改修。ShiftRole{None,Rest}導入・restIdx全面nullable化
  （ユーザー指示で縮小案を却下・全参照約20ファイルへ伝播）。記号"休"の字面一致＋無言0フォールバックを撤去
  （実データでHARD 0→60等の実害を確認済み・history 3.574.0）。UIに「休みとして扱う」トグル追加。
  JSON後方互換の自動付与つき。hosttest 823件緑。C++/C#はindexを受け取るだけで同期不要と判断。
  → `docs/history/3.4xx.md`
- backlog#28/#35/#24: grillingで設計確定・実装（3.602.0）。#28はHf63Infeasibilityへ持続カウンタ追加
  （rsiFocusRotationPersist既定OFF）。#35はExtraRefineゲートに構造的HARD判定を流用
  （extraRefineRequirePostHardDrop既定OFF）。#24（restShiftIndexフル改修）は方針転換を要するため保留継続。
  hosttest 823件緑、tools/loop採否判定は未実施。→ `docs/history/3.4xx.md`
- backlog#34: ポートフォリオ経路ベンチ新設・roleBudgetFit採否判定＝既定OFF維持（3.601.0）。
  PortfolioBudgetBench.kt実行結果（4 fixtures×5 seeds×budgetSec=90s）: golden -1.9%✅・
  sample +0.03%➡️・blocked_covu +0.8%❌・sept2026 -2.3%✅。3/4で改善するも blocked_covu
  での悪化が懸念。超過回数ゼロ。→ `docs/history/3.4xx.md`
- backlog#34 P0着手（3.600.0）。締切/停止済みならロールを始めない・成果を回収してからbreak・RSI+入口の
  無駄走り回避を既定ONで修正。ロール予算を`min(量子,残り)`に収め位相合計を予算ちょうどにする案は
  `roleBudgetFit`（既定OFF、通常経路の探索動学が変わるため計測後に採否）。P1(ExtraRefine絞り込み)は
  既定適用を撤回しbacklog#35へ → `docs/history/3.4xx.md`
- backlog#34: 3.594.0/3.595.0の記載を訂正（3.599.0）。フェーズ境界のstopRole判定を見落とし「35s固定」は
  誤り、SAチャンクは冷却条件でも終了（maxIters単独制御ではない）、現行tools/loopはポートフォリオ経路を
  一度も経由せず#28/#34を測定できない。コード変更なし → `docs/history/3.4xx.md`
- backlog#30完了: 対角有限化のベンチ判定＝既定OFF維持（3.598.0）。230ペアで辞書式 新5/同等224/旧1、
  品質±0.00%・速度+0.3%＝ほぼno-op。ベンチ資材では「完全割当が不可能な日」がまず起きないことの裏付け
  → `docs/history/3.4xx.md`
- backlog#30: 最小費用割当の契約修正＋日ごと割当の対角有限化（3.597.0）。INFは十分大きいだけの有限値＝
  全INF列や鳩の巣形で禁止辺入りの割当が返っていた（2x2/3x3で再現）。返却前検証でnullへ。対角（自分の現
  シフト＝無操作）を常に有限にする案は既定OFFで測定中（腕キー`dayassignidentityfallback`）
  → `docs/history/3.4xx.md`
- backlog#29完了: 窓ルールの最小日数を厳密貪欲へ置換（3.596.0）。旧DPは窓18=6.3秒・窓22以上はOOM＝実機で
  落ちる。consecutive-ones＝全単模を使った右端優先の貪欲でO(期間×窓数)へ。構築DPは状態数上限200,000で
  打ち切り（決定性維持のため時間でなく状態数）。総当たりオラクルとt=13の全4,186通りで一致確認
  → `docs/history/3.4xx.md`
- ロール時間クォンタム超過＝2つの構造的原因を確定（3.595.0）。`runRsiPlus`フェーズ下限の合計超過
  （budget≤30sで最大3.5倍）＋`stopRole`の協調的ポーリング（プリエンプティブ中断なし）。3.409.17記録の
  3〜9倍規模と整合。修正は探索動学変更＝grilling+tools/loop計測が必要、backlog#34更新。コード変更なし
  → `docs/history/3.4xx.md`
- ロール時間クォンタム超過の再検証（3.594.0）。外部提案の規模(300〜3000倍)は実記録(3.409.17、最大
  3〜9倍)と不一致＝誇張。新たな手がかり`nativeSaChunk`の未検証、backlog#34へ記録。コード変更なし
  → `docs/history/3.4xx.md`
- 外部監査5件をbacklog#29〜33へ登録（3.593.0）。DP状態爆発(#29・高)・MinCostAssignment禁止辺(#30・中)・
  C41/C42FlowのbaseCount群外混入(#31・既定OFF実害小)・hf67HardRepair古い回数(#32・意図的既存)・
  DP seed相殺(#33・低)。コード変更なし・調査記録のみ → `docs/history/3.4xx.md`
- 外部監査13件のうち12件を修正（3.592.0）。HF63フラグ解除・RSI最終ラウンド記録・c3nWallProven
  アトミック統合・toleratedBetterカウンタ・背景alternatives伝播・Worker所有権ガード・
  enqueue非同期失敗検出・途中結果復元順序・Undo/Redo表示反映・applyWishes条件付きpushUndo・
  CSV取込ロールバック・CSV日付照合。1件(RSI周期枠)はbacklog#28へ登録し測定してから採否。
  hosttest 815テスト緑 → `docs/history/3.4xx.md`
- backlog#27①: fairAchievementDirectionのtools/loopベンチ判定＝既定OFF維持（3.591.0）。5seed×46ケース、
  仕様§4の4基準すべて不合格（品質±0%・速度+1.6%で便益測れず）。backlog#26の5腕と同じ判断基準で
  既定OFF確定、取りこぼし自体はテストで固定済み → `docs/history/3.4xx.md`
- backlog#27①: fairの候補分類をfairDevOfBucketの黒箱観測へ揃える（3.590.0、実装・既定OFF・測定中）。
  grillingで範囲確定、`PostOptimizationParams.fairAchievementDirection`でA/B切替、tools/loopベンチ
  実行中 → `docs/history/3.4xx.md`
- FairTargetDivergenceTestのCI OOMを修正（3.589.0、PR #205 CI赤対応）。CIのGradle単体テストJVMは
  ヒープが小さく、5回の`runPostOptimization`呼び出しでOutOfMemoryError。実測結果は変えず、
  合成`MagiState`直接構築＋素の入力のみの走査へ軽量化 → `docs/history/3.4xx.md`
- fair仕様調査③: fairTargetとfairDevOfBucketの乖離を実データで実測（3.588.0）。実データ4件
  （生入力+決定的後処理後=計65件）+合成ケース(5件)の`distLocations["fair"]`セルを走査、sept2026の
  後処理後盤面で1件、fairTargetが「一致」と誤判定し放置する取りこぼしを確認。`fairDevOfBucket`は
  判定式を複製せず仮想入力で黒箱観測（`DeltaEvaluator.fairDevAt`と同手法）。①候補生成式・②交換
  探索改良は未着手・backlog#27へ登録 → `docs/history/3.4xx.md`
- 「設定の効き」ログのPolishGate直読みバグを修正（3.587.0）。外部の状況報告5件をサブエージェントで
  実コードと突き合わせ検証、4件は現状と食い違うが1件（実行時スナップショットとログ表示の食い違い）
  は実在すると確認し修正。表示のみの影響で勤務表・スコアには無関係。hosttest 808テスト緑
  → `docs/history/3.4xx.md`
- backlog#26完了: C1成分修復・C3nMarginLnsPolishを実データ4件への直接ON/OFF比較で測定（3.586.0）。
  tools/loopの合成ケースでは再現できない狙いのため、実データへ直接runPostOptimizationを実行する
  手法へ切り替え。sample_v6のc3n=1(HARD)残存を含む4件全てでON/OFF完全同一という結果を得て、
  5腕とも既定OFF維持を確定 → `docs/history/3.4xx.md`
- weight_lint.pyの抑止リスト行番号ズレを修正（3.585.0、PR #202 CI赤対応）。3.580.0のコード挿入で
  `V6HotfixPasses.kt`の既知の空振り対象行が934→951へずれたのを検査が正しく検知、行番号を更新
  → `docs/history/3.4xx.md`
- countChainReactivateの再ゲート結果（3.584.0）: 5腕中唯一実際に発火したが、下位10%品質
  （large infeasible区分）でhardがわずかに悪化する試行が3件あり不合格。CountChainPolish自身の
  history（3.540.0）の「実害なく効く」という記述を訂正（infeasible大規模盤面では成立しない例外）。
  backlog#26の再活性化メカニズムはtools/loopで測定できた3腕（C2/C42/CountChain）とも
  既定ON昇格に不合格と確定、既定値は全て変更なし → `docs/history/3.4xx.md`
- c42FlowPolishReactivateの再ゲート結果（3.583.0）: 5seed×46ケース(230ペア)でゲート不合格
  （品質改善±0%・速度±1%以内、退行ゼロは合格）。C2Polishと同型でmain探索がc42/c42sを既に
  解消済み＝既定OFF維持 → `docs/history/3.4xx.md`
- c2PolishReactivateの再ゲート結果（3.582.0）: 5seed×46ケース(230ペア)でゲート不合格
  （品質改善±0%・速度±1%以内、退行ゼロは合格）。main探索がc2不足を既に解消済みで腕の出番が無い＝
  3.524.0の診断を再確認。既定OFF維持 → `docs/history/3.4xx.md`
- backlog#26続き: tools/loopへ5腕分のxxxReactivate featureキーを追加、C2/C42/CountChainは既存
  ケースで再ゲート実行中（3.581.0）。C1成分修復の専用合成ケース化は`sequentialBlindSpotFixture`を
  8/16/30人×14/28/31日へタイルしてプローブしたが全9組み合わせで旧腕(useComponents=false)がすでに
  c1=0まで解消＝フル後処理チェーンでは他のC1修復パスが先に消費してしまい効かない、と判明し断念
  → `docs/history/3.4xx.md`
- backlog#26: 既定OFF専用修復腕5種（C2Polish/C42FlowPolish/C1成分修復/C3nMarginLnsPolish/
  CountChainPolish）の「対象違反が残る局面でだけ再活性化」メカニズムをgrillingで設計・実装（3.580.0）。
  `V6HotfixPasses.targetFamiliesRemain`（腕の自己申告カウンタでなく正式チェッカーのbreakdown生値で
  判定）＋各腕の新規`xxxReactivate`フラグ（既定OFF、挙動不変）。tools/loop配線・C1/C3n用の専用
  合成ケース新設・5腕の再ゲートは残作業 → `docs/history/3.4xx.md`
- design-lint P10違反を修正（3.579.0、PR #201 CI赤対応）。`Ws1Editor.kt`の削除確認ダイアログが
  「休」を生文字列比較していたのを`Ws1View.restIdx`（`restShiftIndex`経由）へ置換。P10は
  baseline 2件に復帰、hosttest 803テスト緑 → `docs/history/3.4xx.md`
- backlog#23完了: `docs/screen_spec.md` §08b・`docs/sudo_model.md`の陳腐化した重み表を現行値へ更新
  （3.578.0）。SOFT表「14種」→「15種」（`weekly`行追加）、HARD/SOFT両表を3.522.0以降の現行重みへ
  更新・重み降順に並び替え。コード変更なし → `docs/history/3.4xx.md`
- 探索エンジン改良ロードマップ（外部提案）の仕分け（3.577.0）。全工程正式最良保存(3.575.0)と
  削除時暗黙置換の封鎖(3.576.0)は実装済み、DebtBudget型は見送り済みと確認。残る提案（並列監査・
  OFF腕再活性化・停滞判定精緻化・重み予算分離）はbacklog#26へ登録、個別にgrilling＋tools/loop
  測定してから採否（コード変更なし）→ `docs/history/3.4xx.md`
- backlog#24: grillingで対応方針確定（3.576.0）。DebtBudget（探索のHARD一時負債許容）は見送り、
  3.416.0（休削除許可）は反転せず、`V6SanityPort`の既存診断2gで一般ケースは案内済みと判明したため
  対応を「休削除の確認ダイアログに警告文を追加」のみへ絞った（`Ws1Editor.kt`、UI層のみ・エンジン
  無変更）→ `docs/history/3.4xx.md`
- 最終番兵/多重防御を「入力vs最終結果」の2点比較から全段（入力/探索/統合/後処理）比較へ拡張（3.575.0、
  実機ログ起因）。旧実装は統合が入力より改善していても後処理(aptFairSoftTolerance等)が退化させると
  改善ごと入力へ丸ごと戻していた機会損失バグ。`StageCandidate`/`pickBestStage`で修正、
  hosttest 803件緑 → `docs/history/3.4xx.md`
- backlog#24（restShiftIndexの記号依存＋?:0退避）を実データ4件で再現・実害確認（3.574.0、調査のみ・
  コード変更なし）。「休」シフトを`Ws1Ops.removeShift`（通常の編集操作）で削除すると、4件とも
  hard激増（例: sept2026 0→60・golden 0→46、いずれも新規c3n）・weightedScore 5〜104倍悪化を確認。
  理論上の懸念でなく実データで実害を確認したためbacklog#24を深刻へ格上げ、対応方針はgrilling対象
  として保留 → `docs/history/3.4xx.md`
- FixApplyGate/FixSuggesterのHARD族相殺バグ修正＋weight_lint.pyのreverse-direction強化（3.573.0）。
  `docs/automation.md`が済としていた「担当外・希望固定・禁止連・個人固定の新規違反なし」は、実は
  HARD合計が辞書式先頭というだけで**族どうしの相殺**（covU解消と引き換えにc3n新規発生等）を防げて
  いなかった＝実データで再現・`MirrorCore.newHardFamilyViolation`で修正（探索本体は対象外、
  利用者向け「1手」提案・適用境界のみ）。`weight_lint.py`も「正本だけ重み変更し複製の更新忘れ」を
  検出できないreverse-directionの穴をmutation testで確認・族名ベースの値照合へ強化。
  backlog#22（branch protection）は完了申告と別監査の食い違いで再オープン、backlog#24（`restShiftIndex`
  の記号依存）は過去2度精読済みの設計と判明し実装保留で新規登録 → `docs/history/3.4xx.md`
- 外部監査の三重並列トリアージ（3.572.0）。重みリテラル静的検査`tools/weight_lint.py`を新設しCI配線
  （`MirrorKeys.weights`以外への直書きを検出、現状0件）。c3w追加(3.542.0)以来の文書族数「19→20」ずれを
  7ファイル20箇所で修正（過去時制の記録は不変）。`cleanup-artifacts.yml`の部分失敗可視化・
  `dependabot.yml`の同一メジャーgroup化を実施。main branch protectionは利用可能なツールで設定不可＝
  GitHub Web UIでの手動設定が必要と明記。backlog#14(g)（予算超過回帰試験）の打消し線漏れも是正
  （3.511.4で実装済みと判明）→ `docs/history/3.4xx.md`
- backlog #19（HARD同点タイブレーク乖離）の計測実装＋ペアベンチ（3.571.0）。`SaParams.officialTieBreak`
  （既定OFF・計測専用、選定ロジックは無変更＝`SaOfficialTieBreakTest`で固定）で `fullEval` 基準の現行選定と
  `betterReport` 基準の「もう一つの best」を同一実行内で並行追跡。実データ4件×10seed×20秒×4ワーカー＝40走行で
  **HARD退行0/40・weightedScoreの勝ちは0/40**＝採用基準未達につき既定動作は不変（決定は維持）。
  backlog #20（C# `PinInvariantTest` 赤2件）は根本原因3件（`C1IndexChainRepair`/`HF66`/`HF67`/`HF80` の
  厳密ピン保護ガード漏れ）を特定・修正——**Kotlin/C++は無変更**、C# 単独の移植漏れ（3.523.0 C1WindowPolish と
  同型）。`-MAGI_PC` commit `b1f4b64`、MagiEngine.Tests 856/856緑 → `docs/history/3.4xx.md`
- CI ワークフローの Actions を commit SHA へ全ピン留め（3.570.0、ユーザー決定）。7 種・29 箇所すべて
  `uses: owner/repo@<sha> # vN`（元タグをコメントで保持）。`.github/dependabot.yml` を新設し週次で更新 PR を
  自動生成（自動マージはしない）→ `docs/environment.md`
- CI 成果物の保持方針を精緻化（3.570.0、ユーザー決定）。通常の CI 成果物（lint・失敗ログ・debug/native-parity
  ビルド等）は `cleanup-artifacts.yml` が毎日 03:00 UTC 無条件削除（無変更）。**リリース APK（`app-release-*`）
  だけは対象から除外**し `retention-days: 14` を実際に保証（デバッグ鍵署名の動作確認用＝恒久保存は不要という
  根拠）。タグ成果物を GitHub Release アセットへ移す案は将来課題として backlog #21 へ（未着手）
  → `docs/history/3.4xx.md`（3.501.x 台のレビュー対応節）・`docs/environment.md`
- 末尾の待ち時間（3.569.0、ユーザー指示「不要なソートを消す・大きなテキストを生成しない・同じ検査を繰り返さない・
  数ミリ秒の仕事をまとめて渡す・直列だった解析や評価を安全な範囲で並列化する」）。**計測が先**: S=10・8s では末尾
  11〜98ms＝もともと小さい。実データ 60s では探索後 3.6〜6.0s が後処理（共同 LNS 2 本＋C1 広域ビームが単一スレッド
  で締切まで走る）。結果を変えない改修 7 件（盤面の文字列化ハッシュ→`BoardKey`／研磨の入口・出口の二重 check／
  `ChangeSummary`・VM・LNS の新規 `Problem`→`cachedProblem`／`logOp` の 1000 行再整形→1 行／autoSave の文字列化を
  main から外す／既に順序付いたリストの `sortedWith` 2 箇所／**共同 LNS 2 本の候補評価を並列化**＝生成・採否は
  逐次のまま `mapParallel`）。決定論モードの盤面ハッシュは実データ 4 件で旧新一致、時間 −17〜−40%。C# 同日同期 → `docs/history/3.4xx.md`
- UI 層の再構成 Stage C-4/C-5/D と外部レビュー由来の修正（3.563.0〜3.568.0）＝Composable の `vm` 参照 0・
  `key(ui.editRev)` 全廃、`MagiConditionsView` 追加。外部レビューで**再現できたものだけ**を修正:
  Worker の片付け漏れ／リリース CI の書込みトークン分離と `v*` タグの品質ゲート／`android-sdk.yml` の
  `permissions`／**`hosttest.sh` の壊れたキャッシュが永久に再利用される**（`.sha1` 照合を追加）／
  CSV 未知記号サンプルの頭打ち。**未閉引用符の中断・action の SHA ピン・P1-1 比較は据え置き**（理由は本文）
  → `docs/history/3.4xx.md`
- UI 層の再構成 Stage C-2/C-3（3.561.0/3.562.0）＝制約と ws1 の編集を Passive View へ。`ConstraintFamilyView`
  と `Ws1View` を `MagiViewModel` の入れ子から独立した型へ出し、Compose 非依存のビューデータ 2 ファイルへ。
  `vm` 参照 55 箇所と `key(ui.editRev)` 5 箇所を撤去。design_lint に **P13（公開宣言が internal 型を露出）**
  を追加＝ホストでは気づけずAndroidビルド 8 分まで分からない誤りを数秒で止める → `docs/history/3.4xx.md`
- UI 層の再構成 Stage C-1（3.560.0）＝Root に Mediator を配線。段階の表現を `MagiPhaseMachine` 1 つへ
  （ViewModel が所有・Mediator は読むだけ）、3 種のガードを `MagiArbiter` へ委譲。設定系 7 つを Passive View 化。
  **UI のコンパイル検証は `android-sdk.yml` を作業ブランチへ `workflow_dispatch` すれば取れる**（この回で
  public/internal の可視性エラー 2 件を捕捉） → `docs/history/3.4xx.md`
- UI 層の再構成 Stage B（3.559.0）＝派生描画値を `MagiViewState` へ単一化。不変条件テストが新しい不具合を
  自動検出: `needViolations` は最重1クラスのみのため、同じ重み10の c41s の陰に covO が隠れて
  シフト集計の▲が出ない日があった（`needFamilies` 経由へ修正）。キー符号化・必須判定・回数・日別を統合 →
  `docs/history/3.4xx.md`
- UI 層の再構成 Stage A（3.558.0、ユーザー指示「Root 配下・Passive View・CoR・ステートマシン Mediator」）。
  `MagiPhase`/`MagiArbiter`/`MagiEvent`/`MagiMediator` を Compose 非依存で追加しホストテスト白名単へ。
  着手前調査で同じ描画値の独立算出が 13 系統（日別不足/過剰が 3 ソース→6 画面、回数が 5 箇所、
  「必須か」の規則が 4 つ、キーの組立分解が 16 箇所）→ `docs/history/3.4xx.md`
- 日ヘッダの「▲N」が休の過剰で出なかった（3.557.0、実機報告「21日など日付の下に印がつかない」）。`buildDayRisks` が
  過剰の集計を不足用の `need<=0 continue` ガードの内側に置いており、下限0・上限ありのシフト（休）が常に捨てられていた。
  ヘッダと集計が同じ値を別々の源から出していた drift の実例 → `docs/history/3.4xx.md`
- 重み変更（3.556.0、HF77 明示指示）: グループのレンジ/ペア c41/c42 1→9、スキルグループのレンジ/ペア c41s/c42s 6→10、推奨の並び c3m 10→6。
  Kotlin/C++/C#・期待値 5 ファイル・docs を同時更新 → `docs/history/3.4xx.md`
- 休0日の窓LNS `RestZeroWindowLns`（3.555.0、ユーザー指示「28・29 日の休が減るように」）。休は夜勤ブロック末尾の強制休、空ける並びは
  「同じ人が 27〜29 の 3 連」だけ。夜勤列列挙＋人間移動＋ビームを実装したが実データは 30 日の休希望 5 人で成立せず採用 0、現行重みは
  29 日休 2 人の盤面を選ぶ（37816<37888）→ 既定 OFF（ユーザー決定）。残るレバーは明示 0 の日の重み → `docs/history/3.4xx.md`
- 人員過剰(covO)の退避研磨 `CovOReliefPolish`（3.554.0、ユーザー指示「B4 が増えるようにアルゴリズムを考えて」）。添付の手編集盤面
  （B4=5）が最適化器の結果より重み付き 151 良い＝重みでなく「基幹シフトの過剰→B4」単セル手の取りこぼし（HF67 の乱択頼み）。
  診断 diagnoseSurpluses と同じ手を修復として総当たり、後処理の最終段に配置（早期配置は tools/loop で経路が揺れ 8 勝 9 敗）。
  C# 同期 → `docs/history/3.4xx.md`
- 実機ログ精読と公平化の値の分解（2026-09-15、測定記録・コード不変）。必須 3 は希望どうしの衝突、B4 は 3.541.1 どおり重みか
  データのみ。fair 45（Sep-14 盤面）＝床 27＋減らせる 18。**生回数フォールバックが上限 0 の人を母集団に含める齟齬**（桐 Aｱ の床 9 のうち 8）を
  記録、修正は見送り（ユーザー選択）。診断ログの設定ミス `take(12)` 打ち切りも記録 → `docs/history/3.4xx.md`
- エラー・空状態・結果通知の規則を screen_spec §13 に明文化（外部 UX レビュー 3-3、文書のみ・挙動不変）。Snackbar/操作ログの
  役割分担・文言規則・ioReason・CSV 取込失敗 4 型・最適化失敗/停止・空状態。§00 の stale 文言も実装へ → `docs/history/3.4xx.md`
- 「計算」→「最適化」の適用漏れ回収（3.553.0、外部 UX レビュー 3-1）。3.520.0 の決定（実行状態だけ「最適化」、
  `未計算/計算済み`・`再計算` は別概念で残す）は不変、`work/` の通知・バブル・3.551.0 の busy 表示・仕様書「計算を止める」→
  「やめる」を回収。レビュー他項目の照合結果（1-x 既修正、3-2 事実誤認、2-3 E5 保留）も記録 → `docs/history/3.4xx.md`
- MAGI-Godot からのフィードバック取り込み（3.552.0）: `tools/check_apk_native_libs.py`（.so 非圧縮＋16KiB 整列を CI で検査）、
  `MagiStartupGuard`（起動段階マーカー＋例外記録＋次回起動の診断画面）、release upload の失敗可視化、lessons/environment/
  教訓 3 件。MAGI-Godot は 3.552.2 で削除済み＝相互互換の規則は撤回、取り込んだ機能は単体で維持 → `docs/history/3.4xx.md`
- 内部用語を一般画面から外す「破壊再構築」→「組み替え」・おまかせ解決先の説明・「SAチェーン数」（3.551.0、
  自動化方針 #13(i)）。進捗フェーズ名は既に非表示、`AlgorithmLabel.tech` は不変 → `docs/history/3.4xx.md`
- 色ピッカーの形をシフト色・違反色で統一（3.550.0、ユーザー提示モック）。族は行で表し文字見出しなし、
  `rowLabels`/`PALETTE_ROW_LABELS`/「色を選ぶ」を撤去、✓と余白だけで示す → `docs/history/3.4xx.md`
- 画面文言の類似語を統一「群」→「グループ」（3.549.0、ユーザー指示）。制約名も グループのレンジ／グループペア禁止／
  スキルグループ へ。CSV 種別タグ・コード識別子・技術略記は据え置き。決定は CLAUDE.md へ → `docs/history/3.4xx.md`
- CI: setup-android@v3 の既定 `packages: tools platform-tools` の旧 `tools` が SDK リポジトリから消え
  V6 Engine Check / Android SDK が落ちた件（3.548.0）。3 workflow に `packages: platform-tools` を明示 → `docs/history/3.4xx.md`
- シフト種別の色のみ外部へ出力・取込む機能を追加（3.547.0）。`ShiftColorsCsvIO`（記号,色）、
  取込はupsert（載っている記号だけ更新、他は現状維持）、違反色は対象外 → `docs/history/3.4xx.md`
- シフト色パレットへ「背景色系」家族を新設、公休を移動（3.546.0、ユーザー指示「公休は背景色系にする」）。
  中立グレー6色を新規生成（`tools/palette_bg_family_cud.py`）、特別枠は残り5区分に → `docs/history/3.4xx.md`
- 特別枠の1文字ラベルを撤回（3.545.0、ユーザー指示「1文字ラベル不要」）。`lastRowCellLabels`・
  `SPECIAL_FAMILY_LABELS` を削除、他家族と同じ表示に統一 → `docs/history/3.4xx.md`
- シフト色パレットをユーザー手指定36色へ差し替え、違反色パレットを分離（3.544.0）。早番=黄/日勤=青/
  時短パート=緑/遅番=紫/夜勤=インディゴの5段階×6家族＋事由別「特別枠」6区分（公休/有休/研修/出張/
  特別休暇/欠勤）。P型/D型シミュレーション後のΔEを狭い範囲で微調整（`tools/palette_shift_families_cud.py`）。
  違反色は別パレット30色（`tools/palette_violation_cud.py`）。design_lint.py P12 は2パレット独立判定へ
  → `docs/history/3.4xx.md`
- 全画面・全ポップアップの CUD（色覚多様性）見直し（3.543.0）。ColorPickerDialog の36色パレットを
  P型/D型二色覚シミュレーション後のΔEで7家族(背景色系/早番/日勤/時短パート/遅番/夜勤/違反アクセント)×6色=42色へ
  再設計（`tools/palette_cud_redesign.py`・`tools/cud_colors.py`）、日ヘッダ下線を実線/破線化、マトリクス
  フッターに▲▼追加、design_lint.py に P12（パレットCUD距離）を新設 → `docs/history/3.4xx.md`
- 新制約「希望の前日に禁止」cons3w/c3w（3.542.0、ユーザーと対話で設計）。希望(wishLocked)で固定した X の前日セルが Y なら
  違反、HARD 9000＝c3n 同格（HF77 明示指示）。`C3wRow(wishKigou, prevKigou)`・静的表 `Problem.c3wBan` を3者＋C++＋
  `makesForbiddenRun` が共有。希望どうしの連日は違反として数え診断 1b が案内。編集タブ⑤に族追加、CSV 種別「希望前日禁止」。
  759 テスト緑・C++ parity 0 mismatch・言語跨ぎ 5×20 族 MATCH → `docs/history/3.4xx.md`
- 「そのデータで B4 に移行できるアルゴリズムと重み」の検証（3.541.1）。厳密解で B4 の値段を測定: 4 セル +94（古泉の
  c1 +2 窓）、16 セル +21（休のリレー 4 段、c3m +3）、10/28 休ゼロは +724。単一重みの採用境界は covO≥32（HF77 の high>covO
  を破る）か c3m≤3、境界でも利得 −1〜0。古泉の窓ルール免除は covO を減らさない。CountChainPolish に covO 起点を追加
  （現行・変種重みとも B4 連鎖には届かず）→ 結論: レバーは業務データ（休 9〜10）か covO 受容 → `docs/history/3.4xx.md`
- fair 達成率モード v2（3.541.0、ユーザー指示・案E）。範囲外の個人（古泉 休 0〜1 で 6 回）や担当不可の 0 回が群の基準を
  歪め設定どおりの人を罰していた 3.538.0 初版を改定: 回数を帯へクランプ・担当不可(上限0)で0回は母集団から除外・基準達成率は
  幅を重みにした中央値。実データ fair 136→39（旧式 181）。Kotlin/C++ 同式、5 フィクスチャ更新、パリティ MATCH → `docs/history/3.4xx.md`
- 回数連鎖研磨 CountChainPolish（3.540.0、既定OFF・測定中）。ユーザー指示「A4/Cｱ の超過を研磨できるアルゴリズム」。
  作る前に CP-SAT で到達値を測定（105セルで 3954→3806、A4 の目標超過は構造的に残る）。同日 k=2/3 巡回交換を
  複数日で束ね、回数族＋連続系の負債 ≤0 の連鎖だけをチェッカーで keep-best。実データ 3954→3931（high −1）、
  golden −67。既存パス大予算は 0〜−6。トグル/params/LoopBench 配線、テスト 2 件。tools/loop A/B（138 ペア）は新2/同等135/旧1・
  HARD 退行 0＝4 ゲート不合格で既定 OFF 維持（golden で −1.7%、差が出たのは 3 ペアのみ） → `docs/history/3.4xx.md`
- 「休の過剰がB4に移行しない」の切り分け（3.539.0、測定記録）。fair側の許容（全探索6%許容・構造下限差引・
  mayPlace除外・無設定シフト除外）は直交＝fairを評価0にしても10/28の休5人は動かない（最適化器60s）。入力盤面の
  ≤3セル連鎖25本はfair免除でも採用0、low免除でも0、fair+low+c1免除で18＝壁はlow(120)/c1(50)/c3m(10)。
  3.521.0パターン3の再来、是正は業務データ（休の範囲/10/28-29の休=0）。周辺: 達成率モードが意図的な範囲外個人に
  引きずられ他メンバーを罰する（古泉rate 6.0→荒井偏差26、設定は意図した個別設定＝ユーザー確認）、実行#3の予算超過511s/300s、MUS診断「証明つき」がHARD=0盤面と矛盾。追記: 休以外の
  超過(high/aptHigh 25セル)もB4へ移せない（基幹は全日lo=hi=1→空いた枠を休の人が埋めてlow +120、連鎖2052本で採用0） → `docs/history/3.4xx.md`
- fair(公平化)の平均計算を達成率モード化（3.538.0）。ユーザー指示「平均はOlympic average類似の、
  目標値・個人上下限などの設定を配慮した達成率スコアで計算する」をgrilling6問で確定。範囲/apt目標が
  群全員に揃う場合だけ達成率（回数の位置/目標比）で比較、揃わなければ従来のround(平均)へフォールバック。
  `Problem.fairDevOfBucket`を共通ソース化しEvaluator/Delta/Checker/DestroyRepairMarginalCostの4箇所を
  統一（1箇所の改修漏れをテストで検出）。C++も同式へ移植、sept2026を達成率モード検証用の5件目フィクスチャ
  として追加 → `docs/history/3.4xx.md`
- aptFairSoftToleranceのtools/loop正式A/B結果（3.537.0）。46ケース×3seed=138ペアで辞書式
  旧68/新38/同等32＝ONが劣勢、速度-62%・タイムアウト12→32、下位10%品質も旧未満＝4ゲート全て
  不合格。c40-large-c2deficit1でON側のみ例外を観測したが単体再実行6/6で非再現（未解決・環境依存
  の疑い）。HF77の恒久機能指定のため撤去せず既定OFFを維持 → `docs/history/3.4xx.md`
- aptFairSoftToleranceの「設定の効き」テレメトリ欠落を修正（3.536.0）。実機ログでON時も痕跡が
  出ないと発覚（`TuningTelemetry.summary`への追加漏れ）。素のbetterReportなら却下される手を
  容認で採用に転じさせた回数だけを数えるカウンタを追加、他トグルと同型の1行に統合。エンジンの
  採否・重みは不変（読み取り専用） → `docs/history/3.4xx.md`
- 公平化/適切回数研磨に他ソフト6%許容オプションを追加（3.535.0）。ユーザー指示でkeep-best原則
  （採用基準を増やさない）の唯一の明示的例外として、`AptFairPolish.toleratedBetter`の累積予算方式
  （fair/apt改善のためHARD不増加を維持したまま対象家族以外のSOFT悪化を研磨開始時点比+6%まで容認）を
  既定OFFのUIトグルで追加。grillingで範囲を確定、tools/loop A/Bも実施 → `docs/history/3.4xx.md`
- 設定ミス診断6f: 必須/推奨(c3/c3m)と禁止(c3n)の連続数矛盾を検出（3.534.0）。ユーザー提示案の具体例は
  検証の結果誤り（L=3,N=4は矛盾しない）と判明、正しい条件（L≥N）で実装。実現可能性表示・自動修正は
  新規DP計算/HF77の安全基準に照らし見送り、検知のみ既存のSettingIssue枠組みへ追加（UI変更不要）
  → `docs/history/3.4xx.md`
- グループ一括設定: グループ別セクション化と一括解除を追加（3.533.0）。`groupRangeSummary()`の既存
  ソート順を利用しgroupByでチップをグループ別に整理、重複するグループ名接頭辞を除去、
  `clearGroupRangeSection(g)`新設。編集モード化・インライン追加UI・配色全面見直し・下部ナビ記号化は
  既存の統一パターン維持のため不採用 → `docs/history/3.4xx.md`
- 設定タブを静音化: 「直す優先順位」表(WeightTableCard)を既定で折りたたむ（3.532.0）。7節中
  唯一この表だけ既存の折りたたみ対策(ColorSettingsView、3.483.0)から漏れていたと判明し
  CollapsibleSectionで統一。他6節は対象外（根拠なき全面リフォーム回避） → `docs/history/3.4xx.md`
- 回数マトリクスに行単位の警告マーカーを追加（3.531.0）。ユーザー提示デザイン案の大半（薄色/濃色・
  =N表記・罫線排除）は実装済みと確認、行頭⚠マーカーだけ新規実装。下部ナビ記号化は見送り
  → `docs/history/3.4xx.md`
- 職員一覧をシフト種別/グループと同じ並び替え・削除の形へ統一（3.530.0）。▲▼ボタン→ドラッグハンドル
  （`ReorderableRows`を`internal`化して再利用）、独立した編集/削除ボタン→行タップ+編集ダイアログ内の
  削除（`StaffDialog.onDelete`新設）。ユーザー提示デザイン案をgrillingで既存の3.515.6パターンへ統一する
  形にスコープ確定 → `docs/history/3.4xx.md`
- 外部仕様書「決定論的修復統合基盤」を評価し3件だけ取り入れ（3.529.0）。SHA-256+TLV正規化・因果証拠
  構造体・新規safety/パッケージ・全操作への汎用Preview化は既存の軽量な仕組み(checkSeq/fixBoardKey/
  FixApplyGate/SaveGate/checkRev)と重複するため不採用。採用: ①applyAlternative()の鮮度チェック追加
  ②保存状態(SaveState)をUIへ表示 ③Undo履歴に操作名を表示 → `docs/history/3.4xx.md`
- 設定タブの既定OFFトグルはUIに残し、AB評価で既定ONへ確定した機構(blockSwapC3nFilter/lnsAdaptive)は
  UI表示から外す（3.528.0）。opt-outフラグ自体は温存。ユーザー指示「AB評価で確定した既定Onのフラグは
  UIに表示しない」→ `docs/history/3.4xx.md`
- 日付ヘッダーに人員過剰バッジ「▲N」を新設（3.527.0）。既存「▼N」（人員不足）と対称。
  `V6PortAnalyzer.V6DayRisk.surplus`新設・`DayHeader`（MagiScheduleViews.kt）で同じ行に並べて表示
  （ユーザー明示指示、grillingで配置確認）。実データで12日A4 surplus=1を確認
  → `docs/history/3.4xx.md`

- weekly（曜日平準化）の指標式を再定義（3.526.0）。旧`round(平均)`式は合計3回以下だと目標が0に丸まり
  「配置に関わらず偏差が合計回数のまま不変」＝同一曜日集中を検出できない死角があった（実データ調査で
  発覚）。新式`dev=Σ|7×曜日回数-合計|÷7`へ変更（HF77明示指示、重みは不変）。Kotlin/C++両方の
  `weeklyDevOfBucket`のみ変更・増分コスト計算は自動追従。golden/sample_v6/sept2026の期待値を更新
  → `docs/history/3.4xx.md`

- 3.524.0への/code-review指摘3件を修正（3.525.0）。`Evaluator.fullEvalParts`のbreakdown mapを
  `clear()`してC++側の初期化と対称化、`NativeParityFixtureTest`の期待値パーサが`#`コメント行で
  クラッシュしないよう修正、`host_parity_bench.cpp`の族別breakdownが1〜18件だけ揃った壊れたファイルを
  黙って2値照合へ後退させず fail-loud にした
  → `docs/history/3.4xx.md`

- native-parityの言語跨ぎ照合を族単位へ強化・tools/loopにc2deficit/c42pair追加（3.524.0）。backlog#6:
  `Evaluator.fullEvalParts`/C++`fullEvalParts`に任意の`breakdown`出力を追加し、19族(MirrorKeys.all)
  すべてを比較する`full_coverage_state.json`フィクスチャを新設（golden/sample_v6/blocked_covuは3件
  そろってapt=c41=c41s=c42s=0で死角だった）。backlog#12(b): `tools/loop`のケース生成にcons2不足2件
  以上・cons42違反ありの2分類を追加し、C2Polish/C42FlowPolishのiter15/18「検証不能」を解消（再測定は
  両パスとも旧腕と同点、既定OFF維持）
  → `docs/history/3.4xx.md`

- 3.522.0の3件の既知failureを解決（3.523.0）。PinInvariantTestの真因は`厳密ピン(staffRange lo==hi)`
  ではなく`希望固定(wishLocked)`セルの値保持違反＝`C1WindowPolish.applyC1IndexChainRepair`の候補日
  フィルタに他の全パスが持つ`!p.wishLocked(staff,d)`ガードが欠けていた（重み非依存の旧バグだが
  重み表全面見直しで探索経路が変わり顕在化）。修正して候補から除外。CombinatorialRepairTest/
  ViolationComponentRepairTestはcons41を4重複させ「単独不採用・結合で採用」の性質を復元
  → `docs/history/3.4xx.md`
- 重み表の全面見直し（3.522.0、HF77明示指示）。tools/loop 34ケース×10seedのbaseline対比ベンチマークで決定:
  groupViol/covU/c3n/pref(HARD)を10000/8000/7000/9000→11000/10000/9000/8000へ再配分、
  low/c1/c3/c3m/c3mn/c2/c41s/c42s を90/30/3/2/30/1/1/1→120/50/15/10/90/4/6/6、apt/fair/weeklyを1→4/2/2、
  covOを5→10へ（SOFT中「上限超過(high)>人員過剰(covO)」をoutcomeレベルでも保つため。high違反+42%→+3.4%まで
  解消・covUは3配分中最良を維持）。B1のaptHigh違反群(古泉/山本/佐藤)がapt/fair同重みの綱引きで解消不能
  だった実機ログを受けapt=4に改定（D3再改定）。副産物として`DestroyRepairMarginalCost.fairMarginalAt`が
  fair重みを内部適用していなかった潜在ギャップ(旧重み1では無害)を発見・修正。`MirrorKeys.weights`・
  `Evaluator.fullEvalParts`・`DeltaEvaluator`・destroy-repair/polish系4ファイル・`magi_native.cpp`5箇所
  → `docs/history/3.4xx.md`
- 設定ミス診断に検査6e追加: 希望件数>個人上限（grilling確定）。実機ログで「古泉 健一の有給（上限0・
  希望10件）」「荒井克枝のCｵ（上限0・希望10件）」がhigh違反として最適化後も残り続ける事象を調査し、
  希望固定(wishLocked)セルは最適化器のどのパスも動かさないため上限超過は解消不能と判明（不具合ではなく
  設定ミス）。6d（apt版）と同型の判定を`staffRange.hi`に追加、`V6SanityPort.forcedCountIssues`（3.521.0）
  → `docs/history/3.4xx.md`
- UX改善: UI用語統一・ゲーム要素廃止（ユーザー指示、STEP①現状把握→②改善検討→③実施検証）。
  フェーズ名バッジ「狩猟」→「未完成」、感嘆符/煽り表現を平易な文へ、「できあがり度」の残存2箇所を
  「解消度」（3.480.0で改称済みの正式語）へ統一。「最適化」vs「計算」の混在を「最適化を実行したか」の
  概念に絞って統一（別概念の「計算」＝違反数の再評価等は残す）。Kotlin/C#両実装＋
  `docs/operator_ux.md`等の用語集docsを同時更新（docsは実装より古い旧語「できあがり度」「守れていない約束」
  「できれば直したい点」を含んでいたため実装に合わせて修正）。史料（changelog/history/archive）は不変
  （3.520.0）  → `docs/history/3.4xx.md`
- 未計測だった2件のAB結果: combineExhaustPairsはiter24（170ペア）でlarge/infeasibleの1ペアに必須件数増を
  確認し既定OFFで確定（再提案しない）。personSwapKick(3.517.0)は新規ハーネスPersonSwapBench.ktで実データ
  4件×5seed×フルoptimize(PORTFOLIO)を実施し全20ペアで必須退行ゼロ・4フィクスチャ全てで負け越しなしを
  確認、既定trueへ昇格。ユーザー向け説明文の「結果は悪化しない」という不正確な記述も訂正（3.519.0）
  → `docs/history/3.4xx.md`
- 既定OFFの全トグルを棚卸し・4分類（既存測定で判断可／既に決着済み＝再測定しない／未計測／新機能）し、
  `filterC3nIncrease`（ON/OFFで最終盤面完全一致・速度のみ）と`lnsAdaptive`（iter9で品質±0・速度実データ-23%、
  既存結論「推奨ON」）を既定trueへ昇格。`PolishGate`とUiStateの既定乖離（表示と実態の不一致）を発見・修正。
  C#（-magi_pc）もFilterC3nIncreaseを同時昇格・dotnet test green（3.518.0）  → `docs/history/3.4xx.md`
- PORTFOLIO の新役割 PERSON_SWAP_ILS（全月入替→再最適化のILS摂動、grilling4問）を既定OFFで実装。
  同群2名の1ヶ月分割当を丸ごと交換→RSI+でフル再最適化。fair は交換不変（分析的に証明済み）で、
  改善は c1/c3/high 等の局所解構造が変わることで生じる（実データprobeで weightedScore 9831→9605
  を確認済み）。ペア選定はfair負担の大きい職員優先のヒューリスティック。`PolishGate.personSwapKick`
  既定false＝OFF時は既存6役割ローテーションがビット単位で不変。`tools/loop`の正式A/Bは未着手
  （3.517.0）  → `docs/history/3.4xx.md`
- HF77明示指示で上限超過(high)の重みを45→25に変更（設定タブの重み表で「上限超過を人員過剰と期間の制約の
  間に移動する」→数値指示25）。Evaluator/DeltaEvaluator/destroy-repair系polish(4ファイル)/`magi_native.cpp`
  （評価器+SaChunk+コメント）・言語跨ぎ期待値(Kotlin/C#)・テスト内重複リテラル(逆順パターン含む)を同期。
  `severityFromVioKey`/`heavySoftFamilies`のHIGH→WARN降格、`C1WindowPolish`関連テストの前提再設計を伴う
  （3.516.0）  → `docs/history/3.4xx.md`
- 編集タブ「シフト種別」「グループ」を行デザイン刷新＋ドラッグ&ドロップ並び替えへ（ユーザー提示モックアップ・
  grilling5問）: ▲/▼(3.515.3)から長押しドラッグへ（片手一本指の既定をこの2箇所限定で明示的に上書き）、削除は
  行から編集シートの中へ、グループの並び替えは新規実装（Ws1Ops.moveGroup）。TalkBackでは操作不可な既知の制約を開示（3.515.6）  → `docs/history/3.4xx.md`
- 設定・勤務表タブの文字サイズを意味階層に揃える（backlog #17、3.515.4の続き）: MagiSetupCards/MagiDashboardCards/
  MagiScheduleViewsのlabelSmall・label+太字見出しを章=titleMedium／節=titleSmall／本文・行=bodyMedium／補足=bodySmall
  へ統一。凡例・バッジ相当（ViolationLegend/TallyLegend/セル内注記）はlabel*のまま据え置き（3.515.5）  → `docs/history/3.4xx.md`
- 編集タブの文字サイズを意味階層に揃える: 節見出しがlabel+太字・説明も行もlabelSmallで全部14sp＝ボタンより見出しが
  小さい逆転を、章=titleMedium／節=titleSmall／本文・行=bodyMedium／補足=bodySmall／label=部品ラベル・チップのみに
  統一（DESIGN.md §3.3に規則を明文化。設定・勤務表タブは backlog）（3.515.4）  → `docs/history/3.4xx.md`
- 職員・シフト種別の並び替え（▲/▼）を追加: Ws1Ops.moveStaff/moveShift（removeStaff/removeShiftと同じ index 付け替え、
  記号参照は不変、端は no-op）＋Affordance.MoveRowButtons（片手一本指＝ドラッグなし）。Ws1OpsMoveTest 4件。
  C#同期は別途（3.515.3）  → `docs/history/3.4xx.md`
- 実機報告6件を修正: (1)群の目標ステッパーの+/-がセルタップシートを閉じる（key(ui.editRev)配下のremember破棄）、
  (2)曜日ヘッダの下線がneedViolations(シフト×日)のみの違反日に付かない、(3)covOセル詳細で希望固定してない
  在勤者が表示・操作できない、(4)違反ナビでジャンプしたセルをタップしても理由が出ないケースがある
  （(2)(4)はneedViolations未合算という同根）、(5)色ピッカーが選ぶと閉じる（4呼出元のonPickが明示的に閉じていた）、
  (6)分析タブの設定警告（cons3n重複「Dﾃ→A4」）が18文字で切れて勤務表の違反に見える（1件のときは場所＋理由を全文表示）
  （3.515.2）  → `docs/history/3.4xx.md`
- 3.515.0の外部レビュー指摘2件を修正: (1)FixSuggesterのfocusShiftは日を指定できず、別日の改善がこのセルの解消として誤カウントされ得た＝返った手が実際にday j自体を動かすか確認するよう修正、(2)covO深追いがpushReport本体（編集ロック解除・経過時間表示）を最大8秒巻き込んでいた＝deepCovODiagFollowUpとして別ジョブ化しpushReportを待たせない設計に修正（3.515.1）  → `docs/history/3.4xx.md`
- covO「なぜ減らないか」診断（V6PortAnalyzer.diagnoseSurpluses）を複数職員・別日の組合せまで探すよう拡張（ユーザー指示「複数の職員および過去未来の日も押し出し可能できるように」、既存のFixSuggester「直し方を探す」を再利用、実データでは今回の5件とも解なし＝low/high重み差による構造的トレードオフと確定。副次的にViolationComponentRepair/V6FinalPortの無駄なsurplus計算をincludeSurplus=falseで除去、deepSurplusは最適化完了直後のみtrueにしライブ編集診断への影響を回避）（3.515.0）  → `docs/history/3.4xx.md`
- combineExhaustPairs・lnsAdaptiveをUIの詳細設定からON/OFFできるように（ユーザー指示「フラグのOn,Offはユーザーが選択できるように」、不合格判定で凍結中のdebtLaneSlots等は対象外）＋シフト集計カードの既定開閉を展開へ戻す（3.483.0 S-4の反転、ユーザー指示「シフト集計は開く。閉じない」）（3.514.0）  → `docs/history/3.4xx.md`
- 最終番兵の復帰盤面バグを修正（handleOptimizeで番兵発火時にfinalSchedをcappedInputでなくnormInputへ戻していたため、finalReportは「上限0のセル除外済み」の集計を返すのに実際に返す盤面には元の上限0割当が残る食い違いが起き得た。実機報告「大島愛のDﾃが上限0なのに1件割当」から発見。sentinelScheduleへ抽出しユニットテスト、C#同日同期）（3.513.0）  → `docs/history/3.4xx.md`
- CombinatorialRepair.combineAndApplyのmaxStagnantTries固定200回を実機報告から緩和（プール56件でもC(56,2)=1540通りある2人組交換のうち13%しか試さず打ち切っていた。exhaustPairs/pairCap追加、既定OFF・opt-in、6箇所の研磨パスへ配線、未計測）（3.512.6）  → `docs/history/3.4xx.md`
- LiveScheduleCard「状態遷移」の変化件数が実機で常に0固定だった実機バグを修正（remember(cur)の計算ラムダ内でprevHolder[0]=curを書いていたため、Composeの「破棄コンポジションでも複数回呼ばれうる」契約に反し副作用が破棄回で先に効いてしまっていた。書き込みをSideEffectへ分離）（3.512.5）  → `docs/history/3.4xx.md`
- iter22/23 結果（分離条件）: debtLaneSlots（新良8/同等135/旧良27、品質-0.18%）・bestOfK（新良15/同等138/旧良17、品質-0.17%、必須増12件はlarge-infeasibleに集中）はいずれも不合格。ユーザー判断で検証・追加実装を中止、既定OFFのまま凍結（WeightDebt/ConstraintRepairInference/familyPriorityScoreに続く同系統4度目・5度目の不合格）（3.512.4）  → `docs/history/3.4xx.md`
- debtLaneSlots/bestOfK: trimFrontierのビーム縮小バグ修正（負債候補0件でも非負債候補の枠がbeamWidth-debtLaneSlotsに固定され純粋な退行を起こしうる構造をユーザーの机上テストで発見）＋LoopBenchのdebtlane/bestofkフラグをレバー単体で分離測定できる条件へ修正。旧iter22/23は交絡した参考値のまま保存し採否判定には不使用。host JVM 全721テストgreen（3.512.3）  → `docs/history/3.4xx.md`
- iter21 結果: quantitativeRangeEval（C2/C41/C41sの量的評価、backlog #12(a)）は170ペアで新良5/同等153/旧良12、品質±0.00%・速度-1.1%とも不合格、必須件数が増えた試行6件はlarge-infeasibleカテゴリのみ（他15分類は完全無風）＝ゲート不合格、既定OFF維持（3.512.2 追記）  → `docs/history/3.4xx.md`
- V6FinalPort.handleOptimizeのトレーリングラムダ誤束縛を修正（backlog #12(a)でonProgressの後にquantitativeRangeEvalを追加したため本番呼出2箇所がAndroidビルドだけ壊れていた、EliteRelinkingで一度踏んだのと同じ罠の再発、host JVMでは検出不能=v6-engine-checkで発覚）。回帰テスト追加、host JVM 718テストgreen（3.512.1, backlog #12(a)）  → `docs/history/3.4xx.md`
- 違反連結成分修復の二車線ビーム debtLaneSlots と族優先度の順序非依存化 bestOfK（ユーザー提示「適応型制約違反研磨エンジン 完全統合実装仕様」v2.1の核だけを既存コードの構造的欠陥として取り出した実装、重い機構=結合度負債テンソル・Restless Bandit・並列トランザクションは§20の解除条件未達のため見送り）。既定OFF・未計測、host JVM 717テストgreen（3.512.0, backlog #12(b)/#15）  → `docs/history/3.4xx.md`
- iter20 結果: C1RepairAnalysis.solveComponentは170ペア全件で新旧同等（合成ケースに複数cons1窓の近接局面が無く検証不能、c42flow/iter18と同型）＝ゲート不合格、既定OFF維持（3.511.9 追記）  → `docs/history/3.4xx.md`
- C1重複窓の連結成分化 C1RepairAnalysis.solveComponent（同一職員の複数cons1窓を区間重なりでグラフ化し連結成分へ分割、休/夜2ルール重複でsolveWindowが後の窓を解けなくなる局面を解消）。既定OFFのhash 4/4不変、iter20で不合格・既定OFF維持（3.511.9, backlog #12(b)）  → `docs/history/3.4xx.md`
- iter19 結果: C3nMarginLnsPolishは170ペアで新良1／同等168／旧良1＝品質±0.00%とほぼ無風（合成ケースに専用局面の仕込みが薄い）＝ゲート不合格、既定OFF維持（3.511.8 追記）  → `docs/history/3.4xx.md`
- C3n（禁止連続）の前後余白込みLNS C3nMarginLnsPolish（違反パターン日+前後marginDaysを複数セル同時destroy-rebuild、1セル付け替えが届かない局面を拾う）。既定OFFのhash 4/4不変、iter19で不合格・既定OFF維持（3.511.8, backlog #12(b)）  → `docs/history/3.4xx.md`
- iter18 結果: C42FlowPolishは170ペア全件で新旧同等（合成ケースにc42違反の仕込みが無く検証不能、C2Polish/iter15と同型）＝ゲート不合格、既定OFF維持（3.511.7 追記）  → `docs/history/3.4xx.md`
- c42/c42s（群ペア禁止）専用 min-cost-flow研磨 C42FlowPolish（c42PairCountは真に凸のためc41のような誘導コスト置換は不要、片側固定ヤコビ近似＋対称2試行）。既定OFFのhash 4/4不変、iter18で不合格・既定OFF維持（3.511.7, backlog #12(b)）  → `docs/history/3.4xx.md`
- iter17 結果: familyPriorityScore は170ペアで新良10／同等143／旧良17＝品質−0.26%、必須が増えた試行9件・個別10%超退行2件＝ゲート不合格、既定OFF維持（3.511.6 追記）  → `docs/history/3.4xx.md`
- 族選択を件数×重み×改善可能性へ familyPriorityScore（ViolationComponentRepair.anchors、セグメント内のみソート、HARD優先の境界は死守）。既定OFFのhash 4/4不変、iter17で不合格・既定OFF維持（3.511.6, backlog #12(b)）  → `docs/history/3.4xx.md`
- iter16 結果: C41FlowPolish は170ペアで新良21／同等143／旧良6＝品質+0.14%・速度+1.1%とも10%未達、必須が増えた試行2件＝ゲート不合格、既定OFF維持（3.511.5 追記）  → `docs/history/3.4xx.md`
- C41/C41s専用 min-cost-flow研磨 C41FlowPolish: FlexibleDayFlowを群内サブセットへ再利用。二値指標の生差分は非凸でMCMFが誤って先取りする発見（RangePolish型の凸誘導コストへ置換して解消）。既定OFFのhash 4/4不変、iter16で不合格・既定OFF維持（3.511.5, backlog #12(b)）  → `docs/history/3.4xx.md`
- 予算超過の回帰試験 BudgetOverrunTest（自然満了3秒枠・外部停止5秒以内・例外ワーカー2秒以内）を追加。RSI_PLUS のフェーズ予算按分に35秒下限を発見（極小予算のみ、production の300秒規模では無傷）＝記録のみ、探索コードは無変更（3.511.4, backlog #14(g)）  → `docs/history/3.4xx.md`
- iter15 結果: C2Polish は170ペア全件で完全に無変化（合成ケースのcons2は不足最大1件でバッチ化の優位性を試せていない、実データでも自己変換だけでは届かない残りのみ）＝ゲート不合格、既定OFF維持。教訓: 無変化=検証不能、deficit≥2のケースが必要（3.511.3 追記）  → `docs/history/3.4xx.md`
- iter14 結果: 循環交換のk=4,5拡張は170ペアで新良1／同等169／旧良0＝必須退行0だが品質・速度10%未達でゲート不合格、既定OFF維持（3.511.2 追記）  → `docs/history/3.4xx.md`
- C2（個人合計）専用研磨パス C2Polish: 不足日をまとめて一括適用・1回だけ判定（c2は二値フラグのため1件ずつでは同点却下される）。T=5の小盤面ではweeklyの悪化で相殺される教訓、T=31で解消。既定OFFのhash 4/4不変、iter15で採否（3.511.3, backlog #12(b)）  → `docs/history/3.4xx.md`\n- iter12 結果: 長期ブロック交換の動的長 dynamicBlockLengths は170ペアで新良0／同等169／旧良1（大・充足不能ケースで必須+1）＝ゲート不合格、既定OFF維持。単一パスのkeep-bestはチェーン全体のkeep-bestを保証しない経路依存の一例（3.511.0 追記）  → `docs/history/3.4xx.md`
- 循環交換の k=4,5 拡張（既存 k=2,3 全列挙はそのまま、4/5人はランダム試行で追加。全列挙は組合せ爆発するため避けた）。4職員の環で2/3職員では届かない改善を固定するテスト追加。既定OFFのhash 4/4不変、iter14で採否（3.511.2, backlog #12(b)/#13(e)）  → `docs/history/3.4xx.md`
- 停滞時の探索幅拡大 StallEscalationConfig（巡回研磨クラスタが1巡も採用0のときだけ共同LNS/成分修復の幅を2倍で1回だけ再試行）。既定OFFのhash 4/4不変、iter13で採否（3.511.1, backlog #12(b)/#13(a)）  → `docs/history/3.4xx.md`
- 長期ブロック交換の候補長を動的化 dynamicBlockLengths（違反窓長・禁止連長・希望島半径・当月日数から導出、固定28が2月以外で当月まるごとに届かない穴を修正）。既定OFFのhash 4/4不変、iter12で採否（3.511.0, backlog #14(c)）  → `docs/history/3.4xx.md`
- iter11 結果: 成分修復の一時負債予算 ConstraintRepairInference は 170 ペアで新良 8／同等 150／旧良 12、実データ 20 ペア全件同一、充足不能ケースで必須 ±（増 6・減 4）＝ゲート不合格、既定 OFF 維持（3.510.5 追記）  → `docs/history/3.4xx.md`
- iter10 結果: 共同 LNS の WeightDebt は 170 ペアで新良 0／同等 155／旧良 15、必須 8→10 が 1 件、品質 −0.16%＝ゲート不合格、既定 OFF 維持（3.510.4 追記）  → `docs/history/3.4xx.md`
- 違反連結成分修復の一時負債予算 ConstraintRepairInference（起点の族の 重み×現在量×2.0 を SOFT 負債の上限、HARD 悪化の枝は外す）を opt-in 実装。前提訂正: 既存ビームは悪化ノードも展開していた。4 実データ最終盤面 4/4 同一・推定 40〜70% 減＝iter11 で採否（3.510.5, ユーザー仕様 v3）  → `docs/history/3.4xx.md`
- 共同 LNS の一時負債を重みで絞る WeightDebt（負債 ≤ クレジット×2.0、HARD は件数予算のまま）を opt-in 実装。4 実データ: 2 件同一盤面で 26〜30% 速く、sample_v6 重み −480、sept2026 +5＝iter10 で採否（3.510.4, backlog #15(f)）  → `docs/history/3.4xx.md`
- 机上評価「動的重み対応・制約負債型複合修復」（設計 v2）: 最終採用は既に重みのみ、追従しないのは中間の件数 debt 3 箇所・固定予算・3 言語のリテラル。凸型負債は採用 31 件中 1 件却下、由来倍率は不要（D9 で 90 倍済み）、予算配分は下限が要る（3.510.3 補遺）  → `docs/history/3.4xx.md`
- 族別 Credit/Debt の可視化: ChangeSummary.familyDeltas/familyLine（改善した族・悪化した族と重み付き合計）を完了カード 2 行目と「後処理 収支」ログへ。採否判定は不変、後処理ハッシュ 4/4 一致（3.510.3, backlog #15(a)）  → `docs/history/3.4xx.md`
- 机上評価「制約クレジット台帳付き複合修復」（ユーザー提示設計）: Credit≥Debt は betterReport と同式、一時負債・候補盤面分離・共通ゲートは既存（成分修復/共同 LNS/adoptionGate）。件数上限＋Total 厳密改善は 4 実データの採用 31 件中 12 件（39%）を却下＝入れない推奨、明示個人範囲の保護は影響 0（3.510.2 補遺）  → `docs/history/3.4xx.md`
- 共同 LNS の短い試行→採用時だけ本予算（lnsAdaptive、既定 OFF）を実装し iter9 で測定: 退行 0・品質 ±0・速度 平均 +10%（p90 −20%、実データ −23%、メモリ 0.64 倍）だが中央値 −1.5%＝規則上は不合格、既定 ON はユーザー判断（推奨 ON）（3.510.2）  → `docs/history/3.4xx.md`
- iter8 結果: C3 選択日ペア交換は 170 ペアで新良 11（全部実データ）／同等 158／旧良 1、品質 +0.04%・速度 ±0＝ゲート不合格、既定 OFF 維持（3.510.1）  → `docs/history/3.4xx.md`
- C3「選択日ペア交換」C3PairMaskPolish を最終段の低予算パスとして実装（違反日を含む非連続 1〜3 日の同日交換、c3n 枝刈りで正式評価 1/5、既定 OFF）。4 実データで golden 採用 2（重み −0.7%）。採否は tools/loop iter8 で（3.510.0）  → `docs/history/3.4xx.md`
- 研磨パス共通の採用ゲート adoptionGate（改善判定→改善時だけ厳密ピン判定）へ 18 箇所を統一。挙動不変（後処理 4/4 一致）。C# 同型（3.509.5）  → `docs/history/3.4xx.md`
- 改善提案の適用直前ゲート FixApplyGate（仮盤面で完全再評価、辞書式改善・固定不変のときだけ反映、見送り理由を表示）と完了カードの前後比較 ChangeSummary（変更人数・セル数・希望充足・個人回数）。自動化 UX 評価 7 項目の対応を automation.md §6 に（3.509.4）  → `docs/history/3.4xx.md`
- 自動化方針（ユーザー提示）を docs/automation.md に実装との対応表（済/部分/未）として固定。制約 CSV の個人レンジ行は非負整数・下限≤上限のみ受理（3.509.3）  → `docs/history/3.4xx.md`
- 日割当研磨（厳密/交互）の apt 目標を生の groupShiftApt から Problem.apt（実効目標）へ統一＝D9・到達クランプ・担当可ゲートと整合。「頭打ち＝改善手なし・正常」を「現在の探索範囲では改善手なし」へ。外部レビューの評価定義・探索動学・UX 提案は backlog #12 へ集約（3.509.2）  → `docs/history/3.4xx.md`
- 外部レビュー一覧の仕分け＝Android で既に済んでいた 6 項目を確認し、残りを修正: 負数の個人回数を未設定扱い（D9 に数えない・Sanity 2h で案内）、CSV ヘッダ判定を構造で（先頭未知職員をヘッダ扱いしない）、停止ログの到達不能分岐、「全員に適用」文言。C# は加えて CSV 例の複数表示・クイック解決の入力欄同期・アクセシビリティ名・言語跨ぎ契約テスト（3.509.1）  → `docs/history/3.4xx.md`
- 決定 D9: 個人の下限/上限がある (職員,シフト) には群目標（apt）を適用しない＝個人設定だけを適用（ユーザー再指示）。空欄キーは未設定扱い、別職員・別シフト不干渉、二重計上なし。到達範囲クランプは適用される組にだけ残す。期待値 3 ファイル更新・C++ MATCH・C# 同期（3.509.0）  → `docs/history/3.4xx.md`
- 自律レビュー第11段 UI 層＝エンジン契約との接点。「なおすのを手伝って」の候補職員を canDo→mayPlace（上限 0 の職員を出さない）、他 5 接点は整合。全面精読は実機ビルド待ち（3.508.2）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第10段 V6HotfixPasses 各パス内部＝HF80 の localBestImprovement を「その場で 1 セル書き換え→不採用なら戻す」に（試行ごとの盤面コピーを撤去、Problem/Evaluator は入口で 1 回）、runPolishCluster は cachedProblem。seed 固定プローブ 48/48 一致、HF80 の検査 3 件追加。エンジン層の精読は一巡、残りは UI 層（3.508.1）  → `docs/history/3.4xx.md`
- 適切回数（apt）の群目標を「構造的に到達できる範囲」へクランプ＝桒澤 B4「目標 1／現在 17」の幻の超過の恒久対策（到達下限 T−Σ他シフト実効上限・希望固定込み、個人 [lo,hi] 優先）。評価は実効目標 apt、診断 6b/6d/6-C は設定値 aptRaw。実データで動くのは 9 組のみ、期待値 3 ファイル更新・C++ MATCH・C# 同期（3.508.0）  → `docs/history/3.4xx.md`
- 机上評価: C3「選択日ペア交換」／C1「連結成分・選択日交換」（ユーザー設計案、実装せず）＝机上例はペア交換限定でのみ局所障壁、既存 C3Sequence が c3n 単独違反者を起点にしない隙間を発見。実データ 4 件の最終盤面に残る 2 職員×≤3 日交換の改善手は golden −2.9%（非連続 2〜3 日、c3mn）・他 ≤0.2%。推奨: C3 版のみ最終段の低予算パスとして測る、C1 版は見送り（3.507.9 補遺）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第9段 V6SearchOperators＝2 パス乱択 12 箇所を pickUniform へ、findC41Fix/findC41sFix を findGroupRangeFix に統合、厳密ピン判定を共有、隣接日修正の不変量計算をループ外へ。seed 固定プローブ 72/72 一致、find*Fix の検査 6 件追加（3.507.9）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第8段 CSV 層 ScheduleCsvBridge＝4 実データで parse(build) の完全再現を固定（勤務表/職員/希望/制約、エスケープ文字含む）。数値でない個人レンジは取込で弾かず Sanity 2h に任せる決定を検査で固定。C# の 3.474.0/3.475.0 未同期 5 点を同日同期（3.507.8）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第7段 V6LateOperators＝improve（400 行）を LateSession＋chainSwap3/4・rectSwap2・c1BlockN へ（RNG の呼び出し順を保持）、RectSwap の違反者抽出を baseViolators に統一、isBalanceable をシフト別に記憶。seed 固定・締切遠方で 48/48 一致（3.507.7）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 7 報告＝決定的モードで再現性 34/34（VM 再起動で JVM が 2 回消え、再開可能にした続きも同一盤面）・新良 179/同等 150/旧良 11・品質 +1.81%・必須が減った試行 61。タイムアウト 19 行は c25/c26（予算を無視する設計の名目超え）。ベンチの再開（済み行を飛ばして追記）と grep の行バッファ（3.507.6）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第6段 V6SanityPort＝buildGuidance（689 行）/buildViolationDebug（316 行）を GuidanceBuilder/DebugBuilder のメソッドへ機械分割、表示上限を Guidance に集約。「担当できる人数」を placeableFor（mayPlace＋その日の希望固定）で数える（forcedCovU/構造 HARD 下限/検査 3/c1 作業需要/代用可）＝3.507.0 の取り残し。設定ミス 24/24・違反詳細 20/24 一致（4 件は代用可の意図した減少）（3.507.5）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第5段 V6SwapSuggester＝FixSuggester を Session 化（7 フェーズを関数へ、上限を Limits に集約）、連鎖に mayPlace ガード、再最適化の冗長評価と別日交換の同日重複列挙を撤去。4 実データ × 5 設定で提案一覧 20/20 一致。C# のドリフト 2 件（休 index 0・署名に日なし）を修正（3.507.4）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 7 第一弾＝後処理の決定的モード（`deterministic`: hf67/hf66 の ms キャップ・巡回クラスタの締切・共同 LNS の maxMillis/patience・最終段の残り時間判定を回数上限へ。C1 90,000・個人 60,000 で時間モードと 4/4 同一盤面、2 回とも再現）。実機は既定 OFF。ベンチ iter7 走行中（3.507.3）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 6 報告＝ピン対応生成で新良 163/同等 169/旧良 8・必須が減った試行 64（最大 -38）・品質 +1.84%（大規模の充足不能 +11.3%・中規模 dense +2.2%）・速度 -3.1%。退行ゼロ合格、10% ゲート未達。再現性 32/34 は同時実行の CPU 競合。§4 の 14 機能回帰スイート `LoopFeatureRegressionTest` を新設し機能同等性 14/14 を初めて計測（3.507.2）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 6 第一弾＝起点生成をピン対応に（lo=hi を単独で崩す単セル→行内入替、同日交換→2 日交換）。ピン枝刈り golden 775→256・sample 1421→61・sept 272→98、sample_v6 は採用 3→9・total 337→332、他 3 件は同一。ベンチ中（3.507.1）  → `docs/history/3.4xx.md`
- 個人上限 0（0〜0）のシフトを最適化器が置かない＝`Problem.mayPlace`（候補生成 Kotlin 101 箇所・C++ 28 箇所・C# 101 箇所）＋入口 hf66 で既存セルを外す＋最終番兵の基準を「外した入力」に。評価・表示・重みは不変。再現データで必須 0 のまま 桒澤/大島 の該当が 0、4 フィクスチャは旧新同一、C++ パリティ 0（3.507.0）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 5 報告＝半径拡大＋最終段予算で新良 153/同等 179/旧良 8・必須が減った試行 62（最大 -38）・増 1・品質 +1.72%（大規模の充足不能 +11.5%）・速度 -2.3%・再現性 34/34。退行ゼロ合格、10% ゲート未達。次は決定的予算のベンチと生成候補の質（3.506.1）  → `docs/history/3.4xx.md`
- 「グループ単位の回数」で下限・上限とも「なし」を適用＝グループ全員ぶんの個人上下限を解除（`clearGroupRangeAll`、実機報告「なし 設定出来ない」）（3.506.0）  → `docs/history/3.4xx.md`
- CLAUDE.md を Claude 5 世代向けに見直し＝376 行→84 行（手順・履歴・バックログを docs/environment.md・docs/backlog.md・architecture.md・topics.md へ移動、重み規約を .claude/rules/weights.md へ、禁止形を判断基準へ）（3.505.9）  → `docs/history/3.4xx.md`
- CLAUDE.md の見直し提案（記事の 7 ステップ、docs/claudemd-tune-proposal.md＝承認待ち）と事実の訂正（重み c1/c3mn 30・covO 5、fork 名、ホスト JVM でビルド可、検証ハーネス節）（3.505.8）  → `docs/history/3.4xx.md`
- SUDO モデル（docs/sudo_model.md）を実装と再照合＝重み c1/c3mn 30・covO 5、O の実測 5015、欠損セル -1、S に Windows 版、D に拒否候補の再利用（3.505.7）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 5 第一弾実装＝起点生成に同長区間交換と同日 3 職員巡回、最終段の予算を残り時間で 4 倍。実データ 4/4 盤面同一。ベンチ中（3.505.6）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 4 報告＝起点生成の最終段で新良 152/同等 180/旧良 8・必須が減った試行 52（最大 -20）・増 1・品質 +1.07%（充足不能 +6〜8%）・速度 -1.8%。退行ゼロのゲートを初めて合格、10% ゲートは未達。次は生成の半径拡大（3.505.5）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 4 第一弾実装＝違反起点からの候補生成（半径 1）。巡の中で生成すると単セル covU 修正が共同 LNS の余地を先に使い sample_v6 が HARD 8→11 に退行したため、生成つき修復は共同 LNS の後の最終段へ（4/4 非退行）。ベンチ中（3.505.4）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 3 報告＝新良 71/同等 245/旧良 24・必須退行 0（減 6・増 4、最大 -9）・品質 +0.11%・速度 -3.1%・メモリ 1.18 倍＝10% ゲート不合格。改善は飽和＝ハイブリッド併用を維持してループを閉じる推奨（3.505.3）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 3 第一弾実装＝相方なしピン破りの事前除外・構造的に埋められない covU 起点の後回し。実データ 4/4 盤面同一、推定回数は最大 1/2（3.505.2、ベンチ中）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 2 報告＝違反起点のトランザクション修復を 340 ペアで比較。必須退行 0・新良 68/同等 249/旧良 23・品質 +0.08%・速度 -3.3%・再現性 33/34＝10% ゲートは不合格→§6 ハイブリッド併用として既定 ON（C# 同期）。次反復の候補 3 点（3.505.1）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 2 第一弾＝違反起点のトランザクション修復 ViolationComponentRepair（拒否候補の横断プール・DeltaEvaluator 推定＋ピン事前枝刈りのビーム・正式 commit・既定 OFF）。実データ sample_v6 で HARD 9→8。外部ドッグフーディング 4 件（ISCC 終了コード・RoundRobin 例外経路・月初月末テスト・ビーム盤面重複排除）も反映（3.505.0）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 1 報告＝希望島＋アンカー窓交換 vs 旧チェーンを 340 ペアで比較。必須ゼロ率 35.3%→35.3%・品質 平均 -0.18%・速度 -0.8%・再現性 33/34＝ゲート不合格→§6 ハイブリッド併用で温存、断捨離せず、改善飽和として閉じる推奨。ハーネスは tools/loop/（3.504.2）  → `docs/history/3.4xx.md`
- 外部提示 C# 6 ファイルの優劣検証と移植＝希望島ビームの島巡回＋残り予算で頭打ち（単体 1 改善/11 不変・後処理 4/4 不変）と C1 ビームのハッシュ鍵 dedup・try/finally 巻き戻しを採用、公平分配/枝刈り計上/root 初期化/族ゲート/OpsAreValid は計測つきで否決。提示 RoundRobin の破棄漏れをテストで捕捉（3.504.0）。最終コードレビュー 5 件を反映＝ビームの走査枠は評価した手だけ数える・KDoc 修正・beamMoves 一本化（3.504.1）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第4段 V6PortAnalyzer＝diagnoseCoverage を不足/緩和案/過剰の3関数へ分割、Probe 定数・chainFills/spreadTerm/parseKeyPair 共有化。出力ハッシュ 24/24 一致（3.503.0）  → `docs/history/3.4xx.md`
- バックログ #9(c)(d)・#10 を実装＝希望島ビームを「走査 2 倍・良い順に保持」へ（計測は中立）、担当外希望の一括クリアを canDo 再判定へ、「なおすのを手伝って」の連打防止を検査世代 checkRev へ。#4 は運用で解消と確定（3.502.0）  → `docs/history/3.4xx.md`
- 希望島研磨の候補配分を変更＝禁止連は件数が増える手だけ枝刈り・同日/窓/両翼を交互評価・島の枠 25% を巡回に確保（3.501.0, ユーザー指示。単体は混在・後処理全体の最終盤面は 4/4 不変）  → `docs/history/3.4xx.md`
- 外部レビュー第3・4段の Android 同期＝`setCell`/`setCells` にシフト index の上限検査、案内付き修正の連打防止（同型）をバックログ #10 へ（3.500.2）  → `docs/history/3.4xx.md`
- 外部レビュー（-MAGI_PC 側 2 段）の Android 同期＝Undo/Redo で engineRan を落とし改善提案を破棄、希望島の評価配分・c3n 枝刈り・ビーム列挙順・担当外希望の再判定をバックログ #9 に登録（3.500.1）  → `docs/history/3.4xx.md`

版数付きの作業記録（355節）は**この索引だけを常時読み込み**、本文は `docs/history/` に置く
（毎ターンのコンテキスト固定費を減らすため。3.468.0 で分離を始め、3.470.0 で本体から全部出した）。
バケツは版数で分かれる: `2.x.md` / `3.0xx.md` / `3.1xx.md` / `3.2xx.md` / `3.3xx.md` / `3.4xx.md`。
**同じ領域を触る前に必ずここを検索する**＝過去に測って否決した案・同型のバグ・決定記録を二度踏まないための索引。
本文の引き方: `grep -n '（3.409.21' docs/history/3.4xx.md` のように版数で引き、その節を読む。
見出しは原文のまま（版数・補足・ユーザー指示の引用を落とさない）。

- 後処理オーケストレータ（V6HotfixPasses.runPostOptimization）の自律レビュー＆リファクタ＝PostChain ランナー（採用・ピン帰属合流・ログ・計時を 1 経路に）・PostOptimizationParams 集約・SeedTag・クラスタ/検証ログ/診断の分離（3.500.0, ユーザー指示「自律レビュー＆リファクタリングループ」第3段、4 実データで最終盤面一致、24 サイトの定型を撤去）  → `docs/history/3.4xx.md`
- 可変長ブロック交換（AdaptiveBlockSwapPolish）の自律レビュー＆リファクタ＝Session 化・KeepBest/RejectStats の共通化・Priority/Params 集約・厳密窓交換の重複排除を方向フィルタの後へ（3.499.0, ユーザー指示「自律レビュー＆リファクタリングループ」第2段、fixture 24 ケースで盤面一致、旧実装が捨てていた窓が各条件 1〜9 件回復、C# は Step B と重み出典のみ同時）  → `docs/history/3.4xx.md`
- 希望島研磨（WishIslandPolish）の自律レビュー＆リファクタ＝候補の遅延生成・Session 化・パラメータ集約・不活性島の評価スキップ（3.498.0, ユーザー指示「自律レビュー＆リファクタリングループ」、fixture 12 ケースで盤面一致を確認、C# も同時）  → `docs/history/3.4xx.md`
- CLAUDE.md の常時読み込み分を 5.8 万→1.2 万トークンへ＝索引と話題別叙述を docs/history へ（3.497.3, docs のみ, ユーザー提示の記事2本→ AskUserQuestion で「索引と叙述節を全部 docs へ」を選択）  → `docs/history/3.4xx.md`
- ナレッジグラフ4ツールを実測して Serena だけ試験導入（3.497.2, docs/設定のみ, ユーザー提示の記事「トークン2000分の1——オントロジー×ナレッジグラフで Claude Code の推測を消す」→ AskUserQuestion で「Serena だけ」を選択）  → `docs/history/3.4xx.md`
- 追加コメントの点検を手順化＝comment-check スキルと tools/comment_ratio.py（3.497.1, docs/tools のみ, ユーザー提示の記事「ルールではなく skill に指示を書くことで、Claude のコメントを減らせた」）  → `docs/history/3.4xx.md`
- エンジンの締切・経過・停滞判定を単調時計へ（3.490.0, レビュー第6弾＝Android 側は1件が該当）  → `docs/history/3.4xx.md`
- 個人の下限を 0 に設定できなかった（3.489.0, 実機報告「設定出来ない」→「個人の下限をゼロに出来ない」）  → `docs/history/3.4xx.md`
- 読めない startDate を読込で拒否＋groupShiftApt を読込で G×K に正規化（3.488.0, レビュー第5弾）  → `docs/history/3.4xx.md`
- 原子置換のフォールバック前に所有権を再確認＋成果物掃除の全件失敗を赤に（3.487.0, レビュー第4弾＝新規2件）  → `docs/history/3.4xx.md`
- 読込時に endDate を日数へ正規化＋Android Lint を CI で実行＋失敗ログ条件（3.486.0, レビュー第3弾＝総括12件の検証）  → `docs/history/3.4xx.md`
- 自動保存の世代逆転を塞ぐ（3.485.0, Windows 版レビュー第2弾3件の検証＝Android 側は1件が該当）  → `docs/history/3.4xx.md`
- 担当可否の単一セルでも「休」を外せないようにする（3.484.0, Windows 版レビュー指摘の兄弟バグ）  → `docs/history/3.4xx.md`
- OPPO A5 5G（Android 16・360dp 帯）を対象に加える＝幅 390dp 未満の名前列56dp化と下部バーの圧縮（3.497.0, ユーザー指示）  → `docs/history/3.4xx.md`
- 希望島研磨（WishIslandPolish）を新設（3.496.0, ユーザー提示の確定仕様「実現可能な希望シフト日を固定アンカーにする」）  → `docs/history/3.4xx.md`
- 違反アンカー型・可変長ウィンドウ交換＝AdaptiveBlockSwap に STRICT_WHOLE_WINDOW モードを追加（3.495.0, ユーザー提示の設計。RunSwapPolish を置換）  → `docs/history/3.4xx.md`
- 連交換研磨を汎用化＝夜勤前提を撤廃（3.494.0, ユーザー指示「特定のシフトを特別扱いしない」。RunSwapPolish が NightRunSwapPolish を置換）  → `docs/history/3.4xx.md`
- 夜勤連交換研磨（NightRunSwapPolish）を新設（3.493.0, ユーザー指示「夜勤を他職員と交換する違反研磨」。計測で改善手0を示したうえで「keep-best で入れる」を選択）  → `docs/history/3.4xx.md`
- 人員過剰の「希望固定N人」を名指しして、その場で希望を取り消せるようにする（3.492.0, 実機指摘「データ修正のサポートが無い」）  → `docs/history/3.4xx.md`
- 停止の意味論と並列仮説の全本起動を揃える＋読込正規化の永続化（3.491.0, レビュー第7弾＋「あなたが賢く見直す」の自己見直し。3.490.0 の「採否不変」を訂正＝EarlyChain が初めて生きた。回帰テストは変異検証つき）  → `docs/history/3.4xx.md`
- 全画面の静的モックアップをキャンバスに起こしリポジトリへ格納（3.483.1, docs のみ, ユーザー指示「/design すべて」→「mainにマージする」）  → `docs/history/3.4xx.md`
- ドッグフーディング所見20件を全部修正（3.483.0, ユーザー指示「すべて修正する」＝`docs/screen_inventory_textart.md` の所見表）  → `docs/history/3.4xx.md`
- 編集タブの簡素化＝職員一覧の一本化・並びルールの起点別チップ＋重複ガード・導線重複の解消・用語統一・マトリクスの文字欠け修正（3.482.0, ユーザー提示のスクショ18枚検証レポート・grillingで4点確定＋追加指示1点）  → `docs/history/3.4xx.md`
- 勤務表タブの再設計＝日ヘッダの固定＋週送り/違反ナビの画面下固定（3.481.0, Claude起案・grillingで「任せる」＝推奨2項目を実施）  → `docs/history/3.4xx.md`
- ホームタブをAI提案型に再設計＝処方箋(スマートアクション)先出し・診断文の折りたたみ・重複ボタン排除（3.480.0, ユーザー提示の詳細設計案・grillingで5点確定）  → `docs/history/3.4xx.md`
- シフト集計カードに職員別/日別の切替を復活＝3.477.0で撤去した機能を実機要望で再統合（3.479.0, ユーザー指示・grillingで1点確認）  → `docs/history/3.4xx.md`
- MagiThemeのFontSize/Typographyトークン適用＝ハードコードfontSize 63件をMaterialTheme.typography.*へ・design_lint P11新設（3.478.0, ユーザー指示・grillingで5点確定）  → `docs/history/3.4xx.md`
- 担当可否・目標・上下限・実績を職員×シフトの1マトリクスへ統合＝AptSection/StaffRangeSectionを撤去（3.477.0, ユーザー提示案・grillingで7点確定）  → `docs/history/3.4xx.md`
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
