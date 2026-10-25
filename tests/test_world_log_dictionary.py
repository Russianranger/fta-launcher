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
        self.assertEqual(asset, ''.join(f"{e['prefixSha256']}\t{e['id']}\n" for e in proof['events']) +
                         ''.join(f"{c['categorySha256']}\t{c['id']}\n" for c in proof['warningCategories']))
        self.assertEqual(22, len(proof['warningCategories']))
        self.assertEqual(17, len(proof['startupModules']))
        self.assertEqual(447, len(proof['events']))
        self.assertEqual(81, len(proof['warningSources']))
        original = ''.join(asset.splitlines(keepends=True)[:160])
        self.assertEqual('93fd3a4b6f0c567f7ab261997737c5fcaee31dd9fa74c3a0c97804f086724ae2',
                         hashlib.sha256(original.encode()).hexdigest())
        self.assertFalse(proof['crashCauseProven'])
        self.assertFalse(proof['hookCompletionProven'])

    def test_warnings_outside_startup_modules_are_appended_without_variable_prefixes(self):
        initial = {'modules/mod-start/src/hook.cpp': b'void OnStartup() {}\nLOG_INFO("module", "Existing startup marker");'}
        old, before = dictionary(initial)
        sources = dict(initial, **{
            'src/server/game/example.cpp': b'LOG_INFO("core", "Unselected normal info");\n'
                b'LOG_WARN("core", "Source warning prefix {}", private_value);\n'
                b'LOG_WARN("core", "{} dynamic-first-warning", private_value);',
            'modules/mod-other/src/other.cpp': b'LOG_WARN("module", "Another warning prefix {}", private_value);',
        })
        asset, proof = dictionary(sources)
        self.assertTrue(asset.startswith(old))
        self.assertEqual(before['events'], proof['events'][:1])
        self.assertEqual(3, len(proof['events']))
        self.assertEqual(2, len(proof['warningSources']))
        self.assertEqual(2, len(proof['warningCategories']))
        core = next(c for c in proof['warningCategories'] if c['categorySha256'] == hashlib.sha256(b'core').hexdigest())
        self.assertEqual(2, len(core['sourceLocations']))
        self.assertNotIn('private_value', asset + json.dumps(proof))
        self.assertNotIn('warning prefix', asset + json.dumps(proof))


if __name__ == '__main__':
    unittest.main()
