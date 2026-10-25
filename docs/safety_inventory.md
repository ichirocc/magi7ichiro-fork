# 安全機構の棚卸し（Q-02）と 28 項目の分類（Q-07）

対象: `ichirocc/magi7ichiro-fork` origin/main = `3ef490c0`（3.723.0）。読み取りのみ。clone は shallow（`git rev-parse --is-shallow-repository` = true）。

CI の対応（以下「CI」列の略号）
- **V6** = `v6-engine-check.yml`: `python3 tools/weight_lint.py` → `gradle testDebugUnitTest`（JUnit 全件、ui/ と work/ のテストも含む）。main と `claude/**` への push、main 向け PR で走る
- **REL** = `release-build.yml`: `tools/design_lint.py` → `bash tools/host/hosttest.sh`（JUnit 全件をホスト JVM で）。タグで走る
- **NP** = `native-parity.yml`: `tools/native/host_parity_bench.cpp` を `-DMAGI_HOST_TEST` で `magi_host` にビルドし、5 つの fixture について Δ==フル・scalar==bit・`--expect=*_eval_expected.txt`（Kotlin との言語跨ぎの値）を照合。TSAN ジョブもある
- C# 側（-MAGI_PC）の写しは `windows-*.yml`。このリポジトリからは確認していない

## Q-02 安全機構

| 機構 | 責務 | 主なコードの場所 | 固定しているテスト | CI |
|---|---|---|---|---|
| UnifiedViolationChecker | 違反判定の正（source of truth）。表示・採否・番兵はすべてこの報告を読む | `app/src/main/java/com/magi/app/v6/MirrorCore.kt:181`（`check` は :202） | `CheckerEquivalenceTest.reportsMatchTheReferenceOnRealAndRandomBoards`（参照実装 `CheckerReferenceV0.kt` と照合）、`CheckerReportDigestTest`、`DeltaEvaluatorTest.deltaPerFamilyMatchesCheckerBreakdown_allSoftFamilies`、`ObjectiveParityTest` ×2 | V6, REL |
| 最終番兵 | 入力・途中の段・最終の中から最良を採る。手動固定を壊した段と上限 0 のセルが残った段を外す。悪化したら `cappedInput` へ戻す | `V6FinalPort.kt:1081`〜`:1100`（`excludeCapZeroStages` :1460、`clearedCapZeroStages` :1466、`pickBestStage` :1517）、`MirrorCore.kt:739`（手動固定）、`:749`/`:753`（上限 0 のセル） | `SessionRegressionTest.pickBestStage_fallsBackToCappedInputNotRawInputOnRegression` / `pickBestStage_prefersAnIntermediateStageOverBothInputAndFinal` / `pickBestStage_keepsTheFinalStageWhenItIsActuallyBest` / `excludeCapZeroStages_*` ×2、`ZeroCapExclusionTest.handleOptimizeReturnsZeroCappedCellsAndDoesNotRevertToInput` / `clearedCapZeroStagesAddsTheStageWithCappedCellsRemoved`、`PinInvariantTest` ×2、`AdoptionGateTest` ×2 | V6, REL |
| `Problem.mayPlace` / `mayPlaceAt` | 最適化器が置いてよいシフト（担当可から個人上限 0 を外したもの。`mayPlaceAt` は拡張希望の禁止も外す）。候補生成・入口 hf66・最終番兵が同じ基準を読む | `MirrorCore.kt:769`（`Problem.mayPlace`）、`Problem.kt:83`（`mayPlaceAt`）、`Problem.kt:69-71`（`placeable`） | `ZeroCapExclusionTest`（11 件、`mayPlaceExcludesCappedShiftButCanDoAndUiListsAreUnchanged` ほか）、`C1UpperZeroGuardTest` ×3、`ExtWishOptimizeTest.optimizerNeverPlacesABannedShiftAcrossAlgorithms` / `postProcessingWithEveryFlagOnNeverPlacesABannedShift` | V6, REL |
| `c3wBan` / `extBanned` の共有表 | 「希望の前日に禁止」と拡張希望の禁止を、チェッカー・Evaluator・Delta・C++・診断が同じ表から読む | `Problem.kt:154`/`:254`（`c3wBan`）、`Problem.kt:74`（`extBan`）、`DeltaEvaluator.kt:644`、`magi_native.cpp:146-147`/`:164`、UI 側は `MagiUiState.kt:144`（`extBanned`） | `C3wConstraintTest.bannedCellCountsOnceInCheckerEvaluatorAndDelta` ほか 6 件、`ExtWishRulesTest` 8 件、`ExtWishOptimizeTest` 3 件、`NativeParityFixtureTest`（fixture の値で C++ と照合） | V6, REL, NP |
| `reportComparator` keep-best | 採否の辞書式（hard → weightedScore → total）の唯一の実装 | `MirrorCore.kt:86`（`betterReport` :94） | `AdaptiveEliteArchiveTest`（アーカイブの並びが `betterReport` と一致）、`SessionRegressionTest.checkResultWorse_lexicographic`、`AptFairPolishToleranceTest`（OFF のとき `betterReport` と同じ判定） | V6, REL |
| 保存の世代と所有権（3.485〜3.487） | 古い自動保存が新しい保存を上書きしない（世代ゲート）。古い実行は新しい実行のファイルを書かない（所有権の確認と、rename 失敗時に直接書く前の再確認） | `work/SaveGate.kt:12`（`writeIfLatest` :19）、`MagiViewModel.kt:197-199`/`:854`/`:886`、`work/RunFiles.kt:142`（`writeFileAtomically`、`commitGuard` を :164 と :172 で 2 回評価） | `SaveGateTest.staleGenerationIsDropped` / `failedWriteDoesNotAdvanceGeneration`、`RunFilesTest.theGuardIsReEvaluatedBeforeTheNonAtomicFallbackWrites` / `anOldRunCannotOverwriteTheNewRunsFileBetweenItsOwnershipCheckAndRename` / `releaseIfOwnerDoesNotDeleteFilesOfANewerRun` / `aFailedCommitGuardLeavesTheTargetUntouched` ほか（計 20 件）、`BgHandoffTest` 4 件 | V6, REL |
| 保存失敗の再試行 | 自動保存の失敗を状態（`SaveState.Failed`）として出し、初回だけダイアログを開き、再試行ボタンを出す | `MagiViewModel.kt:899-960`（`SaveFailNotifier`、`reportAutoSave` :929、`retrySave` :939）、`CellSheetLogic.kt:674`（`SaveFailNotifier`） | `Review3701Test.dialogShownOnceWhenSaveTurnsFailed` / `saveFailedLineInHomeStatusRegardlessOfStage` / `topBarSaveWordOnlyWhenFailed`、`BgHandoffTest.recoveryFilesSurviveWhenSavingTheAppliedStateFails`、`CountShortageTextTest.timeoutAndSaveFailureAreSaidSeparately`。`retrySave` が実際に書き直すことは ViewModel の層なので未テスト | V6, REL |
| JNI の境界検査（3.442） | 制約の index（cons1/cons2/c41/c42/cons3w）を JNI の入口で検査し、範囲外なら handle を作らない（0 を返して Kotlin へ退化） | `magi_native.cpp:227`（`consIndicesValidN`）、`:2651` | `host_parity_bench.cpp:820-850`（CONS-GUARD: 正当な値を通すことと、範囲外 10 種類を拒むことを確かめる）。JUnit は無い（ホスト JVM は `.so` を読めない） | NP |
| 2 層のネイティブ番兵 / NativeGate | 1 層目は C++ のチャンク末尾の自己整合（差分スコア == フル再計算）。2 層目は Kotlin `fullEval` による照合。どちらかが不一致なら `NativeGate.disable` で Kotlin へ退化する | `magi_native.cpp:595`/`:1015`/`:1039`、`NativeBridge.kt:98`（`NativeGate`、`disable` は +17 行）、`SaOptimizer.kt:180`/`:194`/`:255`/`:263`/`:277`、`V6FinalPort.kt:1134`、`NativeEval.kt:78`（`parityCheck`） | C++ 側は NP の harness（Δ==フル、scalar==bit）。Kotlin 側の「閉じて退化する」経路を直接たたくテストは無い（`SaWishLockTest` のコメントが、`.so` が無いので検証できないと明記）。`PolishRobustnessTest` はカウンタだけ | NP（C++ 層のみ） |
| Δ=フルのパリティ | 差分評価がフル評価と常に一致する | `DeltaEvaluator.kt`、`Evaluator.kt:56`/`:71` | `DeltaEvaluatorTest`（`deltaMatchesFullEval_*` 4 件、`previewMoveReturnEqualsScoreAfterCommit`）、`ObjectiveParityTest` ×2、`WeeklyFairMarginalTest.marginalDeltaMatchesFullCheckerAcrossRandomStates`、`QuantitativeRangeEvalTest.deltaMatchesFullEvalUnderQuantitativeMode`、`C1JointLnsDeltaEvalTest` | V6, REL（C++ の Δ==フルは NP） |
| native-parity CI | Kotlin と C++ の評価値を 1 つの数に固定し、片側だけの変更を落とす | `.github/workflows/native-parity.yml:91-108`、`tools/native/host_parity_bench.cpp`、`tools/native/test_state_to_flat.py` | `NativeParityFixtureTest` 5 件（Kotlin 側が同じ `*_eval_expected.txt` を検証） | NP、V6 |
| golden | Web 版の結果との一致、および言語跨ぎの基準値 | `app/src/test/resources/golden_eval_expected.txt` ほか | `V6WebGoldenParityTest.loadDataBitMatchesWeb` / `webOptimizedScheduleIsHardCleanAndConsistent`、`NativeParityFixtureTest.goldenEvaluatorValueMatchesTheSharedCrossLanguageFixture` | V6, REL, NP |
| ScheduleWriteSites の許可リスト | ui/ で勤務表を書き換える場所を、許可した関数だけに限る | テストそのものが機構（`allowed` の 22 関数） | `ScheduleWriteSitesTest.everyScheduleWriteIsOnAnAllowedPath` | V6, REL |

## Q-07 28 項目の分類

3.723.0（INDEX の先頭行）でこの 28 項目は一度裏取りされている。下の表は、それをコードとテストで照合し直した結果。

| ID | 内容 | 分類 | 根拠 |
|---|---|---|---|
| B-01 | cappedInput と normInput の食い違い（3.513） | 検証済み | `SessionRegressionTest.pickBestStage_fallsBackToCappedInputNotRawInputOnRegression` |
| B-02 | 上限 0 の職員への交換（3.621.2） | 検証済み | `ZeroCapExclusionTest.personSwapKickDoesNotMoveCappedShiftOntoCappedStaff`（`V6NativeOptimizer.kt:1158`）。補足: history 3.621.2 は「C1 系研磨の一部（`C1JointLnsPolish`・`C1TemporalFlowPolish`・`C2Polish`・`C3RotationPolish`）が `canDo` で候補を選ぶ」ことを未検証として残している。`C1UpperZeroGuardTest` が固定しているのは index chain・window・beam の 3 つだけ |
| B-03 | 停止で running が解除されない（3.438） | 記録あり・テスト無し | 修正は `OptimizationWorker.kt:327`（`releasedByMe = true`）と :359。`ReviewFixes3442Test` の KDoc が「Robolectric か instrumented test が要る」と自ら書いている。足すべきテスト: 解除判定を純関数に切り出す `OptimizationWorkerReleaseTest.cancelPathReleasesRunningEvenAfterMarkerIsCleared`（マーカー削除後も `setRunning(false)` に至ることを確かめる） |
| B-04 | 停止した保存が書き続ける（3.485） | 検証済み | `SaveGateTest.staleGenerationIsDropped` / `failedWriteDoesNotAdvanceGeneration`。ViewModel 側の採番の配線（`MagiViewModel.kt:854/886`）はテストしていない |
| B-05 | 原子的でない書き込みの前の所有権の再確認（3.487） | 検証済み | `RunFilesTest.theGuardIsReEvaluatedBeforeTheNonAtomicFallbackWrites` |
| B-06 | 休が見つからないとき index 0 に倒れる（3.428） | 検証済み | `ReviewFixes3428Test.hf66FillsUnauthorizedCellsWithRestNotWithTheLowestIndex` / `fillShiftIndexPrefersRestThenFirstAllowedAndNeverThrows` |
| B-07 | 行の追加がグループの担当可否を見ない | 設計どおり（検証済み） | 3.723.0 の判断: 新しい行・列はすべて `Ws1Ops.fillShift`（`Ws1Ops.kt:302`）を通る。固定しているテストは `ReviewFixes3442Test.csvUpsertFillsNewStaffRowWithAnAllowedShift`、`SessionRegressionTest.addStaffAndResizeFillWithResolvedRestShift` / `filledCellsAreAlwaysAShiftTheStaffMayActuallyWork` |
| B-08 | JNI の配列の境界（3.442） | 検証済み（C++ のみ） | `host_parity_bench.cpp` の CONS-GUARD（NP ジョブ）。JUnit は無い |
| L-01 | 修復が希望固定を上書きする（3.523） | 検証済み | `PinInvariantTest.postOptimizationHoldsPinsOnRealProductionState` / `postOptimizationHoldsPinsAcrossRandomStates`（3.523.0 はこの失敗を直した） |
| L-02 | fair の目標と偏差の基準 | 設計どおり | `docs/business-logic.md:76`、CLAUDE.md の fair（3.541.0 の案 E）。`FairAchievementV2Test` 4 件 |
| L-03 | weekly の丸め（3.526） | 設計どおり | `docs/business-logic.md:77`（Σ\|7×w−回数\|÷7）。3.723.0 の判断は「不均衡 1 単位ごとに必ず 2 増える」。`WeeklyFairMarginalTest`、`GridDisplayMarksTest.weeklySkewPhraseMatchesTheScoredDeviation`、`WideC3nFixtureTest`。補足: `MirrorEngineTest.weeklyFloorIsTheRemainderAgainstSevenTimesTheRoundedTarget` の KDoc は 3.355.0 の round 目標のまま（コメントのずれ） |
| L-04 | 必須と禁止の矛盾の検出（3.534/3.723） | 検証済み | `V6SanityPortTest.mustForbiddenSameShiftContradictionIsReported` / `mustShorterThanForbiddenIsNotFalselyReported` / `mustSeqContainingForbiddenSeqIsReported` / `mustSeqNotContainingForbiddenSeqIsNotReported`（`V6SanityPort.kt:706`） |
| L-05 | c3w の規則の一貫性 | 設計どおり（検証済み） | `Problem.c3wBan` を 3 者・C++・診断が共有。`C3wConstraintTest` 6 件と NP |
| L-06 | MUS の証明が手動固定を数える（3.717/3.723） | 検証済み | `ConstraintMusTest.staffMusCountsManualPinsLikeWishes` / `staffMusMixesWishAndManualPin`（`ConstraintMus.kt:34`/`:90`） |
| L-07 | 重み付き中央値 | 設計どおり | 3.541.0 の案 E。`FairAchievementV2Test.weightedMedianIgnoresTheMemberAtTheEdgeOfHisOwnBand` |
| L-08 | apt の補正値を保存しない | 設計どおり | 3.508.0 の決定（保存するのは設定値、実効値は評価のたびに計算）。`AptReachClampTest.appliedTargetIsClampedToTheReachableRange` ほか 5 件 |
| M-01 | 原子保存のコールバックの既定（3.428） | 解決（2026-10-25） | 置き換えの実体（`commitAndReplace`）が諦めたファイル名を `NonAtomicWrites` へ必ず積む＝`onNonAtomic` を渡さない呼び出し（背景の入力・途中経過・完了結果、`RunFiles.kt`）でも残る。画面は自動保存の記録のときに取り出し、ファイルごとに 1 度だけ操作ログへ出す（`MagiViewModel.reportNonAtomicSave`）。`prevBackupFile` は既に `onNonAtomic` を渡していた。`RunFilesTest.givingUpWithoutACallbackIsStillRecorded` |
| M-02 | WorkManager の enqueue の失敗 | 記録あり・テスト無し | `MagiViewModel.kt:673-708`（外部レビュー P1-02 で同期の失敗、3.592.0 で非同期の失敗を片付けるようにした）。`BgHandoffTest.theGuardClosesAtEnqueueAndOnlyItsOwnRunOpensIt` はゲートだけを見る。足すべきテスト: 片付けの判定を切り出す `BgEnqueueFailureTest.asyncEnqueueFailureCleansUpOnlyTheCurrentRun` |
| M-03 | 予算を何か所でも定義している | 未解決（軽微） | 定数は `V6FinalPort.kt:21/23`（`POST_RESERVE_SEC`/`CHAIN_RESERVE_SEC`）。合算は UI が 3 か所で別々に書いている（`CellSheetLogic.kt:1682`、`MagiSetupCards.kt:221`、`PreRunCheckSheet.kt:53`）。ほかに `MagiViewModel.kt:251` は予算+600 秒、`SaOptimizer.kt:29` の既定は 8000ms。`BudgetOverrunTest` が固定しているのは実時間の上限だけ |
| M-04 | RSI+ の按分が予算を超える | 未解決（計測待ち） | backlog #16（35 秒の暗黙の下限、`BudgetOverrunTest` で見つかった）。3.723.0 は探索動学の扱いとして触っていない |
| M-05 | fair の全走査（3.451） | 設計どおり | 3.723.0 の判断: fair の差分評価は該当する群×シフトだけを見る。`WeeklyFairMarginalTest.marginalDeltaMatchesFullCheckerAcrossRandomStates` / `fairMarginalAtIsZeroWhenGroupHasFewerThanTwoMembers`。3.451.0 の OOM 修正そのものに専用のテストは無い |
| M-06 | グループ上限 0 の候補 | 未解決（業務判断） | 3.507.0 の決定は個人上限 0 だけを扱い、グループの上限 0 はその範囲外 |
| M-07 | 職員を名前で突き合わせる | 未解決（データ項目の変更が要る） | 安定 ID は `docs/data-models.md` の変更になる（3.723.0）。`EditLandingTest` が同名の職員の扱いを固定している |
| U-01 | TalkBack の読み上げ順 | 未解決 | `app/src/main` に `traversalIndex`/`isTraversalGroup` が 0 件。テストも無い |
| U-02 | 使われていないフラグと設定の食い違い | 設計どおり | 3.723.0 の判断: 既定 OFF のフラグ 8 個は定義ファイル内の後処理とテストで使われている |
| U-03 | weight lint が拾う数値の形（3.516） | 未解決（一部） | `tools/weight_lint.py:207-209` の正規表現 `\d+(?:\.\d+)?(L)?` は `10_000L` や `1e4` を拾わない（実行して確認）。`= 25`（整数の代入）も意図して対象外。現在の main にこの形の重みの複製は無い。lint 自体のテストも無い。足すべきテスト: `tools/test_weight_lint.py`（`_` 区切りと指数表記の検出） |
| U-04 | versionCode の重複（PR #393 で検査を追加） | 未解決 | main には検査が無い（`.github` と `tools` に `versionCode` の参照が 0 件）。PR #393 は未マージ。読める範囲（shallow）の履歴 47 版では、別の versionName が同じ code を持つ例は見つからなかった |
| U-05 | CLAUDE.md に履歴が混ざる | 解決（2026-10-25） | 重みの節・D3 の版ごとの経緯（旧階層・旧値の列・指示の文言）を `docs/business-logic.md` §2「重みの経緯」へ移した。CLAUDE.md は現行値と変えない決定だけ。検査は無い（運用） |

### 集計
- 検証済み 11: B-01, B-02, B-04, B-05, B-06, B-08（C++）, L-01, L-04, L-06、および設計どおりかつテストのある B-07, L-05
- 記録あり・テスト無し 2: B-03, M-02
- 設計どおり 6: L-02, L-03, L-07, L-08, M-05, U-02
- 未解決 9: M-01（一部）, M-03, M-04, M-06, M-07, U-01, U-03（一部）, U-04, U-05

### 機構側のテストの穴（Q-02 から）
1. NativeGate が閉じて Kotlin へ退化する経路は JUnit で一度も通っていない（`.so` が無いため）。足すなら、`SaOptimizer` の照合部分を注入できる形にして `NativeGateFallbackTest.mismatchDisablesGateAndKotlinResultIsUsed`。
2. `retrySave` と世代の採番は ViewModel の層にあり、テストしていない（`SaveGate` 単体だけ）。

## 2026-10-24 の再照合（ブランチ `claude/merge-wub4fq`、外部レビュー「監査資料と実装のずれ」）
上の表は 3.723.0 時点の棚卸し。いまのコードとテストで照合し直した状態:

| ID | 状態 | 根拠 |
|---|---|---|
| M-03 予算の定義が複数 | 解決（エンジン側） | `TimeBudget`・`BudgetShare`（全面改修 P3、`TimeBudgetTest`）。各段の締切は `PostStageDeadlineTest`（T-9） |
| M-06 グループ上限 0 | 解決（業務判断済み） | P8（`docs/overhaul_design.md` §6.9） |
| M-07 職員を名前で照合 | 解決 | P9＝`Staff.id`（`docs/data-models.md`） |
| U-01 TalkBack の読み上げ順 | 対応（実機確認待ち） | 勤務表に `collectionInfo`・`traversalIndex`・`isTraversalGroup`（R-02、`MagiScheduleViews.kt`）。実機の TalkBack は T-18 |
| U-03 weight lint の数値の形 | 解決 | `10_000L`・`1e4` を拾う（`tools/test_weight_lint.py`、V6 Engine Check で実行）。断片の移動も検出（2026-10-24） |
| U-04 versionCode の重複 | 解決 | `tools/check_version.py`（Design Lint で実行） |
| 新 S-01 SOFT の桁あふれ | 解決 | 個人の下限 1 億で差分評価が必須を 43 と誤って返した。実行前の検証 `Ws1Ops.countSettingError`（回数の設定 ≤ 1 万）と、差分評価の桁の検査（`SoftOverflowGuardTest`） |
| 機構の穴 1 NativeGate の退化 | 一部 | `NativeGateFallbackTest` は退化後の経路を固定するが、実際の JNI からの不一致の検出は通らない（`.so` が無い）。native-parity CI が C++ と Kotlin の一致を別に守る |
| 機構の穴 2 保存の再試行 | 一部 | `SaveRetryWiringTest` は失敗・再試行・復旧の状態遷移を固定。実ファイルの書き直しまでは見ない（ViewModel はホストで組めない） |

残る未解決: M-04（計測待ち）、U-05（文書の運用）、機構の穴 1・2（一部）。
