"""Provider safety tests use generated archives and mocked ELF metadata only."""
from pathlib import Path
import hashlib
import io
import json
import stat
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import stage_android_provider as provider


def ar_archive(payload: bytes, name: str = 'data.tar.xz') -> bytes:
    header = (name + '/').ljust(16) + '0'.ljust(12) + '0'.ljust(6) + '0'.ljust(6)
    header += '100644'.ljust(8) + str(len(payload)).ljust(10) + '`\n'
    return b'!<arch>\n' + header.encode('ascii') + payload + (b'\n' if len(payload) % 2 else b'')


def deb_fixture(entries: list[tuple[str, bytes | str]]) -> bytes:
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode='w:xz') as archive:
        for name, content in entries:
            entry = tarfile.TarInfo(name)
            if isinstance(content, str):
                entry.type = tarfile.SYMTYPE
                entry.linkname = content
                archive.addfile(entry)
            else:
                entry.size = len(content)
                archive.addfile(entry, io.BytesIO(content))
    return ar_archive(data.getvalue())


ELF_OUTPUT = '''ELF Header:
  Class:                             ELF64
  Type:                              DYN (Position-Independent Executable file)
  Machine:                           AArch64
  INTERP 0x000001 0x000001 0x000001 0x000020 0x000020 R 0x1
      [Requesting program interpreter: /system/bin/linker64]
  LOAD 0x000000 0x000000 0x000000 0x001000 0x001000 R E 0x4000
 0x0000000000000001 (NEEDED) Shared library: [libc.so]
'''


class PackageTests(unittest.TestCase):
    def test_unsafe_paths_are_refused(self):
        for name in ('../outside', '/tmp/file', 'C:/file', 'a\\b', 'a/./b', 'a//b', ''):
            with self.subTest(name=name), self.assertRaises(provider.ProviderError):
                provider.safe_name(name)
        self.assertEqual(provider.safe_name('./data/file', leading_dot=True), 'data/file')

    def test_regular_files_are_copied_without_alias_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'input.deb'
            archive.write_bytes(deb_fixture([
                (provider.PREFIX + 'lib/libexample.so.1', b'contents'),
                (provider.PREFIX + 'lib/libexample.so', 'libexample.so.1'),
            ]))
            output = root / 'output'
            provider.extract_deb(archive, output)
            self.assertEqual((output / provider.PREFIX / 'lib/libexample.so.1').read_bytes(), b'contents')
            self.assertFalse((output / provider.PREFIX / 'lib/libexample.so').exists())

    def test_escape_links_and_duplicate_paths_are_refused(self):
        cases = [
            [(provider.PREFIX + 'lib/escape', '../../../../../../outside')],
            [(provider.PREFIX + 'lib/escape', '/tmp/outside')],
            [(provider.PREFIX + 'file', b'a'), (provider.PREFIX + 'file', b'b')],
            [(provider.PREFIX + 'File', b'a'), (provider.PREFIX + 'file', b'b')],
            [('../outside', b'a')],
        ]
        for entries in cases:
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as directory:
                archive = Path(directory) / 'input.deb'
                archive.write_bytes(deb_fixture(entries))
                with self.assertRaises(provider.ProviderError):
                    provider.extract_deb(archive, Path(directory) / 'output')

    def test_truncated_ar_member_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'input.deb'
            archive.write_bytes(ar_archive(b'contents')[:-3])
            with self.assertRaises(provider.ProviderError):
                provider.deb_data(archive)

    def test_member_size_limits_precede_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(provider.ProviderError):
                provider.copy_stream(io.BytesIO(b'example'), Path(directory) / 'file', provider.MAX_FILE + 1)
            self.assertFalse((Path(directory) / 'file').exists())

    def test_cached_hash_mismatch_never_fetches_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            cached = Path(directory) / 'input.deb'
            cached.write_bytes(b'wrong')
            with mock.patch.object(provider, 'urlopen') as network:
                with self.assertRaises(provider.ProviderError):
                    provider.fetch_pinned('https://example.invalid/input.deb', cached, '0' * 64)
                network.assert_not_called()
            self.assertEqual(cached.read_bytes(), b'wrong')


class ElfTests(unittest.TestCase):
    def test_android_elf_metadata_and_alignment(self):
        with mock.patch.object(provider.subprocess, 'check_output', return_value=ELF_OUTPUT):
            metadata = provider.inspect_elf(Path('/fixture'))
        self.assertEqual(metadata['interpreter'], '/system/bin/linker64')
        self.assertEqual(metadata['needed'], ['libc.so'])
        self.assertEqual(metadata['minimumLoadAlignment'], 16384)

    def test_host_architecture_linker_or_bad_alignment_is_refused(self):
        for text in (ELF_OUTPUT.replace('AArch64', 'Advanced Micro Devices X86-64'),
                     ELF_OUTPUT.replace('/system/bin/linker64', '/lib/ld-linux-aarch64.so.1'),
                     ELF_OUTPUT.replace('0x4000', '0x3000')):
            with self.subTest(text=text), mock.patch.object(provider.subprocess, 'check_output', return_value=text):
                with self.assertRaises(provider.ProviderError):
                    provider.inspect_elf(Path('/fixture'))

    def test_private_dependency_closure_must_be_complete(self):
        executable = {'role': 'database-server', 'interpreter': '/system/bin/linker64',
                      'needed': ['libc.so', 'libprivate.so.1']}
        library = {'role': 'library', 'logicalName': 'libprivate.so.1', 'needed': ['libc.so']}
        provider.check_closure([executable, library])
        for records in ([executable], [library, library],
                        [{**executable, 'needed': ['libc.so.6']}],
                        [{**executable, 'interpreter': None}, library]):
            with self.assertRaises(provider.ProviderError):
                provider.check_closure(records)

    def test_world_runtime_can_refuse_embedded_library_search_paths(self):
        for tag in ('RPATH', 'RUNPATH'):
            output = ELF_OUTPUT + f'\n 0x000000000000001d ({tag}) Library runpath: [/private/build/lib]\n'
            with self.subTest(tag=tag), mock.patch.object(provider.subprocess, 'check_output', return_value=output):
                with self.assertRaises(provider.ProviderError):
                    provider.inspect_elf(Path('/fixture'), reject_search_paths=True)

    def test_native_hash_is_checked_before_elf_parser(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'binary'
            path.write_bytes(b'wrong')
            with mock.patch.object(provider, 'inspect_elf') as parser:
                with self.assertRaises(provider.ProviderError):
                    provider.verify_native(path, {'size': 5, 'sha256': '0' * 64}, 'readelf')
                parser.assert_not_called()


class BootstrapAndApkTests(unittest.TestCase):
    def test_recorded_provider_notices_are_complete_and_pinned(self):
        lock = json.loads(provider.LOCK.read_text())
        notices = provider.legal_inputs(lock)
        names = {pin['name'] for pin, _ in notices}
        self.assertIn('libcxx-NDK-r29-NOTICE', names)
        upstream = next(path for pin, path in notices if pin['name'] == 'POCKET_REALM_THIRD_PARTY_NOTICES.md')
        self.assertEqual(provider.digest(upstream), lock['fallbackApk']['noticeSha256'])

    def test_notice_gate_precedes_distribution_processing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'output'
            with mock.patch.object(provider, 'legal_inputs', side_effect=provider.ProviderError('Missing notice')):
                with mock.patch.object(provider, 'apk_inputs') as unpack:
                    with self.assertRaises(provider.ProviderError):
                        provider.stage(output, {}, source_apk=Path(directory) / 'input.apk')
                    unpack.assert_not_called()
            self.assertFalse(output.exists())

    def test_missing_or_modified_upstream_notice_refuses_stage(self):
        lock = json.loads(provider.LOCK.read_text())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'notice.md'
            path.write_bytes(b'changed upstream source offer')
            with self.assertRaises(provider.ProviderError):
                provider.legal_inputs(lock, notices_path=path)
            with mock.patch.object(provider, 'LEGAL', Path(directory)):
                with self.assertRaises(provider.ProviderError):
                    provider.legal_inputs(lock)

    def test_bootstrap_preserves_official_sql_and_checks_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            share = Path(directory)
            (share / 'one.sql').write_text('SELECT 1;\n')
            body = b'create database if not exists mysql;\nuse mysql;\nSET @auth_root_socket=NULL;\nSELECT 1;\n'
            pin = {'inputs': ['one.sql'], 'sha256': hashlib.sha256(body).hexdigest()}
            self.assertEqual(provider.bootstrap(share, pin), body)
            with self.assertRaises(provider.ProviderError):
                provider.bootstrap(share, {**pin, 'sha256': '0' * 64})
            (share / 'one.sql').write_text('SELECT @BUILD_PATH@;')
            with self.assertRaises(provider.ProviderError):
                provider.bootstrap(share, pin)

    def test_fallback_container_identity_precedes_zip_read(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'fixture.apk'
            archive.write_bytes(b'not a zip')
            with mock.patch.object(provider.zipfile, 'ZipFile') as reader:
                with self.assertRaises(provider.ProviderError):
                    provider.apk_inputs(archive, Path(directory) / 'out',
                                        {'fallbackApk': {'size': 9, 'sha256': '0' * 64}})
                reader.assert_not_called()

    def test_apk_traversal_duplicate_and_symlinks_refused(self):
        for names in (['../outside'], ['assets/a', 'assets/a'], ['assets/A', 'assets/a'], ['SYMLINK']):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as directory:
                archive = Path(directory) / 'fixture.apk'
                with warnings.catch_warnings(), zipfile.ZipFile(archive, 'w') as output:
                    warnings.simplefilter('ignore', UserWarning)
                    for name in names:
                        item = zipfile.ZipInfo(name)
                        if name == 'SYMLINK':
                            item.create_system = 3
                            item.external_attr = (stat.S_IFLNK | 0o777) << 16
                        output.writestr(item, b'fixture')
                lock = {'fallbackApk': {'size': archive.stat().st_size, 'sha256': provider.digest(archive)}, 'nativeFiles': []}
                with self.assertRaises(provider.ProviderError):
                    provider.apk_inputs(archive, Path(directory) / 'output', lock)

    def test_failed_stage_never_publishes_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source.apk'
            source.write_bytes(b'wrong')
            lock = {'fallbackApk': {'size': 5, 'sha256': '0' * 64}}
            with self.assertRaises(provider.ProviderError):
                provider.stage(root / 'output', lock, source_apk=source)
            self.assertFalse((root / 'output').exists())
            self.assertEqual(list(root.glob('.fta-provider-*')), [])

    def test_existing_stage_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'output'
            output.mkdir()
            (output / 'old').write_bytes(b'preserved')
            with self.assertRaises(provider.ProviderError):
                provider.stage(output, {}, source_apk=root / 'any.apk')
            self.assertEqual((output / 'old').read_bytes(), b'preserved')


if __name__ == '__main__':
    unittest.main()
