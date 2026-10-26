#!/usr/bin/env python3
"""Disposable guest probe, owned by one Android PRoot tracer; no game files.

All output is bounded before reaching the native supervisor's separate log cap.
Every child has a deadline. The Android owner additionally bounds the whole tree.
"""
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import time

STAGES = ('cpu-topology', 'display', 'prefix', 'cpu-registry', 'x86-entry', 'x64-entry',
          'two-channels', 'sixteen-channels', 'owner-exit', 'wine-stop')
MARKERS = {
    'x86-entry': b'FTA_SYNTHETIC_X86_ENTRY_OK',
    'x64-entry': b'FTA_SYNTHETIC_X64_ENTRY_OK',
    'two-channels': b'FTA_SYNTHETIC_PARENT_OK channels=2 roundTrips=64 ownerExit=0',
    'sixteen-channels': b'FTA_SYNTHETIC_PARENT_OK channels=16 roundTrips=512 ownerExit=0',
    'owner-exit': b'FTA_SYNTHETIC_OWNER_EXIT_OK parentExit=0 childExit=38',
}
OUTPUT_LIMIT = 256 * 1024


def record(stage, status):
    if stage not in STAGES or not isinstance(status, int):
        raise ValueError('unknown probe stage')
    print(f'FTA_ANDROID_STAGE {stage} {status}', flush=True)


def stop(child):
    # Only an unreaped direct child/session created by this process is signalled.
    if child.poll() is None:
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            child.wait(2)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait(5)


def run(stage, args, seconds, env=None):
    child = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             stdin=subprocess.DEVNULL, start_new_session=True, env=env)
    retained = bytearray(); deadline = time.monotonic() + seconds
    selector = selectors.DefaultSelector(); selector.register(child.stdout, selectors.EVENT_READ)
    try:
        eof = False
        while not eof:
            if time.monotonic() >= deadline:
                raise TimeoutError(stage)
            for key, mask in selector.select(min(0.1, max(0, deadline - time.monotonic()))):
                data = os.read(key.fd, 16384)
                if not data:
                    eof = True; break
                room = OUTPUT_LIMIT - len(retained)
                if room:
                    retained.extend(data[:room])
            # Inherited output pipes cannot keep a completed stage alive indefinitely.
            if child.poll() is not None and not selector.select(0):
                break
        status = child.wait(max(0.01, deadline - time.monotonic()))
        expected = MARKERS.get(stage)
        if status == 0 and expected is not None and expected not in retained.replace(b'\r', b'').splitlines():
            status = 125
        # Known diagnostic categories only; raw runtime text remains private.
        for text, category in ((b"Couldn't detect CPU features", 'cpu-features-missing'),
                               (b'Forced Assert', 'translator-assertion')):
            if text in retained:
                print('FTA_ANDROID_DIAGNOSTIC ' + category, flush=True)
        record(stage, status)
        if status:
            raise RuntimeError(stage)
    except (TimeoutError, subprocess.TimeoutExpired):
        record(stage, 124); raise
    finally:
        selector.close(); child.stdout.close(); stop(child)


def main():
    os.environ.update(PATH='/opt/wine/bin:/usr/bin:/bin', HOME='/session/home', LANG='C.UTF-8',
        WINEPREFIX='/prefix', WINEARCH='win64', DISPLAY=':8', WINEDEBUG='-all,err+all',
        WINEDLLOVERRIDES='winemenubuilder,mshtml,mscoree=', WINEESYNC='0', WINEFSYNC='0',
        FEX_DISABLETELEMETRY='1', FEX_SILENTLOG='0', TMPDIR='/tmp')
    display = None; passed = False
    try:
        if list(Path('/prefix').iterdir()):
            raise RuntimeError('prefix must be fresh')
        Path('/session/home').mkdir(exist_ok=True)
        with open('/sys/devices/system/cpu/online', 'rb') as source:
            topology = source.read(4097)
        if not 0 < len(topology) <= 4096:
            raise RuntimeError('cpu topology unavailable')
        record('cpu-topology', 0)
        display = subprocess.Popen(['Xtigervnc', ':8', '-geometry', '640x480', '-depth', '24',
            '-rfbport', '-1', '-SecurityTypes', 'None', '-nolisten', 'tcp', '-ac'],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        deadline = time.monotonic() + 10
        while not Path('/tmp/.X11-unix/X8').is_socket():
            if display.poll() is not None or time.monotonic() >= deadline:
                record('display', 124); raise RuntimeError('display')
            time.sleep(0.1)
        record('display', 0)
        run('prefix', ['wine', 'wineboot.exe', '-u'], 180)
        run('cpu-registry', ['wine', 'reg.exe', 'query', r'HKLM\Hardware\Description\System\CentralProcessor\0', '/reg:64'], 30)
        run('x86-entry', ['wine', '/probe/probe32.exe', '--self-test'], 90)
        run('x64-entry', ['wine', '/probe/probe64.exe', '--self-test'], 90)
        run('two-channels', ['wine', '/probe/probe32.exe', '2'], 90)
        run('sixteen-channels', ['wine', '/probe/probe32.exe', '16'], 90)
        run('owner-exit', ['wine', '/probe/probe64.exe', '--observe-owner-exit'], 90)
        # Synthetic exit/IPC qualification does not claim graceful gameplay shutdown.
        subprocess.run(['wineserver', '-k'], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=10, check=True)
        run('wine-stop', ['wineserver', '-w'], 30)
        passed = True
    except Exception:
        print('FTA_ANDROID_PROBE_FAILED', flush=True)
    finally:
        if display is not None:
            stop(display)
    if passed:
        print('FTA_ANDROID_SYNTHETIC_PASS', flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
