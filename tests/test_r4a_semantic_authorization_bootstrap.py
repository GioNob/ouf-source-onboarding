import sys
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import r4a_semantic_authorization_bootstrap as bootstrap
import r4a_semantic_authorization_verify_db as verifier
import r4a_semantic_human_scopes as human_scopes


# Fingerprint of the eleven-entry manifest shipped with 52d.
CATALOGUE_HASH_52D = 'e6aa14b72a22b18966cb57af4f6da75279feb8069e1efbccba1cf48af2a57083'


class SemanticBootstrapTest(unittest.TestCase):
    def test_plan_accepts_twelve_matching_descriptors_and_grants(self):
        self.assertEqual(len(verifier.EXPECTED_CAPS), 12)
        self.assertEqual(len(verifier.EXPECTED_GRANTS), 12)
        with patch.object(sys, 'argv', ['bootstrap', 'plan']), \
                patch.object(bootstrap, 'actor_token', return_value='fixture'), \
                patch.object(bootstrap.policy, 'active', return_value={'policyRef': 'fixture:1', 'policy': {}}), \
                patch.object(bootstrap.policy, 'build_candidate', return_value=({}, [])), \
                patch.object(bootstrap, 'desired_grants', return_value=[]) as desired, \
                patch.object(bootstrap, 'catalogue', return_value=[]) as catalogue:
            bootstrap.main()
        catalogue.assert_called_once_with('fixture', verifier.EXPECTED_CAPS, False)
        desired.assert_called_once_with({}, verifier.EXPECTED_GRANTS)

    def test_batch_search_reuses_the_search_scope_and_has_a_matching_admin_grant(self):
        batch = 'ouf.semantic.search.batch'
        search, = (row for row in verifier.EXPECTED_CAPS
                   if row['descriptor']['capabilityId'] == 'ouf.semantic.search')
        self.assertEqual(verifier.EXPECTED_CAPS[-1],
                         {**search, 'descriptor': {**search['descriptor'], 'capabilityId': batch}})
        self.assertEqual(verifier.EXPECTED_GRANTS[-1],
                         {'grantId': 'grant-semantic-search-batch-human-admin', 'capabilityId': batch})

    def test_discovery_descriptors_and_grants_match_the_mcp_contract(self):
        discovery = 'ouf.semantic.discovery'
        operations = {discovery: 'COMMAND', discovery + '.status': 'READ',
                      discovery + '.candidates': 'READ', discovery + '.adopt': 'COMMAND'}
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

    def current_bootstrap_fixture(self):
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
        return old, active, state

    def test_structural_verifier_rejects_eleven_entry_hash_for_twelve_entry_state(self):
        old, active, state = self.current_bootstrap_fixture()
        with self.assertRaisesRegex(ValueError, '^STATE_MANIFEST_MISMATCH$'):
            verifier.verify(old, active, {**state, 'catalogueHash': CATALOGUE_HASH_52D})

    def test_structural_verifier_accepts_owner_null_normalization(self):
        old, active, state = self.current_bootstrap_fixture()
        self.assertEqual(verifier.verify(old, active, state), (1, 13, 1, 13))

        eleven = {**active, 'capabilities': active['capabilities'][:-1], 'grants': active['grants'][:-1]}
        batch_delta = {**state, 'basePolicyRef': state['targetPolicyRef'], 'targetPolicyRef': 'ouf-lab-authorization:29',
                       'baselineCapabilitiesHash': bootstrap.policy.digest(eleven['capabilities']),
                       'baselineGrantsHash': bootstrap.policy.digest(eleven['grants']),
                       'addedCapabilityIds': ['ouf.semantic.search.batch'],
                       'addedGrantIds': ['grant-semantic-search-batch-human-admin']}
        self.assertEqual(verifier.verify(eleven, {**active, 'version': 29}, batch_delta), (12, 13, 12, 13))

        legacy = {**eleven,
                  'capabilities': [row for row in eleven['capabilities']
                                   if row['requiredScope'] != 'ouf.semantic.discovery'],
                  'grants': [row for row in eleven['grants']
                             if not row['capabilityId'].startswith('ouf.semantic.discovery')]}
        original_state = {key: value for key, value in state.items() if key != 'catalogueHash'}
        original_state.update(addedCapabilityIds=[row['capabilityId'] for row in legacy['capabilities'][1:]],
                              addedGrantIds=[row['grantId'] for row in legacy['grants'][1:]])
        self.assertEqual(verifier.verify(old, legacy, original_state), (1, 8, 1, 8))

        ten_caps = verifier.EXPECTED_CAPS[:10]
        ten_grants = verifier.EXPECTED_GRANTS[:10]
        ten = {**active, 'capabilities': active['capabilities'][:11], 'grants': active['grants'][:11]}
        # Recorded from the release manifest before adoption. Keep its fingerprint independent of this checkout.
        ten_state = {**state, 'catalogueHash': '21d7e5f0a139003e83a8c30d1029554bd7978f8eb67075c652efee1521f4c043',
                     'addedCapabilityIds': [row['descriptor']['capabilityId'] for row in ten_caps],
                     'addedGrantIds': [row['grantId'] for row in ten_grants]}
        self.assertEqual(verifier.verify(old, ten, ten_state), (1, 11, 1, 11))

        upgraded = {**eleven, 'version': 29}
        upgrade = {
            'catalogueHash': CATALOGUE_HASH_52D,
            'basePolicyRef': state['targetPolicyRef'],
            'targetPolicyRef': active['bundleId'] + ':29',
            'baselineCapabilitiesHash': bootstrap.policy.digest(legacy['capabilities']),
            'baselineGrantsHash': bootstrap.policy.digest(legacy['grants']),
            'addedCapabilityIds': [row['capabilityId'] for row in eleven['capabilities']
                                   if row['requiredScope'] == 'ouf.semantic.discovery'],
            'addedGrantIds': [row['grantId'] for row in eleven['grants']
                              if row['capabilityId'].startswith('ouf.semantic.discovery')],
        }
        self.assertEqual(verifier.verify(legacy, upgraded, upgrade), (8, 12, 8, 12))
        adoption_delta = {**upgrade, 'addedCapabilityIds': ['ouf.semantic.discovery.adopt'],
                          'addedGrantIds': ['grant-semantic-discovery-adopt-human-admin'],
                          'baselineCapabilitiesHash': bootstrap.policy.digest(ten['capabilities']),
                          'baselineGrantsHash': bootstrap.policy.digest(ten['grants'])}
        self.assertEqual(verifier.verify(ten, upgraded, adoption_delta), (11, 12, 11, 12))
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
