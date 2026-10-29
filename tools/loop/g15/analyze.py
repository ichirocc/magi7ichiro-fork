import csv, statistics as st, math, sys
rows = list(csv.DictReader(open(sys.argv[1])))
by = {}
for r in rows:
    if not r.get('elapsedMs'): continue
    by.setdefault((r['fixture'], r['seed']), {})[r['arm']] = r
pairs = [v for v in by.values() if 'base' in v and 'new' in v]
dw = [float(p['new']['weighted']) - float(p['base']['weighted']) for p in pairs]
dh = [int(p['new']['hard']) - int(p['base']['hard']) for p in pairs]
eb = [int(p['base']['elapsedMs']) for p in pairs]; en = [int(p['new']['elapsedMs']) for p in pairs]
n = len(dw); m = st.mean(dw); sd = st.stdev(dw) if n > 1 else 0
t = 2.045 if n >= 30 else 2.1
hi = m + t * sd / math.sqrt(n); lo = m - t * sd / math.sqrt(n)
print(f"pairs={n}")
print(f"hard worse pairs={sum(1 for d in dh if d > 0)} better={sum(1 for d in dh if d < 0)}")
print(f"weighted diff mean={m:.1f} 95%CI=[{lo:.1f},{hi:.1f}]  (new-base; <=0 good)")
mb, mn = st.median(eb), st.median(en)
print(f"elapsed median base={mb/1000:.1f}s new={mn/1000:.1f}s diff={(mn-mb)/1000:+.1f}s ({(mn/mb-1)*100:+.1f}%)  limit +2% and +1s")
for f in sorted({k[0] for k in by}):
    ps = [v for k, v in by.items() if k[0] == f and 'base' in v and 'new' in v]
    print(f"  {f}: w base={st.mean(float(p['base']['weighted']) for p in ps):.0f} new={st.mean(float(p['new']['weighted']) for p in ps):.0f}  t base={st.median(int(p['base']['elapsedMs']) for p in ps)/1000:.1f}s new={st.median(int(p['new']['elapsedMs']) for p in ps)/1000:.1f}s")
