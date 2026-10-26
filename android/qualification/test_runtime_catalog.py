import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

from build_runtime_catalog import catalog


class RuntimeCatalogTests(unittest.TestCase):
    def fixture(self, path, mode=0o755, duplicate=False):
        payload = b'\x7fELF\x02\x01' + bytes(12) + (183).to_bytes(2, 'little') + bytes(44)
        marker = json.dumps({'format': 2}).encode()
        with tarfile.open(path, 'w:gz', format=tarfile.GNU_FORMAT) as archive:
            for name, data in [('wine', payload), ('etc/memento-client-runtime.json', marker)]:
                entry = tarfile.TarInfo(name); entry.size = len(data); entry.mode = mode
                archive.addfile(entry, io.BytesIO(data))
            if duplicate:
                archive.addfile(tarfile.TarInfo('wine'))
        return dict(archive=dict(bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest()),
            components={'wine': dict(bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest(), elfMachine=183)},
            archiveEntries=2, archiveFileBytes=len(payload) + len(marker), marker={'format': 2})

    def test_catalog_payload_offsets_hashes_and_expanded_identity_are_exact(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / 'runtime.gz'; lock = self.fixture(path)
            result = catalog(path, lock); self.assertEqual(result, catalog(path, lock))
            rows = result.decode().splitlines(); expanded = gzip.decompress(path.read_bytes())
            for row in rows[1:-1]:
                kind, name, mode, size, offset, sha, link = row.split('\t')
                self.assertEqual('f', kind); self.assertEqual('-', link)
                self.assertEqual(sha, hashlib.sha256(expanded[int(offset):int(offset) + int(size)]).hexdigest())
            self.assertEqual(['END', str(len(expanded)), hashlib.sha256(expanded).hexdigest()], rows[-1].split('\t'))

    def test_source_identity_checked_before_parsing(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / 'runtime.gz'; lock = self.fixture(path)
            path.write_bytes(b'x' * path.stat().st_size)
            with self.assertRaisesRegex(ValueError, 'runtime-hash-mismatch'): catalog(path, lock)

    def test_source_audit_cannot_be_bypassed_by_catalog_generation(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / 'runtime.gz'; lock = self.fixture(path, duplicate=True)
            with self.assertRaisesRegex(ValueError, 'duplicate-or-sparse'): catalog(path, lock)

    def test_special_permissions_are_rejected(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / 'runtime.gz'; lock = self.fixture(path, mode=0o4755)
            with self.assertRaisesRegex(ValueError, 'unsupported-runtime-metadata'): catalog(path, lock)


if __name__ == '__main__':
    unittest.main()
