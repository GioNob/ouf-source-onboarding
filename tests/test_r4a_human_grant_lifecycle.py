import importlib.util
import copy
from datetime import datetime, timezone
import json
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

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


import r4a_human_grant_renewal as renew
SUBJECT = "b93d8cf6-cd14-4ee6-91d7-84cd76c4f500"
UNTIL = "2026-12-31T23:00:00Z"
MANIFEST = Path(__file__).parents[1] / "catalogue/teatri-human-grant-renewal-20261109.json"


@pytest.fixture
def renewal_case(monkeypatch, tmp_path):
    monkeypatch.setattr(renew, "utcnow", lambda: datetime(2026, 11, 9, tzinfo=timezone.utc))
    selections = renew.selectors(MANIFEST)
    rows = [human.grant(SUBJECT, "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z",
                        row["grantId"], row["capabilityId"]) for row in selections]
    # A non-null constraint must survive; renewal does not reinterpret it.
    rows[0]["constraints"] = {"effect": "ALLOW", "requiredAcr": "2"}
    role = {**copy.deepcopy(rows[1]), "grantId": "ouf-role:unchanged", "subjectId": None,
            "constraints": {"externalRoleRef": "reviewer", "allowedDataLabels": ["OPEN"],
                            "allowedDetailLevels": ["PUBLIC_OPERATIONAL"]}}
    service = {**copy.deepcopy(rows[1]), "grantId": "unselected-service", "subjectId": None,
               "servicePrincipalId": "ingestion"}
    policy = {"bundleId": "test-bundle", "version": 12, "publishedAt": "2026-10-01T00:00:00Z",
              "capabilities": [{"capabilityId": r["capabilityId"], "operation": "EXECUTE",
                                "requiredScope": r["capabilityId"], "allowedActors": ["HUMAN"]}
                               for r in selections], "grants": [role, *rows, service]}
    current = {"policyRef": "test-bundle:12", "policy": policy}
    args = SimpleNamespace(mode="plan", grants_file=MANIFEST, valid_until=UNTIL,
                           valid_from=None, state_file=tmp_path / "renewal.json",
                           subject_id=SUBJECT, confirm_publish=False, reviewed_draft_hash=None)
    scope = {"grants": selections, "subjectId": SUBJECT, "validUntil": UNTIL}
    return SimpleNamespace(current=current, args=args, scope=scope)


class RenewalOwner:
    """Owner contract fake: publication time differs and revisions are fenced."""
    def __init__(self, current):
        self.current = copy.deepcopy(current)
        self.draft = None
        self.calls = []
        self.fail = None
        self.after_effect = False
        self.preview_edit = lambda value: None

    def active(self, *args):
        return copy.deepcopy(self.current)

    def request(self, base, token, path, method="GET", body=None, etag=None):
        self.calls.append((method, path))
        if path == self.fail and not self.after_effect:
            raise TimeoutError("owner timeout")
        if path == "/policies":
            assert method == "POST"
            self.draft = {"id": "40c0e7a5-2123-4807-a147-35697149e319", "revision": 1,
                          "state": "DRAFT", "baseActiveRef": self.current["policyRef"],
                          "policy": copy.deepcopy(body)}
            value = self.draft
        elif path.endswith(":preview"):
            assert etag == self.draft["revision"] and self.draft["state"] == "DRAFT"
            before = {g["grantId"]: g for g in self.current["policy"]["grants"]}
            value = {"draftId": self.draft["id"], "revision": self.draft["revision"],
                     "baseActiveRef": self.draft["baseActiveRef"], "addedCapabilities": [],
                     "removedCapabilities": [], "draftHash": "sha256:" + renew.policy_hash(self.draft["policy"]),
                     "grantChanges": [{"grantId": g["grantId"], "before": before[g["grantId"]],
                                       "after": copy.deepcopy(g)}
                                      for g in self.draft["policy"]["grants"] if g != before[g["grantId"]]]}
            self.preview_edit(value)
        elif path.endswith(":publish"):
            assert etag == self.draft["revision"] and self.draft["state"] == "DRAFT"
            policy = copy.deepcopy(self.draft["policy"])
            policy["publishedAt"] = "2026-11-09T18:00:00Z"
            self.current = {"policyRef": "test-bundle:13", "policy": policy}
            self.draft.update(state="PUBLISHED", revision=2)
            value = self.draft
        else:
            assert method == "GET" and path == "/policies/" + self.draft["id"]
            value = self.draft
        if path == self.fail:
            raise TimeoutError("reply lost after effect")
        return 200, copy.deepcopy(value), {"ETag": '"' + str(self.draft["revision"]) + '"'}


@pytest.fixture
def owner(monkeypatch, renewal_case):
    api = RenewalOwner(renewal_case.current)
    monkeypatch.setattr(renew.lifecycle, "active", api.active)
    monkeypatch.setattr(renew.lifecycle, "request", api.request)
    return api


def run_phase(case, mode):
    case.args.mode = mode
    renew.run(case.args, lambda subject: "not-a-live-token")


def test_renewal_manifest_is_exactly_the_five_authorized_pairs():
    assert {r["grantId"]: r["capabilityId"] for r in renew.selectors(MANIFEST)} == {
        "grant-onboarding-configuration-write-human-admin": "ouf.onboarding.configuration.write",
        "grant-managed-file-upload-human-admin": "ouf.managed-source.file.upload",
        "grant-managed-file-profile-human-admin": "ouf.managed-source.file.profile",
        "grant-managed-file-preview-human-admin": "ouf.managed-source.preview",
        "grant-managed-file-onboarding-create-human-admin": "ouf.managed-source.onboarding.create"}


def test_renewal_only_changes_selected_expiries_and_bundle_version(renewal_case):
    case = renewal_case
    original = copy.deepcopy(case.current)
    state = renew.make_plan(case.current, case.scope)
    restored = copy.deepcopy(state["candidate"])
    restored["version"] -= 1
    before = {g["grantId"]: g for g in original["policy"]["grants"]}
    for row in restored["grants"]:
        if row["grantId"] in {r["grantId"] for r in case.scope["grants"]}:
            assert row["validUntil"] == UNTIL
            row["validUntil"] = before[row["grantId"]]["validUntil"]
    assert restored == original["policy"]  # Includes grant order and role/service constraints.
    assert case.current == original


@pytest.mark.parametrize("field,value", [
    ("subjectId", "different"), ("tenantId", "other"), ("servicePrincipalId", "runtime"),
    ("capabilityId", "different"), ("validUntil", "2026-12-01T00:00:00Z")])
def test_renewal_rejects_identity_mismatch_or_unexpired_grant(renewal_case, field, value):
    case = renewal_case
    case.current["policy"]["grants"][1][field] = value
    with pytest.raises(ValueError, match="RENEW_(GRANT_IDENTITY_MISMATCH|EXPIRED_GRANT_REQUIRED)"):
        renew.make_plan(case.current, case.scope)


def test_renewal_requires_all_grants_and_human_capability(renewal_case):
    case = renewal_case
    removed = case.current["policy"]["grants"].pop(1)
    with pytest.raises(ValueError, match="RENEW_GRANT_MISSING"):
        renew.make_plan(case.current, case.scope)
    case.current["policy"]["grants"].insert(1, removed)
    case.current["policy"]["capabilities"][0]["allowedActors"] = ["SERVICE"]
    with pytest.raises(ValueError, match="RENEW_HUMAN_CAPABILITY_REQUIRED"):
        renew.make_plan(case.current, case.scope)


@pytest.mark.parametrize("expiry", ["2026-11-08T00:00:00Z", "2026-12-31T23:00:00", "bad"])
def test_renewal_rejects_past_or_invalid_expiry(renewal_case, expiry):
    renewal_case.scope["validUntil"] = expiry
    with pytest.raises(ValueError, match="RENEW_(NEW_EXPIRY_NOT_FUTURE|INVALID_VALIDITY)"):
        renew.make_plan(renewal_case.current, renewal_case.scope)


def test_renewal_duplicate_manifest_rejected(tmp_path):
    rows = renew.selectors(MANIFEST)
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(rows + rows[:1]))
    with pytest.raises(ValueError, match="RENEW_DUPLICATE_GRANT_ID"):
        renew.selectors(path)


def test_plan_draft_preview_and_publish_are_separate(renewal_case, owner, capsys):
    case = renewal_case
    run_phase(case, "plan")
    assert owner.calls == [] and case.args.state_file.stat().st_mode & 0o777 == 0o600
    plan_output = json.loads(capsys.readouterr().out)
    assert plan_output["status"] == "RENEW_PLAN_NO_OWNER_WRITES"
    run_phase(case, "draft")
    preview_text = capsys.readouterr().out
    preview = json.loads(preview_text)
    assert preview["status"] == "OWNER_PREVIEW" and preview["policyPublished"] is False
    assert len(preview["changes"]) == 5
    assert SUBJECT not in preview_text and "not-a-live-token" not in preview_text
    assert owner.current["policyRef"] == "test-bundle:12"
    run_phase(case, "draft")  # Reuses the known draft, never recreates it.
    assert owner.calls.count(("POST", "/policies")) == 1
    assert not any(path.endswith(":publish") for _, path in owner.calls)
    case.args.confirm_publish = True
    case.args.reviewed_draft_hash = preview["draftHash"]
    run_phase(case, "publish")
    run_phase(case, "verify")
    assert owner.current["policyRef"] == "test-bundle:13"
    assert human.lifecycle.read_state(case.args.state_file)["phase"] == "PUBLISHED"
    with pytest.raises(ValueError, match="REQUIRES_RECONCILIATION"):
        run_phase(case, "publish")
    assert sum(path.endswith(":publish") for _, path in owner.calls) == 1


def test_cli_renew_dispatch_and_forbidden_valid_from(renewal_case, owner, monkeypatch):
    case = renewal_case
    monkeypatch.setattr(human, "human_token", lambda subject: "not-a-live-token")
    argv = ["human", "plan", "--operation", "renew", "--subject-id", SUBJECT,
            "--grants-file", str(MANIFEST), "--valid-until", UNTIL,
            "--state-file", str(case.args.state_file)]
    monkeypatch.setattr(sys, "argv", argv)
    human.main()
    monkeypatch.setattr(sys, "argv", argv + ["--valid-from", "2026-09-25T00:00:00Z"])
    with pytest.raises(ValueError, match="RENEW_VALID_FROM_MUST_NOT_CHANGE"):
        human.main()
    assert not owner.calls


def test_active_drift_blocks_draft_before_create(renewal_case, owner):
    case = renewal_case
    run_phase(case, "plan")
    owner.current["policy"]["grants"][-1]["validUntil"] = UNTIL
    with pytest.raises(ValueError, match="RENEW_ACTIVE_CHANGED"):
        run_phase(case, "draft")
    assert not owner.calls


@pytest.mark.parametrize("part", ["capability", "extra-grant", "validFrom", "constraints"])
def test_owner_preview_rejects_extra_changes(renewal_case, owner, part):
    def corrupt(value):
        if part == "capability":
            value["addedCapabilities"] = [{"capabilityId": "unexpected"}]
        elif part == "extra-grant":
            value["grantChanges"].append({"grantId": "unexpected"})
        else:
            value["grantChanges"][0]["after"][part] = "unexpected"
    owner.preview_edit = corrupt
    run_phase(renewal_case, "plan")
    with pytest.raises(ValueError, match="RENEW_PREVIEW_"):
        run_phase(renewal_case, "draft")
    assert human.lifecycle.read_state(renewal_case.args.state_file)["phase"] == "DRAFT_READY"
    assert not any(path.endswith(":publish") for _, path in owner.calls)


def test_full_draft_check_catches_other_tenant_or_unselected_changes(renewal_case, owner):
    run_phase(renewal_case, "plan")
    run_phase(renewal_case, "draft")
    owner.draft["policy"]["grants"][-1]["tenantId"] = "other-tenant"
    with pytest.raises(ValueError, match="RENEW_DRAFT_POLICY_CHANGED"):
        run_phase(renewal_case, "preview")


@pytest.mark.parametrize("invalid_hash", ["a" * 64, "sha256:" + "a" * 63, "sha256:" + "A" * 64],
                         ids=["missing-prefix", "wrong-length", "uppercase"])
def test_preview_validates_and_preserves_owner_hash(renewal_case, owner, capsys, invalid_hash):
    run_phase(renewal_case, "plan")
    capsys.readouterr()
    owner.preview_edit = lambda value: value.update(draftHash=invalid_hash)
    with pytest.raises(ValueError, match="RENEW_PREVIEW_HASH_INVALID"):
        run_phase(renewal_case, "draft")

    owner_hash = "sha256:" + "0123456789abcdef" * 4
    owner.preview_edit = lambda value: value.update(draftHash=owner_hash)
    run_phase(renewal_case, "preview")
    preview = json.loads(capsys.readouterr().out)
    assert preview["draftId"] == owner.draft["id"]
    assert preview["draftHash"] == owner_hash
    assert human.lifecycle.read_state(renewal_case.args.state_file)["previewHash"] == owner_hash
    assert owner.calls.count(("POST", "/policies")) == 1


def test_preview_failure_keeps_id_and_resumes_without_new_draft(renewal_case, owner):
    run_phase(renewal_case, "plan")
    owner.fail = "/policies/40c0e7a5-2123-4807-a147-35697149e319:preview"
    with pytest.raises(TimeoutError):
        run_phase(renewal_case, "draft")
    state = human.lifecycle.read_state(renewal_case.args.state_file)
    assert state["draftId"] == owner.draft["id"] and state["phase"] == "DRAFT_READY"
    owner.fail = None
    run_phase(renewal_case, "preview")
    assert owner.calls.count(("POST", "/policies")) == 1


@pytest.mark.parametrize("effect", [False, True])
def test_ambiguous_create_is_never_replayed(renewal_case, owner, effect):
    run_phase(renewal_case, "plan")
    owner.fail, owner.after_effect = "/policies", effect
    with pytest.raises(TimeoutError):
        run_phase(renewal_case, "draft")
    with pytest.raises(ValueError, match="REQUIRES_RECONCILIATION=DRAFT_REQUESTED"):
        run_phase(renewal_case, "draft")
    assert owner.calls.count(("POST", "/policies")) == 1


@pytest.mark.parametrize("confirmed,review_hash", [(False, None), (True, None), (True, "wrong")])
def test_publish_requires_explicit_confirmation_and_reviewed_hash(renewal_case, owner, confirmed, review_hash):
    run_phase(renewal_case, "plan")
    run_phase(renewal_case, "draft")
    renewal_case.args.confirm_publish = confirmed
    renewal_case.args.reviewed_draft_hash = review_hash
    with pytest.raises(ValueError, match="(CONFIRM_PUBLISH_REQUIRED|REVIEWED_DRAFT_HASH_REQUIRED|RENEW_REVIEWED_HASH_MISMATCH)"):
        run_phase(renewal_case, "publish")
    assert not any(path.endswith(":publish") for _, path in owner.calls)


def test_publish_checks_active_and_fresh_owner_preview(renewal_case, owner):
    run_phase(renewal_case, "plan")
    run_phase(renewal_case, "draft")
    renewal_case.args.confirm_publish = True
    renewal_case.args.reviewed_draft_hash = human.lifecycle.read_state(renewal_case.args.state_file)["previewHash"]
    owner.preview_edit = lambda value: value.update(draftHash="sha256:" + "0" * 64)
    with pytest.raises(ValueError, match="RENEW_REVIEWED_HASH_MISMATCH"):
        run_phase(renewal_case, "publish")
    owner.current["policyRef"] = "test-bundle:99"
    with pytest.raises(ValueError, match="RENEW_ACTIVE_CHANGED"):
        run_phase(renewal_case, "publish")
    assert not any(path.endswith(":publish") for _, path in owner.calls)


def test_ambiguous_publish_reconciles_by_read_without_replay(renewal_case, owner):
    case = renewal_case
    run_phase(case, "plan")
    run_phase(case, "draft")
    state = human.lifecycle.read_state(case.args.state_file)
    case.args.confirm_publish = True
    case.args.reviewed_draft_hash = state["previewHash"]
    owner.fail, owner.after_effect = "/policies/" + state["draftId"] + ":publish", True
    with pytest.raises(TimeoutError):
        run_phase(case, "publish")
    with pytest.raises(ValueError, match="REQUIRES_RECONCILIATION=PUBLISH_REQUESTED"):
        run_phase(case, "publish")
    run_phase(case, "verify")
    assert sum(path.endswith(":publish") for _, path in owner.calls) == 1
    owner.current["policy"]["grants"][-1]["validUntil"] = UNTIL
    with pytest.raises(ValueError, match="RENEW_ACTIVE_NOT_EXPECTED"):
        run_phase(case, "verify")


def test_state_scope_drift_and_concurrent_launch_block(renewal_case, owner):
    case = renewal_case
    run_phase(case, "plan")
    with renew.state_lock(case.args.state_file):
        with pytest.raises(ValueError, match="RENEW_OPERATION_IN_PROGRESS"):
            run_phase(case, "draft")
    case.args.valid_until = "2027-01-01T00:00:00Z"
    with pytest.raises(ValueError, match="RENEW_STATE_SCOPE_MISMATCH"):
        run_phase(case, "draft")
    assert not owner.calls


def test_state_symlink_and_permissive_file_rejected(renewal_case, owner, tmp_path):
    case = renewal_case
    run_phase(case, "plan")
    case.args.state_file.chmod(0o644)
    with pytest.raises(ValueError, match="0600"):
        run_phase(case, "draft")
    case.args.state_file.chmod(0o600)
    link = tmp_path / "linked.json"
    link.symlink_to(case.args.state_file)
    case.args.state_file = link
    with pytest.raises(ValueError, match="0600"):
        run_phase(case, "draft")
    assert not owner.calls


def test_existing_add_cli_does_not_require_renewal_helper(tmp_path):
    for name in ("r4a_human_grant_lifecycle.py", "r4a_service_grant_lifecycle.py"):
        (tmp_path / name).write_bytes((SCRIPTS / name).read_bytes())
    result = subprocess.run([sys.executable, str(tmp_path / "r4a_human_grant_lifecycle.py"),
                             "--help"], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr


def test_expired_proposal_cannot_be_published(renewal_case, owner, monkeypatch):
    run_phase(renewal_case, "plan")
    run_phase(renewal_case, "draft")
    renewal_case.args.confirm_publish = True
    renewal_case.args.reviewed_draft_hash = human.lifecycle.read_state(renewal_case.args.state_file)["previewHash"]
    monkeypatch.setattr(renew, "utcnow", lambda: datetime(2027, 1, 1, tzinfo=timezone.utc))
    with pytest.raises(ValueError, match="RENEW_NEW_EXPIRY_NOT_FUTURE"):
        run_phase(renewal_case, "publish")
    assert not any(path.endswith(":publish") for _, path in owner.calls)

