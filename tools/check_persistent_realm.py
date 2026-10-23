#!/usr/bin/env python3
"""Exercise persistent activation/reopen with production Kotlin, SQL and JNI on real host MariaDB.

Synthetic data only. No seed, existing datadir or external endpoint is accepted.
Two separate owning JVMs must share one active generation and never reimport it.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from check_runtime_recovery import initialize, sha

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('server', 'initializer', 'client', 'jni-header', 'kotlin-classes', 'kotlin-stdlib', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='fta-persistent-realm-') as temporary:
        work = Path(temporary)
        os.chmod(work, 0o700)
        shutil.copyfile(args.jni_header, work / 'jni.h')
        subprocess.run(['g++', '-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-pthread', '-fPIC', '-shared',
            '-I' + str(work), str(ROOT / 'native/process-supervisor/process_owner.cpp'),
            str(ROOT / 'native/process-supervisor/process_supervisor.cpp'), '-o', str(work / 'libfta_process.so')], check=True)
        classpath = ':'.join(str(p.resolve()) for p in (work, args.kotlin_classes, args.kotlin_stdlib))
        fixtures = ('DatabaseLifecycleHost.java', 'RuntimeRecoveryHost.java', 'PersistentRealmHost.java')
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', classpath, '-d', str(work)] +
            [str(ROOT / 'tests/fixtures' / name) for name in fixtures], check=True)
        initialize(work / 'template', args)
        command = ['java', '-Djava.library.path=' + str(work), '-cp', classpath, 'PersistentRealmHost',
            str(work / 'realm'), str(work / 'template'), str(args.server.resolve()), str(args.client.resolve())]
        first = subprocess.run(command + ['new'], check=True, capture_output=True, text=True, timeout=120)
        assert first.stdout.strip().splitlines() == ['prepared=1', 'reopened=2', 'retainedRow=true']
        pointer = work / 'realm/active.properties'
        before = pointer.read_bytes()
        second = subprocess.run(command + ['existing'], check=True, capture_output=True, text=True, timeout=120)
        assert second.stdout.strip().splitlines() == ['prepared=0', 'reopened=2', 'retainedRow=true']
        assert pointer.read_bytes() == before
        assert len(list((work / 'realm/generations').glob('r-*'))) == 1
        assert not (work / 'realm/writer.properties').exists()
        sources = ('android/app/src/main/java/com/russianranger/fta/runtime/PersistentRealmStore.kt',
            'native/process-supervisor/process_owner.cpp', 'native/process-supervisor/process_supervisor.cpp',
            'tests/fixtures/PersistentRealmHost.java', 'tests/fixtures/RuntimeRecoveryHost.java', 'tests/fixtures/DatabaseLifecycleHost.java')
        evidence = {'formatVersion': 1, 'scope': 'host-production-realm-storage-protocol-sql-and-jni',
            'androidExecuted': False, 'originalSeedImported': False, 'compiledWorldUpdaterExecuted': False,
            'hostProviderVersion': '12.3.2-MariaDB', 'hostTransport': 'private-loopback-with-generated-credentials',
            'owningJvmSessions': 2, 'freshPreparations': 1, 'sameDatabaseReopens': 4, 'cleanSqlShutdowns': 5,
            'existingGenerationNeverReimported': True, 'activePointerUnchanged': True, 'retainedRowUnchanged': True,
            'onlyOneGenerationCreated': True, 'writerLeaseReleased': True,
            'sourceSha256': {name: sha(ROOT / name) for name in sources},
            'serverSha256': sha(args.server), 'clientSha256': sha(args.client)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
