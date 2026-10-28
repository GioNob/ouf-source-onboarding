"""No live network or policy changes: required HUMAN control capabilities have canonical manifests."""
import contextlib, importlib.util, io, json, sys, unittest
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
class ManagedProfileCatalogueTest(unittest.TestCase):
 def test_role_capability_closure_uses_existing_intake_and_explicit_recovery_manifest(self):
  role=json.loads((ROOT/'src/main/resources/authorization/managed-profile-recovery-role-v1.json').read_text())
  entries=[]
  for name in ('r4a-managed-file-intake.json','r4a-managed-profile-recovery.json'):
   entries+=json.loads((ROOT/'catalogue'/name).read_text())
  byid={x['descriptor']['capabilityId']:x for x in entries}
  for permission in role['permissions']:
   cap=permission['capabilityId'];self.assertIn(cap,byid)
   row=byid[cap];self.assertEqual(row['ownerRef'],'onboarding')
   self.assertEqual(row['descriptor']['requiredScope'],cap)
   self.assertEqual(row['descriptor']['allowedActors'],['HUMAN'])
 def test_recovery_manifest_only_declares_exact_command_and_grants_nothing(self):
  rows=json.loads((ROOT/'catalogue/r4a-managed-profile-recovery.json').read_text())
  self.assertEqual(rows,[{'ownerRef':'onboarding','descriptor':{'capabilityId':'ouf.onboarding.quarantine.release','operation':'COMMAND','requiredScope':'ouf.onboarding.quarantine.release','allowedActors':['HUMAN']}}])
  for row in rows:self.assertEqual(set(row),{'ownerRef','descriptor'})
 def test_existing_reconciler_accepts_manifest_locally_without_network(self):
  spec=importlib.util.spec_from_file_location('register_manifest_validation',ROOT/'scripts/r4a_register_capabilities.py')
  m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
  with patch.object(sys,'argv',['register','--manifest',str(ROOT/'catalogue/r4a-managed-profile-recovery.json')]),patch('urllib.request.urlopen',side_effect=AssertionError('network forbidden'))as request,contextlib.redirect_stdout(io.StringIO())as out:m.main()
  request.assert_not_called();self.assertIn('NO_NETWORK_OR_WRITES=true',out.getvalue())
 def test_recovery_writes_and_template_require_same_explicit_capability(self):
  source=(ROOT/'src/main/java/it/comune/trieste/ouf/onboarding/api/ManagedProfileRecoveryApi.java').read_text()
  self.assertEqual(source.count('actors.requireHuman(r,"ouf.onboarding.quarantine.release")'),3)
  for name in ('budget','resume','cancel'):self.assertIn(name,source.lower())
if __name__=='__main__':unittest.main()
