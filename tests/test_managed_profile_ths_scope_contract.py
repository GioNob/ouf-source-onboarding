"""Canonical OAuth request dependencies of the existing native HUMAN recovery path."""
import json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class ThsScopeContractTests(unittest.TestCase):
 def setUp(self):
  self.contract=json.loads((ROOT/'catalogue/managed-profile-ths-oauth-request-v1.json').read_text())
 def test_role_and_registered_descriptor_scopes_close_exact_recovery_contract(self):
  role=json.loads((ROOT/'src/main/resources/authorization/managed-profile-recovery-role-v1.json').read_text())
  entries=[]
  for name in ('r4a-managed-file-intake.json','r4a-managed-profile-recovery.json'):entries+=json.loads((ROOT/'catalogue'/name).read_text())
  descriptors={r['descriptor']['capabilityId']:r['descriptor'] for r in entries}
  required=[descriptors[p['capabilityId']]['requiredScope'] for p in role['permissions']]
  self.assertEqual(self.contract['requiredScopes'],required)
  self.assertEqual(len(required),3)
 def test_native_client_and_existing_card_write_capabilities_match_scope_contract(self):
  source=(ROOT/'src/main/java/it/comune/trieste/ouf/onboarding/api/ManagedProfileRecoveryThs.java').read_text()
  self.assertIn('"'+self.contract['clientRegistrationId']+'".equals(c.getClientRegistration().getRegistrationId())',source)
  api=(ROOT/'src/main/java/it/comune/trieste/ouf/onboarding/api/ManagedProfileRecoveryApi.java').read_text()
  self.assertIn('"ouf.managed-source.file.profile"',api);self.assertIn('"ouf.onboarding.quarantine.release"',api)
  self.assertIn('ouf.managed-source.preview',self.contract['requiredScopes'])
 def test_additive_request_is_explicitly_separate_from_binding_permission_and_execution(self):
  self.assertEqual(self.contract['scopeRequestProperty'],'spring.security.oauth2.client.registration.ouf-ths.scope')
  self.assertEqual(self.contract['mergeMode'],'ADD_MISSING_PRESERVE_ALL_EXISTING')
  self.assertTrue(self.contract['preserveOtherProperties'])
  for name in ('configuresIamBindings','grantsPermissions','grantsProfilingBudget','resumesJobs'):self.assertFalse(self.contract[name])
if __name__=='__main__':unittest.main()
