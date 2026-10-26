import hashlib
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest

from check_client_runtime import file_identity, inspect_tar, member_name


def archive(rows):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as tar:
        for name, value in rows:
            item = tarfile.TarInfo(name)
            if isinstance(value, tuple):
                item.type, item.linkname = value
                tar.addfile(item)
            else:
                item.size = len(value); tar.addfile(item, io.BytesIO(value))
    output.seek(0)
    return tarfile.open(fileobj=output, mode='r|')


def elf():
    value = bytearray(64); value[:6] = b'\x7fELF\x02\x01'; struct.pack_into('<H', value, 18, 183)
    return bytes(value)


class ClientRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.data = elf(); self.name = 'opt/wine/bin/wine'
        self.required = {self.name: dict(bytes=64, sha256=hashlib.sha256(self.data).hexdigest(), elfMachine=183)}
        self.rows = [('etc/memento-client-runtime.json', json.dumps({'format': 2}).encode()), (self.name, self.data)]

    def inspect(self, rows):
        with archive(rows) as tar:
            return inspect_tar(tar, self.required)

    def test_reads_regular_components_without_extracting(self):
        result = self.inspect(self.rows)
        self.assertEqual(self.required, result['components']); self.assertEqual(2, result['entries'])

    def test_identity_checks_bytes_before_archive_read(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture'; path.write_bytes(self.data)
            expected = self.required[self.name]; file_identity(path, expected)
            path.write_bytes(self.data[:-1])
            with self.assertRaisesRegex(ValueError, 'runtime-size-mismatch'): file_identity(path, expected)
            path.write_bytes(b'!' * 64)
            with self.assertRaisesRegex(ValueError, 'runtime-hash-mismatch'): file_identity(path, expected)

    def test_unsafe_member_paths_rejected(self):
        for path in ('../escape', '/absolute', 'a//b', 'a/./b', 'a/../b', 'a\\b', 'a\nb', ''):
            with self.assertRaisesRegex(ValueError, 'unsafe-runtime-path'): member_name(path)

    def test_duplicate_paths_rejected(self):
        with self.assertRaisesRegex(ValueError, 'duplicate-or-sparse'):
            self.inspect(self.rows + [(self.name, self.data)])

    def test_missing_and_linked_components_rejected(self):
        with self.assertRaisesRegex(ValueError, 'missing-runtime-components'): self.inspect(self.rows[:1])
        with self.assertRaisesRegex(ValueError, 'runtime-component-not-regular'):
            self.inspect(self.rows[:1] + [(self.name, (tarfile.SYMTYPE, 'other'))])

    def test_component_digest_and_machine_are_independent_requirements(self):
        with self.assertRaisesRegex(ValueError, 'runtime-component-hash-mismatch'):
            self.inspect(self.rows[:1] + [(self.name, b'!' * 64)])
        changed = bytearray(self.data); changed[18] = 62
        self.required[self.name]['sha256'] = hashlib.sha256(changed).hexdigest()
        with self.assertRaisesRegex(ValueError, 'runtime-elf-mismatch'):
            self.inspect(self.rows[:1] + [(self.name, bytes(changed))])

    def test_absolute_guest_symlink_is_allowed_only_without_descendant_entries(self):
        rows = self.rows + [('bin', (tarfile.SYMTYPE, '/usr/bin'))]
        self.assertEqual(1, self.inspect(rows)['linkCount'])
        for rows in (rows + [('bin/escape', b'no')], [('bin/escape', b'no')] + rows):
            with self.assertRaisesRegex(ValueError, 'runtime-entry-beneath-link'): self.inspect(rows)

    def test_escaping_links_and_nonregular_hardlink_targets_rejected(self):
        for name, target in [('escape', '../outside'), ('dir/escape', '../../outside')]:
            with self.assertRaisesRegex(ValueError, 'escaping-runtime-link'):
                self.inspect(self.rows + [(name, (tarfile.SYMTYPE, target))])
        with self.assertRaisesRegex(ValueError, 'unsafe-runtime-hardlink'):
            self.inspect(self.rows + [('linked', (tarfile.LNKTYPE, 'missing'))])

    def test_devices_are_never_extracted_from_runtime(self):
        with self.assertRaisesRegex(ValueError, 'special-runtime-entry'):
            self.inspect(self.rows + [('device', (tarfile.CHRTYPE, ''))])

    def test_marker_missing_or_oversized_rejected(self):
        with self.assertRaisesRegex(ValueError, 'missing-runtime-marker'): self.inspect(self.rows[1:])
        with self.assertRaisesRegex(ValueError, 'runtime-marker-limit'):
            self.inspect([(self.rows[0][0], b' ' * 4097)] + self.rows[1:])


if __name__ == '__main__':
    unittest.main()
