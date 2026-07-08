#!/usr/bin/env python3
"""MAGI design lint — melta-ui 流「壊れたら気づくハーネス」（docs/DESIGN.md の禁止事項 P1-P4）。

一次ソース（MainActivity.MagiTheme / MagiTokens.kt）に集約されたトークンからの逸脱を静的検査する。
Compose/Kotlin をコンパイルせずに grep 相当で検出（サンドボックスでも走る）。advisory=既定は非 fatal。

使い方:
    python3 tools/design_lint.py            # 報告のみ（exit 0）
    python3 tools/design_lint.py --strict   # 違反があれば exit 1（CI で fail させたいとき）

検査:
    P1 純黒本文/背景     : ui/*.kt の Color(0xFF000000) / Color.Black（UD の MainActivity は対象外）
    P2 生 hex の散布      : ui/*.kt（MagiTokens.kt 除く）の Color(0x……) 直書き（baseline 監視）
    P3 重い影            : .shadow( / shadowElevation の使用
    P4 任意角丸          : ui/*.kt の RoundedCornerShape(<dp>) 直書き（pill=999/CircleShape は除外）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI_DIR = os.path.join(ROOT, "app/src/main/java/com/magi/app/ui")

RE_BLACK = re.compile(r"Color\(0xFF000000\)|Color\.Black")
RE_HEX = re.compile(r"Color\(0x[0-9A-Fa-f]{8}\)")
RE_SHADOW_MODIFIER = re.compile(r"\.shadow\(")
RE_SHADOW_ELEV = re.compile(r"shadowElevation\s*=\s*(\d+)\s*\.dp")
RE_SHAPE = re.compile(r"RoundedCornerShape\(\s*(\d+)\s*\.dp")
HEAVY_SHADOW_DP = 4  # 4dp 以上を「重い影」とみなす（軽い1-2dp の区切り影は許容）


def ui_files():
    if not os.path.isdir(UI_DIR):
        return []
    return sorted(os.path.join(UI_DIR, f) for f in os.listdir(UI_DIR) if f.endswith(".kt"))


def scan():
    findings = {"P1": [], "P2": [], "P3": [], "P4": []}
    for path in ui_files():
        rel = os.path.relpath(path, ROOT)
        # MagiTokens.kt は一次トークン層。ensureReadable の黒/白フォールバックは意図的なので P1/P2 の対象外。
        is_tokens = os.path.basename(path) == "MagiTokens.kt"
        with open(path, encoding="utf-8") as fh:
            for n, line in enumerate(fh, 1):
                code = line.split("//", 1)[0]  # コメントは無視
                if not is_tokens and RE_BLACK.search(code):
                    findings["P1"].append(f"{rel}:{n}")
                if not is_tokens and RE_HEX.search(code):
                    findings["P2"].append(f"{rel}:{n}")
                if RE_SHADOW_MODIFIER.search(code):
                    findings["P3"].append(f"{rel}:{n} (.shadow)")
                for m in RE_SHADOW_ELEV.finditer(code):
                    if int(m.group(1)) >= HEAVY_SHADOW_DP:
                        findings["P3"].append(f"{rel}:{n} (shadowElevation {m.group(1)}dp)")
                for m in RE_SHAPE.finditer(code):
                    dp = int(m.group(1))
                    if dp != 999:  # pill(999)/CircleShape は意図的
                        findings["P4"].append(f"{rel}:{n} ({dp}dp)")
    return findings


def main():
    strict = "--strict" in sys.argv
    findings = scan()
    labels = {
        "P1": "純黒本文/背景 (Color.Black / 0xFF000000)",
        "P2": "生 hex 直書き (Color(0x……)) ※MagiTokens.kt 除く=baseline監視",
        "P3": "重い影 (.shadow / shadowElevation)",
        "P4": "任意角丸 (RoundedCornerShape(<dp>)) ※999=pill 除外",
    }
    total = sum(len(v) for v in findings.values())
    print("=== MAGI design lint (docs/DESIGN.md P1-P4) ===")
    for key in ("P1", "P2", "P3", "P4"):
        hits = findings[key]
        print(f"\n[{key}] {labels[key]}: {len(hits)} 件")
        for h in hits[:40]:
            print(f"    {h}")
        if len(hits) > 40:
            print(f"    …ほか {len(hits) - 40} 件")
    hard = len(findings["P1"]) + len(findings["P3"])
    print(f"\n合計 {total} 件（P1純黒+P3影=hard {hard} 件 / P2生hex・P4角丸=baseline監視）。")
    if strict and hard > 0:
        print("--strict: P1/P3 の hard 違反があるため exit 1")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
