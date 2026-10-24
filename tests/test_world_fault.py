import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class WorldFaultTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('gcc'), 'Linux host compiler required')
    def test_real_signal_fixture_preserves_exit_and_emits_only_stable_executable_offsets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run([sys.executable, str(ROOT / 'tools/check_world_fault.py'), '--output', str(root / 'proof.json'),
                            '--build-log', str(root / 'build.log')], check=True, capture_output=True, timeout=30)
            proof = json.loads((root / 'proof.json').read_text())
            self.assertTrue(proof['normalExitPreserved'] and proof['sigsegvPreserved'] and proof['sigbusPreserved'])
            self.assertTrue(proof['sigtermUntouched'] and proof['sigabrtUntouched'] and proof['faultOffsetMatchesSymbol'])
            self.assertTrue(proof['repeatedFaultOffsetsStable'])
            self.assertTrue(proof['preinstalledHandlersChained'] and proof['originalSiginfoContextMaskAndAlternateStackPreserved'])
            self.assertTrue(proof['oneShotHandlerPreserved'] and proof['ignoredFatalSignalRefused'])
            self.assertFalse(proof['rawAddressesExported'] or proof['memoryContentsExported'])

    def test_runtime_and_packager_pin_same_capture_module(self):
        pin = json.loads((ROOT / 'manifests/world-fault.json').read_text())
        kotlin = (ROOT / 'android/app/src/main/java/com/russianranger/fta/runtime/WorldFaultCapture.kt').read_text()
        import hashlib
        self.assertEqual(pin['sourceSha256'], hashlib.sha256((ROOT / 'native/world-fault/world_fault.c').read_bytes()).hexdigest())
        self.assertIn('const val SHA256 = "' + pin['moduleSha256'] + '"', kotlin)
        self.assertIn('const val BYTES = ' + str(pin['moduleBytes']) + 'L', kotlin)
        self.assertEqual(16384, pin['elf']['minimumLoadAlignment'])


if __name__ == '__main__':
    unittest.main()
