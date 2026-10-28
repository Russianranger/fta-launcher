#!/usr/bin/env python3
"""Stage the separately pinned import guard and append its exact source evidence."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import zipfile

from build_client_bridge import ROOT, SOURCE_SHA, INPUTS, require
from check_client_readonly import checks
from stage_android_provider import digest, inspect_elf
from stage_client_probe import BRIDGE

PAYLOADS = {'libproot_ro.so': 'd7d606c2a6e59687054448ac6711282ee1f542ec8ff317dd9ae86cb1da9a6603', 'libfta_storage_probe.so': '78400783fc04669c55447a51e2b38b77018c493201ef0708033bb229a4fd92e8'}
SOURCES = ('native/client-bridge/proot-readonly.patch', 'native/client-bridge/read_only.c',
           'native/client-bridge/readonly_fixture.c', 'native/client-bridge/proot-exitkill.patch')
NOTICE = ('FTA import-tree write guard: GPL-3.0-or-later; disposable fixture: MIT.\n'
          'Based on the pinned Termux PRoot and talloc sources described in client-probe/NOTICE.txt.\n'
          'Exact sources, patches and recipe accompany this APK in FTA-client-bridge-corresponding-source.zip.\n'
          'Cooperative file-integrity protection; not a hostile-code security sandbox.\n').encode()


def metadata():
    return dict(formatVersion=1, payloadSha256=PAYLOADS, loaderSha256=BRIDGE['libproot-loader.so'],
                sourceSha256={name: digest(ROOT / name) for name in SOURCES}, checks=checks(),
                noticeSha256=hashlib.sha256(NOTICE).hexdigest())


def verify_apk(archive):
    prefix = 'assets/client-isolation/'
    require({n for n in archive.namelist() if n.startswith(prefix) and not n.endswith('/')} ==
            {prefix + 'manifest.json', prefix + 'NOTICE.txt'}, 'Unexpected isolation assets')
    require(archive.getinfo(prefix + 'manifest.json').file_size <= 8192, 'Oversized isolation metadata')
    manifest = json.loads(archive.read(prefix + 'manifest.json'))
    require(manifest == metadata() and archive.read(prefix + 'NOTICE.txt') == NOTICE, 'Isolation source identity differs')
    for name, expected in {**PAYLOADS, 'libproot-loader.so': BRIDGE['libproot-loader.so']}.items():
        entry = archive.getinfo('lib/arm64-v8a/' + name)
        require(0 < entry.file_size <= 1024 * 1024 and hashlib.sha256(archive.read(entry)).hexdigest() == expected,
                'Isolation native identity differs')
    return manifest


def verify_companion(companion, manifest):
    with zipfile.ZipFile(companion) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate source companion entry')
        require(json.loads(archive.read('readonly/manifest.json')) == manifest == metadata(), 'Isolation source manifest differs')
        proof = json.loads(archive.read('readonly/build-evidence.json'))
        require(proof['scope'] == 'android-client-readonly-build-only' and proof['sourceArchiveSha256'] == SOURCE_SHA and
                proof['sourceComponents'] == INPUTS and proof['buildJobs'] == 1 and
                {k:v['sha256'] for k,v in proof['payloads'].items()} == {**PAYLOADS, 'libproot-loader.so': BRIDGE['libproot-loader.so']},
                'Isolation build identity differs')
        require(archive.read('readonly/NOTICE.txt') == NOTICE, 'Isolation source notice differs')
        with zipfile.ZipFile(io.BytesIO(archive.read('launcher-source.zip'))) as source:
            for name in (*SOURCES, 'tools/build_client_bridge.py', 'tools/stage_client_readonly.py', 'tools/check_client_readonly.py'):
                require(source.read(name) == (ROOT / name).read_bytes(), 'Isolation reproduction source differs')
    return digest(companion)


def stage(build, provider, companion):
    proof = json.loads((build / 'build-evidence.json').read_text())
    require(proof['scope'] == 'android-client-readonly-build-only' and proof['sourceComponents'] == INPUTS and
            proof['readonlySourceSha256'] == {name: digest(ROOT / name) for name in SOURCES[:3]} and
            proof['ownershipPatchSha256'] == digest(ROOT / SOURCES[3]), 'Unqualified isolation build')
    native = provider / 'jniLibs/arm64-v8a'
    for name, expected in PAYLOADS.items():
        source = build / 'project/app/src/main/jniLibs/arm64-v8a' / name
        require(digest(source) == expected and not (native / name).exists(), 'Isolation build identity differs')
        elf = inspect_elf(source, reject_search_paths=True)
        require(elf['minimumLoadAlignment'] >= 16384 and elf['interpreter'] == '/system/bin/linker64', 'Invalid isolation ELF')
        shutil.copyfile(source, native / name)
    require(digest(native / 'libproot-loader.so') == BRIDGE['libproot-loader.so'], 'Retained loader differs')
    assets = provider / 'assets/client-isolation'; assets.mkdir(parents=True, exist_ok=False)
    manifest = metadata(); encoded = json.dumps(manifest, indent=2) + '\n'
    (assets / 'manifest.json').write_text(encoded); (assets / 'NOTICE.txt').write_bytes(NOTICE)
    with zipfile.ZipFile(companion, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        require(not any(n.startswith('readonly/') for n in archive.namelist()), 'Do not replace isolation source evidence')
        archive.writestr('readonly/manifest.json', encoded); archive.writestr('readonly/NOTICE.txt', NOTICE)
        archive.write(build / 'build-evidence.json', 'readonly/build-evidence.json')
        archive.write(build / 'build.sh', 'readonly/generated-build-recipe.sh')
        archive.writestr('readonly/REPRODUCE.txt', 'Use the included launcher-source.zip. Run tools/build_client_bridge.py with --readonly and the pinned source/NDK ZIPs in a fresh directory. Original bridge remains unchanged. tools/check_client_readonly.py runs the native Linux fixture; neither build proves Android execution.\n')
    verify_companion(companion, manifest)
    print(json.dumps(dict(isolationStaged=True, checks=len(checks()), sourceCompanionSha256=digest(companion))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('bridge-build', 'provider', 'companion'): parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args(); stage(args.bridge_build, args.provider, args.companion)
