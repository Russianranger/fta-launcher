import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from create_world_session_source import BASE_SHA, BASE_PREFIX, PREFIX, INDEX, record
from refresh_world_session_source import refresh


class RefreshWorldSourceTests(unittest.TestCase):
    def fixture(self, root):
        pin = dict(moduleSha256='a' * 64, sourceTreeSha256='b' * 64)
        files = {'LICENSE': b'original notice', 'native/retained.c': b'unchanged compiled source',
                 'tools/package_private_update.py': b'old packaging helper', 'manifests/world-session.json': json.dumps(pin).encode()}
        for name, data in files.items():
            path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        original = b'original archive bytes'; sums = (hashlib.sha256(original).hexdigest() + '  source.zip\n').encode()
        proof = dict(status='compiled-world-session-qualified', **pin, requiredModuleCount=52, sqlAndConfigurationsByteIdentical=True)
        members = {BASE_PREFIX + 'source.zip': original, BASE_PREFIX + 'SHA256SUMS': sums,
            PREFIX + 'BUILD_EVIDENCE.json': json.dumps(proof).encode(), PREFIX + 'README.txt': b'original reproduction instructions\n'}
        members.update({PREFIX + 'repository/' + name: data for name, data in files.items()})
        index = dict(baselineCompanionSha256=BASE_SHA, worldSession=pin, repositoryFiles=sorted(files),
                     files={name: record(data) for name, data in members.items()})
        source = root / 'old.zip'
        with zipfile.ZipFile(source, 'w') as archive:
            for name, data in members.items(): archive.writestr(name, data)
            archive.writestr(INDEX, json.dumps(index))
        return source, hashlib.sha256(source.read_bytes()).hexdigest(), files, sums, members

    def test_refresh_preserves_original_archives_proof_and_current_native_sources(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); source, sha, files, sums, members = self.fixture(root)
            (root / 'tools/package_private_update.py').write_bytes(b'new packaging helper')
            (root / 'tools/new_helper.py').write_bytes(b'new independent helper')
            public = sorted([*files, 'tools/new_helper.py'])
            with patch('refresh_world_session_source.source_files', return_value=public), \
                 patch('create_world_session_source.source_files', return_value=public), \
                 patch('create_world_session_source.BASE_SUMS_SHA', hashlib.sha256(sums).hexdigest()):
                output = root / 'new.zip'; actual = refresh(source, sha, output, root)
                self.assertNotEqual(sha, actual)
                with zipfile.ZipFile(output) as archive:
                    for name in (BASE_PREFIX + 'source.zip', BASE_PREFIX + 'SHA256SUMS', PREFIX + 'BUILD_EVIDENCE.json', PREFIX + 'repository/native/retained.c'):
                        self.assertEqual(members[name], archive.read(name))
                    self.assertEqual(b'new packaging helper', archive.read(PREFIX + 'repository/tools/package_private_update.py'))

    def test_wrong_input_identity_or_native_source_change_is_not_a_recipe_refresh(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); source, sha, files, sums, members = self.fixture(root)
            with self.assertRaises(ValueError): refresh(source, '0' * 64, root / 'new.zip', root)
            (root / 'native/retained.c').write_bytes(b'changed native code')
            with patch('create_world_session_source.BASE_SUMS_SHA', hashlib.sha256(sums).hexdigest()):
                with self.assertRaisesRegex(ValueError, 'Retained native reproduction input changed'):
                    refresh(source, sha, root / 'new.zip', root)
            self.assertFalse((root / 'new.zip').exists())


if __name__ == '__main__':
    unittest.main()
