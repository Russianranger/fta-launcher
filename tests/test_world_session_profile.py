import copy
import json
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from verify_world_session import profile_delta, verify_link_libraries, compiler_identity, LINK_LIBRARIES, PROFILE, MANAGER


class WorldSessionProfileTests(unittest.TestCase):
    def test_android_compiler_identity_comes_from_generated_language_config_not_cache(self):
        expected = '6156dd4e5e466333197a00b8d20bca72c292186643b749dcaaa3aa9164afb1de'
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); config = root / 'CMakeFiles/3.28.3/CMakeCXXCompiler.cmake'
            config.parent.mkdir(parents=True); compiler = root / 'clang++'; compiler.write_bytes(b'synthetic compiler')
            good = f'set(CMAKE_CXX_COMPILER "{compiler}")\nset(CMAKE_CXX_COMPILER_ID "Clang")\nset(CMAKE_CXX_COMPILER_VERSION "19.0.1")\n'
            config.write_text(good)
            with patch('verify_world_session.digest', return_value=expected): self.assertEqual(expected, compiler_identity(root))
            with patch('verify_world_session.digest', return_value='0' * 64):
                with self.assertRaisesRegex(ValueError, 'compiler bytes'): compiler_identity(root)
            config.write_text(good.replace('19.0.1', '20.0.0'))
            with self.assertRaisesRegex(ValueError, 'compiler identity'): compiler_identity(root)
            config.write_text(good)
            extra = root / 'CMakeFiles/other/CMakeCXXCompiler.cmake'; extra.parent.mkdir(); extra.write_text(good)
            with self.assertRaisesRegex(ValueError, 'ambiguous'): compiler_identity(root)

    def test_only_one_reviewed_shutdown_source_delta_can_extend_the_baseline(self):
        good = json.loads((ROOT / 'native-port/profiles.json').read_text())
        patch = profile_delta(good)
        self.assertEqual([MANAGER], list(patch['files']))
        for mutation in ('patch', 'file', 'module'):
            bad = copy.deepcopy(good); profile = bad['profiles'][PROFILE]
            if mutation == 'patch': profile['patches'][0]['sha256'] = '0' * 64
            elif mutation == 'file': profile['patches'][-1]['files']['src/server/game/Other.cpp'] = {}
            else: profile['result_inventory']['modules'].pop()
            with self.assertRaises(ValueError): profile_delta(bad)

    def test_retained_libraries_must_match_both_actual_link_and_pinned_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); reply = root / '.cmake/api/v1/reply'; reply.mkdir(parents=True)
            paths = {}; entries = []
            for key, logical in LINK_LIBRARIES.items():
                path = root / logical; data = ('synthetic ' + logical).encode(); path.write_bytes(data); paths[key] = path
                entries.append({'logicalName': logical, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
            (root / 'CMakeCache.txt').write_text(''.join(key + ':FILEPATH=' + str(path) + '\n' for key, path in paths.items()))
            (reply / 'index-test.json').write_text(json.dumps({'reply': {'codemodel-v2': {'jsonFile': 'model.json'}}}))
            (reply / 'model.json').write_text(json.dumps({'configurations': [{'targets': [{'name': 'worldserver', 'jsonFile': 'world.json'}]}]}))
            target = {'link': {'commandFragments': [{'fragment': str(path)} for path in paths.values()]}}
            (reply / 'world.json').write_text(json.dumps(target))
            self.assertEqual(6, len(verify_link_libraries(root, {'files': entries})))
            missing = copy.deepcopy(target); missing['link']['commandFragments'].pop()
            (reply / 'world.json').write_text(json.dumps(missing))
            with self.assertRaisesRegex(ValueError, 'absent from the actual world link'):
                verify_link_libraries(root, {'files': entries})
            (reply / 'world.json').write_text(json.dumps(target))
            paths['MYSQL_LIBRARY'].write_bytes(b'changed library')
            with self.assertRaises(ValueError): verify_link_libraries(root, {'files': entries})
