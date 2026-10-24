"""tools/weight_lint.py の数値リテラル検出の自己テスト（U-03）。`python3 -m unittest tools/test_weight_lint.py`。"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import weight_lint  # noqa: E402


def values(code):
    return sorted(v for _, v, _ in weight_lint._candidates(code))


class CandidatesTest(unittest.TestCase):
    def test_underscore_separated_long(self):
        self.assertIn(10000.0, values("val s = covU * 10_000L"))

    def test_exponent_forms(self):
        self.assertIn(10000.0, values("val s = covU * 1e4"))
        self.assertIn(9000.0, values("x + 9.0E3"))

    def test_double_assignment_with_exponent(self):
        self.assertIn(10000.0, values("val w = 1.0e4"))

    def test_plain_forms_still_caught(self):
        self.assertIn(9000.0, values("violations.c3n * 9000"))
        self.assertIn(8000.0, values("val w = 8000.0"))

    def test_plain_int_assignment_is_still_ignored(self):
        self.assertEqual([], values("val i = 25"))


class MovedExemptTest(unittest.TestCase):
    """抑止の断片が別のファイルへ移っても空振りで落とさず、移った先を返す（2026-10-24、P10b のファイル分割で CI が落ちた対策）。"""
    def test_fragment_found_in_another_file_is_reported_as_moved(self):
        orig = dict(weight_lint.WEIGHT_LINT_EXEMPT)
        key = ("app/src/main/java/com/magi/app/v6/AlnsSearch.kt", "iter % 120L == 0L")
        self.assertIn(key, orig)
        try:
            entry = weight_lint.WEIGHT_LINT_EXEMPT.pop(key)
            old = ("app/src/main/java/com/magi/app/v6/V6NativeOptimizer.kt", key[1])
            weight_lint.WEIGHT_LINT_EXEMPT[old] = entry
            _, by_value = weight_lint.parse_weights()
            findings, used, _, moved = weight_lint.scan(by_value)
            self.assertEqual(key[0], moved.get(old))
            self.assertTrue(used.get(old))
            self.assertFalse([f for f in findings if "iter % 120L" in f[4]])
        finally:
            weight_lint.WEIGHT_LINT_EXEMPT.clear()
            weight_lint.WEIGHT_LINT_EXEMPT.update(orig)


if __name__ == "__main__":
    unittest.main()
