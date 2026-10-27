#!/usr/bin/env python3
"""Disposable guest probe, owned by one Android PRoot tracer; no game files.

All output is bounded before reaching the native supervisor's separate log cap.
Every child has a deadline. The Android owner additionally bounds the whole tree.
"""
import os
from pathlib import Path
import selectors
import signal
import socket
import subprocess
import sys
import threading
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
DIAGNOSTICS = (
    (b"Couldn't detect CPU features", 'cpu-features-missing'),
    (b'Forced Assert', 'translator-assertion'),
    (b'is not owned by you', 'prefix-owner-mismatch'),
    (b'Cannot establish any listening sockets', 'display-listen-failed'),
    (b'SocketCreateListener() failed', 'display-listen-failed'),
    (b'Permission denied', 'permission-denied'),
    (b'File name too long', 'socket-path-too-long'),
    (b'Invalid argument', 'invalid-argument'),
)


def diagnose(data):
    for category in sorted({category for text, category in DIAGNOSTICS if text in data}):
        print('FTA_ANDROID_DIAGNOSTIC ' + category, flush=True)


class DisplayCapture:
    """Drain continuously with a fixed retained prefix; never block X on a full pipe."""
    def __init__(self, child):
        self.child = child
        self.data = bytearray()
        self.lock = threading.Lock()
        self.thread = threading.Thread(target=self.drain, daemon=True)
        self.thread.start()

    def drain(self):
        while True:
            data = self.child.stdout.read1(16384)
            if not data:
                return
            with self.lock:
                room = OUTPUT_LIMIT - len(self.data)
                self.data.extend(data[:room])

    def close(self):
        self.thread.join(1)
        with self.lock:
            diagnose(bytes(self.data))
        if not self.thread.is_alive():
            self.child.stdout.close()


def socket_check(path=Path('/tmp/.X11-unix/X8')):
    path.parent.mkdir(mode=0o700, exist_ok=True)
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.bind(str(path))
        path.unlink()
        status = 0
    except OSError as error:
        status = error.errno or 126
    print(f'FTA_ANDROID_SOCKET_STATUS {status}', flush=True)
    return status


def start_display(args=None, path=Path('/tmp/.X11-unix/X8'), seconds=10):
    if args is None:
        args = ['Xtigervnc', ':8', '-geometry', '640x480', '-depth', '24',
                '-rfbport', '-1', '-SecurityTypes', 'None', '-nolisten', 'tcp', '-ac']
    child = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, start_new_session=True)
    capture = DisplayCapture(child)
    try:
        deadline = time.monotonic() + seconds
        while True:
            status = child.poll()
            if status is not None:
                print(f'FTA_ANDROID_DISPLAY_EXIT {status}', flush=True)
                print('FTA_ANDROID_DISPLAY_EVENT exited', flush=True)
                record('display', status if status != 0 else 126)
                raise RuntimeError('display')
            if path.is_socket():
                print('FTA_ANDROID_DISPLAY_EVENT ready', flush=True)
                record('display', 0)
                return child, capture
            if time.monotonic() >= deadline:
                print('FTA_ANDROID_DISPLAY_EVENT timeout', flush=True)
                record('display', 124)
                raise RuntimeError('display')
            time.sleep(0.1)
    except BaseException:
        stop(child)
        capture.close()
        raise


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
        diagnose(retained)
        record(stage, status)
        if status:
            raise RuntimeError(stage)
    except (TimeoutError, subprocess.TimeoutExpired):
        diagnose(retained)
        record(stage, 124); raise
    finally:
        selector.close(); child.stdout.close(); stop(child)


def main():
    probes = Path(__file__).resolve().parent
    os.environ.update(PATH='/opt/wine/bin:/usr/bin:/bin', HOME='/session/home', LANG='C.UTF-8',
        WINEPREFIX='/prefix', WINEARCH='win64', DISPLAY=':8', WINEDEBUG='-all,err+all',
        WINEDLLOVERRIDES='winemenubuilder,mshtml,mscoree=', WINEESYNC='0', WINEFSYNC='0',
        FEX_DISABLETELEMETRY='1', FEX_SILENTLOG='0', TMPDIR='/tmp', XDG_CACHE_HOME='/session/cache')
    display = None; capture = None; passed = False
    try:
        if list(Path('/prefix').iterdir()):
            raise RuntimeError('prefix must be fresh')
        Path('/session/home').mkdir(exist_ok=True)
        with open('/sys/devices/system/cpu/online', 'rb') as source:
            topology = source.read(4097)
        if not 0 < len(topology) <= 4096:
            raise RuntimeError('cpu topology unavailable')
        record('cpu-topology', 0)
        status = socket_check()
        if status:
            print('FTA_ANDROID_DISPLAY_EVENT socket-check-failed', flush=True)
            record('display', status)
            raise RuntimeError('display socket')
        display, capture = start_display()
        run('prefix', ['wine', 'wineboot.exe', '-u'], 180)
        run('cpu-registry', ['wine', 'reg.exe', 'query', r'HKLM\Hardware\Description\System\CentralProcessor\0', '/reg:64'], 30)
        run('x86-entry', ['wine', str(probes / 'probe32.exe'), '--self-test'], 90)
        run('x64-entry', ['wine', str(probes / 'probe64.exe'), '--self-test'], 90)
        run('two-channels', ['wine', str(probes / 'probe32.exe'), '2'], 90)
        run('sixteen-channels', ['wine', str(probes / 'probe32.exe'), '16'], 90)
        run('owner-exit', ['wine', str(probes / 'probe64.exe'), '--observe-owner-exit'], 90)
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
            capture.close()
    if passed:
        print('FTA_ANDROID_SYNTHETIC_PASS', flush=True)
    return 0 if passed else 1


def ownership(normal):
    """Three traced processes, including two session leaders, with a 20s failsafe.

    Only the initial direct tracee can kill its own captured tracer parent, and
    only after Android's handle owner writes that exact parent to the control
    file. No PID from arguments, external files or an unowned process is used.
    """
    tracer = os.getppid()
    child = os.fork()
    initial = child > 0
    if child == 0:
        os.setsid()
        child = os.fork()
        if child == 0:
            os.setsid()
    signal.alarm(20)
    print(f'FTA_ANDROID_OWNED {os.getpid()}', flush=True)
    if normal:
        if child > 0:
            waited, status = os.waitpid(child, 0)
            return 0 if waited == child and os.waitstatus_to_exitcode(status) == 0 else 2
        return 0
    while True:
        if initial:
            try:
                with open('/session/ownership-control', 'rb') as control:
                    command = control.read(65)
            except FileNotFoundError:
                command = b''
            if command == f'kill {tracer}\n'.encode() and os.getppid() == tracer and tracer > 1:
                os.kill(tracer, signal.SIGKILL)
        time.sleep(0.02)


if __name__ == '__main__':
    if sys.argv[1:] == ['--socket-check']:
        sys.exit(socket_check())
    if sys.argv[1:] in (['--ownership-normal'], ['--ownership-hold']):
        sys.exit(ownership(sys.argv[1] == '--ownership-normal'))
    sys.exit(main())
