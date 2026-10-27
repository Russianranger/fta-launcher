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
from unittest import mock

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

    def test_late_and_split_diagnostics_survive_full_capture_without_unbounded_storage(self):
        capture = probe.OutputCapture()
        capture.append(b'x' * (probe.OUTPUT_LIMIT + 100))
        capture.append(b'private boot event wait ti')
        capture.append(b'med out /private/path')
        self.assertEqual(probe.OUTPUT_LIMIT, len(capture.data))
        self.assertLessEqual(len(capture.tail), 256)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            capture.report()
        self.assertEqual('FTA_ANDROID_DIAGNOSTIC wineboot-event-timeout\n', output.getvalue())

    def test_slow_success_is_allowed_without_turning_actual_timeouts_into_passes(self):
        command = [sys.executable, '-c', 'import time; time.sleep(0.2)']
        with contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaises(TimeoutError): probe.run('prefix', command, 0.05)
            self.assertIn('prefix 124', output.getvalue())
        with contextlib.redirect_stdout(io.StringIO()) as output:
            probe.run('prefix', command, 2)
            self.assertIn('prefix 0', output.getvalue())

    def test_progress_continues_after_command_closes_output_and_stops_on_deadline(self):
        samples = []
        code = 'import os,time; os.close(1); os.close(2); time.sleep(30)'
        with mock.patch.object(probe, 'PROGRESS_SECONDS', 0.03), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(TimeoutError):
                probe.run('prefix', [sys.executable, '-c', code], 0.25, progress=samples.append)
        self.assertGreaterEqual(len(samples), 3)
        self.assertLess(samples[-1], 3)

    def test_exited_command_does_not_wait_for_noisy_inherited_pipe(self):
        # The descendant has its own one-second failsafe and is never signalled
        # through a reused parent PID. Closing the read end ends its writes.
        code = '''import os,time
if os.fork() == 0:
    end = time.monotonic() + 1
    try:
        while time.monotonic() < end: os.write(1, b'x' * 16384)
    except BrokenPipeError: pass
    os._exit(0)
time.sleep(0.03)
'''
        started = time.monotonic()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            probe.run('prefix', [sys.executable, '-c', code], 0.5)
        self.assertIn('prefix 0', output.getvalue())
        self.assertLess(time.monotonic() - started, 0.8)

    def test_prefix_progress_reports_only_bounded_metadata_and_never_registry_content(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()) as output:
            root = Path(directory)
            (root / 'drive_c/windows/system32').mkdir(parents=True)
            (root / 'drive_c/windows/system32/private.dll').write_text('private')
            (root / 'system.reg').write_text('secret')
            (root / 'user.reg').symlink_to(root / 'system.reg')
            (root / '.update-timestamp').write_text('1')
            probe.prefix_progress(2.5, root)
            self.assertEqual('FTA_ANDROID_PREFIX_PROGRESS 2500 1 0 6 1\n', output.getvalue())

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
