"""Private auth packaging tests; fixtures contain no game source or SQL rows."""
from pathlib import Path
from copy import deepcopy
import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import stage_auth_runtime as auth


def sha(data):
    return hashlib.sha256(data).hexdigest()


class AuthRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.binary = self.root / 'authserver'
        self.binary.write_bytes(b'auth-elf-fixture')
        self.libs = self.root / 'libs'
        self.libs.mkdir()
        self.profiles = self.root / 'profiles.json'
        self.profiles.write_text(json.dumps({'profiles': {'fixture-port': {'result_inventory': {'tree_sha256': '1' * 64}}}}))
        self.profile_patch = mock.patch.object(auth, 'PORT_PROFILES', self.profiles)
        self.profile_patch.start()
        self.report = {'schema_version': 1, 'platform': 'android', 'status': 'build-passed',
                       'stage': 'auth', 'source_preserved': True, 'required_module_count': 52,
                       'target_abi': 'arm64-v8a', 'port_profile': 'fixture-port', 'source_tree_sha256': '1' * 64,
                       'configuration': {'CMAKE_BUILD_TYPE': 'Release', 'CMAKE_CXX_STANDARD': '20',
                                         'MODULES': 'static', 'SCRIPTS': 'static', 'CMAKE_SYSTEM_NAME': 'Android',
                                         'APPS_BUILD': 'auth-only'},
                       'artifacts': {'authserver': {'sha256': sha(self.binary.read_bytes()),
                                                    'size': self.binary.stat().st_size}}}

    def tearDown(self):
        self.profile_patch.stop()
        self.temporary.cleanup()

    def metadata(self, path, readelf='readelf'):
        is_auth = path.name in ('authserver', auth.AUTH_NAME)
        is_lib = path.name.startswith('libconnector')
        return {'needed': ['libconnector.so', 'libc.so'] if is_auth else ['libc.so'],
                'sonames': ['libconnector.so'] if is_lib else [],
                'interpreter': '/system/bin/linker64' if is_auth or path.name in ('libserver.so', 'libclient.so') else None,
                'minimumLoadAlignment': 16384}

    def test_completed_android_report_binds_exact_auth_artifact(self):
        result = auth.verify_build_report(self.report, self.binary)
        self.assertEqual(result['authserverSha256'], sha(self.binary.read_bytes()))
        self.assertFalse(result['runtimeQualified'])
        for key, bad in (('platform', 'host'), ('status', 'configure-passed'),
                         ('required_module_count', 51), ('target_abi', 'x86_64'), ('source_preserved', False)):
            changed = {**self.report, key: bad}
            with self.subTest(key=key), self.assertRaises(auth.ProviderError):
                auth.verify_build_report(changed, self.binary)

    def test_configuration_or_source_drift_is_refused(self):
        changed = deepcopy(self.report)
        changed['configuration']['MODULES'] = 'none'
        with self.assertRaises(auth.ProviderError):
            auth.verify_build_report(changed, self.binary)
        changed = {**self.report, 'source_tree_sha256': '2' * 64}
        with self.assertRaises(auth.ProviderError):
            auth.verify_build_report(changed, self.binary)
        self.binary.write_bytes(b'changed executable')
        with self.assertRaises(auth.ProviderError):
            auth.verify_build_report(self.report, self.binary)

    def test_duplicate_json_keys_are_refused(self):
        path = self.root / 'evidence.json'
        path.write_text('{"platform":"android","platform":"host"}')
        with self.assertRaises(auth.ProviderError):
            auth.strict_json(path)

    def test_actual_host_elf_is_refused(self):
        candidate = Path('/bin/true')
        if not candidate.is_file() or not shutil.which('readelf'):
            self.skipTest('Linux readelf fixture unavailable')
        with self.assertRaises(auth.ProviderError):
            auth.extend_closure(candidate, [], {}, {}, 'readelf')

    def test_unresolved_or_unproven_dependency_is_refused(self):
        library = self.libs / 'libconnector.so'
        library.write_bytes(b'target-library')
        with mock.patch.object(auth, 'inspect_elf', side_effect=self.metadata):
            with self.assertRaises(auth.ProviderError):
                auth.extend_closure(self.binary, [], {}, {}, 'readelf')
            with self.assertRaises(auth.ProviderError):
                auth.extend_closure(self.binary, [], {'libconnector.so': [(library, self.metadata(library))]}, {}, 'readelf')

    def test_provider_dependency_collision_requires_identical_bytes(self):
        library = self.libs / 'libconnector.so'
        library.write_bytes(b'new-library')
        base = [{'path': 'native/libexisting.so', 'role': 'library', 'logicalName': 'libconnector.so',
                 'sha256': sha(b'old-library'), 'size': len(b'old-library'), 'needed': ['libc.so']}]
        candidates = {'libconnector.so': [(library, self.metadata(library))]}
        with mock.patch.object(auth, 'inspect_elf', side_effect=self.metadata):
            with self.assertRaises(auth.ProviderError):
                auth.extend_closure(self.binary, base, candidates, {}, 'readelf')
            base[0]['sha256'] = sha(library.read_bytes())
            records, sources = auth.extend_closure(self.binary, base, candidates, {}, 'readelf')
            self.assertEqual(len(records), 1)
            self.assertEqual(set(sources), {auth.AUTH_NAME})

    def test_platform_zlib_is_distinct_from_private_provider_zlib(self):
        info = {'needed': ['libc.so', 'libz.so'], 'sonames': [],
                'interpreter': '/system/bin/linker64', 'minimumLoadAlignment': 16384}
        with mock.patch.object(auth, 'inspect_elf', return_value=info):
            records, _ = auth.extend_closure(self.binary, [], {}, {}, 'readelf')
            self.assertEqual(len(records), 1)
        info['needed'] = ['libz.so.1']
        with mock.patch.object(auth, 'inspect_elf', return_value=info):
            with self.assertRaises(auth.ProviderError):
                auth.extend_closure(self.binary, [], {}, {}, 'readelf')

    def test_dependency_provenance_rejects_unknown_source(self):
        record = {'formatVersion': 1, 'libraries': [{'logicalName': 'libconnector.so', 'sha256': 'a' * 64,
                  'license': 'LGPL-2.1-or-later', 'source': {'project': 'Connector/C', 'version': '3.4.9',
                  'url': 'https://example.invalid/source.tar.gz', 'sha256': 'b' * 64}}]}
        self.assertEqual(auth.dependency_records(record)['libconnector.so']['source']['version'], '3.4.9')
        record['libraries'][0]['source'].pop('sha256')
        with self.assertRaises(auth.ProviderError):
            auth.dependency_records(record)

    def test_target_library_link_cannot_escape_to_host(self):
        (self.libs / 'liboutside.so').symlink_to(self.binary)
        with self.assertRaises(auth.ProviderError):
            auth.collect_libraries(self.libs, 'readelf')

    def fixture_provider(self):
        provider = self.root / 'provider'
        native = provider / 'jniLibs/arm64-v8a'
        native.mkdir(parents=True)
        files = []
        pins = []
        for name, role in [('libserver.so', 'database-server'), ('libclient.so', 'database-client')]:
            body = name.encode()
            (native / name).write_bytes(body)
            files.append({'path': 'native/' + name, 'role': role, 'sha256': sha(body), 'size': len(body), **self.metadata(native / name)})
            pins.append({'apkName': name, 'sha256': sha(body), 'size': len(body), 'role': role})
        data = provider / 'assets/runtime/data/provider/bootstrap.sql'
        data.parent.mkdir(parents=True)
        data.write_bytes(b'bootstrap-system-fixture')
        bootstrap = {'path': 'data/provider/bootstrap.sql', 'sha256': sha(data.read_bytes()), 'size': data.stat().st_size}
        files.append({**bootstrap, 'role': 'data'})
        legal = provider / 'assets/runtime/legal/NOTICE.txt'
        legal.parent.mkdir(parents=True)
        legal.write_bytes(b'fixture legal notice')
        notice = {'name': legal.name, 'sha256': sha(legal.read_bytes()), 'size': legal.stat().st_size}
        manifest = {'formatVersion': 1, 'providerId': 'fixture', 'providerVersion': '12.3.2',
                    'abi': 'arm64-v8a', 'capabilities': ['database'], 'files': files, 'bootstrap': bootstrap}
        (provider / 'assets/fta-runtime-manifest.json').write_text(json.dumps(manifest))
        (provider / 'BUILD_PROVENANCE.json').write_text(json.dumps({'noticeProvided': True,
            'redistributionNoticePending': False, 'legalFiles': [notice]}))
        pin = self.root / 'provider-lock.json'
        pin.write_text(json.dumps({'providerId': 'fixture', 'nativeFiles': pins, 'bootstrap': bootstrap, 'legalFiles': [notice]}))
        return provider, pin

    def test_success_preserves_every_provider_payload_and_adds_only_auth_scope(self):
        provider, pin = self.fixture_provider()
        library = self.libs / 'libconnector.so'
        library.write_bytes(b'\x7fELFtarget-library')
        report = self.root / 'build.json'
        report.write_text(json.dumps(self.report))
        seed = self.root / 'seed.json'
        seed.write_text(json.dumps({'formatVersion': 1, 'database': 'acore_auth'}))
        dep = self.root / 'deps.json'
        dep.write_text(json.dumps({'formatVersion': 1, 'libraries': [{'logicalName': 'libconnector.so',
            'sha256': sha(library.read_bytes()), 'license': 'LGPL-2.1-or-later',
            'source': {'project': 'Connector/C', 'version': '3.4.9', 'url': 'https://example.invalid/source', 'sha256': 'a' * 64}}]}))
        output = self.root / 'extended'
        with mock.patch.object(auth, 'PROVIDER_LOCK', pin), mock.patch.object(auth, 'SEED_LOCK', seed):
            with mock.patch.object(auth, 'inspect_elf', side_effect=self.metadata):
                result = auth.stage(provider, self.binary, self.libs, report, seed, output, dependency_provenance=dep)
        self.assertTrue(result['basePayloadsPreserved'])
        original = json.loads((provider / 'assets/fta-runtime-manifest.json').read_text())
        updated = json.loads((output / 'assets/fta-runtime-manifest.json').read_text())
        self.assertEqual(updated['files'][:len(original['files'])], original['files'])
        self.assertEqual(updated['capabilities'], ['database', 'authserver'])
        self.assertNotIn('worldserver', updated['capabilities'])
        for entry in original['files']:
            self.assertEqual(auth.stage_path(output, entry).read_bytes(), auth.stage_path(provider, entry).read_bytes())
        self.assertEqual((output / 'assets/runtime/legal/NOTICE.txt').read_bytes(), b'fixture legal notice')
        self.assertEqual((output / 'assets/runtime/data/metadata/auth-seed-lock.json').read_bytes(), seed.read_bytes())

    def test_existing_output_and_tampered_provider_are_refused(self):
        output = self.root / 'output'
        output.mkdir()
        with self.assertRaises(auth.ProviderError):
            auth.stage(self.root / 'none', self.binary, self.libs, self.root / 'none.json', self.root / 'none.json', output)
        provider, pin = self.fixture_provider()
        with self.assertRaises(auth.ProviderError):
            auth.stage(provider, self.binary, self.libs, self.root / 'none.json', self.root / 'none.json', provider / 'nested-output')
        (provider / 'assets/runtime/data/provider/bootstrap.sql').write_bytes(b'tampered')
        with mock.patch.object(auth, 'PROVIDER_LOCK', pin), mock.patch.object(auth, 'inspect_elf', side_effect=self.metadata):
            with self.assertRaises(auth.ProviderError):
                auth.verify_provider(provider, 'readelf')


if __name__ == '__main__':
    unittest.main()
