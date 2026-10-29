#!/usr/bin/env python3
"""Bounded original-client startup through its unmodified DLL-owned host launch."""
import os
import stat
import threading
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
PREFIX_STAGES = ('cpu-topology', 'display', 'prefix', 'wine-stop')
READY = b'FTA_PREFIX_READY_V1\n'
EVENTS = ('client-started', 'client-create-error', 'client-early-exit', 'host-header-valid', 'host-owned',
          'host-channels', 'host-not-observed', 'startup-observed', 'client-forced-stop', 'client-stopped', 'host-stopped')


class StartupCapture(base.OutputCapture):
    def report(self):
        super().report()
        for line in self.data.replace(b'\r', b'').splitlines():
            match = re.fullmatch(rb'FTA_CLIENT_EVENT ([a-z-]+) ([0-9]{1,10})', line)
            if match and match[1].decode() in EVENTS and int(match[2]) <= 0xffffffff:
                print(line.decode(), flush=True)


class PrefixCache:
    """Flush/advice only our disposable prefix, without following DOS links.

    The worker never reads payloads or changes contents. Advising clean file
    ranges is optional; accepted calls do not establish actual cache eviction.
    Directory-relative NOFOLLOW opens keep every operation in the owned tree.
    """
    MAX_ENTRIES = 10000
    MAX_FILE = 128 * 1024 * 1024

    def __init__(self, root=Path('/prefix')):
        self.root = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
        self.cancel = threading.Event()
        self.lock = threading.Lock()
        self.seen = {}
        self.synced = self.accepted = self.errors = self.bytes = 0
        self.thread = None

    def sweep(self):
        count = 0
        def visit(directory, depth):
            nonlocal count
            if depth > 16: raise ValueError('prefix depth bound')
            with os.scandir(directory) as entries:
                for entry in entries:
                    if self.cancel.is_set(): return
                    count += 1
                    if count > self.MAX_ENTRIES: raise ValueError('prefix entry bound')
                    if entry.is_symlink(): continue
                    fd = None
                    try:
                        fd = os.open(entry.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=directory)
                        info = os.fstat(fd)
                        if stat.S_ISDIR(info.st_mode): visit(fd, depth + 1)
                        elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and 0 < info.st_size <= self.MAX_FILE:
                            identity = (info.st_dev, info.st_ino)
                            stamp = (info.st_size, info.st_mtime_ns, info.st_ctime_ns)
                            if self.seen.get(identity) == stamp: continue
                            os.fdatasync(fd)
                            with self.lock: self.synced += 1
                            os.posix_fadvise(fd, 0, info.st_size, os.POSIX_FADV_DONTNEED)
                            with self.lock:
                                self.accepted += 1; self.bytes += info.st_size
                            self.seen[identity] = stamp
                    except OSError:
                        with self.lock: self.errors += 1
                    finally:
                        if fd is not None: os.close(fd)
        visit(self.root, 0)
        # Bound retained metadata even if Wine replaces files repeatedly.
        if len(self.seen) > self.MAX_ENTRIES: self.seen.clear()

    def start(self):
        def work():
            while not self.cancel.is_set():
                try: self.sweep()
                except Exception:
                    with self.lock: self.errors += 1
                self.cancel.wait(0.5)
        self.thread = threading.Thread(target=work, daemon=True)
        self.thread.start()

    def close(self):
        self.cancel.set()
        if self.thread is not None:
            self.thread.join(2)
            if self.thread.is_alive(): raise RuntimeError('prefix cache worker still active')
        os.close(self.root)

    def report(self):
        with self.lock: values = (self.synced, self.accepted, self.errors, self.bytes)
        print('FTA_PREFIX_CACHE ' + ' '.join(map(str, values)), flush=True)


def prepared(root=Path('/prefix'), session=Path('/session')):
    marker = session / 'prefix-ready'
    if not stat.S_ISREG(marker.lstat().st_mode) or marker.lstat().st_size != len(READY) or marker.read_bytes() != READY:
        raise RuntimeError('prefix receipt unavailable')
    for name in ('system.reg', 'user.reg', 'userdef.reg'):
        info = (root / name).lstat()
        if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= 64 * 1024 * 1024:
            raise RuntimeError('prefix registry incomplete')
    for name in ('system32', 'syswow64'):
        path = root / 'drive_c/windows' / name
        if not stat.S_ISDIR(path.lstat().st_mode) or not any(path.iterdir()):
            raise RuntimeError('prefix binaries incomplete')


def main(fixture=False, prepare=False):
    base.STAGES = PREFIX_STAGES if prepare else STAGES
    base.MARKERS = {'original-startup': b'FTA_ORIGINAL_STARTUP_PASS'}
    base.OutputCapture = StartupCapture
    os.environ.update(PATH='/opt/wine/bin:/usr/bin:/bin', HOME='/session/home', LANG='C.UTF-8',
        WINEPREFIX='/prefix', WINEARCH='win64', DISPLAY=':8', WINEDEBUG='-all,err+all',
        WINEDLLOVERRIDES='winemenubuilder,mshtml,mscoree=;d3d9=n,b', WINEESYNC='0', WINEFSYNC='0',
        FEX_DISABLETELEMETRY='1', FEX_SILENTLOG='0', TMPDIR='/tmp', XDG_CACHE_HOME='/session/cache')
    display = None; capture = None; cache = None; passed = False; stop_ok = False
    try:
        if prepare:
            if list(Path('/prefix').iterdir()): raise RuntimeError('prefix must be fresh')
            Path('/session/home').mkdir()
        else: prepared()
        with open('/sys/devices/system/cpu/online', 'rb') as source: topology = source.read(4097)
        if not 0 < len(topology) <= 4096: raise RuntimeError('cpu topology unavailable')
        base.record('cpu-topology', 0)
        if base.socket_check(): raise RuntimeError('display socket')
        display, capture = base.start_display(['Xtigervnc', ':8', '-geometry', '1280x720', '-depth', '24',
            '-rfbport', '-1', '-SecurityTypes', 'None', '-nolisten', 'tcp', '-ac'])
        if prepare:
            cache = PrefixCache(); cache.start()
            def progress(elapsed):
                base.prefix_progress(elapsed); cache.report()
            base.run('prefix', ['wine', 'wineboot.exe', '-i'], base.PREFIX_SECONDS,
                env=dict(os.environ, WINEDEBUG='-all,err+all,trace+wineboot'), progress=progress)
        else:
            base.record('prefix', 0)
            base.run('original-startup', ['wine', '/client/startup64.exe', '--fixture' if fixture else '--original'], 145)
        passed = True
    except Exception:
        print('FTA_CLIENT_PREFIX_FAILED' if prepare else 'FTA_CLIENT_STARTUP_FAILED', flush=True)
    finally:
        try:
            subprocess.run(['wineserver', '-k'], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=10, check=True)
            base.run('wine-stop', ['wineserver', '-w'], 30); stop_ok = True
        except Exception:
            print('FTA_CLIENT_STOP_FAILED', flush=True)
        if cache is not None:
            try: cache.close()
            except Exception: stop_ok = False
            cache.report()
        if display is not None: base.stop(display); capture.close()
    if passed and stop_ok:
        if prepare:
            with open('/session/prefix-ready', 'xb') as marker:
                marker.write(READY); marker.flush(); os.fsync(marker.fileno())
            prepared()
        print('FTA_CLIENT_PREFIX_PASS' if prepare else 'FTA_CLIENT_STARTUP_PASS', flush=True)
    return 0 if passed and stop_ok else 1


if __name__ == '__main__':
    if sys.argv[1:] not in (['--original'], ['--fixture'], ['--prepare']): sys.exit(2)
    sys.exit(main(fixture=sys.argv[1:] == ['--fixture'], prepare=sys.argv[1:] == ['--prepare']))
