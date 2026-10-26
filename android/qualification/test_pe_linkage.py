import struct
import unittest

from pe_linkage import PE, InvalidPE
from warcraftxl_contract import REQUIRED_EXPORTS, assess, proxy_exports_satisfy


def image(wide=False):
    data = bytearray(0x3200)
    data[:2] = b'MZ'; struct.pack_into('<I', data, 60, 0x80)
    data[0x80:0x84] = b'PE\0\0'
    size = 240 if wide else 224
    struct.pack_into('<HHIIIHH', data, 0x84, 0x8664 if wide else 0x14c, 1, 0, 0, 0, size, 0x2002)
    struct.pack_into('<H', data, 0x98, 0x20b if wide else 0x10b)
    start = 112 if wide else 96
    struct.pack_into('<I', data, 0x98 + start - 4, 16)
    row = 0x98 + size
    data[row:row + 8] = b'.fixture'
    struct.pack_into('<IIII', data, row + 8, 0x3000, 0x1000, 0x3000, 0x200)
    struct.pack_into('<I', data, row + 36, 0x60000020)
    return data


def put(data, rva, value):
    data[rva - 0x1000 + 0x200:rva - 0x1000 + 0x200 + len(value)] = value


def directory(data, index, rva, size, wide=False):
    struct.pack_into('<II', data, 0x98 + (112 if wide else 96) + index * 8, rva, size)


def imports(wide=False):
    data = image(wide)
    directory(data, 1, 0x1100, 40, wide)
    put(data, 0x1100, struct.pack('<IIIII', 0x1300, 0, 0, 0x1200, 0x1380))
    put(data, 0x1200, b'd3d9.dll\0')
    put(data, 0x1300, struct.pack('<Q' if wide else '<I', 0x1500))
    put(data, 0x1500, b'\0\0WxlGetControlSettings\0')
    return data


def exports(names=None):
    names = sorted(REQUIRED_EXPORTS) if names is None else names
    data = image()
    directory(data, 0, 0x1100, 0x700)
    put(data, 0x1100 + 20, struct.pack('<IIIII', len(names), len(names), 0x1200, 0x1300, 0x1400))
    cursor = 0x1500
    for i, name in enumerate(names):
        put(data, 0x1200 + i * 4, struct.pack('<I', 0x2000 + i * 16))
        put(data, 0x1300 + i * 4, struct.pack('<I', cursor))
        put(data, 0x1400 + i * 2, struct.pack('<H', i))
        put(data, cursor, name.encode() + b'\0'); cursor += len(name) + 1
    return data


class PeLinkageTests(unittest.TestCase):
    def test_reads_named_imports_for_both_pe_widths(self):
        for wide in (False, True):
            pe = PE(imports(wide))
            self.assertEqual('x64' if wide else 'x86', pe.architecture)
            self.assertEqual({'d3d9.dll': ['WxlGetControlSettings']}, pe.imports())

    def test_ordinal_import_is_not_a_named_symbol(self):
        for wide in (False, True):
            data = imports(wide)
            put(data, 0x1300, struct.pack('<Q' if wide else '<I', (1 << (63 if wide else 31)) | 12))
            self.assertEqual([12], PE(data).imports()['d3d9.dll'])

    def test_ordinal_reserved_bits_rejected(self):
        data = imports(); put(data, 0x1300, struct.pack('<I', 0x80010001))
        with self.assertRaises(InvalidPE): PE(data).imports()

    def test_first_thunk_fallback(self):
        data = imports(); put(data, 0x1100, struct.pack('<I', 0))
        put(data, 0x1380, struct.pack('<I', 0x1500))
        self.assertEqual(['WxlGetControlSettings'], PE(data).imports()['d3d9.dll'])

    def test_unreferenced_strings_are_not_imports(self):
        data = image(); put(data, 0x1200, b'd3d9.dll\0WxlGetControlSettings\0')
        self.assertEqual({}, PE(data).imports())

    def test_import_directory_requires_bounded_terminator(self):
        data = imports(); directory(data, 1, 0x1100, 20)
        with self.assertRaisesRegex(InvalidPE, 'unterminated-import-directory'): PE(data).imports()

    def test_duplicate_import_libraries_rejected(self):
        data = imports(); directory(data, 1, 0x1100, 60)
        put(data, 0x1114, struct.pack('<IIIII', 0x1300, 0, 0, 0x1200, 0x1380))
        with self.assertRaisesRegex(InvalidPE, 'duplicate-import-library'): PE(data).imports()

    def test_string_length_is_bounded(self):
        data = imports(); put(data, 0x1502, b'x' * 257 + b'\0')
        with self.assertRaisesRegex(InvalidPE, 'pe-name-limit'): PE(data).imports()

    def test_rva_must_have_unique_backing(self):
        data = imports(); put(data, 0x1300, struct.pack('<I', 0x7ffffff0))
        with self.assertRaisesRegex(InvalidPE, 'ambiguous-or-unmapped-rva'): PE(data).imports()
        data = imports(); struct.pack_into('<H', data, 0x86, 2)
        row = 0x98 + 224; data[row + 40:row + 80] = data[row:row + 40]
        with self.assertRaisesRegex(InvalidPE, 'ambiguous-or-unmapped-rva'): PE(data).imports()

    def test_invalid_headers_and_section_bounds_rejected(self):
        for change in (lambda d: d.__setitem__(0, 0),
                       lambda d: struct.pack_into('<H', d, 0x84, 0xaa64),
                       lambda d: struct.pack_into('<I', d, 0x98 + 224 + 20, len(d))):
            data = image(); change(data)
            with self.assertRaises(InvalidPE): PE(data)
        with self.assertRaises(InvalidPE): PE(b'MZ')

    def test_reads_required_direct_executable_exports(self):
        self.assertTrue(proxy_exports_satisfy(REQUIRED_EXPORTS, PE(exports())))

    def test_missing_or_ordinal_only_exports_do_not_match(self):
        self.assertFalse(proxy_exports_satisfy(REQUIRED_EXPORTS, PE(exports(['Other']))))
        self.assertFalse(proxy_exports_satisfy(REQUIRED_EXPORTS, PE(image())))

    def test_forwarder_is_not_a_direct_proxy_export(self):
        data = exports(); put(data, 0x1200, struct.pack('<I', 0x1700))
        put(data, 0x1700, b'OTHER.WxlGetControlOption\0')
        self.assertTrue(next(iter(PE(data).exports().values()))['forwarded'])
        self.assertFalse(proxy_exports_satisfy(REQUIRED_EXPORTS, PE(data)))

    def test_nonexecutable_and_null_targets_do_not_match(self):
        data = exports(); struct.pack_into('<I', data, 0x98 + 224 + 36, 0x40000040)
        self.assertFalse(proxy_exports_satisfy(REQUIRED_EXPORTS, PE(data)))
        data = exports(); put(data, 0x1200, struct.pack('<I', 0))
        self.assertFalse(proxy_exports_satisfy(REQUIRED_EXPORTS, PE(data)))

    def test_invalid_export_ordinal_rejected(self):
        data = exports(); put(data, 0x1400, struct.pack('<H', 99))
        with self.assertRaises(InvalidPE): PE(data).exports()

    def test_export_count_bound_and_duplicate_names(self):
        data = exports(); put(data, 0x1100 + 20, struct.pack('<I', 8193))
        with self.assertRaisesRegex(InvalidPE, 'pe-export-limit'): PE(data).exports()
        data = exports(['Same', 'Same'])
        with self.assertRaisesRegex(InvalidPE, 'duplicate-export-name'): PE(data).exports()

    def test_wrong_architecture_or_non_dll_cannot_be_proxy(self):
        data = exports(); struct.pack_into('<H', data, 0x96, 2)
        for data in (data, image(True)):
            with self.assertRaisesRegex(InvalidPE, 'proxy-architecture-mismatch'):
                proxy_exports_satisfy(REQUIRED_EXPORTS, PE(data))

    def test_parser_read_budget_is_enforced(self):
        pe = PE(image()); pe.budget = 1
        with self.assertRaisesRegex(InvalidPE, 'pe-read-budget'): pe.at(0, 2)

    def test_mismatched_dll_cannot_claim_the_pinned_contract(self):
        with self.assertRaisesRegex(InvalidPE, '^warcraftxl-identity-mismatch$'):
            assess(exports())


if __name__ == '__main__':
    unittest.main()
