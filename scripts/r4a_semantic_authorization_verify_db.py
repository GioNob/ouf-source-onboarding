#!/usr/bin/env python3
"""Read-only, structural verification of the published Semantic policy delta.

The owner rewrites publishedAt and may normalize omitted null grant fields;
this checker compares version 27 and ACTIVE by exact entry identity instead of
hashing the whole JSON array constructed by the caller.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit

import r4a_authorization_lifecycle as lifecycle
from r4a_admin_human_scope_bindings import ADMIN_SUBJECT

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_CAPS = json.loads((ROOT / 'catalogue/r4a-semantic-human-capabilities.json').read_text())
EXPECTED_GRANTS = json.loads((ROOT / 'catalogue/r4a-semantic-human-grants.json').read_text())


def inspect(container):
    value = json.loads(subprocess.check_output(['docker', 'inspect', container], text=True))
    if len(value) != 1 or not value[0]['State']['Running']:
        raise ValueError('CONTAINER_NOT_RUNNING:' + container)
    return dict(item.split('=', 1) for item in value[0]['Config']['Env'] if '=' in item)


def indexed(rows, key):
    if not isinstance(rows, list) or len(rows) != len({row.get(key) for row in rows}):
        raise ValueError('DUPLICATE_OR_INVALID:' + key)
    return {row[key]: row for row in rows}


def verify(old, active, state):
    if (active['bundleId'] + ':' + str(active['version']) != state['targetPolicyRef']
            or old['bundleId'] + ':' + str(old['version']) != state['basePolicyRef']
            or lifecycle.digest(old['capabilities']) != state['baselineCapabilitiesHash']
            or lifecycle.digest(old['grants']) != state['baselineGrantsHash']):
        raise ValueError('POLICY_IDENTITY_OR_BASELINE_MISMATCH')
    before_caps = indexed(old['capabilities'], 'capabilityId')
    after_caps = indexed(active['capabilities'], 'capabilityId')
    before_grants = indexed(old['grants'], 'grantId')
    after_grants = indexed(active['grants'], 'grantId')
    expected_caps = {row['descriptor']['capabilityId']: row['descriptor'] for row in EXPECTED_CAPS}
    expected_grants = {row['grantId']: row['capabilityId'] for row in EXPECTED_GRANTS}
    if (set(after_caps) - set(before_caps) != set(expected_caps)
            or set(after_grants) - set(before_grants) != set(expected_grants)
            or any(after_caps.get(key) != value for key, value in before_caps.items())
            or any(after_grants.get(key) != value for key, value in before_grants.items())):
        raise ValueError('UNEXPECTED_EXISTING_POLICY_CHANGE')
    for key, descriptor in expected_caps.items():
        if lifecycle.normalize_descriptor(after_caps[key]) != lifecycle.normalize_descriptor(descriptor):
            raise ValueError('SEMANTIC_DESCRIPTOR_MISMATCH:' + key)
    template = [row for row in old['grants'] if row.get('capabilityId') == 'authorization.policy.admin'
                and row.get('subjectId') == ADMIN_SUBJECT and row.get('tenantId') == 'ouf-lab']
    if len(template) != 1:
        raise ValueError('ADMIN_BASELINE_NOT_UNIQUE')
    for grant_id, capability_id in expected_grants.items():
        actual = after_grants[grant_id]
        desired = {**template[0], 'grantId': grant_id, 'capabilityId': capability_id,
                   'constraints': None, 'organizationId': None}
        for field in desired:
            if actual.get(field) != desired[field]:
                raise ValueError('SEMANTIC_GRANT_MISMATCH:' + grant_id)
        if set(actual) - set(desired):
            raise ValueError('SEMANTIC_GRANT_EXTRA_FIELDS:' + grant_id)
    return len(before_caps), len(after_caps), len(before_grants), len(after_grants)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--state-file', type=Path, required=True)
    args = p.parse_args()
    state = lifecycle.read_state(args.state_file)
    pg = inspect('ouf-postgres')
    owner = inspect('ouf-onboarding')
    jdbc = owner.get('OUF_ONB_DB_URL', '')
    parsed = urlsplit(jdbc.removeprefix('jdbc:'))
    database = parsed.path.lstrip('/')
    user = pg.get('POSTGRES_USER')
    if (not jdbc.startswith('jdbc:postgresql://') or parsed.hostname not in
            ('ouf-postgres', '127.0.0.1', 'localhost') or not database
            or '/' in database or not user):
        raise ValueError('DATABASE_BINDING_INVALID')
    base_version = int(state['basePolicyRef'].rsplit(':', 1)[1])
    if base_version < 1 or base_version + 1 != int(state['targetPolicyRef'].rsplit(':', 1)[1]):
        raise ValueError('STATE_POLICY_VERSION_INVALID')
    sql = """select a.bundle_id || ':' || a.version, old.bundle_payload::text,
       current.bundle_payload::text
       from ouf_authorization.active_policy_bundle a
       join ouf_authorization.policy_bundle old
         on old.bundle_id=a.bundle_id and old.version={base_version}
       join ouf_authorization.policy_bundle current
         on current.bundle_id=a.bundle_id and current.version=a.version
       where a.singleton_key=true""".format(base_version=base_version)
    raw = subprocess.check_output(
        ['docker', 'exec', '--user', 'postgres', 'ouf-postgres',
         'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-A', '-t', '-F', '\t',
         '-U', user, '-d', database, '-c', sql], text=True)
    lines = raw.strip().splitlines()
    if len(lines) != 1:
        raise ValueError('POLICY_QUERY_NOT_UNIQUE')
    ref, old_json, active_json = lines[0].split('\t')
    old, active = json.loads(old_json), json.loads(active_json)
    if ref != state['targetPolicyRef']:
        raise ValueError('ACTIVE_REF_UNEXPECTED:' + ref)
    counts = verify(old, active, state)
    print('ACTIVE_POLICY_REF=' + ref)
    print('CAPABILITIES_BEFORE=' + str(counts[0]) + ' AFTER=' + str(counts[1]))
    print('GRANTS_BEFORE=' + str(counts[2]) + ' AFTER=' + str(counts[3]))
    print('SEMANTIC_AUTHORIZATION_VERIFY=PASS')
    print('EXISTING_ENTRIES_PRESERVED=true NO_WRITES=true SECRET_VALUES_NOT_PRINTED=true')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, IndexError, TypeError,
            subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print('SEMANTIC_AUTHORIZATION_VERIFY_BLOCKED=' +
              (str(exc) if isinstance(exc, ValueError) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(1)
