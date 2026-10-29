import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import original_startup as guest
from check_original_startup import transcript, prefix_transcript


class OriginalStartupTests(unittest.TestCase):
    def good(self):
        names = ('client-started', 'host-header-valid', 'host-owned', 'startup-observed', 'client-stopped', 'host-stopped')
        return '\n'.join([f'FTA_ANDROID_STAGE {s} 0' for s in guest.STAGES] +
                         [f'FTA_CLIENT_EVENT {n} 1' for n in names] +
                         ['FTA_CLIENT_EVENT host-channels 2', 'FTA_CLIENT_STARTUP_PASS'])

    def test_complete_transcript_qualifies_only_the_observed_protocol(self):
        result = transcript(self.good(), 0)
        self.assertEqual(2, result['host-channels']); self.assertEqual(1, result['host-stopped'])

    def test_missing_duplicate_failed_and_contradictory_protocol_is_rejected(self):
        for text in (self.good().replace('FTA_CLIENT_EVENT host-owned 1', ''),
                     self.good() + '\nFTA_CLIENT_EVENT host-owned 1',
                     self.good() + '\nFTA_CLIENT_EVENT client-early-exit 0',
                     self.good() + '\nFTA_CLIENT_STOP_FAILED',
                     self.good().replace('host-channels 2', 'host-channels 17'),
                     self.good().replace('wine-stop 0', 'wine-stop 1')):
            with self.assertRaises(ValueError): transcript(text, 0)
        with self.assertRaises(ValueError): transcript(self.good(), 1)

    def test_capture_does_not_export_private_runtime_lines(self):
        capture = guest.StartupCapture()
        capture.append(b'PRIVATE path/config\nFTA_CLIENT_EVENT client-started 1\nFTA_CLIENT_EVENT unknown 99\n')
        with patch('sys.stdout', new_callable=io.StringIO) as output: capture.report()
        self.assertEqual('FTA_CLIENT_EVENT client-started 1\n', output.getvalue())

    def test_failed_startup_still_stops_wineserver_and_display(self):
        for failure in ('prefix', 'original-startup'):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory); (root / 'prefix').mkdir(); (root / 'session').mkdir()
                calls = []; child = Mock(); capture = Mock()
                def run(stage, *args, **kwargs):
                    calls.append(stage)
                    if stage == failure: raise RuntimeError('synthetic failure')
                with patch.object(guest, 'Path', side_effect=lambda p: root / p.lstrip('/')), \
                     patch('builtins.open', return_value=io.BytesIO(b'0-3')), \
                     patch.dict(guest.os.environ, {}, clear=True), \
                     patch.multiple(guest.base, STAGES=guest.base.STAGES, MARKERS=guest.base.MARKERS, OutputCapture=guest.base.OutputCapture), \
                     patch.object(guest, 'prepared'), patch.object(guest, 'PrefixCache'), \
                     patch.object(guest.base, 'socket_check', return_value=0), \
                     patch.object(guest.base, 'start_display', return_value=(child, capture)), \
                     patch.object(guest.base, 'run', side_effect=run), \
                     patch.object(guest.base, 'stop') as stop, \
                     patch.object(guest.subprocess, 'run') as server, patch('sys.stdout', new_callable=io.StringIO):
                    self.assertEqual(1, guest.main(prepare=failure == "prefix"))
                    self.assertEqual('wine-stop', calls[-1])
                    server.assert_called_once(); stop.assert_called_once_with(child); capture.close.assert_called_once()


if __name__ == '__main__': unittest.main()
