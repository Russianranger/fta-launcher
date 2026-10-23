"""Synthetic source syntax only; original source and game assets remain private."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from create_game_data_policy import FILES, cpp_text, derive


class GameDataPolicyTests(unittest.TestCase):
    def source(self):
        source = {path: b'' for path in FILES}
        lines = []
        for n in range(113):
            lines += [f'DBCStorage<T> store{n}(layout);', f'LOAD_DBC(store{n}, "File{n}.dbc");']
            if n < 7:
                lines += [f'!store{n}.LookupEntry({100+n})']
        lines += ['// DBCStorage<T> unused(unusedLayout);', '// LOAD_DBC(unused, "Unused.dbc");']
        source[FILES[0]] = '\n'.join(lines).encode()
        source[FILES[1]] = b'char constexpr layout[] = "dbbbXis"; //char constexpr unusedLayout[] = "f";'
        source[FILES[3]] = b'const uint32 MapVersionMagic = 9;'
        source[FILES[8]] = b'const char VMAP_MAGIC[] = "VMAP_4.8";'
        source[FILES[10]] = b'#define MMAP_VERSION 20'
        source[FILES[12]] = b'const int DT_NAVMESH_VERSION = 7;'
        source[FILES[6]] = b'MAP_TEST = 0'
        source[FILES[7]] = '\n'.join(f'MapMgr::ExistMapAndVMap(MAP_TEST, {n*600}.0f, 1000.0f)' for n in range(8)).encode()
        return source

    def test_comments_are_removed_without_damaging_literal_slashes(self):
        text = cpp_text(b'"https://example/*literal*/" // ignored\n/* ignored */ \'/\' "escaped\\\"//text"')
        self.assertIn('"https://example/*literal*/"', text)
        self.assertIn('"escaped\\\"//text"', text)
        self.assertNotIn('ignored', text)

    def test_only_active_loads_are_required_and_byte_fields_keep_one_byte(self):
        policy = dict(line.split('=', 1) for line in derive(self.source()).splitlines())
        self.assertEqual(policy['dbcCount'], '113')
        self.assertNotIn('dbc.Unused.dbc', policy)
        self.assertEqual(policy['dbc.File0.dbc'], '7,16,0,100')
        self.assertEqual(policy['dbc.File112.dbc'], '7,16,0,-1')
        self.assertEqual(len(policy['startupTiles'].split(',')), 8)

    def test_block_commented_load_is_not_required(self):
        source = self.source()
        source[FILES[0]] += b'\n/* DBCStorage<T> unused(unusedLayout); LOAD_DBC(unused, "Unused.dbc"); */'
        self.assertNotIn('dbc.Unused.dbc=', derive(source))

    def test_missing_or_duplicate_active_load_fails(self):
        source = self.source()
        source[FILES[0]] = source[FILES[0]].replace(b'LOAD_DBC(store112, "File112.dbc");', b'')
        with self.assertRaises(AssertionError):
            derive(source)
        source = self.source()
        source[FILES[0]] = source[FILES[0]].replace(b'"File112.dbc"', b'"File0.dbc"')
        with self.assertRaises(AssertionError):
            derive(source)

    def test_unknown_field_or_missing_sentinel_index_fails(self):
        for fmt in (b'dl', b'ii'):
            source = self.source()
            source[FILES[1]] = b'char constexpr layout[] = "' + fmt + b'";'
            with self.assertRaises(AssertionError):
                derive(source)

    def test_source_version_change_requires_deliberate_review(self):
        source = self.source()
        source[FILES[10]] = b'#define MMAP_VERSION 21'
        with self.assertRaises(AssertionError):
            derive(source)


if __name__ == '__main__':
    unittest.main()
