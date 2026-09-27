import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / 'scripts'))
import r4a_stage_deploy_snapshot as snapshot


class SnapshotTest(unittest.TestCase):
    def test_apply_verify_and_refuse_changed_container(self):
        with TemporaryDirectory() as root:
            base = Path(root)
            directory = base / 'snapshots'
            target = directory / 'inspect.json'
            payload = json.dumps([{'Name': '/' + name, 'Config': {'Env': ['SECRET=never-print']}}
                                  for name in snapshot.NAMES]).encode()
            with patch.object(snapshot.os, 'geteuid', return_value=0), \
                    patch.object(snapshot, 'inspect_containers', return_value=payload):
                plan = snapshot.execute('plan', base, directory, target, os.getuid())
                self.assertEqual(plan['SNAPSHOT_EXISTS'], 'false')
                applied = snapshot.execute('apply', base, directory, target, os.getuid())
                self.assertEqual(applied['SNAPSHOT_MATCHES_CURRENT'], 'true')
                self.assertNotIn('never-print', str(applied))
                self.assertEqual(snapshot.execute('verify', base, directory, target, os.getuid())['SNAPSHOT_EXISTS'], 'true')
            with patch.object(snapshot.os, 'geteuid', return_value=0), \
                    patch.object(snapshot, 'inspect_containers', return_value=payload + b' '):
                with self.assertRaisesRegex(ValueError, 'SNAPSHOT_DIFFERS_FROM_CURRENT'):
                    snapshot.execute('apply', base, directory, target, os.getuid())


if __name__ == '__main__':
    unittest.main()
