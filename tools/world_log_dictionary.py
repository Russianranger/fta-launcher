#!/usr/bin/env python3
"""Derive non-content log fingerprints from pinned original startup and warning sources.

Only SHA256 of 16 literal ASCII prefix bytes reaches the APK. Dynamic values,
format strings and original source code are not copied into public artifacts.
Matches locate source messages, not completed hooks or a proven crash cause.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

from fta_source import SOURCE_ROOT, SOURCE_SHA256, sha256_file

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'android/app/src/main/assets/metadata/world-log-prefixes.tsv'
PROOF = ROOT / 'docs/evidence/world-log-dictionary.json'
LOG = re.compile(r'LOG_(?:INFO|WARN|ERROR|FATAL)\s*\(\s*(?:"[^"\n]+"|[A-Z_]+)\s*,\s*((?:"(?:[^"\\]|\\.)*"\s*)+)')
WARN = re.compile(LOG.pattern.replace('(?:INFO|WARN|ERROR|FATAL)', 'WARN'))
LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')


def dictionary(sources):
    modules = sorted({name.split('/')[1] for name, data in sources.items()
                      if name.startswith('modules/') and 'void OnStartup()' in data.decode('utf-8')})
    selected = {name: data for name, data in sources.items() if
                (name.startswith('modules/') and name.split('/')[1] in modules) or
                name in ('src/server/apps/worldserver/Main.cpp', 'src/server/database/Database/MySQLConnection.cpp')}
    events = []
    warnings = {name: data for name, data in sources.items() if
                name.startswith(('src/server/', 'modules/')) and WARN.search(data.decode('utf-8'))}
    # Keep every existing startup ID stable. Append warning sites outside that original selection.
    entries = [(name, data, LOG) for name, data in sorted(selected.items())]
    entries += [(name, data, WARN) for name, data in sorted(warnings.items()) if name not in selected]
    for name, data, pattern in entries:
        source = data.decode('utf-8')
        for match in pattern.finditer(source):
            try:
                message = ''.join(json.loads(token) for token in LITERAL.findall(match[1]))
            except ValueError:
                continue
            prefix = message.split('{', 1)[0].encode()[:16]
            if len(prefix) != 16 or not all(32 <= byte <= 126 for byte in prefix):
                continue
            events.append({'id': f'w{len(events):04d}', 'source': name,
                           'line': source.count('\n', 0, match.start()) + 1,
                           'prefixSha256': hashlib.sha256(prefix).hexdigest()})
    if len(events) > 1000:
        raise ValueError('Fingerprint dictionary exceeds the runtime bound')
    asset = ''.join(f"{e['prefixSha256']}\t{e['id']}\n" for e in events)
    return asset, {'formatVersion': 1, 'prefixBytes': 16, 'startupModules': modules,
                   'warningSources': sorted(warnings),
                   'sourceSha256': {name: hashlib.sha256(data).hexdigest() for name, data in sorted((selected | warnings).items())},
                   'events': events, 'dictionarySha256': hashlib.sha256(asset.encode()).hexdigest(),
                   'dynamicValuesExported': False, 'hookCompletionProven': False, 'crashCauseProven': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-zip', type=Path, required=True)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    if sha256_file(args.source_zip) != SOURCE_SHA256:
        raise ValueError('Original source identity differs')
    with zipfile.ZipFile(args.source_zip) as archive:
        sources = {name.removeprefix(SOURCE_ROOT + '/'): archive.read(name) for name in archive.namelist()
                   if name.startswith(SOURCE_ROOT + '/') and name.endswith('.cpp')}
    asset, proof = dictionary(sources)
    proof['sourceArchiveSha256'] = SOURCE_SHA256
    encoded = json.dumps(proof, indent=2) + '\n'
    if args.write:
        ASSET.parent.mkdir(parents=True, exist_ok=True)
        ASSET.write_text(asset); PROOF.write_text(encoded)
    else:
        if ASSET.read_text() != asset or PROOF.read_text() != encoded:
            raise ValueError('Source fingerprint dictionary differs')
    print(f"Verified {len(proof['events'])} source message fingerprints; no native execution or crash diagnosis claimed")


if __name__ == '__main__':
    main()
