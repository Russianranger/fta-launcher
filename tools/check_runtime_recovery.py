#!/usr/bin/env python3
"""Run the production recovery protocol/SQL/JNI against real, disposable host processes.

Two fresh datadirs only; no seed or existing endpoint is accepted. Host command
delivery is a fixture, not Android PendingIntent/foreground-service evidence.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def initialize(root, args):
    root.mkdir(parents=True)
    base = args.server.resolve().parent.parent
    with (root / 'bootstrap.log').open('w') as log:
        subprocess.run([str(args.initializer), '--no-defaults', f'--basedir={base}', f'--datadir={root / "data"}',
            '--auth-root-authentication-method=socket', '--skip-test-db'], stdout=log, stderr=subprocess.STDOUT,
            cwd=root, timeout=120, check=True)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    password = secrets.token_hex(32)
    init = root / 'init.sql'
    init.write_text("CREATE USER IF NOT EXISTS 'fta_lifecycle'@'127.0.0.1' IDENTIFIED BY '" + password + "';\n"
                    "GRANT ALL PRIVILEGES ON *.* TO 'fta_lifecycle'@'127.0.0.1' WITH GRANT OPTION;\n")
    defaults = root / 'client.cnf'
    defaults.write_text('[client]\nuser=fta_lifecycle\npassword=' + password + '\n')
    os.chmod(defaults, 0o600); os.chmod(init, 0o600)
    server_args = ['--no-defaults', f'--basedir={base}', f'--datadir={root / "data"}', '--socket=',
        f'--pid-file={root / "db.pid"}', '--bind-address=127.0.0.1', f'--port={port}', f'--init-file={init}',
        '--skip-name-resolve', '--performance-schema=OFF', '--innodb-buffer-pool-size=64M',
        '--innodb-log-file-size=16M', '--aria-pagecache-buffer-size=16M', '--key-buffer-size=8M',
        '--max-connections=8', '--thread-cache-size=2']
    if os.geteuid() == 0:
        server_args.append('--user=root')
    (root / 'server.args').write_text('\n'.join(server_args) + '\n')
    (root / 'client.args').write_text('\n'.join([f'--defaults-file={defaults}', '--protocol=TCP', '--host=127.0.0.1',
        f'--port={port}', '--batch', '--skip-column-names', '--connect-timeout=2']) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('server', 'initializer', 'client', 'jni-header', 'kotlin-classes', 'kotlin-stdlib', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    # Reap the database and active SQL client orphaned by the deliberately killed JVM.
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0):
        raise RuntimeError('Host child-subreaper setup failed')
    with tempfile.TemporaryDirectory(prefix='fta-recovery-') as temporary:
        work = Path(temporary)
        os.chmod(work, 0o700)
        shutil.copyfile(args.jni_header, work / 'jni.h')
        subprocess.run(['g++', '-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-pthread', '-fPIC', '-shared',
            '-I' + str(work), str(ROOT / 'native/process-supervisor/process_owner.cpp'),
            str(ROOT / 'native/process-supervisor/process_supervisor.cpp'), '-o', str(work / 'libfta_process.so')], check=True)
        classpath = ':'.join(str(p.resolve()) for p in (work, args.kotlin_classes, args.kotlin_stdlib))
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', classpath, '-d', str(work),
            str(ROOT / 'tests/fixtures/DatabaseLifecycleHost.java'), str(ROOT / 'tests/fixtures/RuntimeRecoveryHost.java')], check=True)
        for suffix in ('13', '14'):
            initialize(work / 'database-diagnostics' / ('g-00000000-0000-0000-0000-0000000000' + suffix), args)
        command = ['java', '-Djava.library.path=' + str(work), '-cp', classpath, 'RuntimeRecoveryHost',
            str(work), str(args.server.resolve()), str(args.client.resolve())]
        result = subprocess.run(command, capture_output=True, text=True, timeout=300)
        if result.returncode:
            # Fixture logs contain no SQL or credentials; preserve only Java failures.
            for phase in ('CANCEL', 'CRASH', 'RESUME'):
                log = work / (phase + '.log')
                if log.exists():
                    print(phase + ': ' + log.read_text()[-4000:])
            raise RuntimeError(result.stderr[-4000:])
        proof = {name: value == 'true' for name, value in (line.split('=') for line in result.stdout.splitlines())}
        if len(proof) != 7 or not all(proof.values()):
            raise RuntimeError('Host recovery protocol did not pass every field')
        end = time.monotonic() + 10
        adopted = 0
        while True:
            try:
                pid, status = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                break
            if pid:
                if not os.WIFSIGNALED(status) or os.WTERMSIG(status) != 9:
                    raise RuntimeError('Unexpected orphan termination')
                adopted += 1
            elif time.monotonic() > end:
                raise RuntimeError('Host worker left a live descendant')
            else:
                time.sleep(0.05)
        if adopted != 2:
            raise RuntimeError('Expected exactly the database and active SQL client from the killed owner')
        proof['bothOrphansReapedSigkill'] = True
        proof['noAdoptedChildrenRemain'] = True
        sources = ('native/process-supervisor/process_owner.cpp', 'native/process-supervisor/process_supervisor.cpp',
            'android/app/src/main/java/com/russianranger/fta/runtime/RecoveryProtocol.kt',
            'android/app/src/main/java/com/russianranger/fta/runtime/DatabaseLifecycleProbe.kt',
            'tests/fixtures/RuntimeRecoveryHost.java', 'tests/fixtures/DatabaseLifecycleHost.java')
        evidence = {'formatVersion': 1, 'scope': 'host-production-recovery-protocol-sql-and-jni',
            'androidExecuted': False, 'androidStopActionDelivered': False, 'seedImported': False,
            'hostProviderVersion': '12.3.2-MariaDB', 'hostTransport': 'private-loopback-with-generated-credentials',
            'hostProcNamespaceTranslation': True, 'checks': proof,
            'sourceSha256': {name: sha(ROOT / name) for name in sources},
            'serverSha256': sha(args.server), 'clientSha256': sha(args.client)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
