import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import r4a_semantic_authorization_bootstrap as bootstrap


class SemanticBootstrapTest(unittest.TestCase):
    def test_adds_only_new_human_grant_with_admin_validity(self):
        template = {
            'grantId': 'admin', 'capabilityId': 'authorization.policy.admin',
            'tenantId': 'ouf-lab', 'subjectId': bootstrap.ADMIN_SUBJECT,
            'servicePrincipalId': None, 'organizationId': None,
            'validFrom': '2026-09-25T00:00:00Z', 'validUntil': '2036-09-25T00:00:00Z',
            'constraints': None,
        }
        desired = [{'grantId': 'semantic-propose-admin',
                    'capabilityId': 'ouf.semantic.propose'}]
        grants = bootstrap.desired_grants({'grants': [template]}, desired)
        self.assertEqual(grants, [{**template, **desired[0]}])
        self.assertEqual(bootstrap.desired_grants({'grants': [template, *grants]}, desired), [])
        changed = {**grants[0], 'validUntil': '2035-01-01T00:00:00Z'}
        with self.assertRaisesRegex(ValueError, 'EXISTING_GRANT_CONFLICT'):
            bootstrap.desired_grants({'grants': [template, changed]}, desired)


if __name__ == '__main__':
    unittest.main()
