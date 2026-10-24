#!/usr/bin/env python3
"""Check reported DBC layouts using unchanged original loaders and private SQL snapshots.

The SQL adapter supplies exact two-column query results captured from an isolated,
hash-verified Thor backup. It does not qualify a live Android database connection,
full world startup, or the contents of actual game assets.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import tempfile
from pathlib import Path
import zipfile

from build_native import check_jobs
from check_game_data_source import synthetic_assets
from create_game_data_policy import FILES, cpp_text, derive
from fta_source import SOURCE_ROOT, SOURCE_SHA256, sha256_file

ROOT = Path(__file__).resolve().parents[1]


def validate_rows(text, count):
    rows = [line.split('\t') for line in text.splitlines()]
    if len(rows) != count or any(len(row) != 2 for row in rows):
        raise ValueError('Incomplete SQL result')
    if [int(row[0]) for row in rows] != list(range(count - 1, -1, -1)):
        raise ValueError('SQL result lacks exact descending ID coverage')
    import math
    if not all(math.isfinite(float(row[1])) for row in rows):
        raise ValueError('SQL result contains a non-finite coefficient')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source-zip', 'report', 'database-proof', 'database-snapshots', 'fixture-output', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--cxx', default='g++')
    parser.add_argument('--disable-leak-check', action='store_true', help='For ptrace/container hosts where LeakSanitizer cannot inspect tasks; address checks stay enabled')
    args = parser.parse_args()
    assert sha256_file(args.source_zip) == SOURCE_SHA256
    check_jobs(1)
    report = json.loads(args.report.read_text())
    assert report['apkSha256'] == '4f3f224abaae30837ded42fbccfbefcf52f07df68a5a07fa8a7a76400555bdb5'
    problems = report['gameDataDiagnostic']['problems']
    assert len(problems) == report['gameDataDiagnostic']['problemCount'] == 12
    database = json.loads(args.database_proof.read_text())
    assert database['retainedMetadataMatchesBeforeAndAfter'] and database['cleanSqlShutdown']
    assert database['backupSha256'] == '0b5fb6ef6912add622f6371d5a1cfcbde3bd63155d03b95ecec6e6d0fa36df8f'
    with tempfile.TemporaryDirectory(prefix='fta-dbc-compat-') as temp:
        work = Path(temp)
        with zipfile.ZipFile(args.source_zip) as z:
            source = {name: z.read(SOURCE_ROOT + '/' + name) for name in FILES}
            policy = derive(source)
            assert policy == (ROOT / 'manifests/game-data-policy.properties').read_text()
            loads = cpp_text(source[FILES[0]])
            formats = dict(re.findall(r'char constexpr (\w+)\[\]\s*=\s*"([A-Za-z]+)"', cpp_text(source[FILES[1]])))
            stores = dict(re.findall(r'DBCStorage\s*<[^>]+>\s+(\w+)\((\w+)\)', loads))
            routes = {name: (formats[stores[store]], table) for store, name, table in
                      re.findall(r'LOAD_DBC\((\w+),\s*"([^"]+)",\s*"([^"]+)"\)', loads)}
            assert routes['PowerDisplay.dbc'][0] == 'nixxxx'
            assert '"CharHairTextures.dbc"' not in loads
            load_body = loads[loads.index('inline void LoadDBC'):loads.index('void LoadDBCStores')]
            assert load_body.index('if (dbTable)') > load_body.index('if (storage.Load(')
            assert 'storage.LoadFromDB(dbTable, storage.GetFormat());' in load_body
            assert 'if (storage.GetNumRows())' in load_body
            scanned = 0
            for info in z.infolist():
                name = info.filename.removeprefix(SOURCE_ROOT + '/')
                if name.startswith(('src/server/', 'src/common/', 'modules/')) and name.endswith(('.cpp', '.h', '.hpp', '.c')):
                    scanned += 1
                    assert '"CharHairTextures.dbc"' not in cpp_text(z.read(info)), name
                if info.is_dir() or not name.startswith(('src/common/', 'src/server/shared/DataStores/', 'deps/fmt/include/')):
                    continue
                target = work / 'source' / name
                assert '..' not in target.relative_to(work / 'source').parts
                target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(z.read(info))
        assert scanned > 2000
        assets = synthetic_assets(policy)
        parameters = bytearray(assets['mmaps/000.mmap']); struct.pack_into('<I', parameters, 24, 0x80000000)
        assets['mmaps/000.mmap'] = bytes(parameters)
        manifest = []
        for problem in problems:
            path = problem['asset']; name = path.split('/')[-1]; header = problem['observedHeader']
            rows, fields, record, strings = [header[k] for k in ('rows', 'fields', 'recordBytes', 'stringBytes')]
            assert header['magic'] == 0x43424457 and header['fileBytes'] == 20 + rows * record + strings
            content = bytearray(struct.pack('<4s4I', b'WDBC', rows, fields, record, strings) + bytes(rows * record + strings))
            if name.startswith('gt'):
                fmt, table = routes[name]; assert fmt == 'df' and (fields, record, strings) == (1, 4, 1)
                entry = database['tables'][table]; assert entry['rows'] == rows and entry['allFileRowIndicesCovered']
                data = args.database_snapshots / (table + '.tsv')
                assert sha256_file(data) == entry['privateQueryResultSha256']
                validate_rows(data.read_text(), rows)
                manifest.append(f'{name}\t{table}\t{rows}\n')
                for i in range(rows): struct.pack_into('<f', content, 20 + i * 4, -123.0)
            elif name == 'PowerDisplay.dbc':
                assert (rows, fields, record, strings) == (7, 6, 15, 117)
                for i in range(rows):
                    struct.pack_into('<II', content, 20 + i * record, i + 1, i + 100)
                    content[28 + i * record:35 + i * record] = b'ignored'
            else:
                assert name == 'CharHairTextures.dbc' and (rows, fields, record, strings) == (181, 8, 32, 0)
            assets[path] = bytes(content)
        assert len(manifest) == 10
        compatibility_source = ROOT / 'android/app/src/main/java/com/russianranger/fta/runtime/GameDataDbcCompatibility.kt'
        kotlin_routes = {name: (table, int(rows)) for name, table, rows in re.findall(
            r'"dbc/([^"/]+)" to Route\("([a-z_]+)", (\d+)\)', compatibility_source.read_text())}
        expected_routes = {line.split()[0]: (line.split()[1], int(line.split()[2])) for line in manifest}
        assert kotlin_routes == expected_routes, 'Launcher routes differ from original source and retained table coverage'
        for name, data in assets.items():
            p = work / 'assets' / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)
        manifest_file = work / 'cases.tsv'; manifest_file.write_text(''.join(manifest))
        includes = ['src/common', 'src/common/Compiler', 'src/common/Debugging', 'src/common/Utilities',
                    'src/common/DataStores', 'src/server/shared/DataStores', 'deps/fmt/include']
        fixture = ROOT / 'tests/fixtures/dbc_compat'
        originals = ['src/common/DataStores/DBCFileLoader.cpp', 'src/server/shared/DataStores/DBCStore.cpp',
                     'src/server/shared/DataStores/DBCDatabaseLoader.cpp']
        command = [args.cxx, '-std=c++20', '-O1', '-g', '-fsanitize=address', '-fno-omit-frame-pointer',
                   '-DFMT_HEADER_ONLY', '-I' + str(fixture)]
        command += ['-I' + str(work / 'source' / p) for p in includes]
        command += [str(fixture / 'loader.cpp')] + [str(work / 'source' / p) for p in originals] + ['-o', str(work / 'loader')]
        subprocess.run(command, check=True)
        environment = os.environ.copy()
        if args.disable_leak_check: environment['ASAN_OPTIONS'] = 'detect_leaks=0'
        subprocess.run([str(work / 'loader'), str(work / 'assets'), str(args.database_snapshots), str(manifest_file)], check=True, env=environment)
        args.fixture_output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.fixture_output, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, data in assets.items(): z.writestr(name, data)
        proof = dict(formatVersion=1, scope='original-dbc-loaders-with-reported-headers-and-captured-private-sql',
                     sourceArchiveSha256=SOURCE_SHA256, deviceReportSha256=sha256_file(args.report),
                     policySha256=hashlib.sha256(policy.encode()).hexdigest(), database=database,
                     originalLoaderSourceSha256={p: sha256_file(work / 'source' / p) for p in originals},
                     fixtureSourceSha256={p.name: sha256_file(p) for p in fixture.iterdir() if p.is_file()},
                     launcherCompatibilitySourceSha256=sha256_file(compatibility_source), launcherDatabaseRoutesMatchOriginalSourceAndBackup=True,
                     fixtureSourceMmapHighBitCapacityIncluded=True,
                     originalLoadDbcSqlFallbackOrderVerified=True, originalRequiredDbcInventoryUnchanged=True,
                     serverCommonModuleFilesScanned=scanned, charHairTexturesNotReferencedByServer=True,
                     reportedZeroStringHeaderAcceptedByOriginalFileLoader=True, packedPowerDisplayRowsReadCorrectly=7,
                     gtTablesLoadedFromCapturedOriginalQueries=10, gtRowsComparedExactly=8922, emptySqlFallbackFails=True,
                     originalLoaderSourcesUnmodified=True, originalAssertionsEnabled=True, addressSanitizerPassed=True,
                     leakSanitizerEnabled=not args.disable_leak_check,
                     compilerJobs=1, cppStandard=20, databaseConnectorIsHostFixture=True,
                     actualGameAssetsTested=False, androidExecuted=False, worldReady=False,
                     nativeFullAssetSemanticsQualified=False, originalSqlOrMetadataExpectationsChanged=False)
        args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(proof, indent=2) + '\n')
    print('Original loader compatibility checks passed; actual game data and Android world loading remain unqualified')


if __name__ == '__main__':
    main()
