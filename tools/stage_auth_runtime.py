#!/usr/bin/env python3
"""Extend a verified provider stage with a real, privately built ARM64 authserver."""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

from stage_android_provider import (CHUNK, MAX_FILE, ProviderError, SYSTEM_LIBS,
                                    check_closure, digest, inspect_elf, safe_name)

ROOT = Path(__file__).resolve().parents[1]
SEED_LOCK = ROOT / 'manifests/auth-seed-lock.json'
PORT_PROFILES = ROOT / 'native-port/profiles.json'
PROVIDER_LOCK = ROOT / 'manifests/android-provider-lock.json'
AUTH_NAME = 'libfta_authserver.so'
SEED_PATH = 'data/metadata/auth-seed-lock.json'
AUTH_SYSTEM_LIBS = SYSTEM_LIBS | {'libz.so'}  # Public Android NDK zlib, distinct from private libz.so.1.


def strict_json(path: Path, maximum: int = 2 * CHUNK) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > maximum:
        raise ProviderError('Required JSON evidence is missing, linked, or oversized')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ProviderError('JSON evidence contains a duplicate key')
            result[key] = value
        return result
    value = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique)
    if not isinstance(value, dict):
        raise ProviderError('JSON evidence must be an object')
    return value


def verified_file(path: Path, record: dict) -> None:
    if (path.is_symlink() or not path.is_file() or path.stat().st_size != record.get('size')
            or not isinstance(record.get('sha256'), str)
            or not re.fullmatch(r'[0-9a-f]{64}', record['sha256'])
            or path.stat().st_size > MAX_FILE or digest(path) != record['sha256']):
        raise ProviderError('Runtime file differs from its recorded identity')


def stage_path(root: Path, record: dict) -> Path:
    name = safe_name(record['path'])
    if name.startswith('native/') and name.count('/') == 1:
        if not re.fullmatch(r'lib[A-Za-z0-9_+.-]+\.so', name.removeprefix('native/')):
            raise ProviderError('Native APK filename is invalid')
        return root / 'jniLibs/arm64-v8a' / name.removeprefix('native/')
    if name.startswith('data/'):
        return root / 'assets/runtime' / name
    raise ProviderError('Unknown runtime file route')


def verify_provider(root: Path, readelf: str) -> tuple[dict, dict, dict[str, str]]:
    if root.is_symlink() or not root.is_dir():
        raise ProviderError('Provider stage must be an existing regular directory')
    manifest = strict_json(root / 'assets/fta-runtime-manifest.json')
    provenance = strict_json(root / 'BUILD_PROVENANCE.json')
    provider_pin = strict_json(PROVIDER_LOCK)
    if (manifest.get('formatVersion') != 1 or manifest.get('abi') != 'arm64-v8a'
            or set(manifest.get('capabilities', [])) != {'database'}
            or manifest.get('providerId') != provider_pin['providerId']):
        raise ProviderError('Auth extension requires a database-only ARM64 provider stage')
    if provenance.get('noticeProvided') is not True or provenance.get('redistributionNoticePending') is not False:
        raise ProviderError('Provider redistribution notices have not been staged')
    files = manifest.get('files')
    if not isinstance(files, list) or not files or len(files) > 4096:
        raise ProviderError('Invalid base provider file inventory')
    allowed = {'assets/fta-runtime-manifest.json', 'BUILD_PROVENANCE.json'}
    hashes = {}
    native = []
    for record in files:
        path = stage_path(root, record)
        relative = path.relative_to(root).as_posix()
        if relative.casefold() in {name.casefold() for name in allowed}:
            raise ProviderError('Duplicate base provider file')
        verified_file(path, record)
        allowed.add(relative)
        hashes[relative] = record['sha256']
        if record['path'].startswith('native/'):
            actual = inspect_elf(path, readelf)
            if actual['needed'] != record.get('needed', []):
                raise ProviderError('Base provider ELF dependencies differ from the manifest')
            native.append({**record, **actual})
    check_closure(native)
    expected_native = {item['apkName']: (item['sha256'], item['size'], item['role'], item.get('logicalName'))
                       for item in provider_pin['nativeFiles']}
    actual_native = {item['path'].removeprefix('native/'): (item['sha256'], item['size'], item['role'], item.get('logicalName'))
                     for item in native}
    if actual_native != expected_native:
        raise ProviderError('Base provider native bytes differ from the pinned distribution')
    if manifest.get('bootstrap', {}).get('sha256') != provider_pin['bootstrap']['sha256']:
        raise ProviderError('Base provider bootstrap identity differs from the pinned distribution')
    roles = [record['role'] for record in native]
    if roles.count('database-server') != 1 or roles.count('database-client') != 1:
        raise ProviderError('Base provider executable inventory is incomplete')
    legal = provenance.get('legalFiles')
    if not isinstance(legal, list) or not legal:
        raise ProviderError('Provider legal file inventory is missing')
    expected_legal = {item['name']: (item['sha256'], item['size']) for item in provider_pin['legalFiles']}
    if {item['name']: (item['sha256'], item['size']) for item in legal} != expected_legal:
        raise ProviderError('Base provider legal bytes differ from the pinned notice set')
    for record in legal:
        name = safe_name(record['name'])
        if '/' in name:
            raise ProviderError('Unsafe provider legal filename')
        relative = 'assets/runtime/legal/' + name
        if relative in allowed:
            raise ProviderError('Duplicate provider legal filename')
        verified_file(root / relative, record)
        allowed.add(relative)
        hashes[relative] = record['sha256']
    for path in root.rglob('*'):
        if path.is_symlink():
            raise ProviderError('Provider stage contains a filesystem link')
        if path.is_file() and path.relative_to(root).as_posix() not in allowed:
            raise ProviderError('Provider stage contains an undeclared payload')
    return manifest, provenance, hashes


def verify_build_report(report: dict, authserver: Path) -> dict:
    if (report.get('schema_version') != 1 or report.get('platform') != 'android'
            or report.get('status') != 'build-passed' or report.get('stage') != 'auth'
            or report.get('source_preserved') is not True or report.get('required_module_count') != 52
            or report.get('target_abi') != 'arm64-v8a'):
        raise ProviderError('Authserver requires a completed source-preserving Android ARM64 build report')
    configuration = report.get('configuration', {})
    for key, expected in {'CMAKE_BUILD_TYPE': 'Release', 'CMAKE_CXX_STANDARD': '20',
                          'MODULES': 'static', 'SCRIPTS': 'static',
                          'CMAKE_SYSTEM_NAME': 'Android', 'APPS_BUILD': 'auth-only'}.items():
        if configuration.get(key) != expected:
            raise ProviderError('Authserver build configuration does not preserve the required profile')
    profiles = strict_json(PORT_PROFILES)
    profile = report.get('port_profile')
    expected = profiles.get('profiles', {}).get(profile, {}).get('result_inventory', {}).get('tree_sha256')
    if not expected or report.get('source_tree_sha256') != expected:
        raise ProviderError('Authserver source identity does not match a reviewed port profile')
    artifact = report.get('artifacts', {}).get('authserver')
    if not isinstance(artifact, dict):
        raise ProviderError('Build report lacks the exact authserver artifact identity')
    verified_file(authserver, artifact)
    return {'platform': 'android', 'targetAbi': report['target_abi'],
            'configuration': configuration, 'sourceTreeSha256': expected,
            'portProfile': profile, 'authserverSha256': artifact['sha256'],
            'authserverSize': artifact['size'], 'sourcePreserved': True,
            'requiredModuleCount': 52, 'runtimeQualified': False}


def collect_libraries(root: Path, readelf: str) -> dict[str, list[tuple[Path, dict]]]:
    if root.is_symlink() or not root.is_dir():
        raise ProviderError('Target library directory is unavailable')
    candidates = {}
    seen = set()
    count = 0
    base = root.resolve()
    for path in root.rglob('*'):
        count += 1
        if count > 20000:
            raise ProviderError('Target library directory entry limit exceeded')
        if not path.is_file():
            continue
        real = path.resolve()
        if not real.is_relative_to(base):
            raise ProviderError('Target library link leaves its declared directory')
        if real in seen:
            continue
        seen.add(real)
        if real.stat().st_size > MAX_FILE:
            raise ProviderError('Target library exceeds its size limit')
        with real.open('rb') as stream:
            if stream.read(4) != b'\x7fELF':
                continue
        metadata = inspect_elf(real, readelf)
        if metadata['interpreter'] is not None:
            raise ProviderError('Target dependency directory contains an executable instead of a library')
        for name in metadata['sonames']:
            candidates.setdefault(name, []).append((real, metadata))
    return candidates


def dependency_records(value: dict | None) -> dict[str, dict]:
    if value is None:
        return {}
    if value.get('formatVersion') != 1 or not isinstance(value.get('libraries'), list):
        raise ProviderError('Target dependency provenance has an unsupported format')
    result = {}
    for entry in value['libraries']:
        name = entry.get('logicalName')
        source = entry.get('source', {})
        if (not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_+.-]+', name) or name in result
                or not re.fullmatch(r'[0-9a-f]{64}', entry.get('sha256', ''))
                or not isinstance(entry.get('license'), str) or not entry['license']
                or not all(isinstance(source.get(key), str) and source[key] for key in ('project', 'version', 'url', 'sha256'))
                or not re.fullmatch(r'[0-9a-f]{64}', source['sha256']) or not source['url'].startswith('https://')):
            raise ProviderError('Target dependency provenance is incomplete or ambiguous')
        result[name] = entry
    return result


def extend_closure(authserver: Path, base_files: list[dict], candidates: dict,
                   provenance: dict[str, dict], readelf: str) -> tuple[list[dict], dict[str, Path]]:
    metadata = inspect_elf(authserver, readelf)
    if metadata['interpreter'] != '/system/bin/linker64':
        raise ProviderError('Authserver is not an Android Bionic executable')
    auth = {'path': 'native/' + AUTH_NAME, 'role': 'auth-server', 'sha256': digest(authserver),
            'size': authserver.stat().st_size, **metadata}
    base_logical = {item['logicalName']: item for item in base_files if item.get('logicalName')}
    records = [auth]
    sources = {AUTH_NAME: authserver}
    queue = list(metadata['needed'])
    handled = set()
    while queue:
        logical = queue.pop(0)
        if logical in handled or logical in AUTH_SYSTEM_LIBS:
            continue
        handled.add(logical)
        matches = candidates.get(logical, [])
        hashes = {digest(path) for path, _ in matches}
        if len(hashes) > 1:
            raise ProviderError('Target dependency has conflicting ELF candidates')
        existing = base_logical.get(logical)
        if existing:
            if hashes and hashes != {existing['sha256']}:
                raise ProviderError('Auth dependency conflicts with the pinned provider library')
            continue
        if not matches:
            raise ProviderError('Authserver has an unresolved target dependency')
        path, info = matches[0]
        evidence = provenance.get(logical)
        actual = next(iter(hashes))
        if evidence is None or evidence['sha256'] != actual:
            raise ProviderError('Added target library lacks matching build/source provenance')
        name = 'libfta_authdep_' + re.sub(r'[^A-Za-z0-9]+', '_', logical).strip('_') + '_' + actual[:10] + '.so'
        if name in sources or any(item['path'] == 'native/' + name for item in base_files):
            raise ProviderError('Auth dependency APK filename collides with another payload')
        records.append({'path': 'native/' + name, 'role': 'library', 'logicalName': logical,
                        'sha256': actual, 'size': path.stat().st_size, **info})
        sources[name] = path
        queue.extend(info['needed'])
    combined = base_files + records
    logical_names = [entry['logicalName'] for entry in combined if entry.get('logicalName')]
    if len(set(logical_names)) != len(logical_names):
        raise ProviderError('Combined runtime has duplicate logical dependencies')
    if any(name not in set(logical_names) | AUTH_SYSTEM_LIBS for entry in combined for name in entry['needed']):
        raise ProviderError('Combined auth runtime has an unresolved ELF dependency')
    return records, sources


def stage(provider_stage: Path, authserver: Path, target_libs: Path, build_report: Path,
          seed_lock: Path, output: Path, *, dependency_provenance: Path | None = None,
          readelf: str = 'readelf') -> dict:
    if output.exists() or output.is_symlink():
        raise ProviderError('Extended runtime output must not already exist')
    destination = output.resolve()
    if destination.is_relative_to(provider_stage.resolve()) or destination.is_relative_to(target_libs.resolve()):
        raise ProviderError('Extended runtime output must remain outside all input trees')
    if not authserver.is_file() or authserver.is_symlink():
        raise ProviderError('A real regular authserver ELF is required')
    manifest, provider_provenance, base_hashes = verify_provider(provider_stage, readelf)
    report = strict_json(build_report)
    build = verify_build_report(report, authserver)
    if seed_lock.is_symlink() or digest(seed_lock) != digest(SEED_LOCK):
        raise ProviderError('Auth seed metadata differs from the reviewed public lock')
    seed = strict_json(seed_lock, maximum=64 * 1024)
    if seed.get('formatVersion') != 1 or seed.get('database') != 'acore_auth':
        raise ProviderError('Auth seed metadata has an unsupported scope')
    native_files = [item for item in manifest['files'] if item['path'].startswith('native/')]
    dep_evidence = dependency_records(strict_json(dependency_provenance) if dependency_provenance else None)
    added, sources = extend_closure(authserver, native_files, collect_libraries(target_libs, readelf), dep_evidence, readelf)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.fta-auth-runtime-', dir=output.parent))
    try:
        staged = temporary / 'stage'
        shutil.copytree(provider_stage, staged)
        for name, source in sources.items():
            target = staged / 'jniLibs/arm64-v8a' / name
            if target.exists():
                raise ProviderError('Added runtime payload would replace a provider file')
            shutil.copyfile(source, target)
            target.chmod(0o755)
        data = staged / 'assets/runtime' / SEED_PATH
        if data.exists():
            raise ProviderError('Auth seed metadata would replace provider data')
        data.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(seed_lock, data)
        updated = deepcopy(manifest)
        updated['files'].extend(added + [{'path': SEED_PATH, 'role': 'data', 'sha256': digest(data), 'size': data.stat().st_size}])
        updated['capabilities'] = ['database', 'authserver']
        for relative, expected in base_hashes.items():
            if digest(staged / relative) != expected:
                raise ProviderError('Provider payload changed while extending the runtime')
        for entry in added:
            verified_file(stage_path(staged, entry), entry)
        (staged / 'assets/fta-runtime-manifest.json').write_text(json.dumps(updated, indent=2) + '\n')
        evidence = {'formatVersion': 1, 'distribution': 'private-user-apk-only',
                    'publicDistributionQualified': False, 'androidRuntimeExecuted': False,
                    'worldserverIncluded': False, 'authBuild': build,
                    'buildReportSha256': digest(build_report), 'providerManifestSha256': digest(provider_stage / 'assets/fta-runtime-manifest.json'),
                    'authSeedLockSha256': digest(data), 'addedLibraries': [item for name, item in dep_evidence.items()
                        if any(entry.get('logicalName') == name for entry in added)],
                    'basePayloadsPreserved': True, 'baseProviderProvenance': provider_provenance}
        (staged / 'AUTH_BUILD_PROVENANCE.json').write_text(json.dumps(evidence, indent=2) + '\n')
        os.rename(staged, output)
        return {key: evidence[key] for key in ('distribution', 'androidRuntimeExecuted', 'worldserverIncluded', 'basePayloadsPreserved')}
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provider-stage', type=Path, required=True)
    parser.add_argument('--authserver', type=Path, required=True)
    parser.add_argument('--target-libs', type=Path, required=True)
    parser.add_argument('--build-report', type=Path, required=True)
    parser.add_argument('--auth-seed-lock', type=Path, default=SEED_LOCK)
    parser.add_argument('--dependency-provenance', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--readelf', default='readelf')
    args = parser.parse_args()
    try:
        result = stage(args.provider_stage, args.authserver, args.target_libs, args.build_report,
                       args.auth_seed_lock, args.output, dependency_provenance=args.dependency_provenance,
                       readelf=args.readelf)
        print(json.dumps({'status': 'staged', **result}, indent=2))
    except (ProviderError, OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({'status': 'blocked', 'error': str(error) if isinstance(error, ProviderError)
                          else type(error).__name__, 'androidRuntimeExecuted': False}))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
