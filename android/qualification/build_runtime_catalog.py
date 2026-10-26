#!/usr/bin/env python3
"""Generate extraction offsets/hashes from the unchanged, pinned public runtime.

The Android installer never interprets tar paths/headers. It streams only these
audited ranges after checking the complete compressed source identity. This is
public runtime metadata, never client/game data or a repackaged archive.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import tarfile

from check_client_runtime import LOCK, file_identity, inspect_tar, member_name, require


def catalog(path, lock):
    file_identity(path, lock['archive'])
    with tarfile.open(path, 'r|gz') as archive:
        audit = inspect_tar(archive, lock['components'])
    require(audit['entries'] == lock['archiveEntries'] and
            audit['fileBytes'] == lock['archiveFileBytes'] and audit['marker'] == lock['marker'],
            'runtime-inventory-mismatch')
    rows = ['\t'.join(('FTA_RUNTIME_CATALOG', '1', str(lock['archive']['bytes']), lock['archive']['sha256']))]
    with tarfile.open(path, 'r|gz') as archive:
        for item in archive:
            require(item.name not in ('.', './') and not item.pax_headers and
                    item.mode & ~0o777 == 0 and item.offset_data % 512 == 0, 'unsupported-runtime-metadata')
            name = member_name(item.name)
            kind = 'f' if item.isfile() else 'd' if item.isdir() else 's' if item.issym() else 'h'
            digest = '-'
            if item.isfile():
                digest = hashlib.file_digest(archive.extractfile(item), 'sha256').hexdigest()
            else:
                require(item.size == 0, 'non-file-runtime-payload')
            target = item.linkname if item.issym() else member_name(item.linkname) if item.islnk() else '-'
            require(all(c not in name + target for c in '\t\r\n'), 'invalid-catalog-field')
            rows.append('\t'.join((kind, name, str(item.mode), str(item.size), str(item.offset_data), digest, target)))
    expanded = hashlib.sha256(); size = 0
    with gzip.open(path, 'rb') as source:
        for chunk in iter(lambda: source.read(256 * 1024), b''):
            size += len(chunk); require(size <= 4 * 1024**3, 'runtime-expanded-limit'); expanded.update(chunk)
    rows.append('\t'.join(('END', str(size), expanded.hexdigest())))
    return ('\n'.join(rows) + '\n').encode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text())
    data = catalog(args.archive, lock)
    identity = dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
    if 'catalog' in lock:
        require(identity == lock['catalog'], 'runtime-catalog-drift')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('xb') as output:
        output.write(data)
    print(json.dumps(identity))


if __name__ == '__main__':
    main()
