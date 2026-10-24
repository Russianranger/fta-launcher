import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from check_dbc_compatibility import validate_rows


class DbcCapturedRowsTests(unittest.TestCase):
    def test_complete_descending_rows(self):
        validate_rows('2\t3.5\n1\t2.0\n0\t1.5\n', 3)

    def test_hole_duplicate_order_and_wrong_count(self):
        for data in ('2\t1\n0\t1\n', '1\t1\n1\t1\n0\t1\n', '0\t1\n1\t1\n2\t1\n'):
            with self.assertRaises(ValueError): validate_rows(data, 3)

    def test_missing_extra_columns_and_nonfinite_coefficients(self):
        for data in ('0\n', '0\t1\t2\n', '0\tnan\n', '0\tinf\n'):
            with self.assertRaises(ValueError): validate_rows(data, 1)
