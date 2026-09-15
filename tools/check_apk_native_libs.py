#!/usr/bin/env python3
"""APK 内のネイティブ .so が「非圧縮（STORED）」かつ「データ先頭が 16KiB 境界」かを検査する。

Android 16 の 16KB ページ端末は .so を APK から直接 mmap するため、ELF 側の max-page-size=16384
（CMakeLists.txt）だけでなく APK 内エントリの整列も要る。整列は AGP/zipalign 任せで、これまで未検証だった。
データ先頭 = ローカルヘッダ位置 + 30 + ファイル名長 + **ローカルヘッダの** extra 長（中央ディレクトリの
extra 長とは一致しないので zipfile の値は使わずローカルヘッダを読む）。
使い方: python3 tools/check_apk_native_libs.py <apk> [<apk>...]   失敗があれば非ゼロ終了。
"""
import struct
import sys
import zipfile

REQUIRED = ["lib/arm64-v8a/libmagi_native.so"]
ALIGN = 16384
LOCAL_HEADER_SIG = 0x04034B50
LOCAL_HEADER_LEN = 30


def data_offset(fp, info):
    fp.seek(info.header_offset)
    head = fp.read(LOCAL_HEADER_LEN)
    sig, _v, _f, _m, _t, _d, _crc, _cs, _us, name_len, extra_len = struct.unpack("<IHHHHHIIIHH", head)
    if sig != LOCAL_HEADER_SIG:
        raise ValueError(f"{info.filename}: local header signature mismatch (0x{sig:08x})")
    return info.header_offset + LOCAL_HEADER_LEN + name_len + extra_len


def check(path):
    problems = []
    seen = set()
    with zipfile.ZipFile(path) as z, open(path, "rb") as fp:
        for info in z.infolist():
            if not (info.filename.startswith("lib/") and info.filename.endswith(".so")):
                continue
            seen.add(info.filename)
            off = data_offset(fp, info)
            stored = info.compress_type == zipfile.ZIP_STORED
            aligned = off % ALIGN == 0
            mark = "OK " if (stored and aligned) else "NG "
            print(f"  {mark}{info.filename}  stored={stored} offset={off} (mod {ALIGN} = {off % ALIGN})")
            if not stored:
                problems.append(f"{info.filename}: compressed (method={info.compress_type})")
            if not aligned:
                problems.append(f"{info.filename}: data offset {off} not {ALIGN}-aligned")
    for req in REQUIRED:
        if req not in seen:
            problems.append(f"{req}: missing from APK")
    return problems


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    rc = 0
    for path in argv[1:]:
        print(f"== {path}")
        problems = check(path)
        for p in problems:
            print(f"  ERROR {p}")
        if problems:
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv))
