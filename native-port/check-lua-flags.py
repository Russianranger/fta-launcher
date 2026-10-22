#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Exercise exact Lua CMake warning cleanup and optional Android C compilation.

Runs host CMake, and optionally compiles two tiny Android objects without executing
them. Neither mode runs Lua/worldserver or qualifies device runtime behavior.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from fta_port import load_profile  # noqa: E402
from fta_source import verify_source_tree  # noqa: E402

RELATIVE = 'modules/mod-ale/src/lualib/lua/CMakeLists.txt'
BASE_FLAGS = ('-g -DANDROID -fdata-sections -ffunction-sections -funwind-tables '
              '-fstack-protector-strong -no-canonical-prefixes -D_FORTIFY_SOURCE=2 '
              '-Wformat -Werror=format-security -O3 -DNDEBUG -fPIC')
CASES = {
    'ndk-security': BASE_FLAGS,
    'standalone-first': '-Werror ' + BASE_FLAGS,
    'standalone-middle': BASE_FLAGS.replace('-DANDROID', '-DANDROID -Werror'),
    'standalone-last': BASE_FLAGS + ' -Werror',
    'standalone-tab': BASE_FLAGS.replace('-DANDROID ', '-DANDROID\t-Werror\t'),
    'standalone-only': '-Werror',
    'adjacent-standalone': '-Werror -Werror ' + BASE_FLAGS,
    'targeted-preserved': BASE_FLAGS + ' -Werror=unused-value',
    'existing-no-error-cleanup': '-Werror -Wno-error=unused-variable ' + BASE_FLAGS,
}


def digest(body):
    return hashlib.sha256(body).hexdigest()


def production_lines(source: Path, profile: str) -> tuple[list[str], list[str]]:
    text = (source / RELATIVE).read_text()
    lines = [line for line in text.splitlines() if line.startswith('string(REGEX REPLACE ')
             and ('-Werror' in line or '-Wno-error' in line)]
    if len(lines) != 4:
        raise ValueError('Expected exactly four production Lua warning-cleanup statements')
    entries = [entry for entry in load_profile(profile)['patches']
               if entry['path'] == 'patches/0006-lua-warning-flags.patch']
    if len(entries) != 1:
        raise ValueError('Selected profile must include the reviewed Lua warning-flag patch')
    entry = entries[0]
    patch = ROOT / 'native-port' / entry['path']
    if digest(patch.read_bytes()) != entry['sha256']:
        raise ValueError('Lua warning patch differs from its reviewed identity')
    before = [line[1:] for line in patch.read_text().splitlines() if line.startswith('-string(REGEX REPLACE ')]
    if len(before) != 2:
        raise ValueError('Expected the two original standalone-Werror cleanup statements')
    return lines, before + [line for line in lines if '-Wno-error' in line]


def cmake_results(root: Path, cmake: str, new: list[str], old: list[str], env: dict) -> dict:
    script = root / 'flags.cmake'
    program = ['cmake_minimum_required(VERSION 3.16)']
    for branch, statements in (('old', old), ('new', new)):
        for label, flags in CASES.items():
            program.extend(['set(CMAKE_C_FLAGS [==[' + flags + ']==])',
                            'set(CMAKE_CXX_FLAGS [==[' + flags + ']==])', *statements])
            for language in ('C', 'CXX'):
                path = root / (branch + '-' + label + '-' + language + '.txt')
                program.append('file(WRITE [==[' + str(path) + ']==] "${CMAKE_' + language + '_FLAGS}")')
    script.write_text('\n'.join(program) + '\n')
    result = subprocess.run([cmake, '-P', str(script)], env=env, capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise ValueError('Exact Lua CMake statements failed: ' + result.stderr[-2000:])
    transformed = {}
    old_corruptions = 0
    for label, flags in CASES.items():
        tokens = shlex.split(flags)
        expected = [token for token in tokens if token != '-Werror' and not token.startswith('-Wno-error=')]
        for language in ('C', 'CXX'):
            new_flags = (root / ('new-' + label + '-' + language + '.txt')).read_text()
            if shlex.split(new_flags) != expected:
                raise ValueError('Production cleanup changed a retained flag or left standalone -Werror: ' + label)
            old_flags = (root / ('old-' + label + '-' + language + '.txt')).read_text()
            if '-Werror=format-security' in tokens:
                if '=format-security' not in shlex.split(old_flags):
                    raise ValueError('Original Lua cleanup did not reproduce the NDK flag corruption')
                old_corruptions += 1
            if label == 'ndk-security' and language == 'C':
                transformed['compileFlags'] = shlex.split(new_flags)
    return {'casesPerLanguage': len(CASES), 'languages': ['C', 'CXX'],
            'originalCorruptionsReproduced': old_corruptions, **transformed}


def target_compile(root: Path, cc: str, flags: list[str], env: dict) -> dict:
    machine = subprocess.check_output([cc, '-dumpmachine'], env=env, text=True, timeout=20).strip()
    if not re.fullmatch(r'aarch64[^\s]*android33', machine):
        raise ValueError('--cc must explicitly target Android API 33 AArch64')
    version = subprocess.check_output([cc, '--version'], env=env, text=True, timeout=20)
    if not re.search(r'clang version 19\.0\.1\b', version):
        raise ValueError('--cc must use the reviewed NDK r28c Clang 19.0.1')
    for label, body, should_pass in (
            ('literal', '#include <stdio.h>\nvoid check(void) { printf("%s", "literal"); }\n', True),
            ('nonliteral', '#include <stdio.h>\nvoid check(char *input) { printf(input); }\n', False)):
        source = root / (label + '.c')
        source.write_text(body)
        result = subprocess.run([cc, *flags, '-c', str(source), '-o', str(root / (label + '.o'))],
                                env=env, capture_output=True, text=True, timeout=30)
        (root / (label + '.log')).write_text(result.stdout + result.stderr)
        if should_pass and result.returncode:
            raise ValueError('Android literal-format compile failed: ' + result.stderr[-2000:])
        if not should_pass and (result.returncode == 0 or 'format-security' not in result.stderr
                                or 'error:' not in result.stderr):
            raise ValueError('Android nonliteral-format compile did not preserve the format-security error')
    return {'targetTriple': machine, 'clangVersion': '19.0.1', 'literalCompilePassed': True,
            'nonliteralCompileRejectedForFormatSecurity': True, 'objectsExecuted': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--profile', default='android-arm64-world-v4')
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--cc', help='optional explicit NDK r28c aarch64-linux-android33-clang')
    args = parser.parse_args()
    inventory = verify_source_tree(args.source, port_profile=args.profile)
    source = args.source.resolve(strict=True)
    new, old = production_lines(source, args.profile)
    # Avoid ambient include/library/toolchain overrides when qualifying target compilation.
    env = {key: value for key, value in os.environ.items()
           if key in ('HOME', 'TMPDIR', 'PATH', 'LANG', 'LC_ALL', 'LD_LIBRARY_PATH')}
    with tempfile.TemporaryDirectory(prefix='fta-lua-flags-') as directory:
        root = Path(directory)
        results = cmake_results(root, args.cmake, new, old, env)
        compile_flags = results.pop('compileFlags')
        if args.cc:
            results['androidCompile'] = target_compile(root, args.cc, compile_flags, env)
    print(json.dumps({'status': 'passed', 'profile': args.profile, **results,
                      'actualSourceTreeSha256': inventory['tree_sha256'],
                      'luaCMakeSha256': digest((source / RELATIVE).read_bytes()),
                      'productionStatementsSha256': digest(('\n'.join(new) + '\n').encode()),
                      'originalStatementsSha256': digest(('\n'.join(old) + '\n').encode()),
                      'scope': 'exact production Lua cleanup under host CMake; optional Android object compilation only; no Lua/world/device execution'}))


if __name__ == '__main__':
    main()
