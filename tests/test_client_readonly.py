import io
import json
from pathlib import Path
import re
import sys
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from build_client_bridge import ROOT
from check_client_readonly import checks
from stage_client_readonly import PAYLOADS, NOTICE, metadata, verify_apk
from stage_client_probe import BRIDGE


class ClientReadonlyTests(unittest.TestCase):
    def test_controller_pins_and_exact_protocol_match_native_fixture(self):
        directory = ROOT / 'android/app/src/main/java/com/russianranger/fta/client'
        native = checks()
        kotlin = re.findall(r'"([a-z-]+)"', (directory / 'ClientIsolationProtocol.kt').read_text())
        self.assertEqual(native, kotlin)
        pins = dict(re.findall(r'"(lib[a-z_-]+\.so)" to "([0-9a-f]{64})"', (directory / 'ClientIsolationPins.kt').read_text()))
        self.assertEqual({**PAYLOADS, 'libproot-loader.so': BRIDGE['libproot-loader.so']}, pins)
        self.assertEqual(50, len(native))
        self.assertEqual(2, len(PAYLOADS))
        self.assertNotIn('libproot.so', PAYLOADS)

    def test_packaging_refuses_altered_source_missing_notice_and_partial_guard(self):
        content = {'assets/client-isolation/manifest.json': json.dumps(metadata()).encode(),
                   'assets/client-isolation/NOTICE.txt': NOTICE}
        for name in (*PAYLOADS, 'libproot-loader.so'): content['lib/arm64-v8a/' + name] = b'bad ELF'
        for operation in ('source', 'notice', 'extra', 'payload'):
            data = content.copy()
            if operation == 'source':
                bad = metadata(); bad['sourceSha256']['native/client-bridge/read_only.c'] = '0' * 64
                data['assets/client-isolation/manifest.json'] = json.dumps(bad).encode()
            if operation == 'notice': data.pop('assets/client-isolation/NOTICE.txt')
            if operation == 'extra': data['assets/client-isolation/extra'] = b'extra'
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w') as out:
                for name, value in data.items(): out.writestr(name, value)
            with zipfile.ZipFile(stream) as archive, self.assertRaises(ValueError): verify_apk(archive)

if __name__ == '__main__': unittest.main()
