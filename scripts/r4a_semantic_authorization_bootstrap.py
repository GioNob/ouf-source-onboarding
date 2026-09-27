#!/usr/bin/env python3
"""Register and publish the exact first Semantic HUMAN capabilities and grants.

One HUMAN device login per invocation. Plan is read-only. Apply writes an
additive policy candidate, checks the owner preview, and records a private
state file before publishing. Existing capabilities and grants are preserved.
"""
import argparse
import base64
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time

import r4a_authorization_lifecycle as policy
import r4a_register_capabilities as registration
from r4a_admin_human_scope_bindings import ADMIN_SUBJECT

CAPABILITIES = Path(__file__).resolve().parents[1] / 'catalogue/r4a-semantic-human-capabilities.json'
GRANTS = Path(__file__).resolve().parents[1] / 'catalogue/r4a-semantic-human-grants.json'
CATALOGUE_URL = policy.DEFAULT_BASE + '/capabilities'


def normalized_grant(row):
    # The owner omits nullable fields when it serializes a published Grant.
    return {key: value for key, value in row.items() if value is not None}


def actor_token():
    token = policy.device_login()
    try:
        part = token.split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))
    except (IndexError, UnicodeDecodeError, ValueError) as exc:
        raise ValueError('HUMAN_TOKEN_INVALID') from exc
    aud = claims.get('aud', [])
    if isinstance(aud, str):
        aud = [aud]
    if not (claims.get('iss') == policy.ISSUER
            and claims.get('azp') == 'ouf-human-admin'
            and claims.get('sub') == ADMIN_SUBJECT
            and claims.get('preferred_username') == 'ouf-admin'
            and claims.get('ouf_actor_type') in ('HUMAN', 'HUMAN_USER')
            and 'ouf-api-gateway' in aud
            and 'authorization.policy.admin' in str(claims.get('scope', '')).split()
            and isinstance(claims.get('exp'), int) and claims['exp'] > time.time() + 60):
        raise ValueError('HUMAN_TOKEN_CONTRACT_MISMATCH')
    return token


def catalogue(token, wanted, apply):
    existing = {}
    offset = 0
    while True:
        _, raw = registration.request(CATALOGUE_URL + f'?limit=200&offset={offset}', token)
        rows = json.loads(raw)
        if not isinstance(rows, list) or len(rows) > 200:
            raise ValueError('CATALOGUE_PAGE_INVALID')
        for row in rows:
            name = row.get('capability_id')
            if not isinstance(name, str) or name in existing:
                raise ValueError('CATALOGUE_DUPLICATE')
            existing[name] = row
        if len(rows) < 200:
            break
        offset += 200
        if offset > 100000:
            raise ValueError('CATALOGUE_PAGINATION_LIMIT')
    missing = []
    for entry in wanted:
        name = entry['descriptor']['capabilityId']
        found = existing.get(name)
        if found is None:
            missing.append(entry)
            continue
        descriptor = found['descriptor']
        if isinstance(descriptor, dict) and descriptor.get('type') == 'jsonb':
            descriptor = descriptor['value']
        if isinstance(descriptor, str):
            descriptor = json.loads(descriptor)
        if found.get('owner_ref') != 'semantic' or policy.normalize_descriptor(descriptor) != policy.normalize_descriptor(entry['descriptor']):
            raise ValueError('CATALOGUE_DESCRIPTOR_CONFLICT:' + name)
    if apply:
        for entry in missing:
            code, _ = registration.request(CATALOGUE_URL, token, 'POST', entry)
            if code != 201:
                raise RuntimeError('CATALOGUE_WRITE_FAILED')
    return [entry['descriptor']['capabilityId'] for entry in missing]


def desired_grants(active, entries):
    subject = ADMIN_SUBJECT
    admin = [row for row in active['grants'] if row.get('subjectId') == subject
             and row.get('capabilityId') == 'authorization.policy.admin'
             and row.get('servicePrincipalId') is None and row.get('tenantId') == 'ouf-lab']
    if len(admin) != 1:
        raise ValueError('ADMIN_GRANT_NOT_UNIQUE')
    template = admin[0]
    if (template.get('constraints') is not None or template.get('organizationId') is not None
            or datetime.fromisoformat(template['validFrom'].replace('Z', '+00:00')) > datetime.now(timezone.utc)
            or datetime.fromisoformat(template['validUntil'].replace('Z', '+00:00')) <= datetime.now(timezone.utc)):
        raise ValueError('ADMIN_GRANT_NOT_UNCONSTRAINED_AND_ACTIVE')
    wanted = []
    for item in entries:
        exact = [grant for grant in active['grants'] if grant.get('grantId') == item['grantId']]
        candidate = {**template, 'grantId': item['grantId'], 'capabilityId': item['capabilityId'],
                     'constraints': None, 'organizationId': None}
        if exact:
            if len(exact) != 1 or normalized_grant(exact[0]) != normalized_grant(candidate):
                raise ValueError('EXISTING_GRANT_CONFLICT:' + item['grantId'])
        else:
            equivalents = [grant for grant in active['grants'] if grant.get('capabilityId') == item['capabilityId']
                           and grant.get('subjectId') == subject and grant.get('tenantId') == 'ouf-lab']
            if equivalents:
                raise ValueError('EQUIVALENT_GRANT_REQUIRES_REVIEW')
            wanted.append(candidate)
    return wanted


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=('plan', 'apply'))
    p.add_argument('--state-file', type=Path)
    args = p.parse_args()
    entries = json.loads(CAPABILITIES.read_text())
    grants = json.loads(GRANTS.read_text())
    if (len(entries) != 7 or len(grants) != 7
            or {x['descriptor']['capabilityId'] for x in entries} != {x['capabilityId'] for x in grants}
            or any(x.get('ownerRef') != 'semantic' or x['descriptor']['allowedActors'] != ['HUMAN']
                   for x in entries)):
        raise ValueError('SEMANTIC_MANIFEST_CHANGED')
    token = actor_token()
    current = policy.active(policy.DEFAULT_BASE, token)
    candidate, missing = policy.build_candidate(current, [row['descriptor'] for row in entries])
    additions = desired_grants(current['policy'], grants)
    missing_registration = catalogue(token, entries, False)
    print('ACTIVE_POLICY_REF=' + current['policyRef'])
    print('CATALOGUE_MISSING=' + (','.join(missing_registration) or 'NONE'))
    print('CAPABILITIES_TO_ADD=' + (','.join(row['capabilityId'] for row in missing) or 'NONE'))
    print('GRANTS_TO_ADD=' + (','.join(row['grantId'] for row in additions) or 'NONE'))
    print('EXISTING_POLICY_ENTRIES_PRESERVED=true')
    if args.mode == 'plan':
        print('NO_WRITES=true')
        return
    if not missing and not additions:
        print('SEMANTIC_AUTHORIZATION_ALREADY_ACTIVE=true NO_WRITES=true')
        return
    if args.state_file is None:
        raise ValueError('STATE_FILE_REQUIRED')
    registered = catalogue(token, entries, True)
    if set(registered) != set(missing_registration):
        raise ValueError('CATALOGUE_CHANGED_DURING_APPLY')
    candidate['grants'] = [*candidate['grants'], *additions]
    status, draft, headers = policy.request(policy.DEFAULT_BASE, token, '/policies', 'POST', candidate)
    etag = headers.get('ETag', '').strip('"')
    if status != 200 or not etag.isdigit():
        raise RuntimeError('DRAFT_CREATE_FAILED')
    state = {'draftId': draft['id'], 'revision': int(etag),
             'basePolicyRef': current['policyRef'],
             'targetPolicyRef': candidate['bundleId'] + ':' + str(candidate['version']),
             'baselineCapabilitiesHash': policy.digest(current['policy']['capabilities']),
             'baselineGrantsHash': policy.digest(current['policy']['grants']),
             'addedCapabilityIds': sorted(row['capabilityId'] for row in missing),
             'addedGrantIds': sorted(row['grantId'] for row in additions)}
    _, preview, _ = policy.request(policy.DEFAULT_BASE, token,
                                    '/policies/' + state['draftId'] + ':preview', 'POST',
                                    etag=state['revision'])
    if (sorted(row['capabilityId'] for row in preview['addedCapabilities']) != state['addedCapabilityIds']
            or preview['removedCapabilities']
            or sorted(row['grantId'] for row in preview['grantChanges']) != state['addedGrantIds']
            or any(row.get('before') is not None or row.get('after', {}).get('grantId') != row['grantId']
                   for row in preview['grantChanges'])):
        raise ValueError('POLICY_PREVIEW_UNEXPECTED_CHANGE')
    policy.write_state(args.state_file, state)
    _, result, _ = policy.request(policy.DEFAULT_BASE, token,
                                   '/policies/' + state['draftId'] + ':publish', 'POST',
                                   etag=state['revision'])
    if result.get('state') != 'PUBLISHED':
        raise RuntimeError('POLICY_PUBLISH_FAILED')
    final = policy.active(policy.DEFAULT_BASE, token)
    expected_caps = {row['capabilityId']: policy.normalize_descriptor(row)
                     for row in candidate['capabilities']}
    actual_caps = {row['capabilityId']: policy.normalize_descriptor(row)
                   for row in final['policy']['capabilities']}
    expected_grants = {row['grantId']: normalized_grant(row) for row in candidate['grants']}
    actual_grants = {row['grantId']: normalized_grant(row) for row in final['policy']['grants']}
    if (final['policyRef'] != state['targetPolicyRef']
            or len(expected_caps) != len(candidate['capabilities'])
            or len(actual_caps) != len(final['policy']['capabilities'])
            or len(expected_grants) != len(candidate['grants'])
            or len(actual_grants) != len(final['policy']['grants'])
            or actual_caps != expected_caps or actual_grants != expected_grants):
        raise ValueError('ACTIVE_POLICY_VERIFY_FAILED')
    print('SEMANTIC_AUTHORIZATION_PUBLISHED=true POLICY_REF=' + final['policyRef'])


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, json.JSONDecodeError) as exc:
        code = str(exc) if isinstance(exc, (ValueError, RuntimeError)) else type(exc).__name__
        print('SEMANTIC_AUTHORIZATION_BLOCKED=' + code, file=sys.stderr)
        raise SystemExit(1)
