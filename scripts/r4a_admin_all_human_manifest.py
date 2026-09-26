#!/usr/bin/env python3
"""Plan the missing HUMAN grants for the verified ouf-admin subject.

Reads the active Authorization policy via HUMAN Device Flow. Never grants a
SERVICE-only capability to a HUMAN. Generated manifest is consumed by the
existing plan/draft/preview/publish/verify lifecycle; this tool never edits
the active policy or changes Keycloak scopes.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TENANT = 'ouf-lab'
ISSUER = 'https://auth.ouf-lab.it/realms/ouf'
BASE = 'https://api.ouf-lab.it/api/trusted-human/v1/authorization'
SUBJECT_RE = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')


def read_json(url: str, token: str | None = None) -> dict:
    headers = {'Authorization': 'Bearer ' + token} if token is not None else {}
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        raise ValueError('HTTP_' + str(exc.code)) from None


def device_login(subject_id: str) -> str:
    discovery = read_json(ISSUER + '/.well-known/openid-configuration')

    def post(url: str, values: dict) -> tuple[int, dict]:
        request = urllib.request.Request(
            url, data=urllib.parse.urlencode(values).encode(), method='POST',
            headers={'Content-Type': 'application/x-www-form-urlencoded'})
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as exc:
            try:
                return exc.code, json.load(exc)
            except (ValueError, UnicodeError):
                return exc.code, {}

    status, start = post(discovery['device_authorization_endpoint'], {
        'client_id': 'ouf-human-admin', 'scope': 'openid authorization.policy.admin'})
    if status != 200:
        raise ValueError('DEVICE_AUTHORIZATION_FAILED')
    print('OPEN_IN_BROWSER=' + start['verification_uri'], flush=True)
    print('ENTER_DEVICE_CODE=' + start['user_code'], flush=True)
    interval = max(5, min(30, int(start.get('interval', 5))))
    deadline = time.monotonic() + min(600, int(start['expires_in']))
    while time.monotonic() < deadline:
        time.sleep(interval)
        status, response = post(discovery['token_endpoint'], {
            'grant_type': 'urn:ietf:params:oauth:grant-type:device_code',
            'client_id': 'ouf-human-admin', 'device_code': start['device_code']})
        if status == 200:
            token = response['access_token']
            break
        if response.get('error') == 'slow_down':
            interval = min(30, interval + 5)
        elif response.get('error') != 'authorization_pending':
            raise ValueError('DEVICE_LOGIN_FAILED')
    else:
        raise ValueError('DEVICE_LOGIN_EXPIRED')
    try:
        part = token.split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))
    except (IndexError, ValueError, UnicodeDecodeError) as exc:
        raise ValueError('HUMAN_TOKEN_INVALID') from exc
    aud = claims.get('aud', [])
    if isinstance(aud, str):
        aud = [aud]
    if not (claims.get('iss') == ISSUER and claims.get('azp') == 'ouf-human-admin'
            and claims.get('sub') == subject_id
            and claims.get('preferred_username') == 'ouf-admin'
            and claims.get('ouf_actor_type') in ('HUMAN', 'HUMAN_USER')
            and isinstance(aud, list) and 'ouf-api-gateway' in aud
            and 'authorization.policy.admin' in str(claims.get('scope', '')).split()
            and claims.get('exp', 0) > time.time()):
        raise ValueError('HUMAN_TOKEN_CONTRACT_MISMATCH')
    return token


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
    if not SUBJECT_RE.fullmatch(args.subject_id):
        raise ValueError('INVALID_SUBJECT_ID')
    token = device_login(args.subject_id)
    current = read_json(BASE + '/policies/active', token)
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
