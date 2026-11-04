#!/usr/bin/env python3
"""シーソーの地図（docs/seesaw.md §6.1 の S1）の腕を作る（測定用）。作業ツリー ROOT の Kotlin で、族 FAM の重みだけを ×MUL にする。
  python3 tools/loop/seesaw_arm.py <ROOT> <族> [倍率=2]
直すのは重みが直接書かれた 3 か所（MirrorKeys.weights・Evaluator.fullEvalParts・DeltaEvaluator の集約）。ほかは MirrorKeys.weightOf か
PenaltyWeights を読むので追従する。ホスト JVM の測定では C++ を使わないので C++ は直さない（製品のビルドには使わないこと）。"""
import re, sys

root, fam = sys.argv[1], sys.argv[2]
mul = int(sys.argv[3]) if len(sys.argv) > 3 else 2
v6 = f"{root}/app/src/main/java/com/magi/app/v6"
W = {"c1": 90, "high": 50, "c3m": 6, "c3mn": 25, "fair": 5, "apt": 4, "c3": 15, "c2": 4, "c41": 9, "c42": 9, "c41s": 10, "c42s": 10,
     "covO": 10, "low": 120, "weekly": 2}
w = W[fam]; nw = w * mul
# (ファイル, 旧, 新)。旧は 1 回だけ現れること。
edits = [("MirrorCore.kt", f'"{fam}" to {w}.0', f'"{fam}" to {nw}.0')]
ev = {"high": ("rawHigh * 50L", f"rawHigh * {nw}L"), "low": ("rawLow * 120L", f"rawLow * {nw}L"), "apt": ("rawApt * 4L", f"rawApt * {nw}L")}
if fam in ev:
    edits.append(("Evaluator.kt",) + ev[fam])
else:
    edits.append(("Evaluator.kt", f'raw * {w}L; record("{fam}"', f'raw * {nw}L; record("{fam}"'))
if fam == "high":
    edits.append(("DeltaEvaluator.kt", "(n - hi).toLong() * 50L", f"(n - hi).toLong() * {nw}L"))
elif fam == "low":
    edits.append(("DeltaEvaluator.kt", "(lo - n).toLong() * 120L", f"(lo - n).toLong() * {nw}L"))
else:
    edits.append(("DeltaEvaluator.kt", f"{fam} * {w} ", f"{fam} * {nw} "))
for fn, old, new in edits:
    p = f"{v6}/{fn}"; s = open(p, encoding="utf-8").read()
    if fn == "DeltaEvaluator.kt" and fam not in ("high", "low"):
        # 集約式 `c1 * 90 + …` の族名は単語の境界で合わせる（c3 と c3m・c3mn を取り違えない）
        pat = re.compile(rf"(?<![A-Za-z0-9]){re.escape(fam)} \* {w}(?=[ \n])")
        n = len(pat.findall(s))
        assert n == 1, (fn, fam, n)
        s = pat.sub(f"{fam} * {nw}", s)
    else:
        assert s.count(old) == 1, (fn, old, s.count(old))
        s = s.replace(old, new)
    open(p, "w", encoding="utf-8").write(s)
print(f"{fam}: {w} -> {nw}（{len(edits)} か所）")
