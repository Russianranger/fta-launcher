#!/usr/bin/env python3
"""Exercise fatal-signal preservation on the host; optionally cross-build ARM64 witness."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile

from build_native import check_jobs
from stage_android_provider import digest, inspect_elf

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'native/world-fault/world_fault.c'
READY = b'FTA_FAULT_READY_V1\n'
FAULT = rb'FTA_FAULT_V1 signal=(11|7) code=(-?[0-9]+) pc=([0-9]+):([a-f0-9]{16}) lr=([0-9]+):([a-f0-9]{16})\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--module', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build-log', type=Path, required=True)
    args = parser.parse_args()
    if bool(args.ndk) != bool(args.module):
        raise ValueError('NDK and output module must be supplied together')
    check_jobs(1)
    with tempfile.TemporaryDirectory(prefix='fta-world-fault-') as temporary, args.build_log.open('w') as log:
        root = Path(temporary); module = root / 'libfta_world_fault.so'; driver = root / 'driver'
        def build(command):
            subprocess.run(command, check=True, stdout=log, stderr=log)
        flags = ['-std=c11', '-O2', '-fPIC', '-shared', '-Wall', '-Wextra', '-Werror', '-Wl,-z,defs']
        build(['gcc'] + flags + [str(SOURCE), '-ldl', '-o', str(module)])
        build(['gcc', '-O0', '-fPIE', '-pie', '-Wall', '-Wextra', '-Werror',
               str(ROOT / 'tests/fixtures/world_fault_host.c'), '-o', str(driver)])
        env = dict(os.environ, LD_PRELOAD=str(module))
        def run(mode):
            return subprocess.run([str(driver), str(mode)], env=env, capture_output=True, timeout=5)
        good = run(0)
        if good.returncode != 0 or good.stdout or good.stderr != READY:
            raise ValueError('Witness changed normal execution')
        offsets = []
        for _ in range(5):
            bad = run(signal.SIGSEGV)
            match = re.fullmatch(READY + FAULT, bad.stderr)
            if bad.returncode != -signal.SIGSEGV or bad.stdout or not match or match[3] != b'1':
                raise ValueError('Witness failed to retain the original fatal signal and main-image offset')
            offsets.append(int(match[4], 16))
        if len(set(offsets)) != 1:
            raise ValueError('Witness leaked ASLR addresses rather than stable offsets')
        symbols = subprocess.check_output(['nm', '-S', '--defined-only', str(driver)], text=True)
        address, size = re.search(r'^([a-f0-9]+) ([a-f0-9]+) T fault_here$', symbols, re.M).groups()
        if not int(address, 16) <= offsets[0] < int(address, 16) + int(size, 16):
            raise ValueError('Captured PC does not identify the actual fault fixture')
        bus = run(signal.SIGBUS)
        if bus.returncode != -signal.SIGBUS or not re.fullmatch(READY + FAULT, bus.stderr):
            raise ValueError('SIGBUS not preserved')
        for mode in (signal.SIGTERM, signal.SIGABRT):
            result = run(mode)
            if result.returncode != -mode or result.stderr != READY:
                raise ValueError('An unrelated signal was intercepted')
        proof = dict(status='host_fault_witness_passed', sourceSha256=digest(SOURCE),
            normalExitPreserved=True, sigsegvPreserved=True, sigbusPreserved=True, sigtermUntouched=True,
            sigabrtUntouched=True, repeatedFaultOffsetsStable=True, faultOffsetMatchesSymbol=True,
            rawAddressesExported=False, memoryContentsExported=False, nativeWorldRecompiled=False,
            compilerJobs=1, androidExecutionQualified=False)
        if args.ndk:
            props = (args.ndk / 'source.properties').read_text()
            if 'Pkg.Revision = 28.2.13676358' not in props:
                raise ValueError('Pinned NDK revision differs')
            compiler = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang'
            if args.module.exists():
                raise ValueError('Do not overwrite a measured module')
            build([str(compiler)] + flags + [str(SOURCE), '-ldl', '-Wl,-z,max-page-size=16384',
                                          '-Wl,-soname,libfta_world_fault.so', '-o', str(args.module)])
            elf = inspect_elf(args.module, reject_search_paths=True)
            if set(elf['needed']) != {'libc.so', 'libdl.so'} or elf['minimumLoadAlignment'] < 16384:
                raise ValueError('Fault module dependency or alignment differs')
            proof.update(moduleBytes=args.module.stat().st_size, moduleSha256=digest(args.module), elf=elf,
                         ndkVersion='28.2.13676358', androidApi=33,
                         compilerSha256=digest(compiler.resolve().parent / 'clang-19'))
        args.output.write_text(json.dumps(proof, indent=2) + '\n')
        print(json.dumps(proof))


if __name__ == '__main__':
    main()
