#!/usr/bin/env python3
"""最小 HARD の厳密解（CP-SAT）。HardFloorExport.kt の *.model.json を読み、置ける範囲を
mayPlace（最適化器と同じ）と canDo（上限 0 を外す）の 2 通りで解いて *.sol.json を書く。
HARD = covU（covUCell 表）＋ c3n（窓の完全一致 1 件）＋ c3w（前日セル）。希望・手動固定は固定、
groupViol・pref は置ける範囲の外なので 0。正しさは HardFloorExport の verify（checker）で確かめる。
  python3 tools/loop/hard_floor_cpsat.py <dir> [秒=120] [workers=4]
"""
import json, sys, glob, os
from ortools.sat.python import cp_model

def solve(m, key, secs, workers):
    S, T, K = m["S"], m["T"], m["K"]
    allowed = m[key]
    md = cp_model.CpModel()
    x = {}
    for i in range(S):
        for j in range(T):
            ks = allowed[i][j]
            if not ks:  # 置けるシフトが無い（防御）＝担当可へ
                ks = [k for k in range(K) if m["canDo"][i][k]]
            for k in ks:
                x[i, j, k] = md.NewBoolVar("")
            md.AddExactlyOne(x[i, j, k] for k in ks)
    terms = []
    for k in range(K):
        for j in range(T):
            tab = m["covU"][k][j]
            if max(tab) == 0:
                continue
            vs = [x[i, j, k] for i in range(S) if (i, j, k) in x]
            cnt = md.NewIntVar(0, S, "")
            md.Add(cnt == sum(vs))
            u = md.NewIntVar(0, max(tab), "")
            md.AddElement(cnt, tab, u)
            terms.append(u)
    for seq in m["c3n"]:
        d = len(seq)
        for i in range(S):
            for s in range(T - d + 1):
                lits = [x.get((i, s + l, seq[l])) for l in range(d)]
                if any(v is None for v in lits):
                    continue
                f = md.NewBoolVar("")
                md.Add(f >= sum(lits) - (d - 1))
                terms.append(f)
    for i in range(S):
        for j in range(T):
            for k in m["c3wBan"][i][j]:
                if (i, j, k) in x:
                    terms.append(x[i, j, k])
    md.Minimize(sum(terms))
    sv = cp_model.CpSolver()
    sv.parameters.max_time_in_seconds = secs
    sv.parameters.num_workers = workers
    st = sv.Solve(md)
    sol = [[next(k for k in range(K) if (i, j, k) in x and sv.Value(x[i, j, k])) for j in range(T)] for i in range(S)]
    return sv.StatusName(st), int(sv.ObjectiveValue()), int(sv.BestObjectiveBound()), sol

def main():
    d = sys.argv[1]; secs = float(sys.argv[2]) if len(sys.argv) > 2 else 120; w = int(sys.argv[3]) if len(sys.argv) > 3 else 4
    for f in sorted(glob.glob(os.path.join(d, "*.model.json"))):
        name = os.path.basename(f)[:-len(".model.json")]
        m = json.load(open(f))
        for key, tag in (("allowed", "mayPlace"), ("allowedCanDo", "canDo")):
            status, obj, bound, sol = solve(m, key, secs, w)
            json.dump(sol, open(os.path.join(d, f"{name}.{tag}.sol.json"), "w"))
            print(f"{name} {tag} status={status} minHARD={obj} bound={bound}", flush=True)

if __name__ == "__main__":
    main()
