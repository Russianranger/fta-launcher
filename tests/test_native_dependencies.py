"""Dependency-driver composition tests: local fixtures, no compiler or network."""
from contextlib import ExitStack, redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile


SOURCE = Path(__file__).resolve().parents[1] / 'native-port/build-dependencies.py'
SPEC = importlib.util.spec_from_file_location('native_dependencies', SOURCE)
driver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(driver)


def archive(path, root, *, zipped=False, unsafe_name=None, link_target='tool'):
    entries = {root + '/tool': b'executable', root + '/include/openssl/ssl.h': b'source-header'}
    if unsafe_name:
        entries[unsafe_name] = b'unsafe'
    if zipped:
        with zipfile.ZipFile(path, 'w') as bundle:
            for name, body in entries.items():
                item = zipfile.ZipInfo(name)
                item.external_attr = (stat.S_IFREG | 0o755) << 16
                bundle.writestr(item, body)
            item = zipfile.ZipInfo(root + '/alias')
            item.external_attr = (stat.S_IFLNK | 0o777) << 16
            bundle.writestr(item, link_target.encode())
    else:
        with tarfile.open(path, 'w:gz') as bundle:
            for name, body in entries.items():
                item = tarfile.TarInfo(name)
                item.size, item.mode = len(body), 0o755
                bundle.addfile(item, io.BytesIO(body))
            item = tarfile.TarInfo(root + '/alias')
            item.type, item.linkname = tarfile.SYMTYPE, link_target
            bundle.addfile(item)


class NativeDependencyTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='fta-native-deps-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.work = self.root / 'output'

    def fixture_inputs(self):
        self.repo, self.stage = self.root / 'repo', self.root / 'provider'
        (self.repo / 'manifests').mkdir(parents=True)
        (self.stage / 'assets').mkdir(parents=True)
        native = self.stage / 'jniLibs/arm64-v8a'
        native.mkdir(parents=True)
        pins, records = [], []
        self.provider_bytes = {}
        for logical in ('libssl.so.3', 'libcrypto.so.3', 'libz.so.1'):
            body = ('fixture-' + logical).encode()
            name = 'libfta_' + logical.replace('.', '_') + '.so'
            pin = {'logicalName': logical, 'apkName': name, 'size': len(body),
                   'sha256': hashlib.sha256(body).hexdigest(), 'role': 'library'}
            pins.append(pin)
            records.append({**pin, 'path': 'native/' + name})
            (native / name).write_bytes(body)
            self.provider_bytes[logical] = body
        lock = {'providerId': 'fixture-arm64', 'packages': {'openssl': {'version': '1:3.6.3'}},
                'nativeFiles': pins}
        self.manifest = {'providerId': 'fixture-arm64', 'abi': 'arm64-v8a', 'files': records}
        (self.repo / 'manifests/android-provider-lock.json').write_text(json.dumps(lock))
        self.manifest_path = self.stage / 'assets/fta-runtime-manifest.json'
        self.manifest_path.write_text(json.dumps(self.manifest))
        self.archives = {}
        self.argv = [str(SOURCE), '--work-root', str(self.work), '--provider-stage', str(self.stage)]
        for key, (_, pin, source_root) in driver.PINS.items():
            path = self.root / (key + ('.zip' if key == 'ndk' else '.tar.gz'))
            archive(path, source_root, zipped=key == 'ndk')
            self.archives[path] = pin
            self.argv.extend(['--' + key + '-archive', str(path)])

    def invoke(self, *, plan=False):
        self.calls = []
        real_digest = driver.digest
        loader = '/private/fixture-host-runtime'

        def fake_run(argv, **kwargs):
            self.calls.append(argv)
            self.assertEqual(kwargs['env']['LD_LIBRARY_PATH'], loader)
            self.assertNotIn('CFLAGS', kwargs['env'])
            self.assertTrue(kwargs['check'])
            kwargs['stdout'].write(b'fixture-tool-version-or-build-output\n')
            if 'build_generated' in argv:
                generated = self.work / 'build/openssl-headers/include/openssl'
                generated.mkdir(parents=True)
                (generated / 'configuration.h').write_bytes(b'generated-header')
            if '--install' in argv:
                target = self.work / 'android-deps/lib/mariadb'
                target.mkdir()
                (target / 'libmariadbclient.a').write_bytes(b'fixture-connector')
            if argv[-1] == 'install':
                for name in ('filesystem', 'program_options', 'iostreams', 'regex'):
                    (self.work / 'android-deps/lib' / ('libboost_' + name + '.a')).write_bytes(b'fixture-boost')

        captured = io.StringIO()
        with ExitStack() as patches:
            patches.enter_context(mock.patch.object(sys, 'argv', self.argv + (['--plan'] if plan else [])))
            patches.enter_context(mock.patch.object(driver, 'REPO', self.repo))
            patches.enter_context(mock.patch.object(driver, 'digest', side_effect=lambda path: self.archives[path] if path in self.archives else real_digest(path)))
            patches.enter_context(mock.patch.object(driver.shutil, 'which', return_value='/usr/bin/true'))
            patches.enter_context(mock.patch.object(driver.platform, 'machine', return_value='x86_64'))
            patches.enter_context(mock.patch.object(driver.platform, 'platform', return_value='fixture-linux'))
            patches.enter_context(mock.patch.object(driver.sys, 'platform', 'linux'))
            patches.enter_context(mock.patch.object(driver, 'check_jobs'))
            patches.enter_context(mock.patch.dict(driver.os.environ, {'LD_LIBRARY_PATH': loader, 'CFLAGS': 'host-poison'}))
            patches.enter_context(mock.patch.object(driver.subprocess, 'run', side_effect=fake_run))
            patches.enter_context(redirect_stdout(captured))
            driver.main()
        return json.loads(captured.getvalue()) if plan else json.loads((self.work / 'receipt.json').read_text())

    def test_archives_share_sources_and_preserve_modes_and_links(self):
        destination = self.root / 'sources'
        for root, zipped in (('first', True), ('second', False)):
            path = self.root / root
            archive(path, root, zipped=zipped)
            driver.extract(path, destination, root)
            self.assertEqual((destination / root / 'alias').read_bytes(), b'executable')
            self.assertTrue((destination / root / 'tool').stat().st_mode & stat.S_IXUSR)
            with self.assertRaisesRegex(ValueError, 'fresh'):
                driver.extract(path, destination, root)

    def test_traversal_and_escaping_archive_links_are_rejected(self):
        for zipped in (True, False):
            for unsafe_name, target in (('source/../escaped', 'tool'), (None, '../../escaped')):
                with self.subTest(zipped=zipped, target=target, name=unsafe_name):
                    path = self.root / 'unsafe'
                    archive(path, 'source', zipped=zipped, unsafe_name=unsafe_name, link_target=target)
                    with self.assertRaises(ValueError):
                        driver.extract(path, self.root / 'sources', 'source')
                    self.assertFalse((self.root / 'escaped').exists())

    def assert_commands(self, receipt):
        prefix = self.work / 'android-deps'
        self.assertEqual(len(receipt['commands']), 7)
        ssl, b2 = receipt['commands'][0]['argv'], receipt['commands'][-1]['argv']
        self.assertIn('--prefix=' + str(prefix), ssl)
        self.assertIn('--openssldir=' + str(prefix / 'etc/tls'), ssl)
        self.assertIn('--prefix=' + str(prefix), b2)
        self.assertIn('--build-dir=' + str(self.work / 'build/boost'), b2)
        self.assertTrue(receipt['hostLoaderPathConfigured'])
        self.assertNotIn('/private/fixture-host-runtime', json.dumps(receipt))
        self.assertFalse(receipt['runtimeQualified'])

    def test_plan_validates_physical_provider_route_without_writes(self):
        self.fixture_inputs()
        receipt = self.invoke(plan=True)
        self.assert_commands(receipt)
        self.assertFalse(receipt['completed'])
        self.assertEqual(len(receipt['providerLibraries']), 3)
        self.assertFalse(self.work.exists())
        self.assertEqual(self.calls, [])

    def test_mocked_main_composes_all_four_extracts_headers_installs_and_logs(self):
        self.fixture_inputs()
        receipt = self.invoke()
        self.assert_commands(receipt)
        self.assertTrue(receipt['completed'])
        prefix = self.work / 'android-deps'
        for name, body in self.provider_bytes.items():
            self.assertEqual((prefix / 'lib' / name).read_bytes(), body)
            self.assertTrue((prefix / 'lib' / (name.split('.so')[0] + '.so')).is_symlink())
        self.assertEqual((prefix / 'include/openssl/ssl.h').read_bytes(), b'source-header')
        self.assertEqual((prefix / 'include/openssl/configuration.h').read_bytes(), b'generated-header')
        self.assertEqual(len(receipt['libraryHashes']), 8)
        self.assertEqual(len(self.calls), 15)
        self.assertIn([str(self.work / 'sources/boost_1_83_0/b2'), '-v'], self.calls)
        events = [json.loads(line) for line in (self.work / 'logs/executed.jsonl').read_text().splitlines()]
        self.assertEqual([event['argv'] for event in events], self.calls)
        self.assertTrue(all(Path(event['log']).is_file() for event in events))
        self.assertTrue((self.work / 'logs/version-g++.log').is_file())

    def test_provider_route_and_changed_bytes_fail_before_output_creation(self):
        self.fixture_inputs()
        original = self.manifest['files'][0]['path']
        self.manifest['files'][0]['path'] = 'data/' + Path(original).name
        self.manifest_path.write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, 'APK route'):
            self.invoke(plan=True)
        self.assertFalse(self.work.exists())
        self.manifest['files'][0]['path'] = original
        self.manifest_path.write_text(json.dumps(self.manifest))
        (self.stage / 'jniLibs/arm64-v8a' / Path(original).name).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'hash/size'):
            self.invoke(plan=True)
        self.assertFalse(self.work.exists())


if __name__ == '__main__':
    unittest.main()
