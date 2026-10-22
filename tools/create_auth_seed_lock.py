#!/usr/bin/env python3
"""Generate public hash-only metadata for the private disposable auth diagnostic."""
from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path, PurePosixPath

try:
    from .fta_database import DATABASE_SHA256, DatabasePlan, GateError, inventory
except ImportError:
    from fta_database import DATABASE_SHA256, DatabasePlan, GateError, inventory

AUTH_RUNTIME_DIRECTORIES = (
    "Data/data/sql/archive/db_auth",
    "Data/data/sql/custom/db_auth",
    "Data/data/sql/updates/db_auth",
    "Data/data/sql/updates/pending_db_auth",
)


def auth_seed_lock(plan: DatabasePlan, archive_size: int) -> dict:
    """Return names, sizes, and hashes; SQL bytes and row values are excluded."""
    if plan.archive_sha256 != DATABASE_SHA256:
        raise GateError("Auth diagnostic requires the reviewed database archive")
    seed = [e for e in plan.seed() if e.schema == "acore_auth"]
    updates = [e for e in plan.updates() if e.schema == "acore_auth"]
    if len(seed) != 27 or any(e.kind != "table" for e in seed) or len(updates) != 1:
        raise GateError("Auth diagnostic inventory differs from the reviewed release")
    result = {
        "formatVersion": 1,
        "purpose": "fresh-disposable-auth-dry-run-only",
        "archiveSha256": plan.archive_sha256,
        "archiveSize": archive_size,
        "database": "acore_auth",
        "gameClientBuild": 12340,
        "files": [],
        "sourceDirectory": "Data",
        "directories": list(AUTH_RUNTIME_DIRECTORIES),
        "constraints": {"freshDisposableDatadirOnly": True, "liveDatabaseAccess": False,
                        "charactersOrWorldSeedImport": False, "sqlRewriting": False,
                        "sqlContentsInManifest": False, "authTcpListener": False},
    }
    for entry in sorted(seed, key=lambda e: e.path):
        result["files"].append({"path": entry.path, "role": "auth-table",
            "targetPath": "auth-seed/" + PurePosixPath(entry.path).name,
            "size": entry.size, "sha256": entry.sha256})
    for entry in sorted(updates, key=lambda e: e.path):
        result["files"].append({"path": entry.path, "role": "auth-update",
            "targetPath": str(PurePosixPath(entry.path).relative_to("Database/RuntimeSQL")),
            "size": entry.size, "sha256": entry.sha256})
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path, default=Path("manifests/auth-seed-lock.json"))
    parser.add_argument("--check", action="store_true", help="Check existing metadata without writing")
    args = parser.parse_args()
    if (args.output.resolve() == args.archive.resolve()
            or (args.output.exists() and args.archive.exists() and args.output.samefile(args.archive))):
        parser.error("Manifest path must not replace the private input archive")
    try:
        plan = inventory(args.archive)
        result = auth_seed_lock(plan, args.archive.stat().st_size)
        encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
        if args.check:
            if args.output.read_text(encoding="utf-8") != encoded:
                raise GateError("Auth diagnostic lock metadata differs from the reviewed archive")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded, encoding="utf-8")
    except (GateError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(str(error) if isinstance(error, GateError) else type(error).__name__)
        return 1
    print(json.dumps({"status": "verified" if args.check else "generated", "auth_seed_files": 27, "auth_runtime_sql_files": 1}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
