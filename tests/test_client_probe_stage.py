import io
from pathlib import Path
import struct
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from stage_client_probe import license_text, pe


class ClientProbeStageTests(unittest.TestCase):
    def test_synthetic_pe_architecture_and_bounds_checked(self):
        data = bytearray(128); data[:2] = b'MZ'; struct.pack_into('<I', data, 60, 64)
        data[64:68] = b'PE\0\0'; struct.pack_into('<H', data, 68, 0x14c)
        pe(data, 0x14c)
        with self.assertRaises(ValueError): pe(data, 0x8664)
        struct.pack_into('<I', data, 60, 120)
        with self.assertRaises(ValueError): pe(data, 0x14c)
        with self.assertRaises(ValueError): pe(b'MZ', 0x14c)

    def test_licenses_must_be_exact_regular_members_without_duplicates(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'source.tar.gz'
            with tarfile.open(path, 'w:gz') as archive:
                item = tarfile.TarInfo('COPYING'); item.size = 7; archive.addfile(item, io.BytesIO(b'license'))
            self.assertEqual(b'license', license_text(path, {'COPYING'}))
            with self.assertRaises(ValueError): license_text(path, {'LICENSE'})
            with tarfile.open(path, 'w:gz') as archive:
                for _ in range(2):
                    item = tarfile.TarInfo('COPYING'); item.size = 7; archive.addfile(item, io.BytesIO(b'license'))
            with self.assertRaises(ValueError): license_text(path, {'COPYING'})


if __name__ == '__main__':
    unittest.main()
