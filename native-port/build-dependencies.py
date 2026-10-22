#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Reproduce ARM64/API-33 dependencies from explicit, pinned local inputs.

Python 3.11+ and Linux x86_64 host tools are prerequisites. No downloads, server execution, or
runtime qualification are performed. --plan validates inputs without writing.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import stat
import subprocess
import sys
import tarfile
import zipfile

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'tools'))
from build_native import check_jobs  # noqa: E402

PINS = {
    'ndk': ('r28c', 'dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f', 'android-ndk-r28c'),
    'boost': ('1.83.0', 'c0685b68dd44cc46574cce86c4e17c0f611b15e195be9848dfd0769a0a207628', 'boost_1_83_0'),
    'openssl': ('3.6.3', '243a86649cf6f23eeb6a2ff2456e09e5d77dd9018a54d3d96b0c6bdd6ba6c7f1', 'openssl-3.6.3'),
    'connector': ('64e9fe09fea6a017bab92e19a08d2301e3da5b56', '3b442e60aeb707dd1db192de2fa0f7d2f1940ebe40a7d772aeb2a0647c5b8ebb', 'mariadb-connector-c-64e9fe09fea6a017bab92e19a08d2301e3da5b56'),
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def safe_name(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or '..' in path.parts or '\\' in name or '.git' in path.parts or any(ord(c) < 32 for c in name):
        raise ValueError(f'Unsafe archive/path name: {name!r}')
    return path


def extract(archive, destination, root):
    """Write regular members first, preserve modes, then validate contained links."""
    destination.mkdir(parents=True, exist_ok=True)
    if (destination / root).exists() or (destination / root).is_symlink():
        raise ValueError('Archive source root must be fresh')
    zipped = zipfile.is_zipfile(archive)
    with (zipfile.ZipFile(archive) if zipped else tarfile.open(archive)) as bundle:
        members = bundle.infolist() if zipped else bundle.getmembers()
        seen, links = set(), []
        for item in members:
            name = item.filename if zipped else item.name
            path = safe_name(name)
            if not path.parts or path.parts[0] != root or str(path) in seen:
                raise ValueError(f'Unexpected or duplicate archive member: {name}')
            seen.add(str(path))
            mode = item.external_attr >> 16 if zipped else item.mode
            if zipped and stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR, stat.S_IFLNK):
                raise ValueError('Unsupported ZIP member type')
            islink = stat.S_ISLNK(mode) if zipped else item.issym() or item.islnk()
            if islink:
                target = bundle.read(item).decode() if zipped else item.linkname
                if PurePosixPath(target).is_absolute() or '\\' in target or any(ord(c) < 32 for c in target):
                    raise ValueError('Unsafe archive link')
                base = destination if not zipped and item.islnk() else destination / path.parent
                if not (base / target).resolve().is_relative_to(destination / root):
                    raise ValueError('Archive link escapes its source root')
                links.append((path, target, not zipped and item.islnk()))
            elif not zipped and not (item.isdir() or item.isfile()):
                raise ValueError('Unsupported archive member type')
        link_paths = {path for path, _, _ in links}
        for item in members:
            path = safe_name(item.filename if zipped else item.name)
            if any(parent in link_paths for parent in path.parents):
                raise ValueError('Archive member has a link parent')
            if path in link_paths:
                continue
            output = destination / path
            if item.is_dir() if zipped else item.isdir():
                output.mkdir(parents=True, exist_ok=True)
            else:
                output.parent.mkdir(parents=True, exist_ok=True)
                with (bundle.open(item) if zipped else bundle.extractfile(item)) as source, output.open('xb') as target:
                    shutil.copyfileobj(source, target)
                output.chmod(((item.external_attr >> 16) if zipped else item.mode) & 0o777 or 0o644)
        for path, target, hard in links:
            output = destination / path
            output.parent.mkdir(parents=True, exist_ok=True)
            os.link(destination / target, output) if hard else output.symlink_to(target)
        for path, _, _ in links:
            if not (destination / path).resolve().is_relative_to(destination / root):
                raise ValueError('Archive link chain escapes its source root')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in PINS:
        parser.add_argument('--' + name + '-archive', type=Path, required=True)
    parser.add_argument('--provider-stage', type=Path, required=True)
    parser.add_argument('--work-root', type=Path, required=True, help='fresh, nonexistent output directory')
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--ninja', default='ninja')
    parser.add_argument('--plan', action='store_true')
    args = parser.parse_args()
    if sys.platform != 'linux' or platform.machine() != 'x86_64':
        raise ValueError('This pinned NDK and host bootstrap require Linux x86_64')
    if not args.plan:
        check_jobs(1)
    work = args.work_root.absolute()
    if any(not (c.isalnum() or c in '/._- ') for c in str(work)):
        raise ValueError('--work-root contains characters unsafe for Boost.Jam configuration')
    if work.exists() or work.is_symlink() or not work.parent.is_dir() or work.parent.resolve() != work.parent:
        raise ValueError('--work-root must be fresh with an existing, non-symlink parent')
    tools = {name: shutil.which(value) for name, value in {'cmake': args.cmake, 'ninja': args.ninja, 'make': 'make', 'perl': 'perl', 'gcc': 'gcc', 'g++': 'g++', 'sh': 'sh'}.items()}
    if not all(tools.values()):
        raise ValueError('Missing host tools: cmake, ninja, make, perl, gcc, g++, sh are required')
    tools = {name: str(Path(value).resolve()) for name, value in tools.items()}
    archives = {name: getattr(args, name + '_archive').resolve(strict=True) for name in PINS}
    for name, path in archives.items():
        if not path.is_file() or digest(path) != PINS[name][1]:
            raise ValueError(f'{name} archive does not match its pinned SHA-256')
    stage = args.provider_stage.resolve(strict=True)
    manifest = json.loads((stage / 'assets/fta-runtime-manifest.json').read_text())
    lock = json.loads((REPO / 'manifests/android-provider-lock.json').read_text())
    if manifest['providerId'] != lock['providerId'] or manifest['abi'] != 'arm64-v8a' or lock['packages']['openssl']['version'] != '1:3.6.3':
        raise ValueError('Provider identity or OpenSSL version mismatch')
    libraries = {}
    for name in ('libssl.so.3', 'libcrypto.so.3', 'libz.so.1'):
        records = [item for item in manifest['files'] if item.get('logicalName') == name]
        pins = [item for item in lock['nativeFiles'] if item.get('logicalName') == name]
        if len(records) != 1 or len(pins) != 1:
            raise ValueError('Missing or duplicate provider library')
        item, pin = records[0], pins[0]
        relative = safe_name(item['path'])
        if relative.parts != ('native', pin['apkName']):
            raise ValueError('Provider library has an invalid APK route')
        path = stage / 'jniLibs/arm64-v8a' / relative.name
        if path.is_symlink() or not path.resolve().is_relative_to(stage) or not path.is_file() or path.stat().st_size != pin['size'] or digest(path) != pin['sha256'] or item['sha256'] != pin['sha256']:
            raise ValueError(f'Provider hash/size mismatch: {name}')
        libraries[name] = path
    ndk, boost, ssl, connector = [work / 'sources' / PINS[key][2] for key in PINS]
    prefix, build = work / 'android-deps', work / 'build'
    binary = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    env = {key: value for key, value in os.environ.items() if key in ('HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'LD_LIBRARY_PATH')}
    env.update(PATH=str(binary) + ':/usr/local/bin:/usr/bin:/bin', ANDROID_NDK_ROOT=str(ndk))
    headers, conn, jam = build / 'openssl-headers', build / 'connector', build / 'boost-user-config.jam'
    commands = [
        ([tools['perl'], str(ssl / 'Configure'), 'android-arm64', '--prefix=' + str(prefix), '--openssldir=' + str(prefix / 'etc/tls'), 'shared', 'zlib-dynamic', 'no-ssl', 'no-hw', 'no-srp', 'no-tests', 'enable-tls1_3', '-D__ANDROID_API__=33'], headers),
        ([tools['make'], '-j1', 'build_generated'], headers),
        ([tools['cmake'], '-S', str(connector), '-B', str(conn), '-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + tools['ninja'], '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'), '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-33', '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_INSTALL_PREFIX=' + str(prefix), '-DOPENSSL_INCLUDE_DIR=' + str(prefix / 'include'), '-DOPENSSL_SSL_LIBRARY=' + str(prefix / 'lib/libssl.so'), '-DOPENSSL_CRYPTO_LIBRARY=' + str(prefix / 'lib/libcrypto.so'), '-DWITH_UNIT_TESTS=OFF', '-DWITH_CURL=OFF', '-DWITH_ICONV=OFF', '-DWITH_SSL=OPENSSL', '-DWITH_MYSQLCOMPAT=ON', '-DCMAKE_COMPILE_WARNING_AS_ERROR=OFF'], build),
        ([tools['cmake'], '--build', str(conn), '--parallel', '1'], build),
        ([tools['cmake'], '--install', str(conn)], build),
        ([tools['sh'], './bootstrap.sh', '--with-toolset=gcc', '--with-libraries=filesystem,program_options,iostreams,regex', '--prefix=' + str(prefix)], boost),
        ([str(boost / 'b2'), '--user-config=' + str(jam), '--prefix=' + str(prefix), '--build-dir=' + str(build / 'boost'), '--layout=system', '-j1', 'toolset=clang-android', 'target-os=android', 'architecture=arm', 'address-model=64', 'abi=aapcs', 'binary-format=elf', 'variant=release', 'threading=multi', 'link=static', 'runtime-link=static', 'cxxstd=20', 'cxxflags=-fPIC', '--with-filesystem', '--with-program_options', '--with-iostreams', '--with-regex', '-sNO_BZIP2=1', '-sNO_LZMA=1', '-sNO_ZSTD=1', '-sNO_ICU=1', '-sZLIB_INCLUDE=' + str(binary.parent / 'sysroot/usr/include'), '-sZLIB_LIBPATH=' + str(binary.parent / 'sysroot/usr/lib/aarch64-linux-android/33'), 'install'], boost),
    ]
    receipt = {'formatVersion': 1, 'platform': 'arm64-v8a/android-33', 'sourcePins': PINS, 'archives': {key: str(value) for key, value in archives.items()}, 'providerLibraries': {name: digest(path) for name, path in libraries.items()}, 'jobs': 1, 'python': sys.version, 'host': platform.platform(), 'environment': {key: env[key] for key in ('PATH', 'ANDROID_NDK_ROOT')}, 'commands': [{'argv': argv, 'cwd': str(cwd)} for argv, cwd in commands], 'disabledOptionalBoostFeatures': ['bzip2', 'lzma', 'zstd', 'icu'], 'runtimeQualified': False, 'completed': False}
    receipt['hostLoaderPathConfigured'] = bool(env.get('LD_LIBRARY_PATH'))
    if args.plan:
        print(json.dumps(receipt, indent=2))
        return
    work.mkdir()
    logs = work / 'logs'
    logs.mkdir()
    def save():
        (work / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    def run(argv, cwd, label):
        check_jobs(1)
        event = {'argv': argv, 'cwd': str(cwd), 'log': str(logs / (label + '.log'))}
        with (logs / 'executed.jsonl').open('a') as events:
            events.write(json.dumps(event) + '\n')
        with Path(event['log']).open('wb') as output:
            subprocess.run(argv, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT, check=True)
    save()
    for name, archive in archives.items():
        extract(archive, work / 'sources', PINS[name][2])
    headers.mkdir(parents=True)
    (prefix / 'include/openssl').mkdir(parents=True)
    (prefix / 'lib').mkdir()
    for name, path in libraries.items():
        shutil.copyfile(path, prefix / 'lib' / name)
        (prefix / 'lib' / (name.split('.so')[0] + '.so')).symlink_to(name)
    jam.write_text(f'using clang : android : "{binary}/aarch64-linux-android33-clang++" : <archiver>"{binary}/llvm-ar" <ranlib>"{binary}/llvm-ranlib" ;\n')
    for name, tool in tools.items():
        run([tool, '-v' if name == 'perl' else '--version'], work, 'version-' + name) if name != 'sh' else None
    run([str(binary / 'clang'), '--version'], work, 'version-clang')
    for index, (argv, cwd) in enumerate(commands):
        run(argv, cwd, f'{index:02d}')
        if index == 1:
            for include in (ssl / 'include/openssl', headers / 'include/openssl'):
                for path in include.glob('*.h'):
                    shutil.copyfile(path, prefix / 'include/openssl' / path.name)
        if index == 5:
            run([str(boost / 'b2'), '--version'], boost, 'version-b2')
    receipt['libraryHashes'] = {str(path.relative_to(prefix)): digest(path) for path in sorted(prefix.rglob('*')) if path.is_file() and not path.is_symlink() and ('.so' in path.name or path.suffix == '.a')}
    receipt['completed'] = True
    save()
    print(work / 'receipt.json')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, tarfile.TarError, zipfile.BadZipFile) as error:
        sys.exit(f'build-dependencies: {error}')
