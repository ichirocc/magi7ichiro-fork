#!/usr/bin/env python3
"""RestartArmBench: 「同じ結果」（A が R のまま返った対）に絞り、「下書きから作り直して R より良いときだけ採る」の効果を集計する。

使い方: python3 tools/loop/restart_arm_same.py <prefix> [<prefix> ...]   （<prefix>.csv と <prefix>.base.csv を読む。複数は合算）
差は R − 採った盤面の weighted（正＝下書きが効いた。良いときだけ採るので負にはならない）。勝＝下書きを採った、分＝R のまま。
符号検定は A（＝R）に対する勝敗で数える（分は除く）。
"""
import csv, math, random, statistics as st, sys


def key(r):
    return (int(r["hard"]), float(r["weightedScore"]), int(r["total"]))


diffs, wins, ties, lost_raw, rows = [], 0, 0, 0, []
for p in sys.argv[1:]:
    base = {(r["fixture"], r["seed"]): r for r in csv.DictReader(open(p + ".base.csv", encoding="utf-8"))}
    by = {}
    for r in csv.DictReader(open(p + ".csv", encoding="utf-8")):
        by.setdefault((r["fixture"], r["seed"]), {})[r["arm"]] = r
    for k, a in by.items():
        R, A, B = base[k], a["off"], a["on"]
        if key(A) != key(R):
            continue
        took = key(B) < key(R)
        d = float(R["weightedScore"]) - float(B["weightedScore"]) if took else 0.0
        diffs.append(d)
        wins += took
        ties += not took
        lost_raw += key(B) > key(R)
        rows.append((p.rsplit("/", 1)[-1], k[0], k[1], d, "下書き" if took else "R"))

for r in rows:
    print(*r)
n = len(diffs)
p_sign = min(1.0, 2 * sum(math.comb(wins, i) for i in range(0, 1)) / 2 ** wins) if wins else 1.0
random.seed(1)
boots = sorted(st.mean(random.choices(diffs, k=n)) for _ in range(10000)) if n else [0]
print(f"同じ結果の対 {n}: 下書きを採った {wins}・R のまま {ties}（うち下書きが R に負けた {lost_raw}）・悪化 0")
print(f"weighted 差（R−採った盤面）平均 {st.mean(diffs) if n else 0:+.1f}  中央 {st.median(diffs) if n else 0:+.1f}  "
      f"95% 区間 [{boots[249]:+.1f}, {boots[9749]:+.1f}]  符号検定（勝 {wins}・負 0）p={p_sign:.4f}")
