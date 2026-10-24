import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from create_world_session_source import BASE_SHA, INDEX, PREFIX, record, verify_companion


class WorldSessionSourceTests(unittest.TestCase):
    def test_source_companion_binds_members_current_recipes_and_world_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'manifests').mkdir()
            pin = {'moduleSha256': 'a' * 64, 'sourceTreeSha256': 'b' * 64}
            (root / 'manifests/world-session.json').write_text(json.dumps(pin))
            (root / 'LICENSE').write_text('synthetic notice')
            proof = dict(status='compiled-world-session-qualified', **pin, requiredModuleCount=52,
                         sqlAndConfigurationsByteIdentical=True)
            members = {'baseline/source.zip': b'synthetic original source', PREFIX + 'repository/LICENSE': b'synthetic notice',
                       PREFIX + 'BUILD_EVIDENCE.json': json.dumps(proof).encode()}
            index = dict(baselineCompanionSha256=BASE_SHA, worldSession=pin, repositoryFiles=['LICENSE'],
                         files={name: record(data) for name, data in members.items()})
            def archive(values):
                p = root / 'companion.zip'
                with zipfile.ZipFile(p, 'w') as z:
                    for name, data in values.items(): z.writestr(name, data)
                    z.writestr(INDEX, json.dumps(index))
                return p
            with patch('create_world_session_source.source_files', return_value=['LICENSE']):
                self.assertRegex(verify_companion(archive(members), root), r'^[a-f0-9]{64}$')
                for name in members:
                    changed = dict(members); changed[name] = b'changed'
                    with self.assertRaises(ValueError): verify_companion(archive(changed), root)
                with self.assertRaises(ValueError): verify_companion(archive(dict(members, unexpected=b'extra')), root)
                (root / 'LICENSE').write_text('changed recipe')
                with self.assertRaises(ValueError): verify_companion(archive(members), root)


if __name__ == '__main__':
    unittest.main()
