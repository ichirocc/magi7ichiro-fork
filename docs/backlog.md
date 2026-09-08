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
12. **外部レビュー（2026-09-07、ユーザー提示 3 表）の提案＝決定・測定待ち**（3.509.2 で仕分け。詳細は history 3.509.2）。
    (a) 評価定義: C2 を不足量、C41/C41s を距離で評価＝HF77・言語跨ぎ期待値・C++ 5 箇所を同時に変える目的関数変更。300 試行ペア比較（品質 10%・速度 10%・必須退行 0）を通してから。
    (b) 探索動学: C2 専用研磨／C41・C42 の最小費用フロー研磨／族選択を件数×重み×改善可能性へ／採用 0 時の探索半径拡張／C1 重複窓の連結成分化／C3n の前後余白込み LNS／
        循環交換の 3〜5 人拡張／適応的予算配分／C3「選択日ペア交換」（3.507.9 補遺の机上評価: golden −2.9%）＝いずれも `tools/loop` で測ってから 1 件ずつ。
    (c) D9 の方向別合成（片側だけ個人優先）＝決定 D9 は完全上書き。要件変更の明示があれば。
    (d) UX: 高度修正の適用前プレビュー（対象・変更セル数・必須/ソフト/希望の前後）、自動修正候補の比較、実現不能と探索不足の分離表示、
        時間予算/並列数/アルゴリズムのプリセット化、操作名付き Undo 履歴、利用者向け名称（「破壊」「LNS」「HF80」を出さない）＝画面設計。実機ビルドと design-review が要る。
13. **自動化方針（`docs/automation.md`）の「未」**: (a) 停滞時の探索半径・職員数・窓長の段階的拡大（測定）／(b) 採用ゲートの一本化（各パスの `isBetter`＋`exactPinRegression` を 1 関数へ。挙動不変のリファクタ）／
    (c) 必須減少時のソフト損失上限（5%）＝採用基準の変更（決定待ち）／(d) 時間予算の自動計算（規模・必須数から）／(e) 3〜5 職員循環交換の 5 人まで拡張（測定）。

