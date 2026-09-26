from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / 'scripts'))
import r4a_admin_human_scope_bindings as scopes


class AdminScopeBindingsTest(unittest.TestCase):
    def test_exact_client_all_scopes_and_one_missing_binding(self):
        rows = [{'id': 'scope-' + str(index), 'name': name}
                for index, name in enumerate(scopes.SCOPES)]
        def get(_, endpoint, *args):
            if endpoint == 'clients':
                return [{'clientId': scopes.CLIENT, 'id': 'client-id'}]
            if endpoint == 'client-scopes':
                return rows + [{'id': 'workload-only', 'name': 'ouf.internal.object-storage.read'}]
            if endpoint.endswith('default-client-scopes'):
                return [rows[0]]
            if endpoint.endswith('optional-client-scopes'):
                return rows[1:-1]
            raise AssertionError(endpoint)
        with patch.object(scopes, 'get', side_effect=get):
            client, ids, absent, unbound, bound = scopes.audit('ouf-keycloak')
        self.assertEqual(client, 'client-id')
        self.assertEqual(absent, [])
        self.assertEqual(unbound, [scopes.SCOPES[-1]])
        self.assertEqual(len(bound), len(scopes.SCOPES) - 1)
        self.assertNotIn('ouf.internal.object-storage.read', ids)


if __name__ == '__main__':
    unittest.main()
