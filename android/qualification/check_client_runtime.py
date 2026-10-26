#!/usr/bin/env python3
"""Read-only audit of the exact public Wine/FEX candidate before isolated CI use.

Does not install, extract, execute or qualify an Android/client runtime.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import struct
import tarfile

LOCK = Path(__file__).with_name('client-runtime-lock.json')


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def file_identity(path, expected):
    require(path.is_file() and path.stat().st_size == expected['bytes'], 'runtime-size-mismatch')
    with path.open('rb') as stream:
        require(hashlib.file_digest(stream, 'sha256').hexdigest() == expected['sha256'], 'runtime-hash-mismatch')


def member_name(name):
    name = name.removeprefix('./').rstrip('/')
    require(0 < len(name) <= 4096 and not name.startswith('/') and
            all(part not in ('', '.', '..') for part in name.split('/')) and
            all(ord(c) >= 32 and c != '\\' for c in name), 'unsafe-runtime-path')
    return name


def inspect_tar(archive, required):
    entries = {}; links = {}; selected = {}; marker = None; total = 0; count = 0
    for item in archive:
        count += 1; require(count <= 50000, 'runtime-entry-limit')
        if item.name in ('.', './'):
            require(item.isdir() and '' not in entries, 'invalid-runtime-root')
            entries[''] = 'directory'; continue
        name = member_name(item.name)
        require(name not in entries and not item.issparse(), 'duplicate-or-sparse-runtime-entry')
        require(item.isfile() or item.isdir() or item.issym() or item.islnk(), 'special-runtime-entry')
        require(0 <= item.size <= 512 * 1024 * 1024, 'runtime-member-size-limit')
        total += item.size; require(total <= 4 * 1024**3, 'runtime-total-size-limit')
        entries[name] = 'file' if item.isfile() else 'directory' if item.isdir() else 'link'
        if item.issym() or item.islnk():
            target = item.linkname
            require(target and len(target) <= 4096 and all(ord(c) >= 32 and c != '\\' for c in target),
                    'unsafe-runtime-link')
            if item.islnk():
                target = member_name(target)
            else:
                # Absolute symlinks are guest-root paths. No archive member may be
                # extracted through a link, so they cannot redirect extraction.
                target = posixpath.normpath(target.lstrip('/') if target.startswith('/') else
                                           posixpath.join(posixpath.dirname(name), target))
                require(target != '..' and not target.startswith('../'), 'escaping-runtime-link')
            links[name] = (target, item.islnk())
        if name in required or name == 'etc/memento-client-runtime.json':
            require(item.isfile(), 'runtime-component-not-regular')
            stream = archive.extractfile(item)
            require(stream is not None, 'missing-runtime-component-stream')
            if name.endswith('.json'):
                require(item.size <= 4096, 'runtime-marker-limit')
                marker = json.loads(stream.read(4097))
            else:
                expected = required[name]
                require(item.size == expected['bytes'], 'runtime-component-size-mismatch')
                head = stream.read(min(4096, item.size)); digest = hashlib.sha256(head)
                for chunk in iter(lambda: stream.read(256 * 1024), b''):
                    digest.update(chunk)
                require(digest.hexdigest() == expected['sha256'], 'runtime-component-hash-mismatch')
                if 'elfMachine' in expected:
                    require(len(head) >= 64 and head[:6] == b'\x7fELF\x02\x01' and
                            int.from_bytes(head[18:20], 'little') == expected['elfMachine'],
                            'runtime-elf-mismatch')
                else:
                    require(len(head) >= 64 and head[:2] == b'MZ', 'runtime-pe-mismatch')
                    offset = struct.unpack_from('<I', head, 60)[0]
                    require(64 <= offset <= len(head) - 26 and head[offset:offset + 4] == b'PE\0\0' and
                            hex(struct.unpack_from('<H', head, offset + 4)[0]) == expected['peMachine'],
                            'runtime-pe-mismatch')
                selected[name] = expected
    for name in entries:
        require(all(str(parent) not in links for parent in PurePosixPath(name).parents),
                'runtime-entry-beneath-link')
    for target, hard in links.values():
        if hard:
            require(entries.get(target) == 'file', 'unsafe-runtime-hardlink')
    require(set(selected) == set(required), 'missing-runtime-components')
    require(type(marker) is dict, 'missing-runtime-marker')
    return dict(entries=count, fileBytes=total, linkCount=len(links), components=selected, marker=marker)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text())
    file_identity(args.archive, lock['archive']); file_identity(args.manifest, lock['manifest'])
    manifest = json.loads(args.manifest.read_text())
    require(all(manifest.get(k) == v for k, v in lock['marker'].items()) and
            all(manifest.get(k) == v for k, v in lock['archive'].items()), 'runtime-manifest-mismatch')
    with tarfile.open(args.archive, 'r|gz') as archive:
        result = inspect_tar(archive, lock['components'])
    require(result['entries'] == lock['archiveEntries'] and result['fileBytes'] == lock['archiveFileBytes'],
            'runtime-inventory-mismatch')
    require(result.pop('marker') == lock['marker'], 'runtime-marker-mismatch')
    result.update(formatVersion=1, scope='pinned-public-wine-fex-static-audit',
        archiveSha256=lock['archive']['sha256'], manifestSha256=lock['manifest']['sha256'],
        archiveIdentityValidated=True, archiveStructureValidated=True, componentIdentitiesValidated=True,
        archiveExtracted=False, wineExecuted=False, androidRuntimeQualified=False,
        originalHostExecuted=False, warcraftXLCompatible=False, gameLoginQualified=False)
    with args.output.open('x') as output:
        json.dump(result, output, indent=2); output.write('\n')
    print(json.dumps({'runtimeEntries': result['entries'], 'pinnedComponents': len(result['components'])}))


if __name__ == '__main__':
    main()
