import importlib.util
import pathlib
import unittest

PATH=pathlib.Path(__file__).parents[1]/"scripts"/"r4a_authorization_lifecycle.py"
spec=importlib.util.spec_from_file_location("lifecycle",PATH)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class LifecycleTest(unittest.TestCase):
    def active(self):
        return {
            "policyRef":"bundle:14","contentHash":"abc",
            "policy":{
                "bundleId":"bundle","version":14,"publishedAt":"2026-09-24T00:00:00Z",
                "capabilities":[{"capabilityId":"a","operation":"READ","requiredScope":"a","allowedActors":["HUMAN"]}],
                "grants":[{"grantId":"g","capabilityId":"a","tenantId":"t"}],
            },
        }

    def test_candidate_only_adds_missing_and_preserves_grants(self):
        desired=[
            {"capabilityId":"a","operation":"READ","requiredScope":"a","allowedActors":["HUMAN"]},
            {"capabilityId":"b","operation":"SEARCH","requiredScope":"b","allowedActors":["HUMAN"]},
        ]
        candidate,missing=m.build_candidate(self.active(),desired,"2026-09-24T01:00:00Z")
        self.assertEqual(candidate["version"],15)
        self.assertEqual([x["capabilityId"] for x in missing],["b"])
        self.assertIs(candidate["grants"],self.active()["policy"]["grants"]) if False else self.assertEqual(candidate["grants"],self.active()["policy"]["grants"])
        self.assertEqual([x["capabilityId"] for x in candidate["capabilities"]],["a","b"])

    def test_semantic_conflict_is_blocked(self):
        with self.assertRaisesRegex(ValueError,"ACTIVE_SEMANTIC_CONFLICT=a"):
            m.build_candidate(self.active(),[{"capabilityId":"a","operation":"WRITE","requiredScope":"a","allowedActors":["HUMAN"]}])

    def test_preview_refuses_grant_or_removal_drift(self):
        added={"capabilityId":"b","operation":"SEARCH","requiredScope":"b","allowedActors":["HUMAN"]}
        self.assertTrue(m.validate_preview({"addedCapabilities":[added],"removedCapabilities":[],"grantChanges":[]},[added]))
        with self.assertRaisesRegex(ValueError,"PREVIEW_CHANGES_GRANTS"):
            m.validate_preview({"addedCapabilities":[added],"removedCapabilities":[],"grantChanges":[{"grantId":"g"}]},[added])

    def test_verify_requires_exact_target_and_unchanged_grants(self):
        active=self.active();candidate,_=m.build_candidate(active,[{"capabilityId":"b","operation":"SEARCH","requiredScope":"b","allowedActors":["HUMAN"]}])
        state={"bundleId":"bundle","targetVersion":15,"targetCapabilityIds":["a","b"],"baselineGrantsHash":m.digest(active["policy"]["grants"])}
        final={"policyRef":"bundle:15","policy":candidate}
        self.assertTrue(m.verify_active(final,state))
        final["policy"]["grants"].append({"grantId":"x","capabilityId":"a","tenantId":"t"})
        with self.assertRaisesRegex(ValueError,"ACTIVE_GRANTS_CHANGED"):
            m.verify_active(final,state)


if __name__=="__main__":
    unittest.main()
