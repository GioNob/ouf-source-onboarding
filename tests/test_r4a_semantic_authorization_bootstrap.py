import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import r4a_semantic_authorization_bootstrap as bootstrap
import r4a_semantic_authorization_verify_db as verifier
import r4a_semantic_human_scopes as human_scopes


class SemanticBootstrapTest(unittest.TestCase):
    def test_discovery_descriptors_and_grants_match_the_mcp_contract(self):
        discovery = 'ouf.semantic.discovery'
        operations = {discovery: 'COMMAND', discovery + '.status': 'READ',
                      discovery + '.candidates': 'READ'}
        rows = [row for row in verifier.EXPECTED_CAPS
                if row['descriptor']['capabilityId'].startswith(discovery)]
        self.assertCountEqual(rows, [
            {'ownerRef': 'semantic', 'descriptor': {'capabilityId': cap, 'operation': op,
             'requiredScope': discovery, 'allowedActors': ['HUMAN']}}
            for cap, op in operations.items()])
        self.assertCountEqual(
            [row for row in verifier.EXPECTED_GRANTS if row['capabilityId'].startswith(discovery)],
            [{'grantId': 'grant-' + cap.removeprefix('ouf.').replace('.', '-') + '-human-admin',
              'capabilityId': cap} for cap in operations])
        self.assertCountEqual(human_scopes.SCOPES,
                              {row['descriptor']['requiredScope'] for row in verifier.EXPECTED_CAPS})

    def test_structural_verifier_accepts_owner_null_normalization(self):
        admin = {
            'grantId': 'admin', 'capabilityId': 'authorization.policy.admin',
            'tenantId': 'ouf-lab', 'subjectId': bootstrap.ADMIN_SUBJECT,
            'servicePrincipalId': None, 'organizationId': None,
            'validFrom': '2026-09-25T00:00:00Z', 'validUntil': '2036-09-25T00:00:00Z',
        }
        old = {'bundleId': 'ouf-lab-authorization', 'version': 27,
               'capabilities': [{'capabilityId': 'authorization.policy.admin',
                                 'operation': 'COMMAND', 'requiredScope': 'authorization.policy.admin',
                                 'allowedActors': ['HUMAN']}], 'grants': [admin]}
        active = {**old, 'version': 28,
                  'capabilities': [*old['capabilities'],
                                   *(row['descriptor'] for row in verifier.EXPECTED_CAPS)],
                  'grants': [*old['grants'], *({**admin, **row} for row in verifier.EXPECTED_GRANTS)]}
        state = {'catalogueHash': bootstrap.policy.digest([verifier.EXPECTED_CAPS, verifier.EXPECTED_GRANTS]),
                 'basePolicyRef': 'ouf-lab-authorization:27',
                 'targetPolicyRef': 'ouf-lab-authorization:28',
                 'baselineCapabilitiesHash': bootstrap.policy.digest(old['capabilities']),
                 'baselineGrantsHash': bootstrap.policy.digest(old['grants']),
                 'addedCapabilityIds': [x['descriptor']['capabilityId'] for x in verifier.EXPECTED_CAPS],
                 'addedGrantIds': [x['grantId'] for x in verifier.EXPECTED_GRANTS]}
        self.assertEqual(verifier.verify(old, active, state), (1, 11, 1, 11))

        legacy = {**active,
                  'capabilities': [row for row in active['capabilities']
                                   if row['requiredScope'] != 'ouf.semantic.discovery'],
                  'grants': [row for row in active['grants']
                             if not row['capabilityId'].startswith('ouf.semantic.discovery')]}
        original_state = {key: value for key, value in state.items() if key != 'catalogueHash'}
        original_state.update(addedCapabilityIds=[row['capabilityId'] for row in legacy['capabilities'][1:]],
                              addedGrantIds=[row['grantId'] for row in legacy['grants'][1:]])
        self.assertEqual(verifier.verify(old, legacy, original_state), (1, 8, 1, 8))

        upgraded = {**active, 'version': 29}
        upgrade = {
            'catalogueHash': state['catalogueHash'],
            'basePolicyRef': state['targetPolicyRef'],
            'targetPolicyRef': active['bundleId'] + ':29',
            'baselineCapabilitiesHash': bootstrap.policy.digest(legacy['capabilities']),
            'baselineGrantsHash': bootstrap.policy.digest(legacy['grants']),
            'addedCapabilityIds': [row['capabilityId'] for row in active['capabilities']
                                   if row['requiredScope'] == 'ouf.semantic.discovery'],
            'addedGrantIds': [row['grantId'] for row in active['grants']
                              if row['capabilityId'].startswith('ouf.semantic.discovery')],
        }
        self.assertEqual(verifier.verify(legacy, upgraded, upgrade), (8, 11, 8, 11))
        with self.assertRaisesRegex(ValueError, 'STATE_MANIFEST_MISMATCH'):
            verifier.verify(legacy, upgraded, {**upgrade, 'addedGrantIds': []})
        with self.assertRaisesRegex(ValueError, 'STATE_MANIFEST_MISMATCH'):
            verifier.verify(legacy, upgraded, {**upgrade, 'catalogueHash': 'wrong'})
        active['grants'][1]['subjectId'] = 'other'
        with self.assertRaisesRegex(ValueError, 'SEMANTIC_GRANT_MISMATCH'):
            verifier.verify(old, active, state)

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
        self.assertEqual(bootstrap.desired_grants(
            {'grants': [template, {k: v for k, v in grants[0].items() if v is not None}]}, desired), [])
        changed = {**grants[0], 'validUntil': '2035-01-01T00:00:00Z'}
        with self.assertRaisesRegex(ValueError, 'EXISTING_GRANT_CONFLICT'):
            bootstrap.desired_grants({'grants': [template, changed]}, desired)
        with self.assertRaisesRegex(ValueError, 'ADMIN_GRANT_NOT_UNCONSTRAINED'):
            bootstrap.desired_grants({'grants': [{**template, 'constraints': {'role': 'superadmin'}}]}, desired)


if __name__ == '__main__':
    unittest.main()
