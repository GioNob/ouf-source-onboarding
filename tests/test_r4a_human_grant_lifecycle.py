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


def test_upload_grant_is_distinct_and_add_only():
    subject = "b93d8cf6-cd14-4ee6-91d7-84cd76c4f500"
    wanted = human.grant(subject, "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z",
                         "grant-managed-file-upload-human-admin", "ouf.managed-source.file.upload")
    assert wanted["capabilityId"] == "ouf.managed-source.file.upload"
    assert wanted["grantId"] == "grant-managed-file-upload-human-admin"
    assert wanted["subjectId"] == subject
    assert wanted["servicePrincipalId"] is None
    with pytest.raises(ValueError, match="INVALID_GRANT_OR_CAPABILITY"):
        human.grant(subject, wanted["validFrom"], wanted["validUntil"], "bad/id", wanted["capabilityId"])


def test_managed_file_manifest_adds_four_distinct_human_grants():
    rows = human.manifest(Path(__file__).parents[1] / "catalogue/r4a-managed-file-human-grants.json",
                          "b93d8cf6-cd14-4ee6-91d7-84cd76c4f500",
                          "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z")
    assert len(rows) == 4
    assert {g["capabilityId"] for g in rows} == {
        "ouf.managed-source.file.upload", "ouf.managed-source.file.profile",
        "ouf.managed-source.preview", "ouf.managed-source.onboarding.create"}
    assert all(g["servicePrincipalId"] is None and g["subjectId"] == rows[0]["subjectId"] for g in rows)


def test_preview_checks_each_added_human_grant(monkeypatch):
    rows = human.manifest(Path(__file__).parents[1] / "catalogue/r4a-managed-file-human-grants.json",
                          "b93d8cf6-cd14-4ee6-91d7-84cd76c4f500",
                          "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z")
    state = {"addedGrantIds": [g["grantId"] for g in rows],
             "desiredGrants": {g["grantId"]: g for g in rows}}
    changed = [{"grantId": g["grantId"], "after": g, "before": None} for g in rows]
    changed[-1]["after"] = {**rows[-1], "subjectId": "wrong"}
    monkeypatch.setattr(human.lifecycle, "preview", lambda *args: {"grantChanges": changed})
    with pytest.raises(ValueError, match="PREVIEW_GRANT_CONTENT_MISMATCH"):
        human.review_preview("base", "token", state)


def test_preview_requires_exact_added_grant(monkeypatch):
    wanted = human.grant("b93d8cf6-cd14-4ee6-91d7-84cd76c4f500",
                         "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z")
    state = {"desiredGrants": {human.GRANT_ID: wanted}}
    monkeypatch.setattr(human.lifecycle, "preview", lambda *args: {
        "grantChanges": [{"grantId": human.GRANT_ID, "before": None,
                          "after": {**wanted, "subjectId": "another-subject"}}]})
    with pytest.raises(ValueError, match="PREVIEW_GRANT_CONTENT_MISMATCH"):
        human.review_preview("base", "token", state)
