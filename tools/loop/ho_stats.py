#!/usr/bin/env python3
"""HandleOptimizeBench の CSV を対で集計し、腕の差を「決定／未決」で返す（docs/stall_escape.md §13）。

使い方: python3 tools/loop/ho_stats.py results/a.csv [results/b.csv ...] [--metric weightedScore|total] [--boot 10000]

- 対: 同じファイル・fixture・seed・rep の off（既定）と on（比較腕）。差は off − on（正＝既定が悪い）。
- 揺れ: 同じ fixture・seed・arm の rep 間の差（rep 列が無い CSV は rep=1 とし、揺れは出ない）。
- 判定: 対が 8 未満なら常に「未決」。8 以上で、平均差のブートストラップ 95% 区間が 0 を含まなければ「決定」、含めば「未決」と、区間を 0 から外すのに要る対の数の目安。
  符号検定（両側・二項）も併記する。外部ライブラリは使わない。
"""
import csv, math, random, statistics as st, sys


def load(paths):
    rows = []
    for fi, p in enumerate(paths):
        for r in csv.DictReader(open(p, encoding="utf-8")):
            r["_file"] = fi
            r.setdefault("rep", "1")
            rows.append(r)
    return rows


def pairs(rows, metric):
    by = {}
    for r in rows:
        k = (r["_file"], r["fixture"], int(r["seed"]), int(r["rep"] or 1))
        by.setdefault(k, {})[r["arm"]] = r
    out = []
    for k in sorted(by):
        a = by[k]
        if "off" in a and "on" in a:
            d = float(a["off"][metric]) - float(a["on"][metric])
            out.append((k, d, int(a["off"]["elapsedMs"]) - int(a["on"]["elapsedMs"]),
                        int(a["off"]["hard"]) - int(a["on"]["hard"])))
    return out


def noise(rows, metric):
    by = {}
    for r in rows:
        by.setdefault((r["_file"], r["fixture"], int(r["seed"]), r["arm"]), []).append(float(r[metric]))
    spreads = {"off": [], "on": []}
    ranges = {"off": 0.0, "on": 0.0}
    for (f, fx, seed, arm), vals in by.items():
        if len(vals) >= 2:
            spreads[arm].append(st.pvariance(vals) * len(vals) / (len(vals) - 1))   # 標本分散
            ranges[arm] = max(ranges[arm], max(vals) - min(vals))
    pooled = {arm: (math.sqrt(st.mean(v)) if v else None) for arm, v in spreads.items()}
    return pooled, ranges, {arm: len(v) for arm, v in spreads.items()}


def sign_test_p(pos, neg):
    n = pos + neg
    if n == 0:
        return 1.0
    k = min(pos, neg)
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / 2 ** n
    return min(1.0, 2 * tail)


def bootstrap_ci(ds, boot, seed=20261008):
    rng = random.Random(seed)
    n = len(ds)
    means = sorted(st.mean(rng.choice(ds) for _ in range(n)) for _ in range(boot))
    return means[int(0.025 * boot)], means[int(0.975 * boot) - 1]


def main(argv):
    metric = "weightedScore"
    boot = 10000
    paths = []
    it = iter(argv)
    for a in it:
        if a == "--metric":
            metric = next(it)
        elif a == "--boot":
            boot = int(next(it))
        else:
            paths.append(a)
    if not paths:
        print(__doc__)
        return 2
    rows = load(paths)
    ps = pairs(rows, metric)
    if not ps:
        print("対が無い（off と on がそろった fixture・seed・rep が必要）")
        return 1
    ds = [d for _, d, _, _ in ps]
    pos = sum(1 for d in ds if d > 0)
    neg = sum(1 for d in ds if d < 0)
    ties = len(ds) - pos - neg
    mean = st.mean(ds)
    sd = st.stdev(ds) if len(ds) > 1 else float("nan")
    lo, hi = bootstrap_ci(ds, boot)
    pooled, ranges, nrep = noise(rows, metric)
    hard_diff = sum(1 for _, _, _, dh in ps if dh != 0)
    time_mean = st.mean(dt for _, _, dt, _ in ps) / 1000.0

    print(f"指標={metric}  対={len(ds)}  差=off−on（正＝既定が悪い）")
    print(f"  平均 {mean:+.1f}  中央 {st.median(ds):+.1f}  SD {sd:.1f}  範囲 {min(ds):+.0f}〜{max(ds):+.0f}")
    print(f"  既定が悪い {pos}  良い {neg}  同点 {ties}  符号検定 p={sign_test_p(pos, neg):.3f}")
    print(f"  平均差のブートストラップ 95% 区間 [{lo:+.1f}, {hi:+.1f}]  時間差の平均 {time_mean:+.1f} s  hard が違う対 {hard_diff}")
    for arm in ("off", "on"):
        if pooled[arm] is not None:
            print(f"  揺れ（{arm}, 同一 seed の rep 間）: プール SD {pooled[arm]:.1f}  最大範囲 {ranges[arm]:.0f}  （seed 数 {nrep[arm]}）")
    MIN_PAIRS = 8   # 対が少ないとパーセンタイル・ブートストラップの区間は当てにならない（5 対で区間の端が −1.0 だった例、2026-10-08）
    if len(ds) < MIN_PAIRS:
        verdict = f"未決: 対が {len(ds)} と少ない（{MIN_PAIRS} 未満は判定しない。同じ seed の反復か seed の追加で対を増やす）"
    elif lo > 0:
        verdict = "決定: 既定が悪い（区間が 0 より上）"
    elif hi < 0:
        verdict = "決定: 既定が良い（区間が 0 より下）"
    else:
        need = (1.96 * sd / abs(mean)) ** 2 if mean != 0 and sd == sd else float("inf")
        verdict = f"未決: 区間が 0 を含む。いまの平均差と SD なら、区間を 0 から外すには約 {math.ceil(need) if need != float('inf') else '∞'} 対が要る"
    print("  判定: " + verdict)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
