#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Check pinned shared-settings publication on host and NDK r28c.

Generated private harnesses extract the actual reviewed Settings and access
statements. Host tests execute; Android probes only compile/link. Neither scope
qualifies a complete core build, module gameplay, or Android execution.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from fta_port import check_patch_paths, load_profile  # noqa: E402
from fta_source import verify_source_tree  # noqa: E402

PATCH = 'patches/0015-atomic-shared-settings.patch'
MODULES = {
    'AoeLoot': 'modules/mod-aoe-loot/src/aoe_loot.cpp',
    'JunkToGold': 'modules/mod-junk-to-gold/src/mod_junk_to_gold.cpp',
}
FIXTURE = 'modules/mod-aoe-loot/tests/IntegrationTests.cpp'
FIXTURE_HEADER = 'modules/mod-aoe-loot/tests/stubs/Fixture.h'
DATA_MAP = 'src/common/Utilities/DataMap.h'
STUBS = ('Bag', 'Chat', 'Config', 'Creature', 'DatabaseEnv', 'Group', 'Item',
         'ItemTemplate', 'LevelingChallengeMgr', 'Log', 'LootMgr', 'Map',
         'ObjectMgr', 'Player', 'ScriptMgr')
WRITES = 10000
READERS = 4
MAX_LOG = 4 * 1024 * 1024


def digest(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def substitutions(namespace: str) -> list[tuple[str, str]]:
    global_old = 'std::atomic<std::shared_ptr<Settings const>> Config{std::make_shared<Settings>()};'
    global_new = 'std::shared_ptr<Settings const> Config{std::make_shared<Settings>()};'
    names = [namespace + '::Config', namespace + '::Config'] if namespace == 'AoeLoot' else ['Config', namespace + '::Config']
    pairs = [(global_old, global_new)]
    for name in dict.fromkeys(names):
        pairs.append((name + '.load()',
                      'std::atomic_load_explicit(&' + name + ', std::memory_order_seq_cst)'))
    pairs.append((namespace + '::Config.store(std::move(config));',
                  'std::atomic_store_explicit(&' + namespace + '::Config, std::shared_ptr<' +
                  namespace + '::Settings const>(std::move(config)), std::memory_order_seq_cst);'))
    return pairs


def fixture_substitutions() -> list[tuple[str, str]]:
    return [(namespace + '::Config.store(' + local + ');',
             'std::atomic_store_explicit(&' + namespace + '::Config, std::shared_ptr<' +
             namespace + '::Settings const>(' + local + '), std::memory_order_seq_cst);')
            for namespace, local in [('AoeLoot', 'aoeConfig'), ('JunkToGold', 'junkConfig'), ('AoeLoot', 'config')]]


def sources(source: Path, profile_name: str) -> tuple[dict, dict, dict]:
    profile = load_profile(profile_name)
    entries = [entry for entry in profile['patches'] if entry['path'] == PATCH]
    require(len(entries) == 1, 'Profile must contain the reviewed shared-settings patch exactly once')
    entry = entries[0]
    paths = set(MODULES.values()) | {FIXTURE}
    require(set(entry['files']) == paths, 'Shared-settings patch must bind exactly the three reviewed files')
    patch = ROOT / 'native-port' / PATCH
    require(digest(patch.read_bytes()) == entry['sha256'], 'Reviewed patch identity differs')
    check_patch_paths(patch, paths)
    original, fixed = {}, {}
    for path in sorted(paths):
        fixed[path] = (source / path).read_bytes().decode('utf-8')
        body = fixed[path]
        pairs = fixture_substitutions() if path == FIXTURE else substitutions(next(name for name, item in MODULES.items() if item == path))
        for old, new in pairs:
            expected = 2 if path == MODULES['AoeLoot'] and old.endswith('.load()') else 1
            require(body.count(new) == expected and old not in body,
                    'Actual source does not contain the exact reviewed access statements: ' + path)
            body = body.replace(new, old)
        original[path] = body
        pins = entry['files'][path]
        require(digest(fixed[path].encode()) == pins['after_sha256'] and
                digest(body.encode()) == pins['before_sha256'], 'Complete production/preimage file identity differs: ' + path)
    return original, fixed, entry


def settings(body: str) -> str:
    start = body.index('    struct Settings\n')
    end = body.index('\n    };', start) + len('\n    };')
    return body[start:end]


def pieces(bodies: dict) -> dict:
    result = {}
    for namespace, path in MODULES.items():
        body = bodies[path]
        declaration = re.findall(r'^    (?:std::atomic<)?std::shared_ptr<Settings const>>? Config\{std::make_shared<Settings>\(\)\};$', body, re.M)
        loads = re.findall(r'^        (auto const config = [^;]+;)$', body, re.M)
        stores = [line.strip() for line in body.splitlines()
                  if ('Config.store(' in line or 'std::atomic_store_explicit(' in line)]
        require(len(declaration) == 1 and len(loads) == 2 and len(stores) == 1,
                'Expected one declaration, two snapshot loads and one production store: ' + namespace)
        result[namespace] = {'settings': settings(body), 'global': declaration[0].strip(),
                             'loads': loads, 'store': stores[0]}
    return result


PRELUDE = r'''
#include <atomic>
#include <barrier>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <thread>
#include <unordered_set>
#include <utility>
#include <vector>
using uint32 = std::uint32_t;
static void Check(bool value) { if (!value) std::abort(); }
'''


def access_code(parts: dict, fixture: str) -> str:
    text = PRELUDE
    for namespace, part in parts.items():
        text += '\nnamespace ' + namespace + ' {\n' + part['settings'] + '\n' + part['global'] + '\n'
        for index, load in enumerate(part['loads']):
            text += 'std::shared_ptr<Settings const> Read' + str(index) + '() { ' + load + ' return config; }\n'
        text += 'void Publish(std::shared_ptr<Settings> config) { ' + part['store'] + ' Check(!config); }\n}\n'
    for index, (namespace, local) in enumerate([('AoeLoot', 'aoeConfig'), ('JunkToGold', 'junkConfig'), ('AoeLoot', 'config')]):
        statements = [line.strip() for line in fixture.splitlines()
                      if (namespace + '::Config.store(' + local + ');' in line or
                          ('&' + namespace + '::Config,' in line and 'const>(' + local + ')' in line))]
        require(len(statements) == 1, 'Fixture copy statement not unique')
        text += ('void Copy' + str(index) + '() { auto ' + local + ' = std::make_shared<' + namespace + '::Settings>(); '
                 'auto* pointer = ' + local + '.get(); ' + statements[0] +
                 ' Check(' + local + '.get() == pointer && ' + local + '.use_count() == 2); '
                 'Check(' + namespace + '::Read0().get() == pointer); }\n')
    return text


HOST_TEMPLATE = r'''
namespace NS {
static std::atomic<unsigned> destroyed[10003]{};
struct Tracked : Settings {
    uint32 serial;
    explicit Tracked(uint32 value) : serial(value) { FILL }
    ~Tracked() { destroyed[serial].fetch_add(1, std::memory_order_relaxed); }
};
static void Validate(std::shared_ptr<Settings const> const& snapshot) {
    Check(bool(snapshot));
    auto const* value = static_cast<Tracked const*>(snapshot.get());
    uint32 serial = value->serial;
    Check(COHERENT);
}
static void Run() {
    auto initial = Read0();
    Check(DEFAULTS);
    Check(Read1().get() == initial.get());
    initial.reset();
    COPY_TESTS
    std::shared_ptr<Settings> old = std::make_shared<Tracked>(10001);
    std::weak_ptr<Settings const> weak = old;
    Publish(std::move(old));
    Check(!old);
    auto held = Read0();
    Publish(std::make_shared<Tracked>(10002));
    Check(!weak.expired() && destroyed[10001].load() == 0);
    Validate(held);
    Publish({});
    Check(destroyed[10002].load() == 1);
    Publish(std::make_shared<Tracked>(0));
    std::atomic<bool> done{false};
    std::atomic<bool> halfway{false};
    std::atomic<unsigned> observations{0};
    std::atomic<unsigned> midpointObservations{0};
    std::barrier start(5);
    std::barrier midpoint(5);
    std::vector<std::thread> readers;
    for (unsigned reader = 0; reader != 4; ++reader)
        readers.emplace_back([&] {
            start.arrive_and_wait();
            unsigned count = 0;
            while (!halfway.load(std::memory_order_seq_cst)) {
                Validate(Read0());
                Validate(Read1());
                ++count;
            }
            auto middle = Read0();
            Validate(middle);
            Check(static_cast<Tracked const*>(middle.get())->serial == 5000);
            middle.reset();
            midpointObservations.fetch_add(1, std::memory_order_seq_cst);
            midpoint.arrive_and_wait();
            do {
                Validate(Read0());
                Validate(Read1());
                ++count;
            } while (!done.load(std::memory_order_seq_cst) || count < 10000);
            observations.fetch_add(count, std::memory_order_relaxed);
        });
    start.arrive_and_wait();
    for (uint32 serial = 1; serial <= 10000; ++serial) {
        std::shared_ptr<Settings> config = std::make_shared<Tracked>(serial);
        Publish(std::move(config));
        Check(!config);
        if (serial == 5000) {
            halfway.store(true, std::memory_order_seq_cst);
            midpoint.arrive_and_wait();
            Check(midpointObservations.load() == 4);
        }
    }
    done.store(true, std::memory_order_seq_cst);
    for (auto& reader : readers) reader.join();
    Check(midpointObservations.load() == 4);
    Check(observations.load() >= 40000);
    Validate(Read1());
    Publish({});
    Validate(held);
    Check(!weak.expired() && destroyed[10001].load() == 0);
    held.reset();
    Check(weak.expired() && destroyed[10001].load() == 1);
    for (auto const& count : destroyed) Check(count.load() == 1);
}
}
'''


def host_harness(parts: dict, fixture: str) -> str:
    text = access_code(parts, fixture)
    cases = {
        'AoeLoot': ('enabled = value % 2; message = value % 3 == 0; group = value % 5 == 0; range = float(value); maxCorpses = value + 10;',
                    'value->enabled == bool(serial % 2) && value->message == (serial % 3 == 0) && value->group == (serial % 5 == 0) && value->range == float(serial) && value->maxCorpses == serial + 10',
                    'initial && !initial->enabled && initial->message && initial->group && initial->range == 55.0f && initial->maxCorpses == 10', 'Copy0(); Copy2();'),
        'JunkToGold': ('enabled = value % 2; message = value % 3 == 0; keepItems = {value, value + 20000, value + 40000};',
                       'value->enabled == bool(serial % 2) && value->message == (serial % 3 == 0) && value->keepItems.size() == 3 && value->keepItems.contains(serial) && value->keepItems.contains(serial + 20000) && value->keepItems.contains(serial + 40000)',
                       'initial && !initial->enabled && initial->message && initial->keepItems.empty()', 'Copy1();'),
    }
    for namespace, replacements in cases.items():
        case = HOST_TEMPLATE.replace('NS', namespace)
        for key, value in zip(('FILL', 'COHERENT', 'DEFAULTS', 'COPY_TESTS'), replacements):
            case = case.replace(key, value)
        text += case
    return text + '\nint main() { AoeLoot::Run(); JunkToGold::Run(); std::cout << "shared-settings checks passed\\n"; }\n'


def run(command: list[str], directory: Path, label: str, *, timeout: int = 90) -> tuple[subprocess.CompletedProcess, dict]:
    environment = {key: value for key, value in os.environ.items()
                   if key in ('HOME', 'PATH', 'TMPDIR', 'LANG', 'LC_ALL', 'LD_LIBRARY_PATH')}
    environment['LC_ALL'] = 'C'
    log = directory / (label + '.log')
    with log.open('wb') as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, env=environment,
                                timeout=timeout, check=False)
    require(log.stat().st_size <= MAX_LOG, 'Probe log exceeded its evidence limit: ' + label)
    body = log.read_bytes()
    return result, {'label': label, 'command': command, 'exitCode': result.returncode,
                    'logSha256': digest(body), 'logBytes': len(body)}


def require_pass(command: list[str], directory: Path, label: str, *, timeout: int = 90) -> dict:
    result, record = run(command, directory, label, timeout=timeout)
    require(result.returncode == 0, 'Probe failed; inspect private log: ' + label)
    return record


def fixture_tree(directory: Path, source: Path, bodies: dict) -> Path:
    for name, body in bodies.items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body.encode())
    for name in (FIXTURE_HEADER, DATA_MAP):
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, path)
    stubs = directory / 'generated-stubs'
    stubs.mkdir()
    for name in STUBS:
        (stubs / (name + '.h')).write_text('#pragma once\n#include "Fixture.h"\n')
    return stubs


def ndk_tools(root: Path) -> tuple[Path, dict]:
    properties = (root / 'source.properties').read_text()
    require(re.search(r'^Pkg.Revision\s*=\s*28\.2\.13676358\s*$', properties, re.M) is not None,
            'Expected reviewed NDK r28c revision')
    toolchain = root / 'toolchains/llvm/prebuilt/linux-x86_64'
    compiler = toolchain / 'bin/aarch64-linux-android33-clang++'
    require(compiler.is_file(), 'NDK Android API33 AArch64 compiler missing')
    headers = ['__memory/shared_ptr.h', '__atomic/atomic.h', '__atomic/cxx_atomic_impl.h', 'version']
    pins = {name: digest((toolchain / 'sysroot/usr/include/c++/v1' / name).read_bytes()) for name in headers}
    archive = toolchain / 'sysroot/usr/lib/aarch64-linux-android/libc++_static.a'
    require(archive.is_file(), 'NDK static libc++ archive missing')
    return toolchain, {'revision': '28.2.13676358', 'api': 33, 'target': 'aarch64-linux-android33',
                       'compilerSha256': digest(compiler.resolve().read_bytes()),
                       'headers': pins, 'libcxxStaticSha256': digest(archive.read_bytes())}


def target_probes(directory: Path, toolchain: Path, original: dict, fixed: dict, fixture: str) -> tuple[list, dict]:
    compiler = str(toolchain / 'bin/aarch64-linux-android33-clang++')
    records = [require_pass([compiler, '--version'], directory, 'ndk-version')]
    require('clang version 19.0.1' in (directory / 'ndk-version.log').read_text(), 'Expected NDK Clang 19.0.1')
    for namespace, part in original.items():
        unit = directory / ('ndk-original-' + namespace + '.cpp')
        unit.write_text(PRELUDE + '\nnamespace ' + namespace + ' {\n' + part['settings'] + '\n' + part['global'] + '\n}\n')
        result, record = run([compiler, '-std=c++20', '-pthread', '-c', str(unit), '-o', str(unit.with_suffix('.o'))],
                             directory, 'ndk-original-' + namespace)
        diagnostic = (directory / ('ndk-original-' + namespace + '.log')).read_text()
        require(result.returncode > 0 and not unit.with_suffix('.o').exists() and 'shared_ptr' in diagnostic and
                ('trivially copyable' in diagnostic or '_Atomic cannot be applied' in diagnostic),
                'Original NDK probe did not reproduce the shared_ptr specialization failure: ' + namespace)
        record.update(expectedFailureConfirmed=True, sourceSha256=digest(unit.read_bytes()))
        records.append(record)
    unit = directory / 'ndk-fixed.cpp'
    unit.write_text(access_code(fixed, fixture) + r'''
int main() {
    AoeLoot::Publish(std::make_shared<AoeLoot::Settings>());
    JunkToGold::Publish(std::make_shared<JunkToGold::Settings>());
    Check(AoeLoot::Read0() && AoeLoot::Read1() && JunkToGold::Read0() && JunkToGold::Read1());
    Copy0(); Copy1(); Copy2();
}
''')
    binary = directory / 'ndk-fixed'
    records.append(require_pass([compiler, '-std=c++20', '-O2', '-pthread', '-static-libstdc++',
                                 '-Wl,--no-undefined', '-Wl,-z,max-page-size=16384',
                                 str(unit), '-o', str(binary)], directory, 'ndk-fixed-link'))
    readelf = str(toolchain / 'bin/llvm-readelf')
    records.append(require_pass([readelf, '-h', '-d', '-s', '--wide', str(binary)], directory, 'ndk-fixed-elf'))
    elf = (directory / 'ndk-fixed-elf.log').read_text()
    needed = re.findall(r'\(NEEDED\).*Shared library: \[([^\]]+)\]', elf)
    raw = binary.read_bytes()
    require(raw[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', raw, 18)[0] == 183,
            'Fixed probe is not little-endian ELF64 AArch64')
    phoff = struct.unpack_from('<Q', raw, 32)[0]
    phsize, phcount = struct.unpack_from('<HH', raw, 54)
    require(phsize == 56 and phoff + phsize * phcount <= len(raw), 'Invalid ELF program header table')
    loads = []
    for index in range(phcount):
        kind, _, offset, address, _, _, _, alignment = struct.unpack_from('<IIQQQQQQ', raw, phoff + index * phsize)
        if kind == 1:
            require(alignment >= 16384 and alignment & (alignment - 1) == 0 and offset % alignment == address % alignment,
                    'Target LOAD segment is not 16 KiB page compatible')
            loads.append(alignment)
    require(loads, 'Target LOAD segments missing')
    require(needed and set(needed) <= {'libc.so', 'libm.so', 'libdl.so'}, 'Unexpected target dynamic dependency')
    helpers = [line for line in elf.splitlines() if '__get_sp_mut' in line or '__sp_mut' in line]
    require(helpers and not any(re.search(r'\bUND\b', line) for line in helpers),
            'Static libc++ shared_ptr atomic helpers were not resolved')
    return records, {'sha256': digest(binary.read_bytes()), 'size': binary.stat().st_size,
                     'machine': 'AArch64', 'class': 'ELF64', 'needed': sorted(needed),
                     'loadAlignments': loads, 'page16kCompatible': True,
                     'sharedPtrAtomicHelpersResolved': True, 'sourceSha256': digest(unit.read_bytes()), 'executed': False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--port-profile', required=True)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--host-cxx', default='c++')
    parser.add_argument('--output', required=True, type=Path, help='fresh private directory outside repository/source')
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    output = args.output.resolve()
    require(not output.exists() and not args.output.is_symlink(), 'Output must be a fresh private directory')
    require(not output.is_relative_to(ROOT) and not output.is_relative_to(source), 'Generated evidence must stay outside repository/source')
    inventory = verify_source_tree(source, port_profile=args.port_profile)
    require(len(inventory['modules']) == 52, 'Expected all 52 source modules')
    original, fixed, patch = sources(source, args.port_profile)
    original_parts, fixed_parts = pieces(original), pieces(fixed)
    toolchain, ndk = ndk_tools(args.ndk.resolve(strict=True))
    compiler = shutil.which(args.host_cxx)
    require(compiler is not None, 'Host C++20 compiler missing')
    output.mkdir(parents=True, mode=0o700)
    records = [require_pass([compiler, '--version'], output, 'host-version')]
    host = []
    for label, bodies, parts in [('original', original, original_parts), ('fixed', fixed, fixed_parts)]:
        directory = output / label
        directory.mkdir()
        unit = directory / 'publication.cpp'
        unit.write_text(host_harness(parts, bodies[FIXTURE]))
        binary = directory / 'publication'
        records.append(require_pass([compiler, '-std=c++20', '-O2', '-pthread', str(unit), '-o', str(binary)],
                                    output, label + '-host-compile'))
        records.append(require_pass([str(binary)], output, label + '-host-run', timeout=60))
        stubs = fixture_tree(directory / 'fixture', source, bodies)
        fixture_binary = directory / 'integration'
        includes = [stubs, directory / 'fixture' / Path(FIXTURE_HEADER).parent,
                    directory / 'fixture' / Path(DATA_MAP).parent]
        records.append(require_pass([compiler, '-std=c++20', '-O2', '-pthread',
                                     *[flag for path in includes for flag in ('-I', str(path))],
                                     str(directory / 'fixture' / FIXTURE), '-o', str(fixture_binary)],
                                    output, label + '-fixture-compile'))
        records.append(require_pass([str(fixture_binary)], output, label + '-fixture-run', timeout=30))
        fixture_result = (output / (label + '-fixture-run.log')).read_text()
        match = re.fullmatch(r'(\d+) production-module integration checks passed\n', fixture_result)
        require(match is not None and int(match[1]) > 0, 'Original fixture completion marker missing')
        host.append({'variant': label, 'publicationPassed': True, 'fixtureChecks': int(match[1]),
                     'harnessSha256': digest(unit.read_bytes()), 'binarySha256': digest(binary.read_bytes()),
                     'fixtureBinarySha256': digest(fixture_binary.read_bytes())})
    require(host[0]['fixtureChecks'] == host[1]['fixtureChecks'], 'Original/fixed fixture check counts differ')
    target_records, elf = target_probes(output, toolchain, original_parts, fixed_parts, fixed[FIXTURE])
    records.extend(target_records)
    require(verify_source_tree(source, port_profile=args.port_profile) == inventory, 'Source changed during probe')
    report = {'formatVersion': 1, 'status': 'passed', 'portProfile': args.port_profile,
              'sourceTreeSha256': inventory['tree_sha256'], 'moduleCount': 52, 'cxxStandard': 20,
              'patchSha256': patch['sha256'], 'files': patch['files'],
              'fixtureInputs': {name: digest((source / name).read_bytes()) for name in (FIXTURE_HEADER, DATA_MAP)},
              'hostCompilerSha256': digest(Path(compiler).resolve().read_bytes()),
              'memoryOrder': 'seq_cst', 'productionLoads': 4, 'productionMoveStores': 2, 'fixtureCopyStores': 3,
              'readersPerModule': READERS, 'publicationsPerModule': WRITES,
              'snapshotConsistency': True, 'initialDefaults': True, 'heldSnapshotLifetime': True,
              'allReadersObservedMidpoint': True,
              'exactlyOnceTrackedDestruction': True, 'copyAndMoveOwnershipPreserved': True,
              'host': host, 'ndk': ndk, 'targetElf': elf, 'commands': records,
              'targetExecuted': False, 'worldBuildQualified': False, 'gameplayQualified': False,
              'scope': 'host original/fixed extracted settings concurrency and existing production-module fixture with source stubs; NDK original specialization failure and fixed static-libc++ compile/link only'}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'sourceTreeSha256': report['sourceTreeSha256'],
                      'hostFixtureChecks': [item['fixtureChecks'] for item in host],
                      'reportSha256': digest((output / 'report.json').read_bytes()), 'targetExecuted': False}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
