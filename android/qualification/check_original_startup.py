#!/usr/bin/env python3
"""Qualify the startup observer with synthetic x86 client / x64 host on ARM64."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from check_guarded_wine import snapshot
from check_android_runtime_path import verify
from build_runtime_catalog import catalog
from check_client_runtime import LOCK
from original_startup import STAGES, EVENTS, PREFIX_STAGES


def transcript(text, status):
    lines = text.splitlines()
    if status or lines.count('FTA_CLIENT_STARTUP_PASS') != 1 or any(
            line in ('FTA_CLIENT_STARTUP_FAILED', 'FTA_CLIENT_STOP_FAILED') for line in lines):
        raise ValueError('startup observer failed')
    if [line for line in lines if line.startswith('FTA_ANDROID_STAGE ')] != [f'FTA_ANDROID_STAGE {s} 0' for s in STAGES]:
        raise ValueError('startup stages incomplete')
    values = {}
    for line in lines:
        if not line.startswith('FTA_CLIENT_EVENT '): continue
        match = re.fullmatch(r'FTA_CLIENT_EVENT ([a-z-]+) ([0-9]{1,10})', line)
        if not match or match[1] not in EVENTS or match[1] in values: raise ValueError('invalid startup events')
        values[match[1]] = int(match[2])
    required = {'client-started', 'host-header-valid', 'host-owned', 'startup-observed', 'client-stopped', 'host-stopped'}
    if not required <= values.keys() or any(values[n] != 1 for n in required) or not 2 <= values.get('host-channels', 0) <= 16:
        raise ValueError('startup observations incomplete')
    if set(values) - required - {'host-channels', 'client-forced-stop'} or values.get('client-forced-stop', 1) != 1:
        raise ValueError('contradictory startup events')
    return values


def prefix_transcript(text, status):
    lines = text.splitlines()
    if status or lines.count('FTA_CLIENT_PREFIX_PASS') != 1 or any(
            line in ('FTA_CLIENT_PREFIX_FAILED', 'FTA_CLIENT_STOP_FAILED', 'FTA_CLIENT_STARTUP_PASS', 'FTA_CLIENT_STARTUP_FAILED') or line.startswith('FTA_CLIENT_EVENT ') for line in lines):
        raise ValueError('prefix preparation failed')
    if [line for line in lines if line.startswith('FTA_ANDROID_STAGE ')] != [f'FTA_ANDROID_STAGE {s} 0' for s in PREFIX_STAGES]:
        raise ValueError('prefix stages incomplete')
    cache = [line.split()[1:] for line in lines if line.startswith('FTA_PREFIX_CACHE ')]
    if not cache or any(len(row) != 4 or any(not v.isdigit() or int(v) > 1024**4 for v in row) for row in cache):
        raise ValueError('prefix cache observations missing')
    synced, accepted, errors, byte_count = map(int, cache[-1])
    if not 0 < accepted <= synced or byte_count <= 0: raise ValueError('prefix cache advice not exercised')
    return dict(syncedFiles=synced, acceptedFiles=accepted, errors=errors, advisedBytes=byte_count, actualEvictionMeasured=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('proot', 'loader', 'archive', 'runtime', 'fixtures', 'work'): parser.add_argument('--' + name, type=Path, required=True)
    a = parser.parse_args()
    if os.uname().machine != 'aarch64': raise ValueError('native ARM64 required')
    work = a.work.resolve(); work.mkdir(); root = a.runtime.resolve()
    rows = [line.split('\t') for line in catalog(a.archive, json.loads(LOCK.read_text())).decode().splitlines()[1:-1]]
    verify(root, rows)
    for name in ('immutable', 'view', 'prefix', 'session', 'cache', 'prefix-source', 'prefix-view'): (work / name).mkdir()
    original = work / 'immutable'; view = work / 'view'
    for parent in (original, view): (parent / 'Utils').mkdir()
    for name in ('Wow.exe', 'Utils/WarcraftXLHost.exe'):
        shutil.copyfile(a.fixtures / name, original / name); (view / name).symlink_to('/immutable/' + name)
    before = snapshot(original)
    with tempfile.TemporaryDirectory(prefix='fta-original-') as temporary:
        tmp = Path(temporary)
        def command(prepare):
            cmd = [str(a.proot.resolve()), '--kill-on-exit', '--fta-readonly=' + str(work / 'prefix-source' if prepare else original), '--sysvipc', '-0', '-r', str(root), '-b', '/dev', '-b', '/proc', '-b', '/sys']
            for source, target in ((a.fixtures.resolve(), '/client'), (work / 'prefix-source' if prepare else original, '/immutable'),
                    (work / 'prefix-view' if prepare else view, '/view'), (work / 'prefix', '/prefix'),
                    (work / 'session', '/session'), (work / 'cache', '/var/cache'), (tmp, '/tmp')):
                cmd += ['-b', f'{source}:{target}']
            return cmd + ['-w', '/view', '/usr/bin/env', '-i', 'PYTHONDONTWRITEBYTECODE=1', '/usr/bin/python3', '-B',
                '/client/original_startup.py', '--prepare' if prepare else '--fixture']
        environment = dict(os.environ, PROOT_LOADER=str(a.loader.resolve()), PROOT_TMP_DIR=str(tmp), PROOT_NO_SECCOMP='1', PROOT_DONT_POLLUTE_ROOTFS='1')
        with (work / 'prefix.log').open('wb') as log:
            prefix = subprocess.run(command(True), stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, env=environment, timeout=1000)
        data = (work / 'prefix.log').read_bytes()
        if len(data) > 65536: raise ValueError('oversized prefix transcript')
        prefix_cache = prefix_transcript(data.decode(), prefix.returncode)
        verify(root, rows)
        if snapshot(original) != before: raise ValueError('prefix preparation accessed source')
        # The second owned tracer reuses the completed prefix; it does not run wineboot.
        with (work / 'probe.log').open('wb') as log:
            process = subprocess.run(command(False), stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                env=environment, timeout=1000)
    with (work / 'probe.log').open('rb') as stream: data = stream.read(65537)
    if len(data) > 65536: raise ValueError('oversized startup transcript')
    events = transcript(data.decode(), process.returncode)
    if snapshot(original) != before: raise ValueError('protected source changed')
    verify(root, rows)
    result = dict(scope='synthetic-original-startup-observer-on-arm64', events=events, prefixPreparedBeforeClientExposure=True,
        prefixCache=prefix_cache, separateOwnedPrefixInvocation=True,
        protectedSourceUnchanged=True, fullRuntimePrePostRehash=True, originalClientExecuted=False,
        androidExecuted=False, warcraftXLCompatible=False, gameLoginQualified=False)
    (work / 'evidence.json').write_text(json.dumps(result, indent=2) + '\n'); print(json.dumps(result))


if __name__ == '__main__': main()
