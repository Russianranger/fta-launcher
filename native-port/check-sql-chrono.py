#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Check five pinned SQL chrono callers using real parameter storage in two TUs.

Host original/fixed probes execute synthetic values in the actual in-memory
PreparedStatementData variant. NDK probes compile/link only; no SQL client,
database, full server, or Android executable is run.
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

PATCH = 'patches/0017-sql-chrono-timestamps.patch'
SITES = {
    'src/server/game/Handlers/TicketHandler.cpp': 7,
    'src/server/game/Handlers/CalendarHandler.cpp': 7,
    'src/server/game/Handlers/MiscHandler.cpp': 7,
    'src/server/game/Entities/Pet/Pet.cpp': 15,
    'src/server/scripts/Commands/cs_quest.cpp': 2,
}
HEADER = 'src/server/database/Database/PreparedStatement.h'
DEFINITION = 'src/server/database/Database/PreparedStatement.cpp'
HEADERS = [HEADER, 'src/server/database/Database/SQLOperation.h',
           'src/server/database/Database/DatabaseEnvFwd.h', 'src/common/Define.h',
           'src/common/CompilerDefs.h', 'src/common/Utilities/Duration.h',
           'src/server/game/Time/GameTime.h']
VALUES = [0, 1, 2147483647, 4294967295, 4294967296, 9223372036854775807, -1, -9223372036854775808]


def digest(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def function(body: str, signature: str) -> str:
    start = body.index(signature)
    opening = body.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (body[end] == '{') - (body[end] == '}')
        end += 1
    return body[start:end]


def statements(source: Path, profile: str) -> tuple[list, list, dict]:
    entries = [entry for entry in load_profile(profile)['patches'] if entry['path'] == PATCH]
    require(len(entries) == 1, 'Expected the reviewed SQL chrono patch exactly once')
    entry = entries[0]
    require(set(entry['files']) == set(SITES), 'SQL chrono patch must touch exactly five reviewed callers')
    patch = ROOT / 'native-port' / PATCH
    require(digest(patch.read_bytes()) == entry['sha256'], 'SQL chrono patch identity differs')
    check_patch_paths(patch, set(SITES))
    original, fixed = [], []
    old_expression = 'GameTime::GetGameTime().count()'
    new_expression = 'static_cast<int64>(' + old_expression + ')'
    for path, index in SITES.items():
        body = (source / path).read_bytes().decode()
        new = 'stmt->SetData(' + str(index) + ', ' + new_expression + ');'
        old = 'stmt->SetData(' + str(index) + ', ' + old_expression + ');'
        require(body.count(new) == 1 and old not in body, 'Expected exact scalar timestamp caller: ' + path)
        before = body.replace(new, old)
        pins = entry['files'][path]
        require(digest(body.encode()) == pins['after_sha256'] and digest(before.encode()) == pins['before_sha256'],
                'Actual caller or reconstructed complete preimage differs: ' + path)
        fixed.append(new)
        original.append(old)
    return original, fixed, entry


def definition(source: Path) -> tuple[str, dict]:
    body = (source / DEFINITION).read_bytes().decode()
    constructor = function(body, 'PreparedStatementBase::PreparedStatementBase(')
    destructor = function(body, 'PreparedStatementBase::~PreparedStatementBase(')
    binding = 'template<typename T>\n' + function(body, 'Acore::Types::is_non_string_view_v<T> PreparedStatementBase::SetValidData(')
    explicit = re.findall(r'^template void PreparedStatementBase::SetValidData\(const uint8 index, int64 const& value\);$', body, re.M)
    require(len(explicit) == 1, 'Expected actual explicit canonical int64 instantiation')
    require('ASSERT(index < statement_data.size());' in binding and
            'statement_data[index].data.emplace<T>(value);' in binding,
            'Expected the original checked variant binding body')
    extracted = '\n'.join([constructor, destructor, binding, explicit[0]]) + '\n'
    text = '#include "PreparedStatement.h"\n#include <cstdlib>\n'
    text += '#define ASSERT(condition, ...) do { if (!(condition)) std::abort(); } while (0)\n' + extracted
    return text, {'fileSha256': digest(body.encode()), 'extractedSha256': digest(extracted.encode()),
                  'constructorSha256': digest(constructor.encode()), 'destructorSha256': digest(destructor.encode()),
                  'bindingSha256': digest(binding.encode()), 'instantiationSha256': digest(explicit[0].encode())}


USE = r'''
#include "PreparedStatement.h"
#include "GameTime.h"
#include <cstdlib>
#include <iostream>
#include <limits>
#include <type_traits>
#include <variant>
using Rep = decltype(GameTime::GetGameTime().count());
static_assert(sizeof(int64) == 8 && std::is_signed_v<int64>);
static_assert(sizeof(Rep) == 8 && std::is_signed_v<Rep>);
static_assert(std::numeric_limits<int64>::digits == 63 && std::numeric_limits<Rep>::digits == 63);
using Storage = decltype(PreparedStatementData::data);
static_assert(std::variant_size_v<Storage> == 14);
static_assert(std::is_same_v<std::variant_alternative_t<8, Storage>, int64>);
#ifdef __ANDROID__
static_assert(std::is_same_v<Rep, long long> && std::is_same_v<int64, long>);
#else
static_assert(std::is_same_v<Rep, int64>);
#endif
namespace GameTime {
    Rep current;
    Seconds GetGameTime() { return Seconds(current); }
}
CALLERS
int main() {
    int64 const values[] = {0, 1, 2147483647, 4294967295LL, 4294967296LL,
        std::numeric_limits<int64>::max(), -1, std::numeric_limits<int64>::min()};
    for (int64 value : values) {
        GameTime::current = static_cast<Rep>(value);
        for (unsigned site = 0; site != 5; ++site) {
            PreparedStatementBase storage(0, 16);
            callers[site](&storage);
            auto const& parameter = storage.GetParameters().at(indices[site]).data;
            if (parameter.index() != 8 || !std::holds_alternative<int64>(parameter) ||
                std::get<int64>(parameter) != value) std::abort();
            std::cout << site << ':' << value << ':' << parameter.index() << ':'
                      << std::get<int64>(parameter) << ':' << sizeof(std::get<int64>(parameter)) << '\n';
        }
    }
}
'''


def use(statements: list[str]) -> str:
    callers = '\n'.join('void Caller' + str(index) + '(PreparedStatementBase* stmt) { ' + statement + ' }'
                        for index, statement in enumerate(statements))
    callers += '\nvoid (*callers[])(PreparedStatementBase*) = {' + ','.join('Caller' + str(index) for index in range(5)) + '};\n'
    callers += 'unsigned const indices[] = {' + ','.join(str(index) for index in SITES.values()) + '};\n'
    return USE.replace('CALLERS', callers)


def run(command: list[str], output: Path, label: str, *, passed: bool = True) -> dict:
    environment = {key: value for key, value in os.environ.items()
                   if key in ('HOME', 'PATH', 'TMPDIR', 'LANG', 'LC_ALL', 'LD_LIBRARY_PATH')}
    environment['LC_ALL'] = 'C'
    path = output / (label + '.log')
    with path.open('wb') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=60,
                                env=environment, check=False)
    require(path.stat().st_size <= 4 * 1024 * 1024, 'Probe log exceeded its bound')
    require(result.returncode == 0 if passed else result.returncode > 0, 'Unexpected command outcome: ' + label)
    return {'label': label, 'command': command, 'exitCode': result.returncode,
            'logSha256': digest(path.read_bytes()), 'logBytes': path.stat().st_size}


def elf_metadata(binary: Path) -> dict:
    body = binary.read_bytes()
    require(body[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', body, 18)[0] == 183,
            'Expected little-endian ELF64 AArch64')
    offset = struct.unpack_from('<Q', body, 32)[0]
    size, count = struct.unpack_from('<HH', body, 54)
    require(size == 56 and offset + size * count <= len(body), 'Invalid program headers')
    alignments = []
    for index in range(count):
        kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from('<IIQQQQQQ', body, offset + index * size)
        if kind == 1:
            require(alignment >= 16384 and alignment & (alignment - 1) == 0 and
                    file_offset % alignment == address % alignment, 'LOAD segment is not 16 KiB compatible')
            alignments.append(alignment)
    require(alignments, 'Missing target LOAD segments')
    return {'sha256': digest(body), 'size': len(body), 'class': 'ELF64', 'machine': 'AArch64',
            'loadAlignments': alignments, 'page16kCompatible': True, 'executed': False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--port-profile', required=True)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--host-cxx', default='c++')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    source, output = args.source.resolve(strict=True), args.output.resolve()
    require(not output.exists() and not args.output.is_symlink(), 'Output must be a fresh private directory')
    require(not output.is_relative_to(ROOT) and not output.is_relative_to(source), 'Output must stay outside repository/source')
    inventory = verify_source_tree(source, port_profile=args.port_profile)
    require(len(inventory['modules']) == 52, 'Expected all 52 source modules')
    original, fixed, patch = statements(source, args.port_profile)
    definition_text, definition_pins = definition(source)
    wire_path = 'src/server/database/Database/MySQLPreparedStatement.cpp'
    wire = (source / wire_path).read_bytes()
    require(b'template<> struct MySQLType<int64> : std::integral_constant<enum_field_types, MYSQL_TYPE_LONGLONG> { };' in wire and
            b'uint32 len = uint32(sizeof(T));' in wire and b'param->is_unsigned = std::is_unsigned_v<T>;' in wire,
            'Expected original signed int64 SQL wire policy')
    host = shutil.which(args.host_cxx)
    require(host is not None, 'Host C++20 compiler missing')
    ndk = args.ndk.resolve(strict=True)
    properties = (ndk / 'source.properties').read_text()
    require(re.search(r'^Pkg.Revision\s*=\s*28\.2\.13676358\s*$', properties, re.M) is not None, 'Expected NDK r28c')
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    target = str(toolchain / 'bin/aarch64-linux-android33-clang++')
    output.mkdir(parents=True, mode=0o700)
    records = [run([host, '--version'], output, 'host-version'), run([target, '--version'], output, 'ndk-version')]
    require('clang version 19.0.1' in (output / 'ndk-version.log').read_text(), 'Expected NDK Clang 19.0.1')
    definitions = output / 'definition.cpp'
    definitions.write_text(definition_text)
    for label, body in [('original', original), ('fixed', fixed)]:
        (output / (label + '.cpp')).write_text(use(body))
    includes = [source / 'src/server/database/Database', source / 'src/common',
                source / 'src/common/Utilities', source / 'src/server/game/Time']
    flags = ['-std=c++20', '-O2', '-fno-lto', *[flag for path in includes for flag in ('-I', str(path))]]
    binaries = {}
    for label, compiler in [('host', host), ('ndk', target)]:
        definition_object = output / (label + '-definition.o')
        records.append(run([compiler, *flags, '-c', str(definitions), '-o', str(definition_object)], output, label + '-definition-compile'))
        for variant in ('original', 'fixed'):
            unit, object_path = output / (variant + '.cpp'), output / (label + '-' + variant + '.o')
            records.append(run([compiler, *flags, '-c', str(unit), '-o', str(object_path)], output, label + '-' + variant + '-compile'))
            binary = output / (label + '-' + variant)
            link_flags = ['-static-libstdc++', '-Wl,--no-undefined', '-Wl,-z,max-page-size=16384'] if label == 'ndk' else []
            records.append(run([compiler, *link_flags, str(object_path), str(definition_object), '-o', str(binary)],
                               output, label + '-' + variant + '-link', passed=label != 'ndk' or variant != 'original'))
            if label == 'host':
                records.append(run([str(binary)], output, label + '-' + variant + '-run'))
                binaries[variant] = digest(binary.read_bytes())
            elif variant == 'original':
                diagnostic = (output / 'ndk-original-link.log').read_text()
                require(not binary.exists() and 'undefined symbol' in diagnostic and
                        'PreparedStatementBase::SetValidData<long long>' in diagnostic,
                        'Original NDK link did not reproduce the exact missing long-long specialization')
    expected = ''.join(f'{site}:{value}:8:{value}:8\n' for value in VALUES for site in range(5)).encode()
    require((output / 'host-original-run.log').read_bytes() == expected == (output / 'host-fixed-run.log').read_bytes(),
            'Original/fixed host canonical variant values differ')
    nm = str(toolchain / 'bin/llvm-nm')
    for label, flags_nm, path in [
        ('ndk-definition-symbols', ['--defined-only'], output / 'ndk-definition.o'),
        ('ndk-original-symbols', ['--undefined-only'], output / 'ndk-original.o'),
        ('ndk-fixed-symbols', ['--undefined-only'], output / 'ndk-fixed.o'),
        ('ndk-final-symbols', ['--defined-only'], output / 'ndk-fixed')]:
        records.append(run([nm, '-C', *flags_nm, str(path)], output, label))
        text = (output / (label + '.log')).read_text()
        specialization = 'long long' if label == 'ndk-original-symbols' else 'long'
        require('PreparedStatementBase::SetValidData<' + specialization + '>' in text,
                'Actual specialization symbol mismatch: ' + label)
        if specialization == 'long':
            require('PreparedStatementBase::SetValidData<long long>' not in text, 'Unexpected long-long specialization')
    binary = output / 'ndk-fixed'
    records.append(run([str(toolchain / 'bin/llvm-readelf'), '-d', str(binary)], output, 'ndk-dynamic'))
    dynamic = (output / 'ndk-dynamic.log').read_text()
    needed = re.findall(r'\(NEEDED\).*Shared library: \[([^\]]+)\]', dynamic)
    require(needed and set(needed) <= {'libc.so', 'libdl.so', 'libm.so'} and
            'RUNPATH' not in dynamic and 'RPATH' not in dynamic, 'Unexpected target dynamic linkage')
    target_elf = elf_metadata(binary)
    target_elf['needed'] = sorted(needed)
    require(verify_source_tree(source, port_profile=args.port_profile) == inventory, 'Source changed during probes')
    report = {'formatVersion': 1, 'status': 'passed', 'portProfile': args.port_profile,
              'sourceTreeSha256': inventory['tree_sha256'], 'moduleCount': 52, 'cxxStandard': 20,
              'patchSha256': patch['sha256'], 'files': patch['files'],
              'headers': {path: digest((source / path).read_bytes()) for path in HEADERS},
              'wirePolicy': {'path': wire_path, 'sha256': digest(wire), 'sourceAuditOnly': True,
                             'canonicalType': 'MYSQL_TYPE_LONGLONG', 'signed': True, 'widthBytes': 8},
              'definition': definition_pins, 'useTranslationUnits': {variant: digest((output / (variant + '.cpp')).read_bytes()) for variant in ('original', 'fixed')},
              'separateTranslationUnits': True, 'callerCount': 5, 'hostValuesPerCaller': 8, 'hostVariantChecksPerVersion': 40,
              'canonicalVariantIndex': 8, 'canonicalValueType': 'signed64 int64', 'hostOriginalFixedValuesEqual': True,
              'hostBinaries': binaries, 'hostCompilerSha256': digest(Path(host).resolve().read_bytes()),
              'ndkCompilerSha256': digest(Path(target).resolve().read_bytes()), 'ndkRevision': '28.2.13676358',
              'originalNdkCompilePassed': True, 'originalNdkMissingLongLongLinkReproduced': True,
              'fixedNdkCanonicalInt64LinkPassed': True, 'targetElf': target_elf, 'commands': records,
              'targetExecuted': False, 'sqlExecuted': False, 'serverQualified': False,
              'scope': 'exact five caller statements and actual PreparedStatement/GameTime headers; extracted constructor/destructor/binding/explicit int64 definition in a separate TU; synthetic host variant values; NDK compile/link only'}
    path = output / 'report.json'
    path.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': 'passed', 'sourceTreeSha256': inventory['tree_sha256'], 'hostChecksPerVersion': 40,
                      'reportSha256': digest(path.read_bytes()), 'targetExecuted': False, 'sqlExecuted': False}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
