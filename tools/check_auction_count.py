#!/usr/bin/env python3
"""Exercise original auction snapshot and Field conversions with synthetic rows.

No live database, server, player records or Android execution is involved.
"""
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
from check_script_unload import function
from fta_source import SOURCE_ROOT, SOURCE_SHA256, sha256_file

ROOT = Path(__file__).resolve().parents[1]
SELLER = 'modules/mod-auctionator/src/AuctionatorSeller.cpp'
FIELD = 'src/server/database/Database/Field.cpp'
PROFILE = 'android-arm64-world-v15'
SIGNATURE = 'AuctionatorSeller::AuctionHouseSnapshot AuctionatorSeller::BuildAuctionHouseSnapshot('


def verify_change(before, after):
    old = b'uint32 const count = fields[4].Get<uint32>();'
    new = b'uint32 const count = static_cast<uint32>(fields[4].Get<uint64>());'
    if before.count(old) != 1 or after != before.replace(old, new):
        raise ValueError('Auction patch must change only the reviewed COUNT getter')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source-zip', 'mysql-include', 'output', 'log-directory'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if sha256_file(args.source_zip) != SOURCE_SHA256:
        raise ValueError('Original source archive differs')
    check_jobs(1)
    profile = json.loads((ROOT / 'native-port/profiles.json').read_text())['profiles'][PROFILE]
    pin = profile['patches'][-1]
    patch = ROOT / 'native-port' / pin['path']
    if list(pin['files']) != [SELLER] or sha256_file(patch) != pin['sha256']:
        raise ValueError('Auction patch identity differs')
    args.log_directory.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'tests/fixtures/auction_count'
    with tempfile.TemporaryDirectory(prefix='fta-auction-count-') as temporary, zipfile.ZipFile(args.source_zip) as archive:
        work = Path(temporary); source = work / 'source'
        for item in archive.infolist():
            name = item.filename.removeprefix(SOURCE_ROOT + '/')
            if (item.is_dir() or name == item.filename or not name.startswith((
                    'src/common/', 'src/server/database/Database/', 'deps/fmt/include/'))):
                continue
            if Path(name).is_absolute() or '..' in Path(name).parts:
                raise ValueError('Unsafe source path')
            target = source / name; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(item))
        original = archive.read(SOURCE_ROOT + '/' + SELLER)
        target = source / SELLER; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(original)
        if sha256_file(target) != pin['files'][SELLER]['before_sha256']:
            raise ValueError('Original seller differs')
        for command in (['git', 'apply', '--check', str(patch)], ['git', 'apply', str(patch)]):
            subprocess.run(command, cwd=source, check=True, capture_output=True)
        fixed = target.read_bytes(); verify_change(original, fixed)
        if sha256_file(target) != pin['files'][SELLER]['after_sha256']:
            raise ValueError('Patched seller differs')
        util = (source / 'src/common/Utilities/Util.cpp').read_text()
        body = function(util, 'bool StringEqualI(')
        leaf = work / 'original-string-equal.cpp'; leaf.write_text('#include "Util.h"\n' + body)
        includes = ['src/common', 'src/common/Compiler', 'src/common/Debugging', 'src/common/Utilities',
                    'src/common/Logging', 'src/server/database/Database', 'deps/fmt/include']
        observed = {}
        env = dict(os.environ, ASAN_OPTIONS='detect_leaks=0:halt_on_error=1', UBSAN_OPTIONS='halt_on_error=1')
        for label, content in [('original', original), ('fixed', fixed)]:
            (work / 'snapshot.inc').write_text(function(content.decode(), SIGNATURE))
            for strict in (False, True):
                for sanitizer in (False, True):
                    tag = label + ('-strict' if strict else '-normal') + ('-asan-ubsan' if sanitizer else '-release')
                    flags = ['-O1', '-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-fno-pie', '-no-pie'] if sanitizer else ['-O2']
                    if strict: flags += ['-DACORE_STRICT_DATABASE_TYPE_CHECKS']
                    binary = work / tag
                    command = ['g++', '-std=gnu++20', '-DFMT_HEADER_ONLY', *flags,
                               '-I' + str(fixture), '-I' + str(work), '-I' + str(args.mysql_include)]
                    command += ['-I' + str(source / name) for name in includes]
                    command += [str(fixture / 'snapshot.cpp'), str(source / FIELD), str(leaf), '-o', str(binary)]
                    with (args.log_directory / (tag + '-build.log')).open('w') as log:
                        subprocess.run(command, check=True, stdout=log, stderr=log)
                    warning_count = (3 if strict else 2) if label == 'original' else 0
                    result = subprocess.run([str(binary), str(warning_count)], capture_output=True, env=env, timeout=10)
                    (args.log_directory / (tag + '.log')).write_bytes(result.stdout + result.stderr)
                    expected = f'rows=12 warnings={warning_count * 12} snapshot-equivalent=true\n'.encode()
                    if result.returncode != 0 or result.stdout != expected:
                        raise ValueError('Auction fixture failed: ' + tag)
                    observed[tag] = {'exitCode': result.returncode, 'rows': 12, 'warnings': warning_count * 12}
        proof = dict(status='original-auction-count-warning-reproduced-and-caller-fixed',
            sourceArchiveSha256=SOURCE_SHA256, sourceProfile=PROFILE, patchSha256=pin['sha256'],
            originalFieldSha256=sha256_file(source / FIELD), originalStringEqualILeafSha256=hashlib.sha256(body.encode()).hexdigest(),
            sellerSourceSha256=pin['files'][SELLER],
            fixtureSourceSha256={p.name: sha256_file(p) for p in sorted(fixture.iterdir()) if p.is_file()},
            originalSnapshotFunctionCompiled=True, originalFieldUnmodified=True, syntheticTextProtocolRows=True,
            snapshotCountConversionAndAccumulationPreserved=True, queryBytesUnchanged=True,
            emptyResultAndMissingOriginResolverCovered=True, uint32AndUint64BoundariesCovered=True,
            releaseAndAddressUndefinedSanitizersPassed=True, strictFieldTypesTestedBothModes=True,
            leakSanitizerClaimed=False, compilerJobs=1, liveDatabaseTested=False, androidExecutionQualified=False,
            measuredThorWarningCallerProven=False, databaseFallbackValidated=False, worldReady=False, results=observed)
        args.output.write_text(json.dumps(proof, indent=2) + '\n')
        print(proof['status'])


if __name__ == '__main__':
    main()
