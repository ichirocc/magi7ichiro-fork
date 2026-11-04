#!/usr/bin/env python3
# [2026-11-03 ドッグフーディング] docs/seesaw.md の改める前の §4（2σ の揺れ規則）・§6.1（競合率 c>=0.6）・§6.2 S2a を文言どおりに当てた記録（§13.2）。
# 判定の規則は §13 で改めたので、この集計の「揺れ」「独立」は今の規則ではない。
"""docs/seesaw.md の手順（§4 の 1・2a、§6.1 の競合率、§2 の交換率）を文言どおりに当てる。
   python3 -I seesaw_dogfood.py Q.csv P.csv 押した族
腕 Q＝今の重み、腕 P＝押した族の重みだけ ×2。対は (fixture, seed)。差は P−Q（§2: 新−旧）。"""
import csv, random, statistics, sys

FAM = ["c1", "c2", "c3", "c3n", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covU", "covO", "pref",
       "low", "high", "groupViol", "apt", "fair", "weekly", "c3w", "extWish"]
HARD = ["groupViol", "c3n", "covU", "pref", "c3w", "extWish"]


def load(p):
    with open(p, encoding="utf-8") as f:
        return {(r["fixture"], r["seed"]): {k: int(v) for k, v in r.items() if k in FAM + ["hard", "total"]} for r in csv.DictReader(f)}


def ci(d, rnd):  # weight_swap_stats.py と同じ（4000 回、2.5%・97.5%）
    m = sorted(sum(d[rnd.randrange(len(d))] for _ in d) / len(d) for _ in range(4000))
    return m[100], m[3899]


Q, P, A = load(sys.argv[1]), load(sys.argv[2]), sys.argv[3]
keys = sorted(set(Q) & set(P))
fixtures = sorted({k[0] for k in keys})
print(f"対 {len(keys)}（{len(fixtures)} fixture）。押した族 {A}。差は P−Q")

# §4 の 1: σ_A＝同じ腕・同じデータで seed を変えたときの標準偏差（データごとに出して平均）。腕 Q で出す（§6.1「揺れ: 腕 Q を seed 6 個」）
def sigma(fam):
    sds = []
    for fx in fixtures:
        v = [Q[k][fam] for k in keys if k[0] == fx]
        if len(v) >= 2: sds.append(statistics.stdev(v))
    return sum(sds) / len(sds) if sds else 0.0

rnd = random.Random(7)
D = {f: [P[k][f] - Q[k][f] for k in keys] for f in FAM + ["hard", "total"]}
print("\n族      平均差  95%区間           増/減/同   σ(Q)   §4の1:|平均|<2σ")
for f in ["hard", "total"] + FAM:
    d = D[f]
    if all(P[k][f] == 0 and Q[k][f] == 0 for k in keys): continue
    lo, hi = ci(d, rnd); s = sigma(f) if f in FAM else 0.0
    mean = sum(d) / len(d)
    noise = "揺れ" if f in FAM and abs(mean) < 2 * s else ""
    ci0 = "区間が0を含む" if lo <= 0 <= hi else "区間が0を外す"
    print(f"{f:<8}{mean:>+7.2f}  [{lo:+6.2f},{hi:+6.2f}]  {sum(x>0 for x in d):>2}/{sum(x<0 for x in d):>2}/{sum(x==0 for x in d):>2}   {s:5.2f}   {noise:<4} {ci0}")

# §6.1: 競合率と判定、§2: 交換率
print(f"\n§6.1 の判定（A={A}）: 分母＝A か B の少なくとも一方が動いた対、c＝逆向きの割合、h＝同じ向きの割合")
dA = D[A]
for B in FAM:
    if B == A: continue
    dB = D[B]
    moved = [i for i in range(len(keys)) if dA[i] != 0 or dB[i] != 0]
    if not moved: continue
    opp = sum(1 for i in moved if dA[i] * dB[i] < 0); same = sum(1 for i in moved if dA[i] * dB[i] > 0)
    c = opp / len(moved); h = same / len(moved)
    sub = [dB[i] for i in range(len(keys)) if dA[i] < 0]
    if len(moved) < 8:
        verdict = "判定しない（分母<8）"
    elif sub:
        lo, hi = ci(sub, rnd)
        verdict = "競合" if c >= 0.6 and lo > 0 else ("調和" if h >= 0.6 and hi < 0 else "独立")
    else:
        verdict = "独立（A が減った対が無い）"
    r = ""
    if sub:
        decA = [-dA[i] for i in range(len(keys)) if dA[i] < 0]
        mA = statistics.median(decA)
        r = f" r={statistics.median(sub)/mA:+.2f}" if mA else ""
    s4 = "揺れ(§4)" if abs(sum(dA)/len(dA)) < 2 * sigma(A) or abs(sum(dB)/len(dB)) < 2 * sigma(B) else "揺れでない(§4)"
    print(f"  {A}→{B:<6} 分母{len(moved):>2} c={c:.2f} h={h:.2f} A減の対{len(sub):>2}{r:<9} {verdict:<10} {s4}")

# §6.2 S2a: データごとに全腕・全 seed の盤面を集め、必須が最小のものだけで優越を見る
print("\n§6.2 S2a（必須が最小の盤面だけ、21 族の件数ベクトルで優越）")
def dom(x, y):
    return all(x[f] <= y[f] for f in FAM) and any(x[f] < y[f] for f in FAM)
tot_p = tot_pd = 0
for fx in fixtures:
    boards = [("Q", k[1], Q[k]) for k in keys if k[0] == fx] + [("P", k[1], P[k]) for k in keys if k[0] == fx]
    hmin = min(b[2]["hard"] for b in boards)
    pool = [b for b in boards if b[2]["hard"] == hmin]
    nd = [b for b in pool if not any(dom(o[2], b[2]) for o in pool if o is not b)]
    pd = [b for b in pool if b[0] == "P" and any(dom(o[2], b[2]) for o in pool if o is not b)]
    pp = [b for b in pool if b[0] == "P"]
    qd = [b for b in pool if b[0] == "Q" and any(dom(o[2], b[2]) for o in pool if o is not b)]
    tot_p += len(pp); tot_pd += len(pd)
    print(f"  {fx:<22} 盤面 {len(boards)}・必須最小({hmin}) {len(pool)}・非劣 {len(nd)}・P のうち優越された {len(pd)}/{len(pp)}・Q のうち優越された {len(qd)}/{len(pool)-len(pp)}")
print(f"  合計: P の盤面のうち優越された {tot_pd}/{tot_p}")

# 族を (A, B) の 2 つだけに絞ったときの優越（参考: §1 の「両方を減らす盤面」を文字どおりに読んだ場合）
print(f"\n参考: ({A}, B) の 2 族だけで比べた優越（ほかの族は見ない）")
for B in ["c1", "c3m", "c3mn", "low", "fair", "apt", "weekly"]:
    n = m = 0
    for fx in fixtures:
        pool = [Q[k] for k in keys if k[0] == fx] + [P[k] for k in keys if k[0] == fx]
        hmin = min(b["hard"] for b in pool); pool = [b for b in pool if b["hard"] == hmin]
        for k in keys:
            if k[0] != fx or P[k]["hard"] != hmin: continue
            m += 1
            x = P[k]
            if any(o[A] <= x[A] and o[B] <= x[B] and (o[A] < x[A] or o[B] < x[B]) for o in pool if o is not x): n += 1
    print(f"  ({A},{B:<6}) P の盤面のうち、2 族で優越された {n}/{m}")
