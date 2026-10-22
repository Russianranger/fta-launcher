#!/usr/bin/env python3
"""Run the private seed in a new disposable MariaDB instance."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import shutil
import signal
import secrets
import socket as socket_module
import subprocess
import tempfile
import time
import zipfile

try:
    from .fta_database import (GateError, SCHEMAS, DatabasePlan, SqlEntry,
                              assert_empty_targets, identifier, inventory, update_action)
except ImportError:  # Direct `python tools/run_database_gate.py` invocation.
    from fta_database import (GateError, SCHEMAS, DatabasePlan, SqlEntry,
                              assert_empty_targets, identifier, inventory, update_action)


class DatabaseCLI:
    def __init__(self, executable: str, socket: Path, work: Path, *,
                 port: int | None = None, defaults_file: Path | None = None):
        self.work = work
        if (port is None) != (defaults_file is None):
            raise GateError("Loopback transport requires private credentials")
        if port is None:
            connection = ["--no-defaults", "--protocol=SOCKET", f"--socket={socket}", "--user=root"]
        else:
            if not 1 <= port <= 65535:
                raise GateError("Invalid internal loopback port")
            connection = [f"--defaults-file={defaults_file}", "--protocol=TCP", "--host=127.0.0.1", f"--port={port}"]
        self.command = [executable, *connection, "--batch", "--skip-column-names", "--binary-mode",
                        "--local-infile=0", "--default-character-set=utf8mb4",
                        "--max-allowed-packet=256M"]
        self.environment = {k: v for k, v in os.environ.items() if not k.startswith(("MYSQL", "MARIADB"))}
        self.environment.update(HOME=str(work), MYSQL_HISTFILE="/dev/null")

    def run(self, *, sql: str | None = None, script=None, schema: str | None = None,
            timeout: int = 300, capture: bool = True) -> str:
        args = self.command.copy()
        if schema:
            identifier(schema)
            if schema not in SCHEMAS:
                raise GateError("Refused unknown database")
            args.append("--database=" + schema)
        if sql is not None:
            args.append("--execute=" + sql)
        with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
            try:
                result = subprocess.run(args, stdin=script if script is not None else subprocess.DEVNULL,
                                        stdout=output if capture else subprocess.DEVNULL, stderr=errors, env=self.environment,
                                        cwd=self.work, timeout=timeout, check=False)
            except subprocess.TimeoutExpired:
                raise GateError("Database CLI operation timed out") from None
            if result.returncode:
                errors.seek(0)
                diagnostic = errors.read(16384).decode("utf-8", errors="replace")
                # Database tools can echo INSERT rows/passwords. Never publish raw output.
                match = re.search(r"ERROR\s+(\d+)(?:\s+\([A-Z0-9]+\))?(?:\s+at\s+line\s+(\d+))?", diagnostic)
                metadata = f" SQL error {match[1]}" if match else ""
                if match and match[2]:
                    metadata += " at script line " + match[2]
                raise GateError(f"Database CLI failed (exit {result.returncode}){metadata}")
            if not capture:
                return ""
            output.seek(0, 2)
            if output.tell() > 1024 * 1024:
                raise GateError("Database metadata output limit exceeded")
            output.seek(0)
            return output.read().decode("utf-8", errors="strict").strip()

    def query(self, sql: str) -> str:
        return self.run(sql=sql, timeout=60)

    def import_member(self, archive: zipfile.ZipFile, entry: SqlEntry) -> None:
        # One member at a time, disk-backed; CRC was already checked in inventory.
        with tempfile.TemporaryFile(dir=self.work) as script, archive.open(entry.path) as source:
            shutil.copyfileobj(source, script, length=1024 * 1024)
            script.seek(0)
            self.run(script=script, schema=entry.schema, capture=False)


def executable(value: str) -> str:
    resolved = shutil.which(value)
    if not resolved:
        raise GateError("Required MariaDB executable is unavailable")
    return str(Path(resolved).resolve())


@contextmanager
def sandbox(server: str, initializer: str, client: str, *, transport: str = "unix"):
    # No external socket, data directory, host, password, or destructive flag exists.
    if transport not in ("unix", "loopback"):
        raise GateError("Unsupported private database transport")
    if shutil.disk_usage(tempfile.gettempdir()).free < 3 * 1024 * 1024 * 1024:
        raise GateError("Disposable database requires at least 3 GiB free temporary storage")
    with tempfile.TemporaryDirectory(prefix="fta-db-") as name:
        work = Path(name)
        os.chmod(work, 0o700)
        provider_base = Path(server).resolve().parent.parent
        data = work / "data"
        tmp = work / "tmp"
        tmp.mkdir(mode=0o700)
        socket = work / "db.sock"
        env = {k: v for k, v in os.environ.items() if not k.startswith(("MYSQL", "MARIADB"))}
        env.update(HOME=str(work), MYSQL_HISTFILE="/dev/null")
        with (work / "install.log").open("wb") as install_log:
            try:
                result = subprocess.run([initializer, "--no-defaults", f"--basedir={provider_base}", f"--datadir={data}",
                                         "--auth-root-authentication-method=" + ("socket" if transport == "loopback" else "normal"), "--skip-test-db"],
                                        stdout=install_log, stderr=subprocess.STDOUT,
                                        cwd=work, env=env, timeout=120, check=False)
            except subprocess.TimeoutExpired:
                raise GateError("MariaDB initialization timed out") from None
        if result.returncode:
            raise GateError("MariaDB initialization failed; private diagnostics were discarded")
        command = [server, "--no-defaults", f"--basedir={provider_base}", f"--datadir={data}", f"--socket={socket}",
                   f"--pid-file={work / 'db.pid'}", f"--log-error={work / 'server.log'}",
                   f"--tmpdir={tmp}", "--skip-name-resolve",
                   "--local-infile=0", "--innodb-buffer-pool-size=128M",
                   "--innodb-log-file-size=64M", "--max-allowed-packet=256M",
                   "--max-connections=10", "--performance-schema=OFF", f"--secure-file-priv={work}"]
        port = None
        defaults_file = None
        if transport == "unix":
            command.append("--skip-networking")
        else:
            # This optional host-only transport exists for environments that deny
            # Unix socket binding. No caller-supplied host, port, or credentials.
            with socket_module.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
            password = secrets.token_hex(32)
            defaults_file = work / "client.cnf"
            with open(defaults_file, "x", opener=lambda p, flags: os.open(p, flags, 0o600)) as options:
                options.write("[client]\nuser=fta_gate\npassword=" + password + "\n")
            init_file = work / "bootstrap.sql"
            with open(init_file, "x", opener=lambda p, flags: os.open(p, flags, 0o600)) as bootstrap:
                bootstrap.write("CREATE USER 'fta_gate'@'127.0.0.1' IDENTIFIED BY '" + password + "';\n"
                                "GRANT ALL PRIVILEGES ON *.* TO 'fta_gate'@'127.0.0.1' WITH GRANT OPTION;\n")
            # Root accounts retain Unix-socket auth, so none accepts a blank TCP
            # login. The new loopback account is provisioned before listening.
            command.extend(["--socket=", "--bind-address=127.0.0.1", f"--port={port}", f"--init-file={init_file}"])
            password = ""
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            command.append("--user=root")
        with (work / "process.log").open("wb") as process_log:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=process_log,
                                       stderr=subprocess.STDOUT, cwd=work, env=env,
                                       start_new_session=True)
            cli = DatabaseCLI(client, socket, work, port=port, defaults_file=defaults_file)
            try:
                deadline = time.monotonic() + 60
                while True:
                    if process.poll() is not None:
                        raise GateError("Disposable MariaDB exited before readiness")
                    try:
                        if ((port is not None or socket.exists())
                                and cli.query("SELECT @@pid_file;") == str(work / "db.pid")):
                            break
                    except GateError:
                        pass
                    if time.monotonic() >= deadline:
                        raise GateError("Disposable MariaDB readiness timed out")
                    time.sleep(0.2)
                yield cli
            finally:
                # SIGTERM is MariaDB's graceful database shutdown request.
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait(timeout=10)


def inspect_seed(cli: DatabaseCLI, plan: DatabasePlan) -> dict:
    summary = {}
    for schema in SCHEMAS:
        expected = {e.name for e in plan.seed() if e.schema == schema and e.kind == "table"}
        actual = set(filter(None, cli.query("SELECT table_name FROM information_schema.tables "
                     f"WHERE table_schema='{schema}' AND table_type='BASE TABLE' ORDER BY table_name;").splitlines()))
        if actual != expected:
            raise GateError("Imported table names differ from the archive plan")
        wanted_triggers = {e.name for e in plan.seed() if e.schema == schema and e.kind == "trigger"}
        triggers = set(filter(None, cli.query("SELECT trigger_name FROM information_schema.triggers "
                       f"WHERE trigger_schema='{schema}' ORDER BY trigger_name;").splitlines()))
        if triggers != wanted_triggers:
            raise GateError("Imported trigger names differ from the archive plan")
        # Counts and shape queries exercise representative core access without exporting rows.
        checks = {"acore_auth": ("account", "realmlist", "build_info"),
                  "acore_characters": ("characters", "character_inventory", "updates"),
                  "acore_world": ("creature_template", "item_template", "quest_template", "updates")}[schema]
        counts = {}
        for table in checks:
            count = cli.query(f"SELECT COUNT(*) FROM {identifier(schema)}.{identifier(table)};")
            if not count.isdigit():
                raise GateError("Invalid representative row count")
            counts[table] = int(count)
        shape = cli.query("SELECT table_name,column_name,ordinal_position,column_type,is_nullable,"
                          "COALESCE(column_default,'<NULL>'),COALESCE(collation_name,'<NULL>'),extra "
                          f"FROM information_schema.columns WHERE table_schema='{schema}' "
                          "ORDER BY table_name,ordinal_position;")
        import hashlib
        shape_digest = hashlib.sha256(shape.encode("utf-8")).hexdigest()
        receipt_count = cli.query(f"SELECT COUNT(*) FROM {identifier(schema)}.`updates` WHERE `hash` REGEXP '^[0-9A-Fa-f]{{40}}$';")
        if not receipt_count.isdigit() or not int(receipt_count):
            raise GateError("Missing or invalid updater receipts")
        summary[schema] = {"tables_verified": len(actual), "triggers_verified": len(triggers),
                           "representative_row_counts": counts,
                           "valid_update_receipts": int(receipt_count),
                           "column_schema_sha256": shape_digest}
    return summary


def probe_updates(cli: DatabaseCLI, archive: zipfile.ZipFile, plan: DatabasePlan, progress: dict,
                  *, probe_updater_policy: bool = False) -> None:
    """Execute unchanged SQL in a disposable DB, with an explicit receipt policy.

    Default strict mode refuses changed hashes. The opt-in policy probe follows
    the source UpdateFetcher changed-file path: execute first, then REPLACE its
    receipt only after success. It never implements orphan deletion or rehashing.
    """
    transitions = progress.setdefault("receipt_transitions", [])
    progress["policy"] = "original_changed_hash_reapply_subset" if probe_updater_policy else "strict"
    progress.setdefault("reapplied_changed", 0)
    progress.setdefault("updated_states", 0)
    for entry in plan.updates():
        progress["current_file"] = entry.path
        existing = cli.query(f"SELECT CONCAT('receipt:',`hash`,':',`state`) FROM {identifier(entry.schema)}.`updates` WHERE `name`='{entry.name}';")
        if "\n" in existing:
            raise GateError("Duplicate runtime update receipt")
        if existing and not existing.startswith("receipt:"):
            raise GateError("Invalid runtime update receipt response")
        before_hash = before_state = None
        if existing:
            fields = existing.split(":")
            if len(fields) != 3 or fields[2] not in ("RELEASED", "CUSTOM", "MODULE", "ARCHIVED", "PENDING"):
                raise GateError("Invalid runtime update receipt response")
            before_hash, before_state = fields[1:]
        state = "PENDING" if "/pending_" in entry.path else "RELEASED"
        transition = {"schema": entry.schema, "file": entry.name,
                      "receipt_before_sha1": before_hash, "receipt_before_state": before_state,
                      "archive_raw_sha1": entry.sha1, "status": "pending"}
        transitions.append(transition)
        action = update_action(entry, before_hash, probe_updater_policy=probe_updater_policy)
        transition["action"] = action
        if action == "verified_existing":
            progress["verified_existing"] += 1
            if probe_updater_policy and before_state != state:
                cli.run(sql=f"UPDATE {identifier(entry.schema)}.`updates` SET `state`='{state}' WHERE `name`='{entry.name}';", capture=False)
                progress["updated_states"] += 1
            transition.update(status="verified", receipt_after_sha1=before_hash,
                              receipt_after_state=state if probe_updater_policy else before_state)
            continue
        began = time.monotonic()
        try:
            cli.import_member(archive, entry)
        except GateError:
            transition["status"] = "sql_failed_without_receipt_replacement"
            # Record actual metadata after a partial SQL failure without masking
            # the original error or assuming the script was transactional.
            try:
                after_failure = cli.query(f"SELECT CONCAT(`hash`,':',`state`) FROM {identifier(entry.schema)}.`updates` WHERE `name`='{entry.name}';")
                if re.fullmatch(r"[A-Fa-f0-9]{40}:(?:RELEASED|CUSTOM|MODULE|ARCHIVED|PENDING)", after_failure):
                    failed_hash, failed_state = after_failure.split(":")
                    transition.update(receipt_after_sha1=failed_hash, receipt_after_state=failed_state)
                else:
                    transition["receipt_after_query"] = "missing_or_invalid"
            except GateError:
                transition["receipt_after_query"] = "unavailable"
            # Original UpdateFetcher also does not replace a failed script's receipt.
            raise
        speed = max(0, int((time.monotonic() - began) * 1000))
        verb = "REPLACE" if probe_updater_policy else "INSERT"
        stored_hash = entry.sha1.upper() if probe_updater_policy else entry.sha1
        cli.run(sql=f"{verb} INTO {identifier(entry.schema)}.`updates` (`name`,`hash`,`state`,`speed`) "
                    f"VALUES ('{entry.name}','{stored_hash}','{state}',{speed});", capture=False)
        after = cli.query(f"SELECT CONCAT(`hash`,':',`state`) FROM {identifier(entry.schema)}.`updates` WHERE `name`='{entry.name}';")
        if after != stored_hash + ":" + state:
            raise GateError("Updated runtime receipt did not match the successful SQL script")
        after_hash, after_state = after.split(":")
        transition.update(status="applied", receipt_after_sha1=after_hash, receipt_after_state=after_state)
        if action == "reapply_changed":
            progress["reapplied_changed"] += 1
        else:
            progress["applied_in_disposable_database"] += 1
    progress.pop("current_file", None)
    for schema in ("acore_characters", "acore_world"):
        count = cli.query(f"SELECT COUNT(*) FROM {identifier(schema)}.`fta_release_updates` WHERE status='APPLIED' AND sha256 REGEXP '^[0-9A-Fa-f]{{64}}$';")
        if not count.isdigit() or not int(count):
            raise GateError("Missing valid release receipts after runtime SQL probe")
        progress[schema + "_release_receipts"] = int(count)
    progress["remaining_routines"] = int(cli.query("SELECT COUNT(*) FROM information_schema.routines "
                                              "WHERE routine_schema IN ('acore_auth','acore_characters','acore_world');"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="Private reviewed Database.zip; never commit it")
    parser.add_argument("--report", type=Path, required=True, help="Metadata-only JSON report")
    parser.add_argument("--inventory-only", action="store_true", help="Verify the archive without a DB instance")
    parser.add_argument("--probe-updater-policy", action="store_true",
                        help="Disposable-only original changed-hash reapply probe; no SQL rewrites")
    parser.add_argument("--transport", choices=("unix", "loopback"), default="unix",
                        help="Own disposable instance transport; loopback uses generated credentials")
    parser.add_argument("--mariadbd", default="mariadbd")
    parser.add_argument("--install-db", default="mariadb-install-db")
    parser.add_argument("--mysql", default="mariadb")
    args = parser.parse_args()
    if (args.report.resolve() == args.archive.resolve()
            or (args.report.exists() and args.archive.exists() and args.report.samefile(args.archive))):
        parser.error("Report path must not replace the private input archive")
    report = {"status": "failed", "qualification": "host-only; Android and Thor remain unproven",
              "stage": "archive_inventory", "required_android_provider_version": "12.3.2",
              "transport": args.transport, "probe_updater_policy": args.probe_updater_policy,
              "seed_import_status": "not_run", "runtime_sql_status": "not_run"}
    try:
        plan = inventory(args.archive)
        report["inventory"] = plan.summary()
        if args.inventory_only:
            report.update(status="inventory_passed", stage="complete")
        else:
            report["stage"] = "provider_startup"
            server, initializer, client = (executable(v) for v in (args.mariadbd, args.install_db, args.mysql))
            with sandbox(server, initializer, client, transport=args.transport) as cli:
                version = cli.query("SELECT VERSION();")
                if not re.fullmatch(r"[A-Za-z0-9.+~_-]{1,120}", version) or "MariaDB" not in version:
                    raise GateError("Unexpected database provider version format")
                report["host_provider_version"] = version
                report["matches_android_provider_version"] = version.startswith("12.3.2-")
                report["stage"] = "empty_target_preflight"
                assert_empty_targets(cli.query)
                for schema in SCHEMAS:
                    cli.run(sql=f"CREATE DATABASE {identifier(schema)} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;", capture=False)
                with zipfile.ZipFile(args.archive) as archive:
                    report["stage"] = "seed_import"
                    report["seed_files_imported"] = 0
                    for entry in plan.seed():
                        report["current_file"] = entry.path
                        cli.import_member(archive, entry)
                        report["seed_files_imported"] += 1
                    report.pop("current_file", None)
                    report["stage"] = "seed_validation"
                    report["seed_validation"] = inspect_seed(cli, plan)
                    report["seed_import_status"] = "passed"
                    report["stage"] = "runtime_sql_probe"
                    progress = {"verified_existing": 0, "applied_in_disposable_database": 0}
                    report["runtime_sql"] = progress
                    report["runtime_sql_status"] = "running"
                    probe_updates(cli, archive, plan, progress, probe_updater_policy=args.probe_updater_policy)
                    report["runtime_sql_status"] = "passed"
                report.update(status="host_compatibility_passed", stage="complete")
    except (GateError, OSError, zipfile.BadZipFile, UnicodeError, ValueError) as error:
        # Only our own GateError strings may be published. OS paths and ZIP member
        # diagnostics can contain user material, so other errors get a safe label.
        report["error"] = str(error) if isinstance(error, GateError) else type(error).__name__
        if report["runtime_sql_status"] == "running":
            report["runtime_sql_status"] = "failed"
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "stage": report["stage"]}, sort_keys=True))
    return 0 if report["status"] in ("inventory_passed", "host_compatibility_passed") else 1


if __name__ == "__main__":
    raise SystemExit(main())
