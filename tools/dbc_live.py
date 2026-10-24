"""Host-only live Connector/C qualification of the unchanged original DBC path.

Only check_dbc_backup invokes this against its verified disposable restore.
The ten table grants are read-only; raw rows and credentials remain private.
This does not replace or qualify the Android world process or connection pool.
"""
import hashlib
import os
from pathlib import Path
import re
import secrets
import struct
import subprocess
import zipfile

from build_native import check_jobs
from check_dbc_compatibility import validate_rows
from fta_source import SOURCE_ROOT, SOURCE_SHA256, sha256_file

ROOT = Path(__file__).resolve().parents[1]
CONNECTOR_SHA256 = 'd5e5f789c858c952e98652fdb42704c4093c6169cc8af6196c9c49943a597f61'
ORIGINALS = (
    'src/common/DataStores/DBCFileLoader.cpp',
    'src/server/shared/DataStores/DBCStore.cpp',
    'src/server/shared/DataStores/DBCDatabaseLoader.cpp',
    'src/server/database/Database/QueryResult.cpp',
    'src/server/database/Database/Field.cpp',
)


def measured_routes(cases, tablemap):
    """Require every reviewed mapping; never interpolate report-provided SQL names."""
    kotlin = (ROOT / 'android/app/src/main/java/com/russianranger/fta/runtime/GameDataDbcCompatibility.kt').read_text()
    routes = {name: (table, int(rows)) for name, table, rows in re.findall(
        r'"dbc/([^"/]+)" to Route\("([a-z_]+)", (\d+)\)', kotlin)}
    if len(routes) != 10 or len(cases) != 10:
        raise ValueError('Incomplete reviewed GT routes')
    measured = {}
    for case in cases:
        name = case['asset'].removeprefix('dbc/')
        header = case['observedHeader']
        expected = routes.get(name)
        if name in measured or expected is None or tablemap.get(name) != expected[0]:
            raise ValueError('GT route differs from original source')
        rows = header['rows']
        if (rows != expected[1] or any(type(header[k]) is not int for k in
                ('rows', 'fields', 'recordBytes', 'stringBytes', 'fileBytes', 'magic')) or
                (header['fields'], header['recordBytes'], header['stringBytes'], header['fileBytes'], header['magic']) !=
                (1, 4, 1, 21 + rows * 4, 0x43424457)):
            raise ValueError('Unreviewed GT file layout')
        measured[name] = expected
    if measured != routes:
        raise ValueError('Incomplete measured GT coverage')
    return routes


def compile_loader(source_zip, work, connector_base):
    if sha256_file(source_zip) != SOURCE_SHA256:
        raise ValueError('Original source identity mismatch')
    library = connector_base / 'lib/libmariadb.so.3'
    if sha256_file(library) != CONNECTOR_SHA256:
        raise ValueError('Use the reviewed Connector/C 3.4.9 from the pinned 12.3.2 host distribution')
    check_jobs(1)
    source = work / 'source'
    with zipfile.ZipFile(source_zip) as z:
        for item in z.infolist():
            name = item.filename.removeprefix(SOURCE_ROOT + '/')
            if item.is_dir() or name == item.filename or not name.startswith((
                    'src/common/', 'src/server/shared/DataStores/',
                    'src/server/database/Database/', 'deps/fmt/include/')):
                continue
            if Path(name).is_absolute() or '..' in Path(name).parts:
                raise ValueError('Unsafe original source path')
            target = source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(item))
    # Compile the unchanged utility leaf used by original StringConvert/Field.
    # Avoid linking unrelated network, random, UTF-8 and server utility code.
    util = source / 'src/common/Utilities/Util.cpp'
    body = re.search(r'bool StringEqualI\(std::string_view a, std::string_view b\)\n\{[^}]*\}[^}]*\}', util.read_text())
    if body is None:
        raise ValueError('Original StringEqualI leaf changed')
    leaf = work / 'original-string-equal.cpp'
    leaf.write_text('#include "Util.h"\n' + body[0] + '\n')
    fixture = ROOT / 'tests/fixtures/dbc_live'
    includes = ['src/common', 'src/common/Compiler', 'src/common/Debugging', 'src/common/Utilities',
                'src/common/DataStores', 'src/common/Logging', 'src/server/shared/DataStores',
                'src/server/database/Database', 'deps/fmt/include']
    binary = work / 'dbc-live'
    command = ['g++', '-std=gnu++20', '-O1', '-g', '-fsanitize=address', '-fno-omit-frame-pointer',
               '-DACORE_STRICT_DATABASE_TYPE_CHECKS', '-DFMT_HEADER_ONLY', '-I' + str(fixture)]
    command += ['-I' + str(source / name) for name in includes]
    command += ['-I' + str(connector_base / 'include/mysql'), str(fixture / 'loader.cpp')]
    command += [str(source / name) for name in ORIGINALS]
    command += [str(leaf), str(library), '-Wl,-rpath,' + str(library.parent), '-o', str(binary)]
    subprocess.run(command, check=True)
    return binary, {
        'connectorVersion': '3.4.9', 'connectorLibrarySha256': CONNECTOR_SHA256,
        'originalSourceSha256': {name: sha256_file(source / name) for name in ORIGINALS},
        'originalStringEqualILeafSha256': hashlib.sha256(body[0].encode()).hexdigest(),
        'fixtureSourceSha256': {p.name: sha256_file(p) for p in sorted(fixture.iterdir()) if p.is_file()},
        'sourceArchiveSha256': SOURCE_SHA256, 'compilerJobs': 1, 'cppStandard': 20,
        'originalResultSetAndFieldUsed': True, 'strictFieldTypesEnabled': True,
        'originalLoaderSourcesUnmodified': True, 'originalAssertionsEnabled': True,
        'connectionPoolIsHostAdapter': True, 'androidExecuted': False,
        'databaseFallbackValidated': False, 'worldReady': False,
    }


def run_loader(cli, binary, work, snapshots, routes, port):
    assets = work / 'dbc-live-assets'; assets.mkdir()
    manifest = work / 'dbc-live-cases.tsv'
    manifest.write_text(''.join(f'{name}\t{table}\t{rows}\n' for name, (table, rows) in routes.items()))
    for name, (table, rows) in routes.items():
        validate_rows((snapshots / (table + '.tsv')).read_text(), rows)
        (assets / name).write_bytes(struct.pack('<4s4I', b'WDBC', rows, 1, 4, 1) + bytes(rows * 4 + 1))
    user = 'fta_dbc_readonly'; secret = secrets.token_hex(24)
    defaults = work / 'dbc-readonly.cnf'
    with open(defaults, 'x', opener=lambda p, flags: os.open(p, flags, 0o600)) as out:
        out.write('[client]\nuser=' + user + '\npassword=' + secret + '\n')
    # These are disposable fixture objects outside all three retained schemas.
    cli.query('CREATE DATABASE fta_dbc_empty;')
    cli.query(f"CREATE USER '{user}'@'127.0.0.1' IDENTIFIED BY '{secret}';")
    try:
        for table, _ in routes.values():
            cli.query(f'CREATE TABLE fta_dbc_empty.`{table}` LIKE acore_world.`{table}`;')
            for schema in ('acore_world', 'fta_dbc_empty'):
                cli.query(f"GRANT SELECT ON {schema}.`{table}` TO '{user}'@'127.0.0.1';")
        grants = cli.query(f"SHOW GRANTS FOR '{user}'@'127.0.0.1';").splitlines()
        wanted = {f'GRANT SELECT ON `{schema}`.`{table}` TO '
                  for schema in ('acore_world', 'fta_dbc_empty') for table, _ in routes.values()}
        if (len(grants) != 21 or sum(line.startswith('GRANT USAGE ON *.* TO ') for line in grants) != 1 or
                any('WITH GRANT OPTION' in line for line in grants) or
                not all(any(line.startswith(prefix) for line in grants) for prefix in wanted)):
            raise ValueError('Native probe role is not limited to the twenty read-only table grants')
        env = os.environ.copy()
        # Task inspection is unavailable in this runtime. ASan stays enabled.
        env['ASAN_OPTIONS'] = 'detect_leaks=0:abort_on_error=1'
        for mode, expected in (('live', 'liveTables=10 rows=8922'), ('empty', 'emptyTables=10 rows=0')):
            result = subprocess.run([str(binary), str(defaults), str(port), str(manifest),
                str(assets), str(snapshots), mode], capture_output=True, text=True, env=env, timeout=60)
            (work / ('dbc-' + mode + '.log')).write_text(result.stdout + result.stderr)
            if result.returncode or result.stdout.strip() != expected:
                raise RuntimeError('Native DBC ' + mode + ' fixture failed; private log retained during run')
    finally:
        cli.query(f"DROP USER '{user}'@'127.0.0.1';")
        cli.query('DROP DATABASE fta_dbc_empty;')
    return {'liveTablesLoaded': 10, 'liveRowsComparedBitExactly': 8922,
            'emptySqlTablesRemainFailed': 10, 'liveQueriesUseOriginalSql': True,
            'nativeRoleSelectOnly': True, 'addressSanitizerPassed': True,
            'leakSanitizerEnabled': False, 'retainedWorldRowsWritten': False,
            'syntheticDbcBodies': True, 'actualGameAssetsTested': False}
