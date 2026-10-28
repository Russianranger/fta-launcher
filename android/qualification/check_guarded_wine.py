#!/usr/bin/env python3
"""Run protected PE32/PE64 file access on native ARM64 Linux with full runtime rehash."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from build_runtime_catalog import catalog
from check_android_runtime_path import verify
from check_client_runtime import LOCK
from guarded_wine import CHECKS, STAGES


def snapshot(root):
    result = {}
    for path in [root, *sorted(root.rglob('*'))]:
        stat = path.lstat()
        if path.is_symlink(): raise ValueError('protected fixture contains link')
        result[str(path.relative_to(root))] = [stat.st_mode, stat.st_uid, stat.st_gid, stat.st_ino,
            stat.st_nlink, stat.st_mtime_ns, stat.st_ctime_ns,
            hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None]
    return result


def transcript(text, status):
    lines = text.splitlines()
    if status or lines.count('FTA_GUARDED_WINE_PASS') != 1: raise ValueError('guarded Wine failed')
    stages = [line for line in lines if line.startswith('FTA_ANDROID_STAGE ')]
    expected = [f'FTA_ANDROID_STAGE {stage} 0' for stage in STAGES]
    if stages != expected: raise ValueError('incomplete guarded stages')
    seen = [line for line in lines if line.startswith('FTA_GUARDED_WIN ')]
    expected = [f'FTA_GUARDED_WIN {arch} {name} 0' for arch in ('x86', 'x64') for name in CHECKS]
    if seen != expected: raise ValueError('incomplete Windows storage checks')
    deletion = [line for line in lines if line.startswith('FTA_GUARDED_DELETE ')]
    if len(deletion) != 2: raise ValueError('missing Windows deletion observations')
    observed = {}
    for arch, line in zip(('x86', 'x64'), deletion):
        match = re.fullmatch(r'FTA_GUARDED_DELETE ' + arch + r' ([01]) ([0-9]{1,5})', line)
        if not match: raise ValueError('invalid Windows deletion observation')
        observed[arch] = dict(returnedSuccess=bool(int(match[1])), error=int(match[2]))
    if any(line.startswith('FTA_GUARDED_ERROR ') or line == 'FTA_GUARDED_WINE_FAILED' for line in lines):
        raise ValueError('contradictory guarded Wine transcript')
    return dict(stages={stage: 0 for stage in STAGES}, checksPerArchitecture=len(CHECKS), windowsDeletion=observed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('proot', 'loader', 'archive', 'fixtures', 'work'): parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args(); work = args.work.resolve(); work.mkdir()
    if os.uname().machine != 'aarch64': raise ValueError('native ARM64 is required')
    lock = json.loads(LOCK.read_text()); data = catalog(args.archive, lock)
    if hashlib.sha256(data).hexdigest() != lock['catalog']['sha256']: raise ValueError('catalog drift')
    rows = [line.split('\t') for line in data.decode().splitlines()[1:-1]]
    root = work / 'runtime'; root.mkdir()
    subprocess.run(['tar', '--no-same-owner', '--same-permissions', '-xzf', str(args.archive.resolve()), '-C', str(root)], check=True)
    verify(root, rows)
    for name in ('session', 'prefix', 'cache', 'immutable', 'view'): (work / name).mkdir(mode=0o700)
    original = work / 'immutable'; view = work / 'view'
    for parent in (original, view):
        for name in ('Data', 'WTF', 'Logs'): (parent / name).mkdir()
    (original / 'Data/canary.bin').write_bytes(bytes(i % 251 for i in range(65536)))
    (original / 'WTF/Config.wtf').write_text('fixture-original-settings\n')
    for name in ('probe32.exe', 'probe64.exe'): shutil.copyfile(args.fixtures / name, original / name)
    for name in ('probe32.exe', 'probe64.exe', 'Data/canary.bin'): (view / name).symlink_to('/immutable/' + name)
    shutil.copyfile(original / 'WTF/Config.wtf', view / 'WTF/Config.wtf')
    before = snapshot(original)
    with tempfile.TemporaryDirectory(prefix='fta-guard-') as temporary:
        base = Path(temporary); tmp = base / ('p' * (79 - len(os.fsencode(base)) - 1)); tmp.mkdir()
        command = [str(args.proot.resolve()), '--kill-on-exit', '--fta-readonly=' + str(original), '--sysvipc', '-0', '-r', str(root),
                   '-b', '/dev', '-b', '/proc', '-b', '/sys']
        for source, target in ((args.fixtures.resolve(), '/client'), (original, '/immutable'), (view, '/view'),
                (work / 'session', '/session'), (work / 'prefix', '/prefix'), (tmp, '/tmp'), (work / 'cache', '/var/cache')):
            command += ['-b', f'{source}:{target}']
        command += ['-w', '/view', '/usr/bin/env', '-i', 'PYTHONDONTWRITEBYTECODE=1', '/usr/bin/python3', '-B', '/client/guarded_wine.py', '--wine']
        with (work / 'probe.log').open('wb') as log:
            value = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                env=dict(os.environ, PROOT_LOADER=str(args.loader.resolve()), PROOT_TMP_DIR=str(tmp), PROOT_NO_SECCOMP='1'), timeout=1000)
    with (work / 'probe.log').open('rb') as incoming: output = incoming.read(65537)
    if len(output) > 65536: raise ValueError('oversized probe log')
    after = snapshot(original)
    (work / 'source-observation.json').write_text(json.dumps(dict(protectedFixtureUnchanged=after == before,
        changedEntries=sorted(n for n in set(before) | set(after) if before.get(n) != after.get(n))), indent=2) + '\n')
    if after != before: raise ValueError('protected fixture changed')
    result = transcript(output.decode(), value.returncode)
    if not (view / 'WTF/Config.wtf').read_bytes().startswith(b'private'): raise ValueError('independent configuration not written')
    verify(root, rows)
    result.update(scope='guarded-wine-on-native-arm64-linux', runtimeEntries=len(rows), fullRuntimePrePostRehash=True,
        protectedFixtureUnchanged=True, independentConfigurationWritten=True, sourceSharedBytes=65536 +
        sum((original / n).stat().st_size for n in ('probe32.exe', 'probe64.exe')),
        sourceCopiedBytes=(original / 'WTF/Config.wtf').stat().st_size,
        androidExecuted=False, originalClientExecuted=False, importedClientAccessed=False)
    (work / 'evidence.json').write_text(json.dumps(result, indent=2) + '\n'); print(json.dumps(result))


if __name__ == '__main__': main()
