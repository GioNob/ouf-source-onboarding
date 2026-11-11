"""Expiry-only HUMAN and SERVICE renewal through the Authorization owner API.

Finite operator workflow: no automatic retries of mutations. An interrupted
create/publish is retained in the private state and requires reconciliation.
"""
from __future__ import annotations

from calendar import monthrange
from contextlib import contextmanager
from copy import deepcopy
from datetime import datetime, timezone
import fcntl
import json
import os
import re
import stat
import tempfile
from uuid import UUID

import r4a_service_grant_lifecycle as lifecycle

SCHEMA = "ouf.human-grant-renewal.v1"
SERVICE_SCHEMA = "ouf.service-grant-renewal.v1"


def need(condition, code):
    if not condition:
        raise ValueError(code)


def instant(value):
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        need(result.tzinfo is not None, "RENEW_INVALID_VALIDITY")
        return result
    except (AttributeError, TypeError, ValueError):
        raise ValueError("RENEW_INVALID_VALIDITY") from None


def utcnow():
    return datetime.now(timezone.utc)


def utc_expiry(value):
    match = re.fullmatch(r"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})"
                         r"(?:\.([0-9]{1,9}))?(?:Z|\+00:00)", value)
    need(match is not None, "RENEW_PREVENTIVE_EXPIRY_UTC_REQUIRED")
    seconds = instant(match[1] + "Z")
    nanos = int((match[2] or "").ljust(9, "0"))
    return seconds, nanos


def canonical_utc_expiry(value):
    seconds, nanos = utc_expiry(value)
    # Match the owner's Instant representation, preserving nanoseconds.
    digits = f"{nanos:09d}".rstrip("0")
    fraction = "." + digits.ljust(((len(digits) + 2) // 3) * 3, "0") if digits else ""
    return seconds.isoformat(timespec="seconds").removesuffix("+00:00") + fraction + "Z"


def preventive_expiry(scope, now):
    expected = utc_expiry(scope["expectedCurrentValidUntil"])
    new = utc_expiry(scope["validUntil"])
    need(new > expected, "RENEW_NEW_EXPIRY_NOT_LATER")
    need(new > (now.replace(microsecond=0), now.microsecond * 1000), "RENEW_NEW_EXPIRY_NOT_FUTURE")
    year = now.year + 1
    limit = now.replace(year=year, day=min(now.day, monthrange(year, now.month)[1]))
    need(new <= (limit.replace(microsecond=0), limit.microsecond * 1000),
         "RENEW_NEW_EXPIRY_OVER_TWELVE_MONTHS")
    return expected


def selectors(path, grant_type="HUMAN"):
    rows = json.loads(path.read_text())
    need(isinstance(rows, list) and 0 < len(rows) <= 100, "RENEW_MANIFEST_INVALID")
    fields = {"grantId", "capabilityId"}
    if grant_type == "SERVICE":
        fields.add("servicePrincipalId")
    ids = set()
    for row in rows:
        need(isinstance(row, dict) and set(row) == fields, "RENEW_MANIFEST_INVALID")
        need(all(isinstance(row[k], str) and re.fullmatch(r"[A-Za-z0-9._-]+", row[k])
                 for k in ("grantId", "capabilityId")), "RENEW_MANIFEST_INVALID")
        if grant_type == "SERVICE":
            principal = row["servicePrincipalId"]
            need(isinstance(principal, str) and bool(principal.strip()), "RENEW_MANIFEST_INVALID")
        need(row["grantId"] not in ids, "RENEW_DUPLICATE_GRANT_ID")
        ids.add(row["grantId"])
    return sorted(rows, key=lambda row: row["grantId"])


def state_schema(grant_type):
    return SERVICE_SCHEMA if grant_type == "SERVICE" else SCHEMA


def matches_grant(old, selection, scope, grant_type):
    if old.get("tenantId") != "ouf-lab" or old.get("capabilityId") != selection["capabilityId"]:
        return False
    if grant_type == "SERVICE":
        return (old.get("subjectId") is None and old.get("organizationId") is None
                and old.get("servicePrincipalId") == selection["servicePrincipalId"])
    return old.get("subjectId") == scope["subjectId"] and old.get("servicePrincipalId") is None


def policy_hash(policy, published=False):
    value = deepcopy(policy)
    value["grants"] = [lifecycle.normalize_grant(g) for g in value["grants"]]
    if published:
        # The owner supplies publication time; every other field is checked.
        value.pop("publishedAt", None)
    return lifecycle.digest(value)


def make_plan(current, scope):
    grant_type = scope.get("grantType", "HUMAN")
    policy = current["policy"]
    need(current["policyRef"] == f"{policy['bundleId']}:{policy['version']}",
         "RENEW_ACTIVE_REF_MISMATCH")
    caps = {c["capabilityId"]: c for c in policy["capabilities"]}
    grants = {g["grantId"]: g for g in policy["grants"]}
    need(len(grants) == len(policy["grants"]), "RENEW_DUPLICATE_ACTIVE_GRANT")
    now = utcnow()
    expected = None
    if "expectedCurrentValidUntil" in scope:
        expected = preventive_expiry(scope, now)
    else:
        need(instant(scope["validUntil"]) > now, "RENEW_NEW_EXPIRY_NOT_FUTURE")
    candidate = deepcopy(policy)
    changes = []
    for selection in scope["grants"]:
        gid, cid = selection["grantId"], selection["capabilityId"]
        old = grants.get(gid)
        need(old is not None, "RENEW_GRANT_MISSING=" + gid)
        need(matches_grant(old, selection, scope, grant_type), "RENEW_GRANT_IDENTITY_MISMATCH=" + gid)
        need(cid in caps and grant_type in caps[cid].get("allowedActors", []),
             f"RENEW_{grant_type}_CAPABILITY_REQUIRED=" + gid)
        if expected is None:
            need(instant(old["validFrom"]) < instant(old["validUntil"]) <= now,
                 "RENEW_EXPIRED_GRANT_REQUIRED=" + gid)
        else:
            need(utc_expiry(old["validUntil"]) == expected,
                 "RENEW_CURRENT_EXPIRY_MISMATCH=" + gid)
            need(utc_expiry(old["validFrom"]) < expected, "RENEW_INVALID_VALIDITY")
        updated = {**deepcopy(old), "validUntil": scope["validUntil"]}
        changes.append({"grantId": gid, "before": deepcopy(old), "after": updated})
    replacements = {c["grantId"]: c["after"] for c in changes}
    candidate["grants"] = [replacements.get(g["grantId"], g) for g in candidate["grants"]]
    candidate["version"] = int(policy["version"]) + 1
    return {
        "schema": state_schema(grant_type), "phase": "PLANNED", "scope": scope,
        "baseActiveRef": current["policyRef"], "baselineHash": policy_hash(policy),
        "targetPolicyRef": f"{candidate['bundleId']}:{candidate['version']}",
        "candidate": candidate, "changes": changes,
    }


@contextmanager
def state_lock(path):
    fd = os.open(str(path) + ".lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        need(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600
             and info.st_uid == os.geteuid(), "RENEW_LOCK_NOT_PRIVATE")
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("RENEW_OPERATION_IN_PROGRESS") from None
        yield
    finally:
        os.close(fd)


def save(path, state):
    if path.exists() or path.is_symlink():
        lifecycle.read_state(path)  # Refuse links, wrong owner or permissive files.
    fd, temporary = tempfile.mkstemp(prefix=".renew-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as output:
            json.dump(state, output, sort_keys=True, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def unchanged_active(base, token, state):
    current = lifecycle.active(base, token)
    need(current["policyRef"] == state["baseActiveRef"]
         and policy_hash(current["policy"]) == state["baselineHash"],
         "RENEW_ACTIVE_CHANGED_REPLAN_REQUIRED")
    return current


def check_draft(draft, state):
    need(draft.get("id") == state["draftId"]
         and draft.get("revision") == state["revision"]
         and draft.get("state") == "DRAFT"
         and draft.get("baseActiveRef") == state["baseActiveRef"],
         "RENEW_DRAFT_IDENTITY_OR_REVISION_CHANGED")
    need(policy_hash(draft["policy"]) == policy_hash(state["candidate"]),
         "RENEW_DRAFT_POLICY_CHANGED")


def review(base, token, state):
    unchanged_active(base, token, state)
    status, draft, _ = lifecycle.request(base, token, f"/policies/{state['draftId']}")
    need(status == 200, "RENEW_DRAFT_READ_FAILED")
    check_draft(draft, state)
    status, value, _ = lifecycle.request(base, token, f"/policies/{state['draftId']}:preview",
                                          "POST", etag=state["revision"])
    need(status == 200 and value.get("draftId") == state["draftId"]
         and value.get("revision") == state["revision"]
         and value.get("baseActiveRef") == state["baseActiveRef"],
         "RENEW_PREVIEW_IDENTITY_MISMATCH")
    need(value.get("addedCapabilities") == [] and value.get("removedCapabilities") == [],
         "RENEW_PREVIEW_CAPABILITIES_CHANGED")
    expected = {c["grantId"]: c for c in state["changes"]}
    changes = value.get("grantChanges", [])
    need(sorted(c["grantId"] for c in changes) == sorted(expected),
         "RENEW_PREVIEW_GRANT_SET_CHANGED")
    for change in changes:
        need(all(lifecycle.normalize_grant(change.get(side)) ==
                 lifecycle.normalize_grant(expected[change["grantId"]][side])
                 for side in ("before", "after")), "RENEW_PREVIEW_GRANT_CONTENT_CHANGED")
    need(isinstance(value.get("draftHash"), str)
         and re.fullmatch(r"sha256:[0-9a-f]{64}", value["draftHash"]), "RENEW_PREVIEW_HASH_INVALID")
    return value


def comparison(state, changes, status, draft_hash=None):
    # Project only review metadata, IDs and validity; never print policy identities.
    print(json.dumps({
        "status": status, "baseActiveRef": state["baseActiveRef"],
        "targetPolicyRef": state["targetPolicyRef"], "draftId": state.get("draftId"),
        "revision": state.get("revision"), "draftHash": draft_hash,
        "changes": [{"grantId": c["grantId"], "field": "validUntil",
                     "before": c["before"]["validUntil"], "after": c["after"]["validUntil"]}
                    for c in changes],
        "otherGrantAndCapabilityFieldsUnchanged": True,
        "ownerMetadataChanges": ["version", "publishedAt (at publish)"],
        "policyPublished": False,
    }, sort_keys=True))


def verify(base, token, state):
    current = lifecycle.active(base, token)
    need(current["policyRef"] == state["targetPolicyRef"]
         and policy_hash(current["policy"], published=True) ==
         policy_hash(state["candidate"], published=True), "RENEW_ACTIVE_NOT_EXPECTED")


def run(args, login, *, grant_type="HUMAN"):
    need(grant_type in ("HUMAN", "SERVICE"), "RENEW_GRANT_TYPE_INVALID")
    need(args.grants_file is not None and args.state_file is not None,
         "RENEW_MANIFEST_EXPIRY_STATE_REQUIRED")
    need(args.valid_from is None, "RENEW_VALID_FROM_MUST_NOT_CHANGE")
    scope = {"subjectId": args.subject_id}
    preventive = args.expected_current_valid_until is not None or args.new_valid_until is not None
    if preventive:
        need(args.expected_current_valid_until is not None and args.new_valid_until is not None,
             "RENEW_PREVENTIVE_EXPIRY_PAIR_REQUIRED")
        need(args.valid_until is None, "RENEW_EXPIRY_FLAGS_CONFLICT")
        scope["expectedCurrentValidUntil"] = canonical_utc_expiry(args.expected_current_valid_until)
        scope["validUntil"] = canonical_utc_expiry(args.new_valid_until)
    else:
        need(args.valid_until is not None, "RENEW_MANIFEST_EXPIRY_STATE_REQUIRED")
        instant(args.valid_until)
        scope["validUntil"] = args.valid_until
    scope["grants"] = selectors(args.grants_file, grant_type)
    if grant_type == "SERVICE":
        scope["grantType"] = grant_type
    if args.mode == "publish":
        need(args.confirm_publish, "CONFIRM_PUBLISH_REQUIRED")
        need(bool(args.reviewed_draft_hash), "REVIEWED_DRAFT_HASH_REQUIRED")
        if preventive:
            preventive_expiry(scope, utcnow())
        else:
            need(instant(args.valid_until) > utcnow(), "RENEW_NEW_EXPIRY_NOT_FUTURE")
    base, path = lifecycle.DEFAULT_BASE, args.state_file
    token = login(args.subject_id)
    with state_lock(path):
        state = lifecycle.read_state(path) if path.exists() or path.is_symlink() else None
        if state:
            need(state.get("schema") == state_schema(grant_type) and state.get("scope") == scope,
                 "RENEW_STATE_SCOPE_MISMATCH")
        if args.mode == "plan":
            need(state is None or state["phase"] == "PLANNED", "RENEW_PLAN_ALREADY_DISPATCHED")
            proposed = make_plan(lifecycle.active(base, token), scope)
            need(state is None or state == proposed, "RENEW_ACTIVE_CHANGED_REPLAN_REQUIRED")
            save(path, proposed)
            comparison(proposed, proposed["changes"], "RENEW_PLAN_NO_OWNER_WRITES")
            return
        need(state is not None, "RENEW_PLAN_REQUIRED")
        if args.mode == "verify":
            need(state["phase"] in ("PUBLISH_REQUESTED", "PUBLISHED"), "RENEW_PUBLISH_NOT_REQUESTED")
            verify(base, token, state)
            state["phase"] = "PUBLISHED"
            save(path, state)
            print("ACTIVE_VERIFIED=true POLICY_REF=" + state["targetPolicyRef"])
            return
        if args.mode == "draft" and state["phase"] == "PLANNED":
            current = unchanged_active(base, token, state)
            need(make_plan(current, scope)["candidate"] == state["candidate"],
                 "RENEW_PLAN_CONTENT_CHANGED")
            state["phase"] = "DRAFT_REQUESTED"
            save(path, state)  # Durable before the non-idempotent request.
            status, draft, headers = lifecycle.request(base, token, "/policies", "POST", state["candidate"])
            need(status == 200, "RENEW_DRAFT_CREATE_FAILED_RECONCILE")
            etag = headers.get("ETag", "").strip('"')
            need(etag.isdigit(), "RENEW_DRAFT_ETAG_INVALID_RECONCILE")
            try:
                draft_id = str(UUID(draft["id"]))
            except (ValueError, TypeError, AttributeError):
                raise ValueError("RENEW_DRAFT_ID_INVALID_RECONCILE") from None
            state.update(draftId=draft_id, revision=int(etag), phase="DRAFT_READY")
            save(path, state)  # Keep the owner ID even if the subsequent preview fails.
            check_draft(draft, state)
        need(state["phase"] == "DRAFT_READY", "RENEW_PHASE_REQUIRES_RECONCILIATION=" + state["phase"])
        if args.mode == "publish":
            need(state.get("previewHash") == args.reviewed_draft_hash,
                 "RENEW_REVIEWED_HASH_MISMATCH")
        preview = review(base, token, state)
        if args.mode in ("draft", "preview"):
            state["previewHash"] = preview["draftHash"]
            save(path, state)
            comparison(state, preview["grantChanges"], "OWNER_PREVIEW", preview["draftHash"])
            return
        need(preview["draftHash"] == args.reviewed_draft_hash, "RENEW_REVIEWED_HASH_MISMATCH")
        state["phase"] = "PUBLISH_REQUESTED"
        save(path, state)
        status, value, _ = lifecycle.request(base, token, f"/policies/{state['draftId']}:publish",
                                              "POST", etag=state["revision"])
        need(status == 200 and value.get("state") == "PUBLISHED", "RENEW_PUBLISH_REQUIRES_RECONCILIATION")
        verify(base, token, state)
        state["phase"] = "PUBLISHED"
        save(path, state)
        print("PUBLISHED=true ACTIVE_VERIFIED=true POLICY_REF=" + state["targetPolicyRef"])
