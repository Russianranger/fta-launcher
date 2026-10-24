import hashlib
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from world_log_dictionary import ASSET, PROOF, dictionary


class WorldLogDictionaryTests(unittest.TestCase):
    def test_only_literal_prefixes_from_startup_modules_are_fingerprinted(self):
        sources = {
            'modules/mod-example/src/hook.cpp': b'void OnStartup() {}\nLOG_INFO("module", "Sixteen chars!!! " "private-format {}", value);\n'
                b'LOG_ERROR("module", "{} dynamic-first private", value);\n',
            'modules/mod-unrelated/src/log.cpp': b'LOG_INFO("module", "Unrelated long message");',
        }
        asset, proof = dictionary(sources)
        self.assertEqual(['mod-example'], proof['startupModules'])
        self.assertEqual(1, len(proof['events']))
        self.assertEqual(hashlib.sha256(b'Sixteen chars!!!').hexdigest(), proof['events'][0]['prefixSha256'])
        self.assertNotIn('private-format', asset + json.dumps(proof))
        self.assertEqual(2, proof['events'][0]['line'])

    def test_checked_in_dictionary_matches_redacted_source_index(self):
        proof = json.loads(PROOF.read_text())
        asset = ASSET.read_text()
        self.assertEqual(proof['dictionarySha256'], hashlib.sha256(asset.encode()).hexdigest())
        self.assertEqual(asset, ''.join(f"{e['prefixSha256']}\t{e['id']}\n" for e in proof['events']))
        self.assertEqual(17, len(proof['startupModules']))
        self.assertEqual(160, len(proof['events']))
        self.assertFalse(proof['crashCauseProven'])
        self.assertFalse(proof['hookCompletionProven'])


if __name__ == '__main__':
    unittest.main()
