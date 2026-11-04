#!/usr/bin/env python3
"""日の区間の部分問題の測定（docs/seesaw.md §13.5・§13.6、測定用・アプリには入れない）。全職員×日の区間（--days 日）だけを自由にし、
区間を --step 日ずつずらしながら CP-SAT で解いて、良ければ採って次の区間へ進む（積み上げ）。
  python3 tools/loop/seesaw_blocks.py <model.json> <state.json> <classpath> <board.json> [--days 7] [--step 7] [--secs 20] [--workers 4] [--mode pareto|weighted]
--mode pareto  : 写した族（fair 以外）を今の値以下に縛って件数の和を最小化。checker で 21 族すべて以下（どれかで未満）なら採る（§13.5）。
--mode weighted: 必須（covU＋c3n＋c3w）だけを今の値以下に縛って、写した族（fair 以外）の重み付き和を最小化。checker の betterReport
                 （必須→重み→合計）で良ければ採る＝アプリの採否と同じ考え方。fair は写さないので、checker の照合で fair の悪化も見る。
最後の行に、採用した区間の数・族の差・重み、区間ごとの「モデルを組む秒」「解く秒」と解の状態の内訳を書く。"""
import argparse, collections, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from membrane_cpsat import Board, Oracle, MODELLED, better  # noqa: E402
from seesaw_s2b import ALL, dominates  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("model"); ap.add_argument("state"); ap.add_argument("cp"); ap.add_argument("board")
ap.add_argument("--days", type=int, default=7); ap.add_argument("--step", type=int, default=7); ap.add_argument("--secs", type=float, default=20)
ap.add_argument("--workers", type=int, default=4); ap.add_argument("--mode", choices=["pareto", "weighted"], default="pareto")
a = ap.parse_args()
m = json.load(open(a.model)); S, T = m["S"], m["T"]
o = Oracle(a.cp, a.state)
x = json.load(open(a.board)); r0 = o.check(x); cur, rc = x, r0
nf = [f for f in MODELLED if f != "fair"]
adopted = 0; build_s = []; solve_s = []; status = collections.Counter()
for d0 in range(0, T, a.step):
    if d0 + a.days > T and d0 > 0 and d0 + a.step - a.days >= T:
        break
    free = {(i, j) for i in range(S) for j in range(d0, min(T, d0 + a.days))}
    fx = rc["bd"]; hx = fx["covU"] + fx["c3n"] + fx["c3w"]
    t0 = time.time()
    caps = {f: fx[f] for f in nf} if a.mode == "pareto" else None
    b = Board(m, cur, free, hx, fam_caps=caps, fair="skip")
    if a.mode == "pareto":
        b.md.ClearObjective(); b.md.Minimize(sum(e for f in nf for e, *_ in b.terms[f]))
    t1 = time.time()
    st, sol, obj, fam, _ = b.solve(a.secs, workers=a.workers)
    t2 = time.time()
    build_s.append(t1 - t0); solve_s.append(t2 - t1); status[st] += 1
    if sol is None or sol == cur:
        continue
    ry = o.check(sol)
    ok = (ry["hard"] <= rc["hard"] and dominates(ry["bd"], fx)) if a.mode == "pareto" else better(ry, rc)
    if ok:
        cur, rc = sol, ry; adopted += 1
diff = {f: rc["bd"][f] - r0["bd"][f] for f in ALL if rc["bd"][f] != r0["bd"][f]}
print(f"{os.path.basename(a.board)} mode={a.mode} 区間 {a.days} 日・{a.step} 日ずつ・{a.secs:g} 秒×{a.workers} スレッド: "
      f"区間 {len(solve_s)}・採用 {adopted}・差 {diff}・重み {r0['weighted']}->{rc['weighted']}（{rc['weighted'] - r0['weighted']:+d}）・"
      f"組む {sum(build_s):.1f} 秒（最大 {max(build_s):.2f}）・解く {sum(solve_s):.1f} 秒（最大 {max(solve_s):.2f}）・状態 {dict(status)}", flush=True)
