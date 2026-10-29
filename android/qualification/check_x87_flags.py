#!/usr/bin/env python3
"""Host-only synthetic x87 profile comparison, using the exact installed Wine/FEX."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

PROFILES = {'default': {}, 'reduced': {'FEX_X87REDUCEDPRECISION': '1'},
            'strict-reduced': {'FEX_X87REDUCEDPRECISION': '1', 'FEX_X87STRICTREDUCEDPRECISION': '1'}}


def guest(profile):
    import android_probe as base
    class Capture(base.OutputCapture):
        def report(self):
            for line in self.data.splitlines():
                if line.startswith(b'FTA_X87_'): print(line.decode('ascii'), flush=True)
    base.OutputCapture = Capture
    base.STAGES = ('x87', 'wine-stop'); base.MARKERS = {'x87': b'FTA_X87_PASS'}
    os.environ.update(PATH='/opt/wine/bin:/usr/bin:/bin', HOME='/session/home', LANG='C.UTF-8',
        WINEPREFIX='/prefix', WINEARCH='win64', WINEDEBUG='-all',
        WINEDLLOVERRIDES='winemenubuilder,mshtml,mscoree=', WINEESYNC='0', WINEFSYNC='0',
        FEX_DISABLETELEMETRY='1', FEX_SILENTLOG='0', TMPDIR='/tmp', XDG_CACHE_HOME='/session/cache')
    os.environ.update(PROFILES[profile])
    success = False; cleanup = False
    try:
        base.run('x87', ['wine', '/immutable/x87-flags.exe'], 45); success = True
    except Exception: pass
    finally:
        try:
            subprocess.run(['wineserver', '-k'], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, timeout=10, check=True)
            base.run('wine-stop', ['wineserver', '-w'], 30); cleanup = True
        except Exception: pass
    print('FTA_X87_PROFILE ' + profile + ' ' + str(int(success)) + ' ' + str(int(cleanup)), flush=True)
    return 0 if success and cleanup else 1


def main():
    from check_guarded_wine import snapshot
    from check_android_runtime_path import verify
    from build_runtime_catalog import catalog
    from check_client_runtime import LOCK
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('proot', 'loader', 'archive', 'runtime', 'fixtures', 'prefix', 'work'):
        p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args()
    if os.uname().machine != 'aarch64': raise ValueError('native ARM64 required')
    root = a.runtime.resolve(); fixtures = a.fixtures.resolve(); work = a.work.resolve(); work.mkdir()
    rows = [l.split('\t') for l in catalog(a.archive, json.loads(LOCK.read_text())).decode().splitlines()[1:-1]]
    original = work / 'immutable'; original.mkdir()
    shutil.copyfile(fixtures / 'x87-flags.exe', original / 'x87-flags.exe')
    verify(root, rows); before = snapshot(fixtures); protected_before = snapshot(original); results = {}
    for profile in PROFILES:
        case = work / profile; case.mkdir(); (case / 'home').mkdir(); (case / 'view').mkdir()
        (case / 'cache').mkdir()
        with tempfile.TemporaryDirectory(prefix='fta-x87-') as temporary:
            tmp = Path(temporary)
            cmd = [str(a.proot.resolve()), '--kill-on-exit', '--fta-readonly=' + str(original), '--sysvipc', '-0',
                '-r', str(root), '-b', '/dev', '-b', '/proc', '-b', '/sys']
            for source, target in ((fixtures, '/client'), (original, '/immutable'), (case / 'view', '/view'), (a.prefix.resolve(), '/prefix'),
                    (case, '/session'), (case / 'cache', '/var/cache'), (tmp, '/tmp')):
                cmd += ['-b', f'{source}:{target}']
            cmd += ['-w', '/view', '/usr/bin/env', '-i', 'PYTHONDONTWRITEBYTECODE=1', '/usr/bin/python3', '-B',
                '/client/check_x87_flags.py', '--guest', profile]
            environment = dict(os.environ, PROOT_LOADER=str(a.loader.resolve()), PROOT_TMP_DIR=str(tmp),
                PROOT_NO_SECCOMP='1', PROOT_DONT_POLLUTE_ROOTFS='1')
            with (work / (profile + '.log')).open('wb') as log:
                result = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                    env=environment, timeout=120)
            with (work / (profile + '.log')).open('rb') as log: data = log.read(65537)
            if len(data) > 65536: raise ValueError('oversized fixture transcript')
            text = data.decode(); lines = text.splitlines()
            if f'FTA_X87_PROFILE {profile} {int(result.returncode == 0)} 1' not in lines:
                raise ValueError('fixture cleanup or status incomplete')
            results[profile] = dict(exitCode=result.returncode, observations=[l for l in lines if l.startswith('FTA_X87_')])
        if snapshot(fixtures) != before: raise ValueError('synthetic source changed')
        if snapshot(original) != protected_before: raise ValueError('protected synthetic executable changed')
    verify(root, rows)
    proof = dict(scope='synthetic-x87-flags-on-native-arm64', profiles=results,
        fullRuntimePrePostRehash=True, fixtureUnchanged=True, originalClientExecuted=False, androidExecuted=False)
    (work / 'evidence.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps(proof))


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--guest' and sys.argv[2] in PROFILES:
        sys.exit(guest(sys.argv[2]))
    main()
