"""Guard the failed deployment cleanup against removing a running container."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec=importlib.util.spec_from_file_location('reconcile',Path(__file__).resolve().parents[1]
                                        /'scripts'/'r4a_onboarding_failed_rollout_reconcile.py')
reconcile=importlib.util.module_from_spec(spec)
spec.loader.exec_module(reconcile)


class ReconcileSafetyTest(unittest.TestCase):
    def test_running_candidate_is_never_removed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/'r4a-before-staging.docker-inspect.json').write_text(json.dumps([
                {'Name':'/ouf-onboarding','Id':'original'},
                {'Name':'/ouf-onboarding-r4a-smoke','Id':'smoke'}]))
            (root/'r4a-onboarding-rollout.json').write_text(json.dumps({
                'old_id':'original','smoke_id':'smoke',
                'backup_name':reconcile.OLD,'db_dump':str(root/'backup.dump')}))
            (root/'backup.dump').write_bytes(b'private')
            containers={
                'ouf-onboarding':{'Id':'original','State':{'Running':True}},
                'ouf-onboarding-r4a-smoke':{'Id':'smoke','State':{'Running':True}},
                reconcile.CANDIDATE:{'Image':reconcile.FAILED_IMAGE_ID,
                                     'State':{'Status':'running'}},
            }
            with (patch.object(reconcile,'ROOT',root),
                  patch.object(reconcile,'STATE',root/'r4a-onboarding-rollout.json'),
                  patch.object(reconcile,'SNAPSHOT',root/'r4a-before-staging.docker-inspect.json'),
                  patch.object(reconcile,'ARCHIVE',root/'archived.json'),
                  patch.object(reconcile,'private'),
                  patch.object(reconcile.os,'geteuid',return_value=0),
                  patch.object(reconcile,'inspect',side_effect=lambda name,missing_ok=False:containers.get(name)),
                  patch.object(reconcile.subprocess,'run') as execute):
                with self.assertRaisesRegex(ValueError,'CANDIDATE_NOT_INERT'):
                    reconcile.preflight()
                execute.assert_not_called()
