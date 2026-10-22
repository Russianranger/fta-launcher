"""Public contracts use synthetic identities, never private SQL or player rows."""
from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from tools.create_full_seed_lock import (CANONICAL_RECEIPTS, RECEIPT_LIMIT, full_seed_lock,
                                         parse_receipt_summary, receipt_query)
from tools.fta_database import DATABASE_SHA256, EXPECTED_TABLES, SCHEMAS, DatabasePlan, GateError, SqlEntry


class FullSeedLockTests(unittest.TestCase):
    def fixture(self):
        entries = []
        schemas = []
        for schema, count in EXPECTED_TABLES.items():
            for index in range(count):
                name = f"synthetic_{index:03}"
                entries.append(SqlEntry(f"Database/{schema}/table-{name}.sql", schema, "table", name,
                                        10, "a" * 64, "b" * 40))
            trigger_names = ["synthetic_trigger"] if schema == "acore_world" else []
            schemas.append({"name": schema, "tableCount": count, "triggerNames": trigger_names,
                            "routineCount": 0, "eventCount": 0, "receiptCount": 1,
                            "receiptBytes": 80, "receiptSha256": "c" * 64})
        entries.append(SqlEntry("Database/acore_world/trigger-synthetic_trigger.sql", "acore_world",
                                "trigger", "synthetic_trigger", 10, "d" * 64, "e" * 40))
        for index in range(236):
            entries.append(SqlEntry(f"Database/RuntimeSQL/Data/data/sql/updates/db_world/synthetic_{index:03}.sql",
                                    "acore_world", "update", f"synthetic_{index:03}.sql", 10, "f" * 64, "a" * 40))
        plan = DatabasePlan(DATABASE_SHA256, tuple(entries), 8000)
        baseline = {"formatVersion": 1, "archiveSha256": DATABASE_SHA256,
                    "requiredProviderVersion": "12.3.2", "runtimeSqlApplied": False, "schemas": schemas}
        return plan, baseline

    def test_only_pinned_seed_metadata_crosses_the_public_boundary(self):
        plan, baseline = self.fixture()
        baseline["privateSql"] = "PRIVATE_ROW_VALUE"
        baseline["schemas"][0]["rows"] = "PRIVATE_ROW_VALUE"
        lock = full_seed_lock(plan, 103160479, baseline)
        self.assertEqual(len(lock["files"]), 564)
        self.assertEqual([entry["kind"] for entry in lock["files"]][-1], "trigger")
        self.assertTrue(all(entry["kind"] == "table" for entry in lock["files"][:-1]))
        self.assertNotIn("PRIVATE_ROW_VALUE", json.dumps(lock))
        self.assertFalse(lock["constraints"]["runtimeSqlApplied"])
        self.assertFalse(lock["constraints"]["liveDatabaseAccess"])
        self.assertTrue(lock["constraints"]["freshDisposableDatadirOnly"])
        self.assertEqual(lock["canonicalReceipts"], CANONICAL_RECEIPTS)
        for entry in lock["files"]:
            self.assertEqual(set(entry), {"path", "schema", "kind", "name", "size", "sha256"})

    def test_manifest_order_is_deterministic_and_does_not_include_updates(self):
        plan, baseline = self.fixture()
        reversed_plan = DatabasePlan(plan.archive_sha256, tuple(reversed(plan.entries)), plan.uncompressed_bytes)
        self.assertEqual(full_seed_lock(plan, 123, baseline), full_seed_lock(reversed_plan, 123, baseline))
        self.assertFalse(any(entry["kind"] == "update" for entry in full_seed_lock(plan, 123, baseline)["files"]))

    def test_wrong_archive_version_runtime_or_object_contract_is_refused(self):
        plan, baseline = self.fixture()
        mutations = [("archiveSha256", "0" * 64), ("requiredProviderVersion", "10.11"),
                     ("runtimeSqlApplied", True), ("formatVersion", 2), ("formatVersion", True)]
        for key, value in mutations:
            bad = deepcopy(baseline)
            bad[key] = value
            with self.subTest(key=key), self.assertRaises(GateError):
                full_seed_lock(plan, 123, bad)
        for key, value in (("tableCount", 26), ("triggerNames", ["extra"]), ("routineCount", 1),
                           ("eventCount", 1), ("tableCount", 27.0), ("routineCount", False),
                           ("receiptSha256", "sql rows"), ("receiptCount", 0)):
            bad = deepcopy(baseline)
            bad["schemas"][0][key] = value
            with self.subTest(key=key), self.assertRaises(GateError):
                full_seed_lock(plan, 123, bad)
        for bad in ([], {}, {**baseline, "schemas": [None]}):
            with self.assertRaises(GateError):
                full_seed_lock(plan, 123, bad)
        with self.assertRaises(GateError):
            full_seed_lock(DatabasePlan("0" * 64, plan.entries, 8000), 123, baseline)
        with self.assertRaises(GateError):
            full_seed_lock(DatabasePlan(plan.archive_sha256, plan.entries[1:], 8000), 123, baseline)

    def test_receipt_protocol_rejects_truncation_invalid_fields_and_unbounded_metadata(self):
        digest = hashlib.sha256(b"synthetic.sql\t" + b"B" * 40 + b"\tRELEASED\n").hexdigest()
        self.assertEqual(parse_receipt_summary(f"FTA_RECEIPTS=1:64:64:0:{digest}"),
                         {"receiptCount": 1, "receiptBytes": 64, "receiptSha256": digest})
        for value in (f"FTA_RECEIPTS=1:64:63:0:{digest}", f"FTA_RECEIPTS=1:64:64:1:{digest}",
                      f"FTA_RECEIPTS=0:0:0:0:{digest}", f"FTA_RECEIPTS=10001:64:64:0:{digest}",
                      f"FTA_RECEIPTS=1:{RECEIPT_LIMIT+1}:{RECEIPT_LIMIT+1}:0:{digest}",
                      f"FTA_RECEIPTS=1:64:64:0:{digest.upper()}", "PRIVATE_ROW_VALUE", "FTA_RECEIPTS=1:64:64:0:"):
            with self.subTest(value=value), self.assertRaises(GateError):
                parse_receipt_summary(value)

    def test_receipt_query_uses_fixed_schema_binary_order_exact_hash_case_and_length_guard(self):
        for schema in SCHEMAS:
            sql = receipt_query(schema)
            self.assertIn(f"`{schema}`.`updates`", sql)
            self.assertIn("ORDER BY BINARY `name` SEPARATOR ''", sql)
            self.assertIn("SUM(OCTET_LENGTH", sql)
            self.assertIn("OCTET_LENGTH(GROUP_CONCAT", sql)
            self.assertNotIn("UPPER(", sql)
            self.assertNotIn("LOWER(", sql)
            self.assertNotIn("REPLACE", sql)
            self.assertNotIn("INSERT", sql)
            self.assertNotIn("DELETE", sql)
        for schema in ("acore_world`; DROP DATABASE foo", "other", ""):
            with self.assertRaises(GateError):
                receipt_query(schema)

    def test_cli_metadata_paths_cannot_overwrite_private_archive_or_hardlink(self):
        tool = Path(__file__).resolve().parents[1] / "tools/create_full_seed_lock.py"
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "private.zip"
            archive.write_bytes(b"private original bytes")
            link = Path(directory) / "metadata.json"
            link.hardlink_to(archive)
            for flag in ("--output", "--baseline-report"):
                for path in (archive, link):
                    args = [sys.executable, str(tool), str(archive), "--baseline-report", str(Path(directory) / "baseline.json")]
                    args.extend([flag, str(path)])
                    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    self.assertEqual(result.returncode, 2)
                    self.assertEqual(archive.read_bytes(), b"private original bytes")

if __name__ == "__main__":
    unittest.main()
