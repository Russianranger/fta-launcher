#!/usr/bin/env python3
"""Extend the exact private corresponding-source companion with the reviewed world-session repairs.

All original source archives/notices remain byte-identical. No binaries or keys.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

from stage_android_provider import digest

ROOT = Path(__file__).resolve().parents[1]
BASE_SHA = '5e2532f8e78f1de0453e73f59c9d07fc4353cfdd393e355f3c22e0e443a334f1'
BASE_SUMS_SHA = 'fe386a4c654bc6041a67ed3d956985ec0758eaa281d2cd64bc1effa3f4069c0f'
BASE_PREFIX = 'fta-corresponding-source/'
PREFIX = 'world-session-v15/'
INDEX = PREFIX + 'CONTENTS.json'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def source_files(root):
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '--',
        'LICENSE', 'native-port', 'native', 'tools', 'tests', 'licenses', 'manifests'], cwd=root, text=True).splitlines()
    return sorted(set(names))


def record(data):
    return {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def inventory(archive):
    names = archive.namelist()
    require(len(names) == len(set(names)), 'Duplicate source companion member')
    for entry in archive.infolist():
        p = PurePosixPath(entry.filename)
        require(not p.is_absolute() and '..' not in p.parts and '\\' not in entry.filename and
                not entry.is_dir() and entry.file_size <= 768 * 1024 * 1024 and
                entry.external_attr >> 16 & 0o170000 != 0o120000, 'Unsafe source companion member')
    return names


def verify_baseline(archive, actual):
    sums_name = BASE_PREFIX + 'SHA256SUMS'
    sums = archive.read(sums_name)
    require(hashlib.sha256(sums).hexdigest() == BASE_SUMS_SHA, 'Original source checksum inventory differs')
    expected = {sums_name}
    for line in sums.decode('utf-8').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  (.+)', line)
        require(match is not None, 'Malformed original checksum record')
        name = BASE_PREFIX + match[2]
        require(name not in expected and actual.get(name, {}).get('sha256') == match[1],
                'Original corresponding-source member differs')
        expected.add(name)
    require({name for name in actual if not name.startswith(PREFIX)} == expected,
            'Original corresponding-source inventory differs')


def verify_companion(path, root=ROOT):
    with zipfile.ZipFile(path) as archive:
        names = inventory(archive)
        index = json.loads(archive.read(INDEX))
        require(index['baselineCompanionSha256'] == BASE_SHA, 'Corresponding-source baseline differs')
        require(set(names) == set(index['files']) | {INDEX}, 'Source companion inventory differs')
        actual = {name: record(archive.read(name)) for name in index['files']}
        require(actual == index['files'], 'Source companion member differs')
        verify_baseline(archive, actual)
        expected = source_files(root)
        require(index['repositoryFiles'] == expected, 'Public reproduction source inventory differs')
        for name in expected:
            require(archive.read(PREFIX + 'repository/' + name) == (root / name).read_bytes(),
                    'Corresponding reproduction source differs')
        pin = json.loads((root / 'manifests/world-session.json').read_text())
        require(index['worldSession'] == pin, 'Source companion targets another world executable')
        evidence = json.loads(archive.read(PREFIX + 'BUILD_EVIDENCE.json'))
        require(evidence['status'] == 'compiled-world-session-qualified' and evidence['moduleSha256'] == pin['moduleSha256']
                and evidence['sourceTreeSha256'] == pin['sourceTreeSha256'] and evidence['requiredModuleCount'] == 52
                and evidence['sqlAndConfigurationsByteIdentical'] is True, 'Source companion build proof differs')
    return digest(path)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('base', 'build-evidence', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    args = p.parse_args()
    require(not args.output.exists() and digest(args.base) == BASE_SHA, 'Exact source companion base required; output must be fresh')
    pin = json.loads((ROOT / 'manifests/world-session.json').read_text())
    public = source_files(ROOT)
    index = dict(baselineCompanionSha256=BASE_SHA, worldSession=pin, repositoryFiles=public, files={})
    with zipfile.ZipFile(args.base) as old, zipfile.ZipFile(args.output, 'x', compression=zipfile.ZIP_STORED) as out:
        inventory(old)
        def add(name, data):
            require(name not in index['files'] and name != INDEX, 'Source companion extension collision')
            out.writestr(name, data); index['files'][name] = record(data)
        for name in old.namelist():
            require(not name.startswith(PREFIX), 'Source companion extension already exists')
            add(name, old.read(name))
        for name in public:
            path = ROOT / name
            require(path.is_file() and not path.is_symlink(), 'Reproduction source must be a regular file')
            add(PREFIX + 'repository/' + name, path.read_bytes())
        add(PREFIX + 'BUILD_EVIDENCE.json', args.build_evidence.read_bytes())
        add(PREFIX + 'README.txt', (
            'This additive world-session build preserves all baseline source archives, notices and recipes above.\n'
            'Use world-session-v15/repository as the reviewed launcher checkout.\n'
            'Stage the original source ZIP with tools/fta_source.py; apply android-arm64-world-v15 with tools/fta_port.py.\n'
            'Reproduce dependencies using native-port/build-dependencies.py and build-world-dependencies.py, NDK r28c, API33.\n'
            'Reuse the exact retained provider libraries with the generated target headers.\n'
            'Run tools/build_native.py --platform android --stage world --port-profile android-arm64-world-v15 --jobs 1,\n'
            'with explicit source/build/toolchain/dependency-prefix/mysql-library/mysql-include/mysql-executable paths.\n'
            'Use the original Lua 5.2.4 archive hash, static scripts/modules, C++20 and all 52 modules.\n'
            'tools/verify_world_session.py verifies the actual file-API, link, source and baseline dependency evidence.\n'
            'The script-unload, auction-count and fault-witness v2 host probes are included.\n'
            'This companion contains no APK, server binary, live database, game assets or signing material.\n'
            'Compilation evidence does not qualify Android runtime or graceful shutdown.\n').encode())
        out.writestr(INDEX, json.dumps(index, indent=2) + '\n')
    print(json.dumps({'sha256': verify_companion(args.output), 'bytes': args.output.stat().st_size,
                      'baselineMembersPreserved': len(index['files']) - len(public) - 2}))


if __name__ == '__main__':
    main()
