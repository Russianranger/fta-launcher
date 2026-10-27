#!/usr/bin/env python3
"""Exercise Android's guest/bind layout through native Linux PRoot, then rehash.

Synthetic public fixtures only. This cannot qualify Android kernel execution.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess

from build_runtime_catalog import catalog
from check_client_runtime import LOCK


def verify(root, rows):
    expected = {row[1]: row for row in rows}; seen = set()
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            path = Path(directory) / name; relative = path.relative_to(root).as_posix()
            if relative not in expected:
                raise ValueError('unexpected runtime mutation')
            seen.add(relative); kind, _, mode, size, offset, sha, link = expected[relative]
            info = path.lstat()
            if kind == 's':
                if not stat.S_ISLNK(info.st_mode) or os.readlink(path) != link:
                    raise ValueError('runtime symlink changed')
            elif kind == 'h':
                if not stat.S_ISREG(info.st_mode) or not os.path.samefile(path, root / link):
                    raise ValueError('runtime hardlink changed')
            elif kind == 'd':
                if not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != int(mode):
                    raise ValueError('runtime directory changed')
            else:
                if not stat.S_ISREG(info.st_mode) or info.st_size != int(size) or stat.S_IMODE(info.st_mode) != int(mode):
                    raise ValueError('runtime file metadata changed')
                with path.open('rb') as stream:
                    if hashlib.file_digest(stream, 'sha256').hexdigest() != sha:
                        raise ValueError('runtime file bytes changed')
    if seen != set(expected):
        raise ValueError('runtime inventory changed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('proot', 'loader', 'archive', 'fixtures', 'work'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve(); work.mkdir()
    lock = json.loads(LOCK.read_text()); data = catalog(args.archive, lock)
    if hashlib.sha256(data).hexdigest() != lock['catalog']['sha256']:
        raise ValueError('catalog drift')
    rows = [line.split('\t') for line in data.decode().splitlines()[1:-1]]
    root = work / 'root'; root.mkdir()
    subprocess.run(['tar', '--no-same-owner', '--same-permissions', '-xzf', str(args.archive.resolve()), '-C', str(root)], check=True)
    verify(root, rows)
    for name in ('session', 'prefix', 'tmp', 'cache'):
        (work / name).mkdir(mode=0o700)
    command = [str(args.proot.resolve()), '--kill-on-exit', '--sysvipc', '-0', '-r', str(root),
               '-b', '/dev', '-b', '/proc', '-b', '/sys']
    for source, destination in ((args.fixtures.resolve(), '/client'), (work / 'session', '/session'),
            (work / 'prefix', '/prefix'), (work / 'tmp', '/tmp'), (work / 'cache', '/var/cache')):
        command += ['-b', f'{source}:{destination}']
    command += ['-w', '/session', '/usr/bin/env', '-i', 'PYTHONDONTWRITEBYTECODE=1', '/usr/bin/python3', '-B', '/client/android_probe.py', '--wine']
    with (work / 'probe.log').open('wb') as output:
        result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
            env=dict(os.environ, PROOT_LOADER=str(args.loader.resolve()), PROOT_TMP_DIR=str(work / 'tmp'), PROOT_NO_SECCOMP='1'), timeout=930)
    with (work / 'probe.log').open('rb') as source:
        raw = source.read(65537)
    if len(raw) > 65536:
        raise ValueError('oversized guest output')
    text = raw.decode()
    if result.returncode or 'FTA_ANDROID_SYNTHETIC_PASS' not in text.splitlines():
        raise ValueError('exact guest path failed; inspect private probe.log')
    verify(root, rows)
    print(json.dumps(dict(scope='android-guest-layout-on-native-linux', runtimeEntries=len(rows),
        fullPreRehash=True, fullPostRehash=True, syntheticMixedRuntimePassed=True, androidExecutionQualified=False)))


if __name__ == '__main__':
    main()
