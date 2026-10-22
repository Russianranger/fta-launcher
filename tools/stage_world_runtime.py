#!/usr/bin/env python3
"""Privately stage a completed original ARM64 world build without executing it.

Inputs must include the exact previously pinned database/auth payload, reviewed
source/build evidence and locally retained corresponding-source inputs. Fixture
tests exercise refusals; they never constitute native compilation evidence.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

from stage_android_provider import CHUNK, ProviderError, digest, inspect_elf, safe_name
from stage_auth_runtime import (AUTH_SYSTEM_LIBS, collect_libraries, dependency_records,
                                stage_path, strict_json, verified_file)
from fta_source import SourceError, verify_source_tree
from build_native import BuildError, world_build_evidence

ROOT = Path(__file__).resolve().parents[1]
BASE_LOCK = ROOT / 'manifests/auth-runtime-lock.json'
UPDATER_LOCK = ROOT / 'manifests/world-updater-lock.json'
PORT_PROFILES = ROOT / 'native-port/profiles.json'
SOURCE_EVIDENCE = ROOT / 'licenses/world-runtime/SOURCE_EVIDENCE.json'
WORLD_NAME = 'libfta_worldserver.so'
UPDATER_PATH = 'data/metadata/world-updater-lock.json'
CONFIG_PREFIX = 'data/world-config/modules/'
MIN_ALIGNMENT = 16384


def regular_directory(path: Path) -> None:
    if path.is_symlink() or not path.is_dir():
        raise ProviderError('A regular input directory is required')


def verify_elf(path: Path, record: dict, readelf: str, *, preserved: bool = False) -> dict:
    """Bind all declared ELF metadata, including the minimum 16 KiB alignment."""
    # The unchanged, hash-pinned Termux provider embeds its historical Termux
    # RUNPATH. Newly built world payloads must embed no private/host search path.
    actual = inspect_elf(path, readelf, reject_search_paths=not preserved)
    if actual['minimumLoadAlignment'] < MIN_ALIGNMENT:
        raise ProviderError('World runtime requires 16 KiB ELF LOAD alignment')
    for key in ('needed', 'sonames', 'interpreter', 'minimumLoadAlignment'):
        if key in record and actual[key] != record[key]:
            raise ProviderError('ELF metadata differs from the recorded runtime identity')
    if record.get('role') in ('database-server', 'database-client', 'auth-server', 'world-server'):
        if actual['interpreter'] != '/system/bin/linker64':
            raise ProviderError('Runtime executable must use the Android Bionic interpreter')
    elif actual['interpreter'] is not None:
        raise ProviderError('A runtime library must not be an executable')
    return actual


def check_runtime_closure(native: list[dict]) -> None:
    logical = [item['logicalName'] for item in native if item.get('logicalName')]
    if len(logical) != len(set(logical)):
        raise ProviderError('Runtime has duplicate logical libraries')
    available = set(logical) | AUTH_SYSTEM_LIBS
    if any(name not in available for item in native for name in item['needed']):
        raise ProviderError('Runtime ELF closure contains a missing or non-Android dependency')


def verify_base(root: Path, readelf: str) -> tuple[dict, dict, dict[str, str]]:
    """Require every byte of the known passing 96-file/17-notice private base."""
    regular_directory(root)
    lock = strict_json(BASE_LOCK)
    manifest_path = root / 'assets/fta-runtime-manifest.json'
    manifest = strict_json(manifest_path)
    provenance = strict_json(root / 'BUILD_PROVENANCE.json')
    if (lock.get('formatVersion') != 1 or digest(manifest_path) != lock.get('manifestSha256')
            or manifest.get('formatVersion') != 1 or manifest.get('abi') != 'arm64-v8a'
            or manifest.get('providerId') != lock.get('providerId')
            or manifest.get('capabilities') != ['database', 'authserver']
            or manifest.get('files') != lock.get('files')
            or len(manifest['files']) != lock.get('runtimeFileCount')):
        raise ProviderError('World extension requires the exact reviewed database/auth base')
    if (provenance.get('noticeProvided') is not True
            or provenance.get('redistributionNoticePending') is not False
            or provenance.get('legalFiles') != lock.get('legalFiles')
            or len(provenance['legalFiles']) != lock.get('legalFileCount')):
        raise ProviderError('Base legal notices differ from the exact reviewed inventory')
    allowed = {'assets/fta-runtime-manifest.json', 'BUILD_PROVENANCE.json'}
    hashes = {name: digest(root / name) for name in allowed}
    recovery_path = root / 'AUTH_BUILD_PROVENANCE.json'
    if recovery_path.exists() or recovery_path.is_symlink():
        recovery = strict_json(recovery_path)
        auth = next((record for record in lock['files'] if record['role'] == 'auth-server'), {})
        if (recovery.get('formatVersion') != 1 or recovery.get('currentSessionNativeCompilePerformed') is not False
                or recovery.get('authserverSha256') != auth.get('sha256')
                or recovery.get('historicalAuthSourceProfile') != lock.get('historicalAuthSourceProfile')
                or recovery.get('historicalAuthSourceTreeSha256') != lock.get('historicalAuthSourceTreeSha256')
                or recovery.get('worldserverIncluded') is not False):
            raise ProviderError('Recovered auth evidence does not describe the preserved historical artifact')
        allowed.add(recovery_path.name)
        hashes[recovery_path.name] = digest(recovery_path)
    native = []
    for record in manifest['files']:
        path = stage_path(root, record)
        relative = path.relative_to(root).as_posix()
        if relative.casefold() in {name.casefold() for name in allowed}:
            raise ProviderError('Base runtime has duplicate file routes')
        verified_file(path, record)
        hashes[relative] = record['sha256']
        allowed.add(relative)
        if record['path'].startswith('native/'):
            native.append({**record, **verify_elf(path, record, readelf, preserved=True)})
    roles = [item['role'] for item in native]
    if any(roles.count(role) != 1 for role in ('database-server', 'database-client', 'auth-server')):
        raise ProviderError('Base runtime lacks its original database/auth executables')
    check_runtime_closure(native)
    for record in provenance['legalFiles']:
        name = safe_name(record['name'])
        if '/' in name:
            raise ProviderError('Legal notice name must be flat')
        relative = 'assets/runtime/legal/' + name
        if relative.casefold() in {name.casefold() for name in allowed}:
            raise ProviderError('Base runtime has duplicate legal files')
        verified_file(root / relative, record)
        hashes[relative] = record['sha256']
        allowed.add(relative)
    for path in root.rglob('*'):
        if path.is_symlink():
            raise ProviderError('Base runtime contains a filesystem link')
        if path.is_file() and path.relative_to(root).as_posix() not in allowed:
            raise ProviderError('Base runtime contains an undeclared file')
    return manifest, provenance, hashes


def verify_build_report(report: dict, worldserver: Path, authserver: Path,
                        source: Path, build_dir: Path) -> tuple[dict, dict]:
    if (report.get('schema_version') != 1 or report.get('platform') != 'android'
            or report.get('status') != 'build-passed' or report.get('stage') != 'world'
            or report.get('source_preserved') is not True or report.get('required_module_count') != 52
            or report.get('target_abi') != 'arm64-v8a'):
        raise ProviderError('Worldserver requires a completed source-preserving Android ARM64 world build')
    configuration = report.get('configuration', {})
    expected_config = {'CMAKE_BUILD_TYPE': 'Release', 'CMAKE_CXX_STANDARD': '20',
                       'MODULES': 'static', 'SCRIPTS': 'static', 'CMAKE_SYSTEM_NAME': 'Android',
                       'APPS_BUILD': 'all', 'LUA_VERSION': 'lua52', 'LUA_STATIC': 'ON', 'CONF_DIR': 'configs',
                       'ANDROID_PLATFORM': 'android-33'}
    if any(configuration.get(key) != expected for key, expected in expected_config.items()):
        raise ProviderError('World build does not preserve C++20/all modules/static scripts/default static Lua 5.2')
    if configuration.get('ANDROID_STL', configuration.get('CMAKE_ANDROID_STL_TYPE')) != 'c++_static':
        raise ProviderError('World build must use the reviewed static C++ runtime')
    if configuration.get('ANDROID_ABI', configuration.get('CMAKE_ANDROID_ARCH_ABI')) != 'arm64-v8a':
        raise ProviderError('World build cache must bind the Android ARM64 ABI')
    profile = report.get('port_profile')
    profiles = strict_json(PORT_PROFILES).get('profiles', {})
    expected = profiles.get(profile, {}).get('result_inventory')
    if not expected or report.get('source_tree_sha256') != expected.get('tree_sha256'):
        raise ProviderError('World build source identity differs from a reviewed port profile')
    regular_directory(source)
    actual_inventory = verify_source_tree(source, port_profile=profile)
    if actual_inventory != expected or len(expected.get('modules', [])) != 52:
        raise ProviderError('Actual world source/module inventory differs from the reviewed profile')
    regular_directory(build_dir)
    for name, path in (('worldserver', worldserver), ('authserver', authserver)):
        record = report.get('artifacts', {}).get(name)
        if not isinstance(record, dict) or not path.resolve().is_relative_to(build_dir.resolve()):
            raise ProviderError('Completed world build lacks its actual target artifact')
        verified_file(path, record)
        if Path(record.get('path', '')).resolve() != path.resolve():
            raise ProviderError('Build report artifact path differs from the explicit build input')
    compiled = report.get('compiled_world_evidence')
    if not isinstance(compiled, dict) or compiled != world_build_evidence(build_dir, source, expected['modules']):
        raise ProviderError('World build report differs from its actual CMake target/link evidence')
    if (compiled.get('schema_version') != 1 or compiled.get('world_target_completed') is not True
            or compiled.get('module_names') != expected['modules']
            or set(compiled.get('module_compilation_counts', {})) != set(expected['modules'])
            or any(type(value) is not int or value <= 0 for value in compiled['module_compilation_counts'].values())
            or compiled.get('static_modules') is not True or compiled.get('static_scripts') is not True
            or compiled.get('lua_version') != 'lua52' or compiled.get('lua_static') is not True
            or compiled.get('cxx_standard') != 20
            or compiled.get('linked_static_targets') != ['modules', 'scripts', 'lualib']):
        raise ProviderError('World compiled evidence omits a required module/static target')
    required_targets = {'worldserver': 'EXECUTABLE', 'modules': 'STATIC_LIBRARY',
                        'scripts': 'STATIC_LIBRARY', 'lualib': 'STATIC_LIBRARY'}
    for name, kind in required_targets.items():
        target = compiled.get('target_artifacts', {}).get(name, {})
        path = Path(target.get('path', ''))
        if target.get('type') != kind or not path.resolve().is_relative_to(build_dir.resolve()):
            raise ProviderError('World target evidence has an invalid artifact route/type')
        verified_file(path, target)
    if compiled['target_artifacts']['worldserver']['sha256'] != digest(worldserver):
        raise ProviderError('World CMake target and staged ELF have different identities')
    ncurses = compiled.get('target_dependency_artifacts', {}).get('ncurses')
    pin = next((entry for entry in strict_json(BASE_LOCK)['files'] if entry.get('logicalName') == 'libncursesw.so.6'), None)
    if not isinstance(ncurses, dict) or pin is None or any(ncurses.get(key) != pin[key] for key in ('sha256', 'size')):
        raise ProviderError('World link must use the unchanged pinned provider ncurses library')
    verified_file(Path(ncurses.get('path', '')), ncurses)
    proof = {'platform': 'android', 'targetAbi': 'arm64-v8a', 'configuration': configuration,
             'sourceTreeSha256': expected['tree_sha256'], 'portProfile': profile,
             'worldserverSha256': digest(worldserver), 'worldserverSize': worldserver.stat().st_size,
             'authPrerequisiteSha256': digest(authserver), 'preservedBaseAuthRebuilt': False,
             'sourcePreserved': True, 'requiredModuleCount': 52, 'compiledWorldEvidence': compiled,
             'runtimeQualified': False}
    return proof, expected


def verify_updater_lock(path: Path, build: dict) -> dict:
    value = strict_json(path)
    if digest(path) != digest(UPDATER_LOCK):
        raise ProviderError('Compiled updater metadata differs from the reviewed public lock')
    source = strict_json(SOURCE_EVIDENCE)['sourceArchive']
    if (value.get('formatVersion') != 1 or value.get('sourceProfile') != build['portProfile']
            or value.get('sourceTreeSha256') != build['sourceTreeSha256']
            or value.get('sourceArchiveSha256') != source['sha256']
            or value.get('sourceDirectory') != 'Data' or value.get('moduleCount') != 52
            or value.get('moduleSqlCount') != 0 or len(value.get('runtimeSqlFiles', [])) != 236):
        raise ProviderError('Updater metadata does not bind this complete world source/build')
    return value


def pinned_libraries(value: dict) -> dict[str, dict]:
    evidence = dependency_records(value)
    sources = strict_json(SOURCE_EVIDENCE)
    # Newly added shared libraries are explicitly reviewed, rather than accepting
    # arbitrary source URLs or copying a whole target dependency prefix.
    if set(evidence) - {'libreadline.so.8'}:
        raise ProviderError('World stage has an unreviewed new shared dependency')
    if 'libreadline.so.8' in evidence:
        entry = evidence['libreadline.so.8']
        pin = sources['inputs']['readline']
        expected_source = {'project': 'GNU Readline', **{key: pin[key] for key in ('version', 'url', 'sha256')}}
        patches = [{key: item[key] for key in ('number', 'url', 'sha256')} for item in sources['readlinePatches']]
        if (entry.get('source') != expected_source or entry.get('license') != 'GPL-3.0-or-later'
                or entry.get('builtVersion') != '8.3.3' or entry.get('patches') != patches
                or entry.get('sha256') != sources['readlineLibrary']['sha256']):
            raise ProviderError('Readline provenance differs from the pinned source and three patches')
    return evidence


def extend_closure(worldserver: Path, base: list[dict], candidates: dict,
                   provenance: dict[str, dict], readelf: str) -> tuple[list[dict], dict[str, Path]]:
    metadata = verify_elf(worldserver, {'role': 'world-server'}, readelf)
    if 'libc++_shared.so' in metadata['needed'] or any(name.startswith(('liblua', 'libboost')) for name in metadata['needed']):
        raise ProviderError('World ELF does not preserve the required static C++/Lua/Boost linkage')
    records = [{'path': 'native/' + WORLD_NAME, 'role': 'world-server',
                'sha256': digest(worldserver), 'size': worldserver.stat().st_size, **metadata}]
    sources = {WORLD_NAME: worldserver}
    base_logical = {entry['logicalName']: entry for entry in base if entry.get('logicalName')}
    queue, handled = list(metadata['needed']), set()
    while queue:
        logical = queue.pop(0)
        if logical in handled or logical in AUTH_SYSTEM_LIBS:
            continue
        handled.add(logical)
        matches = candidates.get(logical, [])
        hashes = {digest(path) for path, _ in matches}
        if len(hashes) > 1:
            raise ProviderError('World dependency has conflicting target candidates')
        existing = base_logical.get(logical)
        if existing:
            if hashes and hashes != {existing['sha256']}:
                raise ProviderError('World dependency would replace a preserved provider/auth library')
            continue
        if not matches:
            raise ProviderError('World ELF has an unresolved target dependency')
        path, _ = matches[0]
        info = verify_elf(path, {'role': 'library'}, readelf)
        if logical not in info['sonames']:
            raise ProviderError('New world library has an inconsistent SONAME')
        actual = next(iter(hashes))
        evidence = provenance.get(logical)
        if evidence is None or evidence['sha256'] != actual:
            raise ProviderError('New world library lacks matching pinned build/source provenance')
        name = 'libfta_worlddep_' + re.sub(r'[^A-Za-z0-9]+', '_', logical).strip('_') + '_' + actual[:10] + '.so'
        if name in sources or any(entry['path'] == 'native/' + name for entry in base):
            raise ProviderError('New world dependency collides with an existing APK filename')
        records.append({'path': 'native/' + name, 'role': 'library', 'logicalName': logical,
                        'sha256': actual, 'size': path.stat().st_size, **info})
        sources[name] = path
        queue.extend(info['needed'])
    check_runtime_closure(base + records)
    return records, sources


def legal_inputs(source: Path, source_archive: Path, archives: Path, notices: Path,
                 ndk: Path, inventory: dict) -> list[tuple[dict, Path]]:
    evidence = strict_json(SOURCE_EVIDENCE)
    pin = evidence['sourceArchive']
    verified_file(source_archive, {'sha256': pin['sha256'], 'size': pin['bytes']})
    for root in (archives, notices, ndk):
        regular_directory(root)
    for pin in list(evidence['inputs'].values()) + evidence['readlinePatches']:
        path = archives / safe_name(pin['filename'])
        if path.is_symlink() or not path.is_file() or digest(path) != pin['sha256']:
            raise ProviderError('Corresponding dependency source archive/patch is missing or changed')
    revision = (ndk / 'source.properties').read_text()
    if not re.search(r'^Pkg.Revision\s*=\s*' + re.escape(evidence['ndk']['version']) + r'\s*$', revision, re.M):
        raise ProviderError('World static runtime notice input has the wrong NDK revision')
    header = source / evidence['coreLaterVersionNoticePath']
    text = header.read_text(encoding='utf-8')[:4096]
    if not re.search(r'either version 2.*?any later version', text, re.S):
        raise ProviderError('Original world source lacks its required GPL later-version election')
    result = []
    for record in inventory['license_files'] + [{'path': 'AUTHORS', 'sha256': digest(source / 'AUTHORS')}]:
        relative = safe_name(record['path'])
        path = source / relative
        name = 'world-source-' + re.sub(r'[^A-Za-z0-9_.-]', '_', relative) + '-' + record['sha256'][:10] + '.txt'
        pin = {'name': name, 'sha256': record['sha256'], 'size': path.stat().st_size, 'sourcePath': relative}
        verified_file(path, pin)
        result.append((pin, path))
    roots = {'dependency-notice': notices, 'ndk': ndk, 'repository': SOURCE_EVIDENCE.parent}
    for record in evidence['notices']:
        name = safe_name(record['name'])
        if '/' in name or record.get('sourceKind') not in roots:
            raise ProviderError('World legal evidence has an unsafe route')
        path = roots[record['sourceKind']] / safe_name(record['inputPath'])
        verified_file(path, record)
        result.append((record, path))
    result.append(({'name': 'WORLD_SOURCE_EVIDENCE.json', 'sha256': digest(SOURCE_EVIDENCE),
                    'size': SOURCE_EVIDENCE.stat().st_size}, SOURCE_EVIDENCE))
    names = [record['name'].casefold() for record, _ in result]
    if len(names) != len(set(names)):
        raise ProviderError('World legal evidence has duplicate filenames')
    return result


def module_configs(source: Path, build: dict) -> list[tuple[dict, Path]]:
    result = []
    for path in sorted((source / 'modules').glob('*/conf/*.conf.dist')):
        name = path.name.removesuffix('.dist')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+\.conf', name) or path.is_symlink():
            raise ProviderError('Original module configuration has an unsafe name')
        result.append(({'path': CONFIG_PREFIX + name, 'role': 'data', 'sha256': digest(path),
                        'size': path.stat().st_size}, path))
    names = [record['path'].removeprefix(CONFIG_PREFIX) for record, _ in result]
    if len(names) != len(set(name.casefold() for name in names)):
        raise ProviderError('Original module configurations have colliding filenames')
    compiled = build['compiledWorldEvidence']
    if compiled.get('module_configuration_files') != sorted(names):
        raise ProviderError('Staged module configurations differ from actual CONFIG_MODULES')
    return result


def stage(base_stage: Path, worldserver: Path, authserver: Path, target_libs: Path,
          build_report: Path, source: Path, build_dir: Path, source_archive: Path,
          source_archives: Path, dependency_notices: Path, ndk: Path, updater_lock: Path,
          dependency_provenance: Path, output: Path, *, readelf: str = 'readelf') -> dict:
    if output.exists() or output.is_symlink():
        raise ProviderError('Extended world runtime output must not already exist')
    destination = output.resolve()
    inputs = (base_stage, target_libs, source, build_dir, source_archives, dependency_notices, ndk)
    if any(destination.is_relative_to(path.resolve()) for path in inputs):
        raise ProviderError('World output must remain outside every input tree')
    manifest, base_provenance, base_hashes = verify_base(base_stage, readelf)
    report = strict_json(build_report)
    build, inventory = verify_build_report(report, worldserver, authserver, source, build_dir)
    verify_updater_lock(updater_lock, build)
    dep_evidence = pinned_libraries(strict_json(dependency_provenance))
    native = [record for record in manifest['files'] if record['path'].startswith('native/')]
    added, sources = extend_closure(worldserver, native, collect_libraries(target_libs, readelf), dep_evidence, readelf)
    legal = legal_inputs(source, source_archive, source_archives, dependency_notices, ndk, inventory)
    configs = module_configs(source, build)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.fta-world-runtime-', dir=output.parent))
    try:
        staged = temporary / 'stage'
        shutil.copytree(base_stage, staged)
        for name, path in sources.items():
            target = staged / 'jniLibs/arm64-v8a' / name
            if target.exists():
                raise ProviderError('World stage would replace a preserved native payload')
            shutil.copyfile(path, target)
            target.chmod(0o755)
        data = configs + [({'path': UPDATER_PATH, 'role': 'data', 'sha256': digest(updater_lock),
                           'size': updater_lock.stat().st_size}, updater_lock)]
        for record, path in data:
            target = stage_path(staged, record)
            if target.exists():
                raise ProviderError('World stage would replace preserved data')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            verified_file(target, record)
        for record, path in legal:
            target = staged / 'assets/runtime/legal' / record['name']
            if target.exists():
                raise ProviderError('World notice would replace a preserved legal file')
            shutil.copyfile(path, target)
            verified_file(target, record)
        for relative, expected in base_hashes.items():
            if digest(staged / relative) != expected:
                raise ProviderError('A preserved base file changed during world staging')
        for record in added:
            verified_file(stage_path(staged, record), record)
        updated = deepcopy(manifest)
        updated['files'].extend(added + [record for record, _ in data])
        updated['capabilities'] = ['database', 'authserver', 'worldserver']
        # Only bounded source/configuration identities enter the signed manifest
        # and diagnostic exports. The full CMake paths stay in private proof.
        updated['worldBuild'] = {'formatVersion': 1, 'portProfile': build['portProfile'],
                                 'sourceTreeSha256': build['sourceTreeSha256'],
                                 'sourceArchiveSha256': strict_json(SOURCE_EVIDENCE)['sourceArchive']['sha256'],
                                 'worldserverSha256': build['worldserverSha256'], 'requiredModuleCount': 52,
                                 'cxxStandard': 20, 'staticModules': True, 'staticScripts': True,
                                 'luaVersion': 'lua52', 'luaStatic': True,
                                 'compiledUpdaterLockSha256': digest(updater_lock), 'configurationDirectory': 'configs'}
        (staged / 'assets/fta-runtime-manifest.json').write_text(json.dumps(updated, indent=2) + '\n')
        proof = {'formatVersion': 1, 'distribution': 'private-original-source-owner-only',
                 'publicDistributionQualified': False, 'androidRuntimeExecuted': False,
                 'worldserverIncluded': True, 'worldReady': False, 'worldBuild': build,
                 'buildReportSha256': digest(build_report), 'baseRuntimeLockSha256': digest(BASE_LOCK),
                 'baseManifestSha256': digest(base_stage / 'assets/fta-runtime-manifest.json'),
                 'baseRuntimeFilesPreserved': len(manifest['files']),
                 'baseLegalFilesPreserved': len(base_provenance['legalFiles']),
                 'basePayloadsPreserved': True, 'baseProviderProvenance': base_provenance,
                 'compiledUpdaterLockSha256': digest(updater_lock), 'moduleConfigFiles': [record for record, _ in configs],
                 'sourceEvidenceSha256': digest(SOURCE_EVIDENCE), 'correspondingSourceInputsVerified': True,
                 'correspondingSourceDeliveryQualified': False,
                 'coreLicenseElection': 'GPL-3.0-or-later', 'legalFilesAdded': [record for record, _ in legal],
                 'addedLibraries': [entry for name, entry in dep_evidence.items()
                                    if any(record.get('logicalName') == name for record in added)]}
        (staged / 'WORLD_BUILD_PROVENANCE.json').write_text(json.dumps(proof, indent=2) + '\n')
        os.rename(staged, output)
        return {key: proof[key] for key in ('distribution', 'worldserverIncluded', 'androidRuntimeExecuted',
                                           'worldReady', 'basePayloadsPreserved')}
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('base-stage', 'worldserver', 'authserver', 'target-libs', 'build-report', 'source',
                 'build-dir', 'source-archive', 'source-archives', 'dependency-notices', 'ndk',
                 'dependency-provenance', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--updater-lock', type=Path, default=UPDATER_LOCK)
    parser.add_argument('--readelf', default='readelf')
    args = vars(parser.parse_args())
    try:
        result = stage(**args)
        print(json.dumps({'status': 'staged', **result}, indent=2))
    except (ProviderError, BuildError, SourceError, OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({'status': 'blocked', 'error': str(error) if isinstance(error, (ProviderError, BuildError, SourceError))
                          else type(error).__name__, 'androidRuntimeExecuted': False, 'worldReady': False}))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
