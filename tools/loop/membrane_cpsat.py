#!/usr/bin/env python3
"""膜の探索の試作（M0、測定用・アプリには入れない）。docs/membrane_search.md §10 の三層膜・電荷・融合/分裂/溶解・チャネル・OWA 順を、
細胞＝部分問題の CP-SAT（外のセルは固定）と正式評価（CheckServer.kt＝UnifiedViolationChecker）で回す。
  /usr/bin/python3 tools/loop/membrane_cpsat.py <model.json> <state.json> <classpath> <outdir> [総秒=540]
model.json は HardFloorExport の export（file:<state.json>）。classpath は CheckServer を含む JVM のクラスパス。
目的関数は fair を除く全族の重み付き和を CP-SAT で厳密に写し（起点盤面で族ごとに checker と一致することを最初に確かめる）、
fair を含む採否は checker の betterReport（必須→重み→合計）で決める。
"""
import json, os, subprocess, sys, time
from ortools.sat.python import cp_model

MODELLED = ["covU", "c3n", "c3w", "c1", "c2", "c3", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covO", "low", "high", "apt", "weekly"]
HARD = {"covU", "c3n", "c3w", "pref", "groupViol", "extWish"}


class Oracle:
    def __init__(self, cp, state):
        self.p = subprocess.Popen(["java", "-Xmx2g", "-cp", cp, "probe.checkserver.CheckServerKt", state],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        self.calls = 0

    def check(self, board):
        self.p.stdin.write(json.dumps(board) + "\n"); self.p.stdin.flush()
        self.calls += 1
        return json.loads(self.p.stdout.readline())


def better(a, b):
    return (a["hard"], a["weighted"], a["total"]) < (b["hard"], b["weighted"], b["total"])


class Board:
    """盤面全体の CP-SAT。free にないセルは盤面の値に固定する。terms[族] = [(式, 職員 or None, 日 or None)]。"""

    def __init__(self, m, board, free, hmax, fam_caps=None):
        S, T, K = m["S"], m["T"], m["K"]
        self.m, self.S, self.T, self.K = m, S, T, K
        md = self.md = cp_model.CpModel()
        x = self.x = {}
        for i in range(S):
            for j in range(T):
                ks = m["allowedAt"][i][j] or [k for k in range(K) if m["canDo"][i][k]]
                if (i, j) not in free:
                    ks = [board[i][j]]
                for k in ks:
                    x[i, j, k] = md.NewBoolVar("")
                md.AddExactlyOne(x[i, j, k] for k in ks)
        X = lambda i, j, k: x.get((i, j, k), 0)
        terms = self.terms = {f: [] for f in MODELLED}

        def table(tabs, fam):
            for k in range(K):
                for j in range(T):
                    tab = tabs[k][j]
                    if max(tab) == 0:
                        continue
                    cnt = sum(X(i, j, k) for i in range(S))
                    u = md.NewIntVar(0, max(tab), "")
                    c = md.NewIntVar(0, S, ""); md.Add(c == cnt)
                    md.AddElement(c, tab, u)
                    terms[fam].append((u, None, j, k))
        table(m["covU"], "covU"); table(m["covO"], "covO")

        def windows(seqs, fam, forbidden):
            for seq in seqs:
                d = len(seq)
                single = len(set(seq)) == 1
                if not forbidden and single:
                    k, L = seq[0], d
                    for i in range(S):
                        for j in range(T):
                            if (i, j, k) not in x:
                                continue
                            prev = X(i, j - 1, k) if j > 0 else 0
                            for t in range(1, L):
                                # run が j で始まり j..j+t が揃わない＝不足 1（月末で切れた run も数える）
                                if j + t >= T:
                                    p = 0
                                else:
                                    p = md.NewBoolVar("")
                                    for l in range(t + 1):
                                        md.AddImplication(p, x[i, j + l, k]) if (i, j + l, k) in x else md.Add(p == 0)
                                y = md.NewBoolVar("")
                                md.Add(y >= x[i, j, k] - prev - p)
                                terms[fam].append((y, i, j, None))
                    continue
                for i in range(S):
                    for s in range(T - d + 1):
                        lits = [X(i, s + l, seq[l]) for l in range(d)]
                        if forbidden:
                            if any(isinstance(v, int) and v == 0 for v in lits):
                                continue
                            f = md.NewBoolVar(""); md.Add(f >= sum(lits) - (d - 1))
                        else:
                            r = md.NewBoolVar("")
                            for v in lits[1:]:
                                md.Add(r <= v)
                            f = md.NewBoolVar(""); md.Add(f >= lits[0] - r)
                        terms[fam].append((f, i, s, None))
        windows(m["c3n"], "c3n", True); windows(m["c3mn"], "c3mn", True)
        windows(m["c3"], "c3", False); windows(m["c3m"], "c3m", False)

        for i in range(S):
            for j in range(T):
                for k in m["c3wBan"][i][j]:
                    if (i, j, k) in x:
                        terms["c3w"].append((x[i, j, k], i, j, k))
        for day1, k, day2 in m["c1"]:
            for i in range(S):
                if not m["canDo"][i][k]:
                    continue
                for j in range(T - day1 + 1):
                    v = md.NewBoolVar("")
                    md.Add(sum(X(i, j + l, k) for l in range(day1)) >= day2).OnlyEnforceIf(v.Not())
                    terms["c1"].append((v, i, j, k))
        cnt = {(i, k): sum(X(i, j, k) for j in range(T)) for i in range(S) for k in range(K)}
        for k, c in m["c2"]:
            for i in range(S):
                if m["canDo"][i][k]:
                    v = md.NewBoolVar(""); md.Add(cnt[i, k] >= c).OnlyEnforceIf(v.Not()); terms["c2"].append((v, i, None, k))
        for i in range(S):
            for k in range(K):
                lo, hi, t = m["lo"][i][k], m["hi"][i][k], m["apt"][i][k]
                if lo > 0:
                    d = md.NewIntVar(0, lo, ""); md.Add(d >= lo - cnt[i, k]); terms["low"].append((d, i, None, k))
                if hi >= 0:
                    d = md.NewIntVar(0, T, ""); md.Add(d >= cnt[i, k] - hi); terms["high"].append((d, i, None, k))
                if t >= 0:
                    d = md.NewIntVar(0, T, ""); md.Add(d >= cnt[i, k] - t); md.Add(d >= t - cnt[i, k]); terms["apt"].append((d, i, None, k))
                # weekly: Σ_d |7 w_d − n| を 7 で切り捨て
                ws = [sum(X(i, j, k) for j in range(T) if (m["dow0"] + j) % 7 == dd) for dd in range(7)]
                if all(isinstance(w, int) for w in ws):
                    n = sum(ws); dev = sum(abs(7 * w - n) for w in ws) // 7
                    if dev:
                        terms["weekly"].append((dev, i, None, k))
                    continue
                a = []
                for w in ws:
                    e = md.NewIntVar(0, 7 * T, ""); md.Add(e >= 7 * w - cnt[i, k]); md.Add(e >= cnt[i, k] - 7 * w); a.append(e)
                q = md.NewIntVar(0, T, ""); md.Add(7 * q >= sum(a) - 6)
                terms["weekly"].append((q, i, None, k))

        def grp_day(grp, g, k, j):
            return sum(X(i, j, k) for i in range(S) if grp[i] == g)

        for fam, grp, ng in (("c41", m["sgrp"], m["G"]), ("c41s", m["ssk"], m["nSkill"])):
            for g, k, l, u in m[fam]:
                for j in range(T):
                    z = grp_day(grp, g, k, j)
                    v = md.NewBoolVar("")
                    md.Add(z >= l).OnlyEnforceIf(v.Not()); md.Add(z <= u).OnlyEnforceIf(v.Not())
                    terms[fam].append((v, None, j, k))
        for fam, grp in (("c42", m["sgrp"]), ("c42s", m["ssk"])):
            for g1, s1, g2, s2 in m[fam]:
                for j in range(T):
                    A = grp_day(grp, g1, s1, j)
                    if g1 == g2 and s1 == s2:
                        n = md.NewIntVar(0, S, ""); md.Add(n == A)
                        nn = md.NewIntVar(0, S * S, ""); md.AddMultiplicationEquality(nn, [n, n])
                        pr = md.NewIntVar(0, S * S, ""); md.Add(2 * pr >= nn - n)
                    else:
                        B = grp_day(grp, g2, s2, j)
                        a = md.NewIntVar(0, S, ""); md.Add(a == A)
                        b = md.NewIntVar(0, S, ""); md.Add(b == B)
                        pr = md.NewIntVar(0, S * S, ""); md.AddMultiplicationEquality(pr, [a, b])
                    terms[fam].append((pr, None, j, s1))

        self.hard_expr = sum(e for f in ("covU", "c3n", "c3w") for e, *_ in terms[f])
        md.Add(self.hard_expr <= hmax)
        for f, cap in (fam_caps or {}).items():
            md.Add(sum(e for e, *_ in terms[f]) <= cap)
        w = m["weights"]
        self.obj = sum(int(w[f]) * e for f in MODELLED for e, *_ in terms[f])
        md.Minimize(self.obj)
        for i in range(S):
            for j in range(T):
                if (i, j, board[i][j]) in x:
                    md.AddHint(x[i, j, board[i][j]], 1)

    def solve(self, secs, workers=4):
        sv = cp_model.CpSolver()
        sv.parameters.max_time_in_seconds = secs
        sv.parameters.num_workers = workers
        st = sv.Solve(self.md)
        if st not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
            return sv.StatusName(st), None, None, None, None
        sol = [[next(k for k in range(self.K) if (i, j, k) in self.x and sv.Value(self.x[i, j, k])) for j in range(self.T)] for i in range(self.S)]
        fam = {f: sum(int(sv.Value(e)) if not isinstance(e, int) else e for e, *_ in self.terms[f]) for f in MODELLED}
        return sv.StatusName(st), sol, int(sv.ObjectiveValue()), fam, (sv, self)


def modelled_counts(m, board):
    b = Board(m, board, set(), 10 ** 9)
    st, sol, obj, fam, _ = b.solve(10)
    return fam, obj


def per_staff_load(m, oracle_bd_board, board):
    """OWA 順の材料: 職員ごとの個人の違反の重み（c1・low・high・apt・c3m・c3mn・weekly）。"""
    b = Board(m, board, set(), 10 ** 9)
    sv, bb = b.solve(10)[4]
    w = m["weights"]; load = [0.0] * m["S"]
    for f in ("c1", "low", "high", "apt", "c3m", "c3mn", "weekly", "c3w"):
        for e, i, j, k in bb.terms[f]:
            if i is not None:
                load[i] += w[f] * (e if isinstance(e, int) else sv.Value(e))
    return load


def blockers(m, board, free, hmax, secs):
    """融合の材料: 必須を 1 件だけ緩めて解き、増えた必須の位置（職員, 日）を返す。改善が無ければ空。"""
    b = Board(m, board, free, hmax + 1)
    r = b.solve(secs)
    if r[1] is None:
        return [], None
    sv, bb = r[4]
    base = Board(m, board, set(), 10 ** 9).solve(10)[4]
    bsv, bbb = base
    out = []
    for f in ("covU", "c3n", "c3w"):
        for (e, i, j, k), (e0, *_ ) in zip(bb.terms[f], bbb.terms[f]):
            v = e if isinstance(e, int) else sv.Value(e)
            v0 = e0 if isinstance(e0, int) else bsv.Value(e0)
            if v > v0:
                out.append((f, i, j, k))
    return out, r[2]


def main():
    mp, state, cp, outd = sys.argv[1:5]
    budget = float(sys.argv[5]) if len(sys.argv) > 5 else 540
    t0 = time.time()
    os.makedirs(outd, exist_ok=True)
    log = open(os.path.join(outd, "membrane.log"), "w")

    def say(*a):
        s = " ".join(str(v) for v in a)
        print(s, flush=True); log.write(s + "\n"); log.flush()

    m = json.load(open(mp))
    S, T, K = m["S"], m["T"], m["K"]
    oracle = Oracle(cp, state)
    skin = [list(r) for r in m["board"]]
    rep = oracle.check(skin)
    say("起点", rep["hard"], rep["total"], rep["weighted"], {k: v for k, v in rep["bd"].items() if v})

    # 1. 写しの確認: fair 以外の全族で checker と一致すること
    fam, obj = modelled_counts(m, skin)
    bad = {f: (fam[f], rep["bd"][f]) for f in MODELLED if fam[f] != rep["bd"][f]}
    say("写しの照合", "一致" if not bad else f"不一致 {bad}", "重み(fair除く)", obj, "checker−fair", rep["weighted"] - 5 * rep["bd"]["fair"])
    if bad:
        sys.exit(1)

    # 2. 膜の種: 違反のある職員 × 7 日窓（中間膜 week）。細胞＝職員集合 × 日区間。
    maxd = max([len(s) for s in m["c3n"]] + [1]) - 1
    def partners(i):
        return [i2 for i2 in range(S) if i2 != i and m["sgrp"][i2] == m["sgrp"][i]]

    def seeds(board):
        b = Board(m, board, set(), 10 ** 9)
        sv, bb = b.solve(10)[4]
        out = []
        for f in ("c1", "c3m", "c3mn", "c42", "c42s", "covO", "high", "apt", "weekly"):
            for e, i, j, k in bb.terms[f]:
                v = e if isinstance(e, int) else sv.Value(e)
                if v <= 0:
                    continue
                if i is not None and j is not None:
                    out.append((f, (i,), (max(0, j - maxd), min(T, j + 7 + maxd))))
                elif i is not None:
                    out.append((f, (i,), (0, T)))
                else:
                    who = tuple(i2 for i2 in range(S) if board[i2][j] == k)
                    out.append((f, who, (max(0, j - 3), min(T, j + 4))))
        return out

    load = per_staff_load(m, rep, skin)
    say("OWA 職員の個人の違反の重み", [round(v) for v in load])

    CAP_STAFF, CAP_DAYS = 6, 31
    CELL_SECS = 5
    hmax = rep["hard"]
    accepted, proofs, capped, evidence = 0, 0, 0, []
    sweep = 0
    while time.time() - t0 < budget:
        sweep += 1
        sd = seeds(skin)
        # OWA: 最も不利な職員を含む細胞から（重み 1, 1/2, 1/4… の順位和で並べる）
        order = sorted(range(S), key=lambda i: -load[i])
        rank = {i: r for r, i in enumerate(order)}
        cells = {}
        for f, who, (a, b) in sd:
            key = (tuple(sorted(who)), a // 7, b)
            cells.setdefault(key, set()).add(f)
        cells = {(w, max(0, a7 * 7 - 2), b): v for (w, a7, b), v in cells.items()}
        cl = sorted(cells.items(), key=lambda kv: -sum(0.5 ** rank[i] for i in kv[0][0]))
        say(f"巡 {sweep}: 細胞 {len(cl)}（{sum(len(v) for v in cells.values())} 種）")
        improved_in_sweep = False
        for (who, a, b), fams in cl:
            if time.time() - t0 > budget:
                break
            staff = set(who); days = (a, b)
            # 初期の細胞: 違反の職員＋同じグループから 2 名（OWA の不利な順）まで＝3 名 × 窓
            for i in list(staff):
                for i2 in sorted(partners(i), key=lambda q: -load[q])[:2]:
                    if len(staff) < 3:
                        staff.add(i2)
            hist = []
            for step in range(8):
                if time.time() - t0 > budget:
                    break
                free = {(i, j) for i in staff for j in range(days[0], days[1])}
                st, sol, obj, fm, _ = Board(m, skin, free, hmax).solve(CELL_SECS)
                cur_obj = modelled_counts(m, skin)[1]
                hist.append(f"{sorted(staff)}×{days[0]+1}-{days[1]}日:{st}:{obj}/{cur_obj}")
                if sol is not None and obj < cur_obj:
                    r2 = oracle.check(sol)
                    if better(r2, rep):
                        skin, rep = sol, r2
                        accepted += 1; improved_in_sweep = True
                        json.dump(skin, open(os.path.join(outd, "membrane.sol.json"), "w"))
                        say(f"  溶解（減らせる）{sorted(fams)} 細胞 {sorted(staff)} {days[0]+1}-{days[1]}日 → 必須 {rep['hard']} 合計 {rep['total']} 重み {rep['weighted']}",
                            {k: v for k, v in rep["bd"].items() if v})
                        load = per_staff_load(m, rep, skin)
                        break
                    hist.append(f"checker不採用(fair {r2['bd']['fair']} vs {rep['bd']['fair']}, 重み {r2['weighted']})")
                if st == "OPTIMAL" and obj >= cur_obj:
                    proofs += 1
                # 融合: 必須を 1 件緩めたときに増える必須の位置を細胞へ取り込む（チャネル＝その位置の族）
                bl, _ = blockers(m, skin, free, hmax, CELL_SECS)
                grow, d2 = set(), days
                for f, i, j, k in bl:
                    if i is not None:
                        grow.add(i)
                    else:
                        grow |= {i2 for i2 in range(S) if m["canDo"][i2][k] and i2 not in staff}
                    if j is not None:
                        d2 = (max(0, min(d2[0], j - maxd)), min(T, max(d2[1], j + maxd + 1)))
                add = sorted(grow - staff, key=lambda q: -load[q])[: max(0, CAP_STAFF - len(staff))]
                if not add and d2 == days:
                    # 阻んだ必須が細胞の中だけ＝融合の先が無い。日を全期間へ一度だけ広げ、それでも同じなら証拠として止める
                    if days != (0, T):
                        days = (0, T); continue
                    capped += 1
                    evidence.append({"fams": sorted(fams), "staff": sorted(staff), "days": [days[0] + 1, days[1]],
                                     "status": st, "blockers": bl[:6], "hist": hist})
                    say(f"  停止 {sorted(fams)} {hist[-1]} 阻んだ必須 {[(f, i, j) for f, i, j, k in bl[:4]]}")
                    break
                staff |= set(add); days = d2
        if not improved_in_sweep:
            break
    json.dump(skin, open(os.path.join(outd, "membrane.sol.json"), "w"))
    say(f"膜の結果: 必須 {rep['hard']} 合計 {rep['total']} 重み {rep['weighted']} 採用 {accepted} 細胞内の最適証明 {proofs} 停止 {capped} {time.time()-t0:.0f}s")
    ref = float(os.environ.get("MAGI_MEM_GLOBAL", "0"))
    if ref > 0:
        st, sol, obj, fm, _ = Board(m, skin, {(i, j) for i in range(S) for j in range(T)}, hmax).solve(ref)
        if sol is not None:
            r2 = oracle.check(sol)
            say(f"参照（全盤面を 1 細胞・{ref:.0f}s・{st}）: 必須 {r2['hard']} 合計 {r2['total']} 重み {r2['weighted']}", {k: v for k, v in r2["bd"].items() if v})
            json.dump(sol, open(os.path.join(outd, "global.sol.json"), "w"))
    json.dump(evidence, open(os.path.join(outd, "evidence.json"), "w"), ensure_ascii=False, indent=1)
    say(f"結果: 必須 {rep['hard']} 合計 {rep['total']} 重み {rep['weighted']} 採用 {accepted} 細胞内の最適証明 {proofs} 停止 {capped} checker {oracle.calls} 回 {time.time()-t0:.0f}s",
        {k: v for k, v in rep["bd"].items() if v})


if __name__ == "__main__":
    main()
