#!/usr/bin/env python3
"""Generate metadata for a fresh, normal-mode compiled world updater gate.

The optional host measurement executes original SQL under a source-audited policy
model. It is an expected-result fixture, never evidence of a compiled server run.
No live datadir, external connection, seed rows, or SQL text is accepted/exported.
"""
from __future__ import annotations

import argparse
from collections import Counter
from copy import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import secrets
import zipfile

try:
    from .create_full_seed_lock import CANONICAL_RECEIPTS, RECEIPT_LIMIT, baseline_seed, parse_receipt_summary, receipt_query
    from .fta_database import DATABASE_SHA256, SCHEMAS, DatabasePlan, GateError, assert_empty_targets, identifier, inventory
    from .fta_source import SOURCE_SHA256, SourceError, inventory_tree
    from .run_database_gate import executable, sandbox
except ImportError:
    from create_full_seed_lock import CANONICAL_RECEIPTS, RECEIPT_LIMIT, baseline_seed, parse_receipt_summary, receipt_query
    from fta_database import DATABASE_SHA256, SCHEMAS, DatabasePlan, GateError, assert_empty_targets, identifier, inventory
    from fta_source import SOURCE_SHA256, SourceError, inventory_tree
    from run_database_gate import executable, sandbox

ROOT = Path(__file__).resolve().parents[1]
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
STATES = ("RELEASED", "ARCHIVED", "CUSTOM", "MODULE", "PENDING")
POLICY = {"enableDatabases": 7, "redundancy": True, "allowRehash": True,
          "archivedRedundancy": False, "cleanDeadRefMaxCount": 3, "dryRun": False}
HOST_POLICY_AUDIT_FILES = (
    "src/server/apps/worldserver/Main.cpp",
    "src/server/game/World/World.cpp",
    "src/server/database/Database/DatabaseLoader.cpp",
    "src/server/database/Database/DatabaseWorkerPool.cpp",
    "src/server/database/Updater/DBUpdater.cpp",
    "src/server/database/Updater/UpdateFetcher.cpp",
    "src/server/database/Updater/UpdateFetcher.h",
    "modules/mod-ale/src/ALE_SC.cpp",
    "modules/mod-ale/src/LuaEngine/LuaEngine.cpp",
)
AUDIT_FILES = HOST_POLICY_AUDIT_FILES + (
    "modules/mod-world-inspector-runtime/src/WorldInspectorRuntime.cpp",
)
ORIGINAL_AUDIT_HASHES = (
    "782a1100dcbc84c24b924f6394ee3cb7ec33712881089274aaa3f22974edd1a0",
    "2774cc222da87dc909264b91c6868378674b1fc26525fb1f45a61564f2419788",
    "0f275c42496e1f74cdc7ba2a76ae83b2017d1a55ad67d35e9ad56da1c2137486",
    "5b9009993bd2367a4e010d49fa56676a35c463e07a0606851494dd17ef0fe77c",
    "a091e4dc6b83457eef7f63ef7cf973ccc63ab1344154dc69010704e2e5f79019",
    "9b153a1a637f11613dd6ea00a68d4b5b2e3d47c33d6b8ca7ab5fd5bf38a5259a",
    "2186590153b8d14355ef53f7003cd2f2d73a2ec24aaba27bdfb6b3ff52716518",
    "bbbde21d580845c5aeedc5b82bcb30cb1ac45fcc0d8a05e03485ba9f1f40d896",
    "118a80c9ea5309af6a99de3ea076e1609ac281ccaba2411493e672a3ca5c3005",
    "dd31bb4522aff0c33fbd50c3b49697584187f3c41a7e751064e9e218424eb0cd",
)
CANONICAL_COLUMNS = ("UTF-8(HEX(table_name) + ':' + HEX(column_name) + ':' + ordinal_position + ':' + "
    "HEX(column_type) + ':' + HEX(is_nullable) + ':' + nullable HEX(column_default) + ':' + "
    "nullable HEX(collation_name) + ':' + HEX(extra) + LF), ordered by BINARY table_name, ordinal_position; NULL is '~'")
CANONICAL_RELEASES = ("UTF-8(release_id + TAB + name + TAB + exact-case sha256 + TAB + status + TAB + once_only + LF), "
                      "ordered by BINARY release_id, BINARY name; no timestamps")


def include_directories() -> list[dict]:
    result = []
    for schema in SCHEMAS:
        suffix = schema.removeprefix("acore_")
        for subdir, state in (("archive/db_", "ARCHIVED"), ("custom/db_", "CUSTOM"),
                              ("updates/db_", "RELEASED"), ("updates/pending_db_", "PENDING")):
            result.append({"schema": schema, "path": "data/sql/" + subdir + suffix, "state": state})
    return result


def verify_runtime_layout(archive: Path, plan: DatabasePlan) -> None:
    """The empty include paths must exist in the reviewed archive itself."""
    with zipfile.ZipFile(archive) as bundle:
        entries = {item.filename: item for item in bundle.infolist()}
        for item in include_directories():
            path = "Database/RuntimeSQL/Data/" + item["path"] + "/"
            if path not in entries or not entries[path].is_dir():
                raise GateError("Runtime include directory is absent from the original archive")
        actual = {name for name, item in entries.items() if name.startswith("Database/RuntimeSQL/") and not item.is_dir()}
        if actual != {entry.path for entry in plan.updates()}:
            raise GateError("Runtime source contains files outside the locked SQL inventory")


def schema_scoped_client(cli):
    """Use the device updater's schema grants and original utf8 client charset."""
    username = "fta_world_diag"
    host = "127.0.0.1" if any(arg == "--protocol=TCP" for arg in cli.command) else "localhost"
    password = secrets.token_hex(32)
    cli.run(sql=f"CREATE USER '{username}'@'{host}' IDENTIFIED BY '{password}';\n" +
            "\n".join(f"GRANT ALL PRIVILEGES ON {identifier(schema)}.* TO '{username}'@'{host}';" for schema in SCHEMAS), capture=False)
    defaults = cli.work / "world-client.cnf"
    import os
    with open(defaults, "x", opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
        stream.write("[client]\nuser=" + username + "\npassword=" + password + "\n")
    scoped = copy(cli)
    scoped.command = [arg for arg in cli.command if not arg.startswith(("--defaults-file=", "--user=")) and arg != "--no-defaults"]
    scoped.command.insert(1, "--defaults-file=" + str(defaults))
    scoped.command = ["--default-character-set=utf8" if arg.startswith("--default-character-set=") else arg for arg in scoped.command]
    grants = scoped.query("SHOW GRANTS FOR CURRENT_USER;").splitlines()
    schema_grants = {f"GRANT ALL PRIVILEGES ON `{schema}`.* TO " for schema in SCHEMAS}
    if (len(grants) != 4 or sum(line.startswith("GRANT USAGE ON *.* TO ") for line in grants) != 1
            or not all(any(line.startswith(prefix) and "WITH GRANT OPTION" not in line for line in grants) for prefix in schema_grants)
            or scoped.query("SELECT CURRENT_USER();") != username + "@" + host):
        raise GateError("World model client grants differ from the device schema-only policy")
    return scoped


def aggregate_query(prefix: str, source: str, row: str, order: str, invalid: str = "0") -> str:
    if prefix not in ("FTA_COLUMNS", "FTA_RELEASES"):
        raise GateError("Unsupported world metadata protocol")
    joined = f"GROUP_CONCAT({row} ORDER BY {order} SEPARATOR '')"
    return (f"SET SESSION group_concat_max_len={RECEIPT_LIMIT};\n"
            f"SELECT CONCAT('{prefix}=',COUNT(*),':',COALESCE(SUM(OCTET_LENGTH({row})),0),':',"
            f"COALESCE(OCTET_LENGTH({joined}),0),':',COALESCE(SUM({invalid}),0),':',"
            f"COALESCE(SHA2({joined},256),SHA2('',256))) FROM {source};")


def column_query(schema: str) -> str:
    if schema not in SCHEMAS:
        raise GateError("Unsupported world metadata schema")
    row = ("CONCAT(HEX(table_name),':',HEX(column_name),':',ordinal_position,':',HEX(column_type),':',"
           "HEX(is_nullable),':',IF(column_default IS NULL,'~',HEX(column_default)),':',"
           "IF(collation_name IS NULL,'~',HEX(collation_name)),':',HEX(extra),CHAR(10))")
    return aggregate_query("FTA_COLUMNS", f"information_schema.columns WHERE table_schema='{schema}'",
                           row, "BINARY table_name,ordinal_position")


def release_query(schema: str) -> str:
    if schema not in SCHEMAS:
        raise GateError("Unsupported world metadata schema")
    row = "CONCAT(release_id,CHAR(9),name,CHAR(9),sha256,CHAR(9),status,CHAR(9),once_only,CHAR(10))"
    invalid = ("BINARY release_id NOT REGEXP '^[A-Za-z0-9_.+-]{1,120}$' OR "
               "BINARY name NOT REGEXP '^Sql/[A-Za-z0-9_.+-]{1,186}$' OR "
               "BINARY sha256 NOT REGEXP '^[A-Fa-f0-9]{64}$' OR BINARY status<>'APPLIED' OR once_only<>1")
    return aggregate_query("FTA_RELEASES", identifier(schema) + ".`fta_release_updates`", row,
                           "BINARY release_id,BINARY name", invalid)


def parse_aggregate(value: str, prefix: str, field: str, *, empty: bool = False) -> dict:
    match = re.fullmatch(re.escape(prefix) + r"=([0-9]+):([0-9]+):([0-9]+):([0-9]+):([a-f0-9]{64})", value)
    if not match:
        raise GateError("Invalid world metadata aggregate")
    count, size, joined, invalid = map(int, match.groups()[:4])
    if count > 100000 or size > RECEIPT_LIMIT or joined != size or invalid:
        raise GateError("World metadata aggregate is invalid or truncated")
    if not count or not size:
        if not empty or count or size or match[5] != EMPTY_SHA256:
            raise GateError("World metadata aggregate is unexpectedly empty")
    return {field + "Count": count, field + "Bytes": size, field + "Sha256": match[5]}


def source_metadata(source: Path, profile: str) -> dict:
    profiles = json.loads((ROOT / "native-port/profiles.json").read_text())
    expected = profiles.get("profiles", {}).get(profile, {}).get("result_inventory", {})
    actual = inventory_tree(source)
    if (not expected or actual != expected or len(actual["modules"]) != 52):
        raise GateError("World source tree differs from the reviewed port profile")
    modules = source / "modules"
    if any(p.is_symlink() for p in modules.rglob("*")):
        raise GateError("World module inventory contains a filesystem link")
    sql = [p for p in modules.rglob("*") if p.is_file() and p.suffix.lower() == ".sql"]
    if sql:
        raise GateError("Reviewed world source unexpectedly contains module SQL")
    sql_dirs = sorted(p.relative_to(source).as_posix() for p in modules.rglob("*")
                      if p.is_dir() and "/data/sql" in p.relative_to(source).as_posix())
    return {"sourceArchiveSha256": SOURCE_SHA256, "sourceProfile": profile,
            "sourceTreeSha256": actual["tree_sha256"], "moduleCount": 52, "moduleSqlCount": 0,
            "moduleDirectories": ["modules/" + name for name in actual["modules"]],
            "moduleSqlDirectories": sql_dirs,
            "sourceAudit": [{"path": name, "sha256": hashlib.sha256((source / name).read_bytes()).hexdigest()}
                            for name in AUDIT_FILES]}


def expected_source_audit(profile: dict) -> list[dict]:
    hashes = dict(zip(AUDIT_FILES, ORIGINAL_AUDIT_HASHES))
    for patch in profile.get("patches", []):
        for path, change in patch.get("files", {}).items():
            if path in hashes:
                if change.get("before_sha256") != hashes[path]:
                    raise GateError("Reviewed world source audit patch chain is inconsistent")
                hashes[path] = change["after_sha256"]
    return [{"path": name, "sha256": hashes[name]} for name in AUDIT_FILES]


def host_policy_source_audit(audits: list[dict]) -> list[dict]:
    """Retain the execution-source identities recorded by the host SQL model.

    The additional WorldInspector pin binds the native queue configuration
    evidence. That module was outside the measured host updater model.
    """
    return [item for item in audits if item["path"] in HOST_POLICY_AUDIT_FILES]


def source_policy_probe(cli, archive: zipfile.ZipFile, plan: DatabasePlan) -> dict:
    """Model original UpdateFetcher including snapshot, rehash, rename and cleanup.

    Files and receipt hashes are exact-case. Duplicate-hash rename ambiguity is
    rejected, rather than guessing C++ unordered_map iteration order. SQL runs
    unchanged and every client failure aborts before replacement of its receipt.
    """
    results = {}
    for schema in SCHEMAS:
        expected_includes = sorted("$/" + e["path"] + "\t" + e["state"]
                                   for e in include_directories() if e["schema"] == schema)
        actual_includes = cli.query(f"SELECT path,state FROM {identifier(schema)}.`updates_include` ORDER BY BINARY path;").splitlines()
        if actual_includes != expected_includes:
            raise GateError("Source policy include directories differ from the reviewed release")
        raw = cli.query(f"SELECT name,hash,state FROM {identifier(schema)}.`updates` ORDER BY BINARY name;")
        applied = {}
        for line in raw.splitlines():
            fields = line.split("\t")
            if (len(fields) != 3 or not re.fullmatch(r"[A-Za-z0-9_.+-]{1,240}\.sql", fields[0])
                    or not re.fullmatch(r"(?:[a-fA-F0-9]{40})?", fields[1]) or fields[2] not in STATES
                    or fields[0] in applied):
                raise GateError("Source policy encountered an invalid receipt snapshot")
            applied[fields[0]] = (fields[1], fields[2])
        by_hash = {}
        for name, (digest, _) in applied.items():
            by_hash.setdefault(digest, []).append(name)
        available = sorted((e for e in plan.updates() if e.schema == schema), key=lambda e: e.name)
        names = {e.name for e in available}
        stats = Counter()
        stats["recentBefore"] = sum(state == "RELEASED" for _, state in applied.values())
        stats["archivedBefore"] = len(applied) - stats["recentBefore"]
        # Source applies RELEASED/ARCHIVED before PENDING/CUSTOM/MODULE.
        available = [e for e in available if "/pending_" not in e.path] + [e for e in available if "/pending_" in e.path]
        for entry in available:
            before = applied.get(entry.name)
            state = "PENDING" if "/pending_" in entry.path else "RELEASED"
            digest = entry.sha1.upper()
            if before is None and digest in by_hash:
                matches = by_hash[digest]
                if len(matches) != 1:
                    raise GateError("Source policy rename hash is ambiguous")
                old = matches[0]
                if old not in names:
                    cli.run(sql=f"DELETE FROM {identifier(schema)}.`updates` WHERE name='{entry.name}';\n"
                                f"UPDATE {identifier(schema)}.`updates` SET name='{entry.name}' WHERE name='{old}';", capture=False)
                    applied.pop(old, None)
                    stats["renamed"] += 1
                    continue
            if before and before[0] == digest:
                if before[1] != state:
                    cli.run(sql=f"UPDATE {identifier(schema)}.`updates` SET state='{state}' WHERE name='{entry.name}';", capture=False)
                    stats["stateUpdated"] += 1
                stats["verified"] += 1
            else:
                if before and not before[0]:
                    stats["rehashed"] += 1
                else:
                    cli.import_member(archive, entry)
                    stats["reapplied" if before else "applied"] += 1
                cli.run(sql=f"REPLACE INTO {identifier(schema)}.`updates` (name,hash,state,speed) "
                            f"VALUES ('{entry.name}','{digest}','{state}',0);", capture=False)
            applied.pop(entry.name, None)
        if len(applied) <= POLICY["cleanDeadRefMaxCount"]:
            for name, (_, state) in applied.items():
                if state != "MODULE":
                    cli.run(sql=f"DELETE FROM {identifier(schema)}.`updates` WHERE name='{name}';", capture=False)
                    stats["orphanDeleted"] += 1
        stats["orphanWarnings"] = sum(state != "MODULE" for _, state in applied.values())
        stats["orphanRemaining"] = len(applied) - stats["orphanDeleted"]
        results[schema] = dict(sorted(stats.items()))
    return results


def postconditions(cli) -> list[dict]:
    result = []
    for schema in SCHEMAS:
        tables = cli.query("SELECT table_name FROM information_schema.tables "
                           f"WHERE table_schema='{schema}' AND table_type='BASE TABLE' ORDER BY BINARY table_name;").splitlines()
        if cli.query(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{schema}';") != str(len(tables)):
            raise GateError("Unexpected non-table catalog object after source policy probe")
        triggers = cli.query("SELECT trigger_name FROM information_schema.triggers "
                             f"WHERE trigger_schema='{schema}' ORDER BY BINARY trigger_name;").splitlines()
        for kind in ("routine", "event"):
            if cli.query(f"SELECT COUNT(*) FROM information_schema.{kind}s WHERE {kind}_schema='{schema}';") != "0":
                raise GateError("Unexpected routine or event after source policy probe")
        receipts = parse_receipt_summary(cli.query(receipt_query(schema)))
        columns = parse_aggregate(cli.query(column_query(schema)), "FTA_COLUMNS", "column")
        release = ({"releaseReceiptCount": 0, "releaseReceiptBytes": 0, "releaseReceiptSha256": EMPTY_SHA256}
                   if "fta_release_updates" not in tables else
                   parse_aggregate(cli.query(release_query(schema)), "FTA_RELEASES", "releaseReceipt", empty=True))
        result.append({"name": schema, "tableNames": tables, "triggerNames": triggers,
                       "routineCount": 0, "eventCount": 0, **receipts, **columns, **release})
    return result


def world_updater_lock(plan: DatabasePlan, archive_size: int, source: dict, measured: dict) -> dict:
    if plan.archive_sha256 != DATABASE_SHA256 or type(archive_size) is not int or archive_size <= 0 or len(plan.updates()) != 236:
        raise GateError("World updater requires the unchanged reviewed archive")
    measured_policy = measured.get("policy") if isinstance(measured, dict) else None
    valid_policy = (isinstance(measured_policy, dict) and set(measured_policy) == set(POLICY)
                    and all(type(measured_policy[key]) is type(value) and measured_policy[key] == value for key, value in POLICY.items()))
    if (not isinstance(measured, dict) or type(measured.get("formatVersion")) is not int
            or measured.get("formatVersion") != 1 or measured.get("archiveSha256") != DATABASE_SHA256
            or measured.get("providerVersion") != "12.3.2-MariaDB" or not valid_policy
            or measured.get("scope") != "host-source-policy-model-only"
            or measured.get("schemaPrivilegesOnly") is not True or measured.get("runtimeClientDefaultCharset") != "utf8"):
        raise GateError("World updater expected-result measurement is incompatible")
    schemas = measured.get("schemas")
    if not isinstance(schemas, list) or [s.get("name") for s in schemas if isinstance(s, dict)] != list(SCHEMAS):
        raise GateError("World updater postcondition schemas are invalid")
    public = []
    for schema in schemas:
        item = {"name": schema["name"]}
        for field in ("tableNames", "triggerNames"):
            values = schema.get(field)
            if (not isinstance(values, list) or not values
                    or any(not isinstance(n, str) or not re.fullmatch(r"[A-Za-z0-9_]{1,64}", n) for n in values)
                    or values != sorted(set(values))):
                if field != "triggerNames" or values != []:
                    raise GateError("World updater catalog identities are invalid")
            item[field] = values
        for field in ("routineCount", "eventCount"):
            if type(schema.get(field)) is not int or schema[field] != 0:
                raise GateError("World updater requires zero remaining routines and events")
            item[field] = 0
        for stem, prefix in (("receipt", "FTA_RECEIPTS"), ("column", "FTA_COLUMNS"), ("releaseReceipt", "FTA_RELEASES")):
            count, size, sha = (schema.get(stem + ending) for ending in ("Count", "Bytes", "Sha256"))
            if type(count) is not int or type(size) is not int:
                raise GateError("World updater aggregate types are invalid")
            value = f"{prefix}={count}:{size}:{size}:0:{sha}"
            parsed = (parse_receipt_summary(value) if stem == "receipt" else
                      parse_aggregate(value, prefix, stem, empty=stem == "releaseReceipt"))
            item.update(parsed)
        public.append(item)
    source_fields = ("sourceArchiveSha256", "sourceProfile", "sourceTreeSha256", "moduleCount", "moduleSqlCount",
                     "moduleDirectories", "moduleSqlDirectories", "sourceAudit")
    if not isinstance(source, dict):
        raise GateError("World updater source metadata must be an object")
    profiles = json.loads((ROOT / "native-port/profiles.json").read_text())["profiles"]
    profile = profiles.get(source.get("sourceProfile"), {})
    expected = profile.get("result_inventory", {})
    if (source.get("sourceArchiveSha256") != SOURCE_SHA256 or type(source.get("moduleCount")) is not int
            or source.get("moduleCount") != 52 or type(source.get("moduleSqlCount")) is not int
            or source.get("moduleSqlCount") != 0 or source.get("sourceTreeSha256") != expected.get("tree_sha256")
            or source.get("moduleDirectories") != ["modules/" + name for name in expected.get("modules", [])]
            or source.get("moduleSqlDirectories") != []
            or not re.fullmatch(r"android-arm64-world-v[0-9]{1,3}", str(source.get("sourceProfile")))):
        raise GateError("World updater module source contract is incomplete")
    audits = source.get("sourceAudit")
    if audits != expected_source_audit(profile):
        raise GateError("World updater source audit identities are invalid")
    if measured.get("sourcePolicyAudit") != host_policy_source_audit(audits):
        raise GateError("World updater expected-result fixture was measured against different audited source")
    files = [{"path": e.path, "targetPath": PurePosixPath(e.path).relative_to("Database/RuntimeSQL").as_posix(),
              "schema": e.schema, "size": e.size, "sha256": e.sha256, "sha1": e.sha1.upper()}
             for e in sorted(plan.updates(), key=lambda e: e.path)]
    transitions = measured.get("transitions")
    if not isinstance(transitions, dict) or set(transitions) != set(SCHEMAS):
        raise GateError("World updater measurement lacks all source policy phases")
    phases = []
    for schema, label in zip(SCHEMAS, ("Auth", "Character", "World")):
        stats = transitions[schema]
        fields = ("recentBefore", "archivedBefore", "applied", "reapplied", "verified", "rehashed",
                  "renamed", "stateUpdated", "orphanDeleted", "orphanRemaining", "orphanWarnings")
        if (not isinstance(stats, dict) or any(type(stats.get(key, 0)) is not int or not 0 <= stats.get(key, 0) <= 10000 for key in fields)
                or any(key not in stats for key in ("recentBefore", "archivedBefore", "orphanWarnings"))):
            raise GateError("World updater source policy phase counts are invalid")
        updated = stats.get("applied", 0) + stats.get("reapplied", 0)
        info = f"Containing {stats['recentBefore']} new and {stats['archivedBefore']} archived updates."
        completion = (f">> Applied {updated} {'query' if updated == 1 else 'queries'}. " + info if updated else
                      f">> {label} database is up-to-date! " + info)
        phases.append({"database": label, "startMarker": f"Updating {label} database...", "completionMarker": completion,
                       "orphanedReceiptWarnings": stats["orphanWarnings"]})
    return {"formatVersion": 1, "purpose": "fresh-disposable-compiled-world-updater-only",
            "archiveSha256": plan.archive_sha256, "archiveSize": archive_size, "requiredProviderVersion": "12.3.2",
            **{key: source[key] for key in source_fields}, "sourceDirectory": "Data", "policy": POLICY,
            "runtimeSqlFiles": files, "includeDirectories": include_directories(), "schemas": public,
            "canonicalReceipts": CANONICAL_RECEIPTS, "canonicalColumns": CANONICAL_COLUMNS,
            "canonicalReleaseReceipts": CANONICAL_RELEASES, "aggregateConcatLimitBytes": RECEIPT_LIMIT,
            "expectedResultEvidence": {"scope": "host-source-policy-model-only", "compiledWorldExecutionQualified": False,
                                       "schemaPrivilegesOnly": True, "globalPrivilegesGranted": False,
                                       "runtimeClientDefaultCharset": "utf8"},
            "nativeBoundary": {"mode": "normal", "exitCode": 1, "databaseOrder": ["Auth", "Character", "World"],
                               "updatePhases": phases,
                               "preparedCompletionMarker": "Loading World Information...",
                               "expectedStopMarker": "Failed to find map files for starting areas"},
            "constraints": {"freshDisposableDatadirOnly": True, "liveDatabaseAccess": False,
                            "sqlRewriting": False, "sqlContentsInManifest": False, "completeLogsRequired": True,
                            "failedSqlAllowed": False, "authTcpListener": False, "worldTcpListener": False,
                            "worldReady": False}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--source", type=Path, required=True, help="Exact staged reviewed Android source profile")
    parser.add_argument("--source-profile", default="android-arm64-world-v8")
    parser.add_argument("--measurement", type=Path, required=True, help="Private metadata-only host expected-result fixture")
    parser.add_argument("--measure-host-policy", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("manifests/world-updater-lock.json"))
    parser.add_argument("--mariadbd", default="mariadbd")
    parser.add_argument("--install-db", default="mariadb-install-db")
    parser.add_argument("--mysql", default="mariadb")
    parser.add_argument("--transport", choices=("unix", "loopback"), default="unix")
    parser.add_argument("--character-sets-dir", type=Path, help="Exact private provider charset data, if separate from host binaries")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for path in (args.output, args.measurement):
        if path.resolve() == args.archive.resolve() or (path.exists() and path.samefile(args.archive)):
            parser.error("Metadata paths must not replace the private archive")
        if path.resolve().is_relative_to(args.source.resolve()):
            parser.error("Metadata paths must not modify the original source tree")
    if (args.output.resolve() == args.measurement.resolve()
            or (args.output.exists() and args.measurement.exists() and args.output.samefile(args.measurement))):
        parser.error("Measurement and lock output paths must differ")
    try:
        plan = inventory(args.archive)
        verify_runtime_layout(args.archive, plan)
        source = source_metadata(args.source, args.source_profile)
        if args.measure_host_policy:
            with sandbox(*(executable(v) for v in (args.mariadbd, args.install_db, args.mysql)), transport=args.transport,
                         character_sets_dir=args.character_sets_dir) as cli:
                version = cli.query("SELECT VERSION();")
                if version != "12.3.2-MariaDB":
                    raise GateError("World expected-result measurement requires MariaDB 12.3.2")
                assert_empty_targets(cli.query)
                for schema in SCHEMAS:
                    cli.run(sql=f"CREATE DATABASE {identifier(schema)} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;", capture=False)
                with zipfile.ZipFile(args.archive) as archive:
                    for entry in plan.seed():
                        cli.import_member(archive, entry)
                    baseline_seed(cli, plan)
                    runtime_cli = schema_scoped_client(cli)
                    transitions = source_policy_probe(runtime_cli, archive, plan)
                measured = {"formatVersion": 1, "scope": "host-source-policy-model-only", "archiveSha256": plan.archive_sha256,
                            "providerVersion": version, "policy": POLICY, "schemaPrivilegesOnly": True,
                            "runtimeClientDefaultCharset": "utf8", "sourcePolicyAudit": host_policy_source_audit(source["sourceAudit"]),
                            "transitions": transitions, "schemas": postconditions(runtime_cli)}
                if postconditions(runtime_cli) != measured["schemas"]:
                    raise GateError("Read-only world postcondition inspection changed metadata")
            args.measurement.parent.mkdir(parents=True, exist_ok=True)
            args.measurement.write_text(json.dumps(measured, indent=2, sort_keys=True) + "\n")
        else:
            measured = json.loads(args.measurement.read_text())
        encoded = json.dumps(world_updater_lock(plan, args.archive.stat().st_size, source, measured), indent=2, sort_keys=True) + "\n"
        if args.check:
            if args.output.read_text() != encoded:
                raise GateError("World updater lock differs from its reviewed source or measurement")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded)
    except (GateError, SourceError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(str(error) if isinstance(error, GateError) else type(error).__name__)
        return 1
    print(json.dumps({"status": "verified" if args.check else "generated", "runtimeSqlFiles": 236,
                      "qualification": "expected-result model only; compiled world execution remains unqualified"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
