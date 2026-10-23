#!/usr/bin/env python3
"""Qualify original host authserver with production Kotlin wire/lifecycle and JNI; private seed never leaves scratch."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import time
import subprocess
import zipfile

from run_database_gate import sandbox

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('server', 'initializer', 'client', 'auth', 'libraries', 'database-zip', 'java-home', 'jni-header', 'kotlin-classes', 'kotlin-stdlib', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((ROOT / 'manifests/auth-seed-lock.json').read_text())
    assert sha(args.database_zip) == lock['archiveSha256'] and args.database_zip.stat().st_size == lock['archiveSize']
    with sandbox(str(args.server), str(args.initializer), str(args.client), transport='loopback') as cli:
        work = cli.work
        shutil.copyfile(args.jni_header, work / 'jni.h')
        subprocess.run(['g++', '-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-pthread', '-fPIC', '-shared',
            '-I' + str(work),
            str(ROOT / 'native/process-supervisor/process_owner.cpp'), str(ROOT / 'native/process-supervisor/process_supervisor.cpp'),
            '-o', str(work / 'libfta_process.so')], check=True)
        cp = ':'.join(str(p.resolve()) for p in (work, args.kotlin_classes, args.kotlin_stdlib))
        subprocess.run([str(args.java_home / 'bin/java'), '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', cp, '-d', str(work), str(ROOT / 'tests/fixtures/LocalAuthHost.java')], check=True)
        with zipfile.ZipFile(args.database_zip) as z:
            for directory in lock['directories']:
                (work / 'seed' / directory).mkdir(parents=True, exist_ok=True)
            cli.query('CREATE DATABASE acore_auth CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
            for entry in sorted(lock['files'], key=lambda e: e['targetPath']):
                data = z.read(entry['path'])
                assert len(data) == entry['size'] and hashlib.sha256(data).hexdigest() == entry['sha256']
                target = work / 'seed' / entry['targetPath']; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
                if entry['role'] == 'auth-table':
                    with target.open('rb') as script:
                        cli.run(script=script, schema='acore_auth', capture=False)
        assert cli.query("SELECT COUNT(*) FROM acore_auth.account WHERE username='FTA0123456789ABC';") == '0'
        accounts = cli.query('SELECT COUNT(*) FROM acore_auth.account;')
        password = secrets.token_hex(24)
        port = next(x.split('=', 1)[1] for x in cli.command if x.startswith('--port='))
        props = {'client': str(args.client), 'auth': str(args.auth), 'libraries': str(args.libraries), 'password': password, 'databasePort': port}
        session = work / 'host-session.properties'; session.write_text(''.join(k + '=' + v + '\n' for k, v in props.items())); session.chmod(0o600)
        (work / 'client.args').write_text('\n'.join(cli.command[1:]) + '\n')
        result = subprocess.run([str(args.java_home / 'bin/java'), '-Djava.library.path=' + str(work), '-cp', cp, 'LocalAuthHost', str(work)],
            capture_output=True, text=True, timeout=180)
        if result.returncode:
            # Only JVM exception summaries; never emit native logs, SQL, credentials or rows.
            raise RuntimeError('Host auth protocol failed: ' + result.stderr[-3000:])
        assert result.stdout.splitlines() == ['originalAuthProtocolAndRestartsPassed=true', 'authCleanStops=2', 'databaseHealthChecks=20', 'staleStartupFlagCleared=false', 'generatedDatabaseRoleAndCleanupPassed=true']
        assert cli.query('SELECT COUNT(*) FROM acore_auth.account;') == accounts
        assert cli.query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='acore_auth';") == '27'
        version = cli.query('SELECT VERSION();'); assert version == '12.3.2-MariaDB'
        cli.query('SHUTDOWN;')
        end = time.monotonic() + 15
        while True:
            try:
                with socket.create_connection(('127.0.0.1', int(port)), timeout=1): pass
            except ConnectionRefusedError:
                break
            if time.monotonic() >= end: raise RuntimeError('Database listener remained open after SQL shutdown')
            time.sleep(0.1)
    sources = ['android/app/src/main/java/com/russianranger/fta/runtime/LocalAuthProbe.kt',
        'android/app/src/main/java/com/russianranger/fta/runtime/LocalRealmAuthState.kt',
        'android/app/src/main/java/com/russianranger/fta/runtime/AuthDiagnosticConfig.kt',
        'native/process-supervisor/process_owner.cpp', 'native/process-supervisor/process_supervisor.cpp',
        'tests/fixtures/LocalAuthHost.java', 'tools/check_local_auth.py']
    proof = {'formatVersion': 1, 'scope': 'host-original-auth-listener-protocol-and-jni', 'androidExecuted': False,
        'originalAuthTablesImportedUnchanged': 27, 'originalAuthUpdaterEnabled': True, 'configuredRealmExplicitlyKeptOffline': True, 'authCleanStops': 2,
        'build12340UnknownAccountProtocolPassed': True, 'closedListenerAfterBothStops': True,
        'databaseHealthChecks': 20, 'existingGameAccountCountUnchanged': True, 'temporaryDatabaseRoleRemoved': True,
        'productionUuidDatabaseRoleAndConfigUsed': True,
        'hostDatabaseVersion': version, 'hostDatabaseTransport': 'private-generated-loopback', 'databaseSqlShutdownRequested': True, 'databaseListenerClosedAfterShutdown': True,
        'serverSha256': sha(args.server), 'authserverSha256': sha(args.auth), 'databaseZipSha256': lock['archiveSha256'],
        'sourceSha256': {name: sha(ROOT / name) for name in sources}, 'gameLoginQualified': False, 'worldReady': False}
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
