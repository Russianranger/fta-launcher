import struct
import unittest

from pe_linkage import PE, InvalidPE
from test_pe_linkage import image, put, directory
from warcraftxl_contract import HOST_KERNEL_IMPORTS, HOST_LIBRARIES, host_imports_satisfy, inspect_host
from check_warcraftxl_host import expected_channels, expected_header


def host_image(libraries=None):
    libraries = libraries if libraries is not None else {
        'kernel32.dll': sorted(HOST_KERNEL_IMPORTS), 'user32.dll': ['wsprintfA'],
        'wininet.dll': ['InternetOpenA']}
    data = image(True); struct.pack_into('<H', data, 0x96, 0x22)
    directory(data, 1, 0x1100, (len(libraries) + 1) * 20, True)
    cursor = 0x1800
    for index, (name, symbols) in enumerate(libraries.items()):
        name_rva, thunk = 0x1200 + index * 32, 0x1300 + index * 0x100
        put(data, 0x1100 + index * 20, struct.pack('<IIIII', thunk, 0, 0, name_rva, thunk))
        put(data, name_rva, name.encode() + b'\0')
        for slot, symbol in enumerate(symbols):
            put(data, thunk + slot * 8, struct.pack('<Q', cursor))
            value = b'\0\0' + symbol.encode() + b'\0'
            put(data, cursor, value); cursor += len(value)
    return data


class WarcraftXlHostTests(unittest.TestCase):
    def test_original_static_library_roles_and_api_requirements(self):
        self.assertEqual(HOST_LIBRARIES, set(host_imports_satisfy(PE(host_image()))))

    def test_missing_or_additional_libraries_rejected(self):
        for names in ({}, {'kernel32.dll': sorted(HOST_KERNEL_IMPORTS)},
                      {'kernel32.dll': sorted(HOST_KERNEL_IMPORTS), 'user32.dll': ['wsprintfA'],
                       'wininet.dll': ['InternetOpenA'], 'unexpected.dll': ['Other']}):
            with self.assertRaisesRegex(InvalidPE, 'host-static-import-mismatch'):
                host_imports_satisfy(PE(host_image(names)))

    def test_every_required_api_is_checked(self):
        for missing in HOST_KERNEL_IMPORTS:
            names = {'kernel32.dll': sorted(HOST_KERNEL_IMPORTS - {missing}),
                     'user32.dll': ['wsprintfA'], 'wininet.dll': ['InternetOpenA']}
            with self.assertRaisesRegex(InvalidPE, 'host-static-import-mismatch'):
                host_imports_satisfy(PE(host_image(names)))

    def test_host_must_be_x64_executable_not_dll(self):
        for data in (image(False), image(True)):
            with self.assertRaisesRegex(InvalidPE, 'host-architecture-mismatch'):
                host_imports_satisfy(PE(data))

    def test_uninspected_delay_or_clr_dependency_fails_closed(self):
        for index, size in ((13, 32), (14, 72)):
            data = host_image(); directory(data, index, 0x2800, size, True)
            with self.assertRaisesRegex(InvalidPE, 'unexpected-host-delay-or-clr-directory'):
                host_imports_satisfy(PE(data))

    def test_another_valid_host_cannot_claim_original_identity(self):
        with self.assertRaisesRegex(InvalidPE, 'host-identity-mismatch'):
            inspect_host(host_image(), PE(image(False)))

    def test_expected_channel_selection_includes_unknown_and_unsigned_values(self):
        for count in range(65):
            self.assertEqual(4 if count == 0 else min(16, max(2, count)), expected_channels(count))
        for count in (0x7fffffff, 0x80000000, 0xffffffff):
            self.assertEqual(16, expected_channels(count))
        for value in (-1, 0x100000000, True, 1.0):
            with self.assertRaises(InvalidPE): expected_channels(value)

    def test_expected_snapshot_preserves_all_64_bytes_and_unsigned_process_word(self):
        for channels in range(2, 17):
            for pid in (0, 1, 0x80000000, 0xffffffff):
                words = struct.unpack('<16I', expected_header(channels, pid))
                self.assertEqual((0x4c585857, 4, 0, 0, 0, 0, channels, pid) + (0,) * 8, words)

    def test_invalid_fixture_inputs_do_not_wrap_into_valid_headers(self):
        for channels, pid in ((1, 1), (17, 1), (2, -1), (2, 0x100000000), (True, 1), (2, True)):
            with self.assertRaises(InvalidPE): expected_header(channels, pid)


if __name__ == '__main__':
    unittest.main()
