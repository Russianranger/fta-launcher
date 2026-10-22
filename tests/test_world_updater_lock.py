"""World updater expected-result contracts use synthetic SQL identities only."""
from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from tools.create_world_updater_lock import (AUDIT_FILES, EMPTY_SHA256, POLICY, ROOT, column_query,
    expected_source_audit, host_policy_source_audit, include_directories, parse_aggregate, release_query, source_policy_probe,
    verify_runtime_layout, world_updater_lock)
from tools.fta_database import DATABASE_SHA256, SCHEMAS, DatabasePlan, GateError, SqlEntry
from tools.fta_source import SOURCE_SHA256


def entry(name, schema="acore_world", digest="a" * 40, pending=False):
    folder = ("pending_db_" if pending else "db_") + schema.removeprefix("acore_")
    return SqlEntry(f"Database/RuntimeSQL/Data/data/sql/updates/{folder}/{name}", schema, "update", name,
                    11, "b" * 64, digest)


class FakeCLI:
    def __init__(self, receipts=None, fail=False):
        self.receipts = receipts or {}
        self.statements = []
        self.imported = []
        self.fail = fail

    def query(self, sql):
        schema = next(s for s in SCHEMAS if f"`{s}`" in sql)
        if "updates_include" in sql:
            return "\n".join(sorted("$/" + e["path"] + "\t" + e["state"]
                                    for e in include_directories() if e["schema"] == schema))
        return "\n".join("\t".join(row) for row in self.receipts.get(schema, []))

    def run(self, *, sql, capture):
        self.statements.append(sql)

    def import_member(self, archive, item):
        self.imported.append(item.name)
        if self.fail:
            raise GateError("Synthetic SQL failure")


class SourcePolicyTests(unittest.TestCase):
    def probe(self, entries, rows=(), **kwargs):
        cli = FakeCLI({"acore_world": rows}, **kwargs)
        result = source_policy_probe(cli, None, DatabasePlan(DATABASE_SHA256, tuple(entries), 100))
        return cli, result["acore_world"]

    def test_case_sensitive_reapply_and_rehash_execute_before_receipt(self):
        cli, stats = self.probe([entry("a.sql"), entry("b.sql"), entry("c.sql")],
            [("a.sql", "a" * 40, "RELEASED"), ("b.sql", "", "RELEASED"),
             ("c.sql", "A" * 40, "PENDING")])
        self.assertEqual(cli.imported, ["a.sql"])
        self.assertEqual(stats["reapplied"], 1)
        self.assertEqual(stats["rehashed"], 1)
        self.assertEqual(stats["verified"], 1)
        self.assertEqual(stats["stateUpdated"], 1)
        self.assertEqual(sum("REPLACE" in sql for sql in cli.statements), 2)

    def test_sql_failure_never_writes_a_receipt_or_continues(self):
        cli = FakeCLI(fail=True)
        with self.assertRaises(GateError):
            source_policy_probe(cli, None, DatabasePlan(DATABASE_SHA256, (entry("a.sql"), entry("b.sql")), 100))
        self.assertEqual(cli.imported, ["a.sql"])
        self.assertEqual(cli.statements, [])

    def test_rename_preserves_receipt_without_replay_and_ambiguity_fails_closed(self):
        cli, stats = self.probe([entry("new.sql")], [("old.sql", "A" * 40, "ARCHIVED")])
        self.assertEqual(cli.imported, [])
        self.assertEqual(stats["renamed"], 1)
        self.assertIn("SET name='new.sql' WHERE name='old.sql'", cli.statements[0])
        with self.assertRaises(GateError):
            self.probe([entry("new.sql")], [("old.sql", "A" * 40, "RELEASED"), ("other.sql", "A" * 40, "RELEASED")])

    def test_release_pass_precedes_pending_and_cleanup_uses_original_orphan_count(self):
        cli, stats = self.probe([entry("a.sql", pending=True), entry("z.sql")],
                               [(f"old{i}.sql", "C" * 40, "RELEASED") for i in range(4)])
        self.assertEqual(cli.imported, ["z.sql", "a.sql"])
        self.assertEqual(stats["orphanRemaining"], 4)
        self.assertNotIn("orphanDeleted", stats)
        cli, stats = self.probe([], [("old.sql", "C" * 40, "RELEASED"), ("module.sql", "D" * 40, "MODULE")])
        self.assertEqual(stats["orphanDeleted"], 1)
        self.assertEqual(stats["orphanRemaining"], 1)
        self.assertEqual(stats["orphanWarnings"], 1)
        self.assertNotIn("module.sql", "\n".join(cli.statements))


class WorldLockTests(unittest.TestCase):
    def fixture(self):
        profile_name = "android-arm64-world-v7"
        profile_record = json.loads((ROOT / "native-port/profiles.json").read_text())["profiles"][profile_name]
        profile = profile_record["result_inventory"]
        source = {"sourceArchiveSha256": SOURCE_SHA256, "sourceProfile": profile_name,
                  "sourceTreeSha256": profile["tree_sha256"], "moduleCount": 52, "moduleSqlCount": 0,
                  "moduleDirectories": ["modules/" + name for name in profile["modules"]], "moduleSqlDirectories": [],
                  "sourceAudit": expected_source_audit(profile_record), "private": "PRIVATE_SQL_VALUE"}
        schemas = [{"name": name, "tableNames": ["synthetic"], "triggerNames": [], "routineCount": 0, "eventCount": 0,
                    "receiptCount": 1, "receiptBytes": 90, "receiptSha256": "d" * 64,
                    "columnCount": 1, "columnBytes": 90, "columnSha256": "e" * 64,
                    "releaseReceiptCount": 0, "releaseReceiptBytes": 0, "releaseReceiptSha256": EMPTY_SHA256,
                    "private": "PRIVATE_SQL_VALUE"} for name in SCHEMAS]
        measured = {"formatVersion": 1, "archiveSha256": DATABASE_SHA256, "providerVersion": "12.3.2-MariaDB",
                    "scope": "host-source-policy-model-only", "policy": deepcopy(POLICY), "schemas": schemas,
                    "schemaPrivilegesOnly": True, "runtimeClientDefaultCharset": "utf8",
                    "sourcePolicyAudit": deepcopy(host_policy_source_audit(source["sourceAudit"])),
                    "transitions": {name: {"recentBefore": 2, "archivedBefore": 3, "applied": 1, "orphanWarnings": 2} for name in SCHEMAS},
                    "private": "PRIVATE_SQL_VALUE"}
        plan = DatabasePlan(DATABASE_SHA256, tuple(entry(f"synthetic_{i:03}.sql") for i in range(236)), 2596)
        return plan, source, measured

    def test_metadata_allowlist_preserves_all236_and_never_claims_ready(self):
        plan, source, measured = self.fixture()
        lock = world_updater_lock(plan, 123, source, measured)
        self.assertEqual(len(lock["runtimeSqlFiles"]), 236)
        self.assertEqual(lock["runtimeSqlFiles"][0]["targetPath"], "Data/data/sql/updates/db_world/synthetic_000.sql")
        self.assertEqual(lock["runtimeSqlFiles"][0]["sha1"], "A" * 40)
        self.assertEqual(len(lock["includeDirectories"]), 12)
        self.assertFalse(lock["constraints"]["worldReady"])
        self.assertFalse(lock["constraints"]["failedSqlAllowed"])
        self.assertFalse(lock["expectedResultEvidence"]["compiledWorldExecutionQualified"])
        self.assertFalse(lock["policy"]["dryRun"])
        self.assertEqual(lock["nativeBoundary"]["updatePhases"][0]["completionMarker"],
                         ">> Applied 1 query. Containing 2 new and 3 archived updates.")
        self.assertNotIn("PRIVATE_SQL_VALUE", json.dumps(lock))
        reverse = DatabasePlan(plan.archive_sha256, tuple(reversed(plan.entries)), 2596)
        self.assertEqual(lock, world_updater_lock(reverse, 123, source, measured))

    def test_wrong_measurements_profiles_or_remaining_routines_fail_closed(self):
        plan, source, measured = self.fixture()
        for field, value in (("scope", "compiled-world-passed"), ("formatVersion", True),
                             ("providerVersion", "12.3.3-MariaDB"), ("policy", {**POLICY, "dryRun": True})):
            bad = deepcopy(measured); bad[field] = value
            with self.subTest(field=field), self.assertRaises(GateError):
                world_updater_lock(plan, 123, source, bad)
        bad = deepcopy(measured); bad["sourcePolicyAudit"] = []
        with self.assertRaises(GateError): world_updater_lock(plan, 123, source, bad)
        for key in ("redundancy", "allowRehash", "archivedRedundancy", "dryRun"):
            bad = deepcopy(measured); bad["policy"][key] = int(bad["policy"][key])
            with self.subTest(key=key), self.assertRaises(GateError):
                world_updater_lock(plan, 123, source, bad)
        for field, value in (("routineCount", 1), ("eventCount", 1), ("columnCount", True),
                             ("tableNames", ["synthetic", "synthetic"]), ("tableNames", [None]),
                             ("releaseReceiptSha256", "a" * 64), ("receiptBytes", 0)):
            bad = deepcopy(measured); bad["schemas"][0][field] = value
            with self.subTest(field=field), self.assertRaises(GateError):
                world_updater_lock(plan, 123, source, bad)
        for field, value in (("moduleSqlCount", 1), ("sourceProfile", "unknown"), ("sourceTreeSha256", "a" * 64),
                             ("moduleDirectories", []), ("moduleSqlDirectories", ["modules/unreviewed/data/sql"]),
                             ("sourceAudit", [{"path": path, "sha256": "c" * 64} for path in AUDIT_FILES])):
            bad = deepcopy(source); bad[field] = value
            with self.subTest(field=field), self.assertRaises(GateError):
                world_updater_lock(plan, 123, bad, measured)

    def test_original_runtime_tree_requires_real_include_directories_and_no_extra_payload(self):
        plan, _, _ = self.fixture()
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "synthetic.zip"
            for missing, extra in ((False, False), (True, False), (False, True)):
                with zipfile.ZipFile(archive, "w") as bundle:
                    for index, item in enumerate(include_directories()):
                        if not missing or index:
                            bundle.writestr("Database/RuntimeSQL/Data/" + item["path"] + "/", "")
                    for item in plan.updates():
                        bundle.writestr(item.path, "synthetic")
                    if extra:
                        bundle.writestr("Database/RuntimeSQL/Data/data/sql/custom/db_world/unreviewed.sql", "synthetic")
                if missing or extra:
                    with self.assertRaises(GateError): verify_runtime_layout(archive, plan)
                else:
                    verify_runtime_layout(archive, plan)

    def test_queue_source_pin_preserves_the_existing_guarded_host_model_scope(self):
        plan, source, measured = self.fixture()
        original_measurement = deepcopy(measured)
        lock = world_updater_lock(plan, 123, source, measured)
        self.assertEqual(len(lock["sourceAudit"]), 10)
        self.assertEqual(len(measured["sourcePolicyAudit"]), 9)
        self.assertEqual(measured, original_measurement)
        self.assertEqual(lock["sourceAudit"][-1], {
            "path": "modules/mod-world-inspector-runtime/src/WorldInspectorRuntime.cpp",
            "sha256": "dd31bb4522aff0c33fbd50c3b49697584187f3c41a7e751064e9e218424eb0cd"})
        self.assertFalse(lock["expectedResultEvidence"]["compiledWorldExecutionQualified"])
        for target in ("source", "measurement"):
            bad_source, bad_measured = deepcopy(source), deepcopy(measured)
            if target == "source":
                bad_source["sourceAudit"][-1]["sha256"] = "0" * 64
            else:
                bad_measured["sourcePolicyAudit"].append(deepcopy(source["sourceAudit"][-1]))
            with self.subTest(target=target), self.assertRaises(GateError):
                world_updater_lock(plan, 123, bad_source, bad_measured)

    def test_aggregate_protocol_preserves_case_and_rejects_truncation(self):
        self.assertEqual(parse_aggregate(f"FTA_RELEASES=0:0:0:0:{EMPTY_SHA256}", "FTA_RELEASES", "releaseReceipt", empty=True)["releaseReceiptCount"], 0)
        for value in ("FTA_COLUMNS=1:20:19:0:" + "a" * 64, "FTA_COLUMNS=1:20:20:1:" + "a" * 64,
                      "FTA_COLUMNS=0:0:0:0:" + EMPTY_SHA256, "FTA_COLUMNS=1:20:20:0:" + "A" * 64):
            with self.assertRaises(GateError):
                parse_aggregate(value, "FTA_COLUMNS", "column")
        self.assertIn("HEX(column_default)", column_query("acore_world"))
        self.assertIn("ORDER BY BINARY table_name,ordinal_position", column_query("acore_world"))
        self.assertIn("SHA2('',256)", release_query("acore_auth"))
        self.assertNotIn("UPPER(", release_query("acore_characters"))
        for schema in ("unknown", "acore_world';DROP DATABASE x"):
            with self.assertRaises(GateError): column_query(schema)
            with self.assertRaises(GateError): release_query(schema)

    def test_cli_refuses_archive_aliases_and_source_outputs_before_reading(self):
        tool = ROOT / "tools/create_world_updater_lock.py"
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); archive = root / "private.zip"; archive.write_bytes(b"private original")
            source = root / "source"; source.mkdir(); linked = root / "linked.json"; linked.hardlink_to(archive)
            for output in (archive, linked, source / "new.json"):
                result = subprocess.run([sys.executable, str(tool), str(archive), "--source", str(source),
                    "--measurement", str(root / "measurement.json"), "--output", str(output)], capture_output=True)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(archive.read_bytes(), b"private original")


if __name__ == "__main__":
    unittest.main()
