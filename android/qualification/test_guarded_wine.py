import contextlib
import io
from pathlib import Path
import re
import unittest

from check_guarded_wine import transcript
from guarded_wine import CHECKS, STAGES, StorageCapture


class GuardedWineTests(unittest.TestCase):
    def output(self):
        return '\n'.join([*(f'FTA_ANDROID_STAGE {s} 0' for s in STAGES),
            *(f'FTA_GUARDED_WIN {a} {n} 0' for a in ('x86', 'x64') for n in CHECKS), 'FTA_GUARDED_DELETE x86 1 0', 'FTA_GUARDED_DELETE x64 1 0', 'FTA_GUARDED_WINE_PASS'])

    def test_every_windows_check_matches_the_compiled_fixture_contract(self):
        source = (Path(__file__).parent / 'wine_storage_probe.c').read_text()
        self.assertEqual(list(CHECKS), re.findall(r'check\("([a-z-]+)"', source))
        self.assertEqual(len(CHECKS), len(set(CHECKS)))
        self.assertEqual(28, transcript(self.output(), 0)['checksPerArchitecture'])

    def test_missing_duplicate_failed_and_unknown_checks_reject(self):
        good = self.output()
        for line in good.splitlines():
            with self.assertRaises(ValueError): transcript(good.replace(line, ''), 0)
            with self.assertRaises(ValueError): transcript(good + '\n' + line, 0)
        with self.assertRaises(ValueError): transcript(good, 1)
        with self.assertRaises(ValueError): transcript(good.replace('x64 log-write 0', 'x64 log-write 1'), 0)
        with self.assertRaises(ValueError): transcript(good + '\nFTA_GUARDED_WIN x64 unknown 0', 0)

    def test_capture_only_exports_known_bounded_windows_records(self):
        capture = StorageCapture()
        capture.append(b'PRIVATE\nFTA_GUARDED_WIN x86 seek 0\r\nFTA_GUARDED_WIN x64 unknown 0\n')
        output = io.StringIO()
        with contextlib.redirect_stdout(output): capture.report()
        self.assertEqual('FTA_GUARDED_WIN x86 seek 0\n', output.getvalue())
        capture.append(b'x' * (1024 * 1024))
        self.assertLessEqual(len(capture.data), 256 * 1024)


if __name__ == '__main__': unittest.main()
