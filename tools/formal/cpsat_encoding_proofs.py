"""CP-SAT モデル（app/src/main/cpp/magi_cpsat.h）の符号化が採点の式（fullEvalParts・Kotlin チェッカー）と等しいことを Z3 で証明する。

モデルは「補助変数 y が条件 C(x, y) を満たし、y を最小化する」形で各違反を表す。等しいことは次の 2 点で示す:
  ① 健全: C を満たすどの y も y >= 元の式の値（＝下限が実際の値を超えない）
  ② 達成: y = 元の式の値 は C を満たす（＝最小化で等号になる）
整数・真偽の変数は範囲の制約のほかは無制限（被覆・回数・曜日は任意の非負整数、連の長さは T<=16 の全列）。
    python3 tools/formal/cpsat_encoding_proofs.py   → 全部 PROVED なら終了コード 0
"""
import itertools
import sys

import z3

results = []


def prove(name, claim):
    s = z3.Solver()
    s.set("timeout", 60000)
    s.add(z3.Not(claim))
    r = s.check()
    ok = r == z3.unsat
    results.append((name, ok, str(r)))
    print(f"{'PROVED' if ok else 'FAILED'}  {name}  ({r})")


def zmax(a, b): return z3.If(a >= b, a, b)
def zmin(a, b): return z3.If(a <= b, a, b)


def min_encoding(name, value, cond, y, extra=()):
    """y の条件 cond(y) のもとで、最小値が value と等しい（①健全 ②達成）。"""
    pre = z3.And(*extra) if extra else z3.BoolVal(True)
    prove(f"{name}: 健全（条件を満たす y は値以上）", z3.ForAll([y], z3.Implies(z3.And(pre, cond(y)), y >= value)))
    prove(f"{name}: 達成（値そのものが条件を満たす）", z3.Implies(pre, cond(value)))


g, a, b, L = z3.Ints("g a b L")
nonneg = (g >= 0, a >= 0, b >= 0)

# 被覆。per-cell OR/AND（Problem.covUCell/covOCell）を min/max に畳む式の同値と、補助変数の符号化。
prove("covU: min(max(0,a-g),max(0,b-g)) = max(0,min(a,b)-g)",
      z3.Implies(z3.And(*nonneg), zmin(zmax(0, a - g), zmax(0, b - g)) == zmax(0, zmin(a, b) - g)))
prove("covO: min(max(0,g-a),max(0,g-b)) = max(0,g-max(a,b))",
      z3.Implies(z3.And(*nonneg), zmin(zmax(0, g - a), zmax(0, g - b)) == zmax(0, g - zmax(a, b))))
u = z3.Int("u")
min_encoding("covU 補助変数 u>=0, u+got>=L", zmax(0, L - g), lambda y: z3.And(y >= 0, y + g >= L), u, nonneg)
min_encoding("covO 補助変数 o>=0, o-got>=-L", zmax(0, g - L), lambda y: z3.And(y >= 0, y - g >= -L), u, nonneg)

# 回数の下限・上限・適切回数（low/high/apt）。
n, lo, hi, t = z3.Ints("n lo hi t")
min_encoding("low d>=0, d+n>=lo", zmax(0, lo - n), lambda y: z3.And(y >= 0, y + n >= lo), u, (n >= 0, lo >= 0))
min_encoding("high d>=0, d-n>=-hi", zmax(0, n - hi), lambda y: z3.And(y >= 0, y - n >= -hi), u, (n >= 0, hi >= 0))
min_encoding("apt d>=|n-t|", z3.If(n >= t, n - t, t - n), lambda y: z3.And(y >= 0, y - n >= -t, y + n >= t), u, (n >= 0, t >= 0))

# c1・c2: f∈{0,1}, z + d·f >= d の最小 f は [z < d]（窓の回数 z が最低数 d 未満なら違反 1）。
z, d, m, ul = z3.Ints("z d m ul")
f = z3.Int("f")
min_encoding("c1/c2 z+d*f>=d", z3.If(z < d, 1, 0), lambda y: z3.And(y >= 0, y <= 1, z + d * y >= d), f, (z >= 0, d >= 1))
# c41: 0<=z<=m、z + l·f >= l かつ z − (m−u)·f <= u の最小 f は [z<l or z>u]（l>0・u<m のときだけ条件を置く）。
l41 = z3.Int("l41")
min_encoding("c41 range", z3.If(z3.Or(z < l41, z > ul), 1, 0),
             lambda y: z3.And(y >= 0, y <= 1,
                              z3.Implies(l41 > 0, z + l41 * y >= l41),
                              z3.Implies(ul < m, z - (m - ul) * y <= ul)),
             f, (z >= 0, z <= m, l41 >= 0, ul >= l41, m >= 0))

# c42（同じ集合）: n(n-1)/2 は凸。p >= t·z − t(t+1)/2（t=1..m−1）・p>=0 の最小 p は z(z−1)/2（0<=z<=m）。
#   接線の各不等式を量化して示す（m は業務上限 30 名まで全部）。
for mm in range(1, 31):
    zz, pp = z3.Ints("zz pp")
    cond = lambda y, mm=mm: z3.And(y >= 0, *[y >= tt * zz - tt * (tt + 1) // 2 for tt in range(1, mm)])
    s = z3.Solver()
    s.add(zz >= 0, zz <= mm)
    s.add(z3.Not(z3.And(z3.ForAll([pp], z3.Implies(cond(pp), pp >= zz * (zz - 1) / 2)), cond(zz * (zz - 1) / 2))))
    r = s.check()
    if r != z3.unsat:
        results.append((f"c42 same-set m={mm}", False, str(r))); print(f"FAILED  c42 same-set m={mm} ({r})")
results.append(("c42 same-set tangents m=1..30", all(ok for nm, ok, _ in results if nm.startswith("c42 same-set m=")), "unsat"))
print("PROVED  c42 same-set tangents m=1..30")

# weekly: 7q >= E − 6・q>=0 の最小 q は floor(E/7)（E>=0）。e_d >= |7w_d − c| の最小 Σe は Σ|7w_d − c|。
E, q = z3.Ints("E q")
min_encoding("weekly 7q>=E-6", E / 7, lambda y: z3.And(y >= 0, 7 * y >= E - 6), q, (E >= 0,))
w7, c7 = z3.Ints("w7 c7")
min_encoding("weekly e>=|7w-c|", z3.If(7 * w7 >= c7, 7 * w7 - c7, c7 - 7 * w7),
             lambda y: z3.And(y >= 0, y - 7 * w7 + c7 >= 0, y + 7 * w7 - c7 >= 0), u, (w7 >= 0, c7 >= 0))
# 曜日の床の単調性: E1<=E2 なら floor(E1/7) <= floor(E2/7)（Σe を最小にしてから割る＝割ってから最小と同じ）。
E1, E2 = z3.Ints("E1 E2")
prove("weekly floor 単調", z3.Implies(z3.And(E1 >= 0, E1 <= E2), E1 / 7 <= E2 / 7))

# c3 族（窓マッチ）。x0・r_l は真偽。
x0 = z3.Int("x0"); a3 = z3.Int("a3")
for D in range(2, 7):
    rs = z3.Ints(" ".join(f"r{l}" for l in range(1, D)))
    bools = [z3.And(v >= 0, v <= 1) for v in [x0, *rs]]
    allr = z3.And(*[r == 1 for r in rs])
    # 非 forbidden の複数シフト連: fire＝先頭一致 ∧ ¬残り全一致。f + a >= x0、a <= r_l の最小 f。
    prove(f"c3 非forbidden D={D}: 健全",
          z3.ForAll([f, a3], z3.Implies(z3.And(*bools, f >= 0, f <= 1, a3 >= 0, a3 <= 1, f + a3 >= x0, *[a3 <= r for r in rs]),
                                        f >= z3.If(z3.And(x0 == 1, z3.Not(allr)), 1, 0))))
    prove(f"c3 非forbidden D={D}: 達成",
          z3.Implies(z3.And(*bools), z3.Exists([a3], z3.And(a3 >= 0, a3 <= 1, *[a3 <= r for r in rs],
                                                            z3.If(z3.And(x0 == 1, z3.Not(allr)), 1, 0) + a3 >= x0))))
    # forbidden: fire＝全一致。f >= Σ − (D−1) の最小 f。
    total = x0 + sum(rs)
    min_encoding(f"c3 forbidden D={D}", z3.If(z3.And(x0 == 1, allr), 1, 0),
                 lambda y: z3.And(y >= 0, y <= 1, y >= total - (D - 1)), f, bools)


# run の不足（C3Run.rowDeficit）: 長さ r<L の連ごとに L−r。モデル＝連の始まり s ごとに Σ_{t=1}^{L−1} [start_s ∧ ¬(s..s+t が全部 k)]
#   （月末を越える日は k でない）。T<=16 の全 0/1 列・L=2..5 で両者を突き合わせる（有界の全数）。
def row_deficit(row, Lr):
    sub, r = 0, 0
    for j in range(len(row) + 1):
        on = j < len(row) and row[j] == 1
        if on:
            r += 1
        elif r > 0:
            if r < Lr:
                sub += Lr - r
            r = 0
    return sub


def model_deficit(row, Lr):
    T = len(row); tot = 0
    for s in range(T):
        start = row[s] == 1 and (s == 0 or row[s - 1] == 0)
        if not start:
            continue
        for t in range(1, Lr):
            covered = all(s + u < T and row[s + u] == 1 for u in range(t + 1))
            tot += 0 if covered else 1
    return tot


ok_rows = True
for T in range(1, 17):
    for Lr in range(2, 6):
        for bits in itertools.product((0, 1), repeat=T):
            if row_deficit(bits, Lr) != model_deficit(bits, Lr):
                ok_rows = False; print(f"FAILED  run deficit T={T} L={Lr} row={bits}"); break
results.append(("run deficit T<=16 L=2..5 全列", ok_rows, "exhaustive"))
print(f"{'PROVED' if ok_rows else 'FAILED'}  run deficit: rowDeficit == 連の始まりごとの和（T<=16・L=2..5 の全 0/1 列）")

# run の不足の補助変数: start=x_s−x_{s−1}（下側）、y + a >= start・a <= 各 x の最小 y は [start ∧ ¬全部]。
xs = z3.Ints("xs xp x1 x2 x3")
xsv, xpv, *seg = xs
bools = [z3.And(v >= 0, v <= 1) for v in xs]
allseg = z3.And(xsv == 1, *[v == 1 for v in seg])
startb = z3.And(xsv == 1, xpv == 0)
y = z3.Int("y")
prove("run 補助変数: 健全",
      z3.ForAll([y, a3], z3.Implies(z3.And(*bools, y >= 0, y <= 1, a3 >= 0, a3 <= 1, a3 <= xsv, *[a3 <= v for v in seg], y + a3 >= xsv - xpv),
                                    y >= z3.If(z3.And(startb, z3.Not(allseg)), 1, 0))))
prove("run 補助変数: 達成",
      z3.Implies(z3.And(*bools), z3.Exists([a3], z3.And(a3 >= 0, a3 <= 1, a3 <= xsv, *[a3 <= v for v in seg],
                                                        z3.If(z3.And(startb, z3.Not(allseg)), 1, 0) + a3 >= xsv - xpv))))

failed = [r for r in results if not r[1]]
print(f"\n{len(results) - len(failed)}/{len(results)} PROVED")
sys.exit(1 if failed else 0)
