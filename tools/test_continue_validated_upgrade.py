import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

import continue_validated_upgrade as upgrade


class ContinuationTest(unittest.TestCase):
    def setUp(self):
        self.folder=tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root=Path(self.folder.name)
        self.argv=['continue_validated_upgrade.py','--old',str(self.root/'old'),
            '--new',str(self.root),'--snapshot',str(self.root/'snapshot.json'),
            '--validation',str(self.root/'validation.json'),'--identity-helper',str(self.root/'helper'),
            '--java','java']

    def test_failed_import_never_starts_supervisor(self):
        with patch.object(sys,'argv',self.argv),patch.object(upgrade.subprocess,'run',side_effect=subprocess.CalledProcessError(1,['audit'])),patch.object(upgrade.subprocess,'Popen') as start:
            with self.assertRaises(subprocess.CalledProcessError):upgrade.main()
            start.assert_not_called()
        self.assertEqual(json.loads((self.root/'upgrade-status.json').read_text())['stage'],'upgrade-stopped-inspect-logs')

    def test_failed_identity_preflight_never_starts_supervisor(self):
        with patch.object(sys,'argv',self.argv),patch.object(upgrade.subprocess,'run',side_effect=[None,subprocess.CalledProcessError(1,['preflight'])]),patch.object(upgrade.subprocess,'Popen') as start:
            with self.assertRaises(subprocess.CalledProcessError):upgrade.main()
            start.assert_not_called()

    def test_successful_audit_and_preflight_start_supervisor(self):
        child=Mock(pid=123);child.wait.return_value=0
        with patch.object(sys,'argv',self.argv),patch.object(upgrade.subprocess,'run') as run,patch.object(upgrade.subprocess,'Popen',return_value=child) as start:
            upgrade.main()
            self.assertEqual(run.call_count,2)
            self.assertIn('--low-memory-priority',run.call_args_list[0].args[0])
            self.assertIn('--check-only',run.call_args_list[1].args[0])
            self.assertEqual(start.call_count,1)
        self.assertEqual((self.root/'runner.pid').read_text(),'123')
        self.assertEqual(json.loads((self.root/'upgrade-status.json').read_text())['stage'],'planet-supervisor-finished')

    def test_retry_preserves_previous_monitor_log(self):
        (self.root/'import-monitor').mkdir()
        child=Mock(pid=456);child.wait.return_value=0
        with patch.object(sys,'argv',self.argv),patch.object(upgrade.subprocess,'run') as run,patch.object(upgrade.subprocess,'Popen',return_value=child):
            upgrade.main()
            command=run.call_args_list[0].args[0]
            self.assertEqual(command[command.index('--out')+1],str(self.root/'import-monitor-0002'))


if __name__=='__main__':unittest.main()
