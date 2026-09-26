#!/usr/bin/env python3
"""Plan the missing HUMAN grants for the verified ouf-admin subject.

Reads the active Authorization policy via HUMAN Device Flow. Never grants a
SERVICE-only capability to a HUMAN. Generated manifest is consumed by the
existing plan/draft/preview/publish/verify lifecycle; this tool never edits
the active policy or changes Keycloak scopes.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys

import r4a_human_grant_lifecycle as human
import r4a_service_grant_lifecycle as lifecycle

TENANT = 'ouf-lab'


def build(policy: dict, subject: str) -> tuple[list[dict], list[str], list[str]]:
    capabilities = policy['capabilities']
    if not isinstance(capabilities, list) or len(capabilities) != len({
            cap['capabilityId'] for cap in capabilities}):
        raise ValueError('CAPABILITY_LIST_INVALID')
    current = policy['grants']
    existing_ids = {grant['grantId'] for grant in current}
    manifest = []
    scopes = set()
    covered = []
    for cap in sorted(capabilities, key=lambda row: row['capabilityId']):
        if 'HUMAN' not in cap['allowedActors']:
            continue
        capid = cap['capabilityId']
        scopes.add(cap['requiredScope'])
        equivalents = [grant for grant in current if grant.get('capabilityId') == capid
                       and grant.get('subjectId') == subject and grant.get('tenantId') == TENANT]
        if equivalents:
            if not any(grant.get('servicePrincipalId') is None
                       and grant.get('organizationId') is None
                       and grant.get('constraints') is None
                       and isinstance(grant.get('validFrom'), str)
                       and isinstance(grant.get('validUntil'), str)
                       and datetime.fromisoformat(grant['validFrom'].replace('Z', '+00:00')) <= datetime.now(timezone.utc)
                       and datetime.fromisoformat(grant['validUntil'].replace('Z', '+00:00')) > datetime.now(timezone.utc)
                       for grant in equivalents):
                raise ValueError('ADMIN_GRANT_VALIDITY_REVIEW')
            covered.append(capid)
            continue
        grant_id = 'grant-ouf-admin-' + hashlib.sha256(capid.encode('utf-8')).hexdigest()[:20]
        if grant_id in existing_ids:
            raise ValueError('ADMIN_GRANT_ID_COLLISION')
        manifest.append({'grantId': grant_id, 'capabilityId': capid})
    if not scopes:
        raise ValueError('NO_HUMAN_CAPABILITIES')
    return manifest, sorted(scopes), covered


def write_manifest(path: Path, rows: list[dict]) -> None:
    if not rows:
        raise ValueError('NO_MISSING_GRANTS')
    if path.is_symlink() or path.exists():
        raise ValueError('MANIFEST_ALREADY_EXISTS')
    if not path.parent.is_dir() or path.parent.is_symlink():
        raise ValueError('MANIFEST_PARENT_UNSAFE')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as stream:
        json.dump(rows, stream, indent=2)
        stream.write('\n')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--subject-id', required=True)
    parser.add_argument('--manifest-out', type=Path,
                        help='Write missing grant IDs to a new private file; no policy mutation')
    args = parser.parse_args()
    if not human.SUBJECT_RE.fullmatch(args.subject_id):
        raise ValueError('INVALID_SUBJECT_ID')
    token = human.human_token(args.subject_id)
    current = lifecycle.active(lifecycle.DEFAULT_BASE, token)
    rows, scopes, covered = build(current['policy'], args.subject_id)
    if args.manifest_out is not None:
        write_manifest(args.manifest_out, rows)
    print('ACTIVE_POLICY_REF=' + current['policyRef'])
    print('ADMIN_EXISTING_HUMAN_GRANTS=' + str(len(covered)))
    print('ADMIN_MISSING_HUMAN_GRANTS=' + str(len(rows)))
    print('REQUIRED_HUMAN_SCOPES=' + ','.join(scopes))
    print('SERVICE_ONLY_GRANTS_UNCHANGED=true')
    print('POLICY_UNCHANGED=true')
    print('MANIFEST_WRITTEN=' + str(args.manifest_out is not None).lower())


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as exc:
        code = str(exc) if isinstance(exc, (ValueError, RuntimeError)) else type(exc).__name__
        print('ADMIN_GRANT_PLAN_BLOCKED=' + code, file=sys.stderr)
        raise SystemExit(1)
