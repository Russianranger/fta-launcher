#!/usr/bin/env python3
"""Apply only a pinned FTA portability patch profile to a separate source copy."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile

from fta_source import SourceError, inventory_tree, sha256_file, verify_source_tree

PORT_DIRECTORY = Path(__file__).resolve().parents[1] / 'native-port'


def load_profile(name: str) -> dict:
    profiles = json.loads((PORT_DIRECTORY / 'profiles.json').read_text())
    if name not in profiles['profiles']:
        raise SourceError('Unknown reviewed native port profile')
    return profiles['profiles'][name]


def safe_relative_path(name: str) -> Path:
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or '\\' in name or ':' in name
            or any(part in ('', '.', '..') for part in name.split('/'))):
        raise SourceError('Unsafe native port manifest path')
    return Path(*path.parts)


def check_patch_paths(patch: Path, expected_paths: set[str]) -> None:
    """The reviewed series may only modify existing allowlisted source files."""
    old_paths, new_paths = [], []
    for line in patch.read_text().splitlines():
        if line.startswith(('--- ', '+++ ')):
            prefix = 'a/' if line.startswith('--- ') else 'b/'
            name = line[4:].split('\t', 1)[0]
            if not name.startswith(prefix):
                raise SourceError('Patch adds, deletes or renames a source file')
            name = name[2:]
            safe_relative_path(name)
            (old_paths if prefix == 'a/' else new_paths).append(name)
    if set(old_paths) != expected_paths or old_paths != new_paths or len(old_paths) != len(set(old_paths)):
        raise SourceError('Patch file paths differ from the reviewed allowlist')


def apply_profile(source: Path, destination: Path, profile_name: str) -> dict:
    verify_source_tree(source)
    profile = load_profile(profile_name)
    if destination.exists() or destination.is_symlink():
        raise SourceError('Port destination must not already exist')
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.fta-port-', dir=destination.parent))
    work = temporary / 'source'
    try:
        shutil.copytree(source, work)
        for entry in profile['patches']:
            patch = PORT_DIRECTORY / safe_relative_path(entry['path'])
            if sha256_file(patch) != entry['sha256']:
                raise SourceError('Reviewed patch SHA256 mismatch')
            check_patch_paths(patch, set(entry['files']))
            for name, hashes in entry['files'].items():
                if sha256_file(work / safe_relative_path(name)) != hashes['before_sha256']:
                    raise SourceError('Source file differs from reviewed patch preimage')
            for command in (['git', 'apply', '--check', str(patch)], ['git', 'apply', str(patch)]):
                result = subprocess.run(command, cwd=work, stdout=subprocess.DEVNULL,
                                        stderr=subprocess.PIPE, check=False)
                if result.returncode:
                    raise SourceError('Reviewed portability patch did not apply exactly')
            for name, hashes in entry['files'].items():
                if sha256_file(work / safe_relative_path(name)) != hashes['after_sha256']:
                    raise SourceError('Patched source file differs from reviewed result')
        result = inventory_tree(work)
        if result != profile['result_inventory']:
            raise SourceError('Patched source tree differs from the reviewed complete result')
        verify_source_tree(source)
        os.rename(work, destination)
        return result
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--profile', required=True)
    args = parser.parse_args()
    try:
        result = apply_profile(args.source, args.destination, args.profile)
        print(json.dumps({'profile': args.profile, 'inventory': result}, indent=2))
    except (OSError, ValueError) as error:
        parser.exit(1, f'Native port refused: {error}\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
