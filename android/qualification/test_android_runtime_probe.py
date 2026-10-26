import contextlib
import io
import subprocess
import sys
import time
import unittest

import android_runtime_probe as probe


class AndroidRuntimeProbeTests(unittest.TestCase):
    def run_probe(self, code, stage='prefix', seconds=3):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            probe.run(stage, [sys.executable, '-c', code], seconds)
        return output.getvalue()

    def test_exact_fixture_marker_and_zero_exit_required(self):
        marker = probe.MARKERS['x86-entry'].decode()
        self.assertIn('x86-entry 0', self.run_probe(f'print({marker!r})', 'x86-entry'))
        for code in ('print("missing")', f'print({marker!r}); exit(7)'):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                with self.assertRaises(RuntimeError): probe.run('x86-entry', [sys.executable, '-c', code], 3)
                self.assertNotIn('x86-entry 0', output.getvalue())

    def test_output_is_bounded_and_never_exported_verbatim(self):
        text = self.run_probe('print("private-path-and-runtime-detail" * 100000)')
        self.assertEqual('FTA_ANDROID_STAGE prefix 0\n', text)

    def test_marker_outside_output_budget_cannot_pass(self):
        marker = probe.MARKERS['x64-entry'].decode()
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(RuntimeError):
                probe.run('x64-entry', [sys.executable, '-c', f'print("x" * {probe.OUTPUT_LIMIT}); print({marker!r})'], 3)

    def test_timeout_terminates_and_reaps_owned_process(self):
        start = time.monotonic()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaises((TimeoutError, subprocess.TimeoutExpired)):
                probe.run('prefix', [sys.executable, '-c', 'import time; time.sleep(30)'], 0.1)
            self.assertIn('prefix 124', output.getvalue())
        self.assertLess(time.monotonic() - start, 4)

    def test_known_assertion_is_classified_without_raw_message(self):
        result = self.run_probe("print(\"private Couldn't detect CPU features /path\")")
        self.assertIn('FTA_ANDROID_DIAGNOSTIC cpu-features-missing', result)
        self.assertNotIn('/path', result)


if __name__ == '__main__':
    unittest.main()
