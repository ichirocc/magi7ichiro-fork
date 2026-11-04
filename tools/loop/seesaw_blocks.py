#!/usr/bin/env python3
"""weekly の取り逃しの測定（docs/seesaw.md §13.5、測定用）。全職員×日の区間（--days 日）だけを自由にし、写した族（fair 以外）を今の値以下に
縛って件数の和を CP-SAT で最小化する。出た盤面を checker で照合し、21 族すべてで以下（どれかで未満）なら採って次の区間へ進む（積み上げ）。
  python3 tools/loop/seesaw_blocks.py <model.json> <state.json> <classpath> <board.json> [--days 7] [--step 7] [--secs 20]
盤面全体の S2b（seesaw_s2b.py）が見つけた改善のうち、日の区間の部分問題でどれだけ届くかを見る。"""
import argparse, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from membrane_cpsat import Oracle, MODELLED  # noqa: E402
from seesaw_s2b import ALL, dominates, pareto_solve  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("model"); ap.add_argument("state"); ap.add_argument("cp"); ap.add_argument("board")
ap.add_argument("--days", type=int, default=7); ap.add_argument("--step", type=int, default=7); ap.add_argument("--secs", type=float, default=20)
a = ap.parse_args()
m = json.load(open(a.model)); S, T = m["S"], m["T"]
o = Oracle(a.cp, a.state)
x = json.load(open(a.board)); r0 = o.check(x); cur, rc = x, r0
nf = [f for f in MODELLED if f != "fair"]
adopted = 0
for d0 in range(0, T, a.step):
    free = {(i, j) for i in range(S) for j in range(d0, min(T, d0 + a.days))}
    fx = rc["bd"]; hx = fx["covU"] + fx["c3n"] + fx["c3w"]
    st, sol, obj, _ = pareto_solve(m, cur, free, {f: fx[f] for f in nf}, nf, hx, a.secs, "skip")
    if sol is not None and obj < sum(fx[f] for f in nf):
        ry = o.check(sol)
        if ry["hard"] <= rc["hard"] and dominates(ry["bd"], fx):
            cur, rc = sol, ry; adopted += 1
diff = {f: rc["bd"][f] - r0["bd"][f] for f in ALL if rc["bd"][f] != r0["bd"][f]}
print(f"{os.path.basename(a.board)} 区間 {a.days} 日・{a.step} 日ずつ: 採用 {adopted} 区間、差 {diff}（重み {r0['weighted']}->{rc['weighted']}）", flush=True)
