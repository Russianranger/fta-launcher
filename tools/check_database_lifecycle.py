#!/usr/bin/env python3
"""Exercise the production Kotlin lifecycle probe and JNI ownership on host MariaDB.

All storage/credentials are generated in a fresh temporary directory. No seed,
external database endpoint or existing datadir is accepted. Host execution does
not qualify Bionic, Android services or screen-off behavior.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('server', 'initializer', 'client', 'jni-header', 'kotlin-classes', 'kotlin-stdlib', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    # Adopt and reap the known native children after the deliberately killed JVM.
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0):
        raise RuntimeError('Host child-subreaper setup failed')
    with tempfile.TemporaryDirectory(prefix='fta-lifecycle-') as temporary:
        work = Path(temporary)
        os.chmod(work, 0o700)
        shutil.copyfile(args.jni_header, work / 'jni.h')
        subprocess.run(['g++', '-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-pthread', '-fPIC', '-shared',
            '-I' + str(work), str(ROOT / 'native/process-supervisor/process_owner.cpp'),
            str(ROOT / 'native/process-supervisor/process_supervisor.cpp'), '-o', str(work / 'libfta_process.so')], check=True)
        classpath = ':'.join(str(p.resolve()) for p in (work, args.kotlin_classes, args.kotlin_stdlib))
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', classpath, '-d', str(work),
            str(ROOT / 'tests/fixtures/DatabaseLifecycleHost.java')], check=True)
        base = args.server.resolve().parent.parent
        with (work / 'bootstrap.log').open('w') as log:
            subprocess.run([str(args.initializer), '--no-defaults', f'--basedir={base}', f'--datadir={work / "data"}',
                '--auth-root-authentication-method=socket', '--skip-test-db'], stdout=log, stderr=subprocess.STDOUT,
                cwd=work, timeout=120, check=True)
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        password = secrets.token_hex(32)
        init = work / 'init.sql'
        init.write_text("CREATE USER IF NOT EXISTS 'fta_lifecycle'@'127.0.0.1' IDENTIFIED BY '" + password + "';\n"
                        "GRANT ALL PRIVILEGES ON *.* TO 'fta_lifecycle'@'127.0.0.1' WITH GRANT OPTION;\n")
        defaults = work / 'client.cnf'
        defaults.write_text('[client]\nuser=fta_lifecycle\npassword=' + password + '\n')
        os.chmod(defaults, 0o600); os.chmod(init, 0o600)
        server_args = ['--no-defaults', f'--basedir={base}', f'--datadir={work / "data"}', '--socket=',
            f'--pid-file={work / "db.pid"}', '--bind-address=127.0.0.1', f'--port={port}', f'--init-file={init}',
            '--skip-name-resolve', '--performance-schema=OFF', '--innodb-buffer-pool-size=64M',
            '--innodb-log-file-size=16M', '--aria-pagecache-buffer-size=16M', '--key-buffer-size=8M',
            '--max-connections=8', '--thread-cache-size=2']
        if os.geteuid() == 0:
            server_args.append('--user=root')
        (work / 'server.args').write_text('\n'.join(server_args) + '\n')
        (work / 'client.args').write_text('\n'.join([f'--defaults-file={defaults}', '--protocol=TCP', '--host=127.0.0.1',
            f'--port={port}', '--batch', '--skip-column-names', '--connect-timeout=2']) + '\n')
        command = ['java', '-Djava.library.path=' + str(work), '-cp', classpath, 'DatabaseLifecycleHost',
            str(work), str(args.server.resolve()), str(args.client.resolve())]
        result = subprocess.run(command + ['probe'], check=True, capture_output=True, text=True, timeout=240)
        checks = dict(line.split('=') for line in result.stdout.splitlines())
        proof = {name: value == 'true' for name, value in checks.items()}
        with (work / 'victim.log').open('w') as log:
            victim = subprocess.Popen(command + ['owner-victim'], stdout=log, stderr=subprocess.STDOUT)
            try:
                end = time.monotonic() + 60
                ready = work / 'victim-ready'
                while not ready.exists():
                    if victim.poll() is not None or time.monotonic() > end:
                        raise RuntimeError('Host owner victim did not become ready')
                    time.sleep(0.1)
                pid = int(ready.read_text().strip())
                victim.kill()
                assert victim.wait(timeout=10) == -signal.SIGKILL
                end = time.monotonic() + 10
                while True:
                    observed, status = os.waitpid(pid, os.WNOHANG)
                    if observed == pid:
                        assert os.WIFSIGNALED(status) and os.WTERMSIG(status) == signal.SIGKILL
                        break
                    if time.monotonic() > end:
                        raise RuntimeError('Native database survived owner death')
                    time.sleep(0.05)
                proof['ownerSigkillReapedDatabase'] = True
                result = subprocess.run(command + ['recover-owner'], check=True, capture_output=True, text=True, timeout=90)
                assert result.stdout.strip() == 'ownerDeathRecovery=true'
                proof['ownerDeathRecovery'] = True
            finally:
                if victim.poll() is None:
                    victim.kill(); victim.wait(timeout=10)
        # Reap any adopted direct SQL client too; no live descendant may remain.
        end = time.monotonic() + 10
        while True:
            try:
                pid, _ = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                break
            if pid == 0:
                if time.monotonic() > end:
                    raise RuntimeError('Host owner left a live descendant')
                time.sleep(0.05)
        proof['noAdoptedChildrenRemain'] = True
        evidence = {'formatVersion': 1, 'scope': 'host-production-kotlin-and-jni-only', 'androidExecuted': False,
            'seedImported': False, 'screenOffQualified': False, 'hostProviderVersion': '12.3.2-MariaDB',
            'hostTransport': 'private-loopback-with-generated-credentials', 'checks': proof,
            'sourceSha256': {str(p.relative_to(ROOT)): sha(p) for p in (
                ROOT / 'native/process-supervisor/process_owner.cpp', ROOT / 'native/process-supervisor/process_supervisor.cpp',
                ROOT / 'android/app/src/main/java/com/russianranger/fta/runtime/DatabaseLifecycleProbe.kt')},
            'serverSha256': sha(args.server), 'clientSha256': sha(args.client)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
