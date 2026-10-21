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

from tools.fta_database import (GateError, SqlEntry, assert_empty_targets,
                                identifier, inventory, update_action)

from tools.run_database_gate import DatabaseCLI


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
