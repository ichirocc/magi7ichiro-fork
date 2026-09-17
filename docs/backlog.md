# バックログ / 未対応（CLAUDE.md から移設, 3.505.9）

> 完了した項目は打消し線のまま残す（同型の課題を二度登録しないため）。開いている項目の要約は CLAUDE.md にある。

1. ~~TallyCard の読取/編集モード完全整合（result専用検査結果の plumbing）~~ **→ 3.96.0 で完了**（ユーザー向け機能の TallyCard 項参照）。
2. 未レビュー領域の精読: ~~`V6LateOperators`~~（3.507.7）/~~`V6SearchOperators`~~（3.507.9）/~~`V6HotfixPasses` 各パス内部~~（3.508.1）, ~~CSV~~（3.507.8）/UI 層（3.508.2 でエンジン契約との接点は確認済み。全面精読は実機ビルドが要るので保留）。**（3.508.2）** `V6PortAnalyzer`（3.503.0）・`V6SwapSuggester`（3.507.4）・`V6SanityPort`（3.507.5）・`V6LateOperators`（3.507.7）は一巡。
   **(3.84.0, 並列監査で一巡＝`docs/history/3.0xx.md`)**。※`V6WebCompat` は 3.393.0 に撤去済み（Web 版は存在しない）。
3. ~~C++/NDK 移植は**不要**の結論（純Kotlin＋被覆対応Δ評価で十分高速）~~ **→ 撤回（3.136〜／第2期・第3期でネイティブ加速＝
   C++フル評価器＋SA/LAHC/ALNS/Polishチャンク＋JNI＋実行時パリティを実装。監査指摘は下記6/7）**。エンジンは ALNS/Destroy-Repair/
   ChainSwap3-4/C1BlockN/PathRelink/LNS/Reheat/Oscillation/適応的オペレータ重み/希望ロック枝刈り を実装済み。
   §4 ILP matheuristic のみ意図的に未実装。
4. cons3n のデータ重複（Dﾃ→A4 が2行）は二重計上だが最適化器/チェッカーで一貫（SettingIssue が dedup を提案）。
   **（3.502.0 確定）** エンジン側の dedup は採点を変える＝HF77 対象・C++ パリティ期待値も動くため行わない。`DELETE_DUP_SEQ` のワンタップ修正で運用解消＝閉じる。
5. **E5「月全体の俯瞰」= ユーザーの明示 go まで保留**（決定記録）。指数(見やすさ12指標)で唯一70未満(58)だが、
   最低スコア≠最高価値・片手一本指/編集主体との緊張のため、着手も再提案もしない（明示 go があった場合のみ）。
6. ~~**[ネイティブ・保守性] C++評価器のパリティに自動テスト無し**（3.168.0系精読で判明）。JVM単体テストは arm64 `.so` を
   ロード不可（`NativeBridge.available=false`）→ Kotlin のみ検証。CI(Release Build/Android SDK)は CMake で `.so` を
   ビルドするので**C++コンパイルエラーは捕捉**するが**意味的乖離（重み取り違え等）は捕捉しない**。`Evaluator.kt`（や
   `MirrorCore`/`DeltaEvaluator`）を変えて `magi_native.cpp` を変え忘れると実機で番兵発火→**ネイティブ黙殺（速度退行・誤出力なし）**。
   3.171.0 で緩和策の一つ（ユーザーが明示的に照合を切れる「照合トグル」＋既定ONの維持）を実装。3.172.0 で
   `tools/native/host_parity_bench.cpp`（ホストビルド可能なパリティ+ベンチharness）を追加。
   **→ CI配線 完了（`docs/history/3.1xx.md` の「ネイティブパリティのCI自動化」3.178.0）**。`.github/workflows/native-parity.yml` が
   pull_request→main / push→main / 手動 で harness を g++ ビルド・実行し、mismatch>0 で非ゼロ終了＝ジョブ失敗。
   これで Evaluator.kt を変えて magi_native.cpp を変え忘れる意味的乖離が自動検出される。**残課題**: harness の
   合成問題は S<=64/T<=64・乱数生成で、実データ形状の網羅ではない（fixture 拡充は将来課題）
   **→ 3.357.0 で言語跨ぎパリティも追加**（旧: 照合は C++ scalar vs C++ bit-op ＝同じ言語どうしで、
   両方の C++ 経路を一貫して変えれば通ってしまった。`golden_eval_expected.txt` を Kotlin テストと
   `--expect=` の両側から固定し、片側だけの変更が必ず落ちるようにした）
   **→ 3.199.0 でフィクスチャ拡充**（`tools/native/state_to_flat.py`＝実 state JSON→flat 変換を新設し、
   CI が golden_state.json の実データ問題でも照合＋合成 builder に休シフト range/apt/c1/c2・実現不可能希望・
   -1/非canDo混入盤面を追加。この拡充が実バグ=SaChunk の -1 未対応を実際に捕捉した）。
   **→ 3.362.0 で2つ目の実データ形状 sample_v6 を追加**（golden は入力盤面 hard=0＝C++ の HARD族パスを
   実データで一度も exercise しないため、sample_state_v6 の hard=15 盤面を第2 fixture に。`--expect` を
   flat と出現順で対応づけ1回のベンチで両形状を言語跨ぎ照合。詳細は「パリティネットへ2つ目の…」節）。
   残課題: 合成問題は S<=64/T<=64・乱数生成で、実データ形状の網羅ではない（fixture 拡充は将来課題）
   残: real/user 相当の「構造的 covU が床超で blocked-now」な形状は依然 repo に無い
   **→ 3.409.15 で解消**（2026-08 実運用 state の**匿名化版** `blocked_covu_state.json`＝covU=4 が床0を超えて
   blocked-now、を第3フィクスチャとして追加。言語跨ぎ照合＋形状の回帰テストつき）~~。
   **→ 3.524.0 で「意味的乖離検出」を族単位へ強化して完了**。従来の `--expect=` 照合は hard/soft の集約値2つ
   だけで、golden/sample_v6/blocked_covu の3実データが**そろって apt=c41=c41s=c42s=0**（一度も発火しない）
   と判明——この4族はどちらの側にバグがあっても集約値だけでは検出できない死角だった（2族の重み取り違えが
   打ち消し合うケースも同様）。`Evaluator.fullEvalParts`/C++`fullEvalParts` に任意の族別 breakdown 出力を追加
   （引数省略時は既存呼び出しと挙動不変）し、`--expect=` ファイルに19族(MirrorKeys.all)の内訳行があれば
   `host_parity_bench.cpp` が族単位でも突き合わせるよう拡張。19族すべてを非ゼロにした `full_coverage_state.json`
   を4件目のフィクスチャに追加してこの死角を塞いだ（詳細は `docs/history/3.4xx.md`）。
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
   ~~(a) 島ごとの評価枠を同日候補が先に使い切り窓・両翼・巡回が飢餓になる~~ **→ 3.501.0 で交互評価＋巡回 25% 確保（ユーザー指示・計測は history）**、
   ~~(b) `makesForbiddenRun` の事前枝刈りが禁止連続を**減らす**手まで落とす~~ **→ 3.501.0 で増分判定へ**、~~(c) ビームの `expandNode` が
   列挙順の先頭 `beamWidth*beamBranchFactor` 件だけを並べ替える~~ **→ 3.502.0 で走査 2 倍・良い順に保持（計測は中立）**、~~(d) `clearOutOfScopeWishes` が
   非同期診断の `settingIssues` を根拠にする~~ **→ 3.502.0 で `Problem.canDo` から再判定**。4 件すべて完了（Kotlin が正・C# 同期済み）。

10. ~~**[UI・同型] 「なおすのを手伝って」の連打防止が盤面の変更で解除される**~~ **→ 3.502.0 で `UiState.checkRev`（検査世代）へ**（3.500.2 で登録。-MAGI_PC 側の外部レビュー第3段）。
   `GuidedFixDialog` の `pending = remember(ui.schedule)` は setCell 直後の schedule 変化でリセットされるが、`coverageDiag` の再検査はまだ
   終わっていない＝古い診断の候補が再有効化され、素早い2回操作で covO と covU を同時に作れる。C# は `UiState.CheckRev`（`makeUi` ごとの検査世代）
   を追加し「押下時より新しい世代の反映」でだけ解除する形に直した（`GuidedFixFlow`）。Android も同じ形（UiState に検査世代を足す）で直す。


11. **[探索動学・実機確認待ち] 個人上限 0 の除外（3.507.0）の実機での受容**。再現データでは 桒澤/大島 の該当が 0 になる代わりに c1 32→116・重み付き 2849→4877
   （残り 4 名の余裕ゼロ＝設定側の問題）。利用者の実データ（希望 93 件）で「0 になった」ことと代償の見え方を確認し、代償が受け入れ難ければ
   設定側の案内（上限 0 の職員を除いた容量の表示＝「上下注意」行に『置ける N 名』）を足す。上限 0 のセルを UI の集計に「除外中」と示す案は表示変更なので提案どまり。
   ~~実機報告「大島愛の Dﾃ が上限 0 なのに 1 件割当」の調査で、`handleOptimize` の最終番兵が発火時に `finalSched` を
   `cappedInput`（`inputReport` と同じ盤面）でなく `normInput`（外す前の生入力）へ戻していたバグを発見・修正~~ **→ 3.513.0 で解消**。
12. **外部レビュー（2026-09-07、ユーザー提示 3 表）の提案＝決定・測定待ち**（3.509.2 で仕分け。詳細は history 3.509.2）。
    (a) 評価定義: C2 を不足量、C41/C41s を距離で評価＝HF77・言語跨ぎ期待値・C++ 5 箇所を同時に変える目的関数変更。300 試行ペア比較（品質 10%・速度 10%・必須退行 0）を通してから。
    (b) 探索動学: C2 専用研磨＝3.511.3 `C2Polish` 実装・iter15 で**全170ペア無変化**（合成ケースのcons2は不足最大1件でバッチ化の優位性を試せていない＝検証不能、既定OFF維持。deficit≥2の合成ケース追加か玉突き拡張の実装後に再計測）
        **→ 3.524.0 で `tools/loop` に `c2deficit`（cons2目標を不足2件以上へ）・`c42pair`（cons42違反を実際に仕込む）の2分類を追加（30→42ケース）し検証可能にした。
        小/中/大×5seedのスコープ付き再測定では `C2Polish`/`C42FlowPolish` とも**全ケースで旧腕と完全同点**（新たに仕込んだ初期違反は一般探索の段階で解消済み＝専用研磨に残作業が無い）。
        「検証不能」から「実測して無風」に前進したが既定OFFは維持（全170ペアでの正式ゲート再測定は未実施、詳細は `docs/history/3.4xx.md`）**／
        C41・C41s の最小費用フロー研磨＝3.511.5 `C41FlowPolish` 実装・iter16 で**不合格**（170ペア新良21/同等143/旧良6、品質+0.14%・速度+1.1%とも10%未達、必須が増えた試行2件、既定OFF維持。c42/c42s は別途 3.511.7 `C42FlowPolish` 実装・iter18 で**全170ペア新旧同等**＝合成ケースにc42違反の仕込みが無く検証不能、既定OFF維持）／
        族選択を件数×重み×改善可能性へ＝3.511.6 `familyPriorityScore` 実装・iter17 で**不合格**（170ペア新良10/同等143/旧良17、品質−0.26%、必須が増えた試行9件、既定OFF維持）。
        3.512.0 で「並べ替えても`repair()`の貪欲な早期採用（最初に成功した起点をすぐ採用）の下では効果が埋もれる」という仮説から
        `Params.bestOfK`（既定1、2以上で最大bestOfK件を実際に`search()`し`betterReport`で客観的に最良の一つを採用）を実装・iter23で**不合格**
        （170ペア新良15/同等138/旧良17、品質−0.17%、必須が増えた試行12件はlarge-infeasibleに集中、既定OFF維持。ユーザー判断で検証終了）／採用 0 時の探索半径拡張＝3.511.1 `StallEscalationConfig` 実装・iter13 で**不合格**（170ペア全件無変化＝発火条件「クラスタ全体で1巡も採用0」が実質発火しない、既定OFF維持）。発火条件を「直近ラウンドの採用数が閾値未満」等へ緩めた再設計が要る（測定）／C1 重複窓の連結成分化＝3.511.9 `C1RepairAnalysis.solveComponent` 実装・iter20 で**全170ペア新旧同等**（合成ケースに複数cons1窓の近接局面が無く検証不能、既定OFF維持）／C3n の前後余白込み LNS＝3.511.8 `C3nMarginLnsPolish` 実装・iter19 で**不合格**（170ペア新良1/同等168/旧良1、品質±0.00%とほぼ無風、既定OFF維持）／
        循環交換の 3〜5 人拡張＝3.511.2 実装・iter14 で必須退行0だが品質・速度10%未達＝**不合格、既定OFF維持**（試行数20回/日では170ペア中1件にしか当たらない、増やせば変わる可能性）／適応的予算配分／~~C3「選択日ペア交換」~~（3.510.0 実装・iter8 で品質 +0.04%＝不合格、既定 OFF 維持）＝いずれも `tools/loop` で測ってから 1 件ずつ。
    (c) D9 の方向別合成（片側だけ個人優先）＝決定 D9 は完全上書き。要件変更の明示があれば。
    (d) UX: 高度修正の適用前プレビュー（対象・変更セル数・必須/ソフト/希望の前後）、自動修正候補の比較、実現不能と探索不足の分離表示、
        時間予算/並列数/アルゴリズムのプリセット化、操作名付き Undo 履歴、利用者向け名称（「破壊」「LNS」「HF80」を出さない）＝画面設計。実機ビルドと design-review が要る。
13. **自動化方針（`docs/automation.md`）の「未」**: (a) 停滞時の探索半径・職員数・窓長の段階的拡大（測定）／~~(b) 採用ゲートの一本化~~（3.509.5 `adoptionGate`）／
    (c) 必須減少時のソフト損失上限（5%）＝採用基準の変更（決定待ち）／(d) 時間予算の自動計算（規模・必須数から）／~~(e) 3〜5 職員循環交換の 5 人まで拡張（測定）~~（3.511.2、backlog #12(b) と同一実装）。
    (f) 安全ゲート（`FixApplyGate`）合格の先頭候補を利用者のタップなしで自動適用（自動化 UX 評価 優先 1 の後半。適用後に「変更を見る／元に戻す」）／(g) 1 手候補ゼロ→「自動で整える」（区間交換・循環・成分修復・LNS）への自動接続（優先 2、Undo は接続全体で 1 回）／(h) 前景/背景の自動選択（優先 5）／~~(i) 内部用語の詳細設定への隔離（優先 7、design-review）~~（3.551.0: ラベル/説明/busy 表示の文言を利用者向け語へ。design-review は実機ビルド後）。
14. **重み・時間配分の外部分析（2026-09-08、ユーザー提示。最新 main とログ 10 本の傾向）＝適応制御として導入しペア比較で採否**:
    (a) C1 共同 LNS・個人共同 LNS を「短時間試行→改善時だけ拡張」（3.510.2 で `lnsAdaptive` 実装・iter9: 退行 0・品質 ±0・速度 平均 +10%/実データ −23%/中央値 −1.5%＝規則上不合格。**既定 ON はユーザー判断待ち、推奨 ON**）。
        ログでは両 LNS が後処理時間の 75〜91% を占め採用 0／(b) 後処理 4 巡→基本 2 巡・採用時だけ最大 4／固定長ブロック交換（11/13/17/19/23/28 日）を違反窓長・禁止連長・希望島半径からの動的窓へ＝3.511.0 `dynamicBlockLengths` 実装・iter12 で**不合格**（大・充足不能ケースで必須+1、既定OFF維持。単一パスのkeep-bestがチェーン全体を保証しない経路依存）／
    (d) C2・C41/C41s を不足量・超過量評価へ（評価仕様変更＝#12(a)）＝3.512.0で`Problem.quantitativeRangeEval`として全層実装済み・既定OFF、
        iter21で**不合格**（170ペア新良5/同等153/旧良12、品質±0.00%・速度-1.1%とも10%未達、必須件数が増えた試行6件はlarge-infeasible
        カテゴリのみ〈平均122.00→122.60、供給不足枠で不足量評価が異なる手を選ぶ〉、既定OFF維持）／(e) apt・fair・weekly は重みを変えず辞書式の副目的（apt 偏差→fair 最大偏差→weekly 最大偏差→偏差合計→変更セル数）で比較（採用基準の変更＝決定待ち）／
    (f) low=90（→60）と c3m=2 の A/B（HF77: 明示数値の指示があれば）。low の値自体より「一時的に low を悪化させ同一トランザクション内で戻す複合手」を先に強化／
    ~~(g) 予算超過の回帰試験（内部ループが 100〜250ms ごとに停止判定、300 秒設定で実時間 305 秒以内、停止要求から 5 秒以内、例外ワーカーで固まらない）~~ **→ 3.511.4 で完了**（`BudgetOverrunTest.kt`。V5/ALNS/RSI/PORTFOLIOは固定スラック±3000msで自然な予算超過を検証、RSI_PLUSはフェーズ下限（backlog#16）を踏まえ別途60秒上限で検証、停止要求は300ms計算後に5秒以内で反映、`runMultiWorker`は一部/全ワーカー例外でも2秒以内に制御が返ることを検証。ループの100〜250ms間隔そのものを直接計測する単体テストは無い（本番コードへの計装が要るため見送り）。2026-09-16 に本行の打消し線漏れを是正（実装は既にmain）。
    維持: HARD 辞書式・HARD 桁 1e9・HARD 族分類・C1/C3mn=30・8 ワーカー・後処理予約 25 秒・月内完結。
15. **「制約クレジット台帳付き複合修復」（2026-09-08 ユーザー提示設計）＝机上評価済み（history 3.510.2 補遺）、決定待ち**:
    ~~(a) 族別 Credit/Debt を採用ログ・完了表示（`ChangeSummary`）へ出す＝判定不変の可視化~~（3.510.3）／(b) 件数倍率上限（≤3/≤1/≤2）と Total 厳密改善＝採用基準の追加。
    実データの採用 31 件中 12 件を却下するので**入れない推奨**。入れるなら明示 go＋weekly/fair/apt を対象外＋opt-in で `tools/loop`／
    (c) 明示個人範囲の最終悪化禁止＝現行採用に影響 0。HARD 扱いにする決定があれば／(d) 一時 HARD 負債の禁止＝既存 `C1JointLnsPolish.hardDebt=1` の撤去（要計測）／(e) C2/C41 不足量評価＝#12(a)。
    設計 v2（3.510.3 補遺）: (f) 中間探索の件数 debt（C1/個人共同 LNS の totalDebt/c1Debt/personalDebt）を重み基準（負債 ≤ クレジット×係数）へ＝3.510.4 `WeightDebt` opt-in 実装、iter10 で新良 0・旧良 15・必須 +2 が 1 件＝**不合格、既定 OFF 維持**／
    (g) 凸型負債 W×D(D+1)/2（族単位で採用 31 件中 1 件却下、職員集中は未計測）＝採用基準の追加、opt-in＋計測／(h) 族別予算 Priority=W×違反量＝HARD 残存盤面で SOFT が 0 になるので下限つきで計測／
    (i) 設定由来倍率（明示個人 ×3）は不要（D9 で群目標を置換済み、チェッカーとずれる）／(j) WeightProfile は重みが設定化されるまで不要。
    仕様 v3（3.510.5）: (k) 成分修復の一時負債予算 `ConstraintRepairInference`＝opt-in 実装、iter11 で新良 8・旧良 12・実データ全件同一＝**不合格、既定 OFF 維持**／(l) 同点処理「変更セル数」の段＝採用基準の追加（決定待ち）／
    (m) C# `ConstraintRepairInference` は origin/main に無い＝push 後に Kotlin と突き合わせる。
    仕様 v2.1（3.512.0、ユーザー提示「適応型制約違反研磨エンジン 完全統合実装仕様」）: iter11 の無差別の一因として `search()` の
    ビームトリムが単一プールのため負債候補が飢餓しうる構造を発見し `Params.debtLaneSlots`（二車線ビーム、既定 0）を実装。
    trimFrontier のビーム縮小バグ（3.512.3）を修正した分離条件 iter22 で**不合格**（170ペア新良8/同等135/旧良27、品質−0.18%、
    個別10%超退行1件、既定OFF維持）。**ユーザー判断（2026-09-09）で検証・追加実装を中止**——仕様書の重い機構
    （結合度認識型負債テンソル§7.1・Restless Bandit§12・並列トランザクション§13・予算再配分）は着手しない。
    WeightDebt(iter10)・ConstraintRepairInference(iter11)・familyPriorityScore(iter17)に続き「族別一時SOFT負債」
    「族優先度スケジューリング」系統は4・5度目の不合格＝新しい具体的な測定可能な仮説を伴わない限り再検証しない。
16. **[探索動学・実機影響は無し・決定待ち] RSI_PLUS のフェーズ予算按分に35秒の暗黙下限**（3.511.4 `BudgetOverrunTest` で発見）。
    `runRsiPlus`（V6NativeOptimizer.kt）は Seed/Hypothesis/Refine/Polish の4フェーズへ `max(10, budgetSec*0.2)`/`max(10, budgetSec*0.35)`/
    `max(10, budgetSec*0.3)`/`max(5, 残り)` で予算を按分し、budgetSec<50 ではほぼ常に 10+10+10+5=35秒が下限になる（1秒予算でも実測36秒）。
    production の予算（後処理予約25秒を含め常に数十秒〜300秒、backlog #14(g) の実要件「300秒→305秒以内」も無傷）では発生しないため
    実害は無いが、将来 UI から短い予算（プレビュー・クイック実行等）でRSI_PLUSを呼ぶ機能を足す場合は踏む。フェーズ下限を
    budgetSec に応じて緩めるかは探索動学の変更＝明示指示があれば。現状は放置で良い（実機で踏む経路が無い）。
17. ~~**[表示のみ] 設定タブ・勤務表タブの文字サイズ階層**（3.515.4 の続き）~~ **→ 3.515.5 で完了**。
    `MagiSetupCards.kt`・`MagiScheduleViews.kt`・`MagiDashboardCards.kt`を`docs/DESIGN.md` §3.3の階層
    （章=titleMedium／節=titleSmall／本文・行=bodyMedium／補足=bodySmall／label=部品ラベル・チップ・凡例のみ）へ統一。
18. ~~**[要継続調査・ユーザー承知の上でmainへマージ済み] 重み表全面見直し（3.522.0）後、厳密ピン(staffRange lo==hi)保護に
    残存する穴**~~ **→ 3.523.0で完了**。デバッグ計装（呼出直前に盤面をdeep copyしてからexactPinRegressionで
    比較、`chain.adopt`/`replaceBoard`全箇所＋関数末尾の絶対値チェック）で全経路を追ったが不一致ゼロ＝
    exactPinRegression(staffRange lo==hi)は無傷と判明。実際の失敗はテスト内の**別の**assertion
    （希望固定`lockedCells`の値保持）で、`C1WindowPolish.applyC1IndexChainRepair`の候補日フィルタが
    `p.wishLocked(staff, d)` を見ておらず、他の全パスが持つガードだけこの1関数に欠けていた
    （`C1DeltaPrefilter.screenCell`の合算delta方式はpref+1と他族-1が相殺してNEUTRAL判定になり得るため
    生成側で塞ぐ必要があった）。候補フィルタへ`!p.wishLocked(staff, d)`を追加して修正、
    `PinInvariantTest`green化。`CombinatorialRepairTest`・`ViolationComponentRepairTest`の
    `combineTwoRejectedState`もcons41を4重複させ「単独不採用(タイ)・結合で採用」の性質を新重みで復元
    （詳細はdocs/history/3.4xx.md）。
19. **[探索動学・決定待ち] HARD 同点時のタイブレークが探索と公式で食い違う**（3.568.0 で測定・登録）。
    公式の `betterReport` は第2キー `weightedScore`（HARD 族の重みも入る）だが、SA/LAHC のコア loop と
    `SaOptimizer` のワーカー横断 best が使う `fullEval` は `hard1` を**生カウント**で合算する＝HARD 件数が
    同点なら族の重みを見ない。乖離が残るのは `fullEval` で決める 11 箇所だけ（RSI・チェーン選抜・研磨・
    番兵の 87 箇所は既に `betterReport`）。実測: 実データ 7 件×3 seed の 21 走行中 12 走行で発生・計 30 件
    （HARD 同点遷移の 0.01〜0.1%）、形はすべて「c3n(9000) −1 / covU(10000) +1 で soft を下げる」。
    ただし最終盤面は 7 件中 6 件が単一族か同重み族へ収束し、**最終出力を決めた形跡は無い**。
    この性質自体は 3.522.0 の重み表全面見直しの動機として業務担当者が既に指摘済みで、隣接案
    （HARD 内部重みを辞書式の独立段にする＝外部仕様 v12.1 Gate 5）は 3.510.3 で決定待ちに登録済み＝**同じ枠**。
    直す場合は `ObjectiveParityTest`（この非対称を固定しているテスト）の期待式も設計変更の対象になる。
    **明示指示＋`tools/loop` のペアベンチが前提**。経緯と再現手順は `docs/history/3.4xx.md` の 3.563.0〜3.568.0 節。
    **→ 3.571.0 で計測実装＋ペアベンチ実施**（`SaParams.officialTieBreak`、既定OFF・選定ロジックは無変更）。
    実データ4件×10seed×20秒×4ワーカー＝40走行で HARD退行0/40・weightedScoreの勝ちは0/40＝**採用基準を満たさず
    既定動作は不変**（この決定自体は維持、詳細は `docs/history/3.4xx.md`）。
20. ~~**[C# パリティ・要調査] `-MAGI_PC` の `PinInvariantTest.PostOptimizationHoldsPinsAcrossRandomStates` が赤**（3.569.0 の同期時に
    発見。random#1「実現可能な希望（職員0 日5）が後処理で動いた」。同期前の 6276edf でも同じ＝今回の変更と無関係）。
    Kotlin の同名テストは緑なので、C# の後処理チェーンのどこかが `wishLocked` を破っている。C# 単独では直さない
    （パリティの原則）＝Kotlin 側の同じ入力（`BusyState(JavaRandom(0x91A7L))` の random#1）で差分を取ってから同期する。~~
    **→ 3.571.0 で解消**。Kotlin側は当初から緑＝原因はC#単独の実装漏れ3件（①`C1IndexChainRepair`候補日フィルタに
    `WishLocked`ガード欠如、②`HF66`/`HF67`の`IsBetter`判定に`ExactPinRegression`ガード欠如、③`HF80StrategicOscillation`
    が基準盤面`original`を持たず`ExactPinRegression`も欠如）。3件とも3.523.0のC1WindowPolishと同型の
    「他パスは持つ厳密ピン保護ガードが1パスだけ欠けていた」移植漏れ。Kotlin/C++は無変更。`-MAGI_PC` commit
    `b1f4b64`、MagiEngine.Tests 856/856緑（詳細は `docs/history/3.4xx.md`）。
21. **[将来課題・実装不要] タグ成果物（リリース APK）を GitHub Release アセットへ移す**（3.570.0、ユーザー決定
    「将来的にGitHub Release assetへ移す」）。現状は `release-build.yml` が `v*` タグで `actions/upload-artifact`
    （`retention-days: 14`）へ APK を置くだけ＝Actions アーティファクトの保存枠を消費し、`cleanup-artifacts.yml`
    の除外リスト（`app-release-` prefix）で個別に保護している。Release アセットへ移せばこの除外が不要になり、
    保存枠も別勘定になる（`gh release create`/`softprops/action-gh-release` 等で `v*` タグ push 時にアセット添付）。
    ストア配布用の署名鍵・Lint ゲート・縮小と合わせて検討する話＝**明示 go まで着手しない**。
22. **[要人手対応・ツール権限外・再オープン] main の branch protection / ruleset が未設定**（2026-09-16、
    外部監査で指摘・3.572.0で確認）。force-push・削除・CI未通過コミットの直接 push を防ぐ設定が repo 側に
    無い。このセッションで使える GitHub MCP ツールに branch protection/repository ruleset を変更する手段が
    無く（`gh` CLI・直接 API access も環境上不可）、コードやワークフローの変更では実現できない＝**GitHub
    Web UI（Settings→Branches→Branch protection rules、または Rulesets）から人手で設定が必要**。
    推奨設定: main への直接 push 禁止（PR 必須）、必須ステータスチェック（Design Lint／Native Parity Check／
    V6 Engine Check）、force-push 禁止。CLAUDE.md の運用（「main へのマージは本人の『mainにマージする』で
    squash」「force-push は自分の作業ブランチだけ」）は既にこの制約を前提に運用されているため、設定しても
    通常のワークフローへの影響は無いはず。
    **経緯**: 一度「ユーザーがGitHub Web UIで設定した」との申告を受け完了扱いにしたが（2026-09-16）、
    別の監査で GitHub の設定画面が実際には「Rulesets: You haven't created any rulesets」「Classic branch
    protections: have not been configured」「main branch isn't protected」を示していると報告され、
    完了申告と食い違った。このセッションには branch protection の状態を照会するツールが無く、
    どちらの報告が現状を反映しているか自分では確認できない＝**要再確認**として再オープンする
    （設定完了時は実際に GitHub の設定画面で Active になっていることを目視確認してから閉じること）。
23. ~~**[将来課題・要専用パス] `docs/screen_spec.md` §08b の SOFT テーブルが古い**~~（2026-09-16、
    外部監査契機の19→20族修正時に発見・3.572.0）。**→ 3.577.0で修正・完了**。`weekly`行を追加し
    「SOFT（できれば・15種）」へ、HARD/SOFT両表の重みを現行値（`MirrorKeys.weights`）へ更新
    （groupViol 11000/covU 10000/c3n・c3w 9000/pref 8000、low 120/c3mn 90/c1 50/high 25/c3 15/
    c41s・c42s・covO 10/c41・c42 9/c3m 6/c2・apt 4/fair・weekly 2）、重み降順の並びに揃え、
    「違反箇所の出し方」のセル系にc3w・場所なしにweeklyを追加。`docs/sudo_model.md:500`の重み値引用
    （c1=30/c3mn=30/covO=5.0という3.522.0以前の値）も現行値へ修正。
24. **[要product判断・実データで実害確認済み＝深刻] `restShiftIndex`（`MirrorCore.kt`）の記号依存＋無言の0退避**
    （2026-09-16、外部監査で指摘・実データ4件で再現確認・3.574.0）。`fun restShiftIndex(state) =
    shifts.indexOfFirst { it.kigou == "休" }.takeIf { it >= 0 } ?: 0` は①表示記号"休"への文字列一致
    ②見つからなければ**無言で index 0 を休とみなす**。設計自体は過去に2度精読済み（`docs/DESIGN.md:116`
    の原則、`design_lint.py` P10のラチェット、`docs/data-models.md:78`が挙動を明記済み。詳細は
    `docs/history/3.4xx.md`3.419.0/3.420.0節）で、3.416.0（方針「休は通常のシフト定義」）が「休」削除禁止を
    撤廃したため到達可能になった、という筋も既知。
    **実データ4件（sept2026/blocked_covu/golden/sample_v6、いずれも実運用データの匿名化版）で
    `Ws1Ops.removeShift`（通常のUI編集操作と同じ経路）により「休」シフトを削除し再現・実害を確認**:
    削除後、`restShiftIndex`は新しい先頭シフト（実データでは一貫して"Pｼ"）を「休」とみなし、`fillShift`が
    旧休セルをそのシフト（担当不可なら群の担当可能な先頭シフト）へ**すべて実勤務として埋める**——
    「休みだった日」が一斉に出勤扱いになる。結果、**4件すべてでHARDが激増**: sept2026 hard 0→60
    （うちc3n＝禁止連 0→60）、blocked_covu 4→44（c3n 0→43）、golden 0→46（c3n 0→46）、
    sample_v6 15→73（c3n 2→65）。weightedScoreも5〜104倍に悪化（例: sept2026 5245→543578）。
    covO/highも軒並み15〜20倍規模で急増（想定外の出勤による人員過剰・上限超過）。**理論上の懸念ではなく、
    通常のシフト編集1回で実データが即座に大量のHARD違反を抱える状態になることを確認済み**。
    **→ 3.576.0でgrilling実施・暫定対応を実装**: ①探索エンジンのHARD一時負債許容（DebtBudget）は
    見送り（既存のkeep-best原則を維持、効果未測定の新機構は入れない）。②3.416.0（休も他シフトと同等に
    削除可）は反転しない＝削除自体は引き続き許可。③スケジュールセルは必ず有効indexという設計（データ
    モデル）は不変。④対応は`Ws1Editor.kt`の削除確認ダイアログに限定＝削除対象が「休」のときだけ
    「休だった日は自動的に他のシフトへ変わります。」を表示し、削除前にユーザーへ明示する（UI層のみ、
    エンジン・チェッカー・restShiftIndexのシグネチャは無変更）。フル改修（`ShiftRole`データモデル追加・
    JSON互換のschema変更）は引き続き保留。
25. **[運用・要個別判断] Dependabotのminor/patch group化設定後、既存の個別メジャー更新PRが残っている**
    （2026-09-16、外部監査で指摘）。3.572.0で`dependabot.yml`にgroup設定を追加したが、設定変更は既存の
    オープンPRを自動では統合・クローズしない。既に作成済みのメジャーバージョン更新PR（5件程度、
    actions/checkout・upload-artifact・cache・github-script・setup-android）は個別に採否判断が必要
    （それぞれ独立したメジャー更新でCI結果を見て判断する話＝一括処理は不可）。
26. **[探索エンジン・要個別grilling＋tools/loop測定] 探索ロードマップ（外部提案、2026-09-16）の未着手部分**。
    3.575.0（全工程正式最良保存＝pickBestStage）と3.576.0（削除時の暗黙置換封鎖）で提案の①②③は
    実装済み（①正式比較器hard→weightedScore→totalは無変更・重みと探索予算は別管理のまま、
    ②削除時の意味破壊防止はUI確認ダイアログで対応、③統合の改善を後処理が食い潰す機会損失は
    `pickBestStage`で解消）。残る提案は個別に測定してから採否（**DebtBudget型の一時負債許容は
    3.576.0のgrillingで見送り済み・再提案しない**）:
    - 並列所有権・停止・統合経路の監査（親子で同一Semaphoreを共有するとデッドロックし得る、盤面の
      所有権・乱数のワーカー分離・単一Reducerでの最良更新・実行世代スナップショット）。
    - ~~既定OFFの専用修復腕（C2/C42専用・C1成分修復・C3n余白LNS・CountChain等）を「対象違反が残る
      局面でだけ条件付きに接続」する再活性化基準（腕の自己申告改善でなく正式評価での寄与を見る）。~~
      **→ 3.580.0でgrilling実施・メカニズム実装済み**。決定: ①DebtBudgetは対象外（既に見送り済み）。
      ②「正式評価での寄与」＝実行中の`betterReport`/`reportComparator`（オンライン比較。tools/loopの
      gate.py4部門ゲートとは別レイヤーと確認）。③既定は現状維持、条件成立時だけ腕ごとの新規
      `xxxReactivate`フラグ（既定OFF）で「対象違反のbreakdown生値」を見て試す。腕自身の自己申告
      カウンタ（`TuningTelemetry.countChainApplied`等）は判定に使わない。④5腕（C2Polish/C42FlowPolish/
      C1成分修復/C3nMarginLnsPolish/CountChainPolish）同時に同じ形で実装（`V6HotfixPasses.
      targetFamiliesRemain`＋各腕呼び出し箇所のOR条件、`ArmReactivationTest`で固定）。各パス自体は
      既存どおりchain.adopt/replaceBoardで無条件反映されるが、パス内部が自分のkeep-bestで非退行を
      保証する既存設計（`runPostOptimization`のKDoc）は不変＝呼ぶかどうかの判定だけを追加。
      **→ 3.581.0で進捗**: tools/loopに5腕分の`xxxReactivate`featureキーを追加。C2Polish/C42FlowPolish/
      CountChainPolishは既存ケース（c2deficit/c42pair/46ケース）で再ゲート可能なため5seed×46ケースの
      再ベンチマークを実行中（バックグラウンド）。C1成分修復は`sequentialBlindSpotFixture`
      （`C1RepairAnalysisComponentsTest`、5人1組=全可1:休班2:夜班2の単体実証済み構成）を
      8/16/30人×14/28/31日へ比率タイルして`V6HotfixPasses.runPostOptimization`（決定的モード）へ
      直接プローブ（教訓#30＝本番導入前にまず発火確認）した結果、**全9組み合わせで
      useComponents=false/true とも完全同点（c1=0に一致）**＝単体テストでは再現する「視野の狭さでの
      手詰まり」が、フル後処理チェーン内では`exactWindow`の**前**に走る他のC1修復パス
      （時系列DP・広域ビーム・自己再配置・index駆動修復）が先に解消してしまい、component-repair
      固有の効き所へ到達しない（iter20の実データ4件での結果と同じ構造、規模を変えても再現せず）。
      C3nMarginLnsPolishも同型のearly-pass-consumption構造が疑われる（iter19の診断と整合）が未検証。
      **結論**: C1成分修復・C3nMarginLnsPolish用の専用合成ケースは、単に構造的シナリオを再現するだけ
      では作れない（他パスが先に消費する）＝tools/loopのケース生成器という枠組みでは検証が難しい
      可能性が高く、実データで偶然遭遇するのを待つか、`exactWindow`単体を強制的に先頭で呼ぶような
      別の計測手法が要る＝別途grillingで方針を決める（今回はここで打ち切り、無理に合成ケースを
      作らない）。C#(-magi_pc)への同期は5腕の採否確定後。
      **c2polishreactivateの再ゲート結果（5seed×46ケース=230ペア、`iter_c2reactivate.csv`）**:
      辞書式で新が良い0/同等229/旧が良い1、必須違反退行0件、品質改善率 平均-0.01%/中央値+0.00%
      （c2deficitカテゴリでも+0.00%〜-0.13%＝無風）、速度±1%以内。ゲート
      `{退行ゼロ: 合格, 品質≥10%: 不合格, 速度≥10%: 不合格, 安定性: 合格}` → **不合格**。
      「対象違反が残る局面でだけ試す」よう条件を絞っても、main探索が既にc2不足を解消済みのため
      腕の出番自体がほとんど無い（3.524.0の診断がxxxReactivate版でも再確認された）。
      **c2PolishReactivateの既定ON昇格は見送り**（既定OFFのまま、コードは残す）。
      **c42flowreactivateの再ゲート結果（5seed×46ケース=230ペア、`iter_c42reactivate.csv`）**:
      辞書式で新が良い0/同等225/旧が良い5、必須違反退行0件・10%超の個別退行0件（探索経路の分岐由来と
      推定、CountChainPolishのiter記録と同型）、品質改善率 平均-0.01%/中央値+0.00%
      （c42pairカテゴリはmedium区分で-0.32%のみ、他は無風）、速度±1%以内。ゲート
      `{退行ゼロ: 合格, 品質≥10%: 不合格, 速度≥10%: 不合格, 安定性: 合格}` → **不合格**。
      C2Polishと同型の「main探索が既にc42を解消済みで出番が無い」構造を再確認。
      **c42FlowPolishReactivateの既定ON昇格も見送り**（既定OFFのまま、コードは残す）。
      **countchainreactivateの再ゲート結果（5seed×46ケース=230ペア、`iter_countchainreactivate.csv`）**:
      他4腕と違い**実際に発火して盤面を変える**（辞書式で新が良い8/同等215/旧が良い7）。必須違反退行
      （旧hard=0→新hard>0）は0件・10%超の個別退行も0件だが、**下位10%品質(旧の最悪帯)で新が旧以上を
      満たさず**＝`large infeasible`区分（hard>0が前提の充足不能ケース）で一部試行のhardが
      125.30→126.00（平均、必須件数が増えた試行3件）とわずかに悪化。ゲート
      `{退行ゼロ: 不合格, 品質≥10%: 不合格, 速度≥10%: 不合格, 安定性: 合格}` → **不合格**。
      CountChainPolish自身のhistory（3.540.0）が「効く盤面では実害なく効く」と楽観的に記していたのは
      **充足不能（infeasible）な大規模盤面では成立しない例外がある**と訂正が必要＝腕自身の
      keep-bestは自分の呼び出し前後を保証するが、盤面を変えたことで後続パスが辿る探索経路が変わり
      （探索経路の分岐）、下流で結果的にわずかに悪化する場合がある。
      **countChainReactivateの既定ON昇格も見送り**（既定OFFのまま、コードは残す）。
      tools/loopで実際に測定できた3腕（C2Polish/C42FlowPolish/CountChainPolish）はいずれも
      既定ON昇格に不合格と確定。C1成分修復（専用合成ケースが作れず`xxxReactivate`のtools/loopゲート
      測定は未実施）・C3nMarginLnsPolish（未着手）は「測定して不合格」でなく「既定OFFのまま未計測」
      が正確な現状で、測定手法は別途grillingで決める。5腕とも既定値（全てfalse）は変更なし。
    - 停滞判定の精緻化（正式最良不変・同一探索範囲の反復・修復途中の進捗・証明済み下限・時間不足を
      区別）と、既存の再配属/摂動/エリート機構への小さな追加としての限定的な専門腕の試行。
    - 業務重み（正式スコア用）と腕選択・予算配分の基準を分離する設計指針の明文化（探索スケジューラに
      新しい重み表を作らない）。
    比較は同一入力・同一seed群・同一総予算（固定仕事量と壁時計予算の両方）で行い、tools/loopの
    ベンチマークで測ってから個別に採否する（CLAUDE.md「探索動学の変更は測ってから採否」）。
