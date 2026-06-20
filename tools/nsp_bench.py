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
    for r in range(restarts):
        # --- 非線形 restart 摂動 ---
        if r == 0:
            cur = [row[:] for row in best]
        else:
            cur = [row[:] for row in best]
            if feats.get('nonlinear_restart'):
                frac = r / (restarts - 1)
                strength = (0.18 * (0.6 + 1.2 * (1 - frac) ** 2))
            else:
                strength = 0.18
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
            # 候補手: 1セル変更
            i = rng.randrange(S); j = rng.randrange(T)
            old = cur[i][j]; nw = rng.choice(alw[i])
            if nw == old:
                checkpoints.append(bestRaw); continue
            cur[i][j] = nw
            nh, ns = score(inst, cur); nsRaw = raw(nh, ns)
            temp = max(0.05, 1.0 - it / per)
            # --- GLS augment ---
            aug = 0.0
            if feats.get('gls'):
                aug = 200.0 * (pen.get((i, j, nw), 0) - pen.get((i, j, old), 0))
            # --- 戦略的振動(受理層 hard 割引) ---
            relax = 0.0
            if feats.get('oscillation'):
                stall = total_iters - lastImprove
                hardStuck = (ch > 0) and (total_iters - lastHardImprove) > oscTrig   # HF63: HARD が停滞
                inWindow = (it % oscPer) < oscOn
                excursionOk = ch <= (bestRaw // 1_000_000) + 2
                if stall > oscTrig and inWindow and hardStuck and excursionOk:
                    relax = 0.9999
            # per-step 上限(±2 hard)
            if nsRaw > curRaw + 2_000_000:
                cur[i][j] = old; checkpoints.append(bestRaw); continue
            if relax > 0:
                dh = (nh - ch) * (1 - relax) * 1_000_000
                delta = dh + (ns - cs) + aug
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
                cur[i][j] = old
            # --- GLS penalize + aging ---
            if feats.get('gls') and (total_iters - lastImprove) > 200 and it % 50 == 0 and ch > 0:
                # 最悪セル(covU が出ている日の勤務者)を1つ penalize
                pen[(i, j, cur[i][j])] = pen.get((i, j, cur[i][j]), 0) + 1
                kicks += 1
                if feats.get('gls_decay') and kicks % 256 == 0:
                    for key in list(pen.keys()):
                        v = pen[key] * 80 // 100
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
    for label, insts in [("BORDERLINE(tight=0.35)", inst_border), ("OVER-CONSTRAINED(tight=0.7)", inst_over)]:
        print(f"\n=== {label}  (S={S} T={T} K={K}, {len(insts)}inst x {len(seeds)}seed x {iters}iter) ===")
        print(f"{'variant':<20} {'mean_final':>12} {'mean_hard':>10} {'mean_AUC':>12}   (低いほど良い)")
        rows = [run_variant(n, f, insts, seeds, iters) for n, f in variants]
        base_auc = rows[0][3]
        for n, mf, mh, auc in rows:
            d = (auc - base_auc) / base_auc * 100 if base_auc else 0
            print(f"{n:<20} {mf:>12.1f} {mh:>10.2f} {auc:>12.0f}   ({d:+.1f}% vs base)")

if __name__ == "__main__":
    main()
