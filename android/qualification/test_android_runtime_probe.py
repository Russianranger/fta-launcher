import contextlib
import io
import subprocess
import sys
import time
import unittest
from pathlib import Path
import tempfile
import errno
import socket

import android_runtime_probe as probe


class AndroidRuntimeProbeTests(unittest.TestCase):
    def require_unix_socket(self):
        # Some local execution sandboxes deny all socket creation/bind. Native
        # ARM64 CI still requires the actual socket regression and Wine run.
        with tempfile.TemporaryDirectory() as directory:
            try:
                with socket.socket(socket.AF_UNIX) as listener:
                    listener.bind(str(Path(directory) / 'capability'))
            except OSError as error:
                if error.errno in (errno.EPERM, errno.EACCES):
                    self.skipTest('Host sandbox denies Unix sockets; live regression required in CI')
                raise
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

    def test_display_early_exit_is_not_reported_as_timeout_and_output_is_redacted(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaises(RuntimeError):
                probe.start_display([sys.executable, '-c', 'print("Permission denied /secret"); exit(7)'],
                                    Path(directory) / 'missing', 2)
            text = output.getvalue()
            self.assertIn('FTA_ANDROID_DISPLAY_EXIT 7', text)
            self.assertIn('FTA_ANDROID_STAGE display 7', text)
            self.assertIn('FTA_ANDROID_DIAGNOSTIC permission-denied', text)
            self.assertNotIn('/secret', text); self.assertNotIn('124', text)

    def test_display_timeout_stops_owned_child(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()) as output:
            began = time.monotonic()
            with self.assertRaises(RuntimeError):
                probe.start_display([sys.executable, '-c', 'import time; time.sleep(30)'], Path(directory) / 'missing', 0.1)
            self.assertIn('FTA_ANDROID_DISPLAY_EVENT timeout', output.getvalue())
            self.assertLess(time.monotonic() - began, 4)

    def test_display_drain_is_bounded_and_ready_socket_requires_live_child(self):
        self.require_unix_socket()
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()) as output:
            path = Path(directory) / 'X8'
            code = f'import socket,time; print("private" * 300000, flush=True); s=socket.socket(socket.AF_UNIX); s.bind({str(path)!r}); time.sleep(30)'
            child, capture = probe.start_display([sys.executable, '-c', code], path, 3)
            try:
                self.assertLessEqual(len(capture.data), probe.OUTPUT_LIMIT)
                self.assertIsNone(child.poll())
            finally:
                probe.stop(child); capture.close()
            self.assertFalse(capture.thread.is_alive())
            self.assertIn('FTA_ANDROID_DISPLAY_EVENT ready', output.getvalue())
            self.assertNotIn('private', output.getvalue())

    def test_socket_preflight_unlinks_only_its_own_socket(self):
        self.require_unix_socket()
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            path = Path(directory) / 'socket' / 'X8'
            self.assertEqual(0, probe.socket_check(path)); self.assertFalse(path.exists())
            path.write_text('retain unrelated entry')
            self.assertNotEqual(0, probe.socket_check(path)); self.assertEqual('retain unrelated entry', path.read_text())


if __name__ == '__main__':
    unittest.main()
