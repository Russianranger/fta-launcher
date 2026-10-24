import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from check_script_unload import function

ROOT = Path(__file__).resolve().parents[1]


class ScriptUnloadTests(unittest.TestCase):
    def test_profile_extends_exact_original_build_with_one_shutdown_file(self):
        profiles = json.loads((ROOT / 'native-port/profiles.json').read_text())['profiles']
        old, new = profiles['android-arm64-world-v13'], profiles['android-arm64-world-v14']
        self.assertEqual(old['patches'], new['patches'][:-1])
        patch = new['patches'][-1]
        self.assertEqual({'src/server/game/Scripting/ScriptMgr.cpp'}, set(patch['files']))
        self.assertEqual(patch['sha256'], hashlib.sha256((ROOT / 'native-port' / patch['path']).read_bytes()).hexdigest())
        for key in ('file_count', 'modules', 'license_files'):
            self.assertEqual(old['result_inventory'][key], new['result_inventory'][key])
        self.assertEqual(52, len(new['result_inventory']['modules']))

    def test_probe_keeps_nested_original_function_body(self):
        body = 'void Subject::Unload()\n{\n  for (auto item : items) { release(item); }\n}\n'
        self.assertEqual(body, function('prefix\n' + body + 'void other() {}', 'void Subject::Unload()'))

    def test_wrong_source_archive_fails_before_compiler_or_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'wrong.zip'; source.write_bytes(b'wrong private input')
            output = root / 'proof.json'; logs = root / 'logs'
            result = subprocess.run([sys.executable, str(ROOT / 'tools/check_script_unload.py'),
                '--source-zip', str(source), '--output', str(output), '--log-directory', str(logs)], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b'Original source archive differs', result.stderr)
            self.assertFalse(output.exists()); self.assertFalse(logs.exists())


if __name__ == '__main__':
    unittest.main()
