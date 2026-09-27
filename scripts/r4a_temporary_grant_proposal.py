#!/usr/bin/env python3
"""Plan and materialize one temporary OUF grant proposal without publishing it.

The helper reads configured grants through the trusted-HUMAN review endpoint,
refuses incomplete pagination and overlapping equivalent grants, and writes a
0600 proposal request that can be submitted only through the governed
authorization.permissions.propose MCP path. It never confirms or publishes.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_BASE = "https://api.ouf-lab.it/api/trusted-human/v1/authorization"
CAP_RE = re.compile(r"^[a-z0-9._-]{1,256}$")
GRANT_RE = re.compile(r"^[A-Za-z0-9_:.-]{1,128}$")


def parse_instant(value):
    if not isinstance(value, str) or not value:
        raise ValueError("invalid instant")
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def zulu(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def equivalent(grant, subject_id, capability_id, tenant_id, resource_type):
    if not isinstance(grant, dict):
        return False
    constraints = grant.get("constraints") or {}
    return (
        grant.get("subjectId") == subject_id
        and grant.get("capabilityId") == capability_id
        and grant.get("tenantId") == tenant_id
        and constraints.get("effect", "ALLOW") == "ALLOW"
        and constraints.get("resourceType") == resource_type
        and constraints.get("resourceId") is None
        and not constraints.get("resourceAttributes")
    )


def active_equivalent(grants, subject_id, capability_id, tenant_id, resource_type, now):
    matches = []
    for grant in grants:
        if not equivalent(grant, subject_id, capability_id, tenant_id, resource_type):
            continue
        try:
            start = parse_instant(grant["validFrom"])
            end = parse_instant(grant["validUntil"])
        except (KeyError, ValueError, TypeError):
            raise ValueError("invalid existing grant validity") from None
        if start <= now < end:
            matches.append(grant)
    return matches


def build_change(subject_id, capability_id, tenant_id, grant_id, valid_from, valid_until,
                 reason, resource_type="capability"):
    if not subject_id or len(subject_id) > 256:
        raise ValueError("invalid subjectId")
    if not CAP_RE.fullmatch(capability_id):
        raise ValueError("invalid capabilityId")
    if not tenant_id or len(tenant_id) > 256:
        raise ValueError("invalid tenantId")
    if not GRANT_RE.fullmatch(grant_id):
        raise ValueError("invalid grantId")
    if not reason or len(reason) > 1000:
        raise ValueError("invalid reason")
    if not resource_type or len(resource_type) > 128:
        raise ValueError("invalid resourceType")
    if valid_until <= valid_from:
        raise ValueError("validUntil must be after validFrom")
    grant = {
        "grantId": grant_id,
        "capabilityId": capability_id,
        "tenantId": tenant_id,
        "subjectId": subject_id,
        "servicePrincipalId": None,
        "organizationId": None,
        "validFrom": zulu(valid_from),
        "validUntil": zulu(valid_until),
        "constraints": {
            "effect": "ALLOW",
            "externalRoleRef": None,
            "resourceType": resource_type,
            "resourceId": None,
            "resourceAttributes": {},
            "allowedDataLabels": [],
            "allowedDetailLevels": [],
            "requiredAcr": None,
            "requiredAmr": [],
            "maxAuthenticationAgeSeconds": None,
        },
    }
    return {
        "operation": "UPSERT",
        "grantId": grant_id,
        "grant": grant,
        "reason": reason,
    }


def read_token(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.geteuid():
        raise ValueError("token file must be regular, owned by caller, mode 0600")
    token = path.read_text(encoding="ascii").strip()
    if not token or len(token) > 16384:
        raise ValueError("invalid token file")
    return token


def request_access(base, token, subject_id):
    query = urllib.parse.urlencode({"subjectId": subject_id, "limit": 200})
    req = urllib.request.Request(
        base.rstrip("/") + "/access?" + query,
        headers={"Authorization": "Bearer " + token, "Accept": "application/json"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        raise RuntimeError("HTTP_" + str(exc.code)) from None


def validate_access(value, subject_id):
    if not isinstance(value, dict) or not isinstance(value.get("grants"), list):
        raise ValueError("invalid access response")
    if value.get("nextAfter") is not None:
        raise ValueError("ACCESS_PAGINATION_INCOMPLETE")
    for grant in value["grants"]:
        if grant.get("subjectId") != subject_id:
            raise ValueError("ACCESS_SUBJECT_MISMATCH")
    return value


def write_private_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as out:
        json.dump(value, out, indent=2, sort_keys=True)
        out.write("\n")
    os.replace(tmp, path)
    os.chmod(path, 0o600)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--subject-id", required=True)
    p.add_argument("--capability-id", required=True)
    p.add_argument("--tenant-id", default="ouf-lab")
    p.add_argument("--resource-type", default="capability")
    p.add_argument("--grant-id", required=True)
    p.add_argument("--reason", required=True)
    p.add_argument("--duration-minutes", type=int, default=60)
    p.add_argument("--valid-from")
    p.add_argument("--base-url", default=DEFAULT_BASE)
    p.add_argument("--admin-token-file", type=Path)
    p.add_argument("--access-json", type=Path)
    p.add_argument("--output", type=Path)
    args = p.parse_args()

    if args.duration_minutes < 5 or args.duration_minutes > 1440:
        raise ValueError("duration must be between 5 and 1440 minutes")
    if not args.base_url.startswith("https://") or "?" in args.base_url or "#" in args.base_url:
        raise ValueError("HTTPS base URL without query required")
    if (args.admin_token_file is None) == (args.access_json is None):
        raise ValueError("choose exactly one of --admin-token-file and --access-json")

    if args.access_json is not None:
        access = json.loads(args.access_json.read_text(encoding="utf-8"))
    else:
        access = request_access(args.base_url, read_token(args.admin_token_file), args.subject_id)
    access = validate_access(access, args.subject_id)

    now = datetime.now(timezone.utc)
    start = parse_instant(args.valid_from) if args.valid_from else now
    end = start + timedelta(minutes=args.duration_minutes)

    existing = active_equivalent(
        access["grants"], args.subject_id, args.capability_id, args.tenant_id,
        args.resource_type, now
    )
    print("POLICY_REF=" + str(access.get("policyRef")))
    print("CONFIGURED_GRANTS=" + str(len(access["grants"])))
    print("ACTIVE_EQUIVALENT_GRANTS=" + str(len(existing)))
    if existing:
        print("NO_PROPOSAL=true")
        raise ValueError("ACTIVE_EQUIVALENT_GRANT_EXISTS")

    change = build_change(
        args.subject_id, args.capability_id, args.tenant_id, args.grant_id,
        start, end, args.reason, args.resource_type
    )
    envelope = {
        "toolName": "authorization.permissions.propose",
        "arguments": change,
        "expectedBasePolicyRef": access.get("policyRef"),
    }
    print("PROPOSAL_OPERATION=UPSERT")
    print("GRANT_ID=" + args.grant_id)
    print("CAPABILITY_ID=" + args.capability_id)
    print("VALID_FROM=" + change["grant"]["validFrom"])
    print("VALID_UNTIL=" + change["grant"]["validUntil"])
    print("RESOURCE_TYPE=" + args.resource_type)
    print("POLICY_NOT_CHANGED=true")
    if args.output is None:
        print("REQUEST_NOT_WRITTEN=true")
        return
    write_private_json(args.output, envelope)
    print("REQUEST_FILE=" + str(args.output))
    print("REQUEST_FILE_MODE=0600")
    print("THS_CONFIRMATION_REQUIRED=true")


if __name__ == "__main__":
    try:
        main()
    except (OSError, UnicodeError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print("TEMP_GRANT_BLOCKED=" + str(exc), file=sys.stderr)
        raise SystemExit(1) from None
