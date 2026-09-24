#!/usr/bin/env python3
"""Reconcile an OUF capability manifest through the trusted HUMAN API.

Default is local validation. --check reads the current catalogue; --apply
requires a HUMAN bearer token held in a private file. Never print that token.
Publication of an Authorization PolicyBundle is a separate trusted action.
"""
import argparse
import json
import os
from pathlib import Path
import stat
import sys
import urllib.error
import urllib.request

ACTORS = {"HUMAN", "SERVICE", "AI_AGENT"}


def request(url, token, method="GET", body=None):
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/json",
        **({"Content-Type": "application/json"} if data is not None else {}),
    })
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, response.read(2_000_000)
    except urllib.error.HTTPError as exc:
        # Never display an untrusted response, which might contain private data.
        raise RuntimeError(f"HTTP_{exc.code}") from None


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--manifest", required=True, type=Path)
    p.add_argument("--endpoint", default="https://api.ouf-lab.it/api/trusted-human/v1/authorization/capabilities")
    p.add_argument("--token-file", type=Path)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--apply", action="store_true")
    args = p.parse_args()
    if not args.endpoint.startswith("https://") or "?" in args.endpoint or "#" in args.endpoint:
        raise ValueError("HTTPS endpoint without query required")
    entries = json.loads(args.manifest.read_text(encoding="utf-8"))
    if not isinstance(entries, list) or not entries or len(entries) > 10000:
        raise ValueError("manifest must be a nonempty JSON array")
    desired = {}
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"ownerRef", "descriptor"}:
            raise ValueError("invalid manifest entry")
        owner, descriptor = entry["ownerRef"], entry["descriptor"]
        if not isinstance(owner, str) or not owner.strip() or not isinstance(descriptor, dict):
            raise ValueError("invalid owner or descriptor")
        if set(descriptor) != {"capabilityId", "operation", "requiredScope", "allowedActors"}:
            raise ValueError("invalid descriptor fields")
        ident = descriptor["capabilityId"]
        if (not isinstance(ident, str) or not ident.strip() or ident in desired
                or not all(isinstance(descriptor[k], str) and descriptor[k].strip()
                           for k in ("operation", "requiredScope"))
                or not isinstance(descriptor["allowedActors"], list)
                or not descriptor["allowedActors"]
                or len(set(descriptor["allowedActors"])) != len(descriptor["allowedActors"])
                or not set(descriptor["allowedActors"]).issubset(ACTORS)):
            raise ValueError("invalid or duplicate capability descriptor")
        desired[ident] = entry
    print(f"MANIFEST_VALID=true COUNT={len(desired)}")
    if not args.check and not args.apply:
        print("NO_NETWORK_OR_WRITES=true")
        return
    if args.token_file is None:
        raise ValueError("--token-file required for --check and --apply")
    info = args.token_file.lstat()
    if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600
            or info.st_uid != os.geteuid()):
        raise ValueError("token file must be regular, owned by caller, mode 0600")
    token = args.token_file.read_text(encoding="ascii").strip()
    if not token or len(token) > 16384:
        raise ValueError("invalid token file")
    existing = {}
    offset = 0
    while True:
        _, raw = request(args.endpoint + f"?limit=200&offset={offset}", token)
        rows = json.loads(raw)
        if not isinstance(rows, list) or len(rows) > 200:
            raise ValueError("invalid catalogue page")
        for row in rows:
            if not isinstance(row, dict):
                raise ValueError("invalid catalogue row")
            ident = row.get("capability_id")
            if not isinstance(ident, str) or ident in existing:
                raise ValueError("duplicate or invalid catalogue identifier: pagination unsupported")
            existing[ident] = row
        if len(rows) < 200:
            break
        offset += 200
        if offset > 100000:
            raise ValueError("catalogue pagination limit exceeded")
    missing = []
    for ident, entry in desired.items():
        row = existing.get(ident)
        if row is None:
            missing.append(ident)
        elif (row.get("owner_ref") != entry["ownerRef"]
              or not isinstance(row.get("descriptor"), dict)
              or {**row["descriptor"], "allowedActors": sorted(row["descriptor"].get("allowedActors", []))}
              != {**entry["descriptor"], "allowedActors": sorted(entry["descriptor"]["allowedActors"])}):
            raise ValueError(f"SEMANTIC_CONFLICT={ident}")
    print(f"ALREADY_MATCHED={len(desired)-len(missing)} MISSING={len(missing)}")
    if not args.apply:
        print("NO_WRITES=true")
        return
    for ident in missing:
        status, _ = request(args.endpoint, token, "POST", desired[ident])
        if status != 201:
            raise RuntimeError(f"UNEXPECTED_STATUS={status}")
        print(f"REGISTERED={ident}")
    print("REGISTRATION_COMPLETE=true; POLICY_BUNDLE_UNCHANGED=true")


if __name__ == "__main__":
    try:
        main()
    except (OSError, UnicodeError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"REGISTRATION_BLOCKED={exc}", file=sys.stderr)
        raise SystemExit(1) from None
