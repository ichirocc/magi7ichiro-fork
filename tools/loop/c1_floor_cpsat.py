#!/usr/bin/env python3
"""頭打ち盤面の c1 の下限（CP-SAT）。HardFloorExport.kt の *.model.json を読み、
HARD（covU＋c3n＋c3w、hard_floor_cpsat.py と同じ式）を上限 H 以下に保ったまま c1（違反窓の数）を最小にする。
置ける範囲は mayPlace（最適化器と同じ、希望・手動固定は固定）。開始解は書き出した盤面。
  /usr/bin/python3 tools/loop/c1_floor_cpsat.py <model.json> <H> [秒=300] [workers=4] [low上限|-] [high上限|-] [tag=c1]
low/high は個人の下限・上限の超過の合計に上限を足す（c1 より重い族を悪化させない比較）。解は <model>.<tag>.sol.json。checker 照合は HardFloorExport の verify（*.mayPlace.sol.json として置いて読む）。
"""
import json, sys
from ortools.sat.python import cp_model


def build(m, hmax, lowmax=None, highmax=None):
    S, T, K = m["S"], m["T"], m["K"]
    md = cp_model.CpModel()
    x = {}
    for i in range(S):
        for j in range(T):
            ks = m["allowedAt"][i][j] or [k for k in range(K) if m["canDo"][i][k]]
            for k in ks:
                x[i, j, k] = md.NewBoolVar("")
            md.AddExactlyOne(x[i, j, k] for k in ks)
    hard = []
    for k in range(K):
        for j in range(T):
            tab = m["covU"][k][j]
            if max(tab) == 0:
                continue
            cnt = md.NewIntVar(0, S, "")
            md.Add(cnt == sum(x[i, j, k] for i in range(S) if (i, j, k) in x))
            u = md.NewIntVar(0, max(tab), "")
            md.AddElement(cnt, tab, u)
            hard.append(u)
    for seq in m["c3n"]:
        d = len(seq)
        for i in range(S):
            for s in range(T - d + 1):
                lits = [x.get((i, s + l, seq[l])) for l in range(d)]
                if any(v is None for v in lits):
                    continue
                f = md.NewBoolVar("")
                md.Add(f >= sum(lits) - (d - 1))
                hard.append(f)
    for i in range(S):
        for j in range(T):
            for k in m["c3wBan"][i][j]:
                if (i, j, k) in x:
                    hard.append(x[i, j, k])
    md.Add(sum(hard) <= hmax)
    low, high = [], []
    for i in range(S):
        for k in range(K):
            n = sum(x[i, j, k] for j in range(T) if (i, j, k) in x)
            lo, hi = m["lo"][i][k], m["hi"][i][k]
            if lo > 0:
                d = md.NewIntVar(0, lo, ""); md.Add(d >= lo - n); low.append(d)
            if hi >= 0:
                d = md.NewIntVar(0, T, ""); md.Add(d >= n - hi); high.append(d)
    if lowmax is not None:
        md.Add(sum(low) <= lowmax)
    if highmax is not None:
        md.Add(sum(high) <= highmax)
    c1 = []
    for day1, k, day2 in m["c1"]:
        for i in range(S):
            if not m["canDo"][i][k]:
                continue
            for j in range(T - day1 + 1):
                z = sum(x[i, j + l, k] for l in range(day1) if (i, j + l, k) in x)
                v = md.NewBoolVar("")
                md.Add(z >= day2).OnlyEnforceIf(v.Not())
                c1.append(v)
    md.Minimize(sum(c1))
    for i in range(S):
        for j in range(T):
            b = m["board"][i][j]
            if (i, j, b) in x:
                md.AddHint(x[i, j, b], 1)
    return md, x, S, T, K


def main():
    path, hmax = sys.argv[1], int(sys.argv[2])
    secs = float(sys.argv[3]) if len(sys.argv) > 3 else 300
    w = int(sys.argv[4]) if len(sys.argv) > 4 else 4
    lowmax = int(sys.argv[5]) if len(sys.argv) > 5 and sys.argv[5] != "-" else None
    highmax = int(sys.argv[6]) if len(sys.argv) > 6 and sys.argv[6] != "-" else None
    tag = sys.argv[7] if len(sys.argv) > 7 else "c1"
    m = json.load(open(path))
    md, x, S, T, K = build(m, hmax, lowmax, highmax)
    sv = cp_model.CpSolver()
    sv.parameters.max_time_in_seconds = secs
    sv.parameters.num_workers = w
    st = sv.Solve(md)
    print(f"{tag} status={sv.StatusName(st)} minC1={int(sv.ObjectiveValue())} bound={int(sv.BestObjectiveBound())} H<={hmax} low<={lowmax} high<={highmax}", flush=True)
    if st in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        sol = [[next(k for k in range(K) if (i, j, k) in x and sv.Value(x[i, j, k])) for j in range(T)] for i in range(S)]
        json.dump(sol, open(path[:-len(".model.json")] + f".{tag}.sol.json", "w"))


if __name__ == "__main__":
    main()
