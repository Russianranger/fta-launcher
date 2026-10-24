#!/usr/bin/env python3
"""Build only the original OpenSSL legacy module against retained Android crypto.

The worldserver, libcrypto and realm provider manifest are never rebuilt or edited.
The private source companion already supplies this exact Apache-2.0 source archive.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tarfile
import tempfile
import zipfile

from build_native import check_jobs
from stage_android_provider import digest, inspect_elf

ROOT = Path(__file__).resolve().parents[1]
CRYPTO_SHA = '4122f5c95088f11a43c14e49e257cc1ce700960e855840cf9b9f39a8acbcd7e8'
CONFIGURE = ['android-arm64', '-D__ANDROID_API__=33', '--prefix=/data/data/com.termux/files/usr',
             '--openssldir=/data/data/com.termux/files/usr/etc/tls', 'shared', 'zlib-dynamic',
             'no-ssl', 'no-hw', 'no-srp', 'no-tests', 'enable-tls1_3', '-DNO_SYSLOG',
             '-Wl,-z,max-page-size=16384']


def safe_relative(name):
    path = Path(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name or '\0' in name:
        raise ValueError('Unsafe toolchain archive path')
    return path


def extract_ndk(archive, destination):
    prefix = 'android-ndk-r28c/'
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            if not (entry.filename.startswith(prefix + 'toolchains/llvm/prebuilt/linux-x86_64/') or
                    entry.filename in (prefix + 'source.properties', prefix + 'NOTICE',
                                       prefix + 'meta/abis.json', prefix + 'meta/platforms.json')):
                continue
            target = destination / safe_relative(entry.filename)
            target.parent.mkdir(parents=True, exist_ok=True)
            if entry.is_dir():
                target.mkdir(exist_ok=True)
            elif stat.S_ISLNK(entry.external_attr >> 16):
                link = source.read(entry).decode()
                if not (target.parent / link).resolve().is_relative_to(destination / prefix):
                    raise ValueError('Escaping toolchain link')
                target.symlink_to(link)
            else:
                with source.open(entry) as incoming, target.open('xb') as output:
                    shutil.copyfileobj(incoming, output)
                target.chmod((entry.external_attr >> 16) & 0o777)
    return destination / prefix


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'ndk-zip', 'crypto-elf', 'output', 'evidence', 'build-log'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    inputs = json.loads((ROOT / 'native-port/android-inputs.json').read_text())['inputs']
    for path, expected in ((args.source, inputs['openssl']['sha256']),
                           (args.ndk_zip, inputs['ndk']['sha256']), (args.crypto_elf, CRYPTO_SHA)):
        if path.is_symlink() or digest(path) != expected:
            raise ValueError('Pinned build input identity differs')
    if args.output.exists() or args.evidence.exists():
        raise ValueError('Do not overwrite a measured provider artifact')
    check_jobs(1)
    with tempfile.TemporaryDirectory(prefix='fta-openssl-legacy-') as temporary:
        work = Path(temporary)
        ndk = extract_ndk(args.ndk_zip, work)
        with tarfile.open(args.source) as archive:
            archive.extractall(work, filter='data')
        source = work / 'openssl-3.6.3'
        tools = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        env = dict(os.environ, ANDROID_NDK_ROOT=str(ndk), PATH=str(tools) + ':' + os.environ['PATH'])
        with args.build_log.open('w') as log:
            subprocess.run(['./Configure'] + CONFIGURE, cwd=source, env=env, check=True, stdout=log, stderr=log)
            shutil.copyfile(args.crypto_elf, source / 'libcrypto.so.3')
            (source / 'libcrypto.so').symlink_to('libcrypto.so.3')
            subprocess.run(['make', '-j1', 'build_generated'], cwd=source, env=env, check=True, stdout=log, stderr=log)
            subprocess.run(['make', '-j1', '-o', 'libcrypto.so', '-o', 'libcrypto.so.3', 'providers/legacy.so'],
                           cwd=source, env=env, check=True, stdout=log, stderr=log)
        if digest(source / 'libcrypto.so.3') != CRYPTO_SHA:
            raise ValueError('Retained crypto library changed during linking')
        module = source / 'providers/legacy.so'
        elf = inspect_elf(module, reject_search_paths=True)
        if set(elf['needed']) != {'libcrypto.so.3', 'libdl.so', 'libc.so'} or elf['minimumLoadAlignment'] < 16384:
            raise ValueError('Unexpected provider dependency closure or page alignment')
        symbols = subprocess.check_output(['readelf', '--dyn-syms', '--wide', str(module)], text=True)
        if ' OSSL_provider_init' not in symbols:
            raise ValueError('Original provider entry point is missing')
        proof = dict(status='android_openssl_legacy_built', opensslVersion='3.6.3', androidApi=33,
            sourceArchiveSha256=inputs['openssl']['sha256'], ndkArchiveSha256=inputs['ndk']['sha256'],
            retainedCryptoSha256=CRYPTO_SHA, retainedCryptoUnchanged=True, originalSourceModified=False,
            configureArguments=CONFIGURE, compilerJobs=1, moduleBytes=module.stat().st_size,
            moduleSha256=digest(module), elf=elf, originalProviderEntryPointPresent=True,
            linkedWithUndefinedSymbolsRejected=True, sourceCompanionVersion=8,
            nativeWorldRecompiled=False, androidExecutionQualified=False, worldReady=False)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(module, args.output)
        args.evidence.write_text(json.dumps(proof, indent=2) + '\n')
        print(json.dumps({k: proof[k] for k in ('status', 'moduleBytes', 'moduleSha256')}))


if __name__ == '__main__':
    main()
