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
from original_startup import STAGES, EVENTS, PREFIX_STAGES, FAULT_BOUNDS, HOST_FAULT_BOUNDS


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


def fault_transcript(text, status):
    lines = text.splitlines()
    if status != 1 or lines.count('FTA_CLIENT_STARTUP_FAILED') != 1 or 'FTA_CLIENT_STARTUP_PASS' in lines:
        raise ValueError('crash fixture must remain failed')
    expected = [f'FTA_ANDROID_STAGE {s} {11 if s == "original-startup" else 0}' for s in STAGES]
    if [l for l in lines if l.startswith('FTA_ANDROID_STAGE ')] != expected:
        raise ValueError('crash fixture cleanup incomplete')
    events = failure_events(lines)
    expected_events = {'client-started': 1, 'client-early-exit': 0xc0000005,
        'observer-failure-phase': 1, 'client-state': 2, 'client-exit-code': 0xc0000005, 'host-state': 0}
    if events != dict(expected_events, **{'observation-elapsed-ms': events.get('observation-elapsed-ms', -1)}):
        raise ValueError('crash fixture exit differs')
    fault = {}
    for line in lines:
        if not line.startswith('FTA_CLIENT_FAULT '): continue
        f = line.split(' ')
        if len(f) != 3 or f[1] not in FAULT_BOUNDS or f[1] in fault or not f[2].isdigit() or int(f[2]) > FAULT_BOUNDS[f[1]]:
            raise ValueError('invalid fault observation')
        fault[f[1]] = int(f[2])
    if fault.keys() != FAULT_BOUNDS.keys(): raise ValueError('fault details missing')
    required = dict(pidCorrelated=1, traceLinesDropped=0, traceStateOverflow=0, exceptionCode=0xc0000005,
        accessType=1, accessDetailsKnown=1, moduleId=1, exitCode=0xc0000005, exitObserved=1, exceptionMatchesExit=1)
    if any(fault[k] != v for k, v in required.items()) or fault['accessViolationCount'] < 1 or not fault['loadedClientModules'] & 1:
        raise ValueError('synthetic write fault not correlated: ' + json.dumps(fault, sort_keys=True))
    if not 0 <= fault['moduleRva'] < fault['moduleSize'] or fault['moduleBase'] + fault['moduleRva'] != fault['exceptionAddress']:
        raise ValueError('fault module location differs')
    return fault


def failure_events(lines):
    values = {}
    for line in lines:
        if not line.startswith('FTA_CLIENT_EVENT '): continue
        fields = line.split(' ')
        if len(fields) != 3 or fields[1] not in EVENTS or fields[1] in values or not fields[2].isdigit() or int(fields[2]) > 0xffffffff:
            raise ValueError('invalid lifecycle event')
        values[fields[1]] = int(fields[2])
    if not 0 <= values.get('observation-elapsed-ms', -1) <= 145000:
        raise ValueError('failure elapsed observation missing')
    return values


def late_transcript(text, status, mode):
    lines = text.splitlines()
    if status != 1 or lines.count('FTA_CLIENT_STARTUP_FAILED') != 1 or 'FTA_CLIENT_STARTUP_PASS' in lines:
        raise ValueError('post-host exit must remain failed')
    if [l for l in lines if l.startswith('FTA_ANDROID_STAGE ')] != [
            f'FTA_ANDROID_STAGE {stage} {11 if stage == "original-startup" else 0}' for stage in STAGES]:
        raise ValueError('post-host cleanup incomplete')
    events = failure_events(lines)
    expected = {'client-started': 1, 'host-header-valid': 1, 'host-owned': 1, 'host-channels': 2,
        'observer-failure-phase': 2}
    if any(events.get(k) != v for k, v in expected.items()) or any(k in events for k in ('startup-observed', 'client-stopped', 'host-stopped')):
        raise ValueError('fixture did not exit during post-host observation')
    if mode == 'client-exit':
        if events.get('client-state') != 2 or events.get('client-exit-code') != 0 or events.get('client-early-exit') != 0:
            raise ValueError('clean client exit missing')
        prefix = 'FTA_CLIENT_FAULT '; bounds = FAULT_BOUNDS; code = 0
    else:
        code = 73 if mode == 'host-exit' else 0xc0000005
        if events.get('client-state') != 1 or events.get('host-state') != 2 or events.get('host-exit-code') != code:
            raise ValueError('host exit not independently observed')
        prefix = 'FTA_HOST_FAULT '; bounds = HOST_FAULT_BOUNDS
    fault = {}
    for line in lines:
        if not line.startswith(prefix): continue
        f = line.split(' ')
        if len(f) != 3 or f[1] not in bounds or f[1] in fault or not f[2].isdigit() or int(f[2]) > bounds[f[1]]:
            raise ValueError('invalid late fault record')
        fault[f[1]] = int(f[2])
    if fault.keys() != bounds.keys() or any(fault[k] != v for k, v in dict(pidCorrelated=1,
            traceLinesDropped=0, traceStateOverflow=0, exitCode=code, exitObserved=1).items()):
        raise ValueError('late process exit correlation missing')
    if mode == 'host-crash' and (fault['moduleId'] != 1 or fault['moduleBase'] <= 0xffffffff or
            fault['exceptionMatchesExit'] != 1 or fault['accessType'] != 1 or fault['accessDetailsKnown'] != 1 or
            not 0 <= fault['moduleRva'] < fault['moduleSize'] or fault['moduleBase'] + fault['moduleRva'] != fault['exceptionAddress']):
        raise ValueError('64-bit host write fault not located')
    return dict(events=events, fault=fault, remainsFailed=True)


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
        def command(prepare, crash=False, late=None):
            cmd = [str(a.proot.resolve()), '--kill-on-exit', '--fta-readonly=' + str(work / 'prefix-source' if prepare else original), '--sysvipc', '-0', '-r', str(root), '-b', '/dev', '-b', '/proc', '-b', '/sys']
            for source, target in ((a.fixtures.resolve(), '/client'), (work / 'prefix-source' if prepare else original, '/immutable'),
                    (work / 'prefix-view' if prepare else view, '/view'), (work / 'prefix', '/prefix'),
                    (work / 'session', '/session'), (work / 'cache', '/var/cache'), (tmp, '/tmp')):
                cmd += ['-b', f'{source}:{target}']
            return cmd + ['-w', '/view', '/usr/bin/env', '-i', 'PYTHONDONTWRITEBYTECODE=1', '/usr/bin/python3', '-B',
                '/client/original_startup.py', '--prepare' if prepare else '--fixture-' + late if late else '--fixture-crash' if crash else '--fixture']
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
        verify(root, rows)
        if snapshot(original) != before: raise ValueError('protected source changed')
        with (work / 'fault.log').open('wb') as log:
            crash = subprocess.run(command(False, crash=True), stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                env=environment, timeout=1000)
        with (work / 'fault.log').open('rb') as stream: fault_data = stream.read(65537)
        if len(fault_data) > 65536: raise ValueError('oversized fault transcript')
        fault = fault_transcript(fault_data.decode(), crash.returncode)
        late_results = {}
        for mode in ('client-exit', 'host-exit', 'host-crash'):
            with (work / (mode + '.log')).open('wb') as log:
                case = subprocess.run(command(False, late=mode), stdin=subprocess.DEVNULL, stdout=log,
                    stderr=subprocess.STDOUT, env=environment, timeout=180)
            with (work / (mode + '.log')).open('rb') as log: data = log.read(65537)
            if len(data) > 65536: raise ValueError('oversized late transcript')
            late_results[mode] = late_transcript(data.decode(), case.returncode, mode)
            if snapshot(original) != before: raise ValueError('protected source changed in late fixture')

    with (work / 'probe.log').open('rb') as stream: data = stream.read(65537)
    if len(data) > 65536: raise ValueError('oversized startup transcript')
    events = transcript(data.decode(), process.returncode)
    if snapshot(original) != before: raise ValueError('protected source changed')
    verify(root, rows)
    result = dict(scope='synthetic-original-startup-observer-on-arm64', events=events, prefixPreparedBeforeClientExposure=True,
        prefixCache=prefix_cache, separateOwnedPrefixInvocation=True,
        syntheticWriteFault=fault, faultRemainsFailed=True, postHostExitFixtures=late_results, rawPrivateLogsExported=False,
        protectedSourceUnchanged=True, fullRuntimePrePostRehash=True, originalClientExecuted=False,
        androidExecuted=False, warcraftXLCompatible=False, gameLoginQualified=False)
    (work / 'evidence.json').write_text(json.dumps(result, indent=2) + '\n'); print(json.dumps(result))


if __name__ == '__main__': main()
