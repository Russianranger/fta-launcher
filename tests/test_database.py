"""Safety tests use synthetic SQL only. Never load the private seed in unit CI."""
from __future__ import annotations

import hashlib
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools.fta_database import (DATABASE_SHA256, DatabasePlan, GateError, SqlEntry, assert_empty_targets,
                                identifier, inventory, update_action)

from tools.run_database_gate import DatabaseCLI, probe_updates, sandbox
from tools.create_auth_seed_lock import auth_seed_lock


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.archive = Path(self.directory.name) / "database.zip"

    def make(self, entries):
        with zipfile.ZipFile(self.archive, "w") as archive:
            for name, body in entries:
                archive.writestr(name, body)
        return hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def plan(self, digest):
        return inventory(self.archive, expected_sha256=digest, require_release_layout=False)

    def test_plan_orders_tables_before_triggers_and_keeps_sql_private(self):
        digest = self.make([
            ("Database/acore_world/trigger-world_trigger.sql", "DELIMITER $$\nPRIVATE_SQL_RECORD"),
            ("Database/acore_world/table-creature.sql", "PRIVATE_SQL_RECORD"),
            ("Database/acore_auth/table-account.sql", "PRIVATE_SQL_RECORD"),
            ("Database/RuntimeSQL/Data/data/sql/updates/db_world/update.sql", "PRIVATE_SQL_RECORD"),
        ])
        plan = self.plan(digest)
        self.assertEqual([e.kind for e in plan.entries], ["table", "table", "trigger", "update"])
        self.assertEqual(plan.summary()["tables"]["acore_auth"], 1)
        self.assertNotIn("PRIVATE_SQL_RECORD", repr(plan))
        self.assertNotIn("PRIVATE_SQL_RECORD", repr(plan.summary()))
        self.assertEqual(plan.seed()[0].sha256, hashlib.sha256(b"PRIVATE_SQL_RECORD").hexdigest())

    def test_wrong_digest_is_refused_before_zip_processing(self):
        self.make([("Database/acore_auth/table-account.sql", "synthetic")])
        with self.assertRaises(GateError):
            self.plan("0" * 64)

    def test_path_traversal_absolute_windows_and_hidden_segments_refused(self):
        for name in ("Database/../../escape", "/absolute", "C:/absolute", "Database/a\\b",
                     "Database/./a", "Database//a", "outside/file"):
            with self.subTest(name=name):
                digest = self.make([(name, "synthetic")])
                with self.assertRaises(GateError):
                    self.plan(digest)

    def test_crc_corruption_is_detected_even_with_updated_archive_digest(self):
        self.make([("Database/acore_auth/table-account.sql", "unchanged_synthetic_payload")])
        damaged = self.archive.read_bytes().replace(b"unchanged_synthetic_payload", b"different_synthetic_payload")
        self.archive.write_bytes(damaged)
        with self.assertRaises(zipfile.BadZipFile):
            self.plan(hashlib.sha256(damaged).hexdigest())

    def test_case_colliding_duplicates_refused(self):
        digest = self.make([("Database/A.txt", "one"), ("Database/a.txt", "two")])
        with self.assertRaises(GateError):
            self.plan(digest)

    def test_symlinks_refused(self):
        member = zipfile.ZipInfo("Database/link")
        member.create_system = 3
        member.external_attr = (stat.S_IFLNK | 0o777) << 16
        digest = self.make([(member, "../../outside")])
        with self.assertRaises(GateError):
            self.plan(digest)

    def test_size_and_entry_limits_refused(self):
        digest = self.make([("Database/acore_auth/table-account.sql", "12345")])
        for field in ("MAX_ENTRY_BYTES", "MAX_TOTAL_BYTES"):
            with self.subTest(field=field), patch("tools.fta_database." + field, 4):
                with self.assertRaises(GateError):
                    self.plan(digest)
        with patch("tools.fta_database.MAX_ENTRIES", 0):
            with self.assertRaises(GateError):
                self.plan(digest)

    def test_minimal_fixture_cannot_pass_release_layout_gate(self):
        digest = self.make([("Database/acore_auth/table-account.sql", "synthetic")])
        with self.assertRaises(GateError):
            inventory(self.archive, expected_sha256=digest)

    def test_empty_seed_and_ambiguous_update_names_refused(self):
        for entries in ([('Database/acore_auth/table-account.sql', '')], [
            ('Database/RuntimeSQL/Data/data/sql/updates/db_world/a.sql', 'one'),
            ('Database/RuntimeSQL/Data/data/sql/updates/pending_db_world/a.sql', 'two')]):
            digest = self.make(entries)
            with self.assertRaises(GateError):
                self.plan(digest)


class FreshInstallTests(unittest.TestCase):
    def test_checks_all_four_catalogs_before_mutation(self):
        queries = []
        def query(sql):
            queries.append(sql)
            return "0"
        assert_empty_targets(query)
        self.assertEqual(len(queries), 4)
        for sql in queries:
            self.assertIn("acore_auth", sql)
            self.assertIn("acore_characters", sql)
            self.assertIn("acore_world", sql)
            self.assertTrue(sql.startswith("SELECT"))

    def test_existing_tables_views_routines_triggers_or_events_refused(self):
        for nonempty_at in range(4):
            results = iter(["0"] * nonempty_at + ["1"])
            with self.subTest(catalog=nonempty_at), self.assertRaises(GateError):
                assert_empty_targets(lambda sql: next(results))

    def test_unknown_empty_state_is_refused(self):
        for output in ("", "NULL", "0\n0", "٠", "-1", "cannot connect"):
            with self.subTest(output=output), self.assertRaises(GateError):
                assert_empty_targets(lambda sql: output)

    def test_connection_error_is_not_treated_as_empty(self):
        def broken(sql):
            raise GateError("connection failed")
        with self.assertRaises(GateError):
            assert_empty_targets(broken)

    def test_identifier_injection_refused(self):
        for value in ("auth`; DROP DATABASE x; --", "auth'", "two.words", "a" * 65):
            with self.assertRaises(GateError):
                identifier(value)
        self.assertEqual(identifier("acore_world"), "`acore_world`")

    def test_existing_receipts_never_replayed_or_overwritten(self):
        entry = SqlEntry("synthetic.sql", "acore_world", "update", "synthetic.sql", 1, "a" * 64, "b" * 40)
        self.assertEqual(update_action(entry, None), "apply")
        self.assertEqual(update_action(entry, "B" * 40), "verified_existing")
        for receipt in ("", "c" * 40, "bad-value"):
            with self.assertRaises(GateError):
                update_action(entry, receipt)


class AuthSeedManifestTests(unittest.TestCase):
    def plan(self):
        entries = [SqlEntry(f"Database/acore_auth/table-t{i:02}.sql", "acore_auth", "table", f"t{i:02}",
                            i + 1, "a" * 64, "b" * 40) for i in range(27)]
        entries += [SqlEntry("Database/RuntimeSQL/Data/data/sql/updates/db_auth/auth.sql", "acore_auth", "update",
                             "auth.sql", 20, "c" * 64, "d" * 40)]
        # A complete archive plan may include other private schemas; none belongs
        # in the auth-only Android lock, even when its object name looks valid.
        entries += [SqlEntry("Database/acore_characters/table-private_fixture.sql", "acore_characters", "table",
                             "private_fixture", 12, "e" * 64, "f" * 40)]
        return DatabasePlan(DATABASE_SHA256, tuple(entries), 100)

    def test_manifest_contains_only_auth_metadata_and_disables_listener(self):
        manifest = auth_seed_lock(self.plan(), 123)
        self.assertEqual(manifest["formatVersion"], 1)
        self.assertEqual(len(manifest["files"]), 28)
        self.assertEqual(sum(f["role"] == "auth-table" for f in manifest["files"]), 27)
        self.assertEqual(sum(f["role"] == "auth-update" for f in manifest["files"]), 1)
        self.assertNotIn("private_fixture", repr(manifest))
        self.assertFalse(manifest["constraints"]["authTcpListener"])
        self.assertFalse(manifest["constraints"]["liveDatabaseAccess"])
        self.assertTrue(manifest["constraints"]["freshDisposableDatadirOnly"])
        for entry in manifest["files"]:
            self.assertEqual(set(entry), {"path", "role", "targetPath", "size", "sha256"})
        self.assertEqual(manifest["files"][-1]["targetPath"], "Data/data/sql/updates/db_auth/auth.sql")

    def test_manifest_generation_is_deterministic(self):
        plan = self.plan()
        reversed_plan = DatabasePlan(plan.archive_sha256, tuple(reversed(plan.entries)), plan.uncompressed_bytes)
        self.assertEqual(auth_seed_lock(plan, 123), auth_seed_lock(reversed_plan, 123))

    def test_manifest_refuses_unreviewed_or_incomplete_auth_plan(self):
        plan = self.plan()
        for invalid in (DatabasePlan("0" * 64, plan.entries, 100),
                        DatabasePlan(plan.archive_sha256, plan.entries[1:], 100)):
            with self.assertRaises(GateError):
                auth_seed_lock(invalid, 123)


class UpdaterPolicyTests(unittest.TestCase):
    def setUp(self):
        self.entry = SqlEntry("synthetic.sql", "acore_world", "update", "synthetic.sql", 1, "a" * 64, "b" * 40)
        self.plan = DatabasePlan("a" * 64, (self.entry,), 1)

    def fake_cli(self, *, fail_script=False, hash_value="c" * 40, state="CUSTOM"):
        class FakeCLI:
            def __init__(self):
                self.hash = hash_value
                self.state = state
                self.events = []
            def query(self, sql):
                if "CONCAT('receipt:'" in sql:
                    return "receipt:" + self.hash + ":" + self.state
                if "SELECT CONCAT(`hash`" in sql:
                    return self.hash + ":" + self.state
                if "fta_release_updates" in sql:
                    return "1"
                if "information_schema.routines" in sql:
                    return "0"
                raise AssertionError(sql)
            def import_member(self, archive, entry):
                self.events.append("import")
                if fail_script:
                    raise GateError("Database CLI failed (exit 1) SQL error 1644")
            def run(self, *, sql, capture):
                if sql.startswith("REPLACE"):
                    self.events.append("replace_receipt")
                    self.hash = "B" * 40
                    self.state = "RELEASED"
                elif sql.startswith("UPDATE"):
                    self.events.append("update_state")
                    self.state = "RELEASED"
                else:
                    raise AssertionError(sql)
        return FakeCLI()

    def progress(self):
        return {"verified_existing": 0, "applied_in_disposable_database": 0}

    def test_strict_mode_blocks_changed_receipt_without_import(self):
        cli = self.fake_cli()
        with self.assertRaises(GateError):
            probe_updates(cli, None, self.plan, self.progress())
        self.assertEqual(cli.events, [])
        self.assertEqual(cli.hash, "c" * 40)

    def test_source_policy_reapplies_before_replacing_receipt(self):
        cli = self.fake_cli()
        progress = self.progress()
        probe_updates(cli, None, self.plan, progress, probe_updater_policy=True)
        self.assertEqual(cli.events, ["import", "replace_receipt"])
        transition = progress["receipt_transitions"][0]
        self.assertEqual(transition["receipt_before_sha1"], "c" * 40)
        self.assertEqual(transition["receipt_after_sha1"], "B" * 40)
        self.assertEqual(progress["reapplied_changed"], 1)

    def test_failed_script_never_replaces_receipt(self):
        cli = self.fake_cli(fail_script=True)
        progress = self.progress()
        with self.assertRaises(GateError):
            probe_updates(cli, None, self.plan, progress, probe_updater_policy=True)
        self.assertEqual(cli.events, ["import"])
        transition = progress["receipt_transitions"][0]
        self.assertEqual(transition["receipt_before_sha1"], transition["receipt_after_sha1"])
        self.assertEqual(transition["status"], "sql_failed_without_receipt_replacement")

    def test_matching_hash_only_updates_state_when_needed(self):
        cli = self.fake_cli(hash_value="B" * 40)
        progress = self.progress()
        probe_updates(cli, None, self.plan, progress, probe_updater_policy=True)
        self.assertEqual(cli.events, ["update_state"])
        self.assertEqual(progress["updated_states"], 1)
        self.assertEqual(progress["verified_existing"], 1)

    def test_policy_uses_original_uppercase_receipt_encoding(self):
        self.assertEqual(update_action(self.entry, "b" * 40, probe_updater_policy=True), "reapply_changed")
        self.assertEqual(update_action(self.entry, "B" * 40, probe_updater_policy=True), "verified_existing")

    def test_empty_or_malformed_receipts_remain_refused_in_policy_probe(self):
        for receipt in ("", "not-a-sha1"):
            with self.assertRaises(GateError):
                update_action(self.entry, receipt, probe_updater_policy=True)


class CliSafetyTests(unittest.TestCase):
    def test_error_output_does_not_expose_seed_rows(self):
        with tempfile.TemporaryDirectory() as work:
            cli = DatabaseCLI(sys.executable, Path(work) / "socket", Path(work))
            cli.command = [sys.executable, "-c", "import sys; sys.stderr.write('ERROR 1273 (HY000) at line 29: SECRET_SEED_VALUE'); sys.exit(1)"]
            with self.assertRaises(GateError) as raised:
                cli.run()
            message = str(raised.exception)
            self.assertIn("1273", message)
            self.assertIn("29", message)
            self.assertNotIn("SECRET_SEED_VALUE", message)

    def test_cli_streams_entire_delimiter_script_unchanged(self):
        with tempfile.TemporaryDirectory() as work:
            cli = DatabaseCLI(sys.executable, Path(work) / "socket", Path(work))
            cli.command = [sys.executable, "-c", "import sys; sys.stdout.buffer.write(sys.stdin.buffer.read())"]
            script = b"DELIMITER $$\nCREATE PROCEDURE test() BEGIN SELECT 1; END$$\nDELIMITER ;\n"
            with tempfile.TemporaryFile() as source:
                source.write(script)
                source.seek(0)
                self.assertEqual(cli.run(script=source), script.decode().strip())

    def test_report_cannot_replace_input_or_hardlink(self):
        runner = Path(__file__).resolve().parents[1] / "tools" / "run_database_gate.py"
        with tempfile.TemporaryDirectory() as work:
            archive = Path(work) / "private.zip"
            archive.write_bytes(b"private original bytes")
            hardlink = Path(work) / "report.json"
            hardlink.hardlink_to(archive)
            for report in (archive, hardlink):
                result = subprocess.run([sys.executable, str(runner), str(archive), "--report", str(report)],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(archive.read_bytes(), b"private original bytes")

    def test_loopback_requires_file_credentials_and_fixed_host(self):
        with tempfile.TemporaryDirectory() as work:
            directory = Path(work)
            for kwargs in ({"port": 32123}, {"defaults_file": directory / "client.cnf"}):
                with self.assertRaises(GateError):
                    DatabaseCLI("mariadb", directory / "socket", directory, **kwargs)
            cli = DatabaseCLI("mariadb", directory / "socket", directory,
                              port=32123, defaults_file=directory / "client.cnf")
            self.assertTrue(cli.command[1].startswith("--defaults-file="))
            self.assertIn("--host=127.0.0.1", cli.command)
            self.assertIn("--protocol=TCP", cli.command)
            self.assertFalse(any(a.startswith("--password") for a in cli.command))

    def test_loopback_bootstrap_uses_restricted_files_before_listener(self):
        observed = {}
        def fake_popen(command, **kwargs):
            work = Path(kwargs["cwd"])
            options = work / "client.cnf"
            bootstrap = work / "bootstrap.sql"
            observed["mode_options"] = stat.S_IMODE(options.stat().st_mode)
            observed["mode_bootstrap"] = stat.S_IMODE(bootstrap.stat().st_mode)
            password = options.read_text().split("password=", 1)[1].strip()
            observed["secret_length"] = len(password)
            observed["secret_in_command"] = password in repr(command)
            observed["loopback_bound"] = "--bind-address=127.0.0.1" in command
            observed["no_unix_bind"] = "--socket=" in command
            observed["bootstrap_before_listener"] = password in bootstrap.read_text()
            process = type("ExitedProcess", (), {"poll": lambda self: 1})()
            return process
        with patch("tools.run_database_gate.subprocess.run") as initialize, \
                patch("tools.run_database_gate.subprocess.Popen", side_effect=fake_popen):
            initialize.return_value.returncode = 0
            with self.assertRaises(GateError):
                with sandbox("server", "initializer", "client", transport="loopback"):
                    self.fail("Exited mock database must not become ready")
            self.assertIn("--auth-root-authentication-method=socket", initialize.call_args.args[0])
        self.assertEqual(observed["mode_options"], 0o600)
        self.assertEqual(observed["mode_bootstrap"], 0o600)
        self.assertEqual(observed["secret_length"], 64)
        self.assertFalse(observed["secret_in_command"])
        self.assertTrue(observed["loopback_bound"])
        self.assertTrue(observed["no_unix_bind"])
        self.assertTrue(observed["bootstrap_before_listener"])

    def test_defaults_and_network_cannot_redirect_private_socket(self):
        with tempfile.TemporaryDirectory() as work:
            with patch.dict("os.environ", {"MYSQL_HOST": "remote.example", "MYSQL_PWD": "secret"}):
                cli = DatabaseCLI("mariadb", Path(work) / "socket", Path(work))
            self.assertEqual(cli.command[1], "--no-defaults")
            self.assertIn("--protocol=SOCKET", cli.command)
            self.assertIn("--binary-mode", cli.command)
            self.assertIn("--local-infile=0", cli.command)
            self.assertNotIn("MYSQL_HOST", cli.environment)
            self.assertNotIn("MYSQL_PWD", cli.environment)


if __name__ == "__main__":
    unittest.main()
