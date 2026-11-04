#!/usr/bin/env python3
"""シーソーの地図の集計（docs/seesaw.md §6.1 の S1、2026-11-03 に改めた規則）。run_seesaw_map.sh の出力ディレクトリを読む。
  python3 tools/loop/seesaw_map_stats.py <出力ディレクトリ>
・族 A ごとに P-A.csv と Q-A.csv を (fixture, seed) で対にする。差は P−Q。区間は 95% ブートストラップ（weight_swap_stats.py と同じ 4000 回・seed 7）。
・ΔA の区間が負でなければ「A を押せていない」として行を出さない。族 B は ΔB の区間が正＝競合、負＝調和、0 を含む＝独立。対が 8 未満は判定しない。
・競合率（逆向きに動いた対の割合）と交換率（中央値の比）は記述に添えるだけ。
・揺れ（A/A）: Q は族の数だけ走るので、同じ (fixture, seed) の Q どうしの差から、族ごとの「腕が同じでも出る差」の区間も出す。"""
import csv, glob, os, random, statistics, sys

FAM = ["c1", "c2", "c3", "c3n", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covU", "covO", "pref",
       "low", "high", "groupViol", "apt", "fair", "weekly", "c3w", "extWish"]
LBL = {"c1": "期間の制約", "c2": "個人の合計", "c3": "守るとよい並び", "c3m": "推奨の並び", "c3mn": "回避の並び", "c41": "グループのレンジ",
       "c42": "グループペア", "c41s": "スキルグループのレンジ", "c42s": "スキルグループペア", "covO": "人員過剰", "low": "下限割れ",
       "high": "上限超過", "apt": "目安の回数との差", "fair": "グループ内の回数のばらつき", "weekly": "曜日の偏り"}
d = sys.argv[1]
rnd = random.Random(7)


def load(p):
    with open(p, encoding="utf-8") as f:
        return {(r["fixture"], r["seed"]): {k: int(v) for k, v in r.items() if k in FAM + ["hard", "total"]} for r in csv.DictReader(f)}


def ci(v):
    m = sorted(sum(v[rnd.randrange(len(v))] for _ in v) / len(v) for _ in range(4000))
    return m[100], m[3899]


qs = {}
print("| 押した族 | 押せたか（Δ・区間） | 競合（増えた族） | 調和（一緒に減った族） | 対 |")
print("|---|---|---|---|---|")
for pf in sorted(glob.glob(os.path.join(d, "P-*.csv"))):
    A = os.path.basename(pf)[2:-4]
    qf = os.path.join(d, f"Q-{A}.csv")
    if not os.path.exists(qf):
        continue
    P, Q = load(pf), load(qf); qs[A] = Q
    keys = sorted(set(P) & set(Q))
    if len(keys) < 8:
        print(f"| {A} | 対 {len(keys)}（8 未満、判定しない） | | | {len(keys)} |"); continue
    dA = [P[k][A] - Q[k][A] for k in keys]; lo, hi = ci(dA); mA = sum(dA) / len(dA)
    if hi >= 0:
        print(f"| {LBL.get(A, A)} | 押せていない {mA:+.2f} [{lo:+.2f}, {hi:+.2f}] | | | {len(keys)} |"); continue
    comp, harm = [], []
    for B in FAM:
        if B == A:
            continue
        dB = [P[k][B] - Q[k][B] for k in keys]
        if not any(dB):
            continue
        l2, h2 = ci(dB); mB = sum(dB) / len(dB)
        moved = [i for i in range(len(keys)) if dA[i] or dB[i]]
        c = sum(1 for i in moved if dA[i] * dB[i] < 0) / len(moved) if moved else 0
        sub = [dB[i] for i in range(len(keys)) if dA[i] < 0]; dec = [-dA[i] for i in range(len(keys)) if dA[i] < 0]
        r = statistics.median(sub) / statistics.median(dec) if sub and statistics.median(dec) else 0
        txt = f"{LBL.get(B, B)} {mB:+.2f} [{l2:+.2f}, {h2:+.2f}]（競合率 {c:.2f}・交換率 {r:+.2f}）"
        if l2 > 0:
            comp.append(txt)
        elif h2 < 0:
            harm.append(txt)
    print(f"| {LBL.get(A, A)} | {mA:+.2f} [{lo:+.2f}, {hi:+.2f}] | {'<br>'.join(comp) or '―'} | {'<br>'.join(harm) or '―'} | {len(keys)} |")

# 揺れ（A/A）: 同じ (fixture, seed) の Q どうし（族の数だけある）を、族の順の隣どうしで対にした差
if len(qs) >= 2:
    names = sorted(qs)
    print("\n揺れ（A/A、同じ重みの腕どうしの差。区間が 0 を外す族は、測定の偏りを疑う）")
    for f in ["hard", "total", "c1", "high", "c3", "c3m", "c3mn", "fair", "apt", "weekly", "low", "covO"]:
        v = []
        for a1, a2 in zip(names, names[1:]):
            for k in set(qs[a1]) & set(qs[a2]):
                v.append(qs[a2][k][f] - qs[a1][k][f])
        if v and any(v):
            lo, hi = ci(v)
            print(f"  {f:<7} 差の平均 {sum(v)/len(v):+.2f} [{lo:+.2f}, {hi:+.2f}]・差の標準偏差 {statistics.pstdev(v):.2f}（{len(v)} 対）")
