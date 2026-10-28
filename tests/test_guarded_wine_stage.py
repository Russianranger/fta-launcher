import hashlib
import io
import json
from pathlib import Path
import re
import struct
import sys
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import stage_guarded_wine as stage


class GuardedWineStageTests(unittest.TestCase):
    def entries(self):
        files = {'android_probe.py': (stage.ROOT / stage.SOURCES[2]).read_bytes(),
                 'guarded_wine.py': (stage.ROOT / stage.SOURCES[1]).read_bytes(), 'NOTICE.txt': stage.NOTICE}
        for name, machine in [('probe32.exe', 0x14c), ('probe64.exe', 0x8664)]:
            data = bytearray(128); data[:2] = b'MZ'; struct.pack_into('<I', data, 60, 64)
            data[64:68] = b'PE\0\0'; struct.pack_into('<H', data, 68, machine); files[name] = bytes(data)
        manifest = stage.metadata({n: dict(bytes=len(b), sha256=hashlib.sha256(b).hexdigest()) for n, b in files.items()})
        return {**{'assets/client-guarded-wine/' + n: b for n, b in files.items()},
                'assets/client-guarded-wine/manifest.json': json.dumps(manifest).encode(),
                'lib/arm64-v8a/libproot_ro.so': b'guard', 'lib/arm64-v8a/libproot-loader.so': b'loader'}

    def verify(self, entries):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            for n, b in entries.items(): archive.writestr(n, b)
        with zipfile.ZipFile(data) as archive: return stage.verify_apk(archive)

    def test_exact_inventory_hash_architecture_and_native_identity_are_required(self):
        bridges = {n: hashlib.sha256(b).hexdigest() for n, b in [('libproot_ro.so', b'guard'), ('libproot-loader.so', b'loader')]}
        with patch.object(stage, 'BRIDGES', bridges):
            good = self.entries(); self.assertEqual(5, len(self.verify(good)['files']))
            for name in good:
                changed = dict(good); changed[name] += b'changed'
                with self.assertRaises((ValueError, json.JSONDecodeError)): self.verify(changed)
            with self.assertRaises(ValueError): self.verify(dict(good, **{'assets/client-guarded-wine/unlisted': b'unknown'}))
            broken = dict(good); prefix = 'assets/client-guarded-wine/'
            broken[prefix + 'probe32.exe'] = broken[prefix + 'probe64.exe']
            manifest = json.loads(broken[prefix + 'manifest.json']); manifest['files']['probe32.exe'] = manifest['files']['probe64.exe']
            broken[prefix + 'manifest.json'] = json.dumps(manifest).encode()
            with self.assertRaises(ValueError): self.verify(broken)

    def test_android_and_guest_check_lists_and_stages_agree(self):
        text = (stage.ROOT / 'android/app/src/main/java/com/russianranger/fta/client/ClientGuardedWine.kt').read_text()
        expected = stage.contract()
        for name in ('stages', 'checks'):
            body = text.split('val ' + name + ' = listOf(', 1)[1].split(')', 1)[0]
            self.assertEqual(expected[name], re.findall(r'"([a-z0-9-]+)"', body))
        self.assertEqual(28, len(expected['checks']))


if __name__ == '__main__': unittest.main()
