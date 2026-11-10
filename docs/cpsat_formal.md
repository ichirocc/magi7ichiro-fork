# CP-SAT による根拠ある最適化と形式的な検証

2026-11-10 ユーザー指示「実装コストを無視して、大規模改良拡張を目指し、根拠ある勤務表最適化と効率の良い勤務表作成を行います」
「端末内（OR-Tools CP-SAT）を高速化対応する」「formal verification で検証する」（参考: arXiv:2608.00965v2＝AI が仕様と証明を書き
SMT 系の検証器で機械的に確かめる手法）。

## 決定（grilling、2026-11-10）
- 根拠は 2 つ: ①下限（これ以上良くならない値）と今の勤務表とのギャップ ②残った違反ごとの説明（必須＝原因の条件の名指し、
  品質＝直す代償を証明付きで）。
- 端末内で OR-Tools CP-SAT を今の探索（C++ の SA/ALNS）と併走。今の最良をヒントに渡し、CP-SAT の解は今の採否・番兵を通して採る。
- 差が 1% 以下になったら予算の途中でも止める（HF77 明示数値指示。必須は差 0 のときだけ）。必須違反の原因を 1 タップで直す画面。
- 先に測らずに組み込む（ユーザー決定）。Windows 版は Android が固まってから。

## モデル（`app/src/main/cpp/magi_cpsat.h`）
- 変数 x[i][j][k]: 置けるシフト（`allowed`、拡張希望の禁止を除く）＋入力の値（エンジンは置かない規則に当たる値も動かさずに残せる＝
  到達できる範囲と一致）。希望・手動固定のセルは固定。
- 2 段: ①必須の件数を最小 ②その件数以下で重み付き（必須の重み込み＝`weightedScore` から fair を除いたもの）を最小。今の採否
  （必須の件数 → 重み → 合計）と同じ順。
- fair（達成率の中央値）は線形に書けないので 0 以上の緩和。下限は常に実際の値以下＝下限として正しい。ギャップは fair を含む実際の
  値（チェッカー）と比べる。
- 必須の重み `HardW`（groupViol 11000・covU 10000・c3n/c3w 9000・pref/extWish 8000）は `MirrorKeys.weights` と同じ値。重みを変えたら
  ここも揃える。

## 検証（何を・どの手段で）
| 対象 | 手段 | 結果（2026-11-10） |
|---|---|---|
| 符号化（補助変数の最小値＝元の式）: 被覆の OR/AND の畳み込みと covU/covO、low/high/apt、c1/c2、c41、c42 同一集合の接線（m=1..30）、weekly（|7w−c| と 7 で割った切り捨て・単調性）、c3 窓マッチ（非 forbidden／forbidden、D=2..6）、run の不足の補助変数 | Z3（SMT）で ①健全（条件を満たす補助変数は元の式の値以上）②達成（元の式の値が条件を満たす）を証明。範囲の制約以外は無制限の整数 | 45/45 PROVED（`tools/formal/cpsat_encoding_proofs.py`） |
| run の不足の分解（`C3Run.rowDeficit`＝連の始まりごとの和） | T<=16・L=2..5 の全 0/1 列を全数 | 一致 |
| モデルの組み立て全体（実際の C++ が作るモデル）＝チェッカー | 実データ 6 件×2 方式で盤面を固定して解いた値を `fullEvalParts` と照合（`tools/native/cpsat_bench.cpp`）＋小さい問題の全盤面（`tools/native/formal_bounded.cpp`、MAGI_WITH_CPSAT） | 12/12 一致（全盤面の検査は下記の版で追記） |
| 差分評価（`SaChunk::deltaApply`、規則ごとの表）＝全体評価 | 有界の全数検証: 300 構成（2×4×3・3×3×3・2×5×3、全制約の種類＋手動固定・拡張希望の禁止、未割当 −1 込み）の全盤面×全 1 マスの書き換えと戻し | 1442 万盤面・3.1 億手で不一致 0 |

CBMC（C の有界モデル検査）はエンジンの C++（標準ライブラリ）を解析できなかった（5.95.1）ため、コードの検証は実際のコードを全数で
走らせる有界検証にした（small-scope hypothesis: 不具合は小さい反例を持つことが多い）。

## 実データでの効き（ホスト 4 ワーカー、優先順位）
- 利用者の 10 月データ: 必須 4 件が最少と 30 秒で証明。重みの下限 46376・ギャップ 5.7%（CP-SAT 自身も入力より良い勤務表）。
- oct2026_grid: 必須 5 件が最少と証明・ギャップ 4.4%（60 秒）。sample_v6・blocked_covu は 13〜34%、golden・sept2026 は 70〜95%＝
  下限が弱い（連続・曜日の項の線形緩和が弱い）→ 下限を上げる工夫は S1 の続き。

## 段（タスク）
S1 モデル（ホスト）✔ → S2 Android 向け OR-Tools（`tools/native/build_ortools_android.sh`、CI）と JNI → S3 併走・ギャップ 1% で停止・番兵
→ S4 説明（必須＝最小の矛盾集合、品質＝直す代償）→ S5 画面（ギャップ・説明・1 タップの修正）→ Windows 版。

## 再実行
```
python3 tools/formal/cpsat_encoding_proofs.py
g++ -O2 -std=c++20 -DMAGI_HOST_TEST -I app/src/main/cpp -I tools/native tools/native/formal_bounded.cpp -o fb && ./fb 300
# CP-SAT 込み（OR-Tools C++ v9.12 の配布物を <O> に展開）:
g++ -O2 -DNDEBUG -std=c++20 -DMAGI_HOST_TEST -DMAGI_WITH_CPSAT -DOR_PROTO_DLL= -DUSE_GLOP -DUSE_BOP -I app/src/main/cpp -I tools/native -I <O>/include \
  tools/native/cpsat_bench.cpp -L<O>/lib -lortools -lprotobuf $(ls <O>/lib/libabsl_*.so | sed 's#.*/lib\(.*\)\.so#-l\1#') -Wl,-rpath,<O>/lib -o cpsat_bench
```
