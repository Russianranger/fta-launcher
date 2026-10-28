#!/usr/bin/env python3
"""Synthetic Windows access through a protected shared tree and independent writable view."""
import os
from pathlib import Path
import re
import subprocess
import sys

try:
    import android_probe as base  # Packaged guest name.
except ModuleNotFoundError as error:
    if error.name != 'android_probe': raise
    import android_runtime_probe as base  # Repository qualification entry.

STAGES = ('cpu-topology', 'display', 'prefix', 'x86-storage', 'x64-storage', 'wine-stop')
CHECKS = ('shared-read-open', 'read-content', 'seek', 'read-mapping', 'mapped-content', 'private-mapping',
          'private-view', 'direct-read', 'private-write-isolated', 'directory-list', 'shared-write-denied',
          'direct-write-denied', 'truncate-denied', 'overwrite-denied', 'protected-create-denied', 'delete-denied',
          'rename-denied', 'attributes-denied', 'hardlink-denied', 'mkdir-denied', 'config-open', 'config-write',
          'log-create', 'log-write', 'log-rename', 'log-delete', 'mixed-child-start', 'mixed-child-read-protection')


class StorageCapture(base.OutputCapture):
    def report(self):
        super().report()
        # Fixed protocol only; runtime logs and arbitrary Windows text stay private.
        for line in self.data.replace(b'\r', b'').splitlines():
            match = re.fullmatch(rb'FTA_GUARDED_WIN (x86|x64) ([a-z-]+) ([01])', line)
            if match and match[2].decode() in CHECKS:
                print(line.decode(), flush=True)
            error = re.fullmatch(rb'FTA_GUARDED_ERROR (x86|x64) ([a-z-]+) ([0-9]{1,5})', line)
            if error and error[2].decode() in CHECKS:
                print(line.decode(), flush=True)


def main():
    base.STAGES = STAGES
    base.MARKERS = {arch + '-storage': f'FTA_GUARDED_WIN_PASS {arch} checks={len(CHECKS)}'.encode() for arch in ('x86', 'x64')}
    base.OutputCapture = StorageCapture
    os.environ.update(PATH='/opt/wine/bin:/usr/bin:/bin', HOME='/session/home', LANG='C.UTF-8',
        WINEPREFIX='/prefix', WINEARCH='win64', DISPLAY=':8', WINEDEBUG='-all,err+all',
        WINEDLLOVERRIDES='winemenubuilder,mshtml,mscoree=', WINEESYNC='0', WINEFSYNC='0',
        FEX_DISABLETELEMETRY='1', FEX_SILENTLOG='0', TMPDIR='/tmp', XDG_CACHE_HOME='/session/cache')
    display = None; capture = None; passed = False
    try:
        if list(Path('/prefix').iterdir()): raise RuntimeError('prefix must be fresh')
        Path('/session/home').mkdir()
        with open('/sys/devices/system/cpu/online', 'rb') as source: topology = source.read(4097)
        if not 0 < len(topology) <= 4096: raise RuntimeError('cpu topology unavailable')
        base.record('cpu-topology', 0)
        if base.socket_check(): raise RuntimeError('display socket')
        display, capture = base.start_display()
        base.run('prefix', ['wine', 'wineboot.exe', '-i'], base.PREFIX_SECONDS,
                 env=dict(os.environ, WINEDEBUG='-all,err+all,trace+wineboot'), progress=base.prefix_progress)
        base.run('x86-storage', ['wine', '/view/probe32.exe'], 90)
        base.run('x64-storage', ['wine', '/view/probe64.exe'], 90)
        subprocess.run(['wineserver', '-k'], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=10, check=True)
        base.run('wine-stop', ['wineserver', '-w'], 30)
        passed = True
    except Exception:
        print('FTA_GUARDED_WINE_FAILED', flush=True)
    finally:
        if display is not None: base.stop(display); capture.close()
    if passed: print('FTA_GUARDED_WINE_PASS', flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    if sys.argv[1:] in (['--ownership-normal'], ['--ownership-hold']):
        sys.exit(base.ownership(sys.argv[1] == '--ownership-normal'))
    sys.exit(main())
