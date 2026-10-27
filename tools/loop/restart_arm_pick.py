#!/usr/bin/env python3
"""RestartArmBench の集計の補助: off＝A（今の盤面から作り直す）、on＝「下書きから作り直し、今の盤面 R より良いときだけ採る」。

どちらも作り直し 1 回ぶんの時間。使い方: python3 tools/loop/restart_arm_pick.py <out.csv の拡張子なし>
→ <prefix>.rb.csv を書く（ho_stats.py で集計）。比較は必須→weighted→total の辞書式（betterReport と同じ）。
"""
import csv, sys

p = sys.argv[1]
base = {(r["fixture"], r["seed"]): r for r in csv.DictReader(open(p + ".base.csv", encoding="utf-8"))}
by = {}
for r in csv.DictReader(open(p + ".csv", encoding="utf-8")):
    by.setdefault((r["fixture"], r["seed"]), {})[r["arm"]] = r


def key(r):
    return (int(r["hard"]), float(r["weightedScore"]), int(r["total"]))


with open(p + ".rb.csv", "w", encoding="utf-8") as out:
    out.write("fixture,seed,arm,elapsedMs,hard,weightedScore,total,rep,hash\n")
    for k, a in by.items():
        R, A, B = base[k], a["off"], a["on"]
        pick = B if key(B) < key(R) else R
        out.write(",".join([k[0], k[1], "off", A["elapsedMs"], A["hard"], A["weightedScore"], A["total"], "1", A["hash"]]) + "\n")
        out.write(",".join([k[0], k[1], "on", B["elapsedMs"], pick["hard"], pick["weightedScore"], pick["total"], "1", pick["hash"]]) + "\n")
        print(k, "R", key(R), "A", key(A), "B", key(B), "採る", "B" if pick is B else "R")
