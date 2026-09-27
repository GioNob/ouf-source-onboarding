import base64
import importlib.util
import json
import os
from pathlib import Path
import time
import unittest
from tempfile import TemporaryDirectory

SPEC=importlib.util.spec_from_file_location('workload_token',Path(__file__).parents[1]/'scripts/r4a_verify_onboarding_workload_token.py')
module=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(module)

class WorkloadTokenTest(unittest.TestCase):
    def test_acceptance_and_missing_scope(self):
        with TemporaryDirectory() as name:
            root=Path(name)
            projection=root/'projection.json'
            projection.write_text(json.dumps({'gateway':{
                'issuerUrl':'https://iam.example/realms/test',
                'requiredAudience':'ouf-api-gateway'}}))
            now=int(time.time())
            claims={'iss':'https://iam.example/realms/test','azp':'ouf-onboarding',
                    'aud':['ouf-api-gateway'],'ouf_actor_type':'SERVICE',
                    'tenant_id':'tenant-test','scope':module.SCOPE,
                    'iat':now,'exp':now+300}
            token_file=root/'token'

            def inspect_claims():
                encoded=base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b'=').decode()
                if token_file.exists():token_file.chmod(0o600)
                token_file.write_text('header.'+encoded+'.signature')
                token_file.chmod(0o440)
                return module.inspect(token_file,projection,'tenant-test',os.getuid(),os.getgid())

            self.assertTrue(all(inspect_claims().values()))
            claims['scope']='authorization.bundle.read'
            self.assertFalse(inspect_claims()['SCOPE_PRESENT'])
            claims['scope']=module.SCOPE
            claims['exp']=now-1
            self.assertFalse(inspect_claims()['NOT_EXPIRED'])

            token_file.chmod(0o666)
            with self.assertRaisesRegex(ValueError,'TOKEN_FILE_METADATA'):
                module.inspect(token_file,projection,'tenant-test',os.getuid(),os.getgid())

if __name__=='__main__':unittest.main()
