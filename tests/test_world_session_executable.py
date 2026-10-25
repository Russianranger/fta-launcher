import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from verify_world_session import profile_delta, PROFILE


class WorldSessionExecutableTests(unittest.TestCase):
    def test_native_pin_and_android_launch_pin_are_identical(self):
        pin = json.loads((ROOT / 'manifests/world-session.json').read_text())
        kotlin = (ROOT / 'android/app/src/main/java/com/russianranger/fta/runtime/WorldSessionExecutable.kt').read_text()
        self.assertIn('const val SHA256 = "' + pin['moduleSha256'] + '"', kotlin)
        self.assertIn('const val BYTES = ' + str(pin['moduleBytes']) + 'L', kotlin)
        self.assertIn('const val NAME = "' + pin['moduleName'] + '"', kotlin)
        self.assertIn('const val PROFILE = "' + pin['sourceProfile'] + '"', kotlin)
        self.assertIn(pin['baselineWorldSha256'], kotlin)
        self.assertIn(pin['baselineManifestSha256'], kotlin)
        self.assertIn(pin['compiledUpdaterLockSha256'], kotlin)
        self.assertEqual('/system/bin/linker64', pin['elf']['interpreter'])
        self.assertGreaterEqual(pin['elf']['minimumLoadAlignment'], 16384)
        profiles = json.loads((ROOT / 'native-port/profiles.json').read_text())
        self.assertEqual(pin['sourceTreeSha256'], profiles['profiles'][PROFILE]['result_inventory']['tree_sha256'])
        self.assertEqual(pin['patchSha256'], profile_delta(profiles)['sha256'])


if __name__ == '__main__':
    unittest.main()
