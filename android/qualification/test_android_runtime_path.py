import hashlib
import os
from pathlib import Path
import tempfile
import unittest

from check_android_runtime_path import verify


class AndroidRuntimePathTests(unittest.TestCase):
    def fixture(self, root):
        target = root / 'file'; target.write_bytes(b'original'); target.chmod(0o644)
        (root / 'link').symlink_to('/guest/file')
        os.link(target, root / 'hard')
        return [['f', 'file', str(0o644), '8', '512', hashlib.sha256(b'original').hexdigest(), '-'],
                ['s', 'link', '511', '0', '1024', '-', '/guest/file'],
                ['h', 'hard', str(0o644), '0', '1536', '-', 'file']]

    def test_checks_exact_files_and_guest_absolute_links_without_following(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work); rows = self.fixture(root); verify(root, rows)
            (root / 'unexpected').write_bytes(b'x')
            with self.assertRaisesRegex(ValueError, 'unexpected runtime mutation'): verify(root, rows)

    def test_replaced_bytes_modes_links_and_hardlinks_are_rejected(self):
        mutations = [lambda r: (r / 'file').write_bytes(b'changed!'), lambda r: (r / 'file').chmod(0o600),
            lambda r: ((r / 'link').unlink(), (r / 'link').symlink_to('/other')),
            lambda r: ((r / 'hard').unlink(), (r / 'hard').write_bytes(b'original'))]
        for mutate in mutations:
            with tempfile.TemporaryDirectory() as work:
                root = Path(work); rows = self.fixture(root); mutate(root)
                with self.assertRaises(ValueError): verify(root, rows)


if __name__ == '__main__':
    unittest.main()
