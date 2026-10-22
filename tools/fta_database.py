"""Pinned, non-destructive planning for the private From the Ashes SQL seed.

No SQL contents are returned by this module. An archive is data, never a set of
shell commands; the compatibility runner consumes its SQL with the database CLI.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
from pathlib import Path, PurePosixPath
import re
import stat
from typing import BinaryIO, Callable
import zipfile

DATABASE_SHA256 = "56a6d3187d6c5fe308e2a5772a19bb087cb55dc999e585db5409bc5469a107f7"
SCHEMAS = ("acore_auth", "acore_characters", "acore_world")
EXPECTED_TABLES = dict(zip(SCHEMAS, (27, 165, 371)))
MAX_ENTRIES = 2000
MAX_ENTRY_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,63}\Z")


class GateError(ValueError):
    """Safe to publish: messages contain metadata only, never SQL contents."""


def identifier(value: str) -> str:
    if not IDENTIFIER.fullmatch(value):
        raise GateError("Invalid SQL identifier")
    return "`" + value + "`"


def stream_digest(stream: BinaryIO, algorithm: str = "sha256") -> str:
    digest = hashlib.new(algorithm)
    while block := stream.read(1024 * 1024):
        digest.update(block)
    return digest.hexdigest()


@dataclass(frozen=True)
class SqlEntry:
    path: str
    schema: str
    kind: str
    name: str
    size: int
    sha256: str
    sha1: str


@dataclass(frozen=True)
class DatabasePlan:
    archive_sha256: str
    entries: tuple[SqlEntry, ...]
    uncompressed_bytes: int

    def seed(self) -> tuple[SqlEntry, ...]:
        return tuple(e for e in self.entries if e.kind in ("table", "trigger"))

    def updates(self) -> tuple[SqlEntry, ...]:
        return tuple(e for e in self.entries if e.kind == "update")

    def summary(self) -> dict:
        return {
            "archive_sha256": self.archive_sha256,
            "uncompressed_bytes": self.uncompressed_bytes,
            "fresh_install_only": True,
            "tables": {s: sum(e.schema == s and e.kind == "table" for e in self.entries) for s in SCHEMAS},
            "triggers": {s: sum(e.schema == s and e.kind == "trigger" for e in self.entries) for s in SCHEMAS},
            "runtime_sql_files": len(self.updates()),
        }


def inventory(path: Path, *, expected_sha256: str = DATABASE_SHA256,
              require_release_layout: bool = True) -> DatabasePlan:
    """Verify the entire ZIP before exposing an import plan; never extract it.

    Tests may supply their own expected digest and minimal layout. The CLI never
    exposes those overrides: only the matching reviewed seed is executable.
    """
    with path.open("rb") as source:
        archive_digest = stream_digest(source)
    if archive_digest != expected_sha256:
        raise GateError("Database archive SHA256 differs from the reviewed release")
    entries: list[SqlEntry] = []
    total = 0
    seen: set[str] = set()
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise GateError("Archive entry limit exceeded")
        for info in infos:
            name = info.filename
            parts = PurePosixPath(name).parts
            mode = info.external_attr >> 16
            normalized = name.rstrip("/").casefold()
            if (not name or "\\" in name or "\x00" in name or name.startswith("/")
                    or any(part in ("", ".", "..") for part in name.rstrip("/").split("/"))
                    or ":" in name or stat.S_ISLNK(mode)
                    or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR))
                    or not parts or parts[0] != "Database"):
                raise GateError("Unsafe archive member")
            if normalized in seen:
                raise GateError("Duplicate archive member")
            seen.add(normalized)
            if info.flag_bits & 1:
                raise GateError("Encrypted archive members are unsupported")
            if info.file_size > MAX_ENTRY_BYTES or info.file_size < 0:
                raise GateError("Archive member size limit exceeded")
            total += info.file_size
            if total > MAX_TOTAL_BYTES:
                raise GateError("Archive expanded-size limit exceeded")
            if info.is_dir():
                continue
            # Streaming all members validates ZIP CRCs too, with bounded memory.
            sha256 = hashlib.sha256()
            sha1 = hashlib.sha1()
            actual_size = 0
            with archive.open(info) as source:
                while block := source.read(1024 * 1024):
                    actual_size += len(block)
                    if actual_size > MAX_ENTRY_BYTES or actual_size > info.file_size:
                        raise GateError("Archive member expanded beyond its declaration")
                    sha256.update(block)
                    sha1.update(block)
            if actual_size != info.file_size:
                raise GateError("Archive member size differs from its declaration")
            if len(parts) == 3 and parts[1] in SCHEMAS and name.endswith(".sql"):
                match = re.fullmatch(r"(table|trigger)-([A-Za-z_][A-Za-z0-9_]*)\.sql", parts[-1])
                if not match:
                    raise GateError("Unexpected SQL seed filename")
                kind, object_name = match.groups()
                identifier(object_name)
                if not actual_size:
                    raise GateError("Empty SQL seed member")
                entries.append(SqlEntry(name, parts[1], kind, object_name, actual_size, sha256.hexdigest(), sha1.hexdigest()))
            elif len(parts) == 8 and parts[:6] == ("Database", "RuntimeSQL", "Data", "data", "sql", "updates") and name.endswith(".sql"):
                folder = parts[6]
                suffix = folder.removeprefix("pending_")
                schema = {"db_auth": SCHEMAS[0], "db_characters": SCHEMAS[1], "db_world": SCHEMAS[2]}.get(suffix)
                if not schema or not re.fullmatch(r"[A-Za-z0-9_.-]+\.sql", parts[-1]):
                    raise GateError("Unexpected runtime SQL filename")
                entries.append(SqlEntry(name, schema, "update", parts[-1], actual_size, sha256.hexdigest(), sha1.hexdigest()))
        if require_release_layout:
            for schema, expected in EXPECTED_TABLES.items():
                if sum(e.schema == schema and e.kind == "table" for e in entries) != expected:
                    raise GateError("Seed table inventory differs from the reviewed release")
            if sum(e.kind == "trigger" for e in entries) != 1 or len([e for e in entries if e.kind == "update"]) != 236:
                raise GateError("Seed trigger/runtime inventory differs from the reviewed release")
            if "Database/CreateDatabases.sql".casefold() not in seen:
                raise GateError("Missing database container script")
    # All tables in all schemas precede all triggers; updates follow only later.
    entries.sort(key=lambda e: ({"table": 0, "trigger": 1, "update": 2}[e.kind], SCHEMAS.index(e.schema), e.path))
    update_keys = [(e.schema, e.name) for e in entries if e.kind == "update"]
    if len(update_keys) != len(set(update_keys)):
        raise GateError("Ambiguous runtime update names")
    return DatabasePlan(archive_digest, tuple(entries), total)


def assert_empty_targets(query: Callable[[str], str]) -> None:
    """Preflight ALL targets before the first mutation; query errors fail closed."""
    schema_list = ",".join("'" + s + "'" for s in SCHEMAS)
    for catalog, field in (("tables", "table_schema"), ("routines", "routine_schema"),
                           ("triggers", "trigger_schema"), ("events", "event_schema")):
        result = query(f"SELECT COUNT(*) FROM information_schema.{catalog} WHERE {field} IN ({schema_list});").strip()
        if not result.isascii() or not result.isdigit():
            raise GateError("Cannot establish that all seed targets are empty")
        if int(result):
            raise GateError("Refused fresh seed: one or more targets contain existing objects")


def update_action(entry: SqlEntry, receipt_hash: str | None, *, probe_updater_policy: bool = False) -> str:
    if receipt_hash is None:
        return "apply"
    if not re.fullmatch("[0-9A-Fa-f]{40}", receipt_hash):
        raise GateError("Runtime update checksum differs from its existing receipt")
    if probe_updater_policy:
        # Original ByteArrayToHexStr uses %02X and string-exact receipt comparison.
        return "verified_existing" if receipt_hash == entry.sha1.upper() else "reapply_changed"
    if receipt_hash.lower() != entry.sha1:
        raise GateError("Runtime update checksum differs from its existing receipt")
    return "verified_existing"
