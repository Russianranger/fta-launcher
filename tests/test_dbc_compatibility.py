import sys
from pathlib import Path
import unittest
import copy
import json

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from check_dbc_compatibility import validate_rows
from dbc_live import measured_routes


class DbcCapturedRowsTests(unittest.TestCase):
    def test_complete_descending_rows(self):
        validate_rows('2\t3.5\n1\t2.0\n0\t1.5\n', 3)

    def test_hole_duplicate_order_and_wrong_count(self):
        for data in ('2\t1\n0\t1\n', '1\t1\n1\t1\n0\t1\n', '0\t1\n1\t1\n2\t1\n'):
            with self.assertRaises(ValueError): validate_rows(data, 3)

    def test_missing_extra_columns_and_nonfinite_coefficients(self):
        for data in ('0\n', '0\t1\t2\n', '0\tnan\n', '0\tinf\n'):
            with self.assertRaises(ValueError): validate_rows(data, 1)


class LiveDbcRoutesTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1]
        proof = json.loads((root / 'docs/evidence/host-2026-10-24-dbc-loaders.json').read_text())
        import re
        kotlin = (root / 'android/app/src/main/java/com/russianranger/fta/runtime/GameDataDbcCompatibility.kt').read_text()
        self.mapping = dict(re.findall(r'"dbc/([^"/]+)" to Route\("([a-z_]+)", \d+\)', kotlin))
        self.cases = []
        for name, table in self.mapping.items():
            rows = proof['database']['tables'][table]['rows']
            self.cases.append({'asset': 'dbc/' + name, 'observedHeader': {
                'rows': rows, 'fields': 1, 'recordBytes': 4, 'stringBytes': 1,
                'fileBytes': 21 + rows * 4, 'magic': 0x43424457}})

    def test_all_reviewed_source_mappings_and_rows(self):
        self.assertEqual(8922, sum(rows for _, rows in measured_routes(self.cases, self.mapping).values()))

    def test_missing_duplicate_and_unreviewed_cases(self):
        for cases in (self.cases[:-1], self.cases + [self.cases[0]], [self.cases[0]] * 10):
            with self.assertRaises(ValueError): measured_routes(cases, self.mapping)
        bad = copy.deepcopy(self.cases); bad[0]['asset'] = 'dbc/unknown.dbc'
        with self.assertRaises(ValueError): measured_routes(bad, self.mapping)

    def test_wrong_header_and_noninteger_values_rejected(self):
        for key in self.cases[0]['observedHeader']:
            for value in (0, '1', True):
                bad = copy.deepcopy(self.cases); bad[0]['observedHeader'][key] = value
                with self.assertRaises(ValueError): measured_routes(bad, self.mapping)

    def test_source_sql_mapping_cannot_be_supplied_by_report(self):
        bad = dict(self.mapping); bad[next(iter(bad))] = 'unexpected_table'
        with self.assertRaises(ValueError): measured_routes(self.cases, bad)
