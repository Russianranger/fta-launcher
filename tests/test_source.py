"""Adversarial archive and build-profile tests; no proprietary input is needed."""
from pathlib import Path
import argparse
import hashlib
import io
import stat
import sys
import tempfile
import unittest
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from fta_source import SourceError, checked_entries, inventory_tree, stage_source
from build_native import BuildError, check_cache, check_jobs, commands, read_cache


class SourceTests(unittest.TestCase):
    def archive(self, names):
        data = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(data, 'w') as archive:
                for name in names:
                    archive.writestr(name, b'fixture')
        return zipfile.ZipFile(io.BytesIO(data.getvalue()))

    def test_reject_unsafe_paths(self):
        for name in ('../outside', '/AzerothCore_Linux/file', 'C:/file',
                     'AzerothCore_Linux/../outside', 'AzerothCore_Linux/./file',
                     'AzerothCore_Linux//file', 'AzerothCore_Linux\\file', 'Other/file'):
            with self.subTest(name=name), self.archive([name]) as archive:
                with self.assertRaises(SourceError):
                    checked_entries(archive)

    def test_duplicate_and_file_directory_collision(self):
        for names in (['AzerothCore_Linux/a', 'AzerothCore_Linux/a'],
                      ['AzerothCore_Linux/a', 'AzerothCore_Linux/a/b']):
            with self.archive(names) as archive:
                with self.assertRaises(SourceError):
                    checked_entries(archive)

    def test_pinned_distribution_readme_is_allowed(self):
        with self.archive(['README.txt', 'AzerothCore_Linux/CMakeLists.txt']) as archive:
            self.assertEqual(len(checked_entries(archive)), 2)

    def test_symlink_refused(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            entry = zipfile.ZipInfo('AzerothCore_Linux/link')
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, '../outside')
        with zipfile.ZipFile(io.BytesIO(data.getvalue())) as archive:
            with self.assertRaises(SourceError):
                checked_entries(archive)

    def test_limits_checked_before_reading(self):
        with self.archive(['AzerothCore_Linux/file']) as archive:
            archive.infolist()[0].file_size = 129 * 1024 * 1024
            with self.assertRaises(SourceError):
                checked_entries(archive)

    def test_verified_copy_preserves_contents_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            archive_path = parent / 'source.zip'
            with zipfile.ZipFile(archive_path, 'w') as archive:
                archive.writestr('AzerothCore_Linux/modules/mod-example/src/file.cpp', b'original\x00bytes')
                archive.writestr('AzerothCore_Linux/LICENSE', b'fixture license')
            digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
            destination = parent / 'source'
            with self.assertRaises(SourceError):
                stage_source(archive_path, destination, expected_sha256='0' * 64)
            self.assertFalse(destination.exists())
            stage_source(archive_path, destination, expected_sha256=digest)
            self.assertEqual((destination / 'modules/mod-example/src/file.cpp').read_bytes(), b'original\x00bytes')
            first = inventory_tree(destination)
            self.assertEqual(first['modules'], ['mod-example'])
            self.assertEqual(first, inventory_tree(destination))
            with self.assertRaises(SourceError):
                stage_source(archive_path, destination, expected_sha256=digest)
            (destination / 'LICENSE').write_bytes(b'changed')
            self.assertNotEqual(first['tree_sha256'], inventory_tree(destination)['tree_sha256'])


class BuildTests(unittest.TestCase):
    def arguments(self, **overrides):
        values = dict(source=Path('/private/source'), build_dir=Path('/private/build'), platform='host',
                      stage='auth', jobs=1, cmake='cmake', configure_only=False, toolchain=None,
                      dependency_prefix=None, mysql_library=None, mysql_include=None, mysql_executable=None,
                      c_compiler=None, cxx_compiler=None)
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_auth_first_and_required_module_profile(self):
        plan = commands(self.arguments(stage='world'))
        self.assertIn('-DMODULES=static', plan[0])
        self.assertIn('-DSCRIPTS=static', plan[0])
        self.assertIn('-DCMAKE_CXX_STANDARD=20', plan[0])
        self.assertEqual(plan[1][plan[1].index('--target') + 1], 'authserver')
        self.assertEqual(plan[2][plan[2].index('--target') + 1], 'worldserver')

    def test_no_build_inside_source(self):
        with self.assertRaises(BuildError):
            commands(self.arguments(build_dir=Path('/private/source/build')))

    def test_existing_cache_cannot_drop_modules(self):
        for cache in ({'DISABLED_AC_MODULES':'mod-pocket-pals'},
                      {'MODULE_MOD-POCKET-PALS':'disabled'}, {'SCRIPTS_WORLD':'disabled'},
                      {'MODULE_MOD-POCKET-PALS':'dynamic'}):
            with self.assertRaises(BuildError):
                check_cache(cache, 'host', configured=False)

    def test_job_and_memory_limits(self):
        with self.assertRaises(BuildError):
            check_jobs(3, available=100 * 1024**3)
        with self.assertRaises(BuildError):
            check_jobs(2, available=2 * 1024**3)
        check_jobs(1, available=3 * 1024**3)

    def test_real_cache_metadata_does_not_disable_modules(self):
        with tempfile.TemporaryDirectory() as directory:
            cache_path = Path(directory) / 'CMakeCache.txt'
            cache_path.write_text('MODULE_MOD-POCKET-PALS:STRING=default\n'
                                  'MODULE_MOD-POCKET-PALS-STRINGS:INTERNAL=default;disabled;static;dynamic\n'
                                  'MODULE_MOD-POCKET-PALS-ADVANCED:INTERNAL=1\n'
                                  'SCRIPTS_WORLD:STRING=static\n'
                                  'SCRIPTS_WORLD-STRINGS:INTERNAL=default;disabled;static;dynamic\n')
            check_cache(read_cache(cache_path), 'host', configured=False)

    def test_cross_build_needs_explicit_toolchain(self):
        with self.assertRaises(BuildError):
            commands(self.arguments(platform='android'))

    def test_cross_build_prevents_host_mysql_config_probe(self):
        with tempfile.TemporaryDirectory() as directory:
            toolchain = Path(directory) / 'toolchain.cmake'
            toolchain.write_text('# fixture\n')
            plan = commands(self.arguments(platform='android', toolchain=toolchain,
                             mysql_library=Path('/target/lib/libmariadb.so'),
                             mysql_include=Path('/target/include/mariadb')))
            self.assertIn('-DMYSQL_CONFIG:FILEPATH=', plan[0])
            with self.assertRaises(BuildError):
                check_cache({'MYSQL_CONFIG':'/usr/bin/mysql_config'}, 'android', configured=False)


if __name__ == '__main__':
    unittest.main()
