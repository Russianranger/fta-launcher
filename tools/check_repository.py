#!/usr/bin/env python3
"""Reject private runtime payloads from the tracked public repository.

This is a publication boundary check, not a claim that arbitrary source has
received a complete credential audit. Synthetic SQL in Python fixtures is fine;
seed files and binaries are not.
"""
from pathlib import Path
import subprocess
import sys


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).split(b"\0")
    rejected = []
    forbidden = {".zip", ".7z", ".sql", ".apk", ".aab", ".so", ".db", ".jks", ".keystore"}
    private_dirs = {"inputs", "private-work", "external", "reports"}
    for raw in paths:
        if not raw:
            continue
        relative = Path(raw.decode("utf-8"))
        path = root / relative
        if (relative.suffix.lower() in forbidden or relative.parts[0] in private_dirs
                or relative.name == ".env" or relative.name.startswith(".env.")):
            rejected.append(str(relative))
        elif path.is_file() and path.stat().st_size > 8 * 1024 * 1024:
            rejected.append(str(relative))
    if rejected:
        print("Refusing private/generated payloads in Git:", file=sys.stderr)
        for path in rejected:
            print(path, file=sys.stderr)
        return 1
    print("Public repository boundary: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
