#!/usr/bin/env python3
"""Kill production-store JVMs at each import boundary and verify retained assets.

Uses synthetic headers only. Does not qualify Android process exit APIs or world data.
An optional existing stress fixture is imported and fully reverified under a 64 MiB heap.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

from check_game_data_source import synthetic_assets

REPO = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--kotlin-classpath', required=True)
    p.add_argument('--java', default='java')
    p.add_argument('--stress-zip', type=Path)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    policy = REPO / 'manifests/game-data-policy.properties'
    with tempfile.TemporaryDirectory(prefix='fta-import-interruptions-') as temporary:
        root = Path(temporary)
        archive = root / 'synthetic.zip'
        assets = synthetic_assets(policy.read_text())
        with zipfile.ZipFile(archive, 'w') as z:
            for name, data in assets.items():
                z.writestr(name, data)
        classes = root / 'classes'
        classes.mkdir()
        subprocess.run([args.java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', args.kotlin_classpath,
                        '-d', str(classes), str(REPO / 'tests/fixtures/GameDataHost.java'),
                        str(REPO / 'tests/fixtures/GameDataCheckpointHost.java')], check=True)
        command = [args.java, '-Xmx64m', '-cp', str(classes) + ':' + args.kotlin_classpath]

        def run(harness, mode, store, source, extra, expected=0):
            result = subprocess.run(command + [harness, mode, str(store), str(policy), str(source), str(extra)])
            if result.returncode != expected:
                raise ValueError(f'{harness} {mode} exited {result.returncode}, expected {expected}')

        store = root / 'store'
        run('GameDataHost', 'import', store, archive, 0)
        original = (store / 'active').read_bytes()
        stages = ['copy', 'copy_sync', 'archive_index', 'extract', 'preflight', 'publish']
        for stage in stages:
            run('GameDataCheckpointHost', 'halt-' + stage, store, archive, '', 73)
            run('GameDataCheckpointHost', 'inspect', store, archive, stage)
            if (store / 'active').read_bytes() != original:
                raise ValueError(f'Process exit at {stage} replaced the active candidate')
            run('GameDataHost', 'verify', store, archive, 0)
        run('GameDataCheckpointHost', 'halt-after-publication', store, archive, '', 73)
        run('GameDataCheckpointHost', 'inspect', store, archive, 'publish')
        if (store / 'active').read_bytes() == original:
            raise ValueError('Post-publication exit did not exercise a new active candidate')
        run('GameDataHost', 'verify', store, archive, 0)
        run('GameDataHost', 'import', store, archive, 0)
        run('GameDataHost', 'verify', store, archive, 0)
        if any(path.name.startswith('.partial-') for path in store.iterdir()):
            raise ValueError('Orphan staging was not recovered')
        proof = {'formatVersion': 1, 'scope': 'production-game-data-checkpoints-host',
                 'heapLimitMiB': 64, 'interruptedStagesVerified': stages,
                 'prepublicationExitsPreservedPriorCandidate': True,
                 'postpublicationExitPreservedNewCandidate': True,
                 'durableCheckpointsReadableAfterProcessDeath': True,
                 'orphanStagingRecovered': True, 'separateJvmProcesses': 24,
                 'androidExitHistoryQualified': False, 'actualGameAssetsTested': False, 'worldReady': False}
        if args.stress_zip:
            with zipfile.ZipFile(args.stress_zip) as z:
                count = len(z.infolist())
                db2 = sum(name.endswith('.db2') for name in z.namelist())
            stress = root / 'stress'
            run('GameDataHost', 'import', stress, args.stress_zip, db2)
            run('GameDataHost', 'verify', stress, args.stress_zip, db2)
            proof.update(stressArchiveBytes=args.stress_zip.stat().st_size, stressAssetCount=count,
                         stressImportPassed=True, stressFullReverifyPassed=True, separateJvmProcesses=26)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(proof, indent=2) + '\n')
        print('Checkpoint, process-death and bounded-heap checks passed; Android/real assets remain unqualified')


if __name__ == '__main__':
    main()
