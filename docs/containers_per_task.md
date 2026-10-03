<!-- 2026-10-03 ユーザー提供の文書をそのまま保存。compose 定義・Dockerfile はこの repo に未収録＝記録として扱う -->
# MAGI案件別・残件別コンテナ

この構成は、同じコード基準を共有しつつ、検証結果と実行目的を案件ごとに分離する。

| サービス | 目的 | 終了条件 |
|---|---|---|
| `baseline-hosttest` | 全体回帰の基準値 | hosttest全green |
| `dream-rsi-e0a` | 新探索コア・E0A回帰 | baseline成功後にhosttest全green |
| `low-parity` | Kotlin/C++のlow差分とstate変換 | 変換テスト＋hosttest |
| `c42-gate` | C42専用枝の再測定・撤去判断 | hosttest全green、別途A/B結果を保存 |
| `rsi-budget` | RSI短時間予算の比較準備 | hosttest全green、別途予算ベンチを実行 |

## 起動

```bash
docker compose build
docker compose up --abort-on-container-exit --exit-code-from baseline-hosttest
```

基準を通した後、案件を並列実行する場合:

```bash
docker compose up --build --no-deps --abort-on-container-exit \
  dream-rsi-e0a low-parity c42-gate rsi-budget
```

結果は `artifacts/<service>/` に保存し、依存jarキャッシュは `host-cache` volume で共有する。コードはコンテナへCOPYされるため、実行中に作業ツリーを変更しない。

## 現環境での注意

SandboxへDocker Engineを導入済み。ただしSandboxカーネルの制約によりbridgeネットワークとCompose v1は利用できないため、実行時はDocker CLIの `--network host` を使う。

## 2026-10-04 実行記録

SandboxへDocker Engineを導入し、Docker CLIの `--network host` で実コンテナを起動した。bridgeネットワークはSandboxカーネルのiptables rawテーブル不足で使えなかった。Compose v1は現行Docker APIとの `http+docker` URLスキーム不整合で使えなかったため、Compose定義と同じサービスをDocker CLIで個別実行した。

基準、Dream-RSI/E0A、low-parity、C42、RSI予算の5コンテナはすべて終了コード0。各コンテナでmain 109ファイル、テスト154ファイル、158クラス、1,073テストがgreenだった。low parityの `web=9 / native=8` は全コンテナで再現した。

コンテナ間で同じ成果物を上書きしないよう、各サービスに専用artifactディレクトリを割り当てている。実データを使うベンチを追加する際は、サービスごとにseed・budget・結果CSVを固定する。
