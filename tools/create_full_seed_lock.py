#!/usr/bin/env python3
"""Hash-only contract for an unchanged, disposable full-seed import diagnostic.

The optional baseline command starts its own bounded host instance. It does not
apply runtime SQL, open an existing database, export seed rows, or qualify Android.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import zipfile

try:
    from .fta_database import DATABASE_SHA256, EXPECTED_TABLES, SCHEMAS, DatabasePlan, GateError, identifier, inventory
    from .run_database_gate import executable, sandbox
except ImportError:
    from fta_database import DATABASE_SHA256, EXPECTED_TABLES, SCHEMAS, DatabasePlan, GateError, identifier, inventory
    from run_database_gate import executable, sandbox

RECEIPT_LIMIT = 1024 * 1024
RECEIPT_STATES = ("RELEASED", "ARCHIVED", "CUSTOM", "MODULE", "PENDING")
CANONICAL_RECEIPTS = "UTF-8(name + TAB + exact-case hash + TAB + state + LF), ordered by BINARY name; no timestamp or speed"


def receipt_query(schema: str) -> str:
    """Return only count/length/invalid-count/hash, never original receipt rows.

    Both lengths are required: GROUP_CONCAT truncation must fail closed rather
    than digesting a prefix. State and checksum case are preserved byte for byte.
    """
    if schema not in SCHEMAS:
        raise GateError("Unsupported full-seed schema")
    table = identifier(schema) + ".`updates`"
    row = "CONCAT(`name`,CHAR(9),`hash`,CHAR(9),`state`,CHAR(10))"
    joined = f"GROUP_CONCAT({row} ORDER BY BINARY `name` SEPARATOR '')"
    invalid = ("`name` IS NULL OR BINARY `name` NOT REGEXP '^[A-Za-z0-9_.+-]{1,240}[.]sql$' "
               "OR `hash` IS NULL OR BINARY `hash` NOT REGEXP '^[A-Fa-f0-9]{40}$' "
               "OR `state` IS NULL OR BINARY `state` NOT IN ('RELEASED','ARCHIVED','CUSTOM','MODULE','PENDING')")
    return (f"SET SESSION group_concat_max_len={RECEIPT_LIMIT};\n"
            "SELECT CONCAT('FTA_RECEIPTS=',COUNT(*),':',"
            f"COALESCE(SUM(OCTET_LENGTH({row})),0),':',COALESCE(OCTET_LENGTH({joined}),0),':',"
            f"COALESCE(SUM({invalid}),0),':',COALESCE(SHA2({joined},256),'')) FROM {table};")


def parse_receipt_summary(value: str) -> dict:
    match = re.fullmatch(r"FTA_RECEIPTS=([0-9]+):([0-9]+):([0-9]+):([0-9]+):([a-f0-9]{64})", value)
    if not match:
        raise GateError("Invalid full-seed receipt metadata")
    count, expected_bytes, joined_bytes, invalid = map(int, match.groups()[:4])
    if not 0 < count <= 10000 or not 0 < expected_bytes <= RECEIPT_LIMIT or expected_bytes != joined_bytes or invalid:
        raise GateError("Full-seed receipt metadata is invalid or truncated")
    return {"receiptCount": count, "receiptBytes": expected_bytes, "receiptSha256": match[5]}


def baseline_seed(cli, plan: DatabasePlan) -> dict:
    """Inspect catalog identities and receipts after unchanged seed import only."""
    schemas = []
    for schema in SCHEMAS:
        tables = sorted(e.name for e in plan.seed() if e.schema == schema and e.kind == "table")
        actual = cli.query("SELECT table_name FROM information_schema.tables "
                           f"WHERE table_schema='{schema}' AND table_type='BASE TABLE' ORDER BY BINARY table_name;").splitlines()
        if actual != tables:
            raise GateError("Full-seed imported table identities differ from the archive")
        if cli.query(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{schema}';") != str(len(tables)):
            raise GateError("Full-seed contains unexpected non-table catalog objects")
        triggers = sorted(e.name for e in plan.seed() if e.schema == schema and e.kind == "trigger")
        actual_triggers = cli.query("SELECT trigger_name FROM information_schema.triggers "
                                   f"WHERE trigger_schema='{schema}' ORDER BY BINARY trigger_name;").splitlines()
        if actual_triggers != triggers:
            raise GateError("Full-seed imported trigger identities differ from the archive")
        routine_count = cli.query(f"SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='{schema}';")
        event_count = cli.query(f"SELECT COUNT(*) FROM information_schema.events WHERE event_schema='{schema}';")
        if routine_count != "0" or event_count != "0":
            raise GateError("Full-seed imported routine or event inventory differs from the reviewed release")
        receipts = parse_receipt_summary(cli.query(receipt_query(schema)))
        schemas.append({"name": schema, "tableCount": len(tables), "triggerNames": triggers,
                        "routineCount": 0, "eventCount": 0, **receipts})
    return {"formatVersion": 1, "archiveSha256": plan.archive_sha256,
            "requiredProviderVersion": "12.3.2", "runtimeSqlApplied": False, "schemas": schemas}


def full_seed_lock(plan: DatabasePlan, archive_size: int, baseline: dict) -> dict:
    if plan.archive_sha256 != DATABASE_SHA256 or archive_size <= 0:
        raise GateError("Full-seed diagnostic requires the reviewed database archive")
    if (not isinstance(baseline, dict) or type(baseline.get("formatVersion")) is not int
            or baseline.get("formatVersion") != 1 or baseline.get("archiveSha256") != DATABASE_SHA256
            or baseline.get("requiredProviderVersion") != "12.3.2" or baseline.get("runtimeSqlApplied") is not False):
        raise GateError("Full-seed baseline differs from the reviewed import contract")
    seeds = plan.seed()
    if len(seeds) != 564 or len(plan.updates()) != 236:
        raise GateError("Full-seed inventory differs from the reviewed release")
    expected_order = {schema: index for index, schema in enumerate(SCHEMAS)}
    schemas = baseline.get("schemas")
    if (not isinstance(schemas, list) or not all(isinstance(s, dict) for s in schemas)
            or [s.get("name") for s in schemas] != list(SCHEMAS)):
        raise GateError("Full-seed baseline schemas differ from the reviewed release")
    public_schemas = []
    for schema in schemas:
        name = schema["name"]
        tables = [e for e in seeds if e.schema == name and e.kind == "table"]
        triggers = sorted(e.name for e in seeds if e.schema == name and e.kind == "trigger")
        if (any(type(schema.get(field)) is not int for field in ("tableCount", "routineCount", "eventCount"))
                or len(tables) != EXPECTED_TABLES[name] or schema.get("tableCount") != len(tables)
                or schema.get("triggerNames") != triggers or schema.get("routineCount") != 0
                or schema.get("eventCount") != 0):
            raise GateError("Full-seed baseline object inventory differs from the archive")
        receipt = parse_receipt_summary("FTA_RECEIPTS=" + ":".join(str(schema.get(k)) for k in
            ("receiptCount", "receiptBytes", "receiptBytes")) + ":0:" + str(schema.get("receiptSha256")))
        if any(schema.get(k) != v for k, v in receipt.items()):
            raise GateError("Full-seed baseline receipt metadata is invalid")
        # The baseline is private input too. Only these reviewed metadata fields
        # may cross into a public lock; never copy arbitrary baseline properties.
        public_schemas.append({"name": name, "tableCount": len(tables), "triggerNames": triggers,
                               "routineCount": 0, "eventCount": 0, **receipt})
    files = [{"path": e.path, "schema": e.schema, "kind": e.kind, "name": e.name,
              "size": e.size, "sha256": e.sha256}
             for e in sorted(seeds, key=lambda e: (e.kind != "table", expected_order[e.schema], e.path))]
    return {"formatVersion": 1, "purpose": "fresh-disposable-full-seed-only",
            "archiveSha256": plan.archive_sha256, "archiveSize": archive_size,
            "seedBytes": sum(e.size for e in seeds), "requiredProviderVersion": "12.3.2",
            "canonicalReceipts": CANONICAL_RECEIPTS, "receiptConcatLimitBytes": RECEIPT_LIMIT,
            "schemas": public_schemas, "files": files,
            "constraints": {"freshDisposableDatadirOnly": True, "liveDatabaseAccess": False,
                            "runtimeSqlApplied": False, "sqlRewriting": False,
                            "sqlContentsInManifest": False, "authTcpListener": False,
                            "worldTcpListener": False}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path, default=Path("manifests/full-seed-lock.json"))
    parser.add_argument("--baseline-report", type=Path, required=True,
                        help="Private metadata-only baseline produced by --measure-host-baseline")
    parser.add_argument("--measure-host-baseline", action="store_true")
    parser.add_argument("--mariadbd", default="mariadbd")
    parser.add_argument("--install-db", default="mariadb-install-db")
    parser.add_argument("--mysql", default="mariadb")
    parser.add_argument("--transport", choices=("unix", "loopback"), default="unix")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for path in (args.output, args.baseline_report):
        if path.resolve() == args.archive.resolve() or (path.exists() and path.samefile(args.archive)):
            parser.error("Metadata paths must not replace the private input archive")
    if args.output.resolve() == args.baseline_report.resolve():
        parser.error("Baseline and lock output paths must differ")
    try:
        plan = inventory(args.archive)
        if args.measure_host_baseline:
            try:
                from .fta_database import assert_empty_targets
            except ImportError:
                from fta_database import assert_empty_targets
            with sandbox(*(executable(v) for v in (args.mariadbd, args.install_db, args.mysql)), transport=args.transport) as cli:
                if cli.query("SELECT VERSION();") != "12.3.2-MariaDB":
                    raise GateError("Baseline measurement requires the exact reviewed MariaDB version")
                assert_empty_targets(cli.query)
                for schema in SCHEMAS:
                    cli.run(sql=f"CREATE DATABASE {identifier(schema)} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;", capture=False)
                with zipfile.ZipFile(args.archive) as archive:
                    for entry in plan.seed():
                        cli.import_member(archive, entry)
                baseline = baseline_seed(cli, plan)
                # Repeating read-only metadata inspection must not alter receipts.
                if baseline_seed(cli, plan) != baseline:
                    raise GateError("Read-only baseline inspection changed seed metadata")
            args.baseline_report.parent.mkdir(parents=True, exist_ok=True)
            args.baseline_report.write_text(json.dumps(baseline, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        else:
            baseline = json.loads(args.baseline_report.read_text(encoding="utf-8"))
        result = full_seed_lock(plan, args.archive.stat().st_size, baseline)
        encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
        if args.check:
            if args.output.read_text(encoding="utf-8") != encoded:
                raise GateError("Full-seed lock differs from the reviewed archive or baseline")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded, encoding="utf-8")
    except (GateError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(str(error) if isinstance(error, GateError) else type(error).__name__)
        return 1
    print(json.dumps({"status": "verified" if args.check else "generated", "seed_files": 564,
                      "runtime_sql_applied": False, "qualification": "host baseline only; Android execution remains unqualified"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
