#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build Android Readline from pinned local inputs, reusing pinned provider ncurses.

Linux x86_64, Python 3.11+ and an existing official NDK r28c are prerequisites.
No downloads, native server execution, provider rewriting or runtime qualification.
--plan validates identities and composes commands without creating output.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'tools'))
from build_native import check_jobs  # noqa: E402
from stage_android_provider import inspect_elf, SYSTEM_LIBS  # noqa: E402

_SPEC = importlib.util.spec_from_file_location('fta_dependency_inputs', REPO / 'native-port/build-dependencies.py')
_DEPS = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_DEPS)
digest, extract, safe_name = _DEPS.digest, _DEPS.extract, _DEPS.safe_name
INPUTS = json.loads((REPO / 'native-port/world-inputs.json').read_text())
PROVIDER_NAMES = ('libncursesw.so.6', 'libandroid-support.so')
RUNTIME_PREFIX = '/__fta_runtime__'


def pinned_file(path: Path, sha256: str, label: str) -> Path:
    if path.is_symlink() or not path.is_file() or digest(path) != sha256:
        raise ValueError(f'{label} does not match its pinned SHA-256')
    return path.resolve(strict=True)


def provider_libraries(stage: Path) -> dict[str, Path]:
    if stage.is_symlink() or not stage.is_dir():
        raise ValueError('Provider stage must be a regular directory')
    stage = stage.resolve(strict=True)
    manifest = json.loads((stage / 'assets/fta-runtime-manifest.json').read_text())
    lock = json.loads((REPO / 'manifests/android-provider-lock.json').read_text())
    if manifest.get('providerId') != lock['providerId'] or manifest.get('abi') != 'arm64-v8a':
        raise ValueError('Provider identity mismatch')
    if lock['packages']['ncurses']['version'] != '6.6.20260307+really6.5.20250830':
        raise ValueError('Provider ncurses does not match the generated header source')
    result = {}
    for name in PROVIDER_NAMES:
        records = [item for item in manifest['files'] if item.get('logicalName') == name]
        pins = [item for item in lock['nativeFiles'] if item.get('logicalName') == name]
        if len(records) != 1 or len(pins) != 1:
            raise ValueError('Missing or duplicate provider library')
        record, pin = records[0], pins[0]
        relative = safe_name(record['path'])
        if relative.parts != ('native', pin['apkName']):
            raise ValueError('Provider library has an invalid APK route')
        path = stage / 'jniLibs/arm64-v8a' / relative.name
        if (not path.resolve().is_relative_to(stage) or path.is_symlink()
                or not path.is_file() or path.stat().st_size != pin['size']
                or record.get('size') != pin['size'] or record.get('sha256') != pin['sha256']):
            raise ValueError('Provider library hash/size mismatch')
        result[name] = pinned_file(path, pin['sha256'], 'Provider library')
    return result


def ndk_tools(root: Path) -> dict[str, str]:
    root = root.resolve(strict=True)
    properties = (root / 'source.properties').read_text()
    if not re.search(r'^Pkg.Revision\s*=\s*' + re.escape(INPUTS['ndkRevision']) + r'\s*$', properties, re.M):
        raise ValueError('NDK revision must match the pinned r28c toolchain')
    binary = root / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    names = {'CC': 'aarch64-linux-android33-clang', 'CXX': 'aarch64-linux-android33-clang++',
             'AR': 'llvm-ar', 'RANLIB': 'llvm-ranlib', 'STRIP': 'llvm-strip',
             'readelf': 'llvm-readelf', 'clang': 'clang'}
    result = {}
    for label, name in names.items():
        path = binary / name
        if not path.is_file() or not os.access(path, os.X_OK) or not path.resolve().is_relative_to(root):
            raise ValueError(f'Missing or escaping NDK executable: {name}')
        result[label] = str(path)
    return result


def compose(work: Path, tools: dict, archives: dict, patches: dict, libraries: dict, ndk: dict) -> tuple[list, dict]:
    sources, build = work / 'sources', work / 'build'
    ncsrc = sources / INPUTS['archives']['ncurses']['root']
    rdsrc = sources / INPUTS['archives']['readline']['root']
    ncb, rdb, libs = build / 'ncurses-target', build / 'readline-target', build / 'provider-lib'
    env = {key: value for key, value in os.environ.items() if key in ('HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'LD_LIBRARY_PATH')}
    env.update({key: ndk[key] for key in ('CC', 'CXX', 'AR', 'RANLIB', 'STRIP')})
    env.update(PATH=str(Path(ndk['CC']).parent) + os.pathsep + os.environ.get('PATH', ''),
               CFLAGS='-O2 -fPIC -fexceptions -fstack-protector-strong', CPPFLAGS='-fPIC',
               LDFLAGS='-Wl,-z,max-page-size=16384')
    ncargs = ['--disable-opaque-panel', '--disable-stripping', '--enable-const', '--enable-ext-colors',
              '--enable-ext-mouse', '--enable-overwrite', '--enable-pc-files', '--enable-termcap',
              '--enable-widec', '--without-ada', '--without-cxx-binding', '--without-debug',
              '--without-tests', '--with-normal', '--with-static', '--with-shared',
              '--with-termpath=' + RUNTIME_PREFIX + '/etc/termcap:' + RUNTIME_PREFIX + '/share/misc/termcap',
              '--with-terminfo-dirs=' + RUNTIME_PREFIX + '/share/terminfo',
              '--with-default-terminfo-dir=' + RUNTIME_PREFIX + '/share/terminfo',
              'ac_cv_header_locale_h=no', 'am_cv_langinfo_codeset=no']
    common = ['--build=x86_64-pc-linux-gnu', '--host=aarch64-linux-android', '--prefix=' + RUNTIME_PREFIX]
    commands = []
    for number in sorted(patches):
        commands.append({'argv': [tools['patch'], '--batch', '--fuzz=0', '-p0', '-i', str(patches[number])],
                         'cwd': str(rdsrc), 'phase': 'patch-' + number, 'environment': {}})
    commands.extend([
        {'argv': [str(ncsrc / 'configure'), *common, *ncargs], 'cwd': str(ncb), 'phase': 'ncurses-configure', 'environment': {}},
        {'argv': [tools['make'], '-j1', '-C', 'include', 'all'], 'cwd': str(ncb), 'phase': 'ncurses-headers', 'environment': {}},
    ])
    rdenv = {'CPPFLAGS': '-I' + str(ncb / 'include') + ' -fPIC',
             'LDFLAGS': '-L' + str(libs) + ' -Wl,-z,max-page-size=16384 -Wl,--no-undefined',
             'LIBS': '-lncursesw -landroid-support'}
    shared = ['SHLIB_LIBS=-L' + str(libs) + ' -lncursesw -landroid-support',
              'SHLIB_XLDFLAGS=-L' + str(libs) + ' -Wl,-z,max-page-size=16384 -Wl,--no-undefined -Wl,-soname,$(basename $@)']
    commands.extend([
        {'argv': [str(rdsrc / 'configure'), *common, '--with-curses', '--enable-multibyte',
                  '--disable-static', '--enable-shared', 'bash_cv_wcwidth_broken=no'],
         'cwd': str(rdb), 'phase': 'readline-configure', 'environment': rdenv},
        {'argv': [tools['make'], '-j1', *shared, 'all'],
         'cwd': str(rdb), 'phase': 'readline-build', 'environment': rdenv},
        {'argv': [tools['make'], '-j1', *shared, 'DESTDIR=' + str(build / 'install'), 'install'],
         'cwd': str(rdb), 'phase': 'readline-install', 'environment': rdenv},
    ])
    return commands, env


def inspect_result(path: Path, readelf: str, libraries: dict) -> dict:
    if path.is_symlink() or not path.is_file():
        raise ValueError('Missing real Readline shared library')
    metadata = inspect_elf(path, readelf)
    dynamic = subprocess.check_output([readelf, '-dW', str(path)], text=True, stderr=subprocess.STDOUT, timeout=20)
    if re.search(r'\((?:RUNPATH|RPATH)\)', dynamic):
        raise ValueError('Readline must not embed runtime search paths')
    if metadata['minimumLoadAlignment'] < 16384 or metadata['interpreter'] is not None:
        raise ValueError('Readline must be a 16-KiB-aligned ARM64 shared library')
    if metadata['sonames'] != ['libreadline.so.8']:
        raise ValueError('Readline SONAME mismatch')
    if not {'libncursesw.so.6', 'libandroid-support.so'} <= set(metadata['needed']):
        raise ValueError('Readline must retain the pinned provider dependency route')
    if set(metadata['needed']) - SYSTEM_LIBS - set(libraries):
        raise ValueError('Unresolved or non-Android Readline dependency')
    closure = {}
    for logical, provider in libraries.items():
        current = inspect_elf(provider, readelf)
        if set(current['needed']) - SYSTEM_LIBS - set(libraries):
            raise ValueError('Unresolved provider dependency')
        closure[logical] = {**current, 'sha256': digest(provider), 'size': provider.stat().st_size}
    return {**metadata, 'sha256': digest(path), 'size': path.stat().st_size,
            'providerClosure': closure, 'readelfClosureVerified': True, 'runtimeSearchPaths': []}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk-root', type=Path, required=True)
    parser.add_argument('--provider-stage', type=Path, required=True)
    parser.add_argument('--work-root', type=Path, required=True, help='fresh nonexistent output directory')
    for name in INPUTS['archives']:
        parser.add_argument('--' + name + '-archive', type=Path, required=True)
    for number in INPUTS['readlinePatches']:
        parser.add_argument('--readline-patch-' + number, type=Path, required=True)
    parser.add_argument('--plan', action='store_true')
    args = parser.parse_args()
    if sys.platform != 'linux' or platform.machine() != 'x86_64':
        raise ValueError('This recipe requires Linux x86_64')
    work = args.work_root.absolute()
    if (work.exists() or work.is_symlink() or not work.parent.is_dir()
            or work.parent.resolve() != work.parent):
        raise ValueError('--work-root must be fresh with an existing non-symlink parent')
    if any(work.is_relative_to(path.resolve(strict=True)) for path in (args.ndk_root, args.provider_stage)):
        raise ValueError('--work-root must not be inside the NDK or provider inputs')
    # Autoconf/Make variables contain paths; reject shell metacharacters at this boundary.
    inputs = [work, args.ndk_root, args.provider_stage]
    inputs.extend(getattr(args, key + '_archive') for key in INPUTS['archives'])
    inputs.extend(getattr(args, 'readline_patch_' + key) for key in INPUTS['readlinePatches'])
    if any(not re.fullmatch(r'[A-Za-z0-9_./-]+', str(path.absolute())) for path in inputs):
        raise ValueError('Build input/output paths must not contain spaces or shell metacharacters')
    tools = {key: shutil.which(key) for key in ('make', 'patch', 'sh')}
    if not all(tools.values()):
        raise ValueError('Host make, patch and sh must be available on the incoming PATH')
    ndk = ndk_tools(args.ndk_root)
    archives = {key: pinned_file(getattr(args, key + '_archive'), value['sha256'], key + ' archive')
                for key, value in INPUTS['archives'].items()}
    patches = {key: pinned_file(getattr(args, 'readline_patch_' + key), value['sha256'], 'Readline patch ' + key)
               for key, value in INPUTS['readlinePatches'].items()}
    libraries = provider_libraries(args.provider_stage)
    commands, env = compose(work, tools, archives, patches, libraries, ndk)
    receipt = {'formatVersion': 1, 'target': INPUTS['target'], 'jobs': 1, 'sourcePins': INPUTS,
               'archives': {key: digest(path) for key, path in archives.items()},
               'readlinePatches': {key: digest(path) for key, path in patches.items()},
               'providerLibraries': {key: digest(path) for key, path in libraries.items()},
               'commands': commands, 'runtimeQualified': False, 'completed': False,
               'ncursesProviderReusedUnchanged': True,
               'ncursesGeneratedHeadersOnly': True,
               'ndkIdentityCheck': 'Installed revision metadata and executable paths; clang version checked during build, no NDK archive verification',
               'readlineDownstreamPathPatchApplied': False,
               'runtimePaths': 'Placeholder prefix; default /etc/inputrc remains unchanged. Runtime must supply TERMINFO/private input configuration explicitly.',
               'lua52BuiltByThisDriver': False,
               'hostLoaderPathConfigured': bool(env.get('LD_LIBRARY_PATH'))}
    if args.plan:
        print(json.dumps(receipt, indent=2))
        return
    check_jobs(1)
    work.mkdir()
    logs = work / 'logs'
    logs.mkdir()
    def save():
        (work / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    save()
    version = subprocess.check_output([ndk['clang'], '--version'], env=env, text=True, stderr=subprocess.STDOUT)
    (logs / 'clang-version.log').write_text(version)
    if not re.search(r'clang version ' + re.escape(INPUTS['clangVersion']) + r'\b', version):
        raise ValueError('Installed NDK clang version does not match the reviewed toolchain')
    receipt['clangVersionVerified'] = INPUTS['clangVersion']
    for key, archive in archives.items():
        extract(archive, work / 'sources', INPUTS['archives'][key]['root'])
    build = work / 'build'
    for directory in ('ncurses-target', 'readline-target', 'provider-lib'):
        (build / directory).mkdir(parents=True)
    for name, source in libraries.items():
        (build / 'provider-lib' / name).symlink_to(source)
    (build / 'provider-lib/libncursesw.so').symlink_to('libncursesw.so.6')
    for command in commands:
        check_jobs(1)
        log = logs / (command['phase'] + '.log')
        with (logs / 'executed.jsonl').open('a') as journal:
            journal.write(json.dumps({**command, 'log': str(log)}) + '\n')
        with log.open('wb') as output:
            subprocess.run(command['argv'], cwd=command['cwd'], env={**env, **command['environment']},
                           stdout=output, stderr=subprocess.STDOUT, check=True)
    installed = build / 'install' / RUNTIME_PREFIX.lstrip('/')
    artifact = installed / 'lib/libreadline.so.8.3'
    receipt['artifact'] = inspect_result(artifact, ndk['readelf'], libraries)
    prefix = work / 'android-world-deps'
    (prefix / 'include').mkdir(parents=True)
    (prefix / 'lib').mkdir()
    shutil.copytree(installed / 'include/readline', prefix / 'include/readline', symlinks=False)
    for path in sorted((installed / 'lib').glob('libreadline.so*')):
        if path.is_symlink():
            target = os.readlink(path)
            if '/' in target or not target.startswith('libreadline.so') or not path.resolve().is_relative_to(installed / 'lib'):
                raise ValueError('Readline installed an escaping library link')
            (prefix / 'lib' / path.name).symlink_to(target)
        else:
            shutil.copy2(path, prefix / 'lib' / path.name)
    for logical, source in libraries.items():
        if digest(source) != receipt['providerLibraries'][logical]:
            raise ValueError('Provider bytes changed during the dependency build')
        (prefix / 'lib' / logical).symlink_to(source)
    (prefix / 'lib/libncursesw.so').symlink_to('libncursesw.so.6')
    receipt['outputPrefix'] = str(prefix)
    receipt['completed'] = True
    save()
    print(work / 'receipt.json')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, tarfile.TarError, zipfile.BadZipFile) as error:
        sys.exit(f'build-world-dependencies: {error}')
