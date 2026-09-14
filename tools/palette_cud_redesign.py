#!/usr/bin/env python3
"""[3.543.0/ユーザー指示] ColorPickerDialog の色パレットを 7家族×6色=42色へ再設計する生成スクリプト。

家族: 背景色系／早番／日勤／時短パート／遅番／夜勤（シフトの「イメージ色」6家族）＋
違反/アクセント（必須違反・要調整の既定色＋MagiAccentの残り4色）。既定色・MagiAccent 7色は
固定アンカーとして家族に振り分け済み（blue→早番・green→日勤・orange→遅番、残り4色
（紫・桃・赤・灰）は違反/アクセント家族が丸ごと保持＝ユーザー承認案のとおり）。

各家族はヒュー帯を固定し「イメージ」の統一感を保ちつつ、家族内・家族をまたいだ全ペアで
P型/D型二色覚シミュレーション後の最小 ΔE（tools/cud_colors.py）を目的関数にヒルクライム
局所探索で配置する。一度だけ実行して結果を ShiftColorEditor.kt へ手で転記する（実行時に
毎回計算するものではない＝設計時ツール）。
"""
import random
import sys
import colorsys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cud_colors as cc

random.seed(20260914)

# 固定アンカー（既定色＋MagiAccent）。実際のヒュー値で家族を決める（MagiAccent自身の旧用途ラベルは
# 別文脈で付けたものなので従わない＝green(hue≈148°)は「日勤」でなく「時短パート」のヒュー帯に入る）。
# (family_index, hex, label)
ANCHORS = {
    ("early", 0): ("#3b6fd4", "MagiAccent.blue"),   # hue≈220° early帯
    ("short", 0): ("#2e9e62", "MagiAccent.green"),  # hue≈148° short帯
    ("late", 0): ("#e08a1e", "MagiAccent.orange"),  # hue≈33° late帯
    ("vio", 0): ("#b71c1c", "必須違反(既定)"),
    ("vio", 1): ("#f59e0b", "要調整(既定)"),
    ("vio", 2): ("#8a5cd1", "MagiAccent.purple"),
    ("vio", 3): ("#d24d89", "MagiAccent.pink"),
    ("vio", 4): ("#d23b34", "MagiAccent.red"),
    ("vio", 5): ("#8a979b", "MagiAccent.gray"),
}

# 家族ごとのヒュー帯(度)・彩度/明度レンジ(HSL, 0-1)。「イメージ」を保つための制約。
# [ユーザー指示「各色のコントラストは見やすくする」] 明度上限を白背景(ダイアログ面)から浮く
# 範囲(概ね0.82以下)に抑え、下限も文字が読めない黒つぶれ(0.30未満)を避ける。中間帯を厚めにする。
MIN_BG_CONTRAST = 1.6  # 白(#ffffff)背景に対する WCAG コントラスト比の下限(淡すぎる色を弾く)
# [P型/D型対策] day/short/late(黄・緑・橙)は赤緑色覚異常で互いに一番衝突しやすいヒュー帯＝
# ヒューだけでは分離しきれない。3家族を明度で棲み分けさせる(late=暗い赤褐色 < short=中間の緑 <
# day=明るい黄金)ことで、ヒューが潰れても明度差が残る二重の手がかりにする。
FAMILIES = {
    # 背景色系: 低彩度の中立色(暖色/寒色どちらも可)。
    "bg": {"hue": (0, 360), "sat": (0.03, 0.18), "light": (0.55, 0.80)},
    "early": {"hue": (195, 235), "sat": (0.35, 0.75), "light": (0.42, 0.75)},   # 早番=夜明けの空(青)
    "night": {"hue": (255, 292), "sat": (0.30, 0.65), "light": (0.28, 0.54)},   # 夜勤=夜(紫紺)
    "day": {"hue": (46, 66), "sat": (0.45, 0.85), "light": (0.60, 0.80)},       # 日勤=陽光(黄金・明)
    "short": {"hue": (108, 152), "sat": (0.28, 0.62), "light": (0.42, 0.58)},   # 時短パート=新緑(中)
    # [赤(必須違反)との衝突回避] 遅番のヒュー帯は赤(hue≈0-3°)から離す。上限0.85dpで暗すぎる赤褐色
    # (vio家族の必須違反#b71c1cに寄る)を避ける。
    "late": {"hue": (26, 46), "sat": (0.42, 0.78), "light": (0.30, 0.44)},      # 遅番=夕暮れ・琥珀(暗め)
    "vio": {"hue": (0, 360), "sat": (0.0, 1.0), "light": (0.0, 1.0)},           # 全スロット固定なので未使用
}


def wcag_relative_luminance(hex_color):
    r, g, b = cc.hex_to_rgb(hex_color)

    def chan(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    rl, gl, bl = chan(r), chan(g), chan(b)
    return 0.2126 * rl + 0.7152 * gl + 0.0722 * bl


def contrast_vs_white(hex_color):
    lum = wcag_relative_luminance(hex_color)
    return (1.0 + 0.05) / (lum + 0.05)


FAMILY_ORDER = ["bg", "early", "day", "short", "late", "night", "vio"]
N_PER_FAMILY = 6


def hsl_to_hex(h, s, light):
    r, g, b = colorsys.hls_to_rgb((h % 360) / 360.0, light, s)
    return "#%02x%02x%02x" % (round(r * 255), round(g * 255), round(b * 255))


def random_in_family(fam):
    b = FAMILIES[fam]
    for _ in range(200):
        h = random.uniform(*b["hue"])
        s = random.uniform(*b["sat"])
        li = random.uniform(*b["light"])
        hexv = hsl_to_hex(h, s, li)
        if contrast_vs_white(hexv) >= MIN_BG_CONTRAST:
            return hexv
    return hexv  # 200回で見つからなければ最後の値で妥協(帯設定の問題を後段で気づけるように)


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def jitter_in_family(hex_color, fam, scale):
    b = FAMILIES[fam]
    r, g, bl = cc.hex_to_rgb(hex_color)
    h0, li0, s0 = colorsys.rgb_to_hls(r / 255.0, g / 255.0, bl / 255.0)
    h0 = h0 * 360.0
    for _ in range(50):
        h = clamp(h0 + random.uniform(-scale, scale) * 20, *b["hue"])
        s = clamp(s0 + random.uniform(-scale, scale) * 0.25, *b["sat"])
        li = clamp(li0 + random.uniform(-scale, scale) * 0.20, *b["light"])
        hexv = hsl_to_hex(h, s, li)
        if contrast_vs_white(hexv) >= MIN_BG_CONTRAST:
            return hexv
    return hex_color  # 見つからなければ据え置き(改悪でないので呼出元のヒルクライムが自然に却下しうる)


def build_slots():
    """(family, idx) -> hex|None のスロット表。アンカーは埋め、残りは None。"""
    slots = {}
    for fam in FAMILY_ORDER:
        for i in range(N_PER_FAMILY):
            key = (fam, i)
            slots[key] = ANCHORS[key][0] if key in ANCHORS else None
    return slots


def min_worst_case(all_hex):
    return cc.min_pairwise_worst_case(all_hex)


def optimize():
    slots = build_slots()
    keys = sorted(slots.keys(), key=lambda x: (FAMILY_ORDER.index(x[0]), x[1]))
    idx_of = {k: i for i, k in enumerate(keys)}
    free_keys = [k for k, v in slots.items() if v is None]
    for k in free_keys:
        slots[k] = random_in_family(k[0])
    n = len(keys)
    vals = [slots[k] for k in keys]

    # [速度] O(n^2) の全再計算を毎回避ける: n×n の距離行列を1回だけ作り、以後は変更した1列だけ更新する。
    dist = [[0.0] * n for _ in range(n)]
    for i in range(n):
        for j in range(i + 1, n):
            d = cc.worst_case_delta_e(vals[i], vals[j])
            dist[i][j] = dist[j][i] = d

    def global_min():
        best = float("inf")
        for i in range(n):
            for j in range(i + 1, n):
                if dist[i][j] < best:
                    best = dist[i][j]
        return best

    # [焼きなまし] 純粋なヒルクライムは day/short/late(赤緑色覚異常で衝突しやすい暖色帯)の局所解に
    # すぐ嵌まる。悪化を確率的に受理する閾値を反復とともに絞ることで局所解から脱出しやすくする。
    cur_score = global_min()
    best_score = cur_score
    best_vals = vals[:]
    ITERS = 12000
    for it in range(ITERS):
        k = random.choice(free_keys)
        ik = idx_of[k]
        old = vals[ik]
        old_row = dist[ik][:]
        progress = it / ITERS
        scale = max(0.05, 1.0 - progress)
        threshold = max(0.0, (1.0 - progress) ** 2 * 1.5)  # 序盤ほど悪化を許容
        cand = jitter_in_family(old, k[0], scale) if random.random() < 0.85 else random_in_family(k[0])
        vals[ik] = cand
        for j in range(n):
            if j == ik:
                continue
            d = cc.worst_case_delta_e(cand, vals[j])
            dist[ik][j] = dist[j][ik] = d
        score = global_min()
        if score >= cur_score - threshold:
            cur_score = score
            if score > best_score:
                best_score = score
                best_vals = vals[:]
        else:
            vals[ik] = old
            for j in range(n):
                dist[ik][j] = dist[j][ik] = old_row[j]
    for k in keys:
        slots[k] = best_vals[idx_of[k]]
    return slots, best_score


def main():
    best_slots, best_score = None, -1.0
    for restart in range(4):
        random.seed(20260914 + restart)
        slots, score = optimize()
        print(f"# restart {restart}: worst-case最小ΔE = {score:.2f}", file=sys.stderr)
        if score > best_score:
            best_score, best_slots = score, slots
    slots, score = best_slots, best_score
    print(f"# 最終 worst-case 最小 ΔE = {score:.2f}\n")
    for fam in FAMILY_ORDER:
        row = [slots[(fam, i)] for i in range(N_PER_FAMILY)]
        print(f"# {fam}: " + " ".join(row))
    flat = [slots[(fam, i)] for fam in FAMILY_ORDER for i in range(N_PER_FAMILY)]
    print("\nCOLOR_PALETTE = listOf(")
    for i in range(0, len(flat), 6):
        chunk = flat[i:i + 6]
        print("    " + ", ".join(f'"{c}"' for c in chunk) + ",")
    print(")")
    worst, pair = min_worst_case(flat)
    print(f"\n# worst pair: {flat[pair[0]]} x {flat[pair[1]]}  ΔE={worst:.2f}")


if __name__ == "__main__":
    main()
