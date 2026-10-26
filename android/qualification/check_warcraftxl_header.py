#!/usr/bin/env python3
"""Compare isolated original x86 header-check instructions with Kotlin snapshots.

No DLL entry point, Wine, OS API, game or host transport runs. Only the pinned
71-byte leaf validator is emulated, with synthetic data and bounded instructions.
"""
import argparse
import collections
import hashlib
import itertools
import json
from pathlib import Path
import random
import struct
import subprocess

from pe_linkage import PE
from warcraftxl_contract import assess, load, HEADER_RVA, HEADER_BYTES, HEADER_SHA256


def corpus():
    rng = random.Random(12340)
    cases = []
    values = itertools.product((0, 0x4c585857, 0x5758584c), (0, 1, 3, 4, 5, 0xffffffff),
                               (0, 1, 2, 8, 16, 17, 0x80000002, 0xffffffff), (0, 1, 0xffffffff))
    for magic, protocol, channels, pid in values:
        data = bytearray(rng.randbytes(64))
        for offset, word in ((0, magic), (4, protocol), (24, channels), (28, pid)):
            struct.pack_into('<I', data, offset, word)
        cases.append(bytes(data))
    for _ in range(1024):
        data = bytearray(rng.randbytes(64))
        for offset, word in ((0, rng.choice((0x4c585857, 0x4c585857, rng.getrandbits(32)))),
                             (4, rng.choice((4, 4, rng.getrandbits(32)))),
                             (24, rng.choice((rng.randrange(2, 17), rng.getrandbits(32)))),
                             (28, rng.choice((0, rng.getrandbits(32))))):
            struct.pack_into('<I', data, offset, word)
        cases.append(bytes(data))
    return cases


def original_statuses(code, cases):
    import unicorn
    from unicorn import Uc, UC_ARCH_X86, UC_MODE_32, UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC
    from unicorn.x86_const import UC_X86_REG_ESP, UC_X86_REG_EBP, UC_X86_REG_EAX, UC_X86_REG_EIP, UC_X86_REG_EFLAGS
    if unicorn.__version__ != '2.1.4':
        raise RuntimeError('unicorn-version-mismatch')
    cpu = Uc(UC_ARCH_X86, UC_MODE_32)
    start, data, stack, stop = 0x100000, 0x200000, 0x300000, 0x100100
    cpu.mem_map(start, 4096, UC_PROT_READ | UC_PROT_EXEC)
    cpu.mem_write(start, code)
    cpu.mem_map(data, 4096, UC_PROT_READ)
    cpu.mem_map(stack, 4096, UC_PROT_READ | UC_PROT_WRITE)
    statuses = []
    for snapshot in cases:
        cpu.mem_write(data, snapshot)
        cpu.mem_write(stack + 4080, struct.pack('<II', stop, data))
        cpu.reg_write(UC_X86_REG_ESP, stack + 4080)
        cpu.reg_write(UC_X86_REG_EBP, 0)
        cpu.reg_write(UC_X86_REG_EAX, 0xffffffff)
        cpu.reg_write(UC_X86_REG_EFLAGS, 0x202)
        cpu.emu_start(start, stop, timeout=10000, count=64)
        if cpu.reg_read(UC_X86_REG_EIP) != stop or bytes(cpu.mem_read(data, 64)) != snapshot:
            raise RuntimeError('original-validator-did-not-return-read-only')
        statuses.append(cpu.reg_read(UC_X86_REG_EAX))
    return statuses


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dll', type=Path, required=True)
    parser.add_argument('--kotlin-classpath', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = load(args.dll); evidence = assess(data)
    code = PE(data).rva(HEADER_RVA, HEADER_BYTES)
    assert hashlib.sha256(code).hexdigest() == HEADER_SHA256
    cases = corpus(); statuses = original_statuses(code, cases)
    payload = ('\n'.join(case.hex() for case in cases) + '\n').encode()
    result = subprocess.run(['java', '-Xmx64m', '-cp', args.kotlin_classpath,
                             'com.russianranger.fta.client.WarcraftXlHeaderHost'],
                            input=payload, capture_output=True, check=True, timeout=60)
    actual = [int(line) for line in result.stdout.splitlines()]
    if actual != statuses:
        raise RuntimeError('original-header-comparison-mismatch')
    root = Path(__file__).resolve().parents[2]
    implementation = root / 'android/app/src/main/java/com/russianranger/fta/client/WarcraftXlHostHeader.kt'
    evidence.update(scope='original-warcraftxl-header-emulation-comparison', emulator='Unicorn 2.1.4 x86-32',
        originalValidatorEmulated=True, originalMachineCodeModified=False, syntheticCases=len(cases),
        originalStatusCounts=dict(sorted(collections.Counter(map(str, statuses)).items())),
        instructionLimitPerCase=64, timeoutMicrosecondsPerCase=10000, mappedMemoryBytes=12288,
        syntheticInputUnchanged=True, exactKotlinComparisonPassed=True,
        corpusSha256=hashlib.sha256(payload).hexdigest(), outputSha256=hashlib.sha256(result.stdout).hexdigest(),
        kotlinImplementationSha256=hashlib.sha256(implementation.read_bytes()).hexdigest())
    with args.output.open('x') as output:
        json.dump(evidence, output, indent=2); output.write('\n')
    print(json.dumps(dict(syntheticCases=len(cases), exactKotlinComparisonPassed=True)))


if __name__ == '__main__':
    main()
