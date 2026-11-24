# 盤面の行（1 行 1 職員の JSON 配列）を state JSON の schedule に入れて書き出す。
import json, sys
state, rows, out = sys.argv[1], sys.argv[2], sys.argv[3]
d = json.load(open(state))
d["schedule"] = [json.loads(l) for l in open(rows) if l.strip()]
json.dump(d, open(out, "w"), ensure_ascii=False)
