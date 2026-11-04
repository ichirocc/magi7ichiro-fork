#!/usr/bin/env python3
"""シーソーの S2c＝前線の断面（docs/seesaw.md §6.2、測定用・アプリには入れない）。盤面 x を通る族 A と B の断面を CP-SAT で求める:
  a を f_A(x) から 1 件ずつ下げ、「必須 ≤ h(x)、f_A ≤ a、A・B 以外の写した族（fair 以外）≤ f(x)」のもとで f_B を最小化する。
  python3 tools/loop/seesaw_front.py <model.json> <state.json> <classpath> <board.json> <A> <B> [--steps 6] [--secs 20] [--workers 4]
・解の f_A が a より小さければ、a から f_A までは同じ解なので飛ばす（AUGMECON2 の bypass）。
・INFEASIBLE（条件を満たす盤面が無いと証明された）ならそこで打ち切る。時間切れ（UNKNOWN）では打ち切らない。
・各点は checker で照合して族の件数を出す（fair は写さないので checker の値で見る）。最後に膝点（隣どうしの交換率が最も大きく変わる点）。"""
import argparse, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from membrane_cpsat import Board, Oracle, MODELLED  # noqa: E402

ap = argparse.ArgumentParser()
for k in ("model", "state", "cp", "board", "A", "B"):
    ap.add_argument(k)
ap.add_argument("--steps", type=int, default=6); ap.add_argument("--secs", type=float, default=20); ap.add_argument("--workers", type=int, default=4)
a = ap.parse_args()
m = json.load(open(a.model)); S, T = m["S"], m["T"]
o = Oracle(a.cp, a.state)
x = json.load(open(a.board)); rx = o.check(x); fx = rx["bd"]
A, B = a.A, a.B
assert A in MODELLED and B in MODELLED and A != "fair" and B != "fair", "A・B は写した族（fair 以外）"
nf = [f for f in MODELLED if f != "fair"]
hx = fx["covU"] + fx["c3n"] + fx["c3w"]
free = {(i, j) for i in range(S) for j in range(T)}
pts = [(fx[A], fx[B], "起点", fx)]
aa = fx[A] - 1; n = 0
while aa >= 0 and n < a.steps:
    caps = {f: fx[f] for f in nf if f not in (A, B)}; caps[A] = aa
    b = Board(m, x, free, hx, fam_caps=caps, fair="skip")
    b.md.ClearObjective(); b.md.Minimize(sum(e for e, *_ in b.terms[B]))
    st, sol, obj, fam, _ = b.solve(a.secs, workers=a.workers)
    n += 1
    if st == "INFEASIBLE":
        print(f"  {A} ≤ {aa}: 盤面なし（証明つき）→ 打ち切り", flush=True); break
    if sol is None:
        print(f"  {A} ≤ {aa}: {st}（時間切れ、解なし）", flush=True); aa -= 1; continue
    ry = o.check(sol)
    pts.append((ry["bd"][A], ry["bd"][B], st, ry["bd"]))
    print(f"  {A} ≤ {aa}: {st} {A}={ry['bd'][A]} {B}={ry['bd'][B]} 必須={ry['hard']} fair={ry['bd']['fair']}（起点 {fx['fair']}）", flush=True)
    aa = min(aa, ry["bd"][A]) - 1   # bypass
pts.sort(key=lambda p: -p[0])
rates = [((pts[k + 1][1] - pts[k][1]) / max(1, pts[k][0] - pts[k + 1][0])) for k in range(len(pts) - 1)]
knee = None
allopt = all(p[2] in ("起点", "OPTIMAL") for p in pts)
if len(pts) >= 3 and allopt:
    k = max(range(1, len(pts) - 1), key=lambda k: abs(rates[k] - rates[k - 1]))
    knee = pts[k]
print(f"{os.path.basename(a.board)} 断面 ({A}, {B}): " + " → ".join(f"({p[0]},{p[1]})" for p in pts)
      + f"／交換率 {[round(r, 2) for r in rates]}"
      + (f"／膝点 ({knee[0]},{knee[1]})" if knee else ("／可能解（時間切れ）の点を含む＝前線ではないので膝点は出さない" if not allopt else "／点が 3 つ未満＝膝点なし（両端だけ）")), flush=True)
