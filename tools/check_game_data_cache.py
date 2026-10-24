#!/usr/bin/env python3
"""Compile the exact cache helper; check JNI safety and actual host cache residency.

Does not simulate Android lmkd, SAF/FUSE behavior or qualify Thor survival.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from build_native import check_jobs

REPO = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--kotlin-classpath', required=True)
    p.add_argument('--jdk', type=Path, required=True)
    p.add_argument('--jni-header', type=Path, help='Optional VM-neutral NDK jni.h for a host JDK missing development headers')
    p.add_argument('--library-dir', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    check_jobs(1)
    args.library_dir.mkdir(parents=True, exist_ok=True)
    native = REPO / 'native/process-supervisor/game_data_cache.cpp'
    library = args.library_dir / 'libfta_game_io.so'
    flags = ['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-O2',
             '-I' + str(args.jdk / 'include'), '-I' + str(args.jdk / 'include/linux')]
    if args.jni_header:
        include = args.library_dir / 'include'
        include.mkdir(exist_ok=True)
        (include / 'jni.h').write_bytes(args.jni_header.read_bytes())
        flags += ['-I' + str(include)]
    subprocess.run(flags + ['-shared', '-fPIC', str(native), '-o', str(library)], check=True)
    with tempfile.TemporaryDirectory(prefix='fta-cache-test-') as temporary:
        root = Path(temporary)
        binary = root / 'residency'
        subprocess.run(flags + [str(REPO / 'tests/fixtures/game_data_cache_host.cpp'), '-o', str(binary)], check=True)
        measured = json.loads(subprocess.check_output([str(binary)], text=True))
        subprocess.run([str(args.jdk / 'bin/java'), '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', args.kotlin_classpath,
                        '-d', str(root), str(REPO / 'tests/fixtures/GameDataCacheHost.java')], check=True)
        subprocess.run([str(args.jdk / 'bin/java'), '--add-opens=java.base/java.io=ALL-UNNAMED',
                        '-Djava.library.path=' + str(args.library_dir.resolve()), '-cp', str(root) + ':' + args.kotlin_classpath,
                        'GameDataCacheHost', str(root / 'files')], check=True)
    proof = dict(formatVersion=1, scope='host-file-cache-advice', nativeSourceSha256=hashlib.sha256(native.read_bytes()).hexdigest(),
                 nativeLibrarySha256=hashlib.sha256(library.read_bytes()).hexdigest(), **measured,
                 actualJniEntryPointsPassed=True, fileBytesAndDescriptorPositionUnchanged=True, invalidRangesRejected=True,
                 symbolicLinksNotFollowed=True, offsetsAboveThreeGiBPassed=True, androidCacheEvictionMeasured=False,
                 thorLowMemoryCauseEstablished=False, thorSurvivalQualified=False)
    args.output.write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
