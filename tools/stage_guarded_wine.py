#!/usr/bin/env python3
"""Compile Windows storage fixtures and the original-client startup observer; reuse the exact qualified native guard."""
import argparse
import ast
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

from stage_android_provider import digest
from stage_client_probe import ROOT, BRIDGE, identity, pe, require
from stage_client_readonly import PAYLOADS

BRIDGES = {'libproot_ro.so': PAYLOADS['libproot_ro.so'], 'libproot-loader.so': BRIDGE['libproot-loader.so']}
SOURCES = ('android/qualification/wine_storage_probe.c', 'android/qualification/guarded_wine.py',
           'android/qualification/android_runtime_probe.py', 'android/qualification/original_startup.c',
           'android/qualification/original_startup.py', 'android/qualification/original_startup_fixture.c')
NOTICE = b'FTA guarded Wine storage fixtures and read-only original-client startup observer: MIT; no game content.\nThe unchanged native import guard is GPL-3.0-or-later. Exact sources and reproduction commands accompany this APK in FTA-client-bridge-corresponding-source.zip. See client-isolation/NOTICE.txt and client-probe notices for the native guard and runtime.\n'
ASSETS = {'probe32.exe', 'probe64.exe', 'guarded_wine.py', 'android_probe.py', 'NOTICE.txt', 'startup64.exe', 'original_startup.py'}


def contract():
    tree = ast.parse((ROOT / SOURCES[1]).read_text())
    return {node.targets[0].id.lower(): list(ast.literal_eval(node.value)) for node in tree.body
            if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) and node.targets[0].id in ('CHECKS', 'STAGES')}


def metadata(files):
    return dict(formatVersion=1, bridge=BRIDGES, sourceSha256={n: digest(ROOT / n) for n in SOURCES}, files=files, **contract())


def verify_apk(archive):
    prefix = 'assets/client-guarded-wine/'
    require({n for n in archive.namelist() if n.startswith(prefix) and not n.endswith('/')} ==
            {prefix + n for n in ASSETS | {'manifest.json'}}, 'Unexpected guarded Wine assets')
    require(archive.getinfo(prefix + 'manifest.json').file_size <= 16384, 'Oversized guarded Wine metadata')
    manifest = json.loads(archive.read(prefix + 'manifest.json'))
    require(set(manifest['files']) == ASSETS and manifest == metadata(manifest['files']), 'Guarded Wine source identity differs')
    for name, expected in manifest['files'].items():
        require(0 < archive.getinfo(prefix + name).file_size <= 2 * 1024 * 1024, 'Oversized guarded Wine file')
        data = archive.read(prefix + name)
        require(dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest()) == expected, 'Guarded Wine asset differs')
        if name.startswith('probe'): pe(data, 0x14c if name == 'probe32.exe' else 0x8664)
        if name == 'startup64.exe': pe(data, 0x8664)
    require(archive.read(prefix + 'guarded_wine.py') == (ROOT / SOURCES[1]).read_bytes() and
            archive.read(prefix + 'android_probe.py') == (ROOT / SOURCES[2]).read_bytes() and
            archive.read(prefix + 'original_startup.py') == (ROOT / SOURCES[4]).read_bytes() and
            archive.read(prefix + 'NOTICE.txt') == NOTICE, 'Guarded Wine guest or notice differs')
    for name, expected in BRIDGES.items():
        require(hashlib.sha256(archive.read('lib/arm64-v8a/' + name)).hexdigest() == expected, 'Guarded Wine bridge differs')
    return manifest


def verify_companion(path, manifest):
    with zipfile.ZipFile(path) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate guarded Wine source entry')
        require(json.loads(archive.read('guarded-wine/manifest.json')) == manifest == metadata(manifest['files']) and
                archive.read('guarded-wine/NOTICE.txt') == NOTICE, 'Guarded Wine source manifest differs')
        with zipfile.ZipFile(io.BytesIO(archive.read('launcher-source.zip'))) as source:
            for name in (*SOURCES, 'tools/stage_guarded_wine.py',
                         'android/app/src/main/java/com/russianranger/fta/client/ClientWorkingView.kt',
                         'android/app/src/main/java/com/russianranger/fta/client/ClientGuardedWine.kt'):
                require(source.read(name) == (ROOT / name).read_bytes(), 'Guarded Wine reproduction source differs')
    return digest(path)


def stage(provider, companion):
    native = provider / 'jniLibs/arm64-v8a'
    for name, expected in BRIDGES.items(): require(digest(native / name) == expected, 'Retained guard identity differs')
    assets = provider / 'assets/client-guarded-wine'; assets.mkdir(parents=True, exist_ok=False)
    for compiler, name, machine in [('i686-w64-mingw32-gcc', 'probe32.exe', 0x14c), ('x86_64-w64-mingw32-gcc', 'probe64.exe', 0x8664)]:
        subprocess.run([compiler, '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-Wl,--no-insert-timestamp',
                        str(ROOT / SOURCES[0]), '-o', str(assets / name)], check=True, timeout=120)
        pe((assets / name).read_bytes(), machine)
    shutil.copyfile(ROOT / SOURCES[1], assets / 'guarded_wine.py')
    shutil.copyfile(ROOT / SOURCES[2], assets / 'android_probe.py')
    subprocess.run(['x86_64-w64-mingw32-gcc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-Wl,--no-insert-timestamp', str(ROOT / SOURCES[3]), '-o', str(assets / 'startup64.exe')], check=True, timeout=120)
    pe((assets / 'startup64.exe').read_bytes(), 0x8664)
    shutil.copyfile(ROOT / SOURCES[4], assets / 'original_startup.py')
    (assets / 'NOTICE.txt').write_bytes(NOTICE)
    manifest = metadata({f.name: identity(f) for f in sorted(assets.iterdir())})
    encoded = json.dumps(manifest, indent=2) + '\n'; (assets / 'manifest.json').write_text(encoded)
    with zipfile.ZipFile(companion, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        require(not any(n.startswith('guarded-wine/') for n in archive.namelist()), 'Do not replace guarded Wine source evidence')
        archive.writestr('guarded-wine/manifest.json', encoded); archive.writestr('guarded-wine/NOTICE.txt', NOTICE)
        archive.writestr('guarded-wine/REPRODUCE.txt', 'Use the included launcher-source.zip. After tools/stage_client_probe.py and tools/stage_client_readonly.py, run tools/stage_guarded_wine.py with --provider and --companion. PE compilers and exact flags are in that file. Native payloads are reused byte-for-byte. tools/check_client_readonly.py and android/qualification/check_guarded_wine.py exercise native Linux; android/qualification/check_original_startup.py qualifies the observer with synthetic x86/x64 processes. Original-client execution and Android remain separate physical gates.\n')
    verify_companion(companion, manifest)
    print(json.dumps(dict(guardedWineStaged=True, sourceCompanionSha256=digest(companion))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('provider', 'companion'): parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args(); stage(args.provider, args.companion)
