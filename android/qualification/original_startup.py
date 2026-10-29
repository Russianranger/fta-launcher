#!/usr/bin/env python3
"""Bounded original-client startup through its unmodified DLL-owned host launch."""
import os
import stat
import struct
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
# FEX-2510's NaN store path clobbers integer flags used by the original x87 loop.
# Scoped to the startup child: 64-bit x87 precision is a compatibility workaround,
# not a rendering-accuracy qualification. See docs/wow-startup-x87.md.
STARTUP_X87_ENV = dict(FEX_X87REDUCEDPRECISION='1', FEX_X87STRICTREDUCEDPRECISION='0')
EVENTS = ('client-started', 'client-create-error', 'client-early-exit', 'host-header-valid', 'host-owned',
          'host-channels', 'host-not-observed', 'startup-observed', 'client-forced-stop', 'client-stopped', 'host-stopped')


FAULT_BOUNDS = dict(pidCorrelated=1, traceLinesDropped=1000000, traceStateOverflow=1,
    accessViolationCount=1000000, exceptionCode=0xffffffff, exceptionFlags=0xffffffff,
    exceptionAddress=0x7fffffffffff, accessType=9, accessAddress=0x7fffffffffff, accessDetailsKnown=1,
    moduleId=7, moduleBase=0xffffffff, moduleSize=268435456, moduleRva=268435455,
    loadedClientModules=7, exitCode=0xffffffff, exitObserved=1, exitElapsedMs=145000, exceptionMatchesExit=1)
IMAGE_NAMES = {1: 'Wow.exe', 2: 'WarcraftXL.dll', 3: 'd3d9.dll', 4: 'ntdll.dll',
               5: 'kernelbase.dll', 6: 'kernel32.dll', 7: 'wined3d.dll'}


def image_sizes(source=Path('/immutable'), prefix=Path('/prefix')):
    """Read bounded PE headers only, from already verified source/runtime images."""
    sizes = {}
    for role, name in IMAGE_NAMES.items():
        path = source / name if role <= 3 else prefix / 'drive_c/windows/syswow64' / name
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK)
            with os.fdopen(fd, 'rb') as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or not 64 <= info.st_size <= 128 * 1024 * 1024: continue
                dos = stream.read(64)
                if dos[:2] != b'MZ': continue
                offset = struct.unpack_from('<I', dos, 60)[0]
                if not 64 <= offset <= min(info.st_size - 84, 1024 * 1024): continue
                stream.seek(offset); header = stream.read(84)
                if len(header) != 84 or header[:4] != b'PE\0\0' or struct.unpack_from('<H', header, 4)[0] != 0x14c: continue
                if struct.unpack_from('<H', header, 20)[0] < 60 or struct.unpack_from('<H', header, 24)[0] != 0x10b: continue
                size = struct.unpack_from('<I', header, 80)[0]
                if 4096 <= size <= FAULT_BOUNDS['moduleSize']: sizes[role] = size
        except OSError: pass
    return sizes


class FaultTrace:
    """Bounded Wine 10.13 trace correlation; last observed AV, not a crash dump.

    PIDs and paths exist only in this transient parser. Only fixed roles and
    numeric observations leave it. A handled exception may precede an exit;
    matching codes do not prove that the last observed exception caused it.
    """
    MAX_LINE = 4096
    MAX_PROCESSES = 32
    MAX_MODULES = 128

    def __init__(self, sizes):
        self.sizes = dict(sizes); self.partial = bytearray(); self.discarding = False
        self.processes = {}; self.pid = None; self.exit = None; self.events = []
        self.dropped = 0; self.overflow = False; self.pass_marker = False

    def feed(self, data):
        for part in data.splitlines(keepends=True):
            ended = part.endswith(b'\n')
            if not self.discarding:
                if len(self.partial) + len(part) > self.MAX_LINE:
                    self.partial.clear(); self.discarding = True; self.dropped = min(1000000, self.dropped + 1)
                else: self.partial.extend(part)
            if ended:
                if not self.discarding: self.line(bytes(self.partial).rstrip(b'\r\n'))
                self.partial.clear(); self.discarding = False

    @staticmethod
    def role(path):
        path = path.replace(b'\\\\', b'\\').lower()
        for role, name in IMAGE_NAMES.items():
            roots = (b'z:\\view\\', b'z:\\immutable\\') if role <= 3 else (b'c:\\windows\\syswow64\\', b'c:\\windows\\system32\\')
            if any(path == root + name.lower().encode() for root in roots): return role
        return 0

    def process(self, pid):
        if pid not in self.processes:
            if len(self.processes) >= self.MAX_PROCESSES:
                self.overflow = True; return None
            self.processes[pid] = dict(modules={}, loaded=0, count=0, av=None, pending=None)
        return self.processes[pid]

    def line(self, line):
        if line == b'FTA_ORIGINAL_STARTUP_PASS': self.pass_marker = True
        match = re.fullmatch(rb'FTA_CLIENT_EVENT ([a-z-]+) ([0-9]{1,10})', line)
        if match and match[1].decode() in EVENTS and int(match[2]) <= 0xffffffff:
            if len(self.events) < 64: self.events.append(line)
            else: self.overflow = True
            return
        match = re.fullmatch(rb'FTA_CLIENT_PROCESS ([0-9]{1,10})', line)
        if match and 0 < int(match[1]) <= 0xffffffff:
            if self.pid is not None: self.overflow = True
            else: self.pid = int(match[1])
            return
        match = re.fullmatch(rb'FTA_CLIENT_EXIT ([0-9]{1,10}) ([0-9]{1,10}) ([0-9]{1,6})', line)
        if match and self.pid == int(match[1]) and int(match[2]) <= 0xffffffff and int(match[3]) <= 145000:
            if self.exit is not None: self.overflow = True
            else: self.exit = (int(match[2]), int(match[3]))
            return
        match = re.fullmatch(rb'([0-9a-fA-F]{4,8}):([0-9a-fA-F]{4,8}):(trace|warn|err):(seh|loaddll):([A-Za-z0-9_]+) (.*)', line)
        if not match: return
        pid, tid = int(match[1], 16), int(match[2], 16)
        if self.pid is not None and pid != self.pid: return
        state = self.process(pid)
        if state is None: return
        channel, function, message = match[4], match[5], match[6]
        if channel == b'loaddll':
            loaded = re.fullmatch(rb'Loaded L?"([^"\r\n]{1,2048})" at (?:0x)?([0-9a-fA-F]{1,16}): (native|builtin)', message)
            if loaded:
                role = self.role(loaded[1]); address = int(loaded[2], 16)
                # A replacement (including an unknown image) invalidates an old range.
                state['modules'] = {a: v for a, v in state['modules'].items() if not a <= address < a + v[1]}
                if role <= 3 and role: state['loaded'] |= 1 << (role - 1)
                if role in self.sizes and address <= 0xffffffff and address + self.sizes[role] <= 0x100000000:
                    if len(state['modules']) >= self.MAX_MODULES and address not in state['modules']: self.overflow = True
                    else: state['modules'][address] = (role, self.sizes[role])
            unloaded = re.fullmatch(rb'Unloaded module L?"([^"\r\n]{1,2048})" : (native|builtin)', message)
            if unloaded:
                role = self.role(unloaded[1])
                state['modules'] = {a: v for a, v in state['modules'].items() if v[0] != role}
            return
        if function != b'dispatch_exception': return
        exception = re.fullmatch(rb'code=([0-9a-fA-F]{1,8}) \([^\r\n)]{1,80}\) flags=([0-9a-fA-F]{1,8}) addr=(?:0x)?([0-9a-fA-F]{1,16})', message)
        if exception:
            state['pending'] = None
            code, flags, address = (int(v, 16) for v in exception.groups())
            if code != 0xc0000005 or address > FAULT_BOUNDS['exceptionAddress']: return
            state['count'] = min(1000000, state['count'] + 1)
            modules = [(a, role, size) for a, (role, size) in state['modules'].items() if a <= address < a + size]
            base_address, role, size = modules[0] if len(modules) == 1 else (0, 0, 0)
            state['av'] = dict(exceptionCode=code, exceptionFlags=flags, exceptionAddress=address,
                moduleId=role, moduleBase=base_address, moduleSize=size, moduleRva=address-base_address if role else 0)
            state['pending'] = (tid, state['av'], {})
            return
        info = re.fullmatch(rb' info\[([01])\]=(?:0x)?([0-9a-fA-F]{1,16})', message)
        if info and state['pending'] is not None and state['pending'][0] == tid:
            _, av, details = state['pending']; details[int(info[1])] = int(info[2], 16)
            if details.get(0) in (0, 1, 8) and 0 <= details.get(1, -1) <= FAULT_BOUNDS['accessAddress']:
                av.update(accessType=details[0], accessAddress=details[1], accessDetailsKnown=1)

    def values(self):
        result = dict.fromkeys(FAULT_BOUNDS, 0); result['accessType'] = 9
        result.update(pidCorrelated=int(self.pid is not None), traceLinesDropped=self.dropped, traceStateOverflow=int(self.overflow))
        state = self.processes.get(self.pid)
        if state:
            result.update(accessViolationCount=state['count'], loadedClientModules=state['loaded'])
            if state['av']: result.update(state['av'])
        if self.exit: result.update(exitObserved=1, exitCode=self.exit[0], exitElapsedMs=self.exit[1])
        result['exceptionMatchesExit'] = int(bool(state and state['av'] and self.exit and self.exit[0] == state['av']['exceptionCode']))
        return result


class StartupCapture(base.OutputCapture):
    image_sizes = {}
    fixture_capture = False

    def __init__(self):
        super().__init__(); self.fault = FaultTrace(self.image_sizes)

    def append(self, data):
        super().append(data); self.fault.feed(data)
        marker = b'\nFTA_ORIGINAL_STARTUP_PASS\n'
        if self.fault.pass_marker and b'FTA_ORIGINAL_STARTUP_PASS' not in self.data.replace(b'\r', b'').splitlines():
            # Verbose traces must not hide a real terminal marker after the raw prefix cap.
            del self.data[base.OUTPUT_LIMIT - len(marker):]
            self.data.extend(marker)

    def report(self):
        super().report()
        for line in self.fault.events: print(line.decode(), flush=True)
        if self.fault.overflow: print('FTA_CLIENT_TRACE_OVERFLOW', flush=True)
        if self.fault.pid is not None:
            for key, value in self.fault.values().items(): print(f'FTA_CLIENT_FAULT {key} {value}', flush=True)
            if self.fixture_capture:
                # Synthetic CI failure fixture only; --original never saves raw Wine output.
                Path('/session/fixture-trace.log').write_bytes(self.data)


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


def main(fixture=False, prepare=False, fixture_crash=False):
    base.STAGES = PREFIX_STAGES if prepare else STAGES
    base.MARKERS = {'original-startup': b'FTA_ORIGINAL_STARTUP_PASS'}
    base.OutputCapture = StartupCapture
    StartupCapture.image_sizes = {} if prepare else image_sizes()
    StartupCapture.fixture_capture = fixture_crash
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
            mode = '--fixture-crash' if fixture_crash else '--fixture' if fixture else '--original'
            base.run('original-startup', ['wine', '/client/startup64.exe', mode], 145,
                env=dict(os.environ, **STARTUP_X87_ENV, WINEDEBUG='-all,err+all,trace+seh,trace+loaddll,+pid'))
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
    if sys.argv[1:] not in (['--original'], ['--fixture'], ['--fixture-crash'], ['--prepare']): sys.exit(2)
    sys.exit(main(fixture=sys.argv[1:] == ['--fixture'], prepare=sys.argv[1:] == ['--prepare'],
                  fixture_crash=sys.argv[1:] == ['--fixture-crash']))
