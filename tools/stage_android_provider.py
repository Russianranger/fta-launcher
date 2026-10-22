#!/usr/bin/env python3
"""Stage a hash-pinned Bionic/ARM64 database provider without changing ELF bytes.

The package cache and output directory are external build inputs/outputs. Nothing
is executed from the input distribution and no database is initialized here.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen
import zipfile

LOCK = Path(__file__).resolve().parents[1] / 'manifests/android-provider-lock.json'
LEGAL = Path(__file__).resolve().parents[1] / 'licenses/android-provider'
CHUNK = 1024 * 1024
MAX_FILE = 256 * CHUNK
MAX_EXPANDED = 2 * 1024 * CHUNK
MAX_ENTRIES = 50000
PREFIX = 'data/data/com.termux/files/usr/'
SYSTEM_LIBS = {'libc.so', 'libdl.so', 'libm.so', 'liblog.so', 'libandroid.so'}


class ProviderError(ValueError):
    pass


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(CHUNK), b''):
            value.update(chunk)
    return value.hexdigest()


def safe_name(name: str, *, leading_dot: bool = False) -> str:
    if leading_dot and name.startswith('./'):
        name = name[2:]
    parts = name.rstrip('/').split('/')
    if (not name or name.startswith('/') or '\\' in name or ':' in name or '\x00' in name
            or any(part in ('', '.', '..') for part in parts)):
        raise ProviderError('Distribution contains an unsafe path')
    return '/'.join(parts)


def copy_stream(source, destination: Path, size: int) -> None:
    if size < 0 or size > MAX_FILE:
        raise ProviderError('Distribution member exceeds its size limit')
    destination.parent.mkdir(parents=True, exist_ok=True)
    actual = 0
    with destination.open('xb') as output:
        while block := source.read(CHUNK):
            actual += len(block)
            if actual > size:
                raise ProviderError('Distribution member exceeds its declared size')
            output.write(block)
    if actual != size:
        raise ProviderError('Distribution member is truncated')


def fetch_pinned(url: str, destination: Path, expected: str, *, offline: bool = False,
                 maximum: int = 512 * CHUNK) -> None:
    if destination.is_symlink():
        raise ProviderError('Package cache entry must not be a symlink')
    if destination.exists():
        if digest(destination) != expected:
            raise ProviderError('Cached distribution SHA256 differs from the lock')
        return
    if offline:
        raise ProviderError('Required pinned distribution is absent from the offline cache')
    parsed = urlsplit(url)
    if parsed.scheme != 'https' or not parsed.netloc or parsed.username or parsed.password:
        raise ProviderError('Distribution download must use an HTTPS URL without credentials')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix='.fta-download-', dir=destination.parent, delete=False) as output:
        temporary = Path(output.name)
        value = hashlib.sha256()
        total = 0
        try:
            request = Request(url, headers={'User-Agent': 'FTA pinned-provider builder'})
            with urlopen(request, timeout=60) as source:
                while block := source.read(CHUNK):
                    total += len(block)
                    if total > maximum:
                        raise ProviderError('Pinned distribution download exceeds its size limit')
                    value.update(block)
                    output.write(block)
            output.flush()
            os.fsync(output.fileno())
            if value.hexdigest() != expected:
                raise ProviderError('Downloaded distribution SHA256 differs from the lock')
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)


def deb_data(path: Path) -> bytes:
    """Read the data tar member from a Debian ar file without invoking a shell."""
    with path.open('rb') as source:
        if source.read(8) != b'!<arch>\n':
            raise ProviderError('Package is not a Debian ar archive')
        result = None
        count = 0
        while header := source.read(60):
            count += 1
            if count > 20 or len(header) != 60 or header[58:] != b'`\n':
                raise ProviderError('Invalid Debian ar member header')
            try:
                name = header[:16].decode('ascii').strip().rstrip('/')
                size = int(header[48:58].decode('ascii').strip())
            except (ValueError, UnicodeError):
                raise ProviderError('Invalid Debian ar member metadata') from None
            if size < 0 or size > MAX_FILE:
                raise ProviderError('Debian ar member exceeds its size limit')
            member = source.read(size)
            if len(member) != size:
                raise ProviderError('Truncated Debian ar member')
            if size % 2 and len(source.read(1)) != 1:
                raise ProviderError('Truncated Debian ar padding')
            if name.startswith('data.tar.'):
                if result is not None:
                    raise ProviderError('Duplicate Debian data archive')
                result = member
        if result is None:
            raise ProviderError('Debian package has no data archive')
        return result


def extract_deb(path: Path, destination: Path) -> None:
    """Materialize regular files only; links never become filesystem links."""
    with tarfile.open(fileobj=io.BytesIO(deb_data(path)), mode='r:*') as archive:
        seen = set()
        total = 0
        for count, member in enumerate(archive, 1):
            if count > MAX_ENTRIES:
                raise ProviderError('Package entry limit exceeded')
            if member.name in ('.', './') and member.isdir():
                continue
            name = safe_name(member.name, leading_dot=True)
            if name.casefold() in seen:
                raise ProviderError('Package contains duplicate or case-colliding paths')
            seen.add(name.casefold())
            if member.size < 0 or member.size > MAX_FILE:
                raise ProviderError('Package member exceeds its size limit')
            total += member.size
            if total > MAX_EXPANDED:
                raise ProviderError('Package expansion limit exceeded')
            if member.issym() or member.islnk():
                # Original package aliases may be relative, but none are copied.
                # Still reject escape links instead of silently accepting them.
                target = member.linkname
                if member.issym():
                    target = str(PurePosixPath(name).parent / target)
                stack = []
                if target.startswith('/') or '\\' in target or ':' in target:
                    raise ProviderError('Package contains an unsafe link')
                for part in target.split('/'):
                    if part == '..':
                        if not stack:
                            raise ProviderError('Package contains an escaping link')
                        stack.pop()
                    elif part not in ('', '.'):
                        stack.append(part)
                if not '/'.join(stack).startswith(PREFIX.rstrip('/') + '/'):
                    raise ProviderError('Package link leaves the Termux prefix')
                continue
            if member.isdir():
                continue
            if not member.isreg():
                raise ProviderError('Package contains a device or special file')
            target = destination.joinpath(*PurePosixPath(name).parts)
            with archive.extractfile(member) as source:
                copy_stream(source, target, member.size)
            target.chmod(0o644)


def inspect_elf(path: Path, readelf: str = 'readelf', *, reject_search_paths: bool = False) -> dict:
    try:
        output = subprocess.check_output([readelf, '-h', '-lW', '-dW', str(path)],
                                         text=True, stderr=subprocess.STDOUT, timeout=20)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError):
        raise ProviderError('ELF metadata inspection failed') from None
    if not re.search(r'Class:\s+ELF64', output) or not re.search(r'Machine:\s+AArch64', output):
        raise ProviderError('Native provider must contain ELF64 AArch64 binaries')
    if not re.search(r'Type:\s+DYN\b', output):
        raise ProviderError('Native provider must use position-independent ELF files')
    if reject_search_paths and re.search(r'\((?:RPATH|RUNPATH)\)', output):
        raise ProviderError('Native runtime must not embed a runtime library search path')
    interpreters = re.findall(r'Requesting program interpreter:\s*([^\]]+)\]', output)
    if interpreters and interpreters != ['/system/bin/linker64']:
        raise ProviderError('Native executable does not use Android Bionic linker64')
    needed = re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', output)
    sonames = re.findall(r'\(SONAME\).*?\[([^\]]+)\]', output)
    if any('/' in name or not re.fullmatch(r'[A-Za-z0-9_+.-]+', name) for name in needed + sonames):
        raise ProviderError('ELF contains an unsafe dependency name')
    alignments = []
    for line in output.splitlines():
        fields = line.split()
        if fields and fields[0] == 'LOAD':
            try:
                alignment = int(fields[-1], 16)
            except ValueError:
                raise ProviderError('Invalid ELF load alignment') from None
            if alignment < 4096 or alignment & (alignment - 1):
                raise ProviderError('Invalid ELF load alignment')
            alignments.append(alignment)
    if not alignments:
        raise ProviderError('ELF has no loadable segments')
    return {'needed': sorted(set(needed)), 'sonames': sonames,
            'interpreter': interpreters[0] if interpreters else None,
            'minimumLoadAlignment': min(alignments)}


def check_closure(records: list[dict]) -> None:
    logical = {record['logicalName'] for record in records if record.get('logicalName')}
    if len(logical) != sum(bool(record.get('logicalName')) for record in records):
        raise ProviderError('Duplicate logical provider library')
    for record in records:
        if record['role'] in ('database-server', 'database-client') and record.get('interpreter') != '/system/bin/linker64':
            raise ProviderError('Provider executable is missing the Android interpreter')
        if any(name not in logical | SYSTEM_LIBS for name in record['needed']):
            raise ProviderError('Provider has an unresolved or non-Android ELF dependency')


def verify_native(path: Path, pin: dict, readelf: str) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size != pin['size'] or digest(path) != pin['sha256']:
        raise ProviderError('Native provider binary differs from the pinned identity')
    metadata = inspect_elf(path, readelf)
    # Two pinned Termux libraries intentionally omit DT_SONAME; the DT_NEEDED
    # lookup name still maps to their immutable APK entry through a symlink.
    if pin.get('logicalName') and metadata['sonames'] and pin['logicalName'] not in metadata['sonames']:
        raise ProviderError('Provider library SONAME differs from the lock')
    record = {key: pin[key] for key in ('sha256', 'size', 'role')}
    record.update(path='native/' + pin['apkName'], **metadata)
    if pin.get('logicalName'):
        record['logicalName'] = pin['logicalName']
    return record


def bootstrap(share: Path, pin: dict) -> bytes:
    texts = []
    for name in pin['inputs']:
        if safe_name(name) != name or '/' in name:
            raise ProviderError('Invalid bootstrap input name')
        path = share / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size > 16 * CHUNK:
            raise ProviderError('Pinned bootstrap input is absent or oversized')
        expected = pin.get('inputSha256', {}).get(name)
        if expected and digest(path) != expected:
            raise ProviderError('Bootstrap component differs from the pinned identity')
        texts.append(path.read_text(encoding='utf-8'))
    body = 'create database if not exists mysql;\nuse mysql;\nSET @auth_root_socket=NULL;\n' + '\n'.join(texts)
    placeholders = re.findall(r'@([A-Za-z0-9_]+)@', body)
    if any('_' in token or token.upper() == token or token in ('prefix', 'bindir', 'libdir', 'sysconfdir')
           for token in placeholders):
        raise ProviderError('Unresolved installer placeholder in bootstrap SQL')
    result = (body.rstrip() + '\n').encode('utf-8')
    if hashlib.sha256(result).hexdigest() != pin['sha256']:
        raise ProviderError('Bootstrap SQL differs from the pinned identity')
    return result


def apk_inputs(path: Path, destination: Path, lock: dict) -> tuple[dict[str, Path], Path]:
    pin = lock['fallbackApk']
    if path.stat().st_size != pin['size'] or digest(path) != pin['sha256']:
        raise ProviderError('Fallback APK differs from its pinned release identity')
    native = {}
    share = destination / 'share'
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ProviderError('APK entry limit exceeded')
        seen = set()
        total = 0
        wanted = {'lib/arm64-v8a/' + item['sourceApkName']: item for item in lock['nativeFiles']}
        prefix = 'assets/database/provider/share/mysql/'
        for info in infos:
            name = safe_name(info.filename)
            if name.casefold() in seen:
                raise ProviderError('APK contains duplicate or case-colliding paths')
            seen.add(name.casefold())
            mode = info.external_attr >> 16
            if info.flag_bits & 1 or stat.S_ISLNK(mode) or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise ProviderError('APK contains encrypted or special members')
            # Unrelated runtime payloads are never opened. Bound their declared
            # total while retaining the tighter limit for copied members below.
            if info.file_size < 0 or info.file_size > MAX_EXPANDED:
                raise ProviderError('APK member exceeds its size limit')
            total += info.file_size
            if total > MAX_EXPANDED:
                raise ProviderError('APK expansion limit exceeded')
            if info.is_dir():
                continue
            target = None
            if name in wanted:
                target = destination / 'native' / wanted[name]['apkName']
                native[wanted[name]['apkName']] = target
            elif name.startswith(prefix):
                target = share.joinpath(*PurePosixPath(name.removeprefix(prefix)).parts)
            if target is not None:
                with archive.open(info) as source:
                    copy_stream(source, target, info.file_size)
        if len(native) != len(wanted):
            raise ProviderError('Fallback APK lacks the complete pinned native provider')
    return native, share


def package_inputs(cache: Path, destination: Path, lock: dict, *, offline: bool,
                   mirror_base: str | None = None) -> tuple[dict[str, Path], Path]:
    roots = {}
    base = (mirror_base or lock['packageBaseUrl']).rstrip('/') + '/'
    for name, pin in lock['packages'].items():
        archive = cache / pin['filename']
        fetch_pinned(base + quote(pin['relativeUrl'], safe='/+'), archive, pin['sha256'], offline=offline)
        root = destination / name
        extract_deb(archive, root)
        roots[name] = root / PREFIX
    native = {}
    for pin in lock['nativeFiles']:
        root = roots[pin['package']]
        if 'packageRelativePath' in pin:
            candidates = [root / pin['packageRelativePath']]
        else:
            candidates = [p for p in (root / 'lib').rglob('*') if p.is_file() and p.stat().st_size == pin['size']]
        matches = [p for p in candidates if p.is_file() and digest(p) == pin['sha256']]
        if len(matches) != 1:
            raise ProviderError('Pinned binary is absent or ambiguous in its package')
        native[pin['apkName']] = matches[0]
    share = roots['mariadb'] / 'share/mariadb'
    if not share.is_dir():
        share = roots['mariadb'] / 'share/mysql'
    return native, share


def legal_inputs(lock: dict, notices_path: Path | None = None) -> list[tuple[dict, Path]]:
    """Require the received notice and full recorded terms before staging bytes."""
    pins = lock.get('legalFiles')
    if not pins or not isinstance(pins, list):
        raise ProviderError('Provider lock lacks required redistribution notices')
    result = []
    names = set()
    for pin in pins:
        name = pin['name']
        if safe_name(name) != name or '/' in name or name in names:
            raise ProviderError('Invalid or duplicate provider notice filename')
        names.add(name)
        source = notices_path if name == 'POCKET_REALM_THIRD_PARTY_NOTICES.md' and notices_path else LEGAL / name
        if (source.is_symlink() or not source.is_file() or source.stat().st_size != pin['size']
                or pin['size'] > CHUNK or digest(source) != pin['sha256']):
            raise ProviderError('Required provider notice is missing or differs from its pinned identity')
        result.append((pin, source))
    required = {'POCKET_REALM_THIRD_PARTY_NOTICES.md', 'FTA_DATABASE_PROVIDER_NOTICES.md',
                'SOURCE_EVIDENCE.json', 'GPL-2.0.txt', 'GPL-3.0.txt', 'Apache-2.0.txt',
                'mariadb-COPYING', 'openssl-LICENSE.txt', 'libcxx-NDK-r29-NOTICE',
                'libandroid-support-LICENSE.txt', 'wcwidth-LICENSE.txt',
                'libcrypt-FreeBSD-NOTICE.txt', 'libedit-COPYING', 'ncurses-COPYING',
                'pcre2-LICENCE.md', 'zlib-LICENSE'}
    if not required <= names:
        raise ProviderError('Provider notice set is incomplete')
    received = next(pin for pin, _ in result if pin['name'] == 'POCKET_REALM_THIRD_PARTY_NOTICES.md')
    if received['sha256'] != lock['fallbackApk']['noticeSha256']:
        raise ProviderError('Received upstream notice identity differs from the release')
    return result


def stage(output: Path, lock: dict, *, package_cache: Path | None = None,
          source_apk: Path | None = None, readelf: str = 'readelf', offline: bool = False,
          mirror_base: str | None = None, notices_path: Path | None = None) -> dict:
    if output.exists() or output.is_symlink():
        raise ProviderError('Provider output must not already exist')
    if not bool(source_apk) ^ bool(package_cache):
        raise ProviderError('Choose exactly one APK input or package cache')
    notices = legal_inputs(lock, notices_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.fta-provider-', dir=output.parent))
    try:
        unpacked = temporary / 'inputs'
        unpacked.mkdir()
        if source_apk:
            native, share = apk_inputs(source_apk, unpacked, lock)
            source = {'kind': 'pinned-apk', 'sha256': lock['fallbackApk']['sha256'],
                      'url': lock['fallbackApk']['url']}
        else:
            native, share = package_inputs(package_cache, unpacked, lock, offline=offline, mirror_base=mirror_base)
            source = {'kind': 'pinned-termux-packages', 'packages':
                      {name: item['sha256'] for name, item in lock['packages'].items()}}
        staged = temporary / 'staged'
        jni = staged / 'jniLibs' / 'arm64-v8a'
        provider = staged / 'assets/runtime/data/provider'
        jni.mkdir(parents=True)
        provider.mkdir(parents=True)
        records = []
        for pin in lock['nativeFiles']:
            path = native[pin['apkName']]
            records.append(verify_native(path, pin, readelf))
            target = jni / pin['apkName']
            shutil.copyfile(path, target)
            target.chmod(0o755)
        check_closure(records)
        body = bootstrap(share, lock['bootstrap'])
        (provider / 'bootstrap.sql').write_bytes(body)
        # Retain original system-table/help assets. Nothing is executable here.
        shutil.copytree(share, provider / 'share/mysql')
        for path in sorted(provider.rglob('*')):
            if path.is_file():
                records.append({'path': 'data/provider/' + path.relative_to(provider).as_posix(),
                                'role': 'data', 'sha256': digest(path), 'size': path.stat().st_size})
        manifest = {'formatVersion': 1, 'providerId': lock['providerId'],
                    'providerVersion': lock['providerVersion'], 'abi': 'arm64-v8a',
                    'capabilities': ['database'], 'files': records,
                    'bootstrap': {'path': 'data/provider/bootstrap.sql', 'sha256': lock['bootstrap']['sha256'], 'size': len(body)},
                    'qualification': {'androidExecuted': False, 'databaseSeedCompatible': False,
                                      'publicRedistributionQualified': False}}
        (staged / 'assets/fta-runtime-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        provenance = {'formatVersion': 1, 'source': source, 'reference': lock['reference'],
                      'bootstrapInputHashes': {name: digest(share / name) for name in lock['bootstrap']['inputs']},
                      'nativeFiles': len(lock['nativeFiles']), 'privateLibraries': len(lock['nativeFiles']) - 2,
                      'elfBytesChanged': False, 'redistributionNoticePending': False,
                      'noticeProvided': True, 'upstreamNoticeSha256': lock['fallbackApk']['noticeSha256'],
                      'sourceEvidence': lock['sourceEvidence'], 'legalFiles': [pin for pin, _ in notices]}
        legal = staged / 'assets/runtime/legal'
        legal.mkdir(parents=True)
        for pin, path in notices:
            shutil.copyfile(path, legal / pin['name'])
        (staged / 'BUILD_PROVENANCE.json').write_text(json.dumps(provenance, indent=2) + '\n')
        os.rename(staged, output)
        return provenance
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--packages-dir', type=Path)
    inputs.add_argument('--from-apk', type=Path)
    inputs.add_argument('--fetch-apk', type=Path, help='Cache path for the exact pinned fallback APK')
    parser.add_argument('--mirror-base')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--readelf', default='readelf')
    parser.add_argument('--notices-path', type=Path, help='Optional byte-identical received upstream notice')
    args = parser.parse_args()
    try:
        lock = json.loads(LOCK.read_text())
        legal_inputs(lock, args.notices_path)
        if args.fetch_apk:
            fetch_pinned(lock['fallbackApk']['url'], args.fetch_apk,
                         lock['fallbackApk']['sha256'], offline=args.offline)
            args.from_apk = args.fetch_apk
        result = stage(args.output, lock, package_cache=args.packages_dir, source_apk=args.from_apk,
                       readelf=args.readelf, offline=args.offline, mirror_base=args.mirror_base,
                       notices_path=args.notices_path)
        print(json.dumps({'status': 'staged', **result}, indent=2))
    except (ProviderError, OSError, ValueError, tarfile.TarError, zipfile.BadZipFile) as error:
        print(json.dumps({'status': 'blocked', 'error': str(error) if isinstance(error, ProviderError)
                          else type(error).__name__, 'androidExecuted': False}))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
