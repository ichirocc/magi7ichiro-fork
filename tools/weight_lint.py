#!/usr/bin/env python3
"""MAGI weight lint — 制約ファミリー重みの単一の真実（`MirrorKeys.weights`）からの逸脱を検出する。

CLAUDE.md の規約: 20 の制約ファミリー重み（c1/c2/c3/c3n/…/weekly/c3w）は
`app/src/main/java/com/magi/app/v6/MirrorCore.kt` の `MirrorKeys.weights` にだけ定義し、他の
Kotlin コードは `MirrorKeys.weightOf(family)` / `MirrorKeys.weights[...]` 経由で参照する
（HF77＝重みの変更は業務担当者の明示数値指示＋1件ずつ、という運用は人間のレビューでしか
守られておらず、生の数値リテラルが評価器/比較器/探索コードに紛れ込んでも機械では検出できない
＝この lint はその穴を塞ぐ）。

検出対象: `app/src/main/java/com/magi/app/v6/*.kt`（`MirrorCore.kt` 自身とテストは除外）の中で、
`MirrorKeys.weights` の現在値のいずれかと**厳密に一致する**数値リテラルが、算術/比較/代入/
when分岐の文脈で使われている行。族の値は実行時に `MirrorCore.kt` をパースして取るので、
このスクリプト自身に古い値のコピーは持たない（重みを変えても lint が stale にならない）。
**ただし「新しい重みと同じ値が許可外の場所に増えた」ことは検出できても、「重みを変更したのに複製側の
更新を忘れて古い値が残った」ことは、抑止リストが族名で検証する仕組み（下記）を経由しない限り検出できない
点に注意（この lint 単体は前方向の逸脱だけを見る片方向の検査）。**

検出する文脈（例）:
    violations.c3n * 9000          # 演算子の直後/直前の数値
    "c3n" -> 9000.0                 # when 分岐の値
    pen += (lo - n).toLong() * 120L # 算術演算の被演算子

抑止（この repo の design_lint.py の P2_BASELINE/P10_BASELINE/P12_EXEMPT_PAIRS と同じ流儀＝
新たに1行ごとのインラインコメント規約を作らず、この lint 自身に理由つきの許可リストを持つ）:
  - WEIGHT_LINT_EXEMPT に (相対パス, 行番号): (対象族名のタプル, "理由") を追加する（既定の抑止手段。
    narrow に保つ）。`.claude/rules/weights.md` が列挙する destroy-repair/polish 系の重複リテラル
    （性能上の理由で `MirrorKeys.weightOf` の呼び出しコストを避け、手動同期で揃える設計）と、族名と
    無関係な数値の偶然の一致（GLS の周期・内部優先順位オフセット等）はここに載せる。
    **[3.573.0/外部レビュー指摘] 抑止は「その行に重み形の数値が今も現れる」だけでなく「宣言した族名の
    “現在の”重みと一致する値が今もその行にある」ことまで検証する**（族名を書かせるのはこのため）。
    旧実装は行の値そのものを検索対象にしていたため、正本だけ重みを変更し複製側の更新を忘れても
    複製側の古い値がもう「現在のどの重みとも一致しない」場合にしか気づけず、たまたま**別の族の現在値と
    数値が一致**すれば古い値のまま緑になり得た（reverse-direction の穴、実例は無いが再現手順で確認済み）。
    値が変わると行番号もずれるので、重みを変更するコミットでここも一緒に確認する。
  - WEIGHT_LINT_FILE_EXEMPT にファイル相対パス: "理由" を追加する（ファイル単位。`Evaluator.kt`/
    `DeltaEvaluator.kt` のような「19族の重み全部を集約する関数を持つ」ファイルだけに限定して使う。
    行単位で管理するとメンテ不能になるうえ、ドリフトは ObjectiveParityTest/native-parity CI が
    別に守っている）。

使い方:
    python3 tools/weight_lint.py            # 違反があれば exit 1（CI 向け。既定で fail する）
    python3 tools/weight_lint.py --summary  # 件数だけ
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENGINE_DIR = os.path.join(ROOT, "app/src/main/java/com/magi/app/v6")
MIRROR_CORE = os.path.join(ENGINE_DIR, "MirrorCore.kt")

# [narrow exclusion / 抑止リスト] (相対パス, 行番号) -> 理由。
#   `.claude/rules/weights.md` が列挙する「destroy-repair/polish 系の重複リテラル」＝ホットパスで
#   `MirrorKeys.weightOf` の呼び出しコスト（String の when 分岐）を避けるため、checker と同じ数値を
#   手動同期で複製している箇所（HF77 の重み変更コミットで同時に更新する運用。MirrorCore.kt 自身の
#   `classWeight` 事前表と同じ設計判断＝3.395.0 のコメント参照）。
#   これらは「MirrorKeys を経由していない」という lint の定義上は真だが、経由しない理由が
#   ドキュメント化された既知の設計判断であり、誤って値がドリフトした場合はこの許可リストの
#   行番号がコード側とずれる（見つからなくなる）ので、そのときは目視で再確認すること。
#   値は (対象族名のタプル, 理由)。族名を書いた行は「その族の“現在の”重みと一致する値がまだそこにあるか」
#   まで毎回検証する（族名タプルが空＝族と無関係な偶然の一致＝値そのものの追跡はしない、下の「偽陽性」節）。
WEIGHT_LINT_EXEMPT = {
    ("app/src/main/java/com/magi/app/v6/DestroyRepairMarginalCost.kt", 41):
        (("low",), "low(120) の複製。weights.md: destroy-repair marginal cost はホットパスで weightOf 呼び出しコストを避ける"),
    ("app/src/main/java/com/magi/app/v6/DestroyRepairMarginalCost.kt", 42):
        (("high",), "high(25) の複製。同上"),
    ("app/src/main/java/com/magi/app/v6/DestroyRepairMarginalCost.kt", 44):
        (("apt",), "apt(4) の複製。同上"),
    ("app/src/main/java/com/magi/app/v6/RangePolish.kt", 285):
        (("low",), "low(120) の複製。weights.md 明記の既知重複"),
    ("app/src/main/java/com/magi/app/v6/RangePolish.kt", 286):
        (("high",), "high(25) の複製。同上"),
    ("app/src/main/java/com/magi/app/v6/RangePolish.kt", 473):
        (("low",), "low(120) の複製。同上"),
    ("app/src/main/java/com/magi/app/v6/RangePolish.kt", 474):
        (("high",), "high(25) の複製。同上"),
    ("app/src/main/java/com/magi/app/v6/DayAssignmentPolish.kt", 75):
        (("low", "high"), "low(120)/high(25) の複製（乗数が左の逆順パターン）。weights.md 明記の既知重複"),
    ("app/src/main/java/com/magi/app/v6/DayAssignmentPolish.kt", 165):
        (("low", "high"), "low(120)/high(25) の複製（乗数が左の逆順パターン）。同上"),
    # [dayPenalty] covU(10000)/covO(10) の候補見積り。C1TemporalFlowPolish.kt・DayAssignmentPolish.kt
    #   と同じ「ホットパスで MirrorKeys.weightOf を避ける」設計だが、weights.md の destroy-repair/polish
    #   系チェックリストには covU/covO の組までは列挙されていない（low/high の組だけが明記）。
    #   4ファイルで完全に同一の1行関数（[3.522.0] タグ＝covO の重み改定と同時に更新済み＝ドリフトなし）
    #   ＝新規に紛れ込んだ複製ではなく既存の一貫した実装。weights.md のチェックリストへの追記は別途検討。
    ("app/src/main/java/com/magi/app/v6/C1TemporalFlowPolish.kt", 112):
        (("low",), "low(120) の複製。weights.md 明記の既知重複（同関数113行のhigh(25)・115行のapt(4)と同型）"),
    ("app/src/main/java/com/magi/app/v6/C1TemporalFlowPolish.kt", 122):
        (("covU", "covO"), "covU(10000)/covO(10) の複製（dayPenalty、4ファイル共通・[3.522.0]で同期済み）"),
    ("app/src/main/java/com/magi/app/v6/C41FlowPolish.kt", 28):
        (("covU", "covO"), "covU(10000)/covO(10) の複製（dayPenalty、4ファイル共通・[3.522.0]で同期済み）"),
    ("app/src/main/java/com/magi/app/v6/C42FlowPolish.kt", 27):
        (("covU", "covO"), "covU(10000)/covO(10) の複製（dayPenalty、4ファイル共通・[3.522.0]で同期済み）"),
    ("app/src/main/java/com/magi/app/v6/RangePolish.kt", 484):
        (("covU", "covO"), "covU(10000)/covO(10) の複製（dayPenalty、4ファイル共通・[3.522.0]で同期済み）"),
    # [偽陽性/族名と無関係な偶然の一致＝族名タプルは空。値そのものの継続一致は検証しない]
    ("app/src/main/java/com/magi/app/v6/C1JointLnsPolish.kt", 490):
        ((), "50 は GoalKind別の内部優先順位オフセット（C1=100/TEMPORAL=150/COVERAGE=200/RANGE_LOW=50、"
        "同関数139/463/477行）。c1の重み(50)とは無関係な偶然の一致（GoalKindはRANGE_LOWで、C1ではない）"),
    ("app/src/main/java/com/magi/app/v6/Hf63Infeasibility.kt", 38):
        ((), "\"pref\" to 10 の 10 はfamily→添字の列挙表のインデックス。直前の\"covO\"（別ペア）に反応した"
        "文脈窓の偽陽性で、covO(10)の重みとは無関係"),
    ("app/src/main/java/com/magi/app/v6/V6HotfixPasses.kt", 961):
        ((), "120 は localBestImprovement の評価予算パラメータ（250 + cycle*120）。low の重みとは無関係な偶然の一致"),
    ("app/src/main/java/com/magi/app/v6/V6LateOperators.kt", 86):
        ((), "200*high+120*low は旧Webゲート(HF151系)の固定係数として明示的に維持されている値（同ファイルの"
        "KDoc参照）。MirrorKeys由来ではない（200がhigh=25と一致しないことがその証拠）。120がlow(120)と"
        "偶然一致しているだけ"),
    ("app/src/main/java/com/magi/app/v6/V6NativeOptimizer.kt", 1672):
        ((), "iter % 50L はGLS停滞検出の周期（cadence）。c1の重みとは無関係な偶然の一致"),
    ("app/src/main/java/com/magi/app/v6/V6NativeOptimizer.kt", 1698):
        ((), "iter % 120L は進捗報告(publishLiveBest)の周期（cadence）。lowの重みとは無関係な偶然の一致"),
}

# [file-level exemption] Evaluator.kt(fullEvalParts)・DeltaEvaluator.kt(集約式)は weights.md が
#   名指しする「同じコミットで揃える」ファイルの筆頭で、19族**全部**の重みを撞き合わせる場所＝
#   行単位で列挙すると更新のたびに行番号がずれてメンテ不能になる（design_lint.py のラチェット
#   baseline と同じ「厳密には妥当だが1行ずつ管理する価値がない」ケース）。この2ファイルのドリフトは
#   既に別の機械検査で守られている（Kotlin側=ObjectiveParityTest、C++側=native-parity CI、実行時は
#   SaOptimizer の2層番兵）ため、この lint の対象外にしてよい。ここに追加する基準は「全族の重みを
#   本質的に**全部**集約する関数」だけに限る（destroy-repair/polish の個別コスト関数を追加しない）。
WEIGHT_LINT_FILE_EXEMPT = {
    "app/src/main/java/com/magi/app/v6/Evaluator.kt":
        "fullEvalParts＝全族の重みを集約する評価器本体。weights.md明記の同期対象、"
        "ドリフトはObjectiveParityTest/native-parity CIが別途守る",
    "app/src/main/java/com/magi/app/v6/DeltaEvaluator.kt":
        "Δ評価の集約式＝同上。fullEvalParts と同じ理由でファイル単位除外",
}


def parse_weights():
    """`MirrorCore.kt` の `MirrorKeys.weights = linkedMapOf(...)` を実行時にパースする（stale 防止）。"""
    with open(MIRROR_CORE, encoding="utf-8") as fh:
        src = fh.read()
    m = re.search(r"val weights:\s*Map<String,\s*Double>\s*=\s*linkedMapOf\((.*?)\)\n", src, re.DOTALL)
    if not m:
        raise SystemExit("weight_lint: MirrorCore.kt の weights = linkedMapOf(...) が見つかりません（形が変わった可能性）")
    body = m.group(1)
    pairs = re.findall(r'"(\w+)"\s+to\s+([\d.]+)', body)
    if not pairs:
        raise SystemExit("weight_lint: weights の中身をパースできませんでした")
    by_family = {name: float(val) for name, val in pairs}
    by_value = {}
    for name, val in by_family.items():
        by_value.setdefault(val, []).append(name)
    return by_family, by_value


def _strip_kotlin_file(lines):
    """行コメント・文字列に加え、**複数行の /* */（KDoc 含む）**も落とした行リストを返す。

    design_lint.py の `_strip_kotlin_file` と同じ手法をベースに、**消した区間は同じ文字数の
    空白で埋めて桁位置を保つ**（design_lint.py 版は1文字へ潰すが、ここでは数値リテラルの
    マッチ位置を元の行にそのまま当てて前後の文脈（`_family_context`）を探すため、位置がずれると
    無関係な箇所を拾う／本来の手がかりを見失う）。KDoc の箇条書き（`* 2. …` のような説明文中の
    数字）を行単位のストリッパだけで扱うと「`*` の直後の数字」を乗算と誤認して大量に誤検出する
    （実測: 対策前は KDoc プローズだけで数百件の偽陽性）。ブロックコメントの開閉はファイル全体で
    状態を持ち越して追跡する。
    """
    out = []
    in_block = False
    for line in lines:
        res, i, n = [], 0, len(line)
        while i < n:
            if in_block:
                j = line.find("*/", i)
                if j < 0:
                    res.append(" " * (n - i))
                    i = n
                else:
                    in_block = False
                    res.append(" " * (j + 2 - i))
                    i = j + 2
                continue
            c = line[i]
            if c == '"':
                start = i
                i += 1
                while i < n:
                    if line[i] == "\\":
                        i += 2
                        continue
                    if line[i] == '"':
                        i += 1
                        break
                    i += 1
                res.append(" " * (i - start))
                continue
            if c == "/" and i + 1 < n and line[i + 1] == "/":
                res.append(" " * (n - i))
                break
            if c == "/" and i + 1 < n and line[i + 1] == "*":
                in_block = True
                res.append("  ")
                i += 2
                continue
            res.append(c)
            i += 1
        out.append("".join(res))
    return out


# 算術/比較/代入/when分岐の文脈で数値リテラルの前後に来る演算子。
#   `=`（等号1つ）は明示的な `.0` 小数リテラルのときだけ対象にする（`val i = 2` のような
#   ありふれた整数代入まで拾うと雑音になりすぎる。weights map の値は常に Double リテラルなので、
#   複製された定数も同じ形＝ `= 9000.0` になっているはずという前提）。
_OP = r'(?:\*|/|%|\+|-|==|!=|<=|>=|<|>|->|\bto\b)'
RE_NUM_AFTER_OP = re.compile(_OP + r'\s*(\d+(?:\.\d+)?)(L)?\b')
RE_NUM_BEFORE_OP = re.compile(r'\b(\d+(?:\.\d+)?)(L)?\s*' + _OP)
RE_NUM_EQ_DOUBLE = re.compile(r'=\s*(\d+\.\d+)\b')


def _candidates(code):
    """1行（コメント/文字列除去済み）から算術/比較文脈の数値リテラルを (文字列, 値, 開始位置) で列挙する。"""
    seen = set()
    out = []
    for rx in (RE_NUM_AFTER_OP, RE_NUM_BEFORE_OP):
        for m in rx.finditer(code):
            span = m.span(1)
            if span in seen:
                continue
            seen.add(span)
            out.append((m.group(1), float(m.group(1)), span[0]))
    for m in RE_NUM_EQ_DOUBLE.finditer(code):
        span = m.span(1)
        if span in seen:
            continue
        seen.add(span)
        out.append((m.group(1), float(m.group(1)), span[0]))
    return out


# [ノイズ対策] 19族の重みのうち 2/4/6/9/10/15/25 は「よくある小さな整数」でもある
#   （ループ境界・`< 2`・`/ 2`・配列サイズ等）。演算子隣接だけで拾うと大半が無関係の偽陽性になる
#   （実測: 対策前は数百件）。それらの値は**同じ行に該当ファミリー名が現れているとき**だけ
#   報告する（例のパターン `violations.c3n * 9000` 自身がそうであるように、族の手がかりが
#   隣にあってはじめて「その重みの複製」と言える）。90/120/8000/9000/10000/11000/50 は業務データ
#   としてまず偶然出現しない値なので、族名が無くても算術/比較文脈に出た時点で報告する
#   （DISTINCTIVE_MIN 未満＝文脈必須、以上＝単独で報告）。
DISTINCTIVE_MIN = 50.0


CONTEXT_WINDOW = 24  # 数値リテラルの前後この文字数だけを文脈として見る（行全体だと離れた無関係の
#   ペアまで拾う＝実測: `"c41" to 6, "c42" to 7, "covU" to 8, "covO" to 9, "pref" to 10` のような
#   族名→添字の列挙表で、離れた位置の別ファミリー名と値がたまたま重みと一致して誤検出した）。


def _family_context(raw_line, pos, families):
    """raw_line（コメント・文字列を残したままの元の行）の pos 付近に families の手がかりがあるか。

    手がかりは2通り: ①`"family"` という文字列そのもの（`record("c2", raw)` / `"c3n" -> 9000.0` 等）
    ②camelCase の接尾辞として使われる大文字始まりの綴り（`rawLow`・`sFair`・`dC41s`・`sApt` 等。
    Evaluator.kt/DeltaEvaluator.kt の集約式がこの形）。②は次の文字が小文字なら誤検出とみなして
    除外する（`Lowest` のような無関係な語の部分一致を避ける）。窓を数値リテラルの近傍に絞るのは
    「族名→添字」のような列挙表で離れた無関係の対を拾わないため。
    """
    lo = max(0, pos - CONTEXT_WINDOW)
    hi = min(len(raw_line), pos + CONTEXT_WINDOW)
    window = raw_line[lo:hi]
    for fam in families:
        if f'"{fam}"' in window:
            return fam
        cap = fam[0].upper() + fam[1:]
        for m in re.finditer(re.escape(cap), window):
            end = m.end()
            if end >= len(window) or not window[end].isalpha() or not window[end].islower():
                return fam
    return None


def engine_files():
    if not os.path.isdir(ENGINE_DIR):
        return []
    out = []
    for fn in sorted(os.listdir(ENGINE_DIR)):
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(ENGINE_DIR, fn)
        if path == MIRROR_CORE:
            continue
        if "/test/" in path.replace(os.sep, "/"):
            continue
        out.append(path)
    return out


def scan(by_value):
    """戻り値: (findings, used_exempt_values)。used_exempt_values は (相対パス, 行番号) -> その行で
    実際にマッチした数値の集合（`main()` が WEIGHT_LINT_EXEMPT の宣言族名の“現在の”重みと突き合わせて、
    行番号のずれによる空振りだけでなく「値そのものが古いまま残っている」抑止も検出する）。
    """
    findings = []
    used_exempt_values = {}
    for path in engine_files():
        rel = os.path.relpath(path, ROOT)
        if rel in WEIGHT_LINT_FILE_EXEMPT:
            continue
        with open(path, encoding="utf-8") as fh:
            raw = fh.read().split("\n")
        stripped = _strip_kotlin_file(raw)
        for i, code in enumerate(stripped):
            if not code.strip():
                continue
            n = i + 1
            for text, val, pos in _candidates(code):
                fams = by_value.get(val)
                if not fams:
                    continue
                if (rel, n) in WEIGHT_LINT_EXEMPT:
                    used_exempt_values.setdefault((rel, n), set()).add(val)
                    continue
                if val < DISTINCTIVE_MIN:
                    hit_fam = _family_context(raw[i], pos, fams)
                    if hit_fam is None:
                        continue
                    fams = [hit_fam]
                findings.append((rel, n, text, fams, raw[i].strip()))
    return findings, used_exempt_values


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--summary", action="store_true", help="件数だけ表示")
    a = ap.parse_args()

    by_family, by_value = parse_weights()
    findings, used_exempt_values = scan(by_value)

    # [3.573.0/外部レビュー指摘] 空振り（行に重み形の値が一つも無い＝行番号ずれ／削除）と、
    #   値ミスマッチ（宣言した族の“現在の”重みがその行の値の中に無い＝正本だけ変更し複製を
    #   更新し忘れた可能性）を分けて検出する。族名タプルが空（偽陽性の登録）はミスマッチ判定の対象外。
    stale_exempt = []
    mismatched_exempt = []
    for key, (fams, reason) in WEIGHT_LINT_EXEMPT.items():
        matched_here = used_exempt_values.get(key, set())
        if not matched_here:
            stale_exempt.append(key)
            continue
        if fams:
            missing = [f for f in fams if by_family.get(f) not in matched_here]
            if missing:
                mismatched_exempt.append((key, missing, matched_here))

    print("=== MAGI weight lint (MirrorKeys.weights の単一ソース逸脱検査) ===")
    print(f"MirrorKeys.weights: {len(by_family)} 族 / {len(by_value)} 種の値を検出")
    print(f"抑止リスト: WEIGHT_LINT_EXEMPT {len(WEIGHT_LINT_EXEMPT)} 行 / "
          f"WEIGHT_LINT_FILE_EXEMPT {len(WEIGHT_LINT_FILE_EXEMPT)} ファイル")
    print(f"検査対象: {len(engine_files()) - len(WEIGHT_LINT_FILE_EXEMPT)} ファイル"
          f"（v6/*.kt、MirrorCore.kt とファイル抑止分を除く）")
    if not a.summary:
        for rel, n, text, fams, src in findings:
            fam_label = "/".join(fams)
            print(f"    {rel}:{n}  {text} が重み {fam_label}({by_family[fams[0]]:g}) と一致: {src}")
    print(f"\n合計 {len(findings)} 件")
    blockers = bool(findings)
    if findings:
        print(
            "MirrorKeys.weightOf(family) / MirrorKeys.weights[...] 経由に直すか、"
            "既知の意図的な複製なら理由と対象族名を添えて WEIGHT_LINT_EXEMPT へ追加してください。"
        )
    if stale_exempt:
        # [ラチェット/design_lint.py の baseline 昇降と同じ発想] コードが変わって該当行に
        #   もう重み形の数値がない＝抑止が空振りしている。放置すると行番号のずれで別の行を
        #   誤って見逃す事故につながるので、古い抑止は削除を促して fail させる。
        blockers = True
        print(f"\n抑止リストが空振りしています（{len(stale_exempt)} 件、コード変更で行がずれたか削除された可能性）:")
        for rel, n in sorted(stale_exempt):
            print(f"    {rel}:{n}  {WEIGHT_LINT_EXEMPT[(rel, n)][1]}")
        print("該当行を確認し、WEIGHT_LINT_EXEMPT から削除するか正しい行番号へ直してください。")
    if mismatched_exempt:
        # [3.573.0/外部レビュー指摘] reverse-direction の穴＝正本の重みだけ変更し、複製側の更新を
        #   忘れた場合の検出。旧実装は「複製側の古い値が現在のどの重みとも一致しない」ときしか
        #   気づけず、古い値がたまたま別の族の現在値と一致すれば見逃していた。
        blockers = True
        print(f"\n抑止リストの値が現在の重みと一致しません（{len(mismatched_exempt)} 件、"
              "重みを変更したのに複製側の更新を忘れた可能性）:")
        for (rel, n), missing, matched_here in sorted(mismatched_exempt):
            _, reason = WEIGHT_LINT_EXEMPT[(rel, n)]
            expect = ", ".join(f"{f}={by_family[f]:g}" for f in missing)
            print(f"    {rel}:{n}  宣言した族の現在値 [{expect}] がこの行に見つかりません"
                  f"（実際にこの行にある値: {sorted(matched_here)}）。{reason}")
        print("MirrorKeys.weights の変更に合わせてこの行の値も更新するか、抑止の族名を見直してください。")
    return 1 if blockers else 0


if __name__ == "__main__":
    sys.exit(main())
