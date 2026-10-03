# 盤面ごとの分担実行（ID = B01〜B78）

製品コードは変えない。日本語で簡潔に報告。

1. `planA_harness.tgz` を作業用ディレクトリ W（例: /tmp/w/pa）に展開する（q2/ と pc/）。
2. `/root/.cache/magi-host-libs` に kotlin-stdlib-2.3.21.jar・kotlinx-coroutines-core-jvm-1.8.1.jar・json-20240303.jar を用意する（`tools/host/hosttest.sh` が取得する）。
3. 盤面リストは `boards/want_<ID>.csv`（1 行＝1 盤面。1・2 列目が budget・fixture）。`pc/run3.sh` を参考に、cd 先を W、java の最後の引数をこのファイル、結果を `pc/out3_<ID>.csv`、ループをこの budget・fixture だけにして走らせる。PREREG2 の条件は変えない（4 回・ON 先と OFF 先を半分ずつ・JVM の最初に捨てる空回し 1 対・同じ締切）。負荷が 1.5 未満でほかの重いプロセスがないことを確かめてから走らせる。
4. 終わったら `pc/out3_<ID>.csv` をこのディレクトリの `out3_<ID>.csv` に写し、ブランチ `claude/merge-wub4fq` にコミットして push する（競合したら `git pull --rebase` して再 push、最大 6 回、間隔を 5〜30 秒のランダムに）。ほかのファイルは触らない。
5. 集計・判定はしない。

コミットの末尾は次の 2 行:

    Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_012CsEwNhkqxPbRvUpvkCop6
