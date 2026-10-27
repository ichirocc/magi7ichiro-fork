#!/usr/bin/env python3
"""重みの入れ替え（WeightSwapProbe）の旧→新を族ごとの件数で対にして集計する。
   python3 tools/loop/weight_swap_stats.py old.csv new.csv
差は 新−旧（負＝新しい重みで減った）。平均・95% ブートストラップ区間・増/減/同の数。"""
import csv, random, sys

def load(p):
    with open(p, encoding='utf-8') as f:
        return {(r['fixture'], r['seed']): r for r in csv.DictReader(f)}

old, new = load(sys.argv[1]), load(sys.argv[2])
keys = sorted(set(old) & set(new))
cols = [c for c in next(iter(old.values())).keys() if c not in ('fixture', 'seed', 'arm', 'elapsedMs')]
rnd = random.Random(7)

def ci(d):
    m = []
    for _ in range(4000):
        s = [d[rnd.randrange(len(d))] for _ in d]
        m.append(sum(s) / len(s))
    m.sort()
    return m[100], m[3899]

print(f"対 {len(keys)}（fixture×seed）。差は 新−旧（負＝減った）")
print(f"{'族':<10}{'旧平均':>8}{'新平均':>8}{'差平均':>8}  95%区間        増/減/同")
for c in ['hard', 'total'] + [c for c in cols if c not in ('hard', 'total')]:
    d = [int(new[k][c]) - int(old[k][c]) for k in keys]
    o = sum(int(old[k][c]) for k in keys) / len(keys); n = sum(int(new[k][c]) for k in keys) / len(keys)
    if o == 0 and n == 0: continue
    lo, hi = ci(d)
    print(f"{c:<10}{o:>8.1f}{n:>8.1f}{sum(d)/len(d):>+8.1f}  [{lo:+.1f}, {hi:+.1f}]  {sum(x>0 for x in d)}/{sum(x<0 for x in d)}/{sum(x==0 for x in d)}")
print("\nfixture 別の 旧→新（c1・c3mn・total、seed 平均）")
for fx in sorted({k[0] for k in keys}):
    ks = [k for k in keys if k[0] == fx]
    avg = lambda t, c: sum(int(t[k][c]) for k in ks) / len(ks)
    print(f"  {fx:<22} c1 {avg(old,'c1'):.1f}→{avg(new,'c1'):.1f}  c3mn {avg(old,'c3mn'):.1f}→{avg(new,'c3mn'):.1f}  total {avg(old,'total'):.1f}→{avg(new,'total'):.1f}  hard {avg(old,'hard'):.1f}→{avg(new,'hard'):.1f}")
