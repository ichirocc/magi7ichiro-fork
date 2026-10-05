import csv,sys,math,collections
rows=list(csv.DictReader(open(sys.argv[1]))); A=sys.argv[2]; B=sys.argv[3] if len(sys.argv)>3 else 'off'
d={}
for r in rows: d[(r['fixture'],r['budget'],r['seed'],r['arm'])]=r
def key(r): return (int(r['hard']),float(r['weightedScore']),int(r['total']))
def summ(filt,label):
    w=l=t=0; dc1=0; hi=0; dw=[]; wall=[]; lost=[]
    for (f,b,s,a),r in d.items():
        if a!=A or not filt(f,b): continue
        o=d.get((f,b,s,B)); 
        if not o: continue
        ka,kb=key(r),key(o)
        if ka<kb: w+=1
        elif ka>kb: l+=1; lost.append((f,b,s,r['weightedScore'],o['weightedScore'],r['c1'],o['c1']))
        else: t+=1
        dc1+=int(r['c1'])-int(o['c1']); hi+= int(r['hard'])>int(o['hard'])
        ow=float(o['weightedScore']); dw.append((float(r['weightedScore'])-ow)/ow*100 if ow else float(r['weightedScore'])-ow)
        wall.append((int(r['wallMs'])-int(o['wallMs']))/1000)
    n=w+l
    p=min(1,2*sum(math.comb(n,k) for k in range(0,min(w,l)+1))/2**n) if n else 1
    wall.sort()
    print(f"{label}: {w}/{l}/{t} p={p:.3f} dW%={sum(dw)/max(1,len(dw)):+.2f} c1={dc1:+d} HARD増={hi} 時間中央値={wall[len(wall)//2] if wall else 0:+.2f}s")
    return lost
lost=summ(lambda f,b:True,'全体')
for f in sorted({k[0] for k in d}): summ(lambda ff,b,f=f:ff==f,f)
for b in ['60','120']: summ(lambda f,bb,b=b:bb==b,b+'s')
print('負け:',lost)
