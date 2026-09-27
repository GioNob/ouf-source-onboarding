import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

path = Path(__file__).with_name('r4a_chatgpt_managed_file_scopes.py')
spec = importlib.util.spec_from_file_location('chatgpt_scopes', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ChatGPTScopeTest(unittest.TestCase):
    def test_exact_client_and_optional_to_default_without_other_bindings(self):
        scopes = {name: {'id': 'id-' + str(i), 'name': name, 'protocol': 'openid-connect',
                         'attributes': {'include.in.token.scope': 'true'}}
                  for i, name in enumerate(module.SCOPES)}
        default = {'unrelated-default'}
        optional = {scopes[module.SCOPES[0]]['id'], 'unrelated-optional'}
        writes = []

        def listing(*args):
            path = args[0]
            if path == 'clients':
                return [{'id': 'client-uuid', 'clientId': module.CLIENT}]
            if path == 'client-scopes':
                return list(scopes.values())
            if path.endswith('/default-client-scopes'):
                return [{'id': value} for value in default]
            if path.endswith('/optional-client-scopes'):
                return [{'id': value} for value in optional]
            raise AssertionError(path)

        def change(base, scope_id, kind, verb):
            writes.append((scope_id, kind, verb))
            destination = default if kind == 'default' else optional
            (destination.add if verb == 'update' else destination.remove)(scope_id)

        with patch.object(module, 'listing', side_effect=listing), patch.object(module, 'change', side_effect=change):
            base, found, before = module.inspect()
            self.assertEqual(before[module.SCOPES[0]], 'OPTIONAL')
            self.assertTrue(all(before[name] == 'MISSING' for name in module.SCOPES[1:]))
            module.reconcile(base, found, before)
            self.assertEqual(set(scopes[name]['id'] for name in module.SCOPES) | {'unrelated-default'}, default)
            self.assertEqual({'unrelated-optional'}, optional)
            self.assertEqual(writes[0], (scopes[module.SCOPES[0]]['id'], 'optional', 'delete'))

if __name__ == '__main__':
    unittest.main()
