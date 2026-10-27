#!/usr/bin/env python3
"""Stage only verified Android PRoot and newly compiled synthetic PE fixtures.

No private/native server or original client binaries are read or compiled.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import zipfile

from build_client_bridge import INPUTS, SOURCE_SHA, loader_elf
from stage_android_provider import digest, inspect_elf

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = {
    'libproot.so': '46dcafabc77b386a7127f6cb7c71bdac03c372ff6bb4db0e1e9493ee29332adb',
    'libproot-loader.so': '614591192bb34c98cc6cef528fb74ecfc1c97d0fabf4e352df1f16873c71d447',
}
SOURCES = ('android/qualification/runtime_probe.c', 'android/qualification/android_runtime_probe.py',
           'native/client-bridge/proot-exitkill.patch')


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def identity(path):
    return dict(bytes=path.stat().st_size, sha256=digest(path))


def pe(data, machine):
    require(64 <= len(data) <= 2 * 1024 * 1024 and data[:2] == b'MZ', 'Invalid synthetic PE')
    offset = struct.unpack_from('<I', data, 60)[0]
    require(64 <= offset <= len(data) - 26 and data[offset:offset + 4] == b'PE\0\0' and
            struct.unpack_from('<H', data, offset + 4)[0] == machine, 'Wrong synthetic PE architecture')


def verify_apk(archive):
    """Verify public assembly additions before private-donor packaging accepts them."""
    prefix = 'assets/client-probe/'
    require(archive.getinfo(prefix + 'manifest.json').file_size <= 16384, 'Oversized client probe metadata')
    manifest = json.loads(archive.read(prefix + 'manifest.json'))
    require(manifest['formatVersion'] == 1 and manifest['bridge'] == BRIDGE and
            manifest['sourceSha256'] == {name: digest(ROOT / name) for name in SOURCES}, 'Client probe source identity differs')
    require(set(manifest['files']) == {'probe32.exe', 'probe64.exe', 'android_probe.py', 'NOTICE.txt', 'proot-COPYING.txt', 'talloc-COPYING.txt', 'GNU-GPL-3.txt'},
            'Unexpected client probe assets')
    require({n for n in archive.namelist() if n.startswith(prefix) and not n.endswith('/')} ==
            {prefix + n for n in manifest['files']} | {prefix + 'manifest.json'}, 'Unlisted client probe payload')
    lock = json.loads((ROOT / 'android/qualification/client-runtime-lock.json').read_text())
    data = archive.read('assets/metadata/client-runtime-catalog.tsv')
    require(dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest()) == lock['catalog'] and
            json.loads(archive.read('assets/metadata/client-runtime-lock.json')) == lock, 'Runtime extraction metadata differs')
    for name, expected in manifest['files'].items():
        data = archive.read(prefix + name)
        require(dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest()) == expected, 'Client probe asset differs')
        if name.startswith('probe') and name.endswith('.exe'):
            pe(data, 0x14c if name == 'probe32.exe' else 0x8664)
    require(archive.read(prefix + 'android_probe.py') == (ROOT / SOURCES[1]).read_bytes(), 'Guest program differs')
    for name, expected in BRIDGE.items():
        data = archive.read('lib/arm64-v8a/' + name)
        require(hashlib.sha256(data).hexdigest() == expected, 'Android client bridge identity differs')
        if 'loader' in name:
            loader_elf(data)
    return manifest


def verify_companion(path, manifest):
    with zipfile.ZipFile(path) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())) and
                all(i.file_size <= 64 * 1024 * 1024 for i in archive.infolist()), 'Invalid bridge source companion')
        require(json.loads(archive.read('manifest.json')) == manifest, 'Source companion targets a different probe')
        for name, expected in INPUTS.items():
            require(hashlib.sha256(archive.read('original/' + name)).hexdigest() == expected, 'Missing exact bridge source')
        for name in ('NOTICE.txt', 'proot-COPYING.txt', 'talloc-COPYING.txt', 'GNU-GPL-3.txt'):
            data = archive.read(name)
            require(dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest()) == manifest['files'][name], 'Source notice differs')
        proof = json.loads(archive.read('build-evidence.json'))
        require(proof['sourceArchiveSha256'] == SOURCE_SHA and
                {k: v['sha256'] for k, v in proof['payloads'].items()} == BRIDGE, 'Source build identity differs')
        with zipfile.ZipFile(io.BytesIO(archive.read('launcher-source.zip'))) as launcher:
            for name, expected in manifest['sourceSha256'].items():
                require(hashlib.sha256(launcher.read(name)).hexdigest() == expected, 'Launcher source differs')
            require(launcher.read('tools/build_client_bridge.py') == (ROOT / 'tools/build_client_bridge.py').read_bytes(), 'Bridge recipe differs')
    return digest(path)


def license_text(archive, names):
    with tarfile.open(archive) as source:
        found = [entry for entry in source if entry.isfile() and entry.name in names]
        require(len(found) == 1 and found[0].size <= 128 * 1024, 'Missing exact component license')
        return source.extractfile(found[0]).read()


def stage(build, provider, companion):
    proof = json.loads((build / 'build-evidence.json').read_text())
    require(proof['sourceArchiveSha256'] == SOURCE_SHA and proof['sourceComponents'] == INPUTS and
            proof['ownershipPatchSha256'] == digest(ROOT / SOURCES[2]) and proof['buildJobs'] == 1, 'Unqualified bridge build')
    assets = provider / 'assets/client-probe'; assets.mkdir(parents=True, exist_ok=False)
    native = provider / 'jniLibs/arm64-v8a'; native.mkdir(parents=True, exist_ok=True)
    for name, expected in BRIDGE.items():
        source = build / 'project/app/src/main/jniLibs/arm64-v8a' / name
        require(not source.is_symlink() and digest(source) == expected and not (native / name).exists(), 'Bridge identity differs')
        if 'loader' in name:
            loader_elf(source.read_bytes())
        else:
            meta = inspect_elf(source, reject_search_paths=True)
            require(meta['minimumLoadAlignment'] >= 16384 and meta['needed'] == ['libc.so', 'libdl.so'], 'Unexpected tracer ELF')
        shutil.copyfile(source, native / name)
    for compiler, name, machine in [('i686-w64-mingw32-gcc', 'probe32.exe', 0x14c), ('x86_64-w64-mingw32-gcc', 'probe64.exe', 0x8664)]:
        subprocess.run([compiler, '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', str(ROOT / SOURCES[0]),
                        '-o', str(assets / name)], check=True, timeout=120)
        pe((assets / name).read_bytes(), machine)
    shutil.copyfile(ROOT / SOURCES[1], assets / 'android_probe.py')
    (assets / 'proot-COPYING.txt').write_bytes(license_text(build / 'original/proot-source.tar.gz', {'COPYING'}))
    (assets / 'talloc-COPYING.txt').write_bytes(license_text(build / 'original/talloc-2.4.3.tar.gz', {'talloc-2.4.3/LICENSE'}))
    gpl3 = Path('/usr/share/common-licenses/GPL-3').read_bytes()
    require(len(gpl3) in range(30000, 40000) and b'GNU GENERAL PUBLIC LICENSE' in gpl3 and b'29 June 2007' in gpl3,
            'Complete GPLv3 license is required alongside LGPLv3')
    (assets / 'GNU-GPL-3.txt').write_bytes(gpl3)
    (assets / 'NOTICE.txt').write_text(
        'Termux PRoot commit 7266fb3e8516535682f5a9c8f3a7e70f6506eddb: GPL-2.0-or-later.\n'
        'Samba talloc 2.4.3, statically linked: LGPL-3.0-or-later; original notices retained in source.\n'
        'FtA synthetic fixtures and Android controller: repository MIT license. No game files included.\n'
        'Exact corresponding sources, patches and build drivers accompany this APK as FTA-client-bridge-corresponding-source.zip.\n'
        'The separate unchanged Wine/FEX runtime is downloaded directly from Russianranger/uo-android-launcher v0.2.0.\n'
        'Its full package notices and exact Debian versions remain inside the runtime. Pinned Wine and FEX source archives:\n'
        'https://github.com/Russianranger/uo-android-launcher/releases/download/v0.2.0/wine-arm64ec-source.tar.gz\n'
        'https://github.com/Russianranger/uo-android-launcher/releases/download/v0.2.0/fex-2510-source-with-submodules.tar.gz\n'
        'Runtime build recipe: https://github.com/Russianranger/uo-android-launcher/blob/80859342ae2e8cf3cdcfad508d94b5d76774d379/client-runtime/Dockerfile\n')
    manifest = dict(formatVersion=1, bridge=BRIDGE, sourceSha256={name: digest(ROOT / name) for name in SOURCES},
                    files={file.name: identity(file) for file in sorted(assets.iterdir())})
    (assets / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    require(not companion.exists(), 'Do not replace a source companion')
    # Public repository snapshot includes all driver imports, patches, tests and MIT notice.
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', 'tools', 'native/client-bridge', 'android/qualification'], cwd=ROOT, check=True)
    source_zip = subprocess.check_output(['git', 'archive', '--format=zip', 'HEAD'], cwd=ROOT)
    with zipfile.ZipFile(companion, 'x', compression=zipfile.ZIP_DEFLATED) as output:
        output.writestr('launcher-source.zip', source_zip)
        for name, expected in INPUTS.items():
            source = build / 'original' / name
            require(digest(source) == expected, 'Original corresponding source differs')
            output.write(source, 'original/' + name)
        output.write(build / 'build-evidence.json', 'build-evidence.json')
        output.write(build / 'build.sh', 'generated-build-recipe.sh')
        for file in assets.iterdir():
            if file.suffix == '.txt' or file.name == 'manifest.json':
                output.write(file, file.name)
        output.writestr('REPRODUCE.txt', f'Launcher commit: {revision}\nUnpack launcher-source.zip. Follow docs/client-android-bridge.md.\n'
            'Run tools/build_client_bridge.py with the original pinned public corresponding-source archive and NDK r28c ZIP in a new directory.\n'
            'The driver verifies both inputs and compiles with one job. The fixture compilation commands are in tools/stage_client_probe.py.\n'
            'All original archives, applied patches, generated recipe, license notices, source helpers and measured output identities are included.\n'
            'The separate retained private server source companion remains necessary for private APK server payloads.\n')
    verify_companion(companion, manifest)
    print(json.dumps(dict(clientProbeStaged=True, correspondingSource=identity(companion))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('bridge-build', 'provider', 'companion'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args(); stage(args.bridge_build.resolve(), args.provider.resolve(), args.companion.resolve())
