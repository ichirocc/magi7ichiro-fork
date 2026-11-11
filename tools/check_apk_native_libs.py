#!/usr/bin/env python3
"""APK 内のネイティブ .so が「非圧縮（STORED）」かつ「データ先頭が 16KiB 境界」かを検査する。

Android 16 の 16KB ページ端末は .so を APK から直接 mmap するため、ELF 側の max-page-size=16384
（CMakeLists.txt）だけでなく APK 内エントリの整列も要る。整列は AGP/zipalign 任せで、これまで未検証だった。
データ先頭 = ローカルヘッダ位置 + 30 + ファイル名長 + **ローカルヘッダの** extra 長（中央ディレクトリの
extra 長とは一致しないので zipfile の値は使わずローカルヘッダを読む）。
あわせて .so が要求するライブラリ（ELF の DT_NEEDED）が端末に元からあるものだけかも見る。APK に入るのは libmagi_native.so
だけなので、ほかの .so（例: OR-Tools の依存物を共有で組んだ libabsl_*.so・libprotobuf.so）を要求すると端末で読み込みに
失敗し、ネイティブ加速も CP-SAT も黙って無効になる（3.727.51 の実機ログ「.so 未ロード」）。
使い方: python3 tools/check_apk_native_libs.py <apk> [<apk>...]   失敗があれば非ゼロ終了。
"""
import struct
import sys
import zipfile

REQUIRED = ["lib/arm64-v8a/libmagi_native.so"]
ALIGN = 16384
LOCAL_HEADER_SIG = 0x04034B50
LOCAL_HEADER_LEN = 30
# 端末（NDK の安定 API）が持つライブラリ。APK に同梱していない .so はこれ以外を要求してはならない。
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so"}


def needed_libs(elf):
    """ELF64 リトルエンディアンの DT_NEEDED（要求するライブラリ名）を返す。"""
    if elf[:4] != b"\x7fELF" or elf[4] != 2 or elf[5] != 1:
        raise ValueError("not an ELF64 little-endian file")
    phoff, = struct.unpack_from("<Q", elf, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", elf, 0x36)
    loads, dyn = [], None
    for i in range(phnum):
        p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz = struct.unpack_from("<IIQQQQ", elf, phoff + i * phentsize)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dyn = (p_offset, p_filesz)
    if dyn is None:
        return []

    def to_offset(vaddr):
        for v, o, sz in loads:
            if v <= vaddr < v + sz:
                return vaddr - v + o
        raise ValueError(f"address 0x{vaddr:x} is not in a PT_LOAD segment")

    needed, strtab = [], None
    for off in range(dyn[0], dyn[0] + dyn[1], 16):
        tag, val = struct.unpack_from("<qQ", elf, off)
        if tag == 0:
            break
        if tag == 1:
            needed.append(val)
        elif tag == 5:
            strtab = to_offset(val)
    if strtab is None:
        return []
    return [elf[strtab + n:elf.index(b"\0", strtab + n)].decode() for n in needed]


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
            bundled = {n.rsplit("/", 1)[-1] for n in z.namelist() if n.startswith(info.filename.rsplit("/", 1)[0] + "/")}
            needed = needed_libs(z.read(info.filename))
            missing = [n for n in needed if n not in SYSTEM_LIBS and n not in bundled]
            print(f"     NEEDED={' '.join(needed)}")
            if missing:
                problems.append(f"{info.filename}: requires libraries not on the device nor in the APK: {' '.join(missing)}")
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
