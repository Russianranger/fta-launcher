#!/usr/bin/env python3
"""Compare isolated original x64 host headers with original x86/Kotlin readers.

Only pinned channel-selection/header-initialization fragments run in an emulator.
Hardware concurrency and GetCurrentProcessId are synthetic. No process, OS API,
mapping, event, asset request, executable entry point or live transport runs.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import struct
import subprocess

from pe_linkage import PE, require
from warcraftxl_contract import (assess, load, HEADER_RVA, HEADER_BYTES,
    HOST_CHANNEL_RVA, HOST_CHANNEL_BYTES, HOST_INIT_RVA, HOST_INIT_BYTES)
from check_warcraftxl_header import original_statuses

HARDWARE_COUNTS = tuple(range(21)) + (32, 64, 0x7fffffff, 0x80000000, 0xffffffff)
SYNTHETIC_PIDS = (0, 1, 0x80000000, 0xffffffff)
STRIDE = 0xc0040
PAGE = 4096
HOST_BASE = 0x140000000


def expected_channels(hardware):
    require(type(hardware) is int and 0 <= hardware <= 0xffffffff, 'invalid-synthetic-hardware-count')
    return min(16, max(2, hardware or 4))


def expected_header(channels, pid):
    require(type(channels) is int and 2 <= channels <= 16 and
            type(pid) is int and 0 <= pid <= 0xffffffff, 'invalid-synthetic-header-input')
    data = bytearray(64)
    for offset, word in ((0, 0x4c585857), (4, 4), (24, channels), (28, pid)):
        struct.pack_into('<I', data, offset, word)
    return bytes(data)


def original_headers(host_data):
    import unicorn
    from unicorn import Uc, UC_ARCH_X86, UC_MODE_64, UC_PROT_READ as R, UC_PROT_WRITE as W, UC_PROT_EXEC as X
    from unicorn.x86_const import UC_X86_REG_RSP, UC_X86_REG_RAX, UC_X86_REG_RIP, UC_X86_REG_R8, UC_X86_REG_EFLAGS
    require(unicorn.__version__ == '2.1.4', 'unicorn-version-mismatch')
    host = PE(host_data)
    select = host.rva(HOST_CHANNEL_RVA, HOST_CHANNEL_BYTES)
    initialize = host.rva(HOST_INIT_RVA, HOST_INIT_BYTES)
    constants = host.rva(0xccf68, 8)
    headers = []; counts = []; api_calls = 0
    stack, window, stub = 0x10000000, 0x20000000, 0x30000000
    globals_page = HOST_BASE + 0xfc000
    for hardware in HARDWARE_COUNTS:
        for pid in SYNTHETIC_PIDS:
            cpu = Uc(UC_ARCH_X86, UC_MODE_64)
            cpu.mem_map(HOST_BASE + 0xc000, PAGE, R | X)
            cpu.mem_write(HOST_BASE + HOST_CHANNEL_RVA, select)
            cpu.mem_write(HOST_BASE + HOST_INIT_RVA, initialize)
            cpu.mem_map(HOST_BASE + 0xcc000, PAGE, R)
            cpu.mem_write(HOST_BASE + 0xccf68, constants)
            # Import slot points only at our one-instruction synthetic return stub.
            cpu.mem_write(HOST_BASE + 0xcc080, struct.pack('<Q', stub))
            cpu.mem_map(globals_page, PAGE, R | W)
            cpu.mem_map(stack, PAGE, R | W)
            cpu.mem_map(stub, PAGE, R | X); cpu.mem_write(stub, b'\xc3')
            cpu.reg_write(UC_X86_REG_RSP, stack + PAGE // 2)
            cpu.reg_write(UC_X86_REG_RAX, hardware)
            cpu.reg_write(UC_X86_REG_EFLAGS, 0x202)
            stop = HOST_BASE + HOST_CHANNEL_RVA + HOST_CHANNEL_BYTES
            cpu.emu_start(HOST_BASE + HOST_CHANNEL_RVA, stop, timeout=10000, count=64)
            require(cpu.reg_read(UC_X86_REG_RIP) == stop, 'channel-fragment-did-not-stop')
            channels = struct.unpack('<I', cpu.mem_read(HOST_BASE + 0xfc8c0, 4))[0]
            require(channels == expected_channels(hardware), 'host-channel-comparison-mismatch')
            counts.append(channels)
            cpu.mem_write(HOST_BASE + 0xfc8b8, struct.pack('<Q', window))
            cpu.mem_protect(globals_page, PAGE, R)
            pages = set()
            for channel in range(channels):
                address = window + channel * STRIDE
                page = address & -PAGE
                require(page not in pages and address + 64 <= page + PAGE, 'invalid-synthetic-mapping')
                pages.add(page); cpu.mem_map(page, PAGE, R | W)
                cpu.mem_write(page, b'\xa5' * PAGE)
            calls = []
            def return_pid(current, address, size, unused):
                require(address == stub and size == 1, 'unexpected-emulated-api')
                calls.append(1); require(len(calls) <= channels, 'synthetic-api-call-limit')
                current.reg_write(UC_X86_REG_RAX, pid)
            cpu.hook_add(unicorn.UC_HOOK_CODE, return_pid, begin=stub, end=stub)
            cpu.reg_write(UC_X86_REG_R8, window)
            stop = HOST_BASE + HOST_INIT_RVA + HOST_INIT_BYTES
            cpu.emu_start(HOST_BASE + HOST_INIT_RVA, stop, timeout=10000, count=1024)
            require(cpu.reg_read(UC_X86_REG_RIP) == stop and len(calls) == channels,
                    'host-initializer-did-not-stop')
            api_calls += len(calls)
            for channel in range(channels):
                address = window + channel * STRIDE; page = address & -PAGE
                snapshot = bytes(cpu.mem_read(address, 64))
                require(snapshot == expected_header(channels, pid), 'host-header-comparison-mismatch')
                expected = bytearray(b'\xa5' * PAGE)
                expected[address - page:address - page + 64] = snapshot
                require(bytes(cpu.mem_read(page, PAGE)) == expected, 'host-header-overwrite')
                headers.append(snapshot)
            # Release each emulator before constructing the next bounded instance.
            del cpu
    return headers, dict(syntheticHostInitializations=len(counts),
        syntheticHardwareCounts=len(HARDWARE_COUNTS), syntheticProcessWords=len(SYNTHETIC_PIDS),
        initializedHeaderCount=len(headers), syntheticGetCurrentProcessIdCalls=api_calls,
        selectedChannelCounts=sorted(set(counts)), maximumEmulatedMemoryBytes=(5 + 16) * PAGE,
        channelInstructionLimit=64, initializationInstructionLimit=1024,
        timeoutMicrosecondsPerFragment=10000, headerGuardBytesUnchanged=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('dll', 'proxy', 'host', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--kotlin-classpath', required=True)
    args = parser.parse_args()
    dll_data, proxy_data, host_data = load(args.dll), load(args.proxy), load(args.host)
    evidence = assess(dll_data, proxy_data, host_data)
    cases, details = original_headers(host_data)
    statuses = original_statuses(PE(dll_data).rva(HEADER_RVA, HEADER_BYTES), cases)
    expected = [7 if struct.unpack_from('<I', case, 28)[0] == 0 else 1 for case in cases]
    require(statuses == expected, 'original-host-client-header-mismatch')
    payload = ('\n'.join(case.hex() for case in cases) + '\n').encode()
    result = subprocess.run(['java', '-Xmx64m', '-cp', args.kotlin_classpath,
                            'com.russianranger.fta.client.WarcraftXlHeaderHost'],
                            input=payload, capture_output=True, check=True, timeout=60)
    require([int(line) for line in result.stdout.splitlines()] == statuses, 'host-kotlin-header-mismatch')
    evidence.update(details)
    evidence.update(scope='original-x64-host-x86-client-header-comparison',
        emulator='Unicorn 2.1.4 x86-64 and x86-32',
        originalHostFragmentsEmulated=True, originalMachineCodeModified=False,
        hardwareConcurrencySynthetic=True, getCurrentProcessIdSynthetic=True,
        windowsApiExecuted=False, liveMappingOrEventCreated=False,
        originalHostClientHeaderComparisonPassed=True, exactKotlinComparisonPassed=True,
        originalStatusCounts=dict(sorted(collections.Counter(map(str, statuses)).items())),
        headerCorpusSha256=hashlib.sha256(payload).hexdigest(),
        outputSha256=hashlib.sha256(result.stdout).hexdigest())
    with args.output.open('x') as output:
        json.dump(evidence, output, indent=2); output.write('\n')
    print(json.dumps({key: evidence[key] for key in
        ('syntheticHostInitializations', 'initializedHeaderCount', 'originalHostClientHeaderComparisonPassed')}))


if __name__ == '__main__':
    main()
