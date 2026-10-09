# 仕様: 禁止希望（prefNot）— 指定日の勤務を指定シフト以外にする

状態: **置き換え済み（実装しない）**。同日ユーザー提示の仕様「希望シフトの拡張」（拡張希望＝採点外・重み未指示）が正。実装と規則は `docs/business-logic.md` の「拡張希望」と `ExtWishRules`。以下は Q1〜Q7 の記録として残す（画面の設計は採っていない。重み 8000 の新しい族は 3.653.0 で拡張希望の違反 `extWish` として採用＝ユーザー明示指示「拡張希望の重みは希望シフトと同じにする」）。

## 1. 決定事項
| # | 論点 | 決定 |
|---|---|---|
| Q1 | 対象 | 職員×日ごと（希望の反対＝「その日その職員にこのシフトを入れない」） |
| Q2 | 強さ | 必須（HARD）・新しい族 `prefNot` |
| Q3 | 重み | **8000**（pref と同格、HF77 ユーザー明示指示 2026-10-07）。画面名「禁止希望」 |
| Q4 | 指定数 | 1 セルに複数シフト可 |
| Q5 | 並列 | 全エンジン経路で同値（Kotlin 評価器 3 者・C++ 並列 worker・C#） |
| Q6 | 入力 | 勤務表の希望入力シートを拡張 |
| Q7 | 希望との矛盾 | 入力で防ぐ＋「つくる前の確認」で警告。矛盾セルは希望を優先し、prefNot は数えない |

## 2. データ
- `MagiState.wishBans: Map<String, Set<Int>>`＝キー `"職員,日"`（`pairKey`）→ 禁止するシフト番号の集合。既定は空。
- JSON: `"wishBans": {"6,13": [2, 4]}`。旧ファイル（キー無し）は空として読む。保存は空なら出力しない（旧版で開ける）。
- CSV 取込: 種別タグ `禁止希望`、列は 職員・日・記号（記号は `/` 区切りで複数）。
- 範囲外の職員・日・シフトは読み込み時に捨て、ログに件数を出す。

## 3. 評価（単一の定義）
- 違反: セル (i,j) の値 k が `wishBans["i,j"]` に含まれ、かつ同じセルに希望（`wishes`）が無いとき 1 件。1 セル最大 1 件。
- 族 `prefNot`、HARD、重み 8000。`MirrorKeys.hard`・`MirrorKeys.weights`・`MirrorKeys.all` に追加。
- 場所: `cellFamilies`/`violations` に `vio-prefNot`（表示はセル単位の印）。
- 同じ式を次の全経路に入れ、件数が一致すること:
  - Kotlin: `Evaluator.fullEvalParts`、`DeltaEvaluator`（1 セル変更の差分は O(1)＝そのセルだけ）、`UnifiedViolationChecker`。
  - C++ `magi_native.cpp`: 評価器と `SaChunk`（並列 worker）。禁止表は `Problem` から作る読み取り専用のビット表 `banBits[i][j]`（K≤64 の bitmask）で、worker 間で共有し書き込まない。
  - C#（-MAGI_PC）: 同日に同期。
- 2 層番兵: C++ 自己整合と Kotlin `fullEval` 照合に prefNot を含める。不一致なら `NativeGate` が閉じる（既存どおり）。
- 重みを足すので `.claude/rules/weights.md` の一覧（Evaluator リテラル・Delta 集約式・destroy-repair/polish 系・C++ 5 箇所・言語跨ぎ期待値 3 ファイル＋C# ミラー・docs/business-logic.md・CLAUDE.md の重み階層行）を同じコミットで揃える。
- 重み階層: groupViol 11000 > covU 10000 > c3n 9000 = c3w 9000 > pref 8000 = **prefNot 8000** > low 120 > …

## 4. 最適化器
- 評価に入るので探索は自然に避ける。加えて初期配置・候補生成は禁止セルへ置かない（`Problem.mayPlaceCell(i,j,k)`＝`mayPlace(i,k) && k ∉ wishBans[i,j]`）。評価・表示は族として数える（入力済みの盤面に残る違反は見える）。
- 希望固定（`wishLocked`）は従来どおり最優先。

## 5. 画面（Android＝正、C# は同等）
- 勤務表の希望入力シートに「希望／禁止」の切替。禁止ではシフトを複数タップで選ぶ（片手一本指、ドラッグ無し）。希望のあるセルでは、その希望シフトは選べない（Q7）。
- セルの印: 小さな「×」（色は `docs/DESIGN.md` の原則、`design_lint.py` を通す）。違反時は内訳チップ「禁止希望」。
- 「つくる前の確認」: 希望と禁止希望の矛盾、担当可能シフトが全部禁止されたセル（構造的に解けない）を警告。

## 6. 受入テスト
- 3 評価器と C++ の件数一致（ランダム盤面・ランダム禁止表、複数 worker）。DeltaEvaluator の 1 セル差分＝フル再計算。
- 希望と矛盾するセルは数えない。旧 JSON の読込で wishBans が空・出力不変（既存の盤面ハッシュ・スコアが変わらない）。
- 並列 8 worker で決定的モードの最終盤面が同じ。
- CSV 取込の往復・範囲外の破棄。
- UI を触るので `android-sdk.yml` を作業ブランチで実コンパイル確認。
