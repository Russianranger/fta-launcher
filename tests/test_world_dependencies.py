"""World dependency identity, containment and command tests; no compiler/network."""
from contextlib import ExitStack, redirect_stdout
from copy import deepcopy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

SOURCE = Path(__file__).resolve().parents[1] / 'native-port/build-world-dependencies.py'
SPEC = importlib.util.spec_from_file_location('world_dependencies', SOURCE)
driver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(driver)


def archive(path, root, member='configure', link=None):
    with tarfile.open(path, 'w:gz') as bundle:
        item = tarfile.TarInfo(root + '/' + member)
        item.mode = 0o755
        if link:
            item.type, item.linkname = tarfile.SYMTYPE, link
            bundle.addfile(item)
        else:
            body = b'fixture-source'
            item.size = len(body)
            bundle.addfile(item, io.BytesIO(body))


class WorldDependencyTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='fta-world-deps-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.work, self.stage, self.repo = [self.root / name for name in ('output', 'provider', 'repo')]
        self.ndk = self.root / 'ndk'
        binary = self.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        binary.mkdir(parents=True)
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 28.2.13676358\n')
        for name in ('aarch64-linux-android33-clang', 'aarch64-linux-android33-clang++',
                     'llvm-ar', 'llvm-ranlib', 'llvm-strip', 'llvm-readelf', 'clang'):
            path = binary / name
            path.write_bytes(b'fixture-ndk-tool')
            path.chmod(0o755)
        self.pins = deepcopy(driver.INPUTS)
        self.argv = [str(SOURCE), '--ndk-root', str(self.ndk), '--provider-stage', str(self.stage),
                     '--work-root', str(self.work)]
        self.archives, self.patches = {}, {}
        for key, pin in self.pins['archives'].items():
            path = self.root / (key + '.tar.gz')
            archive(path, pin['root'])
            pin['sha256'] = driver.digest(path)
            self.archives[key] = path
            self.argv.extend(['--' + key + '-archive', str(path)])
        for key, pin in self.pins['readlinePatches'].items():
            path = self.root / ('patch-' + key)
            path.write_bytes(('fixture-patch-' + key).encode())
            pin['sha256'] = driver.digest(path)
            self.patches[key] = path
            self.argv.extend(['--readline-patch-' + key, str(path)])
        (self.stage / 'assets').mkdir(parents=True)
        (self.stage / 'jniLibs/arm64-v8a').mkdir(parents=True)
        (self.repo / 'manifests').mkdir(parents=True)
        self.provider_bytes, self.native_pins, records = {}, [], []
        for name in driver.PROVIDER_NAMES:
            body = ('provider-' + name).encode()
            apk = 'libfta_' + name.replace('.', '_').replace('-', '_') + '.so'
            pin = {'logicalName': name, 'apkName': apk, 'sha256': hashlib.sha256(body).hexdigest(),
                   'size': len(body), 'role': 'library'}
            self.native_pins.append(pin)
            records.append({**pin, 'path': 'native/' + apk})
            (self.stage / 'jniLibs/arm64-v8a' / apk).write_bytes(body)
            self.provider_bytes[name] = body
        lock = {'providerId': 'fixture', 'packages': {'ncurses': {'version': '6.6.20260307+really6.5.20250830'}},
                'nativeFiles': self.native_pins}
        self.manifest = {'providerId': 'fixture', 'abi': 'arm64-v8a', 'files': records}
        self.manifest_path = self.stage / 'assets/fta-runtime-manifest.json'
        self.manifest_path.write_text(json.dumps(self.manifest))
        (self.repo / 'manifests/android-provider-lock.json').write_text(json.dumps(lock))
        self.calls = []

    def invoke(self, *, plan=True, clang='Android clang version 19.0.1', dynamic=''):
        def run(argv, **kwargs):
            self.calls.append(argv)
            self.assertTrue(kwargs['check'])
            env = kwargs['env']
            self.assertNotIn('AWS_SECRET_ACCESS_KEY', env)
            self.assertEqual(env['CFLAGS'], '-O2 -fPIC -fexceptions -fstack-protector-strong')
            self.assertTrue(env['PATH'].startswith(str(self.ndk)))
            kwargs['stdout'].write(b'fixture build output\n')
            if argv[-1] == 'install':
                installed = self.work / 'build/install/__fta_runtime__'
                (installed / 'include/readline').mkdir(parents=True)
                (installed / 'include/readline/readline.h').write_bytes(b'header')
                (installed / 'lib').mkdir()
                (installed / 'lib/libreadline.so.8.3').write_bytes(b'fixture-arm64-library')
                (installed / 'lib/libreadline.so.8').symlink_to('libreadline.so.8.3')
                (installed / 'lib/libreadline.so').symlink_to('libreadline.so.8')
        def metadata(path, readelf):
            return {'needed': sorted(driver.PROVIDER_NAMES) + ['libc.so'] if path.name.startswith('libreadline') else ['libc.so'],
                    'sonames': ['libreadline.so.8'] if path.name.startswith('libreadline') else [],
                    'interpreter': None, 'minimumLoadAlignment': 16384}
        def output(argv, **kwargs):
            return clang if '--version' in argv else dynamic
        captured = io.StringIO()
        with ExitStack() as patches:
            patches.enter_context(mock.patch.object(driver, 'REPO', self.repo))
            patches.enter_context(mock.patch.object(driver, 'INPUTS', self.pins))
            patches.enter_context(mock.patch.object(sys, 'argv', self.argv + (['--plan'] if plan else [])))
            patches.enter_context(mock.patch.object(driver.platform, 'machine', return_value='x86_64'))
            patches.enter_context(mock.patch.object(driver.sys, 'platform', 'linux'))
            patches.enter_context(mock.patch.object(driver.shutil, 'which', side_effect=lambda name: '/usr/bin/' + name))
            patches.enter_context(mock.patch.object(driver, 'check_jobs'))
            patches.enter_context(mock.patch.object(driver.subprocess, 'run', side_effect=run))
            patches.enter_context(mock.patch.object(driver.subprocess, 'check_output', side_effect=output))
            patches.enter_context(mock.patch.object(driver, 'inspect_elf', side_effect=metadata))
            patches.enter_context(mock.patch.dict(driver.os.environ, {'AWS_SECRET_ACCESS_KEY': 'private-fixture', 'CFLAGS': 'host-poison'}))
            patches.enter_context(redirect_stdout(captured))
            driver.main()
        return json.loads(captured.getvalue()) if plan else json.loads((self.work / 'receipt.json').read_text())

    def test_plan_checks_pins_and_composes_no_download_no_write(self):
        receipt = self.invoke()
        self.assertFalse(self.work.exists())
        self.assertFalse(receipt['completed'])
        self.assertFalse(receipt['runtimeQualified'])
        self.assertFalse(receipt['lua52BuiltByThisDriver'])
        self.assertEqual(self.calls, [])
        self.assertEqual(len(receipt['commands']), 8)
        encoded = json.dumps(receipt)
        self.assertNotIn('private-fixture', encoded)
        self.assertNotIn('host-poison', encoded)
        readline = receipt['commands'][-2]['argv']
        flags = next(arg for arg in readline if arg.startswith('SHLIB_XLDFLAGS='))
        self.assertIn('--no-undefined', flags)
        self.assertIn('max-page-size=16384', flags)
        self.assertIn('$(basename $@)', flags)
        self.assertNotIn('rpath', flags.lower())
        self.assertIn('--disable-static', receipt['commands'][-3]['argv'])
        self.assertEqual(receipt['commands'][4]['argv'][-3:], ['-C', 'include', 'all'])

    def test_mocked_build_patches_logs_and_preserves_provider_identity(self):
        receipt = self.invoke(plan=False)
        self.assertTrue(receipt['completed'])
        self.assertEqual(receipt['artifact']['runtimeSearchPaths'], [])
        self.assertTrue(receipt['artifact']['readelfClosureVerified'])
        self.assertEqual(receipt['artifact']['size'], len(b'fixture-arm64-library'))
        prefix = self.work / 'android-world-deps'
        self.assertEqual((prefix / 'include/readline/readline.h').read_bytes(), b'header')
        self.assertEqual((prefix / 'lib/libreadline.so').read_bytes(), b'fixture-arm64-library')
        for name, body in self.provider_bytes.items():
            self.assertTrue((prefix / 'lib' / name).is_symlink())
            self.assertEqual((prefix / 'lib' / name).read_bytes(), body)
        self.assertEqual(len(self.calls), 8)
        self.assertEqual(self.calls[0][1:4], ['--batch', '--fuzz=0', '-p0'])
        events = [json.loads(line) for line in (self.work / 'logs/executed.jsonl').read_text().splitlines()]
        self.assertEqual([event['argv'] for event in events], self.calls)
        self.assertTrue(all(Path(event['log']).is_file() for event in events))

    def test_each_input_pin_failure_prevents_output_creation(self):
        for label, path in {**self.archives, **self.patches}.items():
            with self.subTest(input=label):
                original = path.read_bytes()
                path.write_bytes(b'changed')
                with self.assertRaisesRegex(ValueError, 'pinned SHA-256'):
                    self.invoke()
                self.assertFalse(self.work.exists())
                path.write_bytes(original)

    def test_changed_provider_route_size_or_bytes_prevents_output(self):
        record = self.manifest['files'][0]
        original = record['path']
        for key, value in (('path', 'data/' + Path(original).name), ('size', 1), ('sha256', '0' * 64)):
            with self.subTest(key=key):
                before = record[key]
                record[key] = value
                self.manifest_path.write_text(json.dumps(self.manifest))
                with self.assertRaises(ValueError):
                    self.invoke()
                record[key] = before
        self.manifest_path.write_text(json.dumps(self.manifest))
        (self.stage / 'jniLibs/arm64-v8a' / Path(original).name).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'hash/size|pinned SHA-256'):
            self.invoke()
        self.assertFalse(self.work.exists())

    def test_existing_output_and_wrong_ndk_are_rejected(self):
        self.work.mkdir()
        with self.assertRaisesRegex(ValueError, 'fresh'):
            self.invoke()
        self.work.rmdir()
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 27.0.0\n')
        with self.assertRaisesRegex(ValueError, 'NDK revision'):
            self.invoke()
        self.assertFalse(self.work.exists())

    def test_output_cannot_merge_into_immutable_provider_or_ndk(self):
        for directory in (self.stage, self.ndk):
            with self.subTest(input=directory.name):
                self.argv[self.argv.index('--work-root') + 1] = str(directory / 'output')
                with self.assertRaisesRegex(ValueError, 'inside the NDK or provider'):
                    self.invoke()
                self.assertFalse((directory / 'output').exists())

    def test_wrong_clang_and_embedded_runtime_path_fail_qualification(self):
        with self.assertRaisesRegex(ValueError, 'clang version'):
            self.invoke(plan=False, clang='clang version 18.0.0')
        self.assertFalse(json.loads((self.work / 'receipt.json').read_text())['completed'])
        import shutil
        shutil.rmtree(self.work)
        with self.assertRaisesRegex(ValueError, 'runtime search paths'):
            self.invoke(plan=False, dynamic='0x1 (RUNPATH) Library runpath: [/__fta_runtime__/lib]')
        self.assertFalse(json.loads((self.work / 'receipt.json').read_text())['completed'])

    def test_archive_traversal_links_and_source_root_mismatch_are_rejected(self):
        for member, link, root in (('../escape', None, 'source'), ('alias', '../../escape', 'source'),
                                   ('configure', None, 'other')):
            with self.subTest(member=member, link=link, root=root):
                path = self.root / 'unsafe.tar.gz'
                archive(path, root, member=member, link=link)
                with self.assertRaises(ValueError):
                    driver.extract(path, self.root / 'unpacked', 'source')
                self.assertFalse((self.root / 'escape').exists())


if __name__ == '__main__':
    unittest.main()
