---
paths:
  - "app/src/main/java/com/magi/app/v6/MirrorCore.kt"
  - "app/src/main/java/com/magi/app/v6/Evaluator.kt"
  - "app/src/main/java/com/magi/app/v6/DeltaEvaluator.kt"
  - "app/src/main/cpp/magi_native.cpp"
---

# 目的関数・重みを触るときの規約

- 重みの単一の真実は `MirrorKeys.weights`。変えるときは HF77（業務担当者の明示数値指示＋1 件ずつ）が前提。
- 同じコミットで揃える: `MirrorKeys.weights`・`Evaluator.fullEvalParts` のリテラル・`DeltaEvaluator` の集約式・
  `magi_native.cpp`（評価器と SaChunk）・言語跨ぎ期待値 3 ファイル（`app/src/test/resources/*_eval_expected.txt`）・
  `docs/business-logic.md`・CLAUDE.md の重み階層行。C#（-MAGI_PC）は同日に同期。
- Δ（DeltaEvaluator）とフル（Evaluator）は常に一致させる（`SaOptimizer` が整合チェックする）。チェッカー
  （`UnifiedViolationChecker`）が source of truth で、最適化器はそれへ寄せる。
- コメントの主張と実装値を grep で照合してからコミットする（この領域は stale コメントの前科がある）。
