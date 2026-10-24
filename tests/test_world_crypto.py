import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from check_world_crypto import embedded_source_root
from build_openssl_legacy import safe_relative


class WorldCryptoTests(unittest.TestCase):
    def test_native_embedded_path_must_be_unique_complete_and_absolute(self):
        path = b'/synthetic/build/src/common/Cryptography/ARC4.cpp\0'
        self.assertEqual('/synthetic/build', embedded_source_root(path + path))
        for data in (b'ARC4.cpp\0', path[:-1], path + b'/different/src/common/Cryptography/ARC4.cpp\0'):
            with self.assertRaises(ValueError): embedded_source_root(data)

    def test_toolchain_archive_paths_cannot_escape(self):
        for path in ('/absolute', '../escape', 'a/../b', 'a\\b', 'a\0b'):
            with self.assertRaises(ValueError): safe_relative(path)

    def test_packaging_and_runtime_share_exact_module_and_retained_crypto_pins(self):
        pin = json.loads((ROOT / 'manifests/openssl-legacy.json').read_text())
        source = (ROOT / 'android/app/src/main/java/com/russianranger/fta/runtime/OpenSslLegacyProvider.kt').read_text()
        self.assertIn('const val MODULE_SHA256 = "' + pin['moduleSha256'] + '"', source)
        self.assertIn('const val MODULE_BYTES = ' + str(pin['moduleBytes']) + 'L', source)
        self.assertIn('const val CRYPTO_SHA256 = "' + pin['retainedCryptoSha256'] + '"', source)
        provider = json.loads((ROOT / 'manifests/android-provider-lock.json').read_text())
        self.assertEqual(1, len([f for f in provider['nativeFiles'] if f['sha256'] == pin['retainedCryptoSha256']]))
        inputs = json.loads((ROOT / 'native-port/android-inputs.json').read_text())['inputs']
        self.assertEqual(inputs['openssl']['sha256'], pin['sourceArchiveSha256'])
        self.assertEqual(inputs['ndk']['sha256'], pin['ndkArchiveSha256'])


if __name__ == '__main__':
    unittest.main()
