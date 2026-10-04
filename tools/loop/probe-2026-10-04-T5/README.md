# T5（時間予算の超過）／T4（案A 既定ON後の追跡）測定（2026-10-04、測定のみ）
- 入口: `V6FinalPort.handleOptimize(secondsRaw=予算, allowImpossible=true, seed)`（時間モード、workers 既定）。
- 条件: 予算 60/120 秒 × seed 1〜3 × `C1JointLnsPolish.deltaChildEvalDefault` ON/OFF（奇数 seed は ON 先、偶数 seed は OFF 先）。fixture ごとに 12 回。
- 実行: `W=/tmp/t5w setsid nohup tools/loop/probe-2026-10-04-T5/run.sh <fixture>.json &`（fixture = golden_state.json / sample_state_v6.json / blocked_covu_state.json / sept2026_state.json）。初回は hosttest で main をビルド（約 4 分）。
- 出力: `$W/out.csv`（fixture,budget,seed,arm,wallMs,hard,weightedScore,total、1 行ずつ fsync、再実行で続きから）と `$W/passlogs.tsv`（"ms" を含むログ行）。終わったら `out_<fixture>.csv`・`passlogs_<fixture>.tsv` としてこのディレクトリへコミット。
- 測定前に負荷を確認（`uptime`）。Serena の KotlinLspServer は止めてよい。
