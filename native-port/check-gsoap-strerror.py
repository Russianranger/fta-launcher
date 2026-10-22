#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Compile exact reviewed soap_strerror bodies against actual Android NDK headers.

The inert soap structure only supplies fields used by this complete function.
No compiled object, gSOAP service, server or device runtime is executed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from fta_port import load_profile  # noqa: E402
from fta_source import verify_source_tree  # noqa: E402

RELATIVE = 'deps/gsoap/stdsoap2.cpp'
GUARD = '#  if defined(__ANDROID__) && defined(__USE_GNU) && __ANDROID_API__ >= 23'
HARNESS = r'''
#include <stdio.h>
#include <string.h>
#include <stddef.h>
#if TEST_EXPECT_GNU
# if !defined(__USE_GNU)
#  error GNU feature selection was not enabled
# endif
#else
# if defined(__USE_GNU)
#  error GNU feature selection unexpectedly enabled
# endif
#endif
#if TEST_EXPECT_POINTER
# ifdef __cplusplus
static_assert(__is_same(decltype(strerror_r(0, (char*)0, 0)), char*));
# else
_Static_assert(__builtin_types_compatible_p(__typeof__(strerror_r(0, (char*)0, 0)), char*), "GNU pointer result");
# endif
#else
# ifdef __cplusplus
static_assert(__is_same(decltype(strerror_r(0, (char*)0, 0)), int));
# else
_Static_assert(__builtin_types_compatible_p(__typeof__(strerror_r(0, (char*)0, 0)), int), "XSI integer result");
# endif
#endif
struct soap {
  int errnum;
  char msgbuf[512];
  size_t recv_maxlength, count;
  int transfer_timeout, recv_timeout, send_timeout;
};
static void soap_strcpy(char *target, size_t size, const char *source) {
  (void)snprintf(target, size, "%s", source);
}
/* Original non-Windows snprintf macro shape, preserving timeout branches. */
#define SOAP_SNPRINTF(buf, len, num) void)snprintf((buf), (len)
#define HAVE_STRERROR_R 1
'''


def digest(body):
    return hashlib.sha256(body).hexdigest()


def function(text: str) -> str:
    start = text.index('static const char*\nsoap_strerror(struct soap *soap)\n{')
    end = text.index('\n}\n#endif', start) + 2
    return text[start:end] + '\n'


def bodies(source: Path, profile: str) -> tuple[str, str, dict]:
    current = (source / RELATIVE).read_text()
    entries = [entry for entry in load_profile(profile)['patches']
               if entry['path'] == 'patches/0008-bionic-gsoap-strerror.patch']
    if len(entries) != 1:
        raise ValueError('Selected profile must include the reviewed gSOAP strerror patch')
    entry = entries[0]
    patch = ROOT / 'native-port' / entry['path']
    if digest(patch.read_bytes()) != entry['sha256']:
        raise ValueError('gSOAP strerror patch differs from its reviewed identity')
    removed, added = [], []
    for line in patch.read_text().splitlines():
        if line.startswith('-') and not line.startswith('--- '):
            removed.append(line[1:])
        elif line.startswith('+') and not line.startswith('+++ '):
            added.append(line[1:])
    old_block, new_block = '\n'.join(removed) + '\n', '\n'.join(added) + '\n'
    if len(removed) != 1 or len(added) != 3 or current.count(new_block) != 1 or GUARD not in added:
        raise ValueError('Expected the exact API-gated Bionic GNU branch adaptation')
    original = current.replace(new_block, old_block)
    pins = entry['files'][RELATIVE]
    if digest(current.encode()) != pins['after_sha256'] or digest(original.encode()) != pins['before_sha256']:
        raise ValueError('Reconstructed gSOAP preimage or actual production file differs from reviewed hashes')
    return function(original), function(current), pins


def tool_info(compiler: str, env: dict, api: int) -> dict:
    triple = subprocess.check_output([compiler, '-dumpmachine'], env=env, text=True, timeout=20).strip()
    if not re.fullmatch(r'aarch64[^\s]*android' + str(api), triple):
        raise ValueError('Compiler must explicitly target Android API ' + str(api) + ' AArch64')
    version = subprocess.check_output([compiler, '--version'], env=env, text=True, timeout=20)
    if not re.search(r'clang version 19\.0\.1\b', version):
        raise ValueError('Compiler must be reviewed NDK r28c Clang 19.0.1')
    header = Path(compiler).resolve(strict=True).parents[1] / 'sysroot/usr/include/string.h'
    body = header.read_bytes()
    if b'#if defined(__USE_GNU) && __ANDROID_API__ >= 23' not in body or b'__RENAME(__gnu_strerror_r)' not in body:
        raise ValueError('Matching NDK string header does not expose the reviewed GNU/API signature gate')
    return {'targetTriple': triple, 'clangVersion': '19.0.1', 'ndkStringHeaderSha256': digest(body)}


def compile_cases(root: Path, compiler: str, language: str, api: int,
                  original: str, production: str, env: dict) -> list[dict]:
    info = tool_info(compiler, env, api)
    records = []
    for gnu in (False, True):
        pointer = gnu and api >= 23
        flags = ['-D_GNU_SOURCE=1'] if gnu else ['-U_GNU_SOURCE', '-D_POSIX_C_SOURCE=200809L']
        for branch, body in (('original', original), ('production', production)):
            label = language + '-' + str(api) + '-' + ('gnu' if gnu else 'xsi') + '-' + branch
            path = root / (label + ('.cpp' if language == 'CXX' else '.c'))
            path.write_text(HARNESS + body + '\nconst char* (*checked_strerror)(struct soap*) = soap_strerror;\n')
            command = [compiler, '-std=' + ('c++20' if language == 'CXX' else 'c11'),
                       '-Werror', '-fPIC', *flags, '-DTEST_EXPECT_GNU=' + str(int(gnu)),
                       '-DTEST_EXPECT_POINTER=' + str(int(pointer)), '-c', str(path), '-o', str(root / (label + '.o'))]
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=30)
            (root / (label + '.log')).write_text(result.stdout + result.stderr)
            expected_pass = branch == 'production' or not pointer
            if expected_pass and result.returncode:
                raise ValueError('Exact function compile failed for ' + label + ': ' + result.stderr[-2400:])
            if not expected_pass and (result.returncode == 0 or 'error:' not in result.stderr
                                      or 'strerror_r' not in result.stderr or not re.search(r'char\s*\*', result.stderr)):
                raise ValueError('Original function failed to reproduce char-pointer/int mismatch: ' + label)
            records.append({'language': language, 'api': api, 'featureMode': 'GNU' if gnu else 'XSI',
                            'body': branch, 'expectedStrerrorReturn': 'char*' if pointer else 'int',
                            'compilePassed': result.returncode == 0, 'expectedOutcomeConfirmed': True,
                            'diagnosticSha256': digest(result.stderr.encode()), **info})
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--profile', default='android-arm64-world-v6')
    parser.add_argument('--cxx', required=True, help='explicit NDK r28c aarch64-linux-android33-clang++')
    parser.add_argument('--cc', help='optional matching Android API33 C compiler')
    parser.add_argument('--api21-cxx', help='optional matching Android API21 C++ compiler')
    parser.add_argument('--api21-cc', help='optional matching Android API21 C compiler')
    args = parser.parse_args()
    inventory = verify_source_tree(args.source, port_profile=args.profile)
    source = args.source.resolve(strict=True)
    original, production, pins = bodies(source, args.profile)
    env = {key: value for key, value in os.environ.items()
           if key in ('HOME', 'TMPDIR', 'PATH', 'LANG', 'LC_ALL', 'LD_LIBRARY_PATH')}
    records = []
    with tempfile.TemporaryDirectory(prefix='fta-gsoap-strerror-') as directory:
        root = Path(directory)
        for compiler, language, api in ((args.cxx, 'CXX', 33), (args.cc, 'C', 33),
                                       (args.api21_cxx, 'CXX', 21), (args.api21_cc, 'C', 21)):
            if compiler:
                records.extend(compile_cases(root, compiler, language, api, original, production, env))
    print(json.dumps({'status': 'passed', 'profile': args.profile, 'caseCount': len(records),
                      'cases': records, 'actualSourceTreeSha256': inventory['tree_sha256'],
                      'productionFileSha256': pins['after_sha256'], 'originalFileSha256': pins['before_sha256'],
                      'productionFunctionSha256': digest(production.encode()),
                      'originalFunctionSha256': digest(original.encode()), 'objectsExecuted': False,
                      'scope': 'exact full soap_strerror function; real NDK headers and target compilation; inert soap fields/copy helper; no gSOAP/server/device execution'}))


if __name__ == '__main__':
    main()
