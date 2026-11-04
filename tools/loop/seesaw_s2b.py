#!/usr/bin/env python3
"""シーソーの S2b＝盤面ごとのパレート判定（docs/seesaw.md §4 の 2b、測定用・アプリには入れない）。
盤面 x ごとに「必須 ≤ h(x)、写した全族 ≤ f(x) のもとで、写した族の件数の和を最小化」を CP-SAT で解き、出た盤面を checker で照合する。
  python3 tools/loop/seesaw_s2b.py <model.json> <state.json> <classpath> <board.json>... [--secs 60] [--cells 6] [--cell-secs 20]
model.json は HardFloorExport の export（file:<state.json>）。classpath は CheckServer を含む JVM のクラスパス。
2 通りで調べる:
  whole: 盤面全体を自由にし、fair は写さない（表が爆発するため）。fair の上限を外すのは緩和なので、
         最適の証明つきで差 0 なら「fair だけが減る盤面」以外に優越する盤面は無い。差が出たら fair を checker で確かめる。
  cell : 同じグループの職員（違反の印の多い順）×全期間だけを自由にし、fair も表で写して全族を縛る。
         外のセルは固定なので、ここで見つかった優越は盤面全体での優越の証拠になる（無いことは細胞の中でしか言えない）。
"""
import argparse, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from membrane_cpsat import Board, Oracle, MODELLED, HARD  # noqa: E402

ALL = ["c1", "c2", "c3", "c3n", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covU", "covO", "pref",
       "low", "high", "groupViol", "apt", "fair", "weekly", "c3w", "extWish"]


def dominates(a, b):
    """a が b に優越する（必須は a ≤ b、21 族すべて a ≤ b、どれかで <）。a・b は checker の bd。"""
    return all(a[f] <= b[f] for f in ALL) and any(a[f] < b[f] for f in ALL)


def pareto_solve(m, x, free, caps, fams, hmax, secs, fair):
    b = Board(m, x, free, hmax, fam_caps=caps, fair=fair)
    b.md.ClearObjective()
    b.md.Minimize(sum(e for f in fams for e, *_ in b.terms[f]))
    st, sol, obj, fam, _ = b.solve(secs)
    return st, sol, obj, fam


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model"); ap.add_argument("state"); ap.add_argument("cp"); ap.add_argument("boards", nargs="+")
    ap.add_argument("--secs", type=float, default=60); ap.add_argument("--cells", type=int, default=6)
    ap.add_argument("--cell-staff", type=int, default=4); ap.add_argument("--cell-secs", type=float, default=20)
    a = ap.parse_args()
    m = json.load(open(a.model))
    S, T = m["S"], m["T"]
    oracle = Oracle(a.cp, a.state)
    for path in a.boards:
        x = json.load(open(path))
        rx = oracle.check(x); fx = rx["bd"]
        name = os.path.basename(path)
        unmod = {f: fx[f] for f in ALL if f not in MODELLED and fx[f]}
        if unmod:
            print(f"{name}: 写していない族に件数がある {unmod} → 判定しない", flush=True); continue
        # 写しの照合（fair を除く）
        _, _, _, fam0 = pareto_solve(m, x, set(), None, [f for f in MODELLED if f != "fair"], 10 ** 9, 10, "skip")
        bad = {f: (fam0[f], fx[f]) for f in MODELLED if f != "fair" and fam0[f] != fx[f]}
        if bad:
            print(f"{name}: 写しが checker と不一致 {bad} → 判定しない", flush=True); continue
        hx = fx["covU"] + fx["c3n"] + fx["c3w"]
        nf = [f for f in MODELLED if f != "fair"]
        base = sum(fx[f] for f in nf)
        # whole（fair を省いた緩和）
        free = {(i, j) for i in range(S) for j in range(T)}
        st, sol, obj, fam = pareto_solve(m, x, free, {f: fx[f] for f in nf}, nf, hx, a.secs, "skip")
        verdict = "未決"
        if sol is not None and obj < base:
            ry = oracle.check(sol)
            if ry["hard"] <= rx["hard"] and dominates(ry["bd"], fx):
                verdict = "見かけあり（優越、盤面全体）"
                diff = {f: ry["bd"][f] - fx[f] for f in ALL if ry["bd"][f] != fx[f]}
                print(f"{name}: whole {st} 和 {base}→{obj} {verdict} 差 {diff}", flush=True)
                continue
            verdict = f"fair 以外は減るが fair が {fx['fair']}→{ry['bd']['fair']}（優越でない）"
        elif st == "OPTIMAL" and obj == base:
            verdict = "fair 以外では優越する盤面なし（最適の証明つき）"
        print(f"{name}: whole {st} 和 {base}→{obj if obj is not None else '-'} {verdict}", flush=True)
        # cell（fair を表で写し、全族を縛る）。職員は、写した族の件数（職員の付く項）が多い順（MembraneCellLns と同じ考え方）
        bb = Board(m, x, set(), 10 ** 9, fair="skip")
        r0 = bb.solve(10)[4]
        load = [0] * S
        if r0:
            sv0, b0 = r0
            for f in nf:
                for e, i, *_ in b0.terms[f]:
                    if i is not None:
                        load[i] += e if isinstance(e, int) else int(sv0.Value(e))
        order = sorted(range(S), key=lambda i: (-load[i], i))
        found = None; tried = 0; proven = 0
        for seed in order:
            if tried >= a.cells:
                break
            g = m["sgrp"][seed]
            staff = [seed] + [i for i in order if i != seed and m["sgrp"][i] == g][: a.cell_staff - 1]
            if len(staff) < 2:
                continue
            tried += 1
            cfree = {(i, j) for i in staff for j in range(T)}
            st2, sol2, obj2, _ = pareto_solve(m, x, cfree, {f: fx[f] for f in MODELLED}, MODELLED, hx, a.cell_secs, "table")
            if st2 == "OPTIMAL" and obj2 == base + fx["fair"]:
                proven += 1
            if sol2 is not None and obj2 < base + fx["fair"]:
                ry = oracle.check(sol2)
                if ry["hard"] <= rx["hard"] and dominates(ry["bd"], fx):
                    found = (staff, {f: ry["bd"][f] - fx[f] for f in ALL if ry["bd"][f] != fx[f]})
                    break
        if found:
            print(f"{name}: cell 職員{found[0]} 見かけあり（優越、細胞） 差 {found[1]}", flush=True)
        else:
            print(f"{name}: cell {tried} 細胞で優越する盤面なし（うち {proven} 細胞は最適の証明つき）", flush=True)


if __name__ == "__main__":
    main()
