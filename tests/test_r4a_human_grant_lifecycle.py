import importlib.util
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("human", SCRIPTS / "r4a_human_grant_lifecycle.py")
human = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(human)


def test_grant_is_named_human_and_time_bounded():
    subject = "b93d8cf6-cd14-4ee6-91d7-84cd76c4f500"
    value = human.grant(subject, "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z")
    assert value["subjectId"] == subject
    assert value["servicePrincipalId"] is None
    assert value["capabilityId"] == "ouf.onboarding.configuration.write"
    with pytest.raises(ValueError, match="INVALID_SUBJECT_ID"):
        human.grant("ouf-admin", value["validFrom"], value["validUntil"])


def test_equivalent_grant_with_another_id_blocks_duplicate():
    wanted = human.grant("b93d8cf6-cd14-4ee6-91d7-84cd76c4f500",
                         "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z")
    with pytest.raises(ValueError, match="EQUIVALENT_GRANT_REQUIRES_REVIEW"):
        human.check_no_equivalent({"grants": [{**wanted, "grantId": "other"}]}, wanted)
