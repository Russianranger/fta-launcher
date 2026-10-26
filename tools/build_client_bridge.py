#!/usr/bin/env python3
"""Build the pinned Android PRoot bridge with one job and 16 KiB ELF segments.

Uses public UO v0.2.0 corresponding source/recipe in an isolated directory.
Never stages an APK, executes the ARM64 output, or touches an installed runtime.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import struct
import subprocess
import tarfile

from build_native import check_jobs
from build_openssl_legacy import extract_ndk
from stage_android_provider import digest, inspect_elf

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SHA = '492043660b1370e1910c8b8ca2b93be1f637f4929034f575591f14e67e015243'
INPUTS = {
    'proot-source.tar.gz': 'd43ad342bb2e8f4d2afe6119b5ec3a1d8bb912d5897e2db7d2ddf928548c1c84',
    'talloc-2.4.3.tar.gz': 'dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd',
    'build-proot.sh': '7ddcee055714f27c2a53fea816c8d43171efc54b655771e979735a30ba343319',
    'native/proot-sysvipc.patch': 'e8e3cb956bdb8b1bcbdf72a23b67ad86fc33fa51430f3a1ee37e1a053f5f0330',
    'native/proot-acceleration.patch': '46ab8e6f80260044bccbd831e22a7fc3a376e45ef945772cac790485ab70306f',
}


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def replace_exact(text, old, new, count=1):
    require(text.count(old) == count, 'Pinned build recipe differs')
    return text.replace(old, new)


def loader_elf(data):
    """The freestanding PRoot loader is intentionally ET_EXEC, unlike the PIE tracer."""
    require(64 <= len(data) <= 1024 * 1024 and data[:7] == b'\x7fELF\x02\x01\x01', 'Invalid loader ELF')
    kind, machine, version, entry, phoff = struct.unpack_from('<HHIQQ', data, 16)
    ehsize, phsize, count = struct.unpack_from('<HHH', data, 52)
    require((kind, machine, version, ehsize, phsize) == (2, 183, 1, 64, 56), 'Wrong loader architecture/type')
    require(1 <= count <= 16 and phoff >= 64 and phoff + count * phsize <= len(data), 'Invalid loader headers')
    loads = []; executable_entry = False; stack = []
    for i in range(count):
        kind, flags, offset, virtual, physical, filesz, memsz, alignment = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phsize)
        require(kind not in (2, 3), 'Loader must not have dynamic linkage or interpreter')
        if kind == 0x6474e551:
            stack.append(flags)
        if kind == 1:
            require(filesz <= memsz and offset + filesz <= len(data), 'Invalid loader segment bounds')
            require(alignment >= 16384 and alignment & (alignment - 1) == 0 and
                    virtual % alignment == offset % alignment, 'Loader needs aligned 16 KiB segments')
            require(flags & 3 != 3, 'Writable executable loader segment')
            loads.append(alignment)
            executable_entry |= bool(flags & 1 and virtual <= entry < virtual + filesz)
    require(loads and executable_entry and len(stack) == 1 and not stack[0] & 1, 'Invalid loader entry or executable stack')
    return dict(type='EXEC', machine='AArch64', interpreter=None, needed=[],
                minimumLoadAlignment=min(loads), executableStack=False)


def recover_sources(bundle, directory):
    selected = set()
    with tarfile.open(bundle, 'r|gz') as archive:
        for item in archive:
            name = item.name.removeprefix('launcher-source/')
            if name not in INPUTS:
                continue
            require(name not in selected and item.isfile() and 0 < item.size <= 1024 * 1024, 'Invalid source bundle member')
            data = archive.extractfile(item).read()
            require(hashlib.sha256(data).hexdigest() == INPUTS[name], 'Source component identity differs')
            target = directory / name; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
            selected.add(name)
    require(selected == set(INPUTS), 'Missing bridge sources')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources', type=Path, required=True)
    parser.add_argument('--ndk-zip', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    ndk_pin = json.loads((ROOT / 'native-port/android-inputs.json').read_text())['inputs']['ndk']
    require(not args.sources.is_symlink() and digest(args.sources) == SOURCE_SHA, 'Source archive identity differs')
    require(not args.ndk_zip.is_symlink() and digest(args.ndk_zip) == ndk_pin['sha256'], 'NDK identity differs')
    work = args.work.resolve()
    require(not work.is_relative_to(ROOT) and not work.exists(), 'Use a new build directory outside Git')
    check_jobs(1); work.mkdir(parents=True)
    ndk = extract_ndk(args.ndk_zip, work / 'toolchain')
    original = work / 'original'; original.mkdir(); recover_sources(args.sources, original)
    project = work / 'project'; native = project / 'native'; native.mkdir(parents=True)
    for name in ('proot-sysvipc.patch', 'proot-acceleration.patch'):
        shutil.copyfile(original / 'native' / name, native / name)
    source = project / 'runtime-work/native'; source.mkdir(parents=True)
    shutil.copyfile(original / 'talloc-2.4.3.tar.gz', source / 'talloc.tar.gz')
    proot = source / 'proot'; proot.mkdir()
    with tarfile.open(original / 'proot-source.tar.gz') as archive:
        archive.extractall(proot, filter='data')
    makefile = proot / 'src/GNUmakefile'
    makefile.write_text(replace_exact(makefile.read_text(),
        '$(ROSEGMENT),-z,noexecstack', '$(ROSEGMENT),-z,noexecstack,-z,max-page-size=16384'))
    recipe = (original / 'build-proot.sh').read_text()
    recipe = replace_exact(recipe, 'repo_root="$(cd "$(dirname "$0")/.." && pwd)"', 'repo_root=' + shlex.quote(str(project)))
    recipe = replace_exact(recipe, 'ndk_path="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/27.2.12479018}"',
                           'ndk_path=' + shlex.quote(str(ndk)))
    recipe = replace_exact(recipe, 'if [ ! -d proot ]; then git clone https://github.com/termux/proot.git proot; fi\ngit -C proot checkout 7266fb3e8516535682f5a9c8f3a7e70f6506eddb',
                           '# Exact archived source was verified and unpacked by the build driver.')
    recipe = replace_exact(recipe, '-j2', '-j1', count=2)
    script = work / 'build.sh'; script.write_text(recipe)
    env = dict(os.environ, MAKEFLAGS='', MFLAGS='')
    with (work / 'build.log').open('w') as log:
        subprocess.run(['bash', str(script)], cwd=work, env=env, check=True, stdout=log, stderr=log, timeout=1800)
    output = project / 'app/src/main/jniLibs/arm64-v8a'
    tracer = output / 'libproot.so'; loader = output / 'libproot-loader.so'
    tracer_meta = inspect_elf(tracer, reject_search_paths=True)
    require(tracer_meta['needed'] == ['libc.so', 'libdl.so'] and tracer_meta['interpreter'] == '/system/bin/linker64' and
            not tracer_meta['sonames'] and tracer_meta['minimumLoadAlignment'] >= 16384, 'Unexpected Android tracer metadata')
    loader_meta = loader_elf(loader.read_bytes())
    evidence = dict(formatVersion=1, scope='android-client-bridge-build-only',
        sourceArchiveSha256=SOURCE_SHA, sourceComponents=INPUTS, ndkArchiveSha256=ndk_pin['sha256'],
        ndkVersion=ndk_pin['version'], androidApi=26, buildJobs=1,
        sourceRecipe='Russianranger/uo-android-launcher v0.2.0 corresponding sources',
        changes=['pinned local sources instead of Git fetch', 'NDK r28c', 'one compiler job', 'explicit loader 16 KiB link alignment'],
        payloads={p.name: dict(bytes=p.stat().st_size, sha256=digest(p), elf=m) for p, m in ((tracer,tracer_meta),(loader,loader_meta))},
        androidBridgeBuilt=True, androidBridgeExecuted=False, androidRuntimeQualified=False,
        originalHostExecuted=False, warcraftXLCompatible=False, gameLoginQualified=False)
    (work / 'build-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps({'androidBridgeBuilt': True, 'output': str(output)}))


if __name__ == '__main__':
    main()
