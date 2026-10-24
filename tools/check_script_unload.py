#!/usr/bin/env python3
"""Reproduce stale enabled-hook dispatch using original code and a pinned shutdown patch."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from build_native import check_jobs
from fta_source import SOURCE_ROOT, SOURCE_SHA256, sha256_file

ROOT = Path(__file__).resolve().parents[1]
MANAGER = 'src/server/game/Scripting/ScriptMgr.cpp'


def function(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end] + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-zip', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--log-directory', type=Path, required=True)
    args = parser.parse_args()
    if sha256_file(args.source_zip) != SOURCE_SHA256:
        raise ValueError('Original source archive differs')
    check_jobs(1)
    patch = ROOT / 'native-port/patches/0018-detach-enabled-hooks-before-unload.patch'
    profile = json.loads((ROOT / 'native-port/profiles.json').read_text())['profiles']['android-arm64-world-v14']
    pin = profile['patches'][-1]
    if sha256_file(patch) != pin['sha256']:
        raise ValueError('Shutdown patch identity differs')
    args.log_directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='fta-script-unload-') as temporary, zipfile.ZipFile(args.source_zip) as archive:
        work = Path(temporary); identities = {}
        def read(name):
            data = archive.read(SOURCE_ROOT + '/' + name)
            identities[name] = hashlib.sha256(data).hexdigest()
            return data.decode()
        original = read(MANAGER)
        if identities[MANAGER] != pin['files'][MANAGER]['before_sha256']:
            raise ValueError('Original manager differs')
        source = work / MANAGER; source.parent.mkdir(parents=True); source.write_text(original)
        subprocess.run(['git', 'apply', '--check', str(patch)], cwd=work, check=True)
        subprocess.run(['git', 'apply', str(patch)], cwd=work, check=True)
        fixed = source.read_text()
        if sha256_file(source) != pin['files'][MANAGER]['after_sha256']:
            raise ValueError('Patched manager differs')
        header = read('src/server/game/Scripting/ScriptMgr.h')
        start = header.index('template<class TScript>\nclass ScriptRegistry')
        (work / 'registry.inc').write_text(header[start:header.rindex('#endif')])
        (work / 'TypeList.h').write_text(read('src/common/Utilities/TypeList.h'))
        misc = read('src/server/game/Scripting/ScriptDefines/MiscScript.h')
        enum = re.search(r'enum MiscHook\s*\{.*?\};', misc, re.S).group()
        (work / 'misc-enum.inc').write_text(enum)
        macros = read('src/server/game/Scripting/ScriptMgrMacros.h')
        macro = macros[macros.index('#define CALL_ENABLED_HOOKS('):macros.index('#define CALL_ENABLED_BOOLEAN_HOOKS(')]
        dispatch = function(read('src/server/game/Scripting/ScriptDefines/MiscScript.cpp'), 'void ScriptMgr::OnDestructObject(')
        (work / 'dispatch.inc').write_text(macro + '\n' + dispatch)
        env = dict(os.environ, ASAN_OPTIONS='detect_leaks=0:halt_on_error=1', UBSAN_OPTIONS='halt_on_error=1')
        observed = {}
        for label, text in [('original', original), ('fixed', fixed)]:
            (work / 'unload.inc').write_text(function(text, 'void ScriptMgr::Unload()'))
            for sanitizer in (False, True):
                tag = label + ('-asan-ubsan' if sanitizer else '-release')
                exe = work / tag
                flags = ['-O1', '-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-fno-pie', '-no-pie'] if sanitizer else ['-O2']
                with (args.log_directory / (tag + '-build.log')).open('w') as log:
                    subprocess.run(['g++', '-std=gnu++20', '-Wall', '-Wextra', '-Werror', *flags, '-I' + str(work),
                                    str(ROOT / 'tests/fixtures/script_unload_host.cpp'), '-o', str(exe)], check=True, stdout=log, stderr=log)
                for mode in ('active', 'late', 'reentrant'):
                    if label == 'original' and not sanitizer and mode != 'active': continue
                    result = subprocess.run([str(exe), mode], env=env, capture_output=True, timeout=10)
                    (args.log_directory / (tag + '-' + mode + '.log')).write_bytes(result.stdout + result.stderr)
                    if label == 'original' and mode != 'active':
                        if result.returncode == 0 or b'heap-use-after-free' not in result.stderr:
                            raise ValueError('Original stale dispatch was not reproduced')
                    elif result.returncode != 0:
                        raise ValueError('Active callback or fixed unload fixture failed: ' + tag + '/' + mode)
                    observed[tag + '/' + mode] = result.returncode
        proof = dict(status='original-stale-hook-reproduced-and-fixed', sourceArchiveSha256=SOURCE_SHA256,
            sourceFilesSha256=identities, patchSha256=pin['sha256'], sourceProfile='android-arm64-world-v14',
            originalActiveCallbacksPassed=True, originalLateAndReentrantHeapUseAfterFree=True,
            fixedActiveCallbacksPreserved=True, fixedLateAndReentrantCallbacksSafe=True,
            hookSlotDimensionsPreserved=True, scriptDeletionCountsPreserved=True,
            releaseAndAddressUndefinedSanitizersPassed=True, leakSanitizerClaimed=False,
            fixtureRegistryTypes=3, fullWorldFixture=False, compilerJobs=1, androidExecutionQualified=False,
            worldReady=False, databaseFallbackValidated=False, fixtureExitCodes=observed)
        args.output.write_text(json.dumps(proof, indent=2) + '\n')
        print(proof['status'])


if __name__ == '__main__':
    main()
