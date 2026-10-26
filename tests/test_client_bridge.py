import hashlib
import io
from pathlib import Path
import struct
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import build_client_bridge as bridge


def loader():
    data = bytearray(256)
    data[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<HHIQQ', data, 16, 2, 183, 1, 0x600000c0, 64)
    struct.pack_into('<HHH', data, 52, 64, 56, 2)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0x60000000, 0x60000000, 256, 256, 16384)
    struct.pack_into('<IIQQQQQQ', data, 120, 0x6474e551, 6, 0, 0, 0, 0, 0, 16)
    return data


class ClientBridgeTest(unittest.TestCase):
    def rejects(self, offset, fmt, value, reason):
        data = loader(); struct.pack_into(fmt, data, offset, value)
        with self.assertRaisesRegex(ValueError, reason):
            bridge.loader_elf(data)

    def test_freestanding_loader_with_16k_segments(self):
        result = bridge.loader_elf(loader())
        self.assertEqual(result['minimumLoadAlignment'], 16384)
        self.assertEqual(result['type'], 'EXEC')
        self.assertFalse(result['executableStack'])

    def test_previous_4k_alignment_is_rejected(self):
        self.rejects(112, '<Q', 4096, '16 KiB')

    def test_misaligned_virtual_mapping_is_rejected(self):
        self.rejects(80, '<Q', 0x60000001, '16 KiB')

    def test_wrong_architecture_and_type_are_rejected(self):
        self.rejects(18, '<H', 62, 'architecture')
        self.rejects(16, '<H', 3, 'architecture')

    def test_truncated_and_oversized_program_table_are_rejected(self):
        self.rejects(56, '<H', 17, 'headers')
        with self.assertRaisesRegex(ValueError, 'headers'):
            bridge.loader_elf(loader()[:100])

    def test_dynamic_loader_and_interpreter_are_rejected(self):
        for kind in (2, 3):
            self.rejects(120, '<I', kind, 'dynamic linkage')

    def test_segment_data_must_fit_file_and_memory(self):
        self.rejects(96, '<Q', 257, 'bounds')
        self.rejects(104, '<Q', 100, 'bounds')

    def test_entry_must_be_in_file_backed_executable_segment(self):
        self.rejects(24, '<Q', 0x60000100, 'entry')
        self.rejects(68, '<I', 4, 'entry')

    def test_writable_code_and_executable_or_missing_stack_rejected(self):
        self.rejects(68, '<I', 7, 'Writable executable')
        self.rejects(124, '<I', 7, 'executable stack')
        self.rejects(120, '<I', 0, 'executable stack')

    def test_recipe_transformation_fails_closed_on_drift(self):
        self.assertEqual(bridge.replace_exact('a-a', 'a', 'b', 2), 'b-b')
        with self.assertRaisesRegex(ValueError, 'recipe differs'):
            bridge.replace_exact('a-a', 'a', 'b')

    def test_source_recovery_requires_exact_regular_unique_members(self):
        data = b'synthetic source'
        def run(rows):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); bundle = root / 'input.tar.gz'
                with tarfile.open(bundle, 'w:gz') as archive:
                    for content, kind in rows:
                        item = tarfile.TarInfo('launcher-source/example'); item.type = kind
                        item.size = len(content) if kind == tarfile.REGTYPE else 0
                        archive.addfile(item, io.BytesIO(content))
                with patch.dict(bridge.INPUTS, {'example': hashlib.sha256(data).hexdigest()}, clear=True):
                    bridge.recover_sources(bundle, root / 'out')
                return (root / 'out/example').read_bytes()
        self.assertEqual(run([(data, tarfile.REGTYPE)]), data)
        for rows, reason in (([], 'Missing'), ([(b'changed', tarfile.REGTYPE)], 'identity'),
                             ([(data, tarfile.SYMTYPE)], 'member'),
                             ([(data, tarfile.REGTYPE)] * 2, 'member')):
            with self.assertRaisesRegex(ValueError, reason):
                run(rows)


if __name__ == '__main__':
    unittest.main()
