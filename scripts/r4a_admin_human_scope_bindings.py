#!/usr/bin/env python3
"""Audit/reconcile all ouf-admin HUMAN OAuth scope bindings for lab policy :27.

Only adds missing OPTIONAL scopes to ouf-human-admin. Does not modify scope
definitions, existing bindings, tokens, passwords or SERVICE clients.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys

REALM = 'ouf'
CLIENT = 'ouf-human-admin'
SCOPES = (
    'authorization.permissions.propose', 'authorization.permissions.read',
    'authorization.policy.admin', 'authorization.proposal.read',
    'installation.configuration.activate', 'installation.configuration.export',
    'installation.configuration.read', 'installation.configuration.write',
    'operations.status.read', 'ouf.managed-source.file.profile',
    'ouf.managed-source.file.upload', 'ouf.managed-source.onboarding.create',
    'ouf.managed-source.preview', 'ouf.onboarding.configuration.write',
    'urban.object.search',
)
KC = '/opt/keycloak/bin/kcadm.sh'


def run(container: str, *args: str) -> str:
    result = subprocess.run(['docker', 'exec', '-i', container, KC, *args],
                            capture_output=True, text=True, check=False)
    if result.returncode:
        if 'Session has expired' in (result.stderr + result.stdout):
            raise ValueError('KCADM_SESSION_EXPIRED')
        raise ValueError('KCADM_COMMAND_FAILED')
    return result.stdout


def get(container: str, *args: str) -> list[dict]:
    try:
        result = json.loads(run(container, 'get', *args, '-r', REALM))
    except json.JSONDecodeError as exc:
        raise ValueError('KCADM_INVALID_JSON') from exc
    if not isinstance(result, list):
        raise ValueError('KCADM_UNEXPECTED_RESPONSE')
    return result


def audit(container: str) -> tuple[str, dict[str, str], list[str], list[str], list[str]]:
    clients = get(container, 'clients', '-q', 'clientId=' + CLIENT)
    exact = [row for row in clients if row.get('clientId') == CLIENT]
    if len(exact) != 1 or not isinstance(exact[0].get('id'), str):
        raise ValueError('ADMIN_CLIENT_NOT_UNIQUE')
    client_id = exact[0]['id']
    catalogue = get(container, 'client-scopes')
    ids = {}
    for name in SCOPES:
        matches = [row for row in catalogue if row.get('name') == name]
        if len(matches) > 1:
            raise ValueError('SCOPE_NOT_UNIQUE')
        if matches and isinstance(matches[0].get('id'), str):
            ids[name] = matches[0]['id']
    default = {row['id'] for row in get(container, 'clients/' + client_id + '/default-client-scopes')}
    optional = {row['id'] for row in get(container, 'clients/' + client_id + '/optional-client-scopes')}
    missing_catalogue = sorted(set(SCOPES) - set(ids))
    missing_binding = sorted(name for name, identifier in ids.items()
                             if identifier not in default and identifier not in optional)
    bound_default = sorted(name for name, identifier in ids.items() if identifier in default)
    bound_optional = sorted(name for name, identifier in ids.items() if identifier in optional)
    if any(identifier in default and identifier in optional for identifier in ids.values()):
        raise ValueError('CLIENT_SCOPE_BINDING_CONFLICT')
    return client_id, ids, missing_catalogue, missing_binding, bound_default + bound_optional


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('plan', 'apply', 'verify'))
    parser.add_argument('--container', default='ouf-keycloak')
    args = parser.parse_args()
    client, ids, missing_catalogue, missing_binding, bound = audit(args.container)
    print('MODE=' + args.mode)
    print('CLIENT=' + CLIENT)
    print('EXPECTED_SCOPES=' + str(len(SCOPES)))
    print('BOUND_SCOPES=' + str(len(bound)))
    print('MISSING_CATALOGUE=' + (','.join(missing_catalogue) or 'NONE'))
    print('MISSING_BINDINGS=' + (','.join(missing_binding) or 'NONE'))
    if args.mode == 'plan':
        print('NO_WRITES=true')
        return
    if missing_catalogue:
        raise ValueError('CATALOGUE_INCOMPLETE')
    if args.mode == 'apply':
        for name in missing_binding:
            run(args.container, 'update', 'clients/' + client + '/optional-client-scopes/' + ids[name],
                '-r', REALM)
    _, _, missing_catalogue, missing_binding, bound = audit(args.container)
    if missing_catalogue or missing_binding or len(bound) != len(SCOPES):
        raise ValueError('SCOPE_BINDING_VERIFY_FAILED')
    print('VERIFY=PASS')
    print('SECRETS_PRINTED=false')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, TypeError, KeyError) as exc:
        code = str(exc) if isinstance(exc, ValueError) else type(exc).__name__
        print('ADMIN_SCOPE_BINDINGS_BLOCKED=' + code, file=sys.stderr)
        raise SystemExit(1)
