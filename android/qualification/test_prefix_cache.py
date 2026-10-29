import hashlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from original_startup import PrefixCache, prepared, READY, PREFIX_STAGES
from check_original_startup import prefix_transcript


class PrefixCacheTests(unittest.TestCase):
    def test_real_sync_and_advice_preserve_bytes_and_skip_unchanged_files(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); (root / 'windows').mkdir()
            data = root / 'windows' / 'data'; data.write_bytes(bytes(range(256)) * 4096)
            digest = hashlib.sha256(data.read_bytes()).digest()
            cache = PrefixCache(root)
            try:
                cache.sweep(); self.assertEqual((1, 1, 0, 1048576), (cache.synced, cache.accepted, cache.errors, cache.bytes))
                cache.sweep(); self.assertEqual(1, cache.accepted)
                self.assertEqual(digest, hashlib.sha256(data.read_bytes()).digest())
                data.write_bytes(b'changed'); cache.sweep(); self.assertEqual(2, cache.accepted)
                with patch('sys.stdout', new_callable=io.StringIO) as out: cache.report()
                self.assertEqual('FTA_PREFIX_CACHE 2 2 0 1048583\n', out.getvalue())
            finally: cache.close()
            self.assertEqual(b'changed', data.read_bytes())

    def test_symlinks_hardlinks_and_special_files_never_reach_advice(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); owned = root / 'owned'; owned.mkdir()
            external = root / 'external'; external.mkdir(); target = external / 'data'; target.write_bytes(b'protected')
            (owned / 'link').symlink_to(target); (owned / 'dir').symlink_to(external, target_is_directory=True)
            os.link(target, owned / 'hard'); os.mkfifo(owned / 'pipe')
            cache = PrefixCache(owned)
            try:
                with patch('original_startup.os.posix_fadvise') as advise: cache.sweep(); advise.assert_not_called()
            finally: cache.close()
            self.assertEqual(b'protected', target.read_bytes())

    def test_replaced_directory_cannot_redirect_open_outside_owned_tree(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); owned = root / 'owned'; owned.mkdir(); (owned / 'dir').mkdir()
            external = root / 'external'; external.mkdir(); (external / 'data').write_bytes(b'private')
            real_open = os.open; swapped = False
            def open_after_check(path, flags, *args, **kwargs):
                nonlocal swapped
                if path == 'dir' and not swapped:
                    swapped = True; (owned / 'dir').rmdir(); (owned / 'dir').symlink_to(external)
                return real_open(path, flags, *args, **kwargs)
            cache = PrefixCache(owned)
            try:
                with patch('original_startup.os.open', side_effect=open_after_check), patch('original_startup.os.posix_fadvise') as advise:
                    cache.sweep(); advise.assert_not_called()
                self.assertEqual(1, cache.errors)
            finally: cache.close()

    def test_failed_sync_does_not_advise_dirty_bytes_and_worker_closes(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); (root / 'data').write_bytes(b'value')
            cache = PrefixCache(root)
            with patch('original_startup.os.fdatasync', side_effect=OSError('test')), patch('original_startup.os.posix_fadvise') as advise:
                cache.sweep(); advise.assert_not_called()
            self.assertEqual((0, 0, 1), (cache.synced, cache.accepted, cache.errors))
            cache.start(); cache.close(); self.assertFalse(cache.thread.is_alive())

    def test_preparation_receipt_and_complete_prefix_are_required(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); session = root / 'session'; session.mkdir(); prefix = root / 'prefix'; prefix.mkdir()
            with self.assertRaises(FileNotFoundError): prepared(prefix, session)
            (session / 'prefix-ready').write_bytes(READY)
            with self.assertRaises(FileNotFoundError): prepared(prefix, session)
            for name in ('system.reg', 'user.reg', 'userdef.reg'): (prefix / name).write_bytes(b'synthetic')
            for name in ('system32', 'syswow64'):
                folder = prefix / 'drive_c/windows' / name; folder.mkdir(parents=True); (folder / 'dll').write_bytes(b'synthetic')
            prepared(prefix, session)
            (session / 'prefix-ready').write_bytes(b'wrong')
            with self.assertRaises(RuntimeError): prepared(prefix, session)

    def test_prefix_transcript_cannot_contain_client_execution_or_missing_cleanup(self):
        text = '\n'.join([f'FTA_ANDROID_STAGE {s} 0' for s in PREFIX_STAGES] + ['FTA_PREFIX_CACHE 2 2 0 100', 'FTA_CLIENT_PREFIX_PASS'])
        self.assertEqual(100, prefix_transcript(text, 0)['advisedBytes'])
        for bad in (text + '\nFTA_CLIENT_EVENT client-started 1', text.replace('wine-stop 0', 'wine-stop 1'),
                    text + '\nFTA_CLIENT_PREFIX_FAILED', text.replace('2 2 0 100', '0 0 1 0')):
            with self.assertRaises(ValueError): prefix_transcript(bad, 0)


if __name__ == '__main__': unittest.main()
