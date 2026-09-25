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


11. **[UX情報設計・ユーザー決定2026-09-17] 個人上限 0 は設定ミスではなく意図的な制約（パート等）として扱う**。
   再現データでは 桒澤/大島 の該当が 0 になる代わりに c1 32→116・重み付き 2849→4877（残り 4 名の余裕ゼロ）が
   起きるが、これは「働ける人が減った代償」であり是正対象ではない。UI誘導は「上限を上げてください」を主導線に
   **しない**（`mayPlace`/上限0除外(3.507.0)は仕様どおり）。残る論点は表示のみ: ①「0になった」ことがエラーに
   見えないこと（例:「パートのため対象外」）、②除外の代償（他制約が寄る理由）の説明、③ワンタップ設定修正・
   Sanityの誘導文でパートの上限0を「直すべき項目」にしないこと（誤って0になった正規職員の検出は別条件が必要）。
   実機のdesign-review時にこの方針で確認する。
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
        循環交換の 3〜5 人拡張＝3.511.2 実装・iter14 で必須退行0だが品質・速度10%未達＝**不合格、既定OFF維持**（試行数20回/日では170ペア中1件にしか当たらない、増やせば変わる可能性）／適応的予算配分／~~C3「選択日ペア交換」~~（3.510.0 実装・iter8 で品質 +0.04%＝不合格、既定 OFF 維持。統計ゲートでは合格したため 3.610.0 で現行コードに再測定＝230ペア中229が同一盤面・勝1/負0・p=1.0＝**不合格、昇格見送り**。後続パスが同じ改善を先に拾うようになった）＝いずれも `tools/loop` で測ってから 1 件ずつ。
    (c) D9 の方向別合成（片側だけ個人優先）＝決定 D9 は完全上書き。要件変更の明示があれば。
    (d) UX: 高度修正の適用前プレビュー（対象・変更セル数・必須/ソフト/希望の前後）、自動修正候補の比較、実現不能と探索不足の分離表示、
        時間予算/並列数/アルゴリズムのプリセット化、操作名付き Undo 履歴、利用者向け名称（「破壊」「LNS」「HF80」を出さない）＝画面設計。実機ビルドと design-review が要る。
13. **自動化方針（`docs/automation.md`）の「未」**: (a) 停滞時の探索半径・職員数・窓長の段階的拡大（測定）／~~(b) 採用ゲートの一本化~~（3.509.5 `adoptionGate`）／
    (c) 必須減少時のソフト損失上限（5%）＝採用基準の変更（決定待ち）／(d) 時間予算の自動計算（規模・必須数から）／~~(e) 3〜5 職員循環交換の 5 人まで拡張（測定）~~（3.511.2、backlog #12(b) と同一実装）。
    (f) 安全ゲート（`FixApplyGate`）合格の先頭候補を利用者のタップなしで自動適用（自動化 UX 評価 優先 1 の後半。適用後に「変更を見る／元に戻す」）／(g) 1 手候補ゼロ→「自動で整える」（区間交換・循環・成分修復・LNS）への自動接続（優先 2、Undo は接続全体で 1 回）／(h) 前景/背景の自動選択（優先 5）／~~(i) 内部用語の詳細設定への隔離（優先 7、design-review）~~（3.551.0: ラベル/説明/busy 表示の文言を利用者向け語へ。design-review は実機ビルド後）。
14. **重み・時間配分の外部分析（2026-09-08、ユーザー提示。最新 main とログ 10 本の傾向）＝適応制御として導入しペア比較で採否**:
    (a) C1 共同 LNS・個人共同 LNS を「短時間試行→改善時だけ拡張」（3.510.2 で `lnsAdaptive` 実装・iter9: 退行 0・品質 ±0・速度 平均 +10%/実データ −23%/中央値 −1.5%＝規則上不合格。**→ 3.514.0/3.518.0 で既定 ON**（`PolishGate.lnsAdaptive = true`、画面トグルあり）。
        ログでは両 LNS が後処理時間の 75〜91% を占め採用 0／(b) 後処理 4 巡→基本 2 巡・採用時だけ最大 4／固定長ブロック交換（11/13/17/19/23/28 日）を違反窓長・禁止連長・希望島半径からの動的窓へ＝3.511.0 `dynamicBlockLengths` 実装・iter12 で**不合格**（大・充足不能ケースで必須+1、既定OFF維持。単一パスのkeep-bestがチェーン全体を保証しない経路依存）／
    (d) C2・C41/C41s を不足量・超過量評価へ（評価仕様変更＝#12(a)）＝3.512.0で`Problem.quantitativeRangeEval`として全層実装済み・既定OFF、
        iter21で**不合格**（170ペア新良5/同等153/旧良12、品質±0.00%・速度-1.1%とも10%未達、必須件数が増えた試行6件はlarge-infeasible
        カテゴリのみ〈平均122.00→122.60、供給不足枠で不足量評価が異なる手を選ぶ〉、既定OFF維持）／(e) apt・fair・weekly は重みを変えず辞書式の副目的（apt 偏差→fair 最大偏差→weekly 最大偏差→偏差合計→変更セル数）で比較（採用基準の変更＝決定待ち）／
    (f) low と c3m の A/B（提示時は low=90→60・c3m=2。現行は `MirrorKeys.weights` で low=120・c3m=6＝3.522.0/3.556.0。HF77: 明示数値の指示があれば）。low の値自体より「一時的に low を悪化させ同一トランザクション内で戻す複合手」を先に強化／
    ~~(g) 予算超過の回帰試験（内部ループが 100〜250ms ごとに停止判定、300 秒設定で実時間 305 秒以内、停止要求から 5 秒以内、例外ワーカーで固まらない）~~ **→ 3.511.4 で完了**（`BudgetOverrunTest.kt`。V5/ALNS/RSI/PORTFOLIOは固定スラック±3000msで自然な予算超過を検証、RSI_PLUSはフェーズ下限（backlog#16）を踏まえ別途60秒上限で検証、停止要求は300ms計算後に5秒以内で反映、`runMultiWorker`は一部/全ワーカー例外でも2秒以内に制御が返ることを検証。ループの100〜250ms間隔そのものを直接計測する単体テストは無い（本番コードへの計装が要るため見送り）。2026-09-16 に本行の打消し線漏れを是正（実装は既にmain）。
    維持: HARD 辞書式・HARD 桁 1e9・HARD 族分類・C1=50/C3mn=90（現行 `MirrorKeys.weights`。提示時は各 30）・8 ワーカー・後処理予約 25 秒・月内完結。
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
    関連: 3.600.0 で按分を `rsiPlusPhaseBudgets` に切り出し、`V6OptimizerOptions.roleBudgetFit`（既定OFF、#34(b)・3.601.0 で有意差なし、2026-09-25 撤去）が予算按分側の対処だった。
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
19. **[探索動学・決着（3.571.0、weightedScore 勝ち 0/40＝既定OFF維持）] HARD 同点時のタイブレークが探索と公式で食い違う**（3.568.0 で測定・登録）。
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
21. ~~**[将来課題・実装不要] タグ成果物（リリース APK）を GitHub Release アセットへ移す**~~（3.570.0、ユーザー決定
    「将来的にGitHub Release assetへ移す」）。~~現状は `release-build.yml` が `v*` タグで `actions/upload-artifact`
    （`retention-days: 14`）へ APK を置くだけ＝Actions アーティファクトの保存枠を消費し、`cleanup-artifacts.yml`
    の除外リスト（`app-release-` prefix）で個別に保護している。~~
    **→ 2026-09-22でユーザー明示go・実装完了**。`release-build.yml`にタグビルド専用の`publish-release` job
    を新設し、`gh release create`/`gh release upload`（追加の第三者actionは使わず既存のgh CLIのみ）で`v*`
    タグへAPKをRelease アセットとして添付する。書込みトークンは`report-failure`と同じくこのjob単独に分離
    （3.567.0で確立した「ビルドjobは読み取り専用」方針を踏襲、Gradle子プロセスへ書込み権限を渡さない）。
    `release` jobの受け渡し用アーティファクトは`release-apk-handoff-<sha>`（retention-days:1）へ改名し
    `cleanup-artifacts.yml`の保護対象から外した（恒久コピーはRelease アセット側）。**手動実行
    （workflow_dispatch）はRelease を作らないため`app-release-*`・retention-days:14・cleanup-artifacts.yml
    の保護を従来どおり維持**（署名鍵・Lintゲート・縮小は引き続き対象外＝今回の変更に含まない）。
    **→ 2026-09-22に実タグpushで動作確認完了**。初回`v3.608.0-verify-release-asset`は`gate`のDesign lintで
    落ちた（P6誤検知＋P10 baseline陳腐化、いずれも今回の変更とは無関係の既存不具合。原因調査・修正は同日）。
    修正コミット`fa84633`を`v3.608.1-verify-release-asset`で再検証し、`gate`→`release`→`publish-release`
    全ジョブ緑、GitHub Releaseが実際に作成されAPK（`app-release.apk`、44.9MB）がアセット添付されることを
    確認（https://github.com/ichirocc/magi7ichiro-fork/releases/tag/v3.608.1-verify-release-asset ）。
    backlog#21は完全に決着。
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
24. ~~**[要product判断・実データで実害確認済み＝深刻] `restShiftIndex`（`MirrorCore.kt`）の記号依存＋無言の0退避**~~
    **→ 3.603.0で完了**: 業務担当者の明示go＋grillingを経てフル改修を実装。`ShiftRole{None,Rest}`を
    `Shift`に追加し、`restShiftIndex`は記号一致でなく`role==Rest`の付与先を返す（無ければnull、
    フォールバックは撤去）。`Problem.restIdx`を含む全参照約20ファイルを`Int?`へ全面伝播
    （ユーザー指示で「入口だけブロックする縮小案」を明示的に却下）。JSON後方互換（旧schemaは記号"休"へ
    自動付与）・UIトグル（`Ws1Editor.kt`「休みとして扱う」単一選択）を追加。詳細は
    `docs/history/3.4xx.md`（3.603.0）。
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
    JSON互換のschema変更）は引き続き保留。**2026-09-20 grillingで再確認**: フル改修は
    `docs/data-models.md`が3.345.0/3.416.0で二度確定させた「休は通常のシフトの一つ・記号一致で解決」の
    方針転換にあたり、参照箇所11ファイル・JSON schema変更を伴う。暫定対応（削除確認ダイアログ）で
    実害は回避済みのため、業務担当者の明示指示があるまで保留を継続（ユーザー判断）。
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
    - ~~並列所有権・停止・統合経路の監査（親子で同一Semaphoreを共有するとデッドロックし得る、盤面の
      所有権・乱数のワーカー分離・単一Reducerでの最良更新・実行世代スナップショット）。~~
      **→ 2026-09-21 監査実施・該当なしと確認**。`grep -rl "Semaphore" app/src/main/java/`は0件＝
      このコードベースに`Semaphore`は一つも存在しない（並行処理は`Mutex`/`synchronized`のみ使用）。
      外部提案が挙げた「親子で同一Semaphore共有のデッドロック」という具体的な機構は実装に存在せず、
      提案側が本コードベースを正確に把握せず一般的な懸念テンプレートを当てはめたものと判断する。
      盤面所有権・乱数のワーカー分離・単一Reducer・実行世代スナップショットの各観点は、
      `V6NativeOptimizer.RunSlot`（コルーチンコンテキストでの実行ごとの箱運び）・`sharedTrajectories`
      のロック方式など既存の設計で個別に対応済み（本セッションでの精読範囲では新規の欠陥は未発見）。
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
      既定ON昇格に不合格と確定。
      **→ 3.586.0でC1成分修復・C3nMarginLnsPolishの測定手法を確定・測定完了**。
      tools/loopの合成ケース生成器はこの2腕の狭い効き所（他パスが先に消費してしまう局面）を
      再現できない（3.581.0で確認済み）と判明していたため、**実データ4件への直接ON/OFF比較**
      （`runPostOptimization`をc1ComponentRepair/c3nMarginLnsEnabledそれぞれtrue/falseで実行し
      breakdown・hard・weightedScoreを比較）という、tools/loopより単純で直接的な手法に切り替えた。
      結果: **golden/sample_v6/blocked_covu/sept2026の4件全てで、ON/OFFの結果が完全に同一
      （hard・c1・c3n・weightedScoreが1件残らず一致）**。特にsample_v6は後処理後もc3n=1
      （HARD、weight9000）が構造的に残る唯一の実データだが、c3nMarginLnsEnabled=trueにしても
      この1件は解消されなかった＝この特定の残存はマージンLNSの対象外の構造（両側wish固定等で
      動かせない完全に手詰まりな配置）である可能性が高い。旧history（3.511.8/3.511.9）の
      「4実データ: 既定OFFのhash 4/4不変」という記述は「OFF同士の再現性確認」であって
      「ON/OFF比較」ではなかった＝今回のほうがより直接的な検証になっている。
      **結論**: 5腕全て（tools/loop測定3腕＋実データ直接比較2腕）で既定ON昇格の根拠なし。
      C1成分修復・C3nMarginLnsPolishも既定OFFのまま維持を確定（コード・テストは残す）。
      今後もし将来の実データ（新しい実機ログ・アップロード）でこの2腕の狙い通りの構造
      （c1なら複数cons1窓の重なりでexhaustive=true手詰まり、c3nならマージン日を使えば解ける
      禁止連続）が実際に見つかれば、その時点で同じ直接比較手法で再検証する。backlog#26は
      これで全項目の測定を完了した。
    - 停滞判定の精緻化（正式最良不変・同一探索範囲の反復・修復途中の進捗・証明済み下限・時間不足を
      区別）と、既存の再配属/摂動/エリート機構への小さな追加としての限定的な専門腕の試行。
      **→ 2026-09-22 grillingで「修復途中の進捗」1点に絞って着手を検討・調査の結果、実装不要と判明**。
      `AdaptiveHypothesisEpochPolicy.shouldRequestNewRole`が使う`improvedThisEpoch`
      （`V6NativeOptimizer.kt:795`）は`better(eliteReport, preEpochEliteReport)`＝正式な
      hard→weightedScore→total辞書式比較器そのもので、HARDが1件でも減ればweightedScore/totalが
      同点・悪化でも既に「改善」と判定される。「HARDが減っているのに停滞扱いされる」という想定した
      具体的な欠陥は存在しない＝正式基準がすでにHARD優先で修復途中の進捗を拾っている。残る3区別
      （証明済み下限・同一探索範囲の反復・時間不足）は狭い1点修正では届かず、当初の「本格再設計」の
      範囲のまま＝合成ケースでこれらの違いを作れるかも不明（backlog#26の他5腕と同型のリスク）。
      具体的で測定可能な仮説が無い限り着手しない（コード変更なし、調査のみ）。
    - 業務重み（正式スコア用）と腕選択・予算配分の基準を分離する設計指針の明文化（探索スケジューラに
      新しい重み表を作らない）。
    比較は同一入力・同一seed群・同一総予算（固定仕事量と壁時計予算の両方）で行い、tools/loopの
    ベンチマークで測ってから個別に採否する（CLAUDE.md「探索動学の変更は測ってから採否」）。
27. **[探索エンジン・①完了] `AptFairPolish.fairTarget`（候補分類）と`Problem.fairDevOfBucket`
    （正式評価・達成率モード）の乖離＝①（候補分類の黒箱観測化）実装・ベンチ判定完了＝既定OFF確定、
    ②（交換探索改良）は未着手**
    （外部提案、2026-09-17）。`fairTarget`（群の全メンバーの生回数の単純平均）は`applyFairPolish`が
    `distLocations["fair"]`（`fairDevOfBucket`由来、担当可否でフィルタした母集団の達成率ベース
    幅重み付き中央値）の各セルをhigh/low分類するのに使われるが、母集団・計算式とも独立した別経路。
    **→ 3.588.0で③（乖離の実測）を実施**: 実データ4件（生入力31件＋決定的後処理後34件=計65件）＋
    母集団差を意図的に作った合成ケース(5件)を合わせ、計70件の`distLocations["fair"]`セルを走査。
    `fairDevOfBucket`は判定式を複製せず仮想入力(±1)で黒箱呼び出しして方向を観測
    （`DeltaEvaluator.fairDevAt`と同じ手法）。結果: sept2026の後処理後盤面で**1件、実在する乖離を
    確認**（`fairTarget`は「一致」と判定し何もしないが、達成率モードでは不足方向の是正が有効＝
    取りこぼし）。母集団差（担当不可かつ回数0のメンバー除外）自体は合成ケースで発生を確認したが、
    今回見つかった実データの乖離はそれとは別要因（整数丸めの一致ゾーンのズレ）。`FairTargetDivergenceTest`
    で固定（`fairTargetOmitsACellThatOfficialAchievementRateWouldStillFlag`）。
    **→ 3.590.0でgrillingにより①（分類ロジックの黒箱観測化）を実装**: 分類ループ本体と
    `worsensOwnFair`（玉突きチェーンのavoid述語）の両方を`fairDevOfBucket`の仮想入力(±1)黒箱観測へ
    置換、`PostOptimizationParams.fairAchievementDirection`（既定OFF）でA/B切替可能に。
    `FairAchievementDirectionTest`で取りこぼしの再現(OFF)・修正(ON)を固定。重み・6%許容・keep-best
    採用規則は変更しない（候補分類ロジックだけの改善）。
    **→ 3.591.0でtools/loopのA/Bベンチ判定（5seed×46ケース）完了**: 仕様§4の4基準（退行ゼロ／品質≥10%／
    速度≥10%／安定性）すべて不合格（品質±0%・速度+1.6%で便益測れず、既にHARDが残るinfeasible区分で
    必須件数が増えた試行6件）。backlog#26の5腕と同じ基準で**既定OFFを維持**。オプトイン切替
    (`fairAchievementDirection`)とテストは残す。①は実装・計測完了。②（交換探索の改良）は未着手。

28. **[探索エンジン・測定済み（有意差なし）・既定OFF維持] `RsiFocusSelection`の周期枠が呼出しごとにリセットされ、
    短いRSI呼出しでは特定の族(covO等)の専用探索機会が回らない条件がある**（外部提案、2026-09-17）。
    3.592.0の12件修正と合わせて検証し、これだけは探索動学の変更にあたるため保留していた。周期枠(`round%3`)
    はrunRsi呼出しごとにゼロから始まるstack-local変数で、短い(2ラウンド)呼出しを繰り返すと最終ラウンド
    以外の専用枠(`round%3==1`=apt, `==2`=covO)に到達しない。値が静的なケースでは同じ族(c1→apt)を
    毎回選び続けcovOの枠が来ない条件が実測で再現された（サブエージェントで実コード確認済み）。
    **grillingで確定・実装**: 持ち越し先はワーカー専属で共有される`Hf63Infeasibility`（ユーザー指示。
    本来の不能性追跡とは無関係だが、既にrunRsi呼出しをまたぐ寿命を持つため流用）に`gFocusRotationRound`
    を追加し`nextFocusRotationRound()`で取得。`RsiFocusSelection.maxViolatedFamily`に`rotationRound`
    引数を追加（既定=`round`＝挙動不変）、`V6OptimizerOptions.rsiFocusRotationPersist`（既定OFF）が
    ONのときだけ`hf63.nextFocusRotationRound()`を使う。1ラウンドにつき1回だけ進め、早期終了判定(pivot)
    にも同じ値を使い回す。hosttest 823件緑。**tools/loopでのA/Bベンチ・採否判定は未実施**。
    **→ 2026-09-21で判定完了・既定OFF維持**。`PortfolioBudgetBench.kt`に`rsiFocusRotationPersist`腕
    （`PORTFOLIO_BENCH_FEATURE=rsifocusrotation`）を追加し実データ4件×5seed×budgetSec=90sで測定
    （`tools/loop/results/portfoliobudget_rsifocus.csv`）。**結果: 全4件でON(持ち越しあり)がOFFより悪化**
    （golden +2.49%・sample +0.19%・blocked_covu +0.46%・sept2026 +0.35%、weightedScore平均、hard件数は
    全fixture・全seedでON/OFF完全一致、epochOverrunCount=0）。改善する fixture が1件も無い＝
    `RsiFocusSelection`の周期枠を呼出しをまたいで持ち越すこと自体は、この規模の実データでは有害
    （golden以外はほぼ無風だがgoldenで明確に悪化）。**既定OFF維持**、フラグ・コードは残す。
    **→ 2026-09-22 机上見直しで訂正**: 4件とも有意差なし（Welch t=+0.10〜+1.65、最大のgoldenでもp≈0.15）。
    PORTFOLIOは壁時計予算＝同一設定でもばらつく（同じ構造のHandleOptimizeBenchのA/A実測で品質±1.6%）。
    「全件悪化・goldenで明確に悪化」は言い過ぎ＝**「便益の証拠なし」**が正しい。既定OFFの結論は不変。

29. **[完了・3.596.0] `SmartInitialScheduler.solveConstructionDp`の状態爆発**（外部提案、2026-09-17・
    サブエージェントで実コード確認、2026-09-18にgrillingで方針決定後に修正）。状態キーは直近
    `maxWindow-1`日のビット履歴＋累積回数で、希望固定/個人上限が無いと状態が併合されず窓長に対し
    指数的に増える。実測（T=31・cap無制限）: 窓14=0.4秒/窓16=2.0秒/窓18=6.3秒/窓20=13.4秒、
    窓22以上はヒープ3GBでもOOM＝実機では「30日間で8回以上」のような妥当な入力でアプリが落ちる。
    **対処**: (a) 診断パス（`cachedMinDays`→`minDaysForFullCompliance`。セル編集のたびに
    `analyzeStaffConflicts`経由で走る）は0違反の可否と最小日数しか要らず、窓制約はconsecutive-ones＝
    全単模なので**右端優先の厳密貪欲**（O(期間×窓数)）へ置換＝爆発が構造的に消える。
    (b) 構築パスはDPのまま**状態数上限200,000**を超えたらnull＝その職員/シフトのC1充足だけ諦める
    （既存の`?: continue`。後段のSA/ALNSが埋めるので「充足不能」とは誤判定しない）。時間でなく状態数で
    切るのは決定性（同じ入力・同じseedで同じ初期解）を保つため。検証は総当たりオラクル
    （2^T全列挙）との網羅照合＝`MinDaysForFullComplianceTest`。C#同期は別タスク（本セッションは
    リポジトリ参照不可）。

30. **[完了・3.598.0] `MinCostAssignment.solve`が禁止辺(INF)を含む割当を返しうる**
    （外部提案、2026-09-17・2026-09-18にgrillingで方針決定後に修正）。**実測で再現**: 2x2で列0が両行とも
    禁止のとき`null`でなく禁止辺1本入りの割当を返す（3x3の鳩の巣形も同様）。既存の`j1 == -1`ガードは
    全INF**行**しか捕まえず、全INF**列**は素通りする。
    **呼出側の実害経路（今回判明）**: `DayAssignmentPolish`の列は「その日の既存シフト」なので、空セル(-1)や
    誰も置けないシフトがあるとその列が全INFになりこの穴を確実に踏む。返る禁止辺が担当不可シフトなら
    groupViol(HARD)でゲートが弾くが、`mayPlace`は「担当可かつ**個人上限≠0**」なので**上限0の職員に置く辺**は
    HARDにならずhigh(25)だけ＝ゲートが受理し得る（3.507.0「上限0は最適化器が置かない」に反する）。
    **対処(1)** 返却前に禁止辺の混入を検証してnull＝無条件で修正（3.597.0、再現ケースを
    `PolishRobustnessTest`へ）。**対処(2)** 対角（自分の現シフトを保つ＝盤面を変えない）を常に有限にし、
    恒等割当を常に実行可能にする＝置けない職員/スロットがある日も残りを研磨できる。凍結対の選び方に
    タイブレーク設計が要らず、最大化は費用最小化がそのまま厳密に行う。探索動学の変更なので
    `PostOptimizationParams.dayAssignIdentityFallback`（既定OFF）に置き、tools/loop A/B（5seed×46ケース）で
    計測した。**judgment=不合格→既定OFF維持**（3.598.0）: 230ペアで辞書式 新5/同等224/旧1、品質は平均・
    中央値とも+0.00%、速度+0.3%、必須違反の退行0件。ほぼ完全なno-op＝ベンチ資材では「完全割当が不可能な日」
    がほとんど発生しないということで、当初の「実害は未確認」と整合する。フラグは将来の再評価用に残す
    （腕キー`dayassignidentityfallback`、CSVは`tools/loop/results/`）。

31. **[既定OFF・実害小] `C42FlowPolish`のbaseCountが（`C41FlowPolish` は 2026-09-25 撤去）群外の職員も数える**（外部提案、
    2026-09-17・実コード確認済み）。群スコープのレンジ制約コストに使う人数集計が、動かす職員以外の
    **全職員**を数えており、対象群のメンバーだけを数えるべき箇所で他群の配置が「満たしている」ように
    誤誘導する。両パスとも既定OFFの実験腕（tools/loopでベンチ済み・不採用、`docs/backlog.md`記録済み）
    のため現状の実害はほぼ無い。ONにする判断が出た場合のみ合わせて修正する。

32. **[意図的・文書化済み] `HardRepairCore.hf67HardRepair`が巡内で古い個人回数を参照する**
    （外部提案、2026-09-17・Kotlin/C++両方で確認済み）。人員不足修復の被覆埋めループは巡開始時に
    回数表を1回作り、巡内の複数割当で更新しない（同一職員へ偏りうる）。C++側
    (`magi_native.cpp`のコメント)に「周内はKotlinと同じく据え置き」と明記された意図的設計（3巡に
    限定）。個人上限のレンジ下限ループは正しく逐次更新している。新規の不具合ではないため、
    優先度は低い（性能とのトレードオフとして残す）。

33. **[低] 初期解DPのseed由来tie(day)項が置く/置かないの両分岐に同一加算され相殺する**（外部提案、
    2026-09-17・実コード確認済み）。`solveConstructionDp`の`tie(day)`は日にのみ依存し配置有無で
    同一値のため、全経路に共通する加算定数として比較で相殺される。最終同点処理は固定ビット順で
    seed依存ではない＝seedを変えても初期解が多様化しない。多様化を狙うなら選択に依存した同点処理が
    必要（対応は低優先度）。

34. **[凍結対策は3.611.0で実装・実機ログで効果確認待ち] ロールの時間クォンタム超過＝2つの構造的原因をコードで確認、修正はgrilling後**
    （外部提案「Stall Exit設計」2026-09-17をきっかけに再検証。サブエージェントで実コード・history照合、
    2026-09-17に追加の外部検証文書を再確認しさらに機構を特定）。
    外部提案は「クォンタム5〜45秒に対し実測15,000秒」と主張したが、実際にこのrepo自身が記録済みの
    規模は300秒予算が474〜959秒（history 3.409.17、`docs/history/3.4xx.md`）＝最大3〜9倍で、
    提案の300〜3000倍という規模とは一致しない（外部提案の数値は誇張/別ソースの可能性）。
    `stopRole`（`V6NativeOptimizer.kt`のエポックループ）はロール呼出しの**間**でしか効かず、超過検知
    （`epochOverrunNotes`）は呼出し後の事後ログのみ＝根本原因は当時「原因の特定には至っていない」と
    明記され再現も失敗している。
    **確認済み機構1（3.594.0/3.595.0で再検証・3.599.0で訂正）**: `runRsiPlus`（`V6NativeOptimizer.kt:1893-1906`）は
    budgetSec(=quantum)をSeed/Hypothesis(RSI)/Refine(ALNS)/Polishの4フェーズへ按分する際、主要3フェーズに
    各`max(10, budget*0.20/0.35/0.30)`の下限（floor）があり、budgetが小さいと下限**算術和**がbudget自体を
    超える（budget=10sで3フェーズ下限合計30s＝3倍、polish下限5sを足すと35s＝3.5倍）。
    **ただし3.595.0時点の記載は「フェーズが必ず全部走る」前提の誤り**: `:1911`
    `val rsi = if (shouldStop()) seed else runRsi(...)`、`:1915`
    `val refine = if (shouldStop()) base else runAlns(...)`と、Seed完了後に**フェーズ境界のstopRole判定**が
    ある。Seedが下限10sを使い切って`shouldStop()`が真になれば、RSI/ALNSは丸ごとスキップされ実時間は
    35sへ積み上がらない（RSI+ロールが実質V5＝種生成だけへ静かに縮退する、という別の問題ではある）。
    35s固定という数値は「全フェーズが必ず走った場合の上限」であり、実測される超過の主要因という主張は
    未検証のまま。
    **確認済み機構2（同日再検証・確定、変更なし）**: `stopRole`はネイティブチャンク（`nativeAlnsChunk`、200
    イテレーション単位、`V6NativeOptimizer.kt:1435-1459`）やRSIのラウンド境界（`:1775-1776`）でしか
    確認されない協調的（cooperative）ポーリングで、プリエンプティブに強制中断する仕組み（Jobキャンセル・
    `withTimeout`等）は無い。ポートフォリオ外側の`while (nowMs() < deadline)`（`:615`）は1ロール呼出しを
    suspend関数として同期的に待つのみで、ロール内部が唯一の脱出点＝ロールが長時間戻らなければ外側loopも
    進めない。
    **未確認のまま／3.599.0で1点訂正**: 上記2機構が実測の3〜9倍規模を単独で説明できるか、外部提案が
    主張した4.3時間級の規模を説明できるかは未確認。`nativeSaChunk`（`magi_native.cpp:963-965`）の
    `maxIters=200000`は安全上限であり、実際は`for (double t = t0; t >= tf && iters < maxIters; ...)`で
    **冷却条件(t<tf)でも終了する**＝「maxItersのみで制御」という3.594.0の記載は不正確。実測反復数を
    記録していないため、1チャンクが実際にどれだけ時間を使っているかは依然不明。
    **測定手段の欠落（3.599.0で判明）**: `tools/loop/LoopBench.kt`のA/Bベンチは`V6NativeOptimizer.optimize`を
    `algorithm = V6Algorithm.V5, workers = 1`固定で呼ぶ（`:82`）＝ポートフォリオ（`runAdaptivePortfolio`）も
    `runRsiPlus`も`stopRole`も一度も経由しない。したがって現行のtools/loopベンチはbacklog#28（RSI周期枠）・
    本backlog#34のいずれも測定できない。着手前にベンチ側の対応が必要（新しい腕でworkers>1/RSI_PLUSを
    実際に回し、フェーズ別実時間・ネイティブ呼出し実時間・SA実測反復数・予算超過時間を記録する）。
    **修正方針（着手前にgrilling要、測定経路の整備が前提）**: (a)`runRsiPlus`のフェーズ下限を
    **ロール単位**でbudget内に収まるよう比例縮小するか、残時間がロール最小値未満ならそのロールを
    ポートフォリオに配らないか、(b)ロール復帰後`now > roleDeadline`なら次のフェーズへ進まず即epoch break
    するのか、(c)ネイティブチャンク上限を残り時間でcapするのか＝いずれも探索の時間配分（search dynamics）
    を変える設計判断のため、grillingで方針を詰めたのちtools/loopで計測してから採否する
    （CLAUDE.md「探索動学の変更は測ってから採否」）。
    backlog#16(g)（予算超過の回帰試験、3.511.4完了）はこの根本原因を直接検証するものではない
    （固定スラックでの自然超過は確認済みだが、100〜250ms間隔のポーリング自体の単体テストは無い）。
    **3.600.0で着手（P0のみ、P1は不採用）**: 外部パッチ（`stall_deadline_review_fixed.patch`）を
    (a)純粋な正しさ＝既定ON と (b)時間配分の変更＝既定OFFフラグ に分割して取り込んだ。
    **(a) 既定ON**: ①締切/停止済みならロールを始めない（始めれば位相下限ぶん必ず超過する）、
    ②ロール成果を**回収してから**締切判定してbreak（旧パッチ案は回収前にbreakし締切間際の改善解を
    捨てていた）、③`runRsiPlus`入口で停止済みなら入力をそのまま返す。いずれも「時間の無い仕事を
    始めない／既に得た成果を捨てない」だけで探索を悪化させる経路が無いため測定不要と判断。
    **(b) 既定OFF=`V6OptimizerOptions.roleBudgetFit`**: ロールへ渡す秒数を`min(量子, 残り)`にし、
    `rsiPlusPhaseBudgets`で位相合計を予算ちょうどに収める（予算>=40sなら従来の10s/5s床を維持、
    短いときだけ比率配分＝**短時間でも各段階を一度は試す**、ユーザー決定2026-09-18）。RSI_PLUSの量子は
    base35s/improving45sで**base35sが比例配分側に落ちる＝通常経路の探索動学変更**のため既定OFF。
    単体テストは`RsiPlusPhaseBudgetTest`（合計＝予算の不変条件・短予算でも全位相≥1・大予算で床維持）。
    **計測はまだできない**（上記「測定手段の欠落」のとおり現行tools/loopはポートフォリオを通らない）＝
    ポートフォリオ経路のベンチを用意してから採否する。
    **→ 3.601.0でベンチ整備・(b)の採否判定完了**（本行、当時のbacklog更新漏れ。2026-09-21に追記）。
    `tools/loop/PortfolioBudgetBench.kt`（`run_portfolio_budget_bench.sh`）を新設し、`V6Algorithm.PORTFOLIO`を
    実際に回すA/Bを実データ4件×5seed×budgetSec=90sで実施（`LoopBench.kt`が経由しない
    `V6OptimizerOptions.roleBudgetFit`の可否）。結果: golden -1.9%・sept2026 -2.3%改善、sample ±0.03%同等、
    blocked_covu +0.8%悪化、epochOverrunCountは全件0。**判定＝既定OFF維持→2026-09-25 フラグ・`rsiPlusPhaseBudgets` ごと撤去**（3/4改善もエッジケース悪化ありで
    探索動学変更のリスク・報酬比が不明確、詳細は`docs/history/3.4xx.md`3.601.0節）。これにより
    「測定手段の欠落」は解消済み＝このベンチ基盤は今後backlog#28（RsiFocusSelection周期枠）等の
    ポートフォリオ経由の測定にも再利用できる（フェーズ別実時間・SA実測反復数までは未計装、必要なら追加）。
    **→ 2026-09-22 机上見直しで訂正**: 却下理由の「blocked_covu +0.8%悪化」は、同構造ハーネスのA/A実測で
    得たノイズ幅（品質±1.6%）の内側＝有意差なし。「悪化あり」でなく**「便益・害とも検出できず」**。既定OFFは維持。
    **実データで新しい規模を確認（3.608.0作業中に発見）**: 実機ログ（Google Pixel 10 Pro XL・11名/31日）で
    `W5:MAX_DISTANCE_RSI_PLUS(q=45s→実8166s)`など**量子比で最大約181倍**を観測、これまで記録の3〜9倍を
    大きく超える。同ログで壁時計（`System.currentTimeMillis`基準の操作ログ経過表示）は19,950秒進んでいたが、
    `EngineClock`（`System.nanoTime`＝単調時計、ディープスリープ中は進まない）ベースの内部集計は8,304秒。
    差分約11,646秒はディープスリープ中の空白と考えられ、単調時計化（3.490.0）はここでは正しく機能している
    （壁時計だったら差分ぶんさらに暴走していたはず）。**残る仮説**: ディープスリープでなくバックグラウンド
    実行制限（Doze/App Standby、画面OFF・フォアグラウンドサービス/WakeLock無し）でCPU配分が絞られた場合、
    単調時計は進み続けるがネイティブSAチャンク（本来ミリ秒級）の実行が極端に遅くなり、`stopRole`の次の
    チェックポイント（チャンク境界・RSIラウンド境界）へ到達すること自体が遅延する、という説明がこの規模の
    量子超過と整合する。未検証・要確認。
    **→ 2026-09-22 実機ログ精査でプロセス凍結と確定**。超過3回とも8ロールが同秒数だけ超過し、その秒数は
    「最後の進捗→完了」の空白と一致（締切漏れなら算法ごとにばらつくはず）。診断文を凍結/経路漏れで出し分けた。
    残課題（要判断）: 実行中の凍結防止（WakeLock/前景サービス経路）、復帰時に後処理予約を使うか。
    **→ 2026-09-23 ユーザー指示「すべて」で凍結対策に着手（3.611.0、作業ブランチ）**: 前景実行（最適化・ソフト研磨）の
    間だけ部分 WakeLock（予算+10分で自動解放）を持つ。ただし敵対的レビューで、**前景サービスの無いアプリの WakeLock は
    画面 OFF から約60秒で OS が無効化する**（TOP_SLEEPING は背景扱い→UID idle→PowerManagerService が cached/idle の
    部分 WakeLock を無効化）と判明＝WakeLock 単独では 300 秒実行の凍結を防げない。別アプリへの切替も同様に凍結しうる。
    防げるのは前景サービス（「バックグラウンドでつくる」＝`OptimizationWorker.setForeground` 済み）。
    **ユーザー決定「前景サービス併用」（2026-09-23）**: 前景実行の間だけ `ForegroundRunKeepAlive`（WorkManager の
    expedited Worker が `setForeground` して待つだけ、dataSync 型・同じ通知チャネル、「勤務表をつくっています」の常駐通知）を
    立て、WakeLock と併せて持つ。独自 Service でなく WorkManager に載せたのは、起動直後に止めると startForeground 前の
    停止でクラッシュする競合を避けるため。実行中の画面の出入り（画面OFF/切替の別・秒数つき、回転などの構成変更は除外、
    1実行5回まで）は操作ログに残す。実機での効果確認は次の実機ログ待ち。復帰時に後処理予約を使うかは未着手。
    **→ 2026-09-22 grillingで方針決定・フェーズ別実測ms計装を追加**（このサンドボックスはAndroid実機ではなく
    Doze自体を再現できないため、確証は次の実機ログ取得が前提。まず情報量を増やす）。`runRsiPlus`
    （`V6NativeOptimizer.kt`）の4フェーズ（Seed/Hypothesis/Refine/Polish）は従来HARD/totalしかログに
    出しておらず、8166s(W5:MAX_DISTANCE_RSI_PLUS)がどのフェーズで生じたか特定できなかった。各フェーズの
    `実測Xms(予算Yms)`をMirrorLogへ追加（Kotlinのみ、探索動学・スコア・native/C#は無変更、hosttest 826件緑）。
    **次に実機ログを取得したら**: 突出したフェーズが1つに絞れれば原因箇所の当たりがつく（例: Seedだけが
    突出→SaOptimizer/nativeSaChunk側、全フェーズが均等に伸びる→プロセス全体がフリーズされる系の原因
    （Doze等）を示唆）。Doze/App Standby仮説そのものの検証は依然、実機ログでの確認が前提＝未検証のまま。

35. **[測定済み（実質A/A）・既定OFF維持] 追加精製(ExtraRefine)を「後処理でHARDが減ったとき」だけに絞る案**（外部パッチの
    P1、2026-09-19にユーザー判断で**既定適用を撤回**）。`V6FinalPort`の`canExtra`へ
    `post.report.hard < integrated.report.hard` を足す案だったが、これは「空振り検出」ではなく
    **追加精製の起動方針変更**であり、既定ONにはできない。却下理由（ユーザー指摘）:
    ①**HARD=0のとき常に省略される**＝統合も後処理もHARD=0なら条件が偽になり、配布可能な表のSOFT仕上げを
    潰す。②HARD不変でも後処理で配置が変わり追加ALNSがまだ動かせるケースを、実機1件のログでは否定できない。
    ③「18s・改善なし」は単一実行の結果で一般化できない。④`pickBestStage`があるため追加精製の有無は
    段の候補集合自体を変える＝keep-bestで品質同等とは言えない。
    **grillingで確定・実装**: 元案の生差分ではなく、停滞検知が既に持つ「構造的に解けないと証明済み」判定
    （構造的covU床`hardFloor`／`ForbiddenDiag`が全run塞がりを証明したc3n壁）を流用し、却下理由①②を安全に
    回避する。`handleOptimize`に`extraRefineRequirePostHardDrop: Boolean = false`引数を追加（ViewModelは
    未使用のまま既定値で呼ぶ＝UI挙動不変）。ONのとき、`post.report.hard>0`かつ非covU HARD残が0（covU<=
    hardFloor）または非covU HARD残がc3nのみでForbiddenDiagが証明済みの場合だけExtraRefineを省略する。
    HARD=0・改善可能なHARD残は従来どおり常時実行。hosttest 823件緑。**tools/loopでのA/Bベンチ・採否判定は
    未実施**（3ケース別の時間/スコア比較は今後）。
    **→ 2026-09-21で判定完了・既定OFF維持**。新規`HandleOptimizeBench.kt`（`runPostOptimization`より下しか
    呼ばない既存ベンチでは`handleOptimize`内部のExtraRefine自体を経由できないため新設）で実データ4件×3回×
    secondsBudget=60sを測定（`tools/loop/results/handleoptimize.csv`）。**結果は方向がケースで割れ、
    信号なし**: golden weighted -1.60%（改善）・sample +0.63%（悪化）・blocked_covu -0.32%（改善）・
    sept2026 +0.77%（悪化）、いずれも10%基準に遠く及ばない。速度もgolden -37%・sept2026 +31%と大きく
    ブレるが、`handleOptimize`にはseed引数が無く（内部固定seed=0、実行ごとの差はワーカー間の実時間競合
    ノイズのみ）golden/blocked_covu(hard=0/4で不変)は条件`post.report.hard>0`のゲート上そもそもON/OFFで
    分岐しないはず＝観測されたブレは同一設定内の実行時ノイズであり機能の効果ではない。**既定OFF維持**
    （信号を検出できず、3回では統計的検出力も不足）。フラグ・コードは残す。
    **→ 2026-09-22 机上見直しで訂正**: 実行ログの探査で、4件中3件はフラグが経路上効かない（golden・sept2026は
    停滞検知で元々ExtraRefineを省略、blocked_covuはc3n+covU混在で条件不成立かつ予算を使い切り）＝**実質A/Aテスト**。
    発動し得るのはsample（c3n壁の証明あり）だけ。代わりにこのハーネスのノイズ幅（品質±1.6%・時間±37%）の実測値として
    有用。なおc3n壁が証明されてもSOFTは改善し得るので、省略は品質とのトレードオフ（未測定）。既定OFFは維持。

36. **[完了・3.610.0で既定ON（構造床条件つき）・大規模の負け越し R1 は 2026-09-25 締結] `postChainRunningKeepBest`（3.608.0）のtools/loop A/B判定**（2026-09-21）。
    `LoopBench.kt`に`MAGI_BENCH_FEATURE=runningkeepbest`腕を追加し5seed×46ケース(230ペア)で実施
    （`tools/loop/results/iter_runningkeepbest.csv`）。**結果: 辞書式 新0/同等230/旧0、品質±0.00%、
    速度+0.7〜0.8%、必須違反退行0、安定性 例外0・再現性46/46。ゲート{退行ゼロ:合格,品質≥10%:不合格,
    速度≥10%:不合格,安定性:合格}→不合格**＝46ケース全カテゴリ・実データ4件とも新旧が完全に同点。
    backlog#26の5腕と同型の構造（合成ベンチ資材では「チェーン内の良い一手が別パスの悪化に道連れで
    棄却される」局面自体が発生しない）で、実データ（前回セッションのCovOReliefPolish 10/25 A4→B4修正が
    Sentinelに棄却された事例）で実証済みの問題を合成ケースが再現できないだけ＝機能自体の無意味さを
    意味しない。**既定OFF維持**、フラグ・コードは残し実データで再遭遇時に直接比較（backlog#26の
    3.586.0手法）で再検証する。
    **副次修正**: このベンチが3.603.0（`ShiftRole`導入）以降で初めて走らせたものだったため、
    `LoopBench.kt`の合成ケース生成`Cases.build()`が`Shift("休",...)`を`role`省略（既定`ShiftRole.None`）
    で構築しており`hf66DataHardening`の「休シフト無しは例外で止める」ガード（3.603.0）に即座に
    引っかかって全ケースが起動不能だった。`role = ShiftRole.Rest`を明示して修正（ホストJVMの
    ロケールがPOSIXで`sun.jnu.encoding`がASCIIになり日本語リテラルが化ける問題も同時に踏んだため、
    `LANG=C.utf8 LC_ALL=C.utf8`でのビルド・実行が必須と再確認）。
    **→ 2026-09-22 机上見直しで上記は無効測定と判明・正しい条件で再測定**。既定設定では各パスが自前の keep-best を
    持ちチェーンが単調＝巻き戻しが構造的に起きない（230/230 盤面ハッシュ一致）。実機で道連れ棄却が起きた
    aptFairSoftTolerance ON 条件（`runningkeepbestaft` 腕、両腕とも許容 ON）で 5seed×46 ケースを再測定
    （`tools/loop/results/iter_runningkeepbestaft.csv`）: **勝112/負72・符号検定 p=0.004・速度+28%（タイムアウト 23→11）
    だが、必須件数が増えた試行が5件（全て充足不能カテゴリ、必須+1〜2）＝統計ゲート不合格→既定OFF維持**。
    keep-best が「一時的に悪化してから必須を減らす」逃げ道を塞ぐ型（CountChain 等と同型）。
    **N9（外部レビュー, 2026-09-24）**: 巻き戻したパスでも `PostChain.adopt` が `r.applied` を返し巡の打ち切り（roundApplied）・停滞検知（totalApplied==0）へ流れる件。
    `postChainRollbackCountsZero`（既定 false、C# 同期済み）で 0 と数える版を `n9rollback` 腕（両腕とも許容 ON）で 5seed×46 ケース測定
    （`tools/loop/results/iter_n9rollback.csv`）: 230ペア: 辞書式 新2/同等224/旧4・符号検定 p=0.69・必須退行0・必須増0・品質平均-0.03%・速度平均+3.8%（中央値-4.0%）・安定性 例外0/再現性46/46＝統計ゲート不合格。**既定 false 維持**（フラグは残す）。
    既定（許容OFF）との突き合わせ: 許容ONのみ 98/115（p=0.27）、許容ON+keep-best 97/86（p=0.46）＝**許容ON自体も
    既定より有意に良いとは言えない**（実機は許容ON）。採るなら「充足不能（HARD残が構造的）な盤面では keep-best を
    切る」等の条件付けが要る＝探索動学の設計判断として明示指示待ち。
    **→ 2026-09-23 ユーザー指示「すべて」で条件付けを実装・測定中**: `PostChain` は構造的 covU 床
    （`V6SanityPort.structuralHardFloor`）が 0 より大きい盤面では keep-best を働かせない（合成の充足不能 6 件だけが床>0、
    他 36 件と実データ 4 件は 0）。任意で同点の手も受け入れる `postChainRunningKeepBestAcceptTies`（既定 false、不合格で 2026-09-25 撤去）。
    腕 `rkbstruct`（床条件のみ）と `rkbstructties`（＋同点受け入れ）を許容 ON 同士で比較中。
    **→ 結果（5seed×46、230ペア）**: A（床条件のみ）＝**勝108/負54・p<0.0001・必須退行0・必須増0＝統計ゲート合格**、
    品質+0.21%・速度+24%（`iter_rkbstruct.csv`）。充足不能30ペアは構造上すべて同点（床>0で旧腕と同じ経路）。
    分類別は大規模の c42pair 1/9・dense 1/9・forbidden 3/7・normal 4/6 が負け越し、c2deficit 10/0・wishheavy 10/0・
    中小規模と実データ（7/2/同11）が勝ち越し。B（＋同点受け入れ）＝勝109/負53 だが必須増1（small-dense）で不合格、
    A→B は 27/29 で有意差なし（`iter_rkbstructties.csv`）。**A を既定 ON に昇格（3.610.0、ユーザー指示「すべて」の
    「条件付きで採る」を実測で満たした）**。許容 OFF（既定）ではチェーンが単調＝巻き戻しが起きず出力不変、効くのは
    許容 ON（実機の設定）のときだけ。C# へ同日移植。
    **大規模での負け越しの手がかり（`iter_rkbstruct.csv` から、2026-09-23）**: keep-best あり腕は変更セル数が seed に
    よらずほぼ同じ値に揃う（large-c42pair 148〜201→96〜122、dense 242〜292→236/254、normal 78〜102→37/51）＝巻き戻しで
    序盤の同じ盤面へ戻り、seed ごとに後段パスが見つける改善を捨てている。負けの幅は SOFT 加重で数十〜百数十と小さい。
    「一時的に悪化してから後段で回収する」経路を塞ぐ型（CountChain 等と同型）。改善案（例: 巻き戻しを締切直前だけにする）は
    探索動学の変更＝測定前提で未着手。
    **→ 測定（2026-09-25、`n36finalonly` 腕、両腕とも許容 ON、5seed×46＝230 ペア、`iter_n36finalonly.csv`）**:
    `postChainKeepBestFinalOnly`（既定 false、C# 同期。不合格で同日撤去）＝パス間では巻き戻さず HF70 の前に 1 回だけ最良盤面へ戻す版。
    **勝11/負36・符号検定 p=0.0003・必須退行0・必須増0＝統計ゲート不合格（有意に不利）→既定 OFF 維持**。
    負けは large-dense 3/7・large-normal 0/4・medium c2deficit 1/8・medium wishheavy 0/6、large c42pair/forbidden は 10/10 同点。
    パス間の巻き戻しは後段パスの起点を良くする効果の方が大きく、「序盤へ戻る」ことが大規模の負けの主因ではなかった。
    **訂正**: 上の「許容ONのみ 98/115（p=0.27）、許容ON+keep-best 97/86（p=0.46）＝許容ON自体も既定より有意に良いとは
    言えない」は**無効**。別々に走らせたベンチの CSV どうしを突き合わせており、初期解が時間予算の最適化器で作られるため
    同じ設定の旧腕でも 100〜180/230 組しか一致しない＝比較が交絡していた。許容 ON と既定の比較は未測定（同一ベンチ内の
    腕として測る必要がある）。**→ 再訂正（2026-09-23）**: 同一ベンチ内の正式A/Bは 3.537.0 に既にあった（`iter_aptfairtol.csv`、138ペアで OFF 68勝/ON 38勝・p=0.0046・必須増3＝ON 不利、history 3.537.0）。「未測定」は見落とし。ただし 3.541.0 以降（fair v2・重み改定・走行 keep-best 既定 ON）の前の測定なので、現行コードで `aptfairtol` 腕を再測定する。
    **→ 再測定結果（3.610.0 以降のコード、5seed×46＝230ペア、`iter_aptfairtol_3610.csv`）**: 許容 OFF→ON で **ON 勝50/OFF 勝84・
    p=0.0042・必須増7（全て充足不能）・速度 −23%＝統計ゲート不合格、ON が有意に不利**（3.537.0 と同じ結論）。実データ 4 件は
    ON 2/OFF 1/同点17 でほぼ中立、合成では c42pair（大 1/8・中 0/8）と希望集中（中 1/9）で OFF が大きく勝ち越し。
    走行 keep-best を既定 ON にしても許容 ON の不利は解消しない。既定 OFF は維持。実機は許容 ON で運用中＝OFF に戻すかは利用者の判断
    （ベンチ上は OFF を支持、実データでは差がほぼ無い）。同一ベンチ内の新旧ペア（A・B・iter8・上の 112/72）は初期解が共通なので有効。
    **→ 無害化（2026-09-24、ユーザー指示）後の再測定（`iter_aptfairtol_guarded.csv`、230 ペア）**: ON 勝63/OFF 勝52・p=0.35・
    必須増5（減6、すべて充足不能）・速度 −7.4%＝段1（必須増 0）不合格・段2（有意勝ち）なし→**既定 OFF 維持**。有意な不利は解消し、
    実データ 20/20 は OFF と同値。詳細は history「許容 ON の無害化」。
    **→ R1（大規模の負け越し）締結（2026-09-25）**: 原因は走行 keep-best ではなく**無害化前の許容**。机上調査（大規模 8 件×seed 5、
    同じ保存初期盤面で腕を比較）で、ON 腕の巻き戻し 181 件（無害化前 157・現行 24）はすべて許容を使った FairPolish の出力だった。
    無害化前の `toleratedBetter`（ガードなし）に戻すと負けが再現（c42pair 0/10・dense 1/9・forbidden 3/7）＝fair を減らす代わりに
    weekly・high・c41 等を増やした出力を keep-best が捨て、捨てなかった OFF 腕はその盤面から後段（CyclicSwapPolish・
    WeeklyRebalancePolish・個人回数共同LNS）で回収して勝っていた（上の「変更セル数が揃う」もこの巻き戻し）。現行コードでは
    normal/forbidden/c42pair は両腕で盤面一致、dense は ON 7/OFF 3。
    現行 main（d2421af、`rkbstruct` 腕は prune 後も不変）で 5seed×46＝230 ペアを再測定（`iter_rkbstruct_head.csv`）:
    **勝45/負11/同点174・p<0.0001・必須退行0・必須増0・品質+0.14%・速度+3.4%・再現性46/46**。大規模は normal 6/1
    （負けの 1 件は加重同点で変更セル数の差だけ）・forbidden と c42pair は 10/10 盤面一致・dense 7/3・c2deficit/wishheavy/infeasible は
    全同点・実データ 1/0/同19。事前に決めた基準（必須悪化 0／勝≥負かつ OFF の有意な勝ちなし／大規模 normal・forbidden・c42pair の
    負け各 1/10 以下・dense 3/10 以下／どの大規模分類も負け 5/10 未満）をすべて満たす→**R1 締結、走行 keep-best は既定 ON のまま**。
    **別件（利用者の決定待ち）**: 同じ保存盤面で許容 ON（実機の設定＝無害化＋keep-best）と既定 OFF を比べると
    **ON 勝8/OFF 勝26/同点6（p=0.003）**＝大規模では許容 OFF が有利（`iter_aptfairtol_guarded.csv` でも大規模の充足可能 4 分類は
    ON 15/OFF 21 と同じ向き、全体は p=0.35）。実機の設定を OFF へ切り替える根拠になる。**利用者の方針（2026-09-25）: 実機も OFF に揃える**（設定画面の操作＝コード変更なし。全体では差が無く実データ 20/20 は同値、OFF の理由は ON が段1〈必須増 0〉不合格・大規模で負け越し・アプリ既定と一致）。
37. **[S5 任意の残作業・2026-09-24 登録]**（本体は Android 0b1d7b3/96a147f/044268d・C# a56506c..ddbb16d で実装済み、`docs/s5_wish_trial.md`）:
    ~~(a) T8 共有 fixture の言語跨ぎ期待値ファイル~~ **済（2026-09-25）**: `wish_trial_expected.txt`（sample_state_v6＋blocked_covu、各 wishLocked 先頭 3 件＋対照）を Kotlin/C# の `WishTrialCrossLanguageTest` が読む。両言語一致／
    ~~(b) 実行マーカーに S5 文脈を載せ中断案内で名指し~~ **済（2026-09-25）**: `work/RunMarker.kt`（`s5`＝staff/day/symbol/name、旧マーカーは従来文）＋`RunMarkerTest`。C# は実行マーカー撤去済み（2026-09-01）＝対象外／
    (c) 「詳しい試算」＝VCR＋後処理チェーンの重い試算を出すか。**利用者の方針（2026-09-25）: 「0」の行（`rk >= h0 && att <= 0`＝
    「この試算では、減る見込みは見つかりませんでした…」）にだけ任意で出す。未実装**。数字の訂正（`s5probe_v2/v3.csv` を再計算）:
    「当たりはほぼ同じ」は VCR 単独（v3 正45/低8/高1）と後処理単独（46/7/1）の比較で、詳しい試算＝VCR＋後処理は 50/3/1＝v3 の 0 の行 14 件の
    見落とし 8 件のうち 6 件を拾う。見落とし 8 件はすべて合成データ（c03・c09・c17・c18）、実データの 0 の行に見落としは無い（v2 は sample の 1 件のみ）。
    詳しい試算の 0 も証明ではない（c18 の 2 件を拾えない）。速度は VCR 中央値 46.5ms・後処理 中央値 1578ms/最大 3030ms（約 34 倍、ホスト）。
    実装前に決めること: ①同じ結果の原則（I9）＝時計依存の 3 秒を許すか、決定的モード（既定上限で C1 共同 LNS だけで 6〜7 秒、3.507.3）か／
    ②保持側の基準（希望を残した盤面の後処理）を盤面ごとに 1 回＝初回は約 2 倍の時間、基準が H0 を下回ると Rk<H0 の文とダイアログ先頭の文が変わる／
    ③停止は途中の最良を返すので明示的に捨てる（`runPostOptimization` は `shouldStop` を受ける＝§ の「締切のみ」は古い）／④確定時にどちらの
    P_cancel を完了文とログ（`MagiViewModel.kt` の S5 結果・§9 のカード行）に使うか／⑤結果の保存を簡易と分ける（今は "i,j,k" だけがキー）／
    ⑥Rk<H0・att≤0 の行も対象にするか。仕様 `s5_wish_trial.md` の「実機で 0 の行が多いと分かってから足す」に従い、実機の 0 の行の頻度を見てから着手を推奨。
38. ~~**[外部机上テスト D2・2026-09-25 登録]** スキルグループ未指定の職員が先頭のスキルグループ（index 0）に入る。~~ **→ 2026-09-25 Android で完了**
    （既定 −1＋最初のスキル群を作るとき全員 −1＝`Ws1Ops.addSkillGroup`、画面の配線は作業ブランチ。C# は同日同期。history「backlog #38」）。
    `Staff.skillIdx` の既定が 0（`model/MagiState.kt:27`）で、職員追加 `Ws1Ops.addStaff`（`v6/Ws1Ops.kt:293` の `Staff(name, gi)`）・
    CSV 新規職員（`v6/ScheduleCsvBridge.kt:185`・`:308`）・`skillIdx` 欠落 JSON（`model/StateParser.kt:39` の `optInt("skillIdx", 0)`）が
    すべて 0 になる。正規の「未所属」は -1（`Ws1Ops.kt:544` のグループ削除・`ScheduleCsvBridge.kt:704` の取込）で、c41s/c42s は
    `Problem.kt:129`（`ssk`）経由で 0 の職員を先頭スキルグループの頭数に数える。既定を -1 へ変えると既存データの c41s/c42s の採点が
    変わる（出力が変わる）ため、利用者の決定まで登録のみ。C# も同じ既定（`Staff` の既定値）。
    **利用者の方針（2026-09-25）: 未所属を表すので既定を −1 にする。グループ 0 件から最初の 1 件を作るときは全員を −1 にする（3.327.0 の方針から外れることを了承）**。精読（2026-09-25、両言語で既定だけ −1 にしたスクラッチで
    Kotlin 869/869・C# エンジン 932/932、C# ViewModels は `MagiViewModelWs1Test.cs:514` の 0 期待が 1 件落ちる＝更新が要る）:
    既定の出どころは 6 か所（`MagiState.kt:27`・`StateParser.kt:39`・C# `MagiState.cs:36`・`StateJsonSerializer.cs:62`・両 repo の
    `tools/native/state_to_flat.py`＝native-parity CI）。配列参照は全箇所 `==` か範囲確認済み（-1 は今も削除・取込で出る）、画面は両アプリとも「(なし)」を
    選べる。書き出しは両言語とも常に `skillIdx` を書く＝保存済みの値は明示の 0 のまま残り、既定変更が効くのは新規職員・白紙開始・名簿取込・
    キーの無い外部 JSON だけ。**罠**: 保存済みの 0 は「先頭を選んだ」か「既定で入った」か区別できない。特にスキルグループ 0 件のファイル
    （白紙開始・名簿取込・アプリで追加した職員は全員 0）で最初のスキルグループを作ると全員がそこへ入る（`addSkillGroup` は追加するだけ、
    検査 2i は範囲外だけを見る＝`V6SanityPort.kt:834-838` のコメントは実態とずれ）。対策案＝グループ 0 件から最初の 1 件を作るときだけ全員を −1 にする
    （その時点では意味の無い値なので採点は変わらない。3.327.0 の「自動で書き換えない」方針からは外れる＝要決定）。副作用: 同じ skillIdx で職員を
    まとめる探索（`WishIslandPolish`・`AdaptiveBlockSwapPolish`・`C3RotationPolish`）は 0 と −1 の混在で経路が変わる＝採点は不変でも最終盤面は変わりうる。
    C# 単独の不具合（Windows の選択欄が前に見た職員の値を追加・編集で書く、`EditView.xaml.cs` のコメント「既定0=未所属」の誤り）は別に直す。
39. **[外部机上テスト SI-3・2026-09-25 登録、未修正]** 初期解の C1 充足がシフト index 順に依存する。
    `SmartInitialScheduler.kt:58-80` は C1 規則をシフトごとにまとめ `rulesByShift.keys.sorted()`（`:68`）の順に空きセルを埋める＝
    同じ職員に複数シフトの C1 があると先のシフトが空きセルを取り、後のシフトの C1 が満たしにくくなる（シフトの並び替えで初期解が変わる）。
    決定的で、後段の最適化器が c1 を目的関数で直すため最終盤面への影響は未測定。順序の変更は初期解（出力）を変えるので登録のみ。
40. **[希望固定セルの漏れ・2026-09-25 登録、修正と測定中]** 利用者の方針（2026-09-25）: 希望どうしが衝突しても最適化器が希望を崩すことは認めない
    （取り消しは S5 で利用者が選ぶ）。宣言（`business-logic.md` の「希望固定セルは最適化器のどのパスも動かさない」・`PinInvariantTest`）に反し、
    ポートフォリオ経路（予算 211 秒以上）で `EliteRelinking.elitePathRelink`（仲間の盤面のセルを希望の確認なしで写す）と
    `EliteIntegrationPolish` の端点採用が希望を破った盤面を残す（c3n 9000→pref 8000 は必須件数が同じで重み付きが下がる＝keep-best が採る）。
    実データの古泉 10/25（希望 休→A4）は利用者のログ「W4 epoch8 入口盤面(ELITE_RELINK/x2)をエリート採用」の結果。`personSwapKick` も確認なしで入れ替え、
    入口の修復は「良ければ採る」＝破れた入力をそのまま持ち越す。塞ぐと実データは必須 3 のまま c3n1/pref1/c3w1→c3n2/c3w1、重み付き +約 1000。
    フラグ付きで実装し tools/loop のペア比較＋実データのポートフォリオ実行で測ってから採否（C# 同日）。
