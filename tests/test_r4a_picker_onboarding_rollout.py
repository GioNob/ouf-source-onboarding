import unittest
from unittest import mock
import json

from scripts import r4a_picker_onboarding_rollout as picker


class PickerRolloutTests(unittest.TestCase):
    def test_preserves_existing_ths_scopes_without_reading_other_clients(self):
        config = '''spring:
  security:
    oauth2:
      client:
        registration:
          ouf-ths:
            client-id: ouf-authorization-ths
            client-secret: ${THS_CLIENT_SECRET}
            scope: openid,authorization.policy.admin
          other:
            scope: openid,unrelated
'''
        client, scopes = picker.scopes_from_ths_config(config)
        self.assertEqual(client, 'ouf-authorization-ths')
        self.assertEqual(scopes, ['openid', 'authorization.policy.admin'])
        overlay = json.loads(picker.picker_overlay(scopes))
        self.assertEqual(overlay['spring']['security']['oauth2']['client']['registration']['ouf-ths']['scope'],
                         ['openid', 'authorization.policy.admin', picker.UPLOAD])
        self.assertEqual(overlay['ouf']['managed-file-picker']['gateway-upload-url'], picker.UPLOAD_URL)

    def test_rejects_unresolved_ths_scope(self):
        with self.assertRaises(picker.Blocked):
            picker.scopes_from_ths_config('  ouf-ths:\n    client-id: ouf-authorization-ths\n    scope: ${THS_SCOPES}\n')

    def test_restores_backup_if_new_container_was_never_created(self):
        state = {'old_id': 'old', 'backup': 'ouf-onboarding-pre-picker-test',
                 'new_id': None}
        with (mock.patch.object(picker, 'inspect_optional', side_effect=[
                    {'Id': 'old', 'State': {'Running': False}}, None]),
              mock.patch.object(picker, 'command') as command,
              mock.patch.object(picker, 'readiness') as readiness):
            picker.rollback(state)
        self.assertEqual(command.call_args_list[0].args[0],
                         ['docker', 'rename', 'ouf-onboarding-pre-picker-test', 'ouf-onboarding'])
        readiness.assert_called_once()


if __name__ == '__main__':
    unittest.main()
