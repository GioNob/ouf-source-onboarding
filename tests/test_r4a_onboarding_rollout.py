"""Safety checks for the container rollout; never invokes Docker."""
from __future__ import annotations

import importlib.util
import io
from pathlib import Path
from contextlib import redirect_stderr
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    'rollout', Path(__file__).resolve().parents[1] / 'scripts' / 'r4a_onboarding_rollout.py')
rollout = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rollout)


class RolloutSafetyTest(unittest.TestCase):
    def test_candidate_configuration_fails_closed_before_any_create(self):
        candidate = {'Mounts': [{'Source': '/private', 'Destination': '/run/private', 'RW': False}],
                     'Config': {'Env': ['A=foo=bar', 'A=second']}}
        with self.assertRaisesRegex(ValueError, 'CANDIDATE_ENV_UNSAFE'):
            rollout.environment(candidate)

    def test_readiness_failure_restores_original_and_smoke(self):
        old = {'Id': 'old-id'}
        smoke = {'Id': 'smoke-id'}
        candidate = {'Id': 'candidate-id'}
        operations = []
        errors = io.StringIO()

        def record(*args):
            operations.append(args)

        with (patch('sys.argv', ['rollout', 'apply', '--db-dump', '/private/dump']),
              patch.object(rollout, 'preflight', return_value=(old, smoke, candidate, 'sha256:test')),
              patch.object(rollout, 'write_state'),
              patch.object(rollout, 'docker', side_effect=record),
              patch.object(rollout, 'create_from'),
              patch.object(rollout, 'readiness', side_effect=ValueError('READINESS_TIMEOUT')),
              patch.object(rollout, 'restore_old') as restore,
              redirect_stderr(errors)):
            with self.assertRaisesRegex(ValueError, 'READINESS_TIMEOUT'):
                rollout.main()
        restore.assert_called_once_with('old-id', 'smoke-id')
        self.assertEqual(operations[:4], [
            ('stop', rollout.SMOKE), ('update', '--restart', 'no', rollout.LIVE),
            ('stop', rollout.LIVE), ('rename', rollout.LIVE, rollout.OLD),
        ])
        self.assertIn('AUTO_CONTAINER_ROLLBACK=PASS', errors.getvalue())
