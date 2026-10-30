import io
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

from original_startup import FaultTrace, StartupCapture, image_sizes, FAULT_BOUNDS


class StartupFaultTests(unittest.TestCase):
    def trace(self, pid=b'00fc', path=rb'Z:\\view\\Wow.exe', address=b'00401234'):
        return (pid + b':0100:trace:loaddll:build_module Loaded L"' + path + b'" at 00400000: native\n' +
            pid + b':0100:trace:seh:dispatch_exception code=c0000005 (EXCEPTION_ACCESS_VIOLATION) flags=0 addr=' + address + b'\n' +
            pid + b':0100:trace:seh:dispatch_exception  info[0]=00000001\n' +
            pid + b':0100:trace:seh:dispatch_exception  info[1]=00100000\n')

    def complete(self):
        return self.trace() + b'FTA_CLIENT_PROCESS 252\nFTA_CLIENT_EVENT client-started 1\nFTA_CLIENT_EVENT client-early-exit 3221225477\nFTA_CLIENT_EXIT 252 3221225477 1234\n'

    def test_chunked_logs_and_late_pid_produce_only_exact_numeric_observations(self):
        for chunk in (1, 7, 4096):
            trace = FaultTrace({1: 0x20000})
            data = self.complete()
            for start in range(0, len(data), chunk): trace.feed(data[start:start+chunk])
            v = trace.values(); self.assertEqual(FAULT_BOUNDS.keys(), v.keys())
            self.assertEqual((1, 0x401234, 0x1234, 1, 0x100000, 1, 1234),
                (v['moduleId'], v['exceptionAddress'], v['moduleRva'], v['accessType'], v['accessAddress'], v['exceptionMatchesExit'], v['exitElapsedMs']))

    def test_other_process_and_thread_records_cannot_supply_client_fault_details(self):
        trace = FaultTrace({1: 0x20000})
        trace.feed(self.trace(pid=b'00ab') + b'FTA_CLIENT_PROCESS 252\n')
        self.assertEqual(0, trace.values()['accessViolationCount'])
        trace.feed(self.trace().replace(b':0100:trace:seh:dispatch_exception  info', b':0101:trace:seh:dispatch_exception  info'))
        self.assertEqual(1, trace.values()['accessViolationCount']); self.assertEqual(0, trace.values()['accessDetailsKnown'])

    def test_unknown_paths_ranges_and_unloaded_images_are_not_named(self):
        for prefix in (self.trace(path=rb'Z:\\private\\Wow.exe'), self.trace(address=b'00600000'),
                b'00fc:0100:trace:loaddll:build_module Loaded L"Z:\\view\\Wow.exe" at 00400000: native\n' +
                b'00fc:0100:trace:loaddll:free_modref Unloaded module L"Z:\\view\\Wow.exe" : native\n' + self.trace().split(b'\n', 1)[1],
                b'00fc:0100:trace:loaddll:build_module Loaded L"Z:\\view\\Wow.exe" at 00400000: native\n' +
                self.trace(path=rb'Z:\\private\\replacement.dll')):
            trace = FaultTrace({1: 0x20000}); trace.feed(prefix + b'FTA_CLIENT_PROCESS 252\n')
            self.assertEqual(0, trace.values()['moduleId']); self.assertEqual(0, trace.values()['moduleRva'])

    def test_bounded_stream_survives_large_raw_output_without_exporting_it(self):
        with patch.object(StartupCapture, 'image_sizes', {1: 0x20000}), patch.object(StartupCapture, 'fixture_capture', False):
            capture = StartupCapture()
            for _ in range(80): capture.append(b'PRIVATE account/path/password ' + b'x' * 4096 + b'\n')
            capture.append(self.complete())
            with patch('sys.stdout', new_callable=io.StringIO) as output: capture.report()
            text = output.getvalue()
            self.assertNotIn('PRIVATE', text); self.assertNotIn('FTA_CLIENT_PROCESS', text); self.assertNotIn('FTA_CLIENT_EXIT', text)
            self.assertNotIn('Z:', text); self.assertIn('FTA_CLIENT_FAULT moduleRva 4660\n', text)
            self.assertIn('FTA_CLIENT_EVENT client-early-exit 3221225477\n', text)
            self.assertLessEqual(len(capture.data), 256 * 1024); self.assertLessEqual(len(capture.fault.partial), FaultTrace.MAX_LINE)
            self.assertEqual(80, capture.fault.dropped)
            capture.append(b'FTA_ORIGINAL_STARTUP_PASS\n')
            self.assertIn(b'FTA_ORIGINAL_STARTUP_PASS', capture.data.splitlines())
            self.assertLessEqual(len(capture.data), 256 * 1024)

    def test_repeated_identity_or_process_limit_sets_explicit_incomplete_flag(self):
        trace = FaultTrace({1: 0x20000})
        for pid in range(FaultTrace.MAX_PROCESSES + 1): trace.feed(self.trace(pid=f'{pid:04x}'.encode()))
        self.assertEqual(FaultTrace.MAX_PROCESSES, len(trace.processes)); self.assertTrue(trace.overflow)
        trace = FaultTrace({}); trace.feed(b'FTA_CLIENT_PROCESS 252\nFTA_CLIENT_PROCESS 252\n')
        self.assertEqual(1, trace.values()['traceStateOverflow'])

    def test_handled_exception_or_unrelated_exit_cannot_claim_code_match(self):
        trace = FaultTrace({1: 0x20000}); trace.feed(self.trace() + b'FTA_CLIENT_PROCESS 252\nFTA_CLIENT_EXIT 252 0 1000\n')
        self.assertEqual(1, trace.values()['accessViolationCount']); self.assertEqual(0, trace.values()['exceptionMatchesExit'])
        trace = FaultTrace({1: 0x20000}); trace.feed(self.trace() + b'FTA_CLIENT_PROCESS 252\nFTA_CLIENT_EXIT 251 3221225477 1000\n')
        self.assertEqual(0, trace.values()['exitObserved'])

    def test_pe_header_bounds_and_links_cannot_invent_module_sizes(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); prefix = root / 'prefix'; source = root / 'source'; source.mkdir()
            data = bytearray(4096); data[:2] = b'MZ'; struct.pack_into('<I', data, 60, 128)
            data[128:132] = b'PE\0\0'; struct.pack_into('<H', data, 132, 0x14c)
            struct.pack_into('<H', data, 148, 224); struct.pack_into('<H', data, 152, 0x10b); struct.pack_into('<I', data, 208, 0x20000)
            path = source / 'Wow.exe'; path.write_bytes(data)
            self.assertEqual({1: 0x20000}, image_sizes(source, prefix))
            (source / 'WarcraftXL.dll').symlink_to(path)
            self.assertEqual({1: 0x20000}, image_sizes(source, prefix))
            struct.pack_into('<I', data, 60, 0xfffffff0); path.write_bytes(data)
            self.assertEqual({}, image_sizes(source, prefix))

    def test_host_trace_uses_its_owned_pid_and_64_bit_module_range(self):
        data = self.trace(pid=b'00ab', path=rb'Z:\\view\\Utils\\WarcraftXLHost.exe', address=b'0000000140001234').replace(b'at 00400000:', b'at 0000000140000000:')
        data += b'FTA_CLIENT_PROCESS 252\nFTA_HOST_PROCESS 171\nFTA_HOST_EXIT 171 3221225477 2000\n'
        host = FaultTrace({1: 0x20000}, host=True); client = FaultTrace({1: 0x20000})
        for offset in range(0, len(data), 7):
            host.feed(data[offset:offset+7]); client.feed(data[offset:offset+7])
        self.assertEqual((1, 0x140000000, 0x1234, 1), tuple(host.values()[k] for k in ('moduleId', 'moduleBase', 'moduleRva', 'exceptionMatchesExit')))
        self.assertEqual(0, client.values()['accessViolationCount']); self.assertEqual(0, client.values()['exitObserved'])
        host.feed(b'FTA_CLIENT_EXIT 171 0 3000\n')
        self.assertEqual(0xc0000005, host.values()['exitCode'])

    def test_runtime_error_masks_omit_private_text_and_other_processes(self):
        capture = StartupCapture()
        capture.append(b'00fc:0100:err:module:import_dll PRIVATE DLL/path\n'
            b'00ab:0104:err:wgl:context PRIVATE renderer\n'
            b'00fc:0100:err:d3d:init PRIVATE settings\n'
            b'00cd:0108:err:opengl:loader unrelated\n'
            b'FTA_CLIENT_PROCESS 252\nFTA_HOST_PROCESS 171\n')
        with patch('sys.stdout', new_callable=io.StringIO) as output: capture.report()
        text = output.getvalue()
        self.assertIn('FTA_CLIENT_RUNTIME_ERRORS 9\n', text)
        self.assertIn('FTA_HOST_RUNTIME_ERRORS 2\n', text)
        self.assertNotIn('PRIVATE', text); self.assertNotIn('unrelated', text)
        self.assertNotIn('FTA_HOST_PROCESS', text)

    def test_host_pe_sizes_require_exact_pe64_header_and_no_symlinks(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); (root / 'Utils').mkdir()
            data = bytearray(4096); data[:2] = b'MZ'; struct.pack_into('<I', data, 60, 128)
            data[128:132] = b'PE\0\0'; struct.pack_into('<H', data, 132, 0x8664)
            struct.pack_into('<H', data, 148, 240); struct.pack_into('<H', data, 152, 0x20b)
            struct.pack_into('<I', data, 208, 0x20000)
            path = root / 'Utils/WarcraftXLHost.exe'; path.write_bytes(data)
            self.assertEqual({1: 0x20000}, image_sizes(root, root / 'absent', host=True))
            self.assertEqual({}, image_sizes(root, root / 'absent'))
            struct.pack_into('<H', data, 132, 0x14c); path.write_bytes(data)
            self.assertEqual({}, image_sizes(root, root / 'absent', host=True))


if __name__ == '__main__': unittest.main()
