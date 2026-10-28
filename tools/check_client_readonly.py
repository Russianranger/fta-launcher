#!/usr/bin/env python3
"""Build and qualify the opt-in native-64 import guard with disposable host files."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import re
from build_client_bridge import ROOT, SOURCE_SHA, recover_sources, replace_exact, require
from build_native import check_jobs
from stage_android_provider import digest
from check_client_bridge_ownership import HELPER


def checks():
    source = (ROOT / 'native/client-bridge/readonly_fixture.c').read_text()
    names = re.findall(r'(?:check|DENIED)\("([a-z-]+)"', source)
    require(len(names) == len(set(names)) and len(names) >= 50, 'Fixture checks must be unique and bounded')
    return names


def snapshot(root):
    return {str(p.relative_to(root)): (p.stat().st_mode, p.stat().st_uid, p.stat().st_gid,
        p.stat().st_mtime_ns, p.stat().st_ctime_ns, p.stat().st_ino, p.stat().st_nlink,
        digest(p) if p.is_file() else None) for p in [root, *sorted(root.rglob('*'))]}


def qualify(proot, loader, fixture, work):
    root = work / 'case'; root.mkdir(); immutable = root / 'immutable'; immutable.mkdir()
    (immutable / 'empty').mkdir(); (immutable / 'canary.bin').write_bytes(bytes(i % 251 for i in range(65536)))
    # Reads must not trigger fake-root's temporary chmod-and-restore (ctime).
    (immutable / 'read-only.txt').write_text('read-only'); (immutable / 'read-only.txt').chmod(0o400)
    writable = root / 'writable'; writable.mkdir(); (writable / 'alias').symlink_to(immutable, target_is_directory=True)
    before = snapshot(immutable)
    command = [str(proot), '--kill-on-exit', '--fta-readonly=' + str(immutable), '-0', '-r', '/', '-w', str(writable), str(fixture), str(root)]
    env = dict(os.environ, PROOT_LOADER=str(loader))
    result = subprocess.run(command, env=env, capture_output=True, timeout=30)
    (work / 'readonly.log').write_bytes(result.stdout + result.stderr)
    require(result.returncode == 0, 'Read-only fixture failed; inspect readonly.log')
    lines = result.stdout.decode('ascii').splitlines()
    require(lines == [f'FTA_RO {name} 0' for name in checks()] + ['FTA_RO_COMPLETE 1'], 'Incomplete or duplicate fixture results')
    require(snapshot(immutable) == before, 'Protected tree or metadata changed')
    # Refuse launch without a valid canonical root, or with existing aliases.
    rejected = []
    for name, option in [('missing', None), ('relative', 'case/immutable'),
                         ('noncanonical', str(immutable) + '/.'), ('root', '/'),
                         ('file', str(immutable / 'canary.bin'))]:
        args = command.copy(); args.pop(2)
        if option is not None: args.insert(2, '--fta-readonly=' + option)
        value = subprocess.run(args, env=env, capture_output=True, timeout=10)
        require(value.returncode != 0 and not value.stdout, 'Invalid root admitted: ' + name); rejected.append(name)
    for name in ['symlink', 'hardlink']:
        target = immutable / 'invalid'
        if name == 'symlink': target.symlink_to(immutable / 'canary.bin')
        else: os.link(immutable / 'canary.bin', target)
        try:
            value = subprocess.run(command, env=env, capture_output=True, timeout=10)
            require(value.returncode != 0 and not value.stdout, 'Unsafe import admitted: ' + name); rejected.append(name)
        finally: target.unlink()
    evidence = dict(formatVersion=1, scope='native-linux-readonly-fixture', machine=os.uname().machine,
        checks={name: 0 for name in checks()}, rootValidationRejected=rejected, protectedTreeUnchanged=True,
        buildJobs=1, prootSha256=digest(proot), fixtureSha256=digest(fixture),
        sourceSha256={str(p.relative_to(ROOT)): digest(p) for p in sorted((ROOT / 'native/client-bridge').glob('*'))},
        androidExecuted=False, wineExecuted=False, originalClientExecuted=False, hostileCodeSandbox=False)
    (work / 'readonly-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
    return evidence


def build(sources, work):
    require(not sources.is_symlink() and digest(sources) == SOURCE_SHA, 'Wrong source archive')
    require(not work.exists() and not work.is_relative_to(ROOT), 'Use a new directory outside Git')
    check_jobs(1); work.mkdir(parents=True)
    original = work / 'original'; original.mkdir(); recover_sources(sources, original)
    for source, target in [('talloc-2.4.3.tar.gz', work), ('proot-source.tar.gz', work / 'proot')]:
        target.mkdir(exist_ok=True)
        with tarfile.open(original / source) as archive: archive.extractall(target, filter='data')
    env = dict(os.environ, CC='gcc', AR='ar', RANLIB='ranlib', CFLAGS='-O2 -fPIC', LDFLAGS='', MAKEFLAGS='', MFLAGS='')
    prefix = work / 'prefix'; (prefix / 'include').mkdir(parents=True); (prefix / 'lib').mkdir()
    source = work / 'proot'; patch = ROOT / 'native/client-bridge/proot-exitkill.patch'
    with (work / 'build.log').open('w') as log:
        def run(args, cwd):
            subprocess.run(args, cwd=cwd, env=env, check=True, stdout=log, stderr=log, timeout=900)
        talloc = work / 'talloc-2.4.3'
        run(['./configure', '--prefix=' + str(prefix), '--disable-python', '--disable-rpath'], talloc)
        run(['make', '-j1'], talloc)
        shutil.copyfile(talloc / 'talloc.h', prefix / 'include/talloc.h')
        run(['ar', 'rcs', str(prefix / 'lib/libtalloc.a')] + [str(p) for p in sorted((talloc / 'bin/default').glob('talloc*.o'))], talloc)
        for name in ('proot-acceleration.patch', 'proot-sysvipc.patch'):
            run(['git', 'apply', str(original / 'native' / name)], source)
        header = source / 'src/extension/ashmem_memfd/ashmem_memfd.c'
        header.write_text('#include <string.h>\n' + header.read_text())
        make = source / 'src/GNUmakefile'
        make.write_text(replace_exact(make.read_text(), 'readelf -s $< | awk -f loader/loader-info.awk > $@',
                                     'python3 loader/loader-info.py $< > $@'))
        (source / 'src/loader/loader-info.py').write_text(HELPER)
        command = ['make', '-C', 'src', '-j1', 'CC=gcc', 'LD=gcc',
            'CPPFLAGS=-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I' + str(source / 'src') + ' -I' + str(prefix / 'include'),
            # This fixture runs native ELF64 only. GCC on AArch64 has no -m32;
            # suppress the makefile's optional ARM32 loader build dependency.
            # Keep the tracer's original arch.h and both Android loaders intact.
            'LDFLAGS=-L' + str(prefix / 'lib') + ' -ltalloc -Wl,-z,noexecstack',
            'PROOT_UNBUNDLE_LOADER=/unused', 'HAS_LOADER_32BIT=']
        run(['git', 'apply', '--check', str(patch)], source); run(['git', 'apply', str(patch)], source)
        run(['git', 'apply', str(ROOT / 'native/client-bridge/proot-readonly.patch')], source)
        shutil.copyfile(ROOT / 'native/client-bridge/read_only.c', source / 'src/extension/fta_readonly.c')
        run(command, source)
        owned = work / 'proot-readonly'; shutil.copyfile(source / 'src/proot', owned); owned.chmod(0o755)
        loader = work / 'loader'; shutil.copyfile(source / 'src/loader/loader', loader); loader.chmod(0o755)
        fixture = work / 'fixture'
        run(['gcc', '-std=c11', '-D_GNU_SOURCE', '-O2', '-Wall', '-Wextra', '-Werror',
             str(ROOT / 'native/client-bridge/readonly_fixture.c'), '-o', str(fixture)], work)
    return owned, loader, fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    proot, loader, fixture = build(args.sources, work)
    result = qualify(proot, loader, fixture, work)
    print(json.dumps(dict(checks=len(result['checks']), protectedTreeUnchanged=True, rejectedRoots=len(result['rootValidationRejected']))))


if __name__ == '__main__':
    main()
