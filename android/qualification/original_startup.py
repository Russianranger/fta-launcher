#!/usr/bin/env python3
"""Bounded original-client startup through its unmodified DLL-owned host launch."""
import os
from pathlib import Path
import re
import subprocess
import sys

try:
    import android_probe as base
except ModuleNotFoundError as error:
    if error.name != 'android_probe': raise
    import android_runtime_probe as base

STAGES = ('cpu-topology', 'display', 'prefix', 'original-startup', 'wine-stop')
EVENTS = ('client-started', 'client-create-error', 'client-early-exit', 'host-header-valid', 'host-owned',
          'host-channels', 'host-not-observed', 'startup-observed', 'client-forced-stop', 'client-stopped', 'host-stopped')


class StartupCapture(base.OutputCapture):
    def report(self):
        super().report()
        for line in self.data.replace(b'\r', b'').splitlines():
            match = re.fullmatch(rb'FTA_CLIENT_EVENT ([a-z-]+) ([0-9]{1,10})', line)
            if match and match[1].decode() in EVENTS and int(match[2]) <= 0xffffffff:
                print(line.decode(), flush=True)


def main(fixture=False):
    base.STAGES = STAGES
    base.MARKERS = {'original-startup': b'FTA_ORIGINAL_STARTUP_PASS'}
    base.OutputCapture = StartupCapture
    os.environ.update(PATH='/opt/wine/bin:/usr/bin:/bin', HOME='/session/home', LANG='C.UTF-8',
        WINEPREFIX='/prefix', WINEARCH='win64', DISPLAY=':8', WINEDEBUG='-all,err+all',
        WINEDLLOVERRIDES='winemenubuilder,mshtml,mscoree=;d3d9=n,b', WINEESYNC='0', WINEFSYNC='0',
        FEX_DISABLETELEMETRY='1', FEX_SILENTLOG='0', TMPDIR='/tmp', XDG_CACHE_HOME='/session/cache')
    display = None; capture = None; passed = False; stop_ok = False
    try:
        if list(Path('/prefix').iterdir()): raise RuntimeError('prefix must be fresh')
        Path('/session/home').mkdir()
        with open('/sys/devices/system/cpu/online', 'rb') as source: topology = source.read(4097)
        if not 0 < len(topology) <= 4096: raise RuntimeError('cpu topology unavailable')
        base.record('cpu-topology', 0)
        if base.socket_check(): raise RuntimeError('display socket')
        display, capture = base.start_display(['Xtigervnc', ':8', '-geometry', '1280x720', '-depth', '24',
            '-rfbport', '-1', '-SecurityTypes', 'None', '-nolisten', 'tcp', '-ac'])
        base.run('prefix', ['wine', 'wineboot.exe', '-i'], base.PREFIX_SECONDS,
            env=dict(os.environ, WINEDEBUG='-all,err+all,trace+wineboot'), progress=base.prefix_progress)
        base.run('original-startup', ['wine', '/client/startup64.exe', '--fixture' if fixture else '--original'], 145)
        passed = True
    except Exception:
        print('FTA_CLIENT_STARTUP_FAILED', flush=True)
    finally:
        try:
            subprocess.run(['wineserver', '-k'], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=10, check=True)
            base.run('wine-stop', ['wineserver', '-w'], 30); stop_ok = True
        except Exception:
            print('FTA_CLIENT_STOP_FAILED', flush=True)
        if display is not None: base.stop(display); capture.close()
    if passed and stop_ok: print('FTA_CLIENT_STARTUP_PASS', flush=True)
    return 0 if passed and stop_ok else 1


if __name__ == '__main__':
    if sys.argv[1:] not in (['--original'], ['--fixture']): sys.exit(2)
    sys.exit(main(sys.argv[1:] == ['--fixture']))
