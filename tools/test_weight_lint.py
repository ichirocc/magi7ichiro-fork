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


if __name__ == "__main__":
    unittest.main()
