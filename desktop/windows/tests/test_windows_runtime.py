"""Windows-only smoke tests: no microphone access or system mutation."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


@unittest.skipUnless(sys.platform == 'win32', 'Requires the Windows adapter')
class WindowsRuntimeTests(unittest.TestCase):
    def test_static_powershell_utf8_transport(self):
        from ema_windows.windows import powershell
        self.assertEqual({'value': 'EMA'}, json.loads(powershell("@{value='EMA'} | ConvertTo-Json -Compress")))

    def test_cancelled_subprocess_never_starts(self):
        from ema_windows.windows import run_process, Cancelled
        with self.assertRaises(Cancelled):
            run_process(['this-file-must-not-exist.exe'], alive=lambda: False)

    def test_uia_worker_process_can_initialize(self):
        from ema_windows.windows import run_process
        worker = Path(__file__).resolve().parents[1] / 'worker_launcher.py'
        self.assertIn('PASS:', run_process([sys.executable, str(worker), '--self-test']))
