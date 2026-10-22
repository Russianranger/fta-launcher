"""Private world packaging safety fixtures; no native build or game SQL is faked."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import stage_world_runtime as world


def sha(body):
    return hashlib.sha256(body).hexdigest()


def json_file(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')
    return path


def record(path):
    return {'sha256': sha(path.read_bytes()), 'size': path.stat().st_size}


class WorldRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        (self.source / 'LICENSE').write_bytes(b'original license fixture')
        (self.source / 'AUTHORS').write_bytes(b'original copyright holder fixture')
        header = self.source / 'src/server/apps/worldserver/Main.cpp'
        header.parent.mkdir(parents=True)
        header.write_text('either version 2 of the License, or\n(at your option) any later version.')
        config = self.source / 'modules/module00/conf/fixture.conf.dist'
        config.parent.mkdir(parents=True)
        config.write_bytes(b'Fixture.Enabled = 1\n')
        self.inventory = {'tree_sha256': '1' * 64, 'modules': ['module%02d' % index for index in range(52)],
                          'license_files': [{'path': 'LICENSE', 'sha256': sha((self.source / 'LICENSE').read_bytes())}]}
        self.profiles = json_file(self.root / 'profiles.json', {'profiles': {'fixture-port': {'result_inventory': self.inventory}}})
        self.build_dir = self.root / 'build'
        self.build_dir.mkdir()
        self.binary = self.build_dir / 'worldserver'
        self.binary.write_bytes(b'world-executable-fixture')
        self.auth_binary = self.build_dir / 'authserver'
        self.auth_binary.write_bytes(b'new-auth-prerequisite-fixture')
        targets = {}
        for name, kind in [('worldserver', 'EXECUTABLE'), ('modules', 'STATIC_LIBRARY'),
                           ('scripts', 'STATIC_LIBRARY'), ('lualib', 'STATIC_LIBRARY')]:
            path = self.binary if name == 'worldserver' else self.build_dir / ('lib' + name + '.a')
            if name != 'worldserver':
                path.write_bytes((name + '-archive-fixture').encode())
            targets[name] = {'path': str(path), 'type': kind, **record(path)}
        self.compiled = {'schema_version': 1, 'world_target_completed': True,
                         'module_names': self.inventory['modules'],
                         'module_compilation_counts': {name: 1 for name in self.inventory['modules']},
                         'static_modules': True, 'static_scripts': True, 'lua_version': 'lua52',
                         'lua_static': True, 'cxx_standard': 20,
                         'debug_section_policy': 'linker-strip-debug',
                         'linked_static_targets': ['modules', 'scripts', 'lualib'],
                         'module_configuration_files': ['fixture.conf'], 'configuration_directory': 'configs',
                         'target_artifacts': targets, 'execution_qualified': False}
        self.report = {'schema_version': 1, 'platform': 'android', 'status': 'build-passed', 'stage': 'world',
                       'source_preserved': True, 'required_module_count': 52, 'target_abi': 'arm64-v8a',
                       'port_profile': 'fixture-port', 'source_tree_sha256': self.inventory['tree_sha256'],
                       'configuration': {'CMAKE_BUILD_TYPE': 'Release', 'CMAKE_CXX_STANDARD': '20',
                                         'MODULES': 'static', 'SCRIPTS': 'static', 'CMAKE_SYSTEM_NAME': 'Android',
                                         'APPS_BUILD': 'all', 'LUA_VERSION': 'lua52', 'LUA_STATIC': 'ON',
                                         'CONF_DIR': 'configs', 'ANDROID_STL': 'c++_static',
                                         'ANDROID_PLATFORM': 'android-33', 'ANDROID_ABI': 'arm64-v8a',
                                         'CMAKE_EXE_LINKER_FLAGS': '-Wl,--strip-debug'},
                       'artifacts': {'worldserver': {'path': str(self.binary), **record(self.binary)},
                                     'authserver': {'path': str(self.auth_binary), **record(self.auth_binary)}},
                       'compiled_world_evidence': self.compiled}
        self.report_path = json_file(self.root / 'build.json', self.report)
        self.libs = self.root / 'libs'
        self.libs.mkdir()
        self.readline = self.libs / 'libreadline.so.8'
        self.readline.write_bytes(b'\x7fELFreadline-fixture')
        self.base, self.base_lock = self.fixture_base()
        ncurses = self.base / 'jniLibs/arm64-v8a/libncursesw.so'
        self.compiled['target_dependency_artifacts'] = {'ncurses': {'path': str(ncurses), **record(ncurses)}}
        json_file(self.report_path, self.report)
        self.archives = self.root / 'archives'
        self.archives.mkdir()
        self.notices = self.root / 'notices'
        self.notices.mkdir()
        (self.notices / 'Readline-GPL-3.0.txt').write_bytes(b'full license fixture')
        self.ndk = self.root / 'ndk'
        self.ndk.mkdir()
        (self.ndk / 'source.properties').write_text('Pkg.Revision = fixture-ndk\n')
        (self.ndk / 'NOTICE').write_bytes(b'static runtime notice fixture')
        self.source_archive = self.root / 'source.zip'
        self.source_archive.write_bytes(b'original source archive fixture')
        archive = self.archives / 'readline.tar.gz'
        archive.write_bytes(b'pinned dependency source fixture')
        patches = []
        for number in ('001', '002', '003'):
            path = self.archives / ('readline-' + number + '.patch')
            path.write_bytes(('upstream patch fixture ' + number).encode())
            patches.append({'number': number, 'url': 'https://example.invalid/' + number,
                            'filename': path.name, 'sha256': sha(path.read_bytes())})
        source_evidence = {'formatVersion': 1, 'coreLaterVersionNoticePath': 'src/server/apps/worldserver/Main.cpp',
                           'sourceArchive': {'sha256': sha(self.source_archive.read_bytes()), 'bytes': self.source_archive.stat().st_size},
                           'inputs': {'readline': {'version': '8.3', 'url': 'https://example.invalid/readline',
                                       'filename': archive.name, 'sha256': sha(archive.read_bytes())}},
                           'readlinePatches': patches, 'readlineLibrary': record(self.readline),
                           'ndk': {'version': 'fixture-ndk'},
                           'notices': [{'name': 'world-readline-GPL3.txt', 'sourceKind': 'dependency-notice',
                                        'inputPath': 'Readline-GPL-3.0.txt', **record(self.notices / 'Readline-GPL-3.0.txt')},
                                       {'name': 'world-NDK-NOTICE', 'sourceKind': 'ndk', 'inputPath': 'NOTICE', **record(self.ndk / 'NOTICE')}]}
        self.source_evidence = json_file(self.root / 'source-evidence.json', source_evidence)
        self.updater = json_file(self.root / 'updater.json', {'formatVersion': 1, 'sourceProfile': 'fixture-port',
                         'sourceTreeSha256': '1' * 64, 'sourceArchiveSha256': sha(self.source_archive.read_bytes()),
                         'sourceDirectory': 'Data', 'moduleCount': 52, 'moduleSqlCount': 0,
                         'runtimeSqlFiles': [{} for _ in range(236)]})
        pin = source_evidence['inputs']['readline']
        self.dependency = {'logicalName': 'libreadline.so.8', 'sha256': sha(self.readline.read_bytes()),
                           'license': 'GPL-3.0-or-later', 'builtVersion': '8.3.3',
                           'source': {'project': 'GNU Readline', **{key: pin[key] for key in ('version', 'url', 'sha256')}},
                           'patches': [{key: item[key] for key in ('number', 'url', 'sha256')} for item in patches]}
        self.dependency_path = json_file(self.root / 'dependency.json', {'formatVersion': 1, 'libraries': [self.dependency]})
        for name, value in [('BASE_LOCK', self.base_lock), ('PORT_PROFILES', self.profiles),
                            ('UPDATER_LOCK', self.updater), ('SOURCE_EVIDENCE', self.source_evidence)]:
            patcher = mock.patch.object(world, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        for name, kwargs in [('verify_source_tree', {'return_value': self.inventory}),
                             ('world_build_evidence', {'side_effect': lambda *args: deepcopy(self.compiled)}),
                             ('inspect_elf', {'side_effect': self.metadata})]:
            patcher = mock.patch.object(world, name, **kwargs)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = mock.patch('stage_auth_runtime.inspect_elf', side_effect=self.metadata)
        patcher.start()
        self.addCleanup(patcher.stop)

    def metadata(self, path, readelf='readelf', **kwargs):
        name = path.name
        if name in ('worldserver', world.WORLD_NAME):
            needed = ['libc.so', 'libreadline.so.8', 'libz.so']
        elif name == 'libreadline.so.8' or name.startswith('libfta_worlddep_'):
            needed = ['libc.so', 'libconnector.so']
        else:
            needed = ['libc.so']
        return {'needed': needed, 'sonames': ['libreadline.so.8'] if 'readline' in name else
                ['libconnector.so'] if name == 'libconnector.so' else
                ['libncursesw.so.6'] if name == 'libncursesw.so' else [],
                'interpreter': '/system/bin/linker64' if name in ('worldserver', world.WORLD_NAME,
                               'libdbserver.so', 'libdbclient.so', 'libfta_authserver.so') else None,
                'minimumLoadAlignment': 16384}

    def fixture_base(self):
        base = self.root / 'base'
        native = base / 'jniLibs/arm64-v8a'
        native.mkdir(parents=True)
        files = []
        for name, role in [('libdbserver.so', 'database-server'), ('libdbclient.so', 'database-client'),
                           ('libfta_authserver.so', 'auth-server'), ('libconnector.so', 'library'),
                           ('libncursesw.so', 'library')]:
            path = native / name
            path.write_bytes((name + '-preserved-fixture').encode())
            files.append({'path': 'native/' + name, 'role': role, **record(path), **self.metadata(path)})
            if role == 'library':
                files[-1]['logicalName'] = 'libncursesw.so.6' if name == 'libncursesw.so' else name
        for relative in ('data/provider/bootstrap.sql', 'data/metadata/auth-seed-lock.json'):
            path = base / 'assets/runtime' / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'non-player-data fixture')
            files.append({'path': relative, 'role': 'data', **record(path)})
        legal = base / 'assets/runtime/legal/NOTICE.txt'
        legal.parent.mkdir(parents=True)
        legal.write_bytes(b'original unchanged legal fixture')
        legal_files = [{'name': legal.name, **record(legal)}]
        manifest = {'formatVersion': 1, 'abi': 'arm64-v8a', 'providerId': 'fixture',
                    'capabilities': ['database', 'authserver'], 'files': files}
        manifest_path = json_file(base / 'assets/fta-runtime-manifest.json', manifest)
        json_file(base / 'BUILD_PROVENANCE.json', {'noticeProvided': True, 'redistributionNoticePending': False,
                                                  'legalFiles': legal_files, 'currentSessionNativeCompilePerformed': False})
        lock = json_file(self.root / 'base-lock.json', {'formatVersion': 1, 'manifestSha256': sha(manifest_path.read_bytes()),
                    'providerId': 'fixture', 'runtimeFileCount': len(files), 'files': files,
                    'legalFileCount': len(legal_files), 'legalFiles': legal_files})
        return base, lock

    def verify_report(self, report=None):
        return world.verify_build_report(report or self.report, self.binary, self.auth_binary, self.source, self.build_dir)

    def run_stage(self, output=None):
        return world.stage(self.base, self.binary, self.auth_binary, self.libs, self.report_path,
                           self.source, self.build_dir, self.source_archive, self.archives,
                           self.notices, self.ndk, self.updater, self.dependency_path, output or self.root / 'output')

    def test_completed_report_requires_actual_both_artifacts_and_linked_static_targets(self):
        proof, _ = self.verify_report()
        self.assertEqual(proof['worldserverSha256'], sha(self.binary.read_bytes()))
        self.assertFalse(proof['runtimeQualified'])
        self.assertFalse(proof['preservedBaseAuthRebuilt'])
        self.auth_binary.write_bytes(b'changed auth prerequisite')
        with self.assertRaises(world.ProviderError):
            self.verify_report()

    def test_incomplete_host_reduced_modules_or_non_default_lua_reports_are_refused(self):
        variants = [('platform', 'host'), ('status', 'configure-passed'), ('stage', 'auth'),
                    ('source_preserved', False), ('required_module_count', 51), ('target_abi', 'x86_64')]
        for key, value in variants:
            with self.subTest(key=key), self.assertRaises(world.ProviderError):
                self.verify_report({**self.report, key: value})
        for key, value in [('CMAKE_CXX_STANDARD', '17'), ('MODULES', 'none'), ('SCRIPTS', 'none'),
                           ('LUA_VERSION', 'lua54'), ('LUA_STATIC', 'OFF'), ('ANDROID_STL', 'c++_shared'),
                           ('CONF_DIR', '/host/configs'), ('ANDROID_PLATFORM', 'android-35'), ('ANDROID_ABI', 'x86_64'),
                           ('CMAKE_EXE_LINKER_FLAGS', ''), ('CMAKE_EXE_LINKER_FLAGS', '-Wl,--strip-all'),
                           ('CMAKE_EXE_LINKER_FLAGS', '-Wl,--strip-debug -Wl,--strip-all')]:
            changed = deepcopy(self.report)
            changed['configuration'][key] = value
            with self.subTest(configuration=key), self.assertRaises(world.ProviderError):
                self.verify_report(changed)

    def test_matching_report_booleans_cannot_replace_actual_link_graph_or_archive(self):
        changed = deepcopy(self.report)
        changed['compiled_world_evidence']['module_compilation_counts']['module00'] = 0
        with self.assertRaises(world.ProviderError):
            self.verify_report(changed)
        archive = Path(self.compiled['target_artifacts']['modules']['path'])
        archive.write_bytes(b'changed static module artifact')
        with self.assertRaises(world.ProviderError):
            self.verify_report()

    def test_link_evidence_must_confirm_debug_only_stripping(self):
        for policy in ('unchanged', 'strip-all', None):
            self.compiled['debug_section_policy'] = policy
            with self.subTest(policy=policy), self.assertRaises(world.ProviderError):
                self.verify_report()

    def test_android_runtime_size_boundaries_include_exact_totals(self):
        maximum = world.MAX_RUNTIME_FILE_BYTES
        self.assertEqual(world.runtime_bounds([{'size': maximum}] * 4), world.MAX_RUNTIME_TOTAL_BYTES)
        for files in ([{'size': maximum + 1}], [{'size': maximum}] * 4 + [{'size': 1}],
                      [{'size': -1}], [{'size': True}], [{'size': 0}] * 4097):
            with self.subTest(entries=len(files)), self.assertRaises(world.ProviderError):
                world.runtime_bounds(files)

    def test_large_private_archive_has_a_separate_bound_and_is_never_a_runtime_payload(self):
        # Sparse size fixtures exercise bounds without allocating or hashing GiB.
        archive = self.root / 'large-private-archive.a'
        length = world.MAX_RUNTIME_FILE_BYTES + 1
        with archive.open('wb') as stream:
            stream.truncate(length)
        pin = {'size': length, 'sha256': 'a' * 64}
        with mock.patch.object(world, 'digest', return_value=pin['sha256']) as hash_file:
            with self.assertRaises(world.ProviderError):
                world.verified_file(archive, pin)
            hash_file.assert_not_called()
            world.verified_file(archive, pin, maximum=world.MAX_BUILD_ARCHIVE_BYTES)
            hash_file.assert_called_once_with(archive)
        with mock.patch.object(world, 'digest') as hash_file:
            with self.assertRaises(world.ProviderError):
                world.verified_file(archive, {**pin, 'size': world.MAX_BUILD_ARCHIVE_BYTES + 1},
                                    maximum=world.MAX_BUILD_ARCHIVE_BYTES)
            hash_file.assert_not_called()

    def test_artifact_or_source_profile_identity_drift_is_refused(self):
        with self.assertRaises(world.ProviderError):
            self.verify_report({**self.report, 'source_tree_sha256': '2' * 64})
        self.binary.write_bytes(b'different world bytes')
        with self.assertRaises(world.ProviderError):
            self.verify_report()

    def test_actual_link_dependency_must_match_preserved_provider_bytes(self):
        replacement = self.root / 'different-ncurses.so'
        replacement.write_bytes(b'different target library fixture')
        self.compiled['target_dependency_artifacts']['ncurses'] = {'path': str(replacement), **record(replacement)}
        with self.assertRaises(world.ProviderError):
            self.verify_report()

    def test_self_consistent_tampered_base_manifest_still_fails_public_pin(self):
        path = self.base / 'jniLibs/arm64-v8a/libfta_authserver.so'
        path.write_bytes(b'replacement old auth')
        manifest_path = self.base / 'assets/fta-runtime-manifest.json'
        value = json.loads(manifest_path.read_text())
        next(item for item in value['files'] if item['role'] == 'auth-server').update(record(path))
        json_file(manifest_path, value)
        with self.assertRaises(world.ProviderError):
            world.verify_base(self.base, 'readelf')

    def test_base_extra_file_symlink_or_legal_drift_is_refused(self):
        extra = self.base / 'extra-undeclared.bin'
        extra.write_bytes(b'extra')
        with self.assertRaises(world.ProviderError):
            world.verify_base(self.base, 'readelf')
        extra.unlink()
        extra.symlink_to(self.binary)
        with self.assertRaises(world.ProviderError):
            world.verify_base(self.base, 'readelf')
        extra.unlink()
        (self.base / 'assets/runtime/legal/NOTICE.txt').write_bytes(b'changed license')
        with self.assertRaises(world.ProviderError):
            world.verify_base(self.base, 'readelf')

    def test_actual_host_elf_is_refused(self):
        if not Path('/bin/true').is_file() or not shutil.which('readelf'):
            self.skipTest('host readelf fixture unavailable')
        with mock.patch.object(world, 'inspect_elf', wraps=__import__('stage_android_provider').inspect_elf):
            with self.assertRaises(world.ProviderError):
                world.extend_closure(Path('/bin/true'), [], {}, {}, 'readelf')

    def test_recursive_dependency_and_provider_collision_fail_closed(self):
        base_manifest = json.loads((self.base / 'assets/fta-runtime-manifest.json').read_text())
        native = [item for item in base_manifest['files'] if item['path'].startswith('native/')]
        candidates = {'libreadline.so.8': [(self.readline, self.metadata(self.readline))]}
        records, sources = world.extend_closure(self.binary, native, candidates,
                                                {'libreadline.so.8': self.dependency}, 'readelf')
        self.assertEqual(len(records), 2)
        self.assertEqual(len(sources), 2)
        with self.assertRaises(world.ProviderError):
            world.extend_closure(self.binary, native, candidates, {}, 'readelf')
        candidates['libconnector.so'] = [(self.readline, self.metadata(self.readline))]
        with self.assertRaises(world.ProviderError):
            world.extend_closure(self.binary, native, candidates, {'libreadline.so.8': self.dependency}, 'readelf')

    def test_4k_executable_or_dependency_alignment_is_refused(self):
        for target in ('worldserver', 'libreadline.so.8'):
            def metadata(path, readelf='readelf', **kwargs):
                info = self.metadata(path)
                if path.name == target:
                    info['minimumLoadAlignment'] = 4096
                return info
            with self.subTest(target=target), mock.patch.object(world, 'inspect_elf', side_effect=metadata):
                with self.assertRaises(world.ProviderError):
                    world.extend_closure(self.binary, [], {'libreadline.so.8': [(self.readline, metadata(self.readline))]},
                                         {'libreadline.so.8': self.dependency}, 'readelf')

    def test_unreviewed_dependency_source_or_missing_readline_patch_is_refused(self):
        self.assertIn('libreadline.so.8', world.pinned_libraries({'formatVersion': 1, 'libraries': [self.dependency]}))
        changed = deepcopy(self.dependency)
        changed['source']['sha256'] = 'a' * 64
        with self.assertRaises(world.ProviderError):
            world.pinned_libraries({'formatVersion': 1, 'libraries': [changed]})
        changed = deepcopy(self.dependency)
        changed['patches'].pop()
        with self.assertRaises(world.ProviderError):
            world.pinned_libraries({'formatVersion': 1, 'libraries': [changed]})
        changed = deepcopy(self.dependency)
        changed['sha256'] = 'f' * 64
        with self.assertRaises(world.ProviderError):
            world.pinned_libraries({'formatVersion': 1, 'libraries': [changed]})

    def test_source_archive_patch_full_notice_or_ndk_revision_drift_is_refused(self):
        args = (self.source, self.source_archive, self.archives, self.notices, self.ndk, self.inventory)
        self.assertGreater(len(world.legal_inputs(*args)), 2)
        for path in (self.archives / 'readline-002.patch', self.notices / 'Readline-GPL-3.0.txt', self.ndk / 'source.properties'):
            original = path.read_bytes()
            path.write_bytes(b'changed source/legal input')
            with self.subTest(path=path.name), self.assertRaises(world.ProviderError):
                world.legal_inputs(*args)
            path.write_bytes(original)

    def test_config_inventory_must_match_actual_compiled_names(self):
        proof, _ = self.verify_report()
        records = world.module_configs(self.source, proof)
        self.assertEqual(records[0][0]['path'], world.CONFIG_PREFIX + 'fixture.conf')
        proof['compiledWorldEvidence']['module_configuration_files'] = ['missing.conf']
        with self.assertRaises(world.ProviderError):
            world.module_configs(self.source, proof)

    def test_updater_lock_must_match_exact_build_and_public_metadata(self):
        proof, _ = self.verify_report()
        world.verify_updater_lock(self.updater, proof)
        changed = self.root / 'changed-updater.json'
        value = json.loads(self.updater.read_text())
        value['sourceProfile'] = 'different-port'
        json_file(changed, value)
        with self.assertRaises(world.ProviderError):
            world.verify_updater_lock(changed, proof)
        proof['sourceTreeSha256'] = '2' * 64
        with self.assertRaises(world.ProviderError):
            world.verify_updater_lock(self.updater, proof)

    def test_success_preserves_all_base_payloads_and_only_adds_reached_library(self):
        output = self.root / 'output'
        original = {path.relative_to(self.base): path.read_bytes() for path in self.base.rglob('*') if path.is_file()}
        (self.libs / 'unreached.so').write_bytes(b'not an ELF and not copied')
        result = self.run_stage(output)
        self.assertTrue(result['basePayloadsPreserved'])
        self.assertFalse(result['androidRuntimeExecuted'])
        self.assertFalse(result['worldReady'])
        for relative, body in original.items():
            if str(relative) != 'assets/fta-runtime-manifest.json':
                self.assertEqual((output / relative).read_bytes(), body)
        before = json.loads(original[Path('assets/fta-runtime-manifest.json')])
        after = json.loads((output / 'assets/fta-runtime-manifest.json').read_text())
        self.assertEqual(after['files'][:len(before['files'])], before['files'])
        self.assertEqual(after['capabilities'], ['database', 'authserver', 'worldserver'])
        self.assertEqual(sum(item['role'] == 'world-server' for item in after['files']), 1)
        self.assertEqual(after['worldBuild']['worldserverSha256'], sha(self.binary.read_bytes()))
        self.assertEqual(after['worldBuild']['compiledUpdaterLockSha256'], sha(self.updater.read_bytes()))
        self.assertEqual(after['worldBuild']['configurationDirectory'], 'configs')
        self.assertNotIn(str(self.root), json.dumps(after))
        self.assertEqual((output / 'assets/runtime/data/metadata/world-updater-lock.json').read_bytes(), self.updater.read_bytes())
        self.assertEqual((output / 'assets/runtime/data/world-config/modules/fixture.conf').read_bytes(), b'Fixture.Enabled = 1\n')
        proof = json.loads((output / 'WORLD_BUILD_PROVENANCE.json').read_text())
        self.assertFalse(proof['publicDistributionQualified'])
        self.assertFalse(proof['correspondingSourceDeliveryQualified'])
        self.assertEqual(proof['addedLibraries'], [self.dependency])
        self.assertFalse(proof['worldBuild']['preservedBaseAuthRebuilt'])
        self.assertEqual(proof['packagedRuntimeBytes'], sum(item['size'] for item in after['files']))
        self.assertFalse(any(path.name.endswith('.a') for path in output.rglob('*')))

    def test_output_reuse_input_nesting_and_failed_stage_leave_no_partial_output(self):
        output = self.root / 'output'
        output.mkdir()
        with self.assertRaises(world.ProviderError):
            self.run_stage(output)
        with self.assertRaises(world.ProviderError):
            self.run_stage(self.source / 'nested-output')
        output.rmdir()
        self.readline.write_bytes(b'changed dependency')
        with self.assertRaises(world.ProviderError):
            self.run_stage(output)
        self.assertFalse(output.exists())
        self.assertEqual(list(self.root.glob('.fta-world-runtime-*')), [])


class PublicWorldMetadataTests(unittest.TestCase):
    def test_base_pin_preserves_provider_auth_connector_and_seed_independent_pins(self):
        root = Path(__file__).resolve().parents[1]
        base = json.loads((root / 'manifests/auth-runtime-lock.json').read_text())
        provider = json.loads((root / 'manifests/android-provider-lock.json').read_text())
        android = json.loads((root / 'native-port/android-inputs.json').read_text())
        qualification = json.loads((root / 'native-port/qualification.json').read_text())
        self.assertEqual(base['runtimeFileCount'], 96)
        self.assertEqual(len(base['files']), 96)
        self.assertEqual(base['legalFileCount'], 17)
        self.assertEqual(base['legalFiles'], sorted(provider['legalFiles'], key=lambda entry: entry['name']))
        native = {item['path'].removeprefix('native/'): item for item in base['files'] if item['path'].startswith('native/')}
        for pin in provider['nativeFiles']:
            actual = native.pop(pin['apkName'])
            self.assertEqual((actual['sha256'], actual['size'], actual['role'], actual.get('logicalName')),
                             (pin['sha256'], pin['size'], pin['role'], pin.get('logicalName')))
        auth_pin = qualification['android_auth_resolver_fix']
        auth = native.pop('libfta_authserver.so')
        self.assertEqual((auth['sha256'], auth['size']), (auth_pin['binary_sha256'], auth_pin['binary_bytes']))
        self.assertEqual(base['historicalAuthSourceProfile'], auth_pin['port_profile'])
        self.assertFalse(base['currentSessionNativeCompilePerformed'])
        connector = next(item for item in android['libraries'] if item['logicalName'] == 'libmariadb.so')
        self.assertEqual(len(native), 1)
        self.assertEqual([(item['logicalName'], item['sha256'], item['size']) for item in native.values()],
                         [('libmariadb.so', connector['sha256'], connector['bytes'])])
        seed = next(item for item in base['files'] if item['path'] == 'data/metadata/auth-seed-lock.json')
        self.assertEqual(seed['sha256'], sha((root / 'manifests/auth-seed-lock.json').read_bytes()))

    def test_world_source_evidence_preserves_public_source_and_readline_patch_pins(self):
        root = Path(__file__).resolve().parents[1]
        evidence = json.loads((root / 'licenses/world-runtime/SOURCE_EVIDENCE.json').read_text())
        inputs = json.loads((root / 'native-port/world-inputs.json').read_text())
        qualification = json.loads((root / 'native-port/qualification.json').read_text())['android_world_dependency']
        source = json.loads((root / 'manifests/reference-inputs.json').read_text())['archives'][0]
        self.assertEqual(evidence['sourceArchive'], source)
        self.assertEqual(evidence['coreLicenseElection'], 'GPL-3.0-or-later')
        self.assertFalse(evidence['publicDistributionQualified'])
        self.assertEqual((evidence['readlineLibrary']['sha256'], evidence['readlineLibrary']['size']),
                         (qualification['binary_sha256'], qualification['binary_bytes']))
        for key, pin in {**inputs['archives'], 'lua52': inputs['lua52']}.items():
            for identity in ('version', 'url', 'sha256'):
                self.assertEqual(evidence['inputs'][key][identity], pin[identity])
        for patch in evidence['readlinePatches']:
            self.assertEqual((patch['url'], patch['sha256']),
                             (inputs['readlinePatches'][patch['number']]['url'], inputs['readlinePatches'][patch['number']]['sha256']))
        notices = {item['name'] for item in evidence['notices']}
        self.assertTrue({'world-Readline-GPL-3.0.txt', 'world-Readline-README.txt',
                         'world-NDK-r28c-NOTICE', 'world-NDK-r28c-NOTICE.toolchain'} <= notices)
        for pin in evidence['notices']:
            if pin['sourceKind'] == 'repository':
                self.assertEqual(pin['sha256'], sha((root / 'licenses/world-runtime' / pin['inputPath']).read_bytes()))


if __name__ == '__main__':
    unittest.main()
