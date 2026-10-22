#!/usr/bin/env python3
"""versionCode / versionName の採番検査（backlog U-04）。

この PR が版を変えていなければ（merge-base と同じ）検査しない。変えていれば main の先端と比べる
（並行する PR が先に同じ番号でマージされた場合も落とすため、merge-base ではなく先端）。
- versionName が変わったのに versionCode が増えていない → 失敗（並行セッションの重複採番）
- versionCode が減った・versionName が同じなのに versionCode だけ変わった → 失敗
- どちらも変わっていない → 成功（版を上げない docs/tools だけの変更）
"""
import re
import subprocess
import sys

PATH = "app/build.gradle.kts"


def parse(text):
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        sys.exit(f"{PATH}: versionCode/versionName が見つかりません")
    return int(code.group(1)), name.group(1)


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def main():
    base_ref = sys.argv[1] if len(sys.argv) > 1 else "origin/main"
    base = git("merge-base", "HEAD", base_ref).strip()
    if base == git("rev-parse", "HEAD").strip():
        print("HEAD は基準と同じ。検査なし")
        return
    new_code, new_name = parse(open(PATH, encoding="utf-8").read())
    if (new_code, new_name) == parse(git("show", f"{base}:{PATH}")):
        print("この変更は版を変えていない。検査なし")
        return
    old_code, old_name = parse(git("show", f"{base_ref}:{PATH}"))
    print(f"基準 {old_code}/{old_name} → HEAD {new_code}/{new_name}")
    if new_code < old_code:
        sys.exit("versionCode が減っています")
    if new_name != old_name and new_code <= old_code:
        sys.exit("versionName を変えたのに versionCode が増えていません（重複採番）")
    if new_name == old_name and new_code != old_code:
        sys.exit("versionName が同じなのに versionCode だけ変わっています")
    print("OK")


if __name__ == "__main__":
    main()
