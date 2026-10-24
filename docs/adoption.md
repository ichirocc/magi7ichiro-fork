# 採否の合成（どの経路がどの検査を通るか）

全面改修 P10d（2026-10-24）。外部の構造レビュー「採否が 1 つの述語に閉じていない」への答えとして、盤面を採る経路ごとに、
どの検査を必ず通るかを 1 枚にまとめた。比較の正本は `reportComparator`（`MirrorCore.kt`、hard → weightedScore → total の辞書式。
`betterReport` はその糖衣）。新しい経路を足すときは、この表の同じ層の行と同じ検査を通すこと。

| 層 | 経路（主なファイル） | 比較 | 手動固定・希望固定 | 回数固定（下限＝上限）を崩さない | 拡張希望の禁止 | 必須を新たに生まない |
|---|---|---|---|---|---|---|
| 探索（内部の受理） | SA・ALNS・RSI の 1 手（`SaOptimizer`、`AlnsSearch`、`RsiSearch`、C++ `SaChunk`） | 差分評価の生スコア（hard を増やさない SA）。Δ と フルは番兵で一致を照合 | 置かない（`Problem.mayPlace`・`wishLocked`） | ―（low/high の重みで罰する） | 置かない（`mayPlaceAt`） | ―（hard ゲート） |
| 探索（最良の更新） | 仮説・ワーカー・ラウンドの keep-best、入口の摂動・エリート（`PortfolioSearch`、`RsiSearch`、`EliteRelinking`、`CandidateCommit`、`V6LateOperators`） | `betterReport` | 同上 | ― | `keepsExtBan`（入口・hf67・エリート） | ― |
| 最良盤面の公開 | ライブ表示・kill 復旧の写し（`OptimizerRuntime.publishLiveBest`） | `betterReport`（CAS） | ― | ― | ― | ―（採用ではない） |
| 探索直後の研磨 | `SearchPolish.hf80PostPolish`（自動で整える も同じ） | 差分評価の SA（hard 非悪化）＋最後に入力と `betterReport` で番兵 | 同上 | ― | `extBanInDiff` | ― |
| 後処理の各段 | 研磨パス（`V6HotfixPasses` の前段・中段・終段、玉突きパイプライン） | `adoptionGate` = `betterReport` | 同上 | `exactPinRegression`／`blocksImproving`（`adoptionGate` の中） | 各パスの候補生成で `extBanned` | ― |
| 後処理のチェーン | 段を畳むたびの keep-best（`PostChain.runningKeepBestFold`） | `betterReport`（構造的な covU 床が 0 のとき） | ― | ― | ― | ― |
| 後処理の例外 | apt/fair 研磨の許容（`AptFairPolish.toleratedBetter`、既定 OFF、3.535.0 HF77） | 対象族以外の SOFT 合計 +6% まで許容（hard 不増加は不変） | 同上 | 同上 | 同上 | ― |
| 画面の変更案・代行 | `FixApplyGate.apply`（変更案の適用・`AgentLoop`） | `betterReport` | 断る（手動固定・希望固定のセルを変える案） | `exactPinRegression` | 断る | `newHardFamilyViolation`＋`ViolationIdentity.newHard`（族の件数と、職員・日・枠の同一性） |
| 設定の緩和 | `RelaxTrial`→`relaxAndApply` | 試算の必須数の再現（`postCheckOk`） | 固定セルは手順から外す | ― | ― | 再現しなければ復旧 |
| 最終番兵 | `V6FinalPort.checkResultWorse` | 入力より hard → weighted → total のどれかで悪化したら入力へ戻す | ― | ― | ― | hard の悪化を断る |

読み方:
- 「―」はその層では検査しない（下の層か、別の仕組みが受け持つ）。回数固定は**研磨と画面の変更案の層の方針**で、探索層は low/high の重みで罰するだけ
  （2026-10-24 の照合で確認。探索層に足すのは探索の動き方の変更＝測ってから）。
- 必須を新たに生まないことを**同一性まで**見るのは画面の変更案・代行だけ。探索と研磨は hard の件数（辞書式の第 1 キー）で守る。
- 2 層番兵（C++ の自己整合＋Kotlin `fullEval` 照合）はこの表の外側で、ネイティブの結果を Kotlin の評価と突き合わせる（不一致なら `NativeGate` が閉じる）。
