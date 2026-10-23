#!/usr/bin/env python3
"""Test original authserver against an isolated production restore of an exact private realm backup."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import zipfile

from run_database_gate import DatabaseCLI
from check_local_auth import ROOT, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('server', 'client', 'auth', 'libraries', 'database-zip', 'backup', 'java-home', 'jni-header', 'kotlin-classes', 'kotlin-stdlib', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--backup-sha256', required=True)
    args = parser.parse_args()
    assert sha(args.backup) == args.backup_sha256
    lock_path = ROOT / 'manifests/world-updater-lock.json'; lock = json.loads(lock_path.read_text())
    assert sha(args.database_zip) == lock['archiveSha256']
    with tempfile.TemporaryDirectory(prefix='fta-local-auth-backup-') as name:
        work = Path(name); work.chmod(0o700)
        shutil.copyfile(args.jni_header, work / 'jni.h')
        subprocess.run(['g++', '-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-pthread', '-fPIC', '-shared', '-I' + str(work),
            str(ROOT / 'native/process-supervisor/process_owner.cpp'), str(ROOT / 'native/process-supervisor/process_supervisor.cpp'),
            '-o', str(work / 'libfta_process.so')], check=True)
        cp = ':'.join(str(p.resolve()) for p in (work, args.kotlin_classes, args.kotlin_stdlib))
        java = str(args.java_home / 'bin/java')
        subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', cp, '-d', str(work)] +
            [str(ROOT / 'tests/fixtures' / n) for n in ('LocalAuthHost.java', 'LocalAuthBackupHost.java')], check=True)
        command = [java, '-Djava.library.path=' + str(work), '-cp', cp]
        result = subprocess.run(command + ['LocalAuthBackupHost', 'restore', str(work), str(lock_path), str(args.backup), args.backup_sha256],
            capture_output=True, text=True, timeout=120)
        assert result.returncode == 0, 'Production restore failed: ' + result.stderr[-2000:]
        assert result.stdout.strip() == 'restoredFilesVerified=true'
        with zipfile.ZipFile(args.database_zip) as z:
            for item in lock['includeDirectories']:
                (work / 'seed/Data' / item['path']).mkdir(parents=True, exist_ok=True)
            for entry in lock['runtimeSqlFiles']:
                data = z.read(entry['path']); assert len(data) == entry['size'] and hashlib.sha256(data).hexdigest() == entry['sha256']
                target = work / 'seed' / entry['targetPath']; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0)); port = reservation.getsockname()[1]
        # The Thor's root@localhost credentials are for a Unix socket. This host
        # only allows TCP, so provision a fresh host-only role in the disposable copy.
        host_secret = secrets.token_hex(24)
        host_defaults = work / 'host-client.cnf'
        host_defaults.write_text('[client]\nuser=fta_backup_host\npassword=' + host_secret + '\n'); host_defaults.chmod(0o600)
        init = work / 'host-init.sql'
        init.write_text("CREATE USER 'fta_backup_host'@'127.0.0.1' IDENTIFIED BY '" + host_secret + "';\nGRANT ALL PRIVILEGES ON *.* TO 'fta_backup_host'@'127.0.0.1' WITH GRANT OPTION;\n"); init.chmod(0o600)
        cli = DatabaseCLI(str(args.client), work / 'unused.sock', work, port=port, defaults_file=host_defaults)
        base = args.server.parent.parent
        server_args = [str(args.server), '--no-defaults', '--basedir=' + str(base), '--datadir=' + str(work / 'restored/database'),
            '--socket=', '--bind-address=127.0.0.1', '--port=' + str(port), '--pid-file=' + str(work / 'db.pid'),
            '--init-file=' + str(init), '--skip-name-resolve', '--performance-schema=OFF', '--innodb-buffer-pool-size=64M', '--innodb-log-file-size=16M',
            '--aria-pagecache-buffer-size=16M', '--key-buffer-size=8M', '--max-connections=8', '--thread-cache-size=2']
        if os.geteuid() == 0: server_args.append('--user=root')
        with (work / 'database.log').open('w') as log:
            process = subprocess.Popen(server_args, stdout=log, stderr=subprocess.STDOUT, cwd=work)
            try:
                end = time.monotonic() + 45
                last_error = ''
                while True:
                    assert process.poll() is None, 'Restored host database exited before readiness'
                    try:
                        if cli.query('SELECT 1;') == '1': break
                    except Exception as error: last_error = str(error)
                    if time.monotonic() >= end:
                        text = (work / 'database.log').read_text(errors='replace')
                        markers = [m for m in ('ready for connections', 'Access denied', 'ERROR', 'syntax', 'init-file', 'Shutdown complete') if m in text]
                        raise RuntimeError('Restored host database readiness deadline: ' + last_error + '; observed markers=' + ','.join(markers))
                    time.sleep(0.1)
                assert cli.query('SELECT VERSION();') == '12.3.2-MariaDB'
                def metadata():
                    for schema in ('acore_auth', 'acore_characters', 'acore_world'):
                        with (work / (schema + '.sql')).open('rb') as script:
                            (work / (schema + '.out')).write_text(cli.run(script=script))
                    checked = subprocess.run(command + ['LocalAuthBackupHost', 'verify', str(work), str(lock_path)],
                        capture_output=True, text=True, timeout=30)
                    assert checked.returncode == 0 and checked.stdout.strip() == 'strictMetadataChecks=24', 'Restored metadata differs'
                metadata()
                counts = cli.query('SELECT COUNT(*) FROM acore_auth.account; SELECT COUNT(*) FROM acore_characters.characters;')
                assert cli.query("SELECT COUNT(*) FROM acore_auth.account WHERE username='FTA0123456789ABC';") == '0'
                password = secrets.token_hex(24)
                props = {'client': str(args.client), 'auth': str(args.auth), 'libraries': str(args.libraries), 'password': password, 'databasePort': str(port)}
                session = work / 'host-session.properties'; session.write_text(''.join(k + '=' + v + '\n' for k, v in props.items())); session.chmod(0o600)
                (work / 'client.args').write_text('\n'.join(cli.command[1:]) + '\n')
                result = subprocess.run(command + ['LocalAuthHost', str(work)], capture_output=True, text=True, timeout=180)
                if result.returncode:
                    text = ''.join((work / n).read_text(errors='replace')[:65536] for n in ('auth1.out', 'auth1.err') if (work / n).exists())
                    markers = [m for m in ('No valid realms specified', 'Failed to initialize network', 'Access denied', 'Could not prepare statements', 'Failed to open', 'does not exist', 'not found', 'Updating Auth database', 'up-to-date', 'Halting process', 'ERROR') if m in text]
                    sql_errors = sorted(set(re.findall(r'ERROR ([0-9]{1,5})', text)))
                    eligibility = cli.query("SELECT COUNT(*)>0 FROM acore_auth.realmlist; SELECT COUNT(*)>0 FROM acore_auth.realmlist WHERE (flag | 2) <> 3;")
                    raise RuntimeError('Original auth on restored backup failed; markers=' + ','.join(markers) + '; SQL errors=' + ','.join(sql_errors) + '; realm eligibility booleans=' + eligibility.replace('\n', ',') + '; ' + result.stderr[-1200:])
                assert result.stdout.splitlines() == ['originalAuthProtocolAndRestartsPassed=true', 'authCleanStops=2', 'databaseHealthChecks=20', 'staleStartupFlagCleared=true', 'generatedDatabaseRoleAndCleanupPassed=true']
                metadata()
                assert cli.query('SELECT COUNT(*) FROM acore_auth.account; SELECT COUNT(*) FROM acore_characters.characters;') == counts
                cli.query('SHUTDOWN;'); assert process.wait(timeout=15) == 0, 'Restored host database did not exit cleanly'
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
    assert sha(args.backup) == args.backup_sha256
    sources = ['android/app/src/main/java/com/russianranger/fta/runtime/ColdRealmBackup.kt',
        'android/app/src/main/java/com/russianranger/fta/runtime/LocalAuthProbe.kt',
        'android/app/src/main/java/com/russianranger/fta/runtime/AuthDiagnosticConfig.kt',
        'android/app/src/main/java/com/russianranger/fta/runtime/LocalRealmAuthState.kt',
        'android/app/src/main/java/com/russianranger/fta/runtime/WorldUpdaterEvidence.kt',
        'tests/fixtures/LocalAuthBackupHost.java', 'tests/fixtures/LocalAuthHost.java', 'tools/check_local_auth_backup.py']
    proof = {'formatVersion': 1, 'scope': 'host-original-auth-on-production-restored-thor-backup', 'androidExecuted': False,
        'backupSha256': args.backup_sha256, 'originalBackupUnchanged': True, 'productionRestorePassed': True,
        'originalSeedReimported': False, 'sourceKnownStaleStartupFlagCleared': True, 'configuredRealmExplicitlyKeptOffline': True, 'strictMetadataChecksBefore': 24, 'strictMetadataChecksAfter': 24,
        'authCleanStops': 2, 'build12340UnknownAccountProtocolPassed': True, 'databaseHealthChecks': 20,
        'productionUuidDatabaseRoleAndConfigUsed': True, 'temporaryDatabaseRoleRemoved': True,
        'existingAccountAndCharacterCountsUnchanged': True, 'databaseCleanSqlShutdownExitCode': 0,
        'hostDatabaseTransport': 'private-loopback-with-generated-host-only-role', 'androidSocketCredentialsExecuted': False, 'serverSha256': sha(args.server), 'authserverSha256': sha(args.auth),
        'sourceSha256': {name: sha(ROOT / name) for name in sources}, 'gameLoginQualified': False, 'worldReady': False}
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
