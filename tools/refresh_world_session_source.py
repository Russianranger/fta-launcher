#!/usr/bin/env python3
"""Refresh reproduction helpers without changing any retained native source/build.

All original archives/notices and the world build evidence stay byte-identical.
The resulting archive has a newly measured identity; it never reuses an old SHA.
"""
import argparse
import json
from pathlib import Path
import zipfile

from create_world_session_source import ROOT, BASE_SHA, PREFIX, INDEX, inventory, record, require, source_files, verify_baseline, verify_companion
from stage_android_provider import digest


def refresh(source, expected_sha, output, root=ROOT):
    require(not output.exists() and digest(source) == expected_sha, 'Exact retained companion required; output must be new')
    with zipfile.ZipFile(source) as old:
        names = inventory(old); previous = json.loads(old.read(INDEX))
        require(previous['baselineCompanionSha256'] == BASE_SHA and set(names) == set(previous['files']) | {INDEX}, 'Source inventory differs')
        actual = {name: record(old.read(name)) for name in previous['files']}
        require(actual == previous['files'], 'Retained source member differs'); verify_baseline(old, actual)
        pin = json.loads((root / 'manifests/world-session.json').read_text())
        require(previous['worldSession'] == pin, 'Native world identity changed; a new build companion is required')
        # Permit packaging and regression changes. All previously supplied native
        # code, recipes, schemas, notices and compilation helpers remain exact.
        for name in previous['repositoryFiles']:
            # The independent PRoot recipe and this source repackager do not
            # participate in the retained world build. Its recipes remain exact.
            if name in ('tools/package_private_update.py', 'tools/build_client_bridge.py',
                        'tools/refresh_world_session_source.py') or name.startswith('tests/'):
                continue
            require((root / name).is_file() and old.read(PREFIX + 'repository/' + name) == (root / name).read_bytes(),
                    'Retained native reproduction input changed: ' + name)
        public = source_files(root)
        index = dict(baselineCompanionSha256=BASE_SHA, refreshedFromCompanionSha256=expected_sha,
                     worldSession=pin, repositoryFiles=public, files={})
        with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_STORED) as out:
            def add(name, data):
                require(name not in index['files'] and name != INDEX, 'Source collision')
                out.writestr(name, data); index['files'][name] = record(data)
            for name in names:
                if not name.startswith(PREFIX):
                    add(name, old.read(name))
            for name in public:
                path = root / name; require(path.is_file() and not path.is_symlink(), 'Invalid reproduction source')
                add(PREFIX + 'repository/' + name, path.read_bytes())
            add(PREFIX + 'BUILD_EVIDENCE.json', old.read(PREFIX + 'BUILD_EVIDENCE.json'))
            add(PREFIX + 'README.txt', old.read(PREFIX + 'README.txt') +
                b'\nDistribution helpers refreshed; every original source archive/notice and native build proof is unchanged.\n')
            out.writestr(INDEX, json.dumps(index, indent=2) + '\n')
    return verify_companion(output, root)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--source-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(dict(sha256=refresh(args.source, args.source_sha256, args.output), bytes=args.output.stat().st_size,
                         originalNativeSourcesChanged=False, nativeBuildEvidenceChanged=False)))
