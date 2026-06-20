#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MAGI 最適化器の「等価ベンチ」(CRINN 流・実行計測スカラー報酬による A/B 評価).

目的: Kotlin エンジン(サンドボックスでは実行不可)で入れた脱出機構が「本当に効くか」を、
      同型の合成 NSP 上で実測比較する。CRINN の QPS-Recall AUC に相当する報酬で順位付けする。

機構は Kotlin と等価(byte 一致ではなく同じ仕組み):
  - 戦略的振動: 深い停滞 & ON窓 & HARD族が停滞(HF63ゲート) & excursion上限 のとき受理層で hard を割引
  - GLS penalty + aging(減衰)
  - 非線形 restart 摂動 (序盤強→終盤弱)
全機構とも globalBest は生スコアで管理 → 解は退化しない(Kotlin と同じ安全性)。

報酬: best_total を反復チェックポイントで平均(=AUC 風。早く良い解へ届くほど高評価)。低いほど良い。
"""
import random, math, statistics

# ---------------- 合成 NSP インスタンス ----------------
def make_instance(S, T, K, seed, tight):
    rng = random.Random(seed)
    canDo = [[True] * K for _ in range(S)]
    for i in range(S):
        for k in range(1, K):
            if rng.random() < 0.25:
                canDo[i][k] = False           # 担当不可で「壁」を作る
    need = [[0] * T for _ in range(K)]
    for k in range(1, K):
        for j in range(T):
            need[k][j] = 1 + (1 if rng.random() < 0.25 + tight * 0.5 else 0)
    lo = [[0] * K for _ in range(S)]
    hi = [[T] * K for _ in range(S)]
    for i in range(S):
        for k in range(1, K):
            if canDo[i][k] and rng.random() < 0.3:
                lo[i][k] = rng.randint(2, 5)
                hi[i][k] = lo[i][k] + rng.randint(0, 3)
    return dict(S=S, T=T, K=K, canDo=canDo, need=need, lo=lo, hi=hi)

def allowed(inst, i):
    return [0] + [k for k in range(1, inst['K']) if inst['canDo'][i][k]]

def score(inst, sched):
    S, T, K, need, lo, hi = inst['S'], inst['T'], inst['K'], inst['need'], inst['lo'], inst['hi']
    hard = 0; soft = 0
    for k in range(1, K):
        for j in range(T):
            cnt = sum(1 for i in range(S) if sched[i][j] == k)
            if cnt < need[k][j]: hard += need[k][j] - cnt          # covU (HARD)
            elif cnt > need[k][j]: soft += cnt - need[k][j]         # covO (soft, 重み1)
    cnt = [[0] * K for _ in range(S)]
    for i in range(S):
        for j in range(T):
            cnt[i][sched[i][j]] += 1
    for i in range(S):
        for k in range(1, K):
            n = cnt[i][k]
            if lo[i][k] and n < lo[i][k]: soft += (lo[i][k] - n) * 90   # low
            if n > hi[i][k]: soft += (n - hi[i][k]) * 45                # high
    return hard, soft

def raw(h, s):
    return h * 1_000_000 + s

# ---------------- ALNS destroy-repair(実機の核) ----------------
def repair_day(inst, sched, j, rng):
    """日 j を貪欲修復: 需要を満たすよう、その日 休(0) の担当可能者を各シフトへ割当てる。"""
    S, K, need = inst['S'], inst['K'], inst['need']
    avail = [i for i in range(S) if sched[i][j] == 0]
    rng.shuffle(avail)
    order = list(range(1, K)); rng.shuffle(order)
    for k in order:
        want = need[k][j]
        have = sum(1 for i in range(S) if sched[i][j] == k)
        idx = 0
        while have < want and idx < len(avail):
            i = avail[idx]; idx += 1
            if sched[i][j] == 0 and inst['canDo'][i][k]:
                sched[i][j] = k; have += 1
        avail = avail[idx:]

def destroy_repair_day(inst, sched, rng):
    """ランダムな1日を破壊(全員休に)→貪欲修復。スライス(列)を snapshot して呼出側が revert 可能に。"""
    T = inst['T']; j = rng.randrange(T)
    col = [sched[i][j] for i in range(inst['S'])]
    for i in range(inst['S']):
        sched[i][j] = 0
    repair_day(inst, sched, j, rng)
    return ('day', j, col)

def destroy_repair_staff(inst, sched, rng):
    """ランダムな1職員の全日を破壊→各日 個別に貪欲修復(被覆の穴を埋める)。行を snapshot。"""
    S, T = inst['S'], inst['T']; i = rng.randrange(S)
    row = sched[i][:]
    for j in range(T):
        sched[i][j] = 0
    # その職員が埋められる被覆穴を貪欲に埋める(なければ休のまま)
    for j in range(T):
        for k in range(1, inst['K']):
            if inst['canDo'][i][k]:
                have = sum(1 for x in range(S) if sched[x][j] == k)
                if have < inst['need'][k][j]:
                    sched[i][j] = k; break
    return ('staff', i, row)

def revert_move(sched, mv):
    kind = mv[0]
    if kind == 'day':
        _, j, col = mv
        for i in range(len(col)):
            sched[i][j] = col[i]
    elif kind == 'staff':
        _, i, row = mv
        sched[i] = row[:]

# ---------------- SA(機構トグル付き) ----------------
def optimize(inst, seed, iters, feats):
    rng = random.Random(seed * 0x9E3779B1 & 0xFFFFFFFF)
    S, T, K = inst['S'], inst['T'], inst['K']
    alw = [allowed(inst, i) for i in range(S)]
    restarts = 3
    best = [[rng.choice(alw[i]) for _ in range(T)] for i in range(S)]
    bh, bs = score(inst, best); bestRaw = raw(bh, bs)
    checkpoints = []
    pen = {}                       # GLS penalty: (i,j,k)->int
    kicks = 0
    total_iters = 0
    per = max(1, iters // restarts)
    # 可変パラメータ(#2 実測チューニング用。既定は Kotlin の現行値)
    gls_lambda = feats.get('gls_lambda', 200.0)
    gls_keep = feats.get('gls_keep', 80)
    gls_decay_every = feats.get('gls_decay_every', 256)
    nr_base = feats.get('nr_base', 0.18); nr_lo = feats.get('nr_lo', 0.6); nr_hi = feats.get('nr_hi_add', 1.2)
    dr_day = feats.get('dr_day', 0.20); dr_staff = feats.get('dr_staff', 0.10)   # ALNS destroy-repair 比率
    for r in range(restarts):
        # --- 非線形 restart 摂動 ---
        if r == 0:
            cur = [row[:] for row in best]
        else:
            cur = [row[:] for row in best]
            if feats.get('nonlinear_restart'):
                frac = r / (restarts - 1)
                strength = (nr_base * (nr_lo + nr_hi * (1 - frac) ** 2))
            else:
                strength = nr_base
            for i in range(S):
                for j in range(T):
                    if rng.random() < strength:
                        cur[i][j] = rng.choice(alw[i])
        ch, cs = score(inst, cur); curRaw = raw(ch, cs)
        lastImprove = total_iters
        lastHardImprove = total_iters          # HF63 ゲート相当: HARD が改善した最後の反復
        prevHard = ch
        # 振動の窓は予算に比例(Kotlin は数百万iterなので固定600/1200/800が極小割合。等価にするため per スケール)
        oscTrig = max(50, per // 10)
        oscPer = max(100, per // 5)
        oscOn = oscPer * 2 // 3
        for it in range(per):
            total_iters += 1
            # --- 候補手の選択: ALNS destroy-repair(day/staff) or 1セル変更 ---
            r_mv = rng.random(); mv = None; ci = cj = -1; old = -1
            if r_mv < dr_day:
                mv = destroy_repair_day(inst, cur, rng)
            elif r_mv < dr_day + dr_staff:
                mv = destroy_repair_staff(inst, cur, rng)
            else:
                i = rng.randrange(S); j = rng.randrange(T)
                old = cur[i][j]; nw = rng.choice(alw[i])
                if nw == old:
                    checkpoints.append(bestRaw); continue
                cur[i][j] = nw; ci, cj = i, j
            nh, ns = score(inst, cur); nsRaw = raw(nh, ns)
            temp = max(0.05, 1.0 - it / per)
            # --- GLS augment(1セル手のみ) ---
            aug = 0.0
            if feats.get('gls') and ci >= 0:
                aug = gls_lambda * (pen.get((ci, cj, cur[ci][cj]), 0) - pen.get((ci, cj, old), 0))
            # --- 戦略的振動(受理層 hard 割引・研究用トグル。Kotlin では 2.55 で revert 済) ---
            relax = 0.0
            if feats.get('oscillation'):
                stall = total_iters - lastImprove
                hardStuck = (ch > 0) and (total_iters - lastHardImprove) > oscTrig
                inWindow = (it % oscPer) < oscOn
                excursionOk = ch <= (bestRaw // 1_000_000) + 2
                if stall > oscTrig and inWindow and hardStuck and excursionOk:
                    relax = 0.9999
            def _revert():
                if mv is not None: revert_move(cur, mv)
                else: cur[ci][cj] = old
            # per-step 上限(±2 hard)
            if nsRaw > curRaw + 2_000_000:
                _revert(); checkpoints.append(bestRaw); continue
            if relax > 0:
                delta = (nh - ch) * (1 - relax) * 1_000_000 + (ns - cs) + aug
            else:
                delta = (nsRaw - curRaw) + aug
            accept = delta <= 0 or rng.random() < math.exp(-min(60, max(0.0, delta) / (200 * temp + 1e-9)))
            if accept:
                curRaw = nsRaw; ch, cs = nh, ns
                if ch < prevHard:
                    prevHard = ch; lastHardImprove = total_iters   # HARD 改善 → HF63 停滞カウンタをリセット
                if nsRaw < bestRaw:
                    bestRaw = nsRaw; best = [row[:] for row in cur]; lastImprove = total_iters
            else:
                _revert()
            # --- GLS penalize + aging(1セル手の停滞時) ---
            if feats.get('gls') and ci >= 0 and (total_iters - lastImprove) > 200 and it % 50 == 0 and ch > 0:
                pen[(ci, cj, cur[ci][cj])] = pen.get((ci, cj, cur[ci][cj]), 0) + 1
                kicks += 1
                if feats.get('gls_decay') and kicks % gls_decay_every == 0:
                    for key in list(pen.keys()):
                        v = pen[key] * gls_keep // 100
                        if v <= 0: del pen[key]
                        else: pen[key] = v
            checkpoints.append(bestRaw)
    auc = statistics.mean(checkpoints) if checkpoints else bestRaw
    return bestRaw, bh if False else (bestRaw // 1_000_000), auc

# ---------------- A/B ランナー ----------------
def run_variant(name, feats, instances, seeds, iters):
    finals = []; hards = []; aucs = []
    for inst in instances:
        for sd in seeds:
            fr, hh, auc = optimize(inst, sd, iters, feats)
            finals.append(fr % 1_000_000 if fr // 1_000_000 == 0 else fr)  # feasible は soft, infeasible は raw
            hards.append(fr // 1_000_000)
            aucs.append(auc)
    return name, statistics.mean(finals), statistics.mean(hards), statistics.mean(aucs)

def main():
    S, T, K = 8, 21, 5
    # borderline(tight=0.35: 越える価値のある壁) と over(tight=0.7: 過拘束) の2系
    inst_border = [make_instance(S, T, K, sd, 0.35) for sd in range(4)]
    inst_over = [make_instance(S, T, K, sd + 100, 0.7) for sd in range(4)]
    seeds = list(range(6))
    iters = 6000
    variants = [
        ("baseline", {}),
        ("+gls", {"gls": True}),
        ("+gls+decay", {"gls": True, "gls_decay": True}),
        ("+nonlinear_restart", {"nonlinear_restart": True}),
        ("+oscillation", {"oscillation": True}),
        ("ALL", {"gls": True, "gls_decay": True, "nonlinear_restart": True, "oscillation": True}),
    ]
    # 真に過拘束で destroy-repair でも hard>0 が残る tier(=脱出が効くなら効く領域)
    inst_hard = [make_instance(S, T, K, sd + 200, 1.0) for sd in range(4)]
    for label, insts in [("BORDERLINE(tight=0.35)", inst_border),
                         ("OVER-CONSTRAINED(tight=0.7)", inst_over),
                         ("HARD-INFEASIBLE(tight=1.0)", inst_hard)]:
        print(f"\n=== {label}  (S={S} T={T} K={K}, {len(insts)}inst x {len(seeds)}seed x {iters}iter, +ALNS destroy-repair) ===")
        print(f"{'variant':<20} {'mean_final':>12} {'mean_hard':>10} {'mean_AUC':>12}   (低いほど良い)")
        rows = [run_variant(n, f, insts, seeds, iters) for n, f in variants]
        base_auc = rows[0][3]
        for n, mf, mh, auc in rows:
            d = (auc - base_auc) / base_auc * 100 if base_auc else 0
            print(f"{n:<20} {mf:>12.1f} {mh:>10.2f} {auc:>12.0f}   ({d:+.1f}% vs base)")

    # ---- #2 実測パラメータスイープ(GLS/nonlinear_restart を hard tier で実測) ----
    print(f"\n=== PARAM SWEEP on HARD-INFEASIBLE (3seed x 4000iter) ===")
    print(f"{'config':<28} {'mean_final':>12} {'mean_hard':>10} {'mean_AUC':>12}   (低いほど良い)")
    sw_seeds = list(range(3)); sw_iters = 4000
    sweep = [
        ("baseline", {}),
        ("gls keep70", {"gls": True, "gls_decay": True, "gls_keep": 70}),
        ("gls keep90", {"gls": True, "gls_decay": True, "gls_keep": 90}),
        ("gls decay128", {"gls": True, "gls_decay": True, "gls_decay_every": 128}),
        ("gls lambda100", {"gls": True, "gls_decay": True, "gls_lambda": 100.0}),
        ("nr hi0.8", {"nonlinear_restart": True, "nr_hi_add": 0.8}),
        ("nr hi2.0", {"nonlinear_restart": True, "nr_hi_add": 2.0}),
        ("nr base0.30", {"nonlinear_restart": True, "nr_base": 0.30}),
    ]
    base_auc = None
    for n, f in sweep:
        _, mf, mh, auc = run_variant(n, f, inst_hard, sw_seeds, sw_iters)
        if base_auc is None: base_auc = auc
        d = (auc - base_auc) / base_auc * 100 if base_auc else 0
        print(f"{n:<28} {mf:>12.1f} {mh:>10.2f} {auc:>12.0f}   ({d:+.1f}% vs base)")

if __name__ == "__main__":
    main()
