#!/usr/bin/env python3
"""Verify and stage the externally supplied From the Ashes source without editing it."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import zipfile

SOURCE_ROOT = "AzerothCore_Linux"
SOURCE_AUXILIARY_FILES = frozenset({"README.txt"})
SOURCE_SHA256 = "907a0a0bfde4f86b0471f798d93d2a74fdc50ece398cbb31c0ba22ddbc8e81bb"
MAX_ENTRIES = 20000
MAX_UNCOMPRESSED = 1024 * 1024 * 1024
MAX_FILE = 128 * 1024 * 1024
CHUNK = 1024 * 1024
MANIFEST = Path(__file__).resolve().parents[1] / "manifests/reference-inputs.json"


class SourceError(ValueError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked_entries(archive: zipfile.ZipFile, root: str = SOURCE_ROOT) -> list[zipfile.ZipInfo]:
    entries = archive.infolist()
    if len(entries) > MAX_ENTRIES:
        raise SourceError("Archive has too many entries")
    seen: dict[str, bool] = {}
    total = 0
    for entry in entries:
        name = entry.filename
        path = PurePosixPath(name)
        parts = name.rstrip("/").split("/")
        if (not name or name.startswith("/") or "\\" in name or ":" in name
                or any(p in ("", ".", "..") for p in parts)
                or (path.parts[0] != root and name not in SOURCE_AUXILIARY_FILES)):
            raise SourceError("Archive contains an unsafe path")
        normalized = name.rstrip("/")
        if normalized in seen:
            raise SourceError("Archive contains duplicate paths")
        mode = entry.external_attr >> 16
        if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
            raise SourceError("Archive contains a link or special file")
        if entry.flag_bits & 1:
            raise SourceError("Encrypted source archives are unsupported")
        if entry.file_size > MAX_FILE:
            raise SourceError("Archive entry exceeds the size limit")
        total += entry.file_size
        if total > MAX_UNCOMPRESSED:
            raise SourceError("Archive exceeds the total size limit")
        seen[normalized] = entry.is_dir()
    for name in seen:
        for parent in PurePosixPath(name).parents:
            if parent.as_posix() in seen and not seen[parent.as_posix()]:
                raise SourceError("Archive file conflicts with a directory")
    return entries


def inventory_tree(source: Path) -> dict:
    """A content digest covers all file paths/bytes; never emits file contents."""
    files: list[tuple[str, str]] = []
    total = 0
    for path in sorted(source.rglob("*")):
        if path.is_symlink():
            raise SourceError("Source tree contains a symlink")
        if path.is_file():
            total += path.stat().st_size
            files.append((path.relative_to(source).as_posix(), sha256_file(path)))
    tree_digest = hashlib.sha256()
    for path, digest in files:
        tree_digest.update(path.encode("utf-8") + b"\0" + digest.encode("ascii") + b"\n")
    modules = sorted(p.name for p in (source / "modules").iterdir() if p.is_dir())
    licenses = [{"path": p, "sha256": d} for p, d in files
                if PurePosixPath(p).name.lower().startswith(("license", "copying"))]
    return {"root": SOURCE_ROOT, "file_count": len(files), "uncompressed_file_bytes": total,
            "tree_sha256": tree_digest.hexdigest(), "modules": modules, "license_files": licenses}


def verify_source_tree(source: Path, *, port_profile: str | None = None) -> dict:
    if port_profile:
        from fta_port import load_profile
        expected = load_profile(port_profile)['result_inventory']
    else:
        expected = json.loads(MANIFEST.read_text())["source_inventory"]
    actual = inventory_tree(source)
    if actual != expected:
        raise SourceError("Source tree differs from the pinned distribution; use a separately reviewed port manifest")
    return actual


def stage_source(archive_path: Path, destination: Path, *, expected_sha256: str = SOURCE_SHA256,
                 expected_root: str = SOURCE_ROOT) -> Path:
    if destination.exists() or destination.is_symlink():
        raise SourceError("Destination must not already exist")
    if sha256_file(archive_path) != expected_sha256:
        raise SourceError("Source archive SHA256 mismatch")
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".fta-source-", dir=destination.parent))
    try:
        with zipfile.ZipFile(archive_path) as archive:
            entries = checked_entries(archive, expected_root)
            for entry in entries:
                target = staging.joinpath(*PurePosixPath(entry.filename).parts)
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as original, target.open("xb") as copied:
                    shutil.copyfileobj(original, copied, CHUNK)
                # Preserve ordinary permission bits without special/setuid bits.
                permissions = (entry.external_attr >> 16) & 0o777
                target.chmod(permissions or 0o644)
        os.rename(staging / expected_root, destination)
        return destination
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    stage = commands.add_parser("stage")
    stage.add_argument("archive", type=Path)
    stage.add_argument("destination", type=Path)
    inventory = commands.add_parser("inventory")
    inventory.add_argument("source", type=Path)
    inventory.add_argument("--port-profile")
    args = parser.parse_args()
    try:
        if args.command == "stage":
            source = stage_source(args.archive, args.destination)
            print(json.dumps(verify_source_tree(source), indent=2))
        else:
            print(json.dumps(verify_source_tree(args.source, port_profile=args.port_profile), indent=2))
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Source verification refused: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
