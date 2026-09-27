import unittest
from unittest import mock
import json
from pathlib import Path
import tempfile

from scripts import r4a_picker_onboarding_rollout as picker


class PickerRolloutTests(unittest.TestCase):
    def test_loads_backup_helper_from_exact_pinned_commit(self):
        commit = 'a' * 40
        with mock.patch.object(picker, 'command', return_value=b'VALUE = 31\n') as command:
            module = picker.load_database(Path.cwd(), commit)
        self.assertEqual(module.VALUE, 31)
        self.assertIn(commit + ':scripts/r4a_onboarding_db_backup.py', command.call_args.args[0])

    def test_scope_helper_and_import_come_from_pinned_commit(self):
        revision = 'b' * 40
        helper = b'from r4a_keycloak_client_scope_catalogue import MARKER\nassert MARKER == 42\n'
        catalogue = b'MARKER = 42\n'
        with tempfile.TemporaryDirectory() as folder:
            with (mock.patch.object(picker, 'ROOT', Path(folder)),
                  mock.patch.object(picker, 'command', side_effect=[helper, catalogue]) as command):
                picker.keycloak_scope(Path.cwd(), revision, 'ouf-authorization-ths', 'plan')
            self.assertEqual(list(Path(folder).iterdir()), [])
        self.assertEqual([call.args[0][-1] for call in command.call_args_list], [
            revision + ':scripts/r4a_keycloak_client_scope_binding.py',
            revision + ':scripts/r4a_keycloak_client_scope_catalogue.py'])

    def test_scope_helper_surfaces_keycloak_session_error(self):
        revision = 'b' * 40
        helper = b'raise SystemExit("CLIENT_SCOPE_BINDING_BLOCKED=KCADM_SESSION_EXPIRED")\n'
        with tempfile.TemporaryDirectory() as folder:
            with (mock.patch.object(picker, 'ROOT', Path(folder)),
                  mock.patch.object(picker, 'command', side_effect=[helper, b''])):
                with self.assertRaisesRegex(picker.Blocked, 'THS_CLIENT_SCOPE_KCADM_SESSION_EXPIRED'):
                    picker.keycloak_scope(Path.cwd(), revision, 'ouf-authorization-ths', 'plan')
            self.assertEqual(list(Path(folder).iterdir()), [])

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

    def test_accepts_json_form_of_ths_yaml_without_exposing_client_secret(self):
        raw = json.dumps({'spring': {'security': {'oauth2': {'client': {'registration': {
            'ouf-ths': {'client-id': 'ouf-authorization-ths',
                        'client-secret': 'private', 'scope': ['openid', 'authorization.policy.admin']}
        }}}}}})
        self.assertEqual(picker.scopes_from_ths_config(raw),
                         ('ouf-authorization-ths', ['openid', 'authorization.policy.admin']))

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
