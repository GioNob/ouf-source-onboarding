import importlib.util
from datetime import datetime, timezone
import pathlib
import tempfile
import unittest

PATH=pathlib.Path(__file__).parents[1]/"scripts"/"r4a_temporary_grant_proposal.py"
spec=importlib.util.spec_from_file_location("temporary_grant",PATH)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class TemporaryGrantProposalTest(unittest.TestCase):
    def test_builds_single_bounded_allow_grant(self):
        start=datetime(2026,9,24,10,0,tzinfo=timezone.utc)
        end=datetime(2026,9,24,11,0,tzinfo=timezone.utc)
        change=m.build_change(
            "subject-a","urban.object.search","ouf-lab","r4a-search-subject-a",
            start,end,"R4a bounded search acceptance","capability"
        )
        self.assertEqual(change["operation"],"UPSERT")
        self.assertEqual(change["grantId"],"r4a-search-subject-a")
        self.assertEqual(change["grant"]["capabilityId"],"urban.object.search")
        self.assertEqual(change["grant"]["subjectId"],"subject-a")
        self.assertEqual(change["grant"]["constraints"]["effect"],"ALLOW")
        self.assertEqual(change["grant"]["constraints"]["resourceType"],"capability")
        self.assertIsNone(change["grant"]["constraints"]["resourceId"])

    def test_active_equivalent_blocks_duplicate(self):
        now=datetime(2026,9,24,10,30,tzinfo=timezone.utc)
        grants=[{
            "grantId":"g","capabilityId":"urban.object.search","tenantId":"ouf-lab",
            "subjectId":"subject-a","validFrom":"2026-09-24T10:00:00Z",
            "validUntil":"2026-09-24T11:00:00Z",
            "constraints":{"effect":"ALLOW","resourceType":"capability","resourceId":None,
                           "resourceAttributes":{}}
        }]
        got=m.active_equivalent(
            grants,"subject-a","urban.object.search","ouf-lab","capability",now
        )
        self.assertEqual([x["grantId"] for x in got],["g"])

    def test_expired_equivalent_does_not_block(self):
        now=datetime(2026,9,24,10,30,tzinfo=timezone.utc)
        grants=[{
            "grantId":"old","capabilityId":"urban.object.search","tenantId":"ouf-lab",
            "subjectId":"subject-a","validFrom":"2026-09-23T10:00:00Z",
            "validUntil":"2026-09-23T11:00:00Z",
            "constraints":{"effect":"ALLOW","resourceType":"capability","resourceId":None,
                           "resourceAttributes":{}}
        }]
        self.assertEqual(
            m.active_equivalent(
                grants,"subject-a","urban.object.search","ouf-lab","capability",now
            ),[]
        )

    def test_incomplete_access_page_is_blocked(self):
        with self.assertRaisesRegex(ValueError,"ACCESS_PAGINATION_INCOMPLETE"):
            m.validate_access(
                {"grants":[],"nextAfter":"cursor","policyRef":"bundle:15"},"subject-a"
            )

    def test_subject_mismatch_is_blocked(self):
        with self.assertRaisesRegex(ValueError,"ACCESS_SUBJECT_MISMATCH"):
            m.validate_access(
                {"grants":[{"subjectId":"other"}],"nextAfter":None},"subject-a"
            )

    def test_private_output_is_0600(self):
        with tempfile.TemporaryDirectory() as td:
            path=pathlib.Path(td)/"request.json"
            m.write_private_json(path,{"x":1})
            self.assertEqual(path.stat().st_mode & 0o777,0o600)


if __name__=="__main__":
    unittest.main()
