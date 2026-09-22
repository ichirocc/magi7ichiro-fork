# 環境固有の手順（CLAUDE.md から移設, 3.505.9）

> CLAUDE.md には「見ても分からない罠」と判断基準だけを残し、手順の本文はここに置く。実装が正・環境が変わったらここを直す。

## ホスト JVM でエンジン層を検証する
- `tools/host/hosttest.sh`（kotlin-compiler-embeddable で `v6/`・`model/`・Android 非依存の `ui/work` と全 JUnit をコンパイルして実行。約 1 分）。
  出力先は `MAGI_HOST_OUT=/tmp/magi-hostbuild-xxx` で変えられる。**ベンチや probe が掴んでいる出力先を再ビルドしない**（クラスファイルが差し替わり結果が汚れる）。
- probe（研磨 1 本・後処理全体の最終盤面ハッシュ比較）は `tools/loop/run_bench.sh` と同じ要領でホストビルドに対して Kotlin ファイルを 1 本コンパイルして走らせる。
- ループのベンチ: `tools/loop/README.md`。

## CI（GitHub Actions）
- ブランチで走るのは Design Lint / Native Parity Check / V6 Engine Check。Release Build と Android SDK は main への push で走る。
- **UI（Compose）のコンパイル検証はブランチでも取れる**: `android-sdk.yml` は `workflow_dispatch` を持つので、
  `actions_run_trigger(run_workflow, workflow_id=android-sdk.yml, ref=<作業ブランチ>)` で debug/release の
  assemble ＋ Android Lint が走る（約 8 分）。ホストでは `ui/` の Compose ファイルをコンパイルできないため、
  UI を触る変更はこれが唯一の実コンパイル確認手段＝main へ出す前に回す（3.559.0 で確立）。
  ただし「公開宣言が internal 型を露出」だけは `tools/design_lint.py` の **P13** が数秒で止める（3.562.0）。
- 監視: `api.github.com/repos/ichirocc/magi7ichiro-fork/actions/runs?head_sha=<sha>`（status / conclusion）。失敗 step は `/actions/runs/{id}/jobs`。
  CI ログ本体は results-receiver 上で取得不可＝コンパイルエラーは目視＋静的チェック（波括弧・フィールド名照合）で見つける。
- ビルド約 4〜5 分 → debug-key APK 約 10.9MB。変更ごとに `versionCode++` と `versionName`（`app/build.gradle.kts`）。
- `concurrency: cancel-in-progress` で取り消された run はコミット一覧で ❌ に見える（連続 push・同一 SHA を別ブランチへ push）。
  失敗ではないので run の conclusion（`cancelled`）で判別する（MAGI-Godot からの知見、3.552.0）。
- 成果物名にブランチ名を使うなら `/` を正規化する（`claude/...` ブランチの手動実行で拒否され成果物 0 件になった実例）:
  Checkout 直後に `echo "SAFE_REF=${GITHUB_REF_NAME//\//-}" >> "$GITHUB_ENV"`。現状の `release-build.yml` は `github.sha` なので不要。
- APK 内の `.so` が非圧縮かつ 16KiB 境界かは `tools/check_apk_native_libs.py` が assemble 後に検査する（3.552.0）。
  Release Build の成果物 upload は `continue-on-error` のまま、`if-no-files-found: error`＋STEP_SUMMARY で失敗を見えるようにした。
- **成果物の保持方針（3.570.0、ユーザー決定）**: 通常の CI 成果物（lint 結果・失敗ログ・debug/native-parity 等の
  ビルド成果物）は `cleanup-artifacts.yml` が毎日 03:00 UTC に無条件で全削除する（`workflow_dispatch` でも即時実行可）。
  **`release-build.yml` のリリース APK（成果物名 `app-release-*`）だけは対象外**にし、`retention-days: 14` を
  実際に保証する（デバッグ鍵署名の動作確認用 APK なので恒久保存はしない＝14日で十分、というのが根拠）。
  除外は `cleanup-artifacts.yml` 側の名前プレフィックス判定（`app-release-`）で行う＝release-build.yml 側は
  ふつうに `retention-days: 14` を指定するだけでよい。他ワークフローの `retention-days: 7` はこの日次削除に
  よってほぼ効かない（削除まで最短1日）が、対象は通常成果物のみなので実害はない。
  **→ 2026-09-22（backlog#21、ユーザー明示go）でタグビルド（`v*` push）は GitHub Release アセットへ移行**。
  `release` job のアップロードは `release-apk-handoff-<sha>`（retention-days: 1、cleanup-artifacts.yml の
  保護対象外）へ改名し、新設 `publish-release` job がそれをダウンロードして `gh release create`/`upload`
  でタグへ添付する（書込みトークンはこの job だけに分離、`report-failure` job と同じ方針）。**手動実行
  （`workflow_dispatch`）は Release を作らないため従来どおり** `app-release-*`・`retention-days: 14`・
  `cleanup-artifacts.yml` の保護を維持する。

## スキル・プラグイン（2026-07-18 ユーザー決定の原文）
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


## ツール選択ルール（Serena 試験導入・3.497.2 の原文）
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

