#!/usr/bin/env python3
"""Governed add-only lifecycle for one named HUMAN grant in OUF Authorization."""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys
import time

import r4a_service_grant_lifecycle as lifecycle

GRANT_ID = "grant-onboarding-configuration-write-human-admin"
CAPABILITY = "ouf.onboarding.configuration.write"
SUBJECT_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def human_token(subject_id):
    token = lifecycle.device_login()
    try:
        part = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    except (IndexError, ValueError, UnicodeDecodeError) as exc:
        raise ValueError("HUMAN_TOKEN_INVALID") from exc
    aud = claims.get("aud", [])
    if isinstance(aud, str):
        aud = [aud]
    if not (
        claims.get("iss") == lifecycle.ISSUER
        and claims.get("azp") == "ouf-human-admin"
        and claims.get("sub") == subject_id
        and claims.get("preferred_username") == "ouf-admin"
        and claims.get("ouf_actor_type") in ("HUMAN", "HUMAN_USER")
        and "ouf-api-gateway" in aud
        and "authorization.policy.admin" in str(claims.get("scope", "")).split()
        and claims.get("exp", 0) > time.time()
    ):
        raise ValueError("HUMAN_TOKEN_CONTRACT_MISMATCH")
    return token


def grant(subject_id, valid_from, valid_until, grant_id=GRANT_ID, capability=CAPABILITY):
    if not SUBJECT_RE.fullmatch(subject_id):
        raise ValueError("INVALID_SUBJECT_ID")
    if not re.fullmatch(r"[A-Za-z0-9._-]+", grant_id) or not re.fullmatch(r"[A-Za-z0-9._-]+", capability):
        raise ValueError("INVALID_GRANT_OR_CAPABILITY")
    start = datetime.fromisoformat(valid_from.replace("Z", "+00:00"))
    end = datetime.fromisoformat(valid_until.replace("Z", "+00:00"))
    if start.tzinfo is None or end.tzinfo is None or not start < end:
        raise ValueError("INVALID_VALIDITY")
    return {
        "grantId": grant_id, "capabilityId": capability, "tenantId": "ouf-lab",
        "subjectId": subject_id, "servicePrincipalId": None, "organizationId": None,
        "validFrom": valid_from, "validUntil": valid_until, "constraints": None,
    }


def check_no_equivalent(policy, wanted):
    for row in policy["grants"]:
        if row.get("grantId") != wanted["grantId"] and all(
            row.get(key) == wanted[key] for key in ("subjectId", "capabilityId", "tenantId")
        ):
            raise ValueError("EQUIVALENT_GRANT_REQUIRES_REVIEW")


def review_preview(base, token, state):
    value = lifecycle.preview(base, token, state)
    change = value["grantChanges"][0]
    desired = state["desiredGrants"].get(change.get("grantId"))
    if desired is None:
        raise ValueError("PREVIEW_GRANT_ID_MISMATCH")
    if lifecycle.normalize_grant(change["after"]) != lifecycle.normalize_grant(desired):
        raise ValueError("PREVIEW_GRANT_CONTENT_MISMATCH")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "draft", "preview", "publish", "verify"))
    parser.add_argument("--subject-id", required=True)
    parser.add_argument("--grant-id", default=GRANT_ID)
    parser.add_argument("--capability", default=CAPABILITY)
    parser.add_argument("--valid-from", default="2026-09-25T00:00:00Z")
    parser.add_argument("--valid-until", default="2026-10-25T00:00:00Z")
    parser.add_argument("--state-file", type=Path)
    parser.add_argument("--confirm-publish", action="store_true")
    args = parser.parse_args()
    wanted = grant(args.subject_id, args.valid_from, args.valid_until, args.grant_id, args.capability)
    token = human_token(args.subject_id)
    base = lifecycle.DEFAULT_BASE

    if args.mode in ("plan", "draft"):
        current = lifecycle.active(base, token)
        policy, missing = lifecycle.plan(current, [wanted])
        check_no_equivalent(policy, wanted)
        print("ACTIVE_POLICY_REF=" + current["policyRef"])
        print("GRANT_ID=" + args.grant_id)
        print("SUBJECT_MATCH=true")
        print("ADD_GRANTS=" + str(len(missing)))
        print("EXISTING_GRANTS_PRESERVED=true")
        if args.mode == "plan":
            print("NO_WRITES=true")
            return
        if not missing:
            raise ValueError("NO_POLICY_CHANGE_REQUIRED")
        if args.state_file is None:
            raise ValueError("STATE_FILE_REQUIRED")
        candidate = {**policy, "version": int(policy["version"]) + 1,
                     "grants": [*policy["grants"], *missing]}
        status, draft, headers = lifecycle.request(base, token, "/policies", "POST", candidate)
        etag = headers.get("ETag", "").strip('"')
        if status != 200 or not etag.isdigit():
            raise RuntimeError("DRAFT_CREATE_FAILED")
        state = {
            "draftId": draft["id"], "revision": int(etag),
            "targetPolicyRef": candidate["bundleId"] + ":" + str(candidate["version"]),
            "addedGrantIds": [args.grant_id], "desiredGrants": {args.grant_id: wanted},
            "capabilitiesHash": lifecycle.digest(policy["capabilities"]),
            "baselineGrantsHash": lifecycle.digest(policy["grants"]),
        }
        review_preview(base, token, state)
        lifecycle.write_state(args.state_file, state)
        print("DRAFT_ID=" + state["draftId"])
        print("PREVIEW_GRANT_ADDS=" + args.grant_id)
        print("POLICY_NOT_PUBLISHED=true")
        return

    if args.state_file is None:
        raise ValueError("STATE_FILE_REQUIRED")
    state = lifecycle.read_state(args.state_file)
    if state.get("desiredGrants") != {args.grant_id: wanted} or state.get("addedGrantIds") != [args.grant_id]:
        raise ValueError("STATE_GRANT_MISMATCH")
    if args.mode == "preview":
        review_preview(base, token, state)
        print("PREVIEW_OK=true")
        print("NO_WRITES=true")
        return
    if args.mode == "verify":
        current = lifecycle.active(base, token)
        lifecycle.verify(current, state)
        print("ACTIVE_VERIFIED=true POLICY_REF=" + current["policyRef"])
        print("EXISTING_GRANTS_PRESERVED=true")
        return
    if not args.confirm_publish:
        raise ValueError("CONFIRM_PUBLISH_REQUIRED")
    review_preview(base, token, state)
    status, value, _ = lifecycle.request(base, token, f"/policies/{state['draftId']}:publish",
                                         "POST", etag=state["revision"])
    if status != 200 or value.get("state") != "PUBLISHED":
        raise RuntimeError("PUBLISH_FAILED")
    current = lifecycle.active(base, token)
    lifecycle.verify(current, state)
    print("PUBLISHED=true POLICY_REF=" + current["policyRef"])
    print("ACTIVE_VERIFIED=true EXISTING_GRANTS_PRESERVED=true")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as exc:
        code = str(exc) if isinstance(exc, (ValueError, RuntimeError)) else type(exc).__name__
        print("HUMAN_GRANT_LIFECYCLE_BLOCKED=" + code, file=sys.stderr)
        raise SystemExit(1)
